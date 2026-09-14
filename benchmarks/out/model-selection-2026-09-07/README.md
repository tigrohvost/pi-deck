# SM-S918B model-selection evidence, 2026-09-07

[Russian assessment](../../../docs/model-selection-2026-09-07.md).

These are research artifacts. Production model admission, runtime selection and the APK were not changed. Scripts contain paths for the audited handset and `/tmp/pideck-model-audit-20260907`; review these paths before reproducing. Private bridge credentials and user conversations are not included.

| Artifact | Meaning |
|---|---|
| `qad-suite-readonly.json` | Installed app, real Pi and tools, five selected Suite-v2 cases |
| `qad-coding.json` | Diagnostic in `confirm_changes`; not canonical writable suite admission |
| `qad-autonomous-coding.json` | Real Pi writable Suite-v2 cases with a temporary 20-minute grant; Q05 pass, Q06 fail |
| `qad-native-tools.json` | Compact synthetic tools; `inferenceSeconds` excludes cooldown; legacy cache caveat in `auditNote` |
| `qwen4-native-both.json` | Two eligible measured decode samples; index 2 has competing file I/O and is excluded |
| `k2-native-both.json` | Initial configuration timeout, no valid speed result |
| `k2-diagnostic.json` | 32-token configuration diagnostic, not a throughput series |
| `k2-i8mm-native-both.json` | Complete warmup plus three valid 192-token samples; median 9.2286 tok/s |
| `k2-i8mm-native-tools.json` | Three compact semantic passes, with leaking IFM reasoning close tags |
| `k2-template-alignment.json` | Changing server reasoning flags alone did not resolve the tag leak |
| `*.verified.json` | Artifact revision, exact bytes, SHA-256 verified on device |
| `k2-parser-fixed.json` | Patched Android binary: single read, clean Russian final, no IFM tag; short-request rates 7.35/8.52 tok/s |
| `restoration.json` | Final app/profile/model/user-session restoration verification |
| `k2-reasoning-effort.patch` | Experimental parser correction; build/device verification recorded separately |
| `build-k2-i8mm.sh` | Android CPU build invocation; requires vendor source at the pinned commit |
| `native_compare.py` | Final research harness, with cache reset at each new case |
| `autonomous_suite.py` | Real bridge fixture runner; stops only after exceeding a suite tool-call limit |

Runtime under test: vendor `MBZUAI-IFM/llama.cpp`, branch `model/K2Horizon`, commit `35999d101cf2233fc54f09c3c8d599da7303ce02`. The parser patch is an additional local diff, not part of that upstream commit. The vendor build fetched a UI artifact labelled `latest`; `--no-webui` disables serving it, but a production build still needs that dependency pinned or removed.

A compact tool pass is not a full PI//DECK admission. A reported `pass` checks the explicitly encoded predicates only; e.g. answer language and leaked internal tags require separate inspection. Raw decode speed includes the model's generated reasoning tokens and is not time to the first visible word.
