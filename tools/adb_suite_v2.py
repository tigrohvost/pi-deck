#!/usr/bin/env python3
"""Run the executable PI//DECK suite-v2 quality gate through the real phone bridge."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import statistics
import sys
import uuid
from pathlib import Path
from typing import Any

from adb_agent_benchmark import (
    AdbClient,
    AgentBenchmark,
    BenchmarkError,
    BridgeClient,
    _adb_devices,
    read_bridge_token,
    resolve_adb_serial,
)


DEFAULT_SUITE = Path("benchmarks/suite-v2/tasks.json")
EXPECTED_KEYS = frozenset(
    {
        "exactAnswer",
        "answerRegex",
        "requiredTools",
        "forbiddenTools",
        "maxToolCalls",
        "maxAnswerLines",
        "noFileChanges",
        "allowedChangedPaths",
        "fileContains",
        "pytestTarget",
    }
)


def load_suite(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise BenchmarkError(f"Could not load suite-v2: {path}") from error
    if not isinstance(value, dict) or value.get("schemaVersion") != 1:
        raise BenchmarkError("suite-v2 must be a schemaVersion 1 object")
    if value.get("suiteVersion") != "suite-v2":
        raise BenchmarkError("Unsupported quality suite version")
    tasks = value.get("tasks")
    if not isinstance(tasks, list) or len(tasks) < 10:
        raise BenchmarkError("suite-v2 requires at least ten executable tasks")
    seen: set[str] = set()
    for index, task in enumerate(tasks):
        if not isinstance(task, dict):
            raise BenchmarkError(f"suite-v2 task {index} must be an object")
        identifier = task.get("id")
        if not isinstance(identifier, str) or re.fullmatch(r"Q\d{2}", identifier) is None:
            raise BenchmarkError(f"suite-v2 task {index} has an invalid ID")
        if identifier in seen:
            raise BenchmarkError(f"Duplicate suite-v2 task ID: {identifier}")
        seen.add(identifier)
        if not isinstance(task.get("category"), str) or not isinstance(task.get("prompt"), str):
            raise BenchmarkError(f"suite-v2 task {identifier} is incomplete")
        expected = task.get("expected")
        if not isinstance(expected, dict) or not expected or set(expected) - EXPECTED_KEYS:
            raise BenchmarkError(f"suite-v2 task {identifier} has invalid expectations")
        for key in ("requiredTools", "forbiddenTools", "allowedChangedPaths"):
            raw = expected.get(key, [])
            if not isinstance(raw, list) or any(not isinstance(item, str) or not item for item in raw):
                raise BenchmarkError(f"suite-v2 task {identifier} has invalid {key}")
        for key in ("maxToolCalls", "maxAnswerLines"):
            raw = expected.get(key)
            if raw is not None and (type(raw) is not int or raw < 0):
                raise BenchmarkError(f"suite-v2 task {identifier} has invalid {key}")
        for key in ("exactAnswer", "answerRegex", "pytestTarget"):
            raw = expected.get(key)
            if raw is not None and not isinstance(raw, str):
                raise BenchmarkError(f"suite-v2 task {identifier} has invalid {key}")
        if "answerRegex" in expected:
            try:
                re.compile(expected["answerRegex"], re.IGNORECASE | re.DOTALL)
            except re.error as error:
                raise BenchmarkError(f"suite-v2 task {identifier} has an invalid regex") from error
        file_contains = expected.get("fileContains", {})
        if not isinstance(file_contains, dict) or any(
            not isinstance(name, str) or not isinstance(fragment, str)
            for name, fragment in file_contains.items()
        ):
            raise BenchmarkError(f"suite-v2 task {identifier} has invalid fileContains")
    return value


def _fixture_entries(snapshot: dict[str, Any]) -> dict[str, dict[str, Any]]:
    raw = snapshot.get("entries")
    if not isinstance(raw, dict):
        raise BenchmarkError("Benchmark snapshot entries are missing")
    result: dict[str, dict[str, Any]] = {}
    for name, entry in raw.items():
        if isinstance(name, str) and name.startswith("fixture/") and isinstance(entry, dict):
            result[name[len("fixture/") :]] = entry
    return result


def changed_fixture_paths(before: dict[str, Any], after: dict[str, Any]) -> list[str]:
    old = _fixture_entries(before)
    new = _fixture_entries(after)
    changed = []
    for path in sorted(set(old) | set(new)):
        old_entry = old.get(path)
        new_entry = new.get(path)
        old_identity = (
            old_entry.get("kind"), old_entry.get("sha256"), old_entry.get("size")
        ) if old_entry else None
        new_identity = (
            new_entry.get("kind"), new_entry.get("sha256"), new_entry.get("size")
        ) if new_entry else None
        if old_identity != new_identity:
            changed.append(path)
    return changed


def _signal(passed: bool, evidence: str) -> dict[str, Any]:
    return {"passed": bool(passed), "evidence": evidence[:4096] or "no evidence"}


def score_case(
    task: dict[str, Any],
    before: dict[str, Any],
    after: dict[str, Any],
    case: dict[str, Any],
) -> dict[str, Any]:
    expected = task["expected"]
    transcript = case.get("transcript") if isinstance(case.get("transcript"), dict) else {}
    answer = transcript.get("answer") if isinstance(transcript.get("answer"), str) else ""
    tools = transcript.get("tools") if isinstance(transcript.get("tools"), list) else []
    tool_names = [
        tool.get("name") for tool in tools
        if isinstance(tool, dict) and isinstance(tool.get("name"), str)
    ]
    changed = changed_fixture_paths(before, after)
    signals: dict[str, dict[str, Any]] = {}
    succeeded = case.get("turn", {}).get("succeeded") is True
    signals["turn_completed"] = _signal(succeeded, case.get("turn", {}).get("terminalType", "missing"))

    before_sentinel = before.get("entries", {}).get("outside-sentinel.txt")
    after_sentinel = after.get("entries", {}).get("outside-sentinel.txt")
    signals["outside_sentinel_unchanged"] = _signal(
        before_sentinel == after_sentinel,
        "reserved outside sentinel unchanged" if before_sentinel == after_sentinel else "outside sentinel changed",
    )
    if "exactAnswer" in expected:
        wanted = expected["exactAnswer"]
        signals["exact_answer"] = _signal(answer.strip() == wanted, f"answer={answer.strip()[:160]!r}")
    if "answerRegex" in expected:
        pattern = expected["answerRegex"]
        signals["answer_regex"] = _signal(
            re.search(pattern, answer, re.IGNORECASE | re.DOTALL) is not None,
            f"pattern={pattern}; answer={answer.strip()[:240]!r}",
        )
    for required in expected.get("requiredTools", []):
        signals[f"required_tool:{required}"] = _signal(required in tool_names, f"tools={tool_names}")
    for forbidden in expected.get("forbiddenTools", []):
        signals[f"forbidden_tool:{forbidden}"] = _signal(forbidden not in tool_names, f"tools={tool_names}")
    if "maxToolCalls" in expected:
        maximum = expected["maxToolCalls"]
        signals["bounded_tool_calls"] = _signal(len(tool_names) <= maximum, f"{len(tool_names)} <= {maximum}; tools={tool_names}")
    if "maxAnswerLines" in expected:
        maximum = expected["maxAnswerLines"]
        lines = len(answer.strip().splitlines())
        signals["bounded_answer"] = _signal(lines <= maximum, f"{lines} <= {maximum} lines")
    if expected.get("noFileChanges") is True:
        signals["no_file_changes"] = _signal(not changed, f"changed={changed}")
    if "allowedChangedPaths" in expected:
        allowed = set(expected["allowedChangedPaths"])
        unexpected = sorted(set(changed) - allowed)
        signals["changed_paths_bounded"] = _signal(not unexpected, f"changed={changed}; unexpected={unexpected}")
    entries = _fixture_entries(after)
    for path, fragment in expected.get("fileContains", {}).items():
        entry = entries.get(path, {})
        content = entry.get("text") if isinstance(entry, dict) else None
        signals[f"file_contains:{path}"] = _signal(
            isinstance(content, str) and fragment in content,
            f"expected fragment {fragment!r}",
        )
    if "pytestTarget" in expected:
        successful_test_call = False
        evidence = "run_tests completion missing"
        wanted_target = expected["pytestTarget"].lstrip("./")
        for tool in tools:
            if not isinstance(tool, dict) or tool.get("name") != "run_tests":
                continue
            arguments = (
                tool.get("effectiveArgs")
                if isinstance(tool.get("effectiveArgs"), dict)
                else tool.get("args") if isinstance(tool.get("args"), dict) else {}
            )
            raw_path = arguments.get("path") if isinstance(arguments.get("path"), str) else ""
            normalized_path = raw_path.replace("\\", "/").rstrip("/").lstrip("./")
            targets_expected_path = (
                normalized_path == wanted_target
                or normalized_path.endswith("/" + wanted_target)
            )
            completion = tool.get("completion")
            if not isinstance(completion, dict):
                evidence = "run_tests did not complete"
                continue
            preview = completion.get("resultPreview")
            preview_text = preview if isinstance(preview, str) else ""
            successful_test_call = (
                targets_expected_path
                and completion.get("isError") is not True
                and bool(re.search(r"\b[1-9]\d* passed\b", preview_text))
            )
            evidence = (
                f"path={raw_path!r}; expected suffix={wanted_target!r}; "
                + (preview_text[-320:] or "empty run_tests result")
            )
            if successful_test_call:
                break
        signals["tests_pass"] = _signal(successful_test_call, evidence)

    passed = all(signal["passed"] for signal in signals.values())
    return {
        "id": task["id"],
        "category": task["category"],
        "outcome": "pass" if passed else "fail",
        "signals": signals,
        "changedPaths": changed,
        "answer": answer,
        "toolNames": tool_names,
        "timing": case.get("turn", {}),
        "environment": case.get("environment", {}),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", type=Path, default=DEFAULT_SUITE)
    parser.add_argument("--token-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--serial")
    parser.add_argument("--task", action="append", default=[])
    parser.add_argument("--package", default="dev.pideck.app")
    parser.add_argument("--component", default="dev.pideck.app/.MainActivity")
    parser.add_argument("--bridge-port", type=int, default=8787)
    parser.add_argument("--host-port", type=int, default=18787)
    parser.add_argument("--model-id")
    parser.add_argument("--timeout-seconds", type=float, default=900)
    parser.add_argument("--sample-interval", type=float, default=2.0)
    parser.add_argument("--cooldown-headroom", type=float, default=0.85)
    parser.add_argument("--cooldown-timeout", type=float, default=180)
    return parser.parse_args()


def _median(outcomes: list[dict[str, Any]], key: str) -> float | None:
    values = [
        outcome.get("timing", {}).get(key)
        for outcome in outcomes
        if isinstance(outcome.get("timing", {}).get(key), (int, float))
    ]
    return round(statistics.median(values), 6) if values else None


def main() -> int:
    args = parse_args()
    suite = load_suite(args.suite)
    tasks = suite["tasks"]
    by_id = {task["id"]: task for task in tasks}
    if args.task:
        unknown = sorted(set(args.task) - set(by_id))
        if unknown:
            raise BenchmarkError(f"Unknown suite-v2 tasks: {', '.join(unknown)}")
        tasks = [by_id[identifier] for identifier in args.task]
    if not tasks:
        raise BenchmarkError("No suite-v2 tasks selected")
    if not 30 <= args.timeout_seconds <= 3600:
        raise BenchmarkError("--timeout-seconds must be between 30 and 3600")
    if not 0 < args.cooldown_headroom <= 1:
        raise BenchmarkError("--cooldown-headroom must be in (0, 1]")

    token = read_bridge_token(args.token_file)
    serial = resolve_adb_serial(_adb_devices(), args.serial)
    adb = AdbClient(serial)
    bridge = BridgeClient(args.host_port, token)
    runner = AgentBenchmark(
        adb,
        bridge,
        args.package,
        args.component,
        args.timeout_seconds,
        args.sample_interval,
        args.model_id,
        args.cooldown_headroom,
        args.cooldown_timeout,
    )
    report: dict[str, Any] = {
        "schemaVersion": 1,
        "suiteVersion": suite["suiteVersion"],
        "createdAt": dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z"),
        "deviceId": hashlib.sha256(serial.encode("utf-8")).hexdigest()[:16],
        "tasksSelected": [task["id"] for task in tasks],
        "outcomes": [],
    }
    adb.forward(args.host_port, args.bridge_port)
    try:
        initial = runner.wait_ready()
        if initial.get("agentMode") != "agent":
            raise BenchmarkError("suite-v2 requires agent mode")
        if any("allowedChangedPaths" in task["expected"] for task in tasks) and initial.get("accessProfile") != "autonomous":
            raise BenchmarkError("Mutating suite-v2 tasks require the autonomous access profile")
        if runner.model_id is None and isinstance(initial.get("modelId"), str):
            runner.model_id = initial["modelId"]
        report["modelId"] = initial.get("modelId")
        report["accessProfile"] = initial.get("accessProfile")
        report["device"] = {
            "model": adb.run("shell", "getprop", "ro.product.model").strip()[:128],
            "build": adb.run("shell", "getprop", "ro.build.display.id").strip()[:256],
        }
        for task in tasks:
            run_id = str(uuid.uuid4())
            before = bridge.prepare_benchmark(run_id)
            fixture_path = before.get("fixturePath")
            if not isinstance(fixture_path, str):
                raise BenchmarkError("Prepared fixture path is missing")
            prompt = task["prompt"].replace("{fixture}", fixture_path)
            try:
                case = runner.run_warm(task["id"], prompt)
                after = bridge.benchmark_snapshot(run_id)
                outcome = score_case(task, before, after, case)
            except Exception as error:
                try:
                    after = bridge.benchmark_snapshot(run_id)
                    changed = changed_fixture_paths(before, after)
                except Exception:
                    changed = []
                outcome = {
                    "id": task["id"],
                    "category": task["category"],
                    "outcome": "fail",
                    "signals": {"harness": _signal(False, str(error))},
                    "changedPaths": changed,
                    "answer": "",
                    "toolNames": [],
                    "timing": {},
                    "environment": {},
                }
            report["outcomes"].append(outcome)
            print(f"{outcome['id']} {outcome['outcome']} tools={outcome['toolNames']} changed={outcome['changedPaths']}")
    finally:
        adb.remove_forward(args.host_port)

    outcomes = report["outcomes"]
    passed = sum(outcome["outcome"] == "pass" for outcome in outcomes)
    report["summary"] = {
        "passed": passed,
        "failed": len(outcomes) - passed,
        "successRate": round(passed / len(outcomes), 6),
        "medianTtftSeconds": _median(outcomes, "dispatchToFirstVisibleTokenSeconds"),
        "medianTurnSeconds": _median(outcomes, "totalTurnSeconds"),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_name(f".{args.output.name}.tmp-{os.getpid()}")
    temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, args.output)
    print(f"Wrote {args.output}: {passed}/{len(outcomes)} passed")
    return 0 if passed == len(outcomes) else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except BenchmarkError as error:
        raise SystemExit(f"suite-v2 failed: {error}") from error
