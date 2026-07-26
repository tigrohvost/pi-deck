"""Exact-identity llama-server supervisor with strict health verification."""

from __future__ import annotations

import json
import os
import secrets
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

from .common import (
    BASE,
    PREFIX,
    PiDeckError,
    atomic_write_bytes,
    atomic_write_json,
    bounded_text,
    managed_environment,
    metadata_for_process,
    process_alive,
    process_matches,
    read_json,
    require_string,
    require_uuid4,
    terminate_exact,
    utc_now,
)
from .model_store import load_catalog, model_by_id, private_model_path, verify_private

SERVER_DIRECTORY = BASE / "server"
SERVER_METADATA = SERVER_DIRECTORY / "supervisor.json"
SERVER_STATUS = SERVER_DIRECTORY / "status.json"
SERVER_CONFIG = SERVER_DIRECTORY / "launch.json"
SERVER_API_KEY = SERVER_DIRECTORY / "api-key"
SERVER_LOG = BASE / "logs" / "llama-server.log"
PI_MODELS = BASE / "pi" / "models.json"
BRIDGE_PORT = 8787
DEFAULT_SERVER_PORT = 8080
MANAGED_SERVER_FLAGS = {
    "-m",
    "--model",
    "--alias",
    "--host",
    "--port",
    "-c",
    "--ctx-size",
    "-np",
    "--parallel",
    "-t",
    "--threads",
    "--jinja",
    "--reasoning",
    "--temp",
    "--top-p",
    "--top-k",
    "--min-p",
    "--presence-penalty",
    "--api-key",
}


def _decimal(value: Any) -> str:
    number = float(value)
    rendered = f"{number:.4f}".rstrip("0")
    return rendered + "0" if rendered.endswith(".") else rendered


def effective_server_arguments(
    model: dict[str, Any],
    model_path: Path,
    threads: int,
    port: int,
    api_key: str,
) -> list[str]:
    runtime = model["runtime"]
    sampling = model["sampling"]
    arguments = [
        str(PREFIX / "bin" / "llama-server"),
        "-m",
        str(model_path),
        "--alias",
        str(model["id"]),
        "--host",
        "127.0.0.1",
        "--port",
        str(port),
        "-c",
        str(int(runtime["recommendedContext"])),
        "-np",
        str(int(runtime["parallelSlots"])),
        "-t",
        str(max(2, min(8, int(threads)))),
    ]
    if runtime["requiresJinja"]:
        arguments.append("--jinja")
    if runtime["reasoningMode"] != "model-default":
        arguments.extend(["--reasoning", str(runtime["reasoningMode"])])
    arguments.extend(
        [
            "--temp",
            _decimal(sampling["temperature"]),
            "--top-p",
            _decimal(sampling["topP"]),
            "--top-k",
            str(int(sampling["topK"])),
            "--min-p",
            _decimal(sampling["minP"]),
            "--presence-penalty",
            _decimal(sampling["presencePenalty"]),
        ]
    )
    extra = runtime.get("serverArgs", [])
    if not isinstance(extra, list) or any(
        not isinstance(item, str) or "\0" in item or len(item) > 512 for item in extra
    ):
        raise PiDeckError("INVALID_CATALOG", "Model serverArgs are unsafe")
    overridden = [
        item.split("=", 1)[0]
        for item in extra
        if item.split("=", 1)[0] in MANAGED_SERVER_FLAGS
    ]
    if overridden:
        raise PiDeckError(
            "INVALID_CATALOG",
            f"Model serverArgs overrides managed flag: {overridden[0]}",
        )
    arguments.extend(extra)
    arguments.extend(["--api-key", api_key])
    return arguments


def _port_available(port: int) -> bool:
    probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(("127.0.0.1", port))
        return True
    except OSError:
        return False
    finally:
        probe.close()


def _request_json(url: str, api_key: str, timeout: float = 1.5) -> dict[str, Any]:
    request = urllib.request.Request(
        url,
        headers={"Authorization": f"Bearer {api_key}", "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            if response.status < 200 or response.status >= 300:
                raise PiDeckError("HEALTH_HTTP", f"Health returned HTTP {response.status}")
            raw = response.read(256 * 1024 + 1)
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise PiDeckError("HEALTH_UNREACHABLE", "llama-server health is unreachable") from error
    if len(raw) > 256 * 1024:
        raise PiDeckError("HEALTH_MALFORMED", "Health payload is oversized")
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise PiDeckError("HEALTH_MALFORMED", "Health payload is not valid JSON") from error
    if not isinstance(value, dict):
        raise PiDeckError("HEALTH_MALFORMED", "Health payload is not an object")
    return value


def strict_health(port: int, model_id: str, api_key: str) -> dict[str, Any]:
    health = _request_json(f"http://127.0.0.1:{port}/health", api_key)
    if health.get("status") != "ok":
        raise PiDeckError("HEALTH_NOT_READY", "llama-server is not ready")
    models = _request_json(f"http://127.0.0.1:{port}/v1/models", api_key)
    data = models.get("data")
    if not isinstance(data, list):
        raise PiDeckError("HEALTH_MALFORMED", "Models payload has no data array")
    exact = [
        item
        for item in data
        if isinstance(item, dict) and item.get("id") == model_id
    ]
    if len(exact) != 1:
        raise PiDeckError(
            "WRONG_MODEL", f"Server does not expose exact expected model ID: {model_id}"
        )
    return {"status": "ok", "modelId": model_id}


def _llama_version() -> str:
    try:
        result = subprocess.run(
            [str(PREFIX / "bin" / "llama-server"), "--version"],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=8,
            check=False,
        )
        return bounded_text(result.stdout.strip(), 2048)
    except (OSError, subprocess.TimeoutExpired):
        return "unknown"


def _write_pi_models(api_key: str, port: int) -> None:
    catalog = load_catalog()
    models = []
    for model in catalog["models"]:
        if not isinstance(model, dict) or model.get("status") in {"BLOCKED", "DEPRECATED"}:
            continue
        models.append(
            {
                "id": model["id"],
                "name": f"{model['title']} · PI//DECK {model['tier']}",
                "reasoning": model["runtime"]["reasoningMode"] == "on",
                "input": ["text"],
                "contextWindow": model["runtime"]["recommendedContext"],
                "maxTokens": model["agent"]["maxTokens"],
                "cost": {
                    "input": 0,
                    "output": 0,
                    "cacheRead": 0,
                    "cacheWrite": 0,
                },
            }
        )
    config = {
        "providers": {
            "pideck": {
                "baseUrl": f"http://127.0.0.1:{port}/v1",
                "api": "openai-completions",
                "apiKey": api_key,
                "compat": {
                    "supportsDeveloperRole": False,
                    "supportsReasoningEffort": False,
                    "supportsStore": False,
                    "supportsUsageInStreaming": False,
                    "maxTokensField": "max_tokens",
                },
                "models": models,
            }
        }
    }
    PI_MODELS.parent.mkdir(parents=True, exist_ok=True)
    os.chmod(PI_MODELS.parent, 0o700)
    atomic_write_json(PI_MODELS, config, 0o600)


def _wake_lock(enabled: bool) -> None:
    executable = PREFIX / "bin" / ("termux-wake-lock" if enabled else "termux-wake-unlock")
    if not executable.exists():
        return
    try:
        subprocess.run(
            [str(executable)],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=8,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        pass


def start_server(request: dict[str, Any]) -> dict[str, Any]:
    operation_id = require_uuid4(request)
    model_id = require_string(request, "modelId", 128)
    threads = int(request.get("threads", 4))
    port = int(request.get("port", DEFAULT_SERVER_PORT))
    if port < 1024 or port > 65535:
        raise PiDeckError("INVALID_PORT", "Server port is outside the allowed range")
    model = model_by_id(model_id)

    verification = verify_private(model_id)
    if verification["state"] != "READY":
        raise PiDeckError(
            "MODEL_NOT_READY",
            f"Private GGUF is {verification['state']}; refusing server start",
        )

    if SERVER_METADATA.is_file():
        try:
            existing = read_json(SERVER_METADATA)
        except PiDeckError:
            existing = {}
        if process_alive(existing):
            status = read_server_status()
            same_launch = (
                status.get("state") == "READY"
                and status.get("modelId") == model_id
                and int(existing.get("port", -1)) == port
                and existing.get("modelSha256") == model["artifact"]["sha256"]
            )
            if same_launch:
                try:
                    key = SERVER_API_KEY.read_text(encoding="utf-8").strip()
                    strict_health(port, model_id, key)
                    return {
                        "state": "READY",
                        "modelId": model_id,
                        "idempotent": True,
                        "port": port,
                    }
                except (OSError, PiDeckError):
                    # A managed process with stale/wrong health is safe to replace, but never
                    # advertise it as READY merely because its PID still exists.
                    pass
            if not terminate_exact(existing):
                raise PiDeckError("SERVER_BUSY", "Could not stop the managed previous server")
            _wake_lock(False)
        elif existing:
            atomic_write_json(
                SERVER_STATUS,
                {
                    "schemaVersion": 1,
                    "state": "STALE",
                    "modelId": existing.get("modelId"),
                    "updatedAt": utc_now(),
                },
            )

    if not _port_available(port):
        raise PiDeckError(
            "PORT_OCCUPIED",
            f"127.0.0.1:{port} is occupied by an unmanaged process; nothing was killed",
        )

    api_key = secrets.token_urlsafe(32)
    atomic_write_bytes(SERVER_API_KEY, api_key.encode("ascii"), 0o600)
    _write_pi_models(api_key, port)
    config = {
        "schemaVersion": 1,
        "operationId": operation_id,
        "modelId": model_id,
        "modelSha256": model["artifact"]["sha256"],
        "threads": max(2, min(8, threads)),
        "port": port,
        "createdAt": utc_now(),
    }
    atomic_write_json(SERVER_CONFIG, config)
    atomic_write_json(
        SERVER_STATUS,
        {
            "schemaVersion": 1,
            "state": "STARTING",
            "modelId": model_id,
            "port": port,
            "updatedAt": utc_now(),
        },
    )
    arguments = [
        sys.executable,
        "-m",
        "pideck_runtime.launcher",
        "server-daemon",
        str(SERVER_CONFIG),
    ]
    environment = managed_environment(operation_id)
    SERVER_LOG.parent.mkdir(parents=True, exist_ok=True)
    log = SERVER_LOG.open("wb")
    try:
        process = subprocess.Popen(
            arguments,
            stdin=subprocess.DEVNULL,
            stdout=log,
            stderr=subprocess.STDOUT,
            env=environment,
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
            "modelSha256": model["artifact"]["sha256"],
            "port": port,
            "executableVersion": _llama_version(),
        },
    )
    atomic_write_json(SERVER_METADATA, metadata)

    timeout_seconds = min(300, max(30, int(request.get("timeoutSeconds", 180))))
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if not process_matches(metadata):
            status = read_server_status()
            raise PiDeckError(
                "SERVER_EXITED",
                f"llama-server supervisor exited while {status.get('state', 'starting')}",
            )
        status = read_server_status()
        if status.get("state") == "READY":
            return {
                "state": "READY",
                "modelId": model_id,
                "port": port,
                "privatePath": str(private_model_path(model)),
                "sha256": model["artifact"]["sha256"],
                "idempotent": False,
            }
        if status.get("state") in {"FAILED", "CRASHED"}:
            terminate_exact(metadata)
            _wake_lock(False)
            raise PiDeckError(
                "SERVER_FAILED", str(status.get("error", "llama-server startup failed"))
            )
        time.sleep(0.25)

    stop_server()
    raise PiDeckError("SERVER_TIMEOUT", "llama-server did not become ready before timeout")


def server_daemon(config_path: Path) -> int:
    config = read_json(config_path)
    operation_id = require_uuid4(config)
    model_id = require_string(config, "modelId", 128)
    port = int(config["port"])
    threads = int(config["threads"])
    model = model_by_id(model_id)
    verification = verify_private(model_id)
    if verification["state"] != "READY":
        atomic_write_json(
            SERVER_STATUS,
            {
                "schemaVersion": 1,
                "state": "FAILED",
                "modelId": model_id,
                "error": "Private GGUF failed full SHA-256 verification",
                "updatedAt": utc_now(),
            },
        )
        return 21

    api_key = SERVER_API_KEY.read_text(encoding="utf-8").strip()
    arguments = effective_server_arguments(
        model, private_model_path(model), threads, port, api_key
    )
    stop_requested = threading.Event()

    def request_stop(_signum: int, _frame: Any) -> None:
        stop_requested.set()

    signal.signal(signal.SIGINT, request_stop)
    signal.signal(signal.SIGTERM, request_stop)
    _wake_lock(True)
    child: subprocess.Popen[Any] | None = None
    child_log = SERVER_LOG.open("ab", buffering=0)
    try:
        child = subprocess.Popen(
            arguments,
            stdin=subprocess.DEVNULL,
            stdout=child_log,
            stderr=subprocess.STDOUT,
            env=managed_environment(operation_id),
            cwd=BASE / "workspace",
            close_fds=True,
        )
        child_metadata = metadata_for_process(
            child,
            arguments,
            operation_id,
            "llama-server",
            {
                "modelId": model_id,
                "modelSha256": model["artifact"]["sha256"],
                "port": port,
            },
        )
        atomic_write_json(SERVER_DIRECTORY / "child.json", child_metadata)
        deadline = time.monotonic() + 180
        last_error = "server has not answered"
        startup_ready = False
        while time.monotonic() < deadline and child.poll() is None and not stop_requested.is_set():
            try:
                strict_health(port, model_id, api_key)
                atomic_write_json(
                    SERVER_STATUS,
                    {
                        "schemaVersion": 1,
                        "state": "READY",
                        "modelId": model_id,
                        "modelSha256": model["artifact"]["sha256"],
                        "port": port,
                        "pid": child.pid,
                        "updatedAt": utc_now(),
                    },
                )
                startup_ready = True
                break
            except PiDeckError as error:
                last_error = error.message
                time.sleep(0.5)

        if not startup_ready and not stop_requested.is_set():
            if child.poll() is not None:
                last_error = f"llama-server exited during startup with code {child.returncode}"
            atomic_write_json(
                SERVER_STATUS,
                {
                    "schemaVersion": 1,
                    "state": "FAILED",
                    "modelId": model_id,
                    "error": last_error,
                    "updatedAt": utc_now(),
                },
            )
            _stop_llama_child(child)
            return 23

        while child.poll() is None and not stop_requested.wait(0.5):
            pass
        _stop_llama_child(child)
        exit_code = child.returncode
        state = "STOPPED" if stop_requested.is_set() else "CRASHED"
        atomic_write_json(
            SERVER_STATUS,
            {
                "schemaVersion": 1,
                "state": state,
                "modelId": model_id,
                "exitCode": exit_code,
                "updatedAt": utc_now(),
            },
        )
        return int(exit_code or 0)
    except Exception as error:
        atomic_write_json(
            SERVER_STATUS,
            {
                "schemaVersion": 1,
                "state": "FAILED",
                "modelId": model_id,
                "error": bounded_text(str(error), 2048),
                "updatedAt": utc_now(),
            },
        )
        return 22
    finally:
        if child is not None and child.poll() is None:
            child.kill()
        child_log.close()
        _wake_lock(False)


def _stop_llama_child(child: subprocess.Popen[Any]) -> None:
    if child.poll() is not None:
        return
    child.send_signal(signal.SIGINT)
    try:
        child.wait(timeout=4)
    except subprocess.TimeoutExpired:
        child.terminate()
        try:
            child.wait(timeout=4)
        except subprocess.TimeoutExpired:
            child.kill()
            child.wait(timeout=2)


def stop_server() -> dict[str, Any]:
    if not SERVER_METADATA.is_file():
        _wake_lock(False)
        return {"state": "STOPPED", "idempotent": True}
    try:
        metadata = read_json(SERVER_METADATA)
    except PiDeckError:
        _wake_lock(False)
        return {"state": "STALE", "idempotent": True}
    if process_matches(metadata):
        if not terminate_exact(metadata):
            raise PiDeckError(
                "SERVER_STOP_UNCONFIRMED", "Managed server did not confirm process exit"
            )
    _wake_lock(False)
    atomic_write_json(
        SERVER_STATUS,
        {
            "schemaVersion": 1,
            "state": "STOPPED",
            "modelId": metadata.get("modelId"),
            "updatedAt": utc_now(),
        },
    )
    SERVER_METADATA.unlink(missing_ok=True)
    (SERVER_DIRECTORY / "child.json").unlink(missing_ok=True)
    return {"state": "STOPPED", "idempotent": False}


def read_server_status() -> dict[str, Any]:
    if not SERVER_STATUS.is_file():
        return {"schemaVersion": 1, "state": "STOPPED"}
    try:
        status = read_json(SERVER_STATUS)
    except PiDeckError:
        return {"schemaVersion": 1, "state": "UNKNOWN"}
    if status.get("state") == "READY":
        if not SERVER_METADATA.is_file():
            return {**status, "state": "CRASHED"}
        try:
            metadata = read_json(SERVER_METADATA)
        except PiDeckError:
            return {**status, "state": "UNKNOWN"}
        if not process_alive(metadata):
            return {**status, "state": "CRASHED"}
    return status
