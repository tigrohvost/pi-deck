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
remain `EXPERIMENTAL`. No Granite, Ministral or Gemma metadata was invented or
added merely because the specification names those candidates.

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
