#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# AI 资产多工具出口：**一条命令**全量重建 + 自检
#
# 用法:
#   bash .agents/mechanism/scripts/ai-sync-all.sh          # 建（幂等）+ 自检
#   bash .agents/mechanism/scripts/ai-sync-all.sh --check  # 只检查不写（0 = 齐备，1 = 有缺）
#
# 覆盖三件（换机 / 新 clone / 手工改过出口后，跑这一条就够）：
#   ① git hooks（core.hooksPath → .githooks/，仓库级配置不入库）
#   ② 技能出口（ai-link-skills.sh  → 软链；清单见 lib/ai-export-targets.sh）
#   ③ 子代理出口（ai-sync-agents.sh → 生成）
#
# 与 ai-worktree-prep.sh 的分工（**不要互相替代**）：
#   · ai-worktree-prep.sh = 新 **worktree** 的「外挂（data/.env/state）+ 出口」；
#   · ai-sync-all.sh      = **主仓库 / 换机**的「出口」一条命令（worktree 里跑也行，幂等）。
#
# 为什么需要它：此前换机要按规范 §七 记住三个脚本名与顺序（setup-hooks → link-skills →
#   sync-agents），漏跑一个的症状都是**静默**的（工具里只是「没有技能 / 没有审查官」）。
# ─────────────────────────────────────────────────────────────
set -u
cd "$(git rev-parse --show-toplevel)"   # 层级无关（六顶层重构后的统一写法）
ROOT="$(pwd)"

# shellcheck source=/dev/null
. "$ROOT/.agents/mechanism/scripts/lib/ai-export-targets.sh"

CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1

FAIL=0
echo "── 工具出口同步（ai-sync-all.sh）──"

# ── ① git hooks（仓库级配置，.git 不入库 ⇒ 换机必丢）──
if [ "$CHECK" -eq 0 ]; then
  if ! bash "$ROOT/.agents/mechanism/scripts/ai-setup-hooks.sh" >/dev/null 2>&1; then
    echo "  ❌ git hooks 启用失败 → bash .agents/mechanism/scripts/ai-setup-hooks.sh"
    FAIL=$((FAIL+1))
  fi
fi
HP="$(git config core.hooksPath 2>/dev/null || true)"
if [ -n "$HP" ] && [ -f "$ROOT/$HP/pre-commit" ]; then
  echo "  ✅ git hooks：core.hooksPath = ${HP}（pre-commit 门禁生效）"
else
  echo "  ❌ git hooks 未启用（或 pre-commit 缺失）→ bash .agents/mechanism/scripts/ai-setup-hooks.sh"
  FAIL=$((FAIL+1))
fi

# ── ② 技能出口（软链；清单来自 lib/ai-export-targets.sh）──
if [ "$CHECK" -eq 1 ]; then
  OUT="$(bash "$ROOT/.agents/mechanism/scripts/ai-link-skills.sh" --check 2>&1)" || {
    echo "$OUT" | sed 's/^/  /'; FAIL=$((FAIL+1)); }
  [ "${FAIL}" -eq 0 ] && echo "  ✅ 技能出口（${#SKILL_TARGETS[@]} 个）：${SKILL_TARGETS[*]}"
else
  OUT="$(bash "$ROOT/.agents/mechanism/scripts/ai-link-skills.sh" 2>&1)" || {
    echo "$OUT" | sed 's/^/  /'; FAIL=$((FAIL+1)); }
  [ "${FAIL}" -eq 0 ] && echo "  ✅ 技能出口（${#SKILL_TARGETS[@]} 个）已同步：${SKILL_TARGETS[*]}"
fi

# ── ③ 子代理出口（生成；TOML / md+YAML 两种格式）──
if [ "$CHECK" -eq 1 ]; then
  OUT="$(bash "$ROOT/.agents/mechanism/scripts/ai-sync-agents.sh" --check 2>&1)" || {
    echo "$OUT" | sed 's/^/  /'; FAIL=$((FAIL+1)); }
else
  OUT="$(bash "$ROOT/.agents/mechanism/scripts/ai-sync-agents.sh" 2>&1)" || {
    echo "$OUT" | sed 's/^/  /'; FAIL=$((FAIL+1)); }
fi
if [ "${FAIL}" -eq 0 ]; then
  echo "  ✅ 子代理出口（${#AGENT_TARGETS[@]} 组）：${AGENT_TARGETS[*]}"
fi

echo ""
if [ "${FAIL}" -gt 0 ]; then
  echo "结果：${FAIL} 项异常（上面已附各自的修复命令）"
  exit 1
fi
if [ "$CHECK" -eq 1 ]; then
  echo "结果：出口齐备（进一步验证: bash .agents/mechanism/guards/ai-guard-tools.sh）"
else
  echo "✅ 出口同步完成（验证: bash .agents/mechanism/scripts/ai-sync-all.sh --check）"
fi
exit 0
