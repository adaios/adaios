---
title: app 端编码段增量深审（对抗找茬官 · 批 1–5 + 推送文案批）
description: D-20261009-05 对抗找茬报告——假设本批一定有坑，从「哪里会炸 / 用户哪里会骂 / 边界哪里漏」攻击性找茬（只报告不改）
version: 1
created: 2026-10-09
updated: 2026-10-09
status: active
lines: 101
depends-on: []
related:
  - ./scope-app-20261009.md
  - ./design-app-20261009.md
  - ./design-push-copy-20261009.md
tags: [review, adversarial, trading, app]
---

# app 端编码段增量深审（对抗找茬官）

**派单**：`D-20261009-05` · **角色**：`ai-adversarial-reviewer` · **日期**：2026-10-09
**审核对象**：`apps/adai-app/` 自 `e87f926e` 之后的提交（批 1–5）+ 后端推送文案批
**判据**：假设本批一定有坑，专挑客观官视角外的最危险的点。**只报告不改**（B7）。

## 结论

**无 P0**（无直接数据丢失）。**P1 × 2**：

1. 截图入账的「客户端指纹去重」是**显示层的假修复**——它挡不住它声称要挡的「重复入账」，却会把后端**刻意保留**的同价同量**真分单**在 UI 上重新并掉（P0-交易59 在展示层复发）；
2. 后端对无标的推送发的深链 `trading:today` 被当成股票代码 → 早/午/尾盘、收盘小结、次日计划这一整类推送**落点错、且被替换成了交易页顶部**（P2-APNs1 的「定位那条」高亮随之永不触发）。

另有 **P2 × 3**（首页打码态露现金金额 · 两端「你的线」口径不一 · 新加 `_loadWatchlist` 无代际令牌）与 **P3 × 5**（等值破线口径 · 滞后注释 · 设置页失败无提示 · 尾盘收尾句 · 连点叠页）。

> 只报告不改（B7）。以下每条都给「位置 / 攻击一句 / 后果 / 如何证伪」，不重复客观官（前端 / 后端）视角。

## 攻击面清单

### P1-1 · 截图入账「客户端指纹去重」是显示层假修复，还会误并真分单

**位置**：`apps/adai-app/lib/pages/trading_page.dart:1175`（去重循环）· `:1183`（丢弃文案）· `:1227`（`_candidateFingerprint`）；后端对照 `services/adai-core/src/main/java/com/adaiadai/core/infrastructure/storage/TradeLogRepository.java:182`（`appendBatch` 用 `sameTrade` 去重）· `services/adai-core/src/main/java/com/adaiadai/core/application/TradeLogCollectService.java:290`（`confirm` 落库的是后端**全量**候选）。

**攻击一句**：这处改动把自己说成「后端跨截图去重失效后的补丁（防重复入账）」，但两件事都不成立——① 「后端跨截图去重失效」是**错的**：`collect()` 逐张调 `appendBatch`，其去重基准是**已落盘的当日候选文件**（`baseline = findByDate`），所以一张一张发与一次发三张，后端跨图去重**完全一样**；② 它**删不了后端候选**：`confirm` 落库的是后端 `todayCandidates` 全量，前端这一屏折掉几条都不影响落库结果。它真正做的只有一件事——把「**每张图的响应都返回全量候选快照**」这件事在界面上折回一张（否则三张的响应会把候选列表乘成三份）；而它用的指纹**不含 `tradeTime`**，于是连后端刻意保留的同价同量**真分单**也一并折掉。

**后果（攻击路径）**：

- 同一笔成交出现在两张截图、OCR 出的成交时刻有出入 → 后端 `sameTrade` 因含 `tradeTime`（P0-交易59 的解药）判为两笔并**都留在候选文件**，客户端指纹（`direction|symbol|price|volume|tradeDate`，**刻意不含 `tradeTime`**，而 DTO 里其实有该字段：`api_service.dart:3089`）把第二条剔出 `_candidates` → UI 只显示「1 笔」，用户点「都记上（**1 笔**）」→ `confirm` 却对后端两条一起落库 → **同一笔被记两遍**，而用户从头到尾只看见 1 笔、**没有机会丢掉那条多余的**。这条「防重复入账」正是本批声称要补的洞，实际没补上（2026-09-15「600536 记成 1000 股」的复发路径仍在）。
- 反向：同价同量的**两笔真分单**（000831 两笔各 200 股 @53.300 的生产实据）→ 指纹相同 → UI 显示 1 笔，而丢弃明细写「1 笔在两/多张截图里重复出现（同一笔只留一条）」→ **把真实第二笔谎报成重复**，与红线「宁可多标可疑，不可放过一笔」相悖。
- 数字当场自相矛盾：卡片「候选 1 笔」/ 按钮「都记上（1 笔）」→ 回执「**2 笔**记进账了（持仓与现金已更新）」。

**复发信号**：坑族「吞笔 / 重复入账」在**展示层**复发；本批新代码（客户端去重）是那条无处方。

**如何证伪**：注入两张各含同一笔（时刻差 1 秒）的截图 + 一张含同价同量真分单的截图，断言「卡片笔数 = `GET /trading/trade-log` 条数 = `confirm` 的 `confirmed`」三者一致（现态：卡片 1、后端 2、confirmed 2）。

### P1-2 · `trading:today` 被当成股票代码：一整类推送落点错、高亮失效

**位置**：`apps/adai-app/lib/utils/trading_deeplink.dart:15`（`:` 后整段当 symbol）· `apps/adai-app/lib/main.dart:401-409`（非空即压 TradingPage）· 对照后端 `services/adai-core/src/main/java/com/adaiadai/core/kernel/push/PushChannel.java:119-125`（无标的一律 `trading:today`）。

**攻击一句**：后端对**无标的**的推送（早/午/尾盘、收盘小结、次日计划…）深链就是 **`trading:today`**，而 `parseTradingDeepLink('trading:today')` 返回字符串 `'today'`（非 null）→ 落进 `focusSym != null` 分支 → **压一个 focusSymbol='today' 的交易页**；`main.dart:412` 自己的注释却把这些类型划进「保持原行为：回 Feed + 定位那条」，**代码与注释当场打架**。

**后果（攻击路径）**：用户点「今天这样」（收盘小结）→ **不回到那条推送卡**、`_pushDeepLink` 的 2.5 秒高亮（P2-APNs1）**永不触发**，而是落在**交易页顶部**；`focusSymbol='today'` 在持仓里必然找不到 → `_applyFocusSymbol` 直接 return，**一只票都不展开**。用户「点的是那条消息，看到的是另一页」，落地页与承诺不符。测试盲区坐实：`test/trading_deeplink_test.dart` **无 `today` 用例**（只测真代码 / 空 / 畸形），所以这处无人守。

**如何证伪**：加 `expect(parseTradingDeepLink('trading:today'), isNull)`（或明确改为落到交易页的「今天」区）+ 一条通道级「点收盘小结」用例，断言落点。现态第一条断言 FAIL。

### P2-1 · 首页打码态下，确认回执明文显示现金变动金额

**位置**：`apps/adai-app/lib/pages/trading_page.dart:1349`（`changes.add('现金 …' + _fmtMoneyFull(d.abs()))`）→ `:1351` 并入 `_lastConfirmReceipt` → `:2330 _buildConfirmReceipt()` 逐行原样 `Text('· $line')`。

**问题**：这张回执卡在**首页**，且**不读 `_amountsRevealed`**（对比同页 `_moneyHome`）。设计红线 I-4「数量与成本默认 `••••`」+ 现存约定「首页（含展开态）默认打码」被这条**本批新加**的路径绕过。

**后果**：打码态（默认）下用户确认一笔入账后，页面直接出现「现金 +12,345.67」明文——递手机 / 旁人一瞥即露现金额。

**如何证伪**：在现有「隐私 A2」用例后追加「确认一笔入账 → 首页不得再出现 `\d,\d{3}` 金额」（现态会命中）。

### P2-2 · 两端「你的线」不是同一条线（app 用原始止损，推送用 effectiveStopLoss）

**位置**：app 侧 `PositionItem.fromJson` 读**原始** `stopLossPrice`（`apps/adai-app/lib/services/api_service.dart:2316`）→ `readHoldLine`（`apps/adai-app/lib/utils/trading_verdict.dart:128`）· 持仓行；后端侧 `MarketAlertService.message` / `TradingSessionPushService` 用 `p.effectiveStopLoss()`，即 `Position.effectiveStopLoss()` = `max(用户线, 系统风险预算线)`（`services/adai-core/src/main/java/com/adaiadai/core/domain/trading/Position.java:70`）。

**后果**：未设人工止损、但有系统算线的票，**推送说「破了你的线 · X」，而 app 那一行写「没设线」**、判断句也说「不用动」——用户被指向互相矛盾的两处。且推送文案（B 组）自称「与他自己写的止损位同基准」，实际取 effective，文案与取值也不符。

**如何证伪**：造一个 `stopLossPrice=null` 且 `computedStopLossPrice` 有值的持仓，对拍 app 行文案与后端推送文案是否指向同一个数。

### P2-3 · 新加的 `_loadWatchlist` 无代际令牌（与同页 `_aux/_lots/_candidates` 不一致）

**位置**：`apps/adai-app/lib/pages/trading_page.dart:374`（直接 `setState`），而同页 `_auxGen/_lotsGen/_candidatesGen` 都在防乱序覆盖。

**后果**：30 分钟自动刷新与下拉刷新重叠时，先发的旧响应可能后到并覆盖新值（影响仅「自选」一段，低概率）——本批新写入路径未纳入既有守卫。

**如何证伪**：与「代际令牌」既有用例同法注入两个乱序响应，断言最终落在后发的那个。

### P3（观察 · 供主链取舍）

1. **等值破线两端口径分叉**：app `readHoldLine` 用 `c <= s`（`trading_verdict.dart:133`，单测写明「触线即提醒」是有意），后端 R66 `DefaultTradingRuleEngine:55` 用严格 `currentPrice < stopLoss` → **恰好等于止损价**时 app 说「破了你的 X」、后端不推。
2. **`_buildTodaySection` 注释滞后**（`trading_page.dart:2089`）：注释仍写「推送设置与复盘历史…没有独立页…故不放进来」，而紧随其后的 `Row` 两个入口都已放进去（批 4）。
3. **`PushSettingsPage` 未传 `onToggleFailed`**（`apps/adai-app/lib/pages/push_settings_page.dart`）：`onToggle` 返回的失败原因无人接（对话框版会弹提示）→ 设置页里改开关失败是「开关不动但不说为什么」。
4. **A3 尾盘收尾句无条件追加**（`services/adai-core/src/main/java/com/adaiadai/core/application/TradingSessionPushService.java:712`）：无论当天有没有写计划都发「按你昨晚写的办」——无计划时引用了不存在的计划。
5. **提醒落点连点会叠页**：每次点通知都 `Navigator.push` 一个**新的** `TradingPage`（各带 30 分钟 `Timer`，`main.dart:407`）；连点不同推送会叠多层，返回要连按多次。

## 建议下一步验证动作

1. **截图入账**：两张各含同一笔（时刻差 1 秒）的图 + 一张含同价同量真分单的图，跑一遍，对齐「卡片笔数 = `/trade-log` 条数 = `confirm.confirmed`」。
2. **深链**：补 `trading:today` 单测 + 一条「点收盘小结」的通道级用例，断言落点（Feed 高亮 or 交易页今天区）。
3. **隐私**：确认一笔入账后，断言首页零明文金额（`\d,\d{3}`）。
4. **你的线**：造 `stopLossPrice=null` 且 `computedStopLossPrice` 有值的持仓，对拍 app 行 vs 推送文案。
