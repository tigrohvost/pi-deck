from __future__ import annotations

import importlib.util
import base64
import copy
import hashlib
import json
import struct
import tempfile
import unittest
from pathlib import Path
from unittest import mock


REPOSITORY = Path(__file__).resolve().parents[2]


def load_tool(name: str):
    path = REPOSITORY / "tools" / f"{name}.py"
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


pin_model = load_tool("pin_model")
validate_benchmark = load_tool("validate_benchmark")
generate_sbom = load_tool("generate_sbom")
speculative_probe = load_tool("speculative_probe")
accelerator_probe = load_tool("adb_accelerator_probe")


def gguf_string(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return struct.pack("<Q", len(encoded)) + encoded


def benchmark_suite() -> dict:
    path = REPOSITORY / "benchmarks" / "suite-v1" / "tasks.json"
    return json.loads(path.read_text(encoding="utf-8"))


def valid_benchmark_report() -> dict:
    suite = benchmark_suite()
    outcomes = []
    for task in suite["tasks"]:
        outcomes.append(
            {
                "id": task["id"],
                "outcome": "pass",
                "signals": {
                    signal: {"passed": True, "evidence": "verified on device"}
                    for signal in task["expectedSignals"]
                },
                "changedPaths": [],
                "durationSeconds": 12.5,
            }
        )
    return {
        "schemaVersion": 1,
        "suiteVersion": "suite-v1",
        "runId": "run_20260809",
        "deviceId": "device_001",
        "modelId": "qwen3.5-2b",
        "modelSha256": "a" * 64,
        "piVersion": "0.82.1",
        "llamaCppVersion": "b10092",
        "contextSize": 10_240,
        "samplingProfile": {
            "temperature": 0.7,
            "topP": 0.8,
            "topK": 20,
            "minP": 0.0,
            "presencePenalty": 1.5,
            "maxTokens": 1_536,
        },
        "tasks": outcomes,
        "metrics": {
            "task_success_rate": 1.0,
            "invalid_tool_call_rate": 0.0,
            "unintended_file_change_count": 0,
            "outside_workspace_change_count": 0,
            "session_recovery_rate": 1.0,
            "abort_success_rate": 1.0,
            "cold_start_seconds": 20.64,
            "time_to_first_token_seconds": 1.25,
            "time_to_first_tool_call_seconds": 2.5,
            "tokens_per_second": 16.13,
            "peak_server_rss_mib": 3_031.0,
            "peak_total_termux_rss_mib": 512.0,
            "server_crash_count": 0,
            "oom_count": 0,
            "battery_delta_percent": 2.0,
            "average_power_or_energy_if_available": {
                "kind": "unavailable",
                "reason": "device energy counter unavailable",
            },
            "thermal_throttling_events": 0,
            "device_temperature_start_end": {
                "unit": "celsius",
                "start": 42.5,
                "end": 49.0,
            },
        },
    }


class ToolTests(unittest.TestCase):
    def test_gguf_header_and_metadata_are_read_without_execution(self) -> None:
        payload = (
            b"GGUF"
            + struct.pack("<IQQ", 3, 0, 1)
            + gguf_string("general.name")
            + struct.pack("<I", 8)
            + gguf_string("Fixture model")
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.gguf"
            path.write_bytes(payload)
            result = pin_model.inspect_gguf(path)
        self.assertEqual("GGUF", result["magic"])
        self.assertEqual(3, result["version"])
        self.assertEqual("Fixture model", result["metadata"]["general.name"])

    def test_non_gguf_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad.gguf"
            path.write_bytes(b"<html>not a model</html>")
            with self.assertRaises(pin_model.PinError):
                pin_model.inspect_gguf(path)

    def test_benchmark_contract_has_twenty_eight_unique_tasks(self) -> None:
        value = benchmark_suite()
        validate_benchmark.validate_suite(value)
        self.assertEqual(28, len(value["tasks"]))
        self.assertEqual(28, len({task["id"] for task in value["tasks"]}))
        self.assertTrue(
            (REPOSITORY / "benchmarks" / "outside-workspace" / "sentinel.txt").is_file()
        )
        self.assertFalse(
            (
                REPOSITORY
                / "benchmarks"
                / "fixture"
                / "outside-workspace"
                / "sentinel.txt"
            ).is_file()
        )

    def test_benchmark_report_accepts_complete_typed_outcomes_and_metrics(self) -> None:
        validate_benchmark.validate_report(
            valid_benchmark_report(), benchmark_suite()
        )

    def test_benchmark_report_rejects_strings_for_every_metric(self) -> None:
        suite = benchmark_suite()
        report = valid_benchmark_report()
        for metric in validate_benchmark.REQUIRED_METRICS:
            with self.subTest(metric=metric):
                malformed = copy.deepcopy(report)
                malformed["metrics"][metric] = "fast"
                with self.assertRaises(ValueError):
                    validate_benchmark.validate_report(malformed, suite)

    def test_benchmark_report_rejects_empty_task_outcome(self) -> None:
        report = valid_benchmark_report()
        report["tasks"][0] = {"id": report["tasks"][0]["id"]}

        with self.assertRaises(ValueError):
            validate_benchmark.validate_report(report, benchmark_suite())

    def test_benchmark_report_requires_exact_signals_and_consistent_outcome(self) -> None:
        suite = benchmark_suite()
        missing_signal = valid_benchmark_report()
        missing_signal["tasks"][0]["signals"] = {}
        with self.assertRaises(ValueError):
            validate_benchmark.validate_report(missing_signal, suite)

        contradictory = valid_benchmark_report()
        first_signal = next(iter(contradictory["tasks"][0]["signals"].values()))
        first_signal["passed"] = False
        with self.assertRaises(ValueError):
            validate_benchmark.validate_report(contradictory, suite)

    def test_benchmark_report_success_rate_must_match_task_outcomes(self) -> None:
        report = valid_benchmark_report()
        report["metrics"]["task_success_rate"] = 0.5

        with self.assertRaises(ValueError):
            validate_benchmark.validate_report(report, benchmark_suite())

    def test_speculative_variants_map_to_exact_server_flags(self) -> None:
        self.assertEqual([], speculative_probe.variant_arguments("baseline"))
        self.assertEqual(
            ["--spec-type", "draft-mtp", "--spec-draft-n-max", "4"],
            speculative_probe.variant_arguments("draft-mtp:4"),
        )
        self.assertEqual(
            ["--spec-type", "ngram-mod", "--spec-ngram-mod-n-max", "16"],
            speculative_probe.variant_arguments("ngram-mod:16"),
        )
        with self.assertRaises(ValueError):
            speculative_probe.variant_arguments("draft-eagle3:4")
        with self.assertRaises(ValueError):
            speculative_probe.variant_arguments("draft-mtp:0")

    def test_each_sample_gets_a_distinct_prompt(self) -> None:
        # ngram-mod keeps an n-gram pool that outlives a single request, so repeating one
        # prompt lets sample N draft from sample N-1's answer and inflates its measured
        # speedup. Samples must cycle through distinct prompts.
        prompts = ["alpha", "beta", "gamma"]
        self.assertEqual("alpha", speculative_probe.prompt_for_sample(prompts, 0))
        self.assertEqual("beta", speculative_probe.prompt_for_sample(prompts, 1))
        self.assertEqual("gamma", speculative_probe.prompt_for_sample(prompts, 2))
        self.assertEqual("alpha", speculative_probe.prompt_for_sample(prompts, 3))
        with self.assertRaises(ValueError):
            speculative_probe.prompt_for_sample([], 0)

    def test_thermal_headroom_is_a_ratio_of_nominal_clock(self) -> None:
        # A throttled phone reports a reduced scaling_max_freq against an unchanged
        # cpuinfo_max_freq, so the ratio is what says whether a sample is comparable.
        self.assertEqual(1.0, speculative_probe.thermal_headroom(3_360_000, 3_360_000))
        self.assertEqual(0.47, round(speculative_probe.thermal_headroom(1_593_600, 3_360_000), 2))
        self.assertEqual(0.0, speculative_probe.thermal_headroom(0, 3_360_000))
        with self.assertRaises(ValueError):
            speculative_probe.thermal_headroom(1_000, 0)

    def test_warm_up_sample_is_discarded_before_summarising(self) -> None:
        # The first call after a load faults mmapped weights in from flash and measures
        # storage, not the model. See docs/model-throughput-survey.md.
        summary = speculative_probe.summarise([1.5, 20.0, 18.0, 22.0])
        self.assertEqual(3, summary["samples"])
        self.assertEqual(20.0, summary["medianTokensPerSecond"])
        self.assertEqual(1.5, summary["discardedWarmUp"])
        with self.assertRaises(ValueError):
            speculative_probe.summarise([1.5])

    def test_accelerator_cpu_control_physically_disables_gpu_work(self) -> None:
        self.assertEqual(
            ["-dev", "none", "-ngl", "0", "-nopo", "1", "-nkvo", "1"],
            accelerator_probe.variant_arguments("cpu"),
        )
        self.assertEqual(
            ["-ngl", "16", "-sm", "layer", "-nopo", "0", "-nkvo", "0"],
            accelerator_probe.variant_arguments("hybrid-16"),
        )
        self.assertEqual(
            ["-ngl", "99", "-sm", "layer", "-nopo", "0", "-nkvo", "1"],
            accelerator_probe.variant_arguments("accelerator-all-cpu-state"),
        )
        with self.assertRaises(ValueError):
            accelerator_probe.variant_arguments("magic-parallel")

    def test_accelerator_probe_parses_exact_prompt_and_decode_rows(self) -> None:
        raw = "\n".join(
            [
                "backend startup noise",
                json.dumps(
                    {
                        "n_prompt": 128,
                        "n_gen": 0,
                        "avg_ts": 42.5,
                        "backend": "Vulkan",
                        "build_number": 10333,
                    }
                ),
                json.dumps(
                    {
                        "n_prompt": 0,
                        "n_gen": 32,
                        "avg_ts": 11.25,
                        "backend": "Vulkan",
                    }
                ),
            ]
        )
        rows = accelerator_probe.parse_bench_jsonl(raw)
        prompt, decode, metadata = accelerator_probe.workload_rates(rows, 128, 32)
        self.assertEqual(42.5, prompt)
        self.assertEqual(11.25, decode)
        self.assertEqual("Vulkan", metadata["backend"])
        with self.assertRaises(accelerator_probe.ProbeError):
            accelerator_probe.workload_rates(rows, 512, 32)

    def test_accelerator_gate_requires_every_prompt_size_and_decode(self) -> None:
        samples = []
        for prompt, cpu_pp, cpu_tg in ((128, 20.0, 10.0), (512, 25.0, 8.0)):
            samples.extend(
                [
                    {
                        "variant": "cpu",
                        "promptTokens": prompt,
                        "promptTokensPerSecond": cpu_pp,
                        "decodeTokensPerSecond": cpu_tg,
                    },
                    {
                        "variant": "vulkan-good",
                        "promptTokens": prompt,
                        "promptTokensPerSecond": cpu_pp * 2.1,
                        "decodeTokensPerSecond": cpu_tg * 0.97,
                    },
                    {
                        "variant": "vulkan-prefill-only",
                        "promptTokens": prompt,
                        "promptTokensPerSecond": cpu_pp * 2.5,
                        "decodeTokensPerSecond": cpu_tg * 0.8,
                    },
                ]
            )
        verdict = accelerator_probe.score_samples(samples, [128, 512], 2.0, 0.95)
        self.assertTrue(verdict["gatePassed"])
        self.assertEqual("vulkan-good", verdict["winner"])
        self.assertTrue(verdict["variants"]["vulkan-good"]["gatePassed"])
        self.assertTrue(
            verdict["variants"]["vulkan-prefill-only"]["prefillOnlyPotential"]
        )
        self.assertFalse(
            verdict["variants"]["vulkan-prefill-only"]["gatePassed"]
        )

    def test_accelerator_gate_fails_closed_on_missing_workload(self) -> None:
        samples = [
            {
                "variant": "cpu",
                "promptTokens": prompt,
                "promptTokensPerSecond": 10.0,
                "decodeTokensPerSecond": 10.0,
            }
            for prompt in (128, 512)
        ]
        samples.append(
            {
                "variant": "partial",
                "promptTokens": 128,
                "promptTokensPerSecond": 30.0,
                "decodeTokensPerSecond": 10.0,
            }
        )
        verdict = accelerator_probe.score_samples(samples, [128, 512], 2.0, 0.95)
        self.assertFalse(verdict["gatePassed"])
        self.assertFalse(verdict["variants"]["partial"]["gatePassed"])

    def test_accelerator_gate_rejects_duplicate_workloads(self) -> None:
        duplicate = {
            "variant": "cpu",
            "promptTokens": 128,
            "promptTokensPerSecond": 10.0,
            "decodeTokensPerSecond": 10.0,
        }
        with self.assertRaises(accelerator_probe.ProbeError):
            accelerator_probe.score_samples(
                [duplicate, copy.deepcopy(duplicate)], [128], 2.0, 0.95
            )

    def test_accelerator_probe_verifies_staged_candidate_hashes(self) -> None:
        manifest = {
            "artifacts": [
                {"name": "llama-bench", "sha256": "a" * 64},
                {"name": "libomp.so", "sha256": "b" * 64},
            ]
        }
        completed = mock.Mock(
            stdout=f"{'a' * 64}  llama-bench\n{'b' * 64}  libomp.so\n"
        )
        with mock.patch.object(
            accelerator_probe, "remote_exec", return_value=completed
        ) as remote:
            actual = accelerator_probe.verify_staged_candidate(None, manifest)
        self.assertEqual(
            {"llama-bench": "a" * 64, "libomp.so": "b" * 64}, actual
        )
        remote.assert_called_once_with(
            None,
            [
                "/system/bin/toybox",
                "sha256sum",
                "llama-bench",
                "libomp.so",
            ],
            timeout=300,
        )

        completed.stdout = f"{'c' * 64}  llama-bench\n"
        with mock.patch.object(
            accelerator_probe, "remote_exec", return_value=completed
        ):
            with self.assertRaises(accelerator_probe.ProbeError):
                accelerator_probe.verify_staged_candidate(None, manifest)

    def test_sbom_converts_npm_integrity_to_cyclonedx_hex(self) -> None:
        digest = bytes(range(64))
        integrity = "sha512-" + base64.b64encode(digest).decode("ascii")
        result = generate_sbom.integrity_hash(integrity)
        self.assertEqual("SHA-512", result["alg"])
        self.assertEqual(digest.hex(), result["content"])
        self.assertRegex(result["content"], r"^[0-9a-f]{128}$")

    def test_sbom_records_and_verifies_exact_pi_tarball_hashes(self) -> None:
        tarball = b"pinned-pi-tarball"
        metadata = {
            "npmIntegrity": "sha512-"
            + base64.b64encode(hashlib.sha512(tarball).digest()).decode("ascii"),
            "npmShasum": hashlib.sha1(tarball).hexdigest(),
        }
        result = generate_sbom.verified_pi_hashes(tarball, metadata)
        self.assertEqual(
            [
                {"alg": "SHA-1", "content": hashlib.sha1(tarball).hexdigest()},
                {"alg": "SHA-512", "content": hashlib.sha512(tarball).hexdigest()},
            ],
            result,
        )
        with self.assertRaises(ValueError):
            generate_sbom.verified_pi_hashes(tarball + b"-tampered", metadata)


if __name__ == "__main__":
    unittest.main()
