#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# worktree 外挂补齐（AdaiOS 专属）——新建 worktree 后第一件事
#
# 为什么需要：
#   `git worktree add` 给出的是**干净检出**：git 跟踪的都有，git 之外的家当全没有。
#   本项目有**四样**关键家当在版本库外，缺了**不报错，只静默出错**：
#     · data/                   337M 个人数据（git 只跟踪 data/adai/identity/profile.sample.md 一个文件）
#                               → 后端 adai.data.base-path 默认 ../../data 正好指向这个空壳
#     · services/adai-core/.env 6 个密钥（DEEPSEEK_API_KEY / GLM_API_KEY / ADAI_ADMIN_TOKEN /
#                               ADAI_PUSH_WECHAT_SENDKEY / ADAI_SMOKE_ACCOUNT / ADAI_SMOKE_PASSWORD）
#                               → spring 配的是 optional:file:.env，读不到即静默降级
#     · .agents/records/state/   巡检游标 / 成本账 / 心跳缓存
#                               → 各 worktree 一份独立账本，分叉后记账与巡检失真
#     · 工具出口（技能 4 个 + 子代理 2 组）
#                               → **刻意不 link 主仓库**，改为在 worktree 里**各自注册**：
#                                 ai-link-skills.sh 用相对软链、ai-sync-agents.sh 生成 ⇒
#                                 指向**本 worktree 的真相源** ⇒ **随分支**（A 分支加的技能不会漏进 B 分支）
#
# 用法（在**新建的 worktree 目录里**执行；在主仓库执行会被拒绝）：
#   bash .agents/mechanism/scripts/ai-worktree-prep.sh --check      # 只检查外挂是否齐备（0 = 齐，1 = 有缺）
#   bash .agents/mechanism/scripts/ai-worktree-prep.sh --dry-run    # 打印将做什么，不落盘
#   bash .agents/mechanism/scripts/ai-worktree-prep.sh              # 默认 link：共享真实数据，几乎不占磁盘
#   bash .agents/mechanism/scripts/ai-worktree-prep.sh --copy       # copy：要写数据的实验用（APFS 写时复制）
#   bash .agents/mechanism/scripts/ai-worktree-prep.sh --force      # 已存在的目标也重建（默认跳过）
#
# 安全设计（2026-10-01 独立对抗审查后的加固，**别删**）：
#   ① **顶层目录永不建整目录符号链接**（data/ 与 state/ 强制逐子项补）——
#      否则 worktree 里一旦出现 `data -> 主仓库/data`，后续任何 `rm -rf <worktree>/data/xxx`
#      都会**顺着链接删掉主仓库的真实数据**（rm 只对**最后一个**路径组件不跟随符号链接，
#      中间组件一律被解析；对抗审查已用 sentinel 实测复现）。
#   ② place() 在删除前断言「目标在 worktree 内的所有父级都不是符号链接」（同上原因）。
#   ③ `--force` 遇到符号链接只 `rm -f` 删链接本身，绝不 `rm -rf`。
#   ④ 每次落盘检查返回码：失败即报错并以非 0 退出（防「半份拷贝被当完成」，磁盘满是常见形态）。
#
# 三条纪律（也写在手册里，别绕）：
#   1. **state / AGENTS.local.md 恒 link**——账本与快照必须唯一，复制一份等于劈成两半
#   2. **工具出口改为「各自注册」**（本脚本落盘时自动跑 ai-link-skills + ai-sync-agents）——
#      **绝不 link 主仓库的出口**：那样技能指向主仓库的 ai-engineering/，**不随分支**
#   3. **要跑会写数据的实验，先 --copy**——默认 link 模式下的写入会直接落到 337M 真实数据上
#
# 相关：.agents/rules/guides/worktree-workflow.md（完整手册：目录方案 / 端口 / 提交纪律 / 验证清单）
# ─────────────────────────────────────────────────────────────
set -uo pipefail

usage() {
  cat <<'EOF'
worktree 外挂补齐（AdaiOS）——补 data / .env（服务端）/ state 三件套

用法（在 worktree 目录里跑）：
  bash .agents/mechanism/scripts/ai-worktree-prep.sh --check      # 只检查（退出码 0 = 齐备，1 = 有缺）
  bash .agents/mechanism/scripts/ai-worktree-prep.sh --dry-run    # 打印计划，不落盘
  bash .agents/mechanism/scripts/ai-worktree-prep.sh              # link（默认，共享真实数据）+ 注册工具出口
  bash .agents/mechanism/scripts/ai-worktree-prep.sh --copy       # copy（要写数据的实验用）
  bash .agents/mechanism/scripts/ai-worktree-prep.sh --force      # 已存在也重建
完整说明见 .agents/rules/guides/worktree-workflow.md
EOF
}

MODE="link"
FORCE=0
DRY=0
CHECK=0

while [ $# -gt 0 ]; do
  case "$1" in
    --copy) MODE="copy" ;;
    --link) MODE="link" ;;
    --check) CHECK=1 ;;
    --dry-run) DRY=1 ;;
    --force) FORCE=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数：$1"; usage; exit 2 ;;
  esac
  shift
done

# ── 定位 worktree（当前）与主仓库（外挂的家）──
WT="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [ -z "${WT}" ]; then
  echo "❌ 当前目录不在 git 仓库内——本脚本要在 worktree 目录里跑。"
  exit 2
fi

# --git-common-dir 在 worktree 里指向主仓库的 .git（比 worktree list 更权威）
COMMON="$(git rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)"
MAIN=""
[ -n "${COMMON}" ] && MAIN="$(dirname "${COMMON}")"
if [ -z "${MAIN}" ] || [ ! -d "${MAIN}/.git" ]; then
  MAIN="$(git worktree list --porcelain 2>/dev/null | awk '/^worktree /{print substr($0,10); exit}')"
fi
if [ -z "${MAIN}" ] || [ ! -d "${MAIN}/.git" ]; then
  echo "❌ 定位不到主仓库根——请从主仓库执行 git worktree add，再进新目录跑本脚本。"
  exit 2
fi

if [ "${WT}" = "${MAIN}" ]; then
  cat <<'EOF'
❌ 当前目录就是主仓库——这里不需要补外挂（外挂本来就在）。
   起一条新线：
     git worktree add ../adaios-<任务短名> -b feat/<任务短名> main
     cd ../adaios-<任务短名> && bash .agents/mechanism/scripts/ai-worktree-prep.sh
EOF
  exit 2
fi

# ── 目标清单 ────────────────────────────────────────────────
# 统一用「模式 <TAB> 源绝对路径 <TAB> 相对主仓库路径」三列输出，
# 让 apply 与 --check 共用同一份清单（避免两处逻辑漂移）。
emit() { printf '%s\t%s\t%s\n' "$1" "$2" "$3"; }

# walk：目录树。两条硬规则——
#   ① 顶层（$4=1）**永远逐子项**，绝不给 data/ 这类顶层目录建整目录符号链接（安全设计 ①）。
#   ② git 跟踪过的目录不能整体拿 symlink 顶掉（会和检出内容打架），逐子项递归：
#      跟踪的跳过（worktree 已检出），未跟踪的补。
walk() {  # $1 源绝对路径  $2 相对主仓库路径  $3 模式  $4=1 表示顶层（强制逐子项）
  local src="$1" rel="$2" mode="$3" top="${4:-0}" child crel
  if [ "${top}" -eq 1 ] || [ -n "$(git -C "${WT}" ls-files -- "${rel}")" ]; then
    for child in "${src}"/* "${src}"/.[!.]*; do
      [ -e "${child}" ] || continue
      crel="${child:${#MAIN}+1}"   # 按长度截取（不用 ${child#${MAIN}/}：MAIN 含 [ 时 glob 剥离会失败）
      if [ -d "${child}" ] && [ ! -L "${child}" ]; then
        walk "${child}" "${crel}" "${mode}" 0
      elif [ -z "$(git -C "${WT}" ls-files -- "${crel}")" ]; then
        emit "${mode}" "${child}" "${crel}"
      fi
    done
  else
    emit "${mode}" "${src}" "${rel}"
  fi
}

targets() {
  local f
  # 1) data/：个人数据整树（只读联调 link / 写入实验 copy）；顶层强制逐子项（安全设计 ①）
  [ -d "${MAIN}/data" ] && walk "${MAIN}/data" "data" "${MODE}" 1
  # 2) .agents/records/state/：**恒 link**（巡检游标与成本账必须唯一）
  [ -d "${MAIN}/.agents/records/state" ] && walk "${MAIN}/.agents/records/state" ".agents/records/state" "link" 1
  # 3) 服务端密钥（根 .env 当前是空文件，按 -s 自动跳过）
  for f in "services/adai-core/.env" ".env"; do
    [ -s "${MAIN}/${f}" ] && emit "${MODE}" "${MAIN}/${f}" "${f}"
  done
  # 4) 开工快照：**恒 link**（主仓库刷新后立刻可见；复制会隔夜过期）
  [ -s "${MAIN}/AGENTS.local.md" ] && emit "link" "${MAIN}/AGENTS.local.md" "AGENTS.local.md"
  # 5) 工具出口**刻意不在这里** —— 由本脚本在落盘阶段「各自注册」（见文件尾部）：
  #    技能 4 个出口走 ai-link-skills.sh（相对软链）· 子代理 12×2 走 ai-sync-agents.sh（生成）。
  #    为什么不 link 主仓库的 .dsh/skills：那样技能指向主仓库的 ai-engineering/，**不随分支**。
  return 0
}

# ── --check：只读自检 ───────────────────────────────────────
if [ "${CHECK}" -eq 1 ]; then
  printf 'worktree 外挂自检\n  worktree：%s\n  主仓库：%s\n\n' "${WT}" "${MAIN}"
  MISSING=0
  OK=0
  while IFS=$'\t' read -r mode src rel; do
    [ -n "${rel:-}" ] || continue
    if [ -L "${WT}/${rel}" ]; then
      printf '  ✅ %-46s link → %s\n' "${rel}" "$(readlink "${WT}/${rel}")"
      OK=$((OK + 1))
    elif [ -e "${WT}/${rel}" ]; then
      printf '  ✅ %-46s 实体（copy 模式）\n' "${rel}"
      OK=$((OK + 1))
    else
      printf '  ❌ %-46s 缺失 → bash .agents/mechanism/scripts/ai-worktree-prep.sh\n' "${rel}"
      MISSING=$((MISSING + 1))
    fi
  done < <(targets)

  # 工具出口：判据交给两个脚本自己（各自注册；脚本未落地时跳过，不误报）
  echo
  echo "── 工具出口（各自注册，随分支）──"
  if [ -f .agents/mechanism/scripts/ai-link-skills.sh ]; then
    if bash .agents/mechanism/scripts/ai-link-skills.sh --check >/dev/null 2>&1; then
      printf '  ✅ %-40s %s\n' "技能出口（4 个）" "ai-link-skills.sh --check 通过"
      OK=$((OK + 1))
    else
      printf '  ❌ %-40s %s\n' "技能出口（4 个）" "不齐 → bash .agents/mechanism/scripts/ai-link-skills.sh"
      MISSING=$((MISSING + 1))
    fi
  fi
  if [ -f .agents/mechanism/scripts/ai-sync-agents.sh ]; then
    if bash .agents/mechanism/scripts/ai-sync-agents.sh --check >/dev/null 2>&1; then
      printf '  ✅ %-40s %s\n' "子代理出口（12×2）" "ai-sync-agents.sh --check 通过"
      OK=$((OK + 1))
    else
      printf '  ❌ %-40s %s\n' "子代理出口（12×2）" "不齐 → bash .agents/mechanism/scripts/ai-sync-agents.sh"
      MISSING=$((MISSING + 1))
    fi
  fi

  printf '\n结果：%d 项齐备 · %d 项缺失\n' "${OK}" "${MISSING}"
  if [ "${OK}" -eq 0 ] && [ "${MISSING}" -eq 0 ]; then
    # 判据必须是「主仓库有没有这三样外挂」，而不是「清单空不空」——清单为空也可能是
    # 「这些内容全都已被 git 跟踪，本来就无需补」（普通仓库的常态）。
    if [ -d "${MAIN}/data" ] || [ -d "${MAIN}/.agents/records/state" ]; then
      echo "⚠️  主仓库的 data/ 与 state/ 里没有「未跟踪」的部分可补（内容可能全在 git 里）——本仓库无需补外挂。"
      exit 0
    fi
    echo "⚠️  主仓库里连 data/ 与 .agents/records/state/ 都不存在——请确认主仓库定位是否正确（可能走错目录）。"
    exit 1
  fi
  [ "${MISSING}" -eq 0 ] || exit 1
  exit 0
fi

# ── 落盘 ───────────────────────────────────────────────────
# 父链必须无符号链接：`rm -rf link/child` 删的是**链接目标**，这是本脚本唯一的 P0 风险
# （2026-10-01 对抗审查实测复现：`link -> src` 时 `rm -rf link/sub` 把 `src/sub` 删掉）。
assert_parents_safe() {  # $1 = 目标绝对路径
  local p="${1%/*}"
  while [ -n "${p}" ] && [ "${p}" != "${WT}" ] && [ "${p}" != "/" ]; do
    if [ -L "${p}" ]; then
      echo "❌ 拒绝操作：${p} 是符号链接（可能指向主仓库），在它下面删除会穿透到真实数据。"
      echo "   处置：rm -f \"${p}\"  先把这个链接删掉（只删链接本身），再重跑本脚本。"
      exit 3
    fi
    p="${p%/*}"
  done
  return 0
}

place() {  # $1 = 源绝对路径  $2 = 目标绝对路径  $3 = 模式
  local src="$1" dst="$2" mode="$3" rel="${2#${WT}/}"
  # 安全断言：只允许动 worktree 内的路径（--force 会删目标，绝不能删到主仓库源）
  case "${dst}" in
    "${WT}"/*) ;;
    *) echo "❌ 拒绝操作 worktree 之外的路径：${dst}"; exit 3 ;;
  esac
  assert_parents_safe "${dst}"

  if [ -e "${dst}" ] || [ -L "${dst}" ]; then
    if [ "${FORCE}" -eq 0 ]; then
      SKIPPED=$((SKIPPED + 1))
      printf '  [跳过] %s（已存在；要重建加 --force）\n' "${rel}"
      return 0
    fi
    if [ "${mode}" = "copy" ] && [ ! -L "${dst}" ]; then
      printf '  [重建] %s（会删掉 worktree 内的本地副本，主仓库不受影响）\n' "${rel}"
    else
      printf '  [重建] %s\n' "${rel}"
    fi
    if [ "${DRY}" -eq 0 ]; then
      if [ -L "${dst}" ]; then
        rm -f "${dst}"     # 安全设计 ③：只删链接本身
      else
        rm -rf "${dst}"    # 父链已断言无符号链接 → 不会穿透
      fi
    fi
  fi

  if [ "${DRY}" -eq 1 ]; then
    printf '  [计划·%s] %s ← %s\n' "${mode}" "${rel}" "${src}"
    PLANNED=$((PLANNED + 1))
    return 0
  fi

  mkdir -p "$(dirname "${dst}")"
  if [ "${mode}" = "copy" ]; then
    # macOS APFS：cp -c 走 clonefile（写时复制）。源是符号链接时必须 -L 解引用——
    # 否则 cp 复制的只是链接本身，「写入隔离」的承诺会漏一个洞。
    if [ -L "${src}" ]; then
      cp -RLc "${src}" "${dst}" 2>/dev/null || { rm -rf "${dst}"; cp -RL "${src}" "${dst}" 2>/dev/null; } || {
        FAILED=$((FAILED + 1)); printf '  ❌ 复制失败：%s（权限 / 磁盘空间？）\n' "${rel}"; return 1; }
    else
      cp -Rc "${src}" "${dst}" 2>/dev/null || { rm -rf "${dst}"; cp -R "${src}" "${dst}" 2>/dev/null; } || {
        FAILED=$((FAILED + 1)); printf '  ❌ 复制失败：%s（权限 / 磁盘空间？）\n' "${rel}"; return 1; }
    fi
    COPIED=$((COPIED + 1))
    printf '  [复制] %s\n' "${rel}"
  else
    if ! ln -s "${src}" "${dst}" 2>/dev/null; then
      FAILED=$((FAILED + 1)); printf '  ❌ 链接失败：%s（目标已存在或权限不足？）\n' "${rel}"; return 1
    fi
    LINKED=$((LINKED + 1))
    printf '  [链接] %s → %s\n' "${rel}" "${src}"
  fi
  return 0
}

printf 'worktree：%s\n主仓库：%s\n模式：%s%s\n\n' \
  "${WT}" "${MAIN}" "${MODE}" "$([ "${DRY}" -eq 1 ] && printf '（dry-run，不落盘）' || true)"

LINKED=0
COPIED=0
SKIPPED=0
PLANNED=0
FAILED=0
while IFS=$'\t' read -r mode src rel; do
  [ -n "${rel:-}" ] || continue
  place "${src}" "${WT}/${rel}" "${mode}"
done < <(targets)

if [ "${DRY}" -eq 1 ]; then
  printf '\n计划：%d 项待补 · %d 项跳过\n' "${PLANNED}" "${SKIPPED}"
  echo "（dry-run：不落盘，也不注册工具出口）"
else
  printf '\n完成：链接 %d · 复制 %d · 跳过 %d\n' "${LINKED}" "${COPIED}" "${SKIPPED}"
  # ── 工具出口各自注册（指向**本 worktree** 的真相源 ⇒ 随分支）──
  #    两个脚本都幂等，重复跑无副作用；缺脚本时跳过（部分 clone 友好）。
  if [ -f .agents/mechanism/scripts/ai-link-skills.sh ] || [ -f .agents/mechanism/scripts/ai-sync-agents.sh ]; then
    echo
    echo "── 工具出口各自注册（随分支）──"
    [ -f .agents/mechanism/scripts/ai-link-skills.sh ] && bash .agents/mechanism/scripts/ai-link-skills.sh 2>&1 | sed 's/^/  /'
    [ -f .agents/mechanism/scripts/ai-sync-agents.sh ] && bash .agents/mechanism/scripts/ai-sync-agents.sh 2>&1 | sed 's/^/  /'
  fi
fi
if [ "${FAILED}" -gt 0 ]; then
  printf '❌ %d 项落盘失败（见上）——**不要**当作已补齐：先解决权限/磁盘问题再重跑，\n' "${FAILED}"
  echo "   否则后端会对着缺失或截断的数据静默降级。"
  exit 1
fi
echo "自检：bash .agents/mechanism/scripts/ai-worktree-prep.sh --check"
echo "⚠️  要跑会写数据的实验，请改用 --copy——默认 link 模式下写入会直接落到主仓库的真实数据上。"
