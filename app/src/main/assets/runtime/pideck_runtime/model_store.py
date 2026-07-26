"""Pinned catalog lookup and private GGUF installation."""

from __future__ import annotations

import hashlib
import json
import os
import re
import secrets
import shutil
from pathlib import Path
from typing import Any

from .common import (
    BASE,
    PiDeckError,
    atomic_write_json,
    fsync_directory,
    require_string,
    sha256_file,
    utc_now,
)

CATALOG_PATH = BASE / "runtime" / "models-v2.json"
SHA_PATTERN = re.compile(r"^[0-9a-f]{64}$")
REVISION_PATTERN = re.compile(r"^[0-9a-f]{40}$")
MODEL_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9._-]+$")
REPOSITORY_PATTERN = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
ARTIFACT_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*\.gguf$")
ALLOWED_LICENSES = {"Apache-2.0", "MIT"}


def load_catalog() -> dict[str, Any]:
    try:
        raw = CATALOG_PATH.read_bytes()
        if not raw or len(raw) > 2 * 1024 * 1024:
            raise ValueError("catalog size")
        catalog = json.loads(raw.decode("utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as error:
        raise PiDeckError("INVALID_CATALOG", "Bundled model catalog is unavailable") from error
    if (
        not isinstance(catalog, dict)
        or catalog.get("schemaVersion") != 2
        or not isinstance(catalog.get("models"), list)
    ):
        raise PiDeckError("INVALID_CATALOG", "Unsupported model catalog schema")
    return catalog


def model_by_id(model_id: str) -> dict[str, Any]:
    if not MODEL_ID_PATTERN.fullmatch(model_id):
        raise PiDeckError("UNKNOWN_MODEL", "Unknown model ID")
    matches = [
        model
        for model in load_catalog()["models"]
        if isinstance(model, dict) and model.get("id") == model_id
    ]
    if len(matches) != 1:
        raise PiDeckError("UNKNOWN_MODEL", f"Model is not present exactly once: {model_id}")
    model = matches[0]
    validate_model(model)
    return model


def validate_model(model: dict[str, Any]) -> None:
    try:
        artifact = model["artifact"]
        source = model["source"]
        license_value = model["license"]
        runtime = model["runtime"]
        file_name = artifact["file"]
        sha256 = artifact["sha256"]
        byte_count = artifact["bytes"]
        revision = source["revision"]
        provenance_status = source["provenanceStatus"]
        spdx = license_value["spdx"]
        context = runtime["recommendedContext"]
        repository = source["repository"]
    except (KeyError, TypeError) as error:
        raise PiDeckError("INVALID_CATALOG", "Model entry misses a critical field") from error
    if (
        not isinstance(file_name, str)
        or not ARTIFACT_PATTERN.fullmatch(file_name)
        or not isinstance(byte_count, int)
        or byte_count <= 0
        or not isinstance(sha256, str)
        or not SHA_PATTERN.fullmatch(sha256)
        or not isinstance(revision, str)
        or not REVISION_PATTERN.fullmatch(revision)
        or not isinstance(repository, str)
        or not REPOSITORY_PATTERN.fullmatch(repository)
        or provenance_status not in {"VERIFIED", "INCOMPLETE"}
        or spdx not in ALLOWED_LICENSES
        or not isinstance(context, int)
        or context < 512
    ):
        raise PiDeckError("INVALID_CATALOG", "Model entry contains unsafe critical metadata")


def model_directory(model: dict[str, Any]) -> Path:
    return BASE / "models" / str(model["id"])


def private_model_path(model: dict[str, Any]) -> Path:
    return model_directory(model) / str(model["artifact"]["file"])


def install_metadata_path(model: dict[str, Any]) -> Path:
    return model_directory(model) / "install.json"


def _allowed_source(source: Path) -> Path:
    try:
        resolved = source.resolve(strict=True)
    except OSError as error:
        raise PiDeckError("SOURCE_MISSING", "Incoming GGUF is not readable") from error
    candidates = [
        Path.home() / "storage" / "downloads" / "PiDeck" / "incoming",
        Path("/storage/emulated/0/Download/PiDeck/incoming"),
    ]
    allowed_roots: list[Path] = []
    for candidate in candidates:
        try:
            allowed_roots.append(candidate.resolve(strict=True))
        except OSError:
            continue
    if not any(resolved == root or root in resolved.parents for root in allowed_roots):
        raise PiDeckError(
            "SOURCE_OUTSIDE_INCOMING",
            "Incoming artifact is outside Download/PiDeck/incoming",
        )
    if not resolved.is_file():
        raise PiDeckError("SOURCE_MISSING", "Incoming artifact is not a regular file")
    return resolved


def _write_all(descriptor: int, content: bytes) -> None:
    view = memoryview(content)
    while view:
        written = os.write(descriptor, view)
        view = view[written:]


def install_private(model_id: str, source_value: str) -> dict[str, Any]:
    model = model_by_id(model_id)
    source = _allowed_source(Path(source_value))
    artifact = model["artifact"]
    expected_bytes = int(artifact["bytes"])
    expected_sha = str(artifact["sha256"])
    destination_directory = model_directory(model)
    destination_directory.mkdir(parents=True, exist_ok=True)
    os.chmod(destination_directory, 0o700)
    destination = private_model_path(model)

    if destination.is_file():
        try:
            actual_bytes, actual_sha = sha256_file(destination, expected_bytes)
        except PiDeckError:
            actual_bytes, actual_sha = -1, ""
        if actual_bytes == expected_bytes and actual_sha == expected_sha:
            _write_install_metadata(model, actual_bytes, actual_sha)
            os.chmod(destination, 0o400)
            return {
                "state": "READY",
                "modelId": model_id,
                "privatePath": str(destination),
                "sha256": actual_sha,
                "idempotent": True,
            }

    safety_margin = max(256 * 1024 * 1024, expected_bytes // 10)
    free = shutil.disk_usage(destination_directory).free
    reclaimable = destination.stat().st_size if destination.exists() else 0
    required = expected_bytes + safety_margin
    if free + reclaimable < required:
        raise PiDeckError(
            "INSUFFICIENT_SPACE",
            f"Private install needs {required} free bytes; {free + reclaimable} available",
        )

    temporary = destination_directory / f".{destination.name}.tmp-{secrets.token_hex(8)}"
    digest = hashlib.sha256()
    copied = 0
    source_flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    source_descriptor = os.open(source, source_flags)
    target_descriptor = os.open(
        temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600
    )
    try:
        while True:
            chunk = os.read(source_descriptor, 4 * 1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
            copied += len(chunk)
            _write_all(target_descriptor, chunk)
        os.fsync(target_descriptor)
    except BaseException:
        try:
            temporary.unlink(missing_ok=True)
        finally:
            raise
    finally:
        os.close(source_descriptor)
        os.close(target_descriptor)

    actual_sha = digest.hexdigest()
    if copied != expected_bytes or actual_sha != expected_sha:
        temporary.unlink(missing_ok=True)
        raise PiDeckError(
            "SHA_MISMATCH",
            f"Incoming artifact failed verification ({copied} bytes, sha256={actual_sha})",
        )

    os.chmod(temporary, 0o400)
    os.replace(temporary, destination)
    fsync_directory(destination_directory)
    _write_install_metadata(model, copied, actual_sha)
    return {
        "state": "READY",
        "modelId": model_id,
        "privatePath": str(destination),
        "sha256": actual_sha,
        "idempotent": False,
    }


def verify_private(model_id: str) -> dict[str, Any]:
    model = model_by_id(model_id)
    destination = private_model_path(model)
    if not destination.is_file():
        return {"state": "MISSING", "modelId": model_id}
    try:
        size, actual_sha = sha256_file(destination, int(model["artifact"]["bytes"]))
    except PiDeckError as error:
        return {"state": "CORRUPT", "modelId": model_id, "reason": error.code}
    expected_sha = str(model["artifact"]["sha256"])
    if actual_sha != expected_sha:
        return {"state": "CORRUPT", "modelId": model_id, "sha256": actual_sha}
    os.chmod(destination, 0o400)
    _write_install_metadata(model, size, actual_sha)
    return {
        "state": "READY",
        "modelId": model_id,
        "privatePath": str(destination),
        "sha256": actual_sha,
    }


def _write_install_metadata(model: dict[str, Any], byte_count: int, sha256: str) -> None:
    existing_installed_at = None
    metadata_path = install_metadata_path(model)
    if metadata_path.is_file():
        try:
            previous = json.loads(metadata_path.read_text(encoding="utf-8"))
            existing_installed_at = previous.get("installedAt")
        except (OSError, json.JSONDecodeError, AttributeError):
            pass
    now = utc_now()
    atomic_write_json(
        metadata_path,
        {
            "schemaVersion": 1,
            "modelId": model["id"],
            "artifactFile": model["artifact"]["file"],
            "bytes": byte_count,
            "sha256": sha256,
            "sourceRevision": model["source"]["revision"],
            "installedAt": existing_installed_at or now,
            "verifiedAt": now,
        },
        0o600,
    )
