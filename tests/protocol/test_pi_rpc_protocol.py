"""Contract test against the exact installed Pi 0.82.1 CLI and a fake llama API."""

from __future__ import annotations

import hashlib
import json
import os
import queue
import subprocess
import tempfile
import threading
import time
import unittest
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[2]
FRAGMENT_PROMPT_MARKER = "PIDECK_PROTOCOL_FRAGMENT"
RETRY_PROMPT_MARKER = "PIDECK_PROTOCOL_RETRY_FULL"
RETRY_COMPLETE_TEXT = "Полный ответ после settled retry"


class FakeLlamaHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    requests: queue.Queue[dict[str, object]] = queue.Queue()

    def log_message(self, _format: str, *_args: object) -> None:
        return

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/v1/chat/completions":
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        request = json.loads(self.rfile.read(length))
        self.requests.put(request)
        if request.get("model") != "fixture-model":
            self.send_error(400)
            return
        serialized_messages = json.dumps(
            request.get("messages", []), ensure_ascii=False
        )
        if RETRY_PROMPT_MARKER in serialized_messages:
            response_text = RETRY_COMPLETE_TEXT
        elif FRAGMENT_PROMPT_MARKER in serialized_messages:
            response_text = "О"
        else:
            response_text = "PIDECK_RPC_OK"
        chunks = [
            {
                "id": "fixture",
                "object": "chat.completion.chunk",
                "created": int(time.time()),
                "model": "fixture-model",
                "choices": [
                    {
                        "index": 0,
                        "delta": {"role": "assistant", "content": response_text},
                        "finish_reason": None,
                    }
                ],
            },
            {
                "id": "fixture",
                "object": "chat.completion.chunk",
                "created": int(time.time()),
                "model": "fixture-model",
                "choices": [
                    {"index": 0, "delta": {}, "finish_reason": "stop"}
                ],
                "usage": {
                    "prompt_tokens": 8,
                    "completion_tokens": 3,
                    "total_tokens": 11,
                },
            },
        ]
        body = b"".join(
            b"data: " + json.dumps(chunk, separators=(",", ":")).encode() + b"\n\n"
            for chunk in chunks
        ) + b"data: [DONE]\n\n"
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        self.wfile.flush()


class PiRpcProtocolTest(unittest.TestCase):
    def test_prompt_stream_state_abort_and_explicit_extension_load(self) -> None:
        pi_binary = os.environ.get("PIDECK_PI_BIN")
        if not pi_binary:
            self.skipTest("Set PIDECK_PI_BIN to the pinned Pi 0.82.1 executable")
        FakeLlamaHandler.requests = queue.Queue()
        version = subprocess.run(
            [pi_binary, "--version"],
            check=True,
            text=True,
            capture_output=True,
            timeout=10,
        ).stdout.strip()
        self.assertEqual("0.82.1", version)

        server = ThreadingHTTPServer(("127.0.0.1", 0), FakeLlamaHandler)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        with tempfile.TemporaryDirectory(prefix="pideck-pi-rpc-") as directory:
            base = Path(directory)
            agent_dir = base / "agent"
            sessions = base / "sessions"
            workspace = base / "workspace"
            agent_dir.mkdir()
            sessions.mkdir()
            workspace.mkdir()
            models = {
                "providers": {
                    "pideck": {
                        "baseUrl": f"http://127.0.0.1:{server.server_port}/v1",
                        "api": "openai-completions",
                        "apiKey": "fixture-secret",
                        "compat": {
                            "supportsDeveloperRole": False,
                            "supportsReasoningEffort": False,
                            "supportsStore": False,
                            "supportsUsageInStreaming": True,
                            "maxTokensField": "max_tokens",
                        },
                        "models": [
                            {
                                "id": "fixture-model",
                                "name": "Fixture",
                                "reasoning": False,
                                "input": ["text"],
                                # Real fixture window is 4096. Pi 0.82.1 subtracts
                                # its own fixed 4096-token API safety margin, so
                                # the generated provider descriptor offsets it.
                                "contextWindow": 8192,
                                "maxTokens": 256,
                                "cost": {
                                    "input": 0,
                                    "output": 0,
                                    "cacheRead": 0,
                                    "cacheWrite": 0,
                                },
                            }
                        ],
                    }
                }
            }
            (agent_dir / "models.json").write_text(json.dumps(models), encoding="utf-8")
            managed_compaction = {
                "enabled": True,
                "reserveTokens": 4_352,
                "keepRecentTokens": 1_024,
            }
            (agent_dir / "settings.json").write_text(
                json.dumps({"compaction": managed_compaction}), encoding="utf-8"
            )
            project_settings = workspace / ".pi" / "settings.json"
            project_settings.parent.mkdir()
            project_settings.write_text(
                json.dumps(
                    {
                        "projectPreference": "preserved",
                        "compaction": managed_compaction,
                    }
                ),
                encoding="utf-8",
            )
            extension = (
                REPOSITORY
                / "app"
                / "src"
                / "main"
                / "assets"
                / "runtime"
                / "pideck-permission-gate.ts"
            )
            cache_extension = (
                REPOSITORY
                / "app"
                / "src"
                / "main"
                / "assets"
                / "runtime"
                / "pideck-local-cache.ts"
            )
            system_prompt_marker = "PIDECK_CUSTOM_SYSTEM_PROMPT_MARKER"
            system_prompt = base / "system-prompt.txt"
            system_prompt.write_text(system_prompt_marker, encoding="utf-8")
            system_prompt.chmod(0o600)
            system_prompt_extension = (
                REPOSITORY
                / "app"
                / "src"
                / "main"
                / "assets"
                / "runtime"
                / "pideck-system-prompt.ts"
            )
            context_guard_extension = (
                REPOSITORY
                / "app"
                / "src"
                / "main"
                / "assets"
                / "runtime"
                / "pideck-context-guard.ts"
            )
            hashline_extension = (
                REPOSITORY
                / "app"
                / "src"
                / "main"
                / "assets"
                / "runtime"
                / "pideck-hashline-edit.ts"
            )
            run_tests_extension = (
                REPOSITORY
                / "app"
                / "src"
                / "main"
                / "assets"
                / "runtime"
                / "pideck-run-tests.ts"
            )
            syntax_check_extension = (
                REPOSITORY
                / "app"
                / "src"
                / "main"
                / "assets"
                / "runtime"
                / "pideck-syntax-check.ts"
            )
            web_tools_extension = (
                REPOSITORY
                / "app"
                / "src"
                / "main"
                / "assets"
                / "runtime"
                / "pideck-web-tools.ts"
            )
            code_nav_extension = (
                REPOSITORY
                / "app"
                / "src"
                / "main"
                / "assets"
                / "runtime"
                / "pideck-code-nav.ts"
            )
            agent_base_prompt = (
                REPOSITORY
                / "app"
                / "src"
                / "main"
                / "assets"
                / "runtime"
                / "pideck-agent-base-prompt.md"
            )
            tool_router_extension = (
                REPOSITORY
                / "app"
                / "src"
                / "main"
                / "assets"
                / "runtime"
                / "pideck-tool-router.ts"
            )
            arguments = [
                pi_binary,
                "--mode",
                "rpc",
                "--provider",
                "pideck",
                "--model",
                "fixture-model",
                "--thinking",
                "off",
                "--system-prompt",
                str(agent_base_prompt),
                "--session-dir",
                str(sessions),
                "--session-id",
                str(uuid.uuid4()),
                "--approve",
                "--offline",
                "--no-extensions",
                "--extension",
                str(cache_extension),
                "--extension",
                str(system_prompt_extension),
                "--extension",
                str(hashline_extension),
                "--extension",
                str(syntax_check_extension),
                "--extension",
                str(run_tests_extension),
                "--extension",
                str(context_guard_extension),
                "--extension",
                str(web_tools_extension),
                "--extension",
                str(code_nav_extension),
                "--extension",
                str(tool_router_extension),
                "--no-builtin-tools",
                "--tools",
                "read,code_nav,web_research,weather,"
                "pideck_bash,pideck_edit,pideck_write,pideck_replace_lines,"
                "pideck_load_tools",
                "--extension",
                str(extension),
            ]
            environment = os.environ.copy()
            environment["PI_CODING_AGENT_DIR"] = str(agent_dir)
            environment["PI_CODING_AGENT_SESSION_DIR"] = str(sessions)
            environment["PIDECK_HOME"] = str(base)
            environment["PI_OFFLINE"] = "1"
            environment["PIDECK_ACCESS_PROFILE"] = "confirm_changes"
            environment["PIDECK_AGENT_MODE"] = "agent"
            environment["PIDECK_HASHLINE_APPROVAL"] = "required"
            environment["PIDECK_SYSTEM_PROMPT_MODE"] = "append"
            environment["PIDECK_SYSTEM_PROMPT_PATH"] = str(system_prompt)
            environment["PIDECK_SYSTEM_PROMPT_SHA256"] = hashlib.sha256(
                system_prompt_marker.encode("utf-8")
            ).hexdigest()
            environment["PIDECK_SYSTEM_PROMPT_BYTES"] = str(
                len(system_prompt_marker.encode("utf-8"))
            )
            process = subprocess.Popen(
                arguments,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                cwd=workspace,
                env=environment,
            )
            assert process.stdin is not None
            assert process.stdout is not None
            assert process.stderr is not None
            lines: queue.Queue[dict[str, object]] = queue.Queue()
            stderr: list[str] = []

            def read_stdout() -> None:
                for line in process.stdout:
                    lines.put(json.loads(line))

            stdout_thread = threading.Thread(target=read_stdout, daemon=True)
            stderr_thread = threading.Thread(
                target=lambda: stderr.extend(process.stderr.readlines()), daemon=True
            )
            stdout_thread.start()
            stderr_thread.start()
            try:
                initial_cmdline = [
                    part.decode("utf-8", "replace")
                    for part in (Path("/proc") / str(process.pid) / "cmdline")
                    .read_bytes()
                    .split(b"\0")
                    if part
                ]
                self.assertTrue(
                    any(Path(part).name == "pi" for part in initial_cmdline),
                    initial_cmdline,
                )
                # The npm launcher is a Node shebang: Linux may expose `node` as
                # argv[0], but the exact pinned launcher remains a complete argv
                # token. Runtime identity deliberately accepts that exact basename,
                # never a substring such as `pi-helper`.
                self.assertFalse(
                    any(Path(part).name == "pi-helper" for part in initial_cmdline),
                    initial_cmdline,
                )
                self.assertNotIn(system_prompt_marker, "\0".join(initial_cmdline))
                state_id = str(uuid.uuid4())
                stats_id = str(uuid.uuid4())
                prompt_id = str(uuid.uuid4())
                process.stdin.write(
                    json.dumps({"id": state_id, "type": "get_state"}) + "\n"
                )
                marker = "PIDECK_SECRET_MARKER_RPC"
                process.stdin.write(
                    json.dumps(
                        {"id": prompt_id, "type": "prompt", "message": marker}
                    )
                    + "\n"
                )
                process.stdin.flush()
                # The prompt is transmitted only over stdin, never in process arguments.
                self.assertNotIn(marker, "\0".join(arguments))

                received: list[dict[str, object]] = []
                deadline = time.monotonic() + 25
                while time.monotonic() < deadline:
                    try:
                        value = lines.get(timeout=0.5)
                    except queue.Empty:
                        if process.poll() is not None:
                            break
                        continue
                    received.append(value)
                    if value.get("type") == "agent_settled":
                        break
                if not any(value.get("type") == "agent_settled" for value in received):
                    self.fail(
                        "Pi did not settle. stderr=" + "".join(stderr)[-4000:]
                    )
                state_response = [
                    value
                    for value in received
                    if value.get("type") == "response" and value.get("id") == state_id
                ]
                prompt_response = [
                    value
                    for value in received
                    if value.get("type") == "response" and value.get("id") == prompt_id
                ]
                process.stdin.write(
                    json.dumps({"id": stats_id, "type": "get_session_stats"}) + "\n"
                )
                process.stdin.flush()
                deadline = time.monotonic() + 5
                stats_response = None
                while time.monotonic() < deadline:
                    try:
                        value = lines.get(timeout=0.5)
                    except queue.Empty:
                        continue
                    if value.get("type") == "response" and value.get("id") == stats_id:
                        stats_response = value
                        break
                self.assertTrue(state_response and state_response[-1].get("success") is True)
                self.assertTrue(prompt_response and prompt_response[-1].get("success") is True)
                self.assertIsNotNone(stats_response)
                self.assertTrue(stats_response.get("success"))
                self.assertIsInstance(stats_response.get("data"), dict)
                deltas = [
                    value.get("assistantMessageEvent", {}).get("delta")
                    for value in received
                    if value.get("type") == "message_update"
                    and isinstance(value.get("assistantMessageEvent"), dict)
                ]
                self.assertIn("PIDECK_RPC_OK", deltas)
                terminal_messages = [
                    value.get("message")
                    for value in received
                    if value.get("type") == "message_end"
                    and isinstance(value.get("message"), dict)
                    and value["message"].get("role") == "assistant"
                ]
                self.assertTrue(terminal_messages)
                self.assertEqual("stop", terminal_messages[-1].get("stopReason"))
                self.assertEqual(
                    ["PIDECK_RPC_OK"],
                    [
                        part.get("text")
                        for part in terminal_messages[-1].get("content", [])
                        if isinstance(part, dict) and part.get("type") == "text"
                    ],
                )
                provider_request = FakeLlamaHandler.requests.get_nowait()
                self.assertEqual(256, provider_request.get("max_tokens"))
                # The first request of a newly started Pi session must not reuse
                # llama-server's single slot: it can still contain recurrent
                # state from an unrelated previous session.
                self.assertIs(provider_request.get("cache_prompt"), False)
                tool_names = {
                    tool.get("function", {}).get("name")
                    for tool in provider_request.get("tools", [])
                    if isinstance(tool, dict)
                }
                self.assertIn("pideck_load_tools", tool_names)
                self.assertIn("pideck_bash", tool_names)
                self.assertIn("code_nav", tool_names)
                self.assertNotIn("web_research", tool_names)
                self.assertNotIn("weather", tool_names)
                self.assertNotIn("pideck_edit", tool_names)
                self.assertIn(
                    system_prompt_marker,
                    json.dumps(
                        provider_request.get("messages", []),
                        ensure_ascii=False,
                    ),
                )
                self.assertIn(
                    "Answer direct questions and explicit-format requests immediately",
                    json.dumps(
                        provider_request.get("messages", []),
                        ensure_ascii=False,
                    ),
                )
                self.assertFalse(
                    any(value.get("type") == "extension_error" for value in received),
                    received,
                )

                # Explicit current-data work activates the managed web group before the
                # provider request; it does not cost an extra model/tool round-trip.
                routed_prompt_id = str(uuid.uuid4())
                process.stdin.write(
                    json.dumps(
                        {
                            "id": routed_prompt_id,
                            "type": "prompt",
                            "message": "поищи в интернете PIDECK_ROUTE_MARKER",
                        },
                        ensure_ascii=False,
                    )
                    + "\n"
                )
                process.stdin.flush()
                routed_events: list[dict[str, object]] = []
                deadline = time.monotonic() + 25
                while time.monotonic() < deadline:
                    try:
                        value = lines.get(timeout=0.5)
                    except queue.Empty:
                        if process.poll() is not None:
                            break
                        continue
                    routed_events.append(value)
                    if value.get("type") == "agent_settled":
                        break
                if not any(value.get("type") == "agent_settled" for value in routed_events):
                    self.fail(
                        "Routed Pi prompt did not settle. stderr=" + "".join(stderr)[-4000:]
                    )
                routed_request = FakeLlamaHandler.requests.get_nowait()
                routed_tool_names = {
                    tool.get("function", {}).get("name")
                    for tool in routed_request.get("tools", [])
                    if isinstance(tool, dict)
                }
                self.assertIn("pideck_load_tools", routed_tool_names)
                self.assertIn("web_research", routed_tool_names, routed_events)
                self.assertNotIn("weather", routed_tool_names)
                self.assertFalse(
                    any(value.get("type") == "extension_error" for value in routed_events),
                    routed_events,
                )

                # A command written from message_end is processed too late to become a
                # follow-up in Pi 0.82.1. The bridge therefore waits for agent_settled and
                # starts an ordinary prompt in the same session. Keep that exact lifecycle
                # pinned so a future retry cannot silently remain queued for the next user.
                fragment_prompt_id = str(uuid.uuid4())
                process.stdin.write(
                    json.dumps(
                        {
                            "id": fragment_prompt_id,
                            "type": "prompt",
                            "message": FRAGMENT_PROMPT_MARKER,
                        }
                    )
                    + "\n"
                )
                process.stdin.flush()
                fragment_events: list[dict[str, object]] = []
                deadline = time.monotonic() + 25
                while time.monotonic() < deadline:
                    try:
                        value = lines.get(timeout=0.5)
                    except queue.Empty:
                        if process.poll() is not None:
                            break
                        continue
                    fragment_events.append(value)
                    if value.get("type") == "agent_settled":
                        break
                self.assertTrue(
                    any(value.get("type") == "agent_settled" for value in fragment_events),
                    fragment_events,
                )
                fragment_terminal = [
                    value["message"]
                    for value in fragment_events
                    if value.get("type") == "message_end"
                    and isinstance(value.get("message"), dict)
                    and value["message"].get("role") == "assistant"
                ]
                self.assertEqual(
                    ["О"],
                    [
                        part.get("text")
                        for part in fragment_terminal[-1].get("content", [])
                        if isinstance(part, dict) and part.get("type") == "text"
                    ],
                )
                fragment_request = FakeLlamaHandler.requests.get_nowait()
                self.assertIn(
                    FRAGMENT_PROMPT_MARKER,
                    json.dumps(fragment_request.get("messages", [])),
                )

                retry_prompt_id = str(uuid.uuid4())
                process.stdin.write(
                    json.dumps(
                        {
                            "id": retry_prompt_id,
                            "type": "prompt",
                            "message": RETRY_PROMPT_MARKER,
                        }
                    )
                    + "\n"
                )
                process.stdin.flush()
                retry_events: list[dict[str, object]] = []
                deadline = time.monotonic() + 25
                while time.monotonic() < deadline:
                    try:
                        value = lines.get(timeout=0.5)
                    except queue.Empty:
                        if process.poll() is not None:
                            break
                        continue
                    retry_events.append(value)
                    if value.get("type") == "agent_settled":
                        break
                self.assertTrue(
                    any(value.get("type") == "agent_settled" for value in retry_events),
                    retry_events,
                )
                retry_response = [
                    value
                    for value in retry_events
                    if value.get("type") == "response"
                    and value.get("id") == retry_prompt_id
                ]
                self.assertTrue(retry_response and retry_response[-1].get("success"))
                retry_terminal = [
                    value["message"]
                    for value in retry_events
                    if value.get("type") == "message_end"
                    and isinstance(value.get("message"), dict)
                    and value["message"].get("role") == "assistant"
                ]
                self.assertEqual(
                    [RETRY_COMPLETE_TEXT],
                    [
                        part.get("text")
                        for part in retry_terminal[-1].get("content", [])
                        if isinstance(part, dict) and part.get("type") == "text"
                    ],
                )
                retry_request = FakeLlamaHandler.requests.get_nowait()
                retry_history = json.dumps(
                    retry_request.get("messages", []), ensure_ascii=False
                )
                self.assertIn(FRAGMENT_PROMPT_MARKER, retry_history)
                self.assertIn(RETRY_PROMPT_MARKER, retry_history)

                abort_id = str(uuid.uuid4())
                process.stdin.write(
                    json.dumps({"id": abort_id, "type": "abort"}) + "\n"
                )
                process.stdin.flush()
                deadline = time.monotonic() + 5
                abort_response = None
                while time.monotonic() < deadline:
                    try:
                        value = lines.get(timeout=0.5)
                    except queue.Empty:
                        continue
                    if value.get("type") == "response" and value.get("id") == abort_id:
                        abort_response = value
                        break
                self.assertIsNotNone(abort_response)
                self.assertTrue(abort_response.get("success"))
            finally:
                try:
                    process.stdin.close()
                except OSError:
                    pass
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
                stdout_thread.join(timeout=2)
                stderr_thread.join(timeout=2)
                process.stdout.close()
                process.stderr.close()


if __name__ == "__main__":
    unittest.main()
