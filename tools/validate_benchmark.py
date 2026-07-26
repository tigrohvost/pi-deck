#!/usr/bin/env python3
"""Validate the static suite contract and machine-readable run reports."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

REQUIRED_METRICS = {
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


def load(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain an object")
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
        if not isinstance(task.get("expectedSignals"), list):
            raise ValueError(f"Missing expected signals in {task_id}")


def validate_report(value: dict[str, Any], suite: dict[str, Any]) -> None:
    if value.get("schemaVersion") != 1 or value.get("suiteVersion") != "suite-v1":
        raise ValueError("Unsupported benchmark report")
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{2,63}", str(value.get("deviceId", ""))):
        raise ValueError("Device ID must be pseudonymous")
    if not re.fullmatch(r"[0-9a-f]{64}", str(value.get("modelSha256", ""))):
        raise ValueError("Model SHA-256 is invalid")
    outcomes = value.get("tasks")
    if not isinstance(outcomes, list) or len(outcomes) != len(suite["tasks"]):
        raise ValueError("Report must contain exactly one outcome per suite task")
    outcome_ids = {item.get("id") for item in outcomes if isinstance(item, dict)}
    suite_ids = {item["id"] for item in suite["tasks"]}
    if outcome_ids != suite_ids:
        raise ValueError("Report task IDs do not match the suite")
    metrics = value.get("metrics")
    if not isinstance(metrics, dict) or not REQUIRED_METRICS.issubset(metrics):
        raise ValueError("Report misses required benchmark metrics")
    if any(value is None for key, value in metrics.items() if key in REQUIRED_METRICS):
        raise ValueError("Committed report may not contain null required metrics")


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
