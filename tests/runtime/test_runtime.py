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


def session_v7() -> str:
    return "01890f76-e8b2-7cc2-98c8-8c4a7ef8d123"


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
        "agentMode": "agent",
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
    value.active_operation_kind = None
    value.abort_requested = False
    value.last_answer = ""
    value.active_failed_reason = None
    value.answer_retry_count = 0
    value.answer_retry_request_id = None
    value.answer_retry_exhausted = False
    value.required_live_tools = frozenset()
    value.successful_live_tools = set()
    value.turn_output_tokens = 0
    value.turn_decode_seconds = 0.0
    value._message_output_started_monotonic = None
    value.pending_approvals = {}
    value.pending_new_session = None
    value.seen_commands = collections.OrderedDict()
    value.last_client_seen = time.monotonic()
    value.context_window = 1024
    value.session_stats = bridge.bounded_session_stats(None, value.context_window)
    value.compacting = False
    value.compaction_reason = None
    value._stats_request_id = None
    value._stats_request_counter = 0
    value.child = FakeChild()
    return value


class RuntimeTestCase(unittest.TestCase):
    def test_bridge_can_rebind_after_exact_managed_restart(self) -> None:
        self.assertTrue(bridge.BridgeHttpServer.allow_reuse_address)

    def test_probe_requires_every_managed_extension_including_tool_router(self) -> None:
        compatibility = {
            "pi": {"version": "0.82.1"},
            "node": {"minimumVersion": "22.19.0"},
            "llamaCpp": {
                "owner": "android-native",
                "minimumVersion": "b10092",
                "maximumTestedVersion": "b10092",
            },
        }
        common.atomic_write_json(
            common.BASE / "runtime" / "compatibility.json", compatibility
        )
        required = (
            common.BASE / "runtime" / "models-v2.json",
            bridge.LOCAL_CACHE_EXTENSION,
            bridge.SYSTEM_PROMPT_EXTENSION,
            bridge.HASHLINE_EXTENSION,
            bridge.CONTEXT_GUARD_EXTENSION,
            bridge.WEB_TOOLS_EXTENSION,
            common.BASE / "runtime" / "pideck-permission-gate.ts",
        )
        for path in required:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture\n", encoding="utf-8")

        versions = [
            mock.Mock(returncode=0, stdout="0.82.1\n"),
            mock.Mock(returncode=0, stdout="v22.19.0\n"),
            mock.Mock(returncode=0, stdout="Python 3.14.0\n"),
        ]
        with mock.patch.object(launcher.subprocess, "run", side_effect=versions):
            incomplete = launcher.probe()
        self.assertFalse(incomplete["layoutReady"])
        self.assertEqual("INCOMPLETE", incomplete["state"])

        bridge.TOOL_ROUTER_EXTENSION.write_text("fixture\n", encoding="utf-8")
        versions = [
            mock.Mock(returncode=0, stdout="0.82.1\n"),
            mock.Mock(returncode=0, stdout="v22.19.0\n"),
            mock.Mock(returncode=0, stdout="Python 3.14.0\n"),
        ]
        with mock.patch.object(launcher.subprocess, "run", side_effect=versions):
            ready = launcher.probe()
        self.assertTrue(ready["layoutReady"])
        self.assertEqual("READY", ready["state"])

    def test_session_id_accepts_android_uuid4_and_pi_uuid7_only(self) -> None:
        uuid4 = operation_id()
        self.assertEqual(
            uuid4,
            common.require_session_id({"sessionId": uuid4}),
        )
        self.assertEqual(
            session_v7(),
            common.require_session_id({"sessionId": session_v7()}),
        )
        with self.assertRaises(common.PiDeckError):
            common.require_session_id(
                {"sessionId": "01890f76-e8b2-1cc2-98c8-8c4a7ef8d123"}
            )

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

        unsafe["runtime"]["serverArgs"] = ["--spec-type", "draft-mtp"]
        with self.assertRaises(common.PiDeckError) as raised:
            server_supervisor.effective_server_arguments(
                unsafe, Path("/private/model.gguf"), 4, 8080, "secret"
            )
        self.assertEqual("INVALID_CATALOG", raised.exception.code)

    def test_phone_sized_compaction_settings_preserve_unrelated_preferences(self) -> None:
        model = tiny_model(b"GGUF")
        model["runtime"]["recommendedContext"] = 10_240
        common.atomic_write_json(
            model_store.PI_SETTINGS_PATH,
            {
                "theme": "nord",
                "compaction": {
                    "enabled": False,
                    "reserveTokens": 16_384,
                    "keepRecentTokens": 20_000,
                    "customKey": "preserved",
                },
            },
        )

        managed = model_store.ensure_pi_compaction_settings(model)

        self.assertEqual(
            {
                "enabled": True,
                "reserveTokens": 2_048,
                "keepRecentTokens": 2_560,
            },
            managed,
        )
        saved = common.read_json(model_store.PI_SETTINGS_PATH)
        self.assertEqual("nord", saved["theme"])
        self.assertEqual("preserved", saved["compaction"]["customKey"])
        self.assertEqual(managed["reserveTokens"], saved["compaction"]["reserveTokens"])
        self.assertEqual(0o600, model_store.PI_SETTINGS_PATH.stat().st_mode & 0o777)

    def test_chat_mode_removes_tool_schema_from_pi_context(self) -> None:
        self.assertEqual(["--no-tools"], launcher._profile_arguments("autonomous", "chat"))
        confirm_tools = ",".join(
            launcher._profile_arguments("confirm_changes", "agent")
        )
        self.assertIn("pideck_bash", confirm_tools)
        self.assertIn("pideck_load_tools", confirm_tools)
        self.assertIn("web_search", confirm_tools)
        self.assertIn("weather", confirm_tools)
        autonomous_tools = ",".join(
            launcher._profile_arguments("autonomous", "agent")
        )
        self.assertIn("pideck_load_tools", autonomous_tools)
        self.assertIn("web_search", autonomous_tools)
        self.assertIn("weather", autonomous_tools)
        read_only_tools = ",".join(
            launcher._profile_arguments("read_only", "agent")
        )
        self.assertIn("pideck_load_tools", read_only_tools)
        self.assertIn("web_search", read_only_tools)
        self.assertIn("weather", read_only_tools)

    def test_explicit_live_data_request_detection_is_narrow(self) -> None:
        self.assertEqual(
            frozenset({"weather", "web_search"}),
            bridge.required_live_tools(
                "поищи в сети погоду в Москве и напиши здесь"
            ),
        )
        self.assertEqual(
            frozenset({"web_search"}),
            bridge.required_live_tools("Найди в интернете документацию Pi"),
        )
        for prompt in (
            "Объясни, почему меняется погода",
            "Найди TODO в проекте",
            "Расскажи о Москве",
            "Как приложение работает в сети?",
            "Сделай режим онлайн",
        ):
            self.assertEqual(frozenset(), bridge.required_live_tools(prompt))

    def test_markdown_only_answer_detection_preserves_real_short_answers(self) -> None:
        for invalid in ("**", "```", "##", "...", "[ ]", "~ ~"):
            self.assertTrue(bridge.is_degenerate_answer(invalid), invalid)
        for valid in ("Да.", "42", "C++", "✅", "a", ""):
            self.assertFalse(bridge.is_degenerate_answer(valid), valid)

    def test_external_server_adoption_is_health_bound_and_exact(self) -> None:
        model = tiny_model(b"GGUF")
        install_catalog(model)
        request = {
            "schemaVersion": 1,
            "operationId": operation_id(),
            "modelId": model["id"],
            "modelSha256": model["artifact"]["sha256"],
            "owner": "android-native",
            "runtimeBuild": "b10092",
            "port": 8080,
            "apiKey": "A" * 43,
            "pid": 4242,
            "decodeThreads": 5,
            "batchThreads": 8,
            "decodeCpuSet": "3-7",
            "batchCpuSet": "0-7",
        }
        with (
            mock.patch.object(
                server_supervisor, "strict_health", return_value={"status": "ok"}
            ) as health,
            mock.patch.object(server_supervisor, "_write_pi_models") as write_models,
            mock.patch.object(server_supervisor, "_wake_lock") as wake_lock,
        ):
            result = server_supervisor.adopt_external_server(request)
            status = server_supervisor.read_server_status()
        self.assertEqual("READY", result["state"])
        self.assertEqual("android-native", status["owner"])
        self.assertEqual("3-7", status["decodeCpuSet"])
        self.assertEqual("0-7", status["batchCpuSet"])
        health.assert_called()
        write_models.assert_called_once_with("A" * 43, 8080)
        wake_lock.assert_called_once_with(False)
        self.assertEqual(
            ("A" * 43).encode("ascii"),
            server_supervisor.SERVER_API_KEY.read_bytes(),
        )

    def test_pi_model_config_requests_exact_streaming_usage(self) -> None:
        model = tiny_model(b"GGUF")
        install_catalog(model)

        server_supervisor._write_pi_models("A" * 43, 8080)

        config = json.loads(server_supervisor.PI_MODELS.read_text("utf-8"))
        provider = config["providers"]["pideck"]
        self.assertEqual("openai-completions", provider["api"])
        self.assertTrue(provider["compat"]["supportsUsageInStreaming"])

    def test_external_server_adoption_rejects_claim_without_health(self) -> None:
        model = tiny_model(b"GGUF")
        install_catalog(model)
        request = {
            "schemaVersion": 1,
            "operationId": operation_id(),
            "modelId": model["id"],
            "modelSha256": model["artifact"]["sha256"],
            "owner": "android-native",
            "runtimeBuild": "b10092",
            "port": 8080,
            "apiKey": "A" * 43,
            "decodeThreads": 5,
            "batchThreads": 8,
            "decodeCpuSet": "3-7",
            "batchCpuSet": "0-7",
        }
        with mock.patch.object(
            server_supervisor,
            "strict_health",
            side_effect=common.PiDeckError("HEALTH_UNREACHABLE", "down"),
        ):
            with self.assertRaises(common.PiDeckError) as raised:
                server_supervisor.adopt_external_server(request)
        self.assertEqual("HEALTH_UNREACHABLE", raised.exception.code)
        self.assertFalse(server_supervisor.SERVER_STATUS.exists())

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

    def test_system_prompt_is_private_integrity_checked_and_not_metadata(self) -> None:
        marker = "PIDECK_SYSTEM_SECRET_ёж"
        descriptor, content = bridge.parse_system_prompt_request(
            {"systemPromptMode": "append", "systemPrompt": marker}
        )
        self.assertEqual("append", descriptor["systemPromptMode"])
        self.assertEqual(len(marker.encode("utf-8")), descriptor["systemPromptBytes"])
        self.assertNotIn(marker, json.dumps(descriptor))

        bridge.persist_system_prompt(bridge.SYSTEM_PROMPT_FILE, content)
        self.assertEqual(0o600, bridge.SYSTEM_PROMPT_FILE.stat().st_mode & 0o777)
        environment = bridge.system_prompt_environment(descriptor)
        self.assertEqual("append", environment["PIDECK_SYSTEM_PROMPT_MODE"])
        self.assertEqual(
            str(bridge.SYSTEM_PROMPT_FILE),
            environment["PIDECK_SYSTEM_PROMPT_PATH"],
        )
        self.assertNotIn(marker, json.dumps(environment))

        bridge.SYSTEM_PROMPT_FILE.write_text("tampered", encoding="utf-8")
        with self.assertRaises(common.PiDeckError) as raised:
            bridge.system_prompt_environment(descriptor)
        self.assertEqual("SYSTEM_PROMPT_INTEGRITY", raised.exception.code)

    def test_system_prompt_modes_size_limit_and_default_cleanup(self) -> None:
        replacement, content = bridge.parse_system_prompt_request(
            {"systemPromptMode": "replace", "systemPrompt": "only this"}
        )
        bridge.persist_system_prompt(bridge.SYSTEM_PROMPT_FILE, content)
        self.assertEqual(
            "replace",
            bridge.system_prompt_environment(replacement)[
                "PIDECK_SYSTEM_PROMPT_MODE"
            ],
        )

        default, empty = bridge.parse_system_prompt_request(
            {"systemPromptMode": "append", "systemPrompt": ""}
        )
        bridge.persist_system_prompt(bridge.SYSTEM_PROMPT_FILE, empty)
        self.assertEqual("default", default["systemPromptMode"])
        self.assertEqual(bridge.EMPTY_PROMPT_SHA256, default["systemPromptSha256"])
        self.assertFalse(bridge.SYSTEM_PROMPT_FILE.exists())
        self.assertEqual(
            {"PIDECK_SYSTEM_PROMPT_MODE": "default"},
            bridge.system_prompt_environment(default),
        )

        with self.assertRaises(common.PiDeckError) as raised:
            bridge.parse_system_prompt_request(
                {
                    "systemPromptMode": "append",
                    "systemPrompt": "я" * (bridge.MAX_SYSTEM_PROMPT_BYTES // 2 + 1),
                }
            )
        self.assertEqual("SYSTEM_PROMPT_TOO_LARGE", raised.exception.code)
        with self.assertRaises(common.PiDeckError) as raised:
            bridge.parse_system_prompt_request(
                {"systemPromptMode": "unknown", "systemPrompt": "value"}
            )
        self.assertEqual("INVALID_SYSTEM_PROMPT_MODE", raised.exception.code)

    def test_bridge_state_exposes_only_system_prompt_fingerprint(self) -> None:
        value = fake_bridge()
        marker = "PIDECK_STATE_SECRET"
        descriptor, _content = bridge.parse_system_prompt_request(
            {"systemPromptMode": "append", "systemPrompt": marker}
        )
        value.config.update(descriptor)
        state = value.state()
        self.assertEqual("append", state["systemPromptMode"])
        self.assertEqual(descriptor["systemPromptSha256"], state["systemPromptSha256"])
        self.assertEqual(len(marker), state["systemPromptBytes"])
        self.assertNotIn(marker, json.dumps(state))

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
        with mock.patch.object(bridge.time, "monotonic", return_value=10.0):
            value.handle_pi_message(
                {
                    "type": "message_update",
                    "assistantMessageEvent": {"type": "text_delta", "delta": "Готово"},
                }
            )
        with mock.patch.object(bridge.time, "monotonic", return_value=12.0):
            value.handle_pi_message(
                {
                    "type": "message_end",
                    "message": {
                        "role": "assistant",
                        "content": [{"type": "text", "text": "Готово"}],
                        "usage": {"output": 40},
                    },
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
        self.assertEqual(40, terminal[-1]["payload"]["outputTokens"])
        self.assertEqual(2_000, terminal[-1]["payload"]["decodeDurationMs"])
        self.assertEqual(20.0, terminal[-1]["payload"]["tokensPerSecond"])
        self.assertFalse(terminal[-1]["payload"]["speedEstimated"])
        self.assertIsNone(value.active_operation_id)

    def test_markdown_only_answer_is_cleared_retried_once_and_replaced(self) -> None:
        value = fake_bridge()
        identifier = operation_id()
        value.command(
            {
                "schemaVersion": 1,
                "operationId": identifier,
                "type": "PROMPT",
                "payload": {"message": "weather", "sessionId": value.session_id},
            }
        )
        value.handle_pi_message({"type": "agent_start"})
        value.handle_pi_message(
            {
                "type": "message_update",
                "assistantMessageEvent": {"type": "text_delta", "delta": "**"},
            }
        )
        value.handle_pi_message(
            {
                "type": "message_end",
                "message": {
                    "role": "assistant",
                    "content": [{"type": "text", "text": "**"}],
                },
            }
        )

        retry = value.child.sent[-1]
        self.assertEqual("follow_up", retry["type"])
        self.assertIn("исходный запрос", retry["message"])
        self.assertEqual("", value.last_answer)
        _gap, events = value.journal.after(0, 0)
        rejected = [
            event for event in events if event["type"] == "MODEL_OUTPUT_REJECTED"
        ]
        self.assertEqual(1, len(rejected))
        self.assertTrue(rejected[-1]["payload"]["willRetry"])

        value.handle_pi_message(
            {
                "type": "response",
                "id": retry["id"],
                "command": "follow_up",
                "success": True,
            }
        )
        value.handle_pi_message(
            {
                "type": "message_update",
                "assistantMessageEvent": {
                    "type": "text_delta",
                    "delta": "В Москве +28 °C.",
                },
            }
        )
        value.handle_pi_message(
            {
                "type": "message_end",
                "message": {
                    "role": "assistant",
                    "content": [{"type": "text", "text": "В Москве +28 °C."}],
                },
            }
        )
        value.handle_pi_message({"type": "agent_settled"})

        _gap, events = value.journal.after(0, 0)
        terminal = [event for event in events if event["type"] == "TURN_COMPLETED"]
        self.assertEqual("В Москве +28 °C.", terminal[-1]["payload"]["answer"])
        self.assertNotIn("**", terminal[-1]["payload"]["answer"])

    def test_second_markdown_only_answer_fails_instead_of_displaying_symbols(self) -> None:
        value = fake_bridge()
        value.command(
            {
                "schemaVersion": 1,
                "operationId": operation_id(),
                "type": "PROMPT",
                "payload": {"message": "weather", "sessionId": value.session_id},
            }
        )
        value.handle_pi_message({"type": "agent_start"})
        invalid_message = {
            "type": "message_end",
            "message": {
                "role": "assistant",
                "content": [{"type": "text", "text": "**"}],
            },
        }
        value.handle_pi_message(invalid_message)
        retry = value.child.sent[-1]
        value.handle_pi_message(
            {
                "type": "response",
                "id": retry["id"],
                "command": "follow_up",
                "success": True,
            }
        )
        value.handle_pi_message(invalid_message)
        value.handle_pi_message({"type": "agent_settled"})

        _gap, events = value.journal.after(0, 0)
        rejected = [
            event for event in events if event["type"] == "MODEL_OUTPUT_REJECTED"
        ]
        self.assertEqual([True, False], [
            event["payload"]["willRetry"] for event in rejected
        ])
        terminal = [event for event in events if event["type"] == "TURN_FAILED"]
        self.assertEqual("", terminal[-1]["payload"]["answer"])
        self.assertIn("дважды", terminal[-1]["payload"]["error"])
        self.assertEqual(
            1,
            len([command for command in value.child.sent if command["type"] == "follow_up"]),
        )

    def test_live_data_answer_requires_tool_and_recovers_once(self) -> None:
        value = fake_bridge()
        value.command(
            {
                "schemaVersion": 1,
                "operationId": operation_id(),
                "type": "PROMPT",
                "payload": {
                    "message": "поищи в сети погоду в Москве и напиши здесь",
                    "sessionId": value.session_id,
                },
            }
        )
        refusal = {
            "type": "message_end",
            "message": {
                "role": "assistant",
                "content": [
                    {
                        "type": "text",
                        "text": "Я не могу искать погоду в интернете.",
                    }
                ],
            },
        }
        value.handle_pi_message(refusal)

        retry = value.child.sent[-1]
        self.assertEqual("follow_up", retry["type"])
        self.assertIn("weather", retry["message"])
        self.assertEqual("", value.last_answer)
        _gap, events = value.journal.after(0, 0)
        rejected = [
            event for event in events if event["type"] == "MODEL_OUTPUT_REJECTED"
        ]
        self.assertEqual("live_tool_required", rejected[-1]["payload"]["reason"])
        self.assertEqual(
            ["weather", "web_search"],
            rejected[-1]["payload"]["requiredTools"],
        )

        value.handle_pi_message(
            {
                "type": "response",
                "id": retry["id"],
                "command": "follow_up",
                "success": True,
            }
        )
        value.handle_pi_message(
            {
                "type": "tool_execution_end",
                "toolName": "weather",
                "toolCallId": "weather-1",
                "isError": False,
                "result": "Москва: +18 °C",
            }
        )
        answer = "В Москве сейчас +18 °C по данным Open-Meteo."
        value.handle_pi_message(
            {
                "type": "message_end",
                "message": {
                    "role": "assistant",
                    "content": [{"type": "text", "text": answer}],
                },
            }
        )
        value.handle_pi_message({"type": "agent_settled"})

        _gap, events = value.journal.after(0, 0)
        terminal = [event for event in events if event["type"] == "TURN_COMPLETED"]
        self.assertEqual(answer, terminal[-1]["payload"]["answer"])

    def test_second_live_data_answer_without_tool_fails(self) -> None:
        value = fake_bridge()
        value.command(
            {
                "schemaVersion": 1,
                "operationId": operation_id(),
                "type": "PROMPT",
                "payload": {
                    "message": "поищи в сети погоду в Москве и напиши здесь",
                    "sessionId": value.session_id,
                },
            }
        )
        answer_without_tool = {
            "type": "message_end",
            "message": {
                "role": "assistant",
                "content": [{"type": "text", "text": "Наверное, сейчас тепло."}],
            },
        }
        value.handle_pi_message(answer_without_tool)
        retry = value.child.sent[-1]
        value.handle_pi_message(
            {
                "type": "response",
                "id": retry["id"],
                "command": "follow_up",
                "success": True,
            }
        )
        value.handle_pi_message(answer_without_tool)
        value.handle_pi_message({"type": "agent_settled"})

        _gap, events = value.journal.after(0, 0)
        rejected = [
            event for event in events if event["type"] == "MODEL_OUTPUT_REJECTED"
        ]
        self.assertEqual(
            [True, False],
            [event["payload"]["willRetry"] for event in rejected],
        )
        terminal = [event for event in events if event["type"] == "TURN_FAILED"]
        self.assertEqual("", terminal[-1]["payload"]["answer"])
        self.assertIn("сетевого инструмента", terminal[-1]["payload"]["error"])

    def test_failed_live_tool_does_not_satisfy_current_data_request(self) -> None:
        value = fake_bridge()
        value.command(
            {
                "schemaVersion": 1,
                "operationId": operation_id(),
                "type": "PROMPT",
                "payload": {
                    "message": "Какая погода в Москве?",
                    "sessionId": value.session_id,
                },
            }
        )
        value.handle_pi_message(
            {
                "type": "tool_execution_end",
                "toolName": "weather",
                "toolCallId": "weather-failed",
                "isError": True,
                "result": "network timeout",
            }
        )
        value.handle_pi_message(
            {
                "type": "message_end",
                "message": {
                    "role": "assistant",
                    "content": [{"type": "text", "text": "Сейчас около +20 °C."}],
                },
            }
        )

        self.assertEqual("follow_up", value.child.sent[-1]["type"])
        self.assertEqual(set(), value.successful_live_tools)

    def test_session_stats_are_bounded_and_emitted_without_message_content(self) -> None:
        value = fake_bridge()
        value.request_session_stats()
        request = value.child.sent[-1]
        self.assertEqual("get_session_stats", request["type"])

        value.handle_pi_message(
            {
                "type": "response",
                "id": request["id"],
                "command": "get_session_stats",
                "success": True,
                "data": {
                    "userMessages": 4,
                    "assistantMessages": 3,
                    "toolCalls": 2,
                    "privateTranscript": "must not cross the bridge",
                    "contextUsage": {
                        "tokens": 768,
                        "contextWindow": 1_024,
                    },
                },
            }
        )

        self.assertEqual(75, value.session_stats["contextUsage"]["percent"])
        self.assertNotIn("privateTranscript", value.session_stats)
        _gap, events = value.journal.after(0, 0)
        changed = [event for event in events if event["type"] == "SESSION_STATS_CHANGED"]
        self.assertEqual(768, changed[-1]["payload"]["contextUsage"]["tokens"])

    def test_manual_compaction_has_its_own_terminal_contract(self) -> None:
        value = fake_bridge()
        identifier = operation_id()
        accepted = value.command(
            {
                "schemaVersion": 1,
                "operationId": identifier,
                "type": "COMPACT",
                "payload": {"customInstructions": "Keep decisions and pending work."},
            }
        )
        self.assertTrue(accepted["accepted"])
        self.assertEqual("compact", value.child.sent[-1]["type"])
        self.assertEqual("compact", value.active_operation_kind)

        value.handle_pi_message({"type": "compaction_start", "reason": "manual"})
        value.handle_pi_message(
            {
                "type": "compaction_end",
                "reason": "manual",
                "result": {
                    "tokensBefore": 900,
                    "estimatedTokensAfter": 240,
                },
            }
        )
        value.handle_pi_message(
            {
                "type": "response",
                "id": identifier,
                "command": "compact",
                "success": True,
                "data": {
                    "tokensBefore": 900,
                    "estimatedTokensAfter": 240,
                },
            }
        )

        _gap, events = value.journal.after(0, 0)
        event_types = [event["type"] for event in events]
        self.assertIn("CONTEXT_COMPACTION_STARTED", event_types)
        self.assertIn("CONTEXT_COMPACTION_FINISHED", event_types)
        self.assertEqual("SESSION_COMPACTED", events[-1]["type"])
        self.assertIsNone(value.active_operation_id)
        self.assertEqual(240, value.session_stats["contextUsage"]["tokens"])

    def test_recovered_tool_error_does_not_fail_the_turn(self) -> None:
        value = fake_bridge()
        value.command(
            {
                "schemaVersion": 1,
                "operationId": operation_id(),
                "type": "PROMPT",
                "payload": {"message": "say hi", "sessionId": value.session_id},
            }
        )
        value.handle_pi_message({"type": "agent_start"})
        value.handle_pi_message(
            {
                "type": "message_update",
                "assistantMessageEvent": {"type": "text_delta", "delta": "Привет"},
            }
        )
        value.handle_pi_message(
            {
                "type": "tool_execution_end",
                "toolName": "grep",
                "toolCallId": "tool-1",
                "isError": True,
                "result": "rg: /: Permission denied (os error 13)",
            }
        )
        # The model saw the error and recovered on the next call, so the turn stands.
        value.handle_pi_message(
            {
                "type": "tool_execution_end",
                "toolName": "grep",
                "toolCallId": "tool-2",
                "isError": False,
                "result": "3 matches",
            }
        )
        value.handle_pi_message({"type": "agent_settled"})
        _gap, events = value.journal.after(0, 0)
        event_types = [event["type"] for event in events]
        self.assertNotIn("TURN_FAILED", event_types)
        terminal = [event for event in events if event["type"] == "TURN_COMPLETED"]
        self.assertEqual("Привет", terminal[-1]["payload"]["answer"])
        self.assertNotIn("error", terminal[-1]["payload"])
        # The failing call is still reported on its own, so nothing is hidden.
        failed_calls = [
            event
            for event in events
            if event["type"] == "TOOL_CALL_COMPLETED" and event["payload"]["isError"]
        ]
        self.assertEqual("tool-1", failed_calls[-1]["payload"]["toolCallId"])

    def test_model_error_still_fails_the_turn(self) -> None:
        value = fake_bridge()
        value.command(
            {
                "schemaVersion": 1,
                "operationId": operation_id(),
                "type": "PROMPT",
                "payload": {"message": "say hi", "sessionId": value.session_id},
            }
        )
        value.handle_pi_message({"type": "agent_start"})
        value.handle_pi_message(
            {
                "type": "tool_execution_end",
                "toolName": "grep",
                "toolCallId": "tool-1",
                "isError": True,
                "result": "rg: /: Permission denied (os error 13)",
            }
        )
        value.handle_pi_message(
            {
                "type": "message_update",
                "assistantMessageEvent": {
                    "type": "error",
                    "error": "context window exceeded",
                },
            }
        )
        value.handle_pi_message({"type": "agent_settled"})
        _gap, events = value.journal.after(0, 0)
        terminal = [event for event in events if event["type"] == "TURN_FAILED"]
        self.assertEqual("context window exceeded", terminal[-1]["payload"]["error"])

    def test_pi_uuid7_session_becomes_authoritative_and_accepts_prompt(self) -> None:
        value = fake_bridge()
        identifier = operation_id()
        accepted = value.command(
            {
                "schemaVersion": 1,
                "operationId": identifier,
                "type": "NEW_SESSION",
                "payload": {"sessionId": operation_id()},
            }
        )
        self.assertTrue(accepted["accepted"])
        value.handle_pi_message(
            {
                "type": "response",
                "id": identifier,
                "command": "new_session",
                "success": True,
            }
        )
        state_request = value.child.sent[-1]
        self.assertEqual("get_state", state_request["type"])
        value.handle_pi_message(
            {
                "type": "response",
                "id": state_request["id"],
                "command": "get_state",
                "success": True,
                "data": {"sessionId": session_v7()},
            }
        )
        self.assertEqual(session_v7(), value.session_id)
        self.assertEqual(session_v7(), value.config["sessionId"])
        _gap, events = value.journal.after(0, 0)
        created = [event for event in events if event["type"] == "SESSION_CREATED"]
        self.assertEqual(session_v7(), created[-1]["payload"]["sessionId"])

        prompt_id = operation_id()
        result = value.command(
            {
                "schemaVersion": 1,
                "operationId": prompt_id,
                "type": "PROMPT",
                "payload": {
                    "message": "continue",
                    "sessionId": session_v7(),
                },
            }
        )
        self.assertTrue(result["accepted"])

    def test_abort_is_structured_and_terminal(self) -> None:
        value = fake_bridge()
        target = operation_id()
        value.active_operation_id = target
        value.active_operation_kind = "prompt"
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
        value.active_operation_kind = "prompt"
        value._stats_request_id = "request-owned-by-exited-child"
        value.handle_pi_exit(9, False)
        _gap, events = value.journal.after(0, 0)
        self.assertIn("TURN_FAILED", [event["type"] for event in events])
        self.assertIsNone(value.active_operation_id)
        self.assertIsNone(value._stats_request_id)

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


class DecisionHeaderTestCase(unittest.TestCase):
    def header(self, **payload: object) -> str:
        return bridge.DECISION_PREFIX + json.dumps(payload) + "\nTool: pideck_write\nTarget: x"

    def test_message_without_a_header_is_left_alone(self) -> None:
        decision, message = bridge.split_decision("Tool: pideck_bash\nls -la")
        self.assertIsNone(decision)
        self.assertEqual("Tool: pideck_bash\nls -la", message)

    def test_header_is_lifted_off_and_bounded(self) -> None:
        decision, message = bridge.split_decision(
            self.header(
                kind="overwrite",
                path="/home/user/.pideck/workspace/AGENTS.md",
                reason="Заменяю три раздела на шаблонные.",
                addedLines=7,
                removedLines=3,
                selfCreated=False,
                preview=["-один", "+два", "+три", "+четыре", "+лишняя"],
            )
        )
        self.assertIsNotNone(decision)
        self.assertEqual("overwrite", decision["kind"])
        self.assertEqual("/home/user/.pideck/workspace/AGENTS.md", decision["path"])
        self.assertEqual(7, decision["addedLines"])
        self.assertEqual(3, decision["removedLines"])
        self.assertFalse(decision["selfCreated"])
        self.assertEqual(
            bridge.MAX_DECISION_PREVIEW_LINES, len(decision["preview"])
        )
        self.assertEqual("Tool: pideck_write\nTarget: x", message)

    def test_unknown_kind_is_refused_and_the_message_survives(self) -> None:
        raw = self.header(kind="format-the-disk", path="/etc/passwd")
        decision, message = bridge.split_decision(raw)
        self.assertIsNone(decision)
        self.assertEqual(raw, message)

    def test_malformed_header_is_refused_and_the_message_survives(self) -> None:
        raw = bridge.DECISION_PREFIX + "{not json\nTool: pideck_write"
        decision, message = bridge.split_decision(raw)
        self.assertIsNone(decision)
        self.assertEqual(raw, message)

    def test_counts_are_forced_into_range(self) -> None:
        decision, _ = bridge.split_decision(
            self.header(
                kind="overwrite",
                addedLines=-5,
                removedLines="many",
                selfCreated="yes",
                preview="not-a-list",
            )
        )
        self.assertEqual(0, decision["addedLines"])
        self.assertEqual(0, decision["removedLines"])
        # Only a real boolean grants the self-created exemption.
        self.assertFalse(decision["selfCreated"])
        self.assertEqual([], decision["preview"])
        self.assertEqual("", decision["path"])

    def test_oversized_fields_are_truncated(self) -> None:
        decision, _ = bridge.split_decision(
            self.header(kind="overwrite", path="p" * 4096, reason="r" * 8192)
        )
        self.assertLessEqual(len(decision["path"]), 1024)
        self.assertLessEqual(len(decision["reason"]), 2048)


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
