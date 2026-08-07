#!/usr/bin/env python3
"""Compare llama.cpp speculative decoding variants on the attached Android device.

The deck's own server is not used here. This drives the same pinned
``libpideck_llama_server.so`` from ``adb shell`` so one model file can be measured
under several ``--spec-type`` settings without reinstalling anything, which is the
harness described in docs/model-throughput-survey.md.

Every figure it reports is a warm figure: the first call after a load faults mmapped
weights in from flash and is discarded. Runs are foreground-equivalent only in the
sense that nothing else is scheduled against them; see the survey for why a
backgrounded deck is a different machine.
"""

from __future__ import annotations

import argparse
import json
import re
import secrets
import statistics
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

VARIANT = re.compile(r"^(?P<mode>[a-z-]+)(?::(?P<budget>\d{1,2}))?$")
SPECULATIVE_FLAG = {
    "draft-mtp": "--spec-draft-n-max",
    "ngram-mod": "--spec-ngram-mod-n-max",
    "ngram-simple": "--spec-draft-n-max",
}
DEVICE_ROOT = "/data/local/tmp/pideck-speculative"
MAX_BUDGET = 64


class ProbeError(RuntimeError):
    pass


def variant_arguments(variant: str) -> list[str]:
    """Turn a ``mode:budget`` label into the exact server flags it stands for."""
    match = VARIANT.fullmatch(variant)
    if match is None:
        raise ValueError(f"Unreadable speculative variant: {variant}")
    mode = match.group("mode")
    if mode == "baseline":
        if match.group("budget") is not None:
            raise ValueError("The baseline variant takes no draft budget")
        return []
    if mode not in SPECULATIVE_FLAG:
        raise ValueError(f"Unsupported speculative mode: {mode}")
    if match.group("budget") is None:
        raise ValueError(f"Speculative variant {variant} needs a draft budget")
    budget = int(match.group("budget"))
    if not 1 <= budget <= MAX_BUDGET:
        raise ValueError(f"Draft budget out of range: {budget}")
    return ["--spec-type", mode, SPECULATIVE_FLAG[mode], str(budget)]


def prompt_for_sample(prompts: list[str], index: int) -> str:
    """Cycle through the supplied prompts so no two consecutive samples repeat.

    ``ngram-mod`` keeps an n-gram pool that survives across requests. Sending one prompt
    repeatedly therefore lets each sample draft from the previous sample's own answer,
    which inflates its measured speedup and does not happen in real use.
    """
    if not prompts:
        raise ValueError("At least one prompt is required")
    return prompts[index % len(prompts)]


def summarise(samples: list[float]) -> dict[str, Any]:
    """Discard the flash-warm-up sample and summarise what is left."""
    if len(samples) < 2:
        raise ValueError("A variant needs a warm-up sample plus at least one measurement")
    warm = samples[1:]
    return {
        "samples": len(warm),
        "medianTokensPerSecond": round(statistics.median(warm), 2),
        "minTokensPerSecond": round(min(warm), 2),
        "maxTokensPerSecond": round(max(warm), 2),
        "discardedWarmUp": round(samples[0], 2),
    }


BIG_CORE = "/sys/devices/system/cpu/cpu7/cpufreq"
COOLDOWN_HEADROOM = 0.98
COOLDOWN_DEADLINE_SECONDS = 600


def thermal_headroom(scaling_max: int, nominal_max: int) -> float:
    """Fraction of the nominal clock the thermal governor is still allowing."""
    if nominal_max <= 0:
        raise ValueError("Nominal maximum frequency must be positive")
    return max(0.0, min(1.0, scaling_max / nominal_max))


def adb(*arguments: str, check: bool = True, timeout: int = 120) -> str:
    result = subprocess.run(
        ["adb", *arguments],
        capture_output=True,
        text=True,
        timeout=timeout,
        check=False,
    )
    if check and result.returncode != 0:
        raise ProbeError(f"adb {' '.join(arguments)} failed: {result.stderr.strip()}")
    return result.stdout


def device_library_directory() -> str:
    package_path = adb("shell", "pm", "path", "dev.pideck.app").strip()
    first = package_path.splitlines()[0].strip()
    if not first.startswith("package:"):
        raise ProbeError("PI//DECK is not installed on the attached device")
    return f"{first[len('package:'):].rsplit('/', 1)[0]}/lib/arm64"


def start_server(
    library_directory: str,
    model_path: str,
    context: int,
    threads: int,
    decode_cpus: str,
    batch_cpus: str,
    extra: list[str],
    port: int,
    api_key_path: str,
) -> "subprocess.Popen[bytes]":
    arguments = [
        f"{library_directory}/libpideck_llama_server.so",
        "-m", model_path,
        "--host", "127.0.0.1",
        "--port", str(port),
        "-c", str(context),
        "-np", "1",
        "-t", str(threads),
        "-tb", "8",
        "-Cr", decode_cpus,
        "--cpu-strict", "1",
        "-Crb", batch_cpus,
        "--cpu-strict-batch", "1",
        "--jinja",
        "--no-webui",
        "--api-key-file", api_key_path,
        *extra,
    ]
    # Detaching on the device does not work: adb keeps the shell session open until the
    # remote process group releases the descriptors it inherited, so `&`, nohup and setsid
    # all still block. Holding the `adb shell` as a local child instead is simpler and makes
    # the server's lifetime exactly this script's lifetime.
    command = (
        f"cd {DEVICE_ROOT} && LD_LIBRARY_PATH={library_directory} "
        f"exec {' '.join(arguments)} > {DEVICE_ROOT}/server.log 2>&1"
    )
    return subprocess.Popen(
        ["adb", "shell", command],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def stop_server(handle: "subprocess.Popen[bytes] | None" = None) -> None:
    adb("shell", "pkill -f libpideck_llama_server.so", check=False)
    if handle is not None:
        try:
            handle.terminate()
            handle.wait(timeout=10)
        except (subprocess.TimeoutExpired, OSError):
            handle.kill()
    time.sleep(2)


def wait_for_health(port: int, api_key: str, deadline_seconds: int = 180) -> None:
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}/health",
        headers={"Authorization": f"Bearer {api_key}"},
    )
    started = time.monotonic()
    while time.monotonic() - started < deadline_seconds:
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                if response.status == 200:
                    return
        except (urllib.error.URLError, OSError):
            time.sleep(2)
    raise ProbeError("Server did not become healthy inside the deadline")


def measure(
    port: int,
    api_key: str,
    prompt: str,
    max_tokens: int,
    request_speculative: dict[str, Any] | None = None,
) -> "tuple[float, float | None]":
    payload: dict[str, Any] = {
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": max_tokens,
        "temperature": 0,
        "seed": 42,
        "stream": False,
        "cache_prompt": False,
    }
    if request_speculative:
        payload.update(request_speculative)
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}/v1/chat/completions",
        data=json.dumps(payload, separators=(",", ":")).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=600) as response:
        body = json.loads(response.read(1024 * 1024))
    timings = body.get("timings") or {}
    rate = timings.get("predicted_per_second")
    if not isinstance(rate, (int, float)):
        raise ProbeError("Server response carried no decode timing")
    prompt_rate = timings.get("prompt_per_second")
    return float(rate), (
        float(prompt_rate) if isinstance(prompt_rate, (int, float)) else None
    )


def big_core_clocks() -> tuple[int, int]:
    raw = adb(
        "shell",
        f"cat {BIG_CORE}/scaling_max_freq {BIG_CORE}/cpuinfo_max_freq",
        check=False,
    ).split()
    if len(raw) < 2 or not all(value.isdigit() for value in raw[:2]):
        return (0, 0)
    return (int(raw[0]), int(raw[1]))


def hottest_cpu_millicelsius() -> int | None:
    listing = adb(
        "shell",
        "for z in /sys/class/thermal/thermal_zone*/; do "
        "printf '%s %s\\n' \"$(cat $z/type 2>/dev/null)\" \"$(cat $z/temp 2>/dev/null)\"; done",
        check=False,
    )
    readings = []
    for line in listing.splitlines():
        parts = line.split()
        if len(parts) == 2 and "cpu" in parts[0].lower() and parts[1].lstrip("-").isdigit():
            readings.append(int(parts[1]))
    return max(readings) if readings else None


def thermal_state() -> dict[str, Any]:
    scaling, nominal = big_core_clocks()
    return {
        "bigCoreScalingMaxHz": scaling,
        "bigCoreNominalMaxHz": nominal,
        "headroom": round(thermal_headroom(scaling, nominal), 3) if nominal else None,
        "hottestCpuMilliCelsius": hottest_cpu_millicelsius(),
    }


def wait_for_thermal_headroom() -> dict[str, Any]:
    """Block until the governor gives the big cores their full clock back.

    Variants are measured in sequence, so without this the one that runs last is
    measured on a hotter phone than the one that ran first, and the ordering alone
    can decide the winner. See docs/speculative-decoding-measurements.md.
    """
    started = time.monotonic()
    state = thermal_state()
    while time.monotonic() - started < COOLDOWN_DEADLINE_SECONDS:
        state = thermal_state()
        headroom = state["headroom"]
        if headroom is None or headroom >= COOLDOWN_HEADROOM:
            return state
        time.sleep(10)
    return state


def peak_rss_kib() -> int | None:
    listing = adb("shell", "ps -A -o RSS,ARGS", check=False)
    for line in listing.splitlines():
        if "libpideck_llama_server.so" in line and "pkill" not in line:
            head = line.strip().split(None, 1)[0]
            if head.isdigit():
                return int(head)
    return None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True, help="Host path to the GGUF to push")
    parser.add_argument(
        "--variant",
        action="append",
        required=True,
        help="Repeatable, for example baseline or draft-mtp:4 or ngram-mod:16",
    )
    parser.add_argument("--context", type=int, default=10240)
    parser.add_argument("--threads", type=int, default=5)
    parser.add_argument("--decode-cpus", default="3-7")
    parser.add_argument("--batch-cpus", default="0-7")
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--runs", type=int, default=4, help="Includes the discarded warm-up")
    parser.add_argument("--max-tokens", type=int, default=128)
    parser.add_argument(
        "--prompt",
        default=(
            "Continue with exactly 100 increasing integers separated by spaces. "
            "Output numbers only: 1 2 3 4 5"
        ),
    )
    parser.add_argument(
        "--prompt-file",
        type=Path,
        action="append",
        help="Repeatable. Samples cycle through the prompts so none is measured twice.",
    )
    parser.add_argument("--label", default="prose")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--server-arg",
        action="append",
        default=[],
        dest="server_args",
        help=(
            "Extra llama-server flag applied to every variant, repeatable. "
            "Lets one probe A/B non-speculative flags such as --flash-attn "
            "or --cache-type-k against the same baseline harness."
        ),
    )
    parser.add_argument("--keep-model", action="store_true")
    parser.add_argument(
        "--request-overrides",
        dest="request_speculative",
        help=(
            "JSON object merged into every request body. Use it to test per-request "
            'speculative fields such as {"speculative.n_max": 0}, or to measure under the '
            "deck's real sampling instead of the deterministic default."
        ),
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not 2 <= args.runs <= 12:
        raise ProbeError("--runs must be between 2 and 12")
    variants = {variant: variant_arguments(variant) for variant in args.variant}
    request_speculative = (
        json.loads(args.request_speculative) if args.request_speculative else None
    )
    if request_speculative is not None and not isinstance(request_speculative, dict):
        raise ProbeError("--request-speculative must be a JSON object")
    prompts = (
        [path.read_text(encoding="utf-8") for path in args.prompt_file]
        if args.prompt_file
        else [args.prompt]
    )

    library_directory = device_library_directory()
    model_name = Path(args.model).name
    device_model = f"{DEVICE_ROOT}/{model_name}"
    api_key = secrets.token_hex(24)
    api_key_path = f"{DEVICE_ROOT}/api-key"

    adb("shell", f"mkdir -p {DEVICE_ROOT}")
    present = adb("shell", f"ls {device_model} 2>/dev/null", check=False).strip()
    if not present:
        print(f"Pushing {model_name} to the device, this takes a while")
        adb("push", str(args.model), device_model, timeout=1800)
    adb("shell", f"printf %s {api_key} > {api_key_path} && chmod 600 {api_key_path}")
    adb("forward", f"tcp:{args.port}", f"tcp:{args.port}")

    handle: "subprocess.Popen[bytes] | None" = None
    report: dict[str, Any] = {
        "schemaVersion": 1,
        "model": model_name,
        "context": args.context,
        "promptLabel": args.label,
        "distinctPrompts": len(prompts),
        "requestSpeculative": request_speculative,
        "maxTokens": args.max_tokens,
        "device": adb("shell", "getprop ro.product.model").strip(),
        "variants": {},
    }
    try:
        for variant, extra in variants.items():
            stop_server(handle)
            handle = None
            print(f"Cooling down before {variant}", flush=True)
            before = wait_for_thermal_headroom()
            print(
                f"Measuring {variant} (headroom {before['headroom']}, "
                f"{before['hottestCpuMilliCelsius']} m°C)",
                flush=True,
            )
            flags = extra + args.server_args
            handle = start_server(
                library_directory,
                device_model,
                args.context,
                args.threads,
                args.decode_cpus,
                args.batch_cpus,
                flags,
                args.port,
                api_key_path,
            )
            try:
                wait_for_health(args.port, api_key)
                samples = []
                prompt_rates = []
                thermal = []
                for index in range(args.runs):
                    decode_rate, prompt_rate = measure(
                        args.port,
                        api_key,
                        prompt_for_sample(prompts, index),
                        args.max_tokens,
                        request_speculative,
                    )
                    samples.append(decode_rate)
                    prompt_rates.append(prompt_rate)
                    thermal.append(thermal_state())
                summary = summarise(samples)
                warm_prompt_rates = [rate for rate in prompt_rates[1:] if rate]
                summary["medianPromptTokensPerSecond"] = (
                    round(statistics.median(warm_prompt_rates), 1)
                    if warm_prompt_rates
                    else None
                )
                summary["serverFlags"] = flags
                summary["residentKiB"] = peak_rss_kib()
                summary["thermalBefore"] = before
                headrooms = [state["headroom"] for state in thermal[1:] if state["headroom"]]
                summary["headroomDuringSamples"] = (
                    [min(headrooms), max(headrooms)] if headrooms else None
                )
                # Per-sample pairs, warm-up included and marked, so a later reader can
                # compare variants at matched thermal state instead of trusting a median
                # that blends a cold first sample with a throttled last one.
                summary["samplesDetail"] = [
                    {
                        "index": index,
                        "warmUp": index == 0,
                        "tokensPerSecond": round(rate, 2),
                        "headroom": state["headroom"],
                        "cpuMilliCelsius": state["hottestCpuMilliCelsius"],
                    }
                    for index, (rate, state) in enumerate(zip(samples, thermal))
                ]
                report["variants"][variant] = summary
                print(f"  median {summary['medianTokensPerSecond']} tok/s", flush=True)
            except (ProbeError, urllib.error.URLError, OSError) as error:
                log = adb("shell", f"tail -20 {DEVICE_ROOT}/server.log", check=False)
                report["variants"][variant] = {
                    "error": str(error),
                    "serverFlags": flags,
                    "serverLogTail": log.strip().splitlines()[-8:],
                }
                print(f"  failed: {error}", flush=True)
    finally:
        stop_server(handle)
        adb("forward", "--remove", f"tcp:{args.port}", check=False)
        adb("shell", f"rm -f {api_key_path}", check=False)
        if not args.keep_model:
            adb("shell", f"rm -f {device_model}", check=False)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Wrote {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
