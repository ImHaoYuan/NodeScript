#!/usr/bin/env bash
# :nodeN 宿主（main.cpp）+ :bridge:native addon 的本机 NDK 交叉编译验证。
# CLAUDE.md：本机只做 C++ 交叉编译验证（AArch64 ELF）；APK/AGP assemble 仍走 CI。
#
# 用法:  engine/node-process/scripts/build-native.sh
# env:   ANDROID_NDK_HOME（必填：NDK 路径；r28c 见 node-runtime-build/VERSIONS.env）
#        NODE_SRC（必填：Node 源码树，取 node_api.h 等三头文件）
#        LIBNODE（必填：libnode.so，符号对表用）
#        —— 三者无缺省（审查步骤 1：机器路径不入脚本），缺即报错带 export 样例
#        OUT_DIR（默认 engine/node-process/build/native-local，build/ 已 gitignore）
#
# 断言（任一失败即 exit 1）：
#  1) addon 导出 napi_register_module_v1；2) 宿主导出/无缺符号可执行；
#  3) node::Start 声明的 mangled 名 == main.cpp dlsym 字面量 == libnode 导出（三方对表）；
#  4) 两个产物 LOAD 段 align >= 16KB（§16 硬门禁）；5) 装载闭包依赖契约（见下 LDFLAGS 注）；
#  6) LOAD 段 p_offset ≡ p_vaddr (mod p_align)（16KB 门的另一半，2026-09-29 补）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

NDK="${ANDROID_NDK_HOME:-}"
TRIPLE=aarch64-linux-android26
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CXX="$TOOLCHAIN/${TRIPLE}-clang++"
NM="$TOOLCHAIN/llvm-nm"
READELF="$TOOLCHAIN/llvm-readelf"
STRIP="$TOOLCHAIN/llvm-strip"

NODE_SRC="${NODE_SRC:-}"
LIBNODE="${LIBNODE:-}"
OUT="${OUT_DIR:-$ROOT/engine/node-process/build/native-local}"

fail() { printf '\033[1;31m[NATIVE FAIL]\033[0m %s\n' "$*" >&2; exit 1; }
step() { printf '\033[1;36m[native]\033[0m %s\n' "$*"; }

[ -n "$NDK" ] || fail "缺 ANDROID_NDK_HOME（export ANDROID_NDK_HOME=/path/to/android-ndk-r28c；r28c 见 node-runtime-build/VERSIONS.env）"
[ -x "$CXX" ] || fail "缺 NDK 编译器 $CXX（ANDROID_NDK_HOME=$NDK 指错了？）"
[ -n "$NODE_SRC" ] || fail "缺 NODE_SRC（export NODE_SRC=/path/to/node-v24.21.0 源码树）"
[ -f "$NODE_SRC/src/node_api.h" ] || fail "缺 node_api.h（NODE_SRC=$NODE_SRC 不是 Node 源码树？）"
[ -n "$LIBNODE" ] || fail "缺 LIBNODE（export LIBNODE=/path/to/libnode.so —— node-runtime-build 出口）"
[ -f "$LIBNODE" ] || fail "缺 libnode.so（LIBNODE=$LIBNODE）"
mkdir -p "$OUT"

# §16 硬门禁：LOAD 段 >= 2**14（16KB）。llvm-readelf -l 的 LOAD 行末列即 align。
assert_16k() {
  local f="$1"
  "$READELF" -l "$f" | python3 -c '
import sys, re
seen = 0
bad = []
for line in sys.stdin:
    if not line.startswith("  LOAD"): continue
    seen += 1
    parts = line.split()
    align = int(parts[-1], 16) if parts[-1].startswith("0x") else int(parts[-1])
    if align < 0x4000: bad.append(line.rstrip())
if seen == 0: sys.exit("no LOAD segments: not ELF?")
if bad: sys.exit("LOAD align < 16KB:\n" + "\n".join(bad))
' || fail "$f 未过 16KB LOAD 对齐门禁"
}

# -D_LIBCPP_HARDENING_MODE=_LIBCPP_HARDENING_MODE_NONE：静态 STL 的 addon 必须钉死无加固
# 态（libc++ hardening 会把 <string>/<vector> 的内联检查引到 __libcpp_verbose_abort，
# 该符号只存在于 libc++_shared.so —— 静态 STL 产物的运行期解析面不该需要它）。
CXXFLAGS=(-std=c++20 -fPIC -O2 -Wall -Wextra -DNAPI_VERSION=10           -D_LIBCPP_HARDENING_MODE=_LIBCPP_HARDENING_MODE_NONE)
# 装载闭包契约（2026-09-29 真机实测改口径，原「-static-libstdc++ 静态 STL」已推翻）：
#
#   bionic 的 linker namespace **不把先做的 dlopen(RTLD_GLOBAL) 符号给后做的 dlopen**
#   （glibc 会）。于是「宿主 dlopen libnode → addon 再 dlopen 时 napi_* 从 libnode 动态表
#   解析」在真机不成立：addon 报 `cannot locate symbol "napi_add_env_cleanup_hook"`。
#   唯一在真机跑通的形态 = 让 libnode 在**进程启动时**就进全局作用域：
#     · addon：DT_NEEDED libnode.so + $ORIGIN（napi_* 按 SONAME 命中已加载的 libnode，
#       与它落在哪个目录无关）
#     · 宿主：**不**链 libnode（保持 dlopen 形，exit 4 语义不变），改为自己 NEEDED
#       libc++_shared.so —— 见下面「传递依赖」那条
#   （绝对 rpath 在 bionic 上**不生效** —— 实测 CANNOT LINK EXECUTABLE … not found；只有
#    $ORIGIN 形态可用。靠 dlopen 宿主「带进」libc++_shared 也不行：dlopen 进来的库不参与
#   后续 dlopen 的依赖解析。）
#
# 由此三条硬约束，断言区逐条把守：
#   · libnode.so 的传递依赖 libc++_shared.so 必须在 exec'd 进程里可解析，而 bionic 的
#     RUNPATH($ORIGIN) **不作用于被依赖库自己的传递依赖**（三处摆放一致复现）→ 解法是让
#     **宿主自己 NEEDED libc++_shared.so**（共享 libc++），同名 SONAME 直接命中；
#     反过来"宿主 NEEDED libnode"会掉进传递依赖无人解析的坑（实测 CANNOT LINK
#     EXECUTABLE … needed by …/libnode.so）——所以宿主保持 dlopen 形，不链 libnode；
#   · addon 相反：它要能被**任一种宿主形态**加载，所以自己 NEEDED libnode（napi_* 按
#     SONAME 命中已加载的 libnode，与落位目录无关），但 MUST NOT 依赖 libc++_shared；
#   · addon 的 napi_* 不在链接期解析（`--allow-shlib-undefined`）；命令行写 -l:libnode.so
#     只是钉住 SONAME 的手段。
# 关键（2026-09-29 真机三处摆放复现）：bionic 的 RUNPATH($ORIGIN) 只作用于**可执行文件
# 自己的**直接 NEEDED，**不**作用于"被依赖库自己的"传递依赖。libnode.so 没有 rpath，
# 它的 DT_NEEDED libc++_shared.so 只认「已加载同名 SONAME」或 LD_LIBRARY_PATH ——
# 所以宿主**必须自己 NEEDED libc++_shared.so**（= 用共享 libc++，撤掉 -static-libstdc++），
# 让 libnode 的同名依赖命中；宿主再 NEEDED libnode 则会掉进"libnode 的传递依赖没人解析"
# 的坑（实测 CANNOT LINK EXECUTABLE … needed by …/libnode.so）。宿主保持 dlopen 形态。
COMMON_LD=(-Wl,-z,max-page-size=16384 -Wl,-rpath,'$ORIGIN')
HOST_LD=("${COMMON_LD[@]}")
# addon 相反：它**不能**依赖 libc++_shared（要在任一种宿主形态下可载），静态 STL 保留；
# napi_* 保持未定义（--allow-shlib-undefined），链接期只为钉住 SONAME 才写 -l:libnode.so。
ADDON_LD=("${COMMON_LD[@]}" -Wl,--allow-shlib-undefined -static-libstdc++
          -Wl,--no-as-needed -L"$(dirname "$LIBNODE")" -l:libnode.so)

step "编 addon → bridge_native.node（NEEDED libnode.so + \$ORIGIN，静态 STL）"
"$CXX" "${CXXFLAGS[@]}" -shared -I "$NODE_SRC/src" "${ADDON_LD[@]}" \
  -o "$OUT/bridge_native.node" "$ROOT/bridge/native/src/main/cpp/bridge_addon.cc"

step "编宿主 → noden（dlopen 形 + NEEDED libc++_shared + \$ORIGIN；不链 libnode）"
"$CXX" "${CXXFLAGS[@]}" -fPIE -pie "${HOST_LD[@]}" \
  -o "$OUT/noden" "$ROOT/engine/node-process/src/main/cpp/main.cpp" -ldl

step "strip 调试段（-l:libnode.so 会把 libnode 的调试信息带进符号表：8.27MB → ~1.0MB）"
# 为什么必须 strip：链接期引 libnode 后 lld 把它的 .symtab/.debug_* 一并写进产物，
# 未 strip 的 addon 从 2.97MB 涨到 8.27MB（实测）；strip 后 1.05MB，**比原配方还小**。
# 断言读的是 program header / .dynamic（strip 不动），所以顺序放在断言之前无碍。
"$STRIP" --strip-unneeded "$OUT/noden" "$OUT/bridge_native.node"
ls -l "$OUT/noden" "$OUT/bridge_native.node"

step "符号对表（声明 ↔ dlsym 字面量 ↔ libnode 导出）"
# 3a. 独立 TU 按 main.cpp 同款声明调用 node::Start → 未定义符即该声明的 mangled 名
cat > "$OUT/start_probe.cc" <<'PROBE'
namespace node { int Start(int argc, char** argv); }
int probe(int argc, char** argv) { return node::Start(argc, argv); }
PROBE
"$CXX" -std=c++20 -c -o "$OUT/start_probe.o" "$OUT/start_probe.cc"
DECLARED="$("$NM" "$OUT/start_probe.o" | awk '$1=="U"{print $2}')"
[ -n "$DECLARED" ] || fail "probe.o 无未定义符号，nm 解析异常"
LITERAL="$(grep -o '_ZN4node5StartEiPPc' "$ROOT/engine/node-process/src/main/cpp/main.cpp" | head -1)"
EXPORTED="$("$NM" -D "$LIBNODE" | awk '$2=="T"{print $3}' | grep -x '_ZN4node5StartEiPPc' || true)"
[ "$DECLARED" = "_ZN4node5StartEiPPc" ] || fail "声明 mangled 名漂移：$DECLARED"
[ "$LITERAL" = "$DECLARED" ] || fail "main.cpp dlsym 字面量与声明不符：'$LITERAL' vs '$DECLARED'"
[ -n "$EXPORTED" ] || fail "libnode.so 未导出 T $DECLARED（§7.8 符号表不符）"
step "node::Start 三方一致: $DECLARED"

step "addon 导出 napi_register_module_v1"
# **不用 `nm … | grep -q`**：脚本开了 `pipefail`，而 `grep -q` 命中即退 → nm 还在写
# （符号表 150KB+，远超 64KB 管道缓冲）就被 SIGPIPE 打死 → 管道状态 141 ≠ 0 →
# `|| fail` 当场误报。实测复现率约 1/5（nm 写完与 grep 退出的竞态），且**只**在
# 这份产物上偶发 —— 是"看起来像符号缺失"的假红。改成先把符号表收进变量再匹配：
# 无管道、无 SIGPIPE、判定确定（`case` 里是纯字符串匹配）。
addon_syms="$("$NM" -D "$OUT/bridge_native.node")"
case "$addon_syms" in
  *napi_register_module_v1*) : ;;
  *) fail "bridge_native.node 缺 napi_register_module_v1" ;;
esac

step "装载闭包契约（NEEDED + RUNPATH，2026-09-29 真机实证口径）"
# 反例即失败：曾经"看着对"的两个形态在真机上分别栽在
#   · dlopen 形 + 静态 STL 宿主：libnode 的传递依赖 libc++_shared 没人解析 →
#     CANNOT LINK EXECUTABLE … needed by …/libnode.so（三处摆放一致复现）
#   · 直接 NEEDED libnode 的宿主：同样死在 libc++_shared，且 exit 4 的语义没了
need_of() { "$READELF" -d "$1" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p'; }
runpath_of() { "$READELF" -d "$1" | sed -n 's/.*Library runpath: \[\(.*\)\]/\1/p'; }

check_needed() {  # $1=文件 $2=必须含(空格分隔) $3=禁止含(空格分隔) $4=一句话
  local f="$1" must="$2" mustnot="$3" why="$4" lib
  mapfile -t needed < <(need_of "$f")
  printf '  %-22s NEEDED: %s\n' "$(basename "$f")" "${needed[*]}"
  for lib in $must; do
    printf '%s\n' "${needed[@]}" | grep -qx "$lib" || fail "$(basename "$f") 缺 DT_NEEDED $lib —— $why"
  done
  for lib in $mustnot; do
    printf '%s\n' "${needed[@]}" | grep -qx "$lib" && fail "$(basename "$f") 不该 NEEDED $lib —— $why"
  done
  for lib in "${needed[@]}"; do
    case "$lib" in libnode.so|libc++_shared.so|libc.so|libdl.so|libm.so) ;; *) fail "$(basename "$f") NEEDED 预期外依赖: $lib" ;; esac
  done
  [ "$(runpath_of "$f")" = '$ORIGIN' ] \
    || fail "$(basename "$f") 缺 RUNPATH=\$ORIGIN（绝对 rpath 在 bionic 无效，实测 not found）"
}

check_needed "$OUT/noden" "libc++_shared.so" "libnode.so" \
  "宿主靠它让 libnode 的同名依赖命中；宿主 NEEDED libnode 会掉进传递依赖无人解析的坑"
check_needed "$OUT/bridge_native.node" "libnode.so" "libc++_shared.so" \
  "addon 的 napi_* 按 SONAME 命中已加载的 libnode；addon 自己不能依赖 libc++_shared（要在任一种宿主形态下可载）"

step "addon 的 napi_* 仍是**未定义**（真机按 global scope 解析的前提）"
undef=$("$NM" -D --undefined-only "$OUT/bridge_native.node" | grep -c 'napi_' || true)
[ "${undef:-0}" -gt 0 ] || fail "addon 的 napi_* 不是未定义（链接期被解析掉了？那它就不再是普通 .node）"
printf '  未定义 napi_* 符号: %s 个\n' "$undef"

step "16KB LOAD 对齐（§16）"
assert_16k "$OUT/noden"
assert_16k "$OUT/bridge_native.node"

# 16KB 门的另一半（2026-09-29 补）：LOAD 段还要满足 p_offset ≡ p_vaddr (mod p_align)，
# 否则内核按 16KB 基页映射时会要求"文件偏移与虚拟地址同余"。只查 align 是半截门禁。
assert_align_congruent() {
  local f="$1"
  "$READELF" -lW "$f" | python3 -c '
import sys
seen = 0
bad = []
for line in sys.stdin:
    parts = line.split()
    if len(parts) < 2 or parts[0] != "LOAD": continue
    seen += 1
    off, vaddr, align = int(parts[1], 16), int(parts[2], 16), int(parts[-1], 16)
    if align == 0: continue
    if (off - vaddr) % align != 0:
        bad.append(f"offset={off:#x} vaddr={vaddr:#x} align={align:#x}")
if seen == 0: sys.exit("no LOAD segments: not ELF?")
if bad: sys.exit("LOAD 段 offset != vaddr (mod align):\n" + "\n".join(bad))
' || fail "$f LOAD 段 offset/vaddr 不同余（16KB 基页映射会失败）"
}
assert_align_congruent "$OUT/noden"
assert_align_congruent "$OUT/bridge_native.node"

step "产物:"
ls -l "$OUT/noden" "$OUT/bridge_native.node"
file "$OUT/noden" "$OUT/bridge_native.node" 2>/dev/null || true
printf '\033[1;32m[NATIVE PASS]\033[0m addon + 宿主编译/符号/对齐全过（交叉验证，未在本机执行 —— 真机链待 CI）\n'
