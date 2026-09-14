# llama.cpp Android runtime

PI//DECK rebuilds the Android arm64 CPU runtime from
[`ggml-org/llama.cpp`](https://github.com/ggml-org/llama.cpp) tag `b10092`
(commit `3ce7da2c852c538c4c5f9806da27029cf8c9cc4a`) plus the pinned
`pideck-affinity1` patch set in [`patches/`](patches/).

Source archive:

`https://github.com/ggml-org/llama.cpp/archive/refs/tags/b10092.tar.gz`

SHA-256:

`b7bd2871ffcb467cc1445b5a5de8dda18fbeefd0a3c42c249a9650209a4da346`

The first patch enables the Linux affinity implementation under Android's
`__linux__` define. The second makes `llama-server` own and attach the parsed
decode/batch CPU thread pools; b10092 otherwise accepts the affinity flags but
leaves all workers on `0-7`. Both patch bytes and every produced ELF are checked
by `verifyNativeRuntime`.

`tools/vendor_llama_android.sh` invokes the reproducible NDK 27.1/CMake 3.22.1
source build. The app packages baseline and optimized Arm CPU variants, and
llama.cpp selects the fastest compatible backend at runtime. The upstream MIT
license is reproduced in this directory.

## Isolated Snapdragon Hexagon candidate

`tools/build_llama_snapdragon_android.sh` builds a separate HTP v73 probe from
the same b10092 source and affinity patches plus
`0003-hexagon-use-android-cmake-toolchain.patch`. It requires Android NDK
28.2.13676358, Android CMake 3.22.1, and the official Hexagon SDK 6.6.0.0 with
Hexagon Tools 19.0.07. The builder validates the source, SDK metadata, and patch
hashes before compiling:

```sh
PIDECK_HEXAGON_SDK_ROOT=/path/to/6.6.0.0 \
ANDROID_NDK_ROOT="$ANDROID_HOME/ndk/28.2.13676358" \
  ./tools/build_llama_snapdragon_android.sh
```

The output stays under `build/experimental/` and is never copied to
`app/src/main/jniLibs`. `tools/adb_hexagon_probe.py` stages it under an isolated
shell-owned directory, verifies a live `HTP0` v73 backend, and compares it with
a physically HTP-free CPU control. Promotion requires correct output, at least
2x prompt throughput, at least 0.95x decode throughput, and no crash. Until all
gates pass, Hexagon remains a reproducible experiment rather than an APK
runtime.

## Isolated Adreno OpenCL candidate

`tools/build_llama_opencl_android.sh` builds an isolated arm64 candidate from
llama.cpp `b10687` (`c841aeeb8bb2fe417038dadfa9b007cf1a9ef950`) with pinned
Khronos OpenCL-Headers and OpenCL-ICD-Loader sources. That tag includes the
upstream Qualcomm E031 compiler workaround needed by the Adreno 740 q6_K
matrix-multiplication path. The builder requires Android NDK 28.2.13676358 and
Android CMake 3.22.1, embeds the Adreno kernels, and produces `llama-bench`,
`llama-server`, and `test-backend-ops` for shell-owned device probes:

```sh
ANDROID_NDK_ROOT="$ANDROID_HOME/ndk/28.2.13676358" \
  ./tools/build_llama_opencl_android.sh
```

Run the filtered q6_K and FlashAttention backend correctness checks before any
performance sweep. `tools/adb_accelerator_probe.py` then compares a physically
OpenCL-free CPU control with partial and full offload under exact model-SHA and
thermal gates. The output is never copied into `app/src/main/jniLibs`; a failed
correctness check, crash, incomplete sweep, or decode ratio below the gate keeps
the production APK on its CPU runtime.

Pass both `--correctness-test q6-k-mul-mat` and
`--correctness-test flash-attention` to make those checks part of the atomic
report. If USB or a thermal gate interrupts a long sweep, `--resume` accepts
only the exact candidate, model SHA, test plan, and already completed sample
prefix; it reruns correctness before continuing.
