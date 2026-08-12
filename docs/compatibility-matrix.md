# Compatibility matrix

| Component | Activated contract | Current evidence |
|---|---|---|
| PI//DECK | 0.3.0-alpha8, Android API 26+, arm64-v8a | JVM/build plus API 36 device validation |
| Termux | package `com.termux`, version >=0.118.0 | signer allowlist; F-Droid 0.118.3 device smoke |
| Termux:API | `com.termux.api` >=0.50.1, same signer | 0.53.0 device smoke; optional wake-lock |
| Node.js | >=22.19.0 | exact Pi package engine requirement; v26.4.0 device smoke |
| Pi | `@earendil-works/pi-coding-agent` 0.82.1 | npm integrity, gitHead, shrinkwrap, exact device CLI |
| Pi RPC | JSONL mode from Pi 0.82.1 | fixture/types plus real `PIDECK_OK` device turn |
| llama.cpp | official Android arm64 b10369 plus isolated Nanbeige fork c6640a1 | archive/source pin + per-ELF SHA-256; flavor-bound foreground adoption and health/models/Jinja/API-key checks |
| Model catalog | schema 2, catalog 2026.08.11.1 | build and strict Java/Python parser validation |
| Qwen3.5 0.8B/2B/4B/9B | experimental | 2B app-private SHA/server/Pi smoke passed; full admission incomplete |
| Ministral 3 3B / Nanbeige4.2 3B | candidate, manual selection only | both downloads and private SHA installs passed; Ministral also passed startup and exact-response smoke, while full suites and Nanbeige inference remain pending |
| Android 16 / API 36 / 12 GiB | Samsung SM-S918B | upgrade migration, Russian/English UI, per-answer exact speed, agent-authored Python and agent-run test, app-owned inference, RPC rebind and exact Pi 0.82.1 smoke |

The runtime probe requires exact Pi, a compatible Node semantic version and the
exact app-owned llama build contract before `READY`. Build-time verification
checks every bundled ELF against `native-runtime.json`; runtime adoption then
requires authenticated exact-model health. Termux does not need a second
`llama-cpp` package. Unknown model IDs fail; recommendation is an explicit UI
choice based on available memory, low-memory state and storage preflight.

Unmeasured 4/6/8/12/16 GiB tiers are not claimed supported. They remain
experimental until pseudonymous reports are committed beneath
`benchmarks/suite-v1/`.
