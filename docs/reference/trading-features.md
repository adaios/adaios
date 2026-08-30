---
title: 交易模块功能手册（trading 插件）
description: AdaiOS trading 插件的完整功能参考——模块定位、后端端点总表与定时任务、Web/App 双端功能清单、知识底座、已知缺陷；开发与审查的基准对照
version: 1
created: 2026-08-22
status: active
depends-on:
  - ../architecture/api-spec.md
  - ../architecture/framework-plus-plugin-model.md
  - ../architecture/data-format-freeze.md
related:
  - feature-reference.md
  - status.md
  - ../rfc/20260815-trading-interaction-redesign.md
  - ../rfc/20260816-trading-data-model.md
  - ../rfc/20260816-trading-data-intelligence.md
  - ../rfc/20260816-trading-session-push.md
  - ../rfc/20260817-trading-push-image-trade-log.md
  - ../rfc/20260822-trading-trade-time-review.md
  - ../rfc/20260823-trading-history-tab-backfill.md
  - ../rfc/20260814-domain-plugin-model.md
tags: [trading, plugin, reference]
---

# 交易模块功能手册（trading 插件）

> **定位：** AdaiOS「trading 插件」的完整功能参考。覆盖模块定位、后端端点总表与定时任务、Web 管理端 / 手机端功能清单、交易知识底座、已知缺陷。
> **用途：** 问题定位、新功能开发、重构与审查时的基准对照。
> **真相源：** 端点契约以 `docs/architecture/api-spec.md` 为准；功能总览与 `docs/reference/feature-reference.md` §9 互为补充（本手册更细、以代码实测为准）。

---

## 〇、插件定位

**trading 是 AdaiOS 的插件之一**（RFC 20260814 Domain=插件模型，框架 + 插件形态见 `framework-plus-plugin-model.md`）：

- 插件标识：`trading`（代码 `PluginRegistry.PLUGIN_TRADING`），**代码/API/数据目录命名均不带 plugin 后缀**——`domain/trading`、`/api/v1/trading/*`、`data/{userId}/trading/`、`os/trading-engine/`
- 启用载体：`Account.plugins`（`data/accounts/accounts.json`），seed 账号 `adai` = `[trading, project]`；新账号默认空（无插件）
- **门控语义**：除注明外，全部交易端点需 trading 插件（未启用 → 403）；前端交易页/交易入口按 `GET /me/plugins` 显隐；行情推送等定时任务只轮询启用 trading 插件的用户（写读双侧门控）
- **唯一例外**：`GET /trading/has-activity` 代码未做插件门控（产品路径只读，见 §8 注意点）

**模块定位（RFC 20260815 用户确认）**：**不是记账工具，是建议引擎**——记录真实交易是喂数据的手段，输出买卖/持仓建议是目的。建议只输出不执行（双端均无平仓/减仓执行按钮）。

---

## 一、后端端点总表（TradingController 42 个 + TradingCaseController 4 个 + admin 1 个）

> 全部端点要求 `X-User-Id` header（默认 `"default"`）；除注明外均受 trading 插件门控（未启用 → 403）。**TradingController 42 个端点均有实现，无 TODO 占位**（2026-08-17/18 批次补齐了此前 404 的 batch/import/positions/{symbol}；2026-08-25 新增 /lots；2026-08-26 新增 /screenshots；2026-08-27 新增 PUT /trade-log/date；2026-08-30 新增 GET/PUT /rules）。**TradingCaseController 4 个（2026-08-30 第四阶段完美买点案例，见 §10）**。

### 1. 交易记录（逐笔流水）

| 方法 | 路径 | 功能 | 说明 |
|:--|:--|:--|:--|
| POST | `/trading/trades` | 记录一笔交易 | BUY/SELL → 持仓增减（加仓摊薄成本/清仓归零）+ 现金市值推导 + 手续费自动算 + 落逐笔流水 + 写 domain=trading 记录（5 分钟同标题去重幂等）；name 可空；止损/买点 BUY 已放开可选（2026-08-18）；SELL 超持仓/未持有 → 400 |
| GET | `/trading/trades` | 查询逐笔流水（含 `tradeTime` 成交时刻可空）| 跨月合并按时间倒序；可选 `from`/`to` 过滤；**RFC 20260822：`?date=` 返回 `{trades, daily}` 当日复盘聚合**（时段分桶/买卖分布/首末笔时间，纯客观）|
| POST | `/trading/trades/batch` | 批量记录交易 | 逐笔走 recordTrade 链路；逐条失败不整批回滚，返回行号+人话原因 |
| POST | `/trading/trades/import` | 历史成交日志导入 | 通达信「历史成交查询」导出 → **双模式自动识别（RFC 20260825）**；**非交易占位代码校验（2026-08-25）**——79/80/81/82 开头（799999 登记指定等）不入库计入 nonTrades；**股息类记账（2026-08-25）**——备注含股息/红利/入账的数量 0 行：入账 +现金、红利税 −现金（不动持仓/批次，落流水可回溯）：成交都在最近 10 日内 → `syncMode="sync"` 同步持仓/现金/流水（orderId 幂等，透传流水不丢幂等键）；明显历史 → `syncMode="append"` 只补流水不重算持仓（原语义）；返回对账提示 + **每日操作总结 `summary`**（sync 模式：买卖聚合 + 批次 diff + 行为标注）|
| POST | `/trading/trades/parse` | 一句话交易解析 | 自然语言 → 结构化（LLM 优先 + 正则兜底，手=×100）；**只解析不落库**；matched=false 前端转精确表单 |
| GET | `/trading/lots` | **批次视图（RFC 20260825）** | 持仓细化到每笔买入：按日合并/LIFO 卖出/回合/初始批次，注入现价 + 流水对账提示；`state=open\|closed\|all` |
| POST | `/trading/sync` | **一键按流水重建持仓（2026-08-25 用户场景）** | 导入历史成交后快照过期 → 以流水为准重建 positions：已清仓残留自动移除（removed）、流水解释不了的真底仓保留（keptInitial）；与 sync 模式互补（sync 增量 / 本端点对齐存量） |

### 2. 持仓管理

| 方法 | 路径 | 功能 | 说明 |
|:--|:--|:--|:--|
| GET | `/trading/positions` | 查询持仓 | 注入实时行情现价（行情失败降级存储价=成本价） |
| GET | `/trading/portfolio` | 投资组合快照 | 持仓（行情注入后）+ 现金（唯一真源 = account.json 的 cash，S5） |
| POST | `/trading/positions/import` | 持仓初始化导入 | 通达信导出 upsert；`replace=true` 全量覆盖（以文件为准）；name 行情补全；返回 `missingStopLoss` 提示补设（R68） |
| PUT | `/trading/positions/{symbol}` | 更新持仓元信息 | 只更新非空字段 role/止损位；不存在 404、止损位非数字 400；**targetPrice 无落盘字段（前端目标价编辑无效，P3）** |

### 3. 账户资金

| 方法 | 路径 | 功能 | 说明 |
|:--|:--|:--|:--|
| GET | `/trading/account` | 账户总体快照 | 券商口径：assets/cash/available/withdrawable/marketValue/pnl（持仓浮盈）/todayPnl/principal；**总盈亏 = 资产 − 本金** |
| POST | `/trading/imports/cash` | 资金股份查询导入 | 通达信「资金股份查询」导出 → 存账户快照 + 更新现金 + 精确成本价（4 位）；首行 CASH_HEAD 未命中 → 400 拒绝落零覆盖（P1-交易5 已修） |
| POST | `/trading/transfer` | 银证转账 | IN/OUT → 本金（净投入）+ 现金 + 资产同步 ±，追加流水；转账本身不变盈亏 |
| GET | `/trading/transfers` | 转账流水 | — |
| PUT | `/trading/principal` | 设置本金 | 只写 principal 字段（不动现金/资产/市值）；≤0 → 400 |

### 4. 自选股 / 买点

| 方法 | 路径 | 功能 | 说明 |
|:--|:--|:--|:--|
| GET | `/trading/watchlist` | 自选股列表 | 通达信形态/指标提示为买点判定原料 |
| POST | `/trading/watchlist/import` | 自选股导入 | 通达信自选导出，按 symbol upsert |
| DELETE | `/trading/watchlist/{symbol}` | 删除自选股 | 不存在 404 |
| GET | `/trading/buy-points` | 自选股买点信号 | 并发拉 K 线 → B1=距前高回撤≥50%+缩量(3日均量<5日均量×0.7)+KDJ.J<13；B2=放量(>5日均量×1.5)+收盘破前 20 日高点；B1? 部分满足候选；**判定是提示不是指令**；**第三阶段（2026-08-30）**：五参（回调/缩量/KDJ/放量/前高窗口）从 `data/{userId}/trading/rules.yaml` 读取（`buyPullbackPct`/`buyShrinkRatio`/`buyKdjLow`/`buyVolumeSurge`/`buyPriorHighDays`），无规则用默认（0.5/0.7/13/1.5/20）|

### 5. 清仓复盘

| 方法 | 路径 | 功能 | 说明 |
|:--|:--|:--|:--|
| GET | `/trading/sold` | 清仓股列表 | — |
| POST | `/trading/sold/import` | 清仓股导入 | 通达信清仓导出 upsert；导入时按规则生成 verdict（盈利了结/亏超5%违 R66 扛单/短持仓亏违 R53） |
| PUT | `/trading/sold/{symbol}/psychology` | 清仓股心理标注 | 用户复盘素材（隐私保护） |
| GET | `/trading/sold/score` | 清仓三维打分 | 买点维度（回溯买入日 K 线：B2=85-100/B1=70-100/B1?=50/无形态=25，数据不足返回 null 不误判）+ 执行维度（盈利 90/守纪律亏损 65/违 R53 45/违 R66 15）；**选股维度恒 null → 总分实为二维 = 买点×0.5 + 执行×0.5（S7）** |

### 6. 交易建议（核心定位：建议引擎）

| 方法 | 路径 | 功能 | 说明 |
|:--|:--|:--|:--|
| POST | `/trading/advice` | 生成持仓建议 | 读持仓+实时行情+规则文本（抽取 `constraintRuleMin~constraintRuleMax` 区间作决策硬约束——**第三阶段按用户规则，默认 66-95 = adai R66-R95 止损+仓位**）+strategy.md → LLM 逐票建议（buy/hold/reduce/clear，reason/rules 必须引用规则号）；**引擎硬判定覆盖 LLM 输出**（跌破止损位→强制 clear；超仓位且 R81 适用→buy 保守改 reduce）；LLM 失败降级基础数据永不抛错；**建议是输出不是指令** |

### 7. 复盘 / 推送 / 交易日志

| 方法 | 路径 | 功能 | 说明 |
|:--|:--|:--|:--|
| POST | `/trading/review` | 生成交易复盘 | AI 基于当日记录+持仓+注入的交易知识/规则/行情生成五节模板正文，写 `data/.../trading/reviews/YYYY-MM-DD_review.md`；date 默认当天 |
| GET | `/trading/review` | 查询复盘 | 无 → 404 |
| GET | `/trading/reviews` | 列出复盘日期 | 倒序 |
| GET | `/trading/has-activity` | 检测交易活动 | 当日记录含买/卖/仓/股/交易等关键词；**⚠️ 唯一无插件门控的交易端点（见 §8 注意点 1）** |
| POST | `/trading/reviews/{date}/promote` | 复盘知识反哺 | 复盘+备注/章节 → 写 `os/trading-engine/99-inbox/YYYY-MM-DD_交易复盘.md`（原子写 tmp+move + **持仓数字脱敏**，防个人数据进 git 追踪的 os/）；需 trading-engine 工作流人工审核融合 |
| GET | `/trading/push-settings` | 推送开关（全量） | 8 类型：session/buy-point/stop-loss/near-stop-loss/loss/gain/break-cost/market；缺失默认开 |
| PUT | `/trading/push-settings/{type}` | 更新推送开关 | 未知类型 400 |
| GET | `/trading/trade-log` | 当日交易日志候选 | 截图/文字归集结果，**未落库待确认** |
| POST | `/trading/trade-log/confirm` | 确认交易日志落库 | 当日完整候选逐笔走 recordTrade 并清空；不完整候选跳过（前端引导补全） |
| PUT | `/trading/trade-log/date` | **补写候选成交日期（v3.32，2026-08-27）** | 缺日期候选行补 `tradeTime`（成交日），补全后允许确认落库（缺日期禁落库拦截） |
| DELETE | `/trading/trade-log` | **丢弃一条保留候选（B6-5，2026-08-23，P1-交易18）** | 逐笔丢弃当日候选（截图误识别/不想要的交易），幂等 |
| POST | `/trading/screenshots` | **截图入账（2026-08-26，交易闭环第一环）** | multipart 1-3 张成交截图 → VLM 识别 → 当日候选（表格批量解析 parseLooseBatch：已成/已报/申购过滤；跨截图 sameTrade 去重）；不建记录/不落原图/不沉淀记忆（`TradingScreenshotAppService`） |
| DELETE | `/trading/pushes/{id}` | **删除单条推送（B10-1，2026-08-23，P1-推送2）** | 删除持久化（app 左滑删）；不存在 404 |

### 6.5 交易规则（第三阶段：用户自己的交易系统，2026-08-30）

| 方法 | 路径 | 功能 | 说明 |
|:--|:--|:--|:--|
| GET | `/trading/rules` | 查询我的交易规则参数 | 16 参数（仓位上限/默认止损/浮盈回吐/短线超期/清仓阈值/买点 5 参/打分权重/硬约束区间）+ 元信息（hasRules/exists/来源 user\|default）；无规则 → 返回默认值（= adai 现状） |
| PUT | `/trading/rules` | 更新交易规则参数 | 部分字段覆盖，落 `data/{userId}/trading/rules.yaml`（YAML 真相源，SnakeYAML SafeConstructor fail-closed）；未知键保留不删；参数范围校验（fail-closed，NaN/越界 400） |

### 8. 导入 / 工具 / 管理

| 方法 | 路径 | 功能 | 说明 |
|:--|:--|:--|:--|
| POST | `/trading/imports/save` | 导入文件上传留存 | multipart；留存 `data/{userId}/trading/imports/{yyyy-MM}/`，GBK→UTF-8 转码，返回 {path, content} |
| GET | `/trading/lookup` | 代码查名称 | 腾讯行情单码查询，失败返回空串（前端可手填） |
| GET | `/api/v1/admin/trading/knowledge/conflicts` | 持仓 vs 规则冲突检测 | 从 rules.md 解析真实规则与当前持仓对比（空仓查 R119/R4、单吊查 R96 四不原则）；**需 X-Admin-Token**（admin） |

### 9. 完美买点案例（第四阶段，2026-08-30 环 1-2 最小闭环）

| 方法 | 路径 | 功能 | 说明 |
|:--|:--|:--|:--|
| POST | `/trading/cases` | 标注完美买点案例 | body `{symbol, buyDate, buyType?, description?, name?, labels?}` → 拉前 60 + 后 30 交易日 K → 特征画像 + 后验 → 落盘 `data/{userId}/trading/cases/{buyDate}_{symbol}.json`；重复 400 / K 线失败 400（不落半成品）|
| GET | `/trading/cases` | 案例列表 | buyDate 倒序（id/symbol/name/buyDate/buyType/features/verify/aiInsight）|
| GET | `/trading/cases/{caseId}` | 案例详情 | `?kline=true` 附 90 根窗口日 K（前端画图重放）；不存在 400 |
| DELETE | `/trading/cases/{caseId}` | 删除案例 | 删案例文件 + 清单条目；不存在 400 |
| POST | `/trading/cases/{caseId}/insight` | **生成 AI 理解（环 3）** | LLM 读特征画像 + K 线统计 → 结构化「为什么这是完美买点」（summary/keyFeatures/confidence）→ aiInsight 落盘；LLM 失败 400 不落半成品 |
| POST | `/trading/cases/match` | **判定当下（环 4，核心价值）** | 当前标的形态 vs 案例库归一化相似度 Top 5（加权欧氏，date 可空=最近交易日）；空库 matches:[] 静默降级；相似度不覆盖规则硬判定 |

---

## 二、后端定时任务（交易日生效）

| 时间 | 任务 | 内容 |
|:--|:--|:--|
| 09:15 | 早盘计划 | 持仓+止损/买点+择时状态 → LLM 生成，失败降级结构化模板；**行情口径=上一交易日收盘（2026-08-30 用户反馈批）**：数据文本标注「昨收/昨日涨跌」+ 提示词写明口径，LLM 不再把昨日涨幅说成「今日」 |
| 12:00 | 午间跟踪 | 上午表现 + 破止损 |
| 14:50 | 尾盘建议 | 逐票 R66/R81 判定 + 复盘提醒 |
| 15:05 | 收盘账户自动更新 | 行情可得部分更新参考市值/当日盈亏/浮盈；**任一持仓缺行情则整体跳过不覆盖**（P1-交易3，防残缺市值覆盖总资产）；**2026-08-29（P2-交易33）跳过时推一条「账户今日未自动更新」行情提醒**（新股/停牌无昨收不再长期无感，受推送开关门控） |
| 15:10 | 收盘买点扫描 | 自选股正式 B1/B2 命中 → 「到买点了」推送（附信号文案）；**B1? 不推送**（只 web 信号列灰显） |
| 15:15 | 收盘交易日志确认 | 当日有归集候选 → 推「今日操作汇总，是否完整」；无候选静默 |
| 15:30 | **收盘小结（close-summary，2026-08-29）** | 当日成交笔数 + 破止损提醒 + 待确认候选提示推送（`close-summary` 类型，双端开关，受推送开关门控；汇总类次日 23:59 消失） |
| 每 30 分钟（10-11/13-15 点，首轮 10:00） | 行情异动轮询 | stop-loss（现价破止损位 R66 硬判定）/near-stop-loss（距止损≤2%）/loss（日跌≥3%）/gain（日涨≥5%）/break-cost（跌破成本线）；**批次级止损（RFC 20260825）**：某批次现价破它自己的止损（未设默认 −7% 兜底）→ 单独推「批次止损预警」带批次日期/成本（不跟底仓混，signature 带 lotId 独立去重）；同票同类当日去重、同股票多类型合并防刷屏；阈值 `adai.market.alert.*` 可配。**2026-08-30（用户反馈批）两修**：① 轮询时段 9-11/13-15 → 10-11/13-15——9:00/9:30 行情接口仍返回上一交易日收盘，旧数据冒充「今日」（生产 08-27 09:00 实锤「今日跌 -3.11%」实为前日跌幅）且按日去重签名被旧数据烧掉名额、盘中真触发反而不推；② 补法定节假日守卫（B5-1 残留：节假日撞工作日时 break-cost/止损类拿前日收盘价每天重推） |

> **节假日**：法定节假日（2026-2027 硬编码表）不推送——`TradingSessionPushService` 全部 7 个定时任务 + `MarketAlertService` 轮询（2026-08-30 补）均有 `isTradingDay` 守卫；周末由 cron MON-FRI 排除。

## 三、后端核心机制

| 机制 | 说明 |
|:--|:--|
| 规则引擎 | G-3 抽离的确定性判定层：止损 R66（现价<止损位→BREACHED；止损未设→R68 无据可判）、仓位 R81（占比>上限→OVER_WEIGHT，100 万以下适用）、rules.md 条目解析（`**R{n} 标题**` + `> 描述`）；建议引擎/时段推送/行情异动**三方共用同口径**（`TradingRuleEngine`/`DefaultTradingRuleEngine`）。**第三阶段（2026-08-30）按用户规则**：仓位上限/行为标注阈值/清仓 verdict/买点 5 参/打分权重/建议硬约束区间全部从 `data/{userId}/trading/rules.yaml` 读取（`GET/PUT /trading/rules` 可配），无规则 → 默认值 = adai 现状（P1-5 降级语义定稿） |
| 持仓占比口径 | R81 分母 = 总资产（持仓市值+现金，现金唯一真源 account.json）——修复单仓+大现金恒发 reduce（P1-交易4） |
| 手续费模型 | 佣金万 0.854（买卖都收，四舍五入到分，无最低 5 元）/ 印花税万 5（仅卖出去尾）/ 过户费万 0.1（仅沪市 6/9 开头）；BUY 摊薄成本价含费 4 位小数；五笔券商交割实例反推确认（`CommissionCalculator`） |
| 交易日志自动归集 | 截图（VLM 识别）/文字（「清仓了XX」宽松解析）→ 当日候选去重（同 symbol+direction）→ 未落库待确认 → 确认后走 recordTrade；拒绝归集 unknown 占位（P1-1 已修） |
| 成交时间采集（RFC 20260822） | 逐笔流水加 `tradeTime`（成交时刻 HH:mm:ss，可空）：历史成交导入解析通达信「成交时间」列；当日记录缺省落盘时刻时分；旧数据 null 兼容 |
| 当日复盘聚合（RFC 20260822） | `GET /trading/trades?date=` 返回 `{trades, daily}`：时段分桶（早盘 09:30-11:30 / 午盘 13:00-14:30 / 尾盘 14:30-15:00）+ 买卖笔数金额 + 首末笔时间——纯客观无 AI |
| K 线数据源 | **腾讯主源 → 东财探测兜底（2026-08-24 主源切换批）**：`adai.market.kline-primary` 默认 tencent——生产东财被限不再刷 500+/日 WARN；连续失败 3 次熔断 5 分钟（半开探测），按日缓存（`KlineService`） |
| 交易知识注入 | **第三阶段（D1）用户私有优先**：读 `data/{userId}/trading/knowledge.md`（有 → 只用自己的）；无 → **仅 owner（adai）回落** `os/trading-engine/knowledge/context/` 五份交付文件（identity/strategy/rules/mistakes/current.md），其他用户不注入交易知识（P1-3 防跨用户泄漏）；内容哈希缓存（`TradingKnowledgeSource`） |
| 行情上下文注入 | trading 场景：大盘指数（上证/深证/创业板）+ 持仓行情表；globalContext 全场景短版（`MarketContextContributor`） |
| 推送渠道 | PushChannel 插件化：FeedPushChannel（落盘 `trading/pushes/{date}.json` 进 Feed）+ BarkPushChannel（iOS 原生推送，2026-08-25 起生产启用，免费无限条数）；WeChatPushChannel（Server酱）已停用（免费 5 条/天不够，代码保留未配置即禁用） |
| **批次推导（RFC 20260825）** | 批次 = 同标的+同方向+同日合并（一天最多一个买批，成本=当日加权平均含费）；**纯流水重放推导不落盘**；卖出 **LIFO** 先扣最近批次、跨批分算已实现盈亏；批次剩余 0 = 关闭（回合 realizedPnl）；positions.md 覆盖不到的底仓 = 初始批次（`_INIT`）；批次止损未设按默认 **−7%** 兜底可后改；行为标注六类（亏损加仓/追高/短线新开/破止损未走/浮盈回吐/短线超期）记录即标注进每日操作总结 |
| **推送定时消失（RFC 20260825）** | `pushes/{date}.json` 记录加 `expiresAt`：行情类（stop-loss/near-stop-loss/loss/gain/break-cost/market/session/buy-point）**次日 09:30 消失**（收盘后晚上仍可看，次日开盘前自动清），汇总类（每日操作总结/复盘）**次日 23:59** 消失；读取侧过滤过期，用户无需手动删时效推送 |
| 并发安全 | 每用户读写锁（tradeLock）串行持仓读-改-写，防并发交易互覆（REVIEW #147）；打分/买点扫描线程池并发拉 K 线 |
| 文件存储 | 持仓 `positions.md`（freeze §2.6）、逐笔流水 `trades/{yyyy-MM}.json`（按月）、自选/清仓 `watchlist.json`/`sold.json`、推送 `pushes/{date}.json`、推送开关 `push-settings.json`、交易日志候选 `trade-log/{yyyy-MM-dd}.json`（freeze §2.8-2.15） |

## 四、Web 管理端功能（adai-web，桌面）

> 页面：页头 6 入口（复盘历史/批量导入/推送设置/复盘/刷新/记录交易）+ 账户总览 stat 卡 + 更新时间行 + 七 Tab 工作区（持仓/自选/清仓/资金/历史成交/规则/**案例**）。实现：`apps/adai-web/lib/pages/trading_page.dart`、`apps/adai-web/lib/utils/trade_import_parser.dart`。
> **2026-08-23（RFC 20260823）**：交易历史 Dialog 升级为常驻第 5 Tab「历史成交」（页头 receipt 按钮 + `_HistoryDialog` 移除）；导入语义加「缺失成交时间回填」（`updated` 计数）。

| 区块 | 功能 | 操作 | 端点 |
|:--|:--|:--|:--|
| 账户总览 | 8 张 stat 卡（总资产/可用/可取/参考市值/当日盈亏/总盈亏/持仓浮盈/持仓数），金额千分位 + FittedBox 防溢出；红涨绿亏；总盈亏=资产−本金（本金>0） | 进页自动加载；「点击更新」手动刷新 | GET `/trading/account`（券商口径优先，assets>0）；GET `/trading/portfolio` 兜底 |
| 持仓列表 | 12 列 DataTable（代码/名称/数量/成本/现价/市值/盈亏/盈亏%/止损/买点/角色/操作），红涨绿亏、横向滚动 | 自动加载；行尾「编辑」→ 弹窗改角色（8 组合）/止损/目标价 → 保存；**行尾「批次」（RFC 20260825）→ 批次明细弹窗**：每批 日期/剩余/成本/现价/盈亏/止损/距止损%/买点/状态（初始底仓/持有中/已清仓-回合盈亏）+ 流水对账不一致警告 | GET `/trading/positions`、PUT `/trading/positions/{symbol}`、GET `/trading/lots` |
| 记录交易 | Dialog：代码 300ms 防抖查名、买入默认止损 = 价格×0.93（−7%，2026-08-17 设定）、买点 8 类型下拉（B1/B2/B3/SB1/暴力特噗/深水炸弹/单针/其他）、SELL 不填止损/买点 | 页头「记录交易」（先整体刷新再弹窗）→ 提交 | POST `/trading/trades`、GET `/trading/lookup` |
| 批量导入 | 三种格式自动识别：① 交易 CSV ② 通达信持仓（全量覆盖）③ 通达信历史成交（幂等补流水+对账提示）；支持文件上传留存或粘贴；逐条成功/失败结果（带行号+人话原因） | 页头「批量导入」→ 粘贴/选文件 → 导入 | POST `/trading/imports/save`、`/trading/trades/batch`、`/trading/positions/import?replace=true`、`/trading/trades/import` |
| 自选股 Tab | 表格（代码/名称/行业/长中短形态/指标提示/买点信号/删除）；「金叉」红色高亮；买点信号「{类型} {score}%」红色徽标；删除带确认弹窗；**案例相似度参考（环 4 二期，开关默认关）**：买点信号「case」+ 相似案例提示 | 「导入自选」→ 粘贴通达信自选导出；行尾 × → 确认删除 | GET `/trading/watchlist`、POST `/trading/watchlist/import`、DELETE `/trading/watchlist/{symbol}`、GET `/trading/buy-points` |
| 清仓 Tab | 表格（介入→清仓/天数/持仓期涨幅/规则对照/买点分/执行分/总分/心理标注）；纪律统计条（总/盈/亏 + 违 R66/R53 计数 + 胜率% + 纪律遵守率%）；行为模式关键词聚合（追高/恐慌割肉/贪心没走/套牢死扛/犹豫错过/急躁操作，「不贪」否定排除）；三维打分列（≥70 蓝/≥50 紫/否则灰）；心理标注弹窗 | 「导入清仓」；点「＋ 标注心理」→ 输入 → 保存 | GET `/trading/sold`、POST `/trading/sold/import`、GET `/trading/sold/score`、PUT `/trading/sold/{symbol}/psychology` |
| 资金 Tab | 现金/总资产概览；转入/转出（金额+备注）；导入资金（现金+精确成本）；本金设置（累计净投入）；说明「现金是 R81 判定分母（总资产=持仓+现金）」 | 「转入」(绿)/「转出」(橙)/「导入资金」/「设置本金」弹窗 | POST `/trading/transfer`、POST `/trading/imports/cash`、PUT `/trading/principal`、GET `/trading/account` |
| 复盘生成 | AI 生成今日复盘 → markdown 弹窗（可滚动/可选中）；生成中按钮转圈禁用；「反哺入库」按钮 | 页头「复盘」 | POST `/trading/review`、POST `/trading/reviews/{date}/promote` |
| 复盘历史 | master-detail：左日期列表 + 右 markdown 内容 | 页头日历按钮 → 点日期切换 | GET `/trading/reviews`、GET `/trading/review?date=` |
| 历史成交 Tab | **RFC 20260823：常驻第 5 Tab（取代页头交易历史 Dialog）**——日期范围查询（默认近 30 天，DatePicker 改日期自动重载）；按日分组列表（日期+笔数，未标注日期置底）；**列（2026-08-25 用户拍板）：源文件原生在前——方向/时间 HH:mm/代码/名称/数量/价格/成交金额/发生金额（买入为负扣款）/成交编号；系统计算的「费用」=｜发生金额−成交金额｜单独放最后区分开**；止损/买点/原因三列已删（历史成交源文件无此数据）；区间统计行「共 N 笔 · 买 X 卖 Y」；旧数据无时间/费用/成交编号显示 '—'；导入后 inline 展示结果（新增/回填/跳过/非交易 + 对账行） | 进 Tab 自动加载 + 手动刷新（无定时轮询）；行首「导入历史成交」 | GET `/trading/trades?from=&to=`、POST `/trading/trades/import` |
| 历史成交导入 | **独立入口（RFC 20260823：只认通达信历史成交导出格式）**——粘贴或选文件，`isTdxHistoryExport` 识别；非历史成交格式人话拒绝不静默落零；幂等 + 缺失成交时间回填；**RFC 20260825：响应含 `syncMode` + `summary`**——sync 模式展示「今日操作总结」卡片（买 X 笔 ¥Y · 卖 X 笔 · 新增/扣减批次 + 行为标注列表，亏损加仓/追高等醒目色）；append 模式提示「已按历史补录处理（只补流水，持仓未动）」 | Tab 内「导入历史成交」按钮 → 粘贴/选文件 → 导入 | POST `/trading/imports/save`、POST `/trading/trades/import` |
| 推送设置 | 8 个推送开关（时段节奏/买点/止损/接近止损/大跌/放飞/破成本/行情条）；缺失 key 默认开；仅请求成功更新本地状态 | 页头铃铛按钮 → 拨动 Switch | GET/PUT `/trading/push-settings[/{type}]` |
| **案例 Tab（第 7 Tab，2026-08-30 第四阶段环 1-2）** | 完美买点案例列表（名称/日期/买点类型/+5d 后验/特征摘要）+「标注案例」按钮（代码+日期+类型+描述 → POST）+ 详情弹窗（**K 线图还原**：主图蜡烛 + MA10 白线 + MA60 黄线 + 买点日标记，副图成交量 + KDJ/MACD——指标前端从 OHLCV 重算，口径对齐后端 CaseFeatureExtractor）+ 特征 chips + 后验 chips + 删除确认 | GET/POST `/trading/cases`、GET/DELETE `/trading/cases/{caseId}?kline=true` |
| **规则 Tab（第 6 Tab，2026-08-30 第三阶段）** | 我的交易规则参数展示（16 参数中文标签：仓位上限/默认止损/浮盈回吐/短线超期/清仓阈值/买点 5 参/打分权重/硬约束区间）+ 编辑弹窗（表单化 PUT）+ 加载失败降级（显示默认值） | 点「规则」Tab → 行内编辑 → 保存 | GET `/trading/rules`、PUT `/trading/rules` |

**导入解析规则（前端 `trade_import_parser.dart`）**：

- **交易 CSV**：逗号分隔（兼容中文逗号）、表头行自动跳过、方向归一化（买/BUY/买入→BUY，卖/SELL/卖出→SELL）、价格/数量>0、BUY 必填止损与买点（白名单校验）、错误逐行收集带行号不整批失败
- **通达信持仓导出**：识别特征「证券代码|代码」+「成本价|成本」；表头列定位（版本差异容忍）；制表符/连续空格分隔；`#` 注释行跳过；代码 6 位；语义 = 当日券商口径快照 → 全量覆盖导入
- **通达信历史成交**：识别特征首有效行含「成交日期」「证券代码」「买卖标志」「成交编号」；解析在服务端（前端只传 content）
- **自选/清仓/资金股份查询**：前端不解析，仅传文本给后端

**交互细节**：三层刷新（30 分钟定时 + 切页刷新 + 手动）；静默刷新失败保留旧数据仅 toast（仅首载失败整页错误页）；可降级请求 `_auxGen` 代际令牌防乱序覆盖；打分空列表短路 + 防重叠；删除自选有确认弹窗、其余写操作靠自然语言回执 SnackBar（「买入 100 股 贵州茅台，已记下」）；错误透出优先后端 `{"error":"人话"}`。

## 五、手机 App 端功能（adai-app，iPhone）

> 页面：单列滚动列表（账户总览卡 → 复盘横幅 → 记录区 → 持仓区），无 tabs。实现：`apps/adai-app/lib/pages/trading_page.dart`。入口：Launcher「交易」行（trading 插件门控）。
> **2026-08-22 精简**：移除「自选股·买点信号」「清仓复盘」两个只读区块（管理归 web，能力不删——买点提醒由 15:10 推送覆盖，完整清仓复盘在 web）。

| 区块 | 功能 | 操作 | 端点 |
|:--|:--|:--|:--|
| 账户总览卡 | 总资产/总盈亏（券商口径优先 `assets>0`，失败退回组合快照）；总盈亏=资产−本金（本金>0）；可用/可取/市值/当日盈亏/本金小字指标行；金额 ≥1 万显示「X.X万」 | 进页自动加载；AppBar 刷新按钮 | GET `/trading/account`、GET `/trading/portfolio` |
| 今日交易复盘 | **RFC 20260822**：账户卡下方「今日 N 笔 · 买 X 卖 Y · 早盘 n · 午盘 n · 尾盘 n · 首末笔时间」一行（纯客观数字；无成交/失败静默不显示）| 自动加载（随主数据刷新）| GET `/trading/trades?date=` |
| 复盘横幅 | 有交易活动 → 「今日有交易 · 生成今日复盘？」；生成后变「今日复盘已生成 ✓」+查看；弹窗右下「反哺入库」；右上关闭仅本会话收起；AppBar 另有被动「复盘」图标入口 | 横幅「生成复盘」/AppBar 图标 | GET `/trading/has-activity`、POST `/trading/review`、POST `/trading/reviews/{date}/promote` |
| 记录区 · 通道 A | 一句话输入（hint：「买了 1000 股京东方 @5.2」）→ parse 回显确认卡（方向徽标+标的，可改数量/价格/方向）；**parse 返回的止损/买点不再回填**（2026-08-18 简化归 web）；matched=false → SnackBar 人话 + 自动展开精确表单（无死路） | 输入 → 「解析」→ 「确认记录」/取消 | POST `/trading/trades/parse`、POST `/trading/trades` |
| 记录区 · 通道 B | 精确表单（标的/价格/数量）+ **隐藏式止损/买点**（2026-08-22：非必填，默认收起「止损/买点（可选）」，展开后止损自动带默认 −7% 可改可清、买点下拉可空选 B1/B2/B3/SB1/暴力特噗/深水炸弹/单针/其他；收起态不预填不发送）+ 底部双按钮「买入(红)」「卖出(绿)」——方向由按钮承担不可能漏选；价格键盘带小数点（A 股 4 位成本价精度） | 「精确填写」展开 → 填 → 点买/卖 | POST `/trading/trades` |
| **截图入账（2026-08-26）** | 交易页「📷 截图入账」入口 → 选 1-3 张成交截图 → VLM 识别 → 当日候选内嵌列表（逐笔确认/丢弃，带方向/标的/数量/价格/成交日期；缺日期候选行警示「补日期」）；**确认前拦截缺日期候选**（v3.32，需先补日期才能确认落库） | 「📷」→ 选图 → 逐笔确认/丢弃 | POST `/trading/screenshots`、PUT `/trading/trade-log/date`、POST `/trading/trade-log/confirm` |
| 持仓区 | 只读资产卡（名称+代码 | 盈亏大字 | N股·成本·现价·止损（未设止损橙色提示）| 盈亏%）；表头「管理」链接去 web；空态引导记录/去 web 导入；**批次简版（RFC 20260825）**：第三行「N 个批次 · 最近买入 M/d」+「含底仓」绿徽标（open 批次含 initial）+「有批次破止损」darkOrange 警示（stopLossDistancePct<0）；无批次数据/拉取失败静默降级不显示该行；**批次明细弹窗（2026-08-28）**：点批次行 → 每笔买入的日期/状态徽标（初始底仓/持有中/已清仓）/剩余·成本·现价/盈亏+盈亏%（开放批次浮动 pnlPct、已清仓回合收益率前端算）/破止损橙色警示，连点守卫防叠两层 | 点按整张卡 → 弹「阿呆说」；点批次行 → 批次明细弹窗 | GET `/trading/positions` + GET `/trading/lots` |
| 阿呆建议弹层 | **app 独有核心交互**：标题「阿呆说 · 名称」+ summary 气泡 + 逐票建议卡（动作徽标 买入红/减仓清仓卖出绿/持有蓝）+「查看建议依据」展开规则号列表；按 symbol 精确匹配（后端无 symbol 按名称兜底）；无匹配显示「这只暂时没有特别要说的」；加载失败可重试；底部「管理持仓（去 web）」；**无任何执行按钮** | 点持仓卡 | POST `/trading/advice` |
| 去 web 引导 | 三处统一入口（持仓空态/持仓表头「管理」/建议弹层底部）：「详细管理去电脑端」——批量导入、持仓编辑、历史明细、K线在电脑端；手机端负责日常记录和阿呆建议 | 点引导链接 | — |

**自动刷新**：30 分钟定时器（initState 起，dispose 取消）+ 页面进入（整页 loading）+ AppBar 刷新按钮 + 交易成功后即时静默刷新（复盘横幅立即重现）；**无下拉刷新**。分层刷新：主数据（positions/portfolio）失败 → 整页错误态；次级数据（account）失败静默保留旧值（账户卡退回组合快照口径）；次级数据 `_auxLoading` 锁 + `_auxGen` 代际令牌防乱序。

**校验（前端人话提示，后端铁律兜底）**：标的非空且格式 6 位数字或 2-6 个汉字/字母；价格必填>0 且 ≤1e7；数量整数>0 且 ≤1e7；卖出预检未持有/超持仓；提交中按钮禁用防双击；A 股红涨绿跌贯穿（盈利/买入=红，亏损/卖出=绿，持有建议=蓝）。

## 六、双端能力对照

| 能力 | App（手机端） | Web（adai-web） |
|:--|:--|:--|
| 持仓 | 只读资产卡，点卡弹「阿呆说」建议 | DataTable + 逐行「编辑」Dialog（PUT /positions/{symbol}） |
| 记录交易 | 标的/价格/数量/方向 + **隐藏式止损/买点**（非必填，展开后止损默认 −7% 可改可清、买点可空） | 止损位/买点类型/目标价/原因（默认止损=买入价−7%） |
| 批量导入 | 无（引导去 web） | 交易 CSV / 通达信持仓（全量覆盖）/ 历史成交（补流水+对账），GBK 转码 |
| 交易历史 | 无（主页推送卡有「确认并入账」） | 交易历史 Dialog（按日分组明细） |
| 截图入账 | **app 独有**：📷 选图 → VLM → 候选逐笔确认/丢弃（含补日期） | 无（历史成交导入替代，只认通达信导出） |
| 规则配置 | **无**（用 adai 默认规则 = 现行为） | **web 独有**：规则 Tab 16 参数展示 + 编辑弹窗（GET/PUT /rules） |
| 复盘 | 仅「今日」复盘（生成/查看/反哺入库） | 另有复盘历史 Dialog（日期列表 + 任意日期） |
| 自选股 | **无**（2026-08-22 移除区块；买点提醒靠 15:10 推送） | 可导入 + 删除单只（带确认）+ 买点信号列 |
| 清仓股 | **无**（2026-08-22 移除区块） | Tab 分区 + 导入 + 行为模式统计 + 三维打分 + 心理标注 |
| 资金管理 | 仅账户卡展示 | 资金区块：转入/转出/本金设置/资金导入 |
| 推送设置 | 不在此页（在主页 Feed 推送卡：左滑删/右滑开） | 本页页头入口（8 开关） |
| 建议引擎 | **app 独有**：点持仓卡弹建议弹层 | 未见对应建议弹层入口 |

## 七、交易知识底座（os/trading-engine）

- **消费入口（第三阶段 D1 定稿）**：**用户私有优先**——`data/{userId}/trading/knowledge.md`（知识注入唯一消费；无则仅 owner/adai 回落 os/）；os/ 五文件是 adai 规则包的**源材料**（`09-scripts/sync-adai-rulepack.sh` 合并同步到 `data/adai/trading/knowledge.md`）
- `knowledge/context/` 五文件（identity/current/strategy/rules/mistakes）——adai 课程沉淀（87 课）交付层；rules.md 收录 **R1-R120**（择时 R1-R20/选股 R21-R32/买入 R33-R50/应对 R51-R65/止损 R66-R80/仓位 R81-R95/纪律 R96-R120）
- `engine/rules-api.md` + Java `TradingRuleEngine`——语言无关规格 + 实现，判定口径一致（止损 R66 现价口径、R81 仓位分母=总资产含现金）
- `engine/buy-point-rules.md`——B1/B2/B3/SB1 买点判定规格（C2 草稿，待用户确认口径）
- 反哺闭环：复盘 → `99-inbox/` → trading-engine 工作流人工审核融合（adai-core 只写 inbox 不自动入库）

## 八、实现状态注意点（代码为准）

1. **`GET /trading/has-activity` 无插件门控**：代码未调用 `requireTradingPlugin`（其余 41 个交易端点均有）——**唯一例外（2026-08-23 api-spec 已显式标注）**，产品路径只读（app 复盘横幅）
2. **`TradingContextContributor` 实际未生效（半成品/死代码）**：`supports()` 恒 false、`enrich()` 恒空串；交易系统状态上下文实际由 `MarketContextContributor`（globalContext）+ `TradingKnowledgeSource` 提供
3. **「三维打分」实为二维**（REVIEW S7）：选股维度恒 null，总分 = 买点×0.5 + 执行×0.5（权重按用户规则，默认 0.5/0.5）
4. **~~C2 买点 5 参数构造器硬编码~~ 已修复（2026-08-30 第三阶段 Step 5）**：买点 5 参（回调 0.5/缩量 0.7/KDJ.J<13/放量 1.5/前高 20 日）从 `data/{userId}/trading/rules.yaml` 读取（无规则用默认）；规格 `os/trading-engine/engine/buy-point-rules.md` 已按代码事实重写（2026-08-23，P2-交易17 虚标纠偏）
5. **Position 无 targetPrice 落盘字段**：PUT `/positions/{symbol}` 只支持 role/止损位；前端「编辑目标价」无效功能（P3）
6. **recordTrade 现金推导依赖已有账户快照**：`update(...).orElse(null)`——首次交易前未导入资金（无 account.json）时现金/市值不更新
7. **行情异动推送新旧两条链路并存**：`MarketAlertService` 直接走 PushChannel；另有 `FeedPushChannel` 落盘 `trading/pushes/{date}.json` 供 Feed 展示
8. **历史成交导入「只补流水」是设计取舍**：不重算持仓/现金（缺窗口前基线，回放重建算不出券商口径），对账只报告不改数据；2026-08-23 起幂等命中且旧记录缺成交时间时回填（`updated` 计数），仍不重算持仓/现金
9. **节假日表硬编码**（2026-08-23 B5-1 补全）：2026 按沪深交易所官方通知、2027 预测（官方通常年底发布），临时调休不追
10. **推送/流水写入均为 best-effort**：失败只告警不阻塞交易落库；流水文件损坏单月跳过；account.json 写失败已升 error 告警（B3-4）
11. **双锁体系（C6，2026-08-23 注释如实化）**：account.json 写路径叠加 application `tradeLock`（业务 RMW）+ repository per-user 锁（文件原子写）——均为**单实例内**进程锁（多实例同写 data/ 即失效，当前单实例）；跨文件一致性（positions/account/流水）无原子手段，收盘更新与交易并发窗口为已知取舍
12. **推送链路（2026-08-23 修复）**：推送标题契约断裂（P1-推送1）/删除持久化（P1-推送2）/app 设置入口（P1-推送3）均已修——MarketPushEvent 透传 title、`DELETE /trading/pushes/{id}`、app 交易页铃铛；徽章/确认按钮双端回归

## 九、已知缺陷（详见 docs/review/REVIEW.md）

- **P1-交易1~10**：切入自动刷新死代码、recordTrade 只动现金不动市值、收盘残缺市值覆盖、占比分母、导入解析静默落零、CURRENT_MD 硬编码路径、六请求 Future.wait 整页错误、清仓打分错挂、B1 几何语义漂移、buy-points 响应示例不符——**前 9 项已修复出表，P1-交易9（B1 几何语义）用户搁置**
- **P1-推送1~3**：推送标题契约断裂、推送删除无持久化、app 推送设置入口 self-lock——**已修（2026-08-23 推送链路批 B9-B11，见 change-log）**
- **P2-交易1~23**：线程池无 shutdown、买点扫描未并发、腾讯兜底无缓存、现金双源不同步、verdict 阈值、KDJ 阈值漂移、B1? 同通道推送、mounted 守卫、buy-points 致命路径、打分无去重、纪律遵守率实为胜率、行为模式误配、快捷操作无错误处理、8 卡溢出、打分列颜色冲突、买点参数无配置接线、文档状态漂移、api-spec 变更记录缺版本行、account 节过时、guard-align 盲区、建议硬判定未过 r81Applicable、importPositions 缺校验——**2026-08-23 走查修复批已出表多数（B3-2 尾盘 r81Applicable、B3-3 收盘昨收残缺、B3-5 遵守率 R53、B4-1/2 文档虚标纠偏、B5-1~6），P2-交易9 几何语义随 P1-交易9 搁置**
- **P2-推送4~6**：8 类型徽章退化、推送设置反馈假阳性、session 开关文案连带——**已修（2026-08-23 B9-5/B11-2/B11-3，双端对齐）**
- **第三阶段（2026-08-30）出表**：S6（买点 5 参构造器硬编码）→ 已修（用户规则可配）；P1-5 降级语义定稿（无规则 = 默认值兜底，行为不空）；P1-3 多用户知识泄漏 → owner 白名单收窄
- **#179 零鉴权**：X-User-Id 无认证（数据访问靠 header 注入）
- **2026-08-23 修复批新增**（REVIEW 已修复区）：confirm 失败清空候选（P0-1）、account.json 写锁（P0-2）、direction 无校验（P1-1）、batch 无校验（P1-2）——全部已修

## 十、完美买点案例库（第四阶段，2026-08-30 环 1-2）

> 定位与详细设计：`docs/rfc/20260830-trading-perfect-case-library.md`（方向）+ `docs/architecture/trading-case-library-design.md`（设计）。**案例是手段，判定当下是价值**——修复 S7（「完美图匹配度」从自称变为样本库支撑）。

**四环链路（当前实现环 1-2）**：

```
环 1  一句话标注（symbol + buyDate [+ 买点类型/描述]）
环 2  ✅ 自动拉 前60+后30 交易日 K → 特征画像 + 后验窗口 → 落盘 JSON → web 画图（P2 批）
环 3  ✅ LLM 理解（aiInsight 生成 + 落盘，2026-08-30 完成；≥20 案例归纳后置）
环 4  ✅ 判定当下（特征归一化 + 相似度引擎 + match 端点，2026-08-30 完成；15:10 扫描接入二期）
```

**特征画像（9 项，全部从 OHLCV 重算，标准化相对值）**：距前 20 日最高收盘回撤 % / 量比（3 日均 ÷ 5 日均）/ KDJ.J + 金叉 / MACD 柱 + 金叉 / MA 关系（close vs MA20/MA60）/ 距 60 日线 %（黄线近似）/ 黄白线态（touch/near/above/below + 白线在黄线之上=开门）/ 盘整天数 / 破前高。黄白线语义依据课程（黄线≈主力成本线≈接近 60 日线）；公式源码待用户提供（P4 精确化）。

**后验窗口**：+5/+10 日收益、买入后最大回撤、是否破默认止损 −7%（缺数据 → null，标注照常成功）。

**标的范围（D4 用户拍板「不要全市场」）**：标注/详情任意 A 股（数据源全 A 覆盖）；判定当下每日自动仅自选股+持仓（P3 接 15:10 扫描），手动 match 任意代码。

**双轨判定（P3）**：规则引擎（B1/B2 参数，确定性基线）+ 案例相似度（经验增强）独立判定、独立降级；相似度不覆盖止损/仓位等硬判定。

---

> **维护说明**：端点契约变更 → 同步 `api-spec.md`；功能数字（测试数/端点数）以 `status.md` 为准，本手册不复制数字；新增功能 → 本手册 + `feature-reference.md` §9 + `change-log.md` 同步。
