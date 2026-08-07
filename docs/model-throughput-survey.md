# Model throughput survey: what fits this phone above 10 tok/s

Evidence date: 2026-07-27. Device: Samsung SM-S918B, Snapdragon 8 Gen 2, 11.3 GiB
RAM. Runtime: bundled Android arm64 `llama.cpp b10092` (`3ce7da2c8`), decode
pinned `5@3-7`, prompt batch `8@0-7`, one slot.

This survey answers a selection question — which further models could serve the
deck's agent at interactive speed — and is separate from
[`performance.md`](performance.md), which certifies the shipped 2B profile.

## Method

Each measurement is a non-streaming `/v1/chat/completions` call with a fixed
prompt, `temperature: 0`, `seed: 42`, `max_tokens: 128`, reading
`timings.predicted_per_second` back from the server.

Two things had to be controlled before any number meant anything:

**The first call after a load measures flash, not the model.** Weights are
mmapped, so the first request faults them in. Qwen3.5 0.8B reported 1.52 tok/s
on its first call and 52 tok/s on its second. Every figure below is from a
warmed server, first call discarded.

**A backgrounded deck is not the same machine.** The server only reaches the
`/top-app` cpuset while the activity is visible; with the deck backgrounded the
same request fell to roughly 1.5 tok/s. All app-run figures are foreground.

## Measured

Run through the deck's own server, so these are the production path:

| Model | Quant | Weights | ctx | Decode | Server RSS |
|---|---|---:|---:|---:|---:|
| Qwen3.5 0.8B | Q4_0 | 0.52 GiB | 4096 | **52.3 tok/s** | 1.29 GB |
| Qwen3.5 2B | Q4_K_M | 1.30 GiB | 10240 | **18.7 tok/s** | 1.38 GB |
| Qwen3.5 4B | Q4_K_M | 2.81 GiB | 8192 | **7.4 tok/s** | 5.03 GB |

The 0.8B figure is the median of five warm runs (spread 36–53 tok/s; it emits
only 21 tokens before stopping, so its sample is the noisiest). The 2B and 4B
figures are stable across runs to within 0.6 tok/s.

The 2B number is higher than the 16.13 tok/s in `performance.md` because that
run generated 292 tokens against a deeper KV cache. Decode rate falls as context
fills, so these short-run numbers are an optimistic bound, not a session average.

## The envelope

Decode is bound by reading the weights once per token. Fitting the two K-quant
points gives `tok/s ≈ 25.6 / GiB^1.2`, and the exponent above 1 is the expected
penalty for worse locality as the model grows. Solving for the target:

> **10 tok/s ⇔ roughly 2.2 GiB of weights**

That is the whole answer to the sizing question, and it rules out more than it
admits. Qwen3.5 4B, already installed here, measures 7.4 tok/s — the deck's
largest current model is below the bar on its own hardware.

Quantisation format moves the bar. Effective read bandwidth was 27.4 GiB/s for
the Q4_0 0.8B against 24.3 and 20.8 GiB/s for the K-quant 2B and 4B, consistent
with Q4_0's ARM repacking path being cheaper per byte. A Q4_0 build of the same
weights should sit slightly above the curve fitted here.

**RAM binds second, and harder than expected.** Qwen3.5 4B at ctx 8192 reached
5.03 GB RSS, and the deck's own guard refused it first: expected peak 5.5 GB
against 3.9 GB available. Weights near 2.2 GiB are therefore also close to the
memory ceiling once a real context is allocated, so the two limits arrive
together rather than one after the other.

## Follow-up, 2026-07-29

Two things this survey left open were measured on the same device, and both answers
are in [`speculative-decoding-measurements.md`](speculative-decoding-measurements.md):

- Speculative decoding does not move this envelope. MTP runs at 0.96× and the
  model-free `ngram-mod` at 1.02–1.05×, so the sizing rule above stands unmodified.
- The figures here were taken without controlling for thermal throttling. A phone
  under sustained load drops to 47 % of its big-core clock, which is enough to
  reorder any comparison whose variants run in sequence. Treat single-pass numbers
  in this document as indicative and re-take anything decisive with
  `tools/speculative_probe.py`, which waits for the clock to recover and records the
  headroom next to every sample.

**The fitted envelope predicts ranking well and absolute speed badly.** Measured
under the deck's own sampling with 192-token answers and a cold start per variant:

| Qwen3.5 2B | Weights | `25.6 / GiB^1.2` | Measured | Ratio |
|---|---:|---:|---:|---:|
| Q4_K_M | 1.24 GiB | 19.8 | **13.06** | 0.66 |
| Q6_K | 1.52 GiB | 15.5 | **9.66** | 0.62 |

The curve was fitted on short, favourable runs, so it overstates sustained
throughput by roughly a third while getting the Q6/Q4 ratio nearly right (0.78
predicted against 0.74 measured). Use it to choose between candidates, not to decide
whether a candidate clears a throughput bar.

That settles the "spend the headroom on better weights" idea: Q6_K costs 26 % of the
decode rate and 575 MB more resident (3.64 GB against 3.07 GB) to buy quantisation
fidelity nobody has measured a quality gain from on this model. Q4_K_M stays.

## Harness note

Models outside the pinned manifest were to be measured by running the same
`libpideck_llama_server.so` directly from `adb shell`, which works and reports
the same build. Cross-checking it on Qwen3.5 4B gave 6.1 tok/s against the app's
7.4 tok/s, measured while two downloads were competing for flash and CPU. The
standalone harness is therefore a lower bound and its figures are not directly
comparable to app-run ones without repeating the cross-check on an idle device.

## Candidates not yet measured

Selected against the 2.2 GiB envelope, tool-calling support and licence:

| Model | Quant | Weights | Predicted | Note |
|---|---|---:|---:|---|
| SmolLM3-3B | Q4_K_M | 1.78 GiB | ~12.5 tok/s | Apache-2.0, GGUF by `ggml-org`, 128K ctx |
| Gemma 4 E2B | QAT q4_0 | 3.12 GiB | 6–15 tok/s | Apache-2.0, native tool use, 128K ctx |
| LFM2-8B-A1B | Q4_0 | 4.41 GiB | ~20 tok/s | MoE, 1.5B active; LFM Open v1.0 licence |

Gemma 4 E2B is the one worth measuring rather than predicting. It carries 5.1B
total parameters but 2.3B effective, using Per-Layer Embeddings; whether
llama.cpp reads only the effective share per token decides between roughly 15
and roughly 6 tok/s. Published mobile figures do not settle it: the 14.04 tok/s
quoted for a Galaxy S26+ was measured with MTP drafting and no baseline, and the
10–25 tok/s quoted for Pixel-class devices is LiteRT-LM on GPU/NPU rather than
llama.cpp on CPU.

LFM2-8B-A1B is the most interesting shape — decode cost follows its 1.5B active
parameters while quality follows the full 8.3B — but 4.41 GiB of weights sits
above what the guard allowed for the 4B, and a MoE that cannot stay resident
pages experts from flash on every token. It also ships under LFM Open v1.0
rather than a plain SPDX licence, which the model manifest would have to account
for.

Qwen3.5-35B-A3B and Qwen3-Coder-Next 80B-A3B pass on active parameters and fail
on total size; no small Qwen3.5-Coder exists, the Small series being
0.8B/2B/4B/9B.

## Bonsai 27B, measured 2026-07-30

The first candidate measured rather than predicted, and the first one whose
answer is a refusal.

`prism-ml/Bonsai-27B-gguf` packs Qwen3.6-27B into 3.54 GiB by spending one sign
bit per weight with an FP16 scale shared across each group of 128 — 1.125 bits
per weight. The size alone puts it inside the 4B's measured footprint, which is
what made it worth a run: 27B of parameters for less resident memory than the
9B needs.

Same harness as above (`tools/speculative_probe.py --variant baseline`), ctx
4096, decode pinned `5@3-7`, batch `8@0-7`, one slot, first sample discarded.

| | Bonsai 27B Q1_0 | Qwen3.5 4B Q4_K_M |
|---|---:|---:|
| Weights | 3.54 GiB | 2.81 GiB |
| Resident | 4.00 GiB | 5.03 GB |
| Decode, warm median | **1.16 tok/s** | 7.4 tok/s |
| Prompt eval | 0.29–1.59 tok/s | 1.5–8.1 tok/s |
| Load, cold flash | 106 s | 35 s |

Three things the run settles:

**It runs on the pinned build.** The vendor ships a llama.cpp fork "including
the Q1_0_g128 hybrid-attention kernels", so the working assumption was that
b10092 would refuse the file. It loads it, and the answers are coherent English
prose and Python. Upstream therefore has a Q1_0 path; it is just not the one the
model was built for.

**Memory was never the binding limit.** Resident peaked at 4.00 GiB with
`MemAvailable` still near 5 GiB throughout, and no swapping. This is the first
model in the survey where the RAM ceiling stayed out of the way.

**The envelope does not describe it.** `25.6 / GiB^1.2` predicts 5.6 tok/s for
3.54 GiB, and the K-quant models land at 0.62–0.66× of their prediction.
Bonsai lands at 0.21×. Effective read bandwidth works out to 4.1 GiB/s against
20.8–27.4 GiB/s for the Q4 and Q6 models, so the cost is not the bytes — it is
what upstream does with them per byte. A fitted curve over K-quants says nothing
about a quantisation whose kernel is missing.

Thermals compound it rather than cause it. The big cores started at their full
3.36 GHz and 46.9 °C and were down to 0.47 headroom by the second sample; the
warm-up sample, taken at 0.59 headroom, was the fastest of the four at 1.37
tok/s. Even extrapolated to an unthrottled clock the model stays near 2 tok/s.

One further cost the number hides: the embedded template reasons by default, and
110 tokens of budget were spent entirely inside the reasoning block on all three
test prompts, with `content` still empty when the budget ran out. At 1.2 tok/s
the first user-visible token is minutes away. The catalog entry therefore pins
`reasoningMode: "off"`, which was checked on the device rather than assumed:
under the entry's own flags the same prompt came back with an empty
`reasoning_content` and a finished answer in 10 tokens.

That answer also shows where 1.125 bits per weight is spent. Asked for the
capital of Australia in Russian, it returned `Столицой Австралии является
Канberra` — right fact, wrong case ending, and the proper noun half-transliterated
back into Latin. The English prose and Python in the other two prompts were
clean. One sample proves nothing on its own, but it is the kind of damage a
1-bit packing is expected to do first, and it lands on this deck's default UI
language.

It is in the catalog as `bonsai-27b` with status `CANDIDATE`, which lists it and
lets it be picked by hand but keeps it out of `ModelCatalog.recommend`. The
recommendation weighs memory, and memory is precisely the axis on which this
model looks good.
