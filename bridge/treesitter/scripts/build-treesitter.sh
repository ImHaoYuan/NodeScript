#!/usr/bin/env bash
# AutoScript :bridge:treesitter：core + JS grammar + JNI → 三个 arm64-v8a .so。
# 用法（仓库根）：bash bridge/treesitter/scripts/build-treesitter.sh
# 本机与 CI 同脚本；本模块没有 Dockerfile。Gradle 只装配 jniLibs，不编 native。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/../../../settings.gradle.kts" ]; then
    ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
else
    printf '[FATAL] 找不到仓库根（%s 上溯三层无 settings.gradle.kts）\n' "$SCRIPT_DIR" >&2
    exit 1
fi
say() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { printf '[FATAL] %s\n' "$*" >&2; exit 1; }

# tag 仅用于首次 clone；真正的输入是不可移动的 commit。host 测试也读取这两行。
TS_VERSION=v0.20.8
TS_COMMIT=0c49d6745b3fc4822ab02e0018770cd6383a779c
GRAMMAR_VERSION=v0.20.1
GRAMMAR_COMMIT=f1e5a09b8d02f8209a68249c93f0ad647b228e6e

# 保持本模块默认路径；VERSIONS.env 中的 /build 是 Node 管线默认，不可覆盖这里。
WORK="${WORK_DIR:-$ROOT_DIR/bridge/treesitter/build}"
OUT="${OUT_DIR:-$WORK/out-treesitter}"
ABI=arm64-v8a
VERSIONS_FILE="$ROOT_DIR/node-runtime-build/VERSIONS.env"
[ -f "$VERSIONS_FILE" ] || die "冻结版本文件缺失: $VERSIONS_FILE"
versions="$(
    source "$VERSIONS_FILE"
    printf '%s %s %s\n' "$NDK_VERSION" "$NDK_VERSION_CODE" "$ANDROID_API"
)" || die "无法读取冻结版本: $VERSIONS_FILE"
read -r NDK_VERSION NDK_VERSION_CODE API_LEVEL <<< "$versions"
[[ "$API_LEVEL" =~ ^[0-9]+$ ]] || die "ANDROID_API 非整数: $API_LEVEL"

: "${ANDROID_NDK_HOME:?请设置 ANDROID_NDK_HOME（版本见 node-runtime-build/VERSIONS.env）}"
: "${JAVA_HOME:?请设置 JAVA_HOME（JNI 需要 jni.h）}"
[ -f "$ANDROID_NDK_HOME/source.properties" ] || die "NDK source.properties 缺失: $ANDROID_NDK_HOME"
ndk_revision="$(sed -n 's/^Pkg\.Revision[[:space:]]*=[[:space:]]*//p' "$ANDROID_NDK_HOME/source.properties" | tr -d '\r')"
[ "$ndk_revision" = "$NDK_VERSION_CODE" ] || die "NDK 版本漂移: $ndk_revision != $NDK_VERSION_CODE ($NDK_VERSION)"
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
CC="$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang"
CXX="$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang++"
READELF="$TOOLCHAIN/bin/llvm-readelf"
NM="$TOOLCHAIN/bin/llvm-nm"
for tool in "$CC" "$CXX" "$READELF" "$NM"; do
    [ -x "$tool" ] || die "NDK 工具缺失: $tool"
done
command -v python3 >/dev/null || die '门禁需要 python3'
command -v git >/dev/null || die '源码 pin 需要 git'
for header in include/jni.h include/linux/jni_md.h; do
    [ -f "$JAVA_HOME/$header" ] || die "JAVA_HOME 下无 $header: $JAVA_HOME"
done
CPP_DIR="$ROOT_DIR/bridge/treesitter/src/main/cpp"
for f in treesitter_native.cpp treesitter_jni.cc treesitter_native.h; do
    [ -f "$CPP_DIR/$f" ] || die "桥面源码缺失: $CPP_DIR/$f"
done

# 核心与 grammar 保留默认可见性：上游 C API/grammar 必须供 JNI 动态链接，不能 hidden。
# C 单元按 C11 编译，不可交给 C++ 编译器（生成 parser 的指定初始化器不是 C++17）。
# Android 的 Bionic 与 host 的 glibc feature-test 宏不同；host 编译旗标以 test/cpp 为准。
# -z,defs：链接期拒绝不能由输入库满足的引用；显式 16KB 旗标不依赖 NDK 默认值。
# 每件显式 basename SONAME：否则链接可能把构建机绝对路径写进 JNI 的 DT_NEEDED。
C_FLAGS=(-shared -fPIC -O3 -DNDEBUG
         -Wl,-z,max-page-size=16384 -Wl,-z,defs)
# JNI 计算核符号 hidden；JNIEXPORT 保留三个 Java_* 入口。
# 静态 STL 仅为不额外分发 libc++_shared.so，不是“两套 STL 同驻必炸”。跨库只有 C/JNI
# 边界，无 C++ 对象/异常跨库；exclude-libs 隐藏静态 STL 导出，避免污染 JNI 符号面。
CXX_FLAGS=("${C_FLAGS[@]}" -fvisibility=hidden -fvisibility-inlines-hidden
           -static-libstdc++ -Wl,--exclude-libs,ALL)

check_cache() {   # 已有缓存只检查，不 reset/clean/checkout，更不替用户丢改动。
    local dir="$1" commit="$2" top dirty head
    [ -e "$dir/.git" ] || die "缓存不是独立 git 源码树: $dir（请另选 WORK_DIR，现目录不会被覆盖）"
    top="$(git -C "$dir" rev-parse --show-toplevel)" || die "缓存 git 无法读取: $dir"
    [ "$(cd "$top" && pwd -P)" = "$(cd "$dir" && pwd -P)" ] || die "缓存根不符: $dir != $top"
    dirty="$(git -C "$dir" status --porcelain=v1 --untracked-files=all)" || die "缓存状态无法读取: $dir"
    [ -z "$dirty" ] || die "缓存 dirty，拒绝编译: $dir（含 staged/unstaged/untracked；不会 reset/clean）"
    head="$(git -C "$dir" rev-parse HEAD)" || die "缓存 HEAD 无法读取: $dir"
    [ "$head" = "$commit" ] || die "缓存 commit 漂移: $dir @ $head != $commit（请另选干净缓存，脚本不切换已有 HEAD）"
}
clone_pinned() {   # $1=name $2=url $3=tag $4=commit
    local dir="$WORK/src/$1"
    if [ ! -e "$dir" ]; then
        say "clone $1 @ $3，校验 $4"
        git clone --depth 1 --branch "$3" "$2" "$dir"
        if ! git -C "$dir" cat-file -e "$4^{commit}" 2>/dev/null; then
            git -C "$dir" fetch --depth 1 origin "$4" || die "$1 无法拉取 pinned commit: $4"
        fi
        git -C "$dir" -c advice.detachedHead=false checkout --detach "$4"
    fi
    check_cache "$dir" "$4"
    say "$1 @ $4 就位（clean）"
}

# 先检查所有已有缓存：任一个 dirty/错 pin 时，不动另一个缓存或开始编译。
for pair in "tree-sitter:$TS_COMMIT" "tree-sitter-javascript:$GRAMMAR_COMMIT"; do
    dir="$WORK/src/${pair%%:*}"
    if [ -e "$dir" ]; then check_cache "$dir" "${pair#*:}"; fi
done
mkdir -p "$WORK/src" "$OUT/$ABI"
clone_pinned tree-sitter            https://github.com/tree-sitter/tree-sitter.git            "$TS_VERSION"      "$TS_COMMIT"
clone_pinned tree-sitter-javascript https://github.com/tree-sitter/tree-sitter-javascript.git "$GRAMMAR_VERSION" "$GRAMMAR_COMMIT"
TS_SRC="$WORK/src/tree-sitter"
GRAMMAR_SRC="$WORK/src/tree-sitter-javascript"

say "编译 libtree-sitter.so ($NDK_VERSION, API $API_LEVEL)"
"$CC" "${C_FLAGS[@]}" -std=c11 -Wl,-soname,libtree-sitter.so \
    -I"$TS_SRC/lib/include" -I"$TS_SRC/lib/src" \
    "$TS_SRC/lib/src/lib.c" \
    -o "$OUT/$ABI/libtree-sitter.so"

say '编译 libtree-sitter-javascript.so'
# 只给 -I"$GRAMMAR_SRC/src"，**不要**再加 -I"$TS_SRC/lib/include"：grammar 的
# `#include <tree_sitter/parser.h>` 必须取 grammar 自带的那份（与生成 parser.c 的版本配套）。
# 核心库 v0.20.8 也带一份 tree_sitter/parser.h，宏定义不同（START_LEXER 少 eof、
# SMALL_STATE 少括号）；include 顺序让它先赢，则 parser.c 照样编过，但任何输入都会
# 让解析器无限分配直到 ts_malloc 失败 exit(1)。宿主门 run-host-tests.sh 同此约束。
"$CC" "${C_FLAGS[@]}" -std=c11 -Wl,-soname,libtree-sitter-javascript.so \
    -I"$GRAMMAR_SRC/src" \
    "$GRAMMAR_SRC/src/parser.c" "$GRAMMAR_SRC/src/scanner.c" \
    -o "$OUT/$ABI/libtree-sitter-javascript.so"

say '编译 libtreesitter.so（JNI 绑定）'
"$CXX" "${CXX_FLAGS[@]}" -std=c++17 -Wl,-soname,libtreesitter.so \
    -I"$TS_SRC/lib/include" \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    "$CPP_DIR/treesitter_native.cpp" "$CPP_DIR/treesitter_jni.cc" \
    -L"$OUT/$ABI" -ltree-sitter -ltree-sitter-javascript \
    -Wl,-rpath-link,"$OUT/$ABI" \
    -o "$OUT/$ABI/libtreesitter.so"

say '产物门禁'
bash "$SCRIPT_DIR/check-treesitter-alignment.sh" "$READELF" "$NM" "$OUT/$ABI"
say '产物清单（未 strip 字节数，不冒充 APK 增量体积）'
for f in libtree-sitter.so libtree-sitter-javascript.so libtreesitter.so; do
    printf '  %-34s %8s 字节\n' "$f" "$(wc -c < "$OUT/$ABI/$f")"
done
say "完成。产物: $OUT/$ABI"
