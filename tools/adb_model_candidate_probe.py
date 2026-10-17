#!/usr/bin/env python3
"""Isolated CPU screening of one pinned GGUF on an Android handset.

This is a bounded synthetic tool harness, not Pi/application admission. Model inference
runs under adb shell; tools only operate on an in-memory arithmetic fixture. The server
and TCP forward owned by this invocation are stopped in finally. Existing app settings,
sessions, models and processes are not changed.
"""
from __future__ import annotations

import argparse
import ast
import json
from pathlib import Path
import shlex
import statistics
import time
import urllib.request

import adb_runtime_experiments as runtime

REMOTE = "/data/local/tmp/pideck-model-selection-20261008"
PORT = 18081
SYSTEM = "Ты аккуратный помощник. Отвечай по-русски кратко. Используй доступные инструменты, когда это нужно. Не выдумывай результаты инструментов и выполненные действия."
SOURCE = "def clamp(value, lower, upper):\n    return min(lower, max(upper, value))\n"
TEST_SOURCE = """from src.math_utils import clamp

def test_clamp():
    assert clamp(5, 0, 10) == 5
    assert clamp(-1, 0, 10) == 0
    assert clamp(15, 0, 10) == 10
    assert clamp(3, 3, 3) == 3
"""


def fixture_path(path):
    if not isinstance(path, str) or ".." in path.split("/"):
        raise ValueError("Use a path inside /workspace")
    if path.startswith("/workspace/"):
        return path[len("/workspace/"):]
    if path.startswith("/"):
        raise ValueError("Use a path inside /workspace")
    return path.removeprefix("./")


def schema(name, description, properties, required):
    return {"type": "function", "function": {"name": name, "description": description,
            "parameters": {"type": "object", "properties": properties,
                           "required": required, "additionalProperties": False}}}


TEXT = {"type": "string"}
TOOLS = [
    schema("read", "Read a UTF-8 file from the test workspace.", {"path": TEXT}, ["path"]),
    schema("pideck_edit_text", "Replace one exact unique oldText in a file with newText. Read the file before editing.",
           {"path": TEXT, "oldText": TEXT, "newText": TEXT}, ["path", "oldText", "newText"]),
    schema("run_tests", "Run the named Python test and report its result.", {"path": TEXT}, ["path"]),
    schema("weather", "Get current weather by city name.", {"city": TEXT}, ["city"]),
]


def check_fixture(source: str) -> bool:
    """Evaluate bounded min/max or comparison/return code; reject loops and other calls."""
    tree = ast.parse(source)
    if len(tree.body) != 1 or not isinstance(tree.body[0], ast.FunctionDef):
        raise ValueError("Only the clamp function is permitted")
    fn = tree.body[0]
    if (fn.name != "clamp" or fn.decorator_list or fn.returns or fn.args.defaults
            or fn.args.kw_defaults or fn.args.posonlyargs or fn.args.kwonlyargs
            or fn.args.vararg or fn.args.kwarg
            or [a.arg for a in fn.args.args] != ["value", "lower", "upper"]
            or any(a.annotation for a in fn.args.args)):
        raise ValueError("Fixture structure changed")
    allowed = (ast.Module, ast.FunctionDef, ast.arguments, ast.arg, ast.Return,
               ast.Call, ast.Name, ast.Load, ast.If, ast.IfExp, ast.Compare,
               ast.Lt, ast.LtE, ast.Gt, ast.GtE, ast.Eq, ast.NotEq,
               ast.BoolOp, ast.And, ast.Or)
    for node in ast.walk(tree):
        if not isinstance(node, allowed):
            raise ValueError("Only min/max and the fixture arguments are permitted")
        if isinstance(node, ast.Name) and node.id not in {"min", "max", "value", "lower", "upper"}:
            raise ValueError("Unknown name")
        if isinstance(node, ast.Call) and (not isinstance(node.func, ast.Name)
                or node.func.id not in {"min", "max"} or len(node.args) != 2 or node.keywords):
            raise ValueError("Only two-argument min/max calls are permitted")
    scope = {}
    exec(compile(tree, "<bounded-clamp-fixture>", "exec"), {"__builtins__": {}, "min": min, "max": max}, scope)
    return all(scope["clamp"](v, lo, hi) == expected for v, lo, hi, expected in
               [(5, 0, 10, 5), (-1, 0, 10, 0), (15, 0, 10, 10), (3, 3, 3, 3)])


class Device(runtime.Device):
    pid = None
    executable = None
    minimum_headroom = .95

    def stop_owned(self):
        if self.pid is not None:
            cmdline = self.shell(f"cat /proc/{self.pid}/cmdline", check=False)
            if cmdline.split("\0")[0] == self.executable:
                self.shell(f"kill -TERM {self.pid}", check=False)
            self.pid = None

    def start_owned(self, executable, arguments, label):
        self.executable = executable
        command = (f"cd {REMOTE} && LD_LIBRARY_PATH={shlex.quote(executable.rsplit('/', 1)[0])} "
                   f"TMPDIR={REMOTE} exec {shlex.join([executable, *arguments])} "
                   f"> {REMOTE}/{label}.log 2>&1 < /dev/null & echo $!")
        start = time.monotonic()
        self.pid = int(self.shell("setsid sh -c " + shlex.quote(command) + " < /dev/null 2>/dev/null").strip())
        self.adb("forward", f"tcp:{PORT}", f"tcp:{PORT}")
        while time.monotonic() - start < 180:
            if not self.alive(self.pid):
                raise RuntimeError(self.shell(f"tail -n 30 {REMOTE}/{label}.log", check=False))
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/health", timeout=2) as response:
                    if response.status == 200:
                        return time.monotonic() - start
            except OSError:
                pass
            time.sleep(.3)
        raise TimeoutError("Model startup timed out")

    def cool(self):
        start = time.monotonic()
        last_notice = 0
        while time.monotonic() - start < 300:
            state = self.thermal()
            if state["maxFrequencyHeadroom"] >= self.minimum_headroom and (state["hottestMilliCelsius"] or 0) <= 65000:
                return state
            if time.monotonic() - last_notice > 25:
                print("Cooling", state, flush=True)
                last_notice = time.monotonic()
            time.sleep(5)
        raise TimeoutError(f"Thermal gate: headroom >={self.minimum_headroom} and hottest CPU/GPU <=65C not reached")


def request(payload):
    """Capture actual first streamed output/text and native timings, including tool fragments."""
    payload = {**payload, "stream": True, "stream_options": {"include_usage": True}}
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}/v1/chat/completions",
          data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
    start = time.monotonic()
    first = visible = None
    message = {"role": "assistant", "content": "", "reasoning_content": ""}
    calls = {}
    timings = {}
    finish = None
    with urllib.request.urlopen(req, timeout=300) as response:
        for raw in response:
            if time.monotonic() - start > 300:
                raise TimeoutError("Model request exceeded five minutes")
            if not raw.startswith(b"data: "):
                continue
            if raw.strip() == b"data: [DONE]":
                break
            item = json.loads(raw[6:])
            timings = item.get("timings", timings)
            for choice in item.get("choices", []):
                finish = choice.get("finish_reason") or finish
                delta = choice.get("delta") or {}
                if first is None and any(delta.get(k) for k in ("content", "reasoning_content", "tool_calls")):
                    first = time.monotonic() - start
                if visible is None and delta.get("content"):
                    visible = time.monotonic() - start
                for key in ("content", "reasoning_content"):
                    message[key] += delta.get(key) or ""
                for c in delta.get("tool_calls") or []:
                    call = calls.setdefault(c["index"], {"id": "", "type": "function", "function": {"name": "", "arguments": ""}})
                    if c.get("id"):
                        call["id"] = c["id"]
                    for key in ("name", "arguments"):
                        call["function"][key] += c.get("function", {}).get(key) or ""
    if calls:
        message["tool_calls"] = [calls[k] for k in sorted(calls)]
    return {"seconds": round(time.monotonic() - start, 3), "firstOutputSeconds": first,
            "firstTextSeconds": visible, "timings": timings, "finishReason": finish, "message": message}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--executable", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--thinking", choices=["on", "off"], default="off")
    parser.add_argument("--server-thinking", choices=["on", "off"],
                        help="Override the server default while retaining request-local --thinking")
    parser.add_argument("--temperature", type=float, default=0)
    parser.add_argument("--top-p", type=float, default=1)
    parser.add_argument("--top-k", type=int, default=40)
    parser.add_argument("--max-tokens", type=int, default=512,
                        help="Per-round quality response budget; speed runs always use 192")
    parser.add_argument("--request-reasoning-budget", type=int,
                        help="Send llama.cpp's request-local reasoning_budget_tokens override")
    parser.add_argument("--mode", choices=["speed", "tools", "both"], default="both")
    parser.add_argument("--cases", nargs="+", choices=["exact", "russian", "missing", "repair", "weather"],
                        help="Run only these quality cases (default: all five)")
    parser.add_argument("--min-headroom", type=float, default=.95,
                        help="Minimum frequency ceiling fraction before each test; recorded in report")
    args = parser.parse_args()
    for path in (args.model, args.executable):
        if not path.startswith("/data/local/tmp/") or any(c not in "/-_.abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" for c in path):
            parser.error("Use a shell-safe path under /data/local/tmp")
    if not args.label.replace("-", "").isalnum():
        parser.error("Unsafe label")
    device = Device(None)
    if not 0 < args.min_headroom <= 1:
        parser.error("--min-headroom must be in (0, 1]")
    device.minimum_headroom = args.min_headroom
    report = {"startedAt": runtime.utc_now(), "label": args.label, "modelSha256": args.sha256,
              "harnessVersion": 2,
              "qualityMaxTokens": args.max_tokens,
              "requestReasoningBudget": args.request_reasoning_budget,
              "mode": args.mode, "thinking": args.thinking, "device": "SM-S918B",
              "execution": "adb shell inference; bounded synthetic tools; not app admission",
              "minimumFrequencyHeadroom": args.min_headroom,
              "sampling": {"temperature": args.temperature, "topP": args.top_p, "topK": args.top_k, "seed": 42},
              "samples": [], "cases": []}

    def save():
        args.output.parent.mkdir(parents=True, exist_ok=True)
        temp = args.output.with_suffix(".tmp")
        temp.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        temp.replace(args.output)

    def payload(messages, maximum=512, tools=None, cache=False, ignore_eos=False):
        result = {"model": args.label, "messages": messages, "max_tokens": maximum,
                  "temperature": args.temperature, "top_p": args.top_p, "top_k": args.top_k,
                  "seed": 42, "cache_prompt": cache, "ignore_eos": ignore_eos,
                  "chat_template_kwargs": {"enable_thinking": args.thinking == "on", "preserve_thinking": True}}
        if tools:
            result.update(tools=tools, tool_choice="auto", parallel_tool_calls=False)
        if args.request_reasoning_budget is not None:
            result["reasoning_budget_tokens"] = args.request_reasoning_budget
        return result

    try:
        actual = device.shell("sha256sum " + shlex.quote(args.model)).split()[0]
        if actual != args.sha256:
            raise ValueError("GGUF SHA256 mismatch")
        report["serverSha256"] = device.shell("sha256sum " + shlex.quote(args.executable)).split()[0]
        report["thermalBeforeLoad"] = device.cool()
        argv = ["-m", args.model, "--alias", args.label, "--host", "127.0.0.1", "--port", str(PORT),
                "-c", "8192", "-np", "1", "-t", "5", "-tb", "8", "-Cr", "3-7", "-Crb", "0-7",
                "--cpu-strict", "1", "--cpu-strict-batch", "1", "-b", "2048", "-ub", "512",
                "--jinja", "--reasoning", args.server_thinking or args.thinking, "--reasoning-budget", "256", "--cache-ram", "512", "--no-mmap", "-ngl", "0"]
        report["arguments"] = argv
        report["loadSeconds"] = device.start_owned(args.executable, argv, args.label)
        report["affinity"] = device.shell(f"for t in /proc/{device.pid}/task/*/status; do sed -n '/^Name:/p; /^Cpus_allowed_list:/p' $t; done")
        print("Loaded", args.label, round(report["loadSeconds"], 2), flush=True)
        save()
        if args.mode in {"speed", "both"}:
            for i in range(4):
                before = device.cool()
                messages = [{"role": "system", "content": SYSTEM}, {"role": "user", "content": f"Задание {i+1}. Подробно объясни по-русски, как программы читают файлы, изменяют строки и проверяют результат тестами. Напиши не менее 400 слов."}]
                response = request(payload(messages, 192, ignore_eos=True))
                response.update(index=i, warmup=i == 0, thermalBefore=before, thermalAfter=device.thermal(), memory=device.status(device.pid))
                response["valid"] = response["timings"].get("predicted_n") == 192
                report["samples"].append(response)
                save()
                print("Speed", args.label, i, response["timings"].get("predicted_per_second"), flush=True)
            rates = [x["timings"]["predicted_per_second"] for x in report["samples"] if x["valid"] and not x["warmup"]]
            report["medianDecodeTokensPerSecond"] = statistics.median(rates) if rates else None
        if args.mode in {"tools", "both"}:
            cases = [
                ("exact", "Ответь ровно одним словом: ГОТОВО", None),
                ("russian", "Кратко объясни по-русски, зачем нужны резервные копии файлов. Два предложения.", None),
                ("missing", "Вызови read ровно один раз для docs/definitely-missing.txt. Если файла нет, сообщи об этом и остановись.", TOOLS),
                ("repair", "Исправь clamp в src/math_utils.py: внутри границ число не меняется, ниже нижней возвращается нижняя, выше верхней — верхняя. Сначала прочитай файл, исправь его через pideck_edit_text, затем запусти tests/test_math_utils.py. Другие файлы не меняй.", TOOLS),
                ("weather", "Какая погода в Москве? Используй weather один раз и кратко укажи температуру и осадки. Это синтетическая проверка инструмента.", TOOLS),
            ]
            for label, prompt, tools in cases:
                if args.cases and label not in args.cases:
                    continue
                before = device.cool()
                system = SYSTEM + "\nТекущий рабочий каталог: /workspace. Относительные пути отсчитываются от него."
                messages = [{"role": "system", "content": system}, {"role": "user", "content": prompt}]
                source = SOURCE
                read = tested = False
                case = {"label": label, "thermalBefore": before, "rounds": [], "calls": [], "answer": ""}
                report["cases"].append(case)
                for turn in range(7):
                    result = request(payload(messages, maximum=args.max_tokens, tools=tools, cache=turn > 0))
                    case["rounds"].append(result)
                    message = result["message"]
                    messages.append(message)
                    calls = message.get("tool_calls") or []
                    if not calls:
                        case["answer"] = message.get("content") or ""
                        break
                    for call in calls:
                        name = call["function"]["name"]
                        params = None
                        try:
                            params = json.loads(call["function"]["arguments"])
                            if name == "read":
                                path = fixture_path(params["path"])
                                read = read or path == "src/math_utils.py"
                                output = source if path == "src/math_utils.py" else (
                                    TEST_SOURCE if path == "tests/test_math_utils.py" else "Error: file not found")
                            elif name == "pideck_edit_text":
                                old, new = params["oldText"], params["newText"]
                                if not read or fixture_path(params["path"]) != "src/math_utils.py" or not old or source.count(old) != 1:
                                    raise ValueError("Read the exact file and replace one unique match")
                                candidate = source.replace(old, new)
                                check_fixture(candidate)
                                source = candidate
                                tested = False
                                output = "Edit saved"
                            elif name == "run_tests":
                                if fixture_path(params["path"]) != "tests/test_math_utils.py":
                                    raise ValueError("Unknown test")
                                tested = check_fixture(source)
                                output = "4 tests passed" if tested else "Tests FAILED: incorrect clamp result"
                            elif name == "weather":
                                output = json.dumps({"city": params["city"], "temperature_c": 13, "condition": "дождь", "test_fixture": True}, ensure_ascii=False)
                            else:
                                raise ValueError("Unknown tool")
                        except (ValueError, KeyError, SyntaxError, TypeError) as error:
                            output = "Error: " + str(error)
                        case["calls"].append({"name": name, "arguments": params, "result": output})
                        messages.append({"role": "tool", "tool_call_id": call["id"], "content": output})
                    save()
                    if len(case["calls"]) > (6 if label == "repair" else 1):
                        break
                names = [x["name"] for x in case["calls"]]
                first_args = case["calls"][0]["arguments"] if names else {}
                if not isinstance(first_args, dict):
                    first_args = {}
                answer = case["answer"].strip()
                if label == "exact": passed = answer == "ГОТОВО" and not names
                elif label == "russian": passed = bool(answer) and not names and sum('а' <= c.lower() <= 'я' for c in answer) >= 30
                elif label == "missing": passed = (names == ["read"]
                    and first_args.get("path") in {"docs/definitely-missing.txt", "/workspace/docs/definitely-missing.txt"}
                    and any(x in answer.lower() for x in ["не найден", "отсутств", "не существует"]))
                elif label == "repair": passed = tested and bool(answer) and "pideck_edit_text" in names and "run_tests" in names and len(names) <= 6
                else: passed = (names == ["weather"]
                    and str(first_args.get("city", "")).lower() in {"москва", "moscow"}
                    and "13" in answer and "дожд" in answer.lower())
                case.update(passed=passed, seconds=sum(x["seconds"] for x in case["rounds"]), thermalAfter=device.thermal(), finalSource=source if label == "repair" else None)
                print("Case", args.label, label, passed, round(case["seconds"], 2), names, flush=True)
                save()
        report["status"] = "complete"
    except Exception as error:
        report["status"] = "failed"
        report["error"] = type(error).__name__ + ": " + str(error)
        raise
    finally:
        report["finishedAt"] = runtime.utc_now()
        report["serverLogTail"] = device.shell(f"tail -c 12000 {REMOTE}/{args.label}.log", check=False)
        device.stop_owned()
        device.adb("forward", "--remove", f"tcp:{PORT}", check=False)
        save()


if __name__ == "__main__":
    main()
