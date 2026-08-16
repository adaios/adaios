#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 定时审查（触发侧：cron 每周自动跑，防审查休眠）
#
# 用法:  bash ai-engineering/weekly-audit.sh [--auto]
# 说明:  每周自动执行：
#         W1 守护检查（G1-G7 防 P0 复发）
#         W2 结构门禁（guard-meta）+ 内容对齐（guard-align）
#         W3 沉淀检查（guard-sediment——change-log 是否连续）
#         W4 失真扫描（端点数/测试数三方对拍报告）
#         W5 未修项报告（REVIEW 战略/P1 清单）
#        --auto = cron 模式（只输出 FAIL 摘要，适合邮件/日志）
# 接入 cron（每周一 9:00）：
#   0 9 * * 1 bash /path/to/ai-engineering/weekly-audit.sh --auto >> /tmp/weekly-audit.log 2>&1
# ─────────────────────────────────────────────────────────────
set -u

cd "$(dirname "$0")/.."
ROOT="$(pwd)"
AUTO="${1:-}"
TODAY=$(date +%Y-%m-%d)

echo "═══ 每周审查（$TODAY）═══"

# W1 守护检查
echo "▸ W1 守护检查（G1-G7）..."
G1=$(bash docs/review/guard.sh 2>&1 | tail -1)
echo "   $G1"

# W2 结构 + 内容
echo "▸ W2 结构门禁..."
META=$(bash ai-engineering/guard-meta.sh 2>&1 | tail -1)
echo "   $META"
echo "▸ W2 内容对齐..."
ALIGN=$(bash ai-engineering/guard-align.sh 2>&1 | tail -1)
echo "   $ALIGN"

# W3 沉淀检查
echo "▸ W3 沉淀检查（change-log 连续性）..."
SED=$(bash ai-engineering/guard-sediment.sh 2>&1 | tail -1)
echo "   $SED"

# W4 失真扫描：端点数三方对拍
echo "▸ W4 失真扫描..."
EPT=$(cat services/adai-core/build/resources/main/META-INF/endpoints.txt 2>/dev/null || echo "?")
STATUS_EPT=$(grep -o '端点：\*\*[0-9]*\*\*' docs/reference/status.md 2>/dev/null | grep -o '[0-9]*' || echo "?")
if [ "$EPT" = "$STATUS_EPT" ]; then
    echo "   ✅ 端点数一致（$EPT）"
else
    echo "   ❌ 端点数漂移：endpoints.txt=$EPT vs status.md=$STATUS_EPT"
fi

# W5 未修项报告
echo "▸ W5 未修项（REVIEW 战略/P1）..."
bash ai-engineering/guard-context.sh 2>&1 | sed -n '/## C2/,/## C3/p' | grep "^- " | head -8 || echo "   （无未修项）"

echo ""
echo "═══ 每周审查完成（$TODAY）═══"
echo "报告存档建议：发现未修项 → docs/review/REVIEW.md；需全维度走查 → 派 8 官（process/audit.md）"
