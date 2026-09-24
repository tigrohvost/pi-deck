#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
destination="${repo_dir}/app/src/main/jniLibs/arm64-v8a"
"${repo_dir}/tools/build_llama_android.sh" "${destination}"
"${repo_dir}/tools/build_nanbeige_android.sh" \
    "${destination}/libpideck_nanbeige_server.so"
"${repo_dir}/tools/build_k2_horizon_android.sh" "${destination}"
"${repo_dir}/tools/build_prism_android.sh" "${destination}/libpideck_prism_server.so"
printf 'Vendored patched llama.cpp b10092 Android arm64 runtime into %s\n' \
    "${destination}"
