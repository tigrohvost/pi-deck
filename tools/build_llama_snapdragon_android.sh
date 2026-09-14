#!/usr/bin/env bash
set -euo pipefail

# Build an isolated Snapdragon Hexagon candidate. This intentionally never writes
# app/src/main/jniLibs: HTP must pass device correctness and performance gates
# before it can become part of the production runtime.
LLAMA_BUILD="b10092"
LLAMA_COMMIT="3ce7da2c852c538c4c5f9806da27029cf8c9cc4a"
LLAMA_SOURCE_ARCHIVE_SHA256="b7bd2871ffcb467cc1445b5a5de8dda18fbeefd0a3c42c249a9650209a4da346"
LLAMA_SOURCE_ARCHIVE_URL="https://github.com/ggml-org/llama.cpp/archive/refs/tags/${LLAMA_BUILD}.tar.gz"
LLAMA_SOURCE_DATE_EPOCH="1784712400"
LLAMA_NDK_REVISION="28.2.13676358"
HEXAGON_SDK_VERSION="6.6.0.0"
HEXAGON_SDK_ARCHIVE_SHA256="4a916e42c1dab9efdf2e58773f901ea780fa43c907bf054ab76572a3b3d942f4"
HEXAGON_SDK_METADATA_SHA256="953d8627d6423f61827690980696c09c9e9a07a234d9dd3b1d64c691d3756edc"
HEXAGON_TOOLS_VERSION="19.0.07"
CMAKE_VERSION="cmake version 3.22.1-g37088a8"
NINJA_VERSION="1.10.2"

PATCH_NAMES=(
    0001-android-enable-linux-thread-affinity.patch
    0002-server-attach-cpu-threadpools.patch
    0003-hexagon-use-android-cmake-toolchain.patch
)
PATCH_SHA256=(
    bfd2eac7e3eec8ab1c92694cbe9a18ee24c02bb7789e327644e786f55f1ab7a1
    4edc60ac71034e0f4f2f74a216e7224b0d85411b4d0c0ec94bf2a8db9e833e18
    368328bb53b40995ee0b14d891beb8d90d62a0ba22b2487d37bb130d3bfdd60d
)

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="${1:-${repo_dir}/build/experimental/llama-b10092-htp-v73}"
if [[ $# -gt 1 ]]; then
    printf 'Usage: %s [output-directory]\n' "$0" >&2
    exit 2
fi

hexagon_sdk_root="${PIDECK_HEXAGON_SDK_ROOT:-${HEXAGON_SDK_ROOT:-}}"
if [[ -z "${hexagon_sdk_root}" || ! -f "${hexagon_sdk_root}/hexagon_sdk.json" ]]; then
    printf 'Set PIDECK_HEXAGON_SDK_ROOT to extracted Hexagon SDK %s.\n' \
        "${HEXAGON_SDK_VERSION}" >&2
    printf 'Expected official archive SHA-256: %s\n' \
        "${HEXAGON_SDK_ARCHIVE_SHA256}" >&2
    exit 2
fi
printf '%s  %s\n' "${HEXAGON_SDK_METADATA_SHA256}" \
    "${hexagon_sdk_root}/hexagon_sdk.json" | sha256sum --check -
hexagon_tools_root="${hexagon_sdk_root}/tools/HEXAGON_Tools/${HEXAGON_TOOLS_VERSION}"
hexagon_clang="${hexagon_tools_root}/Tools/bin/hexagon-clang"
if [[ ! -x "${hexagon_clang}" ]] \
        || ! "${hexagon_clang}" --version | head -1 \
            | grep -Fxq "QuIC LLVM Hexagon Clang version ${HEXAGON_TOOLS_VERSION}"; then
    printf 'Hexagon Tools %s are required.\n' "${HEXAGON_TOOLS_VERSION}" >&2
    exit 2
fi

ndk_root="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
source_properties="${ndk_root}/source.properties"
if [[ -z "${ndk_root}" || ! -f "${source_properties}" ]] \
        || ! grep -Eq "^Pkg\.Revision[[:space:]]*=[[:space:]]*${LLAMA_NDK_REVISION}$" \
            "${source_properties}"; then
    printf 'Snapdragon llama.cpp requires exact Android NDK %s.\n' \
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
    printf 'Snapdragon llama.cpp requires Android CMake 3.22.1 and Ninja 1.10.2.\n' >&2
    exit 2
fi

case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) host_tag="linux-x86_64" ;;
    *)
        printf 'This builder currently supports a Linux x86_64 host.\n' >&2
        exit 2
        ;;
esac
strip_tool="${ndk_root}/toolchains/llvm/prebuilt/${host_tag}/bin/llvm-strip"
if [[ ! -x "${strip_tool}" ]]; then
    printf 'Android NDK llvm-strip is missing.\n' >&2
    exit 2
fi

task_dir="$(mktemp -d "${TMPDIR:-/tmp}/pideck-llama-hexagon.XXXXXX")"
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

architecture_flags="-march=armv8.7a+fp16+dotprod+i8mm -fvectorize -ffp-model=fast -fno-finite-math-only -flto -D_GNU_SOURCE"
reproducible_flags="-g0 -ffile-prefix-map=${source_dir}=. -ffile-prefix-map=${build_dir}=."
SOURCE_DATE_EPOCH="${LLAMA_SOURCE_DATE_EPOCH}" "${cmake_bin}" \
    -S "${source_dir}" \
    -B "${build_dir}" \
    -G Ninja \
    -DCMAKE_MAKE_PROGRAM="${ninja_bin}" \
    -DCMAKE_TOOLCHAIN_FILE="${ndk_root}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-31 \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS="${architecture_flags} ${reproducible_flags}" \
    -DCMAKE_CXX_FLAGS="${architecture_flags} ${reproducible_flags}" \
    -DCMAKE_C_FLAGS_RELEASE='-O3 -DNDEBUG' \
    -DCMAKE_CXX_FLAGS_RELEASE='-O3 -DNDEBUG' \
    -DCMAKE_EXE_LINKER_FLAGS='-flto -Wl,--build-id=none' \
    -DCMAKE_SHARED_LINKER_FLAGS='-flto -Wl,--build-id=none' \
    -DLLAMA_BUILD_NUMBER=10092 \
    -DLLAMA_BUILD_COMMIT=3ce7da2-pideck-affinity1-htpv73 \
    -DBUILD_SHARED_LIBS=ON \
    -DGGML_CCACHE=OFF \
    -DGGML_CPU_ALL_VARIANTS=OFF \
    -DGGML_HEXAGON=ON \
    -DGGML_NATIVE=OFF \
    -DGGML_OPENCL=OFF \
    -DGGML_OPENMP=OFF \
    -DHEXAGON_SDK_ROOT="${hexagon_sdk_root}" \
    -DHEXAGON_TOOLS_ROOT="${hexagon_tools_root}" \
    -DPREBUILT_LIB_DIR=android_aarch64 \
    -DLLAMA_BUILD_APP=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_SERVER=ON \
    -DLLAMA_BUILD_TOOLS=ON \
    -DLLAMA_CURL=OFF \
    -DLLAMA_OPENSSL=OFF \
    -DLLAMA_BUILD_UI=OFF \
    -DLLAMA_USE_PREBUILT_UI=OFF

build_jobs="${PIDECK_BUILD_JOBS:-$(getconf _NPROCESSORS_ONLN)}"
if (( build_jobs > 8 )); then
    build_jobs=8
fi
SOURCE_DATE_EPOCH="${LLAMA_SOURCE_DATE_EPOCH}" "${cmake_bin}" \
    --build "${build_dir}" \
    --target llama-bench llama-server htp-v73 \
    --parallel "${build_jobs}"

mkdir -p "${output_dir}/bin" "${output_dir}/lib"
for executable in llama-bench llama-server; do
    install -m 0755 "${build_dir}/bin/${executable}" "${output_dir}/bin/${executable}"
    "${strip_tool}" --strip-unneeded "${output_dir}/bin/${executable}"
done
android_libraries=(
    libggml-base.so
    libggml-cpu.so
    libggml-hexagon.so
    libggml.so
    libllama-bench-impl.so
    libllama-common.so
    libllama-server-impl.so
    libllama.so
    libmtmd.so
)
for library in "${android_libraries[@]}"; do
    install -m 0755 "${build_dir}/bin/${library}" "${output_dir}/lib/${library}"
    "${strip_tool}" --strip-unneeded "${output_dir}/lib/${library}"
done
install -m 0755 "${build_dir}/ggml/src/ggml-hexagon/libggml-htp-v73.so" \
    "${output_dir}/lib/libggml-htp-v73.so"
install -m 0755 "${build_dir}/ggml/src/ggml-hexagon/ship/libc++_shared.so" \
    "${output_dir}/lib/libc++_shared.so"

printf 'Built isolated llama.cpp %s Hexagon v73 candidate into %s\n' \
    "${LLAMA_BUILD}" "${output_dir}"
printf 'Hexagon SDK archive SHA-256: %s\n' "${HEXAGON_SDK_ARCHIVE_SHA256}"
find "${output_dir}" -type f -print0 | sort -z | xargs -0 sha256sum
