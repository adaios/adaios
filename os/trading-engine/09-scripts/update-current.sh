#!/usr/bin/env bash
# =============================================================================
# update-current.sh — current.md 注记刷新（G-4，2026-08-16；FP-S4 声明修正）
#
# 解决的问题：current.md（择时状态 OAMV）2026-07-11 后停更——信号来源/复盘引用的
# 维护入口丢失。本脚本是**注记刷新器**（不代判、不重组内容，判断是人的）：
#
# 本脚本做什么（确定性部分，自动，幂等）：
#   1. 更新时间戳（语义：注记刷新，非状态更新——市场状态/持仓仍待人工确认）
#   2. 注入「信号口径来源：05-system」注记（择时信号口径的溯源）
#   3. 注入「当前判断参考最近复盘」注记（08-review 最近文件，空则省略）
#   4. 持仓/市场状态区块原样保留——由你按 08-review 手动刷新
#
# 用法：sh 09-scripts/update-current.sh
# 说明：可重复运行（幂等），注记不堆叠。
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

# 3. 更新注记区块（幂等：已存在同文注记则替换，不重复堆叠——FP-P3 修复）
#    持仓区块（## 当前持仓）与其后内容不动；时间戳只表示「注记已刷新」，市场状态/持仓仍待人工确认。
python3 - "$CONTEXT_FILE" "$TODAY" "$SIGNAL_LINES" "$LATEST_REVIEW" <<'PY'
import sys, pathlib, re

path, today, signal_lines, latest_review = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
text = pathlib.Path(path).read_text(encoding="utf-8")

# 时间戳：注明「注记刷新」而非「状态更新」——市场状态/持仓仍需人工确认（防骗 build 门禁）
text = re.sub(
    r"> 更新时间：\*\*\S+\*\*（[^）]*）",
    f"> 更新时间：**{today}**（update-current.sh 注记刷新；市场状态/持仓待人工确认）",
    text)

# 信号来源注记（幂等：匹配到已有注记行则覆盖，否则插入）
signal_note = f"> 信号口径来源：05-system（update-current.sh 自动抽取，{today}）"
signal_re = re.compile(r"> 信号口径来源：05-system（update-current\.sh 自动抽取，[^）]*）")
if signal_lines.strip():
    anchor = r"(\| OAMV 两日涨幅之和 ≥ \*\*\+4%\*\* \| 转回多头区间，可积极入场 \|\n)"
    if signal_re.search(text):
        text = signal_re.sub(signal_note, text)
    else:
        m = re.search(anchor, text)
        if m:
            text = text[:m.end()] + signal_note + "\n" + text[m.end():]

# 复盘来源注记（幂等 + 空复盘省略：无复盘记录时不产生占位符行）
if latest_review:
    review_note = f"> 当前判断参考最近复盘：`{latest_review}`（update-current.sh 注记）"
    review_re = re.compile(r"> 当前判断参考最近复盘：`[^`]*`（update-current\.sh 注记）")
    if review_re.search(text):
        text = review_re.sub(review_note, text)
    else:
        anchor2 = "## 当前关注的系统信号\n"
        if anchor2 in text:
            text = text.replace(anchor2, anchor2 + review_note + "\n", 1)

pathlib.Path(path).write_text(text, encoding="utf-8")
print("✓ 已更新（幂等）：时间戳 + 信号来源注记 + 复盘引用")
print("  ⚠️ 持仓/市值/盈亏区块保留原样——请按 08-review 手动确认后刷新（判断是人的，AI 不代判）")
PY
