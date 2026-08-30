---
title: 行情数据前复权设计——TDX 本地数据正确性（除权除息换算）
description: 通达信 .day 不复权原始价在除权日跳空导致回撤/特征/相似度失真——用东财除权因子表 + 本地换算实现前复权口径，与腾讯 qfq 一致；数据正确性是案例库判定可信的前提
version: 1
created: 2026-08-30
status: draft
depends-on:
  - trading-case-library-design.md
related:
  - api-spec.md
  - ../reference/trading-features.md
tags: [trading, 数据正确性, 复权, 除权, TDX]
---

# 行情数据前复权设计——TDX 本地数据正确性

> **定位**：完美买点案例库的**数据底座修复**。验收中确认 TDX 本地数据（.day）为**不复权原始价**——除权股在除权日价格跳空，导致：①K 线画面与通达信（前复权）不一致 ②回撤/量比等特征失真（把除权跳空误当「大跌买点」）③案例特征与 match 特征不可比 ④腾讯兜底（qfq 前复权）与 TDX 口径混用。**数据不对 → 特征错 → 判定错**，复权是案例库可信的前提。
>
> **状态**：**已实施（2026-08-30 全部完成）**——AdjustmentCalculator（算法+8 单测）+ AdjFactorRepository（东财拉取+文本解析+本地缓存）+ TdxFileKlineSource 集成；茅台 34 根 K 线 vs 腾讯 qfq 校验全部 ≤0.5% 偏差（966 全绿）。全 A 因子预热脚本后置

---

## 一、背景与问题（实测证据）

| # | 问题 | 证据/影响 |
|:-:|:-----|:-----|
| A | TDX `.day` 是不复权原始价 | 除权日（送转/分红）价格跳空——如 10 送 10：10 元 → 5 元「假大跌」|
| B | 特征失真 | 回撤 % 把除权跳空当「大跌 50%」→ 假买点信号；量比/黄白线态同理 |
| C | 画面不一致 | 用户通达信看前复权（连续），我们图不复权（跳空）→ 信任受损 |
| D | 口径混用 | 腾讯兜底返回 qfqday（前复权），TDX 不复权——同一案例两源数据不可比 |
| E | 前后端一致 | 前端重算指标 vs 后端特征：EMA 初始值等细节必须同口径（关联项）|

> **用户原话（2026-08-30）**：「首先要保证数据的正确性」——复权是数据正确性的核心。

## 二、目标

1. **全链路前复权口径统一**：TDX 读取时换算前复权，与腾讯 qfq、通达信画面一致
2. **因子表本地化**：除权因子拉一次存本地（`data/market/adj/`），运行时离线可用
3. **可校验**：抽查除权股，换算结果 vs 腾讯 qfq 对比（允许微小舍入差）
4. **不破坏现有**：TDX 原始 .day 不动（换算在读取层做，可回退）

## 三、方案总览

```
用户同步（照旧）：通达信盘后数据 → TDX .day（不复权原始价）
        │
        ▼
AdjFactorRepository（懒加载，按需拉取）
  东财 RPT_SHAREBONUS_DET（全 A 分红送转：除权日 + 每10股送/转/派）
  → 本地缓存 data/market/adj/factors/{symbol}.json
        │
        ▼
AdjustmentCalculator（前复权换算，纯算法）
  输入：TDX 不复权 candles + 该票因子表
  输出：前复权 candles（最新价不变，历史价按除权事件逐级下调）
        │
        ▼
TdxFileKlineSource.kline/klineRange → 前复权结果（与腾讯 qfq 同口径）
        │
        ▼
校验（测试 + 抽查）：换算结果 vs 腾讯 fqkline qfqday（带 UA 可用）
```

## 四、因子表设计

### 4.1 数据源（东财）

- 接口：`GET https://datacenter-web.eastmoney.com/api/data/v1/get`
- `reportName=RPT_SHAREBONUS_DET`（分红送转明细），`filter=(SECURITY_CODE="600519")`，分页（pageSize 500）
- 关键字段（实测茅台返回）：
  - `EX_DIVIDEND_DATE`：除权除息日
  - `IMPL_PLAN_PROFILE`：方案文本（如「10派280.2423元(含税)」）
  - `SEND_RATIO` / `MODIFY_RATIO`：送股/转增比例（每股，**实测茅台派息为 null**——方案文本在 IMPL_PLAN_PROFILE）
  - `CASH_RATIO`：派息（每股）
- ⚠️ **字段不全问题**（实测）：`SEND_RATIO/CASH_RATIO` 对纯派息股返回 null——需**解析方案文本**（`10派X元` / `10送Y股转Z股派W元`）或换字段。备选：接口返回的 `IMPL_PLAN_PROFILE` 文本正则解析（`10派280.2423` → 每股派 28.02；`10送4转6` → 送 0.4 + 转 0.6）。

### 4.2 本地格式（File First，B2）

```json
# data/market/adj/factors/600519.json
{
  "symbol": "600519",
  "updatedAt": "2026-08-30",
  "events": [
    { "exDate": "2026-06-26", "cashPerShare": 28.02423, "sendPerShare": 0.0, "transferPerShare": 0.0, "profile": "10派280.2423元(含税)" },
    { "exDate": "2025-12-19", "cashPerShare": 23.957, "sendPerShare": 0.0, "transferPerShare": 0.0, "profile": "10派239.57元" }
  ]
}
```

- 送转比例（每股）：`sendPerShare` = 送股比例，`transferPerShare` = 转增比例
- 派息（每股）：`cashPerShare`
- 私有数据? 否——行情/分红是公开数据，`data/market/` 已 gitignore（2026-08-30 TDX 批次）

### 4.3 拉取与更新

- **懒加载**：标注/匹配/详情用到某票时，本地无因子 → 拉一次 → 缓存；有 → 用缓存（TTL 或按日校验）
- **全 A 预热**：后置（可选脚本 `09-scripts/sync-adj-factors.sh` 或后端工具类全量拉取）
- **更新**：因子表随除权事件变化（分红季），本地缓存按日 TTL 失效重拉

## 五、前复权换算算法（标准）

```
输入：candles（旧→新，不复权）+ 因子事件列表（按 exDate）
输出：前复权 candles

1. 对每个除权事件 e，计算「除权参考价」：
   refPrice = (除权前收盘 × 10 − cashPerShare × 10) / (10 + (sendPerShare + transferPerShare) × 10)
   其中「除权前收盘」= 该事件 exDate 的前一交易日收盘（从 candles 取；无 → 跳过事件）
2. 按 exDate 从近到远处理：
   cumulative = 1.0
   对最近的除权事件 e（exDate 最近）：
     e.factor = refPrice / 除权前收盘
     对 e.exDate 之前的所有 K 线：close/open/high/low ×= cumulative
     cumulative ×= e.factor
   （除权日及之后的 K 线因子 = 1；最新价不变）
3. volume/amount 不变（股数送转不影响成交量手数换算，成交额同）
```

> **口径**：与腾讯 qfq、通达信前复权一致（最新价基准）；送转股影响价格比例，派息按含税参考价近似（与券商/通达信含税口径对齐，允许 ±0.1% 舍入差，校验容差 0.5%）。

### 5.1 边界

- 无因子事件的票（次新/多年无分红送转）→ 原样返回（前复权 = 不复权）
- 因子事件早于数据起点 → 忽略（数据窗口内无影响）
- 除权日前收盘缺失（停牌）→ 跳过该事件（无法精确换算，记录日志）
- 除权日当天及之后的 K 线不调整（前复权基准 = 最新）

## 六、集成点

| 位置 | 改动 |
|:-----|:-----|
| `domain/trading/market/` 新增 `AdjustmentFactor`（record）| 因子事件模型 |
| 新增 `AdjustmentCalculator`（纯算法，无 IO）| 前复权换算（单元测试核心）|
| 新增 `AdjFactorRepository`（infrastructure）| 本地因子缓存读写 + 东财拉取（懒加载）|
| `TdxFileKlineSource` | 读取后调 `AdjustmentCalculator` 换算前复权再返回（TDX 无因子表 → 原样）|
| `KlineService` | 优先级不变：TDX（前复权）→ 腾讯（qfq 前复权）→ 东财——**两源口径统一后可直接混用** |

> **口径一致性（关联 E）**：TDX 前复权后与腾讯 qfq 同源同口径——腾讯兜底不再引入不一致数据。前端重算指标 vs 后端特征的一致性测试（同一组数据前后端各算对比）作为本批关联项记录。

## 七、校验与测试

| 项 | 做法 |
|:---|:-----|
| 算法单测 | 构造已知除权场景（10送10 / 10派10 / 送转+派息混合）→ 断言换算后价格 |
| 边界单测 | 无因子/事件早于数据起点/除权日前停牌 |
| 真实数据校验 | 抽查除权股（600519 茅台 2025-12-19 派息、600276 恒瑞等）→ 本地换算 vs 腾讯 qfq 对比（容差 0.5%）|
| 因子表解析单测 | 方案文本正则（`10派X` / `10送Y转Z派W`）解析 |
| 回归 | TDX 无因子票原样返回（行为不变）|

## 八、实施步骤

| # | 任务 | 涉及 |
|:-:|:-----|:-----|
| 1 | 文档定案（本文）| ✅ |
| 2 | ✅ `AdjustmentCalculator`（算法 + 8 单测）| domain/trading/market |
| 3 | ✅ `AdjFactorRepository`（东财拉取 + 方案文本正则 + 本地缓存 + 按日 TTL）| infrastructure/market |
| 4 | ✅ `TdxFileKlineSource` 集成换算（读取即前复权）| infrastructure/market |
| 5 | ✅ 茅台 34 根 vs 腾讯 qfq 全部 ≤0.5% 偏差 | 本地实测 |
| 6 | 文档同步（api-spec 无新端点 / status / change-log / trading-features §三 数据源行）| docs |
| 7 | guard-meta/align + 提交 | — |

## 九、风险与已知限制

- **派息含税口径**：东财 `IMPL_PLAN_PROFILE` 含税文本解析；除权参考价按含税计算（与通达信默认一致）；若个别票除权口径特殊（如 B 股/特别处理）→ 校验发现后白名单处理
- **因子表覆盖**：东财数据源覆盖沪深全 A（实测茅台完整）；新上市/未分红票无事件（原样返回，正确）
- **腾讯校验依赖网络**：校验为测试/抽查用途，运行时主链路不依赖腾讯
- **更新时机**：分红季（5-8 月）除权密集，本地因子 TTL 按日失效重拉；用户盘后同步数据时一并触发

---

## 附：与现有文档的关系

- `trading-case-library-design.md`（案例库设计）——本文是其数据底座修复（§三 数据源盘点：TDX 已接入，本文补复权口径）
- `docs/reference/trading-features.md` §三——K 线数据源行将标注「TDX 前复权口径」
- `data-format-freeze.md`——`data/market/` 契约（TDX .day + adj 因子表）
