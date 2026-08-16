# engine — 交易引擎能力层（规则执行内核）

> **G-3/G-5（2026-08-16）**：能力抽离 + 独立形态的规格层。知识（`knowledge/`）是真相源，本层是**语言无关的规则执行规格**，宿主实现（Java/Python）按此规格编译。

## 定位

```
knowledge/（真相源：规则/策略/教训）
    ↓ 编译
engine/（能力层：规则执行内核——接口规格，语言无关）
    ↓ 形态输出
① AdaiOS 插件（Java 实现，已接入）  ② Skill/Agent  ③ REST  ④ MCP
```

**关系**：knowledge 回答「规则是什么」，engine 回答「规则怎么执行」，形态回答「给谁用」。

## 接口规格

| 接口 | 规格文档 | 职责 | 判定口径（rules.md）|
|:-----|:---------|:-----|:---------------------|
| `matchRules` | `rules-api.md` §1 | 规则条目解析 + 区间抽取（R66-R95 硬约束）| `**R{n} 标题** + > 描述` 格式 |
| `evaluateStopLoss` | `rules-api.md` §2 | 止损硬判定 | R66 收盘跌破止损位 → clear；R68 入场即设止损 |
| `evaluatePosition` | `rules-api.md` §3 | 仓位硬判定 | R81 单票上限 25%（4-5 仓）|

## 实现状态

| 形态 | 实现 | 状态 |
|:-----|:-----|:----:|
| Java 引擎 | `services/adai-core/.../domain/trading/engine/`（`TradingRuleEngine` + `DefaultTradingRuleEngine`）| ✅ G-3 完成，537+ 测试全绿 |
| 建议引擎接入 | `TradingAdviceAppService` 注入引擎，硬判定信号进 prompt | ✅ G-3 |
| Python 实现 | 按 `rules-api.md` 规格实现（MCP/独立 Agent 用）| 📋 待 G-5 后续 |

## 规则

1. **建议是输出不是执行**：引擎只产出判定信号（verdict），不做任何交易动作。
2. **判定口径唯一**：实现必须与 `rules-api.md` 一致；口径变更先改规格，再改实现。
3. **不持有个人数据**：所有引擎调用吃传入的持仓/行情，不落库、不持有（独立形态无隐私风险）。
