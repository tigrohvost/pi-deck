#!/usr/bin/env bash
set -euo pipefail

# Pinned IFM K2 runtime. Separate baseline and i8mm executables keep older
# arm64 devices compatible; Java selects i8mm only when advertised by the CPU.
K2_REPOSITORY="https://github.com/MBZUAI-IFM/llama.cpp.git"
K2_COMMIT="35999d101cf2233fc54f09c3c8d599da7303ce02"
K2_BUILD="k2horizon-35999d1-p2"
K2_NDK_REVISION="28.2.13676358"

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
destination_dir="${1:-${repo_dir}/app/src/main/jniLibs/arm64-v8a}"
ndk_root="${PIDECK_K2_NDK_ROOT:-}"
if [[ -z "${ndk_root}" ]]; then
    printf 'Set PIDECK_K2_NDK_ROOT to Android NDK %s.\n' \
        "${K2_NDK_REVISION}" >&2
    exit 2
fi

source_properties="${ndk_root}/source.properties"
if [[ ! -f "${source_properties}" ]] \
        || ! grep -Eq "^Pkg\.Revision[[:space:]]*=[[:space:]]*${K2_NDK_REVISION}$" \
            "${source_properties}"; then
    printf 'K2 Horizon sidecar requires exact Android NDK %s.\n' \
        "${K2_NDK_REVISION}" >&2
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
    printf 'K2 Horizon sidecar requires Android CMake 3.22.1 and Ninja 1.10.2.\n' >&2
    exit 2
fi

strip_tool="${ndk_root}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
host_cxx="${ndk_root}/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++"
if [[ ! -x "${strip_tool}" || ! -x "${host_cxx}" ]]; then
    printf 'Android NDK host clang++ or llvm-strip is missing.\n' >&2
    exit 2
fi

task_dir="$(mktemp -d "${TMPDIR:-/tmp}/pideck-k2horizon-android.XXXXXX")"
trap 'rm -rf "${task_dir}"' EXIT
source_dir="${task_dir}/source"
build_dir="${task_dir}/build"

git init --quiet "${source_dir}"
git -C "${source_dir}" remote add origin "${K2_REPOSITORY}"
git -C "${source_dir}" fetch --quiet --depth 1 origin "${K2_COMMIT}"
git -C "${source_dir}" checkout --quiet --detach FETCH_HEAD
if [[ "$(git -C "${source_dir}" rev-parse HEAD)" != "${K2_COMMIT}" ]]; then
    printf 'Fetched K2 Horizon source does not match the pinned commit.\n' >&2
    exit 3
fi

patch_file="${repo_dir}/third_party/k2-horizon/patches/0001-request-reasoning-tag.patch"
expected_patch="04fe290be17bf608e862471e555320f1b924896cf096fe3e1d4b9073dc7b0d0b"
actual_patch="$(sha256sum "${patch_file}")"
[[ "${actual_patch%% *}" == "${expected_patch}" ]] || { echo "K2 patch hash mismatch" >&2; exit 3; }
git -C "${source_dir}" apply --check "${patch_file}"
git -C "${source_dir}" apply "${patch_file}"

source_date_epoch="$(git -C "${source_dir}" show -s --format=%ct HEAD)"
common_flags="-O3 -DNDEBUG -ffile-prefix-map=${source_dir}=. -ffile-prefix-map=${build_dir}=."
for variant in baseline i8mm; do
    arm_arch="armv8-a"
    destination="${destination_dir}/libpideck_k2horizon_server.so"
    if [[ "${variant}" == "i8mm" ]]; then
        arm_arch="armv8.6-a+dotprod+fp16+i8mm"
        destination="${destination_dir}/libpideck_k2horizon_i8mm_server.so"
    fi
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
    -DGGML_CPU_ARM_ARCH="${arm_arch}" \
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
    --build "${build_dir}" --target llama-server --parallel "${PIDECK_BUILD_JOBS:-6}"

mkdir -p "$(dirname "${destination}")"
install -m 0755 "${build_dir}/bin/llama-server" "${destination}"
# LLD derives the build ID before the final strip, so otherwise-identical
# binaries built under different absolute runner paths retain different note
# bytes. The note is not used by Android; removing it makes the pinned ELF
# reproducible without changing any executable section.
"${strip_tool}" --strip-unneeded "${destination}"
"${strip_tool}" --remove-section=.note.gnu.build-id "${destination}"

printf 'Built %s (%s) into %s\n' \
    "${K2_BUILD}" "${K2_COMMIT}" "${destination}"
sha256sum "${destination}"
done
