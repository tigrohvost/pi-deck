"""Compare host-side EventJournal.after() latency across two versions of bridge.py."""

import argparse
import hashlib
import importlib.util
import json
import platform
import statistics
import subprocess
import sys
import tempfile
import timeit
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = Path("app/src/main/assets/runtime")
SOURCE = RUNTIME / "pideck_runtime/bridge.py"
EVENT_COUNT = 9000
CALLS_PER_SAMPLE = 500
BLOCKS = 5
ORDER = ("baseline", "candidate", "candidate", "baseline")


def populate(source, directory):
    directory.mkdir()
    module_path = directory / "bridge.py"
    module_path.write_bytes(source)
    spec = importlib.util.spec_from_file_location("pideck_runtime." + directory.name, module_path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.EVENT_JOURNAL = directory / "events.jsonl"
    module.utc_now = lambda: "2026-01-01T00:00:00Z"
    journal = module.EventJournal("bench")
    for index in range(EVENT_COUNT):
        journal.append("MODEL_OUTPUT_DELTA", "turn", "session", {"delta": "текст", "index": index})
    return journal, hashlib.sha256(module.EVENT_JOURNAL.read_bytes()).hexdigest()


def measure(journals, pending):
    cursor = EVENT_COUNT - pending
    baseline = journals["baseline"].after(cursor, 0)
    gap, events = baseline
    assert not gap and [e["sequence"] for e in events] == list(range(cursor + 1, EVENT_COUNT + 1))
    assert journals["candidate"].after(cursor, 0) == baseline

    timers = {
        name: timeit.Timer("journal.after(cursor, 0)", globals={"journal": journal, "cursor": cursor})
        for name, journal in journals.items()
    }
    for timer in timers.values():
        timer.timeit(number=50)
    samples = []
    for block in range(BLOCKS):
        for name in ORDER:
            seconds = timers[name].timeit(number=CALLS_PER_SAMPLE)
            samples.append({"block": block + 1, "variant": name, "elapsedSeconds": seconds})
    medians = {
        name: statistics.median(s["elapsedSeconds"] for s in samples if s["variant"] == name)
        * 1_000_000 / CALLS_PER_SAMPLE
        for name in journals
    }
    return {
        "pending": pending, "cursor": cursor, "exactPageEqual": True,
        "medianMicrosecondsPerPoll": medians, "medianSpeedup": medians["baseline"] / medians["candidate"],
        "samples": samples,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline_ref")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    commit = subprocess.check_output(
        ["git", "rev-parse", "--verify", "--end-of-options", args.baseline_ref + "^{commit}"],
        cwd=ROOT, text=True,
    ).strip()
    sources = {
        "baseline": subprocess.check_output(["git", "show", f"{commit}:{SOURCE.as_posix()}"], cwd=ROOT),
        "candidate": (ROOT / SOURCE).read_bytes(),
    }
    sys.path.insert(0, str(ROOT / RUNTIME))
    with tempfile.TemporaryDirectory(prefix="pideck-event-journal-") as temp:
        implementations = {name: populate(source, Path(temp) / name) for name, source in sources.items()}
        fixture_sha = implementations["baseline"][1]
        assert fixture_sha == implementations["candidate"][1]
        journals = {name: journal for name, (journal, _) in implementations.items()}
        cases = [measure(journals, pending) for pending in (1, 8, 0)]

    report = {
        "schemaVersion": 1, "createdAt": datetime.now(timezone.utc).isoformat(),
        "scope": "Host EventJournal.after() latency for recently emitted agent output",
        "environment": {"platform": platform.system(), "arch": platform.machine(),
                        "python": platform.python_implementation() + " " + platform.python_version()},
        "baselineCommit": commit,
        "sourceSha256": {name: hashlib.sha256(source).hexdigest() for name, source in sources.items()},
        "fixture": {"eventCount": EVENT_COUNT, "eventType": "MODEL_OUTPUT_DELTA", "journalSha256": fixture_sha},
        "method": {"warmupPollsPerVariantAndCase": 50, "blocks": BLOCKS, "orderPerBlock": ORDER,
                   "pollsPerSample": CALLS_PER_SAMPLE, "timeoutSeconds": 0},
        "cases": cases,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    for case in cases:
        times = case["medianMicrosecondsPerPoll"]
        print(f'{case["pending"]} pending: {times["baseline"]:.2f} -> {times["candidate"]:.2f} us '
              f'({case["medianSpeedup"]:.1f}x)')


if __name__ == "__main__":
    main()
