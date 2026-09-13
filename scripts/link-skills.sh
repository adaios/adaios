#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 注册「用户直触发」技能到本机 AI 工具（换机器 clone 后执行一次）
#
# 用法:
#   bash scripts/link-skills.sh          # 建立 / 修复软链
#   bash scripts/link-skills.sh --check  # 只检查不写，缺失或悬空则退出码 1
#
# 背景：工具侧技能目录（.dsh/skills/）被 .gitignore 忽略（注册是「本机状态」，不是仓库资产），
#       所以新 clone / 换机后必须重建。真相源始终是 git 里的 ai-engineering/skills/*.md。
#
# 为什么只注册 1 个而不是 16 个：
#   ai-engineering/ 下共 16 个技能包（roles/ 12 + skills/ 4），但只有「用户一句话就能直触发」
#   的才进工具 catalog。12 个审查官是**流程内触发**（process/review.md 按下表派发），
#   全量注册会常驻会话上下文，并可能在你随口改一行代码时自动派 8+1 官全量走查
#   ——那是全项目最贵的 AI 流程（见 checklists/cost.md / process/audit.md 成本纪律）。
#
# 验证：bash ai-engineering/guard-tools.sh（T4 按软链真身判定，不认名字）
# ─────────────────────────────────────────────────────────────
set -u
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

# ── 注册清单：新增直触发技能时把名字加进来（文件名，不含 .md）──
REGISTER=(learn-digest)

# ── 目标工具 skills 目录（相对仓库根；新增工具在此加一行）──
TARGETS=(".dsh/skills")

CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1

FAIL=0
for t in "${TARGETS[@]}"; do
  for s in "${REGISTER[@]}"; do
    SRC="ai-engineering/skills/$s.md"
    DST="$t/$s.md"

    if [ ! -f "$SRC" ]; then
      echo "  ❌ 技能源文件不存在: ${SRC}（清单里的名字写错了？）"
      FAIL=$((FAIL+1)); continue
    fi

    # 相对软链：仓库整体搬走也不断
    REL="$(python3 -c 'import os,sys; print(os.path.relpath(sys.argv[1], sys.argv[2]))' "$SRC" "$t" 2>/dev/null || true)"
    [ -n "$REL" ] || REL="../../ai-engineering/skills/$s.md"

    if [ -L "$DST" ] && [ "$(readlink "$DST")" = "$REL" ] && [ -f "$DST" ]; then
      [ "$CHECK" -eq 0 ] && echo "  ✅ 已注册: ${DST}"
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
