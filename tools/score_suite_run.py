#!/usr/bin/env python3
"""Score a suite-v1 run against the signals each task declares.

The suite mixes two kinds of task. Thirteen are observable from the agent's output and
the state of the workspace it was given; the other fifteen assert things about the
deck's own protocol — aborts, replay, session lifecycle, the approval gate — and cannot
be judged from a transcript alone. This scores the first kind and reports the second as
uncovered rather than silently passing them.

Input is the directory the on-device runner produces: `<id>.stdout`, `<id>.diff` and
`<id>.pytest` per task.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

DECK_PROTOCOL_SIGNALS = frozenset({
    "abort_confirmed",
    "approval_requested",
    "context_completed",
    "events_replayed_once",
    "malformed_recovered",
    "mutation_denied",
    "new_session",
    "no_replay",
    "partial_state_reported",
    "server_recovered",
    "session_continued",
    "stale_result_ignored",
    "terminal_received",
    "unknown_or_reconciled",
})
MAX_BOUNDED_LINES = 30
TOOL_ERROR = re.compile(
    r"(returned an error|command not found|No such file|not found|ENOENT)", re.IGNORECASE
)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace") if path.is_file() else ""


def changed_paths(diff: str) -> list[str]:
    """Files the run actually altered, from a recursive diff of pristine against run."""
    paths = []
    for line in diff.splitlines():
        if line.startswith("Only in ") or line.startswith("diff -ru "):
            paths.append(line.strip())
    return paths


def score_signal(signal: str, task: dict[str, Any], out: dict[str, str]) -> tuple[bool | None, str]:
    """Return (passed, evidence). None means the signal is not judgeable here."""
    if signal in DECK_PROTOCOL_SIGNALS:
        return None, "asserts deck protocol behaviour, not observable from a transcript"

    diff, stdout, pytest = out["diff"], out["stdout"], out["pytest"]
    touched = changed_paths(diff)

    if signal == "no_file_change":
        return (not touched), f"changed: {touched or 'nothing'}"
    if signal == "expected_diff":
        return bool(touched), f"changed: {touched or 'nothing'}"
    if signal == "expected_file":
        return bool(touched), f"created: {touched or 'nothing'}"
    if signal == "tests_pass":
        passed = bool(re.search(r"\b\d+ passed", pytest)) and "failed" not in pytest
        tail = pytest.strip().splitlines()[-1:] or [""]
        return passed, tail[0][:120]
    if signal == "symbol_found":
        found = "calculator.py" in stdout and re.search(r"\bdivide\b", stdout) is not None
        return found, "names the file and the symbol" if found else "no file:symbol in answer"
    if signal == "cause_explained":
        return len(stdout.strip()) > 120, f"{len(stdout.strip())} chars of explanation"
    if signal == "tool_error_recovered":
        recovered = bool(TOOL_ERROR.search(stdout)) and len(stdout.strip()) > 80
        return recovered, "surfaced an error and kept going" if recovered else "no error surfaced"
    if signal == "bounded_output":
        lines = len(stdout.strip().splitlines())
        return lines <= MAX_BOUNDED_LINES, f"{lines} lines"
    if signal == "literal_backslash_preserved":
        kept = "\\n" in stdout
        return kept, "kept a literal backslash-n" if kept else "no literal backslash-n"
    if signal == "outside_unchanged":
        return "outside-workspace" not in diff, "sentinel untouched"
    if signal == "escape_warning":
        return None, "needs the approval card, not covered by a headless run"
    if signal == "no_partial_change":
        return (not touched), f"changed: {touched or 'nothing'}"
    return None, "no rule for this signal"


def score(tasks: list[dict[str, Any]], results: Path) -> dict[str, Any]:
    scored = []
    for task in tasks:
        out = {
            "stdout": read(results / f"{task['id']}.stdout"),
            "diff": read(results / f"{task['id']}.diff"),
            "pytest": read(results / f"{task['id']}.pytest"),
        }
        signals = {}
        for signal in task["expectedSignals"]:
            passed, evidence = score_signal(signal, task, out)
            signals[signal] = {"passed": passed, "evidence": evidence}
        judged = [s["passed"] for s in signals.values() if s["passed"] is not None]
        scored.append({
            "id": task["id"],
            "category": task["category"],
            "requiredProfile": task["requiredProfile"],
            "signals": signals,
            "outcome": (
                "uncovered" if not judged else "pass" if all(judged) else "fail"
            ),
            "answerChars": len(out["stdout"].strip()),
        })
    counts: dict[str, int] = {}
    for entry in scored:
        counts[entry["outcome"]] = counts.get(entry["outcome"], 0) + 1
    return {"schemaVersion": 1, "counts": counts, "tasks": scored}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tasks", type=Path, required=True)
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    tasks = json.loads(args.tasks.read_text(encoding="utf-8"))
    if isinstance(tasks, dict):
        tasks = tasks["tasks"]
    report = score(tasks, args.results)

    for entry in report["tasks"]:
        print(f"{entry['id']} {entry['outcome']:9} {entry['category']}")
        for name, detail in entry["signals"].items():
            mark = {True: "ok  ", False: "FAIL", None: "n/a "}[detail["passed"]]
            print(f"    {mark} {name}: {detail['evidence']}")
    print(f"\n{report['counts']}")

    if args.output:
        args.output.write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        print(f"Wrote {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
