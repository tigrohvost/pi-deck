#!/usr/bin/env python3
"""Run a bounded authenticated llama-server probe from inside Termux."""

from __future__ import annotations

import argparse
import json
import time
import urllib.request
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--model", default="qwen3.5-2b")
    parser.add_argument("--max-tokens", type=int, default=128)
    parser.add_argument("--delay", type=float, default=0)
    args = parser.parse_args()
    if not 1 <= args.max_tokens <= 512:
        raise SystemExit("--max-tokens must be between 1 and 512")
    if not 0 <= args.delay <= 30:
        raise SystemExit("--delay must be between 0 and 30 seconds")

    key_file = Path.home() / ".pideck" / "server" / "api-key"
    api_key = key_file.read_text(encoding="ascii").strip()
    if not 32 <= len(api_key) <= 128:
        raise SystemExit("managed API key is unavailable")

    payload = {
        "model": args.model,
        "messages": [
            {
                "role": "user",
                "content": (
                    "Continue with exactly 100 increasing integers separated by spaces. "
                    "Output numbers only: 1 2 3 4 5"
                ),
            }
        ],
        "max_tokens": args.max_tokens,
        "temperature": 0,
        "stream": False,
        "seed": 42,
    }
    request = urllib.request.Request(
        f"http://127.0.0.1:{args.port}/v1/chat/completions",
        data=json.dumps(payload, separators=(",", ":")).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    if args.delay:
        time.sleep(args.delay)
    started = time.monotonic()
    with urllib.request.urlopen(request, timeout=180) as response:
        raw = response.read(512 * 1024)
    elapsed = time.monotonic() - started
    result = json.loads(raw.decode("utf-8"))
    usage = result.get("usage") or {}
    choices = result.get("choices") or []
    first = choices[0] if choices else {}
    content = (first.get("message") or {}).get("content", "")
    completion_tokens = int(usage.get("completion_tokens") or 0)
    report = {
        "schemaVersion": 1,
        "model": result.get("model"),
        "httpSeconds": round(elapsed, 3),
        "promptTokens": int(usage.get("prompt_tokens") or 0),
        "completionTokens": completion_tokens,
        "wallTokensPerSecond": (
            round(completion_tokens / elapsed, 3) if elapsed > 0 else None
        ),
        "finishReason": first.get("finish_reason"),
        "content": content[:4096],
    }
    encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(encoded, encoding="utf-8")
    else:
        print(encoded, end="")


if __name__ == "__main__":
    main()
