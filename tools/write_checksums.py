#!/usr/bin/env python3
"""Write sorted SHA-256 checksums for explicit release files."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(4 * 1024 * 1024):
            value.update(chunk)
    return value.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("files", type=Path, nargs="+")
    args = parser.parse_args()
    missing = [path for path in args.files if not path.is_file()]
    if missing:
        raise SystemExit("Missing release files: " + ", ".join(map(str, missing)))
    lines = [f"{digest(path)}  {path.name}" for path in sorted(args.files)]
    args.output.write_text("\n".join(lines) + "\n", encoding="ascii")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
