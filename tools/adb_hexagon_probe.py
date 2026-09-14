#!/usr/bin/env python3
"""Gate an isolated llama.cpp Hexagon v73 candidate on an Android handset.

The tool stages only below /data/local/tmp and never touches the installed app,
its private model store, or its server. A candidate must discover a real HTP0
session and beat a pure-CPU control at every requested workload before the
report recommends any later correctness/admission work.
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


BIG_CORE = "/sys/devices/system/cpu/cpu7/cpufreq"
COOLDOWN_HEADROOM = 0.98
COOLDOWN_MAX_CPU_MILLICELSIUS = 47_000
COOLDOWN_DEADLINE_SECONDS = 600
SAFE_FILENAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._+-]{0,190}$")
REQUIRED_FILES = (
    "bin/llama-bench",
    "bin/llama-server",
    "lib/libc++_shared.so",
    "lib/libggml-base.so",
    "lib/libggml-cpu.so",
    "lib/libggml-hexagon.so",
    "lib/libggml-htp-v73.so",
    "lib/libggml.so",
    "lib/libllama-bench-impl.so",
    "lib/libllama-common.so",
    "lib/libllama-server-impl.so",
    "lib/libllama.so",
    "lib/libmtmd.so",
)
VARIANTS: dict[str, tuple[str, ...]] = {
    "cpu": ("-dev", "none", "-ngl", "0", "-nopo", "1", "-nkvo", "1"),
    "htp-ops": ("-dev", "HTP0", "-ngl", "0", "-nopo", "0", "-nkvo", "0"),
    "htp-all": ("-dev", "HTP0", "-ngl", "99", "-nopo", "0", "-nkvo", "0"),
    "htp-all-cpu-kv": (
        "-dev", "HTP0", "-ngl", "99", "-nopo", "0", "-nkvo", "1",
    ),
    "htp-8": ("-dev", "HTP0", "-ngl", "8", "-nopo", "0", "-nkvo", "0"),
    "htp-16": ("-dev", "HTP0", "-ngl", "16", "-nopo", "0", "-nkvo", "0"),
}


class ProbeError(RuntimeError):
    pass


def variant_arguments(label: str) -> list[str]:
    try:
        return list(VARIANTS[label])
    except KeyError as error:
        raise ValueError(f"Unknown Hexagon variant: {label}") from error


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def candidate_manifest(directory: Path) -> dict[str, Any]:
    if not directory.is_dir():
        raise ProbeError(f"Candidate directory does not exist: {directory}")
    artifacts = []
    for relative in REQUIRED_FILES:
        path = directory / relative
        if not path.is_file() or path.is_symlink():
            raise ProbeError(f"Candidate is missing regular file {relative}")
        artifacts.append({
            "path": relative,
            "bytes": path.stat().st_size,
            "sha256": sha256_file(path),
        })
    identity = hashlib.sha256(
        json.dumps(artifacts, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()
    return {
        "directory": str(directory.resolve()),
        "identitySha256": identity,
        "artifacts": artifacts,
    }


def validated_device_path(value: str) -> str:
    path = PurePosixPath(value)
    if not path.is_absolute() or ".." in path.parts:
        raise ValueError("Device model path must be absolute and traversal-free")
    if tuple(path.parts[:4]) != ("/", "data", "local", "tmp"):
        raise ValueError("Device model path must live below /data/local/tmp")
    if not all(SAFE_FILENAME.fullmatch(part) for part in path.parts[4:]):
        raise ValueError("Device model path contains an unsafe component")
    return str(path)


def parse_device_model_manifest(
        raw: str, path: str, expected_sha256: str | None = None
) -> dict[str, Any]:
    lines = [line.strip() for line in raw.splitlines() if line.strip()]
    if len(lines) != 2 or not lines[0].isdigit():
        raise ProbeError("Could not read device model size and SHA-256")
    fields = lines[1].split()
    if len(fields) < 2 or not re.fullmatch(r"[0-9a-fA-F]{64}", fields[0]):
        raise ProbeError("Could not parse device model SHA-256")
    digest = fields[0].lower()
    if expected_sha256 is not None and digest != expected_sha256.lower():
        raise ProbeError(
            f"Device model SHA-256 mismatch: expected {expected_sha256.lower()}, got {digest}"
        )
    return {"path": path, "bytes": int(lines[0]), "sha256": digest}


def device_model_manifest(
        serial: str | None, path: str, expected_sha256: str | None
) -> dict[str, Any]:
    quoted = shlex.quote(path)
    result = adb_run(
        serial,
        "shell",
        f"/system/bin/toybox stat -c %s {quoted} && "
        f"/system/bin/toybox sha256sum {quoted}",
        timeout=600,
    )
    return parse_device_model_manifest(result.stdout, path, expected_sha256)


def parse_bench_jsonl(raw: str) -> list[dict[str, Any]]:
    rows = []
    for line in raw.splitlines():
        stripped = line.strip()
        if not stripped.startswith("{"):
            continue
        try:
            value = json.loads(stripped)
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict) and isinstance(value.get("avg_ts"), (int, float)):
            rows.append(value)
    if not rows:
        raise ProbeError("llama-bench produced no valid JSONL timing rows")
    return rows


def workload_rates(
        rows: list[dict[str, Any]], prompt_tokens: int, generated_tokens: int
) -> tuple[float, float, dict[str, Any]]:
    prompt = [row for row in rows
              if row.get("n_prompt") == prompt_tokens and row.get("n_gen") == 0]
    decode = [row for row in rows
              if row.get("n_prompt") == 0 and row.get("n_gen") == generated_tokens]
    if len(prompt) != 1 or len(decode) != 1:
        raise ProbeError(
            f"Expected one pp{prompt_tokens} and one tg{generated_tokens} row"
        )
    metadata_keys = (
        "build_commit", "build_number", "cpu_info", "gpu_info", "backends",
        "model_type", "model_size", "model_n_params", "n_gpu_layers",
        "devices", "no_kv_offload", "no_op_offload", "n_batch", "n_ubatch",
    )
    metadata = {key: prompt[0].get(key) for key in metadata_keys if key in prompt[0]}
    return float(prompt[0]["avg_ts"]), float(decode[0]["avg_ts"]), metadata


def score_samples(
        samples: list[dict[str, Any]],
        prompt_tokens: list[int],
        minimum_prompt_ratio: float,
        minimum_decode_ratio: float,
) -> dict[str, Any]:
    indexed: dict[tuple[str, int], dict[str, Any]] = {}
    for sample in samples:
        key = (str(sample.get("variant")), int(sample.get("promptTokens", -1)))
        if key in indexed:
            raise ProbeError(f"Duplicate Hexagon sample for {key[0]} pp{key[1]}")
        indexed[key] = sample
    controls = {}
    for prompt in prompt_tokens:
        control = indexed.get(("cpu", prompt))
        if not control or "error" in control:
            raise ProbeError(f"Missing successful CPU control for pp{prompt}")
        controls[prompt] = control

    results = {}
    for variant in dict.fromkeys(str(sample.get("variant")) for sample in samples):
        if variant == "cpu":
            continue
        comparisons = []
        complete = True
        for prompt in prompt_tokens:
            sample = indexed.get((variant, prompt))
            if not sample or "error" in sample:
                complete = False
                comparisons.append({
                    "promptTokens": prompt,
                    "error": (sample or {}).get("error", "missing"),
                })
                continue
            control = controls[prompt]
            comparisons.append({
                "promptTokens": prompt,
                "promptRatio": round(
                    sample["promptTokensPerSecond"]
                    / control["promptTokensPerSecond"], 6
                ),
                "decodeRatio": round(
                    sample["decodeTokensPerSecond"]
                    / control["decodeTokensPerSecond"], 6
                ),
            })
        prompt_ratios = [row["promptRatio"] for row in comparisons
                         if "promptRatio" in row]
        decode_ratios = [row["decodeRatio"] for row in comparisons
                         if "decodeRatio" in row]
        passed = bool(
            complete
            and prompt_ratios
            and min(prompt_ratios) >= minimum_prompt_ratio
            and min(decode_ratios) >= minimum_decode_ratio
        )
        results[variant] = {
            "comparisons": comparisons,
            "minimumPromptRatio": min(prompt_ratios) if prompt_ratios else None,
            "minimumDecodeRatio": min(decode_ratios) if decode_ratios else None,
            "gatePassed": passed,
            "prefillOnlyPotential": bool(
                complete
                and prompt_ratios
                and min(prompt_ratios) >= minimum_prompt_ratio
                and min(decode_ratios) < minimum_decode_ratio
            ),
        }
    winners = [variant for variant, result in results.items() if result["gatePassed"]]
    winner = max(
        winners,
        key=lambda variant: (
            results[variant]["minimumDecodeRatio"],
            results[variant]["minimumPromptRatio"],
        ),
        default=None,
    )
    return {
        "gate": {
            "minimumPromptRatio": minimum_prompt_ratio,
            "minimumDecodeRatio": minimum_decode_ratio,
            "requireEveryPromptSize": True,
            "requireRealHtpV73Session": True,
            "requireNoCrash": True,
        },
        "variants": results,
        "gatePassed": winner is not None,
        "winner": winner,
        "action": (
            f"Run full correctness and admission gates for {winner}; do not promote yet."
            if winner else
            "Keep the production runtime CPU-only; retain HTP as an isolated probe."
        ),
    }


def adb_prefix(serial: str | None) -> list[str]:
    return ["adb", *(["-s", serial] if serial else [])]


def adb_run(
        serial: str | None,
        *arguments: str,
        timeout: int = 120,
        check: bool = True,
) -> subprocess.CompletedProcess[str]:
    try:
        result = subprocess.run(
            [*adb_prefix(serial), *arguments],
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ProbeError("adb invocation failed") from error
    if check and result.returncode != 0:
        detail = (result.stderr or result.stdout).strip().splitlines()[-1:]
        raise ProbeError(f"adb failed ({result.returncode}): {' '.join(detail)}")
    return result


def remote_exec(
        serial: str | None,
        root: str,
        arguments: list[str],
        timeout: int,
        hexagon_devices: int = 1,
        check: bool = True,
) -> subprocess.CompletedProcess[str]:
    if hexagon_devices not in (0, 1):
        raise ValueError("hexagon_devices must be 0 or 1")
    command = (
        f"cd {shlex.quote(root)} && "
        f"LD_LIBRARY_PATH={shlex.quote(root + '/lib')} "
        f"ADSP_LIBRARY_PATH={shlex.quote(root + '/lib')} "
        f"GGML_HEXAGON_NDEV={hexagon_devices} "
        f"exec {shlex.join(arguments)}"
    )
    return adb_run(serial, "shell", command, timeout=timeout, check=check)


def thermal_state(serial: str | None) -> dict[str, Any]:
    clocks = adb_run(
        serial, "shell",
        f"cat {BIG_CORE}/scaling_max_freq {BIG_CORE}/cpuinfo_max_freq",
        check=True,
    ).stdout.split()
    if len(clocks) < 2 or not all(value.isdigit() for value in clocks[:2]):
        raise ProbeError("Device CPU clock telemetry is unavailable")
    scaling = int(clocks[0])
    nominal = int(clocks[1])
    if scaling <= 0 or nominal <= 0:
        raise ProbeError("Device CPU clock telemetry is invalid")
    raw = adb_run(
        serial, "shell",
        "for z in /sys/class/thermal/thermal_zone*/; do "
        "printf '%s %s\\n' \"$(cat $z/type 2>/dev/null)\" "
        "\"$(cat $z/temp 2>/dev/null)\"; done",
        check=True,
    ).stdout
    cpu_temperatures = []
    for line in raw.splitlines():
        parts = line.split()
        if len(parts) == 2 and "cpu" in parts[0].lower() \
                and parts[1].lstrip("-").isdigit():
            cpu_temperatures.append(int(parts[1]))
    if not cpu_temperatures:
        raise ProbeError("Device CPU temperature telemetry is unavailable")
    return {
        "bigCoreScalingMaxHz": scaling,
        "bigCoreNominalMaxHz": nominal,
        "headroom": round(scaling / nominal, 3) if nominal else None,
        "hottestCpuMilliCelsius": max(cpu_temperatures, default=None),
    }


def thermal_ready(state: dict[str, Any]) -> bool:
    headroom = state.get("headroom")
    temperature = state.get("hottestCpuMilliCelsius")
    return bool(
        isinstance(headroom, (int, float))
        and not isinstance(headroom, bool)
        and isinstance(temperature, (int, float))
        and not isinstance(temperature, bool)
        and headroom >= COOLDOWN_HEADROOM
        and temperature <= COOLDOWN_MAX_CPU_MILLICELSIUS
    )


def wait_for_cooldown(serial: str | None, enabled: bool) -> dict[str, Any]:
    state = thermal_state(serial)
    if not enabled:
        return state
    started = time.monotonic()
    while time.monotonic() - started < COOLDOWN_DEADLINE_SECONDS:
        state = thermal_state(serial)
        if thermal_ready(state):
            return state
        time.sleep(10)
    raise ProbeError(f"Phone did not cool inside the deadline: {state}")


def bench_arguments(
        root: str,
        model: str,
        variant: str,
        prompt_tokens: int,
        generated_tokens: int,
        repetitions: int,
        threads: int,
        cpu_mask: str,
        batch_size: int,
        ubatch_size: int,
) -> list[str]:
    return [
        f"{root}/bin/llama-bench",
        "-m", model,
        "-p", str(prompt_tokens),
        "-n", str(generated_tokens),
        "-b", str(batch_size),
        "-ub", str(ubatch_size),
        "-r", str(repetitions),
        "--delay", "3",
        "-o", "jsonl",
        "-t", str(threads),
        "-C", cpu_mask,
        "--cpu-strict", "1",
        "-fa", "on",
        "-mmp", "0",
        *variant_arguments(variant),
    ]


def write_json_atomic(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", dir=path.parent, delete=False
    ) as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
        temporary = Path(handle.name)
    os.replace(temporary, path)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--device-model", required=True)
    parser.add_argument("--model-sha256")
    parser.add_argument("--variant", action="append", choices=tuple(VARIANTS))
    parser.add_argument("--prompt-tokens", default="128")
    parser.add_argument("--generated-tokens", type=int, default=192)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--threads", type=int, default=5)
    parser.add_argument("--cpu-mask", default="0xf8")
    parser.add_argument("--batch-size", type=int, default=256)
    parser.add_argument("--ubatch-size", type=int, default=256)
    parser.add_argument("--minimum-prompt-ratio", type=float, default=2.0)
    parser.add_argument("--minimum-decode-ratio", type=float, default=0.95)
    parser.add_argument("--serial")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--plan-only", action="store_true")
    parser.add_argument("--no-cooldown", action="store_true")
    parser.add_argument("--cleanup", action="store_true")
    parser.add_argument("--require-promotion", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    manifest = candidate_manifest(args.candidate)
    model = validated_device_path(args.device_model)
    if args.model_sha256 is not None \
            and not re.fullmatch(r"[0-9a-fA-F]{64}", args.model_sha256):
        raise ProbeError("--model-sha256 must be 64 hexadecimal characters")
    variants = args.variant or ["cpu", "htp-all"]
    variants = ["cpu", *[value for value in variants if value != "cpu"]]
    if len(set(variants)) != len(variants):
        raise ProbeError("Each variant may be specified only once")
    try:
        prompts = [int(value) for value in args.prompt_tokens.split(",")]
    except ValueError as error:
        raise ProbeError("--prompt-tokens must be comma-separated integers") from error
    if not prompts or len(set(prompts)) != len(prompts) \
            or not all(16 <= value <= 4096 for value in prompts):
        raise ProbeError("Prompt sizes must be unique integers from 16 to 4096")
    if not 8 <= args.generated_tokens <= 512:
        raise ProbeError("--generated-tokens must be from 8 to 512")
    if not 2 <= args.repetitions <= 10:
        raise ProbeError("--repetitions must be from 2 to 10")
    if not re.fullmatch(r"0x[0-9a-fA-F]{1,16}", args.cpu_mask):
        raise ProbeError("--cpu-mask must be hexadecimal")

    root = "/data/local/tmp/pideck-hexagon-" + manifest["identitySha256"][:12]
    plan = [
        {
            "variant": variant,
            "promptTokens": prompt,
            "arguments": bench_arguments(
                root, model, variant, prompt, args.generated_tokens,
                args.repetitions, args.threads, args.cpu_mask,
                args.batch_size, args.ubatch_size,
            ),
        }
        for variant in variants
        for prompt in prompts
    ]
    report: dict[str, Any] = {
        "schemaVersion": 1,
        "status": "plan-only" if args.plan_only else "measured",
        "candidate": manifest,
        "deviceRoot": root,
        "model": {
            "path": model,
            "expectedSha256": args.model_sha256.lower() if args.model_sha256 else None,
        },
        "method": {
            "variants": variants,
            "promptTokens": prompts,
            "generatedTokens": args.generated_tokens,
            "repetitions": args.repetitions,
            "threads": args.threads,
            "cpuMask": args.cpu_mask,
            "batchSize": args.batch_size,
            "ubatchSize": args.ubatch_size,
            "cooldown": not args.no_cooldown,
        },
        "plan": plan,
    }
    if args.plan_only:
        write_json_atomic(args.output, report)
        print(f"Wrote Hexagon device plan to {args.output}")
        return 0

    adb_run(args.serial, "get-state")
    report["model"] = device_model_manifest(args.serial, model, args.model_sha256)
    adb_run(args.serial, "shell", f"mkdir -p {root}/bin {root}/lib")
    expected = {}
    for artifact in manifest["artifacts"]:
        relative = artifact["path"]
        adb_run(
            args.serial, "push", str(args.candidate / relative), f"{root}/{relative}",
            timeout=600,
        )
        expected[relative] = artifact["sha256"]
    adb_run(args.serial, "shell", f"chmod 755 {root}/bin/* {root}/lib/*")
    checksum = remote_exec(
        args.serial, root,
        ["/system/bin/toybox", "sha256sum", *[f"{root}/{p}" for p in expected]],
        timeout=300,
    )
    actual = {}
    for line in checksum.stdout.splitlines():
        fields = line.split()
        if len(fields) >= 2 and re.fullmatch(r"[0-9a-fA-F]{64}", fields[0]):
            path = fields[-1]
            actual[path.removeprefix(root + "/")] = fields[0].lower()
    if actual != expected:
        raise ProbeError("Candidate integrity check failed after ADB transfer")
    report["stagedCandidateSha256"] = actual

    listing = remote_exec(
        args.serial, root, [f"{root}/bin/llama-bench", "--list-devices"], timeout=120
    )
    backend_listing = (listing.stdout + "\n" + listing.stderr).strip()
    if "HTP0: Hexagon" not in backend_listing or "Hexagon Arch version v73" not in backend_listing:
        raise ProbeError("Candidate did not open a real Hexagon v73 HTP0 session")
    report["device"] = {
        "serial": args.serial,
        "model": adb_run(args.serial, "shell", "getprop ro.product.model").stdout.strip(),
        "soc": adb_run(args.serial, "shell", "getprop ro.soc.model").stdout.strip(),
        "android": adb_run(
            args.serial, "shell", "getprop ro.build.version.release"
        ).stdout.strip(),
        "backendListing": backend_listing.splitlines(),
    }

    samples = []
    report["status"] = "incomplete"
    report["failure"] = "Measurement is in progress; a complete matched sweep is required."
    report["samples"] = samples
    report["verdict"] = {
        "gatePassed": False,
        "winner": None,
        "action": "Discard this incomplete report and rerun the complete matched sweep.",
    }
    write_json_atomic(args.output, report)
    measurement_failure: str | None = None
    try:
        for variant in variants:
            for prompt in prompts:
                before = wait_for_cooldown(args.serial, not args.no_cooldown)
                print(
                    f"Measuring {variant} pp{prompt}/tg{args.generated_tokens} "
                    f"from {before}", flush=True
                )
                command = bench_arguments(
                    root, model, variant, prompt, args.generated_tokens,
                    args.repetitions, args.threads, args.cpu_mask,
                    args.batch_size, args.ubatch_size,
                )
                result = remote_exec(
                    args.serial,
                    root,
                    command,
                    timeout=1800,
                    hexagon_devices=0 if variant == "cpu" else 1,
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
                    sample["logTail"] = (result.stderr or result.stdout).splitlines()[-16:]
                else:
                    try:
                        rows = parse_bench_jsonl(result.stdout)
                        pp, tg, metadata = workload_rates(
                            rows, prompt, args.generated_tokens
                        )
                        sample.update({
                            "promptTokensPerSecond": round(pp, 6),
                            "decodeTokensPerSecond": round(tg, 6),
                            "benchmark": metadata,
                            "warnings": [
                                line for line in result.stderr.splitlines()
                                if "warn" in line.lower()
                            ][-16:],
                        })
                    except ProbeError as error:
                        sample["error"] = str(error)
                        sample["logTail"] = (result.stderr or result.stdout).splitlines()[-16:]
                samples.append(sample)
                report["samples"] = samples
                write_json_atomic(args.output, report)
    except ProbeError as error:
        measurement_failure = str(error)
    finally:
        if args.cleanup:
            adb_run(args.serial, "shell", f"rm -rf -- {root}", check=False)

    report["samples"] = samples
    if measurement_failure is not None:
        report["status"] = "incomplete"
        report["failure"] = measurement_failure
        report["verdict"] = {
            "gatePassed": False,
            "winner": None,
            "action": "Discard this incomplete report and rerun the complete matched sweep.",
        }
        write_json_atomic(args.output, report)
        print(f"Wrote incomplete {args.output}: {measurement_failure}")
        return 2
    report["status"] = "measured"
    report.pop("failure", None)
    report["verdict"] = score_samples(
        samples, prompts, args.minimum_prompt_ratio, args.minimum_decode_ratio
    )
    write_json_atomic(args.output, report)
    print(f"Wrote {args.output}; gate passed: {report['verdict']['gatePassed']}")
    if args.require_promotion and not report["verdict"]["gatePassed"]:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
