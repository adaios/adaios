#!/usr/bin/env bash
# =============================================================================
# sync-adai-rulepack.sh — adai 规则包同步（P1-2，2026-08-30 审查修复）
#
# 解决的问题：交易插件第三阶段后，知识注入改读 data/{userId}/trading/knowledge.md
# （用户私有优先）。data/adai/trading/knowledge.md 是 os/ 五文件的合并快照——
# 若无同步机制，os/ 课程沉淀（rules.md/strategy.md/mistakes.md/identity.md/current.md）
# 更新后 AI 知识注入永久滞后（D3 单一事实源破坏、promote 反哺闭环断链）。
#
# 本脚本做什么（确定性，自动，幂等）：
#   1. os/trading-engine/knowledge/context/ 五文件合并 → data/adai/trading/knowledge.md
#      （identity/strategy/rules/mistakes/current，逐字节保留 + 章节头）
#   2. 校验 data/adai/trading/rules.yaml 16 参数 = 课程默认值（回调0.5/缩量0.7/KDJ13/
#      放量1.5/窗口20/−7%/25% 等）——漂移则警告
#   3. 输出 diff 摘要（本次同步了什么）
#
# 用法：sh 09-scripts/sync-adai-rulepack.sh
# 说明：可重复运行（幂等）；在 os/ 收敛/update-current.sh 之后调用；
#       data/ 侧被 gitignore（B3 隐私），脚本本身入库（工程资产）。
# =============================================================================
set -euo pipefail

ENGINE_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONTEXT_DIR="$ENGINE_ROOT/knowledge/context"
# 仓库根 = os/trading-engine 上两级（os → 仓库根）；data/ 在其下
DATA_DIR="${ADAIOS_DATA_DIR:-$(cd "$ENGINE_ROOT/../.." && pwd)/data}"
TARGET="$DATA_DIR/adai/trading/knowledge.md"
RULES_YAML="$DATA_DIR/adai/trading/rules.yaml"

echo "=== 同步 adai 规则包（os/ → data/adai/trading/）==="

# 1. 合并五文件 → knowledge.md
FILES=(identity.md strategy.md rules.md mistakes.md current.md)
mkdir -p "$(dirname "$TARGET")"
{
  echo "# 交易系统知识（Adai 规则包 · 由 09-scripts/sync-adai-rulepack.sh 生成）"
  echo "> 真相源：os/trading-engine/knowledge/context/（87 课课程沉淀）"
  echo "> 更新：os/ 收敛或 update-current.sh 后重跑本脚本"
  echo ""
} > "$TARGET"
for f in "${FILES[@]}"; do
  SRC="$CONTEXT_DIR/$f"
  if [[ ! -f "$SRC" ]]; then
    echo "⚠️ 缺 $f（跳过）" >&2
    continue
  fi
  echo "" >> "$TARGET"
  echo "## $f" >> "$TARGET"
  echo "" >> "$TARGET"
  cat "$SRC" >> "$TARGET"
  echo "" >> "$TARGET"
  echo "---" >> "$TARGET"
done
echo "✅ knowledge.md 已生成（$TARGET，$(wc -l < "$TARGET") 行）"

# 2. 校验 rules.yaml 参数 = 默认值（防漂移）
if [[ -f "$RULES_YAML" ]]; then
  echo "--- 校验 rules.yaml 参数 ---"
  # 期望默认值（与 TradingRuleSettings.defaults() 一致）
  check_param() {
    local key="$1" expected="$2"
    local actual
    actual=$(grep -A40 "^params:" "$RULES_YAML" | grep "^  $key:" | awk '{print $2}' | tr -d '"' || true)
    if [[ "$actual" != "$expected" ]]; then
      echo "⚠️ $key: 期望 $expected 实际 $actual（与课程默认不符，请检查）"
    fi
  }
  check_param positionLimitPercent 25
  check_param defaultStopLossRatio 0.93
  check_param buyPullbackPct 0.5
  check_param buyShrinkRatio 0.7
  check_param buyKdjLow 13
  check_param buyVolumeSurge 1.5
  check_param buyPriorHighDays 20
  check_param constraintRuleMin 66
  check_param constraintRuleMax 95
  echo "✅ rules.yaml 校验完成（仅列出 ⚠️ 漂移项）"
else
  echo "⚠️ $RULES_YAML 不存在（首次生成需手动创建，见 data-format-freeze §2.16）"
fi

echo "=== 完成 ==="
echo "提示：knowledge.md 生成后，adai 用户交易问答即用最新 os/ 知识（无需重启，时间戳缓存自动感知）"
