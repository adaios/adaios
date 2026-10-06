---
title: R-04 通用 K 线 · 实施方案（2026-10-07 晚）
description: 把「案例专图」泛化成横切四个地方的通用 K 线——四区（主图+量+MACD+KDJ）、我的买卖点标记、止损线/峰值浮盈线；含接口契约、前端组件结构、分批与验收
version: 1
created: 2026-10-07
updated: 2026-10-07
status: active
lines: 85
depends-on:
  - ./design-final-20261006.md
  - ./requirement.md
related:
  - ./uiux-discovery-20261007.md
  - ./frontend-gap-20261006.md
tags: [workspace, trading, design, kline]
---

# R-04 通用 K 线 · 实施方案

> **触发**：adai 2026-10-07「目前生产 K 线图还有成交量、MACD、KDJ，我希望你补上这些副图指标 …… 定了方案不用我同意就开始编码，明天直接看结果」。
> **范围**：只做 **web 端 K 线**（app 端不做图 —— 需求明写「K 线在电脑端」）。**不动**导入/对账/分析其它部分。

## 一、现状（实测 · 2026-10-07）

| 事实 | 出处 |
|:--|:--|
| 生产**唯一上线的 K 线**是案例专图：四区布局（主图 40% / 量 20% / MACD 20% / KDJ 20%），MA5/10/20/60，买点日标记，**KDJ(9,3,3) / MACD(12,26,9) 前端从 OHLCV 重算**（对齐后端 `KdjIndicator`/`MacdIndicator`） | `apps/adai-web/lib/widgets/case_kline_chart.dart`（618 行） |
| **持仓 / 自选 / 清仓都没有图**：自选只有「买点信号」文字列，清仓只有三维打分 + 卖点评价 | `frontend-gap-20261006.md` §二 |
| 图上三标记（**我的买卖点 / 止损线 / 峰值浮盈线**）**均未做** | `scope-frontend-20261006.md` → `R-04` 批 3 |
| **`GET /trading/kline` 端点不存在**（设计里定了契约，没落地） | `TradingController` 实测 44 个端点无此路径 |
| 取数能力已有：`KlineService#kline / #klineRange`（TDX → 腾讯 → 新浪；熔断 + 按日缓存 + health） | `KlineService.java:201 / :250` |

## 二、目标（今晚交付）

**一张图，四处共用**：持仓 / 自选 / 清仓 / 案例 —— 同一组件、同一口径，只有标记不同。

1. **四区**：主图（蜡烛 + MA + 我的买卖点 + 止损线 + 峰值浮盈线）/ 成交量 / MACD(12,26,9) / KDJ(9,3,3)；
2. **我的买卖点**：B（买入）/ S（卖出）/ T（加仓）标记，**点得进去**（看当时那笔）；
3. **两条线**：`stopLine`（你定的止损）· `peakLine`（峰值浮盈线 —— **只在见顶之后才画**，因为它是那时才存在的）；
4. **清仓股照画**：卖点标在图上 + **卖后走势接着画**（＝清仓页「卖掉之后到现在」那一列的可视化）；
5. 数据取不到时**如实说取不到**（不画假图、不沿用旧价）。

## 三、接口契约（新增）

```
GET /api/v1/trading/kline?symbol=603993&window=90        （需 trading 插件；X-User-Id）
→ {
    symbol, name, window,
    candles: [{date, open, high, low, close, volume}],   // 旧→新
    marks:   [{date, type: "B"|"S"|"T", price, quantity, note, lotRef?}],
    stopLine:  {price, from, note} | null,               // 你定的止损（最近一条）
    peakLine:  {price, peakDate, basis} | null,          // 峰值浮盈线（见顶后才有）
    context: { held: bool, closedAt?, pnlPct?, afterPct?, ruleNote? }
  }
```

- `marks` 由**该标的的成交记录**推导（B=买入 / S=卖出 / T=加仓，同日同向合并）；
- `stopLine` 取**批次止损**（`lot-stoploss.json`）或规则里的止损，取最近一条；
- `peakLine` = 持仓期内最高收盘 × (1 − 回吐阈值)，无持仓/无峰值时 **null**；
- K 线取不到 → 200 + `candles: []` + `note`，前端出「暂时取不到行情」而不是空白图。

## 四、前端组件结构

```
apps/adai-web/lib/widgets/trading_kline_chart.dart     ← 新：通用 K 线（从 case 图泛化）
  ├─ TradingKlineChart(candles, marks, stopLine, peakLine, height, onTapMark)
  ├─ TradingIndicators（MA / MACD / KDJ 计算，**直接复用 CaseIndicators 的算法**）
  └─ _TradingKlinePainter（四区布局 + 标记 + 两条线）
apps/adai-web/lib/widgets/case_kline_chart.dart        ← 保留：改为内部转调通用组件（案例口径不回退）
```

**接线**：持仓 / 自选 / 清仓 三处表格行尾加「图」入口 → 打开同一个 K 线弹窗（拉 `/trading/kline`）。

## 五、验收（可判定）

1. 四区都在，指标数值与后端 `KdjIndicator`/`MacdIndicator` 口径一致（前端重算，测试锁数值）；
2. 有我买卖点的标的，图上出现 B/S/T 标记；**没有的不硬编**；
3. 清仓股打开有图，且**卖掉之后那段也在图上**；
4. 行情取不到 → 人话提示，不画假图；
5. 单测：指标计算 + marks 推导 + 端点契约（`trading_kline_test.dart` + 后端 `TradingKlineAppServiceTest`）。

## 六、今晚不做（如实标）

- app 端 K 线（需求明写不做）；规则 / 计划 两个区的**图**（留后续）；
- web 首屏信息架构的代码实现（今晚只做 K 线这一条）——**其余设计稿已定，按批推进**。
