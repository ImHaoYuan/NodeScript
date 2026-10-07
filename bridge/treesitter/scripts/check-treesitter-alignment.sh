#!/usr/bin/env bash
# 用法：bash check-treesitter-alignment.sh <llvm-readelf> <llvm-nm> <so目录>
# 独立于图像库门禁：三件必须为 AArch64 ET_DYN、16KB LOAD 对齐且 offset/vaddr 同余、
# basename SONAME；JNI 必须 DT_NEEDED core+grammar 且恰好只导出三个 Java_* 会话入口。
# libc++ 静态链接仅为不额外分发 runtime；系统 NEEDED 仅允许 Android libc/libdl/libm。
set -euo pipefail
READELF="${1:?缺 llvm-readelf 路径}"
NM="${2:?缺 llvm-nm 路径}"
SO_DIR="${3:?缺 so 目录}"

# capture_output 完整消费工具输出；不写 nm | grep -q（pipefail 下命中也可能 SIGPIPE/141）。
# 工具失败必须传播；也不通过 mapfile < <(...) 隐藏 producer 退出码。
python3 - "$READELF" "$NM" "$SO_DIR" <<'PY'
import pathlib
import re
import subprocess
import sys

readelf, nm, so_dir = sys.argv[1:]
core = "libtree-sitter.so"
grammar = "libtree-sitter-javascript.so"
jni = "libtreesitter.so"
files = (core, grammar, jni)
system_libs = {"libc.so", "libdl.so", "libm.so"}
expected_jni = {
    "Java_com_autoscript_platform_editor_TreeSitterNative_" + method
    for method in ("createSession", "highlight", "destroySession")
}


def fail(message):
    sys.exit("[GATE FAIL] " + message)


def run(tool, *args):
    try:
        result = subprocess.run([tool, *args], capture_output=True, text=True, check=True)
    except (OSError, subprocess.CalledProcessError) as error:
        detail = getattr(error, "stderr", "") or str(error)
        fail(f"{tool} 执行失败: {detail.strip()}")
    return result.stdout


def symbols(path, defined):
    output = run(nm, "-D", "--defined-only" if defined else "--undefined-only",
                 "--format=posix", str(path))
    result = {}
    for line in output.splitlines():
        fields = line.split()
        if not fields:
            continue
        if len(fields) < 2 or len(fields[1]) != 1:
            fail(f"{path.name}: 无法解析 nm 行: {line}")
        result[fields[0]] = fields[1]
    return result


definitions = {}
imports = {}
for name in files:
    path = pathlib.Path(so_dir) / name
    if not path.is_file():
        fail(f"产物不存在: {path}")
    elf = run(readelf, "-W", "-h", "-l", "-d", str(path))
    if not re.search(r"^\s*Machine:\s*AArch64\s*$", elf, re.M):
        fail(f"{name}: 非 AArch64 ELF")
    if not re.search(r"^\s*Type:\s*DYN\b", elf, re.M):
        fail(f"{name}: 非 ET_DYN（共享对象）")
    if not re.search(r"^\s*Class:\s*ELF64\s*$", elf, re.M):
        fail(f"{name}: 非 ELF64")
    loads = [line.split() for line in elf.splitlines() if re.match(r"^\s*LOAD\s", line)]
    if not loads:
        fail(f"{name}: 无 LOAD 段（readelf 解析异常或非共享库）")
    for fields in loads:
        try:
            offset, vaddr, align = int(fields[1], 16), int(fields[2], 16), int(fields[-1], 16)
        except (ValueError, IndexError):
            fail(f"{name}: 无法解析 LOAD: {' '.join(fields)}")
        if align < 0x4000 or align & (align - 1):
            fail(f"{name}: LOAD align 非 >=16KB 的 2 次幂: {align:#x}")
        if offset % align != vaddr % align or offset % 0x4000 != vaddr % 0x4000:
            fail(f"{name}: LOAD p_offset/p_vaddr 不同余: {offset:#x}/{vaddr:#x}, align={align:#x}")
    sonames = re.findall(r"\(SONAME\).*?\[([^\]]*)\]", elf)
    if sonames != [name]:
        fail(f"{name}: SONAME 必须恰为 basename {name}，实际 {sonames}")
    needed = re.findall(r"\(NEEDED\).*?\[([^\]]*)\]", elf)
    allowed = system_libs | ({core, grammar} if name == jni else set())
    unexpected = set(needed) - allowed
    if unexpected:
        fail(f"{name}: NEEDED 出现预期外依赖: {sorted(unexpected)}（不额外分发 C++ runtime）")
    if name == jni and not {core, grammar}.issubset(needed):
        fail(f"{name}: NEEDED 必须包含 {core} 与 {grammar}，实际 {needed}")
    definitions[name] = symbols(path, True)
    imports[name] = symbols(path, False)
    print(f"[OK] {name}: ELF64/AArch64/DYN，16KB LOAD 同余，SONAME={name}，NEEDED={needed}")

# 精确集合与符号类型检查：旧入口、额外入口、仅前缀相同的符号、泄漏的 C++/STL 都红。
actual = definitions[jni]
if set(actual) != expected_jni or any(actual.get(symbol) != "T" for symbol in expected_jni):
    fail(f"{jni}: 导出必须恰为 createSession/highlight/destroySession 三个 JNI 函数；"
         f"缺少={sorted(expected_jni - set(actual))}，"
         f"多出={sorted(set(actual) - expected_jni)}，类型={actual}")
if definitions[core].get("ts_parser_new") != "T":
    fail(f"{core}: 未导出 ts_parser_new（core 不得 hidden）")
if definitions[grammar].get("tree_sitter_javascript") != "T":
    fail(f"{grammar}: 未导出 tree_sitter_javascript（grammar 不得 hidden）")
# 对照本地交付的符号面；其余 Android 系统引用由构建的 -z,defs 与 NEEDED 白名单约束。
local_exports = set(definitions[core]) | set(definitions[grammar])
local_imports = {symbol for symbol in imports[jni]
                 if symbol.startswith(("ts_", "tree_sitter_"))}
missing = local_imports - local_exports
if missing:
    fail(f"{jni}: core/grammar 本地无法满足引用: {sorted(missing)}")
print("[OK] JNI 恰好三导出，core/grammar 公共入口可见且本地 tree-sitter 引用均可满足")
print("[OK] tree-sitter 产物门禁通过")
PY
