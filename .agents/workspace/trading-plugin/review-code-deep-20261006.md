---
title: 编码段 deep 深审 · 交易插件重做（2026-10-06）——三官隔离并行 + 主会话逐条复验，P0 无 / P1×6 / P2×8
description: 对 feat/trading-plugin 批次（工作树 73 项未提交改动：六批链 + 注入契约 + 去留表 + 反哺 + 六面闸门）的编码段 deep 增量深审——守护三连 + 后端 / 前端 / 对抗三官各自独立上下文并行 + 主会话对全部发现逐条复验（官报 2 条 P0 复核后 1 降 P1 准 P0、1 降 P2；纠正 1 处「无锁」误报；消解 1 处已声明取舍）；P0 无，P1×6 + P2×8 登记（规则 6/7 只报告不改）；编号为预分配（待合并后主仓库誊写 REVIEW.md）
version: 1
created: 2026-10-06
updated: 2026-10-06
status: active
lines: 148
depends-on:
  - ./design-final-20261006.md
related:
  - ./LEDGER.md
  - ../../records/REVIEW.md
  - ../../records/change-log.md
tags: [workspace, review, code, deep, trading]
---

# 编码段 deep 深审 · 交易插件重做（2026-10-06）

> **范围**：`feat/trading-plugin` 工作树全部未提交改动（73 项：`services/adai-core` 主/测 + `apps/adai-app` + `apps/adai-web` + `apps/adai-admin`）。
> **基线**：设计文档 `.agents/workspace/trading-plugin/design-final-20261006.md`（§1–§13 全量实现核查为审查前置）。
> **方法**：守护三连（`guard.sh` / `ai-guard-meta.sh` / `ai-guard-feature.sh`）→ 按 review.md 材料裁剪表派官
> （本批 diff 无 `docs/**`、`*.md` 改动 → **未派 docs-contract 官**，与预告的 4 官差异在此）→
> **3 官各自独立上下文并行**（后端 · 前端 · 对抗）→ **主会话逐条复验**（每项以代码行号 + 真实数据实证）。
> **外部信号（硬前置）**：后端 201 suites / 2584 tests 全绿 + 三端 flutter 440/409/72 全绿。
>
> ⚠️ **三官会话工具限制（如实记录）**：三官均报告本会话只有只读工具（Read/Grep/Search），**无 shell**——
> 无法执行 `git diff HEAD` / `git ls-files --others`，「新增 vs 存量」边界靠文件直读重建。官报告内容真实
> （工作树文件即含未提交改动），但范围边界由**主会话对拍**；三官均未改仓库任何文件。
>
> **落盘与归口（在制品期口径）**：本报告为**任务在制品**（落 `workspace/trading-plugin/`）；`REVIEW.md` 誊写与 `docs/records/audits/` 归档**留到本任务收工**时统一做（交接单见 `LEDGER.md`「待交接」）；编号为**预分配**（接续 REVIEW.md 现最大号，誊写时核对）。

## 一、判定摘要

| 官 | 判定 | 关键产出 |
|:--|:--|:--|
| 后端审查官 | **需修正**（P0×1 复核降级） | 「规则/边界仓储损坏降级」官报 P0 → 复核纠正「无锁」不实 + 降 P2（P2-交易95）· 战略×3（1 转收工契约账 · 1 为全仓存量分层现状不登记 · 1 降 P2-交易98）· P1×4（dismiss 复活 → P1-交易93 · 审计无锁 → P2-交易97 · trace 不符 → P1-交易94 · promote 无锁 → 消解） |
| 前端审查官 | **需修正**（P1×3） | 推送徽章契约断裂 3 条合并 → **P1-交易92**（双端实锤）· admin 回执 2 条合并 → **P2-admin1** · 其余 P2/P3 主会话复验后随行 |
| **对抗审查官** | **需先修**（P0×1 复核降 P1-准 P0） | **promote 脱敏对真实复盘失效 → P1-交易89**（真实数据实锤）· P1×4（品种门 → P1-交易90 · 简报缓存 → P1-交易91 · 审计非原子 → P2-交易96 · 白名单成本 → 消解）· P2×5（含 **⭐ 2 处与另两官同中**：promote 无锁 · admin 回执错位） |
| **主会话复验** | **P0 无 → 登记 P1×6 + P2×8** | 官报 2 条 P0 复核后 1 降 P1（准 P0）、1 降 P2；纠正 1 处「无锁」误报；消解 1 处已声明取舍；⭐ guard G2 与对抗官同中 1 处（P2-交易99） |

## 二、主会话逐条复验（证据 → 结论）

### P1（6 条）

**P1-交易89 · promote 脱敏对真实复盘失效（准 P0：对抗官报 P0、主会话复核后定 P1）**
- 证据：`TradingController.sanitizeReviewContent`（:1791-1806）①股数正则 `(持有|买入|卖出|…|加仓|减仓)\s*[\d,]+(?:\.\d+)?\s*股` 要求动词**紧贴**数字；真实复盘 `data/adai/trading/reviews/2026-08-26_review.md` 实际写法：「**有研新材600股**清仓」「新开**广发证券200股**、**亨通光电300股**，并加仓中国稀土100股」「当前持有广发证券200股」——动词与数字间夹标的名 → **全部漏过**（②价格/③现金/③金额/④持仓规模对部分形态有效）。
- 复核定级：候选落点 `data/{userId}/trading/reviews/promote/`（**私有、不进 git**，且本地候选区实测为空 = 尚未发生实际落盘），泄漏进 git 需经「人工提升进 `os/`」下一跳 ⇒ 对抗官 P0 降 **P1（准 P0）**。候选文件去向是 git 跟踪目录，脱敏是这条链的核心防护网——**修法简单**（补「标的名+数字+股」形态或独立匹配「数字+股」），建议本批必修。

**P1-交易90 · 品种门只护统一入口（对抗官 P1-1，证实）**
- 证据：grep `isMainboardCode` 全仓仅 3 处（定义 `TradingImportParser:706` + 使用 `TradingAppService:2226/2236`）；而 `TradingController` `/positions/import`（:285-342）直接调 `importPositions` **无过滤**——同一约束在统一入口与流水侧有、持仓快照导入侧没有（两入口行为不一致，非主板代码可经快照进账本）。

**P1-交易91 · 简报缓存旁路门控（对抗官 P1-2，证实）**
- 证据：`BriefAppService.getCachedBrief`（:93-101）**无任何插件判断**直接返回缓存；`generateBrief`（:104-110）的缓存判断在门控（:118）**之前**——停用 trading 插件的用户仍可从缓存拿到交易简报/命中旧缓存。六面闸门（§11.3）中 Brief 面的缺口。

**P1-交易92 · 前端推送徽章契约断裂（前端官 P1-1/1-2/1-3 合并，双端实锤）**
- 证据（app `feed_card.dart` :448-463 与 web `desktop_feed_card.dart` :188-201 同款逻辑）：
  ① `MarketAlertService` 落库标题为 `p.name() + " 行情提醒"`（:320）/ `lot.name() + " 批次止损预警"`（:362）——**带股票名前缀**，前端 switch 精确匹配 `'行情提醒'`/`'止损预警'` **全不命中** → 落 default 灰「行情」；
  ② 后端真实标题「**午间知会**」（`TradingSessionPushService:558`），前端只有 `'午间跟踪'`（:451/:191）——**死分支**（12:00 推送徽章落灰）；
  ③ `'今日操作确认'` 借 `'尾盘卖点'` 徽章（:454）——两个语义不同的推送共用橙徽章；
  ④ `FeedAppService.toPushEntry`（:668-696）优先透传落库 title、旧数据（title 空）才按 type 兜底映射标准词 ⇒ **反直觉细节：越新的数据越落灰，旧数据反而正常**。
- 修法建议：判据改回结构字段（`type`/来源）而非标题精确串；或后端推送标题不带标的前缀（标的放 body）。

**P1-交易93 · 候选 dismiss 无墓碑 → 下次生成复活（后端官 P1-7，证实）**
- 证据：`TradingUserRuleService.dismiss`（:104-106）= `repository.remove(userId, id)`（无 DISMISSED 态）；`generateCandidates`（:125-158）每次全量重新生成同 id 候选 ⇒ 用户划掉的候选下次生成**又出现**（用户操作被系统无视）。

**P1-交易94 · capital-net trace「不靠手填」与实现矛盾（后端官 P1-6，证实）**
- 证据：`TradingAnalysisService`（:355-356）trace 文案「从资金流水推出（转存 − 转取），**不靠手填**（G-06）」；实际 `EquityCurveService`（:286）`investedSoFar = principalNow - totalTransferDelta`——invested **起点就是快照里的手填 `principal`**（L48 注释：「期初投入缺口 + 逐笔转账累计净投入」，其中 principal 为用户手填），与 P2-交易66「本金自证」缺口直接呼应。属「有据」体系的溯源诚实性问题（用户可见的确定错误陈述，100% 显示）。

### P2（8 条）

**P2-交易95 · 规则/边界仓储「损坏降级为空 + 写侧全量重写」组合（后端官报 P0、主会话复核降 P2）**
- 证据：`RoundBoundaryRepository` / `UserRuleRepository` 读侧损坏 → `log.warn + return List.of()`（降级不坏，**有意设计**）；`upsert` 在条带锁内 `findByUser → 改 → write` **全量写回** ⇒ 文件已损坏时，第一次写会把残余内容整体覆盖（恢复可能性进一步降低）。缺 .bak/升级告警。
- 复核修正：① 官报「无锁」**不实**——两仓储均有 per-user 条带锁（`synchronized (lockFor(userId))` :94/:104）；② 触发前提是文件已损坏（罕见）且两者数据可重建（候选可重生成、边界可重设）⇒ P0 降 **P2**。测试面：`find -iname '*Audit*'` 等无独立仓储测试。

**P2-交易96 · 审计链 11 连写非原子（对抗官 P1-4，复核降 P2）**
- 证据：`TradingAppService.appendCorrectionAudit`（:1495-1508）最多 11 次独立 `audit()`（字段有差异才写，:1511-1519），每次 append 各自 read+write（`TradingAuditFileRepository.append`）⇒ 多字段纠错中途失败（fail-visible 抛、业务中止）会留下**半截孤儿审计**（部分字段有留痕、账未改）。触发前提为写失败（罕见）⇒ P2。

**P2-交易97 · 审计仓储无锁（后端官 P1-5 部分，复核维持 P2）**
- 证据：`TradingAuditFileRepository`（全文 91 行）无任何锁；`append` 为读改写（:51-54）⇒ 同一用户并发两次改账（如双端同时操作）可能丢一条审计（后写覆盖前写）。`FileStorage.write` 自身原子、账目不受影响；窗口毫秒级 ⇒ P2。

**P2-交易98 · pct(base==0) 返回 ZERO 违反契约①「缺数据一律出 null，不出 0」（后端官战略-4，复核降 P2）**
- 证据：`TradingRoundService.pct`（:651-655）`base == null || base.signum() == 0 → BigDecimal.ZERO`——与设计 L268 契约①（验收 5）冲突；对比 `TradingContextContributor` 负成本出「—」的正确处理。触发场景为 base 为 0（负成本送股/空账户）⇒ 低频误导，P2。

**P2-交易99 · 审计月份路径 `LocalDate.now()` 不可注入（⭐ guard G2 HIT 与对抗官 P2-1 同中）**
- 证据：`TradingAuditFileRepository.append`（:44）`filePath(LocalDate.now())`——写路径按「当前月」推送，跨月边界/时钟回拨/测试不可注入（guard G2 报告 HIT 即此处，为 storage 层唯一 now() 用法）。

**P2-交易100 · promote 落点 `{主题}` 占位未实现（后端官 P1-8 的「主题硬编码」部分）**
- 证据：设计 §10.2（L418）落点 `{yyyy-MM-dd}_{主题}.md`，L587 亦讨论「同日多主题仍需序号」；实现 `TradingController:1733` `stem = "…/promote/" + date + "_交易复盘"` **硬编码单一主题**，`PromoteRequest(note, sections)` 无主题入参 ⇒ 设计完成度缺口（低危：同名以 -2/-3 序号兜底不丢文件）。

**P2-admin1 · admin 反哺回执两处（前端官 P2-5/P2-6 + 对抗官 P2-5 ⭐两官同中，实锤）**
- 证据（`apps/adai-admin/lib/pages/system/reviews_tab.dart`）：
  ① :117 弹窗文案「将 ${review.title} 提升为入库候选（**写入你的候选区**，待人工审核）」——admin 操作的是**被操作用户**的复盘，借用了面向用户的第一人称文案（「你的」错位）；
  ② :154-158 成功回执只显示 `'反哺成功：${status}（${path}）'`，**丢弃后端 message**（「…不会自动进入 AI 上下文：需人工审核…」）——而 `PromoteResultDto`（api_dto.dart:248-258）已解析 message 字段（:246 注释也仍写着过时的 `{status, path}`）。

**P2-工程16 · adai-web `pubspec.lock` 4 个传递依赖被顺带升级（主会话发现）**
- 证据：`git diff apps/adai-web/pubspec.lock`——matcher 0.12.19→0.12.20 · meta 1.18.0→1.19.0 · test_api 0.7.11→0.7.12 · vector_math 2.2.0→2.4.3（均 transitive，测试/数学生态）。提交前需确认：有意随批 or 还原（避免把未解释的锁文件变化带进本批 commit）。

### 官报被修正/消解记录（最有价值的部分）

1. **「两仓储无锁」→ 不实**：主会话实读确认 `RoundBoundaryRepository`/`UserRuleRepository` 均有 per-user 条带锁（:94/:104）；官报 P0 的锁论据不成立。
2. **2 条官报 P0 → 复核降级**：脱敏失效（P0→P1 准 P0，未发生实际落盘 + 私有落点）· 仓储损坏组合（P0→P2，可重建 + 前提罕见 + 有意降级设计）。
3. **promote 无锁（⭐ 后端官 P1-8 + 对抗官 P2-4 两官同中）→ 已声明取舍消解**：`TradingController:1736-1738` 注释已显式记录「exists 检查与 write 之间无锁……单用户 UI 串行触发，概率极低；FileStorage.write 自身是原子写……为此加锁不值得」，主会话复核认为取舍合理 ⇒ 不入库（连同上条「主题硬编码」拆出为 P2-交易100）。
4. **「白名单成本可反推」（对抗官）→ 消解**：`TradingContextContributor`（:60-97）注入「现价 + 盈亏% + 仓位占比」，价格属设计 §11.1 明确允许出的类别（「代码·比例·价格可出」）⇒ 降提示级，不入库。
5. **官报其余低优先条目**（merged 三端适配 / evidence 门控例外 / 贡献者依赖等）主会话未逐条复验，未编入 REVIEW 编号——留待修复批或下一增量批次随行核对（不影响本批结论）。

## 三、修复记录（2026-10-06 修复批 · 14 条全修）

> 用户拍板「开修复批，逐条修复全部 14 项」。验证：后端全量 **202 suites / 2597 tests 全绿** · app **441** / web **410** / admin **73** 全绿（含修复批新增回归）。

| 编号 | 状态 | 落点（修了什么） |
|:--|:--|:--|
| P1-交易89 | ✅ 已修 | `TradingController:1811` 股数正则改「数字+股」独立匹配（`[\d,]+(?:\.\d+)?\s*万?\s*股` → `N 股`）——不再要求动词紧贴数字，「有研新材600股」形态不再漏 |
| P1-交易90 | ✅ 已修 | `TradingController:312-345` `/positions/import` 落盘前过 `gatePositions` 品种门（与统一入口同一判据）+ 回执如实上报 |
| P1-交易91 | ✅ 已修 | `BriefAppService:95-115` 缓存命中前判 `staleTradingCache`（生成时含交易域 ∩ 现无插件 → 作废重生成）；`cachedBriefIncludedTradingByUser` 记账。测试补 `plugins.invalidate()` 走真实路径（PluginService 30s TTL 缓存；admin 改插件必调 invalidate） |
| P1-交易92 | ✅ 已修 | app `feed_card.dart:446-483` + web `desktop_feed_card.dart:190-211` 加 `_normalizePushTitle` 剥标的前缀（空格锚点不误配）+ 补「午间知会」真标题（原「午间跟踪」保留兼容旧数据）+「今日操作确认」独立蓝徽章 |
| P1-交易93 | ✅ 已修 | `TradingUserRuleService:109-112` dismiss → `DISMISSED` 墓碑；重生成不复活（:155 ACCEPTED/CUSTOM 不覆盖）；消费侧不吐墓碑（:337） |
| P1-交易94 | ✅ 已修 | `TradingAnalysisService:355-358` trace 文案如实改「快照基准 + 转账净额」，删「不靠手填」 |
| P2-交易95 | ✅ 已修 | `UserRuleRepository` / `RoundBoundaryRepository` 加 `backupCorrupt`：读损坏（解析失败 + 结构不认识两分支）先备份 `.bak-corrupt` 再降级空；写侧全量重写不再毁残余 |
| P2-交易96 | ✅ 已修 | `TradingAuditRepository.appendAll`（整批一次读改写）+ `TradingAuditFileRepository` 实现；`TradingAppService` 三处收批（纠错 11 条 / updateTradeMeta / importCashQuery）——不再留半截孤儿 |
| P2-交易97 | ✅ 已修 | `TradingAuditFileRepository` per-user 16 条带锁（与 RoundBoundary 同模式）；`append` 委托 `appendAll` |
| P2-交易98 | ✅ 已修 | `TradingRoundService.pct` base 缺/0 → null（不再 ZERO）；peak/trough 比较加 null 守；R55 文案 null → 「—」 |
| P2-交易99 | ✅ 已修 | `TradingAuditFileRepository(FileStorage, Clock)` 注入；`filePath(LocalDate.now(clock))`；ClockConfig 提供 systemClock bean |
| P2-交易100 | ✅ 已修 | `TradingController:1746` + `safeTheme:1829`——`request.theme` 入参落到实处（缺省「交易复盘」逐字兼容旧行为；危险字符剔除 + 限长 32） |
| P2-admin1 | ✅ 已修 | `reviews_tab.dart`：弹窗改第三人称「用户「{userId}」的候选区」+ 回执透传后端 message；`system_page.dart` 传 userId；`api_dto.dart` 注释对齐 `{status, path, message}` |
| P2-工程16 | ✅ 已判 | 保留随批：三端锁对比 matcher/meta/test_api 与 app/admin 已提交版一致 · vector_math 为 web 侧 patch · `pub get` 稳定维持；提交说明注明 |

## 四、守卫与收工契约账（align FAIL 为本批欠账）

| 门禁 | 结果 | 说明 |
|:--|:--|:--|
| `ai-guard.sh` | **9 PASS / 1 HIT / 1 NOTE** | HIT = G2（`TradingAuditFileRepository:44` `LocalDate.now()`，即 P2-交易99）|
| `ai-guard-meta.sh` | **PASS** | 299 files |
| `ai-guard-feature.sh` | **PASS** | 37 功能（1 hint：feature-reference §9 未被引用）|
| **`ai-guard-align.sh`** | **FAIL（收工前必补）** | **A1** 11 个新端点未登记 api-spec · **A2** status.md 测试数 2462 vs 实测 **2584** · **A4** 端点数 173 vs 实测 **186** |
| **修复批复跑（2026-10-06）** | **guard.sh 10 PASS / 0 HIT / 1 NOTE** | G2 HIT 随 P2-交易99 修复消除（storage 层已无 now() 推路径）；META 300 files PASS · FEATURE 37 功能 PASS；align 仍 FAIL（= 收工契约账，未变）|

> 契约登记（A1/A2/A4）不编 REVIEW 编号——它们是本批**收工契约环节**的欠账，收工时（ship 前）补：api-spec 补 11 端点 + 升版、status.md 数字对齐。未补完归口门禁/对齐门禁将持续 FAIL。

## 五、教训（本批沉淀）

1. **正则脱敏必须拿真实数据当验收夹具**——设计「六类规则族」在纸上齐整，对真实写法（标的名夹在动词与数字之间）**全线漏过**；脱敏类功能的验收应从 `data/` 捞真实复盘跑一遍（同 P2-交易66 的教训：口径要拿真值验）。
2. **「标题透传」把结构契约退化成字符串**——推送标题带股票名前缀 + 前端按标题精确匹配 ⇒ 越新的数据越落灰、旧数据反而正常；跨端判据应回到结构字段（`type`/来源），标题只做展示。
3. **审查官无 shell 时，范围边界必须主会话对拍**——三官报告均声明无法 `git diff`；「新增 vs 存量」的边界结论只能由主会话给（本批官报的「无锁」误报即源于无法整体比对）。
4. **官报 P0 的复核价值**——2 条官报 P0 复核后 1 降 P1、1 降 P2，同时纠正 1 处误报、消解 1 处已声明取舍：报告成品率取决于主会话逐条复验（沿用 2026-10-04 的方法论）。

---
*三官均未修改仓库任何文件；全部发现由主会话逐条复验后落盘。**2026-10-06 修复批：14 条已全修**（含测试更新；验证：后端 2597 / app 441 / web 410 / admin 73 全绿）——编号为**预分配**：P1-交易89~94 · P2-交易95~100 · P2-admin1 · P2-工程16，待**本任务收工**时统一誊写进 `REVIEW.md` 并归档 `docs/records/audits/`（交接见 `LEDGER.md`）。*
