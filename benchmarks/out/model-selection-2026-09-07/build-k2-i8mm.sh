#!/bin/bash
set -euo pipefail
TASK_DIR=/tmp/pideck-model-audit-20260907
SDK_DIR=/home/che/Android/Sdk
"$SDK_DIR/cmake/3.22.1/bin/cmake" -S "$TASK_DIR/k2-source" -B "$TASK_DIR/k2-build-i8mm" -G Ninja \
 -DCMAKE_MAKE_PROGRAM="$SDK_DIR/cmake/3.22.1/bin/ninja" \
 -DCMAKE_TOOLCHAIN_FILE="$SDK_DIR/ndk/28.2.13676358/build/cmake/android.toolchain.cmake" \
 -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static \
 -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DGGML_CPU_ARM_ARCH=armv8.6-a+dotprod+fp16+i8mm \
 -DGGML_OPENMP=OFF -DGGML_VULKAN=OFF -DGGML_OPENCL=OFF -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF \
 -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=ON -DLLAMA_BUILD_UI=OFF \
 -DLLAMA_BUILD_MTMD=OFF -DLLAMA_SUBPROCESS=OFF -DLLAMA_LLGUIDANCE=OFF \
 -DLLAMA_UI_HOST_CXX="$SDK_DIR/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++" \
 > "$TASK_DIR/k2-i8mm-configure.log" 2>&1
"$SDK_DIR/cmake/3.22.1/bin/cmake" --build "$TASK_DIR/k2-build-i8mm" --target llama-server -j 6 > "$TASK_DIR/k2-i8mm-build.log" 2>&1
"$SDK_DIR/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$TASK_DIR/k2-build-i8mm/bin/llama-server" -o "$TASK_DIR/k2-server-i8mm"
sha256sum "$TASK_DIR/k2-server-i8mm"
