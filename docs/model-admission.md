# Model admission

`app/src/main/assets/models-v2.json` is the only production catalog. Its schema
is `schemas/models-v2.schema.json`; Gradle and strict Java/Python parsers reject
unsafe critical metadata, duplicate IDs, mutable revisions, invalid SHA-256,
unapproved licenses and unknown critical fields.

Admission requires all gates from the specification:

1. exact weight license and evidence URL reviewed manually;
2. immutable repository revision, exact file, independently calculated bytes
   and SHA-256;
3. provenance class plus upstream revision, converter revision, quantization
   command, build environment and license chain;
4. load/health/exact-model/tool/session/abort/context/memory/stability tests on
   the pinned llama.cpp build;
5. all 28 `suite-v1` tasks and the device metrics report;
6. no outside-workspace mutation and no mutation in `READ_ONLY`;
7. ten consecutive smoke runs without server crash.

`tools/pin_model.py` downloads one immutable artifact without executing remote
code, rejects HTML/LFS pointers, streams hash/size, reads bounded GGUF metadata,
requires an exact manual-license confirmation and writes a provenance report.
It cannot promote a model. Manifest mutation additionally requires a separately
prepared entry whose complete provenance matches the independently pinned
artifact.

Current Qwen3.5 artifacts preserve existing compatibility but have incomplete
third-party conversion provenance and no current suite report. They therefore
remain `EXPERIMENTAL`. Granite and Gemma metadata is not added merely because
the specification names those candidates.

## Ministral 3 3B Instruct (`ministral-3-3b-instruct-2512`)

Admitted at `CANDIDATE` on 2026-08-11 from Mistral AI's official GGUF
repository. The Q4_K_M artifact is pinned to repository revision
`eb599d408350ea2bb60452cb86be7c7b2fc28227`, 2,147,023,008 bytes and SHA-256
`9ed150d4367e68df0ac8e1540f6ddc65b42d0ee26378329d1ecbca60f93fc5f8`.
The upstream weights revision is recorded separately as `b35d4dfe56c1`.
It keeps `provenanceStatus: INCOMPLETE` because the official GGUF repository
does not publish the exact converter revision, quantization command and build
environment required by gate 3.

The bundled b10092 runtime contains the Mistral3 architecture and its tool
template path, so `runtime.serverFlavor` remains `stock`. The profile follows
the vendor's low-temperature instruction setting and exposes the embedded
Jinja tool contract. The vendor model card does not list Russian among its
declared languages. A minimal SM-S918B compatibility smoke completed with an
exact `PIDECK_OK` response, about 23.18 prompt tok/s and 0.54 decode tok/s; the
full suites, Russian behavior and multi-turn tool use remain unverified. The
row is therefore manual-only and cannot be recommended automatically.

## Nanbeige4.2 3B (`nanbeige4.2-3b`)

Admitted at `CANDIDATE` on 2026-08-11. The Q4_K_M conversion is third-party,
pinned to `owao/Nanbeige4.2-3B-GGUF` revision `6784dff2a81a3713ddba6be7978a4534189c789c`,
2,574,807,904 bytes and SHA-256
`ffe1b9b8ee95ec4b962c379905aa8be6f72ae9c4645c6c70e3b6ff7b197e6ef4`.
The official upstream weights are independently pinned at `5d54321e9e01`.
The converter did not publish a reproducible conversion record, so provenance
is explicitly `INCOMPLETE`.

Nanbeige's architecture is not supported by the stock b10092 build. Replacing
the global server would put every existing profile on an unvalidated fork, so
the app instead packages one isolated, statically linked Android executable.
`tools/build_nanbeige_android.sh` fetches the official Nanbeige llama.cpp fork
at exact commit `c6640a1c0cf7b38df342b67021a3900b04d092e7`, requires exact NDK
28.2.13676358 and emits `libpideck_nanbeige_server.so`. The catalog flavor,
foreground service allowlist, adoption build ID and native manifest must all
agree before it can reach `READY`; stock models continue to launch b10092.

The sampling and 1024-token reasoning budget follow the model's agentic mode,
but its publisher only claims English and Chinese. Russian behavior, tool-loop
conformance, memory, speed, the complete device suites and ten clean smokes are
still unmeasured. It remains visible for explicit selection and download but
is excluded from recommendation until those gates pass.

## LFM2.5 2.6B (`lfm2.5-2.6b`)

Admitted at `CANDIDATE` on 2026-08-07, and it is the first entry that needed a
new license on the allowlist. The weights ship under the **LFM Open License
v1.0** (`LicenseRef-LFM-Open-1.0` in the manifest — the license is not in the
SPDX registry, so the `LicenseRef-` convention names it). The full text was
reviewed on 2026-08-07: it is Apache-2.0-derived and grants perpetual,
royalty-free use, modification and redistribution, with one carve-out —
commercial use is not licensed for a legal entity above $10M annual revenue
(section 5). PI//DECK's personal on-device use is squarely inside the grant;
the deck does not redistribute the weights. The allowlist entry lives in five
places that all name this review: the manifest schema, `ModelSpec.java`,
`model_store.py`, `pin_model.py` and the `verifyModelManifest` Gradle task.

Provenance is the strongest in the catalog: `LiquidAI/LFM2.5-2.6B-GGUF` is the
vendor's own conversion, pinned at `b421ad1d`, `official: true`. The SHA-256
and byte count come from Hugging Face LFS metadata and were re-verified on the
device before the entry was committed. `provenanceStatus` stays `INCOMPLETE`
only because the vendor publishes no quantization command or converter
revision.

Why admit it at all: on the vendor's published numbers it beats Qwen3.5-4B on
tool calling (BFCLv4 56.9 against 50.6 in the same table) at 1.67 GiB of
weights — 2B-class speed with above-4B tool judgment. Two honest caveats are
in the catalog note. The vendor explicitly does not recommend it for agentic
coding, which is the deck's main job, so this is a potential tool-calling
seat, not a Qwen replacement. And the card publishes no TAU2 number. The
card's `repetition_penalty: 1.1` has no OpenAI-style sampling field, so it
rides through `serverArgs` as `--repeat-penalty 1.1`; temperature 0.1 and
top-k 50 are the card's own values.

`CANDIDATE` means what it meant for Bonsai: listed, selectable by hand, never
auto-offered. Gates 4–7 — pinned-runtime load/health, the 28-task suite, the
mutation checks and ten clean smokes — have not been run; the row must not
leave `CANDIDATE` until they are.

### LFM2.5 2.6B QAD Q4_0 (`lfm2.5-2.6b-qad`)

Promoted separately to `EXPERIMENTAL` on 2026-08-23; the original Q4_K_M row
remains a manual `CANDIDATE`, so saved selections and provenance never silently
change quantization. The QAD artifact is from the same official LiquidAI
repository, pinned at revision `f4a289c8`, 1,593,894,944 bytes and SHA-256
`a247afd6414918eac8e520a9e6137dc271235461ecbe1180462221d5b8d40b03`.

The promotion is deliberately narrower than full admission. On the reference
SM-S918B, using the unchanged stock b10092 runtime and the same thermal-gated
harness, QAD measured **16.47 tok/s over 128 tokens** and **15.71 tok/s over
192**, while the exact Q4_K_M control measured **12.32 tok/s over 192**. The
checked-in raw reports are
[`lfm25-qad-b10092-128-sm-s918b-2026-08-23.json`](../benchmarks/out/lfm25-qad-b10092-128-sm-s918b-2026-08-23.json),
[`lfm25-qad-b10092-192-sm-s918b-2026-08-23.json`](../benchmarks/out/lfm25-qad-b10092-192-sm-s918b-2026-08-23.json), and
[`lfm25-q4km-b10092-sm-s918b-2026-08-23.json`](../benchmarks/out/lfm25-q4km-b10092-sm-s918b-2026-08-23.json).

A live OpenAI-schema smoke first emitted exactly one `read_file` call for
`src/math_utils.py`, with valid JSON arguments and `finish_reason=tool_calls`;
the isolated one-tool control decoded at 15.57 tok/s. Given the returned buggy
source and failing examples, the model identified the correct root cause. The
unrestricted control spent all
768 tokens reasoning and truncated the following `edit` JSON, matching the
official description of LFM2.5 as a pure reasoning model. b10092's
`--reasoning-budget 256` is therefore part of the QAD catalog row. Under that
exact profile the model completed a valid three-round sequence: `read`, a
correct `edit` replacing the broken clamp implementation, then a terminal
answer with no extra tool. In that back-to-back run the 69-token read and
375-token edit rounds decoded at 14.44 and 14.31 tok/s as the phone heated; the
308-token final round recovered to 16.04 tok/s. The compact evidence record is
[`lfm25-qad-agent-smoke-sm-s918b-2026-08-23.json`](../benchmarks/out/lfm25-qad-agent-smoke-sm-s918b-2026-08-23.json).
The catalog still says `EXPERIMENTAL`, not fully admitted, because the 28-task
Pi suite and repeated clean multi-turn smokes remain open.

`EXPERIMENTAL` is sufficient for `ModelCatalog.recommend`, so capacity-gated
automatic choice now follows QAD → Qwen3.5 2B → 0.8B. This implements the
owner's explicit preference for the strongest coding/tool profile whose
controlled 192-token rate clears 15 tok/s; it does not erase the thermal caveat
or LiquidAI's general warning that this small model is not intended to replace
larger agentic coding models.

DSpark was evaluated only as an isolated harness experiment and is not a model
or runtime dependency of the APK. The b10545 Q4_K_M + DSpark run produced 8.49
tok/s with 36.923% acceptance (48/130); b10603 and longer candidate runs
regressed or timed out. Shipping that path would lower speed while replacing a
validated runtime, so it was rejected.

## Bonsai 27B (`bonsai-27b`)

Admitted at `CANDIDATE` on 2026-07-30. Its artifact provenance is stronger than
the Qwen3.5 rows — `prism-ml/Bonsai-27B-gguf` is the model vendor's own
repository, pinned at `f10afb35`, and the SHA-256 was calculated on the device
and matches the one the vendor's own installer checks. What is still missing is
the conversion record: the README names neither the fork revision nor the
quantization command, so `provenanceStatus` stays `INCOMPLETE`, and gates 4–7
have not been run.

`CANDIDATE` is doing real work here rather than standing in for "not finished
yet". The model decodes at 1.16 tok/s on SM-S918B while resident in 4.00 GiB —
it is the one entry that passes the memory guard comfortably and fails on speed,
which the guard cannot see. `ModelCatalog.recommend` and
`MainActivity.largestModelThatFits` therefore skip `CANDIDATE` alongside
`BLOCKED` and `DEPRECATED`; the row is still listed and still selectable by
hand. Measurements are in
[`model-throughput-survey.md`](model-throughput-survey.md).

Two fields read oddly and are correct. `source.architecture` is `qwen35`, the
literal ggml architecture in the file, even though `upstreamModel` is
`Qwen/Qwen3.6-27B`: the vendor left the architecture identifier of the 3.5
series in place, and it is what decides whether the pinned runtime can open the
file at all. `runtime.minimumLlamaCppVersion` is the pinned `b10092` even though
the vendor directs users at their own llama.cpp fork for the `Q1_0_g128`
kernels — b10092 loads and decodes the file correctly, just slowly.
