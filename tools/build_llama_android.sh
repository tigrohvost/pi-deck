#!/usr/bin/env bash
set -euo pipefail

# Rebuild the stock Android runtime from the exact upstream source and the two
# PI//DECK affinity fixes. The output keeps upstream's dynamic CPU dispatcher so
# Android selects the fastest compatible Arm backend at runtime.
LLAMA_BUILD="b10092"
LLAMA_COMMIT="3ce7da2c852c538c4c5f9806da27029cf8c9cc4a"
LLAMA_SOURCE_ARCHIVE_SHA256="b7bd2871ffcb467cc1445b5a5de8dda18fbeefd0a3c42c249a9650209a4da346"
LLAMA_SOURCE_ARCHIVE_URL="https://github.com/ggml-org/llama.cpp/archive/refs/tags/${LLAMA_BUILD}.tar.gz"
LLAMA_SOURCE_DATE_EPOCH="1784712400"
LLAMA_NDK_REVISION="27.1.12297006"
CMAKE_VERSION="cmake version 3.22.1-g37088a8"
NINJA_VERSION="1.10.2"

PATCH_NAMES=(
    0001-android-enable-linux-thread-affinity.patch
    0002-server-attach-cpu-threadpools.patch
)
PATCH_SHA256=(
    bfd2eac7e3eec8ab1c92694cbe9a18ee24c02bb7789e327644e786f55f1ab7a1
    4edc60ac71034e0f4f2f74a216e7224b0d85411b4d0c0ec94bf2a8db9e833e18
)

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="${1:-${repo_dir}/app/src/main/jniLibs/arm64-v8a}"
if [[ $# -gt 1 ]]; then
    printf 'Usage: %s [output-directory]\n' "$0" >&2
    exit 2
fi

ndk_root="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
source_properties="${ndk_root}/source.properties"
if [[ -z "${ndk_root}" || ! -f "${source_properties}" ]] \
        || ! grep -Eq "^Pkg\.Revision[[:space:]]*=[[:space:]]*${LLAMA_NDK_REVISION}$" \
            "${source_properties}"; then
    printf 'Stock llama.cpp requires exact Android NDK %s.\n' \
        "${LLAMA_NDK_REVISION}" >&2
    exit 2
fi

sdk_root="$(cd "${ndk_root}/../.." && pwd)"
cmake_bin="${PIDECK_CMAKE:-${sdk_root}/cmake/3.22.1/bin/cmake}"
ninja_bin="${PIDECK_NINJA:-${sdk_root}/cmake/3.22.1/bin/ninja}"
if [[ ! -x "${cmake_bin}" || ! -x "${ninja_bin}" ]]; then
    printf 'Set PIDECK_CMAKE and PIDECK_NINJA, or install Android CMake 3.22.1.\n' >&2
    exit 2
fi
cmake_actual="$("${cmake_bin}" --version)"
cmake_actual="${cmake_actual%%$'\n'*}"
ninja_actual="$("${ninja_bin}" --version)"
if [[ "${cmake_actual}" != "${CMAKE_VERSION}" || "${ninja_actual}" != "${NINJA_VERSION}" ]]; then
    printf 'Stock llama.cpp requires Android CMake 3.22.1 and Ninja 1.10.2.\n' >&2
    exit 2
fi

case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) host_tag="linux-x86_64" ;;
    *)
        printf 'This reproducible builder currently supports a Linux x86_64 host.\n' >&2
        exit 2
        ;;
esac
strip_tool="${ndk_root}/toolchains/llvm/prebuilt/${host_tag}/bin/llvm-strip"
if [[ ! -x "${strip_tool}" ]]; then
    printf 'Android NDK llvm-strip is missing.\n' >&2
    exit 2
fi

task_dir="$(mktemp -d "${TMPDIR:-/tmp}/pideck-llama-android.XXXXXX")"
trap 'rm -rf -- "${task_dir}"' EXIT
archive="${task_dir}/llama.cpp-${LLAMA_BUILD}.tar.gz"
if [[ -n "${PIDECK_LLAMA_SOURCE_ARCHIVE:-}" ]]; then
    cp "${PIDECK_LLAMA_SOURCE_ARCHIVE}" "${archive}"
elif command -v wget >/dev/null 2>&1; then
    wget --quiet --output-document="${archive}" "${LLAMA_SOURCE_ARCHIVE_URL}"
else
    curl --fail --location --retry 3 --output "${archive}" "${LLAMA_SOURCE_ARCHIVE_URL}"
fi
printf '%s  %s\n' "${LLAMA_SOURCE_ARCHIVE_SHA256}" "${archive}" | sha256sum --check -
tar -xzf "${archive}" -C "${task_dir}"

source_dir="${task_dir}/llama.cpp-${LLAMA_BUILD}"
build_dir="${task_dir}/build"
for index in "${!PATCH_NAMES[@]}"; do
    patch_path="${repo_dir}/third_party/llama.cpp/patches/${PATCH_NAMES[index]}"
    printf '%s  %s\n' "${PATCH_SHA256[index]}" "${patch_path}" | sha256sum --check -
    patch --directory="${source_dir}" --strip=1 --batch < "${patch_path}"
done

common_flags="-O3 -DNDEBUG -g0 -ffile-prefix-map=${source_dir}=. -ffile-prefix-map=${build_dir}=."
SOURCE_DATE_EPOCH="${LLAMA_SOURCE_DATE_EPOCH}" "${cmake_bin}" \
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
    -DCMAKE_EXE_LINKER_FLAGS=-Wl,--build-id=none \
    -DCMAKE_SHARED_LINKER_FLAGS=-Wl,--build-id=none \
    -DLLAMA_BUILD_NUMBER=10092 \
    -DLLAMA_BUILD_COMMIT=3ce7da2-pideck-affinity1 \
    -DBUILD_SHARED_LIBS=ON \
    -DGGML_BACKEND_DL=ON \
    -DGGML_CCACHE=OFF \
    -DGGML_CPU_ALL_VARIANTS=ON \
    -DGGML_NATIVE=OFF \
    -DGGML_OPENMP=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_SERVER=ON \
    -DLLAMA_CURL=OFF \
    -DLLAMA_BUILD_UI=OFF \
    -DLLAMA_USE_PREBUILT_UI=OFF

build_jobs="${PIDECK_BUILD_JOBS:-$(getconf _NPROCESSORS_ONLN)}"
if (( build_jobs > 8 )); then
    build_jobs=8
fi
SOURCE_DATE_EPOCH="${LLAMA_SOURCE_DATE_EPOCH}" "${cmake_bin}" \
    --build "${build_dir}" --target llama-server --parallel "${build_jobs}"

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
mkdir -p "${output_dir}"
for file in "${files[@]}"; do
    install -m 0755 "${build_dir}/bin/${file}" "${output_dir}/${file}"
    "${strip_tool}" --strip-unneeded "${output_dir}/${file}"
done
install -m 0755 "${build_dir}/bin/llama-server" \
    "${output_dir}/libpideck_llama_server.so"
"${strip_tool}" --strip-unneeded "${output_dir}/libpideck_llama_server.so"

printf 'Built llama.cpp %s (%s) with PI//DECK affinity patches into %s\n' \
    "${LLAMA_BUILD}" "${LLAMA_COMMIT}" "${output_dir}"
sha256sum "${output_dir}"/*.so
