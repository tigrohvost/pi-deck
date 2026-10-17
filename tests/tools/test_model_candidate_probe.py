"""Check the boundary around generated code and streamed tool-call reconstruction."""
import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import adb_model_candidate_probe as probe


class CandidateProbeTest(unittest.TestCase):
    def test_fixture_paths_accept_relative_and_workspace_absolute_paths(self):
        for value in ["src/math_utils.py", "./src/math_utils.py", "/workspace/src/math_utils.py"]:
            self.assertEqual("src/math_utils.py", probe.fixture_path(value))
        for value in ["/etc/passwd", "../src/math_utils.py", "/workspace/../secret", None]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                probe.fixture_path(value)

    def test_fixture_distinguishes_the_original_bug_from_a_repair(self):
        self.assertFalse(probe.check_fixture(probe.SOURCE))
        self.assertTrue(probe.check_fixture(probe.SOURCE.replace(
            "min(lower, max(upper, value))", "max(lower, min(upper, value))")))
        self.assertTrue(probe.check_fixture(
            "def clamp(value, lower, upper):\n"
            "    if value < lower: return lower\n"
            "    if value > upper: return upper\n"
            "    return value\n"))
        self.assertTrue(probe.check_fixture(
            "def clamp(value, lower, upper):\n"
            "    return lower if value < lower else upper if value > upper else value\n"))

    def test_generated_code_cannot_escape_the_arithmetic_fixture(self):
        rejected = [
            "import os",
            "def clamp(value, lower, upper):\n    return __import__(value)",
            "def clamp(value, lower, upper):\n    return value.real",
            "def clamp(value, lower, upper):\n    while True: pass",
            "@print\ndef clamp(value, lower, upper):\n    return value",
            "def clamp(value, lower, upper=min(1,2)):\n    return value",
            "def clamp(value: print(), lower, upper):\n    return value",
            "def clamp(value, lower, upper):\n    return min(*value)",
        ]
        for source in rejected:
            with self.subTest(source=source), self.assertRaises((ValueError, SyntaxError)):
                probe.check_fixture(source)

    def test_stream_reassembles_tool_arguments_and_keeps_terminal_timings(self):
        events = [
            {"choices": [{"delta": {"reasoning_content": "Plan"}}]},
            {"choices": [{"delta": {"tool_calls": [{"index": 0, "id": "a", "function": {
                "name": "read", "arguments": '{"path":'}}]}}]},
            {"choices": [{"delta": {"tool_calls": [{"index": 0, "function": {
                "arguments": '"src/a.py"}'}}]}, "finish_reason": "tool_calls"}]},
            {"choices": [], "timings": {"predicted_n": 12}},
        ]
        stream = io.BytesIO(b"\n".join(b"data: " + json.dumps(x).encode() for x in events)
                            + b"\ndata: [DONE]\n")
        with patch.object(probe.urllib.request, "urlopen", return_value=stream):
            result = probe.request({"messages": []})
        self.assertEqual({"path": "src/a.py"}, json.loads(
            result["message"]["tool_calls"][0]["function"]["arguments"]))
        self.assertEqual("read", result["message"]["tool_calls"][0]["function"]["name"])
        self.assertEqual("Plan", result["message"]["reasoning_content"])
        self.assertEqual("tool_calls", result["finishReason"])
        self.assertEqual(12, result["timings"]["predicted_n"])
        self.assertIsNotNone(result["firstOutputSeconds"])
        self.assertIsNone(result["firstTextSeconds"])


if __name__ == "__main__":
    unittest.main()
