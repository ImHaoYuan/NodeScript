#!/usr/bin/env bash
# 宿主机语义测试：把 :bridge:treesitter 的计算核（treesitter_native.cpp —— 纯计算核、
# 零 JNI）与**同 commit** 的 tree-sitter 核心 + JS grammar 链成一个 x86_64 可执行
# 文件，跑断言（断言清单与理由见 host_highlight_test.cpp 头注）。
#
# 与 device 侧门禁的分工：NDK 那条证明"aarch64 编得出、16KB/NEEDED/JNI 符号面合规"，
# 本脚本证明"判读对"。两者缺一不可 —— 真机 dlopen 成功但把中文注释染错位置，
# 是 device 门禁一条都看不见的。
#
# 用法：TS_SRC=<tree-sitter 源码目录> TS_GRAMMAR_SRC=<tree-sitter-javascript 源码目录> \
#         bash bridge/treesitter/test/cpp/run-host-tests.sh
#   commit pin 见 bridge/treesitter/scripts/build-treesitter.sh 的 TS_COMMIT/GRAMMAR_COMMIT。
#   两者均无缺省（机器路径不入脚本），缺即 usage 报错。
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../../../.. && pwd)"; cd "$REPO"

TS_SRC="${TS_SRC:-}"
GRAMMAR_SRC="${TS_GRAMMAR_SRC:-}"
[ -n "$TS_SRC" ] || {
  printf '[FATAL] 缺 tree-sitter 源码目录：export TS_SRC=…（commit pin 见 bridge/treesitter/scripts/build-treesitter.sh）\n' >&2
  exit 2
}
[ -n "$GRAMMAR_SRC" ] || {
  printf '[FATAL] 缺 JS grammar 源码目录：export TS_GRAMMAR_SRC=…（同上）\n' >&2
  exit 2
}
[ -f "$TS_SRC/lib/src/lib.c" ] || { printf '[FATAL] 不是 tree-sitter 源码树：%s\n' "$TS_SRC" >&2; exit 1; }
[ -f "$GRAMMAR_SRC/src/parser.c" ] || { printf '[FATAL] 不是 JS grammar 源码树：%s\n' "$GRAMMAR_SRC" >&2; exit 1; }
command -v g++ >/dev/null || { printf '[FATAL] 缺 g++\n' >&2; exit 1; }

# commit 对表：pin 的唯一事实来源是构建脚本里的两个变量（不另开一份 env 文件，
# 免得又一处会漂的抄本）。跑在别的 commit 上，"同 commit 的语义门禁"就不成立。
want_of() { sed -n "s/^$1=//p" bridge/treesitter/scripts/build-treesitter.sh; }
for pair in "tree-sitter:$TS_SRC:TS_COMMIT" "tree-sitter-javascript:$GRAMMAR_SRC:GRAMMAR_COMMIT"; do
  name="${pair%%:*}"; rest="${pair#*:}"; dir="${rest%%:*}"; var="${rest##*:}"
  want="$(want_of "$var")"
  if [ -d "$dir/.git" ]; then
    got="$(git -C "$dir" rev-parse HEAD 2>/dev/null || echo '<detached-or-unknown>')"
    [ "$got" = "$want" ] || {
      printf '[FATAL] %s commit 漂移：%s != %s（host 门禁的判据必须与 .so 同源）\n' "$name" "$got" "$want" >&2
      printf '       切过去：git -C %s checkout %s\n' "$dir" "$want" >&2
      exit 1
    }
    printf '[pin] %s @ %s\n' "$name" "${got:0:12}"
  else
    printf '[WARN] %s 无 .git，跳过 commit 对表（tarball 解包无从校验）\n' "$dir" >&2
  fi
done

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# 编译单元分两拨，**不能一把 g++ 全丢进去**：上游三个 C 文件是 C99 语法，
# 其中 grammar 的 parser.c 用了指定初始化器且**字段顺序与声明顺序不一致**
# （tree-sitter 生成器的既定产物），g++ 按 C++ 规则判为硬错误
# （designator order for field ... does not match declaration order）。
# device 侧之所以没踩到，是因为那条命令行用的是 clang 的 -std=c11。
# 故：C 文件走 cc -std=c11，C++ 两件走 g++，最后统一链接。
CC_BIN="${CC:-cc}"
command -v "$CC_BIN" >/dev/null || { printf '[FATAL] 缺 C 编译器：%s\n' "$CC_BIN" >&2; exit 1; }

# 两套 include 路径**必须分开**，与 build-treesitter.sh 同一口径：grammar 的
# `<tree_sitter/parser.h>` 要取自己 src/ 下那份。核心库的 lib/include 里也有同名头
# （宏定义不同：START_LEXER 少 eof、SMALL_STATE 少括号），谁排前面谁赢；grammar 被核心库的头
# 编译会照样编过，但任何输入都让解析器无限分配内存直到 OOM —— 这正是本门要抓的"判读错"。
CORE_INC=(-I"$TS_SRC/lib/include" -I"$TS_SRC/lib/src")
GRAMMAR_INC=(-I"$GRAMMAR_SRC/src")
printf '[cc] 上游 C 单元（lib.c / parser.c / scanner.c）\n'
"$CC_BIN" -std=c11 -O2 -fPIC -c "$TS_SRC/lib/src/lib.c"        -o "$OUT/lib.o"     "${CORE_INC[@]}"
"$CC_BIN" -std=c11 -O2 -fPIC -c "$GRAMMAR_SRC/src/parser.c"    -o "$OUT/parser.o"  "${GRAMMAR_INC[@]}"
"$CC_BIN" -std=c11 -O2 -fPIC -c "$GRAMMAR_SRC/src/scanner.c"   -o "$OUT/scanner.o" "${GRAMMAR_INC[@]}"

# C++ 两件：被测计算核 + 带 main 的测试文件。
# 文件必须**列全**：漏一个就是一组 undefined reference，链接期即红，不会静默少染。
printf '[cc] host_highlight_test\n'
g++ -std=c++17 -O2 -Wall -Wextra \
  -I"$TS_SRC/lib/include" -Ibridge/treesitter/src/main/cpp \
  -o "$OUT/host_highlight_test" \
  bridge/treesitter/test/cpp/host_highlight_test.cpp \
  bridge/treesitter/src/main/cpp/treesitter_native.cpp \
  "$OUT/lib.o" "$OUT/parser.o" "$OUT/scanner.o"

printf '[run] host_highlight_test\n'
# 给测试进程一个地址空间上限（2 GiB）：解析器失控时 ts_malloc 失败会立刻
# 打印 "failed to allocate" 并退出，而不是吃光整机内存被 OOM killer 带走别的进程
# （本门首次跑就这样杀过一个 gradle java —— 见上面 include 路径那段注释）。
( ulimit -v 2097152; "$OUT/host_highlight_test" )
