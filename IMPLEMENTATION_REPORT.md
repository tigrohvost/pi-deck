# PI//DECK 0.3.0-alpha1 implementation report

Date: 2026-07-26

## Commits and scope

- Source branch: `main`
- Source commit: `40e477af89781bcd2dd2573cf985238041292658`
- Source remote: `https://github.com/tigrohvost/pi-deck.git`
- Final commit: the commit containing this report; the immutable hash is the
  resulting `origin/main` after publication. A Git commit cannot contain its own
  hash without changing that hash.
- Specification: `pi-deck_codex_spec_ru.md` 1.0

The implementation preserves the Java/Android Views/Termux stack. It replaces
security-sensitive bootstrap, process, operation and agent paths incrementally
rather than rewriting the application.

## Reported defects

| Defect | Resolution |
|---|---|
| `~/.pideck/llama-server.log: No such file or directory` | The private layout creates `~/.pideck/logs` before any redirect, and the supervisor opens `logs/llama-server.log` itself. |
| Android navigation bar covers controls and status | `DeckView` applies system-bar and display-cutout insets to the content padding on enforced edge-to-edge Android versions. |
| `env: .../usr/bin/pi: No such file or directory` | Pi is content-pinned under `~/.pideck/runtime/bin/pi`; probes and the RPC child launch that exact managed executable instead of assuming `/usr/bin/pi`. |

Device testing also exposed three upgrade-only faults: Termux b10092 reports
`version: 0 (unknown)` from the executable, 0.1.x GGUF files live under the old
`Download/PiDeck/models` source root, and an exact old server can survive on
port 8080. The probe now falls back to the owning `dpkg` version only when the
executable runs but has no usable build metadata. Legacy GGUF is accepted only
as re-hashed migration input to the private store. A legacy server is retired
only when its private PID file, process start time and complete 0.1.x argv all
match; unrelated listeners remain untouched.

Regression coverage exists for runtime layout, exact Pi version, direct RPC
launch, strict process identity, operation races and system-bar-aware layout
code. The current candidate was installed over 0.1.7 on a Samsung SM-S918B
without clearing application, Termux, model or session data. Device validation
covered the upgrade path, instrumentation, insets, private model migration,
managed server/bridge startup, exact Pi CLI and a real local model turn.

## Requirement status

Implemented:

- `OP-001`, `OP-002`, `OP-003`, `OP-004`: canonical operation UUIDs,
  per-operation atomic durable records, explicit state transitions and
  operation-scoped watchdogs;
- `AG-001`, `AG-002`, `AG-003`: no heuristic prompt replay, exact
  PID/group/start-tick/token abort and prompts carried over stdin RPC rather
  than argv;
- `SRV-001`, `MOD-001`, `MOD-002`: supervised exact-identity server, strict
  authenticated health/model match, private atomic GGUF installation and
  unknown-model failure;
- `SEC-001`, `SEC-002`: `READ_ONLY` default, deny-by-default one-time
  confirmation gate, explicit autonomous opt-in and accurate local-inference
  wording;
- `CFG-001`, `CFG-002`, `CAT-001`, `RT-001`: preserved user `AGENTS.md`,
  activated compatibility matrix, one strict model catalog and model-specific
  server/sampling profiles;
- `SYS-001`, `DATA-001`, `PARSE-001`: Termux package/version/signer checks,
  bounded state/history and structured bounded JSON parsing;
- `RPC-001` through `RPC-005`: documented transport decision, authenticated
  loopback bridge, pinned Pi JSONL RPC, approvals, cursor reconnect and
  state reconciliation.

Implemented infrastructure, but not eligible for a completed production claim:

- `UPD-001`: exact Pi package, integrity, shrinkwrap, staged symlink switch and
  rollback are implemented; the update smoke does not yet run a full real-model
  prompt plus abort before activation;
- `SIGN-001`: fail-closed production signing, verified-tag release workflow,
  checksums and CycloneDX generation are implemented; no production key or
  signed tag was supplied, so this run produced an unsigned candidate only;
- `CAT-002`, `MODEL-GATE`: immutable metadata and a non-executing GGUF pinning
  tool are present, but the retained Qwen conversions still have incomplete
  independent provenance and remain `EXPERIMENTAL`;
- `MEM-001`: low-memory and three-copy storage preflight recommendations are
  implemented, but supported RAM tiers cannot be declared without device
  reports;
- `DL-001`: Android `DownloadManager`, durable IDs, restart/retry, byte/SHA
  verification and incoming/private phases are implemented; post-download
  verification is an app thread rather than durable WorkManager work;
- `BENCH-001`: the 28-task fixture, schema and report validator are committed;
  an automated device runner and current device/model reports are still
  outstanding.

## Architectural decisions

- [ADR-001: durable operation store](docs/adr/ADR-001-durable-operation-store.md)
- [ADR-002: process identity](docs/adr/ADR-002-process-identity.md)
- [ADR-003: RPC transport](docs/adr/ADR-003-rpc-transport.md)
- [ADR-004: localhost authentication](docs/adr/ADR-004-localhost-authentication.md)
- [ADR-005: permission gate](docs/adr/ADR-005-permission-gate.md)
- [ADR-006: private GGUF](docs/adr/ADR-006-private-gguf.md)
- [ADR-007: model manifest](docs/adr/ADR-007-model-manifest.md)
- [ADR-008: compatibility and rollback](docs/adr/ADR-008-compatibility-rollback.md)
- [ADR-009: local inference terms](docs/adr/ADR-009-local-inference-terms.md)
- [ADR-010: signing and SBOM](docs/adr/ADR-010-release-signing-sbom.md)

## Test matrix

| Layer | Result |
|---|---|
| Gradle clean/JVM/lint/APKs | Passed: 117 tasks; 50 JVM tests; debug, instrumentation and unsigned release APKs assembled |
| Android lint | Passed: 0 errors, 1 `OldTargetApi` warning (`targetSdk=35` while SDK 36 is installed) |
| Embedded Python runtime | Passed: 21 tests with `ResourceWarning` promoted to error |
| Host tools | Passed: 5 tests |
| Exact Pi RPC protocol | Passed: 1 end-to-end test against `@earendil-works/pi-coding-agent` 0.82.1 and a fake authenticated llama endpoint |
| Permission extension | Passed strict TypeScript type-check against the exact Pi 0.82.1 package types |
| Python bytecode compile | Passed for runtime, tools and tests |
| Benchmark contract | Passed: 28 unique tasks and protected outside-workspace fixture |
| APK inspection | Debug APK verifies with Android Debug certificate; release candidate correctly has no signature |
| Android instrumentation execution | Passed on API 36 hardware: 2/2 process-recreation and stale-result tests |
| Real-device UI/runtime smoke | Passed: upgrade install, exact runtime probe, legacy GGUF migration, server, bridge and Pi `PIDECK_OK` turn |

The local commands are documented in `README.md` and
`docs/release-process.md`. CI repeats the build, runtime, protocol, benchmark
and supply-chain checks.

## Device and model matrix

No hardware tier is promoted to supported by this report.

| Device/tier | Evidence | Status |
|---|---|---|
| Samsung SM-S918B, API 36, 12 GiB | 0.3.0-alpha1 APK/insets/instrumentation; Termux 0.118.3; private Qwen3.5 2B; server/bridge; Pi 0.82.1 turn | Smoke passed; experimental pending full benchmark |
| 4/6/8/12/16 GiB tiers | No current committed thermal/memory reports | Experimental |

| Model | Tier | Catalog status | Admission result |
|---|---|---|---|
| Qwen3.5 0.8B Q4_0 | NANO | `EXPERIMENTAL` | Current device benchmark and complete conversion provenance missing |
| Qwen3.5 2B Q4_K_M | EDGE | `EXPERIMENTAL` | Private SHA/server/Pi device smoke passed; 28-task benchmark and complete conversion provenance missing |
| Qwen3.5 4B Q4_K_M | CORE | `EXPERIMENTAL` | Current hardening-tree benchmark and complete conversion provenance missing |
| Qwen3.5 9B Q4_K_M | MAX | `EXPERIMENTAL` | Current device benchmark and complete conversion provenance missing |

Granite, Ministral and Gemma entries were not fabricated without verified
artifact metadata.

## Benchmark summary

`suite-v1` contains 28 deterministic tasks covering read-only behavior,
confirmed and autonomous changes, tool syntax, spaces/literals, long output,
abort, session recovery and attempted workspace escape. The schema requires
latency, throughput, RSS, crash/OOM, battery and thermal metrics. There is no
0.3.0-alpha1 run report, so no quality, stability, thermal or default-model
claim is made.

## Remaining risks

- The full 28-task device benchmark, thermal/battery report and ten-run
  model/server stability test are still required.
- Production signing and published release assets require repository secrets
  and a GitHub-verified signed `v*` tag.
- Termux native packages are repository-resolved rather than sourced from an
  immutable apt snapshot.
- Diagnostic export and a strict live-size cap for every native log are not
  implemented.
- The Activity still owns presentation wiring and mirrors reducer state through
  presentation booleans such as `busy`; extracting a ViewModel/state reducer
  remains non-security-critical follow-up.
- `DownloadManager` survives process loss, but the separate verification phase
  does not yet have durable worker scheduling.
- Large tool results are bounded to event previews and remain recoverable from
  Pi session history; a dedicated bounded blob-reference/export path is not yet
  implemented.

## Local release artifacts

These are validation artifacts, not a production release:

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `app-debug.apk` | 162311 | `5589d5e704596edb76241bc1d56a3f92ad3787213a87ac470fb3a269dd12ecc9` |
| `app-debug-androidTest.apk` | 1675238 | `7d4905cc1617a9d9438e4d6a50a0f96c85f078ae5b8b5f38d1cefaf7b8d5d7cf` |
| `app-release-unsigned.apk` | 107724 | `878ae842d5c13b536b2cce36463c3a09206128f7ea81493a5e8752c5858f8274` |
| `pi-deck.cdx.json` | 90740 | `8e824eef11cab6069b3406c26b2ff9887418954b95b5891671772973cb7573bd` |
| `models-v2.json` | 8709 | `a7e4366f07d4cab47ffbd597b1ab8e6de21257a2aaa6dc32fa3797ca07788368` |
| `compatibility.json` | 1806 | `19e5dc381f39d90af1e8837c876609ce7f365fa6132048d7ea5a457a84c88d70` |

The generated CycloneDX 1.5 document contains 151 components, 141 dependency
records and verified SHA-1/SHA-512 hashes for the exact Pi 0.82.1 tarball.
Production checksums and the SBOM are published only by the signed-tag release
job.
