# Compatibility matrix

| Component | Activated contract | Current evidence |
|---|---|---|
| PI//DECK | 0.3.0-alpha1, Android API 26+ | JVM/build validation |
| Termux | package `com.termux`, version >=0.118.0 | signer allowlist in asset |
| Termux:API | `com.termux.api` >=0.50.1, same signer | optional wake-lock |
| Node.js | >=22.19.0 | exact Pi package engine requirement |
| Pi | `@earendil-works/pi-coding-agent` 0.82.1 | npm integrity, gitHead, shrinkwrap |
| Pi RPC | JSONL mode from Pi 0.82.1 | fixture tests and exact TypeScript types |
| llama.cpp | b10092 only | health/models/Jinja/reasoning/API-key contract |
| Model catalog | schema 2, catalog 2026.07.26.1 | build and parser validation |
| Qwen3.5 0.8B/2B/4B/9B | experimental | pinned bytes/SHA; admission incomplete |
| Android 16 / API 36 / 12 GiB | Samsung SM-S918B | pre-hardening bootstrap smoke; current candidate must be revalidated |

The runtime probe requires exact Pi, a compatible Node semantic version and a
llama build inside the recorded range before `READY`. A binary merely existing
is insufficient. Unknown model IDs fail; recommendation is an explicit UI
choice based on available memory, low-memory state and three-copy storage
preflight.

Unmeasured 4/6/8/12/16 GiB tiers are not claimed supported. They remain
experimental until pseudonymous reports are committed beneath
`benchmarks/suite-v1/`.
