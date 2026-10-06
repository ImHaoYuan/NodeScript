#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BRIDGE_ROOT="$(dirname "$SCRIPT_DIR")"
OUT_DIR="${OUT_DIR:-$BRIDGE_ROOT/build/native-local}"

# NDK 路径（从环境变量读取）
: "${ANDROID_NDK_HOME:?请设置 ANDROID_NDK_HOME}"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
export PATH="$TOOLCHAIN/bin:$PATH"

ABI="arm64-v8a"
API_LEVEL=26  # minSdk 冻结值
CC="aarch64-linux-android${API_LEVEL}-clang"
CXX="aarch64-linux-android${API_LEVEL}-clang++"

echo "==> 构建 tree-sitter natives for $ABI"
echo "    NDK: $ANDROID_NDK_HOME"
echo "    输出: $OUT_DIR/$ABI"

# 1. 克隆 tree-sitter 核心（钉死 v0.20.8）
TS_VERSION="v0.20.8"
TS_SRC="$BRIDGE_ROOT/build/tree-sitter-src"
if [[ ! -d "$TS_SRC" ]]; then
    echo "==> 克隆 tree-sitter $TS_VERSION"
    git clone --depth 1 --branch "$TS_VERSION" \
        https://github.com/tree-sitter/tree-sitter.git "$TS_SRC"
else
    echo "==> tree-sitter 源码已存在: $TS_SRC"
fi

# 2. 克隆 JS grammar（钉死 v0.20.1）
GRAMMAR_VERSION="v0.20.1"
GRAMMAR_SRC="$BRIDGE_ROOT/build/tree-sitter-javascript-src"
if [[ ! -d "$GRAMMAR_SRC" ]]; then
    echo "==> 克隆 tree-sitter-javascript $GRAMMAR_VERSION"
    git clone --depth 1 --branch "$GRAMMAR_VERSION" \
        https://github.com/tree-sitter/tree-sitter-javascript.git "$GRAMMAR_SRC"
else
    echo "==> JS grammar 源码已存在: $GRAMMAR_SRC"
fi

mkdir -p "$OUT_DIR/$ABI"

# 3. 编译 libtree-sitter.so（核心解析器）
echo "==> 编译 libtree-sitter.so"
"$CC" -shared -fPIC -O3 -DNDEBUG \
    -I"$TS_SRC/lib/include" \
    "$TS_SRC/lib/src/lib.c" \
    -o "$OUT_DIR/$ABI/libtree-sitter.so"

# 4. 编译 libtree-sitter-javascript.so（JS grammar）
echo "==> 编译 libtree-sitter-javascript.so"
"$CC" -shared -fPIC -O3 -DNDEBUG \
    -I"$TS_SRC/lib/include" \
    "$GRAMMAR_SRC/src/parser.c" \
    "$GRAMMAR_SRC/src/scanner.c" \
    -o "$OUT_DIR/$ABI/libtree-sitter-javascript.so"

# 5. 编译 JNI 绑定 libtreesitter.so
echo "==> 编译 libtreesitter.so (JNI 绑定)"
"$CXX" -shared -fPIC -O3 -DNDEBUG -std=c++17 \
    -I"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include" \
    -I"$TS_SRC/lib/include" \
    -I"${JAVA_HOME:-/usr/lib/jvm/default}/include" \
    -I"${JAVA_HOME:-/usr/lib/jvm/default}/include/linux" \
    "$BRIDGE_ROOT/src/main/cpp/treesitter_jni.cc" \
    "$BRIDGE_ROOT/src/main/cpp/treesitter_native.cpp" \
    -L"$OUT_DIR/$ABI" \
    -ltree-sitter \
    -ltree-sitter-javascript \
    -o "$OUT_DIR/$ABI/libtreesitter.so"

echo ""
echo "✓ 构建完成: $OUT_DIR/$ABI/"
ls -lh "$OUT_DIR/$ABI/"
