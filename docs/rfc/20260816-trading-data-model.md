---
title: 交易数据模型分层——用户提供 vs 可查询（trading domain 可执行化）
date: 2026-08-16
status: draft
---

# 交易数据模型分层（用户提供 vs 可查询）

> **方向 RFC**：trading domain（R1-R120 规则体系）当前"规则执行所需数据缺失"——建议引擎把 R66-R95 当硬约束，但数据里没有止损位/买点/入场日期，判定只能靠 LLM 猜。本 RFC 定**数据分层模型**：可查询的数据（行情）动态注入，用户只提供"只有自己知道的"（成交/止损/买点/原因），让规则真正可执行。

---

## 〇、问题（实测证据）

| # | 证据 | 影响 |
|:-:|:-----|:-----|
| A | Position 只有 6 字段（symbol/name/qty/avgCost/currentPrice/lastUpdated），**无止损位/买点/入场日期/角色** | 建议引擎把 R66-R95 当硬约束，但"已跌破止损位→clear"无数据可判 |
| B | 交易落盘无逐笔流水（positions.md 聚合快照 + rec_*.md 自然语言文本）| 无法回溯单笔交易/首买日 |
| C | TradeRecord 是死代码（amount/timestamp/sourceRecordId 无 Service 引用）| 结构化的交易历史不存在 |
| D | cashBalance 手填且磁盘实样为 0（已失真）| R71/R73/R89 净值类规则无据 |
| E | 复盘基于当日记录文本（无原因/计划字段）| R106 对错判定无从谈起 |

## 一、核心原则：数据分层（用户提供 vs 可查询）

```
┌─────────────────────────────────────────────────────────┐
│  A. 可查询层（外部行情 · 用户永远不用填）                    │
│  TencentMarketDataSource.quote(symbol) 动态注入           │
│  ├─ 现价 / 昨收 / 今开 / 最高 / 最低（当日K线）              │
│  ├─ 涨跌幅 / 成交量 / 名称（代码→补全）                     │
│  → 建议引擎/复盘/展示/预警 全部用查询值，不存为用户输入        │
└─────────────────────────────────────────────────────────┘
              ▲ 查询时注入
              │
┌─────────────────────────────────────────────────────────┐
│  B. 用户提供层（只有用户知道 · 必须记录）                    │
│  买入时 P0：                                               │
│  ├─ 成交价 price（用户填，可能≠现价）                       │
│  ├─ 数量 volume（用户填）                                  │
│  ├─ 止损位 stopLossPrice（用户计划，R66/R68 核心）           │
│  ├─ 买点类型 buyPoint（用户战法，R33-50）                   │
│  └─ 入场日期 entryDate（后端自动取当天，首买日持久化）         │
│  买入时 P1（可选）：                                        │
│  ├─ 交易原因/预期 reason + 目标价 targetPrice（复盘锚点）     │
│  └─ 持仓角色 role（web 持仓编辑，R82-84）                   │
└─────────────────────────────────────────────────────────┘
```

## 二、字段定义（新模型）

### 2.1 交易记录 TradeRecord（复活死代码 → 逐笔流水）

> 当前 TradeRecord 是死代码，本 RFC 使其成为**逐笔交易真相源**（落盘 `data/{userId}/trading/trades/{yyyy-MM}.json`）。

| 字段 | 类型 | 分层 | 必填 | 说明 |
|:-----|:-----|:----:|:----:|:-----|
| id | String | 系统 | ✅ | `trade_{ts}` |
| symbol | String | 提供 | ✅ | 6 位代码 |
| name | String | 查 | 否 | 行情补全，缺省 symbol |
| direction | BUY/SELL | 提供 | ✅ | |
| price | BigDecimal | 提供 | ✅ | 成交价 |
| volume | int | 提供 | ✅ | |
| amount | BigDecimal | 派生 | — | price×volume |
| **entryDate** | LocalDate | 系统 | ✅ | 交易日期（用户可改/可补录）|
| **stopLossPrice** | BigDecimal | 提供 | BUY 必填 | 止损位（SELL 可空）|
| **buyPoint** | String | 提供 | BUY 必填 | B1/B2/B3/SB1/暴力特噗/深水炸弹/单针/其他 |
| **targetPrice** | BigDecimal | 提供 | 否 | 目标价（盈亏比 R38）|
| **reason** | String | 提供 | 否 | 交易原因/预期（复盘锚点）|
| fee | BigDecimal | 提供 | 否 | 手续费（P2，可全局费率）|
| sourceRecordId | String | 系统 | 否 | 关联记录 |

### 2.2 持仓 Position（加字段）

| 字段 | 分层 | 说明 |
|:-----|:----:|:-----|
| 现有 6 字段 | — | symbol/name/qty/avgCost/currentPrice/lastUpdated 保留 |
| **entryDate** | 系统 | **首买日**（首次 BUY 落盘，加仓不覆盖）|
| **stopLossPrice** | 提供 | 最近一次 BUY 的止损位（可 web 改）|
| **buyPoint** | 提供 | 最近一次 BUY 的买点 |
| **role** | 提供 | 防守/前锋/中场/机动 + 主仓/副仓（web 编辑，P1）|
| positionPercent | 派生 | 已有（市值/总市值）|

### 2.3 可查询字段（不落盘为用户输入）

现价/昨收/今开/最高/最低/涨跌幅/成交量/名称 → 全部查询时注入（TencentMarketDataSource），建议引擎/复盘/展示/预警统一用查询值。

## 三、消费方改造（让规则可执行）

### 3.1 建议引擎 TradingAdviceAppService（P0）

```
现在：注入 持仓占比/成本/现价 + R66-R95 文本 → LLM 猜 clear
改造：注入 每票止损位 stopLossPrice + 入场日期 + 现价
     → 硬判定可用：
       - 现价 < stopLossPrice → "已跌破止损位（R66），建议 clear"
       - 入场后 N 天未涨 → R53 候选
       - buyPoint 关联应对规则（B1→持股/白线，B2/B3→S1 就走，R120）
```

### 3.2 复盘 TradingReviewAppService（P1）

```
现在：当日记录文本（「买入 X N股@价」）+ 持仓变化 → 自由文本
改造：注入 TradeRecord（含 reason/stopLoss/buyPoint）
     → R106 对错判定：计划（reason/止损/买点）vs 实际（现价/是否触发止损）
```

### 3.3 行情预警 MarketAlertService（P1）

```
现在：单日跌幅≥3% 代理"止损"
改造：真止损检查——现价 < stopLossPrice → 推送"已跌破止损位（R66）"
     保留单日大跌预警（补 3.1 之外）
```

## 四、入口改造（极简：app 只加 P0，详细归 web）

### 4.1 app 交易页（P0 必加 3 字段）

```
精确表单：标的 | 价格 | 数量 + [买入][卖出]  +  止损位  +  买点类型（下拉默认B1）
确认卡：数量/价格/方向 可改 + 止损位/买点 回显可改
NL parse 扩展：「买了 X 股 Y @价，止损 Z，B1」→ 结构化含 stopLoss/buyPoint
入场日期：后端自动（无需用户填）
```

> **app 保持简单**：只加止损位+买点 2 个输入（入场日期自动）；原因/角色/手续费 归 web。

### 4.2 web 交易页（P1 详细管理）

```
持仓编辑：role（主仓/副仓/防守/前锋）、止损位修改、targetPrice
交易历史：逐笔流水列表（TradeRecord 落盘后）
批量导入：CSV/粘贴（含 stopLoss/buyPoint/reason）
```

### 4.3 NL parse 扩展（P0）

```
输入：「买了 1000 股京东方 @5.2，止损 4.9，B1」
→ {symbol, name, direction, price, volume, stopLossPrice: 4.9, buyPoint: "B1"}
正则兜底：@价 后跟「止损 X」捕获 stopLoss；「，B1/B2/...」捕获 buyPoint
```

## 五、数据格式变更（freeze 规则）

| 变更 | 规则 | 说明 |
|:-----|:-----|:-----|
| positions.md 加列（entryDate/stopLoss/buyPoint/role）| **MINOR** | 新增可选列，旧文件解析兜底（缺列 → null）|
| 新增 data/trading/trades/{yyyy-MM}.json | **MINOR** | 新目录，无旧文件兼容问题 |
| rec_*.md 文本保留 | 不变 | 记录流照旧，TradeRecord 是结构化补充 |

## 六、改动点清单

### 后端 services/adai-core

| 优先级 | 改动 | 文件 |
|:------:|:-----|:-----|
| P0 | TradeRecord 复活：加 stopLoss/buyPoint/entryDate/targetPrice/reason + 落盘 JSON | domain/trading/TradeRecord + 新 TradingHistoryFileRepository |
| P0 | recordTrade 写 TradeRecord（逐笔流水）+ 写 positions.md 新列 | application/TradingAppService + PositionFileRepository |
| P0 | TradeRequest 加 stopLossPrice/buyPoint/entryDate（BUY 必填止损）| interfaces/TradingController |
| P0 | parse 扩展支持止损/买点 | application/TradingParseAppService |
| P0 | 建议引擎注入止损/入场日期 → clear 判定可执行 | application/TradingAdviceAppService |
| P1 | 复盘注入 TradeRecord + reason | application/TradingReviewAppService |
| P1 | 预警真止损检查 | infrastructure/market/MarketAlertService |
| P1 | positions PUT 加 role/stopLoss 编辑 | interfaces/TradingController |

### 前端

| 优先级 | 改动 | 文件 |
|:------:|:-----|:-----|
| P0 | app 表单+确认卡+NL 加止损/买点 | apps/adai-app/lib/pages/trading_page.dart |
| P0 | ApiService 加字段 | apps/adai-app/lib/services/api_service.dart |
| P1 | web 持仓编辑（role/止损）+ 交易历史 | apps/adai-web/lib/pages/trading_page.dart |
| P1 | web 批量导入 | apps/adai-web |

### 文档

| 变更 | 文件 |
|:-----|:-----|
| freeze §2.6 加列 + §2.13 新增 trades 流水 | docs/architecture/data-format-freeze.md |
| api-spec POST /trades + parse 契约 | docs/architecture/api-spec.md |
| 项目资产卡 adai-core/adai-app/adai-web 更新 | ai-engineering/assets/projects/ |

## 七、验收标准

1. `POST /trading/trades` BUY 必填 stopLossPrice/buyPoint；缺失 → 400
2. 每笔交易落盘 `trades/{yyyy-MM}.json`（逐笔流水可查）
3. Position 持久化 entryDate（首买日）+ stopLoss/buyPoint；加仓不覆盖首买日
4. 建议引擎输出 clear 时：`现价 < stopLossPrice` 有数据可判（注入止损位）
5. 复盘含"计划 vs 实际"对错判定（reason/止损 锚点）
6. NL「买了 X 股 Y @价，止损 Z，B1」正确结构化
7. 旧 positions.md 无新列 → 解析兜底不报错
8. guard-meta/align PASS + 全部测试通过

## 八、不做（本版范围外）

- 历史 K 线数据源（入场K线低点查不到 → 用用户填的止损位替代，不建 K 线基建）
- 手续费自动计算（P2 全局费率）
- 净值自动记账（P2，cashBalance 待 v1.0.1）
- 择时状态结构化（P2，OAMV 区间）
- 多市场/币种（P2）
