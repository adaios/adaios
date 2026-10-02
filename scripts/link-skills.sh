#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 注册「用户直触发」技能到本机 AI 工具（换机器 clone 后执行一次）
#
# 用法:
#   bash scripts/link-skills.sh          # 建立 / 修复软链
#   bash scripts/link-skills.sh --check  # 只检查不写，缺失或悬空则退出码 1
#
# 背景：工具侧技能目录（.dsh/skills/ 等）被 .gitignore 忽略（注册是「本机状态」，不是仓库资产），
#       所以新 clone / 换机后必须重建。真相源始终是 git 里的
#       ai-engineering/skills/<name>/SKILL.md。
#
# 2026-10-03 批 1（RFC 20261003 项目级 AI 上下文中间层）两处变更：
#   ① 出口由 1 个扩到 4 个 —— 一个真相源喂多个工具（软链，零副本 ⇒ 无第二真相源）；
#   ② 技能布局由扁平 <name>.md 改为官方目录布局 <name>/SKILL.md ——
#      Claude Code 与 Qoder **只认目录布局**，DSH 两种都认（2026-10-03 三探针实测），
#      故目录布局是唯一能同时喂三家的形态。旧扁平软链会被自动清理。
#   ⚠️ 出口根 skills/（服务 Qoder）在 .gitignore 里必须是锚定根的 `/skills/`：
#      写成 `skills/` 会把真相源 ai-engineering/skills/ 一起忽略掉。
#
# 为什么只注册「直触发」技能而不是 16 个：
#   ai-engineering/ 下共 16 个技能包（roles/ 12 + skills/ 4），但只有「用户一句话就能直触发」
#   的才进工具 catalog。12 个审查官是**流程内触发**（process/review.md 按下表派发），
#   全量注册会常驻会话上下文，并可能在你随口改一行代码时自动派 8+1 官全量走查
#   ——那是全项目最贵的 AI 流程（见 checklists/cost.md / process/audit.md 成本纪律）。
#
# 验证：bash ai-engineering/guard-tools.sh（T4 按软链真身判定，不认名字）
# 规范：布局 / 出口 / 新增工具的完整规则见 ai-engineering/assets/ai-context-layer-spec.md
# ─────────────────────────────────────────────────────────────
set -u
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

# ── 注册清单：新增直触发技能时把名字加进来（= ai-engineering/skills/ 下的目录名）──
REGISTER=(learn-digest)

# ── 目标工具 skills 目录（相对仓库根；新增工具在此加一行）──
#    DSH              → .dsh/skills、.agents/skills（两者实测均生效）
#    Codex / Cursor / Gemini CLI / Copilot / OpenCode … → .agents/skills
#                       （Vercel 79 家 agent 表：这是**最大公约数**目录）
#    Claude Code      → .claude/skills（官方明确支持技能目录软链；官方不读 .agents/）
#    Qoder            → .qoder/skills（2026-10-03 官方 CLI + IDE 文档确认；
#                       **不是**项目根 skills/ —— 根 skills/ 只服务 OpenClaw 那类工具）
TARGETS=(
  ".dsh/skills"        # DSH
  ".agents/skills"     # DSH + Codex/Cursor/Gemini CLI/Copilot/OpenCode…
  ".claude/skills"     # Claude Code
  ".qoder/skills"      # Qoder（CLI / IDE / JetBrains 插件）
)

CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1

FAIL=0
for t in "${TARGETS[@]}"; do
  for s in "${REGISTER[@]}"; do
    SRC="ai-engineering/skills/$s/SKILL.md"
    DST="$t/$s"
    LEGACY="$t/$s.md"          # 上一代扁平布局的软链（迁移残留）

    if [ ! -f "$SRC" ]; then
      echo "  ❌ 技能源文件不存在: ${SRC}（清单里的名字写错了，或该技能尚未目录化？）"
      FAIL=$((FAIL+1)); continue
    fi

    # 相对软链：指向技能**目录**（仓库整体搬走也不断）
    REL="$(python3 -c 'import os,sys; print(os.path.relpath(sys.argv[1], sys.argv[2]))' "ai-engineering/skills/$s" "$t" 2>/dev/null || true)"
    [ -n "$REL" ] || REL="../../ai-engineering/skills/$s"

    # 上一代扁平软链：存在即视为待清理（--check 下报异常，避免"看着有、其实旧"）
    if [ -L "$LEGACY" ]; then
      if [ "$CHECK" -eq 1 ]; then
        echo "  ❌ 迁移残留（上一代扁平软链）: ${LEGACY} → $(readlink "$LEGACY")"
        FAIL=$((FAIL+1)); continue
      fi
      rm -f "$LEGACY"
      echo "  🧹 清理旧扁平软链: ${LEGACY}"
    fi

    if [ -L "$DST" ] && [ "$(readlink "$DST")" = "$REL" ] && [ -f "$DST/SKILL.md" ]; then
      [ "$CHECK" -eq 0 ] && echo "  ✅ 已注册: ${DST} → ${REL}"
      continue
    fi

    if [ "$CHECK" -eq 1 ]; then
      if [ -L "$DST" ]; then
        echo "  ❌ 悬空/过期软链: ${DST} → $(readlink "$DST")（期望 ${REL}）"
      else
        echo "  ❌ 未注册: ${DST}（跑 bash scripts/link-skills.sh）"
      fi
      FAIL=$((FAIL+1)); continue
    fi

    # 安全护栏：目标已存在且**不是软链**（真实目录/文件）→ 不覆盖，报错走人
    if [ -e "$DST" ] && [ ! -L "$DST" ]; then
      echo "  ❌ 目标已存在且不是软链，拒绝覆盖: ${DST}（人工确认后再处理）"
      FAIL=$((FAIL+1)); continue
    fi

    mkdir -p "$t"
    rm -f "$DST"
    ln -s "$REL" "$DST"
    echo "  🔗 已注册: ${DST} → ${REL}"
  done
done

if [ "$FAIL" -gt 0 ]; then
  echo ""
  echo "结果: ${FAIL} 项异常"
  exit 1
fi

if [ "$CHECK" -eq 0 ]; then
  echo ""
  echo "✅ 技能注册完成（验证: bash ai-engineering/guard-tools.sh）"
fi
exit 0
