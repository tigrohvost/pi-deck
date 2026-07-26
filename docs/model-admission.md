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
