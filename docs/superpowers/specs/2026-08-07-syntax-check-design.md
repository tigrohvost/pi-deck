# Post-write syntax check — design

Date: 2026-08-07. Goal: increase end-to-end agent speed while keeping or
improving quality.

## Why this lever

Decode-side levers are exhausted on this device: speculative decoding measured
0.96–1.05×, Q6_K lost, MNN is a guarded prototype, and the tool router already
cut the cold prompt by 40%. What remains expensive is a *wasted model turn*: at
15 tok/s a single discovery turn ("run it, read the traceback, realize the last
edit broke the file") costs tens of seconds of prefill and decode.
`docs/agent-upgrade-research.md` lists the post-edit syntax check as coding
addition #2: the model currently learns it broke a file only if it thinks to
run the tests. Suite task T08 is exactly this loop.

## Approaches considered

1. **Post-write syntax check extension (chosen).** After a successful file
   mutation, validate the file's syntax and append the first error to the same
   tool result. Removes the discovery turn entirely; quality strictly improves
   because the model fixes the break while the edit is still in context.
   Milliseconds of subprocess per mutation, zero tokens on the happy path.
2. **Bounded `run_tests` tool.** Larger scope: workspace detection, pytest
   integration, approval-gate questions. Complements rather than replaces #1;
   deferred.
3. **Server-flag A/B (`--flash-attn`, KV q8_0, ubatch).** Gains uncertain and
   mostly RAM-side for a hybrid model whose layers are ~¾ recurrent; device
   benching needs thermal cooldown gates. Deferred.

## Design

One new extension, one purpose: `app/src/main/assets/runtime/pideck-syntax-check.ts`.

- Hook: `pi.on("tool_result", …)`. Reacts only when `toolName` is one of
  `write`, `edit`, `pideck_write`, `pideck_edit`, `pideck_replace_lines`,
  the result is not already an error, and `input.path` is a string.
- Checkers by file extension, everything else ignored:
  - `.py` — `python3 -c "import ast,sys; ast.parse(open(sys.argv[1],'rb').read(), sys.argv[1])" <path>`.
    `ast.parse` instead of `py_compile` so no `__pycache__` appears in the
    workspace; the suite scores runs by diffing the workspace.
  - `.js` / `.mjs` / `.cjs` — `node --check <path>`.
  - `.json` — `JSON.parse` in-process.
- On failure, append a bounded Russian note to the existing content, matching
  the house style of `pideck-context-guard.ts`: the first lines of the error
  plus the instruction to fix and save again. On success, append nothing —
  the happy path costs zero tokens.
- Fail-open: missing interpreter, spawn error, or a 5-second timeout produce
  no note. The check is an accelerator, not a gate; the mutation itself has
  already been approved and applied by the existing paths.
- Bounds: stderr capped (first 8 lines / 1 KiB) before it enters the note.

## Wiring

Same shape as every managed extension: constants and `--extension` argv in
`bridge.py` and `launcher.py`, probe failure `SYNTAX_CHECK_EXTENSION_MISSING`,
entry in `RuntimeAssetBundle.ASSETS`, and the extension list in
`tests/extensions/run_extension_checks.mjs`. Registered after the hashline
extension so anchored reads are already annotated; write/edit results are
small, so ordering relative to the context guard is not load-bearing.

## Testing

Host-side, through Pi's own loader in `run_extension_checks.mjs`:
a broken `.py` write gets the note; a clean write gets none; a broken `.mjs`
and `.json` get notes; no `__pycache__` is created next to the checked file;
an unknown extension is ignored. Java/Python tests only assert list
membership, as they do for the other extensions.

## Out of scope

TypeScript validation (no cheap checker on the phone), `bash`-made file
changes (paths unknown), a `run_tests` tool, any server-flag change.
