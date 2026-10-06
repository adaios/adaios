---
title: 设计审核 v2 · 交易插件重做（code-backend-reviewer）
description: 对设计稿 v2 的后端视角审查（设计 × 现状代码对拍）——无 P0；战略级 2（关插件闸门清单漏 kernel Record/Feed 面 · §9 被定为验收基线却未逐端点核对代码）· P1×6 · P2×6 · P3×5；结论：不收敛
version: 1
created: 2026-10-06
updated: 2026-10-06
status: active
lines: 139
depends-on: []
related:
  - ./design-v2-20261006.md
  - ./review-v1-20261006.md
  - ./requirement.md
  - ./decisions.md
  - ./LEDGER.md
  - ../../knowledge/reference/contracts/data-format-freeze.md
tags: [workspace, design, review, backend, trading]
---

# 设计审核 v2 · 交易插件重做（code-backend-reviewer）

## 一、审核对象与方法

- **对象**：`design-v2-20261006.md`（415 行）。
- **方法**：设计 × **现状代码实测**（只读，未改任何文件）。本分支**无后端代码改动**（`git diff --name-only main...HEAD -- services/` 为空），故本轮不是 diff 审查，而是「设计承诺 vs 代码现状」对拍。
- **对拍物**：需求定稿 · review-v1 · design-decisions · `trading-batch1.md` · `.agents/knowledge/reference/contracts/data-format-freeze.md`（数据格式冻结契约）· `services/adai-core/src/main/java/com/adaiadai/core/**`。
- **口径**：凡标「实测」的都有 `文件:行号`。未标的位置以设计稿行号为准。

## 二、结论

**无 P0**（本轮无代码变更）。**战略级 2 · P1×6 · P2×6 · P3×5 → 不收敛**（阻塞项为 2 条战略级）。

> 触发技能清单逐条判定：数据安全 **FAIL**（P1-2/P1-3/P1-4/P1-6）· 分层依赖 **PASS**（§1 依赖方向清楚，仅 1 处包位置与归属不符 → P3-2）· 数据流闭环 **FAIL**（P1-5）· 健壮性 **FAIL**（P2-1/P2-2）· 插件门控 **FAIL**（S1）· 测试覆盖 **FAIL**（P2-5）。

## 三、战略级

**S1 · 「关插件即过滤」的闸门清单漏掉最大的 kernel 面：Record / Timeline / Feed / 记忆**

- 位置：design §8.2 第 275 行（闸门只写「kernel 待办 `source=trading`」）· §11.3 第 327 行（"账与理解都不注入 + 不推送"）· §11.4 第 329–335 行（只列 账/理解 · 次日计划 · 对话动作→待办）；实测 `application/TradingAppService.java:830-856`（`writeTradingRecord`，第 850 行以 `domain="trading"` 写 kernel Record）· `application/FeedAppService.java:316-331`（`tradeDirection` 认 `domain=trading` 的 Record 作成交折叠，**无插件门控**）· `FeedAppService.java:425-440`（该 Record 原样进 FeedEntry）
- 问题：现状**每笔当日成交都会向 kernel 写一条 Record**（标题=「买入 京东方A 1000股@5.20」），Feed / 时间线 / 记忆都消费它。`FeedAppService` 只对**行情条**（196–207 行）与 **push 条**（208–222 行）做了插件门控，**record 条目没有任何门控** → 插件关掉后，历史与残留交易仍会出现在 Feed/时间线里。这与 §11.3 的承诺（"关了插件阿呆不再提你的交易"）**直接冲突**，也正好命中 review-v1 自己提的 **P30**（"必须同时声明 kernel 侧残留面 todos / **Feed** / 推送"）——v2 只落了待办那一面。
- 建议：把「关插件闸门」从「待办 + 推送」扩到**面清单**并逐一给判据：`Feed`（record 条目按 `domain` 过滤）· `Timeline` · `记忆/ContextEngine`（既有 policy 是否已按插件过滤需实测）· `推送` · `待办`；同时在 §8 增一行「残留面 → 过滤点 → 判据」表。**这是 U1 渐进重构的验收前置**（不然"关插件"承诺落不了地）。

**S2 · §9 被定为「验收基线」，但其依据是 overview 快照而非代码对拍——基线不可靠等于静默丢功能**

- 位置：design §13 第 364 行（"**§9 去留表为验收基线**"）· §9 第 279 行（自述"粒度到组，**未逐端点核**"）· 自评风险 1（第 406 行）
- 问题：U1=渐进重构，**§9 是唯一的功能保留清单**；而它来自 overview §2 的分组总览（`trading-plugin-overview.md:84-100`）。实测交易端点 **78 个**（`TradingController` 60 + `TradingCaseController` 7 + `TradingPlanController` 5 + `TradingEvidenceController` 3 + `EquityCurveController` 2 + `TradingRoundController` 1），§9 只有 **21 行**、且**漏掉整组**（见 §七对拍表）。基线漏项 = 实施期"按表替换"时静默删掉已上线能力。
- 建议：合并前把 §七 的差异表并进 §9，逐条补「保留 / 替换 / 删除 + 理由」；并给"删除"类加**反向判据**（该能力有测试/有生产调用/有前端入口 → 不许默认删）。

## 四、P1

**P1-1 · §8.1 对 `TradingContextContributor` 的事实判断错误：它不是死代码，「删除」会静默砍掉一个正在生效的隐私重注入点**

- 位置：design §8.1 第 264 行（"**死代码**（`supports()` 恒 false、`enrich()` 恒空）→ **删除**"）；实测 `domain/trading/TradingContextContributor.java:54-101`（`globalContext()` **活的**：输出"## 交易系统状态" + 逐票「现价/成本/收益」+ 第 96–99 行「总市值/浮动盈亏/**现金余额**」）· `kernel/plugin/PluginRegistry.java:55`（该类映射为 trading 插件 → 被 `ContextEngine` 正常门控注入）· `ContextEngine.java:635-662`（globalContext 在**所有场景**被调用）
- 问题：`supports()` / `enrich()` 确实空，但 `globalContext()` 是全项目**注入金额最重**的一处（成本、总市值、浮动盈亏、现金余额）。设计的两个结论都建立在错误前提上：①"删除无据可依"的依据是错的（有据：它输出金额）；②真正的风险不是"静默留着"，而是"**以为它什么都没做**"。
- 附：删除需同步 `kernel/plugin/PluginRegistry.java:55` 的 switch case，与两个测试类（`src/test/.../PluginRegistryTest.java:4,55`、`TradingContributorsCashSourceTest.java:19,52,65`），否则编译红/死分支。
- 建议：改为「**删除**（理由：与 `MarketContextContributor` **输出同一标题**且携带金额，违反 §8.2 脱敏）」+ 列出需同步改的文件（含测试）。

**P1-2 · §8.2 / §11.1 的脱敏口径与现状注入实现直接冲突，但「保留 · 改造」没把这件事写成改造项**

- 位置：design §8.1 第 263 行（`MarketContextContributor`「保留 · 改造」）· §8.2 第 273 行（"**数量 / 金额 / 成本价不出**"）· §11.1 第 320–322 行；实测 `domain/trading/MarketContextContributor.java:132-176`（`appendPortfolio`：第 144–145 行的表头就是「代码 | 名称 | **数量** | **成本价** | 现价 | **市值** | **盈亏** | 盈亏%」，第 180–183 行再补「总市值 / 浮动盈亏 / **现金余额**」）· `supports("trading")` 为 true（第 42–44 行）→ 该表在 `scene=trading` 时**整表进 prompt**
- 问题：需求「AI 上下文：数量/金额/成本价都不出」+ 验收 9 是硬约束，而现状**整表出**。设计把该贡献者标为"保留·改造"却只说"持仓行情（**短版**）"，没有把「去数量/去成本/去市值金额/去现金余额」列为**必须完成的改造项与验收点** → U1-B 分批替换时这段极可能原样留着。
- 建议：§8.2 增一行「贡献者 → 输出字段白名单（逐字段）」；把 `MarketContextContributor` 的改造写成可验收条目（"输出不得含 `quantity` / `avgCost` / 市值 / 现金余额"），并配测试。

**P1-3 · §10「promote 已脱敏」不成立：关键词白名单漏数量与金额，用户备注完全不过滤，而目标是 **git 跟踪** 的 `os/` 目录**

- 位置：design §10 第 311 行（"既有代码已对 promote 内容脱敏"）· §11.1 第 321 行（"分享 / 导出 / promote 默认剥离金额字段"）；实测 `interfaces/TradingController.java:1790-1801`（`sanitizeReviewContent` 只有 4 条正则：`持有N股` / `市值` / `现金余额` / `成本|现价|止损位|止损价`）· 第 1759-1782 行（`buildPromoteContent`：第 1763–1765 行 `request.note()` 用户备注、第 1768–1774 行 `request.sections()` **原样拼接、不过任何过滤**）· 第 1740 行（文件名）+ `data/adai/trading/imports` 同级——写入目标是 **`os/trading-engine/99-inbox/`（入库候选，进 git）**
- 问题：漏的形态都是真实复盘里的常见写法——「**卖出 500 股**」「**减仓 300 股**」「买回 200 股」（不是"持有N股"）、「**成交金额 5200 元**」；更严重的是**备注列**（用户会写"这笔本金是 XX 万"）原样落进 git 跟踪目录 → B25 家族（派生形态进已跟踪目录 = prompt/金额可被提交）。
- 建议：改为**结构驱动**（promote 的候选内容只由结构化字段渲染，正文过"数字→占位符"的通用脱敏 + 备注过同一过滤器），并加一条单测钉住"卖出 N 股 / 成交金额 X / 备注含金额"三种形态。

**P1-4 · §10 promote 写的是**全局共享**路径且文件名只带日期 → 定位扩容后同日互相覆盖（内容丢失）+ 非 owner 写入自己永不读取的知识库**

- 位置：design §10 第 307–309 行（保留 promote、受插件门控）· §8.1 第 266 行（`TradingKnowledgeSource`："**仅 owner 回落 os/**，其他用户不注入"）；实测 `interfaces/TradingController.java:1716-1747`：写 `os/trading-engine/99-inbox/` 第 1740 行 `fileName = date + "_交易复盘.md"`、第 1744–1745 行 `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)`；`kernel/knowledge/TradingKnowledgeSource.java:128-130`（owner 判定 `adai.plugins.owner-user-id`，默认 `adai`）
- 问题：① 路径**无 userId 维度**、文件名**只带日期** → 两个持 trading 插件的用户同日 promote 会**后写覆盖前写**；② 非 owner promote 的内容按 §8.1 永远不会被注入（连写的人自己也读不到）→ 用户内容静默丢弃；③ 现状只有一个 trading 用户（`data/accounts/accounts.json`），但 **S2「人人可用」正是本需求的定位变更** → 这条从"未来"变成"下一批"。
- 建议：promote 目标改 `.../99-inbox/{userId}/` 或文件名加 userId；非 owner 的 promote 要么 403 人话、要么落自己的 `trading/knowledge.md` 候选区（不给假成功）。

**P1-5 · §12 声称追溯无空缺，但 **R-01 的 app 半边（手动记一笔 / 截图入账）在设计里没有落点**，VLM 的格式契约与丢行上报也无承接**

- 位置：design §5 第 195 行（截图入账分流到 `POST /api/v1/records`，"域侧只被动消费 Record"）· §6 第 221–222 行（`R-01` app = 手动记一笔 / 截图入账）· §3（四条链）与 §9（去留表）**全无这条链**· §12 第 345 行把 `G-01`–`G-07` 一句带过；实测 `interfaces/RecordController.java:255-267`（记录落盘后 `tradeLogCollectService.collectDetailed(...)` 收当日候选 + `isTradeStatement`）· `interfaces/TradingController.java:1253/1269/1329/1352/1395`（`/trade-log` `/screenshots` `/trade-log/confirm` `/trade-log/date` `/trade-log/meta`）· `infrastructure/storage/TradeLogRepository.java`（`trading/trade-log/{date}.json`，契约见 `data-format-freeze.md:404-417`）
- 问题：现状链是「Record/截图 → **当日候选**（可确认/可编辑/可补编号）→ `/trade-log/confirm` → 落账」。设计只写了"走通用记录入口"，**没有写候选确认这一段**，§9 也没登记 `/trade-log*` 与 `/screenshots` 的去留 → 实施期按设计走会**只落记录不落账**，直接踩掉验收 1。另：截图→结构化字段的格式契约与降级上报（B74 / P0-交易53：一张 3 笔的截图只落 1 笔）在 v2 无任何承接。
- 建议：§3 补第五条链「记录/截图 → 候选 → 确认 → 账」，§5 端点表补候选确认端点，§9 补 `/trade-log*` `/screenshots` 去留，并显式引用 B74/B73（格式契约 + dropped 上报 + 前端可见）。

**P1-6 · §4.1 目录表与**现状 + 数据格式冻结契约**不符，而 U2 迁移依赖它**

- 位置：design §4.1 第 163–176 行；实测现状 `data/adai/trading/`：`trades/{yyyy-MM}.json` · `positions.md` · `watchlist.json` · `imports/{yyyy-MM}/{ts}_{name}`（`TradingAppService` 留存路径）· `transfers.json` · `market-stage.json` · `profile-v1.md` · `trade-log/` · `memory-cards/` · `cases/`；冻结契约 `data-format-freeze.md:169-195`（§2.6 positions.md）· `:364-389`（§2.13 `trading/trades/`）· `:404-417`（§2.15 trade-log）· `:418-447`（§2.16 rules.yaml）· `:460-501`（§2.17 cases/）· `:502-512`（§2.19 market-stage.json）· `:513+`（§2.20 advice-history）
- 问题：设计写 `ledger/`（现状 `trades/`）· `positions/`（现状 `positions.md`）· `watchlist/`（现状 `watchlist.json`）· `imports/save/`（现状 `imports/{yyyy-MM}/`）· `market/`（实为**非用户层** `data/market/`，`data-format-freeze.md:448`），**全文未引用 `data-format-freeze.md`**（B21 要求格式改动必须同步该文档）。U2=迁移 + 逐笔对拍，而"从哪读、写到哪"两处都没对齐 → 迁移期读错路径/写新路径是**真钱账丢失**的高危形态。
- 建议：§4.1 拆成两列「现状路径（冻结契约 §x）」+「目标路径」+「迁移方式（读旧写新/原地改名/不迁）」，并对每个改名登记 MINOR/MAJOR 与回滚。

## 五、P2

| # | 问题 | 位置 / 建议 |
|:--:|:--|:--|
| **P2-1** | **去重「指纹」的维度没定义**：§3① 只写"委托号 + 指纹双键"，未说指纹含哪些字段。B75 的教训是键少一维会**静默吞掉真实成交**（同标的同向各 100 股三笔被吞成 1 笔） | design §3① 第 107 行 · 建议写明 `symbol + 方向 + 价格 + 数量（+ 日期）`，并要求"委托号缺失时用指纹、且必须有唯一性测试（双向：重复可去、不同两条不互吞）" |
| **P2-2** | **并发与锁未声明**：新增/改造写入路径（`rounds/`、`ledger/.audit/`、`account.json`、`rules.yaml`）必须继承同文件 `save/delete` 同锁（B14/B57/B60）；现状 `accounts.json` 曾因跨用户无全局锁互覆（B55） | design §4 全节 · 建议 §4 加一条"写并发约定"（原子写已有：`LocalFileStorage.java:105-118`；**锁需逐文件声明**） |
| **P2-3** | **审计日志的失败策略未定**：§4.3 的 `ledger/.audit/` 是验收 4「每个数字点得进去」的支撑，但没写"审计写失败怎么办"。写失败静默继续 = 追溯断（fail-open） | design §4.3 第 180–186 行 · 建议明确 fail-closed 或"降级 + 可见告警"；并说明与既有 `trading/advice-history/`（`data-format-freeze.md:513`）的**两套留痕**边界 |
| **P2-4** | **导入顺序与 batch1 交接口径相反**：设计"事件在前、**快照最后**"；`trading-batch1.md` 交接的是"**快照在流水之前**（钱先于货）" | design §3① 第 115 行 vs `trading-batch1.md:43` 附近 · 建议统一并说明与既有锚定实现（锚定日取较晚者、日期只前进不后退 = B68）的关系，否则 U5「显式全量回放」会落在未统一的顺序口径上 |
| **P2-5** | **无测试口径**：设计通篇没有"测试策略/同批测试"节；而 U2 迁移 + 切笔 + 去重 + 脱敏都是易回归面 | design 全文 · 建议 §13 加"验收性测试"清单（切笔边界表 · 去重双向 · 导入顺序 · 脱敏字段白名单 · 关插件闸门逐面）——B47 要求测试与功能同批 |
| **P2-6** | **U6 只给了新导入的脱敏口径，没有存量**：`imports/` 里现存原始导出含「备注」列（实测 `data/adai/trading/imports/2026-08/*.txt` 末列为备注），U6 的"脱敏后不构成隐私风险"对**存量文件**不成立 | design §4.1/§13 U6 · 建议补"存量 imports 的脱敏/清理口径 + 期限" |

## 六、P3

| # | 问题 |
|:--:|:--|
| **P3-1** | §11.3 用词"受控端点 403"未定义"受控"——应直接指向 §9 的端点清单（B36 要求按**端点清单**而非数据路径枚举门控）；同理 `has-activity`（`TradingController.java:1700-1710` 实测**确无门控**）在 §9 留了"补门控 **或** 登记为只读例外"的二选一，进编码前应定 |
| **P3-2** | 归属与包位置不符：`TradingKnowledgeSource` 在 **kernel 包**（`kernel/knowledge/TradingKnowledgeSource.java:1`），而 §1 声明 kernel 只提供"插件门控 + 通用待办/推送/存储"。§8.1 标"保留"即默认接受此位置 → 要么挪包、要么在 §1 显式登记为例外（B9 同族技术债） |
| **P3-3** | owner 判定依赖未声明配置：`TradingKnowledgeSource.java:56` 默认 `${adai.plugins.owner-user-id:adai}`，而 `application.yml` **未声明**该键、且 seed admin 已改名 `admin`（`data-format-freeze.md:250`）。§8.1 的"仅 owner 回落 os/"应写明该键的配置与取值 |
| **P3-4** | `MarketContextContributor.globalContext` 与 `TradingContextContributor.globalContext` 输出**同一标题**"## 交易系统状态"（`MarketContextContributor.java:64` / `TradingContextContributor.java:67`）→ 现在 prompt 里就是两段同名不同口径的内容；删除其一后应顺带统一标题与职责表述 |
| **P3-5** | 隐私面已 CI 化但未在设计中登记：`data/*/trading/` 已在 `.gitignore:83` 覆盖（含新增 `knowledge.md` / `plans/`），`data/*/todos/` 见 `:96` → 本设计新增目录**不需要**新 ignore；建议在 §4.1 注明"已 `git check-ignore` 验证"（B25 的出口动作），免得实施期重复验证或漏验证 |

## 七、§9 去留表 · 代码对拍实测差异（可直接并入 §9）

> 实测 78 个交易端点 vs §9 的 21 行。下表只列**§9 未登记**的既有能力（按 overview §2.2 分组）。

| 能力组 | 现状端点（实测 `文件:行号`） | §9 | 与设计的关联（为什么必须表态） |
|:--|:--|:--:|:--|
| 出入金 / 本金 | `POST /transfer` · `GET /transfers` · `PUT /principal`（`TradingController.java:884/940/997`；`TransferRecord.java` / `TransferRepository.java` / `CashAdjustment.java`） | ❌ | §4.2 的「净投入 = 转存 − 转取」**唯一数据来源**就是这条；`/principal`（手工本金）与 G-06「本金不靠手填」冲突 → 需标"替换/退役 + 存量 principal 迁移口径" |
| 资金层呈现 | `GET /equity-curve` · `GET /pnl-periods`（`EquityCurveController.java:41/79`） | ❌ | `G-07`「交易层 + 资金层两个面」的**资金层呈现**；§6 三端表里也没有对应行 → §12 说"无空缺"不成立 |
| 清仓股 + 情绪 | `GET /sold` · `POST /sold/import` · `PUT /sold/{symbol}/psychology` · `GET /sold/{symbol}/psychology-questions` · `POST /sold/{symbol}/psychology/answer` · `GET /sold/score`（`TradingController.java:789/800/821/838/857/874`） | ❌ | §3③ 的「卖点尺子闭环 = 你的清仓股分析 → 候选卖点规则」以及 R-13 画像主观层的**原料来源**；不登记就等于闭环的输入源没人管 |
| 候选入账（记录/截图） | `GET /trade-log` · `POST /screenshots` · `POST /trade-log/confirm` · `PUT /trade-log/date` · `PUT /trade-log/meta` · `PUT /trades/{tradeId}/meta`（`TradingController.java:1253/1269/1329/1352/1395/1478`） | ❌ | R-01 的 app 半边（见 P1-5） |
| 批量/解析录入 | `POST /trades/batch` · `POST /trades/parse`（`TradingController.java:395/1583`） | ❌ | app「手动记一笔」的另一既有形态（一句话解析）；§5 只给了 `POST /trading/import`，未表态旧路径 |
| 推送控制 | `GET/PUT /push-settings` · `DELETE /pushes/{id}`（`TradingController.java:1009/1018/1506`） | ❌ | 与 §11.3「不推送」+ ★V1「一天上限」直接相关（用户开关是既有逃生口） |
| 活跃市值 | `GET/PUT /market-stage`（`TradingController.java:1208/1233`；`TradingMarketStage.java`） | ❌（§1 有端口，表无行） | 现状是"推送择时状态的权威源"，§6 三端表也没有它 → 端口保留 ≠ 能力表态 |
| 账户快照 / 组合 | `GET /account` · `GET /portfolio` · `GET /positions/daily`（`TradingController.java:981/173/162`） | ❌ | §5 有 `GET /trading/account`（新），但既有 `/account` 与 `account.json` 的去留未表态 |
| 留痕 | `GET /advice-history`（`TradingController.java:1612`；`trading/advice-history/`，`data-format-freeze.md:513`） | ❌ | 与 §4.3 新增的"系统侧修改日志"是**两套留痕** → 边界要写清 |
| 工具 | `GET /lookup` · `GET /search`（`TradingController.java:267/281`；`NameToSymbolResolver`） | ❌ | 归一化"代码补全"依赖它 |
| 其他目录 | `data/{userId}/trading/memory-cards/`（实测存在） | ❌ | 与"交易理解放 trading 域"的关系未表态 |

**另有 2 处 §9 与正文自相矛盾**（不是漏项，是口径不一）：① §4.1 的 `market/`（全局）位置错（应为非用户层 `data/market/`）；② §9「资金流水主源」的行写 `/imports/save + D1`，而未标注 `/imports/save` 实际落 `trading/imports/{yyyy-MM}/`（设计 §4.1 写成 `imports/save/`）。

## 八、收敛判定

**不收敛**——2 条战略级（S1 关插件闸门漏 kernel 面 · S2 §9 基线未对拍）+ 6 条 P1。

> 一句话：设计 v2 把 review-v1 的 13 条都回了，工程味也上来了；但从后端视角看，它**把"关插件就不再提交易"这句承诺只落到了两个面上（待办 + 推送），漏了 Record/Timeline/Feed 这一整条最大面**；同时 §9 被定为验收基线却没跟代码对过——我实测下来有 11 组既有能力（含出入金、资金曲线、清仓情绪、候选入账）在表外，**渐进重构会静默丢它们**。其余是实施级细节：脱敏口径与现状注入冲突、promote 的脱敏漏形态且目标路径是共享的、目录表与数据格式冻结不符。
>
> 可直接施工的两件：①把 §七 差异表并进 §9 后重判基线；②按 P1-2 给两个贡献者逐字段白名单。

---

**给审核者/编写者**：本稿只做"设计 × 代码"对拍与后端风险判定，未改任何代码或设计文件。§七 的差异表建议直接抄进 §9（那些行号是实测的，可直接复核）。
