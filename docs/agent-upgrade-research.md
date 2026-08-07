# Agent upgrade research: a faster-than-15 tok/s model, and the harness around it

Research date: 2026-07-29. Device of record: Samsung SM-S918B, Snapdragon 8 Gen 2,
11.3 GiB RAM, bundled Android arm64 `llama.cpp b10092`, one slot, foreground.

Two questions were asked and they are answered separately below:

1. Which model gives the best agentic and coding ability while decoding at **12–15
   tok/s or faster** on this phone.

   > **The bar moved after this was written.** Asked to choose in practice, the owner
   > of the device accepted **7 tok/s if the quality is better**, which admits
   > Qwen3.5-4B — rejected throughout the analysis below purely on speed. On its
   > published numbers that is the right trade: BFCL-V4 50.3 against the 2B's 43.6,
   > TAU2 79.9 against 48.8, and LiveCodeBench v6 55.8 where the 2B card reports no
   > coding benchmark at all. 4B is what the phone now runs. Read the speed
   > rejections below as answers to the original 12–15 bar, not as advice under the
   > relaxed one.
2. What to add to the harness around it so the agent searches the web and edits
   code better.

Everything measured on-device comes from [`model-throughput-survey.md`](model-throughput-survey.md)
and [`performance.md`](performance.md). Everything predicted is marked as such and
uses the envelope fitted in the survey, `tok/s ≈ 25.6 / GiB^1.2`. Benchmark scores
are quoted from their publishers and are not independent measurements.

---

## Part 1 — the model

### The constraint, restated at the new bar

The survey solved the envelope for 10 tok/s and got ~2.2 GiB of weights. Re-solving
for the bar in this question:

| Target decode | Implied weights |
|---:|---:|
| 12 tok/s | ~1.88 GiB |
| 15 tok/s | ~1.56 GiB |
| 18.7 tok/s (shipped 2B) | 1.30 GiB (measured) |

So without changing anything else, "12–15 tok/s" means **1.5–1.9 GiB of weights**,
which is a band the current 2B sits below and the current 4B sits far above. The
interesting move is therefore not picking a different model at the same settings —
it is changing what a token costs.

### What changed since the survey: MTP is already in the shipped binary

Qwen3.5 was trained with multi-token-prediction heads, llama.cpp merged MTP
speculative decoding on 2026-05-16 (PR #22673, flag `--spec-type draft-mtp`), and
Unsloth publishes MTP-converted GGUFs for the whole Qwen3.5 small series —
`Qwen3.5-0.8B/2B/4B/9B-MTP-GGUF`. Publisher's claim for MTP is **~1.5–2× faster
generation**.

This was verified against the runtime this app actually ships rather than taken on
trust. Symbols and help strings inside the vendored `b10092` Android arm64 build:

```
adding speculative implementation 'draft-mtp'
adding speculative implementation 'ngram-mod'
adding speculative implementation 'ngram-simple'
--spec-type  --spec-draft-n-max  --spec-draft-n-min
--spec-ngram-mod-n-match  --spec-ngram-simple-size-n
--cache-type-k  --cache-type-v  --flash-attn  --cache-reuse
--grammar  --grammar-file
llama_model_n_layer_nextn
```

A first reading of `ModelSpec.MANAGED_SERVER_FLAGS` suggested a manifest entry could
switch MTP on with no Java change. That was backwards, and the correction matters:
`MANAGED_SERVER_FLAGS` is a **deny** list. A flag named there is reserved for the app
to build and is *rejected* if a catalog entry tries to pass it through `serverArgs`.
`--spec-type`, `--spec-draft-n-min` and `--spec-draft-n-max` were already on it, and
`ModelCatalogTest` asserted that no profile ever emits them. So enabling speculation
needed a real change to the argument builder, which this branch makes: a
`runtime.speculative` block in the catalog, parsed and emitted by `ModelSpec`.

The inverse is also true, and is the cheaper half. `--flash-attn`, `--cache-type-k/v`
and `--cache-reuse` are *not* managed, so a catalog entry can already pass them
through `serverArgs` with no code change at all.

Two documented MTP limits, both harmless here: `-np > 1` is unsupported (the deck
runs one slot) and `--mmproj` is unsupported (the deck runs text-only).

The honest counter-evidence: a public 19-configuration benchmark on an RTX 3090
found **every** speculative mode net-negative for Qwen3.6-35B-A3B, including
configurations with 100 % draft acceptance, and attributed it to MoE expert
saturation. That result is about a small-active MoE on a GPU. A dense-ish model
decoding on a bandwidth-bound ARM CPU is the case where batched verification should
pay, because the weights are read once per verified batch instead of once per token.
"Should" is not "does" — this is the first thing to measure, not the first thing to
ship.

> **Measured, and the answer is that neither mode pays.** On this device MTP runs at
> 0.96× baseline while holding ~200 MB more resident, and the model-free `ngram-mod`
> returns 1.05× on file editing — the workload it should be best at — because it
> only accepts about half of what it drafts. Two earlier passes reported much larger
> effects in both directions; both were measurement faults, one thermal and one from
> reusing a single prompt. The full account is in
> [`speculative-decoding-measurements.md`](speculative-decoding-measurements.md);
> read it before acting on anything in this section.

### Candidates, scored against both criteria

Quality figures are publisher benchmarks. Speed figures are measured where the
survey measured them and predicted from the envelope otherwise.

| Model | Weights (Q4) | Decode | BFCL-V4 | TAU2 | Verdict |
|---|---:|---:|---:|---:|---|
| Qwen3.5-2B (shipped) | 1.30 GiB | **18.7 measured** | 43.6 | 48.8 | baseline |
| Qwen3.5-2B @ Q5_K_M | 1.37 GiB | ~17.6 predicted | — | — | free quality, still over bar |
| Qwen3.5-2B @ Q6_K | 1.52 GiB | ~15.5 predicted | — | — | free quality, at bar |
| Qwen3.5-4B | 2.81 GiB | **7.4 measured** | **50.3** | **79.9** | fails bar as-is |
| Qwen3.5-4B + MTP | 2.81 GiB | 11–15 predicted | 50.3 | 79.9 | the real target |
| LFM2.5-1.2B | ~0.75 GiB | 30–45 predicted | 49 (BFCLv3) | — | fast second seat |
| SmolLM3-3B | 1.78 GiB | ~12.8 predicted | — | — | rejected, see below |
| Phi-4-mini 3.8B | ~2.3 GiB | ~9.4 predicted | — | — | rejected on speed |
| Granite 4.1 3B | ~1.9 GiB | ~11.9 predicted | — | — | rejected on judgment |
| Gemma 4 E2B | 3.12 GiB | ~10.6 reported | — | — | unresolved |
| LFM2.5-8B-A1B | ~4.4 GiB | fast in theory | — | — | blocked on RAM |

Qwen3.5-4B also reports LiveCodeBench v6 **55.8**, the only direct coding number
available across this set. Qwen3.5-2B's card publishes no coding benchmark at all.

The 2B → 4B jump is the largest quality step available: **+6.7 BFCL-V4 and +31.1
TAU2**. TAU2 is multi-turn tool use, which is exactly what a deck agent does, and it
is where the 2B is weakest in absolute terms (48.8).

Rejections, with reasons rather than vibes:

- **SmolLM3-3B** clears the speed bar on paper but scored **0.710** on an
  independent 21-model tool-calling bench, below `qwen2.5:1.5b` (0.840) and far
  below the 0.880 leaders, with the slowest latency of any 3B tested. It buys
  0.5 GiB of weights for worse judgment.
- **Granite 4.1 3B** scored 0.670 on the same bench.
- **Phi-4-mini** scores 0.880 there and is genuinely good at tool calls, but 2.3 GiB
  of weights lands it under 10 tok/s on this device. Third-party mobile figures of
  16–19 tok/s for it are quoted for "mid-range Android" with no methodology and
  conflict with everything measured here; they were not used.
- **Gemma 4 E2B** remains the survey's open question. Per-Layer Embeddings mean 5.1 B
  total against ~2.3 B effective, and whether llama.cpp reads only the effective share
  decides between usable and not. One third-party mobile CPU report says 10.59 tok/s.
  Its 3.12 GiB of weights is also the wrong side of the RAM guard. Measure before
  believing either direction.
- **LFM2.5-8B-A1B** is the most attractive shape on paper — 1 B active, 128 K
  context — and is still blocked by the same thing that blocked LFM2-8B-A1B: 4.4 GiB
  of weights against a guard that already refused a 2.81 GiB model.

### RAM is still the binding constraint, and KV quantisation may not fix it

The 4B measured 5.03 GB RSS at ctx 8192 and the deck's own guard refused it: 5.5 GB
expected peak against 3.9 GB available. Weights are 2.81 GiB of that, so ~2.2 GB is
everything else.

The obvious lever is `--cache-type-k q8_0 --cache-type-v q8_0` with `--flash-attn on`,
which halves KV memory at effectively no quality cost and keeps the fused attention
path as long as K and V use the *same* type. The caveat specific to this model
family: Qwen3.5 is a hybrid, roughly 3 Gated-DeltaNet blocks per 1 full-attention
block, so only about a quarter of its layers hold a conventional KV cache at all.
Much of that 2.2 GB is compute buffers and recurrent state, which KV quantisation
does not touch. Expect this to help and do not plan on it rescuing the 4B by itself.
MTP also adds the next-N head weights and a second compute path, so it costs a little
RAM to buy speed.

### Recommendation

**Tier 1, do this first, no new model required.** Take the shipped Qwen3.5-2B to
`Qwen3.5-2B-MTP-GGUF` and turn on `--spec-type draft-mtp --spec-draft-n-max 4`. Same
architecture, same tokeniser, same behaviour, same manifest shape; the only new thing
is the prediction heads. If MTP delivers even 1.5× on this CPU, decode goes from 18.7
to ~28 tok/s, and that headroom is the budget that pays for everything in Tier 2.
This is also the cheapest possible experiment for the central open question — does
MTP work on an ARM CPU — because it risks nothing.

**Tier 2, spend the headroom on quality.** In order of confidence:

1. ~~Raise the quantisation, not the parameter count.~~ Measured: Q6_K runs at
   9.66 tok/s, not the 15.5 predicted here, and holds 575 MB more. The prediction
   came from an envelope fitted on short favourable runs, which overstates sustained
   throughput by about a third; see the follow-up in
   [`model-throughput-survey.md`](model-throughput-survey.md).
2. Turn thinking on for hard turns. The deck runs `reasoningMode: "off"`, and 2B's
   own card shows IFEval **61.2 non-thinking → 78.6 thinking**, BFCL-V4 43.6 and TAU2
   48.8 in thinking mode. Thinking is not free and not universally good — the
   published finding is that it helps multi-step constraints and *hurts* simple ones
   — so this should be a per-turn decision, not a global switch.
3. Then attempt Qwen3.5-4B-MTP with KV q8_0 and flash attention. This is the real
   prize (50.3 BFCL-V4, 79.9 TAU2, 55.8 LiveCodeBench) and the real risk: it needs
   MTP to land near the top of its claimed range *and* the memory guard to pass.
   Predicted 11–15 tok/s, which straddles the bar rather than clearing it.

**Tier 3, a different seat rather than a replacement.** LFM2.5-1.2B tied for first
at **0.880** on the 21-model tool-calling bench while being ~7× faster than the
models it tied with, and its Thinking variant lifts BFCLv3 tool use from 49 to 57. At
roughly 0.75 GiB it should decode at 30–45 tok/s here. It is a better *NANO* tier
than Qwen3.5-0.8B, which currently occupies that slot with no measured agentic
quality at all. It is not a coding model and should not be sold as one.

**Do not** chase Qwen3.6 — the smallest release is 27B. There is still no small
Qwen3.5-Coder; the Small series is 0.8B/2B/4B/9B.

Every one of these still has to clear `docs/model-admission.md`: 28 suite tasks,
provenance, ten clean smoke runs. Unsloth MTP conversions are third-party, the same
provenance class as the bartowski artifacts already in the manifest, so they land as
`EXPERIMENTAL` with `provenanceStatus: INCOMPLETE` unless the conversion is
reproduced locally.

---

## Part 2 — the harness

The agent currently has **six** built-in tools from Pi 0.82.1 — `read`, `edit`,
`write`, `bash`, `ls`, `grep` — plus PI//DECK's `web_search` and `weather`. That is
the whole surface. The gaps below are ordered by measured effect on weak models, not
by how interesting they are to build.

An important constraint to hold throughout: **more tools makes small models worse.**
The TSCG work reports 4B–14B models dropping to 0–49 % accuracy once more than ~15
JSON-schema tools are in play, recovering to 65–90 % when schemas are compiled to
compact structured text. So the goal is not "add tools", it is "replace the ones that
fail, and keep the count under about ten".

### The single largest available win: stop making the model reproduce text

Pi's `edit` is exact-string search-and-replace. It requires the model to reproduce
whitespace, indentation and character sequences perfectly. This is the documented
number-one failure mode for weak models, and the fix is measured:

> Replacing exact-match editing with **content-hash line anchors** — every line
> returned by `read` carries a 2–3 character hash, and edits reference `2:f1` instead
> of quoting the line — moved Grok Code Fast 1 from 6.7 % to 68.3 % (+61.6 pp),
> MiniMax M2.1 by +41.7 pp and Devstral Medium by +40.5 pp, while strong models moved
> by −5 to +4.6 pp. **The weakest models gain the most.** Grok 4 Fast also emitted
> 61 % fewer output tokens because the retry loops disappeared.

Fewer output tokens is not a side benefit at 15 tok/s — it *is* the benefit. This is
implementable entirely as a Pi extension that wraps `read` and replaces `edit`, in the
same way `pideck-context-guard` already wraps `tool_result`.

The corroborating result from the same area: harness sensitivity is *non-monotone*
across model tiers, so directive, structurally-anchored designs that help a 2B can be
neutral or negative for a frontier model. The deck only ever runs small models. It
should optimise for that tier without apology.

### Coding: five concrete additions

1. **Hash-anchored edit** (above). Highest value, self-contained, no network.
2. **Post-edit syntax check.** After any `write`/`edit`, run the cheap validator for
   the file type (`python3 -m py_compile`, `node --check`, `javac` where present) and
   return the error immediately as part of the tool result. The model currently finds
   out it broke a file only if it thinks to run the tests. Suite task T08 is exactly
   this loop.
3. **A bounded `run_tests` tool.** Today tests go through `bash`, and
   `pideck-context-guard` then truncates pytest's output to a head/tail window —
   which is precisely where the failure summary is *not*. A tool returning
   `passed/failed/errors` plus the first failure verbatim is both smaller and more
   useful. T04, T06, T07 and T08 all depend on reading test output correctly.
4. **`glob` / file map.** Pi has no glob; the model reaches for `bash find`, whose
   output is unbounded and then gets truncated. A bounded, sorted file listing is
   cheaper in tokens and more reliable.
5. **Symbol lookup.** T02 ("find the definition of `divide`") is served by `grep`
   today. A ctags-class index or `ast-grep` answers it in one call with a
   file:line, instead of a grep whose output needs filtering.

### Web: the missing half of `web_search`

`web_search` returns at most 5 results with snippets capped at 520 characters. There
is **no way for the agent to read a page.** It can find a URL and then must either
guess its contents or shell out. That is the biggest functional gap in the deck.

1. **`web_fetch`.** URL in, bounded markdown out. `https://r.jina.ai/<url>` returns
   clean markdown, renders JavaScript server-side and needs no API key, which suits a
   phone that cannot run a browser engine. The privacy cost is real and belongs in
   `security-model.md`: Jina sees every URL fetched. A direct `fetch` plus local
   Readability-style extraction avoids that and fails on JS-heavy pages; offering
   both, with direct-first, keeps the deck's fixed-endpoint property.
2. **Harden the search chain.** The current primary is `mcp.exa.ai` unauthenticated —
   a public endpoint with no SLA and no key — falling back to scraping
   `html.duckduckgo.com`, which breaks whenever DuckDuckGo changes markup or rate-limits
   the IP. Brave Search offers an independent index at 2,000 queries/month free with a
   key; Exa's own free tier is 1,000/month. One keyed provider plus the existing
   scraper is materially more reliable than two unkeyed ones.
3. **`search_then_read` as one call.** At 15 tok/s every extra round trip costs the
   user seconds of visible latency. A composite that searches, fetches the top two
   results and returns one bounded digest turns three model turns into one. This is
   the shape `nicobailon/pi-web-access` (MIT) arrived at with `web_search` +
   `fetch_content` + paginated `get_search_content`; it is worth reading as a design
   reference, though its ten-provider fallback chain and `~/.pi/web-search.json` key
   file would break the deck's reproducible-network-surface rule if adopted directly.
4. **Cache by URL and by query.** `pideck-local-cache` caches the KV prefix; nothing
   caches the network. Repeated searches in one session re-fetch.
5. **Optional, if a second model is ever acceptable:** `LFM2.5-Embedding-350M-GGUF`
   (~0.25 GiB) would let the deck pick the *relevant* 2 K tokens out of a fetched page
   rather than head/tail-truncating it, and would serve local codebase search too.
   The cost is a second resident model on a device where RAM already binds.

### Inference-level changes that help both

These are server flags, not code, and the shipped binary supports all of them.

- **`--spec-type ngram-mod` or `ngram-simple`.** Model-free speculative decoding that
  drafts from n-grams already in the context. It needs no extra download, costs about
  16 MB, and pays off exactly when output repeats input — which is what a file edit
  *is*. This is the cheapest speed experiment available for coding specifically, and
  it composes with nothing else needing to be true. Requires adding the
  `--spec-ngram-*` flags to `MANAGED_SERVER_FLAGS`.
- **`--flash-attn on` with `--cache-type-k q8_0 --cache-type-v q8_0`.** Halves KV at
  no meaningful quality cost. Keep K and V the same type or the fused attention path
  silently falls back to the slow one.
- **`--cache-reuse N`.** Lets the server reuse a prefix after a mid-context change
  instead of reprocessing, which matters when the agent edits a file and resends.
- **Grammar-constrained tool calls.** `--grammar` / `--grammar-file` are present in
  the build. Constrained decoding makes malformed tool calls impossible rather than
  merely unlikely; the published effect is large enough that a constrained 3B beat an
  unconstrained 70B baseline on function calling. With `--jinja` the server already
  applies the model's tool-call template — worth confirming whether it is *also*
  constraining the grammar, since if not, this is a one-flag win.
- **Compact the tool schemas.** Per TSCG, verbose JSON schemas cost small models
  accuracy that structured-text schemas recover. The eight current tool definitions
  are worth a pass for verbosity.

### Prompting and evaluation

The 21-model tool-calling bench catalogues three failure modes that are prompt-level,
not capability-level, and all three are cheap to attack from
`pideck-system-prompt.ts`:

- **Keyword capture** — five of eight working models called `get_weather` whenever
  the word "weather" appeared, even when told not to.
- **Negation blindness** — "don't check the weather, just find the report" was
  routinely ignored.
- **Context blindness** — only 3 of 21 models noticed that the data was *already in
  the prompt* and no call was needed.

Three or four negative exemplars in the guidance block address these directly. Note
that the deck's own `weather` tool is the exact shape that triggers failure mode one.

Finally, the benchmark itself. `suite-v1`'s 28 tasks measure whether the agent *acts*
correctly. The bench above scores `0.4·action + 0.3·restraint + 0.3·wrong-tool-avoidance`,
and restraint is the axis small models fail hardest. `suite-v1` has no task where the
correct behaviour is to call nothing, and no task exercising `web_search` at all. A
`suite-v2` should add both, or the harness work above will be unmeasurable.

---

## What to do next, in order

Items 1, 2, 4 and 5 were done on this branch; what changed is recorded inline.

1. ~~Measure `--spec-type draft-mtp`~~ — **done, and it lost.** See
   [`speculative-decoding-measurements.md`](speculative-decoding-measurements.md).
   It also needed a real code change, not a manifest edit; see the correction above.
2. ~~Measure `--spec-type ngram-mod`~~ — **done, and it returns 1.05×**, inside the
   spread of the samples. Left off in the catalog, now on evidence rather than on
   caution.
3. ~~Spend any headroom on quality: Q6_K weights first~~ — **done, and Q6_K loses.**
   Measured 9.66 tok/s against Q4_K_M's 13.06 (0.74×) and 575 MB more resident, so
   it fails the throughput bar this document set. Thinking-on for hard turns is
   still open and is now the only untested quality lever here.
4. ~~Build the hash-anchored edit extension~~ — **done**: `pideck-hashline-edit.ts`
   plus `pideck_replace_lines`, gated through the one approval path, verified
   against Pi's own `read` output by `tests/extensions/run_extension_checks.mjs`.
   Its effect on task success is not yet measured; that needs a suite run.
5. ~~Add `web_fetch`~~ — **done**, direct-read first with a rendering proxy only as
   a fallback, and the privacy cost written into `security-model.md`.
6. ~~Attempt Qwen3.5-4B-MTP with KV q8_0 + flash attention.~~ Overtaken: with the bar
   relaxed to 7 tok/s the plain 4B is admissible on its own, and it starts — the
   guard passed at 5.5 GB expected against 5.3 GB available once the phone had free
   memory. MTP is the wrong lever for it either way. KV q8_0 and flash attention are
   still worth trying to widen that margin, and both already pass through
   `serverArgs` without a code change.
7. ~~Compact the permanent tool surface~~ — **done** with
   `pideck-tool-router.ts`. Pi still receives the complete hard allowlist for the
   selected access profile, then an ordinary Autonomous turn is narrowed to
   `read,bash,write,pideck_replace_lines,pideck_load_tools`. Explicit live-data
   requests activate their managed group before the first provider request; the
   loader can add other optional groups but cannot cross the Android profile. The
   exact Pi payload fell 40.9% for an ordinary Autonomous fixture. Chat now uses a
   separate 241-character final prompt and no tools.

## Sources

Model and benchmark data: [Qwen3.5-2B](https://huggingface.co/Qwen/Qwen3.5-2B),
[Qwen3.5-4B](https://huggingface.co/Qwen/Qwen3.5-4B),
[Qwen3.5-2B-MTP-GGUF](https://huggingface.co/unsloth/Qwen3.5-2B-MTP-GGUF),
[Qwen3.6 repository](https://github.com/QwenLM/Qwen3.6),
[Liquid LFM2.5](https://www.liquid.ai/blog/introducing-lfm2-5-the-next-generation-of-on-device-ai),
[Liquid model library](https://docs.liquid.ai/lfm/models/complete-library),
[LFM2.5 retrievers](https://www.liquid.ai/blog/lfm2-5-retrievers),
[Local Agent Bench, 21 open-weight models](https://mikeveerman.be/blog/github-2026-02-06-tool-calling-benchmark/),
[On-device tool calling comparison](https://www.ertas.ai/blog/on-device-tool-calling-2026-qwen3-gemma4-phi4),
[Gemma 4 guide](https://codersera.com/blog/gemma-4-complete-guide-2026/),
[BFCL v4 leaderboard](https://gorilla.cs.berkeley.edu/leaderboard.html).

Runtime: [llama.cpp MTP PR #22673](https://github.com/ggml-org/llama.cpp/pull/22673),
[speculative decoding docs](https://github.com/ggml-org/llama.cpp/blob/master/docs/speculative.md),
[negative spec-decode benchmark on 35B-A3B](https://hackmd.io/ODXuOQNzSiyUITz7g9mtBw),
[KV cache quantisation and flash attention](https://github.com/ggml-org/llama.cpp/discussions/22411).

Harness: [Hashline edit format results](https://stencil.so/blog/the-harness-problem),
[aider edit-format benchmarks](https://aider.chat/docs/leaderboards/edit.html),
[TSCG tool-schema compilation](https://arxiv.org/pdf/2605.04107),
[harness sensitivity across agent tiers](https://arxiv.org/pdf/2605.26731),
[when built-in thinking helps and hurts](https://arxiv.org/pdf/2606.09662),
[pi-web-access extension](https://github.com/nicobailon/pi-web-access),
[web search for agents in 2026](https://michaellivs.com/blog/web-search-for-agents-2026/).
