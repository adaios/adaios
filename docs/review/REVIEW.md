---
title: 项目审核全量状态报告
updated: 2026-08-17
last-review: 2026-08-17
baseline: c5aea47..HEAD（13 commits，交易自理批1-5：A-E 全部优化）
mode: deep 增量（交易模块 A-E 优化批次审查）
---

> **结构（RFC `20260815-docs-governance` 减负）**：本文件只留「战略 + P0-P2 未修复 + 最近审核摘要 + 执行成本」；已修复详情见 `docs/reference/change-log.md` + git log；P3/观察项已迁移 `docs/reference/task-log.md`。

> 2026-08-17 deep 增量（范围 `c5aea47..HEAD`，13 commits / 121 文件，交易自理批1-5 A-E 全部优化）：守护 7 PASS / 0 HIT；派 backend/frontend/docs/knowledge ×4。**P0 无。战略×3 + P1×9 + P2×16 + P3×24（合并去重后）**。核心：账户账目无单一真源（现金 3 处独立推导，战略）；C2 买点 5 参数「待用户确认」却已硬编码上线每日推送（战略，K40）；D3 自称「完美图匹配」实为规则近似（战略，K43）；**切入自动刷新是死代码**（`entry.label=='交易'` 判 `=='trading'` 恒 false，P1-前端，功能从未生效）；recordTrade 只动现金不动市值（P1-后端）；closeAccountUpdate 残缺市值覆盖总资产（P1-后端）；CURRENT_MD 硬编码相对路径（3487b00 只修一半，P1×2 官交叉印证）；positionPercent 分母不含现金（R81 bug 复发）；importCashQuery 解析失败静默落零；打分按 symbol `.first` 同代码多笔错挂（P1-前端）；_loadAll 六请求 Future.wait 任一端点失败整页丢数据（P1-前端）；buy-points 响应示例与实现不符（P1-文档）。新增检查点 B49-B54 / F45-F52 / D47-D53 / K40-K43。
> 2026-08-16 修复批（app-polish 审查落地）：**P-be-01 维护端点迁入 /admin/** 鉴权（安全）+ admin 收敛为纯治理（P-role 系列）+ app 补记忆修正/待办完成（P-role-02/P-app-03）+ 带图发图即对话 + 交易建议引擎**。后端 499 · app 112 · admin 34，全部出表。
> 2026-08-15 deep 审核（范围 `7b0a527..HEAD`，33 commits / 181 文件）：守护 7 PASS / 0 HIT；派 backend/frontend/docs ×3。**P0 无。战略×2 + P1×5 + P2×7 + P3×21（P3 迁移 task-log）**。**修复批 S + S2 共 12 项已出表**（P1-B1-B4/D1 + P2-B1/B2/R1/R2/R3/D1/D2，后端 440 · app 94 · admin 33）；**战略剩余仅 #179（v1.0.1 登录体系）**；已沉淀检查点 B34-36 / F33-36 / D27-29。
> 2026-08-15 上午 deep 审核（范围：工作树未提交改动——第二步插件系统 T2.1-T2.10 + 第一步遗留，47 文件）：守护 7 PASS / 0 HIT；派 backend/frontend/docs ×3。**战略×2 + P1×6 + P2×7 + P3×15**。P0 无。战略 S-3（重补路径 domain 未收敛）+ S-4（行情推送写侧未门控）；P1 六项；已沉淀检查点 B31-33 / F30-32 / D23-26。S-3/S-4/P1 全部出表（批 Q/R）。
> 2026-08-14 deep 审核（范围 `7b0a527..HEAD`，18 commits，带图 ask / 删除残留 / 图片交互批）：**P0×1 + 战略×2 + P1×2 + P2×2 + P3×14**。P0-1 + P1-1 + P1-2 + P2-1 + S-1 已修复出表；S-2 展示层已修（层 2 数据层另立 v1.0.1）。

# 项目审核状态报告

常驻全量状态。每次 `/review` 更新本文件——未修复项滚动保留，已修复标 ✅ 移入已修复区，新问题追加。git 历史天然保留每次更新前的快照。

## 最近审核

| 日期 | 模式 | 基线 | 派发角色 | 新增 | 修复 |
|:-----|:-----|:-----|:---------|:-----|:-----|
| 2026-08-17 | deep 增量（交易 A-E 批1-5）| c5aea47..HEAD（13 commits）| backend/frontend/docs/knowledge ×4 | 战略×3 + P1×9 + P2×16 + P3×24 | 0（审核不直接修）|
| 2026-08-16 | deep 增量（框架+插件 G-1~G-6）| 7734d99..HEAD（2 commits）| backend/knowledge/docs ×3 | 战略×4 + P1×4 + P2×9 | 0（审核不直接修）|
| 2026-08-15 | deep 增量（批 Q/R + 展示层聚合 + 发布核对）| 7b0a527..HEAD | backend/frontend/docs ×3 | 战略×2 + P1×5 + P2×7 + P3×21 | 0（审核不直接修）|
| 2026-08-15 | deep 增量（Domain=插件模型 + step-1）| 7b0a527 + 工作树 | backend/frontend/docs ×3 | 战略×2 + P1×6 + P2×7 + P3×15 | 0（审核不直接修）|
| 2026-08-14 | deep 增量（带图 ask + 删除残留 + 图片交互批）| 7b0a527..HEAD | 主会话 + docs/frontend agent×2 | P0×1 + 战略×2 + P1×2 + P2×2 + P3×14 | 5（P0-1 + P1-1 + P1-2 + P2-1 + S-1）|

> 更早审核（08-01 ~ 08-12）见「执行成本」表 + git 历史。

## 🔴 战略缺口（未修复）

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| 179 | 用户层 X-User-Id 零鉴权（任何人传任意 userId 即可读对应数据）；数据访问靠 header 注入无认证。真正收紧需登录体系 | `AccountController` / `WebConfig` | 📋 v1.0.1 立项 |
| S5 | 账户账目无单一真源：总资产/现金/本金被四处独立推导（snapshot.cash、positions.md cashBalance、转账推导、recordTrade 现金），现金有 3 个真源只更新其一 → R81 分母过期 | `TradingAppService` / `AccountSnapshot` | 📋 待讨论 |
| S6 | C2 买点 5 参数（回调50%/缩量0.7/KDJ20/放量1.5/前高20日）标注「待用户确认」却已硬编码上线每日 15:10 推送 + web 信号列 + D3 打分——实现替用户做了决定，无门禁 | `BuyPointDetector` / `buy-point-rules.md` | 📋 待用户确认参数 |
| S7 | D3 自称「完美图匹配度」，实际是规则阈值 + 硬编码分数映射（无完美图样本库/归一化相似度）；「三维打分」总分实为二维（选股维度未接入） | `SoldScoreService` | 📋 待用户确认是否补样本库 |
> **FP-S1/S2/S3/S4 已出表**（2026-08-16 框架+插件审查修复批，见已修复区）：总纲 §五 现状表刷新全 ✅（S1）；引擎口径契约测试 `RuleKnowledgeContractTest`（S2，B44）；R81 分母规格同步总资产（S3）；update-current.sh 声明修正为注记刷新器（S4）。
> S-R1（app 插件失败 SnackBar+重试，双端对拍）与 S-R2（服务端合并插件端点，竞态根治）已出表（2026-08-15，见已修复区）。S-2（展示层聚合）已出表；数据层整体化 RFC `20260815-media-event-unification` approved 排 v1.0.1；S-3/S-4 已出表（批 Q）。

## 🔴 P1（未修复）

| # | 问题 | 位置 | 建议 |
|:-:|:-----|:-----|:-----|
| P1-交易1 | **切入自动刷新是死代码**：`_NavEntry('交易',...,'trading',...)` label=中文'交易'，`_buildPage` 判 `entry.label=='trading'` 恒 false → 切到交易页从不触发刷新（253a35e/37d4b52 核心卖点从未工作）| `desktop_shell.dart:115` / `trading_page.dart:59-65` | 改判 `entry.plugin=='trading'`，补壳层 widget 测试 | ✅ 已修（2026-08-17 R4：改判 entry.plugin=='trading' + 壳层测试）
| P1-交易2 | recordTrade 只动现金不动市值：BUY 少计成交额、SELL 多计成交额 → 账户卡 15:05 前账目错误，快照现金滞后时 cash 可被推成负值 | `TradingAppService.java:137-148` | 买卖同步更新 marketValue | ✅ 已修（2026-08-17 R4：现金↔市值转移，总资产只差手续费 + 2 测试）
| P1-交易3 | closeAccountUpdate 部分行情缺失即用残缺市值覆盖总资产（旧值不可恢复）| `TradingSessionPushService.java:155,168-171` | 行情不全时跳过或保留旧市值 | ✅ 已修（2026-08-17 R4：缺行情跳过保存 + 2 测试）
| P1-交易4 | positionPercent 分母只算持仓不含现金（注释称含现金）→ 单仓+大现金每日误发「超 R81 减仓」（FP-P2 已修 bug 复发）| `TradingSessionPushService.java:327-341` | 分母改市值+现金（B50）|
| P1-交易5 | importCashQuery 解析失败（CASH_HEAD 未命中）静默落零覆盖 account.json + cashBalance 置零 | `TradingAppService.java:519-553` | ✅ 已修（2026-08-17 R3：headerMatched + 抛错 + web toast，见已修复区）|
| P1-交易6 | CURRENT_MD 硬编码 `../../os/...` 相对路径（3487b00 只修了 TradingAdviceAppService，漏了第二个知识消费者）→ 生产择时状态恒「未知」| `TradingSessionPushService.java:60` | ✅ 已修（2026-08-17 R1：配置注入 + 不可读 warn 日志 + 2 测试；见已修复区）|
| P1-交易7 | `_loadAll` 六请求合并 `Future.wait`：任一端点失败（如 buy-points K线抖动）→ 整页替换为错误页丢弃已展示数据（含静默刷新路径）| `trading_page.dart:74-81,98-104` | 致命/可降级请求分离（F41）| ✅ 已修（2026-08-17 R4：致命/可降级分离 + 测试）
| P1-交易8 | 清仓三维打分按 symbol `.where(...).first`：同代码多笔交易分数错挂（两行显示第一笔分数）| `trading_page.dart:774-775` | 按列表顺序索引匹配或 (symbol,buyDate) 复合键（F42）| ✅ 已修（2026-08-17 R4：按序索引匹配）
| P1-交易9 | B1 判定「回调一半」几何语义漂移：课程=回撤到涨幅一半位置 (high+low)/2，代码=距前高回撤 50% close≤high/2（更严）；且支撑/白线条件未实现 | `BuyPointDetector.java:63-64` vs glossary:899 | 与用户确认几何定义；参数表 #5 如实描述（K42）|
| P1-交易10 | api-spec buy-points 响应示例与实现不符：`score:0.8` 量纲错（实际 0-100 约 87）、signals 文案与代码实际输出不同 | `api-spec.md:513-514` | 示例=真实输出（D49）| ✅ 已修（2026-08-17 R4：示例=真实输出）
> **FP-P1~P4 已出表**（2026-08-16 框架+插件审查修复批，见已修复区）：yml 路径 11-context→knowledge/context（P1）；R81 分母改总资产（现金纳入，P2）；update-current.sh 幂等+时间戳语义（P3）；R66 现价口径注明（P4）。**P1 当前清零**。
> **P1 当前清零**（2026-08-15 修复批 S + S2 全部出表：P1-B1/B2/B3/B4 + P1-D1，见已修复区）。2026-08-16 框架+插件审查新增 FP-P1~P4（未修）。

## 🔴 P2（未修复）

| # | 问题 | 位置 | 建议 |
|:-:|:-----|:-----|:-----|
| P2-交易1 | SoldScoreService 16 线程池无 @PreDestroy shutdown；单笔 30s 超时产空 symbol 占位行 | `SoldScoreService.java:35,52-57` | 线程池 shutdown + 无空行占位（B53）| ✅ 已修（2026-08-17 R5：shutdown + 超时保留 symbol）
| P2-交易2 | scanWatchlist 串行拉 K 线（仅打分并行化，买点扫描未并发）且无按标的异常隔离 | `WatchlistBuyPointService.java` | 同 SoldScoreService 并发化（B54）| ✅ 已修（2026-08-17 R5：8 并发 + 异常隔离）
| P2-交易3 | 腾讯 K 线兜底无缓存（东财被限时每请求都打腾讯）| `TencentMarketDataSource` | 加按日缓存 | ✅ 已修（2026-08-17 R5：按日缓存）
| P2-交易4 | 现金双源不同步（snapshot.cash vs positions.md cashBalance）| `TradingAppService` | 现金单一真源（B52）|
| P2-交易5 | SoldTradeVerdict 自造阈值 -10% 挂 R66 名下（课程止损幅度 3-5%，R67/R72）→ 亏 8% 扛单被判「非违反」| `SoldTradeVerdict.java:30-32` | 阈值改 -5% 或标注近似待确认（K42）| ✅ 已修（2026-08-17 R5：-5% 用户确认）
| P2-交易6 | KDJ「大负值」阈值漂移：课程锚点 J<13，代码默认 J<20 偏松 | `KdjIndicator.java:17` / `BuyPointDetector.java:76` | 建议值改 13 或注明待确认 | ✅ 已修（2026-08-17 R5：J<13 用户确认）
| P2-交易7 | B1? 候选信号与正式 B1 同通道推送（「不硬推」声明违背）| `TradingSessionPushService.java:187` | 仅 B1/B2 推送，B1? 灰显候选 | ✅ 已修（2026-08-17 R5：B1? 不推送）
| P2-交易8 | `_loadAll` 入口 setState 无 mounted 守卫 + 多处 await 后直接 _loadAll | `trading_page.dart:69,838,946...` | 入口守卫 + await 前置守卫（F43）| ✅ 已修（2026-08-17 R6：mounted 守卫）
| P2-交易9 | buy-points 留在 _loadAll 致命路径（K线重计算阻塞首屏），与打分异步化自相矛盾 | `trading_page.dart:80` | 移出 Future.wait 异步化（F41）| ✅ 已修（2026-08-17 R4+R6：buy-points 已移出致命路径）
| P2-交易10 | _loadSoldScore 无去重/无空列表短路：每次 _loadAll 都触发全量 K 线打分可重叠 | `trading_page.dart:96-97,107-116` | _sold 空短路 + 代际令牌 | ✅ 已修（2026-08-17 R6：空列表短路 + 在途去重）
| P2-交易11 | 「纪律遵守率」实为胜率（profit/total 且 >=0 计盈），与纪律无关 | `trading_page.dart:707-711` | 改 verdict 口径或改名胜率 | ✅ 已修（2026-08-17 R6：verdict 口径 + 胜率单列）
| P2-交易12 | D2 行为模式单字 contains 误配（「不贪」「着急」）+ 重叠计数与「已标 N 笔」口径不一致 | `trading_page.dart:670-677` | 词组/否定排除 + 区分标注数/模式命中数（F45）| ✅ 已修（2026-08-17 R6：双字词组 + 否定排除）
| P2-交易13 | 快捷导入/删自选/心理标注无错误处理（失败静默+未处理异步异常）| `trading_page.dart:606-609,742-745...` | 统一 try/catch → toast（F46）| ✅ 已修（2026-08-17 R6：确认框 + 失败反馈）
| P2-交易14 | 账户总览 8 卡同行大数值溢出（22px 粗体 RenderFlex）| `trading_page.dart:318-341` | FittedBox/万单位 | ✅ 已修（2026-08-17 R6：FittedBox + 千分位）
| P2-交易15 | 打分列颜色与红涨绿亏冲突（绿色=高分 vs 全局绿色=亏损；'—' 渲染橙色）| `trading_page.dart:787-798` | 中性色阶 + 空值固定灰（F44）| ✅ 已修（2026-08-17 R6：中性色阶 + '—' 固定灰）
| P2-交易16 | 买点参数「可配」无配置接线（三处硬编码 0.5/0.7/20/1.5/20）+ RFC/feature-reference 状态漂移（待做列全是已实现项）| `BuyPointDetector` 调用点 ×3 / `data-intelligence.md` | yml 配置化（K40）+ RFC 滚动（D47）| ✅ 已修（2026-08-17 R5+R6：参数接线待用户确认，RFC 滚动见 R6）
| P2-交易17 | buy-point-rules.md 状态声明矛盾（「待用户确认后实现」vs 已实现）+ 参数 5 语义错位（写「前20日最低点/白线均线」，代码是**前高**窗口）| `buy-point-rules.md:5,56,64` | 改「已按建议值实现，待用户校准后冻结」；参数 5 如实描述（D51/K42）| ✅ 已修（2026-08-17 R6：文档状态同步）
| P2-交易18 | api-spec 变更记录缺 v3.22（15 个交易端点 2026-08-16 全部落地无版本行）| `api-spec.md:5,32-33` | 补 v3.22 行 + 升头部版本号（D48）| ✅ 已修（2026-08-17 R6：v3.22）
| P2-交易19 | api-spec account 节「每日定时任务收市后更新为后续」过时——批1 已实现收盘 15:05 自动更新 | `api-spec.md:574` | 改「收盘 15:05 自动更新行情字段；现金/本金保持券商导入+转账推导」（D53）| ✅ 已修（2026-08-17 R6：account 节修订）
| P2-交易20 | guard-align A1 盲区：正则只匹配括号内带路径的映射，11 个裸 @GetMapping（类级路径继承）不计入 → A1 报 60 vs 真相源 71 | `guard-align.sh:33-37` | 正则补裸注解分支或 A1 直接对拍 endpoints.txt（D50）|
| P2-交易21 | TradingAdviceAppService 输出侧硬判定未过 r81Applicable：OVER_WEIGHT && buy → reduce 覆盖未检查总资产超 100 万前提，与 FP-P2b 语义矛盾（prompt 段尊重前提、输出段没有）| `TradingAdviceAppService.java:194-198` | 输出侧复用 r81Applicable 判定 | ✅ 已修（2026-08-17 R5：r81Applicable）
| P2-交易22 | importPositions 缺 avgCost/quantity 校验：body 无 avgCost → Position.avgCost null → PortfolioSnapshot.of / closeAccountUpdate / 建议引擎 NPE 500 | `TradingController.java:159` / `TradingAppService.java:340` | controller 校验或 domain 兜底 | ✅ 已修（2026-08-17 R5：avgCost/quantity 校验）
| P2-交易23 | **持仓编辑端点从未实现**：前端/测试一直在调 PUT /positions/{symbol}，后端只有 GET/POST——web 点「编辑」保存必 404（功能形同虚设）| `TradingController`（2026-08-17 已补端点 ✅）| ✅ 已修（2026-08-17 R1 续：updatePositionMeta + PUT 端点，见已修复区）|
> **FP-P2a~i 已出表**（2026-08-16 P2 清尾批，见已修复区）：输出侧校验 / R81 100万前提 / 测试补断言 / gap frontmatter / docs/README 登记 / 三阶段 RFC 滚动 / gap 指向 / 脚本相对路径 + CLAUDE.md 收录 / 编号对拍。**P2 当前清零**。
> 历史观察项已迁移 task-log。

## 🔴 P0 / P3

- **P0 未修复当前清零**
- **P3 打磨项全部迁移** `docs/reference/task-log.md`（2026-08-17 交易 A-E 批新增 24 项：后端 8 + 前端 12 + docs 4，见 task-log 待办迁移区；含转账金额非法静默、上次更新时间语义、本金缺¥、isTdxExport 关键词表不一、行为模式 Row 溢出、DTO 零测试、lookup 无防抖、删自选无确认、_statCard 死默认、Timer offstage 继续跑、NaN 放行、R120 引用越界含 SB1、R85 引用错位、DefaultTradingRuleEngine javadoc 未同步、B2 推送缺止损提醒、buy-point-rules 孤儿规格等）

## ✅ 已修复区（最近 10 条，一行摘要；详情见 `docs/reference/change-log.md` + git log）

| # | 摘要 | 修复 |
|:-:|:-----|:----:|
| P2 批 C（文档四连）| roadmap 状态修正 + feature-reference 补端点 + api-spec 403 契约 + os 空文件删 | ✅ 2026-08-17 |
| P2 批 B（前端六连）| 记忆页守卫 + 缩略图降采样 + 图片 caption + toggle catchError；核实 3 项已闭环 | ✅ 2026-08-17 |
| P2 批 A（后端六连）| 原子写 + TagIndex 锁 + 交易流水线 + Memory 三缺陷 + delete 门控 + 插件缓存 | ✅ 2026-08-17 |
| Review 修复批 R11（admin + 收尾）| admin 静默刷新 + setPlugins 死接口删 + PromoteResultDto message；核实 2 条已闭环 | ✅ 2026-08-17 |
| Review 修复批 R10（P3 深水区）| _closeChat indexWhere + emoji 截断 + stripUserPrefix 兜底 + 脱敏千分位 + RFC 勾销 + feed 示例 domain；核实 W-P3-3 已消失 | ✅ 2026-08-17 |
| Review 修复批 R9（P3 尾尾批）| web SnackBar 队列/ValueKey 保活 + app superseded 标记 + ANALYSIS system 收敛注明 + CLAUDE.md 去重拆行 + README 断链 + T1.3 superseded + PATCH 契约；核实 6 条旧 P3 已闭环 | ✅ 2026-08-17 |
| Review 修复批 R8（P3 尾批）| isValidPlugins 查重 + gateDomain 白名单收敛 + HEIC MIME + api-spec 关键词同步 + ask-batch 登记；核实 5 条旧 P3 已闭环 | ✅ 2026-08-17 |
| Review 修复批 R7（P3 收尾）| 八端点 controller 测试 + web DTO 测试 + 降级日志 warn + WeChat interrupt + buy-point-rules 登记 + gap lines；后端 629→640 · web 92→98 | ✅ 2026-08-17 |
| Review 修复批 R6（P2/P3 web）| mounted 守卫/打分去重/纪律遵守率口径/否定词/删除确认/千分位/打分列色/lookup 防抖/NaN 校验等；web 89→92 | ✅ 2026-08-17 |
| Review 修复批 R5（P2 后端）| 线程池 shutdown/并发扫描/腾讯缓存/R66-5%/KDJ-13/B1? 不推/r81Applicable/导入校验/节假日/调度器 4 线程/Feed 标题；后端 626→629 | ✅ 2026-08-17 |
| Review 修复批 R4（P1 五连）| P1-1 切入自动刷新死代码（plugin 判等）；P1-2 买卖同步市值（总资产不变式）；P1-3 收盘缺行情跳过；P1-7 可降级请求分离；P1-8 打分按序匹配；P1-10 api-spec 示例；后端 622→626 · web 87→89 | ✅ 2026-08-17 |
| Review 修复批 R3（导入落零防护）| importCashQuery 解析失败禁止落零（P1-交易5 出表，B51）：headerMatched + TradingException 400 人话 + web 导入失败 toast；后端 620→622（+2）| ✅ 2026-08-17 |
| Review 修复批 R1（择时路径 + 推送文案 + 持仓编辑）| CURRENT_MD 配置注入（生产择时状态恢复，P1-交易6 出表）；loss 文案如实化；**持仓编辑 404 修复**（PUT /positions/{symbol} 补端点，P2-交易23 出表）；web 记录交易默认止损 -7%（用户设定）；生产 5 只持仓按成本×0.93 补止损；后端 612→619（+7）· 端点 71→72 | ✅ 2026-08-17 |
| 框架+插件审查 P2 清尾批（FP-P2a~i）| parseLlmAdvice 输出侧校验（BREACHED→强制 clear、OVER_WEIGHT→buy 保守改 reduce，B45）；R81 100万前提（总资产超 100 万不强制，参考 R82-R95）；测试补断言（硬信号段 + currentPrice≤0）；gap 补 frontmatter（D44）；docs/README 登记新文档；三阶段 RFC 升 approved + 实施记录 + §三同步；gap 指向正式总纲；update-current.sh 相对路径 + CLAUDE.md 收录（09-scripts 行）；编号对拍（CLAUDE.md R1-R120/E1-E30 + agent-skill E1-E30，K39）；后端 556（+1）| ✅ 2026-08-16 |
| 框架+插件审查修复批（FP-S1-S4 + FP-P1-P4）| yml 路径 11-context→knowledge/context（运行时断链根治，三官交叉印证）；R81 分母改总资产（现金纳入，+测试）；update-current.sh 幂等+时间戳语义+声明修正；R66 现价口径注明；总纲 §五 刷新全 ✅；引擎口径契约测试 RuleKnowledgeContractTest（B44）；rules-api.md §2/§3 同步；后端 555（+4）| ✅ 2026-08-16 |
| S-R1/S-R2（deep 战略项）| launcher 插件失败 SnackBar+重试（双端对拍 web）+ 服务端合并插件端点 `PATCH /accounts/{id}/plugins`（账号级锁原子 add/remove，根治 PATCH 全量并发互覆）+ admin 改走合并语义 + 内置 admin 插件服务端保护；api-spec v3.20；后端 446（+6）| ✅ 2026-08-15 |
| 修复批 S2（P2-B2/R2/R3 + P1-D1 + P2-D1/D2）| Account null userId 拒绝（全局中断）+ admin 内置插件开关 isProtected 门控 + launcher 测试补分支 + review-context 断链 + RFC/docs 状态同步；后端 440（+1）· app 94（+2）| ✅ 2026-08-15 |
| 修复批 S（P1-B1-B4 + P2-B1 + P2-R1）| deep 审核后端/前端修复：domainEnum 去引号语义（CHAT 双重引号根治，补最终拼接断言）+ 时间线聚合跨天/intent/歧义边界 + 图片 domain gateDomain + trading 写入口门控（403）+ admin 插件 toggle 串行队列；后端 439（+6）· admin 33（+1）| ✅ 2026-08-15 |
| S-2（展示层）| 「一次输入 = 一个事件」：时间线多轮 chat 每会话单条 + 带图 ask image_qa 聚合为图文事件（引用图不单独成条，缩略图取首图）；Feed 同口径；前端零改动；数据层整体化另立 v1.0.1；后端 433（+4）| ✅ 2026-08-15（层 2 另立）|
| 批 R（P1-5/P1-6/P2-5/P1-7/P2-8）| 前端+文档：adai-web 壳 label 重解析防索引错位 + 插件失败 SnackBar 重试；adai-app Launcher 插件接口拆独立 try/catch；api-spec D1 通用化同步；feature-reference 补插件模型章节；web 47（+1）| ✅ 2026-08-15（**P2-6 除外，reopen 见 P2-R1**）|
| 批 Q（S-3/S-4/P1-4/P2-2/P2-3/P2-4）| 后端插件门控/健壮性六连修：重补路径 gateDomain + MarketAlert 写侧 trading 门控 + 账号迁移读字段存在性 + domain 规则关键词单一真相源 + Account null 过滤 + ContextPackage 收敛 domainEnum；后端 429（+7）| ✅ 2026-08-15（边界漏网见 P1-B1/B4/P2-B1/B2）|
| P1-3 | `data/*/project/` 隐私面补齐（gitignore + git rm --cached）| ✅ 2026-08-15 |
| S-1 | adai-web 多图 ask 同步（askBatch + 上限 3 + `_syncActiveCard`）| ✅ 2026-08-14 |
| P0-1 + P1-1 + P1-2 | 对话态发媒体崩溃/残留错乱/部分失败问句丢（`_syncActiveCard` + `_pendingAsk`）| ✅ 2026-08-14 |
| #169 + #257 | 问候语机械 + 测试覆盖确认出表 | ✅ 2026-08-13 |
| 批 P | deep 31 项清 22：#234 分页终止 + #235-#238 P1 + #240-#246 P2 + P3 14 项 | ✅ 2026-08-12 |
| 批 O 收官 | #101/#103/#177 战略 + #19/#22/#115/#228 P2 + P3 顺手 14 项 | ✅ 2026-08-12 |
| #216 + #217 + #223 | CardMigration 判定收紧 + rewriteId 锚定 frontmatter + os/ 只读例外 | ✅ 2026-08-12 |

## 🔍 全维度走查（ai-engineering/process/audit.md）

> 7 审查官独立并行全量走查，交叉印证（同一问题多官命中 = ⭐ 优先级高）。走查日期 + 摘要滚动保留。

> 2026-08-15 自伤自查（8 官全量，审查 AI 工程层自身）

> 📄 完整发现清单见 `docs/review/audits/2026-08-15-ai-engineering-self.md`。

> 守护：META-GUARD PASS（45 文件）。**P0 无。战略×7 + P1×14 + P2×26 + P3×24**。**核心**：①docs/ai→ai-engineering 迁移清理未闭环（6 官 ⭐⭐⭐⭐⭐⭐）——ship/audit/review 门禁命令 `bash docs/ai/guard-meta.sh` 按文档执行必失败；②guard-meta M1 只校验 frontmatter 边、不查正文路径（盲区）——迁移残留全绿 PASS。**战略 S-A1..A7 + P1-A1..A14** 见存档。检查点沉淀建议 11 条（M4 正文路径扫描 / 迁移三件套 / RFC 验收核验 等）。

### 2026-08-15 首轮（7 官全量）

> 📄 完整按角色发现清单见 `docs/review/audits/2026-08-15.md`（含修复状态标注）。

> 守护 7 PASS / 0 HIT。**P0×1 + 战略×3 + P1×16 + P2×14 + P3×27**。**修复批 W1+W2 后端已出表**（W1：P0-W1 卡片单行化 + W10 SafeArea + W1/W2 双端重试 + W3/W4 自然化 + W16 基建自伤；W2 后端：W12 parseDateTime / W13 门控旁路 / W14 prompt 引号 / W15 标签索引重建，后端 454）；**P1 全部出表**（W1 批 + W2 批：P0-W1/W10/W1-4/W16/W12-15/W5-9/W11——后端 454 · app 94 · web 47 · admin 33）。剩余：战略 S-W1/S-W2/S-W3（roadmap 插件模型 / 双端值复制漂移 / 请求超时已修故 S-W3 部分落地）+ P2/P3（task-log）。检查点沉淀 B37-42 / F37-44 / D30-43 / K29-31 / C1-C7 / U10-17 / V1-3。

**🔴 P0（数据丢失）**

| # | 问题 | 位置 |
|:-:|:-----|:-----|
| P0-W1 | **对话卡片多行 turn 读-写不对称 → 对话历史静默截断丢失**：`toMarkdown` 原样写多行 AI 回答，`parseTurns` 只取前缀所在行 → 下次保存时截断覆盖原文件。须补「写→读→写→读」round-trip 测试（含 \n）| `CardFileRepository.java:186-254` |

**🏛 战略**

| # | 问题 | ⭐ |
|:-:|:-----|:--:|
| S-W1 | roadmap（唯一蓝图）未收录已 approved 的 Domain=插件模型方向——已实现能力在蓝图不可见 | ⭐ |
| S-W2 | 双端「值复制」修复漂移成常态（图片重试/删除确认/全图 Dialog/状态机各落一端）——建议固定对拍项或抽共享 package | ⭐⭐ |
| S-W3 | 请求层无超时/无取消/无响应归属校验——所有等待态卡死防御靠 UI 补丁 | ⭐ |

**🔴 P1（首轮走查 16 项已全部出表 ✅）**

> 走查 P1-W1..W16 全部修复（W1/W2 批），详情见 `docs/reference/change-log.md`（W1 批：P0-W1/W1-W4/W10/W16；W2 批：W5-W9/W11-W15）。P1 区当前无未修复项。

**🔴 P2（11 项，详见 task-log）**：双端重试/删除/文案/色值对拍（多官）、记忆页日期连点乱序、admin 队列无错误恢复、Feed 缩略图无降采样、web caption 丢失、项目写端点门控、accounts.json 非原子、TagIndex 并发 RMW、交易不落 Record 流水线、记忆序列化三缺陷、alice 越界 domain/残留、positions 错配、os/ 知识杂项、roadmap 状态漂移、feature-reference 过期等。

> P2/P3 完整清单已迁移 `docs/reference/task-log.md`（2026-08-15 首轮走查区）。P3 打磨项全部入待办。

## 执行成本

| 日期 | 模式 | 派发角色 | agent 数 | 耗时 | 新增 | 修复 |
|:-----|:-----|:---------|:--------:|:-----|:----:|:----:|
| 2026-08-17 | deep 增量（交易 A-E 批1-5）| backend/frontend/docs/knowledge ×4 | 4 | ~30min | 战略×3 + P1×10 + P2×16 + P3×24（去重后）| 0（审核只报告）|
| 2026-08-17 | 修复批 R11（admin + 收尾）| — | 0 | ~20min | 0 新 | admin×3 |
| 2026-08-17 | 修复批 R10（P3 深水区）| — | 0 | ~30min | 0 新 | W-P3×8 |
| 2026-08-17 | 修复批 R9（P3 尾尾批）| — | 0 | ~30min | 0 新 | P3×12 + 核实 6 闭环 |
| 2026-08-17 | 修复批 R8（P3 尾批）| — | 0 | ~30min | 0 新 | P3×11 |
| 2026-08-17 | 修复批 R7（P3 收尾）| — | 0 | ~30min | 0 新 | P3×11 |
| 2026-08-17 | 修复批 R6（P2/P3 web）| — | 0 | ~40min | 0 新 | P2×8 + P3×7 |
| 2026-08-17 | 修复批 R5（P2 后端）| — | 0 | ~40min | 0 新 | P2×9 + P3×8 |
| 2026-08-17 | 修复批 R4（P1 五连）| — | 0 | ~40min | 0 新 | P1-1/2/3/7/8/10 共 6 |
| 2026-08-17 | 修复批 R1 | — | 0 | ~20min | 0 新 | P1-交易6 + loss 文案 2 |
| 2026-08-16 | 修复批（框架+插件审查发现）| — | 0 | ~20min | 0 新 | FP-S1-S4 + FP-P1-P4 共 8 |
| 2026-08-16 | deep 增量（框架+插件 G-1~G-6）| backend/knowledge/docs ×3 | 3 | ~25min | 战略×4 + P1×4 + P2×9（去重后）| 0（审查只报告）|
| 2026-08-15 | **全维度走查（首轮）** | 7 官全量并行 | 7 | ~1h | P0×1 + 战略×3 + P1×16 + P2×11 + P3×35 | 0（审查只报告）|
| 2026-08-15 | deep 增量（批 Q/R + 展示层聚合 + 发布核对）| backend/frontend/docs ×3 | 3 | ~40min | 战略×2 + P1×5 + P2×7 + P3×21 | 0（审核只报告）|
| 2026-08-15 | deep 增量（Domain=插件模型 + step-1）| backend/frontend/docs ×3 | 3 | ~30min | 战略×2 + P1×6 + P2×7 + P3×15 | 0（审核只报告）|
| 2026-08-14 | deep 增量（带图 ask 批）| docs/frontend ×2 + 主会话 | 3 | ~40min | P0×1 + 战略×2 + P1×2 + P2×2 + P3×14 | 5 |
| 2026-08-12 | 修复批 P | subagent×2 + 主会话 | 2 | ~2h | 0 新 | 22 |
| 2026-08-12 | deep 增量（收官批 O 深度审核）| ×5 角色 | 5 | ~20min | 战略×1 + P1×5 + P2×7 + P3×18 | 0 |
| 2026-08-12 | 收官批 O | subagent×2 + 主会话 | 2 | ~4h | 0 新 | 22 |
| 2026-08-12 | 修复批 N/M/L + 顶部摘要 + 隐私批 | — | 0 | 逐批 20-40min | 0 新 | 14 |
| 2026-08-12 | deep 增量（R1 AI 日志批）| ×5 角色 | 5 | ~20min | P0×2 + 战略×1 + P1×8 + P2×15 + P3×5 | 0 |
| 2026-08-12 | P1 修复批 A-D + #184 | — | 0 | ~90min | 0 新 | 13 |
| 2026-08-09 | 批 K + deep 增量 + 验证修复 + 批 J | ×5 / 主会话 | 5 | ~2.5h | 战略×3 + P1×2 + P2×10 + P3×14 | 22 |
| 2026-08-03 | full 全量（v0.3.0 前）| ×5 角色 | 5 | ~30min | P0×1 + 战略×7 + P1×13 + P2/P3×30 | 0 |
| 2026-08-02 | full 全量（v0.1.0）| ×5 角色 | 5 | ~25min | 前端 3 + 后端 P1 4 + 文档 | 后端 P1 4 |
| 2026-08-02 | deep 增量（adai-web）| ×3 角色 | 3 | ~10min | P0×1 + 战略×3 + P1×9 + P2×8 | 0 |
| 2026-08-01 | 全量 + deep 增量 | ×3 / docs/knowledge | 5 | ~2.5h | 43 | 23 |

> 跑几轮后用成本数据决定全量审核频率（目前建议：增量随时，全量 1-2 周一次）。
