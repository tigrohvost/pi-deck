#!/usr/bin/env python3
"""Screen CPU, batch and memory settings using an isolated, pinned Android model.

Each sample restarts only its own server. Fixed-length generation measures throughput,
not answer quality. Repeat a promising configuration in ABBA order before promotion.
Microbatch 128 remains the comparison baseline even after catalog tuning changes.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shlex
import time
import urllib.request

import adb_model_candidate_probe as probe
import adb_runtime_experiments as runtime

REMOTE = "/data/local/tmp/pideck-inference-sweep"
PORT = 18082
CONFIGURATIONS = {
    "baseline": {},
    "decode4": {"decode_threads": 4, "decode_cpus": "4-7"},
    "decode3": {"decode_threads": 3, "decode_cpus": "5-7"},
    "batch5": {"batch_threads": 5, "batch_cpus": "3-7"},
    "relaxed": {"extra": ["--cpu-strict", "0", "--cpu-strict-batch", "0"]},
    "ubatch256": {"extra": ["-ub", "256"]},
    "no-mmap": {"extra": ["--no-mmap"]},
    "poll0": {"extra": ["--poll", "0", "--poll-batch", "0"]},
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial")
    parser.add_argument("--model-id", required=True)
    parser.add_argument("--model-path", required=True)
    parser.add_argument("--executable", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--tokens", type=int, default=128)
    parser.add_argument("--prompt-repeats", type=int, default=32,
                        help="Input length: use fewer repeats for a short, cool confirmation")
    parser.add_argument("--external-cooling", action="store_true")
    parser.add_argument("--order", nargs="+", choices=CONFIGURATIONS,
                        default=[*CONFIGURATIONS, "baseline"])
    args = parser.parse_args()
    if not 16 <= args.tokens <= 512:
        parser.error("--tokens must be between 16 and 512")
    if not 1 <= args.prompt_repeats <= 128:
        parser.error("--prompt-repeats must be between 1 and 128")
    for path in (args.model_path, args.executable):
        if not path.startswith("/data/local/tmp/") or ".." in Path(path).parts:
            parser.error("Use isolated files under /data/local/tmp")
    probe.REMOTE, probe.PORT = REMOTE, PORT
    device = probe.Device(args.serial)
    device.shell("mkdir -p " + REMOTE)
    model = runtime.catalog_model(args.model_id)
    actual = device.shell("sha256sum " + shlex.quote(args.model_path)).split()[0]
    if actual != model["artifact"]["sha256"]:
        raise ValueError("Model SHA-256 does not match the catalog")
    executable_sha = device.shell("sha256sum " + shlex.quote(args.executable)).split()[0]
    # Same input for all samples; no prefix or slot caching between processes.
    prompt = ("Explain how to keep a small software project reliable.\n"
              + "Use clear names, small changes, repeatable tests and measured performance.\n"
              * args.prompt_repeats)
    report = {"startedAt": runtime.utc_now(), "modelId": args.model_id,
              "modelSha256": actual, "executableSha256": executable_sha,
              "device": device.shell("getprop ro.product.model").strip(),
              "execution": "isolated adb shell CPU; fixed-length synthetic generation",
              "externalCooling": args.external_cooling, "tokens": args.tokens,
              "baselineUbatch": 128,
              "promptRepeats": args.prompt_repeats,
              "promptSha256": hashlib.sha256(prompt.encode()).hexdigest(),
              "order": args.order, "samples": [], "status": "running"}

    def save():
        args.output.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.output.with_suffix(".tmp")
        temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        temporary.replace(args.output)

    def battery():
        text = device.shell("dumpsys battery")
        # dumpsys may append historical broadcasts; use only its current leading block.
        for line in text.splitlines():
            if line.strip().startswith("temperature:"):
                return int(line.split(":", 1)[1].strip()) / 10
        return None

    def stop_server():
        pid = device.pid
        device.stop_owned()
        if pid is None:
            return
        for _ in range(50):
            command = device.shell(f"cat /proc/{pid}/cmdline", check=False).split("\0")[0]
            if command != args.executable:
                return
            time.sleep(.1)
        # Only this invocation's exact child may be killed; never match by process name.
        command = device.shell(f"cat /proc/{pid}/cmdline", check=False).split("\0")[0]
        if command == args.executable:
            device.shell(f"kill -KILL {pid}", check=False)
            time.sleep(.2)

    try:
        for index, name in enumerate(args.order):
            before = device.cool()
            temperature = battery()
            settings = dict(CONFIGURATIONS[name])
            settings["extra"] = ["-ub", "128", *settings.get("extra", [])]
            argv = runtime.server_arguments(model, args.model_path, **settings)
            argv[argv.index("--port") + 1] = str(PORT)
            argv += ["-ngl", "0"]
            loaded = device.start_owned(args.executable, argv, f"sweep-{index}-{name}")
            before = device.cool()
            temperature = battery()
            initial = device.status(device.pid)
            body = {"prompt": prompt, "n_predict": args.tokens, "temperature": 0,
                    "seed": 42, "ignore_eos": True, "cache_prompt": False, "stream": False}
            request = urllib.request.Request(f"http://127.0.0.1:{PORT}/completion",
                data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
            start = time.monotonic()
            with urllib.request.urlopen(request, timeout=300) as response:
                result = json.load(response)
            wall = time.monotonic() - start
            final = device.status(device.pid)
            log = device.shell(f"cat {REMOTE}/sweep-{index}-{name}.log")
            affinity_errors = [line for line in log.splitlines() if "failed to set affinity" in line]
            affinity = device.shell(f"for t in /proc/{device.pid}/task/*/status; do "
                                   "sed -n '/^Name:/p; /^Cpus_allowed_list:/p' $t; done")
            after = device.thermal()
            sample = {"configuration": name, "arguments": argv, "pid": device.pid,
                      "affinityErrors": affinity_errors, "loadSeconds": loaded,
                      "wallSeconds": wall, "timings": result.get("timings"),
                      "memoryBeforeKiB": initial, "memoryAfterKiB": final,
                      "thermalBefore": before, "thermalAfter": after,
                      "batteryBeforeC": temperature, "batteryAfterC": battery(),
                      "affinity": affinity, "content": result.get("content"),
                      "completed": result.get("timings", {}).get("predicted_n") == args.tokens,
                      "valid": (result.get("timings", {}).get("predicted_n") == args.tokens
                                and final.get("VmSwap", 0) == 0
                                and not affinity_errors
                                and after["maxFrequencyHeadroom"] >= .95)}
            report["samples"].append(sample)
            save()
            print(name, json.dumps(sample["timings"]), "RSS KiB", final.get("VmRSS"),
                  "valid", sample["valid"], flush=True)
            stop_server()
        report["status"] = "complete"
    except Exception as error:
        report.update(status="failed", error=type(error).__name__ + ": " + str(error))
        raise
    finally:
        stop_server()
        device.adb("forward", "--remove", f"tcp:{PORT}", check=False)
        save()


if __name__ == "__main__":
    main()
