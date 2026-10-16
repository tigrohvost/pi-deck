# Bonsai 2 4B / 9B: Android integration

Date: 2026-10-16.

PI//DECK runs inference in its Android foreground service, while Pi and tools
run in Termux. The catalog already selects an isolated Prism executable for
Bonsai 2 27B. The two smaller models reuse that path, including authenticated
server adoption, pinned downloads, SHA-256 verification and private storage.
No native runtime replacement or automatic model-selection migration is needed.

## Weights and runtime

The requested [4B MLX](https://huggingface.co/prism-ml/Ternary-Bonsai-2-4B-mlx-2bit)
and [9B MLX](https://huggingface.co/prism-ml/Ternary-Bonsai-2-9B-mlx-2bit) packages
target Apple Silicon. Their official model cards identify the corresponding
[4B GGUF](https://huggingface.co/prism-ml/Ternary-Bonsai-2-4B-gguf) and
[9B GGUF](https://huggingface.co/prism-ml/Ternary-Bonsai-2-9B-gguf) as alternative
packings of the same ternary language weights. PI//DECK uses **PTQ1_0**, the
smallest of those packings, with the existing Android CPU implementation.
This is a format choice, not another quantization of the MLX file.

| Catalog ID | Artifact | Bytes | Available RAM floor | Context |
|---|---|---:|---:|---:|
| `bonsai2-4b` | `Ternary-Bonsai-2-4B-PTQ1_0.gguf` | 949,549,920 | 3072 MiB | 4096 |
| `bonsai2-9b` | `Ternary-Bonsai-2-9B-PTQ1_0.gguf` | 1,997,486,976 | 5120 MiB | 4096 |

The RAM floors and context caps are provisional launch settings, not measured
phone RSS or validated long-context limits. The existing memory-pressure and
storage checks remain in force. Fresh-install storage includes temporary and
private copies plus headroom: about 3.15 GiB / 6.08 GiB respectively.

- 4B revision: `8d6557168168f91ea6697a4a49ed2ef622b53adb`;
  SHA-256: `56711263ec9a9ffeaa74a3a0e58dece8127cf9268404746c721113ca76994d60`.
- 9B revision: `5b2abb02362efbed46bbe204014b02837778b2c2`;
  SHA-256: `3561df7ac7d26a71ffa991ac2db740b10841c3a350aa2676ccd3ff5d428b4e8b`.

Both exact repository revisions contain Apache-2.0 licenses and notices
identifying their Qwen3.5 bases. The publisher does not supply a complete
conversion recipe, so `provenanceStatus` stays `INCOMPLETE` and both profiles
remain manual-only `CANDIDATE` entries.

`prism-842b188` with `pideck-prism1` supplies PTQ1_0 and Hadamard transforms.
The native server receives the embedded Jinja template, thinking off and a
zero reasoning budget, temperature 0.7, top-p 0.8, top-k 20, min-p 0,
presence penalty 1.5, repeat penalty 1.0 and a 64-token penalty window. Mmap
stays enabled; batch/microbatch are 256/128 and prompt cache is 512 MiB.
No speculative drafter or vision projector is loaded. Pi advertises text
input and a 1024-token output budget; its virtual context compensates for Pi's
fixed safety margin while the native context remains 4096.

## Interface changes

The previous Core screen rendered every catalog entry before any settings.
Long action labels could exceed a narrow card, and tapping a downloading
card cancelled the transfer. Verification and installation appeared as
actionable incoming files, without their own progress states.

- The selected model comes first, followed by the recommendation. The compact
  list keeps downloaded, installed, transferring and failed-transfer entries
  visible; **All models** reveals the rest, with the new Bonsai profiles near
  the top. Expansion survives progress refreshes.
- **Selected** identifies the saved choice independently of **active**, which
  requires a running server. An installed, stopped selection offers **Start**.
- Card taps open details: model limitations, RAM and disk requirements, current
  available resources, context, exact attach filename, license and source link.
- Transfer cancellation and other mutations have explicit buttons. Actions
  wrap vertically within narrow cards. Busy operations disable competing
  actions, and verification/private installation display percentage progress.
- Download confirmation includes model limitations and installation space.
  Download failures use the selected UI language.

## Initial host validation

Both complete GGUF files were downloaded with `tools/pin_model.py`; independently
calculated sizes and SHA-256 values matched the publisher's LFS metadata and
`SHA256SUMS`. A Linux x86_64 CPU build of the same Prism commit, with both pinned
patches, loaded each model at context 4096 and passed authenticated health/model
identity, exact `BONSAI_OK`, a Russian arithmetic answer, and an OpenAI `read`
tool call followed by the exact synthetic tool result `BONSAI_TOOL_OK`.
These are native-server API checks, not execution of Pi tools on Android.
The compact [evidence record](../benchmarks/out/bonsai2-4b-9b-host-2026-10-16.json)
includes artifact identities, runtime hashes, settings and individual results.

The catalog passes its JSON schema and the Java/Python parsers. JVM coverage
checks immutable identities, Prism routing, inference arguments, exclusion
from automatic recommendations, and verification/installation action states.
Runtime tests check text-only Pi descriptors and reject an attempt to launch
these models using the Termux stock server.

The instrumented catalog tests cover expansion across refreshes, card taps
versus cancellation, and wrapped actions at 320 dp with the largest in-app
text scale in both languages. The Android test APK is built; these tests need
a connected device to execute.

Final checks passed: **230 JVM tests**, **140 Python runtime tests**, catalog
schema, benchmark-suite contract, `lintDebug` (0 errors, 13 existing-category
warnings), `assembleDebug` and `assembleDebugAndroidTest`. The APK is a debug
build of the working tree, including pre-existing local runtime edits, not a
published release. To reproduce the build checks with JDK 21 and the Android
SDK configured:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
python3 -m unittest discover -s tests/runtime
python3 tools/validate_benchmark.py
```

No handset was connected during that initial validation. The follow-up below
adds partial device evidence; neither run establishes full model admission.

## Handset follow-up: alpha17 and alpha18

On 16 October, both catalog models were downloaded, SHA-256 verified and
installed on a Samsung Galaxy S23 Ultra (SM-S918B), Android 16, with about
10.8 GiB physical RAM. The existing data and sessions were preserved. The
[device evidence record](../benchmarks/out/bonsai2-4b-9b-sm-s918b-2026-10-16.json)
contains exact APK/model identities, per-case timings, verdicts and limits.

| Model / version | Check | Observed result |
| --- | --- | --- |
| 4B / alpha17 | Exact reply; Russian `17 × 23` | `BONSAI_OK`; correct `391`. First visible token at 138.9 / 142.8 s; decode 6.50 / 7.18 tokens/s. |
| 4B / alpha17 | Read a synthetic README | Failed: the router extracted `tool` from “use the read tool” as the filename. Four tool errors; turn aborted after capturing the defect. |
| 9B / alpha17 | Exact reply | `BONSAI_OK`; first visible token at 259.8 s; decode 3.59 tokens/s. |
| 9B / alpha17 | Standalone arithmetic through RPC | Invalid run: the native idle timer stopped the server. The RPC harness bypassed the Android inference lease. No arithmetic answer was produced. |
| 9B / alpha18 | Read README, then `17 × 23`, through the Android composer | One successful `read`; correct first line, but wrong arithmetic: `460`, expected `391`. Total 278.7 s; decode 3.26 tokens/s. |
| 9B / alpha18 | Background for 8 s, then resume during that turn | Same app, native process and active operation; final answer visible, UI returned to READY. |

The read-path defect was fixed in alpha18. A regression against the actual
extension hook failed before the fix and passed afterward. The handset
confirmed the repaired read path on 9B. The combined task still **failed**
overall because of its arithmetic answer; normal turn completion is not a
correctness verdict.

Observed native-process high-water RSS was approximately 1.46 GiB for 4B and
2.34 GiB for 9B. These are partial-run observations, not whole-app memory or
worst-case qualification. Short exact/arithmetic answers contain only 4–6
output tokens; their decode rates are not sustained benchmarks. First-token
latency includes the full Pi prompt/tool context and is not model load time.

The final handset installation is **0.3.0-alpha18**, version code 26, runtime
contract 60. Its existing local release key was retained because the GitHub
certificate cannot update that installation without removing it. All 53
non-signature APK entries match the published production APK byte-for-byte.
The [tag pipeline](https://github.com/tigrohvost/pi-deck/actions/runs/38954724409)
and main pipeline passed. All twelve published release assets were downloaded
and independently checked for identity, hashes and APK signature; the
[alpha18 prerelease](https://github.com/tigrohvost/pi-deck/releases/tag/v0.3.0-alpha18)
is published, not a draft.

The phone disconnected before the 4B alpha18 read rerun, the 9B standalone
arithmetic retry, measured UI cancellation/startup/stop checks, and the device
`run_tests`/`no_tests` scenario. The owner chose to finish with existing results.
The instrumented catalog suite, full 28-task admission suite, and sustained
thermal/long-context checks remain unrun. Both models remain manual-only
`CANDIDATE` entries. The last selected model was 9B; the previous Empero
selection could not be restored after disconnection.
