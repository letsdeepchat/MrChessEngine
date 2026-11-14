#!/usr/bin/env bash
# Builds Stockfish for arm64 Android with the NDK and installs it as
# app/src/main/jniLibs/arm64-v8a/libstockfish.so (packaged as a "native lib" so Android
# installs it executable; the app runs it as a UCI subprocess).
#
# Requirements: Android NDK r26+ (ANDROID_NDK_HOME or ANDROID_HOME/ndk/<ver>), make, curl.
# The Stockfish Makefile downloads the official NNUE networks and embeds them in the binary.
#
#   SF_VERSION=sf_17.1 SF_ARCH=armv8 scripts/build_stockfish_android.sh
#   SF_ARCH=armv8-dotprod  -> ~10-20% faster on phones from ~2018+, crashes on older CPUs.
set -euo pipefail

SF_VERSION="${SF_VERSION:-sf_17.1}"
SF_ARCH="${SF_ARCH:-armv8}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/build/stockfish"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a/libstockfish.so"

NDK="${ANDROID_NDK_HOME:-}"
if [[ -z "$NDK" && -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then
  NDK="$ANDROID_HOME/ndk/$(ls "$ANDROID_HOME/ndk" | sort -V | tail -1)"
fi
[[ -d "$NDK" ]] || { echo "Set ANDROID_NDK_HOME to your NDK (r26 or newer)"; exit 1; }
HOST_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
export PATH="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin:$PATH"
command -v aarch64-linux-android21-clang++ >/dev/null || { echo "NDK clang not found under $NDK"; exit 1; }

mkdir -p "$WORK"
if [[ ! -d "$WORK/Stockfish-$SF_VERSION" ]]; then
  echo "Downloading Stockfish $SF_VERSION ..."
  curl -fsSL "https://github.com/official-stockfish/Stockfish/archive/refs/tags/$SF_VERSION.tar.gz" | tar -xz -C "$WORK"
fi

cd "$WORK/Stockfish-$SF_VERSION/src"
make -j"$(nproc 2>/dev/null || echo 4)" build ARCH="$SF_ARCH" COMP=ndk
make strip ARCH="$SF_ARCH" COMP=ndk || llvm-strip stockfish

mkdir -p "$(dirname "$OUT")"
cp stockfish "$OUT"
echo "Installed $(du -h "$OUT" | cut -f1) engine at $OUT"
