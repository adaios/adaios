---
title: 成交时间点采集 + 当日交易复盘（纯客观数据）
date: 2026-08-22
status: approved
decided-by: adai（2026-08-22 会话：确认方向——只做客观数据，去掉理由/情绪；先写文档后写代码）
implementation: 2026-08-22 已实施——TradeRecord.tradeTime + 历史成交时间列解析 + GET /trades?date= daily 聚合 + App/Web 展示，后端 685 / app 120 / web 103 测试全绿
---

# 成交时间点采集 + 当日交易复盘

> **方向（用户 2026-08-22 确认）**：交易复盘先做**纯客观数据**——买卖时间点是核心；**不做理由/情绪**（事后合理化高发区，数据质量差，之后再说）。
> **流程**：先写本文档（draft），确认后开工（代码 + 数据格式 MINOR 变更）。

---

## 一、问题与现状（实测证据）

### P1 买卖时间点被丢弃（客观数据缺口）

- 通达信「历史成交查询」导出**含有成交时间列**（如 `20260803 14:52:56 600206 有研新材 卖出 -200.00 ...`）
- 但 `TradingImportParser.parseHistoricalTrades` 只解析「成交日期」，**未解析「成交时间」**（`locate` 列里没有该列）
- `TradeRecord` 模型只有 `entryDate`（日期）+ `timestamp`（落盘时刻）——**没有成交时分**
- 实测 `data/adai/trading/trades/2026-08.json` 48 笔：`timestamp` 全部为 `2026-08-18T21:31`（导入当晚），**成交时刻全部丢失**

### P2 当日交易复盘无结构化输出

- `GET /trading/trades` 只返回流水明细，无当日聚合（时段分布/买卖笔数/节奏）
- App/Web 交易页无「今日操作」概览（有几笔、几点操作的、买卖分布）
- 复盘横幅（`POST /review`）是 AI 基于记录文本生成，无客观成交序列输入

### P3 一年多历史成交时间点也无法回溯

- 用户有 2025-04 至今的完整历史成交（清仓股 162 笔 + 逐笔流水），但当前导入丢时间 → **历史回放（某天几点买了什么）做不到**

---

## 二、目标

1. **采集**：逐笔流水中保存**成交时间点**（时:分:秒，客观、不可伪造）
2. **复盘**：当日成交聚合为结构化客观数据（时段分桶/买卖分布/笔数金额）
3. **历史**：全量历史成交导入后时间点可全量回溯（不只今日）
4. **边界**：**不做理由/情绪**（用户决策：去掉，之后再说）

---

## 三、方案

### 3.1 数据模型：`TradeRecord` 加 `tradeTime`（MINOR，向后兼容）

| 项 | 值 |
|:--|:--|
| 字段 | `tradeTime`（`LocalTime` 可空）|
| 语义 | 成交时刻（时:分:秒）——通达信成交时间列 / 当日记录默认落盘时刻时分 |
| 兼容 | 旧文件无此字段 → 解析 null（旧数据 `tradeTime=null`，不报错）|
| 变更等级 | **MINOR**（新增可选字段，符合 data-format-freeze §三）|

- 日常 `POST /trades` 记录：`tradeTime` 缺省 = 请求时刻的时分秒（客观真实）
- 历史导入 `POST /trades/import`：解析通达信「成交时间」列 → `tradeTime`
- `data-format-freeze.md` §2.13 登记 MINOR 变更

### 3.2 导入解析：`parseHistoricalTrades` 加「成交时间」列

- `locate(cells, ..., "成交时间", ...)` 增加该列索引
- 解析 `HH:mm:ss` → `LocalTime`（格式不匹配 → null，不阻塞整行）
- `HistoricalTradeRow` 加 `tradeTime` 字段透传

### 3.3 当日复盘聚合：扩展 `GET /trading/trades`

`GET /trading/trades?date=yyyy-MM-dd` 返回新增聚合块（或独立 `GET /trading/trades/daily?date=`）：

```json
{
  "trades": [...],           // 原有流水明细（含 tradeTime）
  "daily": {
    "date": "2026-08-22",
    "count": 6, "buyCount": 4, "sellCount": 2,
    "buyAmount": 12345.6, "sellAmount": 8901.2,
    "sessions": [
      {"name": "早盘", "range": "09:30-11:30", "count": 2},
      {"name": "午盘", "range": "13:00-14:30", "count": 1},
      {"name": "尾盘", "range": "14:30-15:00", "count": 3}
    ],
    "firstTradeTime": "09:41", "lastTradeTime": "14:52"
  }
}
```

- 时段分桶口径（2026-08-22 用户确认）：早盘 09:30-11:30 / 午盘 13:00-14:30 / 尾盘 14:30-15:00
- `tradeTime=null` 的历史流水：不计入 sessions（只进 count），不误判时段
- 聚合是纯计算（服务端），无 AI

### 3.4 前端展示（纯数字，无主观）

| 端 | 展示 |
|:--|:--|
| App | 交易页账户卡下方加「今日 N 笔 · 早盘 X / 午盘 Y / 尾盘 Z」（有今日成交才显示；静默失败不影响页面）|
| Web | 交易历史 Dialog 行内显示成交时间（HH:mm）；页头或交易历史顶部加「今日操作节奏」一行 |

---

## 四、分析价值（客观归因，攒数据后）

| 维度 | 数据 | 能回答 |
|:--|:--|:--|
| 时段胜率 | tradeTime × 结果（清仓 pnl）| 早盘买的票 vs 尾盘买的票，哪批胜率高（冲动 vs 冷静）|
| 操作节奏 | 每日笔数 × 持仓天数 | 高频日内操作与 21% 短线胜率的相关性 |
| 历史回放 | 全量流水含时间 | 任意一天完整操作序列（几点买/几点卖/间隔）|
| 规则对照 | 时段 × R1-R120 | 开盘冲动追高（R33-R50 买入纪律）可量化 |

> 以上是**后续**分析，不在本批实现范围；本批只做数据采集 + 当日聚合展示。

---

## 五、改动清单（确认后开工）

| # | 层 | 改动 |
|:--|:--|:--|
| 1 | 后端 domain | `TradeRecord` 加 `tradeTime`（可空 LocalTime）+ 序列化 |
| 2 | 后端 application | `parseHistoricalTrades` 加「成交时间」列解析；`recordTrade` 接收可选 tradeTime（缺省 = 此刻时分秒）|
| 3 | 后端 interfaces | `GET /trading/trades` 扩展 `daily` 聚合块（date 参数）|
| 4 | 后端 test | TradeRecord 序列化回归 + 导入解析含时间列 + daily 聚合单元测试 |
| 5 | 前端 app | 交易页「今日 N 笔 · 时段分布」行（静默失败）|
| 6 | 前端 web | 交易历史行显示成交时间 + 今日节奏一行 |
| 7 | 文档 | data-format-freeze §2.13 登记 MINOR；api-spec §5 trades 端点补 tradeTime/daily；trading-features.md 同步 |

**不做**（明确排除）：理由/情绪字段、AI 归因分析、时段胜率统计 UI（后续再说）。

---

## 六、数据格式变更登记（MINOR，v1.0.0 冻结期合规）

- `trading/trades/{yyyy-MM}.json`：加 `tradeTime`（可空 `LocalTime`，ISO `HH:mm:ss`）
- 旧文件无该字段 → 解析 null，向后兼容，无需迁移脚本
- 依据：data-format-freeze §三 MINOR = 新增可选字段直接改
