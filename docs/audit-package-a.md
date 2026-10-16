# Audit Package A — alpha17

Scope: Package A from the [16 October specification](../audit/pi-deck-audit/SPECIFICATION.md#пакет-a-достоверность-и-управление--сначала), as selected by the owner. Packages B–E remain separate iterations. The Bonsai 2 model/catalog changes shipped earlier in alpha16; this package does not change model artifacts, backends, sampling or the UI framework.

| Requirement | Implemented behavior | Regression evidence |
| --- | --- | --- |
| AG-01 | Private structured runner channel; eight distinct verdicts; actual collected/executed counts; operation/workspace/snapshot binding; bounded output and process-group cancellation | Real extension tests for async/generators, missing runner, zero tests, unsupported `-k`, >200 files, stale snapshots, cancellation/deadline/output-cap with surviving-child checks; exact Pi RPC transport |
| AG-02 | Terminal-line parser; DONE is an unverified declaration; only matching host test/inspection evidence verifies a step; revision/scope/operation/expiry-bound approval; interrupted plans do not resume automatically | Actual extension handlers plus bridge/Java tests for three unsupported DONE claims, scoped read evidence, edits after evidence, stale approval and malformed historical proof |
| AG-03, classification only | Pruning uses complete, non-truncated typed zero counts; legacy/partial results and source text containing `no matches` are retained | Regression fixtures import the production navigation/context code |
| APP-01 | Cancellable scheduled health probes; independent control scheduler; operation and server generation fencing; explicit stopped/failed completion; task-bound ACTIVE/IDLE intents | Fake-clock startup/stop tests, cancellation during health, generation replacement, timeout without continuation, exactly-once completion and stale inference lease tests |
| APP-04 | Exact-file manifest; draft upload with bounded retries; download/hash/signature verification before publish; matching files reused; foreign/moved tags rejected; publication retries download the original signed CI artifact | Failure-injected API tests; real verification of alpha16; completed repair and full byte/signature verification of alpha15 |

The new contracts are in `schemas/test-receipt.schema.json`, `schemas/navigation-result.schema.json` and `schemas/plan-ledger.schema.json`. Runtime contract 59 requires the updated helper and bridge. The shared compressed asset envelope is decoded and compared byte-for-byte in JVM tests; the Android installation command remains below its existing 200 KiB ceiling.

The initial nine extension regressions were run against the previous implementation and failed; they now pass against imported production code. `reproduce_findings.py` in the supplied audit package intentionally reproduces historical isolated fragments and is not the regression gate. The CI gate runs `tests/extensions/run_audit_checks.mjs` in addition to the existing extension, RPC, Python, JVM and lint suites.

## Deliberate limits

- Workspace evidence hashing is bounded to 2,048 files / 16 MiB. Symlinks, unreadable trees and exceeded limits produce an unknown snapshot and cannot authorize a verified repair. Passing tests still report their actual run result.
- The offline runner supports synchronous zero-argument functions only. Fixtures, classes, marks, decorators exposing wrapped semantics, async/generator results and pytest selection require pytest. Dependencies are not installed automatically.
- PLAN currently emits `inspection` and `test` evidence. `artifact_check` and `user_acceptance` are reserved contract values, not automatically inferred from model prose. Evidence identifiers and declared paths are visible in the expanded PLAN details.
- Producer streaming for large searches, performance telemetry, broader error contracts and context/retry optimization belong to Package B or later.
- No handset was attached for alpha17 development. Host fake-clock tests do not establish the specification's 250 ms / 2 s / 5 s latency targets on a physical Android device. Instrumentation APK compilation is not device execution; the signed release still needs that separate acceptance run.

## alpha15 repair

The original `pi-deck-signed-release` artifact from Actions run `35267941789` was used. The release tag still resolves to `43faa8b711a8d9ecf1d75b8ef86aa3fb0110ad61`; the APK remains SHA-256 `35daef84c50776cf4c4d5ce5c7f09dc1dff837e97af5719a7eca5d39a202c098` with the pinned production certificate. Missing/starter metadata was replaced, the exact `SOURCE_COMMIT` restored, and `release-manifest.json` added. All twelve required assets were downloaded and verified, including APK signature and embedded native hashes. Unrelated historical assets were preserved.

That historical signed APK contains `compatibility.appVersion = 0.3.0-alpha14`. The verifier documents a narrowly scoped exception only for the exact alpha15 tag/commit; it does not rewrite the signed APK or relax version matching for alpha17.

## Local gate for alpha17

239 JVM tests, 144 runtime Python tests, 75 tooling tests, 2 exact-Pi RPC tests
(including three real test-verdict transports), 16 audit extension regressions,
and the existing 12-extension/10-tool suite passed. Lint: 0 errors / 13 existing
warnings. Debug, instrumentation and unsigned release APKs assembled. The
28-task benchmark contract and workflow/contract syntax checks also passed.
