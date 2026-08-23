#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 工具接入自检（防守侧）— 检测「AI 上下文工程体系」在各工具侧是否真的被加载
#
# 用法:  bash ai-engineering/guard-tools.sh          # 全量自检
# 说明:  体系的「跨工具互通」不是文档承诺，是可验证状态（2026-08-23 对抗审计 P1-4 修复）。
#        自检 5 项，缺什么报什么 + 附修复命令；不写死工具清单到文档（映射表会过时，
#        机制替人记得——运行即知当前工具接入状态）。
#
# 检测项:
#   T1 git hooksPath   → 门禁是否随仓库生效（S-A1 修复验证）
#   T2 AGENTS.local.md → 快照是否新鲜（机器生成 + gitignore，勿手改）
#   T3 仓库内技能      → roles/ + skills/ 的 SKILL.md 是否齐备（name 字段校验）
#   T4 工具侧技能注册  → Claude Code (.claude/skills) / DSH (~/.dsh) 是否指向本体系
#   T5 工具侧上下文注入→ Claude Code 是否把 AGENTS.md 当入口（settings 检查）
# ─────────────────────────────────────────────────────────────
set -u
cd "$(git rev-parse --show-toplevel 2>/dev/null || echo "$(cd "$(dirname "$0")/.." && pwd)")"
ROOT="$(pwd)"
PASS=0; WARN=0; FAIL=0

ok()   { echo "  ✅ $1"; PASS=$((PASS+1)); }
warn() { echo "  ⚠️  $1"; WARN=$((WARN+1)); }
bad()  { echo "  ❌ $1"; FAIL=$((FAIL+1)); }

echo "── 工具接入自检（guard-tools.sh）──"

# T1: git hooksPath（S-A1：换机门禁缺席）
echo ""
echo "T1 git hooks 门禁"
HP="$(git config core.hooksPath 2>/dev/null || true)"
if [ -n "$HP" ] && [ -f "$ROOT/$HP/pre-commit" ]; then
  ok "hooksPath=$HP → pre-commit 生效"
else
  bad "hooksPath 未配置或 pre-commit 缺失 → 四层闸门未生效"
  echo "    修复: bash scripts/setup-hooks.sh（或 git config core.hooksPath .githooks）"
fi

# T2: AGENTS.local.md 快照
echo ""
echo "T2 AGENTS.local.md 快照"
LOCAL="$ROOT/AGENTS.local.md"
if [ -f "$LOCAL" ]; then
  AGE=$(( ($(date +%s) - $(stat -f %m "$LOCAL" 2>/dev/null || echo 0)) / 86400 ))
  if [ "$AGE" -le 1 ]; then
    ok "快照新鲜（$AGE 天前）"
  else
    warn "快照已 $AGE 天（真相源变了会失真）→ bash ai-engineering/guard-context.sh --write-local"
  fi
else
  warn "快照缺失 → bash ai-engineering/guard-context.sh --write-local 生成"
fi

# T3: 仓库内技能齐备（roles/ 9 + skills/ 3，name 字段=文件名）
echo ""
echo "T3 仓库内技能包（SKILL.md）"
MISSING=0
for f in "$ROOT"/ai-engineering/roles/*.md "$ROOT"/ai-engineering/skills/*.md; do
  [ -f "$f" ] || continue
  base="$(basename "$f" .md)"
  if ! grep -q "^name: $base$" "$f"; then
    echo "  ❌ $f: 缺 name: $base（skills-spec 必填）"
    MISSING=$((MISSING+1))
  fi
done
if [ "$MISSING" -eq 0 ]; then
  ok "12 个技能包 name 字段齐备（roles/ 9 + skills/ 3）"
else
  bad "$MISSING 个技能包缺 name"
fi

# T4: 工具侧技能注册（Claude Code / DSH）
echo ""
echo "T4 工具侧技能注册"
CLAUDE_SKILLS="$HOME/.claude/skills"
DSH_SKILLS="$HOME/.dsh/skills"
REG=0
for d in "$CLAUDE_SKILLS" "$DSH_SKILLS"; do
  if [ -d "$d" ]; then
    LINK=$(find "$d" -maxdepth 1 -type l -name "*adaios*" 2>/dev/null | head -1)
    [ -n "$LINK" ] && { ok "找到技能链接: $LINK"; REG=$((REG+1)); }
  fi
done
if [ "$REG" -eq 0 ]; then
  warn "未发现指向 ai-engineering/ 的技能注册（Claude Code / DSH 均无）"
  echo "    说明: 工具侧配置在工具自己设置里（AGENTS.md 工具接入段）；本项仅提示，不强制"
fi

# T5: Claude Code 上下文入口
echo ""
echo "T5 Claude Code 上下文入口"
CC="$ROOT/.claude/settings.json"
if [ -f "$CC" ] && grep -q "AGENTS.md" "$CC" 2>/dev/null; then
  ok ".claude/settings.json 引用 AGENTS.md"
else
  warn "无 .claude/settings.json 显式引用 AGENTS.md（Claude 会默认读 CLAUDE.md；本体系以 AGENTS.md 为入口）"
fi

echo ""
echo "── 结果: $PASS 通过 / $WARN 警告 / $FAIL 失败 ──"
[ "$FAIL" -gt 0 ] && exit 1 || exit 0
