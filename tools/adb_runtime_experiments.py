#!/usr/bin/env python3
"""Paired on-device experiments for PI//DECK runtime speed changes.

The stock b10092 server from ``app/src/main/jniLibs`` runs under the adb shell UID in
``/data/local/tmp/pideck-opt`` with the same argument builder as the APK, so the installed
application is never touched. Each experiment writes one JSON report with commands, timings,
thermal state and process memory snapshots.

Experiments:
  prefix-reuse   a new session reuses the server checkpoint at the end of a byte-identical
                 system+tools prefix (cache_prompt=true on its first request)
  memory         default mmap loading versus --no-mmap: load time, RSS split and speed
  batch-threads  prompt ingestion with 8 threads on CPUs 0-7 versus 5 on the fast cores
  prism-kernel   baseline versus candidate Prism executable (ABBA): identical answers, prefill
  kv-quant       f16 versus q8_0 KV cache on top of the catalog arguments
  slot-snapshot  cold start: a saved system+tools slot restored into a fresh server
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

REPOSITORY = Path(__file__).resolve().parents[1]
RUNTIME = REPOSITORY / "app" / "src" / "main" / "assets" / "runtime"
JNI = REPOSITORY / "app" / "src" / "main" / "jniLibs" / "arm64-v8a"
CATALOG = REPOSITORY / "app" / "src" / "main" / "assets" / "models-v2.json"
DEVICE_DIR = "/data/local/tmp/pideck-opt"
PORT = 18080
WORKSPACE = "/data/data/com.termux/files/home/.pideck/workspace"
STOCK_LIBRARIES = (
    "libpideck_llama_server.so",
    "libllama-server-impl.so",
    "libllama-common.so",
    "libllama.so",
    "libmtmd.so",
    "libggml.so",
    "libggml-base.so",
    "libggml-cpu-android_armv8.0_1.so",
    "libggml-cpu-android_armv8.2_1.so",
    "libggml-cpu-android_armv8.2_2.so",
    "libggml-cpu-android_armv8.6_1.so",
    "libggml-cpu-android_armv9.0_1.so",
    "libggml-cpu-android_armv9.2_1.so",
    "libggml-cpu-android_armv9.2_2.so",
)
STATUS_FIELDS = ("VmRSS", "RssAnon", "RssFile", "VmSwap", "VmHWM")


class ExperimentError(RuntimeError):
    pass


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


# ---------------------------------------------------------------------------------------------
# Pure helpers (unit-tested on the host)
# ---------------------------------------------------------------------------------------------

def decimal(value: float) -> str:
    text = f"{float(value):.6f}".rstrip("0").rstrip(".")
    return text if text else "0"


def server_arguments(
    model: dict[str, Any],
    model_path: str,
    *,
    decode_threads: int = 5,
    decode_cpus: str = "3-7",
    batch_threads: int = 8,
    batch_cpus: str = "0-7",
    extra: list[str] | None = None,
) -> list[str]:
    """Mirrors ModelSpec.llamaServerArguments for one catalog entry (no API key, own port)."""
    runtime = model["runtime"]
    sampling = model["sampling"]
    args = [
        "-m", model_path,
        "--alias", model["id"],
        "--host", "127.0.0.1",
        "--port", str(PORT),
        "-c", str(runtime["recommendedContext"]),
        "-np", str(runtime.get("parallelSlots", 1)),
        "-t", str(max(2, min(8, decode_threads))),
        "-tb", str(batch_threads),
        "-Cr", decode_cpus,
        "--cpu-strict", "1",
        "-Crb", batch_cpus,
        "--cpu-strict-batch", "1",
    ]
    if runtime.get("requiresJinja", False):
        args.append("--jinja")
    reasoning = runtime.get("reasoningMode", "model-default")
    if reasoning != "model-default":
        args += ["--reasoning", reasoning]
    args += [
        "--temp", decimal(sampling["temperature"]),
        "--top-p", decimal(sampling["topP"]),
        "--top-k", str(sampling["topK"]),
        "--min-p", decimal(sampling["minP"]),
        "--presence-penalty", decimal(sampling["presencePenalty"]),
    ]
    args += list(runtime.get("serverArgs", []))
    args += list(extra or [])
    return args


def mobile_agent_guidance() -> str:
    source = (RUNTIME / "pideck-system-prompt.ts").read_text(encoding="utf-8")
    match = re.search(r"const MOBILE_AGENT_GUIDANCE = `(.*?)`;", source, re.S)
    if match is None:
        raise ExperimentError("MOBILE_AGENT_GUIDANCE was not found")
    return match.group(1)


def agent_system_prompt() -> str:
    """The default agent-mode system prompt as Pi assembles it around PI//DECK's parts."""
    base = (RUNTIME / "pideck-agent-base-prompt.md").read_text(encoding="utf-8").strip()
    agents = (RUNTIME / "AGENTS.default.md").read_text(encoding="utf-8")
    return (
        f"{base}\n\n{mobile_agent_guidance()}"
        "\n\n<project_context>\n\nProject-specific instructions and guidelines:\n\n"
        f'<project_instructions path="{WORKSPACE}/AGENTS.md">\n{agents}\n</project_instructions>\n\n'
        "</project_context>\n"
        f"\nCurrent working directory: {WORKSPACE}"
    )


def _function(name: str, description: str, properties: dict[str, Any], required: list[str]) -> dict:
    return {
        "type": "function",
        "function": {
            "name": name,
            "description": description,
            "parameters": {
                "type": "object",
                "properties": properties,
                "required": required,
                "additionalProperties": False,
            },
        },
    }


def agent_tools() -> list[dict[str, Any]]:
    """The AUTONOMOUS core schema in Pi's order, with descriptions of realistic length."""
    text = {"type": "string"}
    integer = {"type": "integer"}
    return [
        _function(
            "read",
            "Read the contents of a file. Supports text files and images (jpg, png, gif, webp). "
            "Images are sent as attachments. For text files, output is truncated to 2000 lines or "
            "50KB (whichever is hit first). Use offset/limit for large files. When you need the "
            "full file, continue with offset until complete. Lines are shown as line:hash| text.",
            {"path": {**text, "description": "Path to the file to read (relative or absolute)"},
             "offset": {**integer, "description": "Line number to start reading from (1-indexed)"},
             "limit": {**integer, "description": "Maximum number of lines to read"}},
            ["path"],
        ),
        _function(
            "code_nav",
            "Locate files, symbols and definitions in the workspace without shell discovery. "
            "Returns bounded path:line matches with a short declaration outline.",
            {"query": {**text, "description": "Symbol, identifier or path fragment to locate"},
             "path": {**text, "description": "Optional directory or file to search within"}},
            ["query"],
        ),
        _function(
            "bash",
            "Execute a bash command in the current working directory. Returns stdout and stderr. "
            "Output is truncated to last 2000 lines or 50KB (whichever is hit first). If "
            "truncated, full output is saved to a temp file. Optionally provide a timeout in "
            "seconds.",
            {"command": {**text, "description": "Bash command to execute"},
             "timeout": {"type": "number", "description": "Timeout in seconds (optional)"}},
            ["command"],
        ),
        _function(
            "write",
            "Write content to a file. Creates the file if it doesn't exist, overwrites if it does. "
            "Automatically creates parent directories.",
            {"path": {**text, "description": "Path to the file to write (relative or absolute)"},
             "content": {**text, "description": "Content to write to the file"}},
            ["path", "content"],
        ),
        _function(
            "pideck_edit_text",
            "Replace one unique literal oldText with newText in a file whose current contents "
            "were read in this task. Copy file text without line:hash prefixes and include "
            "enough context for exactly one match. The edit is committed atomically.",
            {"path": {**text, "description": "File to edit, relative to the workspace unless absolute"},
             "oldText": {**text, "description": "Exact unique text currently in the file"},
             "newText": {**text, "description": "Replacement text"}},
            ["path", "oldText", "newText"],
        ),
        _function(
            "run_tests",
            "Run the exact bounded test for the changed code and return an authoritative "
            "verdict with the failing assertion when it fails.",
            {"path": {**text, "description": "Test file or directory"},
             "expr": {**text, "description": "Optional test name or -k expression"}},
            ["path"],
        ),
        _function(
            "pideck_load_tools",
            "Enable one optional capability only when the active tools cannot finish the task.",
            {"capability": {"type": "string", "enum": ["web", "weather", "exact_edit"]}},
            ["capability"],
        ),
    ]


def parse_proc_status(text: str) -> dict[str, int]:
    """KiB values of the memory fields that separate anonymous from file-backed residency."""
    values: dict[str, int] = {}
    for line in text.splitlines():
        name, _, rest = line.partition(":")
        if name in STATUS_FIELDS:
            match = re.match(r"\s*(\d+)\s*kB", rest)
            if match:
                values[name] = int(match.group(1))
    return values


def prompt_timings(response: dict[str, Any]) -> dict[str, Any]:
    timings = response.get("timings") or {}
    choice = (response.get("choices") or [{}])[0]
    message = choice.get("message") or {}
    return {
        "cacheN": timings.get("cache_n"),
        "promptN": timings.get("prompt_n"),
        "promptMs": timings.get("prompt_ms"),
        "promptPerSecond": timings.get("prompt_per_second"),
        "predictedN": timings.get("predicted_n"),
        "predictedPerSecond": timings.get("predicted_per_second"),
        "content": message.get("content"),
        "reasoning": message.get("reasoning_content"),
        "toolCalls": message.get("tool_calls"),
    }


def answer_signature(step: dict[str, Any]) -> tuple:
    """Reasoning, text and tool calls without per-response random call ids."""
    calls = tuple(
        (call.get("function", {}).get("name"), call.get("function", {}).get("arguments"))
        for call in (step.get("toolCalls") or [])
        if isinstance(call, dict)
    )
    return step.get("reasoning"), step.get("content"), calls


def filler_paragraphs(count: int) -> str:
    """Deterministic varied text so prompts of a target size cannot collapse into repeats."""
    words = (
        "parser token buffer index cache window thread core matrix vector layer state slot "
        "prompt schema module file test edit commit branch session phone memory swap clock"
    ).split()
    lines = []
    for index in range(count):
        picked = [words[(index * 7 + offset * 3) % len(words)] for offset in range(14)]
        lines.append(f"{index + 1}. " + " ".join(picked) + ".")
    return "\n".join(lines)


# ---------------------------------------------------------------------------------------------
# Device plumbing
# ---------------------------------------------------------------------------------------------

class Device:
    def __init__(self, serial: str | None):
        self.serial = serial

    def adb(self, *args: str, check: bool = True, timeout: float = 600) -> subprocess.CompletedProcess:
        command = ["adb"] + (["-s", self.serial] if self.serial else []) + list(args)
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
        if check and result.returncode != 0:
            raise ExperimentError(f"{' '.join(command[:4])} failed: {result.stderr.strip()[:400]}")
        return result

    def shell(self, script: str, *, check: bool = True, timeout: float = 600) -> str:
        return self.adb("shell", script, check=check, timeout=timeout).stdout

    def push_runtime(self) -> dict[str, str]:
        self.shell(f"mkdir -p {DEVICE_DIR}/lib")
        hashes: dict[str, str] = {}
        remote = self.shell(f"cd {DEVICE_DIR}/lib && sha256sum *.so 2>/dev/null", check=False)
        present = {line.split()[1]: line.split()[0] for line in remote.splitlines() if len(line.split()) == 2}
        for name in STOCK_LIBRARIES:
            local = JNI / name
            digest = hashlib.sha256(local.read_bytes()).hexdigest()
            hashes[name] = digest
            if present.get(name) != digest:
                self.adb("push", str(local), f"{DEVICE_DIR}/lib/{name}", timeout=300)
        self.shell(f"chmod 755 {DEVICE_DIR}/lib/*.so")
        return hashes

    def thermal(self) -> dict[str, Any]:
        script = (
            "for z in /sys/class/thermal/thermal_zone*/; do "
            "IFS= read -r t < \"$z/type\" || continue; IFS= read -r v < \"$z/temp\" || continue; "
            "printf 'Z %s %s\\n' \"$t\" \"$v\"; done; "
            "for c in 3 7; do IFS= read -r a < /sys/devices/system/cpu/cpu$c/cpufreq/scaling_max_freq; "
            "IFS= read -r b < /sys/devices/system/cpu/cpu$c/cpufreq/cpuinfo_max_freq; "
            "printf 'F %s %s %s\\n' $c $a $b; done"
        )
        hottest = None
        headroom = 1.0
        for line in self.shell(script).splitlines():
            parts = line.split()
            if parts[:1] == ["Z"] and len(parts) == 3 and parts[2].lstrip("-").isdigit():
                if re.match(r"(cpu|gpu|soc|cluster)", parts[1], re.I):
                    value = int(parts[2])
                    if hottest is None or value > hottest[1]:
                        hottest = (parts[1], value)
            elif parts[:1] == ["F"] and len(parts) == 4:
                headroom = min(headroom, int(parts[2]) / max(1, int(parts[3])))
        return {
            "at": utc_now(),
            "hottestZone": hottest[0] if hottest else None,
            "hottestMilliCelsius": hottest[1] if hottest else None,
            "maxFrequencyHeadroom": round(headroom, 4),
        }

    def wait_cool(self, max_celsius: float, timeout: float = 900) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        state = self.thermal()
        while time.monotonic() < deadline:
            hot = (state["hottestMilliCelsius"] or 0) / 1000
            if hot <= max_celsius and state["maxFrequencyHeadroom"] >= 0.98:
                return state
            time.sleep(15)
            state = self.thermal()
        state["timedOut"] = True
        return state

    def start_server(self, args: list[str], label: str,
                     executable: str = f"{DEVICE_DIR}/lib/libpideck_llama_server.so") -> tuple[int, float]:
        self.stop_all()
        quoted = " ".join(_shell_quote(arg) for arg in args)
        inner = (
            f"cd {DEVICE_DIR} && LD_LIBRARY_PATH={DEVICE_DIR}/lib TMPDIR={DEVICE_DIR} "
            f"exec {executable} {quoted} "
            f"> {DEVICE_DIR}/{label}.log 2>&1 < /dev/null"
        )
        # A detached session: adb shell must not wait for the server's descriptors.
        script = f"setsid sh -c {_shell_quote(inner + ' & echo $!')} < /dev/null 2>/dev/null"
        started = time.monotonic()
        pid = int(self.shell(script).strip().splitlines()[-1])
        self.adb("forward", f"tcp:{PORT}", f"tcp:{PORT}")
        deadline = started + 300
        while time.monotonic() < deadline:
            if not self.alive(pid):
                tail = self.shell(f"tail -n 30 {DEVICE_DIR}/{label}.log", check=False)
                raise ExperimentError(f"server exited during load:\n{tail}")
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/health", timeout=2) as response:
                    if response.status == 200:
                        return pid, time.monotonic() - started
            except (urllib.error.URLError, ConnectionError, OSError):
                pass
            time.sleep(0.25)
        raise ExperimentError("server did not become healthy within 300 s")

    def alive(self, pid: int) -> bool:
        return self.shell(f"[ -d /proc/{pid} ] && echo yes || echo no").strip() == "yes"

    def status(self, pid: int) -> dict[str, int]:
        return parse_proc_status(self.shell(f"cat /proc/{pid}/status", check=False))

    def stop_all(self) -> None:
        # The bracket keeps pkill from matching this very shell's command line.
        self.shell("pkill -f '[l]ibpideck_llama_server.so'; pkill -f '[p]ideck-prism-'; sleep 1",
                   check=False)

    def server_log(self, label: str, lines: int = 200) -> str:
        return self.shell(f"tail -n {lines} {DEVICE_DIR}/{label}.log", check=False)


def _shell_quote(value: str) -> str:
    if re.fullmatch(r"[A-Za-z0-9_./,:=+-]+", value):
        return value
    return "'" + value.replace("'", "'\\''") + "'"


def chat(payload: dict[str, Any], timeout: float = 900) -> tuple[dict[str, Any], float]:
    request = urllib.request.Request(
        f"http://127.0.0.1:{PORT}/v1/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    started = time.monotonic()
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = json.loads(response.read().decode("utf-8"))
    return body, time.monotonic() - started


def catalog_model(model_id: str) -> dict[str, Any]:
    for model in json.loads(CATALOG.read_text(encoding="utf-8"))["models"]:
        if model["id"] == model_id:
            return model
    raise ExperimentError(f"unknown model {model_id}")


def request_payload(model: dict[str, Any], messages: list[dict], cache_prompt: bool,
                    max_tokens: int, tools: bool = True) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "model": model["id"],
        "messages": messages,
        "max_tokens": max_tokens,
        "temperature": 0,
        "seed": 7,
        "stream": False,
        "cache_prompt": cache_prompt,
    }
    if tools:
        payload["tools"] = agent_tools()
        payload["tool_choice"] = "auto"
        payload["parallel_tool_calls"] = False
    return payload


# ---------------------------------------------------------------------------------------------
# Experiments
# ---------------------------------------------------------------------------------------------

def experiment_prefix_reuse(device: Device, model: dict, model_path: str, args: argparse.Namespace) -> dict:
    system = {"role": "system", "content": agent_system_prompt()}
    ask = lambda text: {"role": "user", "content": text}  # noqa: E731
    steps: list[dict[str, Any]] = []
    cool = device.wait_cool(args.max_celsius)
    pid, load_seconds = device.start_server(server_arguments(model, model_path), "prefix-reuse")

    def run(label: str, messages: list[dict], cache: bool) -> dict[str, Any]:
        body, wall = chat(request_payload(model, messages, cache, args.max_tokens))
        entry = {"label": label, "cachePrompt": cache, "wallSeconds": round(wall, 3),
                 "messages": len(messages), **prompt_timings(body)}
        steps.append(entry)
        print(json.dumps({k: entry[k] for k in ("label", "cacheN", "promptN", "promptMs", "wallSeconds")}),
              flush=True)
        return entry

    first = run("session-a-first", [system, ask("Что делает функция divide в calc.py?")], False)
    reply = {"role": "assistant", "content": first["content"] or "OK"}
    run("session-a-growth", [system, ask("Что делает функция divide в calc.py?"), reply,
                             ask("А как она обрабатывает ноль?")], True)
    new_session = [system, ask("Создай файл hello.py, который печатает привет.")]
    reused = run("session-b-first-reuse", new_session, True)
    control = run("session-b-first-control", new_session, False)
    # A request with another system prompt (Chat mode, compaction) leaves no usable checkpoint
    # for the agent prefix; the next agent session must still complete with a full prefill.
    run("other-system", [{"role": "system", "content": "Summarize the conversation."},
                         ask("Кратко: что было сделано?")], False)
    fallback = run("session-c-first-after-other", [system, ask("Покажи список файлов.")], True)
    log = device.server_log("prefix-reuse", 400)
    device.stop_all()
    prefix_tokens = control["promptN"]
    return {
        "loadSeconds": round(load_seconds, 2),
        "thermalBefore": cool,
        "thermalAfter": device.thermal(),
        "steps": steps,
        "verdict": {
            "reusedCacheN": reused["cacheN"],
            "controlPromptN": prefix_tokens,
            "reusedPromptMs": reused["promptMs"],
            "controlPromptMs": control["promptMs"],
            "speedup": (round(control["promptMs"] / reused["promptMs"], 2)
                        if reused["promptMs"] and control["promptMs"] else None),
            # Greedy decoding from a restored checkpoint must reproduce the full prefill.
            "sameAnswer": answer_signature(reused) == answer_signature(control),
            "answerChars": len((control["reasoning"] or "") + (control["content"] or "")),
            "fallbackCompleted": fallback["promptN"] is not None,
            "fallbackCacheN": fallback["cacheN"],
        },
        "checkpointLog": [line for line in log.splitlines() if "checkpoint" in line][-24:],
    }


MEMORY_LAYOUTS = (("mmap", []), ("no-mmap", ["--no-mmap"]))
KV_LAYOUTS = (("kv-f16", []), ("kv-q8_0", ["-ctk", "q8_0", "-ctv", "q8_0"]))


def experiment_memory(device: Device, model: dict, model_path: str, args: argparse.Namespace,
                      layouts: tuple = MEMORY_LAYOUTS) -> dict:
    system = {"role": "system", "content": agent_system_prompt()}
    long_user = {"role": "user", "content": "Прочитай заметки и ответь одним словом OK.\n"
                 + filler_paragraphs(args.filler)}
    variants = []
    for round_index in range(args.repeats):
        # Alternate the order each round so drift from warming does not favour one layout.
        order = layouts if round_index % 2 == 0 else tuple(reversed(layouts))
        for label, extra in order:
            cool = device.wait_cool(args.max_celsius)
            pid, load_seconds = device.start_server(server_arguments(model, model_path, extra=extra),
                                                    f"memory-{label}")
            after_load = device.status(pid)
            body, wall = chat(request_payload(model, [system, long_user], False, args.max_tokens))
            after_prompt = device.status(pid)
            timings = prompt_timings(body)
            device.stop_all()
            entry = {"label": label, "round": round_index, "extra": extra,
                     "loadSeconds": round(load_seconds, 2), "afterLoadKiB": after_load,
                     "afterPromptKiB": after_prompt, "wallSeconds": round(wall, 2),
                     "thermalBefore": cool, **{k: timings[k] for k in
                                                ("promptN", "promptPerSecond", "predictedN",
                                                 "predictedPerSecond")}}
            variants.append(entry)
            print(json.dumps({k: entry[k] for k in ("label", "loadSeconds", "afterPromptKiB",
                                                     "promptPerSecond", "predictedPerSecond")}),
                  flush=True)
    return {"variants": variants}


def experiment_batch_threads(device: Device, model: dict, model_path: str, args: argparse.Namespace) -> dict:
    system = {"role": "system", "content": agent_system_prompt()}
    long_user = {"role": "user", "content": "Прочитай заметки и ответь одним словом OK.\n"
                 + filler_paragraphs(args.filler)}
    layouts = (("batch8@0-7", 8, "0-7"), ("batch5@3-7", 5, "3-7"))
    variants = []
    for round_index in range(args.repeats):
        order = layouts if round_index % 2 == 0 else tuple(reversed(layouts))
        for label, threads, cpus in order:
            cool = device.wait_cool(args.max_celsius)
            pid, load_seconds = device.start_server(
                server_arguments(model, model_path, batch_threads=threads, batch_cpus=cpus),
                f"batch-{label}")
            body, wall = chat(request_payload(model, [system, long_user], False, args.max_tokens))
            timings = prompt_timings(body)
            after = device.thermal()
            device.stop_all()
            entry = {"label": label, "round": round_index, "thermalBefore": cool, "thermalAfter": after,
                     "loadSeconds": round(load_seconds, 2), "wallSeconds": round(wall, 2),
                     **{k: timings[k] for k in ("promptN", "promptPerSecond", "predictedN",
                                                "predictedPerSecond")}}
            variants.append(entry)
            print(json.dumps({k: entry[k] for k in ("label", "round", "promptPerSecond",
                                                     "predictedPerSecond")}), flush=True)
    return {"variants": variants}


CHAT_SYSTEM = (
    "You are PI//DECK's local assistant on this Android phone.\n"
    "Answer the request directly, in the user's language. Be concise unless detail is requested."
)


def experiment_prism_kernel(device: Device, model: dict, model_path: str, args: argparse.Namespace) -> dict:
    """Baseline versus candidate Prism executables in ABBA order on the same GGUF.

    The candidate must reproduce the baseline's greedy answer exactly; only then is its prompt
    ingestion speed compared.
    """
    binaries = {
        "baseline": (args.baseline_binary, f"{DEVICE_DIR}/pideck-prism-baseline"),
        "candidate": (args.candidate_binary, f"{DEVICE_DIR}/pideck-prism-candidate"),
    }
    hashes = {}
    for label, (local, remote) in binaries.items():
        hashes[label] = hashlib.sha256(Path(local).read_bytes()).hexdigest()
        device.adb("push", local, remote, timeout=300)
        device.shell(f"chmod 755 {remote}")
    messages = [
        {"role": "system", "content": CHAT_SYSTEM},
        {"role": "user", "content": "Прочитай заметки и ответь одним словом OK.\n"
         + filler_paragraphs(args.filler)},
    ]
    variants = []
    for label in ("baseline", "candidate", "candidate", "baseline")[: 2 * args.repeats]:
        cool = device.wait_cool(args.max_celsius)
        pid, load_seconds = device.start_server(
            server_arguments(model, model_path), f"prism-{label}", executable=binaries[label][1]
        )
        payload = request_payload(model, messages, False, args.max_tokens, tools=False)
        payload["chat_template_kwargs"] = {"enable_thinking": False}
        body, wall = chat(payload, timeout=1800)
        timings = prompt_timings(body)
        status = device.status(pid)
        log = device.server_log(f"prism-{label}", 80)
        device.stop_all()
        entry = {"label": label, "binarySha256": hashes[label], "thermalBefore": cool,
                 "thermalAfter": device.thermal(), "loadSeconds": round(load_seconds, 2),
                 "wallSeconds": round(wall, 2), "afterPromptKiB": status,
                 "affinityLines": [line for line in log.splitlines() if "affinity" in line][-4:],
                 **{k: timings[k] for k in ("promptN", "promptPerSecond", "predictedN",
                                            "predictedPerSecond", "content", "reasoning")}}
        variants.append(entry)
        print(json.dumps({k: entry[k] for k in ("label", "promptN", "promptPerSecond",
                                                 "predictedPerSecond", "wallSeconds")}), flush=True)
    by_label: dict[str, list[dict]] = {}
    for entry in variants:
        by_label.setdefault(entry["label"], []).append(entry)
    answers = {answer_signature(entry) for entry in variants}
    mean = lambda items: sum(i["promptPerSecond"] for i in items) / len(items)  # noqa: E731
    return {
        "variants": variants,
        "verdict": {
            "identicalAnswers": len(answers) == 1,
            "baselinePromptPerSecond": round(mean(by_label["baseline"]), 3),
            "candidatePromptPerSecond": round(mean(by_label["candidate"]), 3),
            "prefillSpeedup": round(mean(by_label["candidate"]) / mean(by_label["baseline"]), 3),
        },
    }


USER_TURN_MARKERS = ("<|im_start|>user", "<|start_header_id|>user", "<start_of_turn>user", "[INST]")


def system_prefix(first: str, second: str) -> str:
    """The rendered text both prompts share, cut before the user turn marker.

    Cutting at a special-token boundary keeps the prefix tokenisation identical to the start of
    every full prompt, so a restored slot is a strict prefix of the next request.
    """
    common = 0
    for left, right in zip(first, second):
        if left != right:
            break
        common += 1
    shared = first[:common]
    cut = max(shared.rfind(marker) for marker in USER_TURN_MARKERS)
    if cut <= 0:
        raise ExperimentError("no known user-turn marker in the rendered prompt")
    return shared[:cut]


def post_json(path: str, body: dict[str, Any], timeout: float = 900) -> dict[str, Any]:
    request = urllib.request.Request(
        f"http://127.0.0.1:{PORT}{path}", data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"}, method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def experiment_slot_snapshot(device: Device, model: dict, model_path: str, args: argparse.Namespace) -> dict:
    """Cold start: a saved system+tools slot restored into a fresh server process."""
    slots = f"{DEVICE_DIR}/slots/"
    device.shell(f"rm -rf {slots} && mkdir -p {slots}")
    server = server_arguments(model, model_path, extra=["--slot-save-path", slots])
    system = {"role": "system", "content": agent_system_prompt()}
    tools = agent_tools()
    question = {"role": "user", "content": "Создай файл hello.py, который печатает привет."}
    full = request_payload(model, [system, question], True, args.max_tokens)

    cool = device.wait_cool(args.max_celsius)
    device.start_server(server, "slot-prepare")
    rendered = [
        post_json("/apply-template", {"messages": [system, {"role": "user", "content": text}],
                                      "tools": tools, "tool_choice": "auto"})["prompt"]
        for text in ("первый вопрос", "совсем другой текст")
    ]
    prefix = system_prefix(rendered[0], rendered[1])
    started = time.monotonic()
    warm = post_json("/completion", {"prompt": prefix, "n_predict": 0, "cache_prompt": True})
    prefix_seconds = time.monotonic() - started
    saved = post_json("/slots/0?action=save", {"filename": "prefix.bin"})
    snapshot_bytes = int(device.shell(f"stat -c %s {slots}prefix.bin").strip() or 0)

    # A fresh process, as after a model reload: restore, then the real first request.
    device.start_server(server, "slot-restore")
    started = time.monotonic()
    restored = post_json("/slots/0?action=restore", {"filename": "prefix.bin"})
    restore_seconds = time.monotonic() - started
    body, wall = chat(full)
    reused = {"wallSeconds": round(wall, 3), **prompt_timings(body)}

    device.start_server(server, "slot-control")
    control_body, control_wall = chat({**full, "cache_prompt": False})
    control = {"wallSeconds": round(control_wall, 3), **prompt_timings(control_body)}
    device.stop_all()
    print(json.dumps({"prefixTokens": warm.get("tokens_evaluated"), "reusedCacheN": reused["cacheN"],
                      "reusedPromptMs": reused["promptMs"], "controlPromptMs": control["promptMs"]}),
          flush=True)
    return {
        "thermalBefore": cool,
        "prefixChars": len(prefix),
        "prefixTokens": warm.get("tokens_evaluated"),
        "prefixSeconds": round(prefix_seconds, 2),
        "saved": saved,
        "snapshotBytes": snapshot_bytes,
        "restored": restored,
        "restoreSeconds": round(restore_seconds, 3),
        "reused": reused,
        "control": control,
        "verdict": {
            "reusedCacheN": reused["cacheN"],
            "sameAnswer": answer_signature(reused) == answer_signature(control),
            "speedup": (round(control["promptMs"] / reused["promptMs"], 2)
                        if reused["promptMs"] and control["promptMs"] else None),
        },
    }


EXPERIMENTS = {
    "prefix-reuse": experiment_prefix_reuse,
    "memory": experiment_memory,
    "batch-threads": experiment_batch_threads,
    "prism-kernel": experiment_prism_kernel,
    "slot-snapshot": experiment_slot_snapshot,
    "kv-quant": lambda device, model, path, args: experiment_memory(
        device, model, path, args, layouts=KV_LAYOUTS
    ),
}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("experiment", choices=sorted(EXPERIMENTS))
    parser.add_argument("--serial")
    parser.add_argument("--model-id", default="lfm2.5-2.6b-qad")
    parser.add_argument("--model-path", required=True, help="GGUF path on the phone")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--max-tokens", type=int, default=24)
    parser.add_argument("--filler", type=int, default=120, help="filler lines for long prompts")
    parser.add_argument("--repeats", type=int, default=2)
    parser.add_argument("--max-celsius", type=float, default=42.0)
    parser.add_argument("--baseline-binary", help="prism-kernel: local baseline executable")
    parser.add_argument("--candidate-binary", help="prism-kernel: local candidate executable")
    args = parser.parse_args(argv)
    device = Device(args.serial)
    model = catalog_model(args.model_id)
    report: dict[str, Any] = {
        "schemaVersion": 1,
        "experiment": args.experiment,
        "startedAt": utc_now(),
        "device": device.shell("getprop ro.product.model").strip(),
        "modelId": model["id"],
        "modelSha256": model["artifact"]["sha256"],
        "runtimeLibraries": device.push_runtime(),
        "arguments": server_arguments(model, args.model_path),
    }
    try:
        report["result"] = EXPERIMENTS[args.experiment](device, model, args.model_path, args)
    finally:
        device.stop_all()
        device.adb("forward", "--remove", f"tcp:{PORT}", check=False)
    report["finishedAt"] = utc_now()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"report: {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
