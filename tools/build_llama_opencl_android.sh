#!/usr/bin/env bash
set -euo pipefail

# Build an isolated Adreno OpenCL candidate. The output is for shell-owned
# device probes only and is never copied into the production APK.
LLAMA_REF="b10687"
LLAMA_COMMIT="c841aeeb8bb2fe417038dadfa9b007cf1a9ef950"
LLAMA_ARCHIVE_SHA256="03798972d2a6fe4a77288e897517f3a770d0057b9bc58e46cbe3eebc2b166b0f"
LLAMA_ARCHIVE_URL="https://github.com/ggml-org/llama.cpp/archive/refs/tags/${LLAMA_REF}.tar.gz"
OPENCL_HEADERS_COMMIT="c9c8ccfab584f9f7610057c4633dbd3df7e012cc"
OPENCL_HEADERS_ARCHIVE_SHA256="de3b6b8e54a5fec63c6bdfd9f52b087c255af36f6cc2bc81246fae59888c878c"
OPENCL_HEADERS_ARCHIVE_URL="https://github.com/KhronosGroup/OpenCL-Headers/archive/${OPENCL_HEADERS_COMMIT}.tar.gz"
OPENCL_LOADER_COMMIT="18fdcd58286376124f938948aa8ed156079c1c16"
OPENCL_LOADER_ARCHIVE_SHA256="37a3088930c302b41f04e73957e5a481dfa6fb9fa84e5e1e9ceedbda76b1ef69"
OPENCL_LOADER_ARCHIVE_URL="https://github.com/KhronosGroup/OpenCL-ICD-Loader/archive/${OPENCL_LOADER_COMMIT}.tar.gz"
ANDROID_NDK_REVISION="28.2.13676358"
ANDROID_CMAKE_VERSION="cmake version 3.22.1-g37088a8"
ANDROID_NINJA_VERSION="1.10.2"
SOURCE_DATE_EPOCH="1788379200"

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="${1:-${repo_dir}/build/experimental/llama-b10687-opencl-adreno}"
if [[ $# -gt 1 ]]; then
    printf 'Usage: %s [output-directory]\n' "$0" >&2
    exit 2
fi

ndk_root="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
if [[ -z "${ndk_root}" ]] \
        || [[ ! -f "${ndk_root}/source.properties" ]] \
        || ! grep -Eq "^Pkg\.Revision[[:space:]]*=[[:space:]]*${ANDROID_NDK_REVISION}$" \
            "${ndk_root}/source.properties"; then
    printf 'OpenCL candidate requires Android NDK %s.\n' "${ANDROID_NDK_REVISION}" >&2
    exit 2
fi
sdk_root="$(cd "${ndk_root}/../.." && pwd)"
cmake_bin="${PIDECK_CMAKE:-${sdk_root}/cmake/3.22.1/bin/cmake}"
ninja_bin="${PIDECK_NINJA:-${sdk_root}/cmake/3.22.1/bin/ninja}"
if [[ ! -x "${cmake_bin}" || ! -x "${ninja_bin}" ]]; then
    printf 'Android CMake 3.22.1 and Ninja are required.\n' >&2
    exit 2
fi
cmake_actual="$("${cmake_bin}" --version)"
cmake_actual="${cmake_actual%%$'\n'*}"
if [[ "${cmake_actual}" != "${ANDROID_CMAKE_VERSION}" ]] \
        || [[ "$("${ninja_bin}" --version)" != "${ANDROID_NINJA_VERSION}" ]]; then
    printf 'Unexpected CMake or Ninja version.\n' >&2
    exit 2
fi

case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) host_tag="linux-x86_64" ;;
    *)
        printf 'This builder currently supports a Linux x86_64 host.\n' >&2
        exit 2
        ;;
esac
llvm_root="${ndk_root}/toolchains/llvm/prebuilt/${host_tag}"
strip_tool="${llvm_root}/bin/llvm-strip"
mapfile -t omp_candidates < <(
    printf '%s\n' "${llvm_root}"/lib/clang/*/lib/linux/aarch64/libomp.so | sort -V
)
libomp="${omp_candidates[-1]:-}"
libcxx="${llvm_root}/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
if [[ ! -x "${strip_tool}" || ! -f "${libomp}" || ! -f "${libcxx}" ]]; then
    printf 'Selected NDK is missing llvm-strip, libomp, or libc++_shared.\n' >&2
    exit 2
fi

task_dir="$(mktemp -d "${TMPDIR:-/tmp}/pideck-opencl-android.XXXXXX")"
trap 'rm -rf -- "${task_dir}"' EXIT

fetch_archive() {
    local supplied="$1"
    local url="$2"
    local sha256="$3"
    local destination="$4"
    if [[ -n "${supplied}" ]]; then
        cp "${supplied}" "${destination}"
    elif command -v wget >/dev/null 2>&1; then
        wget --quiet --output-document="${destination}" "${url}"
    else
        curl --fail --location --retry 3 --output "${destination}" "${url}"
    fi
    printf '%s  %s\n' "${sha256}" "${destination}" | sha256sum --check -
}

llama_archive="${task_dir}/llama.tar.gz"
headers_archive="${task_dir}/opencl-headers.tar.gz"
loader_archive="${task_dir}/opencl-loader.tar.gz"
fetch_archive "${PIDECK_LLAMA_OPENCL_ARCHIVE:-}" \
    "${LLAMA_ARCHIVE_URL}" "${LLAMA_ARCHIVE_SHA256}" "${llama_archive}"
fetch_archive "${PIDECK_OPENCL_HEADERS_ARCHIVE:-}" \
    "${OPENCL_HEADERS_ARCHIVE_URL}" "${OPENCL_HEADERS_ARCHIVE_SHA256}" "${headers_archive}"
fetch_archive "${PIDECK_OPENCL_LOADER_ARCHIVE:-}" \
    "${OPENCL_LOADER_ARCHIVE_URL}" "${OPENCL_LOADER_ARCHIVE_SHA256}" "${loader_archive}"
tar -xzf "${llama_archive}" -C "${task_dir}"
tar -xzf "${headers_archive}" -C "${task_dir}"
tar -xzf "${loader_archive}" -C "${task_dir}"

llama_source="${task_dir}/llama.cpp-${LLAMA_REF}"
headers_source="${task_dir}/OpenCL-Headers-${OPENCL_HEADERS_COMMIT}"
loader_source="${task_dir}/OpenCL-ICD-Loader-${OPENCL_LOADER_COMMIT}"
loader_build="${task_dir}/opencl-loader-build"
llama_build="${task_dir}/llama-build"
reproducible_flags="-g0 -ffile-prefix-map=${task_dir}=."

SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH}" "${cmake_bin}" \
    -S "${loader_source}" \
    -B "${loader_build}" \
    -G Ninja \
    -DCMAKE_MAKE_PROGRAM="${ninja_bin}" \
    -DCMAKE_TOOLCHAIN_FILE="${ndk_root}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-28 \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS="${reproducible_flags}" \
    -DCMAKE_CXX_FLAGS="${reproducible_flags}" \
    -DCMAKE_SHARED_LINKER_FLAGS='-Wl,--build-id=none' \
    -DOPENCL_ICD_LOADER_HEADERS_DIR="${headers_source}" \
    -DOPENCL_ICD_LOADER_BUILD_SHARED_LIBS=ON \
    -DOPENCL_ICD_LOADER_BUILD_TESTING=OFF \
    -DENABLE_OPENCL_LAYERS=OFF
"${cmake_bin}" --build "${loader_build}" --target OpenCL --parallel 8

architecture_flags="-march=armv8.2-a+dotprod+fp16 -fvectorize -ffp-model=fast -fno-finite-math-only -flto"
SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH}" "${cmake_bin}" \
    -S "${llama_source}" \
    -B "${llama_build}" \
    -G Ninja \
    -DCMAKE_MAKE_PROGRAM="${ninja_bin}" \
    -DCMAKE_TOOLCHAIN_FILE="${ndk_root}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-28 \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_FLAGS="${architecture_flags} ${reproducible_flags}" \
    -DCMAKE_CXX_FLAGS="${architecture_flags} ${reproducible_flags}" \
    -DCMAKE_C_FLAGS_RELEASE='-O3 -DNDEBUG' \
    -DCMAKE_CXX_FLAGS_RELEASE='-O3 -DNDEBUG' \
    -DCMAKE_EXE_LINKER_FLAGS='-flto -Wl,--build-id=none' \
    -DLLAMA_BUILD_NUMBER=10687 \
    -DLLAMA_BUILD_COMMIT="${LLAMA_COMMIT}" \
    -DBUILD_SHARED_LIBS=OFF \
    -DGGML_CCACHE=OFF \
    -DGGML_NATIVE=OFF \
    -DGGML_CPU_ALL_VARIANTS=OFF \
    -DGGML_CPU_KLEIDIAI=OFF \
    -DGGML_OPENMP=ON \
    -DGGML_OPENCL=ON \
    -DGGML_OPENCL_TARGET_VERSION=300 \
    -DGGML_OPENCL_EMBED_KERNELS=ON \
    -DGGML_OPENCL_USE_ADRENO_KERNELS=ON \
    -DGGML_OPENCL_USE_ADRENO_BIN_KERNELS=OFF \
    -DOpenCL_INCLUDE_DIR="${headers_source}" \
    -DOpenCL_LIBRARY="${loader_build}/libOpenCL.so" \
    -DLLAMA_BUILD_TESTS=ON \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_TOOLS=ON \
    -DLLAMA_BUILD_SERVER=ON \
    -DLLAMA_BUILD_UI=OFF \
    -DLLAMA_USE_PREBUILT_UI=OFF \
    -DLLAMA_CURL=OFF \
    -DLLAMA_OPENSSL=OFF

build_jobs="${PIDECK_BUILD_JOBS:-$(getconf _NPROCESSORS_ONLN)}"
if (( build_jobs > 8 )); then
    build_jobs=8
fi
SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH}" "${cmake_bin}" \
    --build "${llama_build}" \
    --target llama-bench llama-server test-backend-ops \
    --parallel "${build_jobs}"

mkdir -p "${output_dir}"
for executable in llama-bench llama-server test-backend-ops; do
    install -m 0755 "${llama_build}/bin/${executable}" "${output_dir}/${executable}"
done
install -m 0755 "${libomp}" "${output_dir}/libomp.so"
install -m 0755 "${libcxx}" "${output_dir}/libc++_shared.so"
"${strip_tool}" --strip-unneeded \
    "${output_dir}/llama-bench" \
    "${output_dir}/llama-server" \
    "${output_dir}/test-backend-ops" \
    "${output_dir}/libomp.so" \
    "${output_dir}/libc++_shared.so"

printf 'Built isolated llama.cpp %s Adreno OpenCL candidate into %s\n' \
    "${LLAMA_REF}" "${output_dir}"
find "${output_dir}" -type f -print0 | sort -z | xargs -0 sha256sum
