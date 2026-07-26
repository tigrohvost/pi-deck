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
