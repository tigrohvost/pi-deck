#!/usr/bin/env bash
set -euo pipefail

# Nanbeige4.2 is not supported by upstream llama.cpp. Keep its official fork
# isolated in one statically linked sidecar instead of replacing the runtime
# used by every other model.
NANBEIGE_REPOSITORY="https://github.com/Nanbeige/llama.cpp.git"
NANBEIGE_COMMIT="c6640a1c0cf7b38df342b67021a3900b04d092e7"
NANBEIGE_BUILD="nanbeige42-c6640a1"
NANBEIGE_NDK_REVISION="28.2.13676358"

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
destination="${1:-${repo_dir}/app/src/main/jniLibs/arm64-v8a/libpideck_nanbeige_server.so}"
ndk_root="${PIDECK_NANBEIGE_NDK_ROOT:-}"
if [[ -z "${ndk_root}" ]]; then
    printf 'Set PIDECK_NANBEIGE_NDK_ROOT to Android NDK %s.\n' \
        "${NANBEIGE_NDK_REVISION}" >&2
    exit 2
fi

source_properties="${ndk_root}/source.properties"
if [[ ! -f "${source_properties}" ]] \
        || ! grep -Eq "^Pkg\.Revision[[:space:]]*=[[:space:]]*${NANBEIGE_NDK_REVISION}$" \
            "${source_properties}"; then
    printf 'Nanbeige sidecar requires exact Android NDK %s.\n' \
        "${NANBEIGE_NDK_REVISION}" >&2
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
    printf 'Nanbeige sidecar requires Android CMake 3.22.1 and Ninja 1.10.2.\n' >&2
    exit 2
fi

strip_tool="${ndk_root}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
host_cxx="${ndk_root}/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++"
if [[ ! -x "${strip_tool}" || ! -x "${host_cxx}" ]]; then
    printf 'Android NDK host clang++ or llvm-strip is missing.\n' >&2
    exit 2
fi

task_dir="$(mktemp -d "${TMPDIR:-/tmp}/pideck-nanbeige-android.XXXXXX")"
trap 'rm -rf "${task_dir}"' EXIT
source_dir="${task_dir}/source"
build_dir="${task_dir}/build"

git init --quiet "${source_dir}"
git -C "${source_dir}" remote add origin "${NANBEIGE_REPOSITORY}"
git -C "${source_dir}" fetch --quiet --depth 1 origin "${NANBEIGE_COMMIT}"
git -C "${source_dir}" checkout --quiet --detach FETCH_HEAD
if [[ "$(git -C "${source_dir}" rev-parse HEAD)" != "${NANBEIGE_COMMIT}" ]]; then
    printf 'Fetched Nanbeige source does not match the pinned commit.\n' >&2
    exit 3
fi

source_date_epoch="$(git -C "${source_dir}" show -s --format=%ct HEAD)"
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
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_UI=OFF \
    -DLLAMA_USE_PREBUILT_UI=OFF \
    -DHOST_CXX_COMPILER="${host_cxx}" \
    -DLLAMA_LLGUIDANCE=OFF \
    -DLLAMA_BUILD_MTMD=OFF
SOURCE_DATE_EPOCH="${source_date_epoch}" "${cmake_bin}" \
    --build "${build_dir}" --target llama-server --parallel

mkdir -p "$(dirname "${destination}")"
install -m 0755 "${build_dir}/bin/llama-server" "${destination}"
# LLD derives the build ID before the final strip, so otherwise-identical
# binaries built under different absolute runner paths retain different note
# bytes. The note is not used by Android; removing it makes the pinned ELF
# reproducible without changing any executable section.
"${strip_tool}" --strip-unneeded "${destination}"
"${strip_tool}" --remove-section=.note.gnu.build-id "${destination}"

printf 'Built %s (%s) into %s\n' \
    "${NANBEIGE_BUILD}" "${NANBEIGE_COMMIT}" "${destination}"
sha256sum "${destination}"
