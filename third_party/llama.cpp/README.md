# llama.cpp Android runtime

PI//DECK bundles the official Android arm64 CPU release of
[`ggml-org/llama.cpp`](https://github.com/ggml-org/llama.cpp) at tag `b10369`
(commit `6e62ba538478202094edc6c100c782719e310aa3`).

Source archive:

`https://github.com/ggml-org/llama.cpp/releases/download/b10369/llama-b10369-bin-android-arm64.tar.gz`

SHA-256:

`9ed20985df5b243299a24636c5d086fc0fd8ea8d0c18da0662a6a113abe3272f`

The checked-in ELF files are stripped copies produced by
`tools/vendor_llama_android.sh`. The app packages the baseline and optimized
Arm CPU variants; llama.cpp selects the best compatible backend at runtime.
The upstream MIT license is reproduced in this directory.
