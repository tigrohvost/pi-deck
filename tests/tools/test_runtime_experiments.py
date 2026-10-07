"""Host tests for the pure parts of tools/adb_runtime_experiments.py."""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPOSITORY / "tools"))

import adb_runtime_experiments as experiments  # noqa: E402


class RuntimeExperimentsTest(unittest.TestCase):
    def test_server_arguments_mirror_the_catalog_and_app_affinity(self) -> None:
        model = experiments.catalog_model("lfm2.5-2.6b-qad")
        args = experiments.server_arguments(model, "/data/local/tmp/m.gguf")
        self.assertEqual(["-m", "/data/local/tmp/m.gguf"], args[:2])
        self.assertEqual(str(model["runtime"]["recommendedContext"]), args[args.index("-c") + 1])
        self.assertEqual("5", args[args.index("-t") + 1])
        self.assertEqual("8", args[args.index("-tb") + 1])
        self.assertEqual("3-7", args[args.index("-Cr") + 1])
        self.assertEqual("0-7", args[args.index("-Crb") + 1])
        self.assertIn("--jinja", args)
        # The catalog's own arguments, including --no-mmap and the prompt cache cap, follow.
        tail = model["runtime"]["serverArgs"]
        self.assertEqual(tail, args[len(args) - len(tail):])
        batch = experiments.server_arguments(model, "m", batch_threads=5, batch_cpus="3-7",
                                             extra=["--no-mmap"])
        self.assertEqual("5", batch[batch.index("-tb") + 1])
        self.assertEqual("3-7", batch[batch.index("-Crb") + 1])
        self.assertEqual("--no-mmap", batch[-1])

    def test_decimal_formatting_matches_java_side(self) -> None:
        self.assertEqual("0.1", experiments.decimal(0.1))
        self.assertEqual("1", experiments.decimal(1.0))
        self.assertEqual("0", experiments.decimal(0.0))

    def test_proc_status_separates_anonymous_and_file_memory(self) -> None:
        status = experiments.parse_proc_status(
            "Name:\tlibpideck\nVmHWM:\t 3399760 kB\nVmRSS:\t 3399664 kB\n"
            "RssAnon:\t 1852712 kB\nRssFile:\t 1546580 kB\nVmSwap:\t       0 kB\nThreads:\t22\n"
        )
        self.assertEqual(
            {"VmHWM": 3399760, "VmRSS": 3399664, "RssAnon": 1852712,
             "RssFile": 1546580, "VmSwap": 0},
            status,
        )

    def test_prompt_timings_and_answer_signature_ignore_random_call_ids(self) -> None:
        def response(call_id: str) -> dict:
            return {
                "timings": {"cache_n": 917, "prompt_n": 522, "prompt_ms": 8918.5,
                            "prompt_per_second": 58.5, "predicted_n": 64,
                            "predicted_per_second": 15.9},
                "choices": [{"message": {
                    "content": "", "reasoning_content": "plan",
                    "tool_calls": [{"id": call_id, "type": "function", "function": {
                        "name": "write", "arguments": "{\"path\":\"hello.py\"}"}}],
                }}],
            }

        first = experiments.prompt_timings(response("a1"))
        second = experiments.prompt_timings(response("b2"))
        self.assertEqual(917, first["cacheN"])
        self.assertEqual(522, first["promptN"])
        self.assertEqual("plan", first["reasoning"])
        self.assertEqual(experiments.answer_signature(first), experiments.answer_signature(second))
        changed = experiments.prompt_timings(response("a1"))
        changed["toolCalls"][0]["function"]["arguments"] = "{\"path\":\"other.py\"}"
        self.assertNotEqual(experiments.answer_signature(first), experiments.answer_signature(changed))

    def test_agent_prefix_is_built_from_the_shipped_prompt_parts(self) -> None:
        system = experiments.agent_system_prompt()
        base = (experiments.RUNTIME / "pideck-agent-base-prompt.md").read_text("utf-8").strip()
        self.assertTrue(system.startswith(base))
        self.assertIn(experiments.mobile_agent_guidance(), system)
        self.assertIn("<project_context>", system)
        self.assertTrue(system.endswith(f"Current working directory: {experiments.WORKSPACE}"))
        names = [tool["function"]["name"] for tool in experiments.agent_tools()]
        self.assertEqual(
            ["read", "code_nav", "bash", "write", "pideck_edit_text", "run_tests",
             "pideck_load_tools"],
            names,
        )
        json.dumps(experiments.agent_tools())

    def test_filler_is_deterministic_and_not_repetitive(self) -> None:
        text = experiments.filler_paragraphs(40)
        self.assertEqual(text, experiments.filler_paragraphs(40))
        lines = text.splitlines()
        self.assertEqual(40, len(lines))
        self.assertGreater(len(set(line.split(". ", 1)[1] for line in lines)), 1)

    def test_system_prefix_stops_at_the_user_turn_boundary(self) -> None:
        head = "<|startoftext|><|im_start|>system\nRules and tools<|im_end|>\n"
        first = head + "<|im_start|>user\nпервый вопрос<|im_end|>\n<|im_start|>assistant\n"
        second = head + "<|im_start|>user\nсовсем другой текст<|im_end|>\n<|im_start|>assistant\n"
        self.assertEqual(head, experiments.system_prefix(first, second))
        with self.assertRaises(experiments.ExperimentError):
            experiments.system_prefix("plain text a", "plain text b")

    def test_shell_quoting_keeps_plain_arguments_and_quotes_the_rest(self) -> None:
        self.assertEqual("-Crb", experiments._shell_quote("-Crb"))
        self.assertEqual("'a b'", experiments._shell_quote("a b"))
        self.assertEqual("'it'\\''s'", experiments._shell_quote("it's"))


if __name__ == "__main__":
    unittest.main()
