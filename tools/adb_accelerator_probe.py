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
COOLDOWN_MAX_COMPUTE_MILLICELSIUS = 47_000

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
OPTIONAL_CANDIDATE_FILES = ("llama-server", "test-backend-ops")
CORRECTNESS_TESTS: dict[str, tuple[str, ...]] = {
    "q6-k-mul-mat": ("test", "-o", "MUL_MAT", "-p", "type_a=q6_K"),
    "flash-attention": ("test", "-o", "FLASH_ATTN_EXT"),
}
ANSI_ESCAPE = re.compile(r"\x1b\[[0-9;]*m")


class ProbeError(RuntimeError):
    pass


def variant_arguments(label: str) -> list[str]:
    try:
        return list(VARIANTS[label])
    except KeyError as error:
        raise ValueError(f"Unknown accelerator variant: {label}") from error


def correctness_arguments(label: str) -> list[str]:
    try:
        arguments = CORRECTNESS_TESTS[label]
    except KeyError as error:
        raise ValueError(f"Unknown backend correctness test: {label}") from error
    return [f"{DEVICE_ROOT}/test-backend-ops", *arguments]


def correctness_result(
    label: str,
    arguments: list[str],
    returncode: int,
    stdout: str,
    stderr: str,
    expected_backend: str,
) -> dict[str, Any]:
    raw = stdout + "\n" + stderr
    clean = ANSI_ESCAPE.sub("", raw)
    test_summaries = [
        (int(passed), int(total))
        for passed, total in re.findall(r"\b(\d+)/(\d+) tests passed\b", clean)
    ]
    backend_seen = expected_backend.casefold() in clean.casefold()
    passed = bool(
        returncode == 0
        and backend_seen
        and test_summaries
        and all(total > 0 and successful == total for successful, total in test_summaries)
    )
    interesting = [
        line.strip()
        for line in clean.splitlines()
        if line.strip()
        and any(
            marker in line.casefold()
            for marker in (
                "backend ",
                "device description",
                "tests passed",
                "backends passed",
                " fail",
                "error",
            )
        )
    ]
    return {
        "label": label,
        "arguments": arguments,
        "returnCode": returncode,
        "expectedBackendObserved": backend_seen,
        "testSummaries": [
            {"passed": successful, "total": total}
            for successful, total in test_summaries
        ],
        "outputSha256": hashlib.sha256(raw.encode("utf-8", "replace")).hexdigest(),
        "summaryTail": interesting[-64:],
        "passed": passed,
    }


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
        if not path.is_file() or path.is_symlink():
            raise ProbeError(f"Candidate is missing {name}")
        artifacts.append(
            {"name": name, "bytes": path.stat().st_size, "sha256": sha256_file(path)}
        )
    for name in OPTIONAL_CANDIDATE_FILES:
        optional = directory / name
        if optional.is_file() and not optional.is_symlink():
            artifacts.append(
                {
                    "name": optional.name,
                    "bytes": optional.stat().st_size,
                    "sha256": sha256_file(optional),
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
        timeout=1200,
    )
    return parse_device_model_manifest(result.stdout, path, expected_sha256)


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
        check=True,
    ).stdout.split()
    if len(clocks) < 2 or not all(value.isdigit() for value in clocks[:2]):
        raise ProbeError("Device CPU clock telemetry is unavailable")
    scaling = int(clocks[0])
    nominal = int(clocks[1])
    if scaling <= 0 or nominal <= 0:
        raise ProbeError("Device CPU clock telemetry is invalid")
    listing = adb_run(
        serial,
        "shell",
        "for z in /sys/class/thermal/thermal_zone*/; do "
        "printf '%s %s\\n' \"$(cat $z/type 2>/dev/null)\" "
        "\"$(cat $z/temp 2>/dev/null)\"; done",
        check=True,
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
    if not readings:
        raise ProbeError("Device compute-temperature telemetry is unavailable")
    return {
        "bigCoreScalingMaxHz": scaling,
        "bigCoreNominalMaxHz": nominal,
        "headroom": round(thermal_headroom(scaling, nominal), 3) if nominal else None,
        "hottestComputeZone": max(readings, key=lambda row: row["milliCelsius"], default=None),
    }


def thermal_ready(state: dict[str, Any]) -> bool:
    headroom = state.get("headroom")
    hottest = state.get("hottestComputeZone")
    temperature = hottest.get("milliCelsius") if isinstance(hottest, dict) else None
    return bool(
        isinstance(headroom, (int, float))
        and not isinstance(headroom, bool)
        and headroom >= COOLDOWN_HEADROOM
        and isinstance(temperature, (int, float))
        and not isinstance(temperature, bool)
        and temperature <= COOLDOWN_MAX_COMPUTE_MILLICELSIUS
    )


def wait_for_thermal_headroom(serial: str | None, enabled: bool) -> dict[str, Any]:
    if not enabled:
        return thermal_state(serial)
    started = time.monotonic()
    state = thermal_state(serial)
    while time.monotonic() - started < COOLDOWN_DEADLINE_SECONDS:
        state = thermal_state(serial)
        if thermal_ready(state):
            return state
        time.sleep(10)
    raise ProbeError(f"Phone did not cool inside the deadline: {state}")


def bench_arguments(
    model: str,
    variant: str,
    prompt_tokens: int,
    generated_tokens: int,
    repetitions: int,
    threads: int,
    cpu_mask: str,
    flash_attention: str = "auto",
) -> list[str]:
    arguments = [
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
    ]
    if flash_attention != "auto":
        arguments.extend(("-fa", flash_attention))
    arguments.extend(variant_arguments(variant))
    return arguments


def write_json_atomic(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    encoded = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    with tempfile.NamedTemporaryFile(
        mode="w", encoding="utf-8", dir=path.parent, delete=False
    ) as handle:
        handle.write(encoded)
        temporary = Path(handle.name)
    os.replace(temporary, path)


def load_resume_report(
    path: Path, planned: dict[str, Any]
) -> tuple[list[dict[str, Any]], str]:
    try:
        raw = path.read_bytes()
        previous = json.loads(raw)
    except (OSError, json.JSONDecodeError) as error:
        raise ProbeError("Resume report is unavailable or invalid") from error
    if not isinstance(previous, dict) or previous.get("status") != "incomplete":
        raise ProbeError("--resume requires an incomplete accelerator report")
    for key in ("schemaVersion", "candidate", "method", "correctnessPlan", "plan"):
        if previous.get(key) != planned.get(key):
            raise ProbeError(f"Resume report {key} differs from the current exact plan")
    expected_model = planned.get("model")
    previous_model = previous.get("model")
    if not isinstance(expected_model, dict) or not isinstance(previous_model, dict):
        raise ProbeError("Resume report model identity is missing")
    expected_sha = expected_model.get("expectedSha256")
    if (
        previous_model.get("path") != expected_model.get("path")
        or not isinstance(previous_model.get("bytes"), int)
        or previous_model.get("bytes", 0) <= 0
        or previous_model.get("sha256") != expected_sha
    ):
        raise ProbeError("Resume report model path or SHA-256 differs")
    samples = previous.get("samples")
    if not isinstance(samples, list) or not all(isinstance(item, dict) for item in samples):
        raise ProbeError("Resume report samples are invalid")
    expected_workloads = [
        (item.get("variant"), item.get("promptTokens"))
        for item in planned.get("plan", [])
        if isinstance(item, dict)
    ]
    actual_workloads = [
        (item.get("variant"), item.get("promptTokens")) for item in samples
    ]
    if actual_workloads != expected_workloads[: len(actual_workloads)]:
        raise ProbeError("Resume samples are not an exact prefix of the current plan")
    if len(actual_workloads) >= len(expected_workloads):
        raise ProbeError("Resume report has no unfinished performance workload")
    if samples and planned.get("correctnessPlan"):
        correctness = previous.get("correctness")
        labels = [
            item.get("label") for item in correctness
            if isinstance(item, dict) and item.get("passed") is True
        ] if isinstance(correctness, list) else []
        expected_labels = [
            item.get("label") for item in planned["correctnessPlan"]
        ]
        if labels != expected_labels:
            raise ProbeError("Resume report does not contain the exact passed correctness gates")
    return [dict(item) for item in samples], hashlib.sha256(raw).hexdigest()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate", type=Path, required=True)
    model = parser.add_mutually_exclusive_group(required=True)
    model.add_argument("--model", type=Path, help="Host GGUF path to stage")
    model.add_argument("--device-model", help="Existing GGUF below /data/local/tmp")
    parser.add_argument("--model-sha256")
    parser.add_argument("--variant", action="append", choices=tuple(VARIANTS))
    parser.add_argument("--prompt-tokens", default="128,512")
    parser.add_argument("--generated-tokens", type=int, default=32)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--threads", type=int, default=8)
    parser.add_argument("--cpu-mask", default="0xff")
    parser.add_argument("--flash-attention", choices=("auto", "on", "off"), default="auto")
    parser.add_argument("--minimum-prompt-ratio", type=float, default=2.0)
    parser.add_argument("--minimum-decode-ratio", type=float, default=0.95)
    parser.add_argument("--expect-backend", default="Vulkan")
    parser.add_argument(
        "--correctness-test",
        action="append",
        choices=tuple(CORRECTNESS_TESTS),
        help="Run a filtered test-backend-ops gate before any performance sample.",
    )
    parser.add_argument("--serial")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--plan-only", action="store_true")
    parser.add_argument(
        "--resume",
        action="store_true",
        help="Continue an exact incomplete output report without repeating completed samples.",
    )
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
    if args.model_sha256 is not None \
            and not re.fullmatch(r"[0-9a-fA-F]{64}", args.model_sha256):
        raise ProbeError("--model-sha256 must be 64 hexadecimal characters")

    manifest = candidate_manifest(args.candidate)
    correctness_tests = args.correctness_test or []
    if len(set(correctness_tests)) != len(correctness_tests):
        raise ProbeError("Each --correctness-test may be specified only once")
    artifact_names = {artifact["name"] for artifact in manifest["artifacts"]}
    if correctness_tests and "test-backend-ops" not in artifact_names:
        raise ProbeError("Correctness tests require candidate test-backend-ops")
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
                args.flash_attention,
            ),
        }
        for variant in variants
        for prompt in prompts
    ]
    report: dict[str, Any] = {
        "schemaVersion": 1,
        "status": "plan-only" if args.plan_only else "measured",
        "candidate": manifest,
        "model": {
            "path": device_model,
            "expectedSha256": args.model_sha256.lower() if args.model_sha256 else None,
        },
        "method": {
            "variants": variants,
            "promptTokens": prompts,
            "generatedTokens": args.generated_tokens,
            "repetitions": args.repetitions,
            "threads": args.threads,
            "cpuMask": args.cpu_mask,
            "flashAttention": args.flash_attention,
            "expectedBackend": args.expect_backend,
            "correctnessTests": correctness_tests,
            "cooldown": not args.no_cooldown,
        },
        "correctnessPlan": [
            {"label": label, "arguments": correctness_arguments(label)}
            for label in correctness_tests
        ],
        "plan": plan,
    }
    resume_samples: list[dict[str, Any]] = []
    if args.resume:
        if args.plan_only:
            raise ProbeError("--resume and --plan-only are mutually exclusive")
        resume_samples, resume_sha256 = load_resume_report(args.output, report)
        report["resume"] = {
            "sourceReportSha256": resume_sha256,
            "completedSamples": len(resume_samples),
            "correctnessRerun": True,
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
    remote_exec(
        args.serial,
        ["/system/bin/chmod", "755", *sorted(artifact_names)],
        timeout=120,
    )
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
    report["model"] = device_model_manifest(
        args.serial, device_model, args.model_sha256
    )

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

    report["correctness"] = []
    correctness_failure: str | None = None
    try:
        for label in correctness_tests:
            before = wait_for_thermal_headroom(args.serial, not args.no_cooldown)
            arguments = correctness_arguments(label)
            print(f"Checking backend correctness: {label}", flush=True)
            result = remote_exec(
                args.serial,
                arguments,
                timeout=1800,
                check=False,
            )
            checked = correctness_result(
                label,
                arguments,
                result.returncode,
                result.stdout,
                result.stderr,
                args.expect_backend,
            )
            checked["thermalBefore"] = before
            checked["thermalAfter"] = thermal_state(args.serial)
            report["correctness"].append(checked)
            if not checked["passed"]:
                break
    except ProbeError as error:
        correctness_failure = str(error)

    if correctness_failure is not None:
        report["status"] = "incomplete"
        report["failure"] = correctness_failure
        report["samples"] = []
        report["verdict"] = {
            "gatePassed": False,
            "winner": None,
            "action": "Discard this incomplete report and rerun the complete matched sweep.",
        }
        if args.cleanup:
            adb_run(
                args.serial,
                "shell", f"rm -rf {shlex.quote(DEVICE_ROOT)}",
                check=False,
            )
        write_json_atomic(args.output, report)
        print(f"Wrote incomplete {args.output}: {correctness_failure}")
        return 2

    if report["correctness"] and not all(
        item["passed"] for item in report["correctness"]
    ):
        report["samples"] = []
        report["verdict"] = {
            "gatePassed": False,
            "winner": None,
            "action": "Keep the production runtime CPU-only; backend correctness failed.",
        }
        if args.cleanup:
            adb_run(
                args.serial,
                "shell", f"rm -rf {shlex.quote(DEVICE_ROOT)}",
                check=False,
            )
        write_json_atomic(args.output, report)
        print(f"Wrote {args.output}; correctness gate passed: False")
        return 3

    samples: list[dict[str, Any]] = list(resume_samples)
    resumed_sample_count = len(samples)
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
        plan_index = 0
        for variant in variants:
            for prompt in prompts:
                if plan_index < resumed_sample_count:
                    plan_index += 1
                    continue
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
                    args.flash_attention,
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
                write_json_atomic(args.output, report)
                plan_index += 1
    except ProbeError as error:
        measurement_failure = str(error)
    finally:
        if args.cleanup:
            adb_run(
                args.serial,
                "shell", f"rm -rf {shlex.quote(DEVICE_ROOT)}",
                check=False,
            )

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
        samples,
        prompts,
        args.minimum_prompt_ratio,
        args.minimum_decode_ratio,
    )
    write_json_atomic(args.output, report)
    print(f"Wrote {args.output}; gate passed: {report['verdict']['gatePassed']}")
    if args.require_promotion and not report["verdict"]["gatePassed"]:
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
