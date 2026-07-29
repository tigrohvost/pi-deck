from __future__ import annotations

import importlib.util
import base64
import hashlib
import json
import struct
import tempfile
import unittest
from pathlib import Path


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


def gguf_string(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return struct.pack("<Q", len(encoded)) + encoded


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
        path = REPOSITORY / "benchmarks" / "suite-v1" / "tasks.json"
        value = json.loads(path.read_text(encoding="utf-8"))
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
