# 规则接口规格（Rules API）

> **语言无关规格**（G-3/G-5）：知识真相源 = `knowledge/context/rules.md`；本文件定义引擎如何执行规则。
> Java 实现：`services/adai-core/.../domain/trading/engine/TradingRuleEngine`（判定口径与本文件一致，测试覆盖）。

## 1. matchRules — 规则解析

**输入**：rules.md 全文（Markdown）
**输出**：规则条目列表 `[{number, title, detail}]`
**格式契约**：`**R{n} 标题**`（可选换行 `> 描述`）

```json
{ "number": 66, "title": "只输一根K线（核心理念）", "detail": "止损设在进场K线最低价下方几个价位（或1%），收盘跌破就走。" }
```

**区间抽取**：调用方按需过滤（建议引擎取 R66-R95 作决策硬约束）。

## 2. evaluateStopLoss — 止损硬判定

**输入**：`currentPrice`（现价，行情优先）、`stopLossPrice`（用户预设止损位，可 null）
**输出**：`{verdict: OK | BREACHED, ruleRef, message}`

| 条件 | verdict | 规则 |
|:-----|:--------|:-----|
| `stopLossPrice == null` | OK（无据可判）| R68 入场即设止损（买入时已强制填写）|
| `currentPrice < stopLossPrice` | **BREACHED** → suggestion 必须 clear | R66 只输一根K线 |
| 其余 | OK | R66 |

## 3. evaluatePosition — 仓位硬判定

**输入**：`positionPercent`（单票持仓占比 0-100，后端按市值/总市值确定性计算）
**输出**：`{verdict: OK | OVER_WEIGHT, ruleRef, message}`

| 条件 | verdict | 规则 |
|:-----|:--------|:-----|
| `positionPercent > 25` | **OVER_WEIGHT** → suggestion 参考 reduce | R81 100万以下分4-5个仓位（单票 1/4~1/5）|
| 其余 | OK | R81 |

## 4. 边界

- **确定性优先**：可计算判定（止损/仓位）由引擎硬判，不依赖 LLM 判读；语义判定（R53 没涨=错、R120 卖点对应买点）保留 prompt 注入。
- **不执行**：verdict 是信号，交易动作永远由人决策（建议是输出不是指令）。
- **口径变更流程**：改本文件 → 同步改 Java 实现 + 测试 → 重新 build 形态。
