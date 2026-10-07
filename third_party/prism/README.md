# Prism Android runtime

Bonsai 2 PTQ1_0 requires the PrismML fork's tensor type 143 and Hadamard
activation transforms. Its static Android executable is isolated from stock
llama.cpp and selected only by the `prism` catalog flavor.

`tools/build_prism_android.sh` pins commit
`842b1880415d6f508f03b789e5ce70194def7bfd` and verifies the source archive before
building. It then applies the checksum-pinned patch set `pideck-prism1` from
`patches/`:

- `0001-cpu-affinity-retry.patch` retries a strict CPU pin that Android briefly
  rejects with `EINVAL`, so every worker really gets its own fast core;
- `0002-ptq1-two-token-dot-arm.patch` decodes each PTQ1_0 block once for two
  prompt tokens with NEON SDOT. It is bit-exact against the scalar path and
  measured 1.46x/1.52x prompt ingestion on SM-S918B; single-token decode keeps
  the original kernel.

Measurements and the full protocol are in `docs/bonsai2-performance-research.md`. It uses NDK 28.2.13676358, Android CMake 3.22.1, and ARMv8.2-A with
dot product and FP16 support. The generated ELF hash is pinned in
`app/src/main/assets/native-runtime.json`.

```sh
PIDECK_PRISM_NDK_ROOT="$ANDROID_HOME/ndk/28.2.13676358" \
  tools/build_prism_android.sh
```

`PIDECK_HOST_CXX` selects the host C++ compiler used for the server's asset
embedding tool. `PIDECK_PRISM_SOURCE_ARCHIVE` can reuse a downloaded archive;
its SHA-256 is still checked.
