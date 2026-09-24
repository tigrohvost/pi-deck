#!/usr/bin/env bash
set -euo pipefail

# Bonsai 2 PTQ1_0 needs Prism quantization and Hadamard kernels.
# Package its pinned fork as an independent Android executable.
PRISM_COMMIT="842b1880415d6f508f03b789e5ce70194def7bfd"
PRISM_BUILD="prism-842b188"
PRISM_NDK_REVISION="28.2.13676358"

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
destination="${1:-${repo_dir}/app/src/main/jniLibs/arm64-v8a/libpideck_prism_server.so}"
ndk_root="${PIDECK_PRISM_NDK_ROOT:-}"
if [[ -z "${ndk_root}" ]]; then
    printf 'Set PIDECK_PRISM_NDK_ROOT to Android NDK %s.\n' \
        "${PRISM_NDK_REVISION}" >&2
    exit 2
fi

source_properties="${ndk_root}/source.properties"
if [[ ! -f "${source_properties}" ]] \
        || ! grep -Eq "^Pkg\.Revision[[:space:]]*=[[:space:]]*${PRISM_NDK_REVISION}$" \
            "${source_properties}"; then
    printf 'Prism sidecar requires exact Android NDK %s.\n' \
        "${PRISM_NDK_REVISION}" >&2
    exit 2
fi

cmake_bin="${PIDECK_CMAKE:-}"
ninja_bin="${PIDECK_NINJA:-}"
if [[ -z "${cmake_bin}" && -n "${ANDROID_HOME:-}" ]]; then
    cmake_bin="${ANDROID_HOME}/cmake/3.22.1/bin/cmake"
fi
if [[ -z "${ninja_bin}" && -n "${ANDROID_HOME:-}" ]]; then
    ninja_bin="${ANDROID_HOME}/cmake/3.22.1/bin/ninja"
fi
if [[ ! -x "${cmake_bin}" || ! -x "${ninja_bin}" ]]; then
    printf 'Set PIDECK_CMAKE and PIDECK_NINJA, or install Android CMake 3.22.1.\n' >&2
    exit 2
fi
cmake_version="$("${cmake_bin}" --version)"
cmake_version="${cmake_version%%$'\n'*}"
ninja_version="$("${ninja_bin}" --version)"
if [[ "${cmake_version}" != "cmake version 3.22.1-g37088a8" \
        || "${ninja_version}" != "1.10.2" ]]; then
    printf 'Prism sidecar requires Android CMake 3.22.1 and Ninja 1.10.2.\n' >&2
    exit 2
fi

strip_tool="${ndk_root}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
host_cxx="${PIDECK_HOST_CXX:-${ndk_root}/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++}"
if [[ ! -x "${strip_tool}" || ! -x "${host_cxx}" ]]; then
    printf 'Android NDK host clang++ or llvm-strip is missing.\n' >&2
    exit 2
fi

task_dir="$(mktemp -d "${TMPDIR:-/tmp}/pideck-prism-android.XXXXXX")"
trap 'rm -rf "${task_dir}"' EXIT
source_dir="${task_dir}/source"
build_dir="${task_dir}/build"

archive="${PIDECK_PRISM_SOURCE_ARCHIVE:-${task_dir}/source.tar.gz}"
if [[ ! -f "${archive}" ]]; then
    curl --fail --location --retry 3 \
        "https://codeload.github.com/PrismML-Eng/llama.cpp/tar.gz/${PRISM_COMMIT}" \
        --output "${archive}"
fi
archive_hash="$(sha256sum "${archive}")"
if [[ "${archive_hash%% *}" != "84ec38b7e7fb45a9f076e923e064e967e7c30b946b71b3b778444b05e8459c3c" ]]; then
    printf 'Prism source archive SHA-256 mismatch.\n' >&2
    exit 3
fi
mkdir -p "${source_dir}"
tar -xzf "${archive}" --strip-components=1 -C "${source_dir}"
source_date_epoch=1790231541
common_flags="-O3 -DNDEBUG -ffile-prefix-map=${source_dir}=. -ffile-prefix-map=${build_dir}=."
SOURCE_DATE_EPOCH="${source_date_epoch}" "${cmake_bin}" \
    -S "${source_dir}" \
    -B "${build_dir}" \
    -G Ninja \
    -DCMAKE_MAKE_PROGRAM="${ninja_bin}" \
    -DCMAKE_TOOLCHAIN_FILE="${ndk_root}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-28 \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS_RELEASE="${common_flags}" \
    -DCMAKE_CXX_FLAGS_RELEASE="${common_flags}" \
    -DBUILD_SHARED_LIBS=OFF \
    -DGGML_CCACHE=OFF \
    -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 \
    -DGGML_OPENMP=OFF \
    -DGGML_VULKAN=OFF \
    -DGGML_OPENCL=OFF \
    -DLLAMA_CURL=OFF \
    -DLLAMA_OPENSSL=OFF \
    -DLLAMA_SUBPROCESS=OFF \
    -DLLAMA_BUILD_APP=OFF \
    -DLLAMA_BUILD_SERVER=ON \
    -DLLAMA_BUILD_NUMBER=10734 \
    -DLLAMA_BUILD_COMMIT=842b188 \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_UI=OFF \
    -DLLAMA_USE_PREBUILT_UI=OFF \
    -DHOST_CXX_COMPILER="${host_cxx}" \
    -DLLAMA_LLGUIDANCE=OFF \
    -DLLAMA_BUILD_MTMD=OFF
SOURCE_DATE_EPOCH="${source_date_epoch}" "${cmake_bin}" \
    --build "${build_dir}" --target llama-server --parallel "${PIDECK_BUILD_JOBS:-4}"

mkdir -p "$(dirname "${destination}")"
install -m 0755 "${build_dir}/bin/llama-server" "${destination}"
# LLD derives the build ID before the final strip, so otherwise-identical
# binaries built under different absolute runner paths retain different note
# bytes. The note is not used by Android; removing it makes the pinned ELF
# reproducible without changing any executable section.
"${strip_tool}" --strip-unneeded "${destination}"
"${strip_tool}" --remove-section=.note.gnu.build-id "${destination}"

printf 'Built %s (%s) into %s\n' \
    "${PRISM_BUILD}" "${PRISM_COMMIT}" "${destination}"
sha256sum "${destination}"
