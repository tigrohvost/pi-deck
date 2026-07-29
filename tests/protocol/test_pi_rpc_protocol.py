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
        chunks = [
            {
                "id": "fixture",
                "object": "chat.completion.chunk",
                "created": int(time.time()),
                "model": "fixture-model",
                "choices": [
                    {
                        "index": 0,
                        "delta": {"role": "assistant", "content": "PIDECK_RPC_OK"},
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
                        "models": [
                            {
                                "id": "fixture-model",
                                "name": "Fixture",
                                "reasoning": False,
                                "input": ["text"],
                                "contextWindow": 4096,
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
            web_tools_extension = (
                REPOSITORY
                / "app"
                / "src"
                / "main"
                / "assets"
                / "runtime"
                / "pideck-web-tools.ts"
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
                str(context_guard_extension),
                "--extension",
                str(web_tools_extension),
                "--no-builtin-tools",
                "--tools",
                "read,grep,find,ls,web_search,web_fetch,weather,"
                "pideck_bash,pideck_edit,pideck_write,pideck_replace_lines",
                "--extension",
                str(extension),
            ]
            environment = os.environ.copy()
            environment["PI_CODING_AGENT_DIR"] = str(agent_dir)
            environment["PI_CODING_AGENT_SESSION_DIR"] = str(sessions)
            environment["PIDECK_HOME"] = str(base)
            environment["PI_OFFLINE"] = "1"
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
                provider_request = FakeLlamaHandler.requests.get_nowait()
                self.assertIs(provider_request.get("cache_prompt"), True)
                tool_names = {
                    tool.get("function", {}).get("name")
                    for tool in provider_request.get("tools", [])
                    if isinstance(tool, dict)
                }
                self.assertIn("web_search", tool_names)
                self.assertIn("weather", tool_names)
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
