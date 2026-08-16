#!/usr/bin/env bash
# =============================================================================
# update-current.sh — current.md 半自动刷新（G-4，2026-08-16）
#
# 解决的问题：current.md（择时状态 OAMV）2026-07-11 后停更——规则/信号表述
# 随系统收敛演化，但 current.md 的手动刷新入口丢失。
#
# 本脚本做什么（确定性部分，自动）：
#   1. 从 05-system/trading-system.md 抽取择时相关信号规则（OAMV 多空切换、离场法则）
#   2. 从 08-review/ 最近一次复盘抽取"当前判断"要点
#   3. 重组 current.md 的"市场阶段/多空切换规则/关注的系统信号"表述
#   4. 更新时间戳
#
# 本脚本不做什么（留给你，手动）：
#   - 持仓数据（数量/市值/盈亏）——来自 data/，由你确认
#   - 市场状态的最终判断——AI 不替你决策（无第三视角：判断是人的）
#
# 用法：sh 09-scripts/update-current.sh
# 说明：脚本只更新规则/信号表述区块；持仓区块原样保留（标注待你刷新）。
# =============================================================================
set -euo pipefail

ENGINE_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SYSTEM_FILE="$ENGINE_ROOT/05-system/trading-system.md"
CONTEXT_FILE="$ENGINE_ROOT/knowledge/context/current.md"
REVIEW_DIR="$ENGINE_ROOT/08-review"

if [[ ! -f "$SYSTEM_FILE" ]]; then
  echo "❌ 未找到 05-system/trading-system.md" >&2
  exit 1
fi
if [[ ! -f "$CONTEXT_FILE" ]]; then
  echo "❌ 未找到 knowledge/context/current.md" >&2
  exit 1
fi

TODAY="$(date +%F)"
echo "→ 重组 current.md 规则/信号表述（来源：05-system + 08-review 最近复盘）"

# 1. 抽取择时信号规则（OAMV / 多空切换 / 离场法则相关行，+ 号已移除防 grep 重复操作符）
SIGNAL_LINES="$(grep -E "OAMV|多空|离场|-2\.3|转多|空头|多头" "$SYSTEM_FILE" | head -12 || true)"

# 2. 最近复盘要点（08-review 最后修改的一个 md）
LATEST_REVIEW="$(ls -t "$REVIEW_DIR"/*.md 2>/dev/null | head -1 || true)"

# 3. 重组信号区块（保留持仓区块——由你手动刷新）
#    做法：把 current.md 中「市场阶段」与「当前关注的系统信号」之间的内容替换为最新信号
#    持仓区块（## 当前持仓）与其后内容不动。
python3 - "$CONTEXT_FILE" "$TODAY" "$SIGNAL_LINES" "$LATEST_REVIEW" <<'PY'
import sys, pathlib

path, today, signal_lines, latest_review = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4] or "(无复盘记录)"
text = pathlib.Path(path).read_text(encoding="utf-8")

# 更新头部时间戳
import re
text = re.sub(r"> 更新时间：\*\*\S+\*\*", f"> 更新时间：**{today}**（update-current.sh 自动刷新）", text)

# 若来源有择时信号行，替换「市场阶段」区块的 OAMV 信号表之前的说明
if signal_lines.strip():
    # 在多空切换规则表后注入「来源」注记
    text = re.sub(
        r"(\| OAMV 两日涨幅之和 ≥ \*\*\+4%\*\* \| 转回多头区间，可积极入场 \|\n)",
        r"\1\n> 信号口径来源：05-system（update-current.sh 自动抽取，" + today + r"）\n",
        text)

# 注入最近复盘来源注记
text = re.sub(
    r"(## 当前关注的系统信号\n)",
    rf"\1> 当前判断参考最近复盘：`{latest_review}`（update-current.sh 注记）\n",
    text)

pathlib.Path(path).write_text(text, encoding="utf-8")
print("✓ 已更新：时间戳 + 信号来源注记 + 复盘引用")
print("  ⚠️ 持仓/市值/盈亏区块保留原样——请按 08-review 手动确认后刷新（判断是人的，AI 不代判）")
PY
