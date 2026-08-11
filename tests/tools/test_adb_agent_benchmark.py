"""Host-only contract tests for the end-to-end ADB agent benchmark."""

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
TOOL_PATH = REPOSITORY / "tools" / "adb_agent_benchmark.py"
SPEC = importlib.util.spec_from_file_location("adb_agent_benchmark", TOOL_PATH)
assert SPEC is not None and SPEC.loader is not None
benchmark = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(benchmark)


class AdbAgentBenchmarkTests(unittest.TestCase):
    def test_resolve_adb_serial_requires_one_ready_device(self) -> None:
        self.assertEqual(
            "R5C123",
            benchmark.resolve_adb_serial(
                "List of devices attached\nR5C123\tdevice product:test\n"
            ),
        )
        with self.assertRaises(benchmark.BenchmarkError):
            benchmark.resolve_adb_serial(
                "List of devices attached\nA\tdevice\nB\tdevice\n"
            )
        with self.assertRaises(benchmark.BenchmarkError):
            benchmark.resolve_adb_serial(
                "List of devices attached\nR5C123\tunauthorized\n", "R5C123"
            )

    def test_bridge_token_file_must_be_private_and_canonical(self) -> None:
        with tempfile.TemporaryDirectory(prefix="pideck-benchmark-token-") as directory:
            path = Path(directory) / "token"
            path.write_text("A" * 43, encoding="ascii")
            path.chmod(0o600)
            self.assertEqual("A" * 43, benchmark.read_bridge_token(path))
            path.chmod(0o644)
            with self.assertRaises(benchmark.BenchmarkError):
                benchmark.read_bridge_token(path)

    def test_tool_args_accept_bridge_json_string_but_fail_closed(self) -> None:
        self.assertEqual(
            {"path": "/workspace/tests/test_counter.py"},
            benchmark.normalized_tool_args(
                '{"path":"/workspace/tests/test_counter.py"}'
            ),
        )
        self.assertEqual({"path": "tests"}, benchmark.normalized_tool_args({"path": "tests"}))
        self.assertEqual({}, benchmark.normalized_tool_args("[1, 2]"))
        self.assertEqual({}, benchmark.normalized_tool_args("not-json"))

        self.assertEqual(
            {"path": "/workspace/tests/test_counter.py", "status": 0},
            benchmark.normalized_tool_result_details(
                '{"content":[],"details":{"path":"/workspace/tests/test_counter.py","status":0}}'
            ),
        )
        self.assertEqual({}, benchmark.normalized_tool_result_details("not-json"))

    def test_battery_and_thermal_tags_are_typed(self) -> None:
        battery = benchmark.parse_battery(
            """
            AC powered: false
            USB powered: true
            Wireless powered: false
            level: 83
            voltage: 4210
            temperature: 347
            """
        )
        self.assertEqual(83, battery["batteryLevelPercent"])
        self.assertEqual(34.7, battery["batteryTemperatureC"])
        self.assertEqual(4.21, battery["batteryVoltageV"])
        self.assertTrue(battery["usbPowered"])

        thermal = benchmark.parse_thermal_sysfs(
            """
            big_scaling=1593600
            big_nominal=3360000
            power_current_now=-1250000
            power_voltage_now=4200000
            power_charge_counter=2500000
            zone=cpu-0-0-user|72800
            zone=battery|35100
            """
        )
        self.assertEqual(0.474, thermal["bigCoreHeadroom"])
        self.assertEqual(72.8, thermal["hottestCpuC"])
        self.assertEqual(-1.25, thermal["batteryCurrentA"])
        self.assertEqual(4.2, thermal["batteryVoltageSysfsV"])
        self.assertEqual(3, benchmark.parse_thermal_status("Thermal Status: 3"))

    def test_native_log_timings_aggregate_multiple_provider_rounds(self) -> None:
        parsed = benchmark.parse_server_timings(
            """
            prompt eval time = 1000.00 ms / 100 tokens (10 ms per token, 100 tokens per second)
            eval time = 500.00 ms / 10 runs (50 ms per token, 20 tokens per second)
            prompt eval time = 2000.00 ms / 300 tokens (6 ms per token, 150 tokens per second)
            eval time = 1000.00 ms / 25 tokens (40 ms per token, 25 tokens per second)
            """
        )
        assert parsed is not None
        self.assertEqual(2, parsed["providerRequests"])
        self.assertEqual(400, parsed["prefill"]["tokens"])
        self.assertEqual(3.0, parsed["prefill"]["seconds"])
        self.assertEqual(35, parsed["decode"]["tokens"])
        self.assertEqual(1.5, parsed["decode"]["seconds"])

    def test_native_log_delta_fails_closed_when_tail_boundary_is_lost(self) -> None:
        self.assertEqual("new", benchmark.native_log_delta("old", "oldnew"))
        self.assertEqual("all", benchmark.native_log_delta("", "all"))
        self.assertIsNone(benchmark.native_log_delta(None, "historical"))
        self.assertIsNone(benchmark.native_log_delta("shifted-window", "window-new"))

    def test_turn_metrics_separate_ttft_tools_retries_and_decode(self) -> None:
        events = [
            event(1, "TURN_STARTED", "2026-08-09T10:00:00Z"),
            event(2, "MODEL_OUTPUT_DELTA", "2026-08-09T10:00:02Z"),
            event(
                3,
                "TOOL_CALL_REQUESTED",
                "2026-08-09T10:00:03Z",
                {"toolCallId": "tool-1", "toolName": "read"},
            ),
            event(
                4,
                "TOOL_CALL_STARTED",
                "2026-08-09T10:00:04Z",
                {"toolCallId": "tool-1", "toolName": "read"},
            ),
            event(
                5,
                "TOOL_CALL_COMPLETED",
                "2026-08-09T10:00:06Z",
                {"toolCallId": "tool-1", "toolName": "read", "isError": False},
            ),
            event(
                6,
                "MODEL_OUTPUT_REJECTED",
                "2026-08-09T10:00:07Z",
                {"reason": "single_letter", "willRetry": True},
            ),
            event(7, "TURN_STARTED", "2026-08-09T10:00:08Z"),
            event(
                8,
                "TURN_COMPLETED",
                "2026-08-09T10:00:10Z",
                {
                    "outputTokens": 20,
                    "decodeDurationMs": 1000,
                    "tokensPerSecond": 20.0,
                    "speedEstimated": False,
                },
            ),
        ]
        receipts = {sequence: 10.0 + sequence for sequence in range(1, 9)}
        metrics = benchmark.derive_turn_metrics(
            events,
            receipts,
            dispatch_started=10.0,
            terminal_received=20.0,
            context_tokens_before=0,
            context_tokens_after=150,
            native_timings=None,
        )
        self.assertEqual(2.0, metrics["dispatchToFirstVisibleTokenSeconds"])
        self.assertEqual("model_output_delta_receipt", metrics["ttftSource"])
        self.assertEqual(3.0, metrics["dispatchToFirstToolCallSeconds"])
        self.assertEqual(10.0, metrics["totalTurnSeconds"])
        self.assertEqual(2.0, metrics["tools"]["executionSeconds"])
        self.assertEqual(4.0, metrics["tools"]["postToolToTerminalSeconds"])
        self.assertEqual(1, metrics["retries"]["rejections"])
        self.assertEqual([1.0], metrics["retries"]["dispatchDelaySeconds"])
        self.assertEqual(3.0, metrics["retries"]["firstRejectionToTerminalSeconds"])
        self.assertEqual(20.0, metrics["provider"]["decodeTokensPerSecond"])
        self.assertEqual(
            "estimated_context_delta_over_visible_ttft",
            metrics["provider"]["prefill"]["source"],
        )
        self.assertEqual(65.0, metrics["provider"]["prefill"]["tokensPerSecond"])

    def test_failed_turn_does_not_invent_a_visible_token(self) -> None:
        metrics = benchmark.derive_turn_metrics(
            [event(1, "TURN_FAILED", "2026-08-09T10:00:01Z", {"error": "boom"})],
            {1: 11.0},
            dispatch_started=10.0,
            terminal_received=11.0,
            context_tokens_before=None,
            context_tokens_after=None,
            native_timings=None,
        )
        self.assertIsNone(metrics["dispatchToFirstVisibleTokenSeconds"])
        self.assertEqual("unavailable", metrics["ttftSource"])

    def test_environment_summary_marks_throttling_and_power_method(self) -> None:
        summary = benchmark.summarize_environment(
            [
                {
                    "bigCoreHeadroom": 1.0,
                    "hottestCpuC": 45.0,
                    "batteryCurrentA": -1.0,
                    "batteryVoltageSysfsV": 4.0,
                    "batteryLevelPercent": 80,
                },
                {
                    "bigCoreHeadroom": 0.7,
                    "hottestCpuC": 70.0,
                    "batteryCurrentA": -2.0,
                    "batteryVoltageSysfsV": 4.0,
                    "batteryLevelPercent": 79,
                },
            ]
        )
        self.assertEqual(1, summary["thermalThrottlingEvents"])
        self.assertEqual(0.7, summary["minimumBigCoreHeadroom"])
        self.assertEqual(70.0, summary["maximumCpuTemperatureC"])
        self.assertEqual(1.0, summary["batteryDeltaPercent"])
        self.assertEqual("instantaneous_sysfs_average_watts", summary["power"]["kind"])
        self.assertEqual(6.0, summary["power"]["value"])


def event(
    sequence: int,
    event_type: str,
    timestamp: str,
    payload: dict | None = None,
) -> dict:
    return {
        "schemaVersion": 1,
        "sequence": sequence,
        "bridgeInstanceId": "fixture",
        "operationId": "operation",
        "sessionId": "session",
        "type": event_type,
        "timestamp": timestamp,
        "payload": payload or {},
    }


if __name__ == "__main__":
    unittest.main()
