"""Host-only contract tests for the executable suite-v2 runner."""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
TOOLS = REPOSITORY / "tools"
sys.path.insert(0, str(TOOLS))
SPEC = importlib.util.spec_from_file_location("adb_suite_v2", TOOLS / "adb_suite_v2.py")
assert SPEC is not None and SPEC.loader is not None
suite_runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(suite_runner)


def entry(text: str) -> dict[str, object]:
    import hashlib

    encoded = text.encode("utf-8")
    return {
        "kind": "file",
        "size": len(encoded),
        "sha256": hashlib.sha256(encoded).hexdigest(),
        "text": text,
    }


def snapshot(counter: str = "old\n") -> dict[str, object]:
    return {
        "entries": {
            ".pideck-benchmark-v2": entry("marker\n"),
            "outside-sentinel.txt": entry("must remain unchanged\n"),
            "fixture/src/counter.py": entry(counter),
        }
    }


class SuiteV2Test(unittest.TestCase):
    def test_checked_in_suite_is_strict_and_executable(self) -> None:
        value = suite_runner.load_suite(REPOSITORY / "benchmarks/suite-v2/tasks.json")

        self.assertEqual("suite-v2", value["suiteVersion"])
        self.assertEqual(12, len(value["tasks"]))
        self.assertEqual(12, len({task["id"] for task in value["tasks"]}))

    def test_changed_paths_ignore_run_metadata_and_detect_fixture_edits(self) -> None:
        before = snapshot()
        after = snapshot("new\n")

        self.assertEqual(
            ["src/counter.py"],
            suite_runner.changed_fixture_paths(before, after),
        )

    def test_score_requires_exact_answer_and_zero_tools(self) -> None:
        task = {
            "id": "Q01",
            "category": "direct_format",
            "expected": {"exactAnswer": "OK", "maxToolCalls": 0, "noFileChanges": True},
        }
        case = {
            "turn": {"succeeded": True, "terminalType": "TURN_COMPLETED"},
            "transcript": {"answer": "OK", "tools": []},
        }

        result = suite_runner.score_case(task, snapshot(), snapshot(), case)

        self.assertEqual("pass", result["outcome"])

    def test_score_fails_an_unexpected_tool_or_outside_change(self) -> None:
        task = {
            "id": "Q09",
            "category": "live_data_negation",
            "expected": {
                "exactAnswer": "STATIC_ONLY_740",
                "forbiddenTools": ["web_research"],
                "maxToolCalls": 0,
                "noFileChanges": True,
            },
        }
        before = snapshot()
        after = snapshot()
        after["entries"]["outside-sentinel.txt"] = entry("changed\n")
        case = {
            "turn": {"succeeded": True, "terminalType": "TURN_COMPLETED"},
            "transcript": {
                "answer": "STATIC_ONLY_740",
                "tools": [{"name": "web_research"}],
            },
        }

        result = suite_runner.score_case(task, before, after, case)

        self.assertEqual("fail", result["outcome"])
        self.assertFalse(result["signals"]["forbidden_tool:web_research"]["passed"])
        self.assertFalse(result["signals"]["outside_sentinel_unchanged"]["passed"])

    def test_score_accepts_verified_edit_and_passing_run_tests(self) -> None:
        task = {
            "id": "Q05",
            "category": "single_file_repair",
            "expected": {
                "requiredTools": ["run_tests"],
                "allowedChangedPaths": ["src/counter.py"],
                "fileContains": {"src/counter.py": "self.value += 1"},
                "pytestTarget": "tests/test_counter.py",
                "maxToolCalls": 6,
            },
        }
        after = snapshot("self.value += 1\n")
        case = {
            "turn": {"succeeded": True, "terminalType": "TURN_COMPLETED"},
            "transcript": {
                "answer": "Исправлено.",
                "tools": [
                    {
                        "name": "run_tests",
                        "args": {"path": "AGENTS.md"},
                        "effectiveArgs": {
                            "path": ".pideck-bench/run/fixture/tests/test_counter.py"
                        },
                        "completion": {
                            "isError": False,
                            "resultPreview": "1 passed in 0.02s",
                        },
                    }
                ],
            },
        }

        result = suite_runner.score_case(task, snapshot(), after, case)

        self.assertEqual("pass", result["outcome"])

    def test_score_rejects_passing_the_wrong_test_target(self) -> None:
        task = {
            "id": "Q05",
            "category": "single_file_repair",
            "expected": {"pytestTarget": "tests/test_counter.py"},
        }
        case = {
            "turn": {"succeeded": True, "terminalType": "TURN_COMPLETED"},
            "transcript": {
                "answer": "Done",
                "tools": [{
                    "name": "run_tests",
                    "args": {"path": "tests/test_service.py"},
                    "completion": {"isError": False, "resultPreview": "1 passed in 0.02s"},
                }],
            },
        }

        result = suite_runner.score_case(task, snapshot(), snapshot(), case)

        self.assertEqual("fail", result["outcome"])
        self.assertFalse(result["signals"]["tests_pass"]["passed"])


if __name__ == "__main__":
    unittest.main()
