#!/usr/bin/env python3
"""Measure end-to-end PI//DECK agent latency through the authenticated RPC bridge.

The harness is intentionally host-side. It uses ``adb forward`` for the bridge,
keeps the deck Activity in the foreground, and never reads app/Termux private
state. The caller supplies the existing bridge token through a private host file.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import os
import re
import stat
import statistics
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any, Callable

MAX_HTTP_BYTES = 1024 * 1024
TERMINAL_EVENTS = frozenset({"TURN_COMPLETED", "TURN_FAILED", "TURN_ABORTED"})
TOKEN_PATTERN = re.compile(r"^[A-Za-z0-9_-]{43}$")
DEFAULT_PACKAGE = "dev.pideck.app"
DEFAULT_COMPONENT = "dev.pideck.app/.MainActivity"
BIG_CORE = "/sys/devices/system/cpu/cpu7/cpufreq"
THERMAL_SCRIPT = (
    f"printf 'big_scaling='; cat {BIG_CORE}/scaling_max_freq 2>/dev/null || true; printf '\n'; "
    f"printf 'big_nominal='; cat {BIG_CORE}/cpuinfo_max_freq 2>/dev/null || true; printf '\n'; "
    "for key in current_now voltage_now charge_counter energy_now; do "
    "printf 'power_%s=' \"$key\"; cat /sys/class/power_supply/battery/$key 2>/dev/null; "
    "printf '\n'; "
    "done; "
    "for zone in /sys/class/thermal/thermal_zone*; do "
    "printf 'zone='; cat \"$zone/type\" 2>/dev/null; printf '|'; "
    "cat \"$zone/temp\" 2>/dev/null; done"
)


class BenchmarkError(RuntimeError):
    pass


def _finite_number(value: Any) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    result = float(value)
    return result if math.isfinite(result) else None


def resolve_adb_serial(devices_output: str, requested: str | None = None) -> str:
    devices: dict[str, str] = {}
    for line in devices_output.splitlines()[1:]:
        fields = line.strip().split()
        if len(fields) >= 2:
            devices[fields[0]] = fields[1]
    if requested is not None:
        if devices.get(requested) != "device":
            raise BenchmarkError(f"ADB device is unavailable or unauthorized: {requested}")
        return requested
    ready = sorted(serial for serial, state in devices.items() if state == "device")
    if len(ready) != 1:
        raise BenchmarkError(
            f"Expected exactly one ready ADB device, found {len(ready)}; use --serial"
        )
    return ready[0]


def read_bridge_token(path: Path) -> str:
    try:
        file_stat = path.stat()
        token = path.read_text(encoding="ascii").strip()
    except (OSError, UnicodeError) as error:
        raise BenchmarkError("Bridge token file is unavailable") from error
    if not stat.S_ISREG(file_stat.st_mode) or file_stat.st_mode & 0o077:
        raise BenchmarkError("Bridge token file must be a private regular file (mode 0600)")
    if TOKEN_PATTERN.fullmatch(token) is None:
        raise BenchmarkError("Bridge token is not canonical")
    return token


def normalized_tool_args(value: Any) -> dict[str, Any]:
    """Accept the bridge's JSON-string wire form without trusting non-object JSON."""
    if isinstance(value, dict):
        return value
    if not isinstance(value, str):
        return {}
    try:
        parsed = json.loads(value)
    except (json.JSONDecodeError, TypeError):
        return {}
    return parsed if isinstance(parsed, dict) else {}


def normalized_tool_result_details(value: Any) -> dict[str, Any]:
    """Read bounded extension details from the bridge's JSON result preview."""
    if not isinstance(value, str):
        return {}
    try:
        parsed = json.loads(value)
    except (json.JSONDecodeError, TypeError):
        return {}
    if not isinstance(parsed, dict) or not isinstance(parsed.get("details"), dict):
        return {}
    return parsed["details"]


def parse_battery(raw: str) -> dict[str, Any]:
    values: dict[str, str] = {}
    for line in raw.splitlines():
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        values[key.strip().casefold().replace(" ", "_")] = value.strip()

    def integer(*keys: str) -> int | None:
        for key in keys:
            candidate = values.get(key)
            if candidate is not None and candidate.lstrip("-").isdigit():
                return int(candidate)
        return None

    def boolean(key: str) -> bool | None:
        candidate = values.get(key)
        if candidate == "true":
            return True
        if candidate == "false":
            return False
        return None

    temperature = integer("temperature")
    voltage_mv = integer("voltage")
    return {
        "batteryLevelPercent": integer("level"),
        "batteryTemperatureC": round(temperature / 10.0, 1) if temperature is not None else None,
        "batteryVoltageV": round(voltage_mv / 1000.0, 4) if voltage_mv is not None else None,
        "usbPowered": boolean("usb_powered"),
        "acPowered": boolean("ac_powered"),
        "wirelessPowered": boolean("wireless_powered"),
    }


def parse_thermal_status(raw: str) -> int | None:
    for pattern in (
        r"Thermal Status\s*:\s*(\d+)",
        r"mStatus\s*[=:]\s*(\d+)",
        r"status\s*[=:]\s*(\d+)",
    ):
        match = re.search(pattern, raw, re.IGNORECASE)
        if match is not None:
            return int(match.group(1))
    return None


def parse_thermal_sysfs(raw: str) -> dict[str, Any]:
    values: dict[str, int] = {}
    cpu_temperatures: list[int] = []
    all_temperatures: list[int] = []
    for line in raw.splitlines():
        line = line.strip()
        if not line:
            continue
        if line.startswith("zone=") and "|" in line:
            zone_type, temperature = line[len("zone=") :].split("|", 1)
            if temperature.strip().lstrip("-").isdigit():
                reading = int(temperature.strip())
                all_temperatures.append(reading)
                if "cpu" in zone_type.casefold() or "soc" in zone_type.casefold():
                    cpu_temperatures.append(reading)
            continue
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if value.lstrip("-").isdigit():
            values[key] = int(value)

    scaling = values.get("big_scaling")
    nominal = values.get("big_nominal")
    headroom = None
    if scaling is not None and nominal is not None and nominal > 0:
        headroom = round(max(0.0, min(1.0, scaling / nominal)), 3)
    hottest = max(cpu_temperatures or all_temperatures, default=None)
    current_ua = values.get("power_current_now")
    voltage_uv = values.get("power_voltage_now")
    return {
        "bigCoreScalingMaxHz": scaling,
        "bigCoreNominalMaxHz": nominal,
        "bigCoreHeadroom": headroom,
        "hottestCpuC": round(hottest / 1000.0, 1) if hottest is not None else None,
        "batteryCurrentA": round(current_ua / 1_000_000.0, 6) if current_ua is not None else None,
        "batteryVoltageSysfsV": round(voltage_uv / 1_000_000.0, 6) if voltage_uv is not None else None,
        "chargeCounterAh": (
            round(values["power_charge_counter"] / 1_000_000.0, 6)
            if "power_charge_counter" in values
            else None
        ),
        "energyCounterWh": (
            round(values["power_energy_now"] / 1_000_000.0, 6)
            if "power_energy_now" in values
            else None
        ),
    }


def _timing_matches(pattern: re.Pattern[str], text: str) -> list[tuple[float, int, float]]:
    matches = []
    for match in pattern.finditer(text):
        milliseconds = float(match.group("milliseconds"))
        tokens = int(match.group("tokens"))
        rate = float(match.group("rate"))
        if milliseconds > 0 and tokens > 0 and rate > 0:
            matches.append((milliseconds / 1000.0, tokens, rate))
    return matches


_PROMPT_TIMING = re.compile(
    r"prompt eval time\s*=\s*(?P<milliseconds>[0-9.]+)\s*ms\s*/\s*"
    r"(?P<tokens>\d+)\s+tokens.*?(?P<rate>[0-9.]+)\s+tokens per second",
    re.IGNORECASE,
)
_DECODE_TIMING = re.compile(
    r"(?<!prompt )eval time\s*=\s*(?P<milliseconds>[0-9.]+)\s*ms\s*/\s*"
    r"(?P<tokens>\d+)\s+(?:tokens|runs).*?(?P<rate>[0-9.]+)\s+tokens per second",
    re.IGNORECASE,
)


def parse_server_timings(text: str) -> dict[str, Any] | None:
    prompt = _timing_matches(_PROMPT_TIMING, text)
    decode = _timing_matches(_DECODE_TIMING, text)
    if not prompt and not decode:
        return None

    def aggregate(values: list[tuple[float, int, float]]) -> dict[str, Any] | None:
        if not values:
            return None
        seconds = sum(value[0] for value in values)
        tokens = sum(value[1] for value in values)
        return {
            "seconds": round(seconds, 6),
            "tokens": tokens,
            "tokensPerSecond": round(tokens / seconds, 3),
        }

    return {
        "source": "native_run_as_log",
        "providerRequests": max(len(prompt), len(decode)),
        "prefill": aggregate(prompt),
        "decode": aggregate(decode),
    }


def native_log_delta(before: str | None, after: str | None) -> str | None:
    """Return only bytes proven to have been appended during one turn.

    The bounded ``tail`` window eventually slides on a long-running server. In
    that case parsing the whole post-turn window would silently mix earlier
    requests into this sample, so exact native timings become unavailable.
    """
    if before is None or after is None or not after.startswith(before):
        return None
    return after[len(before) :]


def _event_epoch(event: dict[str, Any]) -> float | None:
    raw = event.get("timestamp")
    if not isinstance(raw, str):
        return None
    try:
        return dt.datetime.fromisoformat(raw.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def derive_turn_metrics(
    events: list[dict[str, Any]],
    receipts: dict[int, float],
    dispatch_started: float,
    terminal_received: float,
    context_tokens_before: int | None,
    context_tokens_after: int | None,
    native_timings: dict[str, Any] | None,
) -> dict[str, Any]:
    terminal = next(
        (event for event in reversed(events) if event.get("type") in TERMINAL_EVENTS),
        None,
    )
    if terminal is None:
        raise BenchmarkError("Captured turn has no terminal event")

    def receipt(event: dict[str, Any]) -> float | None:
        sequence = event.get("sequence")
        return receipts.get(sequence) if isinstance(sequence, int) else None

    first_delta = next(
        (event for event in events if event.get("type") == "MODEL_OUTPUT_DELTA"),
        None,
    )
    first_tool = next(
        (event for event in events if event.get("type") == "TOOL_CALL_REQUESTED"),
        None,
    )
    first_visible_at = receipt(first_delta) if first_delta is not None else None
    ttft_source = "model_output_delta_receipt" if first_visible_at is not None else "unavailable"
    if (
        first_visible_at is None
        and terminal.get("type") == "TURN_COMPLETED"
        and isinstance(payload := terminal.get("payload"), dict)
        and isinstance(payload.get("answer"), str)
        and payload["answer"]
    ):
        first_visible_at = terminal_received
        ttft_source = "terminal_answer_receipt"
    first_tool_at = receipt(first_tool) if first_tool is not None else None
    payload = terminal.get("payload") if isinstance(terminal.get("payload"), dict) else {}
    output_tokens = payload.get("outputTokens") if type(payload.get("outputTokens")) is int else None
    decode_ms = _finite_number(payload.get("decodeDurationMs"))
    decode_rate = _finite_number(payload.get("tokensPerSecond"))

    started_tools: dict[str, float] = {}
    tool_execution_seconds = 0.0
    tool_names: list[str] = []
    tool_failures = 0
    last_tool_completed: float | None = None
    for event in events:
        event_type = event.get("type")
        event_payload = event.get("payload") if isinstance(event.get("payload"), dict) else {}
        tool_id = event_payload.get("toolCallId")
        event_time = _event_epoch(event)
        if event_type == "TOOL_CALL_REQUESTED":
            name = event_payload.get("toolName")
            if isinstance(name, str) and name not in tool_names:
                tool_names.append(name)
        elif event_type == "TOOL_CALL_STARTED" and isinstance(tool_id, str) and event_time is not None:
            started_tools[tool_id] = event_time
        elif event_type == "TOOL_CALL_COMPLETED":
            if event_payload.get("isError") is True:
                tool_failures += 1
            if isinstance(tool_id, str) and event_time is not None:
                started = started_tools.get(tool_id)
                if started is not None and event_time >= started:
                    tool_execution_seconds += event_time - started
                last_tool_completed = event_time

    rejections = [event for event in events if event.get("type") == "MODEL_OUTPUT_REJECTED"]
    turn_starts = [event for event in events if event.get("type") == "TURN_STARTED"]
    retry_dispatch_delays: list[float] = []
    for rejected in rejections:
        rejected_at = _event_epoch(rejected)
        if rejected_at is None:
            continue
        later = next(
            (
                started_at
                for started in turn_starts
                if (started_at := _event_epoch(started)) is not None and started_at >= rejected_at
            ),
            None,
        )
        if later is not None:
            retry_dispatch_delays.append(later - rejected_at)

    prefill: dict[str, Any] = {
        "source": "unavailable",
        "tokens": None,
        "seconds": None,
        "tokensPerSecond": None,
    }
    if native_timings is not None and isinstance(native_timings.get("prefill"), dict):
        prefill = {"source": native_timings.get("source", "native_log"), **native_timings["prefill"]}
    elif (
        context_tokens_before is not None
        and context_tokens_after is not None
        and context_tokens_after >= context_tokens_before
        and output_tokens is not None
        and first_visible_at is not None
    ):
        estimated_tokens = max(0, context_tokens_after - context_tokens_before - output_tokens)
        estimated_seconds = max(0.0, first_visible_at - dispatch_started)
        if estimated_tokens > 0 and estimated_seconds > 0:
            prefill = {
                "source": "estimated_context_delta_over_visible_ttft",
                "tokens": estimated_tokens,
                "seconds": round(estimated_seconds, 6),
                "tokensPerSecond": round(estimated_tokens / estimated_seconds, 3),
            }

    terminal_epoch = _event_epoch(terminal)
    post_tool = None
    if last_tool_completed is not None and terminal_epoch is not None and terminal_epoch >= last_tool_completed:
        post_tool = round(terminal_epoch - last_tool_completed, 6)
    retry_tail = None
    if rejections:
        rejected_at = _event_epoch(rejections[0])
        if rejected_at is not None and terminal_epoch is not None and terminal_epoch >= rejected_at:
            retry_tail = round(terminal_epoch - rejected_at, 6)

    return {
        "terminalType": terminal["type"],
        "succeeded": terminal["type"] == "TURN_COMPLETED",
        "dispatchToFirstVisibleTokenSeconds": (
            round(first_visible_at - dispatch_started, 6)
            if first_visible_at is not None
            else None
        ),
        "ttftSource": ttft_source,
        "dispatchToFirstToolCallSeconds": (
            round(first_tool_at - dispatch_started, 6) if first_tool_at is not None else None
        ),
        "totalTurnSeconds": round(terminal_received - dispatch_started, 6),
        "provider": {
            "outputTokens": output_tokens,
            "decodeSeconds": round(decode_ms / 1000.0, 6) if decode_ms is not None else None,
            "decodeTokensPerSecond": decode_rate,
            "speedEstimated": payload.get("speedEstimated"),
            "prefill": prefill,
            "nativeTimings": native_timings,
        },
        "tools": {
            "requested": sum(event.get("type") == "TOOL_CALL_REQUESTED" for event in events),
            "completed": sum(event.get("type") == "TOOL_CALL_COMPLETED" for event in events),
            "failed": tool_failures,
            "names": tool_names,
            "executionSeconds": round(tool_execution_seconds, 6),
            "postToolToTerminalSeconds": post_tool,
        },
        "retries": {
            "rejections": len(rejections),
            "reasons": [
                event.get("payload", {}).get("reason")
                for event in rejections
                if isinstance(event.get("payload"), dict)
            ],
            "additionalAgentStarts": max(0, len(turn_starts) - 1),
            "dispatchDelaySeconds": [round(value, 6) for value in retry_dispatch_delays],
            "firstRejectionToTerminalSeconds": retry_tail,
        },
    }


def summarize_environment(samples: list[dict[str, Any]]) -> dict[str, Any]:
    if not samples:
        return {"samples": 0, "power": {"kind": "unavailable", "reason": "no samples"}}
    headrooms = [
        value for sample in samples if (value := _finite_number(sample.get("bigCoreHeadroom"))) is not None
    ]
    temperatures = [
        value for sample in samples if (value := _finite_number(sample.get("hottestCpuC"))) is not None
    ]
    throttling_events = 0
    previously_throttled = False
    for headroom in headrooms:
        throttled = headroom < 0.98
        if throttled and not previously_throttled:
            throttling_events += 1
        previously_throttled = throttled

    powers = []
    for sample in samples:
        current = _finite_number(sample.get("batteryCurrentA"))
        voltage = _finite_number(sample.get("batteryVoltageSysfsV"))
        if current is not None and voltage is not None:
            powers.append(abs(current * voltage))
    power: dict[str, Any]
    if powers:
        power = {
            "kind": "instantaneous_sysfs_average_watts",
            "value": round(statistics.fmean(powers), 4),
            "samples": len(powers),
        }
    else:
        power = {
            "kind": "unavailable",
            "reason": "battery current_now/voltage_now were not both readable",
        }
    start_level = _finite_number(samples[0].get("batteryLevelPercent"))
    end_level = _finite_number(samples[-1].get("batteryLevelPercent"))
    return {
        "samples": len(samples),
        "start": samples[0],
        "end": samples[-1],
        "minimumBigCoreHeadroom": round(min(headrooms), 3) if headrooms else None,
        "maximumCpuTemperatureC": round(max(temperatures), 1) if temperatures else None,
        "thermalThrottlingEvents": throttling_events,
        "batteryDeltaPercent": (
            round(start_level - end_level, 3)
            if start_level is not None and end_level is not None
            else None
        ),
        "power": power,
    }


def summarize_cases(cases: list[dict[str, Any]]) -> dict[str, Any]:
    def median(path: tuple[str, ...]) -> float | None:
        values = []
        for case in cases:
            value: Any = case
            for key in path:
                value = value.get(key) if isinstance(value, dict) else None
            number = _finite_number(value)
            if number is not None:
                values.append(number)
        return round(statistics.median(values), 6) if values else None

    return {
        "samples": len(cases),
        "successful": sum(case.get("turn", {}).get("succeeded") is True for case in cases),
        "medianStartupSeconds": median(("startupSeconds",)),
        "medianTtftSeconds": median(("turn", "dispatchToFirstVisibleTokenSeconds")),
        "medianTotalTurnSeconds": median(("turn", "totalTurnSeconds")),
        "medianDecodeTokensPerSecond": median(("turn", "provider", "decodeTokensPerSecond")),
        "medianPrefillTokensPerSecond": median(
            ("turn", "provider", "prefill", "tokensPerSecond")
        ),
        "totalToolExecutionSeconds": round(
            sum(
                _finite_number(case.get("turn", {}).get("tools", {}).get("executionSeconds")) or 0.0
                for case in cases
            ),
            6,
        ),
        "totalRetryRejections": sum(
            int(case.get("turn", {}).get("retries", {}).get("rejections", 0)) for case in cases
        ),
    }


class AdbClient:
    def __init__(self, serial: str):
        self.serial = serial

    def run(
        self, *arguments: str, check: bool = True, timeout: float = 120
    ) -> str:
        result = subprocess.run(
            ["adb", "-s", self.serial, *arguments],
            capture_output=True,
            text=True,
            check=False,
            timeout=timeout,
        )
        if check and result.returncode != 0:
            message = result.stderr.strip() or f"exit {result.returncode}"
            raise BenchmarkError(f"adb {' '.join(arguments[:3])} failed: {message[:512]}")
        return result.stdout[: 2 * MAX_HTTP_BYTES]

    def forward(self, host_port: int, device_port: int) -> None:
        self.run("forward", f"tcp:{host_port}", f"tcp:{device_port}")

    def remove_forward(self, host_port: int) -> None:
        self.run("forward", "--remove", f"tcp:{host_port}", check=False)

    def force_stop_and_start(self, package: str, component: str) -> None:
        self.run("shell", "am", "force-stop", package)
        self.run("shell", "am", "start", "-W", "-n", component, timeout=60)

    def bring_to_front(self, component: str) -> None:
        self.run("shell", "am", "start", "-W", "-n", component, timeout=60)

    def environment_snapshot(self) -> dict[str, Any]:
        battery = parse_battery(self.run("shell", "dumpsys", "battery", check=False))
        # ``adb shell`` already invokes the device shell. Passing the complete
        # fixed script as its sole command argument preserves semicolons and
        # loops; an unquoted ``sh -c`` argv would execute only its first word.
        thermal = parse_thermal_sysfs(self.run("shell", THERMAL_SCRIPT, check=False))
        thermal_status = parse_thermal_status(
            self.run("shell", "dumpsys", "thermalservice", check=False)
        )
        return {
            "monotonicSeconds": round(time.monotonic(), 6),
            **battery,
            **thermal,
            "androidThermalStatus": thermal_status,
        }

    def native_log_tail(self, package: str) -> str | None:
        result = subprocess.run(
            [
                "adb",
                "-s",
                self.serial,
                "exec-out",
                "run-as",
                package,
                "tail",
                "-c",
                "524288",
                "files/logs/native-llama-server.log",
            ],
            capture_output=True,
            check=False,
            timeout=15,
        )
        if result.returncode != 0:
            return None
        return result.stdout.decode("utf-8", "replace")


class BridgeClient:
    def __init__(self, port: int, token: str):
        self.base = f"http://127.0.0.1:{port}"
        self.token = token

    def _request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        timeout: float = 10,
    ) -> dict[str, Any]:
        encoded = (
            json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            if body is not None
            else None
        )
        request = urllib.request.Request(
            self.base + path,
            data=encoded,
            method=method,
            headers={
                "X-PiDeck-Token": self.token,
                "Accept": "application/json",
                "Content-Type": "application/json; charset=utf-8",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                raw = response.read(MAX_HTTP_BYTES + 1)
        except (urllib.error.URLError, OSError) as error:
            raise BenchmarkError(f"Bridge request failed: {method} {path}") from error
        if len(raw) > MAX_HTTP_BYTES:
            raise BenchmarkError("Bridge response exceeds the bounded size")
        try:
            value = json.loads(raw.decode("utf-8"))
        except (UnicodeError, json.JSONDecodeError) as error:
            raise BenchmarkError("Bridge response is not JSON") from error
        if not isinstance(value, dict) or value.get("ok") is not True:
            raise BenchmarkError(f"Bridge rejected {method} {path}")
        return value

    def state(self) -> dict[str, Any]:
        value = self._request("GET", "/v1/state", timeout=5)
        state = value.get("state")
        if not isinstance(state, dict):
            raise BenchmarkError("Bridge state is missing")
        return state

    def command(self, operation_id: str, command_type: str, payload: dict[str, Any]) -> None:
        self._request(
            "POST",
            "/v1/commands",
            {
                "schemaVersion": 1,
                "operationId": operation_id,
                "type": command_type,
                "payload": payload,
            },
            timeout=10,
        )

    def events(self, after: int, timeout_ms: int = 1000) -> dict[str, Any]:
        query = urllib.parse.urlencode({"after": after, "timeoutMs": timeout_ms})
        return self._request("GET", f"/v1/events?{query}", timeout=timeout_ms / 1000 + 5)

    def prepare_benchmark(self, run_id: str) -> dict[str, Any]:
        value = self._request(
            "POST", "/v1/benchmark/prepare", {"runId": run_id}, timeout=15
        )
        snapshot = value.get("snapshot")
        if not isinstance(snapshot, dict):
            raise BenchmarkError("Bridge benchmark fixture response is missing")
        return snapshot

    def benchmark_snapshot(self, run_id: str) -> dict[str, Any]:
        query = urllib.parse.urlencode({"runId": run_id})
        value = self._request(
            "GET", f"/v1/benchmark/snapshot?{query}", timeout=15
        )
        snapshot = value.get("snapshot")
        if not isinstance(snapshot, dict):
            raise BenchmarkError("Bridge benchmark snapshot is missing")
        return snapshot


class EnvironmentSampler:
    def __init__(self, snapshot: Callable[[], dict[str, Any]], interval: float):
        self.snapshot = snapshot
        self.interval = interval
        self.samples: list[dict[str, Any]] = []
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        def collect() -> None:
            while not self._stop.is_set() and len(self.samples) < 4096:
                try:
                    self.samples.append(self.snapshot())
                except Exception:
                    pass
                self._stop.wait(self.interval)

        self._thread = threading.Thread(target=collect, name="pideck-benchmark-env", daemon=True)
        self._thread.start()

    def finish(self) -> list[dict[str, Any]]:
        if self._stop.is_set():
            return self.samples
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=max(2.0, self.interval + 1.0))
        try:
            self.samples.append(self.snapshot())
        except Exception:
            pass
        return self.samples


class AgentBenchmark:
    def __init__(
        self,
        adb: AdbClient,
        bridge: BridgeClient,
        package: str,
        component: str,
        timeout_seconds: float,
        sample_interval: float,
        model_id: str | None,
        cooldown_headroom: float,
        cooldown_timeout: float,
    ):
        self.adb = adb
        self.bridge = bridge
        self.package = package
        self.component = component
        self.timeout_seconds = timeout_seconds
        self.sample_interval = sample_interval
        self.model_id = model_id
        self.cooldown_headroom = cooldown_headroom
        self.cooldown_timeout = cooldown_timeout

    def wait_cooldown(self) -> dict[str, Any]:
        started = time.monotonic()
        latest: dict[str, Any] = {}
        while True:
            latest = self.adb.environment_snapshot()
            headroom = _finite_number(latest.get("bigCoreHeadroom"))
            if headroom is None or headroom >= self.cooldown_headroom:
                return {
                    "waitedSeconds": round(time.monotonic() - started, 3),
                    "targetHeadroom": self.cooldown_headroom,
                    "met": True if headroom is not None else None,
                    "snapshot": latest,
                }
            elapsed = time.monotonic() - started
            if elapsed >= self.cooldown_timeout:
                return {
                    "waitedSeconds": round(elapsed, 3),
                    "targetHeadroom": self.cooldown_headroom,
                    "met": False,
                    "snapshot": latest,
                }
            time.sleep(min(10.0, max(0.1, self.cooldown_timeout - elapsed)))

    def wait_ready(self, deadline_seconds: float = 180) -> dict[str, Any]:
        deadline = time.monotonic() + deadline_seconds
        last_error = "bridge unavailable"
        while time.monotonic() < deadline:
            try:
                state = self.bridge.state()
                server = state.get("server") if isinstance(state.get("server"), dict) else {}
                if (
                    state.get("piAlive") is True
                    and server.get("state") == "READY"
                    and state.get("activeOperationId") is None
                    and (self.model_id is None or state.get("modelId") == self.model_id)
                ):
                    return state
                last_error = (
                    f"piAlive={state.get('piAlive')} server={server.get('state')} "
                    f"active={state.get('activeOperationId')} model={state.get('modelId')}"
                )
            except BenchmarkError as error:
                last_error = str(error)
            time.sleep(0.5)
        raise BenchmarkError(f"PI//DECK did not become benchmark-ready: {last_error}")

    @staticmethod
    def _context_tokens(state: dict[str, Any]) -> int | None:
        stats = state.get("sessionStats")
        usage = stats.get("contextUsage") if isinstance(stats, dict) else None
        tokens = usage.get("tokens") if isinstance(usage, dict) else None
        return tokens if type(tokens) is int and tokens >= 0 else None

    def new_session(self, state: dict[str, Any]) -> tuple[dict[str, Any], float]:
        cursor = int(state.get("lastSequence", 0))
        bridge_instance_id = state.get("bridgeInstanceId")
        operation_id = str(uuid.uuid4())
        logical_session = str(uuid.uuid4())
        started = time.monotonic()
        self.bridge.command(operation_id, "NEW_SESSION", {"sessionId": logical_session})
        deadline = started + 60
        while time.monotonic() < deadline:
            response = self.bridge.events(cursor)
            if response.get("bridgeInstanceId") != bridge_instance_id:
                raise BenchmarkError("Bridge restarted while creating benchmark session")
            if response.get("eventGap") is True:
                raise BenchmarkError("Event gap while creating benchmark session")
            events = response.get("events")
            if not isinstance(events, list):
                raise BenchmarkError("Bridge events are missing")
            for event in events:
                if not isinstance(event, dict):
                    continue
                sequence = event.get("sequence")
                if isinstance(sequence, int):
                    cursor = max(cursor, sequence)
                if event.get("operationId") != operation_id:
                    continue
                if event.get("type") == "SESSION_CREATED":
                    return self.bridge.state(), time.monotonic() - started
                if event.get("type") == "TURN_FAILED":
                    raise BenchmarkError("Bridge failed to create a fresh benchmark session")
        raise BenchmarkError("Timed out creating a fresh benchmark session")

    def _wait_context_stats(self, before: int | None) -> dict[str, Any]:
        latest = self.bridge.state()
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            tokens = self._context_tokens(latest)
            if tokens is not None and (before is None or tokens != before):
                return latest
            time.sleep(0.25)
            latest = self.bridge.state()
        return latest

    def run_turn(
        self,
        label: str,
        prompt: str,
        ready_state: dict[str, Any],
        sampler: EnvironmentSampler | None = None,
    ) -> dict[str, Any]:
        state, reset_seconds = self.new_session(ready_state)
        session_id = state.get("sessionId")
        if not isinstance(session_id, str):
            raise BenchmarkError("Bridge sessionId is unavailable")
        cursor = int(state.get("lastSequence", 0))
        context_before = self._context_tokens(state)
        before_log = self.adb.native_log_tail(self.package)
        if sampler is None:
            sampler = EnvironmentSampler(self.adb.environment_snapshot, self.sample_interval)
            sampler.start()

        operation_id = str(uuid.uuid4())
        dispatch_started = time.monotonic()
        events: list[dict[str, Any]] = []
        receipts: dict[int, float] = {}
        terminal_received: float | None = None
        bridge_instance_id = state.get("bridgeInstanceId")
        try:
            self.bridge.command(
                operation_id,
                "PROMPT",
                {"message": prompt, "sessionId": session_id},
            )
            deadline = dispatch_started + self.timeout_seconds
            while time.monotonic() < deadline and terminal_received is None:
                response = self.bridge.events(cursor)
                if response.get("bridgeInstanceId") != bridge_instance_id:
                    raise BenchmarkError("Bridge restarted during the benchmark turn")
                if response.get("eventGap") is True:
                    raise BenchmarkError("Event gap invalidated the benchmark turn")
                batch = response.get("events")
                if not isinstance(batch, list):
                    raise BenchmarkError("Bridge events are missing")
                received = time.monotonic()
                for event in batch:
                    if not isinstance(event, dict):
                        continue
                    sequence = event.get("sequence")
                    if isinstance(sequence, int):
                        cursor = max(cursor, sequence)
                    if event.get("operationId") != operation_id:
                        continue
                    events.append(event)
                    if isinstance(sequence, int):
                        receipts[sequence] = received
                    if event.get("type") in TERMINAL_EVENTS:
                        terminal_received = received
                        break
            if terminal_received is None:
                raise BenchmarkError(f"Agent turn timed out after {self.timeout_seconds:g}s")
        finally:
            environment_samples = sampler.finish()
        after_log = self.adb.native_log_tail(self.package)
        log_delta = native_log_delta(before_log, after_log)
        native_timings = parse_server_timings(log_delta or "")
        after_state = self._wait_context_stats(context_before)
        turn = derive_turn_metrics(
            events,
            receipts,
            dispatch_started,
            terminal_received,
            context_before,
            self._context_tokens(after_state),
            native_timings,
        )
        terminal = next(
            (event for event in reversed(events) if event.get("type") in TERMINAL_EVENTS),
            {},
        )
        terminal_payload = (
            terminal.get("payload") if isinstance(terminal.get("payload"), dict) else {}
        )
        requested_tools: list[dict[str, Any]] = []
        completed_tools: dict[str, dict[str, Any]] = {}
        for event in events:
            payload = event.get("payload") if isinstance(event.get("payload"), dict) else {}
            call_id = payload.get("toolCallId")
            if event.get("type") == "TOOL_CALL_REQUESTED":
                requested_tools.append(
                    {
                        "id": call_id,
                        "name": payload.get("toolName"),
                        "args": normalized_tool_args(payload.get("args")),
                    }
                )
            elif event.get("type") == "TOOL_CALL_COMPLETED" and isinstance(call_id, str):
                result_preview = payload.get("resultPreview")
                result_details = normalized_tool_result_details(result_preview)
                completed_tools[call_id] = {
                    "isError": payload.get("isError") is True,
                    "resultPreview": result_preview,
                    "details": result_details,
                }
        for tool in requested_tools:
            completion = completed_tools.get(tool.get("id"))
            if completion is not None:
                tool["completion"] = completion
                effective = dict(tool.get("args", {}))
                details = completion.get("details", {})
                for key in ("path", "expr"):
                    if isinstance(details.get(key), str):
                        effective[key] = details[key]
                tool["effectiveArgs"] = effective
        return {
            "label": label,
            "promptSha256": hashlib.sha256(prompt.encode("utf-8")).hexdigest(),
            "promptBytes": len(prompt.encode("utf-8")),
            "sessionResetSeconds": round(reset_seconds, 6),
            "turn": turn,
            "transcript": {
                "answer": terminal_payload.get("answer", ""),
                "tools": requested_tools,
            },
            "environment": summarize_environment(environment_samples),
            "environmentSamples": environment_samples,
        }

    def run_cold(self, index: int, prompt: str) -> dict[str, Any]:
        self.adb.bring_to_front(self.component)
        cooldown = self.wait_cooldown()
        sampler = EnvironmentSampler(self.adb.environment_snapshot, self.sample_interval)
        sampler.start()
        started = time.monotonic()
        try:
            self.adb.force_stop_and_start(self.package, self.component)
            ready = self.wait_ready()
            startup_seconds = time.monotonic() - started
            result = self.run_turn(f"cold-{index + 1}", prompt, ready, sampler)
        except Exception:
            sampler.finish()
            raise
        result["cooldown"] = cooldown
        result["startupSeconds"] = round(startup_seconds, 6)
        result["coldToFirstVisibleTokenSeconds"] = round(
            startup_seconds
            + result["sessionResetSeconds"]
            + result["turn"]["dispatchToFirstVisibleTokenSeconds"],
            6,
        )
        result["coldEndToEndSeconds"] = round(
            startup_seconds + result["sessionResetSeconds"] + result["turn"]["totalTurnSeconds"],
            6,
        )
        return result

    def run_warm(self, label: str, prompt: str) -> dict[str, Any]:
        self.adb.bring_to_front(self.component)
        cooldown = self.wait_cooldown()
        ready = self.wait_ready()
        result = self.run_turn(label, prompt, ready)
        result["cooldown"] = cooldown
        result["startupSeconds"] = None
        return result


def _adb_devices() -> str:
    try:
        result = subprocess.run(
            ["adb", "devices"], capture_output=True, text=True, check=False, timeout=15
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise BenchmarkError("adb is unavailable") from error
    if result.returncode != 0:
        raise BenchmarkError("adb devices failed")
    return result.stdout


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial")
    parser.add_argument("--token-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--component", default=DEFAULT_COMPONENT)
    parser.add_argument("--bridge-port", type=int, default=8787)
    parser.add_argument("--host-port", type=int, default=18787)
    parser.add_argument("--model-id")
    parser.add_argument("--cold-runs", type=int, default=1)
    parser.add_argument("--warm-runs", type=int, default=3)
    parser.add_argument("--tool-runs", type=int, default=1)
    parser.add_argument("--timeout-seconds", type=float, default=2700)
    parser.add_argument("--sample-interval", type=float, default=2.0)
    parser.add_argument("--cooldown-headroom", type=float, default=0.98)
    parser.add_argument("--cooldown-timeout", type=float, default=600)
    parser.add_argument(
        "--prompt",
        default="Ответь ровно строкой PIDECK_OK и больше ничего не добавляй.",
    )
    parser.add_argument(
        "--tool-prompt",
        default=(
            "Используй инструмент read, прочитай первую строку README.md и затем "
            "кратко назови проект."
        ),
    )
    parser.add_argument(
        "--retry-prompt",
        help=(
            "Optional known retry reproducer. The report records actual MODEL_OUTPUT_REJECTED "
            "events; it never fabricates retry overhead when the model does not retry."
        ),
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    for name in ("cold_runs", "warm_runs", "tool_runs"):
        value = getattr(args, name)
        if not 0 <= value <= 20:
            raise BenchmarkError(f"--{name.replace('_', '-')} must be between 0 and 20")
    if args.cold_runs + args.warm_runs + args.tool_runs == 0 and not args.retry_prompt:
        raise BenchmarkError("At least one benchmark case is required")
    if not 1024 <= args.bridge_port <= 65535 or not 1024 <= args.host_port <= 65535:
        raise BenchmarkError("Bridge ports must be between 1024 and 65535")
    if not 30 <= args.timeout_seconds <= 3600:
        raise BenchmarkError("--timeout-seconds must be between 30 and 3600")
    if not 0.5 <= args.sample_interval <= 60:
        raise BenchmarkError("--sample-interval must be between 0.5 and 60")
    if not 0 < args.cooldown_headroom <= 1:
        raise BenchmarkError("--cooldown-headroom must be in (0, 1]")
    if not 0 <= args.cooldown_timeout <= 3600:
        raise BenchmarkError("--cooldown-timeout must be between 0 and 3600")

    token = read_bridge_token(args.token_file)
    serial = resolve_adb_serial(_adb_devices(), args.serial)
    adb = AdbClient(serial)
    bridge = BridgeClient(args.host_port, token)
    runner = AgentBenchmark(
        adb,
        bridge,
        args.package,
        args.component,
        args.timeout_seconds,
        args.sample_interval,
        args.model_id,
        args.cooldown_headroom,
        args.cooldown_timeout,
    )
    report: dict[str, Any] = {
        "schemaVersion": 1,
        "reportType": "pideck-agent-speed",
        "createdAt": dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z"),
        "deviceId": hashlib.sha256(serial.encode("utf-8")).hexdigest()[:16],
        "package": args.package,
        "component": args.component,
        "bridgePort": args.bridge_port,
        "hostForwardPort": args.host_port,
        "samplingIntervalSeconds": args.sample_interval,
        "cases": {"cold": [], "warm": [], "tool": [], "retry": []},
    }
    adb.forward(args.host_port, args.bridge_port)
    try:
        initial = runner.wait_ready()
        if runner.model_id is None and isinstance(initial.get("modelId"), str):
            # Fence every post-restart sample to the initially selected model.
            runner.model_id = initial["modelId"]
        report["modelId"] = initial.get("modelId")
        report["agentMode"] = initial.get("agentMode")
        report["accessProfile"] = initial.get("accessProfile")
        if args.tool_runs and initial.get("agentMode") != "agent":
            raise BenchmarkError("Tool benchmark requires bridge agentMode=agent")
        report["device"] = {
            "model": adb.run("shell", "getprop", "ro.product.model").strip()[:128],
            "build": adb.run("shell", "getprop", "ro.build.display.id").strip()[:256],
        }
        for index in range(args.cold_runs):
            report["cases"]["cold"].append(runner.run_cold(index, args.prompt))
        for index in range(args.warm_runs):
            report["cases"]["warm"].append(
                runner.run_warm(f"warm-{index + 1}", args.prompt)
            )
        for index in range(args.tool_runs):
            report["cases"]["tool"].append(
                runner.run_warm(f"tool-{index + 1}", args.tool_prompt)
            )
        if args.retry_prompt:
            report["cases"]["retry"].append(runner.run_warm("retry-1", args.retry_prompt))
        report["summary"] = {
            name: summarize_cases(cases) for name, cases in report["cases"].items()
        }
        report["retryCoverageObserved"] = any(
            case.get("turn", {}).get("retries", {}).get("rejections", 0) > 0
            for cases in report["cases"].values()
            for case in cases
        )
    finally:
        adb.remove_forward(args.host_port)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_name(f".{args.output.name}.tmp-{os.getpid()}")
    temporary.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    os.replace(temporary, args.output)
    print(f"Wrote {args.output}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except BenchmarkError as error:
        raise SystemExit(f"benchmark failed: {error}") from error
