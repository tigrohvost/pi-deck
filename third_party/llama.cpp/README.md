# llama.cpp Android runtime

PI//DECK bundles the official Android arm64 CPU release of
[`ggml-org/llama.cpp`](https://github.com/ggml-org/llama.cpp) at tag `b10092`
(commit `3ce7da2c852c538c4c5f9806da27029cf8c9cc4a`).

Source archive:

`https://github.com/ggml-org/llama.cpp/releases/download/b10092/llama-b10092-bin-android-arm64.tar.gz`

SHA-256:

`4f23b4a91b7043db43789fd248142a739d7a1f632d403f756e8be920c45c8076`

The checked-in ELF files are stripped copies produced by
`tools/vendor_llama_android.sh`. The app packages the baseline and optimized
Arm CPU variants; llama.cpp selects the best compatible backend at runtime.
The upstream MIT license is reproduced in this directory.
