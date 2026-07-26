#!/usr/bin/env python3
"""Pin a GGUF artifact without executing repository code.

The tool downloads one immutable Hugging Face artifact, validates its GGUF
header, calculates bytes/SHA-256, records provenance, and optionally appends a
separately reviewed schema-v2 candidate entry. It never infers a license from a
repository name and never promotes a model.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import struct
import tempfile
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, BinaryIO

REVISION = re.compile(r"^[0-9a-f]{40}$")
REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
MODEL_ID = re.compile(r"^[a-z0-9][a-z0-9._-]+$")
ARTIFACT = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*\.gguf$")
ALLOWED_LICENSES = {"Apache-2.0", "MIT"}
LICENSE_CONFIRMATION = "I reviewed the exact weights license"
MAX_METADATA_SCAN = 16 * 1024 * 1024


class PinError(RuntimeError):
    pass


class MetadataScanLimit(PinError):
    pass


class GgufReader:
    def __init__(self, stream: BinaryIO):
        self.stream = stream
        self.scanned = 0

    def read(self, count: int) -> bytes:
        if count < 0 or self.scanned + count > MAX_METADATA_SCAN:
            raise MetadataScanLimit("GGUF metadata scan exceeded the 16 MiB safety bound")
        value = self.stream.read(count)
        if len(value) != count:
            raise PinError("Truncated GGUF metadata")
        self.scanned += count
        return value

    def unpack(self, layout: str) -> tuple[Any, ...]:
        size = struct.calcsize(layout)
        return struct.unpack(layout, self.read(size))

    def string(self) -> str:
        (length,) = self.unpack("<Q")
        if length > 1024 * 1024:
            raise PinError("Oversized GGUF metadata string")
        try:
            return self.read(length).decode("utf-8")
        except UnicodeDecodeError as error:
            raise PinError("Non-UTF-8 GGUF metadata string") from error


FIXED_TYPES: dict[int, tuple[str, int]] = {
    0: ("<B", 1),
    1: ("<b", 1),
    2: ("<H", 2),
    3: ("<h", 2),
    4: ("<I", 4),
    5: ("<i", 4),
    6: ("<f", 4),
    7: ("<?", 1),
    10: ("<Q", 8),
    11: ("<q", 8),
    12: ("<d", 8),
}


def read_value(reader: GgufReader, value_type: int, preview_limit: int = 16) -> Any:
    if value_type in FIXED_TYPES:
        layout, _size = FIXED_TYPES[value_type]
        return reader.unpack(layout)[0]
    if value_type == 8:
        return reader.string()
    if value_type != 9:
        raise PinError(f"Unsupported GGUF metadata type: {value_type}")
    element_type, count = reader.unpack("<IQ")
    if count > 10_000_000:
        raise PinError("GGUF metadata array has an unsafe length")
    preview: list[Any] = []
    if element_type in FIXED_TYPES:
        layout, size = FIXED_TYPES[element_type]
        for index in range(count):
            raw = reader.read(size)
            if index < preview_limit:
                preview.append(struct.unpack(layout, raw)[0])
    elif element_type == 8:
        for index in range(count):
            value = reader.string()
            if index < preview_limit:
                preview.append(value)
    else:
        raise PinError(f"Unsupported GGUF array element type: {element_type}")
    return {"count": count, "preview": preview, "truncated": count > preview_limit}


def inspect_gguf(path: Path) -> dict[str, Any]:
    with path.open("rb") as stream:
        reader = GgufReader(stream)
        if reader.read(4) != b"GGUF":
            raise PinError("Downloaded payload is not a GGUF binary")
        version, tensor_count, metadata_count = reader.unpack("<IQQ")
        if version not in {2, 3}:
            raise PinError(f"Unsupported GGUF version: {version}")
        metadata: dict[str, Any] = {}
        partial = False
        try:
            for _index in range(metadata_count):
                key = reader.string()
                (value_type,) = reader.unpack("<I")
                value = read_value(reader, value_type)
                if key.startswith("general.") or key in {
                    "tokenizer.chat_template",
                    "quantize.imatrix.file",
                }:
                    metadata[key] = value
        except MetadataScanLimit:
            partial = True
        return {
            "magic": "GGUF",
            "version": version,
            "tensorCount": tensor_count,
            "metadataKvCount": metadata_count,
            "metadata": metadata,
            "metadataScanPartial": partial,
        }


def download(url: str, destination: Path) -> tuple[int, str]:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "pi-deck-model-pinner/1"},
        method="GET",
    )
    digest = hashlib.sha256()
    total = 0
    temporary_fd, temporary_name = tempfile.mkstemp(
        prefix=f".{destination.name}.", suffix=".part", dir=destination.parent
    )
    try:
        with os.fdopen(temporary_fd, "wb", buffering=0) as output:
            with urllib.request.urlopen(request, timeout=60) as response:
                final_url = response.geturl()
                if not final_url.startswith("https://"):
                    raise PinError("Artifact redirect left HTTPS")
                content_type = response.headers.get_content_type()
                if content_type in {"text/html", "text/plain"}:
                    raise PinError(f"Unexpected artifact content type: {content_type}")
                while True:
                    chunk = response.read(4 * 1024 * 1024)
                    if not chunk:
                        break
                    if total == 0 and (
                        chunk.startswith(b"<!DOCTYPE")
                        or chunk.startswith(b"<html")
                        or chunk.startswith(b"version https://git-lfs.github.com/spec/")
                    ):
                        raise PinError("Received HTML or a Git LFS pointer instead of GGUF")
                    output.write(chunk)
                    digest.update(chunk)
                    total += len(chunk)
                output.flush()
                os.fsync(output.fileno())
        if total < 32:
            raise PinError("Artifact is implausibly small")
        os.replace(temporary_name, destination)
    except BaseException:
        Path(temporary_name).unlink(missing_ok=True)
        raise
    return total, digest.hexdigest()


def load_object(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise PinError(f"{path} must contain a JSON object")
    return value


def commit_candidate(
    manifest_path: Path,
    candidate_path: Path,
    pinned: dict[str, Any],
    license_spdx: str,
) -> None:
    manifest = load_object(manifest_path)
    candidate = load_object(candidate_path)
    if candidate.get("status") not in {"CANDIDATE", "EXPERIMENTAL"}:
        raise PinError("A pinning tool may only add CANDIDATE or EXPERIMENTAL entries")
    source = candidate.get("source", {})
    artifact = candidate.get("artifact", {})
    license_value = candidate.get("license", {})
    expected = pinned["artifact"]
    if (
        source.get("repository") != pinned["repository"]
        or source.get("revision") != pinned["revision"]
        or artifact.get("file") != expected["file"]
        or artifact.get("bytes") != expected["bytes"]
        or artifact.get("sha256") != expected["sha256"]
        or license_value.get("spdx") != license_spdx
    ):
        raise PinError("Candidate entry does not exactly match the independently pinned artifact")
    if source.get("provenanceStatus") != "VERIFIED":
        raise PinError("Manifest commit requires separately verified complete provenance")
    models = manifest.get("models")
    if not isinstance(models, list):
        raise PinError("Production manifest has no models array")
    model_id = candidate.get("id")
    if not isinstance(model_id, str) or not MODEL_ID.fullmatch(model_id):
        raise PinError("Candidate model ID is invalid")
    if any(isinstance(model, dict) and model.get("id") == model_id for model in models):
        raise PinError(f"Model ID already exists: {model_id}")
    models.append(candidate)
    encoded = (
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    ).encode("utf-8")
    temporary = manifest_path.with_name(f".{manifest_path.name}.tmp")
    with temporary.open("wb") as output:
        output.write(encoded)
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, manifest_path)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--filename", required=True)
    parser.add_argument("--model-id", required=True)
    parser.add_argument("--title", required=True)
    parser.add_argument("--tier", choices=["NANO", "EDGE", "CORE", "MAX"], required=True)
    parser.add_argument("--upstream-model", required=True)
    parser.add_argument("--license-spdx", choices=sorted(ALLOWED_LICENSES), required=True)
    parser.add_argument("--license-evidence-url", required=True)
    parser.add_argument("--license-reviewed-by", required=True)
    parser.add_argument("--confirm-license", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, default=Path("app/src/main/assets/models-v2.json"))
    parser.add_argument("--candidate-entry", type=Path)
    parser.add_argument("--commit-candidate", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not REPOSITORY.fullmatch(args.repository):
        raise PinError("Repository must be owner/name")
    if not REVISION.fullmatch(args.revision):
        raise PinError("Revision must be an immutable lowercase 40-hex commit")
    if not ARTIFACT.fullmatch(args.filename):
        raise PinError("Filename must be a safe GGUF basename")
    if len(args.model_id) > 128 or not MODEL_ID.fullmatch(args.model_id):
        raise PinError("Model ID is invalid")
    if args.confirm_license != LICENSE_CONFIRMATION:
        raise PinError(
            f"Manual license review is required; pass --confirm-license {LICENSE_CONFIRMATION!r}"
        )
    if not args.license_evidence_url.startswith("https://"):
        raise PinError("License evidence URL must use HTTPS")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    artifact_path = args.output_dir / args.filename
    quoted_file = urllib.parse.quote(args.filename)
    url = (
        f"https://huggingface.co/{args.repository}/resolve/"
        f"{args.revision}/{quoted_file}?download=true"
    )
    byte_count, sha256 = download(url, artifact_path)
    gguf = inspect_gguf(artifact_path)
    now = dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")
    pinned = {
        "schemaVersion": 1,
        "repository": args.repository,
        "revision": args.revision,
        "downloadUrl": url,
        "artifact": {
            "file": args.filename,
            "bytes": byte_count,
            "sha256": sha256,
        },
        "gguf": gguf,
        "licenseReview": {
            "spdx": args.license_spdx,
            "evidenceUrl": args.license_evidence_url,
            "reviewedBy": args.license_reviewed_by,
            "reviewedAt": now,
            "manualConfirmation": True,
        },
        "candidateDraft": {
            "id": args.model_id,
            "title": args.title,
            "tier": args.tier,
            "status": "EXPERIMENTAL",
            "source": {
                "repository": args.repository,
                "revision": args.revision,
                "upstreamModel": args.upstream_model,
                "provenanceStatus": "INCOMPLETE",
            },
            "artifact": {
                "file": args.filename,
                "bytes": byte_count,
                "sha256": sha256,
            },
            "admissionBlockers": [
                "complete conversion provenance",
                "define and review runtime profile",
                "run PI//DECK device benchmark",
            ],
        },
    }
    report_path = args.output_dir / f"{args.model_id}-pin-report.json"
    report_path.write_text(
        json.dumps(pinned, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    if args.commit_candidate:
        if args.candidate_entry is None:
            raise PinError("--commit-candidate requires --candidate-entry")
        commit_candidate(args.manifest, args.candidate_entry, pinned, args.license_spdx)
    print(report_path)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except PinError as error:
        raise SystemExit(f"pin_model: {error}")
