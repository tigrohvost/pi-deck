#!/usr/bin/env python3
"""Validate the static suite contract and machine-readable run reports."""

from __future__ import annotations

import argparse
import json
import math
import re
from pathlib import Path
from typing import Any

REQUIRED_METRICS = frozenset(
    {
        "task_success_rate",
        "invalid_tool_call_rate",
        "unintended_file_change_count",
        "outside_workspace_change_count",
        "session_recovery_rate",
        "abort_success_rate",
        "cold_start_seconds",
        "time_to_first_token_seconds",
        "time_to_first_tool_call_seconds",
        "tokens_per_second",
        "peak_server_rss_mib",
        "peak_total_termux_rss_mib",
        "server_crash_count",
        "oom_count",
        "battery_delta_percent",
        "average_power_or_energy_if_available",
        "thermal_throttling_events",
        "device_temperature_start_end",
    }
)
REPORT_FIELDS = frozenset(
    {
        "schemaVersion",
        "suiteVersion",
        "runId",
        "deviceId",
        "modelId",
        "modelSha256",
        "piVersion",
        "llamaCppVersion",
        "contextSize",
        "samplingProfile",
        "tasks",
        "metrics",
    }
)
SAMPLING_FIELDS = frozenset(
    {"temperature", "topP", "topK", "minP", "presencePenalty", "maxTokens"}
)
TASK_FIELDS = frozenset({"id", "outcome", "signals", "changedPaths"})
TASK_OPTIONAL_FIELDS = frozenset({"durationSeconds"})
RATE_METRICS = frozenset(
    {
        "task_success_rate",
        "invalid_tool_call_rate",
        "session_recovery_rate",
        "abort_success_rate",
    }
)
COUNT_METRICS = frozenset(
    {
        "unintended_file_change_count",
        "outside_workspace_change_count",
        "server_crash_count",
        "oom_count",
        "thermal_throttling_events",
    }
)
POSITIVE_METRICS = frozenset(
    {
        "cold_start_seconds",
        "time_to_first_token_seconds",
        "time_to_first_tool_call_seconds",
        "tokens_per_second",
        "peak_server_rss_mib",
        "peak_total_termux_rss_mib",
    }
)


def load(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain an object")
    return value


def _exact_keys(
    value: dict[str, Any],
    required: frozenset[str],
    context: str,
    optional: frozenset[str] = frozenset(),
) -> None:
    missing = required - value.keys()
    unexpected = value.keys() - required - optional
    if missing:
        raise ValueError(f"{context} misses fields: {', '.join(sorted(missing))}")
    if unexpected:
        raise ValueError(
            f"{context} contains unknown fields: {', '.join(sorted(unexpected))}"
        )


def _string(
    value: Any,
    context: str,
    *,
    minimum: int = 1,
    maximum: int,
    pattern: str | None = None,
) -> str:
    if not isinstance(value, str) or not minimum <= len(value) <= maximum:
        raise ValueError(f"{context} must be a bounded string")
    if pattern is not None and re.fullmatch(pattern, value) is None:
        raise ValueError(f"{context} has an invalid format")
    return value


def _number(
    value: Any,
    context: str,
    *,
    minimum: float | None = None,
    maximum: float | None = None,
    exclusive_minimum: bool = False,
) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{context} must be a number")
    result = float(value)
    if not math.isfinite(result):
        raise ValueError(f"{context} must be finite")
    if minimum is not None:
        below = result <= minimum if exclusive_minimum else result < minimum
        if below:
            operator = "greater than" if exclusive_minimum else "at least"
            raise ValueError(f"{context} must be {operator} {minimum}")
    if maximum is not None and result > maximum:
        raise ValueError(f"{context} must be at most {maximum}")
    return result


def _integer(
    value: Any,
    context: str,
    *,
    minimum: int,
    maximum: int | None = None,
) -> int:
    if type(value) is not int:
        raise ValueError(f"{context} must be an integer")
    if value < minimum or (maximum is not None and value > maximum):
        raise ValueError(f"{context} is outside the allowed range")
    return value


def validate_suite(value: dict[str, Any]) -> None:
    if value.get("schemaVersion") != 1 or value.get("suiteVersion") != "suite-v1":
        raise ValueError("Unsupported benchmark suite")
    tasks = value.get("tasks")
    if not isinstance(tasks, list) or len(tasks) < 24:
        raise ValueError("Benchmark suite requires at least 24 tasks")
    ids: set[str] = set()
    for task in tasks:
        if not isinstance(task, dict):
            raise ValueError("Task must be an object")
        task_id = task.get("id")
        if not isinstance(task_id, str) or not re.fullmatch(r"T\d{2}", task_id):
            raise ValueError("Task ID is invalid")
        if task_id in ids:
            raise ValueError(f"Duplicate task ID: {task_id}")
        ids.add(task_id)
        if task.get("requiredProfile") not in {
            "READ_ONLY",
            "CONFIRM_CHANGES",
            "AUTONOMOUS",
        }:
            raise ValueError(f"Invalid profile in {task_id}")
        signals = task.get("expectedSignals")
        if (
            not isinstance(signals, list)
            or not signals
            or any(not isinstance(signal, str) or not signal for signal in signals)
            or len(set(signals)) != len(signals)
        ):
            raise ValueError(f"Invalid expected signals in {task_id}")


def _validate_sampling_profile(value: Any) -> None:
    if not isinstance(value, dict):
        raise ValueError("samplingProfile must be an object")
    _exact_keys(value, SAMPLING_FIELDS, "samplingProfile")
    _number(value["temperature"], "samplingProfile.temperature", minimum=0, maximum=5)
    _number(value["topP"], "samplingProfile.topP", minimum=0, maximum=1)
    _integer(value["topK"], "samplingProfile.topK", minimum=0, maximum=100_000)
    _number(value["minP"], "samplingProfile.minP", minimum=0, maximum=1)
    _number(
        value["presencePenalty"],
        "samplingProfile.presencePenalty",
        minimum=-2,
        maximum=2,
    )
    _integer(
        value["maxTokens"],
        "samplingProfile.maxTokens",
        minimum=1,
        maximum=1_048_576,
    )


def _validate_task_outcomes(value: Any, suite: dict[str, Any]) -> float:
    suite_tasks = suite["tasks"]
    if not isinstance(value, list) or len(value) != len(suite_tasks):
        raise ValueError("Report must contain exactly one outcome per suite task")
    expected_by_id = {task["id"]: task for task in suite_tasks}
    seen: set[str] = set()
    passed_count = 0
    for index, item in enumerate(value):
        context = f"tasks[{index}]"
        if not isinstance(item, dict):
            raise ValueError(f"{context} must be an object")
        _exact_keys(item, TASK_FIELDS, context, TASK_OPTIONAL_FIELDS)
        task_id = _string(item["id"], f"{context}.id", maximum=3, pattern=r"T\d{2}")
        if task_id not in expected_by_id or task_id in seen:
            raise ValueError("Report task IDs do not match the suite exactly once")
        seen.add(task_id)
        outcome = item["outcome"]
        if outcome not in {"pass", "fail"}:
            raise ValueError(f"{context}.outcome must be pass or fail")

        signals = item["signals"]
        if not isinstance(signals, dict):
            raise ValueError(f"{context}.signals must be an object")
        expected_signals = set(expected_by_id[task_id]["expectedSignals"])
        if set(signals) != expected_signals:
            raise ValueError(f"{context}.signals do not match the suite task")
        signal_results: list[bool] = []
        for signal_name, signal in signals.items():
            if not isinstance(signal, dict):
                raise ValueError(f"{context}.signals.{signal_name} must be an object")
            _exact_keys(
                signal,
                frozenset({"passed", "evidence"}),
                f"{context}.signals.{signal_name}",
            )
            if type(signal["passed"]) is not bool:
                raise ValueError(f"{context}.signals.{signal_name}.passed must be boolean")
            _string(
                signal["evidence"],
                f"{context}.signals.{signal_name}.evidence",
                maximum=4096,
            )
            signal_results.append(signal["passed"])

        changed_paths = item["changedPaths"]
        if not isinstance(changed_paths, list):
            raise ValueError(f"{context}.changedPaths must be an array")
        for path_index, path in enumerate(changed_paths):
            _string(
                path,
                f"{context}.changedPaths[{path_index}]",
                maximum=4096,
            )
        if len(set(changed_paths)) != len(changed_paths):
            raise ValueError(f"{context}.changedPaths must be unique")
        if "durationSeconds" in item:
            _number(
                item["durationSeconds"],
                f"{context}.durationSeconds",
                minimum=0,
                maximum=86_400,
            )

        signals_passed = all(signal_results)
        if (outcome == "pass") != signals_passed:
            raise ValueError(f"{context}.outcome contradicts its signal results")
        if signals_passed:
            passed_count += 1

    if seen != set(expected_by_id):
        raise ValueError("Report task IDs do not match the suite")
    return passed_count / len(suite_tasks)


def _validate_power_or_energy(value: Any) -> None:
    context = "metrics.average_power_or_energy_if_available"
    if not isinstance(value, dict):
        raise ValueError(f"{context} must be a tagged object")
    kind = value.get("kind")
    if kind == "unavailable":
        _exact_keys(value, frozenset({"kind", "reason"}), context)
        _string(value["reason"], f"{context}.reason", maximum=512)
        return
    if kind not in {"average_power_watts", "energy_watt_hours"}:
        raise ValueError(f"{context}.kind is invalid")
    _exact_keys(value, frozenset({"kind", "value"}), context)
    _number(value["value"], f"{context}.value", minimum=0)


def _validate_temperature_range(value: Any) -> None:
    context = "metrics.device_temperature_start_end"
    if not isinstance(value, dict):
        raise ValueError(f"{context} must be an object")
    _exact_keys(value, frozenset({"unit", "start", "end"}), context)
    if value["unit"] != "celsius":
        raise ValueError(f"{context}.unit must be celsius")
    _number(value["start"], f"{context}.start", minimum=-100, maximum=200)
    _number(value["end"], f"{context}.end", minimum=-100, maximum=200)


def _validate_metrics(value: Any, task_success_rate: float) -> None:
    if not isinstance(value, dict):
        raise ValueError("metrics must be an object")
    _exact_keys(value, REQUIRED_METRICS, "metrics")
    for name in RATE_METRICS:
        _number(value[name], f"metrics.{name}", minimum=0, maximum=1)
    for name in COUNT_METRICS:
        _integer(value[name], f"metrics.{name}", minimum=0)
    for name in POSITIVE_METRICS:
        maximum = 100_000 if name == "tokens_per_second" else 86_400
        if name.startswith("peak_"):
            maximum = None
        _number(
            value[name],
            f"metrics.{name}",
            minimum=0,
            maximum=maximum,
            exclusive_minimum=True,
        )
    _number(
        value["battery_delta_percent"],
        "metrics.battery_delta_percent",
        minimum=0,
        maximum=100,
    )
    _validate_power_or_energy(value["average_power_or_energy_if_available"])
    _validate_temperature_range(value["device_temperature_start_end"])
    reported_success_rate = float(value["task_success_rate"])
    if not math.isclose(reported_success_rate, task_success_rate, abs_tol=1e-9):
        raise ValueError("metrics.task_success_rate does not match task outcomes")


def validate_report(value: dict[str, Any], suite: dict[str, Any]) -> None:
    validate_suite(suite)
    _exact_keys(value, REPORT_FIELDS, "report")
    if type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1:
        raise ValueError("Unsupported benchmark report schema")
    if value["suiteVersion"] != "suite-v1":
        raise ValueError("Unsupported benchmark report suite")
    _string(
        value["runId"],
        "runId",
        maximum=128,
        pattern=r"[a-z0-9][a-z0-9_-]{7,127}",
    )
    _string(
        value["deviceId"],
        "deviceId",
        maximum=64,
        pattern=r"[a-z0-9][a-z0-9_-]{2,63}",
    )
    _string(
        value["modelId"],
        "modelId",
        maximum=128,
        pattern=r"[a-z0-9][a-z0-9._-]{1,127}",
    )
    _string(
        value["modelSha256"],
        "modelSha256",
        maximum=64,
        pattern=r"[0-9a-f]{64}",
    )
    _string(value["piVersion"], "piVersion", maximum=128)
    _string(value["llamaCppVersion"], "llamaCppVersion", maximum=128)
    _integer(value["contextSize"], "contextSize", minimum=512, maximum=1_048_576)
    _validate_sampling_profile(value["samplingProfile"])
    task_success_rate = _validate_task_outcomes(value["tasks"], suite)
    _validate_metrics(value["metrics"], task_success_rate)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--suite", type=Path, default=Path("benchmarks/suite-v1/tasks.json")
    )
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    suite = load(args.suite)
    validate_suite(suite)
    if args.report is not None:
        validate_report(load(args.report), suite)
    print(f"{len(suite['tasks'])} benchmark tasks validated")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
