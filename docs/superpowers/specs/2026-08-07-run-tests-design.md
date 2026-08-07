# Bounded `run_tests` tool — design

Date: 2026-08-07. Goal: keep cutting wasted model turns; this is coding
addition #3 from `docs/agent-upgrade-research.md`.

## Why

Today tests run through `bash`, and `pideck-context-guard` truncates pytest's
output to a head/tail window — which is precisely where the failure summary is
*not*. The model then spends extra turns re-running with narrower flags or
guessing. Suite tasks T04, T06, T07 and T08 all depend on reading test output
correctly. A tool that returns the verdict plus the first failure verbatim is
both smaller and more useful than the raw stream.

## Approaches considered

1. **Managed `run_tests` extension tool (chosen).** Runs pytest itself with
   output-shaping flags and returns a compact, complete answer in one turn.
2. **Teach the context guard to recognise pytest output.** Fragile parsing of
   arbitrary bash output, and the guard's head/tail contract is deliberately
   generic; rejected.
3. **Prompt guidance only ("run pytest with -x -q").** Costs prompt tokens on
   every turn, still leaves truncation to chance; rejected.

## Design

New extension `app/src/main/assets/runtime/pideck-run-tests.ts`, one tool
`run_tests`.

- Input: optional `path` (file or directory, relative to the workspace),
  optional `expr` (pytest `-k` expression). Both bounded in length.
- Execution: `python3 -m pytest -x -q --tb=short -p no:cacheprovider` plus the
  arguments, `cwd` from Pi's tool context, `PYTHONDONTWRITEBYTECODE=1`,
  abort-signal aware, hard timeout 120 s. `no:cacheprovider` and no bytecode
  keep `.pytest_cache`/`__pycache__` out of the workspace: the suite scores a
  run by diffing the workspace.
- Result: one verdict line (exit code and pytest's own summary tail), then the
  short traceback of the first failure verbatim, total bounded to 4 KiB. A
  timeout or a missing pytest is reported honestly in the result (with bash as
  the named fallback) rather than thrown as a crash.
- Path safety: `path` must resolve inside the workspace; anything else is
  refused. `expr` is passed as a single argv element, never through a shell.

## Profiles

`run_tests` executes arbitrary workspace code (conftest, fixtures), which is
the same trust class as `bash`. It is therefore available only where
unconfirmed execution already is: the `autonomous` profile, as a core tool
(`CORE_TOOLS.autonomous` in the router, the hard `--tools` allowlist in
`bridge.py`/`launcher.py`). `read_only` keeps no execution; `confirm_changes`
keeps its approval-gated `pideck_bash` and gains nothing that bypasses the
gate. Core size goes 5 → 6 active tools on an ordinary Autonomous turn.

## Wiring

Same shape as `pideck-syntax-check.ts`: constants, probe requirement
(`RUN_TESTS_EXTENSION_MISSING`) and `--extension` argv in `bridge.py` and
`launcher.py`, `RuntimeAssetBundle.ASSETS`, the extension and expected-tool
lists in `tests/extensions/run_extension_checks.mjs`, the autonomous `--tools`
string, and the router's `CORE_TOOLS.autonomous`. CI installs pytest before
the extension checks.

## Testing

Through Pi's own loader: a passing workspace returns a one-line verdict; a
failing workspace names the failing test and assertion within the bound; the
workspace is left without `.pytest_cache`/`__pycache__`; a `path` outside the
workspace is refused; a missing pytest reports the fallback instead of
crashing. Router check asserts the new autonomous core.

## Out of scope

Jest/other runners (pytest is what the suite and the device carry), coverage,
parallelism, `confirm_changes` availability.
