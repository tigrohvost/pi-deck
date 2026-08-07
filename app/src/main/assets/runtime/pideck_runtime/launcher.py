"""Command entry point installed into ~/.pideck/runtime."""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
import uuid
from pathlib import Path
from typing import Any

from . import RUNTIME_CONTRACT_VERSION, RUNTIME_VERSION
from .bridge import (
    LOCAL_CACHE_EXTENSION,
    CONTEXT_GUARD_EXTENSION,
    HASHLINE_EXTENSION,
    SYSTEM_PROMPT_EXTENSION,
    TOOL_ROUTER_EXTENSION,
    WEB_TOOLS_EXTENSION,
    bootstrap_bridge,
    parse_system_prompt_request,
    persist_system_prompt,
    serve,
    stop_bridge,
    system_prompt_environment,
)
from .common import (
    BASE,
    PREFIX,
    PiDeckError,
    atomic_write_json,
    bounded_text,
    managed_environment,
    metadata_for_process,
    process_alive,
    read_json,
    read_stdin_json,
    require_session_id,
    require_string,
    require_uuid4,
    result_ok,
    run_cli,
    terminate_exact,
)
from .model_store import (
    ensure_pi_compaction_settings,
    install_private,
    model_by_id,
    verify_private,
)
from .server_supervisor import (
    adopt_external_server,
    read_server_status,
    server_daemon,
    start_server,
    stop_server,
)


def _profile_arguments(profile: str, agent_mode: str = "agent") -> list[str]:
    if agent_mode == "chat":
        return ["--no-tools"]
    if profile == "read_only":
        return [
            "--tools",
            "read,grep,find,ls,web_search,web_fetch,weather,pideck_load_tools",
        ]
    if profile == "confirm_changes":
        return [
            "--no-builtin-tools",
            "--tools",
            "read,grep,find,ls,web_search,web_fetch,weather,"
            "pideck_bash,pideck_edit,pideck_write,pideck_replace_lines,"
            "pideck_load_tools",
            "--extension",
            str(BASE / "runtime" / "pideck-permission-gate.ts"),
        ]
    if profile == "autonomous":
        return [
            "--tools",
            "read,bash,edit,write,grep,find,ls,web_search,web_fetch,weather,"
            "pideck_replace_lines,pideck_load_tools",
        ]
    raise PiDeckError("INVALID_PROFILE", "Unknown access profile")


def agent_once(request: dict[str, Any]) -> dict[str, Any]:
    operation_id = require_uuid4(request)
    model_id = require_string(request, "modelId", 128)
    profile = require_string(request, "accessProfile", 32)
    agent_mode = request.get("agentMode", "agent")
    if not isinstance(agent_mode, str) or agent_mode not in {"chat", "agent"}:
        raise PiDeckError("INVALID_AGENT_MODE", "Unknown agent mode")
    prompt = require_string(request, "prompt", 64 * 1024)
    system_prompt_path = (
        BASE / "processes" / f"agent-system-prompt-{operation_id}.txt"
    )
    system_prompt, system_prompt_content = parse_system_prompt_request(
        request, system_prompt_path
    )
    session_id = request.get("sessionId")
    if session_id is not None:
        require_session_id({"sessionId": session_id})
    model = model_by_id(model_id)
    ensure_pi_compaction_settings(model)
    if not LOCAL_CACHE_EXTENSION.is_file():
        raise PiDeckError(
            "LOCAL_CACHE_EXTENSION_MISSING",
            "Local prompt-cache extension is not installed",
        )
    if not SYSTEM_PROMPT_EXTENSION.is_file():
        raise PiDeckError(
            "SYSTEM_PROMPT_EXTENSION_MISSING",
            "Managed system-prompt extension is not installed",
        )
    if not CONTEXT_GUARD_EXTENSION.is_file():
        raise PiDeckError(
            "CONTEXT_GUARD_EXTENSION_MISSING",
            "Local context-guard extension is not installed",
        )
    if not HASHLINE_EXTENSION.is_file():
        raise PiDeckError(
            "HASHLINE_EXTENSION_MISSING",
            "Managed anchored-edit extension is not installed",
        )
    if not WEB_TOOLS_EXTENSION.is_file():
        raise PiDeckError(
            "WEB_TOOLS_EXTENSION_MISSING",
            "Managed web-tools extension is not installed",
        )
    if not TOOL_ROUTER_EXTENSION.is_file():
        raise PiDeckError(
            "TOOL_ROUTER_EXTENSION_MISSING",
            "Managed tool-router extension is not installed",
        )

    arguments = [
        str(BASE / "runtime" / "bin" / "pi"),
        "--print",
        "--mode",
        "json",
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
        "--extension",
        str(LOCAL_CACHE_EXTENSION),
        "--extension",
        str(SYSTEM_PROMPT_EXTENSION),
        "--extension",
        str(HASHLINE_EXTENSION),
        "--extension",
        str(CONTEXT_GUARD_EXTENSION),
        "--extension",
        str(WEB_TOOLS_EXTENSION),
        "--extension",
        str(TOOL_ROUTER_EXTENSION),
    ]
    persist_system_prompt(system_prompt_path, system_prompt_content)
    if session_id:
        arguments.extend(["--session-id", str(session_id)])
    arguments.extend(_profile_arguments(profile, agent_mode))
    environment = managed_environment(operation_id)
    environment["PI_CODING_AGENT_DIR"] = str(BASE / "pi")
    environment["PI_CODING_AGENT_SESSION_DIR"] = str(BASE / "sessions")
    environment["PIDECK_ACCESS_PROFILE"] = profile
    environment["PIDECK_AGENT_MODE"] = agent_mode
    environment["PIDECK_HASHLINE_APPROVAL"] = (
        "none" if profile == "autonomous" else "required"
    )
    environment.update(system_prompt_environment(system_prompt, system_prompt_path))
    try:
        process = subprocess.Popen(
            arguments,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=environment,
            cwd=BASE / "workspace",
            start_new_session=True,
            close_fds=True,
        )
    except Exception:
        system_prompt_path.unlink(missing_ok=True)
        raise
    metadata_path = BASE / "processes" / f"agent-{operation_id}.json"
    metadata = metadata_for_process(
        process,
        arguments,
        operation_id,
        "pi",
        {
            "modelId": model_id,
            "accessProfile": profile,
            "agentMode": agent_mode,
            "systemPromptMode": system_prompt["systemPromptMode"],
            "systemPromptSha256": system_prompt["systemPromptSha256"],
            "systemPromptBytes": system_prompt["systemPromptBytes"],
        },
    )
    atomic_write_json(metadata_path, metadata)
    try:
        stdout, stderr = process.communicate(
            input=prompt.encode("utf-8"), timeout=int(request.get("timeoutSeconds", 2700))
        )
    except subprocess.TimeoutExpired:
        terminate_exact(metadata)
        raise PiDeckError("AGENT_TIMEOUT", "Agent turn exceeded its configured timeout")
    finally:
        system_prompt_path.unlink(missing_ok=True)
        if not process_alive(metadata):
            metadata_path.unlink(missing_ok=True)
    return {
        "state": "COMPLETED" if process.returncode == 0 else "FAILED",
        "exitCode": process.returncode,
        "stdout": bounded_text(stdout.decode("utf-8", "replace"), 256 * 1024),
        "stderr": bounded_text(stderr.decode("utf-8", "replace"), 64 * 1024),
    }


def abort_agent(request: dict[str, Any]) -> dict[str, Any]:
    target = require_uuid4(request, "targetOperationId")
    metadata_path = BASE / "processes" / f"agent-{target}.json"
    if not metadata_path.is_file():
        return {"state": "NOT_RUNNING", "targetOperationId": target}
    metadata = read_json(metadata_path)
    if metadata.get("operationId") != target:
        raise PiDeckError("IDENTITY_MISMATCH", "Agent metadata belongs to another operation")
    if not process_alive(metadata):
        return {"state": "NOT_RUNNING", "targetOperationId": target}
    if not terminate_exact(metadata):
        raise PiDeckError("ABORT_UNCONFIRMED", "Target process group did not stop")
    metadata_path.unlink(missing_ok=True)
    return {"state": "ABORTED", "targetOperationId": target}


def archive_sessions() -> dict[str, Any]:
    source = BASE / "sessions"
    archive = BASE / "session-archive" / (
        time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:12]
    )
    moved = 0
    if source.is_dir():
        entries = [entry for entry in source.iterdir()]
        if entries:
            archive.mkdir(parents=True, exist_ok=False)
            os.chmod(archive, 0o700)
            for entry in entries:
                entry.rename(archive / entry.name)
                moved += 1
    return {"state": "READY", "archivedEntries": moved, "archive": str(archive) if moved else None}


MAX_LISTED_SESSIONS = 64
MAX_SESSION_SCAN_BYTES = 256 * 1024


def _session_transcript(entry: Path) -> Path | None:
    """The file a session's messages live in, whether the session is a file or a directory."""
    if entry.is_file():
        return entry
    if not entry.is_dir():
        return None
    candidates = [child for child in entry.iterdir() if child.is_file()]
    if not candidates:
        return None
    return max(candidates, key=lambda child: child.stat().st_size)


def _session_summary(entry: Path) -> tuple[str, int]:
    """First user message and message count, or empty when the format is not ours to read."""
    transcript = _session_transcript(entry)
    if transcript is None:
        return "", 0
    title = ""
    messages = 0
    try:
        with transcript.open("r", encoding="utf-8", errors="replace") as handle:
            for line in handle.read(MAX_SESSION_SCAN_BYTES).splitlines():
                line = line.strip()
                if not line:
                    continue
                try:
                    value = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if not isinstance(value, dict):
                    continue
                role = value.get("role") or (value.get("message") or {}).get("role")
                if not role:
                    continue
                messages += 1
                if title or role != "user":
                    continue
                content = value.get("content")
                if isinstance(content, list):
                    parts = [
                        part.get("text", "")
                        for part in content
                        if isinstance(part, dict) and part.get("type") == "text"
                    ]
                    content = " ".join(part for part in parts if part)
                if isinstance(content, str) and content.strip():
                    title = bounded_text(content.strip(), 160)
    except OSError:
        return title, messages
    return title, messages


def _session_bytes(entry: Path) -> int:
    if entry.is_file():
        return entry.stat().st_size
    total = 0
    for child in entry.rglob("*"):
        if child.is_file():
            total += child.stat().st_size
    return total


def list_sessions() -> dict[str, Any]:
    """What is on disk under ~/.pideck/sessions, newest first."""
    source = BASE / "sessions"
    if not source.is_dir():
        return {"state": "READY", "sessions": [], "count": 0, "totalBytes": 0}

    entries = []
    total_bytes = 0
    for entry in source.iterdir():
        try:
            entries.append((entry, entry.stat().st_mtime, _session_bytes(entry)))
        except OSError:
            continue
    total_bytes = sum(size for _, _, size in entries)
    entries.sort(key=lambda item: item[1], reverse=True)

    sessions = []
    for entry, modified, size in entries[:MAX_LISTED_SESSIONS]:
        title, messages = _session_summary(entry)
        sessions.append(
            {
                "id": entry.stem if entry.is_file() else entry.name,
                "title": title,
                "messages": messages,
                "bytes": size,
                "updatedAtEpochMs": int(modified * 1000),
            }
        )
    return {
        "state": "READY",
        "sessions": sessions,
        "count": len(entries),
        "totalBytes": total_bytes,
    }


def probe() -> dict[str, Any]:
    def version(arguments: list[str]) -> str | None:
        try:
            result = subprocess.run(
                arguments,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                timeout=10,
                check=False,
                env=managed_environment(str(uuid.uuid4())),
            )
            return bounded_text(result.stdout.strip(), 2048) if result.returncode == 0 else None
        except (OSError, subprocess.TimeoutExpired):
            return None

    layout_ready = all(
        path.exists()
        for path in (
            BASE / "runtime" / "models-v2.json",
            BASE / "runtime" / "compatibility.json",
            LOCAL_CACHE_EXTENSION,
            SYSTEM_PROMPT_EXTENSION,
            HASHLINE_EXTENSION,
            CONTEXT_GUARD_EXTENSION,
            WEB_TOOLS_EXTENSION,
            TOOL_ROUTER_EXTENSION,
            BASE / "runtime" / "pideck-permission-gate.ts",
            BASE / "workspace",
            BASE / "sessions",
            BASE / "pi",
        )
    )
    pi_version = version([str(BASE / "runtime" / "bin" / "pi"), "--version"])
    node_version = version([str(PREFIX / "bin" / "node"), "--version"])
    python_version = version([sys.executable, "--version"])
    # Inference is owned by the Android foreground service. The exact native
    # artifacts are verified against native-runtime.json by the APK build; the
    # Termux runtime only needs the pinned build identifier for its provider
    # contract and must not depend on a second llama-cpp package.
    llama_executable_version = None
    llama_package_version = None
    llama_version = None
    compatibility: dict[str, Any] = {}
    try:
        compatibility = read_json(BASE / "runtime" / "compatibility.json")
        expected_pi = str(compatibility["pi"]["version"])
        minimum_node = str(compatibility["node"]["minimumVersion"])
        minimum_llama = str(compatibility["llamaCpp"]["minimumVersion"])
        maximum_llama = str(compatibility["llamaCpp"]["maximumTestedVersion"])
        if (
            compatibility["llamaCpp"].get("owner") != "android-native"
            or minimum_llama != maximum_llama
        ):
            raise PiDeckError(
                "INVALID_COMPATIBILITY",
                "Native llama.cpp ownership/build range is not exact",
            )
        llama_version = minimum_llama
        pi_exact = pi_version == expected_pi
        node_supported = _semver_at_least(node_version, minimum_node)
        llama_supported = _llama_in_range(
            llama_version, minimum_llama, maximum_llama
        )
    except (KeyError, TypeError, ValueError, PiDeckError):
        pi_exact = False
        node_supported = False
        llama_supported = False
    versions_compatible = pi_exact and node_supported and llama_supported
    return {
        "state": (
            "READY"
            if layout_ready and versions_compatible
            else "INCOMPATIBLE"
            if layout_ready
            else "INCOMPLETE"
        ),
        "runtimeVersion": RUNTIME_VERSION,
        "runtimeContractVersion": RUNTIME_CONTRACT_VERSION,
        "piVersion": pi_version,
        "nodeVersion": node_version,
        "pythonVersion": python_version,
        "llamaVersion": llama_version,
        "llamaExecutableVersion": llama_executable_version,
        "llamaPackageVersion": llama_package_version,
        "layoutReady": layout_ready,
        "versionsCompatible": versions_compatible,
        "compatibility": {
            "piExact": pi_exact,
            "nodeSupported": node_supported,
            "llamaSupported": llama_supported,
        },
        "server": read_server_status(),
    }


def _semver_at_least(actual: str | None, minimum: str) -> bool:
    def components(value: str | None) -> tuple[int, int, int] | None:
        if not isinstance(value, str):
            return None
        match = re.search(r"(?:^|\D)(\d+)\.(\d+)\.(\d+)(?:\D|$)", value)
        if match is None:
            return None
        return tuple(int(match.group(index)) for index in range(1, 4))

    parsed_actual = components(actual)
    parsed_minimum = components(minimum)
    return (
        parsed_actual is not None
        and parsed_minimum is not None
        and parsed_actual >= parsed_minimum
    )


def _llama_in_range(
    actual: str | None, minimum: str, maximum: str
) -> bool:
    parsed = _llama_build(actual)
    lower = _llama_build(minimum)
    upper = _llama_build(maximum)
    return (
        parsed is not None
        and lower is not None
        and upper is not None
        and lower <= parsed <= upper
    )


def _resolved_llama_version(
    executable_version: str | None, package_version: str | None
) -> str | None:
    if _llama_build(executable_version) is not None:
        return executable_version
    if (
        isinstance(executable_version, str)
        and executable_version.strip()
        and _llama_build(package_version) is not None
    ):
        return package_version
    return executable_version


def _llama_build(value: str | None) -> int | None:
    if not isinstance(value, str):
        return None
    for pattern in (
        r"\bb(\d{4,})\b",
        r"\bbuild[\s:]+(\d{4,})\b",
        r"\bversion[\s:]+(\d{4,})\b",
    ):
        match = re.search(pattern, value, re.IGNORECASE)
        if match is not None:
            build = int(match.group(1))
            # Zero is the sentinel emitted by builds without version metadata.
            return build if build > 0 else None
    return None


def reconcile() -> dict[str, Any]:
    active_agents: list[dict[str, Any]] = []
    for path in (BASE / "processes").glob("agent-*.json"):
        try:
            metadata = read_json(path)
        except PiDeckError:
            continue
        if process_alive(metadata):
            active_agents.append(
                {
                    "operationId": metadata.get("operationId"),
                    "pid": metadata.get("pid"),
                }
            )
    return {
        "state": "READY",
        "activeAgents": active_agents,
        "server": read_server_status(),
    }


def dispatch(command: str) -> dict[str, Any]:
    if command == "probe":
        return result_ok(**probe())
    if command == "install-model":
        request = read_stdin_json()
        return result_ok(
            **install_private(
                require_string(request, "modelId", 128),
                require_string(request, "sourcePath", 16 * 1024),
            )
        )
    if command == "verify-model":
        request = read_stdin_json()
        return result_ok(**verify_private(require_string(request, "modelId", 128)))
    if command == "server-start":
        return result_ok(**start_server(read_stdin_json()))
    if command == "server-adopt":
        return result_ok(**adopt_external_server(read_stdin_json()))
    if command == "server-stop":
        return result_ok(**stop_server())
    if command == "server-status":
        return result_ok(**read_server_status())
    if command == "bridge-start":
        return result_ok(**bootstrap_bridge(read_stdin_json()))
    if command == "bridge-stop":
        return result_ok(**stop_bridge())
    if command == "agent-once":
        return result_ok(**agent_once(read_stdin_json(MAX_PROMPT_INPUT)))
    if command == "abort-agent":
        return result_ok(**abort_agent(read_stdin_json()))
    if command == "archive-sessions":
        return result_ok(**archive_sessions())
    if command == "list-sessions":
        return result_ok(**list_sessions())
    if command == "reconcile":
        return result_ok(**reconcile())
    raise PiDeckError("UNKNOWN_COMMAND", f"Unknown runtime command: {command}")


MAX_PROMPT_INPUT = 96 * 1024


def main() -> None:
    if len(sys.argv) < 2:
        run_cli(lambda: dispatch(""))
        return
    command = sys.argv[1]
    if command == "server-daemon":
        if len(sys.argv) != 3:
            raise SystemExit(64)
        raise SystemExit(server_daemon(Path(sys.argv[2])))
    if command == "bridge-daemon":
        if len(sys.argv) != 3:
            raise SystemExit(64)
        raise SystemExit(serve(Path(sys.argv[2])))
    run_cli(lambda: dispatch(command))


if __name__ == "__main__":
    main()
