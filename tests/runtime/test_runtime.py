"""Host-side tests for the Python runtime embedded in the APK."""

from __future__ import annotations

import collections
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from unittest import mock


REPOSITORY = Path(__file__).resolve().parents[2]
RUNTIME_ROOT = REPOSITORY / "app" / "src" / "main" / "assets" / "runtime"
TEST_ROOT = Path(tempfile.mkdtemp(prefix="pideck-runtime-tests-"))
os.environ["PIDECK_HOME"] = str(TEST_ROOT / "home")
os.environ["PREFIX"] = str(TEST_ROOT / "prefix")
sys.path.insert(0, str(RUNTIME_ROOT))

from pideck_runtime import bridge, common, launcher, model_store, server_supervisor  # noqa: E402


def operation_id() -> str:
    return str(uuid.uuid4())


def tiny_model(content: bytes, expected_sha: str | None = None) -> dict:
    return {
        "id": "fixture-model",
        "title": "Fixture",
        "tier": "NANO",
        "status": "EXPERIMENTAL",
        "license": {"spdx": "Apache-2.0"},
        "source": {
            "repository": "example/fixture-model",
            "revision": "a" * 40,
            "provenanceStatus": "INCOMPLETE",
        },
        "artifact": {
            "file": "fixture.gguf",
            "bytes": len(content),
            "sha256": expected_sha or hashlib.sha256(content).hexdigest(),
        },
        "runtime": {
            "recommendedContext": 1024,
            "maximumTestedContext": 2048,
            "parallelSlots": 1,
            "requiresJinja": True,
            "reasoningMode": "off",
            "serverArgs": [],
        },
        "sampling": {
            "temperature": 0.7,
            "topP": 0.8,
            "topK": 20,
            "minP": 0.0,
            "presencePenalty": 1.5,
        },
        "agent": {"maxTokens": 256},
    }


def install_catalog(model: dict) -> None:
    common.ensure_private_layout()
    common.atomic_write_json(
        common.BASE / "runtime" / "models-v2.json",
        {"schemaVersion": 2, "catalogVersion": "test", "models": [model]},
    )


class FakeProcess:
    def poll(self) -> None:
        return None


class FakeChild:
    def __init__(self, stop_result: bool = True) -> None:
        self.process = FakeProcess()
        self.sent: list[dict] = []
        self.stopped = False
        self.stop_result = stop_result

    def start(self) -> None:
        return

    def send(self, value: dict) -> None:
        self.sent.append(value)

    def stop(self) -> bool:
        self.stopped = True
        return self.stop_result


def fake_bridge() -> bridge.PiDeckBridge:
    value = object.__new__(bridge.PiDeckBridge)
    value.config = {
        "schemaVersion": 1,
        "modelId": "fixture-model",
        "accessProfile": "confirm_changes",
        "sessionId": operation_id(),
    }
    value.token = bridge.validated_token(
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    )
    value.bridge_instance_id = operation_id()
    value.session_id = value.config["sessionId"]
    value.journal = bridge.EventJournal(value.bridge_instance_id)
    value._lock = threading.RLock()
    value._shutdown = threading.Event()
    value.active_operation_id = None
    value.abort_requested = False
    value.last_answer = ""
    value.active_failed_reason = None
    value.pending_approvals = {}
    value.pending_new_session = None
    value.seen_commands = collections.OrderedDict()
    value.last_client_seen = time.monotonic()
    value.child = FakeChild()
    return value


class RuntimeTestCase(unittest.TestCase):
    def setUp(self) -> None:
        shutil.rmtree(common.BASE, ignore_errors=True)
        common.ensure_private_layout()

    def test_exact_process_identity_and_pid_reuse_fixture(self) -> None:
        identifier = operation_id()
        arguments = [
            sys.executable,
            "-c",
            "import time; time.sleep(30)",
        ]
        environment = os.environ.copy()
        environment["PIDECK_OPERATION_ID"] = identifier
        process = subprocess.Popen(
            arguments,
            env=environment,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
        try:
            metadata = common.metadata_for_process(
                process, arguments, identifier, Path(sys.executable).name
            )
            self.assertTrue(common.process_matches(metadata))
            partial_executable = dict(metadata)
            partial_executable["expectedExecutable"] = "py"
            self.assertFalse(common.process_matches(partial_executable))
            reused = dict(metadata)
            reused["procStartTicks"] += 1
            self.assertFalse(common.process_matches(reused))
            wrong_token = dict(metadata)
            wrong_token["operationId"] = operation_id()
            self.assertFalse(common.process_matches(wrong_token))
            self.assertTrue(
                common.terminate_exact(
                    metadata, interrupt_seconds=0.5, terminate_seconds=0.5
                )
            )
            process.wait(timeout=3)
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=3)

    def test_private_install_is_atomic_read_only_and_idempotent(self) -> None:
        content = b"GGUF-fixture-content"
        model = tiny_model(content)
        install_catalog(model)
        source = TEST_ROOT / "incoming.gguf"
        source.write_bytes(content)
        with mock.patch.object(model_store, "_allowed_source", return_value=source):
            first = model_store.install_private(model["id"], str(source))
            second = model_store.install_private(model["id"], str(source))
        destination = model_store.private_model_path(model)
        self.assertEqual("READY", first["state"])
        self.assertFalse(first["idempotent"])
        self.assertTrue(second["idempotent"])
        self.assertEqual(content, destination.read_bytes())
        self.assertEqual(0o400, destination.stat().st_mode & 0o777)
        self.assertEqual(model["artifact"]["sha256"], first["sha256"])

    def test_private_install_source_allowlist_accepts_legacy_migration_only(self) -> None:
        incoming = TEST_ROOT / "Download" / "PiDeck" / "incoming"
        legacy = TEST_ROOT / "Download" / "PiDeck" / "models"
        outside = TEST_ROOT / "Download" / "other"
        for directory in (incoming, legacy, outside):
            directory.mkdir(parents=True, exist_ok=True)
        incoming_file = incoming / "new.gguf"
        legacy_file = legacy / "old.gguf"
        outside_file = outside / "outside.gguf"
        for file in (incoming_file, legacy_file, outside_file):
            file.write_bytes(b"GGUF")
        with mock.patch.object(
            model_store,
            "_managed_source_candidates",
            return_value=(incoming, legacy),
        ):
            self.assertEqual(
                incoming_file.resolve(), model_store._allowed_source(incoming_file)
            )
            self.assertEqual(
                legacy_file.resolve(), model_store._allowed_source(legacy_file)
            )
            with self.assertRaises(common.PiDeckError) as raised:
                model_store._allowed_source(outside_file)
        self.assertEqual("SOURCE_OUTSIDE_INCOMING", raised.exception.code)

    def test_private_install_sha_mismatch_never_commits(self) -> None:
        content = b"bad artifact"
        model = tiny_model(content, expected_sha="0" * 64)
        install_catalog(model)
        source = TEST_ROOT / "bad.gguf"
        source.write_bytes(content)
        with mock.patch.object(model_store, "_allowed_source", return_value=source):
            with self.assertRaises(common.PiDeckError) as raised:
                model_store.install_private(model["id"], str(source))
        self.assertEqual("SHA_MISMATCH", raised.exception.code)
        self.assertFalse(model_store.private_model_path(model).exists())
        self.assertEqual([], list(model_store.model_directory(model).glob(".*.tmp-*")))

    def test_interrupted_private_install_removes_stage_before_rename(self) -> None:
        content = b"GGUF" * 1024
        model = tiny_model(content)
        install_catalog(model)
        source = TEST_ROOT / "interrupted.gguf"
        source.write_bytes(content)
        with (
            mock.patch.object(model_store, "_allowed_source", return_value=source),
            mock.patch.object(model_store, "_write_all", side_effect=OSError("cut")),
        ):
            with self.assertRaises(OSError):
                model_store.install_private(model["id"], str(source))
        self.assertFalse(model_store.private_model_path(model).exists())
        self.assertEqual([], list(model_store.model_directory(model).glob(".*.tmp-*")))

    def test_server_arguments_and_exact_health(self) -> None:
        content = b"GGUF"
        model = tiny_model(content)
        arguments = server_supervisor.effective_server_arguments(
            model, Path("/private/model.gguf"), 99, 8080, "secret"
        )
        self.assertEqual("127.0.0.1", arguments[arguments.index("--host") + 1])
        self.assertEqual("8", arguments[arguments.index("-t") + 1])
        self.assertEqual("fixture-model", arguments[arguments.index("--alias") + 1])
        self.assertEqual("secret", arguments[arguments.index("--api-key") + 1])
        with mock.patch.object(
            server_supervisor,
            "_request_json",
            side_effect=[
                {"status": "ok"},
                {"data": [{"id": "fixture-model-extra"}]},
            ],
        ):
            with self.assertRaises(common.PiDeckError) as raised:
                server_supervisor.strict_health(8080, "fixture-model", "secret")
        self.assertEqual("WRONG_MODEL", raised.exception.code)

        unsafe = tiny_model(content)
        unsafe["runtime"]["serverArgs"] = ["--host=0.0.0.0"]
        with self.assertRaises(common.PiDeckError) as raised:
            server_supervisor.effective_server_arguments(
                unsafe, Path("/private/model.gguf"), 4, 8080, "secret"
            )
        self.assertEqual("INVALID_CATALOG", raised.exception.code)

    def test_legacy_server_takeover_matches_only_exact_01x_command(self) -> None:
        model = tiny_model(b"GGUF")
        install_catalog(model)
        arguments = [
            "llama-server",
            "-m",
            str(
                common.BASE.parent
                / "storage"
                / "downloads"
                / "PiDeck"
                / "models"
                / model["artifact"]["file"]
            ),
            "--alias",
            model["id"],
            "--host",
            "127.0.0.1",
            "--port",
            "8080",
            "-c",
            "8192",
            "-np",
            "1",
            "-t",
            "7",
            "--jinja",
            "--reasoning",
            "off",
            "--temp",
            "0.7",
            "--top-p",
            "0.8",
            "--top-k",
            "20",
            "--min-p",
            "0.0",
            "--presence-penalty",
            "1.5",
        ]
        self.assertTrue(
            server_supervisor._legacy_arguments_recognized(arguments, 8080)
        )
        for index, replacement in (
            (6, "0.0.0.0"),
            (8, "8081"),
            (2, "/tmp/unmanaged.gguf"),
        ):
            changed = list(arguments)
            changed[index] = replacement
            self.assertFalse(
                server_supervisor._legacy_arguments_recognized(changed, 8080)
            )
        self.assertFalse(
            server_supervisor._legacy_arguments_recognized(arguments, 8081)
        )
        self.assertFalse(
            server_supervisor._legacy_arguments_recognized(
                [*arguments, "--api-key", "unmanaged"], 8080
            )
        )

    def test_legacy_server_takeover_rechecks_before_signalling(self) -> None:
        candidate = {
            "pid": 4242,
            "procStartTicks": 123,
            "arguments": ["llama-server"],
        }
        legacy_pid = common.BASE / "legacy-server.pid"
        legacy_pid.write_text("4242\n", encoding="ascii")
        with (
            mock.patch.object(
                server_supervisor, "_legacy_candidate", return_value=candidate
            ),
            mock.patch.object(
                server_supervisor, "_legacy_candidate_matches", return_value=False
            ),
            mock.patch.object(
                server_supervisor, "LEGACY_SERVER_PID", legacy_pid
            ),
            mock.patch.object(server_supervisor, "_wake_lock") as wake_lock,
            mock.patch.object(server_supervisor.os, "kill") as kill,
        ):
            self.assertTrue(server_supervisor._retire_legacy_server(8080))
        kill.assert_not_called()
        self.assertFalse(legacy_pid.exists())
        wake_lock.assert_called_once_with(False)

    def test_legacy_pidfile_is_bound_to_process_start_time(self) -> None:
        with (
            mock.patch.object(
                server_supervisor.os, "sysconf", return_value=100
            ),
            mock.patch.object(server_supervisor.time, "time", return_value=10_000.0),
            mock.patch.object(
                server_supervisor.time, "clock_gettime", return_value=2_000.0
            ),
        ):
            self.assertTrue(
                server_supervisor._process_started_before_file(100_000, 9_000.1)
            )
            self.assertFalse(
                server_supervisor._process_started_before_file(100_000, 8_990.0)
            )

    def test_supervisor_failed_start_cleans_wake_lock_and_keeps_failed_state(self) -> None:
        model = tiny_model(b"GGUF")
        install_catalog(model)
        config = {
            "schemaVersion": 1,
            "operationId": operation_id(),
            "modelId": model["id"],
            "threads": 4,
            "port": 8080,
        }
        config_path = common.BASE / "server" / "fixture-launch.json"
        common.atomic_write_json(config_path, config)
        common.atomic_write_bytes(server_supervisor.SERVER_API_KEY, b"secret")

        class ExitedChild:
            pid = 4242
            returncode = 17

            def poll(self) -> int:
                return 17

            def kill(self) -> None:
                raise AssertionError("An exited child must not be killed")

        wake_calls: list[bool] = []
        with (
            mock.patch.object(server_supervisor, "model_by_id", return_value=model),
            mock.patch.object(
                server_supervisor,
                "verify_private",
                return_value={"state": "READY"},
            ),
            mock.patch.object(
                server_supervisor,
                "effective_server_arguments",
                return_value=["llama-server"],
            ),
            mock.patch.object(
                server_supervisor.subprocess,
                "Popen",
                return_value=ExitedChild(),
            ),
            mock.patch.object(
                server_supervisor,
                "metadata_for_process",
                return_value={"pid": 4242},
            ),
            mock.patch.object(
                server_supervisor,
                "_wake_lock",
                side_effect=wake_calls.append,
            ),
            mock.patch.object(server_supervisor.signal, "signal"),
        ):
            self.assertEqual(23, server_supervisor.server_daemon(config_path))
        self.assertEqual([True, False], wake_calls)
        self.assertEqual(
            "FAILED", common.read_json(server_supervisor.SERVER_STATUS)["state"]
        )

    def test_bridge_token_is_exact_canonical_256_bit_base64url(self) -> None:
        valid = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        self.assertEqual(valid.encode("ascii"), bridge.validated_token(valid))
        for invalid in ("short", valid + "A", "é" * 43, "+" + valid[1:]):
            with self.assertRaises(common.PiDeckError):
                bridge.validated_token(invalid)

    def test_runtime_version_gate_is_exact_and_bounded(self) -> None:
        self.assertTrue(launcher._semver_at_least("v22.19.0", "22.19.0"))
        self.assertTrue(launcher._semver_at_least("node v24.4.1", "22.19.0"))
        self.assertFalse(launcher._semver_at_least("v22.18.9", "22.19.0"))
        self.assertFalse(launcher._semver_at_least("unknown", "22.19.0"))
        self.assertTrue(
            launcher._llama_in_range("version: 10092 (fixture)", "b10092", "b10092")
        )
        self.assertFalse(
            launcher._llama_in_range("version: 10093", "b10092", "b10092")
        )
        self.assertIsNone(launcher._llama_build("version: 0 (unknown)"))
        self.assertEqual(
            10092, launcher._llama_build("0.0.0-b10092-0")
        )
        self.assertEqual(
            "0.0.0-b10092-0",
            launcher._resolved_llama_version(
                "version: 0 (unknown)", "0.0.0-b10092-0"
            ),
        )
        self.assertEqual(
            "version: 10093",
            launcher._resolved_llama_version(
                "version: 10093", "0.0.0-b10092-0"
            ),
        )
        self.assertIsNone(
            launcher._resolved_llama_version(None, "0.0.0-b10092-0")
        )

    def test_bridge_bootstrap_rejects_unknown_model_before_process_start(self) -> None:
        install_catalog(tiny_model(b"GGUF"))
        with self.assertRaises(common.PiDeckError) as raised:
            bridge.bootstrap_bridge(
                {
                    "schemaVersion": 1,
                    "operationId": operation_id(),
                    "token": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                    "modelId": "deleted-model",
                    "accessProfile": "read_only",
                    "port": 8787,
                }
            )
        self.assertEqual("UNKNOWN_MODEL", raised.exception.code)

    def test_prompt_stream_terminal_event_and_duplicate_rejection(self) -> None:
        value = fake_bridge()
        identifier = operation_id()
        accepted = value.command(
            {
                "schemaVersion": 1,
                "operationId": identifier,
                "type": "PROMPT",
                "payload": {
                    "message": "secret marker",
                    "sessionId": value.session_id,
                },
            }
        )
        self.assertTrue(accepted["accepted"])
        self.assertEqual("secret marker", value.child.sent[-1]["message"])
        with self.assertRaises(common.PiDeckError) as raised:
            value.command(
                {
                    "schemaVersion": 1,
                    "operationId": identifier,
                    "type": "PROMPT",
                    "payload": {"message": "must not replay"},
                }
            )
        self.assertEqual("DUPLICATE_OPERATION", raised.exception.code)

        value.handle_pi_message({"type": "agent_start"})
        value.handle_pi_message(
            {
                "type": "message_update",
                "assistantMessageEvent": {"type": "text_delta", "delta": "Готово"},
            }
        )
        value.handle_pi_message(
            {
                "type": "message_update",
                "assistantMessageEvent": {
                    "type": "toolcall_end",
                    "toolCall": {
                        "id": "tool-1",
                        "name": "read",
                        "arguments": {"path": "README.md"},
                    },
                },
            }
        )
        value.handle_pi_message({"type": "agent_settled"})
        _gap, events = value.journal.after(0, 0)
        event_types = [event["type"] for event in events]
        self.assertIn("MODEL_OUTPUT_DELTA", event_types)
        self.assertIn("TOOL_CALL_REQUESTED", event_types)
        requested = [
            event for event in events if event["type"] == "TOOL_CALL_REQUESTED"
        ][-1]
        self.assertEqual("tool-1", requested["payload"]["toolCallId"])
        self.assertEqual("read", requested["payload"]["toolName"])
        terminal = [event for event in events if event["type"] == "TURN_COMPLETED"]
        self.assertEqual("Готово", terminal[-1]["payload"]["answer"])
        self.assertIsNone(value.active_operation_id)

    def test_abort_is_structured_and_terminal(self) -> None:
        value = fake_bridge()
        target = operation_id()
        value.active_operation_id = target
        value._abort_fallback = lambda _target: None
        control = operation_id()
        response = value.command(
            {
                "schemaVersion": 1,
                "operationId": control,
                "type": "ABORT",
                "payload": {"targetOperationId": target},
            }
        )
        self.assertTrue(response["accepted"])
        self.assertEqual("abort", value.child.sent[-1]["type"])
        value.handle_pi_message({"type": "agent_settled"})
        _gap, events = value.journal.after(0, 0)
        self.assertEqual("TURN_ABORTED", events[-1]["type"])

    def test_abort_fallback_never_claims_terminal_before_confirmed_exit(self) -> None:
        value = fake_bridge()
        target = operation_id()
        value.active_operation_id = target
        value.abort_requested = True
        value.child = FakeChild(stop_result=False)
        with mock.patch.object(bridge.time, "monotonic", side_effect=[0.0, 9.0]):
            value._abort_fallback(target)
        _gap, events = value.journal.after(0, 0)
        self.assertNotIn("TURN_ABORTED", [event["type"] for event in events])
        self.assertEqual("BRIDGE_ERROR", events[-1]["type"])
        self.assertEqual("ABORT_UNCONFIRMED", events[-1]["payload"]["code"])
        self.assertEqual(target, value.active_operation_id)

    def test_approval_ttl_replay_and_restart_are_deny_by_default(self) -> None:
        value = fake_bridge()
        identifier = operation_id()
        value.active_operation_id = identifier
        value._handle_extension_ui(
            {
                "type": "extension_ui_request",
                "id": "approval-1",
                "method": "confirm",
                "title": "Write?",
                "message": "target=/tmp/file",
            }
        )
        response = value._approval_decision(
            identifier, {"approvalId": "approval-1", "confirmed": True}
        )
        self.assertTrue(response["confirmed"])
        with self.assertRaises(common.PiDeckError):
            value._approval_decision(
                identifier, {"approvalId": "approval-1", "confirmed": True}
            )

        value._handle_extension_ui(
            {
                "type": "extension_ui_request",
                "id": "approval-expired",
                "method": "confirm",
                "title": "Expired?",
                "message": "must deny",
            }
        )
        value.pending_approvals["approval-expired"]["expiresMonotonic"] = 0
        with self.assertRaises(common.PiDeckError) as raised:
            value._approval_decision(
                identifier,
                {"approvalId": "approval-expired", "confirmed": True},
            )
        self.assertEqual("APPROVAL_EXPIRED", raised.exception.code)
        self.assertEqual(
            {"type": "extension_ui_response", "id": "approval-expired", "cancelled": True},
            value.child.sent[-1],
        )

        value._handle_extension_ui(
            {
                "type": "extension_ui_request",
                "id": "approval-restart",
                "method": "confirm",
                "title": "Restart?",
                "message": "must deny",
            }
        )
        value.shutdown()
        self.assertTrue(value.child.stopped)
        self.assertNotIn("approval-restart", value.pending_approvals)
        audits = [
            json.loads(line)
            for line in bridge.AUDIT_LOG.read_text(encoding="utf-8").splitlines()
        ]
        self.assertFalse(audits[-1]["confirmed"])
        self.assertNotIn("must deny", audits[-1])

    def test_malformed_json_line_is_isolated_and_stdout_eof_is_reportable(self) -> None:
        class CaptureBridge:
            def __init__(self) -> None:
                self.messages: list[dict] = []
                self.errors: list[str] = []

            def handle_pi_message(self, value: dict) -> None:
                self.messages.append(value)

            def protocol_error(self, message: str) -> None:
                self.errors.append(message)

        owner = CaptureBridge()
        child = bridge.PiRpcChild(owner)  # type: ignore[arg-type]

        class StreamProcess:
            stdout = io.BytesIO(b"not-json\n{\"type\":\"agent_start\"}\n")

        child.process = StreamProcess()  # type: ignore[assignment]
        child._read_stdout()
        self.assertEqual([{"type": "agent_start"}], owner.messages)
        self.assertEqual(1, len(owner.errors))

        value = fake_bridge()
        value.active_operation_id = operation_id()
        value.handle_pi_exit(9, False)
        _gap, events = value.journal.after(0, 0)
        self.assertIn("TURN_FAILED", [event["type"] for event in events])
        self.assertIsNone(value.active_operation_id)

    def test_stderr_flood_does_not_block_jsonl_stdout_reader(self) -> None:
        script = (
            "import json,sys;"
            "sys.stderr.write('x'*1048576);sys.stderr.flush();"
            "print(json.dumps({'type':'agent_start'}), flush=True)"
        )
        stderr_path = common.BASE / "logs" / "fake-pi.stderr.log"
        with stderr_path.open("wb") as stderr:
            process = subprocess.Popen(
                [sys.executable, "-c", script],
                stdout=subprocess.PIPE,
                stderr=stderr,
            )

            class CaptureBridge:
                messages: list[dict] = []
                errors: list[str] = []

                def handle_pi_message(self, value: dict) -> None:
                    self.messages.append(value)

                def protocol_error(self, message: str) -> None:
                    self.errors.append(message)

            owner = CaptureBridge()
            child = bridge.PiRpcChild(owner)  # type: ignore[arg-type]
            child.process = process
            child._read_stdout()
            process.wait(timeout=5)
            if process.stdout is not None:
                process.stdout.close()
        self.assertEqual([{"type": "agent_start"}], owner.messages)
        self.assertGreaterEqual(stderr_path.stat().st_size, 1024 * 1024)

    def test_event_rotation_gap_and_payload_truncation(self) -> None:
        with (
            mock.patch.object(bridge, "MAX_EVENTS", 3),
            mock.patch.object(bridge, "MAX_JOURNAL_TAIL", 2),
            mock.patch.object(bridge, "MAX_JOURNAL_BYTES", 100 * 1024 * 1024),
        ):
            journal = bridge.EventJournal(operation_id())
            for index in range(4):
                journal.append("DIAGNOSTIC", None, None, {"index": index})
            gap, events = journal.after(0, 0)
            self.assertTrue(gap)
            self.assertEqual([3, 4], [event["sequence"] for event in events])

        journal = bridge.EventJournal(operation_id())
        event = journal.append(
            "DIAGNOSTIC", None, None, {"text": "z" * (bridge.MAX_EVENT_BYTES + 1)}
        )
        self.assertTrue(event["payload"]["truncated"])

    def test_http_bridge_requires_token_and_uses_constant_contract(self) -> None:
        value = fake_bridge()
        server = bridge.BridgeHttpServer(("127.0.0.1", 0), value)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        port = server.server_address[1]
        try:
            with self.assertRaises(urllib.error.HTTPError) as raised:
                urllib.request.urlopen(
                    f"http://127.0.0.1:{port}/v1/health", timeout=2
                )
            self.assertEqual(401, raised.exception.code)
            raised.exception.close()
            request = urllib.request.Request(
                f"http://127.0.0.1:{port}/v1/health",
                headers={"X-PiDeck-Token": value.token.decode("ascii")},
            )
            with urllib.request.urlopen(request, timeout=2) as response:
                payload = json.loads(response.read().decode("utf-8"))
            self.assertTrue(payload["ok"])
            self.assertEqual(value.bridge_instance_id, payload["bridgeInstanceId"])
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)


class SessionListingTestCase(unittest.TestCase):
    def setUp(self) -> None:
        shutil.rmtree(common.BASE, ignore_errors=True)
        common.ensure_private_layout()

    def test_empty_session_directory_lists_nothing(self) -> None:
        listing = launcher.list_sessions()
        self.assertEqual("READY", listing["state"])
        self.assertEqual([], listing["sessions"])
        self.assertEqual(0, listing["count"])
        self.assertEqual(0, listing["totalBytes"])

    def test_listing_titles_a_session_from_its_first_user_message(self) -> None:
        identifier = str(uuid.uuid4())
        transcript = common.BASE / "sessions" / f"{identifier}.jsonl"
        transcript.write_text(
            "\n".join(
                [
                    json.dumps({"role": "user", "content": "объясни, что делает этот проект"}),
                    json.dumps({"role": "assistant", "content": "Читаю README."}),
                    json.dumps({"role": "user", "content": "а тесты?"}),
                ]
            ),
            encoding="utf-8",
        )

        listing = launcher.list_sessions()

        self.assertEqual(1, listing["count"])
        session = listing["sessions"][0]
        self.assertEqual(identifier, session["id"])
        self.assertEqual("объясни, что делает этот проект", session["title"])
        self.assertEqual(3, session["messages"])
        self.assertEqual(transcript.stat().st_size, session["bytes"])
        self.assertEqual(session["bytes"], listing["totalBytes"])

    def test_unreadable_session_still_appears_with_its_size(self) -> None:
        # Pi may change its transcript format; a session must not vanish because of it.
        opaque = common.BASE / "sessions" / "not-json.bin"
        opaque.write_bytes(b"\x00\x01\x02binary")

        listing = launcher.list_sessions()

        self.assertEqual(1, listing["count"])
        session = listing["sessions"][0]
        self.assertEqual("not-json", session["id"])
        self.assertEqual("", session["title"])
        self.assertEqual(0, session["messages"])
        self.assertEqual(opaque.stat().st_size, session["bytes"])

    def test_listing_is_newest_first_and_bounded(self) -> None:
        for index in range(launcher.MAX_LISTED_SESSIONS + 5):
            entry = common.BASE / "sessions" / f"session-{index:03d}.jsonl"
            entry.write_text(json.dumps({"role": "user", "content": f"n{index}"}), "utf-8")
            os.utime(entry, (1_700_000_000 + index, 1_700_000_000 + index))

        listing = launcher.list_sessions()

        self.assertEqual(launcher.MAX_LISTED_SESSIONS + 5, listing["count"])
        self.assertEqual(launcher.MAX_LISTED_SESSIONS, len(listing["sessions"]))
        self.assertEqual("session-068", listing["sessions"][0]["id"])
        updates = [session["updatedAtEpochMs"] for session in listing["sessions"]]
        self.assertEqual(sorted(updates, reverse=True), updates)

    def test_directory_session_is_measured_and_summarised(self) -> None:
        folder = common.BASE / "sessions" / str(uuid.uuid4())
        folder.mkdir()
        (folder / "meta.json").write_text("{}", encoding="utf-8")
        (folder / "messages.jsonl").write_text(
            json.dumps(
                {
                    "role": "user",
                    "content": [{"type": "text", "text": "найди все TODO"}],
                }
            ),
            encoding="utf-8",
        )

        listing = launcher.list_sessions()

        session = listing["sessions"][0]
        self.assertEqual(folder.name, session["id"])
        self.assertEqual("найди все TODO", session["title"])
        self.assertEqual(1, session["messages"])
        self.assertEqual(
            sum(child.stat().st_size for child in folder.iterdir()), session["bytes"]
        )


if __name__ == "__main__":
    unittest.main()
