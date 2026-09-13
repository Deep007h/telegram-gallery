#!/bin/bash
set -e

# Discover NDK
NDK_DIR=""
if [ -n "$ANDROID_NDK_HOME" ] && [ -d "$ANDROID_NDK_HOME" ]; then
    NDK_DIR="$ANDROID_NDK_HOME"
elif [ -n "$NDK_HOME" ] && [ -d "$NDK_HOME" ]; then
    NDK_DIR="$NDK_HOME"
elif [ -d "$HOME/Android/Sdk/ndk/25.1.8937393" ]; then
    NDK_DIR="$HOME/Android/Sdk/ndk/25.1.8937393"
elif [ -d "$HOME/Android/Sdk/ndk/28.2.13676358" ]; then
    NDK_DIR="$HOME/Android/Sdk/ndk/28.2.13676358"
fi

if [ -z "$NDK_DIR" ]; then
    echo "Error: Android NDK not found. Please set ANDROID_NDK_HOME."
    exit 1
fi

echo "Using NDK: $NDK_DIR"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST_JNI="$SCRIPT_DIR/../../jniLibs"

mkdir -p "$DEST_JNI/arm64-v8a"
mkdir -p "$DEST_JNI/armeabi-v7a"
mkdir -p "$DEST_JNI/x86_64"
mkdir -p "$DEST_JNI/x86"

cd "$SCRIPT_DIR"

# 1. arm64-v8a
echo "==> Building arm64-v8a..."
CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN/aarch64-linux-android26-clang" \
    cargo build --target aarch64-linux-android --release
cp target/aarch64-linux-android/release/libteledrive_native.so "$DEST_JNI/arm64-v8a/"

# 2. x86_64
echo "==> Building x86_64..."
CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$TOOLCHAIN/x86_64-linux-android26-clang" \
    cargo build --target x86_64-linux-android --release
cp target/x86_64-linux-android/release/libteledrive_native.so "$DEST_JNI/x86_64/"

# 3. armeabi-v7a
if rustup target list | grep -q "armv7-linux-androideabi (installed)"; then
    echo "==> Building armeabi-v7a..."
    CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$TOOLCHAIN/armv7a-linux-androideabi26-clang" \
        cargo build --target armv7-linux-androideabi --release
    cp target/armv7-linux-androideabi/release/libteledrive_native.so "$DEST_JNI/armeabi-v7a/"
fi

# 4. x86
if rustup target list | grep -q "i686-linux-android (installed)"; then
    echo "==> Building x86..."
    CARGO_TARGET_I686_LINUX_ANDROID_LINKER="$TOOLCHAIN/i686-linux-android26-clang" \
        cargo build --target i686-linux-android --release
    cp target/i686-linux-android/release/libteledrive_native.so "$DEST_JNI/x86/"
fi

echo "==> All native libraries successfully built and copied to jniLibs!"
