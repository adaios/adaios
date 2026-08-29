---
title: 交易插件架构设计（第三阶段：通用化 + 规则可自定义）
description: 交易插件第三阶段蓝图——通用能力层与个性化规则层分离，规则按用户隔离、可自定义/可导入/可导出，为多用户 + 插件可插拔铺路；adai 规则体系作为首个规则包接入
version: 1
created: 2026-08-29
updated: 2026-08-30
status: draft
lines:      647
depends-on:
  - framework-plus-plugin-model.md
  - ../reference/framework-plugin-gap.md
related:
  - ../reference/trading-features.md
  - ../reference/task-plugin-model.md
  - ../rfc/20260814-domain-plugin-model.md
  - ../rfc/20260816-trading-agent-plugin-model.md
tags: [architecture, trading, plugin, rules, multi-user]
---

# 交易插件架构设计（第三阶段）

> **定位**：AdaiOS 交易插件从「adai 专属交易系统」演进为「通用交易插件 + 个性化规则层」的架构蓝图。第一阶段（人+LLM 框架）与第二阶段（交易插件）已实现；本文定义第三阶段——**交易插件通用化与规则可自定义**，为多用户开放铺路。
>
> **核心命题**：每个人应该有自己的交易系统。交易插件的通用能力（账户/持仓/清仓/历史成交/行情/批次分析）对所有人一致；**规则层（止损幅度/仓位上限/买点信号/行为标注）是每个人的私有内容**，不再把 adai 的课程规则（B1/B2、R66/R81 等）焊死在插件里。
>
> **状态**：draft（2026-08-29 用户拍板方向后实施）

---

## 目录

- [一、背景与目标](#一背景与目标)
- [二、现状盘点（已实现能力）](#二现状盘点已实现能力)
- [三、规则硬编码点清单（要拆的）](#三规则硬编码点清单要拆的)
- [四、对标调研（通用能力清单）](#四对标调研通用能力清单)
- [五、目标架构：能力层 vs 规则层](#五目标架构能力层-vs-规则层)
- [六、规则文件格式设计](#六规则文件格式设计)
- [七、嵌入系统方式（插件模型 + 数据隔离）](#七嵌入系统方式插件模型--数据隔离)
- [八、自定义机制（导入/导出/降级）](#八自定义机制导入导出降级)
- [九、adai 规则体系接入（第三阶段）](#九adai-规则体系接入第三阶段)
- [十、实施路径与测试](#十实施路径与测试)
- [十一、待用户拍板的决策点](#十一待用户拍板的决策点)

---

## 一、背景与目标

### 1.1 为什么做第三阶段

- 交易插件现在 = **adai 的交易系统**：R1-R120 规则、B1/B2 买点、-7% 止损、行为标注六类全部硬编码或绑定 `os/trading-engine/` 知识。
- 多用户方向下这不可行：别人不是 adai，不该用 adai 的规则；**每个人应该有自己的交易系统**。
- 用户明确原则：**「规则配置化 ≠ 把 B1/B2 变成可配置参数——配置化后就不该出现 B1/B2 这些课程概念」**。B1/B2 是 adai 的规则包内容，不是插件内建概念。

### 1.2 三阶段演进

| 阶段 | 内容 | 状态 |
|:-----|:-----|:-----|
| 第一阶段 | 人与 LLM（框架：记录/问答/记忆/Context）| ✅ 已实现 |
| 第二阶段 | 加入交易（交易插件：账户/持仓/清仓/历史成交/行情/批次）| ✅ 已实现（40 端点 + 双端，第三阶段后 42）|
| **第三阶段** | **交易插件通用化 + 规则可自定义/可导入** | ⬜ **本文定义** |

### 1.3 目标

1. **通用能力层**：账户、持仓、清仓、历史成交、行情、批次等**客观分析**——对所有人一致，插件自带。
2. **规则层**：止损幅度、仓位上限、买点信号、行为标注等**判断体系**——按用户隔离，存用户自己的规则文件，引擎只认用户规则。
3. **留口子**：规则格式标准化（可导入导出）、引擎可插拔、无规则时降级（只给客观数据，不给判断）。
4. **测试通过后**：把 adai 规则体系作为**首个高质量规则包**接入（导出为规则文件，可被其他用户导入）。

---

## 二、现状盘点（已实现能力）

### 2.1 端点总览（TradingController 40，第三阶段后 42 + admin）

| 模块 | 端点 | 性质 |
|:-----|:-----|:-----|
| 交易记录 | `POST /trades`、`GET /trades`、`POST /trades/batch`、`POST /trades/import`、`POST /trades/parse`、`POST /sync` | 通用（客观）|
| 持仓 | `GET /positions`、`PUT /positions/{symbol}`、`POST /positions/import`、`GET /lots` | 通用（客观）|
| 账户资金 | `GET /account`、`GET /portfolio`、`POST /transfer`、`GET /transfers`、`PUT /principal`、`POST /imports/cash`、`POST /imports/save` | 通用（客观）|
| 自选/买点 | `GET /watchlist`、`POST /watchlist/import`、`DELETE /watchlist/{symbol}`、`GET /buy-points`、`GET /lookup` | **买点判定含规则**（B1/B2 硬编码）|
| 清仓复盘 | `GET /sold`、`POST /sold/import`、`PUT /sold/{symbol}/psychology`、`GET /sold/score` | **打分含规则**（三维实为二维）|
| 交易建议 | `POST /advice` | **含规则**（R66-R95 硬约束注入 prompt）|
| 推送 | `GET /push-settings`、`PUT /push-settings/{type}`、`DELETE /pushes/{id}`、`GET /trade-log`、`POST /trade-log/confirm`、`PUT /trade-log/date`、`DELETE /trade-log`、`POST /screenshots` | 通用（客观）+ **行为标注含规则** |
| 复盘 | `POST /review`、`GET /review`、`GET /reviews`、`GET /has-activity`、`POST /reviews/{date}/promote` | 通用（客观）+ 反哺 |

### 2.2 客观分析能力（通用，无需规则）

- 账户快照（总资产/可用/可取/市值/当日盈亏/总盈亏/本金）
- 持仓列表（成本/现价/市值/盈亏/盈亏%）
- 逐笔流水 + 历史成交导入（幂等/对账/回填）
- 批次推导（按日合并/LIFO/回合/初始批次）
- 手续费模型（佣金/印花税/过户费）
- 行情接入（腾讯主源/东财兜底/K线/KDJ 指标）
- 资金管理（转账/本金/现金单一真源）
- 交易日志归集（截图 VLM/文字解析 → 候选 → 确认落库）
- 每日操作总结（买卖聚合 + 批次 diff）
- 推送链路（8 开关 + Bark + 定时消失）
- 复盘生成/历史/反哺

### 2.3 前端能力

- **adai-web**：交易管理端（持仓编辑/批量导入/复盘历史/自选/清仓/资金/历史成交/推送设置）
- **adai-app**：日常记录（一句话解析/精确表单）+ 阿呆建议弹层 + 截图入账 + 复盘横幅 + 批次简版

### 2.4 前端硬编码规则术语（也要拆）

| 位置 | 硬编码 |
|:-----|:-----|
| `apps/adai-web/lib/utils/trade_import_parser.dart:62` | 买点类型枚举 `B1/B2/B3/SB1/暴力特噗/深水炸弹/单针/其他`（CSV 批量导入白名单）|
| `apps/adai-web/lib/pages/trading_page.dart:1500` | 记录交易买点下拉默认 `B1` |
| `apps/adai-web/lib/pages/trading_page.dart:942` | 自选买点信号列「命中 B1/B2 红色徽标」|
| `apps/adai-app/lib/pages/trading_page.dart` | 精确表单买点下拉（同 web 枚举）|

> **第三阶段影响**：买点类型枚举是 adai 课程专属概念，应随规则包走——通用插件提供「信号原语」（回调/缩量/KDJ/放量/突破），B1/B2 命名由用户规则包定义；前端下拉改为读用户规则包的信号列表（`GET /trading/rules` 或配置下发）。

---

## 三、规则硬编码点清单（要拆的）

> 这些是「adai 的交易观」焊死在代码/知识里的位置。第三阶段把它们从插件中拆出，变成用户规则文件驱动。

| # | 位置 | 硬编码内容 | 类型 |
|:-:|:-----|:-----|:-----|
| R1 | `DefaultTradingRuleEngine` | R66 止损判定（现价<止损位→BREACHED）、R68 无止损不判、R81 仓位上限 **25%** | 参数化规则 |
| R2 | `BuyPointDetector` | B1（回调≥50% + 缩量<0.7 + KDJ.J<13）、B2（放量>1.5x + 破前高）、B1? 候选、score 映射 | 条件规则 + 参数 |
| R3 | `SoldTradeVerdict` | R53（短持仓亏损≤5天）、R66（亏损>5%→扛单）、末分支 R53 延展 | 条件规则 |
| R4 | `TradingLotService` | 行为标注六类：亏损加仓/追高/短线新开/破止损未走/浮盈回吐（峰值≥20% 回吐≥50%）/短线超期（>5 交易日）；默认止损 **−7%** | 条件规则 + 参数 |
| R5 | `SoldScoreService` | 三维打分实为二维（买点×0.5 + 执行×0.5），B2→85 分/B1→70 分映射 | 参数化规则 |
| R6 | `TradingAdviceAppService` | R66-R95 硬约束区间注入 prompt（rules 数组必须引用规则号）| 知识注入 |
| R7 | `TradingKnowledgeSource` | 读 `os/trading-engine/knowledge/context/` 五文件（identity/strategy/rules/mistakes/current）——**adai 的知识**，全局一份 | 知识注入 |
| R8 | `TradingSessionPushService` / `WatchlistBuyPointService` | 15:10 买点扫描、收盘 15:05 更新、时段推送模板 | 调度 + 规则引用 |

**判断**：R1-R5 是**确定性规则**（参数化/条件式，可文件化）；R6-R7 是**知识注入**（LLM 决策参考，走用户知识文件）；R8 是**调度壳**（通用，但内容引用用户规则）。

---

## 四、对标调研（通用能力清单）

> **方法**：2026-08-29 派 2 个调研子代理并行完成——① 对标 7 个开源/成熟交易系统（invest-record-pro / Ghostfolio / trade-journal / logvestor / Tradervue / Edgewonk / Portfolio Performance），提炼通用能力清单；② 调研规则引擎架构（json-rules-engine / riskguard / freqtrade / PLUTUS / TradeLyser），结论已并入 §六。

### 4.1 对标系统一览（2026-08-29 调研）

| 系统 | 一句话定位 | 与 AdaiOS 的相似度 |
|:-----|:-----|:-----|
| [invest-record-pro](https://github.com/brycegao/invest-record-pro) | 本地隐私优先的投资决策记录 + 本地 AI 复盘 + 交易纪律执行 | **极高**（同款定位：隐私 + AI 复盘 + 纪律）|
| [Ghostfolio](https://github.com/ghostfolio/ghostfolio) | 开源财富管理/组合跟踪（事件溯源式账本）| 高（组合/绩效）|
| [Dx007i/trade-journal](https://github.com/Dx007i/trade-journal) | 本地轻量交易日志 | 中（复盘日记）|
| [logvestor](https://github.com/Kurei999999/logvestor) | 隐私优先桌面交易日志（markdown + CSV + AI）| 高（File First 同构）|
| Tradervue | 成熟交易复盘平台 | 高（复盘/绩效）|
| Edgewonk | 交易心理/纪律复盘 | 中（心理标注）|
| Portfolio Performance | 开源组合绩效分析（德国）| 中（绩效统计深度）|

### 4.2 通用能力清单（合并去重，7 系统频率标注）

> 频率：**几乎所有** = 6-7/7 系统都有（P0 必备）；**多数** = 4-5/7（差异化）；**少数** = 1-3/7（高价值）。

| 模块 | 能力 | 频率 | AdaiOS 现状 |
|:-----|:-----|:-----|:-----|
| **A 标的账户** | 标的库（代码/名称映射）| 几乎所有 | ✅ lookup |
| | 账户/资金（现金/资产）| 几乎所有 | ✅ account |
| **B 交易记录** | 交易/活动流水（逐笔）| 几乎所有 | ✅ trades |
| | 手动录入 + 批量/CSV 导入 | 几乎所有 | ✅ |
| | 一句话/自然语言录入 | 少数 | ✅（parse）|
| **C 持仓资金** | 持仓视图（成本/现价/盈亏）| 几乎所有 | ✅ |
| | 分红/费用/税费归集 | 多数 | ⚠️ 部分（手续费模型有，分红股息展示缺失，P2-批次6）|
| **D 绩效统计** | 基础盈亏统计（总/当日/浮动）| 几乎所有 | ✅ |
| | 胜率/盈亏比/平均盈亏 | 多数 | ⚠️ 部分（web 有纪律遵守率/胜率口径）|
| | 权益曲线（净值走势）| 多数 | ❌ 无 |
| | 回撤分析（最大回撤/持续期）| 多数 | ❌ 无 |
| | MFE/MAE（浮盈最大/浮亏最大）| 少数 | ❌ 无（高价值）|
| | 归因分析（按策略/板块/时间）| 少数 | ❌ 无 |
| **E 图表** | 权益曲线图/资产走势图 | 多数 | ❌ 无 |
| | K线图（个股）| 少数 | ⚠️ 无前端图（后端有 K 线数据）|
| **F 复盘报告** | 逐笔复盘（计划 vs 实际）| 几乎所有 | ✅ review |
| | 复盘历史/导出 | 多数 | ✅ |
| | 心理/情绪标注 | 多数 | ✅ psychology |
| | 纪律量化（违规计数/遵守率）| 少数 | ✅（R66/R53 计数）|
| **G 风险纪律** | 止损/仓位规则 | 多数 | ✅（硬编码，→ 规则层）|
| | 风控参数（回撤熔断/动态仓位）| 少数 | ❌ 无（riskguard 模式，→ 规则层可选）|
| **H 导入导出** | CSV/通达信导入 | 几乎所有 | ✅ |
| | 数据导出/备份 | 几乎所有 | ⚠️ 无导出（data/ 文件本身可备份）|
| | 券商自动同步 | 少数 | ⚠️ 半自动（通达信导出手动导入）|
| **I AI** | AI 复盘/分析 | 少数（新兴）| ✅ |
| | AI 个性化建议 | 少数 | ✅ advice |
| **J 系统体验** | 标签/分类 | 多数 | ✅ |
| | 仪表盘（总览）| 几乎所有 | ✅ account 卡 |
| | 推送/提醒 | 少数 | ✅ 8 开关推送 |
| | 多账户管理 | 多数 | ✅（多账号 + X-User-Id 隔离）|
| | 搜索/筛选 | 多数 | ⚠️ 部分（历史成交日期范围，无全量搜索）|
| | 周期绩效（TWR/ROAI）| 多数 | ❌ 无（→ 绩效统计层候选）|
| | 周期报告（周/月）| 多数 | ⚠️ 无独立周期报告（有每日操作总结）|

### 4.3 P0 必备骨架（对标结论）——AdaiOS 差距

> 调研结论：标的库、流水、持仓视图、基础盈亏、仪表盘、权益曲线、逐笔复盘、标签、CSV 导入、导出备份——**缺任一即残废**。

| P0 必备 | AdaiOS | 差距 |
|:-----|:-----|:-----|
| 标的库/流水/持仓/盈亏/仪表盘/复盘/标签/CSV 导入 | ✅ | 无 |
| **权益曲线（绩效统计核心）** | ❌ | **缺——交易数据已全（account 快照 + 流水可重放），只差展示** |
| **导出/备份** | ⚠️ | 缺导出端点（data/ 文件本身可备份，但无用户侧导出）|

**多数频率缺口（4-5/7，非残废但常见）**：周期绩效（TWR/ROAI）、周期报告（周/月）、搜索筛选、扩展活动类型（股息/利息/费用——对应 P2-批次6 股息入账展示）。

**少数频率（1-3/7 = 差异化/插件生态位）**：MFE/MAE、归因分析、回撤分析、止损跟踪、风险规则引擎、纪律量化、AI 复盘、开放数据格式、券商自动同步——AdaiOS 的纪律量化 + AI 复盘已领先，MFE/MAE 与归因是可选补齐。

### 4.4 架构启示（对标结论）

1. **账本（流水）与派生（持仓/绩效）分层，可重算**——Ghostfolio 事件溯源式、Tradervue 成交流→归组→交易；AdaiOS 已有 `POST /trading/sync`（按流水重建持仓），绩效层（权益曲线/回撤/TWR）可同样从流水重放推导 ✅ 架构不冲突
2. **插件扩展点**：导入器适配器、行情源、AI 提供方（MCP/Ollama）、报告模板、规则引擎——AdaiOS 已有行情源接口（MarketDataSource）+ 规则引擎接口（TradingRuleEngine）+ 推送渠道接口（PushChannel），扩展点思路一致
3. **少数派高价值差异化**：AI 复盘、纪律量化、MFE/MAE、归因分析——AdaiOS 的 AI 复盘/纪律量化已领先，MFE/MAE 与归因分析是可选补齐方向

> **⭐ 结论**：AdaiOS 通用能力覆盖率已很高（P0 骨架全齐），真正缺口是**绩效统计层（权益曲线/回撤/TWR/周期报告）与导出**——这两块与规则层正交，属「通用能力层」补齐项，第三阶段可与规则化并行或后续批做。

---

## 五、目标架构：能力层 vs 规则层

### 5.1 分层总览

```
┌─────────────────────────────────────────────────────┐
│  Layer 0  通用能力层（插件自带，人人一致，客观）        │
│  ─ 账户/持仓/清仓/历史成交/行情/批次/导入/推送/复盘     │
│  ─ 客观事实呈现，不掺任何人的判断                       │
├─────────────────────────────────────────────────────┤
│  Layer 1  规则引擎（确定性判定，按用户加载规则）         │
│  ─ 止损判定 / 仓位判定 / 买点信号 / 行为标注 / 打分     │
│  ─ 读 data/{userId}/trading/rules.md 等用户规则文件    │
│  ─ 无规则 → 降级（只出客观数据，不出判断）              │
├─────────────────────────────────────────────────────┤
│  Layer 2  知识注入（LLM 决策参考，按用户加载知识）       │
│  ─ 用户自己的 rules.md / strategy.md / mistakes.md     │
│  ─ 无知识 → 降级（LLM 只基于客观数据给通用建议）         │
└─────────────────────────────────────────────────────┘
```

### 5.2 规则层拆解（对照 §三 硬编码点）

> **好消息**：`TradingRuleEngine`（G-3 已抽离）本就是接口形态（`evaluateStopLoss` / `evaluatePosition` / `parseRules`），第三阶段**接口不用改**——只需把实现 `DefaultTradingRuleEngine` 从「读 os/ 知识 + 硬编码 25%」改为「读用户规则配置」，或新增一个 `UserRuleTradingRuleEngine` 实现按用户加载。可插拔前置已就位。

| 引擎能力 | 现状（硬编码）| 目标（用户规则驱动）|
|:-----|:-----|:-----|
| 止损判定 | R66/R68 写死 | 用户规则：「止损位」字段 + 「现价<止损位→提醒」条件（通用模板）|
| 仓位判定 | R81 上限 25% 写死 | 用户规则：`positionLimitPercent: 25`（参数化）|
| 买点信号 | B1/B2/B1? 写死 | **插件不内建 B1/B2**；提供「通用信号模板」（如：回调 X% + 缩量 Y + KDJ<Z），用户填自己的参数；adai 的 B1/B2 语义 = adai 规则包 |
| 行为标注 | 六类写死（浮盈回吐 20%/50%、短线 5 天）| 用户规则：六类行为定义 + 阈值参数化（默认值可改）|
| 打分 | 二维（买点×0.5+执行×0.5）| 用户规则：维度权重 + 分数映射（adai 的 B1→70/B2→85 只是他的配置）|
| 建议硬约束 | R66-R95 区间写死 | 用户规则：`constraintRuleRange: [66,95]`（或从用户规则文件自动推导）|

### 5.3 通用 vs 个性化判定原则

> **判定标准**：这个能力是否**不依赖任何人的交易观**就能成立？
> - 「现价跌破止损位」→ 客观事实（止损位是数据）→ 通用
> - 「跌破止损位应该走，因为 R66 只输一根 K 线」→ adai 的交易观 → 个性化
> - 「回调 50% + 缩量 0.7 + KDJ<13 是买点」→ adai 的课程 → 个性化
> - 「仓位 25% 是上限」→ adai 的 R81 → 个性化

---

## 六、规则文件格式设计

> **File First 红线（B2）**：规则 = 文件，存 `data/{userId}/trading/`，与数据同构。文件可导出/导入/版本管理。
>
> **⭐ 2026-08-29 调研结论（规则引擎架构子代理）**：**真相源必须是结构化 YAML/JSON**——唯一同时满足可执行、JSON Schema 可校验、可导入导出、git 可 diff 的格式（json-rules-engine 的 `conditions{fact,operator,value}+event`、Easy Rules 的 YAML descriptor 都是现成范式）。**Markdown 只做"人读导出视图"**（规则包自动生成 README.md），**绝不当执行源**——解析脆弱，A 写的条目 B 的引擎可能解读不同，破坏"导入即可用"。**代码是专家逃生舱**（必须沙箱，导入的代码型规则默认拒绝）。

### 6.0 规则表示三层（调研结论）

| 层 | 形式 | 适用 | 参考 |
|:-----|:-----|:-----|:-----|
| **参数化阈值（默认层）** | YAML/JSON 单变量硬约束 | 止损 -7%、仓位 25%、回撤熔断——**95% 风控规则属于此类**，表单编辑、启动即校验（fail-closed）| riskguard `RiskConfig` / freqtrade `Protections` / vn.py RiskManager |
| **表达式（进阶层）** | 条件表达式（无 eval、操作符白名单）| 跨事实组合 `if 现价 < 成本×(1-止损%) then 提醒` | CEL / json-rules-engine |
| **行为标注（模板+参数）** | 预定义模板骨架 + 参数填空 | 亏损加仓/追高/破止损未走——事件序列+阈值组合 | TradeLyser discipline diary |

### 6.1 目录结构

```
data/{userId}/trading/
├── rules.md              # 确定性规则（引擎读）
├── knowledge.md          # 知识注入（LLM 参考：策略/误区/身份）
├── rules.yaml           # 参数化配置 + 条件规则（止损默认、仓位上限、打分权重等）
└── rules-pack/           # （可选）导入的规则包快照
```

### 6.2 规则文件语法（草案，结构化 YAML 真相源）

> **2026-08-29 修正**：原草案用 markdown 规则条目作执行源——按调研结论改为 **YAML 真相源 + markdown 人读视图**。原 `**R{n} 标题**` 解析器（`RuleEngine.parseRules`）保留用于解析规则包的 markdown 视图/旧格式兼容。

```yaml
# data/{userId}/trading/rules.yaml —— 规则真相源（结构化）
formatVersion: 1
id: my-trading-rules
version: 0.1.0
author: adai

params:                      # 参数化阈值层（表单可编辑）
  positionLimitPercent: 25
  defaultStopLossRatio: 0.93
  givebackPeakPct: 20
  givebackRatioPct: 50
  shortOverdueDays: 5

rules:                       # 条件规则层（json-rules-engine 风格）
  - id: stop-loss-breached
    name: 止损触发
    conditions:
      all:
        - fact: currentPrice
          operator: lessThan
          value:
            fact: stopLossPrice
    event:
      type: alert
      params:
        message: 现价已跌破止损位

signals:                     # 买点信号（adai 的 B1/B2 在此定义，插件不内建）
  - id: pullback-low-buy
    name: B1 回调低吸
    conditions:
      all:
        - fact: pullbackPct
          operator: greaterThanInclusive
          value: 0.5
        - fact: volumeShrinkRatio
          operator: lessThan
          value: 0.7
        - fact: kdjJ
          operator: lessThan
          value: 13

behaviors:                   # 行为标注模板 + 参数
  - id: loss-averaging
    name: 亏损加仓
    template: loss-averaging   # 预定义模板骨架
    params:
      windowDays: 10
```

**配套**：
- `rules.yaml` 用 JSON Schema 校验（导入时 fail-closed）
- 人读视图：规则包自动生成 `README.md`（markdown，供人/文档消费）
- 旧格式兼容：`**R{n} 标题**` markdown 规则可导入转换为 YAML（复用 parseRules）

### 6.2b 行为标注三类参数化示例（调研结论）

> 你之前具体问过的三类行为如何参数化——调研给出「模板 + 参数」方案，判定 = 事件序列 + 阈值组合，全部可模板化：

| 行为 | 判定条件（模板骨架）| 参数 |
|:-----|:-----|:-----|
| **亏损加仓** | 存在亏损持仓（浮亏或前笔止损）时，对其同标的再买入 | 时间窗 N（分钟/根K线）、浮亏阈值、是否同标的 |
| **追高** | 买入价相对当日高点偏离 > X%，或当日涨幅 > Y%，或远离均线 Z% | X/Y/Z、基准（high/MA）|
| **破止损未走** | 现价（或收盘）低于预设止损价且仍持仓超过 M 根K线 | 止损价来源（固定/成本×（1-止损%）/ATR）、容忍根数 M |

```yaml
# data/{userId}/trading/behavior/chase_high.yaml（行为标注规则，模板 + 参数）
id: chase_high
name: 追高标注
category: behavior
enabled: true
type: template
template: chase_high          # 引用全局模板库判定骨架
params:
  deviation_pct: 5            # 买入价偏离当日高点 > 5%
  window_candles: 3
action: annotate              # 触发给该笔交易打「追高」标签
```

> 模板库之外的高级用户可用 CEL 写自定义表达式（进阶层）。

### 6.3 规则包格式（导入导出）

> **调研结论**：规则包 = **zip + manifest.yaml**（PLUTUS 策略标准 / OPA bundle 分发模型）。

```
my-rules-pack/
├── manifest.yaml            # 包元信息（必填）
├── rules/
│   ├── stop_loss.yaml       # 参数化规则
│   ├── position_limit.yaml
│   ├── chase_high.yaml      # 行为标注规则（模板引用 + 参数）
│   └── custom_signal.yaml   # 表达式规则（CEL，可选）
├── README.md                # 自动生成的人读说明（markdown 视图）
└── schema.json              # 可选的规则 JSON Schema（或引用全局 schemaRef）
```

```yaml
# manifest.yaml 样例
formatVersion: 1              # 格式版本，不兼容时拒绝导入
package:
  id: "sl-25-pct-rule"        # 全局唯一
  name: "止损 25% 纪律包"
  version: "1.2.0"            # semver
  author: "user_A"
  tags: [risk, stop-loss]
  description: "单笔 25% 止损 + 破位 2 根 K 线提醒"
  schemaRef: "https://.../trading-rule-schema/v1.json"
  dependencies:               # 依赖模板库/预设版本
    templates: ">=1.0"
  rules: [rules/stop_loss.yaml, ...]
```

- 导出 = 打包 `data/{userId}/trading/` 规则文件
- 导入 = manifest 校验 → JSON Schema 校验 → 导入预览（diff）→ 冲突策略（skip/overwrite/duplicate）→ 落用户目录 → 引擎热加载（时间戳缓存，同 TradingKnowledgeSource 模式）
- **A 的规则 B 能用**，靠三点：① 规则只引用**标准事实字典**（现价/持仓/权益/K线——不引用用户私有数据）；② 参数带默认值 + 范围校验；③ 导入时校验迁移
- **安全**：表达式白名单操作符/函数（CEL 天然安全）；**代码型规则包默认拒绝导入**（除非签名校验通过）
- **导出自动生成 README.md**（markdown 人读版，可直接发群里分享）——markdown 的正式角色
- **adai 规则包** = 从现有 `os/trading-engine/` 生成的首个高质量规则包（B1/B2、R66/R81 语义转成 YAML 规则）

### 6.4 规则执行模型（调研结论）

| 层 | 引擎 | 说明 |
|:-----|:-----|:-----|
| 参数化阈值 | 现有 `TradingRuleEngine` 扩展（读 YAML params）| 止损/仓位/回撤——表单编辑，启动即校验（fail-closed）|
| 条件规则 | json-rules-engine / CEL（轻量级）| `conditions{fact,operator,value} + event`——无 eval、操作符白名单 |
| 行为标注 | 预定义模板 + 参数 | 事件序列判定（亏损加仓 = 持仓浮亏 + 追加买入）|
| 代码型规则 | ❌ 默认拒绝 | 需沙箱（Lua/JS）才放行——第一版不做 |

### 6.5 用户隔离与状态

> **调研结论（细则）**：参考 riskguard / freqtrade / OPA 实践，规则目录可细化为：

```
data/rules/
├── _templates/                 # 全局行为模板库（亏损加仓/追高/破止损未走…）
├── _presets/                   # 全局预设包（保守/均衡/激进，riskguard 风格）
└── users/
    └── {userId}/
        ├── manifest.yaml       # 个人规则包清单
        ├── risk/               # 风控参数（个人覆盖 preset 的 diff）
        │   ├── stop_loss.yaml
        │   └── position_limit.yaml
        ├── signals/            # 买点信号规则
        └── behavior/           # 行为标注规则
            ├── add_to_loser.yaml
            └── chase_high.yaml
```

设计要点：
1. **文件即规则**：每用户独立目录 = 天然隔离 + git 可 diff + 可回滚（File First 红线 B2）
2. **引用边界**：规则只引用**标准事实字典**（`now_price/cost_price/position_qty/equity/candle.*/last_trade.*`——平台 schema 统一定义），禁止引用用户私有数据 → 规则包可跨用户导入
3. **预设 + 覆盖**：用户规则包继承全局预设（conservative/balanced/aggressive），个人文件只存**覆盖项**（riskguard `config.replace()` 模式）——导入别人的包同理只合并 diff，避免整包冲突
4. **状态隔离**：有状态规则（熔断/冷却/连续止损计数）状态按 `(userId, packId, ruleId)` 隔离存储，带乐观锁防多写者
5. **审计**：每次规则求值/触发写审计日志（带 userId，riskguard 哈希链可参考）

### 6.6 对标参考（调研来源）

- [json-rules-engine](https://github.com/CacheControl/json-rules-engine)（conditions+event 范式）
- [riskguard](https://github.com/SilentFleetKK/riskguard)（仓位上限/回撤熔断/动态仓位，RiskConfig 模式）
- [freqtrade Protections](https://www.freqtrade.io/en/2021.1/includes/protections/)（策略保护参数化）
- [PLUTUS](https://osaengine.com/en/blog/plutus-reproducibility-standard-trading-strategies/)（策略标准 + zip + manifest）
- [TradeLyser 纪律日记](https://docs.tradelyser.com/docs/discipline-diary/log-violations)（行为标注模板）

---

## 七、嵌入系统方式（插件模型 + 数据隔离）

### 7.1 复用现有插件基建

- 载体：`Account.plugins` 含 `trading` → 用户启用交易插件（已实现，G-1~G-6）
- 门控：读写端点 `requireTradingPlugin(403)`（已实现）
- 数据隔离：`data/{userId}/trading/`（已实现）
- 前端显隐：`GET /me/plugins`（已实现）

### 7.2 新增：规则层挂载点

| 挂载点 | 改动 |
|:-----|:-----|
| `TradingRuleEngine` | 注入用户规则：`evaluate(userId, ...)` 读 `data/{userId}/trading/rules.yaml`；无规则 → 降级返回默认值判定（P1-5 定稿：默认值兜底）|
| `BuyPointDetector` | 构造参数改从用户规则加载（回调%/缩量/KDJ/放量/窗口），无规则 → 不输出买点信号（只输出客观量：回撤%、量比、KDJ 值）|
| `SoldTradeVerdict` | 阈值/文案从用户规则加载；无规则 → 只出「盈利/亏损」客观分类，不判「违反」|
| `TradingLotService` | 行为标注阈值/启用开关从用户规则加载；无规则 → 只出批次数据，不标行为 |
| `SoldScoreService` | 维度权重从用户规则加载；无规则 → 只出客观数据，不出分 |
| `TradingAdviceAppService` | 硬约束区间从用户规则推导；知识注入从 `data/{userId}/trading/knowledge.md` 读（替换 os/ 全局）|
| `TradingKnowledgeSource` | 改按用户读（`data/{userId}/trading/`），os/ 仅作 adai 的默认知识（或模板）|
| 规则状态（熔断/冷却）| 新增 `data/{userId}/trading/rule-state/`（按 (userId, packId, ruleId) 隔离）|

### 7.3 知识注入的归属问题（要拍板）

现状 `TradingKnowledgeSource` 读 `os/trading-engine/`（仓库内知识，全用户共享一份）——**`os/trading-engine` 本质是「adai 的纪律交易系统内核」**（definition/README.md 自述：一套成熟的纪律交易系统，以引擎形态存在；知识层 = R1-R120 规则 + 六步法策略 + 高频错误 E1-E30，均来自 adai 的 87 课课程）。第三阶段：

- **方案 A**：改读 `data/{userId}/trading/knowledge.md`（用户私有知识），os/ 仅作 adai 默认。多用户时各自知识隔离 ✅ 符合「每人自己的交易系统」
- **方案 B**：保留 os/ 作模板库，用户启用时复制到自己的 data/ 再编辑
- **方案 C**：混合——通用交易常识（如手续费规则）在 os/，个人策略在 data/（分层知识）

> **关键现状事实（2026-08-29 核实）**：
> - `TradingKnowledgeSource`（`kernel/knowledge/`）读 `os/trading-engine/knowledge/context/` 五文件（identity/strategy/rules/mistakes/current），路径走 `@Value("${adai.knowledge.trading-engine-path}")` 配置——改按用户加载只需换路径解析
> - `TradingAdviceAppService` 同样注入该目录 rules.md + strategy.md，抽取 R66-R95 作决策硬约束
> - `TradingContextContributor` 是半成品死代码（`supports()` 恒 false），交易上下文实际由 `MarketContextContributor` + `TradingKnowledgeSource` 提供——第三阶段可顺手清理或改造
> - 反哺闭环：复盘 promote → `os/trading-engine/99-inbox/`（adai-core 只写 inbox 不自动入库）——若走方案 A，反哺目标也要改（用户自己的 `data/{userId}/trading/` 知识）

---

## 八、自定义机制（导入/导出/降级）

### 8.1 用户交互（admin 或交易页）

- **规则页**（web 交易 Tab 新增「规则」区或独立页）：查看/编辑 rules.yaml（表单化参数，D4 决策：表单优先 + 原始文件兜底）
- **导入规则包**：上传/粘贴 → 校验 → 生效（预览 diff）
- **导出规则包**：下载（分享给朋友 / 备份）
- **adai 规则包**：市场入口「导入 adai 的交易纪律包」（模板）

### 8.2 降级行为（无规则用户）

> **P1-5（2026-08-30 审查定稿，用户拍板）**：降级语义 = **默认值兜底**（非纯客观降级）——
> 无规则用户行为与 adai 现状一致（默认 25% 仓位/默认 −7%/-5% 阈值/B1/B2 默认参数/R66/R53 语义），
> 保证「规则可选，但行为不空」；D6 决策「无规则用户给通用建议」在建议引擎层实现（无硬约束 → 通用建议）。

| 场景 | 无规则时（默认值兜底）|
|:-----|:-----|
| 持仓页 | 正常（客观数据）|
| 止损提醒 | 默认 −7% 兜底判定 + R66 语义（与 adai 现状一致）|
| 买点信号 | 默认参数（回调 0.5/缩量 0.7/KDJ13/放量 1.5/窗口 20）判定 B1/B2 |
| 行为标注 | 默认阈值（浮盈回吐 20%/50%、短线 5 天）标注六类 |
| 建议引擎 | 默认硬约束区间 66-95（与 adai 现状一致）|
| 复盘打分 | 默认权重 0.5/0.5 打分 |

> 用户有规则文件 → 全部参数按用户配置；「每人有自己的交易系统」= 有规则时生效，无规则时用默认（不空转）。

### 8.3 导入安全

- manifest + JSON Schema 校验（fail-closed，坏规则包拒绝 + 人话错误）
- 规则只引用标准事实字典（不引用用户私有数据）→ 导入无隐私泄漏
- 代码型规则默认拒绝（沙箱逃生舱第一版不做）
- 导入不覆盖已有规则（冲突提示，skip/overwrite/duplicate 三策略）

---

## 九、adai 规则体系接入（第三阶段）

> 测试全部通过后，把 adai 的交易系统转成规则包接入。

1. **规则包生成**：从 `os/trading-engine/knowledge/context/rules.md`（R1-R120）+ `buy-point-rules.md`（B1/B2/B3/SB1）+ 现有硬编码参数（-7%、25%、0.5/0.7/13/1.5/20）生成 `data/adai/trading/` 规则文件
2. **行为标注六类**：阈值（20%/50%/5 天）写入 adai rules.yaml params
3. **打分权重**：买点×0.5 + 执行×0.5 写入 adai 配置
4. **验证**：adai 账号行为与现状一致（回归测试：规则文件加载后的判定 = 原硬编码判定）
5. **os/trading-engine 定位变化**：从「运行时唯一规则源」变为「adai 规则包的源材料 + 课程知识库」（沉淀/反哺目标不变）

### 9.1 源材料 → 规则文件映射（预研，2026-08-29）

| os/trading-engine 源 | 生成目标（data/adai/trading/）| 说明 |
|:-----|:-----|:-----|
| `knowledge/context/rules.md`（R1-R120，537 行）| `rules.yaml` 规则区 + `README.md` 人读视图 | `**R{n} 标题** + > 描述` 先解析（parseRules）再转换 YAML 条件规则 |
| `engine/buy-point-rules.md`（B1/B2/B3/SB1 判定口径）| `rules.yaml` signals 区 + params | 参数 0.5/0.7/13/1.5/20 落 params；B1/B2 语义转 signals（命名保留在 adai 包内）|
| `knowledge/context/strategy.md`（六步法）| `knowledge.md` 策略区 | LLM 知识注入 |
| `knowledge/context/mistakes.md`（E1-E30）| `knowledge.md` 误区区 | LLM 知识注入 |
| `knowledge/context/identity.md` / `current.md` | `knowledge.md` 身份/现状区 | LLM 知识注入 |
| Java 硬编码（-7%/25%/20%/50%/5 天）| `rules.yaml` params | 从代码抽出落配置 |
| — | `manifest.yaml` | formatVersion/id/version/author（规则包元数据）|

---

## 十、实施路径与测试

> **✅ 2026-08-30 全部实施完成（用户拍板「按建议来」后动工 + 「继续」，后端 864 / web 127 全绿 + META-GUARD PASS）**：
> - **Step 2 基础设施**：`TradingRuleSettings`（16 参数，fail-closed）+ `TradingRuleSettingsRepository`（`data/{userId}/trading/rules.yaml`，SnakeYAML SafeConstructor）+ 单测 8 条
> - **Step 3 R1 止损/仓位引擎**：`TradingRuleEngine` userId 感知重载，仓位上限按用户规则；三调用方传 userId；引擎测试 +2
> - **Step 4 R4 行为标注 + R3 清仓 verdict**：行为标注阈值从用户规则读；`effectiveStopLoss` 按用户 defaultStopLossRatio；`SoldTradeVerdict` 参数化（阈值+规则引用，默认 R66/R53 保前端契约，+4 测试）
> - **Step 5 R2 买点信号**：`WatchlistBuyPointService`/`SoldScoreService` 买点 5 参从用户规则读（D2：通用原语内建，B1/B2 命名留规则包）
> - **Step 6 打分/建议/知识注入**：打分权重（默认 0.5/0.5）、建议硬约束区间（默认 66-95）、知识注入用户私有（`data/{userId}/trading/knowledge.md` 优先，os/ 作 adai 默认）
> - **Step 7 降级验证**：`TradingRuleDegradationTest` 5 条（无规则用户 = 纯客观 + 用户规则覆盖生效）
> - **Step 8 adai 规则包**：`data/adai/trading/rules.yaml`（全默认值）+ `knowledge.md`（os/ 五文件合并），真实文件加载回归（行为不变）
> - **Step 9 规则 UI**：后端 `GET/PUT /trading/rules` 端点 + web 交易页第 6 Tab「规则」（16 参数中文标签 + 编辑弹窗表单化 PUT + 加载失败降级）+ web 测试 +2
>
> **文档同步**：api-spec v3.33（2 端点契约）、data-format-freeze §2.16（rules.yaml 契约）、feature-reference §9（规则能力）、status.md（864/127/88 端点）、change-log 本批。
>
> **遗留（下一批候选）**：规则包 zip+manifest 导入导出（D5/D7 后置）、规则编辑更完整表单（D4 已实现表单优先）、买点信号命名内建与 B1/B2 解耦（D2 已内建原语，命名随 adai 规则包）。

### 10.1 实施顺序（小步动刀，每步可验证）

```
Step 1  文档定案（本文 approved）→ 决策点拍板（§十一）
Step 2  规则文件基础设施：rules.yaml 读写 + fail-closed 校验 + 按用户加载（Repository 层，复用 PushSettings 模式）
Step 3  R1 止损/仓位引擎接用户规则（TradingRuleEngine 读用户配置；接口不变，实现改或新增 UserRule 实现）
Step 4  R4 行为标注 + R3 清仓 verdict 接用户规则（模板 + 参数）
Step 5  R2 买点信号接用户规则（BuyPointDetector 参数从配置加载；B1/B2 语义移入规则包）
Step 6  R5 打分 + R6 建议引擎 + R7 知识注入改按用户（含反哺目标切换）
Step 7  降级行为全链路验证（无规则用户 = 纯客观）
Step 8  adai 规则包生成（os/trading-engine 源材料 → YAML 规则 + manifest zip）+ 回归（adai 行为不变）
Step 9  规则页 UI（web 编辑/导入/导出）+ 测试
Step 10 /ship 收尾（guard-meta/align + change-log + status 更新 + api-spec/freeze/feature-reference 同步）
```

### 10.2 测试要点

- 规则文件加载/热更新（时间戳缓存，同 TradingKnowledgeSource 模式）
- 用户隔离：A 改规则不影响 B
- 降级：无规则用户各端点行为断言
- 回归：adai 规则包加载后判定 = 原硬编码判定（防止行为漂移）
- JSON Schema 校验：坏规则文件拒绝 + 人话错误（fail-closed）
- 导入导出 round-trip（zip + manifest + YAML 一致性）
- 规则跨用户导入：A 的规则包导入 B 后行为正确（标准事实字典验证）
- 有状态规则（熔断/冷却）按 (userId, packId, ruleId) 隔离

### 10.3 工作量预估（粗）

| 步骤 | 量级 | 风险 |
|:-----|:-----|:-----|
| Step 2 基础设施 | 中 | 低 |
| Step 3-4 引擎接配置 | 小-中 | 低（参数化）|
| Step 5 买点重构 | 中 | 中（B1/B2 语义迁移）|
| Step 6 建议/知识注入 | 中 | 中（知识归属方案 A/B/C 待定）|
| Step 7-8 降级 + 回归 | 中 | 中 |
| Step 9 规则 UI | 中-大 | 低（前端表单）|

### 10.4 文档同步面（第三阶段实施时一并更新）

| 文档 | 同步内容 |
|:-----|:-----|
| `api-spec.md` | 新增规则端点契约（如 `GET/PUT /trading/rules`、`POST /trading/rules/import`）；涉及规则的端点（buy-points/advice/sold/score）响应注明「按用户规则」|
| `data-format-freeze.md` | 新增 §2.16 规则文件契约（`trading/rules.yaml` 格式）|
| `trading-features.md` | §三 核心机制改「规则引擎按用户加载」；§八 硬编码注意点移除 |
| `feature-reference.md` §9 | 交易模块功能描述更新（规则页/导入导出）|
| `frontend-reference.md` | 前端买点枚举改读用户规则包 |
| `REVIEW.md` | S6（买点参数硬编码）/S7（三维实为二维）随重构出表或改口径 |

---

## 十一、决策点与拍板结果

> 2026-08-30 用户拍板：**按建议来 + 修全部**（审查后发现 P0/P1 按建议修复，降级语义 P1-5 定稿为默认值兜底）。

| # | 决策点 | 拍板结果（2026-08-30）|
|:-:|:-----|:-----|
| D1 | **知识注入归属** | ✅ **A 用户私有**——`data/{userId}/trading/knowledge.md` 优先，os/ 仅作 adai 默认；P1-3 补 owner 白名单 fallback 收窄 |
| D2 | **买点信号模板** | ✅ 内建通用原语（回调/缩量/KDJ/放量/突破），B1/B2 命名随 adai 规则包（命名解耦后置）|
| D3 | **行为标注默认** | ✅ 带默认模板（六类），阈值用户可调 |
| D4 | **规则编辑 UI** | ✅ 表单优先 + 原始文件兜底（P1-6 补恢复默认/exists 消费）|
| D5 | **adai 规则包分发** | ✅ 独立导出导入（zip+manifest 后置，D7）|
| D6 | **无规则用户建议引擎** | ✅ 默认值兜底（= adai 现状）+ 通用建议（P1-5 定稿）|
| D7 | **规则包市场** | ✅ 先导入导出，市场后置 |
| D8 | **实施范围** | ✅ 先 Step 2-4 验证，再 Step 5-10 铺开（全部完成）|
| D9 | **规则真相源格式** | ✅ **YAML 真相源**（markdown 仅人读）|
| D10 | **规则引擎选型** | ✅ 自研轻量（零新依赖），表达式层后置 |
| D11 | **通用预设包** | ⏸ **后置**——先不做通用预设，默认值即预设（2026-08-30 拍板）|

> **审查后追加（2026-08-30，deep 深审）**：P0-1 保存链路 fail-visible / P0-2 硬约束防掏空 / P1 六项 / P2 八项 / P3 六项 + 文档 14 项——见 `docs/review/audits/2026-08-30-trading-rule-layer-review.md`。

---

## 附：与现有文档的关系

- `framework-plus-plugin-model.md`（形态总纲）——本文是 trading 插件的具体化
- `framework-plugin-gap.md`（G-1~G-6）——已完成的插件机制，本文在其上叠加规则层
- `trading-features.md`（功能手册）——现状事实，第三阶段实施后同步更新
- `task-plugin-model.md`（插件任务拆分）——第二阶段的落地记录，本文是第三阶段蓝图
- `memory-frameworks-borrow.md`（记忆借鉴）——同款「调研对标 → 差距清单 → 分档建议」方法论
