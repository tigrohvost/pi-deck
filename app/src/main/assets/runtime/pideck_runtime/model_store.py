"""Pinned catalog lookup and private GGUF installation."""

from __future__ import annotations

import hashlib
import json
import os
import re
import secrets
import shutil
import stat
from pathlib import Path
from typing import Any

from .common import (
    BASE,
    MAX_JSON_BYTES,
    PiDeckError,
    atomic_write_json,
    fsync_directory,
    read_json,
    require_string,
    sha256_file,
    utc_now,
)

CATALOG_PATH = BASE / "runtime" / "models-v2.json"
PI_SETTINGS_PATH = BASE / "pi" / "settings.json"
PI_PROJECT_SETTINGS_PATH = BASE / "workspace" / ".pi" / "settings.json"
SHA_PATTERN = re.compile(r"^[0-9a-f]{64}$")
REVISION_PATTERN = re.compile(r"^[0-9a-f]{40}$")
MODEL_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9._-]+$")
REPOSITORY_PATTERN = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
ARTIFACT_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*\.gguf$")
# Pi 0.82.1 subtracts this fixed hosted-model safety margin before every
# provider request. Local llama.cpp already enforces its real context window,
# so the generated Pi model descriptor compensates for the fixed subtraction.
PI_CONTEXT_SAFETY_TOKENS = 4096
PI_CONTEXT_CONTRACT_VERSION = 3
# LicenseRef-LFM-Open-1.0: LFM Open License v1.0, reviewed 2026-08-07 — Apache-2.0-derived,
# full use below a $10M annual-revenue threshold; see docs/model-admission.md.
ALLOWED_LICENSES = {"Apache-2.0", "MIT", "LicenseRef-LFM-Open-1.0"}
SERVER_FLAVOR_BUILDS = {
    "stock": "b10369",
    "nanbeige42": "nanbeige42-c6640a1",
}


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
        server_flavor = runtime["serverFlavor"]
        minimum_runtime = runtime["minimumLlamaCppVersion"]
        max_tokens = model["agent"]["maxTokens"]
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
        or not isinstance(server_flavor, str)
        or not isinstance(minimum_runtime, str)
        or SERVER_FLAVOR_BUILDS.get(server_flavor) != minimum_runtime
        or not isinstance(max_tokens, int)
        or isinstance(max_tokens, bool)
        or max_tokens < 1
        or max_tokens >= context
    ):
        raise PiDeckError("INVALID_CATALOG", "Model entry contains unsafe critical metadata")


def pi_advertised_context_window(model: dict[str, Any]) -> int:
    """Returns the virtual Pi window that preserves llama.cpp's real token budget."""
    validate_model(model)
    return int(model["runtime"]["recommendedContext"]) + PI_CONTEXT_SAFETY_TOKENS


def _project_settings_state(value: os.stat_result) -> tuple[int, int, int, int, int]:
    return (
        value.st_dev,
        value.st_ino,
        value.st_size,
        value.st_mtime_ns,
        value.st_ctime_ns,
    )


def _normalize_existing_project_compaction(
    managed: dict[str, int | bool],
) -> None:
    """Normalizes an existing project override without creating project state."""
    settings_path = PI_PROJECT_SETTINGS_PATH
    directory_path = settings_path.parent
    workspace_path = directory_path.parent
    try:
        workspace_state = os.lstat(workspace_path)
    except FileNotFoundError:
        return
    except OSError as error:
        raise PiDeckError(
            "INVALID_PROJECT_SETTINGS",
            "Pi workspace is not safely readable",
        ) from error
    if not stat.S_ISDIR(workspace_state.st_mode):
        raise PiDeckError(
            "INVALID_PROJECT_SETTINGS",
            "Pi workspace must not be a symlink",
        )

    directory_flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    workspace_fd = -1
    directory_fd = -1
    try:
        workspace_fd = os.open(workspace_path, directory_flags)
        if _project_settings_state(os.fstat(workspace_fd))[:2] != (
            workspace_state.st_dev,
            workspace_state.st_ino,
        ):
            raise PiDeckError(
                "PROJECT_SETTINGS_CHANGED",
                "Pi workspace changed while being validated",
            )
        try:
            directory_state = os.stat(
                directory_path.name,
                dir_fd=workspace_fd,
                follow_symlinks=False,
            )
        except FileNotFoundError:
            return
        if not stat.S_ISDIR(directory_state.st_mode):
            raise PiDeckError(
                "INVALID_PROJECT_SETTINGS",
                "Pi project settings directory must not be a symlink",
            )
        directory_fd = os.open(
            directory_path.name,
            directory_flags,
            dir_fd=workspace_fd,
        )
        if _project_settings_state(os.fstat(directory_fd))[:2] != (
            directory_state.st_dev,
            directory_state.st_ino,
        ):
            raise PiDeckError(
                "PROJECT_SETTINGS_CHANGED",
                "Pi project settings changed while being validated",
            )
        try:
            expected_state = os.stat(
                settings_path.name,
                dir_fd=directory_fd,
                follow_symlinks=False,
            )
        except FileNotFoundError:
            return
        if not stat.S_ISREG(expected_state.st_mode):
            raise PiDeckError(
                "INVALID_PROJECT_SETTINGS",
                "Pi project settings must be a regular non-symlink file",
            )

        file_flags = (
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0)
        )
        try:
            file_fd = os.open(settings_path.name, file_flags, dir_fd=directory_fd)
        except OSError as error:
            raise PiDeckError(
                "INVALID_PROJECT_SETTINGS",
                "Pi project settings must be a regular non-symlink file",
            ) from error
        try:
            opened_state = os.fstat(file_fd)
            if (
                not stat.S_ISREG(opened_state.st_mode)
                or _project_settings_state(opened_state)
                != _project_settings_state(expected_state)
            ):
                raise PiDeckError(
                    "PROJECT_SETTINGS_CHANGED",
                    "Pi project settings changed while being validated",
                )
            raw = bytearray()
            while len(raw) <= MAX_JSON_BYTES:
                chunk = os.read(
                    file_fd,
                    min(64 * 1024, MAX_JSON_BYTES + 1 - len(raw)),
                )
                if not chunk:
                    break
                raw.extend(chunk)
        finally:
            os.close(file_fd)
        if not raw or len(raw) > MAX_JSON_BYTES:
            raise PiDeckError(
                "INVALID_PROJECT_SETTINGS",
                "Pi project settings have an invalid size",
            )
        try:
            settings = json.loads(raw.decode("utf-8"))
        except (UnicodeError, json.JSONDecodeError) as error:
            raise PiDeckError(
                "INVALID_PROJECT_SETTINGS",
                "Pi project settings are not valid JSON",
            ) from error
        if not isinstance(settings, dict):
            raise PiDeckError(
                "INVALID_PROJECT_SETTINGS",
                "Pi project settings must contain a JSON object",
            )
        if "compaction" not in settings:
            return

        existing = settings.get("compaction")
        compaction = dict(existing) if isinstance(existing, dict) else {}
        compaction.update(managed)
        settings["compaction"] = compaction
        encoded = json.dumps(
            settings,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
        if len(encoded) > MAX_JSON_BYTES:
            raise PiDeckError(
                "INVALID_PROJECT_SETTINGS",
                "Pi project settings are too large to normalize safely",
            )

        temporary_name = f".{settings_path.name}.tmp-{secrets.token_hex(8)}"
        temporary_created = False
        try:
            temporary_fd = os.open(
                temporary_name,
                os.O_WRONLY
                | os.O_CREAT
                | os.O_EXCL
                | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_NOFOLLOW", 0),
                0o600,
                dir_fd=directory_fd,
            )
            temporary_created = True
            try:
                view = memoryview(encoded)
                while view:
                    written = os.write(temporary_fd, view)
                    view = view[written:]
                os.fchmod(temporary_fd, 0o600)
                os.fsync(temporary_fd)
            finally:
                os.close(temporary_fd)

            current_state = os.stat(
                settings_path.name,
                dir_fd=directory_fd,
                follow_symlinks=False,
            )
            if (
                not stat.S_ISREG(current_state.st_mode)
                or _project_settings_state(current_state)
                != _project_settings_state(expected_state)
            ):
                raise PiDeckError(
                    "PROJECT_SETTINGS_CHANGED",
                    "Pi project settings changed while being normalized",
                )
            os.replace(
                temporary_name,
                settings_path.name,
                src_dir_fd=directory_fd,
                dst_dir_fd=directory_fd,
            )
            temporary_created = False
            os.fsync(directory_fd)
        finally:
            if temporary_created:
                try:
                    os.unlink(temporary_name, dir_fd=directory_fd)
                except FileNotFoundError:
                    pass
    except PiDeckError:
        raise
    except OSError as error:
        raise PiDeckError(
            "INVALID_PROJECT_SETTINGS",
            "Pi project settings could not be normalized safely",
        ) from error
    finally:
        if directory_fd >= 0:
            os.close(directory_fd)
        if workspace_fd >= 0:
            os.close(workspace_fd)


def ensure_pi_compaction_settings(model: dict[str, Any]) -> dict[str, int | bool]:
    """Compacts before Pi can reduce a local provider request below maxTokens."""
    validate_model(model)
    context_window = int(model["runtime"]["recommendedContext"])
    max_tokens = int(model["agent"]["maxTokens"])
    reserve_tokens = PI_CONTEXT_SAFETY_TOKENS + max_tokens
    compaction_threshold = context_window - max_tokens
    keep_recent_target = max(1024, min(3072, context_window // 4))
    keep_recent_tokens = max(1, min(keep_recent_target, compaction_threshold))
    managed = {
        "enabled": True,
        "reserveTokens": reserve_tokens,
        "keepRecentTokens": keep_recent_tokens,
    }
    settings: dict[str, Any] = {}
    if PI_SETTINGS_PATH.is_file():
        settings = read_json(PI_SETTINGS_PATH)
    existing = settings.get("compaction")
    compaction = dict(existing) if isinstance(existing, dict) else {}
    compaction.update(managed)
    settings["compaction"] = compaction
    atomic_write_json(PI_SETTINGS_PATH, settings, 0o600)

    # Pi deep-merges workspace/.pi/settings.json over its global settings. Do
    # not create project state, but normalize an override that Pi would honor.
    _normalize_existing_project_compaction(managed)
    return managed


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
    candidates = _managed_source_candidates()
    allowed_roots: list[Path] = []
    for candidate in candidates:
        try:
            allowed_roots.append(candidate.resolve(strict=True))
        except OSError:
            continue
    if not any(resolved == root or root in resolved.parents for root in allowed_roots):
        raise PiDeckError(
            "SOURCE_OUTSIDE_INCOMING",
            "Incoming artifact is outside a managed PiDeck download directory",
        )
    if not resolved.is_file():
        raise PiDeckError("SOURCE_MISSING", "Incoming artifact is not a regular file")
    return resolved


def _managed_source_candidates() -> tuple[Path, ...]:
    return (
        Path.home() / "storage" / "downloads" / "PiDeck" / "incoming",
        Path("/storage/emulated/0/Download/PiDeck/incoming"),
        # 0.1.x stored DownloadManager artifacts here. Accept this project-owned
        # source only as migration input; install_private still checks the
        # pinned byte count and SHA-256 before atomically activating a private
        # read-only copy.
        Path.home() / "storage" / "downloads" / "PiDeck" / "models",
        Path("/storage/emulated/0/Download/PiDeck/models"),
    )


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
