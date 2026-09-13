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
#   T4 工具侧技能注册  → .dsh / .claude / .agents 的 skills/ 是否软链回本体系（按真身判定）
#   T5 工具侧上下文注入→ 若存在 .claude/settings.json，是否显式引用 AGENTS.md（无则仅提示）
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

# T3: 仓库内技能齐备（name 字段=文件名；计数动态，新增角色不再需要改文案）
echo ""
echo "T3 仓库内技能包（SKILL.md）"
MISSING=0; N_ROLES=0; N_SKILLS=0
for f in "$ROOT"/ai-engineering/roles/*.md; do
  [ -f "$f" ] || continue
  N_ROLES=$((N_ROLES+1))
  base="$(basename "$f" .md)"
  if ! grep -q "^name: $base$" "$f"; then
    echo "  ❌ $f: 缺 name: $base（skills-spec 必填）"
    MISSING=$((MISSING+1))
  fi
done
for f in "$ROOT"/ai-engineering/skills/*.md; do
  [ -f "$f" ] || continue
  N_SKILLS=$((N_SKILLS+1))
  base="$(basename "$f" .md)"
  if ! grep -q "^name: $base$" "$f"; then
    echo "  ❌ $f: 缺 name: $base（skills-spec 必填）"
    MISSING=$((MISSING+1))
  fi
done
if [ "$MISSING" -eq 0 ]; then
  ok "$((N_ROLES+N_SKILLS)) 个技能包 name 字段齐备（roles/ ${N_ROLES} + skills/ ${N_SKILLS}）"
else
  bad "$MISSING 个技能包缺 name"
fi

# T4: 工具侧技能注册（按「链接是否指向本仓库 ai-engineering/」判定，不认名字、不写死工具）
echo ""
echo "T4 工具侧技能注册"
REG=0
for d in "$ROOT/.dsh/skills" "$HOME/.dsh/skills" "$ROOT/.claude/skills" "$HOME/.claude/skills" "$ROOT/.agents/skills" "$HOME/.agents/skills"; do
  [ -d "$d" ] || continue
  for f in "$d"/*; do
    [ -e "$f" ] || [ -L "$f" ] || continue
    # 解析软链真身（macOS 无 readlink -f 兜底用 python3/realpath）
    TGT="$(readlink -f "$f" 2>/dev/null || python3 -c 'import os,sys;print(os.path.realpath(sys.argv[1]))' "$f" 2>/dev/null || echo "$f")"
    case "$TGT" in
      "$ROOT"/ai-engineering/*) ok "技能已注册: ${f/#$HOME/~} → ${TGT#$ROOT/}"; REG=$((REG+1));;
    esac
  done
done
if [ "$REG" -eq 0 ]; then
  warn "未发现指向 ai-engineering/ 的技能注册（.dsh / .claude / .agents 均无）"
  echo "    修复: bash scripts/link-skills.sh（换机/新 clone 后必跑一次）"
fi

# T5: 工具侧上下文入口（**仅当该工具确实在用**才校验；不用则跳过，避免永久警告让「全绿」失去信号）
echo ""
echo "T5 工具侧上下文入口"
CC="$ROOT/.claude/settings.json"
if [ ! -f "$CC" ]; then
  ok "未使用 Claude Code（无 .claude/settings.json）→ 跳过"
elif grep -q "AGENTS.md" "$CC" 2>/dev/null; then
  ok ".claude/settings.json 引用 AGENTS.md"
else
  warn "存在 .claude/settings.json 但未引用 AGENTS.md（Claude 会默认读 CLAUDE.md；本体系以 AGENTS.md 为入口）"
fi

echo ""
echo "── 结果: $PASS 通过 / $WARN 警告 / $FAIL 失败 ──"
[ "$FAIL" -gt 0 ] && exit 1 || exit 0
