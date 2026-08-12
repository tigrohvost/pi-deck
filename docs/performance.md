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

## llama.cpp OpenCL on Adreno 740, measured 2026-08-11

An isolated llama.cpp b10333 build with the embedded Adreno OpenCL kernels
successfully discovered the SM-S918B GPU as `OpenCL 3.0 Adreno(TM) 740` and ran
Qwen3.5 4B without a driver crash. This is experimental hardware for this
backend: Adreno 740 is not in llama.cpp's upstream list of verified devices.

The sweep compared the same armv8.2/dotprod/fp16 candidate binary with zero,
8, 16, then all model layers offloaded. Ratios below use the matching zero-layer
row; the promotion gate remains prompt processing at least 2x, decode at least
0.95x and no crashes.

| Workload | GPU layers | Prompt tok/s | vs CPU | Decode tok/s | vs CPU |
|---|---:|---:|---:|---:|---:|
| 128 prompt / 8 decode | 0 | 18.47 | 1.00x | 3.42 | 1.00x |
| 128 prompt / 8 decode | 8 | 22.54 | 1.22x | 2.88 | 0.84x |
| 128 prompt / 8 decode | 16 | 24.36 | 1.32x | 2.97 | 0.87x |
| 128 prompt / 8 decode | all | **28.19** | **1.53x** | 3.00 | 0.88x |
| 512 prompt / 8 decode | 0 | 27.28 | 1.00x | **7.30** | 1.00x |
| 512 prompt / 8 decode | all | 34.46 | 1.26x | 3.70 | 0.51x |

OpenCL is functional on this phone, but no measured layer setting passes either
half of the performance gate: the best prefill gain is only 1.53x, and every
layer-offload setting slows interactive decoding. The retained command record
does not prove that the zero-layer row used `--device none`; b10333 can still
offload individual operations at `-ngl 0`. The sweep therefore rules out the
tested 8/16/all-layer variants, not op-only offload in isolation. PI//DECK
remains CPU-only because no accelerator promotion has been proven and packages
no experimental OpenCL binary. Raw build metadata, measurements, and this
limitation are in `benchmarks/out/adreno740-opencl-2026-08-11.json`.

### Can CPU and Adreno compute one turn in parallel?

Not usefully with the current OpenCL backend. llama.cpp calls partial layer
offload "CPU+GPU hybrid inference", but transformer and Qwen3.5 recurrent layers
still form a dependency chain: the next split consumes the previous split's
output. `--split-mode row` and `tensor` are explicitly multi-GPU modes, not a
CPU/GPU tensor-parallel mode. The scheduler only enables pipeline parallelism
for more than one accelerator device, ignores CPU for that test, and requires
async compute plus events on every accelerator
([b10333 scheduler source](https://github.com/ggml-org/llama.cpp/blob/08659901c43b51de735740f1cf61bb82fbe0c4e4/src/llama-context.cpp#L422-L447)).

The b10333 OpenCL backend advertises neither async compute nor events, exposes no
async tensor-copy callback, and falls back to synchronized copies at split
boundaries
([OpenCL capabilities](https://github.com/ggml-org/llama.cpp/blob/08659901c43b51de735740f1cf61bb82fbe0c4e4/ggml/src/ggml-opencl/ggml-opencl.cpp#L10770-L10780),
[scheduler copy path](https://github.com/ggml-org/llama.cpp/blob/08659901c43b51de735740f1cf61bb82fbe0c4e4/ggml/src/ggml-backend.cpp#L1715-L1725)).
Shared phone RAM avoids PCIe, but does not remove command submission, device
buffer conversion, or those synchronization points. This matches the measured
shape above: more offload helps wide prefill, while every token of sequential
decode gets slower.

`--override-tensor` is useful mainly for sparse MoE models, where conditional
experts can remain on CPU and shared tensors fit on GPU. Qwen3.5 4B's published
layout instead has 32 layers grouped as three Gated DeltaNet blocks followed by
one attention block, with an ordinary FFN
([official model card](https://huggingface.co/Qwen/Qwen3.5-4B#model-overview)).
Splitting its recurrent weights more finely would add backend boundaries rather
than create independent work. A hypothetical
"Vulkan/OpenCL for prefill, CPU for decode" mode also cannot be implemented in
the app layer: llama.cpp fixes tensor, KV, and recurrent-state placement when a
model context is created. Two live contexts would duplicate roughly 3 GiB of
weights and still need exact KV/recurrent-state transfer. Therefore PI//DECK's
runtime and model catalog are **not** rewritten for phase switching or tensor
overrides.

### Vulkan on Adreno 740: measured and rejected

Vulkan was the one remaining backend worth an isolated measurement. In b10333
it does implement async copies and events
([Vulkan capabilities](https://github.com/ggml-org/llama.cpp/blob/08659901c43b51de735740f1cf61bb82fbe0c4e4/ggml/src/ggml-vulkan/ggml-vulkan.cpp#L17881-L17894))
and has native `SSM_CONV` and `GATED_DELTA_NET` paths used by Qwen3.5. This still
does not make one token CPU/GPU-parallel, but it can reduce the OpenCL boundary
cost and execute the recurrent graph more coherently on a compatible GPU.

`tools/build_llama_vulkan_android.sh` now reproducibly builds pinned llama.cpp
b10333, Vulkan-Headers 1.3.275 and SPIR-V Headers 1.3.275 with NDK r28c. The
upstream candidate was staged by SHA-256 on an SM-S918B running Android 16.
The physically GPU-free controls completed at 19.85/4.00 tok/s for p128/n32
and 17.38/4.00 tok/s for p512/n32. Every GPU-enabled variant — op-only,
8 layers, 16 layers, all layers, and all layers with recurrent/KV state on CPU
— aborted with exit 134 while the Qualcomm driver created
`matmul_q6_k_f32_f16acc_aligned_m`. The raw sweep is in
`benchmarks/out/adreno740-vulkan-2026-08-11.json`.

This was not only an unlucky aligned specialization. Diagnostic, opt-in source
fallbacks moved Q6_K operations to CPU; the driver then rejected Q4_K, followed
by Q8_0. Aligned, unaligned, FP16-accumulation, and FP32-only forms all failed
with `vk::Device::createComputePipeline: ErrorUnknown`. Moving every matrix
weight to CPU exposed the same failure in FlashAttention. After FlashAttention
was disabled, a 32-token prompt completed at 15.88 tok/s, but decode exited 139;
keeping KV and recurrent state on CPU still exited 139 after a 14.91 tok/s
prompt. Qwen3.5 4B contains 124 Q4_K, 16 Q5_K, and 49 Q6_K tensors out of 441,
so removing those kernels also removes the dominant GPU work and creates many
CPU/GPU boundaries.

The diagnostic backend patch was therefore discarded rather than shipped as a
fragile workaround. No Vulkan server correctness or suite-v2 run was warranted:
the candidate did not complete a single decode token. Detailed negative
evidence is in
`benchmarks/out/adreno740-vulkan-diagnostics-2026-08-11.json`; build hashes and
the rejected device status are in
`benchmarks/out/adreno740-vulkan-build-2026-08-11.json`.

The retained probe remains a reproducible re-test command for a materially new
llama.cpp Vulkan backend or phone driver:

```bash
python3 tools/adb_accelerator_probe.py \
  --candidate build/runtime-candidates/vulkan-b10333-adreno740 \
  --device-model /data/local/tmp/pideck-speculative/Qwen_Qwen3.5-4B-Q4_K_M.gguf \
  --output benchmarks/out/adreno740-vulkan-DEVICE-DATE.json
```

It compares a physically GPU-free control (`-dev none`, op offload off),
op-only offload, 8/16/all layers, and full offload with recurrent/KV state kept
on CPU. It verifies every staged binary by SHA-256, uses 3 repetitions at both
128 and 512 prompt tokens, waits for CPU clock recovery before every workload,
records thermal state and crashes, and fails closed if any workload is missing.
Promotion still requires at least 2.0x prefill and 0.95x decode on **both**
prompt sizes. The current device result is an explicit rejection, not a pending
candidate. PI//DECK remains CPU-only, and neither the runtime, model catalog,
nor APK packages the experimental Vulkan binaries.

## Suite-v2 2B/4B boundary, measured 2026-08-11

The executable suite-v2 was used as a targeted tier-boundary probe on the same
SM-S918B. These rows are **not** full model-admission results: Q05 is one scoped
repair, and only the runtime 46 CPU-only build is comparable between models.

| Model / task | Outcome | Tool calls | Total turn | Native prefill | Native decode |
|---|---|---:|---:|---:|---:|
| Qwen3.5 2B / Q05 | fail safely | 6 | 289.07 s | 8,329 tok / 221.73 s = 37.56 tok/s | 714 tok / 65.12 s = 10.97 tok/s |
| Qwen3.5 4B / Q05 | **pass** | **3** | 368.41 s | 3,437 tok / 285.47 s = 12.04 tok/s | 509 tok / 80.54 s = 6.32 tok/s |

The 2B run stayed inside the six-call and file-scope guards and produced the
desired line, but chose the wrong nearby anchor and never obtained a passing
test. The 4B run selected the exact `6:e9` anchor, changed only
`src/counter.py`, and completed `read -> pideck_replace_lines -> run_tests` with
the offline zero-fixture test passing. Raw reports are
`benchmarks/out/suite-v2-qwen3.5-2b-runtime46-q05-2026-08-11.json` and
`benchmarks/out/suite-v2-qwen3.5-4b-runtime46-q05-2026-08-11.json`.

Q06 exposed two runtime faults rather than another fair model score. Runtime 46
left a model-supplied `tests/test_service.py` in both `path` and `expr`, so the
fallback interpreted the path as a `-k` filter and selected zero tests. The next
provider request reused a low-similarity prompt prefix (`LCP=0.171`) after the
tool schema changed. Native logging reached 100% prefill at 1,678 tokens after
456.92 s (3.67 tok/s), then emitted no decode progress until the turn was
manually aborted. The report records 1,421.24 s total and 1,169.26 s from the
last completed tool to the terminal abort:
`benchmarks/out/suite-v2-qwen3.5-4b-runtime46-q06q07-2026-08-11.json`.

Runtime contract 47 fixes both contracts: a test path is removed from `expr`
while a real `-k` or node ID is retained, and `cache_prompt` is enabled only
when every previous message remains an exact prefix and all non-message request
fields, including the dynamic tool schema, are unchanged. Host extension,
protocol, and runtime suites cover those rules. A post-deploy 2B Q06 attempt is
explicitly excluded from quality and speed evidence: the preceding 4B stall
left about 4.3 GiB in device swap, and the first streamed tool request arrived
only at 359.91 s; it was aborted before that tool completed or the cache
transition under test occurred. Its diagnostic report is
`benchmarks/out/suite-v2-qwen3.5-2b-runtime47-q06-2026-08-11.json`.

### What is worth optimizing in 4B?

Yes, but as a bounded **DEEP escalation tier**, not the default agent and not by
GPU offload. Q05 demonstrates a real capability gain over 2B, while its 359.86 s
TTFT and sustained thermal drop from 0.771 to 0.440 big-core headroom make it
unacceptable for routine turns. The next promotion experiment is:

1. keep Qwen3.5 2B as the default and escalate only scoped repairs that need
   stronger anchor choice, multi-file reasoning, or a safe retry after 2B fails;
2. A/B the 4B reasoning budget at 256, 512, and 1,024 tokens on Q05-Q07, then run
   the full suite-v2 before changing the shipped 1,024-token setting;
3. repeat the 4/5/6 decode-thread and batch-thread profile sweep from a cool,
   swap-clean device, with latency, correctness, thermal, and stall gates;
4. retain Q4_K_M unless a Q5 candidate wins the full suite: extra quantization
   quality is not useful if its memory pressure makes the interactive path swap;
5. keep CPU-only inference until a materially newer backend and Qualcomm driver
   pass both the prompt and decode gates above.

## Этап 1: A/B пина llama.cpp — отказ (2026-08-12)

Кандидаты b10369 (текущий официальный релиз) и b10333 (прежний контроль
паритета) против штатного b10092. Метод: `tools/speculative_probe.py`,
установленный debug-APK каждой сборки, один и тот же приватный
Qwen3.5 2B Q4_K_M (SHA сверен с каталогом), decode `5@3-7`, batch `8@0-7`,
ctx 10240, один слот. Каждая серия стартовала при батарее ≤ 30.0 °C без
зарядного тока (Protect battery 80 %); decode — 7 прогонов по 192 токена,
prefill — 4 прогона 2k-токенного промпта, первый сэмпл каждой серии
отброшен. Сырые отчёты: `benchmarks/out/b10092-*.json`,
`benchmarks/out/b10333-*.json`, `benchmarks/out/b10369-*.json`.

| Медиана | b10092 | b10333 | b10369 |
|---|---:|---:|---:|
| Decode, tok/s | **15.48** | 14.26 (0.921) | 13.92 (0.899) |
| Prefill 2k, tok/s | 72.7 | **73.9** (1.017) | 64.5 (0.887) |

Гейт этапа 1 — паритет ±5 % по обеим осям — не пройден ни одним
кандидатом, и это два разных регресса: decode просел между b10092 и
b10333 и дальше к b10369, prefill — только между b10333 и b10369.
Термика исключена: провальные серии шли при равном или большем
big-core headroom, чем базовая (например, decode b10333 целиком при
0.949–1.0 против 0.697–1.0 у b10092). Единственный плюс новых сборок —
RSS ниже на ~290 МБ (2.66 против 2.95 ГБ).

Решение по правилу спеки («нет доказанного выигрыша — изменение не
едет»): **штатный пин остаётся b10092**. Ранняя заметка «контроль b10333
показал паритет» этой серией опровергнута для decode. Последствия для
roadmap: этап 2 закрыт независимо (b10092 сам открывает `lfm2moe`),
этапу 3 официальный релиз не нужен (OpenCL требует кастомной сборки),
поэтому отказ ничего не блокирует. Повторить A/B стоит на будущем пине
после апстрим-фикса; кандидат на bisect для отчёта апстриму — окно
b10092…b10333 по decode на ARM CPU.

## Этап 4, смок на устройстве (2026-08-12)

SM-S918B, debug-сборка ветки perf/stage4-resource-policy, Qwen3.5 2B, без
зарядного тока.

- Таймаут бездействия (5 мин): ядро остановилось само через ~301 с после
  READY; `idle_stop` записан; при следующем resume в консоль легла строка
  «The core stopped on the idle timeout (configured in CORE)» (видна в
  сохранённом транскрипте) и флаг очищен — PASS.
- Честный фон: в свёрнутом состоянии нотификация показывает
  «… · фон: медленно» (скриншот шторки), по возвращении суффикс уходит;
  остановленное ядро хинтом не запускается — PASS.
- Находка гейта: приложение никогда не запрашивало POST_NOTIFICATIONS
  (Android 13+), из-за чего ВСЯ нотификация ядра была невидима
  (`numEnqueuedByApp=13, numPostedByApp=0`). Исправлено в этой ветке:
  разрешение запрашивается при первом старте ядра.
- Ручные смоки с экраном (владелец устройства, та же сессия): таймер не
  остановил ядро во время длинной генерации и сработал только после её
  конца; термо-карточка показала измеренный процент доступной частоты на
  прогретом телефоне; «передышка» напечатала строку ожидания и отправила
  запрос в пределах минуты, в выключенном состоянии отправка мгновенная;
  переключение новых контролов ЯДРА пишет объясняющие строки в консоль —
  все три PASS.
- Попутная воспроизведённая ошибка: строгий Jinja-шаблон Ministral отверг
  сессию с висящим user-ходом («roles must alternate») — известное
  задокументированное поведение модели, новая сессия решает; к этапу 4
  отношения не имеет.
