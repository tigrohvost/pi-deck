"""Security-sensitive filesystem and process primitives for the Termux runtime."""

from __future__ import annotations

import hashlib
import json
import os
import secrets
import signal
import subprocess
import time
from pathlib import Path
from typing import Any, Iterable

SCHEMA_VERSION = 1
MAX_JSON_BYTES = 1024 * 1024
PREFIX = Path(os.environ.get("PREFIX", "/data/data/com.termux/files/usr"))
BASE = Path(os.environ.get("PIDECK_HOME", str(Path.home() / ".pideck"))).resolve()


class PiDeckError(RuntimeError):
    """A structured operational failure safe to show without secret material."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message

    def as_dict(self) -> dict[str, str]:
        return {"code": self.code, "message": self.message}


def ensure_private_layout() -> None:
    for relative in (
        "runtime",
        "models",
        "workspace",
        "sessions",
        "session-archive",
        "processes",
        "server",
        "bridge",
        "logs",
    ):
        path = BASE / relative
        path.mkdir(parents=True, exist_ok=True)
        os.chmod(path, 0o700)


def fsync_directory(path: Path) -> None:
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def atomic_write_bytes(path: Path, content: bytes, mode: int = 0o600) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    os.chmod(path.parent, 0o700)
    temporary = path.parent / f".{path.name}.tmp-{secrets.token_hex(8)}"
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    try:
        view = memoryview(content)
        while view:
            written = os.write(descriptor, view)
            view = view[written:]
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    os.chmod(temporary, mode)
    os.replace(temporary, path)
    fsync_directory(path.parent)


def atomic_write_json(path: Path, value: dict[str, Any], mode: int = 0o600) -> None:
    encoded = json.dumps(
        value, ensure_ascii=False, separators=(",", ":"), sort_keys=True
    ).encode("utf-8")
    if len(encoded) > MAX_JSON_BYTES:
        raise PiDeckError("JSON_TOO_LARGE", f"Refusing to write oversized metadata: {path.name}")
    atomic_write_bytes(path, encoded, mode)


def read_json(path: Path, maximum: int = MAX_JSON_BYTES) -> dict[str, Any]:
    try:
        size = path.stat().st_size
        if size <= 0 or size > maximum:
            raise PiDeckError("INVALID_METADATA", f"Invalid metadata size: {path.name}")
        value = json.loads(path.read_text(encoding="utf-8"))
    except PiDeckError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise PiDeckError("INVALID_METADATA", f"Could not parse {path.name}") from error
    if not isinstance(value, dict):
        raise PiDeckError("INVALID_METADATA", f"Expected an object in {path.name}")
    return value


def read_stdin_json(maximum: int = 256 * 1024) -> dict[str, Any]:
    chunks: list[bytes] = []
    length = 0
    while True:
        chunk = os.read(0, min(64 * 1024, maximum + 1 - length))
        if not chunk:
            break
        chunks.append(chunk)
        length += len(chunk)
        if length > maximum:
            raise PiDeckError("INPUT_TOO_LARGE", "Command input exceeds the bounded limit")
    try:
        value = json.loads(b"".join(chunks).decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise PiDeckError("MALFORMED_JSON", "Command input is not valid JSON") from error
    if not isinstance(value, dict):
        raise PiDeckError("MALFORMED_JSON", "Command input must be a JSON object")
    return value


def require_string(
    value: dict[str, Any], key: str, maximum: int = 4096, allow_empty: bool = False
) -> str:
    result = value.get(key)
    if not isinstance(result, str):
        raise PiDeckError("MISSING_FIELD", f"Required string field is missing: {key}")
    if not allow_empty and not result:
        raise PiDeckError("MISSING_FIELD", f"Required string field is empty: {key}")
    if len(result.encode("utf-8")) > maximum:
        raise PiDeckError("FIELD_TOO_LARGE", f"Field exceeds limit: {key}")
    return result


def require_uuid4(value: dict[str, Any], key: str = "operationId") -> str:
    import uuid

    raw = require_string(value, key, 36)
    try:
        parsed = uuid.UUID(raw)
    except ValueError as error:
        raise PiDeckError("INVALID_OPERATION_ID", "operationId is not a UUID") from error
    if parsed.version != 4 or str(parsed) != raw:
        raise PiDeckError("INVALID_OPERATION_ID", "operationId must be a canonical UUIDv4")
    return raw


def require_session_id(value: dict[str, Any], key: str = "sessionId") -> str:
    """Validates Pi session IDs without conflating them with operation UUIDv4.

    Pi 0.82.x creates time-ordered UUIDv7 sessions, while Android-created/resumed sessions may
    still be UUIDv4. Both are canonical session identifiers; operation IDs remain UUIDv4 only.
    """
    import uuid

    raw = require_string(value, key, 36)
    try:
        parsed = uuid.UUID(raw)
    except ValueError as error:
        raise PiDeckError("INVALID_SESSION_ID", "sessionId is not a UUID") from error
    if parsed.version not in {4, 7} or str(parsed) != raw:
        raise PiDeckError(
            "INVALID_SESSION_ID",
            "sessionId must be a canonical UUIDv4 or UUIDv7",
        )
    return raw


def sha256_file(path: Path, expected_bytes: int | None = None) -> tuple[int, str]:
    digest = hashlib.sha256()
    count = 0
    with path.open("rb", buffering=0) as source:
        while True:
            chunk = source.read(4 * 1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
            count += len(chunk)
    if expected_bytes is not None and count != expected_bytes:
        raise PiDeckError(
            "SIZE_MISMATCH", f"Artifact has {count} bytes; expected {expected_bytes}"
        )
    return count, digest.hexdigest()


def command_hash(arguments: Iterable[str]) -> str:
    normalized = json.dumps(list(arguments), ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def proc_identity(pid: int) -> tuple[int, int]:
    """Return process group and Linux /proc start ticks."""
    if pid <= 1:
        raise PiDeckError("INVALID_PID", "Refusing an unsafe PID")
    try:
        raw = (Path("/proc") / str(pid) / "stat").read_text(encoding="utf-8")
        fields = raw[raw.rfind(")") + 2 :].split()
        process_group = int(fields[2])
        start_ticks = int(fields[19])
    except (OSError, ValueError, IndexError) as error:
        raise PiDeckError("PROCESS_NOT_FOUND", "Managed process no longer exists") from error
    return process_group, start_ticks


def proc_cmdline(pid: int) -> list[str]:
    try:
        raw = (Path("/proc") / str(pid) / "cmdline").read_bytes()
    except OSError as error:
        raise PiDeckError("PROCESS_NOT_FOUND", "Could not read managed process cmdline") from error
    return [part.decode("utf-8", "replace") for part in raw.split(b"\0") if part]


def proc_has_operation_token(pid: int, operation_id: str) -> bool:
    try:
        raw = (Path("/proc") / str(pid) / "environ").read_bytes()
    except OSError:
        return False
    expected = f"PIDECK_OPERATION_ID={operation_id}".encode()
    return expected in raw.split(b"\0")


def metadata_for_process(
    process: subprocess.Popen[Any],
    arguments: list[str],
    operation_id: str,
    expected_executable: str,
    extra: dict[str, Any] | None = None,
) -> dict[str, Any]:
    process_group, start_ticks = proc_identity(process.pid)
    value: dict[str, Any] = {
        "schemaVersion": SCHEMA_VERSION,
        "operationId": operation_id,
        "pid": process.pid,
        "processGroupId": process_group,
        "procStartTicks": start_ticks,
        "commandHash": command_hash(arguments),
        "expectedExecutable": expected_executable,
        "createdAt": utc_now(),
    }
    if extra:
        value.update(extra)
    return value


def process_matches(metadata: dict[str, Any]) -> bool:
    try:
        pid = int(metadata["pid"])
        expected_group = int(metadata["processGroupId"])
        expected_ticks = int(metadata["procStartTicks"])
        operation_id = str(metadata["operationId"])
        expected_executable = str(metadata["expectedExecutable"])
        process_group, start_ticks = proc_identity(pid)
        if process_group != expected_group or start_ticks != expected_ticks:
            return False
        cmdline = proc_cmdline(pid)
        if not any(
            part == expected_executable or Path(part).name == expected_executable
            for part in cmdline
        ):
            return False
        return proc_has_operation_token(pid, operation_id)
    except (KeyError, TypeError, ValueError, PiDeckError):
        return False


def process_alive(metadata: dict[str, Any]) -> bool:
    if not process_matches(metadata):
        return False
    try:
        os.kill(int(metadata["pid"]), 0)
        return True
    except OSError:
        return False


def terminate_exact(
    metadata: dict[str, Any],
    interrupt_seconds: float = 4.0,
    terminate_seconds: float = 4.0,
) -> bool:
    """Stop only the verified process group. A reused PID is never signalled."""
    if not process_matches(metadata):
        return False
    pid = int(metadata["pid"])
    group = int(metadata["processGroupId"])
    if group <= 1 or group == os.getpgrp():
        raise PiDeckError("UNSAFE_PROCESS_GROUP", "Refusing to signal an unsafe process group")

    for selected_signal, grace in (
        (signal.SIGINT, interrupt_seconds),
        (signal.SIGTERM, terminate_seconds),
        (signal.SIGKILL, 1.0),
    ):
        if not process_matches(metadata):
            return True
        try:
            os.killpg(group, selected_signal)
        except ProcessLookupError:
            return True
        deadline = time.monotonic() + grace
        while time.monotonic() < deadline:
            if not process_matches(metadata):
                return True
            time.sleep(0.1)
    return not process_matches(metadata)


def managed_environment(operation_id: str) -> dict[str, str]:
    environment = os.environ.copy()
    environment["PIDECK_OPERATION_ID"] = operation_id
    environment["PIDECK_HOME"] = str(BASE)
    environment["PI_OFFLINE"] = "1"
    environment["PI_SKIP_VERSION_CHECK"] = "1"
    termux_exec = PREFIX / "lib" / "libtermux-exec.so"
    if termux_exec.is_file():
        environment["LD_PRELOAD"] = str(termux_exec)
    return environment


def utc_now() -> str:
    import datetime

    return datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")


def bounded_text(value: Any, maximum: int = 32 * 1024) -> str:
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
    encoded = text.encode("utf-8", "replace")
    if len(encoded) <= maximum:
        return text
    return encoded[: maximum - 32].decode("utf-8", "ignore") + "\n[payload truncated]"


def result_ok(**payload: Any) -> dict[str, Any]:
    return {"schemaVersion": SCHEMA_VERSION, "ok": True, **payload}


def run_cli(handler: Any) -> None:
    try:
        ensure_private_layout()
        result = handler()
        print(json.dumps(result, ensure_ascii=False, separators=(",", ":")), flush=True)
    except PiDeckError as error:
        print(
            json.dumps(
                {"schemaVersion": SCHEMA_VERSION, "ok": False, "error": error.as_dict()},
                ensure_ascii=False,
                separators=(",", ":"),
            ),
            flush=True,
        )
        raise SystemExit(2)
    except Exception:
        # Unexpected details stay in the private diagnostic log, not Android/logcat.
        import traceback

        diagnostic = BASE / "logs" / "runtime-crash.log"
        atomic_write_bytes(diagnostic, traceback.format_exc().encode("utf-8"), 0o600)
        print(
            json.dumps(
                {
                    "schemaVersion": SCHEMA_VERSION,
                    "ok": False,
                    "error": {
                        "code": "INTERNAL_ERROR",
                        "message": "Termux runtime failed; inspect the private diagnostic log",
                    },
                },
                separators=(",", ":"),
            ),
            flush=True,
        )
        raise SystemExit(3)
