#!/usr/bin/env python3
"""Compare pure CPU, partial offload, and full Adreno offload on Android.

The probe deliberately uses a standalone llama-bench candidate rather than the
app-owned production server. A failed or slower accelerator therefore cannot
change the installed PI//DECK runtime. Use ``--plan-only`` while no device is
attached; the exact same command becomes executable when the phone returns.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shlex
import subprocess
import tempfile
import time
from pathlib import Path, PurePosixPath
from typing import Any


DEVICE_ROOT = "/data/local/tmp/pideck-accelerator"
BIG_CORE = "/sys/devices/system/cpu/cpu7/cpufreq"
SAFE_FILENAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._+-]{0,190}$")
COOLDOWN_HEADROOM = 0.98
COOLDOWN_DEADLINE_SECONDS = 600

# ``-ngl 0`` alone is not a pure CPU control: llama.cpp may still offload
# individual ops. The control disables the accelerator device and op offload.
VARIANTS: dict[str, tuple[str, ...]] = {
    "cpu": ("-dev", "none", "-ngl", "0", "-nopo", "1", "-nkvo", "1"),
    "op-offload": ("-ngl", "0", "-nopo", "0", "-nkvo", "0"),
    "hybrid-8": ("-ngl", "8", "-sm", "layer", "-nopo", "0", "-nkvo", "0"),
    "hybrid-16": ("-ngl", "16", "-sm", "layer", "-nopo", "0", "-nkvo", "0"),
    "accelerator-all": (
        "-ngl", "99", "-sm", "layer", "-nopo", "0", "-nkvo", "0",
    ),
    # Qwen3.5 carries both attention KV and recurrent state. This isolates
    # whether keeping that state on CPU helps interactive decode.
    "accelerator-all-cpu-state": (
        "-ngl", "99", "-sm", "layer", "-nopo", "0", "-nkvo", "1",
    ),
}
DEFAULT_VARIANTS = tuple(VARIANTS)
REQUIRED_CANDIDATE_FILES = ("llama-bench", "libomp.so", "libc++_shared.so")


class ProbeError(RuntimeError):
    pass


def variant_arguments(label: str) -> list[str]:
    try:
        return list(VARIANTS[label])
    except KeyError as error:
        raise ValueError(f"Unknown accelerator variant: {label}") from error


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def candidate_manifest(directory: Path) -> dict[str, Any]:
    if not directory.is_dir():
        raise ProbeError(f"Candidate directory does not exist: {directory}")
    artifacts: list[dict[str, Any]] = []
    for name in REQUIRED_CANDIDATE_FILES:
        path = directory / name
        if not path.is_file():
            raise ProbeError(f"Candidate is missing {name}")
        artifacts.append(
            {"name": name, "bytes": path.stat().st_size, "sha256": sha256_file(path)}
        )
    server = directory / "llama-server"
    if server.is_file():
        artifacts.append(
            {
                "name": server.name,
                "bytes": server.stat().st_size,
                "sha256": sha256_file(server),
            }
        )
    return {"directory": str(directory.resolve()), "artifacts": artifacts}


def parse_bench_jsonl(raw: str) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line in raw.splitlines():
        stripped = line.strip()
        if not stripped.startswith("{"):
            continue
        try:
            value = json.loads(stripped)
        except json.JSONDecodeError:
            continue
        if not isinstance(value, dict):
            continue
        rate = value.get("avg_ts")
        if isinstance(rate, (int, float)) and rate > 0:
            rows.append(value)
    if not rows:
        raise ProbeError("llama-bench produced no valid JSONL timing rows")
    return rows


def workload_rates(
    rows: list[dict[str, Any]], prompt_tokens: int, generated_tokens: int
) -> tuple[float, float, dict[str, Any]]:
    prompt_rows = [
        row
        for row in rows
        if row.get("n_prompt") == prompt_tokens and row.get("n_gen") == 0
    ]
    decode_rows = [
        row
        for row in rows
        if row.get("n_prompt") == 0 and row.get("n_gen") == generated_tokens
    ]
    if len(prompt_rows) != 1 or len(decode_rows) != 1:
        raise ProbeError(
            "Expected exactly one prompt and one decode row for "
            f"p{prompt_tokens}/n{generated_tokens}"
        )
    prompt_rate = float(prompt_rows[0]["avg_ts"])
    decode_rate = float(decode_rows[0]["avg_ts"])
    metadata = {
        key: prompt_rows[0].get(key)
        for key in (
            "build_commit",
            "build_number",
            "cpu_info",
            "gpu_info",
            "backends",
            "backend",
            "model_type",
            "model_size",
            "model_n_params",
            "n_gpu_layers",
            "tensor_buft_overrides",
        )
        if key in prompt_rows[0]
    }
    return prompt_rate, decode_rate, metadata


def score_samples(
    samples: list[dict[str, Any]],
    prompt_tokens: list[int],
    minimum_prompt_ratio: float,
    minimum_decode_ratio: float,
) -> dict[str, Any]:
    by_key: dict[tuple[Any, Any], dict[str, Any]] = {}
    for sample in samples:
        key = (sample.get("variant"), sample.get("promptTokens"))
        if key in by_key:
            raise ProbeError(
                f"Duplicate accelerator sample for {key[0]} p{key[1]}"
            )
        by_key[key] = sample
    baseline: dict[int, dict[str, Any]] = {}
    for prompt in prompt_tokens:
        sample = by_key.get(("cpu", prompt))
        if not sample or "error" in sample:
            raise ProbeError(f"Missing successful pure-CPU baseline for p{prompt}")
        baseline[prompt] = sample

    variants: dict[str, Any] = {}
    for label in dict.fromkeys(str(sample.get("variant")) for sample in samples):
        if label == "cpu":
            continue
        comparisons = []
        complete = True
        for prompt in prompt_tokens:
            sample = by_key.get((label, prompt))
            if not sample or "error" in sample:
                complete = False
                comparisons.append(
                    {"promptTokens": prompt, "error": (sample or {}).get("error", "missing")}
                )
                continue
            cpu = baseline[prompt]
            prompt_ratio = sample["promptTokensPerSecond"] / cpu["promptTokensPerSecond"]
            decode_ratio = sample["decodeTokensPerSecond"] / cpu["decodeTokensPerSecond"]
            comparisons.append(
                {
                    "promptTokens": prompt,
                    "promptRatio": round(prompt_ratio, 6),
                    "decodeRatio": round(decode_ratio, 6),
                }
            )
        prompt_ratios = [row["promptRatio"] for row in comparisons if "promptRatio" in row]
        decode_ratios = [row["decodeRatio"] for row in comparisons if "decodeRatio" in row]
        gate_passed = bool(
            complete
            and prompt_ratios
            and min(prompt_ratios) >= minimum_prompt_ratio
            and min(decode_ratios) >= minimum_decode_ratio
        )
        variants[label] = {
            "comparisons": comparisons,
            "minimumPromptRatio": round(min(prompt_ratios), 6) if prompt_ratios else None,
            "minimumDecodeRatio": round(min(decode_ratios), 6) if decode_ratios else None,
            "gatePassed": gate_passed,
            "prefillOnlyPotential": bool(
                complete
                and prompt_ratios
                and min(prompt_ratios) >= minimum_prompt_ratio
                and min(decode_ratios) < minimum_decode_ratio
            ),
        }

    passing = [label for label, result in variants.items() if result["gatePassed"]]
    winner = max(
        passing,
        key=lambda label: (
            variants[label]["minimumPromptRatio"],
            variants[label]["minimumDecodeRatio"],
        ),
        default=None,
    )
    return {
        "gate": {
            "minimumPromptRatio": minimum_prompt_ratio,
            "minimumDecodeRatio": minimum_decode_ratio,
            "requireAllPromptSizes": True,
            "requireNoCrash": True,
        },
        "variants": variants,
        "gatePassed": winner is not None,
        "winner": winner,
        "action": (
            f"Run correctness and suite-v2 gates for {winner}; do not promote yet."
            if winner
            else "Keep the production runtime CPU-only."
        ),
    }


def thermal_headroom(scaling_max: int, nominal_max: int) -> float:
    if nominal_max <= 0:
        raise ValueError("Nominal maximum frequency must be positive")
    return max(0.0, min(1.0, scaling_max / nominal_max))


def adb_prefix(serial: str | None) -> list[str]:
    return ["adb", *(["-s", serial] if serial else [])]


def adb_run(
    serial: str | None,
    *arguments: str,
    timeout: int = 120,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        [*adb_prefix(serial), *arguments],
        capture_output=True,
        text=True,
        timeout=timeout,
        check=False,
    )
    if check and result.returncode != 0:
        detail = (result.stderr or result.stdout).strip().splitlines()[-1:]
        raise ProbeError(f"adb command failed ({result.returncode}): {' '.join(detail)}")
    return result


def remote_path(value: str) -> str:
    path = PurePosixPath(value)
    if not path.is_absolute() or ".." in path.parts:
        raise ValueError("Device model path must be absolute and traversal-free")
    if tuple(path.parts[:4]) != ("/", "data", "local", "tmp"):
        raise ValueError("Device model path must live below /data/local/tmp")
    if not all(SAFE_FILENAME.fullmatch(part) for part in path.parts[4:]):
        raise ValueError("Device model path contains an unsafe component")
    return str(path)


def remote_exec(
    serial: str | None,
    arguments: list[str],
    timeout: int,
    check: bool = True,
    environment: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    assignments = {"LD_LIBRARY_PATH": DEVICE_ROOT, **(environment or {})}
    encoded_environment = " ".join(
        f"{name}={shlex.quote(value)}" for name, value in assignments.items()
    )
    command = (
        f"cd {shlex.quote(DEVICE_ROOT)} && "
        f"{encoded_environment} exec {shlex.join(arguments)}"
    )
    return adb_run(serial, "shell", command, timeout=timeout, check=check)


def verify_staged_candidate(
    serial: str | None, manifest: dict[str, Any]
) -> dict[str, str]:
    expected = {
        artifact["name"]: artifact["sha256"]
        for artifact in manifest["artifacts"]
    }
    result = remote_exec(
        serial,
        ["/system/bin/toybox", "sha256sum", *expected],
        timeout=300,
    )
    actual: dict[str, str] = {}
    for line in result.stdout.splitlines():
        fields = line.split()
        if len(fields) >= 2 and re.fullmatch(r"[0-9a-fA-F]{64}", fields[0]):
            actual[PurePosixPath(fields[-1]).name] = fields[0].lower()
    if actual != expected:
        raise ProbeError(
            "Candidate integrity check failed after ADB transfer: "
            f"expected {expected}, got {actual}"
        )
    return actual


def thermal_state(serial: str | None) -> dict[str, Any]:
    clocks = adb_run(
        serial,
        "shell",
        f"cat {BIG_CORE}/scaling_max_freq {BIG_CORE}/cpuinfo_max_freq",
        check=False,
    ).stdout.split()
    scaling = int(clocks[0]) if len(clocks) >= 2 and clocks[0].isdigit() else 0
    nominal = int(clocks[1]) if len(clocks) >= 2 and clocks[1].isdigit() else 0
    listing = adb_run(
        serial,
        "shell",
        "for z in /sys/class/thermal/thermal_zone*/; do "
        "printf '%s %s\\n' \"$(cat $z/type 2>/dev/null)\" "
        "\"$(cat $z/temp 2>/dev/null)\"; done",
        check=False,
    ).stdout
    readings = []
    for line in listing.splitlines():
        parts = line.split()
        if (
            len(parts) == 2
            and any(name in parts[0].lower() for name in ("cpu", "gpu", "soc"))
            and parts[1].lstrip("-").isdigit()
        ):
            readings.append({"zone": parts[0], "milliCelsius": int(parts[1])})
    return {
        "bigCoreScalingMaxHz": scaling,
        "bigCoreNominalMaxHz": nominal,
        "headroom": round(thermal_headroom(scaling, nominal), 3) if nominal else None,
        "hottestComputeZone": max(readings, key=lambda row: row["milliCelsius"], default=None),
    }


def wait_for_thermal_headroom(serial: str | None, enabled: bool) -> dict[str, Any]:
    if not enabled:
        return thermal_state(serial)
    started = time.monotonic()
    state = thermal_state(serial)
    while time.monotonic() - started < COOLDOWN_DEADLINE_SECONDS:
        state = thermal_state(serial)
        headroom = state["headroom"]
        if headroom is None or headroom >= COOLDOWN_HEADROOM:
            return state
        time.sleep(10)
    return state


def bench_arguments(
    model: str,
    variant: str,
    prompt_tokens: int,
    generated_tokens: int,
    repetitions: int,
    threads: int,
    cpu_mask: str,
) -> list[str]:
    return [
        f"{DEVICE_ROOT}/llama-bench",
        "-m", model,
        "-p", str(prompt_tokens),
        "-n", str(generated_tokens),
        "-b", str(prompt_tokens),
        "-ub", str(prompt_tokens),
        "-r", str(repetitions),
        "--delay", "1",
        "-o", "jsonl",
        "-t", str(threads),
        "-C", cpu_mask,
        "--cpu-strict", "1",
        *variant_arguments(variant),
    ]


def write_json_atomic(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    encoded = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    with tempfile.NamedTemporaryFile(
        mode="w", encoding="utf-8", dir=path.parent, delete=False
    ) as handle:
        handle.write(encoded)
        temporary = Path(handle.name)
    os.replace(temporary, path)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate", type=Path, required=True)
    model = parser.add_mutually_exclusive_group(required=True)
    model.add_argument("--model", type=Path, help="Host GGUF path to stage")
    model.add_argument("--device-model", help="Existing GGUF below /data/local/tmp")
    parser.add_argument("--variant", action="append", choices=tuple(VARIANTS))
    parser.add_argument("--prompt-tokens", default="128,512")
    parser.add_argument("--generated-tokens", type=int, default=32)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--threads", type=int, default=8)
    parser.add_argument("--cpu-mask", default="0xff")
    parser.add_argument("--minimum-prompt-ratio", type=float, default=2.0)
    parser.add_argument("--minimum-decode-ratio", type=float, default=0.95)
    parser.add_argument("--expect-backend", default="Vulkan")
    parser.add_argument("--serial")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--plan-only", action="store_true")
    parser.add_argument("--no-cooldown", action="store_true")
    parser.add_argument("--cleanup", action="store_true")
    parser.add_argument("--require-promotion", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    variants = args.variant or list(DEFAULT_VARIANTS)
    if variants[0] != "cpu":
        variants = ["cpu", *[variant for variant in variants if variant != "cpu"]]
    if len(set(variants)) != len(variants):
        raise ProbeError("Each --variant may be specified only once")
    try:
        prompts = [int(value) for value in args.prompt_tokens.split(",")]
    except ValueError as error:
        raise ProbeError("--prompt-tokens must be a comma-separated integer list") from error
    if not prompts or len(set(prompts)) != len(prompts) or not all(16 <= p <= 4096 for p in prompts):
        raise ProbeError("Prompt sizes must be unique integers between 16 and 4096")
    if not 8 <= args.generated_tokens <= 512:
        raise ProbeError("--generated-tokens must be between 8 and 512")
    if not 2 <= args.repetitions <= 10:
        raise ProbeError("--repetitions must be between 2 and 10")
    if not 1 <= args.threads <= 16:
        raise ProbeError("--threads must be between 1 and 16")
    if not re.fullmatch(r"0x[0-9a-fA-F]{1,16}", args.cpu_mask):
        raise ProbeError("--cpu-mask must be hexadecimal, for example 0xff")
    if args.minimum_prompt_ratio <= 0 or args.minimum_decode_ratio <= 0:
        raise ProbeError("Promotion ratios must be positive")

    manifest = candidate_manifest(args.candidate)
    if args.device_model:
        device_model = remote_path(args.device_model)
        staged_model = False
    else:
        assert args.model is not None
        if not args.model.is_file() or not SAFE_FILENAME.fullmatch(args.model.name):
            raise ProbeError("Host model must be a safe regular GGUF file")
        if args.model.suffix.lower() != ".gguf":
            raise ProbeError("Host model must end in .gguf")
        device_model = f"{DEVICE_ROOT}/{args.model.name}"
        staged_model = True

    plan = [
        {
            "variant": variant,
            "promptTokens": prompt,
            "arguments": bench_arguments(
                device_model,
                variant,
                prompt,
                args.generated_tokens,
                args.repetitions,
                args.threads,
                args.cpu_mask,
            ),
        }
        for variant in variants
        for prompt in prompts
    ]
    report: dict[str, Any] = {
        "schemaVersion": 1,
        "status": "plan-only" if args.plan_only else "measured",
        "candidate": manifest,
        "model": device_model,
        "method": {
            "variants": variants,
            "promptTokens": prompts,
            "generatedTokens": args.generated_tokens,
            "repetitions": args.repetitions,
            "threads": args.threads,
            "cpuMask": args.cpu_mask,
            "expectedBackend": args.expect_backend,
            "cooldown": not args.no_cooldown,
        },
        "plan": plan,
    }
    if args.plan_only:
        write_json_atomic(args.output, report)
        print(f"Wrote executable device plan to {args.output}")
        return 0

    adb_run(args.serial, "get-state")
    adb_run(args.serial, "shell", f"mkdir -p {shlex.quote(DEVICE_ROOT)}")
    for artifact in manifest["artifacts"]:
        source = args.candidate / artifact["name"]
        adb_run(
            args.serial,
            "push", str(source), f"{DEVICE_ROOT}/{artifact['name']}",
            timeout=600,
        )
    adb_run(args.serial, "shell", f"chmod 755 {DEVICE_ROOT}/llama-* {DEVICE_ROOT}/*.so")
    report["stagedCandidateSha256"] = verify_staged_candidate(args.serial, manifest)

    if staged_model:
        assert args.model is not None
        existing = adb_run(
            args.serial,
            "shell", f"stat -c %s {shlex.quote(device_model)} 2>/dev/null",
            check=False,
        ).stdout.strip()
        if existing != str(args.model.stat().st_size):
            adb_run(args.serial, "push", str(args.model), device_model, timeout=3600)

    devices = remote_exec(
        args.serial,
        [f"{DEVICE_ROOT}/llama-bench", "--list-devices"],
        timeout=120,
    )
    device_listing = (devices.stdout + "\n" + devices.stderr).strip()
    if args.expect_backend.lower() not in device_listing.lower():
        raise ProbeError(
            f"Candidate did not discover the expected {args.expect_backend} backend"
        )
    report["device"] = {
        "serial": args.serial,
        "model": adb_run(args.serial, "shell", "getprop ro.product.model").stdout.strip(),
        "soc": adb_run(args.serial, "shell", "getprop ro.soc.model").stdout.strip(),
        "android": adb_run(args.serial, "shell", "getprop ro.build.version.release").stdout.strip(),
        "backendListing": device_listing.splitlines(),
    }

    samples: list[dict[str, Any]] = []
    try:
        for variant in variants:
            for prompt in prompts:
                before = wait_for_thermal_headroom(
                    args.serial, not args.no_cooldown
                )
                command = bench_arguments(
                    device_model,
                    variant,
                    prompt,
                    args.generated_tokens,
                    args.repetitions,
                    args.threads,
                    args.cpu_mask,
                )
                print(f"Measuring {variant} p{prompt}/n{args.generated_tokens}", flush=True)
                result = remote_exec(
                    args.serial,
                    command,
                    timeout=1800,
                    check=False,
                )
                sample: dict[str, Any] = {
                    "variant": variant,
                    "promptTokens": prompt,
                    "generatedTokens": args.generated_tokens,
                    "thermalBefore": before,
                    "thermalAfter": thermal_state(args.serial),
                }
                if result.returncode != 0:
                    sample["error"] = f"llama-bench exited {result.returncode}"
                    sample["logTail"] = (result.stderr or result.stdout).splitlines()[-12:]
                else:
                    try:
                        rows = parse_bench_jsonl(result.stdout)
                        prompt_rate, decode_rate, metadata = workload_rates(
                            rows, prompt, args.generated_tokens
                        )
                        sample.update(
                            {
                                "promptTokensPerSecond": round(prompt_rate, 6),
                                "decodeTokensPerSecond": round(decode_rate, 6),
                                "benchmark": metadata,
                            }
                        )
                    except ProbeError as error:
                        sample["error"] = str(error)
                        sample["logTail"] = (result.stderr or result.stdout).splitlines()[-12:]
                samples.append(sample)
        report["samples"] = samples
        report["verdict"] = score_samples(
            samples,
            prompts,
            args.minimum_prompt_ratio,
            args.minimum_decode_ratio,
        )
    finally:
        if args.cleanup:
            adb_run(
                args.serial,
                "shell", f"rm -rf {shlex.quote(DEVICE_ROOT)}",
                check=False,
            )

    write_json_atomic(args.output, report)
    print(f"Wrote {args.output}; gate passed: {report['verdict']['gatePassed']}")
    if args.require_promotion and not report["verdict"]["gatePassed"]:
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
