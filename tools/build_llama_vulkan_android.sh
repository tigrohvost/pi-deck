#!/usr/bin/env bash
set -euo pipefail

# Build a device-only upstream llama.cpp Vulkan candidate. The production APK
# remains on the separately pinned CPU runtime until the accelerator probe passes.
LLAMA_REF="b10333"
LLAMA_COMMIT="08659901c43b51de735740f1cf61bb82fbe0c4e4"
SPIRV_HEADERS_REF="vulkan-sdk-1.3.275.0"
SPIRV_HEADERS_COMMIT="1c6bb2743599e6eb6f37b2969acc0aef812e32e3"
VULKAN_HEADERS_REF="v1.3.275"
VULKAN_HEADERS_COMMIT="217e93c664ec6704ec2d8c36fa116c1a4a1e2d40"

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="${1:-${repo_dir}/build/runtime-candidates/vulkan-b10333-adreno740}"
if [[ $# -gt 1 ]]; then
    printf 'Usage: %s [output-directory]\n' "$0" >&2
    exit 2
fi

pideck_ndk="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
if [[ -z "${pideck_ndk}" || ! -f "${pideck_ndk}/build/cmake/android.toolchain.cmake" ]]; then
    printf 'Set ANDROID_NDK_ROOT to an Android NDK (r28c was verified).\n' >&2
    exit 2
fi

pideck_sdk_root="$(cd "${pideck_ndk}/../.." && pwd)"
pideck_cmake="${PIDECK_CMAKE:-}"
if [[ -z "${pideck_cmake}" ]]; then
    if command -v cmake >/dev/null 2>&1; then
        pideck_cmake="$(command -v cmake)"
    else
        mapfile -t pideck_cmake_candidates < <(
            printf '%s\n' "${pideck_sdk_root}"/cmake/*/bin/cmake | sort -V
        )
        pideck_cmake="${pideck_cmake_candidates[-1]:-}"
    fi
fi
if [[ ! -x "${pideck_cmake}" ]]; then
    printf 'No executable CMake found; set PIDECK_CMAKE.\n' >&2
    exit 2
fi

pideck_ninja="${PIDECK_NINJA:-$(dirname "${pideck_cmake}")/ninja}"
if [[ ! -x "${pideck_ninja}" ]]; then
    printf 'No executable Ninja found; set PIDECK_NINJA.\n' >&2
    exit 2
fi

case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) pideck_host_tag="linux-x86_64" ;;
    *)
        printf 'This reproducible builder currently supports a Linux x86_64 host.\n' >&2
        exit 2
        ;;
esac

pideck_llvm_root="${pideck_ndk}/toolchains/llvm/prebuilt/${pideck_host_tag}"
pideck_glslc="${pideck_ndk}/shader-tools/${pideck_host_tag}/glslc"
pideck_strip="${pideck_llvm_root}/bin/llvm-strip"
if [[ ! -x "${pideck_glslc}" || ! -x "${pideck_strip}" ]]; then
    printf 'The selected NDK has no host glslc or llvm-strip.\n' >&2
    exit 2
fi

mapfile -t pideck_omp_candidates < <(
    printf '%s\n' "${pideck_llvm_root}"/lib/clang/*/lib/linux/aarch64/libomp.so | sort -V
)
pideck_libomp="${pideck_omp_candidates[-1]:-}"
pideck_libcxx="${pideck_llvm_root}/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
if [[ ! -f "${pideck_libomp}" || ! -f "${pideck_libcxx}" ]]; then
    printf 'The selected NDK has no arm64 libomp.so or libc++_shared.so.\n' >&2
    exit 2
fi

task_dir="$(mktemp -d "${TMPDIR:-/tmp}/pideck-vulkan-android.XXXXXX")"
trap 'rm -rf "${task_dir}"' EXIT

checkout_pinned() {
    local url="$1"
    local ref="$2"
    local expected="$3"
    local destination="$4"
    git clone --quiet --branch "${ref}" --depth 1 "${url}" "${destination}"
    local actual
    actual="$(git -C "${destination}" rev-parse HEAD)"
    if [[ "${actual}" != "${expected}" ]]; then
        printf 'Pinned checkout mismatch for %s: expected %s, got %s\n' \
            "${url}" "${expected}" "${actual}" >&2
        exit 3
    fi
}

llama_source="${task_dir}/llama.cpp"
spirv_source="${task_dir}/SPIRV-Headers"
vulkan_source="${task_dir}/Vulkan-Headers"
checkout_pinned \
    "https://github.com/ggml-org/llama.cpp.git" \
    "${LLAMA_REF}" "${LLAMA_COMMIT}" "${llama_source}"
checkout_pinned \
    "https://github.com/KhronosGroup/SPIRV-Headers.git" \
    "${SPIRV_HEADERS_REF}" "${SPIRV_HEADERS_COMMIT}" "${spirv_source}"
checkout_pinned \
    "https://github.com/KhronosGroup/Vulkan-Headers.git" \
    "${VULKAN_HEADERS_REF}" "${VULKAN_HEADERS_COMMIT}" "${vulkan_source}"
spirv_build="${task_dir}/spirv-build"
spirv_install="${task_dir}/spirv-install"
"${pideck_cmake}" \
    -S "${spirv_source}" \
    -B "${spirv_build}" \
    -G Ninja \
    "-DCMAKE_MAKE_PROGRAM=${pideck_ninja}" \
    "-DCMAKE_INSTALL_PREFIX=${spirv_install}" \
    -DSPIRV_HEADERS_SKIP_EXAMPLES=ON
"${pideck_cmake}" --install "${spirv_build}"

llama_build="${task_dir}/llama-build"
"${pideck_cmake}" \
    -S "${llama_source}" \
    -B "${llama_build}" \
    -G Ninja \
    "-DCMAKE_MAKE_PROGRAM=${pideck_ninja}" \
    "-DCMAKE_TOOLCHAIN_FILE=${pideck_ndk}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-28 \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    "-DCMAKE_C_FLAGS_RELEASE=-O3 -DNDEBUG -g0" \
    "-DCMAKE_CXX_FLAGS=-I${spirv_install}/include" \
    "-DCMAKE_CXX_FLAGS_RELEASE=-O3 -DNDEBUG -g0" \
    -DBUILD_SHARED_LIBS=OFF \
    -DGGML_NATIVE=OFF \
    -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 \
    -DGGML_CPU_KLEIDIAI=OFF \
    -DGGML_VULKAN=ON \
    -DGGML_OPENCL=OFF \
    -DGGML_VULKAN_CHECK_RESULTS=OFF \
    -DGGML_BUILD_TESTS=OFF \
    -DGGML_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_TOOLS=ON \
    -DLLAMA_BUILD_SERVER=ON \
    -DLLAMA_BUILD_UI=OFF \
    -DLLAMA_USE_PREBUILT_UI=OFF \
    -DLLAMA_CURL=OFF \
    "-DVulkan_INCLUDE_DIR=${vulkan_source}/include" \
    "-DVulkan_GLSLC_EXECUTABLE=${pideck_glslc}" \
    "-DSPIRV-Headers_DIR=${spirv_install}/share/cmake/SPIRV-Headers"

pideck_jobs="${PIDECK_BUILD_JOBS:-$(getconf _NPROCESSORS_ONLN)}"
if (( pideck_jobs > 8 )); then
    pideck_jobs=8
fi
# ExternalProject configures the host-side shader generator with Ninja but does
# not forward CMAKE_MAKE_PROGRAM, so make Ninja discoverable for that one step.
env PATH="$(dirname "${pideck_ninja}"):${PATH}" \
    "${pideck_cmake}" --build "${llama_build}" \
    --target llama-bench llama-server --parallel "${pideck_jobs}"

mkdir -p "${output_dir}"
install -m 0755 "${llama_build}/bin/llama-bench" "${output_dir}/llama-bench"
install -m 0755 "${llama_build}/bin/llama-server" "${output_dir}/llama-server"
install -m 0755 "${pideck_libomp}" "${output_dir}/libomp.so"
install -m 0755 "${pideck_libcxx}" "${output_dir}/libc++_shared.so"
"${pideck_strip}" --strip-unneeded \
    "${output_dir}/llama-bench" \
    "${output_dir}/llama-server" \
    "${output_dir}/libomp.so" \
    "${output_dir}/libc++_shared.so"

printf 'Built llama.cpp %s Vulkan Android candidate in %s\n' \
    "${LLAMA_REF}" "${output_dir}"
sha256sum \
    "${output_dir}/llama-bench" \
    "${output_dir}/llama-server" \
    "${output_dir}/libomp.so" \
    "${output_dir}/libc++_shared.so"
