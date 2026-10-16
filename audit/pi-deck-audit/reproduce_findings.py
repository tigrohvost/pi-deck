#!/usr/bin/env python3
"""Isolated semantic reproductions for pi-deck 43faa8b7; NOT its project test suite.

Requires Python 3.10+ and Node.js (optional for the JavaScript cases).
Uses temporary files and local child processes only. Does not access the network.
Source locations and the limited scope of each reproduction are in SPECIFICATION.md.
"""
from __future__ import annotations

import argparse
import json
import platform
import shutil
import subprocess
import sys
import tempfile
import textwrap
from pathlib import Path

COMMIT = "43faa8b711a8d9ecf1d75b8ef86aa3fb0110ad61"
# The critical execution semantics of ZERO_FIXTURE_RUNNER are retained:
# discover callables, reject parameterized functions, call value(), increment passed.
# Deliberately not a replacement test runner and not imported project code.
FALLBACK_FRAGMENT = r'''
import inspect, pathlib, runpy, sys, traceback
file = pathlib.Path(sys.argv[1])
namespace = runpy.run_path(str(file))
passed = 0
selected = 0
for name, value in sorted(namespace.items()):
    if not name.startswith('test_') or not callable(value):
        continue
    selected += 1
    parameters = inspect.signature(value).parameters
    if parameters:
        raise SystemExit(2)
    try:
        value()
    except BaseException:
        traceback.print_exc()
        raise SystemExit(1)
    passed += 1
if selected == 0:
    raise SystemExit(5)
print(f'{passed} passed in offline zero-fixture fallback')
'''

JS_FRAGMENT = r'''
const { execFile } = require('node:child_process');
const { promisify } = require('node:util');
(async () => {
  // Exact regex from pideck-context-guard.ts, tested against the code_nav output format.
  const emptyRegex = /(?:no matches?|nothing found|ничего не найдено|совпадени\p{L}* не найден)/iu;
  const genuineEmpty = 'No filename or text match. Stop or try one narrower synonym.';
  const realHit = 'src/search.py:12:5: return "no matches"';
  const rows = [
    {id:'CG-EMPTY', scope:'isolated predicate, not the complete pruning pipeline',
     genuineEmptyClassifiedAsEmpty:emptyRegex.test(genuineEmpty),
     reproduced:!emptyRegex.test(genuineEmpty)},
    {id:'CG-HIT', scope:'isolated predicate; pruning also requires age and >=4096 saved bytes',
     realSourceHitClassifiedAsEmpty:emptyRegex.test(realHit),
     reproduced:emptyRegex.test(realHit)},
  ];
  try {
    // Same buffering contract as code_nav; a synthetic producer replaces ripgrep.
    const result = await promisify(execFile)(process.execPath,
      ['-e', 'process.stdout.write("x".repeat(300 * 1024))'],
      {encoding:'utf8', maxBuffer:256*1024, timeout:8000});
    rows.push({id:'NAV-BUFFER', scope:'execFile maxBuffer contract with synthetic producer',
      reproduced:false, unexpectedBytes:result.stdout.length});
  } catch (err) {
    rows.push({id:'NAV-BUFFER', scope:'execFile maxBuffer contract with synthetic producer',
      errorCode:err.code, reproduced:err.code==='ERR_CHILD_PROCESS_STDIO_MAXBUFFER'});
  }
  console.log(JSON.stringify(rows));
})().catch(err => { console.error(err); process.exit(1); });
'''


def run_case(root: Path, kind: str, fixture: str) -> dict:
    marker = root / f"{kind}.body-ran"
    source = root / f"test_{kind}.py"
    source.write_text(fixture.replace("MARKER_PATH", repr(str(marker))), encoding="utf-8")
    result = subprocess.run(
        [sys.executable, "-W", "default", "-c", FALLBACK_FRAGMENT, str(source)],
        capture_output=True, text=True, timeout=10, check=False,
    )
    reproduced = result.returncode == 0 and "1 passed" in result.stdout and not marker.exists()
    # Avoid machine-specific temporary paths in the retained report.
    return {
        "id": f"TEST-{kind.upper()}",
        "scope": "isolated fallback fragment; pytest is not invoked",
        "exitCode": result.returncode,
        "stdout": result.stdout.strip(),
        "stderr": result.stderr.replace(str(root), "<temporary-directory>").strip(),
        "failingTestBodyExecuted": marker.exists(),
        "falseSuccessReproduced": reproduced,
        "reproduced": reproduced,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("reproduction-results.json"))
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="pideck-audit-") as directory:
        root = Path(directory)
        cases = [
            run_case(root, "async", textwrap.dedent('''\
                from pathlib import Path
                async def test_should_fail():
                    Path(MARKER_PATH).write_text('executed')
                    raise AssertionError('This test must not pass')
            ''')),
            run_case(root, "generator", textwrap.dedent('''\
                from pathlib import Path
                def test_should_fail():
                    Path(MARKER_PATH).write_text('executed')
                    raise AssertionError('This test must not pass')
                    yield None
            ''')),
        ]
        node = shutil.which("node")
        node_version = None
        if node:
            node_version = subprocess.run([node, "--version"], capture_output=True,
                                          text=True, timeout=5, check=True).stdout.strip()
            result = subprocess.run([node, "-e", JS_FRAGMENT], capture_output=True,
                                    text=True, timeout=15, check=True)
            cases.extend(json.loads(result.stdout))
        else:
            cases.append({"id": "JS-CASES", "skipped": True, "reason": "Node.js not found"})
    report = {
        "sourceCommit": COMMIT,
        "scope": "isolated reproductions only; not Gradle, Pi protocol, APK, or handset tests",
        "environment": {"python": platform.python_version(), "node": node_version},
        "cases": cases,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if all(c.get("reproduced", False) or c.get("skipped", False) for c in cases) else 1


if __name__ == "__main__":
    raise SystemExit(main())
