# Compatibility matrix

| Component | Activated contract | Current evidence |
|---|---|---|
| PI//DECK | 0.3.0-alpha1, Android API 26+ | JVM/build plus API 36 device validation |
| Termux | package `com.termux`, version >=0.118.0 | signer allowlist; F-Droid 0.118.3 device smoke |
| Termux:API | `com.termux.api` >=0.50.1, same signer | 0.53.0 device smoke; optional wake-lock |
| Node.js | >=22.19.0 | exact Pi package engine requirement; v26.4.0 device smoke |
| Pi | `@earendil-works/pi-coding-agent` 0.82.1 | npm integrity, gitHead, shrinkwrap, exact device CLI |
| Pi RPC | JSONL mode from Pi 0.82.1 | fixture/types plus real `PIDECK_OK` device turn |
| llama.cpp | b10092 only | Termux `0.0.0-b10092-0`; health/models/Jinja/reasoning/API-key device checks |
| Model catalog | schema 2, catalog 2026.07.26.1 | build and parser validation |
| Qwen3.5 0.8B/2B/4B/9B | experimental | 2B private SHA/server/Pi smoke passed; full admission incomplete |
| Android 16 / API 36 / 12 GiB | Samsung SM-S918B | APK/insets/instrumentation, upgrade migration and Qwen3.5 2B end-to-end smoke |

The runtime probe requires exact Pi, a compatible Node semantic version and a
llama build inside the recorded range before `READY`. A binary merely existing
is insufficient. Termux's tested b10092 binary reports `version: 0 (unknown)`,
so the probe prefers usable executable metadata and otherwise verifies the
exact owning `llama-cpp` package version with `dpkg-query`. Unknown model IDs
fail; recommendation is an explicit UI choice based on available memory,
low-memory state and three-copy storage preflight.

Unmeasured 4/6/8/12/16 GiB tiers are not claimed supported. They remain
experimental until pseudonymous reports are committed beneath
`benchmarks/suite-v1/`.
