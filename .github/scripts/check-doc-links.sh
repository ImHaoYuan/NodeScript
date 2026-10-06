#!/usr/bin/env bash
#
# 文档相对链接门（2026-10-01 新增；出处：docs/backlog.md 的 C3，外审建议「加一个文档
# 路径/链接检查门」—— 那四条失效引用就是这么扫出来的）。
#
# 规则：扫 git 跟踪的全部 `*.md`，把 markdown 链接 `](目标)` 按**该文件所在目录**解析，
# 目标文件不存在即红（退出码 1，逐条打印）。
#
# 为什么只查 markdown 链接，不查反引号里的路径：反引号里的「斜杠串」绝大多数不是路径
# —— 斜杠分隔的词表（`findOne/findAll`）、分支名（`feat/fastpath-16x`）、别的仓库的
# 路径（`obra/superpowers`）、相对**另一个基准**的子路径（`scripts/build-opencv.sh` 是
# 相对 node-runtime-build 的）。实测全仓 92 条候选里真引用是少数，那种门最后只能靠一张
# 几十条的放行表维持，等于没门。链接不同：基准唯一（文件所在目录）、意图唯一（指向仓库
# 里的文件），实测 27 个 md 全绿且零噪音。
#
# 刻意**不做**的宽松处：不兜底「仓库根相对」二次解析（实测零依赖），不跟随 `#` 之前的
# 通配（`*`/`...` 形态上就不是精确路径，跳过）；围栏代码块里的示例链接先剥掉再扫。
# 要指向「还没建的文件」时，正确做法是别写成链接（写成反引号即可），而不是放宽本门。
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

missing=0
checked=0
while IFS= read -r f; do
  dir=$(dirname "$f")
  # 先剥围栏代码块（``` 之间的示例不该被当引用），再抽链接目标
  while IFS= read -r target; do
    target=${target%%#*}          # 去锚点
    target=${target%% *}          # 去 "title"
    [ -z "$target" ] && continue
    case "$target" in
      http*|https*|mailto:*|"#"*|/*) continue ;;   # 外链/纯锚点/绝对路径：不是本仓相对引用
      *"*"*|*"..."*) continue ;;                    # 通配与省略号：不是精确路径
    esac
    checked=$((checked + 1))
    if [ ! -e "$dir/$target" ]; then
      printf '✗ %s → %s（解析为 %s，不存在）\n' "$f" "$target" "$dir/$target"
      missing=$((missing + 1))
    fi
  done < <(awk '/^[[:space:]]*```/{inb=!inb; next} !inb' "$f" | grep -oP '\]\(\K[^)[:space:]]+' || true)
done < <(git ls-files '*.md')

if [ "$missing" -gt 0 ]; then
  printf '\n文档链接门：%d 条相对链接指向不存在的文件（共检查 %d 条）。\n' "$missing" "$checked"
  exit 1
fi

# 零条 = 门没跑，不是门绿了（2026-10-06，第三轮外审 D3 实测复现）。
# 复现方式：在一个空 git 仓里跑本脚本 → `git ls-files '*.md'` 空 → 循环零次 →
# 老版本打印「0 条相对链接全部存在」并 exit 0。`git ls-files` 失败（非 git 目录、
# 浅克隆、工作区没检出）同样落到这条路径上——**门静默变成一句"全部存在"**，
# 而它本该是"我一条都没看"。这与仓库自己的纪律（skipped ≠ 绿、tests=0 要验尸）同型：
# 检查数为 0 一律红，不给"看起来绿"留缝。
if [ "$checked" -eq 0 ]; then
  printf '\n文档链接门：扫到 0 条相对链接 —— 门没跑起来（不在 git 仓里？工作区没检出？）。\n' >&2
  printf '这不是通过：本门至少该看见 README 与 docs/ 的成百条链接。\n' >&2
  exit 1
fi

printf '文档链接门：%d 条相对链接全部存在。\n' "$checked"
