#!/usr/bin/env bash
#
# 冻结面门（2026-10-07 新增；出处：PR #44 外审 —— 见 docs/log/2026-10-07.md 批 65）。
#
# 挡的是什么：下游/协作者（含 AI）在本仓的**契约面与台账面**上做「不该做的那种改动」——
# 删改 `docs/design/` 的契约、删掉 `docs/log/` 的既有流水条目、改门禁脚本、改模块表。
# 实测过的那一次：PR #44 的 a8ac99b 把 `docs/log/2026-10-06.md` 里 main 的批 58–62 五条
# 删了（−203 行）、把 `docs/log/README.md` 的 2026-10-07 整块删了、把 `docs/design-status.md`
# 的一行口径改回旧文 —— 而 `check-doc-links.sh` **抓不到**（它只验链接目标存在，删条目
# 不影响任何链接）。本门补的就是这个洞。
#
# **为什么这道门下游删不掉**：`pull_request` 事件跑的是**基线分支**（main）里的 workflow
# 文件，不是 PR 里那份。下游在自己的 fork 里改/删本脚本，PR 上跑的仍是 main 的版本；
# 而改 main 上的它又会被本门自己拦（`.github/` 在冻结面里）。门只能由维护者自己开。
#
# 规则两条：
#   1. **冻结面**（FROZEN）：本 PR 完全不许碰。改了就是改契约 / 改门禁 / 改模块表 / 改协作纪律
#      —— 要改先开 issue 说明理由，由维护者改（与 .github/CODEOWNERS 的冻结面同一条口径）。
#   2. **只追加面**（APPEND_ONLY）：**既有条目一个都不许消失**。判据是「结构标记还在不在」，
#      **不比行数、不比正文** —— 所以往顶部加一条（目录表的计数 +1、正文改几个字）照样绿，
#      只有真删条目才红。行数比对会把正常追加也判红，那种门只能靠放行表维持，等于没门。
#
# 本机跑法（与 CI 同一条命令，两个环境变量可换基线）：
#   BASE_REF=origin/main HEAD_REF=HEAD bash .github/scripts/check-frozen-paths.sh
#
# 刻意**不做**的事：不比对正文哈希（正文会被正常修订 —— 批号重编号、措辞订正都发生过）、
# 不做「行数只增不减」（目录表的计数行本来就要改）。判据只认结构标记。
set -uo pipefail

cd "$(git rev-parse --show-toplevel)"

BASE_REF="${BASE_REF:-origin/main}"
HEAD_REF="${HEAD_REF:-HEAD}"

if ! MB="$(git merge-base "$BASE_REF" "$HEAD_REF" 2>/dev/null)"; then
  printf '冻结面门：拿不到 merge-base（%s / %s）—— 基线没取到？先 git fetch origin。\n' \
    "$BASE_REF" "$HEAD_REF" >&2
  exit 1
fi
printf '冻结面门：基线 %s（%s）→ %s\n' "$BASE_REF" "$MB" "$HEAD_REF"

# ── 冻结面：完全不许改 ──────────────────────────────────────────────────────
# 与 .github/CODEOWNERS 的「冻结面 / 契约与安全面」两段一一对应（那边是人读的，这边是机器读的）。
FROZEN='^(docs/design/.*|docs/framework-design\.md|docs/README\.md|\.github/.*|settings\.gradle\.kts|gradle/libs\.versions\.toml|build\.gradle\.kts|build-logic/.*|SECURITY\.md|CONTRIBUTING\.md|CLAUDE\.md)$'

# ── 只追加面：既有条目不许消失 ──────────────────────────────────────────────
APPEND_ONLY='^docs/(log/.*|design-status\.md|design-decisions\.md|backlog\.md)$'

changed="$(git diff --name-only "$MB" "$HEAD_REF")"
if [ -z "$changed" ]; then
  printf '冻结面门：本 PR 零改动（%s 与 %s 同一棵树）。\n' "$MB" "$HEAD_REF"
  exit 0
fi

fail=0

while IFS= read -r path; do
  [ -z "$path" ] && continue
  if [[ "$path" =~ $FROZEN ]]; then
    printf '::error file=%s::%s 属冻结面 —— 不接受对它的改动；要改先开 issue 说明理由，由维护者改\n' \
      "$path" "$path"
    fail=1
  fi
done <<< "$changed"

# 「结构标记」= 一条流水条目 / 一个日期段 / 一个待办条目在文件里的可识别开头。
# 取标记而不是整行：正文会被正常修订，标记不会（只追加纪律就是围着标记定的）。
markers() { # $1 = 文件路径, $2 = git rev
  case "$1" in
    # README 必须排在 `docs/log/*.md` **前面** —— case 的 `*` 也吃 `/`，顺序反了永远匹配不到这一支。
    # 只取日期、不取「（N 条）」：目录表的计数本来就要随新增 +1，那是合法的。
    docs/log/README.md)     git show "$2:$1" 2>/dev/null | grep -oE '^### \[[0-9-]+\]' ;;
    docs/log/*.md)          git show "$2:$1" 2>/dev/null | grep -oE '^### .*$' ;;
    docs/design-status.md)  git show "$2:$1" 2>/dev/null | grep -oE '^\| [0-9]{4}-[0-9]{2}-[0-9]{2} \|' ;;
    *)                      git show "$2:$1" 2>/dev/null | grep -oE '^#{2,3} [^—]*' ;;
  esac
}

while IFS= read -r path; do
  [ -z "$path" ] && continue
  [[ "$path" =~ $APPEND_ONLY ]] || continue
  while IFS= read -r lost; do
    [ -z "$lost" ] && continue
    printf '::error file=%s::%s 是只追加文件，既有条目「%s」在本次改动里消失了 —— 只允许在顶部新增，不许删/改写历史\n' \
      "$path" "$path" "$lost"
    fail=1
  done < <(comm -23 <(markers "$path" "$MB" | sort -u) <(markers "$path" "$HEAD_REF" | sort -u))
done <<< "$changed"

if [ "$fail" -ne 0 ]; then
  printf '\n冻结面门：不通过。口径见 .github/CODEOWNERS 与 CONTRIBUTING.md「文档即契约」。\n' >&2
  exit 1
fi

printf '冻结面门：通过（无冻结面改动；只追加面零条目丢失）。\n'
