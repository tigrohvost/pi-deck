# ADB performance evidence

Evidence date: 2026-07-27. Device: Samsung SM-S918B, Android API 36,
Snapdragon 8 Gen 2, 12 GiB RAM. Model: Qwen3.5 2B Q4_K_M, SHA-256
`57a1085840f497d764a7fc5d346922dbde961efb54cc792ea81d694fd846a1d8`.
Runtime: official Android arm64 `llama.cpp b10092`, context 10240, reasoning off,
one slot. This is a device smoke, not the 28-task model-admission benchmark.

| Check | Result |
|---|---:|
| Native model cold start | 20.64 s |
| Direct prompt ingestion, 39 tokens | 68.38 tok/s |
| Direct generation, 292 tokens | 16.13 tok/s |
| Authenticated HTTP wall rate | 15.54 tok/s |
| Peak server RSS after Pi + direct probes | 3031 MiB |
| Clean-session Pi RPC result | exact `PIDECK_OK` |
| Foreground process CPU-set | `/top-app`, CPUs `0-7` |
| Managed profile | decode `5@3-7`, batch `8@0-7` |

Pi 0.82.1 keeps a 4096-token safety reserve before issuing a provider request.
The default 2B profile therefore uses a real 10240-token llama context so the
agent's system prompt and 1536-token output budget both fit without advertising
capacity the server did not allocate. A new-session RPC smoke streamed
`P`, `IDE`, `CK`, `_OK` and completed with the exact answer `PIDECK_OK`.

The same model under the Termux UID fell to the background CPU-set when the
PI//DECK Activity was visible. A controlled pre-change run produced about
0.058 tok/s in that state; putting the workload in the foreground restored
roughly 16 tok/s. Owning inference in PI//DECK removes that cross-application
scheduler conflict during normal deck use.

The checked-in `tools/adb_llama_probe.py` runs inside Termux, reads the managed
API key without printing it, sends a bounded authenticated completion and
writes a JSON report to shared Downloads. Server timings come from the
app-private native log and process state from ADB `/proc`.

When PI//DECK itself is backgrounded, Android may move its foreground-service
process from `/top-app` to `/moderate`; generation continues but can slow down.
The published throughput therefore describes the intended interactive state:
the deck visible in the foreground.

## Two ways this device gets slower, measured 2026-07-29

Every figure above is a short probe on a cool, foreground phone. Two effects make
a long session slower than that, and both were large enough to reorder results
before they were controlled for.

**Any other app in the foreground costs the cpuset, not just backgrounding the
deck.** The paragraph above describes what happens when the deck is backgrounded.
The condition is broader: the server sits on `/moderate` whenever *something else*
is the resumed activity. Opening Termux alongside a running agent was enough:

```
topResumedActivity=com.termux/.app.TermuxActivity
/proc/<llama-server>/cpuset  →  /moderate
```

Bringing the deck back to the front returned it to `/top-app` immediately. This
matters for the ordinary case of starting a long agent turn and switching away to
do something else.

**Sustained load halves the machine.** After several hours of near-continuous
inference the big cores were clamped to less than half their nominal clock and
prompt processing collapsed:

| | Cool phone | After hours of inference |
|---|---:|---:|
| Hottest CPU thermal zone | 47–52 °C | **82.8 °C** |
| `cpu7 scaling_max_freq` | 3 360 000 (nominal) | **1 478 400 (44 %)** |
| Prompt processing | 37–60 tok/s | **0.36 tok/s** |

The consequence for any multi-task benchmark is that a run without pauses scores
its later tasks on a slower machine than its earlier ones, so task ordering alone
can decide the outcome. That is not hypothetical: it invalidated a first pass of
the speculative-decoding measurements, and the detail is in
[`speculative-decoding-measurements.md`](speculative-decoding-measurements.md).
Anything comparative on this device should wait for the governor to return the
clock before each case, and record the headroom next to each result — which is
what `tools/speculative_probe.py` does.

Charging over USB while measuring keeps the phone warm, so a cooldown gate can
take several minutes per case and may never reach the full clock at all.

## Long-session replay, measured 2026-08-07

The same SM-S918B and Qwen3.5 2B profile were measured from a stopped core with
an existing Agent session at 78% of its 10240-token window. Android cold-launched
the Activity in **0.996 s** and the native server became ready in **24.21 s**.
The queued one-token answer then took **174.27 s** wall time because llama.cpp
spent **173.14 s** evaluating 7975 prompt tokens at 46.06 tok/s; generation itself
was effectively zero. The server remained in `/top-app`, Android thermal status
was 0, and the application process used about 2.50 GiB RSS, so this run was
context replay rather than scheduler, heat, swapping, or slow decoding.

This is distinct from the warm-prefix numbers above. `cache_prompt` reuses the
common prefix while one server process stays alive, but a cold model start has no
in-memory KV cache to reuse. A prompt queued during that start must therefore pass
the same 75% context choice as a prompt submitted after the core is ready; silently
draining the queue used to bypass that choice.

Bridge startup can briefly report an unknown context size before authoritative
session statistics arrive. Queued dispatch now treats that state as requiring a
choice too, and an open choice dialog exclusively owns the queue; a later
readiness callback cannot drain it behind the dialog.

### Why a disk slot cache is not enabled

The pinned b10092 server saved a synthetic 2048-token slot in 59 ms and restored
its 45,417,072-byte file in 50 ms. The first identical prompt after a server
restart still reported `cache_n=0` and re-evaluated all 2048 tokens in 33.15 s;
an immediate repeat in the same process reported `cache_n=2044` and took 0.29 s.
An 8k-token cache would therefore cost roughly 175 MiB without accelerating the
first Qwen3.5 turn.

Qwen3.5 uses recurrent/hybrid state and the server reports that its context does
not support partial sequence removal. llama.cpp handles that path with in-memory
server checkpoints, while the b10092 slot save/restore endpoint serializes the
sequence state and token list, not those checkpoints. Current upstream has the
same boundary, and `--swa-full` is unsupported by this model. PI//DECK therefore
keeps the proven in-memory cache alive and fails closed on stale service state
instead of presenting disk persistence as an optimization.

## Compact Pi tool surface, measured 2026-08-07

Pi 0.82.1 treats `--tools` as a hard registry allowlist. The bundled router now
keeps that security boundary intact, then calls Pi's documented
`setActiveTools()` before prompt assembly. An ordinary Autonomous turn carries
`read,bash,write,pideck_replace_lines,pideck_load_tools` (later joined by
`run_tests`); explicit web, URL and
weather prompts activate the matching managed group before the first provider
request. A model can load the remaining optional groups only within the same
Android-selected profile.

The exact pinned Pi package and fake OpenAI-compatible endpoint captured the
serialized static request below. The character measure includes system messages
and JSON tool schemas, but excludes a production conversation, so its absolute
value is not a token or latency claim; the before/after ratio isolates the part
this change controls.

| Profile / request | Tools sent | Static payload chars | Change |
|---|---:|---:|---:|
| Confirm, previous full surface | 11 | 12,282 | baseline |
| Confirm, ordinary routed turn | 8 | 9,835 | -19.9% |
| Confirm, explicit web turn | 10 | 11,233 | -8.5% |
| Autonomous, previous full surface | 11 | 13,559 | baseline |
| Autonomous, ordinary routed turn | 5 | 8,013 | **-40.9%** |

The explicit-web protocol test proves `web_search` and `web_fetch` are present
in the first provider request, not after an extra LLM round trip. A separate
static Qwen count put the previous Autonomous prompt at 3,372 tokens and the
four-tool core at 1,894 tokens. The shipped core adds one small loader schema,
so the installed-device A/B used the exact final bundle rather than extrapolating
from that count.

The device A/B used separate temporary session directories, the same
`Reply with exactly OK.` prompt, Autonomous mode, Qwen3.5 2B and identical
sampling. `full` omitted only the router and sent the previous complete tool
surface; `routed` loaded the shipped router. The user session was never opened.
Each primary cold case followed a full LLM restart, so the llama KV cache was
empty while Android's normal file page cache remained representative:

| Installed-device path | Full surface | Routed surface | Result |
|---|---:|---:|---:|
| Cold prompt after separate LLM restarts | 59.644 s | **35.779 s** | **-23.865 s / -40.0% / 1.67x** |
| Immediate exact-prompt cache hit | 8.944 s | 8.908 s | no regression |

An earlier sequential cold pair measured 73.819 s full and 38.914 s routed
(-47.3%, 1.90x). It is supporting evidence only because the routed case ran
second; the separately restarted row above is the comparison used for the
claim. All six valid runs exited 0 with identical bounded stdout size and empty
stderr.

Chat now replaces Pi's coding-agent prompt with a fixed 241-character tool-free
prompt while preserving custom append/replace semantics. It never activates
the router or any tool.

The same device run also exposed a foreground-service handoff race: the monitor
could read the previous native `operationId` before Android delivered the new
start intent and kill a valid cold start in about 0.1 s. A bounded 10-second
identity handoff now tolerates only that transition. After installing the fix,
the native server completed in 28.86 s on the first cold start and in 20.51 s
and 18.52 s on subsequent model restarts; bridge startup took 0.49-1.03 s.
Runtime `probe()` now requires every managed extension, including the router,
before it can return `PIDECK_CORE_READY`.

## MNN 3.5 same-model prototype, measured 2026-08-07

An isolated Android-arm64 MNN 3.5.0 probe ran the official
`taobao-mnn/Qwen3.5-2B-MNN` package on the same SM-S918B without starting or
modifying PI//DECK. CPU with four threads was the only viable backend:

| Same Qwen3.5 2B workload | llama.cpp b10092 | MNN 3.5 CPU | Result |
|---|---:|---:|---:|
| 1024 prompt / 128 decode | 18.7 decode tok/s baseline | 22.9-23.4 decode tok/s | about 1.23x |
| 7975 prompt / 1 decode | 174.27 s wall | 106.37 s wall | **1.64x** |
| Peak RSS on 7975 prompt | about 2.5 GiB app process | 2.02 GiB probe | lower |

This does not pass the proposed cold-8k gate of at most 60 seconds or at least
2x. More importantly, MNN's advertised disk prefix path is not shippable for
this model/package. The official Qwen MNN config omits `layer_nums`; MNN defaults
to 32 while the model has 24, so a valid 24-layer cache is rejected. Adding the
correct count makes `setPrefixCacheFile()` recognize it, but the first read then
tries root paths such as `/B400007F9D11C200.k`, fails to mmap and terminates with
SIGSEGV. OpenCL separately failed to build `linear_attention_buf` and crashed;
Vulkan exceeded 5.4 GiB before the first token and was stopped.

MNN CPU therefore remains a guarded prototype candidate, not a backend cutover.
It needs an upstream cache fix, ten clean restore cycles, the 28-task quality
suite and Android service/session/abort tests before it can replace llama.cpp.

## llama.cpp b10333 control, measured 2026-08-09

The official Android arm64 b10333 build was staged under the shell UID and run
beside, not inside, PI//DECK. Both builds used the same Qwen3.5 2B GGUF, 10240
context, thread/cpu-strict profile, uncached 1099-token prompt and deterministic
128-token output. The first b10092 sample was frequency-unmatched and is excluded:
after cooling, the alternating control was effectively tied.

| Cool isolated run | b10092 | b10333 |
|---|---:|---:|
| Model load | 17.63 s | 18.97 s |
| Prompt processing | **77.76 tok/s** | 76.22 tok/s |
| Decode | 12.68 tok/s | **12.70 tok/s** |
| Request wall | **24.43 s** | 24.65 s |
| AP temperature after | 58.4 C | 56.5 C |

Both processes reported cpuset `/`, zero cached prompt tokens and the exact same
1099/128 token counts. This control provides no performance reason to replace the
shipped b10092 kernel. It also demonstrates why a single thermally unmatched run
is not promotion evidence: the discarded b10092 sample was 35.73 s and would
have falsely implied a 1.45x b10333 win.

## Stall-watchdog build smoke, measured 2026-08-10

The first installed build carrying the stall watchdog (post-merge main,
debug APK) ran the new `tools/adb_agent_benchmark.py` end-to-end harness on
the same SM-S918B: 2 cold, 10 warm and 2 tool samples against Qwen3.5 2B,
Autonomous mode, deck foreground, phone charging. Every sample waited for
full big-core headroom (`cooldown.met=true`, headroom 1.0) before running.

**All 14 samples completed; no turn stalled, no busy state survived.** That
closes the Stage 0 gate of the 2026-08-10 design («0 зависаний в смоках»)
for this harness; the full release-smoke pass repeats it at release time.

| Median | Cold (2) | Warm (10) | Tool (2) |
|---|---:|---:|---:|
| Startup | 26.61 s | — | — |
| TTFT | 64.31 s | **0.557 s** | 15.30 s |
| Total turn | 66.91 s | 1.98 s | 17.01 s |
| Decode | 16.10 tok/s | 21.32 tok/s | 17.13 tok/s |
| Prefill | 39.4 tok/s | 10.3 tok/s | 44.1 tok/s |

The warm path already meets the design's < 3 s TTFT target. The cold path
is the standing problem the later stages attack: 26.6 s of startup plus
session replay before the first visible token. Raw evidence:
`benchmarks/out/stage0-smoke-2026-08-10.json`. Two earlier attempts that
morning failed before sampling — one launched seconds after `adb install -r`
killed the core (the harness deliberately does not ignite it), one lost the
USB link mid-run — and produced no report; they are not part of this
evidence.
