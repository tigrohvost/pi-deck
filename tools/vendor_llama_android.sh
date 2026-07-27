#!/usr/bin/env bash
set -euo pipefail

LLAMA_BUILD="b10092"
LLAMA_ARCHIVE_SHA256="4f23b4a91b7043db43789fd248142a739d7a1f632d403f756e8be920c45c8076"
LLAMA_ARCHIVE_URL="https://github.com/ggml-org/llama.cpp/releases/download/${LLAMA_BUILD}/llama-${LLAMA_BUILD}-bin-android-arm64.tar.gz"

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
destination="${repo_dir}/app/src/main/jniLibs/arm64-v8a"
task_dir="$(mktemp -d "${TMPDIR:-/tmp}/pideck-llama-android.XXXXXX")"
trap 'rm -rf "${task_dir}"' EXIT

archive="${task_dir}/llama-android.tar.gz"
if [[ -n "${PIDECK_LLAMA_ARCHIVE:-}" ]]; then
    cp "${PIDECK_LLAMA_ARCHIVE}" "${archive}"
else
    curl --fail --location --retry 3 --output "${archive}" "${LLAMA_ARCHIVE_URL}"
fi
printf '%s  %s\n' "${LLAMA_ARCHIVE_SHA256}" "${archive}" | sha256sum --check -
tar -xzf "${archive}" -C "${task_dir}"

source_dir="${task_dir}/llama-${LLAMA_BUILD}"
strip_tool="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
if [[ ! -x "${strip_tool}" ]]; then
    printf 'Set ANDROID_NDK_ROOT to an Android NDK containing llvm-strip.\n' >&2
    exit 2
fi

mkdir -p "${destination}"
files=(
    libggml-base.so
    libggml.so
    libggml-cpu-android_armv8.0_1.so
    libggml-cpu-android_armv8.2_1.so
    libggml-cpu-android_armv8.2_2.so
    libggml-cpu-android_armv8.6_1.so
    libggml-cpu-android_armv9.0_1.so
    libggml-cpu-android_armv9.2_1.so
    libggml-cpu-android_armv9.2_2.so
    libllama.so
    libllama-common.so
    libllama-server-impl.so
    libmtmd.so
)

for file in "${files[@]}"; do
    install -m 0755 "${source_dir}/${file}" "${destination}/${file}"
done
install -m 0755 "${source_dir}/llama-server" \
    "${destination}/libpideck_llama_server.so"

"${strip_tool}" --strip-unneeded "${destination}"/*.so
printf 'Vendored llama.cpp %s Android arm64 runtime into %s\n' \
    "${LLAMA_BUILD}" "${destination}"
