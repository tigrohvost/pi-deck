# IFM K2 Horizon Android runtime

K2 uses an isolated static CPU server from
[MBZUAI-IFM/llama.cpp](https://github.com/MBZUAI-IFM/llama.cpp), commit
`35999d101cf2233fc54f09c3c8d599da7303ce02`. The stock b10092 runtime remains
responsible for LFM and Qwen. The upstream MIT license is in `LICENSE`.

`patches/0001-request-reasoning-tag.patch` selects the IFM reasoning delimiter
from the actual assistant generation prefix. Low, medium and high prefixes use
different tags; the template's serialized history alone does not identify the
current tag. The p2 parser accepts both the effort-specific closing delimiter and the generic
`</ifm|think>` emitted by the model in medium mode. The same delimiters terminate
the reasoning budget. The prompt stays unchanged. Its SHA and the produced
ELFs are checked by `:app:verifyNativeRuntime`.

Build with the pinned Android NDK 28.2.13676358, Android CMake 3.22.1 and Ninja
1.10.2:

```sh
PIDECK_K2_NDK_ROOT=/path/to/Android/Sdk/ndk/28.2.13676358 \
ANDROID_HOME=/path/to/Android/Sdk \
  tools/build_k2_horizon_android.sh
```

The builder produces a portable ARMv8 baseline and an ARMv8.6 i8mm variant.
`CpuProfile` selects i8mm only when every reported CPU feature list includes
`i8mm`, `asimddp` and `asimdhp`; missing feature information uses the baseline.
Both embedded UI build and prebuilt UI download are disabled. The build neither
fetches a floating UI artifact nor requires npm. Sources, toolchain versions and
patch bytes are pinned; paths and ELF build IDs are normalized.

Both p2 ELFs reproduced the same SHA in two clean builds under different
source/build directories. The i8mm SHA is
`81f15c4254dc9ab75054805ecb19e852fff94c98477a61ea27e08934d7788dcd`;
the ARMv8 baseline SHA is
`54bf7107379b2438e18b296eeb7d0ab92a0db07ceb9e0c24f665876e5009f389`.

The profile uses five decode/batch threads on SM-S918B, no polling, 8192 context,
256 reasoning tokens and low effort. Affinity is non-strict for this vendor
runtime, matching the audited configuration; the stock runtime separately
contains the Android affinity patches. Device acceptance of these newly
packaged binaries is recorded separately from the September 7 research probes.
