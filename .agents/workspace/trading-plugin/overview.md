---
title: 交易插件全景梳理（trading plugin）
description: trading 插件的「一页总览」——定位与整体现状 · 功能全景 · 全部相关 RFC · 未修与待办 · 未优化与已知取舍；把散落在功能手册 / RFC / REVIEW / status / 代码里的交易内容收拢成本分支任务快照
version: 1
created: 2026-10-05
updated: 2026-10-07
status: active
lines: 311
depends-on:
  - ../../knowledge/reference/manuals/trading-features.md
related:
  - ../../knowledge/features/_index.md
  - ../../knowledge/reference/status.md
  - ../../records/REVIEW.md
  - ../../direction/rfc/_index.md
  - ./LEDGER.md
  - ./uiux-discovery-20261007.md
tags: [workspace, task, trading, plugin, overview]
---

# 交易插件全景梳理（trading plugin）

> **这是什么**：把散落在「功能手册 / RFC / REVIEW / status / 代码」里的交易内容**收拢成一页**——定位与现状 · 功能全景 · 全部相关 RFC · 未修与待办 · 未优化与已知取舍。
> **这不是真相源**：本文件是 **2026-10-05 的现算快照（汇总视图）**，只做索引与汇总、不新增事实。任一条目的权威出处见 §八；两者冲突时以真相源为准（数字以 status.md 为准，本文件只引不抄）。
> **归属**：分支任务在制品（`feat/trading-plugin`）——随本分支收尾归档，不入长期真相源。

## 〇、一页看懂

| 维度 | 现状（2026-10-05） |
|:--|:--|
| 定位 | 「阿呆 memory 内核在交易域的投影」——**用你的历史成交，理解你的持仓与当下**（RFC 20260902 用户拍板，取代 20260815 定位条款） |
| 插件标识 | `trading`（代码 `PluginRegistry.PLUGIN_TRADING`）；命名约定 `domain/trading` · `/api/v1/trading/*` · `data/{userId}/trading/` · `os/trading-engine/`（均无后缀） |
| 门控 | 除 `GET /trading/has-activity` 外，全部交易端点需 trading 插件（未启用 → 403）；前端入口按 `GET /me/plugins` 显隐 |
| 记忆五层（RFC 20260902） | ①事件（逐笔流水）②模式（行为标注六类）③案例（完美买点库）④规则（`rules.yaml` 用户纪律）**⑤认知层（画像 + 建议闭环，building）**；①②③④ 已成，⑤待建 |
| 规模（status.md 快照） | 交易域后端 Java **94** 个文件；全项目端点 **173**（其中 TradingController 46 + TradingCaseController 4 + TradingEvidenceController 3 + Round/Plan/admin 等）；后端全量测试 **2462**（0 失败 / 2 skipped） |
| 上线态 | 生产后端 **v3.94**（2026-10-03 第二十次部署）；此后 2026-10-04 C 档 + 2026-10-05 A/B 档**本地已提交、未部署、未推送** |
| 未修概览 | 交易本体：`P2-交易66` · `P2-交易71` · `P2-交易81` · `P2-交易87` · `P2-交易88` · `P2-认知3`；能力缺口：`S-12`；工程侧波及：`P2-工程12③` / `14` / `15` |
| 在制方向 | App 端形态重做（RFC 20260924 draft）· 新用户初始化顺序（P2-交易81）· 「今天没动」接推送（P2-交易87）· **双端 UI/UX 重构（方向稿 v2 → `uiux-discovery-20261007.md`）** |

## 一、插件定位与整体现状

### 1.1 是什么（插件形态）

- **框架 + 插件**（RFC 20260814 / 20260816）：trading 是可插拔的 Domain 插件之一（撤 project 后只剩 `trading` / `learn`），代码 / API / 数据目录命名均**不带 plugin 后缀**。
- **启用载体**：`Account.plugins`（`data/accounts/accounts.json`）；seed 账号 `admin` = `[trading]`，新账号默认空（无插件）。
- **门控语义**：读侧 + 写侧双向门控；行情推送等定时任务只轮询启用 trading 的用户。**唯一例外** = `GET /trading/has-activity`（代码未做门控，产品路径只读，服务 app 复盘横幅）。
- **数据职责分层**（RFC 20260902 §六）：个人业务数据（历史成交 / 持仓 / 自选 / 清仓 / 资金）由**用户自己**在 web 导入；全 A 日线行情包（tdx `.day`）是**全局公共资产**（`data/market/`，userId 层之外），导入归 admin / 运维侧。**产品红线：app / web 永不出现行情数据导入**。

### 1.2 记忆五层模型（口径中枢）

| 层 | 名称 | 载体 | 状态 |
|:--:|:--|:--|:--:|
| ① | 事件 | 逐笔流水 `trading/trades/{yyyy-MM}.json`（唯一成交真相源） | ✅ |
| ② | 模式 | 行为标注六类（追高/恐慌割肉/贪心没走/套牢死扛/犹豫错过/急躁操作） | ✅ |
| ③ | 案例 | 完美买点案例库 `trading/cases/` + 归一化相似度 | ✅ |
| ④ | 规则 | 用户交易规则 `trading/rules.yaml`（16 参数） | ✅ |
| ⑤ | 认知 | 个人画像（profile）+ 建议闭环（advice-loop） | 🚧 building |

### 1.3 现状数字与上线态

- **测试 / 端点 / 代码**：以后端全量 **2462**（2 skipped）、端点 **170**、交易域 Java 文件 **94** 为当日快照；数字唯一真相源是 status.md。
- **生产 vs 本地**：生产后端停在 **v3.94**；`2026-10-04 C 档`（+40）与 `2026-10-05 A 档 / B 档`（+25 / +67）尚**未部署**（部署属外向动作，须用户点头）。iOS 侧构建 15 已 assign 外测组并提交 Beta App Review。

## 二、功能全景

> 完整端点契约见 api-spec.md；逐端点明细见 trading-features.md（本手册更细、以代码实测为准）。本节给**分组总览**。

### 2.1 功能主轴（Feature Index 口径）

| ID | 功能 | 状态 | 需求出处 |
|:---|:-----|:----:|:---------|
| `trade.ledger` | 账本：导入 / 流水 / 对账 | shipped | RFC 20260912 · RFC 20261003（账本强关联） |
| `trade.holdings` | 持仓与账户卡 | shipped | —（含 RFC 20261003） |
| `trade.decision` | 决策时点提醒 + 四要素铁证 | shipped | RFC 20260922 |
| `trade.alert` | 行情异动推送 | shipped | RFC 20260923 · RFC 20260928 |
| `trade.equity` | 资金曲线与周期盈亏 | shipped | —（2026-09-04 自主批，无 RFC） |
| `trade.case` | 案例库与三维打分 | shipped | RFC 20260830 |
| `trade.cognition` | 认知 / 画像（第五层） | **building** | RFC 20260905 |
| `trade.app-form` | App 端交易形态（重做中） | **rfc** | RFC 20260924（draft） |

### 2.2 后端端点分组总览（全项目 173 端点中的交易部分）

> 全部端点要求 `X-User-Id` header（默认 `"default"`）；除注明外均受 trading 插件门控。TradingController 端点**无 TODO 占位**。

| 组 | 代表端点 | 能力 |
|:--|:--|:--|
| 交易记录 | `POST/GET /trades` · `/trades/batch` · `/trades/import` · `/trades/parse` · `/lots` · `/sync` | 记一笔 / 批记 / 历史成交导入 / 一句话解析 / 批次视图 / 一键按流水重建持仓 |
| 持仓管理 | `GET /positions` · `/positions/daily` · `/portfolio` · `POST /positions/import` · `PUT /positions/{symbol}` | 持仓查询（行情注入）/ 逐股当日口径 / 组合快照 / 初始化导入 / 元信息更新 |
| 账户资金 | `GET /account` · `POST /imports/cash` · `/transfer` · `/transfers` · `PUT /principal` · `GET /pnl-periods` | 账户快照 / 资金股份导入 / 银证转账 / 转账流水 / 本金 / 日周月盈亏 |
| 自选与买点 | `GET /watchlist` · `/watchlist/import` · `GET /buy-points` · `/buy-points/scan` · `/market-data/health` · `/market-stage` | 自选 / 买点信号（B1/B2）/ 完整扫描 / 行情链路健康 / 活跃市值区间 |
| 清仓复盘 | `GET /sold` · `/sold/import` · `PUT /sold/{symbol}/psychology` · `GET /sold/score` | 清仓股 / 导入 / 心理标注 / 三维打分（实为二维） |
| 持仓解读 | `POST /advice` | 逐票建议（buy/hold/reduce/clear）+ 每条 `evidence` 四要素铁证 |
| 铁证底座 | `GET /evidence/history` · `/evidence/rule/{ruleRef}` · `POST /evidence/backfill` | ①本人历史统计（样本门槛 N≥5）· ③规则原文逐字 · ④结果回填（幂等） |
| 复盘 / 推送 / 日志 | `POST/GET /review` · `/reviews` · `/reviews/{date}/promote` · `/push-settings` · `/trade-log` · `/trade-log/confirm` · `/trade-log/date` · `/screenshots` · `DELETE /pushes/{id}` | 生成/查询复盘 · 反哺 `99-inbox` · 8 类推送开关 · 候选确认（防重复入账）· 截图入账 |
| 交易规则 | `GET/PUT /rules` · `GET/PUT /market-stage` | 16 参数用户规则 · 活跃市值判定（用户手动） |
| 导入 / 工具 / 管理 | `POST /imports/save` · `GET /integrity` · `GET/PUT /anchor` · `GET /lookup` · admin `/knowledge/conflicts` | 文件留存 · 账实一致性自检 · 锚定查询/回填 · 代码查名称 · 持仓 vs 规则冲突 |
| 计划与回合 | `/plans*`（写/读/对账/`{date}/status`）· `GET /rounds` | 次日操作计划（事前承诺·事中守约·事后对账）· 一轮完整交易 + 规则检查 |
| 案例库 | `POST/GET/DELETE /cases` · `/cases/{id}` · `/cases/{id}/insight` · `/cases/import` · `/cases/match` | 标注 / 列表 / 详情（K 线重放）/ AI 理解 / 批量导入 / 判定当下 |

### 2.3 后端定时任务（仅交易日生效）

| 时间 | 任务 | 要点 |
|:--|:--|:--|
| 09:15 | 早盘计划（含买点） | 持仓概览 + 自选买点**四要素铁证**；**确定性渲染不走 LLM**；账文案标账日期；行情失败整条降级 |
| 12:00 | 午间知会 | **只报事实与位置、不催操作**；无异常不发；行情取不到不发 |
| 14:50 | 尾盘卖点 | **只列触发卖出的持仓**（破止损 R66 / 超仓 R81）；无触发不发 |
| 15:05 | 收盘账户自动更新 | 任一持仓缺行情则**整体跳过不覆盖**（防残缺市值覆盖总资产），跳过时推一条提醒 |
| 15:15 | 收盘交易日志确认 | 有候选才推 |
| 15:30 | 收盘复盘兜底 | **「数据同步完成后」的产物，不是到点硬发**；未同步 → 提示导数据（不落「已发」标记）；每天至多一条 |
| 每 30 分钟 | 行情异动轮询 | stop-loss / near-stop-loss / loss / gain / break-cost；**批次级止损**独立去重；同票同类当日去重 |
| 每 30 分钟（交易时段） | 兜底源体检 | 新浪兜底主动探活，结果进 `market-data/health` 的 `fallbackHealthy` |

> 节假日（2026–2027 硬编码表）与周末不推送；全部定时任务均有 `isTradingDay` / `isTradingDayStrict` 守卫。

### 2.4 核心机制（selective）

- **规则引擎**：确定性判定层——止损 R66（现价口径）、仓位 R81（分母 = 总资产含现金）；建议引擎 / 时段推送 / 行情异动**三方共用同口径**；第三阶段起阈值全部从用户 `rules.yaml` 读取（无规则 = 默认值兜底）。
- **四要素铁证**（RFC 20260922）：①本人历史统计（N≥5）②具体数字证据链 ③规则依据原文 ④可回溯可追责；**缺证据不发、绝不编造**。
- **三条真源**（RFC 20260912）：券商快照（锚点）/ 逐笔流水（唯一成交真相源）/ 派生持仓（算出来的）——派生量恒等于「最近锚点快照 + 锚点之后净变化」，对不上即 drift。
- **账本强关联**（RFC 20261003）：单一账本、两侧原子；T+1「可用 / 可取」分离；全量导入改「先对账、差额不为 0 才确认」；`Idempotency-Key` 幂等键；锚定 fail-closed。
- **手续费模型**：佣金万 0.854 + 印花税万 5（卖出）+ 过户费万 0.1（沪市）；五笔券商交割实例反推确认。
- **批次推导**（RFC 20260825）：同标的 + 同方向 + 同日合并为一批；卖出 LIFO；批次止损 = 覆盖层 > 流水止损 > 默认 −7%。
- **行情链路**（RFC 20260923 / 20260928）：tdx 本地（前复权）→ 腾讯（双域名）→ 新浪（兜底）；连续失败 3 次熔断 5 分钟；`/market-data/health` 可见化。
- **区间盈亏**（v3.68）：逐日总资产差分再剔除银证转账；资金曲线走到锚定日重置为快照基线；日/周/月与资金曲线同源。

### 2.5 Web 管理端（adai-web，桌面）

9 个 Tab：持仓 / 自选 / 清仓 / 资金 / 历史成交 / **分析**（2026-10-06 新增）/ 规则 / 案例 / **计划**（2026-10-03 新增）。页头含：记录交易、复盘、复盘历史、推送设置。web 是「导入与编辑」的主场（各 Tab 专属导入 / 持仓编辑 / 规则 / 案例 / 打分 / 历史明细）。

### 2.6 手机 App 端（adai-app，iPhone）

单列滚动：账户总览卡（含日/周/月盈亏行 → 收益日历）→ 今日交易复盘 → 复盘横幅 → 记录区（一句话 / 精确表单 / **截图入账**）→ 持仓区（只读资产卡 + 批次简版 + 点卡弹「阿呆说」建议）→ 资金（转入/转出/设置本金）→ 明日计划折叠区。**app 独有** = 截图入账 + 「阿呆说」建议弹层；**引导去 web** = 批量导入 / 持仓编辑 / 清仓 / 自选 / 规则。

### 2.7 双端能力对照（摘要）

| 能力 | App | Web |
|:--|:--|:--|
| 持仓 | 只读资产卡 + 建议弹层 | DataTable + 逐行编辑 |
| 记录交易 | 一句话 / 精确表单（隐藏式止损买点） | 含止损 / 买点 / 目标价 / 原因 |
| 批量导入 | 无（去 web） | CSV / 通达信持仓 / 历史成交 |
| 截图入账 | **独有** | 无（历史成交导入替代） |
| 规则 / 案例 / 清仓 / 自选 | 无 | **独有** |
| 复盘 | 仅今日 | 含复盘历史 |
| 资金 | 转入 / 转出 / 本金（无导入） | 全量含导入 |
| 建议引擎 | **独有**弹层 | 无对应入口 |

### 2.8 交易知识底座（os/trading-engine）

`knowledge/context/` 五文件（identity / current / strategy / rules / mistakes，87 课沉淀，rules.md 收录 **R1–R120**）+ `engine/rules-api.md`（Java `TradingRuleEngine` 语言无关规格）+ `engine/buy-point-rules.md`（B1/B2/B3/SB1 判定规格）。消费口径：**用户私有优先**（`data/{userId}/trading/knowledge.md`），无则仅 owner（adai）回落 `os/`；其他用户不注入交易知识（防跨用户泄漏）。反哺闭环：复盘 → `99-inbox/` → 人工审核融合（adai-core 只写 inbox 不自动入库）。

## 三、RFC 内容（全部相关件）

> 唯一索引见 rfc/_index.md。⚠️ **`status` 字段与「代码是否已落地」不等价**——多个 `draft` 件其实已实装（如 20260830 案例库、20260918 重排、20261003 ×2），下表在「落地」列如实标注。

### 3.1 trading 本体（按时间）

| RFC | status | 一句话 | 落地 |
|:--|:--|:--|:--|
| 20260725-frontend-project-trading-pages | implemented | 前端项目状态页 + 交易页 | ✅ |
| 20260816-trading-agent-plugin-model | approved | 交易 Agent 三阶段插件模型（裸问答 → +行情 → +规则） | 部分 |
| 20260816-trading-data-model | draft | 交易数据模型分层（用户提供 vs 可查询） | 部分 |
| 20260816-trading-os-engine | draft | trading-engine 领域引擎化（从插件到可复用引擎） | 部分 |
| 20260816-trading-data-intelligence | draft | 数据智能：自选买点 + 清仓复盘 + 打分系统 | 部分 |
| 20260816-trading-session-push | draft | 交易时段节奏推送 + 微信渠道 | 部分 |
| 20260817-trading-push-image-trade-log | approved | 推送体验 + 图片对话流 + 交易日志自动归集 | ✅ |
| 20260822-trading-trade-time-review | approved | 成交时间采集 + 当日客观复盘（去理由/情绪） | ✅ |
| 20260823-trading-history-tab-backfill | implemented | 历史成交 Tab（第 5 Tab）+ 缺失成交时间回填 | ✅ |
| 20260824-trading-company-insight | draft | 公司透视（九维度客观画像 + 红黄绿排除） | ❌ 未做 |
| 20260825-trading-lot-tracking-behavior | implemented | 逐笔批次跟踪与行为纠偏（lot/LIFO/批次止损） | ✅ |
| 20260830-trading-perfect-case-library | draft | 完美买点案例库（案例沉淀 → 相似度 → 双轨判定） | ✅（环 1–4） |
| 20260902-trading-memory-positioning | approved | **定位重设：从「建议引擎」到「交易记忆」**（五层记忆） | ✅ 定位 |
| 20260905-trading-cognition-layer | approved | ⑤认知层：画像 + 建议闭环 | 🚧 部分 |
| 20260909-trading-clearance-derivation | implemented | 清仓股双轨收录（批 1）；批 2 多段派生视图待排 | 批 1 ✅ |
| 20260912-trading-ledger-integrity | implemented | 账本完整性：三真源收口 + fail-closed + 幂等 + 卖超可见 | ✅ |
| 20260918-trading-app-restructure | draft | App 重排「记录 → 对账 → 照见」+ 两条铁律 | ✅ A1/A2/B/C |
| 20260922-trading-decision-copilot | approved | 目标形态：决策时点对话式提醒 + 四要素铁证 | ✅ A/B/C |
| 20260924-trading-app-form | draft | App 端形态重做「一句判断 + 一行持仓」 | 🚧 进行中 |
| 20261003-trading-cash-position-linkage | draft | 资金与持仓强关联（单一账本 · 两侧原子） | ✅ 已实装 |
| 20261003-trading-plan-and-review-loop | draft | 交易计划与复盘闭环（事前·事中·事后） | ✅ 已实装 |

### 3.2 相邻件（含 trading 内容）

| RFC | status | 与交易的关系 |
|:--|:--|:--|
| 20260814-domain-plugin-model | approved | Domain = 插件模型（trading 的框架底座） |
| 20260816-framework-plus-plugin-model | approved | 框架 + 插件形态 |
| 20260923-market-data-resilience | implemented | 行情链路韧性（域名可配 + 第三源 + 健康端点） |
| 20260928-market-source-consolidation | approved | 行情渠道收敛（删东财 + 腾讯双域名 + 新浪升兜底） |

### 3.3 两件「进行中」的核心 RFC（重点）

**RFC 20260918 · App 端重排（draft，已实装 A1/A2/B/C）**

- 结构：三层「**记录（把真实成交变成账）→ 对账（券商真源 vs 系统推导）→ 照见（阿呆的理解）**」；核心唯一 = 记录与入账。
- 两条铁律：**写账必回执**、**展示必标口径**。
- 三套口径（券商真源 / 行情 / 系统推导）以**锚定日**为界，界面显式标注。
- 用户约束：金额默认 `••••` + 👁、首屏只留「每日入账 + 持仓」、数据列表风；app = 随手记与查看，web = 导入与编辑。

**RFC 20260924 · App 端形态重做（draft，未收敛）**

- 首屏四层：**一句判断（先说结论）+ 入账（常驻）+ 持仓（一行 + 就地展开）+ 今天（时态区）**。
- 三条截图入账卡点解法：选图后等太久 / 候选逐笔确认啰嗦 / 落完账跟持仓资金对不上。
- **未决项（需用户拍板）**：① 截图入账路线 A/B/最小解法三选一；② 整体形态是否成立；③ 判断句文案库（含「常态到底说不说『不用动』」）；④ 技术栈 Flutter vs Swift 原生；⑤ web 是否跟随。

## 四、待办 / 未修（REVIEW 未修项，逐条）

> 未修项唯一真相源是 REVIEW.md；「✅ 部分已修」的行**仍未出表**，残余部分照登。已修项见 docs/archive/review-fixed-2026-10.md。

### 4.1 交易本体（P2）

| # | 一句话 | 状态 |
|:--|:--|:--|
| `P2-交易66` | 「总盈亏」依赖手工 `principal` 与不完整历史出入金，无法自证；本金置信度说明已上线，**历史出入金补录/对账入口仍未做**（转账日期 ≤ 锚定日直接 400） | ⏳ 部分已修 |
| `P2-交易71` | web「标注案例/匹配」只打字不点下拉 → 静默不提交；**保守修法已上线**（校验移进弹窗 + 红字说明）；**修法①（输入即有效 / 候选唯一自动选中）待拍板** | ⏳ 部分已修 |
| `P2-交易81` | 新用户「初始化顺序」未产品化（正确顺序只写在 owner 运维手册；产品内零引导；锚定缺失时全线 fail-closed 让人处处撞墙）；同族：持仓导入不带走止损且无批量补设、近 10 日窗口隐式规则、手机端不能导入 | ❌ 未修（用户拍板「先只登记」） |
| `P2-交易87` | 「今天没动」已能回填进计划记录，但**收盘复盘与 20:30 提醒还不感知它**——填了当晚复盘仍像「这天什么都没有」 | ❌ 未修（S 级，属推送文案变更） |
| `P2-交易88` | 「动作 → 待办 → 记忆」粒度错配：一段对话多条动作共用一条记忆，划掉任意一条就整段消失 | ❌ 未修（M，粒度设计） |
| `P2-认知3` | 认知层 5 个新端点（profile / advice-history / psychology-questions / answer）前端零入口；主观层暂靠试点记忆卡对话补全 | ⏸ 待试点对味后排 UI |

### 4.2 与交易相关的工程 / 能力项

| # | 一句话 | 状态 |
|:--|:--|:--|
| `P2-工程12③` | web 匹配弹窗文案（「输入任意 6 位代码」）与被强制的行为（只输入不算）矛盾；随 `P2-交易71` 修法① 一并拍板 | ⏳ 部分已修 |
| `P2-工程14` | 幂等键与记录落盘**跨文件不原子**（孤儿记录 → 重试重复落盘）；幽灵假成功已修，剩跨文件原子性 + 重试重复落盘 | ⏳ 部分已修 |
| `P2-工程15` | `RecordRetryService` 已有键仍再落一条（需「已有键则跳过落盘」守卫，会改行为）；迁移那条已加固 | ⏳ 部分已修 |
| `S-12` | 阿呆「连续性 / 主动性」缺口——不会自己敲门；交易侧同源表现为「持仓变化类的自动回顾」未做 | ⏳ 待用户拍板 |

### 4.3 已定但需授权的存量处置（属 `data/` 改动）

- **移除 3 笔无委托号流水**（600536 700+100、000831 200）——其中 2 笔实际动过账，叠加成 600536 记为卖 1,600 股（真实 800），现金多进 ≈24,904 元。**待用户授权**。
- **13 笔股息/红利税痕迹已被误清**（净额 1,062.60 元，09-23 清理）——删后流水再无股息痕迹，现金无法仅凭流水重放。处置待定。
- 详见 docs/records/audits/2026-10-03-trading-cash-stocktake.md。

## 五、未优化 / 已知取舍 / 边界（刻意不做）

### 5.1 实现状态注意点（技术债 / 半成品，代码为准）

- **`GET /trading/has-activity` 无插件门控**：唯一例外，产品路径只读。
- **`TradingContextContributor` 未生效（半成品/死代码）**：`supports()` 恒 false、`enrich()` 恒空串；实际由 `MarketContextContributor` + `TradingKnowledgeSource` 提供。
- **「三维打分」实为二维**：选股维度恒 null，总分 = 买点×0.5 + 执行×0.5。
- **Position 无 `targetPrice` 落盘字段**：前端「编辑目标价」无效（P3）。
- **`recordTrade` 现金推导依赖已有账户快照**：首次交易前未导入资金时现金/市值不更新。
- **行情异动推送新旧两条链路并存**（`MarketAlertService` 直推 + `FeedPushChannel` 落盘供 Feed 展示）。
- **历史成交导入「只补流水」是设计取舍**：不重算持仓/现金（缺窗口前基线）。
- **节假日表硬编码**（2026 官方 + 2027 预测）：临时调休不追。
- **推送/流水写入均为 best-effort**：失败只告警不阻塞落库；流水文件损坏单月跳过。
- **双锁体系**：application `tradeLock` + repository per-user 锁，**均为单实例内进程锁**；跨文件一致性（positions/account/流水）无原子手段。

### 5.2 性能 / 架构取舍

- **资金曲线按存续区间取数**（2026-09-29）：只查标的实际存续区间，避免 172 只白补一根最新 K 线（原 ≈44s）。
- **多实例部署同写 `data/` 无跨进程锁**（当前单实例，属已知边界）；锚定防重与流水写入仍为**单实例内**保证。
- **golden 回归夹具为合成数据**（真实成交导出不入 git）。

### 5.3 明确不做（产品边界）

- **建议只输出不执行**：双端均无平仓/减仓/下单执行按钮。
- **不做「可取」每日自动结转**（A 档边界）：停在最近一次券商快照值，由每次转出校验兜住。
- **不做全市场每日扫描**：自动只覆盖自选股 + 持仓，手动可查任意代码。
- **不做分时图**：日线级别。
- **案例相似度不覆盖止损 / 仓位等规则硬判定**（规则引擎给确定性基线，相似度给经验增强，独立判定、独立降级）。
- **不把 web 的导入 / 规则 / 案例 / 打分 / 历史搬上 app**（app 管随身那一半）· **不动阿呆主页 Feed / Launcher / 底部导航**。

## 六、近期变更时间线（近况）

| 日期 | 批次 / 版本 | 要点 |
|:--|:--|:--|
| 2026-10-07 | 探索（未提交） | **UI/UX 重构启动**：方向稿 v2（`uiux-discovery-20261007.md`）——三问三看 + 护栏 + 素材索引；方向 A 终端 / B 叙事 / C 驾驶舱，待圈选 |
| 2026-10-06 | 前端补做批 1（+3 代码文件） | R-12 统一导入（一次交文件 · 页头批量导入归 Tab 专属）+ R-05 分析 Tab（三粒度） |
| 2026-10-05 | A 档 M 级（+25） | 认知②画像切逐笔回合口径 · P2-交易84 锚定日依据显式化 · P2-工程12 闸门可测化 |
| 2026-10-05 | A 档 S 级（+14） | P2-交易85 清仓导入收录门槛 · P2-交易86 兜底源探测收口 · P2-工程14/15 |
| 2026-10-05 | B 档清账（+67） | P2-交易72「今天没动」回填 · P2-交易73 对话动作→待办 · P2-工程13 共锁 |
| 2026-10-04 | C 档（+40） | 交易83 清仓丢行可见 · 交易58 兜底源体检 · learn23 · 审查补强 |
| 2026-10-03 | v3.94 **已上线** | 账本强关联（T+1/幂等/对账导入）· 计划与复盘闭环（rounds + plans） |
| 2026-09-28 | approved | 行情渠道收敛（删东财 + 腾讯双域名 + 新浪升兜底） |
| 2026-09-23 | implemented | 行情链路韧性（域名可配 + 第三源 + 健康端点） |
| 2026-09-22 | v3.82 **已上线** | 四要素铁证底座 + 建议出口带证据 + integrity degraded |
| 2026-09-20 | v3.73 **已上线** | 截图入账两处数据缺陷 + App「记录→对账→照见」重排 |
| 2026-09-12 | v3.61 **已上线** | 账实一致性治本（fail-closed + 幂等统一 + 卖超可见 + 对账自检） |

## 七、参考与横向件

- 功能手册：[trading-features.md](../../../knowledge/reference/manuals/trading-features.md)（逐端点明细，权威）
- 端点契约：[api-spec.md](../../../knowledge/reference/contracts/api-spec.md)（端点契约唯一真相源）
- 数据格式：[data-format-freeze.md](../../../knowledge/reference/contracts/data-format-freeze.md)
- 状态数字：status.md
- 未修总表：REVIEW.md
- RFC 索引：rfc/_index.md
- 风险方案：[trading-risk-plan.md](../../../../docs/research/trading-risk-plan.md)（四旋钮 + 用户数据画像校准）
- 竞品基准：[trading-journal-benchmark.md](../../../../docs/research/trading-journal-benchmark.md)（TradeZella / TraderSync / Edgewonk）
- 案例库设计：.agents/knowledge/reference/designs/trading-case-library-design.md
- 存量盘点：docs/records/audits/2026-10-03-trading-cash-stocktake.md
- 规则层审查：docs/records/audits/2026-08-30-trading-rule-layer-review.md
- 丢行审查：docs/records/audits/2026-09-13-trading-ledger-dirty-rows.md
- 已修归档：docs/archive/review-fixed-2026-10.md

## 八、真相源与参考索引

> 本文件是**汇总快照**，不新增事实。任何冲突以真相源为准。

| 想知道 | 去哪里 |
|:--|:--|
| 有哪些功能、各自状态 / 需求出处 / 欠着 | knowledge/features/_index.md |
| 端点契约（请求 / 响应 / 版本） | knowledge/reference/contracts/api-spec.md |
| 逐端点功能明细（以代码实测为准） | knowledge/reference/manuals/trading-features.md |
| 测试数 / 端点数 / 运行环境 / 发布态 | knowledge/reference/status.md |
| 未修项（P0–P2 / 战略） | records/REVIEW.md |
| 已知坑（复发信号） | rules/assets/pitfalls.md |
| 原则级边界 | rules/assets/boundaries.md |
| 方案与决策记录 | direction/rfc/ |
