# Speculative decoding on this phone: what it actually does

Evidence date: 2026-07-29. Device: Samsung SM-S918B, Snapdragon 8 Gen 2, 11.3 GiB
RAM. Runtime: the bundled Android arm64 `llama.cpp b10092`, run directly from
`adb shell` by [`tools/speculative_probe.py`](../tools/speculative_probe.py).
Weights: `unsloth/Qwen3.5-2B-MTP-GGUF` at revision `e05864f8`, `Q4_K_M`,
SHA-256 `18c92228…4d4f0b`, 1 329 851 808 bytes. Context 10240, one slot, decode
pinned `5@3-7`, batch `8@0-7`, `cache_prompt` off.

One model file was measured under several `--spec-type` settings, so nothing here
is confounded by a different quantisation or a different build. The first call after
every load is discarded because it faults mmapped weights in from flash and measures
storage rather than the model.

[`agent-upgrade-research.md`](agent-upgrade-research.md) predicted MTP would be the
win and n-gram drafting a cheap side experiment. It took three passes to answer
that, and the first two were wrong for reasons worth recording, because both failure
modes flatter speculative decoding and both are easy to repeat.

## First pass, and why it had to be thrown away

The first sweep measured each variant in sequence and produced a clean-looking story.
Then the same variant, on the same prompt and the same flags, measured **6.71 tok/s
in one run and 20.72 tok/s in another**. That is not noise, and the cause was on the
device rather than in the model:

```
cpu7 cpuinfo_max_freq  3360000     # nominal
cpu7 scaling_max_freq  1593600     # what the governor was allowing
cpu thermal zones      71–74 °C
```

The phone was thermally throttled to 47 % of its big-core clock. Because variants
ran in a fixed order inside one invocation, each successive variant was measured on
a hotter phone than the one before it — and `draft-mtp` was always last. The
ordering alone could have produced the result. Every number from that pass is
discarded.

`tools/speculative_probe.py` now waits before each variant until the governor
returns at least 98 % of the nominal big-core clock, and records the headroom and
hottest CPU zone alongside every sample, so a contaminated run is visible in the
report instead of being invisible in the median.

That threshold has a cost worth knowing before running this again: a phone on USB
power, being polled over adb, can sit at 70 % headroom indefinitely and never reach
98 %. Each variant then pays the full ten-minute cooldown deadline before starting
anyway. Unplug the device for a faster sweep, or lower `COOLDOWN_HEADROOM` and
accept a warmer, more variable baseline.

## Second pass, and why it also had to be thrown away

With the thermal problem fixed, `ngram-mod` reported draft acceptance climbing
across the samples of a single variant — 0.708, then 0.932, then 0.958, then 0.975.
Acceptance is not supposed to improve while nothing changes, and the cause was the
protocol rather than the phone: `ngram-mod` keeps an n-gram pool that survives
across requests, and the probe was sending **the same prompt** for every sample. From
the second sample onward the model was drafting from its own previous answer.

Real use does not repeat one prompt verbatim, so every `ngram-mod` figure produced
that way is inflated — including the 3.05× on the edit prompt. The probe now takes
`--prompt-file` repeatedly and cycles a distinct prompt through each sample.

The numbers below are from the third pass: thermally controlled, one distinct
prompt per sample, and measured under the deck's own sampling parameters
(`temperature 0.7`, `top_p 0.8`, `top_k 20`, `presence_penalty 1.5`) rather than the
deterministic defaults, because `presence_penalty` suppresses exactly the repetition
n-gram drafting feeds on.

## Measured

Median of the warm samples from the third pass; seven samples per variant, seven
distinct prompts, both variants entering at `headroom 1.0`.

| Prompt shape | baseline | `ngram-mod:16` | range, baseline | range, ngram |
|---|---:|---:|---|---|
| Code edit (file returned with one line changed) | 13.06 | 13.72 (**1.05×**) | 12.52–14.18 | 8.25–19.65 |
| Prose (six unrelated explanations) | 12.75 | 12.98 (**1.02×**) | 12.59–13.03 | 11.69–14.69 |

Note the spreads. On the edit prompts `ngram-mod` ranges from 8.25 to 19.65 tok/s
while baseline stays inside 12.52–14.18: speculation does not just fail to help on
average, it makes the per-answer latency markedly less predictable.

`draft-mtp:4` was measured in the thermally controlled pass at 14.13 against a
14.77 baseline — **0.96×** — while holding roughly 200 MB more resident. Its earlier
0.40× on prose was a throttling artifact and should not be quoted.

## What each result means

**Neither speculative mode earns its place on this device.** MTP costs 4 % and
200 MB. `ngram-mod` returns 5 % on file editing and 2 % on prose, both inside the
spread of the samples themselves, while widening that spread considerably.

The reason is visible in the acceptance figures. With distinct prompts, `ngram-mod`
accepts **0.375–0.94, typically 0.44–0.59** of what it drafts. At roughly half
acceptance the verification of the rejected half cancels most of what the accepted
half saves, and on a CPU whose decode is already bound by reading weights there is
no headroom left over. MTP behaves the same way for the same reason at 0.57
acceptance.

The contrast with the discarded second pass is the whole lesson: repeating one
prompt drove acceptance to 0.86–0.98 and produced a 3.05× that does not exist.

## Consequence for the catalog

Every entry in `models-v2.json` ships `"speculative": {"mode": "off", "draftMax": 0}`
and the emitted command line for existing profiles is byte-identical to before.
That is now a measured decision rather than caution.

What the branch leaves behind is the ability to answer the question again cheaply:
a schema field, an argument builder with tests, and a probe that controls for
thermal state and for prompt reuse. A future llama.cpp build, a model with better
draft acceptance, or a device that is not memory-bound could flip this, and
re-running `tools/speculative_probe.py` is all it takes to find out.

## Reproducing

```
python3 tools/speculative_probe.py \
  --model /path/to/Qwen3.5-2B-Q4_K_M.gguf \
  --variant baseline --variant ngram-mod:16 --variant draft-mtp:4 \
  --context 10240 --runs 7 --max-tokens 192 --label edit \
  --prompt-file p1.txt --prompt-file p2.txt --prompt-file p3.txt \
  --request-overrides '{"temperature": 0.7, "presence_penalty": 1.5}' \
  --output build/mtp-models/distinct-edit.json
```

Pass `--prompt-file` at least as many times as there are samples, or the n-gram pool
carries one sample's answer into the next and the result is meaningless.

The probe pushes the GGUF to `/data/local/tmp/pideck-speculative`, starts the
server with an `--api-key-file` it generates per run, forwards the port over adb,
and removes the key and the model afterwards unless `--keep-model` is passed. It
never touches the deck's own installed model or its managed key.
