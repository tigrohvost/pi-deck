# Implementation baseline

- Specification: `pi-deck_codex_spec_ru.md` 1.0, 2026-07-26
- Source branch: `main`
- Source commit: `40e477af89781bcd2dd2573cf985238041292658`
- Source remote: `https://github.com/tigrohvost/pi-deck.git`
- Target stack retained: Java 17, Android Views, API 26+, Termux, Python stdlib
- Hardening version: `0.3.0-alpha1`

The original repository was opened in place and its commit recorded before the
hardening changes. The applicable baseline commands are:

```sh
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

The final validation matrix and any environment-specific exceptions are in
`IMPLEMENTATION_REPORT.md`. The implementation commit is the commit containing
that report; this wording avoids an impossible self-referential Git hash.

Verified external contracts:

- Pi package `@earendil-works/pi-coding-agent@0.82.1`, npm SHA-512 integrity and
  bundled `npm-shrinkwrap.json`;
- Pi CLI flags and JSONL RPC/extension contracts from the exact 0.82.1 tarball;
- Node engine requirement `>=22.19.0` from that tarball;
- llama.cpp server health, model-list, Jinja, reasoning and API-key capabilities
  for build `b10092`;
- Termux `RUN_COMMAND` package/signature/version contract recorded in
  `compatibility.json`.
