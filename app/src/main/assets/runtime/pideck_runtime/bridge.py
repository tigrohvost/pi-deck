"""Authenticated loopback bridge for Pi's documented JSONL RPC protocol."""

from __future__ import annotations

import collections
import base64
import hashlib
import hmac
import json
import os
import re
import signal
import subprocess
import sys
import threading
import time
import uuid
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

from .common import (
    BASE,
    PiDeckError,
    atomic_write_bytes,
    atomic_write_json,
    bounded_text,
    fsync_directory,
    managed_environment,
    metadata_for_process,
    process_alive,
    read_json,
    require_string,
    require_uuid4,
    terminate_exact,
    utc_now,
)
from .server_supervisor import SERVER_API_KEY, read_server_status, strict_health
from .model_store import model_by_id

BRIDGE_DIRECTORY = BASE / "bridge"
BRIDGE_CONFIG = BRIDGE_DIRECTORY / "config.json"
BRIDGE_TOKEN = BRIDGE_DIRECTORY / "token"
BRIDGE_METADATA = BRIDGE_DIRECTORY / "supervisor.json"
PI_CHILD_METADATA = BRIDGE_DIRECTORY / "pi-child.json"
EVENT_JOURNAL = BRIDGE_DIRECTORY / "events.jsonl"
AUDIT_LOG = BRIDGE_DIRECTORY / "approval-audit.jsonl"
PI_STDERR_LOG = BASE / "logs" / "pi-rpc.stderr.log"
PERMISSION_EXTENSION = BASE / "runtime" / "pideck-permission-gate.ts"
MAX_EVENT_BYTES = 256 * 1024
MAX_EVENTS = 10_000
MAX_JOURNAL_BYTES = 20 * 1024 * 1024
MAX_JOURNAL_TAIL = 5_000
MAX_AUDIT_BYTES = 5 * 1024 * 1024
AUDIT_RETAIN_BYTES = 2 * 1024 * 1024
MAX_PROMPT_BYTES = 64 * 1024
APPROVAL_TTL_SECONDS = 30
TOKEN_PATTERN = re.compile(r"^[A-Za-z0-9_-]{43}$")

# Pi's extension UI carries a title and a message and nothing else, so the permission gate puts
# what the deck needs to draw a decision on the first line of the message and the bridge lifts it
# back out. An approval without the header still works; it simply arrives without a decision.
DECISION_PREFIX = "PIDECK-DECISION/1 "
DECISION_KINDS = frozenset({"overwrite", "delete", "shell"})
MAX_DECISION_PREVIEW_LINES = 4


def split_decision(message: str) -> tuple[dict[str, Any] | None, str]:
    """Splits the gate's structured header off an approval message."""
    if not message.startswith(DECISION_PREFIX):
        return None, message
    header, _, remainder = message.partition("\n")
    try:
        value = json.loads(header[len(DECISION_PREFIX):])
    except json.JSONDecodeError:
        return None, message
    if not isinstance(value, dict):
        return None, message
    decision = _bounded_decision(value)
    if decision is None:
        return None, message
    return decision, remainder.lstrip("\n")


def _bounded_decision(value: dict[str, Any]) -> dict[str, Any] | None:
    kind = value.get("kind")
    if kind not in DECISION_KINDS:
        return None
    preview_source = value.get("preview")
    preview: list[str] = []
    if isinstance(preview_source, list):
        for line in preview_source[:MAX_DECISION_PREVIEW_LINES]:
            if isinstance(line, str):
                preview.append(bounded_text(line, 200))
    return {
        "kind": kind,
        "path": bounded_text(value.get("path", ""), 1024),
        "reason": bounded_text(value.get("reason", ""), 2048),
        "addedLines": _bounded_count(value.get("addedLines")),
        "removedLines": _bounded_count(value.get("removedLines")),
        "selfCreated": value.get("selfCreated") is True,
        "preview": preview,
    }


def _bounded_count(value: Any) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        return 0
    return min(value, 1_000_000)


def validated_token(value: str) -> bytes:
    if not isinstance(value, str) or not TOKEN_PATTERN.fullmatch(value):
        raise PiDeckError("INVALID_TOKEN", "Bridge token must be canonical base64url")
    try:
        decoded = base64.urlsafe_b64decode(value + "=")
    except (ValueError, TypeError) as error:
        raise PiDeckError("INVALID_TOKEN", "Bridge token is malformed") from error
    if (
        len(decoded) != 32
        or base64.urlsafe_b64encode(decoded).decode("ascii").rstrip("=") != value
    ):
        raise PiDeckError("INVALID_TOKEN", "Bridge token must contain exactly 256 bits")
    return value.encode("ascii")


class EventJournal:
    def __init__(self, bridge_instance_id: str):
        self.bridge_instance_id = bridge_instance_id
        self._condition = threading.Condition()
        self._events: collections.deque[dict[str, Any]] = collections.deque()
        self._sequence = 0
        self._append_count = 0
        EVENT_JOURNAL.parent.mkdir(parents=True, exist_ok=True)
        os.chmod(EVENT_JOURNAL.parent, 0o700)
        atomic_write_bytes(EVENT_JOURNAL, b"", 0o600)

    @property
    def sequence(self) -> int:
        with self._condition:
            return self._sequence

    def append(
        self,
        event_type: str,
        operation_id: str | None,
        session_id: str | None,
        payload: dict[str, Any] | None = None,
        terminal: bool = False,
    ) -> dict[str, Any]:
        with self._condition:
            self._sequence += 1
            event = {
                "schemaVersion": 1,
                "sequence": self._sequence,
                "bridgeInstanceId": self.bridge_instance_id,
                "operationId": operation_id,
                "sessionId": session_id,
                "type": event_type,
                "timestamp": utc_now(),
                "payload": payload or {},
            }
            encoded = json.dumps(
                event, ensure_ascii=False, separators=(",", ":"), sort_keys=True
            ).encode("utf-8")
            if len(encoded) > MAX_EVENT_BYTES:
                event["payload"] = {
                    "truncated": True,
                    "preview": bounded_text(event["payload"], 16 * 1024),
                }
                encoded = json.dumps(
                    event, ensure_ascii=False, separators=(",", ":"), sort_keys=True
                ).encode("utf-8")
            self._events.append(event)
            with EVENT_JOURNAL.open("ab", buffering=0) as output:
                output.write(encoded + b"\n")
                self._append_count += 1
                if terminal or self._append_count >= 10:
                    os.fsync(output.fileno())
                    self._append_count = 0
            self._rotate_if_needed(operation_id)
            self._condition.notify_all()
            return event

    def after(self, sequence: int, timeout_seconds: float) -> tuple[bool, list[dict[str, Any]]]:
        deadline = time.monotonic() + timeout_seconds
        with self._condition:
            while self._sequence <= sequence and time.monotonic() < deadline:
                self._condition.wait(deadline - time.monotonic())
            if not self._events:
                return False, []
            earliest = self._events[0]["sequence"]
            gap = sequence < earliest - 1
            events = [event for event in self._events if event["sequence"] > sequence]
            return gap, events

    def _rotate_if_needed(self, active_operation_id: str | None) -> None:
        try:
            journal_size = EVENT_JOURNAL.stat().st_size
        except OSError:
            journal_size = 0
        if len(self._events) <= MAX_EVENTS and journal_size <= MAX_JOURNAL_BYTES:
            return
        retained: list[dict[str, Any]] = []
        if active_operation_id is not None:
            for event in self._events:
                if event.get("operationId") == active_operation_id:
                    retained.append(event)
        tail = list(self._events)[-MAX_JOURNAL_TAIL:]
        by_sequence = {int(event["sequence"]): event for event in retained + tail}
        ordered = [by_sequence[key] for key in sorted(by_sequence)]
        self._events = collections.deque(ordered)
        content = b"".join(
            json.dumps(
                event, ensure_ascii=False, separators=(",", ":"), sort_keys=True
            ).encode("utf-8")
            + b"\n"
            for event in ordered
        )
        atomic_write_bytes(EVENT_JOURNAL, content, 0o600)


class PiRpcChild:
    def __init__(self, bridge: "PiDeckBridge"):
        self.bridge = bridge
        self.process: subprocess.Popen[bytes] | None = None
        self.metadata: dict[str, Any] | None = None
        self._writer_lock = threading.Lock()
        self._stop_expected = False

    def start(self) -> None:
        if self.process is not None and self.process.poll() is None:
            return
        config = self.bridge.config
        model_id = require_string(config, "modelId", 128)
        model_by_id(model_id)
        profile = require_string(config, "accessProfile", 32)
        operation_id = require_uuid4(config, "bootstrapOperationId")
        session_id = config.get("sessionId")
        if session_id is not None:
            require_uuid4({"sessionId": session_id}, "sessionId")

        arguments = [
            str(BASE / "runtime" / "bin" / "pi"),
            "--mode",
            "rpc",
            "--provider",
            "pideck",
            "--model",
            model_id,
            "--thinking",
            "off",
            "--session-dir",
            str(BASE / "sessions"),
            "--approve",
            "--offline",
            "--no-extensions",
        ]
        if session_id:
            arguments.extend(["--session-id", str(session_id)])
        arguments.extend(self._profile_arguments(profile))
        environment = managed_environment(operation_id)
        environment["PI_CODING_AGENT_DIR"] = str(BASE / "pi")
        environment["PI_CODING_AGENT_SESSION_DIR"] = str(BASE / "sessions")
        PI_STDERR_LOG.parent.mkdir(parents=True, exist_ok=True)
        stderr_log = PI_STDERR_LOG.open("ab", buffering=0)
        try:
            process = subprocess.Popen(
                arguments,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=stderr_log,
                env=environment,
                cwd=BASE / "workspace",
                start_new_session=True,
                close_fds=True,
            )
        finally:
            stderr_log.close()
        self.process = process
        self._stop_expected = False
        self.metadata = metadata_for_process(
            process,
            arguments,
            operation_id,
            "pi",
            {"modelId": model_id, "accessProfile": profile},
        )
        atomic_write_json(PI_CHILD_METADATA, self.metadata)
        self.bridge.journal.append(
            "PI_STARTED",
            None,
            self.bridge.session_id,
            {"pid": process.pid, "modelId": model_id, "accessProfile": profile},
        )
        threading.Thread(target=self._read_stdout, name="pideck-pi-stdout", daemon=True).start()
        threading.Thread(target=self._wait_for_exit, name="pideck-pi-exit", daemon=True).start()

    def _profile_arguments(self, profile: str) -> list[str]:
        if profile == "read_only":
            return ["--tools", "read,grep,find,ls"]
        if profile == "confirm_changes":
            if not PERMISSION_EXTENSION.is_file():
                raise PiDeckError(
                    "PERMISSION_GATE_MISSING", "Permission-gate extension is not installed"
                )
            return [
                "--no-builtin-tools",
                "--tools",
                "read,grep,find,ls,pideck_bash,pideck_edit,pideck_write",
                "--extension",
                str(PERMISSION_EXTENSION),
            ]
        if profile == "autonomous":
            return ["--tools", "read,bash,edit,write,grep,find,ls"]
        raise PiDeckError("INVALID_PROFILE", "Unknown access profile")

    def send(self, value: dict[str, Any]) -> None:
        encoded = json.dumps(
            value, ensure_ascii=False, separators=(",", ":"), sort_keys=True
        ).encode("utf-8")
        if len(encoded) > 128 * 1024:
            raise PiDeckError("RPC_COMMAND_TOO_LARGE", "Pi RPC command exceeds limit")
        with self._writer_lock:
            if self.process is None or self.process.poll() is not None or self.process.stdin is None:
                raise PiDeckError("PI_UNAVAILABLE", "Pi RPC child is not running")
            try:
                self.process.stdin.write(encoded + b"\n")
                self.process.stdin.flush()
            except (BrokenPipeError, OSError) as error:
                raise PiDeckError("RPC_EOF", "Pi RPC stdin is closed") from error

    def _read_stdout(self) -> None:
        process = self.process
        if process is None or process.stdout is None:
            return
        while True:
            line = process.stdout.readline(MAX_EVENT_BYTES + 1)
            if not line:
                break
            if len(line) > MAX_EVENT_BYTES or not line.endswith(b"\n"):
                self.bridge.protocol_error("Pi RPC emitted an oversized/unframed JSONL line")
                while line and not line.endswith(b"\n"):
                    line = process.stdout.readline(MAX_EVENT_BYTES + 1)
                continue
            try:
                value = json.loads(line.decode("utf-8"))
            except (UnicodeError, json.JSONDecodeError):
                digest = hashlib.sha256(line).hexdigest()[:16]
                self.bridge.protocol_error(f"Malformed Pi RPC JSON line sha256={digest}")
                continue
            if not isinstance(value, dict) or not isinstance(value.get("type"), str):
                self.bridge.protocol_error("Pi RPC event misses required type")
                continue
            self.bridge.handle_pi_message(value)

    def _wait_for_exit(self) -> None:
        process = self.process
        if process is None:
            return
        exit_code = process.wait()
        self.bridge.handle_pi_exit(exit_code, self._stop_expected)

    def stop(self) -> bool:
        self._stop_expected = True
        metadata = self.metadata
        if metadata is None and PI_CHILD_METADATA.is_file():
            try:
                metadata = read_json(PI_CHILD_METADATA)
            except PiDeckError:
                metadata = None
        if metadata and process_alive(metadata):
            stopped = terminate_exact(metadata)
        else:
            stopped = True
        if stopped:
            PI_CHILD_METADATA.unlink(missing_ok=True)
        return stopped


class PiDeckBridge:
    def __init__(self, config: dict[str, Any], token: bytes):
        self.config = config
        self.token = token
        self.bridge_instance_id = str(uuid.uuid4())
        self.session_id = config.get("sessionId")
        self.journal = EventJournal(self.bridge_instance_id)
        self._lock = threading.RLock()
        self._shutdown = threading.Event()
        self.active_operation_id: str | None = None
        self.abort_requested = False
        self.last_answer = ""
        self.active_failed_reason: str | None = None
        self.pending_approvals: dict[str, dict[str, Any]] = {}
        self.pending_new_session: dict[str, str] | None = None
        self.seen_commands: collections.OrderedDict[str, str] = collections.OrderedDict()
        self.last_client_seen = time.monotonic()
        self.child = PiRpcChild(self)
        self.child.start()
        self.journal.append(
            "BRIDGE_READY",
            None,
            self.session_id,
            {
                "protocolVersion": 1,
                "modelId": config.get("modelId"),
                "accessProfile": config.get("accessProfile"),
            },
        )
        self.journal.append(
            "SERVER_STATE_CHANGED",
            None,
            self.session_id,
            {"server": read_server_status()},
        )
        threading.Thread(
            target=self._approval_janitor, name="pideck-approval-ttl", daemon=True
        ).start()

    def command(self, request: dict[str, Any]) -> dict[str, Any]:
        if request.get("schemaVersion") != 1:
            raise PiDeckError("UNSUPPORTED_SCHEMA", "Unsupported bridge command schema")
        operation_id = require_uuid4(request)
        command_type = require_string(request, "type", 64).upper()
        payload = request.get("payload", {})
        if not isinstance(payload, dict):
            raise PiDeckError("MALFORMED_COMMAND", "Command payload must be an object")
        with self._lock:
            if command_type != "APPROVAL_DECISION":
                if operation_id in self.seen_commands:
                    raise PiDeckError(
                        "DUPLICATE_OPERATION",
                        "This operationId was already accepted; command was not replayed",
                    )
                self.seen_commands[operation_id] = command_type
                while len(self.seen_commands) > 1000:
                    self.seen_commands.popitem(last=False)

            if command_type == "PROMPT":
                return self._prompt(operation_id, payload)
            if command_type == "ABORT":
                return self._abort(operation_id, payload)
            if command_type == "NEW_SESSION":
                return self._new_session(operation_id, payload)
            if command_type == "APPROVAL_DECISION":
                return self._approval_decision(operation_id, payload)
            if command_type == "GET_STATE":
                self.child.send({"id": operation_id, "type": "get_state"})
                return {"accepted": True, "operationId": operation_id}
            raise PiDeckError("UNKNOWN_COMMAND", f"Unknown bridge command: {command_type}")

    def _ensure_child(self) -> None:
        if self.child.process is None or self.child.process.poll() is not None:
            if self.active_operation_id is not None:
                raise PiDeckError("RECONCILE_REQUIRED", "Pi exited during an active turn")
            self.child = PiRpcChild(self)
            self.child.start()

    def _prompt(self, operation_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        if self.active_operation_id is not None:
            raise PiDeckError(
                "TURN_ALREADY_ACTIVE",
                f"Active operation must finish first: {self.active_operation_id}",
            )
        message = require_string(payload, "message", MAX_PROMPT_BYTES)
        supplied_session = payload.get("sessionId")
        if supplied_session is not None and supplied_session != self.session_id:
            raise PiDeckError("SESSION_MISMATCH", "Prompt targets a different session")
        self._ensure_child()
        self.active_operation_id = operation_id
        self.abort_requested = False
        self.active_failed_reason = None
        self.last_answer = ""
        try:
            self.child.send({"id": operation_id, "type": "prompt", "message": message})
        except Exception:
            self.active_operation_id = None
            raise
        return {"accepted": True, "operationId": operation_id}

    def _abort(self, control_operation_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        target = require_string(payload, "targetOperationId", 36)
        require_uuid4({"target": target}, "target")
        if self.active_operation_id != target:
            raise PiDeckError("TARGET_NOT_ACTIVE", "Abort target is not the active turn")
        if self.abort_requested:
            return {
                "accepted": True,
                "operationId": control_operation_id,
                "targetOperationId": target,
                "idempotent": True,
            }
        self.abort_requested = True
        self.child.send({"id": control_operation_id, "type": "abort"})
        threading.Thread(
            target=self._abort_fallback,
            args=(target,),
            name="pideck-abort-fallback",
            daemon=True,
        ).start()
        return {
            "accepted": True,
            "operationId": control_operation_id,
            "targetOperationId": target,
            "idempotent": False,
        }

    def _abort_fallback(self, target: str) -> None:
        deadline = time.monotonic() + 8.0
        while time.monotonic() < deadline:
            with self._lock:
                if self.active_operation_id != target:
                    return
            time.sleep(0.1)
        with self._lock:
            if self.active_operation_id != target:
                return
            self._deny_all_approvals("Pi child restart after abort timeout")
            if not self.child.stop():
                self.journal.append(
                    "BRIDGE_ERROR",
                    target,
                    self.session_id,
                    {
                        "code": "ABORT_UNCONFIRMED",
                        "message": "Exact Pi process exit could not be confirmed",
                    },
                )
                return
            self.active_operation_id = None
            self.abort_requested = False
            self.journal.append(
                "TURN_ABORTED",
                target,
                self.session_id,
                {"fallback": "exact-process-group"},
                terminal=True,
            )
            self.child = PiRpcChild(self)
            try:
                self.child.start()
            except PiDeckError as error:
                self.protocol_error(error.message)

    def _new_session(
        self, operation_id: str, payload: dict[str, Any]
    ) -> dict[str, Any]:
        if self.active_operation_id is not None:
            raise PiDeckError("TURN_ALREADY_ACTIVE", "Cannot change session during a turn")
        logical_session = require_string(payload, "sessionId", 36)
        require_uuid4({"sessionId": logical_session}, "sessionId")
        self._ensure_child()
        self.child.send({"id": operation_id, "type": "new_session"})
        self.pending_new_session = {
            "operationId": operation_id,
            "requestedSessionId": logical_session,
        }
        return {
            "accepted": True,
            "operationId": operation_id,
            "sessionId": logical_session,
        }

    def _approval_decision(
        self, operation_id: str, payload: dict[str, Any]
    ) -> dict[str, Any]:
        approval_id = require_string(payload, "approvalId", 128)
        confirmed = payload.get("confirmed")
        if not isinstance(confirmed, bool):
            raise PiDeckError("MALFORMED_APPROVAL", "Approval decision must be boolean")
        pending = self.pending_approvals.get(approval_id)
        if pending is None:
            raise PiDeckError("APPROVAL_NOT_PENDING", "Approval is unknown or already resolved")
        if pending["operationId"] != operation_id:
            raise PiDeckError("APPROVAL_OPERATION_MISMATCH", "Approval belongs to another turn")
        if time.monotonic() > pending["expiresMonotonic"]:
            try:
                self.child.send(
                    {
                        "type": "extension_ui_response",
                        "id": approval_id,
                        "cancelled": True,
                    }
                )
            except PiDeckError:
                pass
            self._resolve_approval(approval_id, False, "expired")
            raise PiDeckError("APPROVAL_EXPIRED", "Approval has expired")
        self.child.send(
            {
                "type": "extension_ui_response",
                "id": approval_id,
                "confirmed": confirmed,
            }
        )
        self._resolve_approval(approval_id, confirmed, "android")
        return {
            "accepted": True,
            "operationId": operation_id,
            "approvalId": approval_id,
            "confirmed": confirmed,
        }

    def handle_pi_message(self, value: dict[str, Any]) -> None:
        with self._lock:
            message_type = value["type"]
            if message_type == "response":
                self._handle_response(value)
                return
            if message_type == "extension_ui_request":
                self._handle_extension_ui(value)
                return
            operation_id = self.active_operation_id
            if message_type == "agent_start":
                self.journal.append("TURN_STARTED", operation_id, self.session_id)
            elif message_type == "message_update":
                update = value.get("assistantMessageEvent")
                if isinstance(update, dict) and update.get("type") == "text_delta":
                    delta = update.get("delta")
                    if isinstance(delta, str):
                        self.last_answer += delta
                        self.journal.append(
                            "MODEL_OUTPUT_DELTA",
                            operation_id,
                            self.session_id,
                            {"delta": bounded_text(delta, 64 * 1024)},
                        )
                elif isinstance(update, dict) and update.get("type") == "error":
                    self.active_failed_reason = bounded_text(update.get("error", "model error"), 2048)
                elif isinstance(update, dict) and update.get("type") == "toolcall_end":
                    tool_call = update.get("toolCall")
                    if isinstance(tool_call, dict):
                        self.journal.append(
                            "TOOL_CALL_REQUESTED",
                            operation_id,
                            self.session_id,
                            {
                                "toolName": tool_call.get("name"),
                                "args": bounded_text(
                                    tool_call.get("arguments", {}), 32 * 1024
                                ),
                                "toolCallId": tool_call.get("id"),
                            },
                        )
            elif message_type == "message_end":
                candidate = self._assistant_text(value.get("message"))
                if candidate:
                    self.last_answer = candidate
            elif message_type == "tool_execution_start":
                self.journal.append(
                    "TOOL_CALL_STARTED",
                    operation_id,
                    self.session_id,
                    {
                        "toolName": value.get("toolName"),
                        "args": bounded_text(value.get("args", {}), 32 * 1024),
                        "toolCallId": value.get("toolCallId"),
                    },
                )
            elif message_type == "tool_execution_end":
                self.journal.append(
                    "TOOL_CALL_COMPLETED",
                    operation_id,
                    self.session_id,
                    {
                        "toolName": value.get("toolName"),
                        "toolCallId": value.get("toolCallId"),
                        "isError": value.get("isError") is True,
                        "resultPreview": bounded_text(value.get("result", ""), 16 * 1024),
                    },
                )
                if value.get("isError") is True:
                    self.active_failed_reason = bounded_text(value.get("result", "tool error"), 2048)
            elif message_type == "agent_end":
                messages = value.get("messages")
                if isinstance(messages, list):
                    for message in messages:
                        candidate = self._assistant_text(message)
                        if candidate:
                            self.last_answer = candidate
            elif message_type == "agent_settled":
                self._complete_active_turn()
            elif message_type == "extension_error":
                self.protocol_error(
                    "Pi extension error: " + bounded_text(value.get("error", ""), 2048)
                )
            elif message_type not in {
                "turn_start",
                "turn_end",
                "message_start",
                "queue_update",
                "compaction_start",
                "compaction_end",
                "auto_retry_start",
                "auto_retry_end",
            }:
                self.journal.append(
                    "DIAGNOSTIC",
                    operation_id,
                    self.session_id,
                    {"protocolType": message_type},
                )

    def _handle_response(self, value: dict[str, Any]) -> None:
        command_id = value.get("id")
        command = value.get("command")
        success = value.get("success") is True
        if command == "prompt" and command_id == self.active_operation_id:
            if success:
                self.journal.append(
                    "TURN_ACCEPTED", command_id, self.session_id, {"accepted": True}
                )
            else:
                reason = bounded_text(value.get("error", "Pi rejected prompt"), 2048)
                self.journal.append(
                    "TURN_FAILED",
                    command_id,
                    self.session_id,
                    {"error": reason, "accepted": False},
                    terminal=True,
                )
                self.active_operation_id = None
        elif command == "abort":
            self.journal.append(
                "DIAGNOSTIC",
                self.active_operation_id,
                self.session_id,
                {"abortAccepted": success},
            )
        elif command == "new_session":
            pending = self.pending_new_session
            if pending is None or command_id != pending["operationId"]:
                return
            if success:
                self.child.send(
                    {
                        "id": "session-state:" + pending["operationId"],
                        "type": "get_state",
                    }
                )
            else:
                self.journal.append(
                    "TURN_FAILED",
                    pending["operationId"],
                    self.session_id,
                    {"error": bounded_text(value.get("error", "Pi rejected new session"), 2048)},
                    terminal=True,
                )
                self.pending_new_session = None
        elif command == "get_state" and success:
            data = value.get("data")
            if (
                isinstance(command_id, str)
                and command_id.startswith("session-state:")
                and self.pending_new_session is not None
            ):
                pending = self.pending_new_session
                actual_session_id = data.get("sessionId") if isinstance(data, dict) else None
                try:
                    parsed_session = uuid.UUID(str(actual_session_id))
                except (ValueError, TypeError, AttributeError):
                    parsed_session = None
                if parsed_session is None:
                    self.journal.append(
                        "TURN_FAILED",
                        pending["operationId"],
                        self.session_id,
                        {"error": "Pi get_state did not return a valid sessionId"},
                        terminal=True,
                    )
                else:
                    self.session_id = str(parsed_session)
                    self.config["sessionId"] = self.session_id
                    atomic_write_json(BRIDGE_CONFIG, self.config, 0o600)
                    self.journal.append(
                        "SESSION_CREATED",
                        pending["operationId"],
                        self.session_id,
                        {"sessionId": self.session_id},
                        terminal=True,
                    )
                self.pending_new_session = None
                return
            self.journal.append(
                "DIAGNOSTIC",
                None,
                self.session_id,
                {"state": data if isinstance(data, dict) else {}},
            )

    def _handle_extension_ui(self, value: dict[str, Any]) -> None:
        method = value.get("method")
        approval_id = value.get("id")
        if method != "confirm":
            self.journal.append(
                "DIAGNOSTIC",
                self.active_operation_id,
                self.session_id,
                {"extensionUiMethod": method},
            )
            return
        if (
            self.active_operation_id is None
            or not isinstance(approval_id, str)
            or not approval_id
            or approval_id in self.pending_approvals
        ):
            if isinstance(approval_id, str):
                self.child.send(
                    {"type": "extension_ui_response", "id": approval_id, "cancelled": True}
                )
            return
        expires = time.monotonic() + APPROVAL_TTL_SECONDS
        decision, message = split_decision(
            bounded_text(value.get("message", ""), 32 * 1024)
        )
        pending = {
            "operationId": self.active_operation_id,
            "expiresMonotonic": expires,
            "title": bounded_text(value.get("title", "Allow change?"), 512),
            "message": message,
        }
        self.pending_approvals[approval_id] = pending
        payload = {
            "approvalId": approval_id,
            "title": pending["title"],
            "message": pending["message"],
            "expiresAtEpochMs": int(
                (time.time() + APPROVAL_TTL_SECONDS) * 1000
            ),
        }
        if decision is not None:
            payload["decision"] = decision
        self.journal.append(
            "APPROVAL_REQUESTED",
            self.active_operation_id,
            self.session_id,
            payload,
        )

    def _complete_active_turn(self) -> None:
        operation_id = self.active_operation_id
        if operation_id is None:
            return
        if self.abort_requested:
            event_type = "TURN_ABORTED"
            payload = {"answer": bounded_text(self.last_answer, 256 * 1024)}
        elif self.active_failed_reason:
            event_type = "TURN_FAILED"
            payload = {
                "error": self.active_failed_reason,
                "answer": bounded_text(self.last_answer, 256 * 1024),
            }
        else:
            event_type = "TURN_COMPLETED"
            payload = {"answer": bounded_text(self.last_answer, 256 * 1024)}
        self._deny_all_approvals("turn terminal")
        self.journal.append(
            event_type,
            operation_id,
            self.session_id,
            payload,
            terminal=True,
        )
        self.active_operation_id = None
        self.abort_requested = False
        self.active_failed_reason = None
        self.last_answer = ""

    def _assistant_text(self, message: Any) -> str:
        if not isinstance(message, dict) or message.get("role") != "assistant":
            return ""
        content = message.get("content")
        if isinstance(content, str):
            return content
        if not isinstance(content, list):
            return ""
        parts: list[str] = []
        for part in content:
            if isinstance(part, dict) and part.get("type") == "text":
                text = part.get("text")
                if isinstance(text, str):
                    parts.append(text)
        return "\n".join(parts)

    def _resolve_approval(
        self, approval_id: str, confirmed: bool, source: str
    ) -> None:
        pending = self.pending_approvals.pop(approval_id, None)
        if pending is None:
            return
        audit = {
            "schemaVersion": 1,
            "timestamp": utc_now(),
            "approvalId": approval_id,
            "operationId": pending["operationId"],
            "confirmed": confirmed,
            "source": source,
            # Deliberately no command/content; only a hash allows later correlation.
            "summarySha256": hashlib.sha256(
                (pending["title"] + "\n" + pending["message"]).encode("utf-8")
            ).hexdigest(),
        }
        encoded = json.dumps(
            audit, ensure_ascii=False, separators=(",", ":"), sort_keys=True
        ).encode("utf-8")
        try:
            current_size = AUDIT_LOG.stat().st_size
        except OSError:
            current_size = 0
        if current_size + len(encoded) + 1 > MAX_AUDIT_BYTES:
            try:
                tail = AUDIT_LOG.read_bytes()[-AUDIT_RETAIN_BYTES:]
            except OSError:
                tail = b""
            first_complete_line = tail.find(b"\n")
            if first_complete_line >= 0:
                tail = tail[first_complete_line + 1 :]
            else:
                tail = b""
            atomic_write_bytes(AUDIT_LOG, tail, 0o600)
        with AUDIT_LOG.open("ab", buffering=0) as output:
            output.write(encoded + b"\n")
            os.fsync(output.fileno())
        self.journal.append(
            "APPROVAL_RESOLVED",
            pending["operationId"],
            self.session_id,
            {
                "approvalId": approval_id,
                "confirmed": confirmed,
                "source": source,
            },
        )

    def _deny_all_approvals(self, source: str) -> None:
        for approval_id in list(self.pending_approvals):
            try:
                self.child.send(
                    {"type": "extension_ui_response", "id": approval_id, "cancelled": True}
                )
            except PiDeckError:
                pass
            self._resolve_approval(approval_id, False, source)

    def _approval_janitor(self) -> None:
        while not self._shutdown.wait(0.5):
            with self._lock:
                now = time.monotonic()
                expired = [
                    approval_id
                    for approval_id, pending in self.pending_approvals.items()
                    if now > pending["expiresMonotonic"]
                ]
                for approval_id in expired:
                    try:
                        self.child.send(
                            {
                                "type": "extension_ui_response",
                                "id": approval_id,
                                "cancelled": True,
                            }
                        )
                    except PiDeckError:
                        pass
                    self._resolve_approval(approval_id, False, "expired")

    def handle_pi_exit(self, exit_code: int, expected: bool) -> None:
        with self._lock:
            operation_id = self.active_operation_id
            self.journal.append(
                "PI_EXITED",
                operation_id,
                self.session_id,
                {"exitCode": exit_code, "expected": expected},
            )
            self._deny_all_approvals("pi child exit")
            if operation_id is not None:
                self.journal.append(
                    "TURN_FAILED",
                    operation_id,
                    self.session_id,
                    {
                        "error": "Pi RPC child exited; turn was not replayed",
                        "reconcileRequired": True,
                    },
                    terminal=True,
                )
                self.active_operation_id = None
                self.abort_requested = False

    def protocol_error(self, message: str) -> None:
        self.journal.append(
            "BRIDGE_ERROR",
            self.active_operation_id,
            self.session_id,
            {"code": "PROTOCOL_ERROR", "message": bounded_text(message, 2048)},
        )

    def state(self) -> dict[str, Any]:
        with self._lock:
            return {
                "schemaVersion": 1,
                "bridgeInstanceId": self.bridge_instance_id,
                "lastSequence": self.journal.sequence,
                "activeOperationId": self.active_operation_id,
                "pendingNewSessionOperationId": (
                    self.pending_new_session["operationId"]
                    if self.pending_new_session is not None
                    else None
                ),
                "sessionId": self.session_id,
                "modelId": self.config.get("modelId"),
                "accessProfile": self.config.get("accessProfile"),
                "piAlive": self.child.process is not None
                and self.child.process.poll() is None,
                "pendingApprovals": [
                    {
                        "approvalId": approval_id,
                        "operationId": pending["operationId"],
                        "title": pending["title"],
                    }
                    for approval_id, pending in self.pending_approvals.items()
                ],
                "server": read_server_status(),
            }

    def shutdown(self) -> None:
        with self._lock:
            if self._shutdown.is_set():
                return
            self._shutdown.set()
            self._deny_all_approvals("bridge shutdown")
            self.child.stop()
            self.journal.append(
                "PI_EXITED",
                self.active_operation_id,
                self.session_id,
                {"expected": True, "reason": "bridge shutdown"},
                terminal=True,
            )


class BridgeHttpServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = False

    def __init__(self, address: tuple[str, int], bridge: PiDeckBridge):
        self.bridge = bridge
        super().__init__(address, BridgeHandler)


class BridgeHandler(BaseHTTPRequestHandler):
    server: BridgeHttpServer
    protocol_version = "HTTP/1.1"

    def log_message(self, _format: str, *_args: Any) -> None:
        # Production HTTP access logs could include query state and are intentionally disabled.
        return

    def _authorized(self) -> bool:
        supplied = self.headers.get("X-PiDeck-Token", "").encode("utf-8")
        allowed = hmac.compare_digest(supplied, self.server.bridge.token)
        if allowed:
            self.server.bridge.last_client_seen = time.monotonic()
        return allowed

    def _json(self, status: int, value: dict[str, Any]) -> None:
        encoded = json.dumps(
            value, ensure_ascii=False, separators=(",", ":"), sort_keys=True
        ).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(encoded)

    def _error(self, error: PiDeckError) -> None:
        self._json(
            HTTPStatus.BAD_REQUEST,
            {"schemaVersion": 1, "ok": False, "error": error.as_dict()},
        )

    def _require_auth(self) -> bool:
        if self._authorized():
            return True
        self._json(
            HTTPStatus.UNAUTHORIZED,
            {
                "schemaVersion": 1,
                "ok": False,
                "error": {"code": "UNAUTHORIZED", "message": "Unauthorized"},
            },
        )
        return False

    def do_GET(self) -> None:  # noqa: N802
        if not self._require_auth():
            return
        parsed = urlparse(self.path)
        if parsed.path == "/v1/health":
            self._json(
                HTTPStatus.OK,
                {
                    "schemaVersion": 1,
                    "ok": True,
                    "status": "ok",
                    "bridgeInstanceId": self.server.bridge.bridge_instance_id,
                },
            )
            return
        if parsed.path == "/v1/state":
            self._json(
                HTTPStatus.OK,
                {
                    "schemaVersion": 1,
                    "ok": True,
                    "state": self.server.bridge.state(),
                },
            )
            return
        if parsed.path == "/v1/events":
            query = parse_qs(parsed.query)
            try:
                after = max(0, int(query.get("after", ["0"])[0]))
                timeout_ms = min(
                    25_000, max(0, int(query.get("timeoutMs", ["20000"])[0]))
                )
            except ValueError:
                self._error(PiDeckError("INVALID_QUERY", "Invalid event cursor"))
                return
            gap, events = self.server.bridge.journal.after(after, timeout_ms / 1000)
            self._json(
                HTTPStatus.OK,
                {
                    "schemaVersion": 1,
                    "ok": True,
                    "bridgeInstanceId": self.server.bridge.bridge_instance_id,
                    "eventGap": gap,
                    "events": events,
                    "lastSequence": self.server.bridge.journal.sequence,
                },
            )
            return
        self._json(
            HTTPStatus.NOT_FOUND,
            {
                "schemaVersion": 1,
                "ok": False,
                "error": {"code": "NOT_FOUND", "message": "Not found"},
            },
        )

    def _body(self) -> dict[str, Any]:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError as error:
            raise PiDeckError("MALFORMED_HTTP", "Invalid Content-Length") from error
        if length <= 0 or length > 128 * 1024:
            raise PiDeckError("BODY_TOO_LARGE", "Request body is outside the allowed size")
        raw = self.rfile.read(length)
        try:
            value = json.loads(raw.decode("utf-8"))
        except (UnicodeError, json.JSONDecodeError) as error:
            raise PiDeckError("MALFORMED_JSON", "Request body is not valid JSON") from error
        if not isinstance(value, dict):
            raise PiDeckError("MALFORMED_JSON", "Request body must be an object")
        return value

    def do_POST(self) -> None:  # noqa: N802
        if not self._require_auth():
            return
        parsed = urlparse(self.path)
        try:
            if parsed.path == "/v1/commands":
                response = self.server.bridge.command(self._body())
                self._json(
                    HTTPStatus.ACCEPTED,
                    {"schemaVersion": 1, "ok": True, **response},
                )
                return
            if parsed.path == "/v1/shutdown":
                self.server.bridge.shutdown()
                self._json(
                    HTTPStatus.OK,
                    {"schemaVersion": 1, "ok": True, "state": "STOPPING"},
                )
                threading.Thread(
                    target=self.server.shutdown, name="pideck-http-shutdown", daemon=True
                ).start()
                return
        except PiDeckError as error:
            self._error(error)
            return
        self._json(
            HTTPStatus.NOT_FOUND,
            {
                "schemaVersion": 1,
                "ok": False,
                "error": {"code": "NOT_FOUND", "message": "Not found"},
            },
        )


def serve(config_path: Path) -> int:
    config = read_json(config_path)
    require_uuid4(config, "bootstrapOperationId")
    if config.get("schemaVersion") != 1:
        raise PiDeckError("UNSUPPORTED_SCHEMA", "Unsupported bridge config schema")
    host = config.get("host")
    if host != "127.0.0.1":
        raise PiDeckError("UNSAFE_BIND", "Bridge may bind only to 127.0.0.1")
    port = int(config.get("port", 8787))
    if port < 1024 or port > 65535:
        raise PiDeckError("INVALID_PORT", "Bridge port is outside the allowed range")
    try:
        token = validated_token(BRIDGE_TOKEN.read_text(encoding="ascii"))
    except (OSError, UnicodeError) as error:
        raise PiDeckError("INVALID_TOKEN", "Bridge token is unavailable") from error

    bridge = PiDeckBridge(config, token)
    try:
        server = BridgeHttpServer((host, port), bridge)
    except OSError as error:
        bridge.shutdown()
        raise PiDeckError(
            "PORT_OCCUPIED",
            f"127.0.0.1:{port} is occupied by an unmanaged process; nothing was killed",
        ) from error
    stop_requested = threading.Event()

    def request_stop(_signum: int, _frame: Any) -> None:
        if stop_requested.is_set():
            return
        stop_requested.set()
        bridge.shutdown()
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGINT, request_stop)
    signal.signal(signal.SIGTERM, request_stop)
    try:
        server.serve_forever(poll_interval=0.25)
        return 0
    finally:
        bridge.shutdown()
        server.server_close()


def bootstrap_bridge(request: dict[str, Any]) -> dict[str, Any]:
    operation_id = require_uuid4(request)
    token = require_string(request, "token", 64)
    token_bytes = validated_token(token)
    token_sha256 = hashlib.sha256(token_bytes).hexdigest()
    model_id = require_string(request, "modelId", 128)
    model = model_by_id(model_id)
    profile = require_string(request, "accessProfile", 32)
    if profile not in {"read_only", "confirm_changes", "autonomous"}:
        raise PiDeckError("INVALID_PROFILE", "Unknown access profile")
    session_id = request.get("sessionId")
    if session_id is not None:
        require_uuid4({"sessionId": session_id}, "sessionId")
    port = int(request.get("port", 8787))
    if port < 1024 or port > 65535:
        raise PiDeckError("INVALID_PORT", "Bridge port is outside the allowed range")
    server = read_server_status()
    if (
        server.get("state") != "READY"
        or server.get("modelId") != model_id
        or server.get("modelSha256") != model["artifact"]["sha256"]
    ):
        raise PiDeckError(
            "SERVER_NOT_READY",
            "Exact requested model is not READY in the managed llama-server",
        )
    try:
        server_port = int(server["port"])
        server_key = SERVER_API_KEY.read_text(encoding="ascii").strip()
        strict_health(server_port, model_id, server_key)
    except (KeyError, TypeError, ValueError, OSError, PiDeckError) as error:
        raise PiDeckError(
            "SERVER_NOT_READY",
            "Managed llama-server failed authenticated exact-model health verification",
        ) from error

    if BRIDGE_METADATA.is_file():
        try:
            existing = read_json(BRIDGE_METADATA)
        except PiDeckError:
            existing = {}
        if process_alive(existing):
            same = (
                existing.get("modelId") == model_id
                and existing.get("accessProfile") == profile
                and existing.get("sessionId") == session_id
                and int(existing.get("port", -1)) == port
                and existing.get("tokenSha256") == token_sha256
            )
            if same:
                try:
                    import urllib.request

                    health_request = urllib.request.Request(
                        f"http://127.0.0.1:{port}/v1/health",
                        headers={"X-PiDeck-Token": token},
                    )
                    with urllib.request.urlopen(health_request, timeout=1) as response:
                        health = json.loads(response.read(64 * 1024).decode("utf-8"))
                    if health.get("ok") is True and health.get("status") == "ok":
                        return {
                            "state": "READY",
                            "port": port,
                            "idempotent": True,
                        }
                except Exception:
                    pass
            if not terminate_exact(existing):
                raise PiDeckError("BRIDGE_BUSY", "Could not stop previous managed bridge")

    config = {
        "schemaVersion": 1,
        "bootstrapOperationId": operation_id,
        "modelId": model_id,
        "accessProfile": profile,
        "sessionId": session_id,
        "host": "127.0.0.1",
        "port": port,
        "createdAt": utc_now(),
    }
    atomic_write_bytes(BRIDGE_TOKEN, token_bytes, 0o600)
    atomic_write_json(BRIDGE_CONFIG, config, 0o600)
    arguments = [
        sys.executable,
        "-m",
        "pideck_runtime.launcher",
        "bridge-daemon",
        str(BRIDGE_CONFIG),
    ]
    log_path = BASE / "logs" / "bridge.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log = log_path.open("wb")
    try:
        process = subprocess.Popen(
            arguments,
            stdin=subprocess.DEVNULL,
            stdout=log,
            stderr=subprocess.STDOUT,
            env=managed_environment(operation_id),
            cwd=BASE / "workspace",
            start_new_session=True,
            close_fds=True,
        )
    finally:
        log.close()
    metadata = metadata_for_process(
        process,
        arguments,
        operation_id,
        "pideck_runtime.launcher",
        {
            "modelId": model_id,
            "accessProfile": profile,
            "sessionId": session_id,
            "port": port,
            "tokenSha256": token_sha256,
        },
    )
    atomic_write_json(BRIDGE_METADATA, metadata)
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if not process_alive(metadata):
            raise PiDeckError("BRIDGE_EXITED", "RPC bridge exited during startup")
        try:
            import urllib.request

            http_request = urllib.request.Request(
                f"http://127.0.0.1:{port}/v1/health",
                headers={"X-PiDeck-Token": token},
            )
            with urllib.request.urlopen(http_request, timeout=1) as response:
                body = json.loads(response.read(64 * 1024).decode("utf-8"))
            if body.get("ok") is True and body.get("status") == "ok":
                return {"state": "READY", "port": port, "idempotent": False}
        except Exception:
            time.sleep(0.2)
    terminate_exact(metadata)
    raise PiDeckError("BRIDGE_TIMEOUT", "RPC bridge did not become ready")


def stop_bridge() -> dict[str, Any]:
    if not BRIDGE_METADATA.is_file():
        return {"state": "STOPPED", "idempotent": True}
    try:
        metadata = read_json(BRIDGE_METADATA)
    except PiDeckError:
        return {"state": "STALE", "idempotent": True}
    if process_alive(metadata) and not terminate_exact(metadata):
        raise PiDeckError("BRIDGE_STOP_UNCONFIRMED", "Bridge did not confirm process exit")
    BRIDGE_METADATA.unlink(missing_ok=True)
    PI_CHILD_METADATA.unlink(missing_ok=True)
    return {"state": "STOPPED", "idempotent": False}
