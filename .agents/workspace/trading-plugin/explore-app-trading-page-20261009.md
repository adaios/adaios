---
title: app 交易页结构测绘（2026-10-09）
description: apps/adai-app/lib/pages/trading_page.dart 结构地图——供「首屏四层」重构：build 顺序 / 构建器台账 / 打码状态 / 持仓模型 / 测试触点 / 入口
version: 1
created: 2026-10-09
updated: 2026-10-09
status: active
lines: 209
depends-on: []
related:
  - ./plan-app-20261009.md
  - ./design-uiux-20261007.md
  - ../../direction/rfc/20260924-trading-app-form.md
tags: [workspace, trading, app, explore]
---

# app 交易页结构测绘

> 派单 **D-20261009-01** · 角色 `explorer` · 2026-10-09。
> **只报告不改**（B7）：本文件**未改任何代码/现有文件**，仅新增本测绘文件。
> 对象：`apps/adai-app/lib/pages/trading_page.dart`（**4215 行**，单文件）。每条结论均给 `文件:行号`。
> 用途：为「首屏四层」（判断句 / 入账 / 持仓一行 + 就地展开 / 今天时态区）重构提供结构输入。

## 〇 概览：文件里的类与顶层函数

单文件里混着 6 个类 + 9 个顶层工具函数（重构时的「牵连面」）：

| 类 / 函数 | 行 | 职责一句话 |
|:--|--:|:--|
| `TradingPage`（StatefulWidget） | 24 | 页面外壳；注入 `api` + 测试钩子 `debugPickImages` |
| `_TradingPageState` | 37 | **本页全部状态与构建器都住这里**（约 3510 行） |
| `_AdviceSheet` / `_AdviceSheetState` | 3559 / 3574 | 点持仓卡弹出的「阿呆建议」底部弹层 |
| `_LotSummary`（数据类） | 3805 | 批次简版摘要（持仓卡副行用） |
| `_LotDetailSheet` | 3863 | 批次明细底部弹层 |
| `_LotTile` | 4013 | 批次弹层里的单批次行 |
| `_PushSettingsDialog` / `...State` | 4120 / 4131 | 推送设置弹窗（AppBar 铃铛入口） |
| 顶层函数：`_fmtMoney` 3764 · `_fmtMoneyFull` 3773 · `_fmtPrice` 3781 · `_trimZero` 3786 · `_fmtPriceInput` 3797 · `_fmtShortDate` 3820 · `_lotPnlPctText` 3851 · `_lotWeightedCost` 4001 · `_fmtSigned` 4008 | — | 格式化 / 计算工具 |

> 布局外壳：`Scaffold` 1561 → `AppBar` 1562（返回 1567 · 标题「交易」1573 · 铃铛「推送设置」1576 · 复盘 1585 · 刷新 1591）→ `body` 1594（`_loading` 转圈 1595 / `_error` 错误页 1596 / `RefreshIndicator` 1600 → `ListView` 1602）。

---

## ① `build()` 内 `ListView.children` 逐项顺序（1602–1700）

> `ListView` 起 **1602**，`children:` 起 **1605**，闭合 **1700**。

| # | 行号 | 项 | 渲染条件 |
|:--:|:--|:--|:--|
| 1 | **1608** | `_buildPrivacyBar()`（日期 · 今天 + 金额揭开 👁） | 恒在（**首屏第一项**） |
| 2 | 1609 | `SizedBox(height: 10)` | 恒在 |
| 3 | **1613–1616** | `_buildMarketHealthBanner(_marketHealth!)` + 间距 | `_marketHealth != null && _marketHealth!.hasIssue` |
| 4 | **1620–1623** | `_buildIntegrityBanner(_integrity!)` + 间距 | `_integrity != null && _integrity!.hasIssue` |
| 5 | **1625** | `_buildEntrySection()`（**焦点 1 · 每日入账**） | 恒在 |
| 6 | **1626–1629** | `_buildConfirmCard()` + 间距 | `_draft != null`（NL 出草稿） |
| 7 | **1630–1633** | `_buildExactForm()` + 间距 | `_showForm` |
| 8 | **1635–1638** | `_buildUploadPlaceholder()` + 间距 | `_shotsUploading` |
| 9 | **1640–1643** | `_buildCandidatesCard()` + 间距 | `_candidates.isNotEmpty` |
| 10 | **1645–1648** | `_buildConfirmReceipt()` + 间距 | `_lastConfirmReceipt.isNotEmpty` |
| 11 | **1650–1653** | `_buildDroppedNotice()` + 间距 | `_dropped.isNotEmpty` |
| 12 | 1654 | `SizedBox(height: 12)` | 恒在 |
| 13 | **1656** | `_buildDayStatusRow()`（今天没动 / 想动没动） | 恒在 |
| 14 | 1657 | `SizedBox(height: 16)` | 恒在 |
| 15 | **1659–1667** | 持仓标题行：`_sectionTitle('持仓'/'持仓明细')` + `共 N 只` + `_buildManageHint()` | 恒在（`N` 仅 `_positions` 非空时出） |
| 16 | **1670** | `_buildDailyPositionsHeader(_positionsDaily!)` | `_positionsDaily != null` |
| 17 | 1671 | `SizedBox(height: 8)` | 恒在 |
| 18 | **1672** | `_buildPositionCards()`（**焦点 2 · 持仓主区**） | 恒在（内部空态分流） |
| 19 | **1674–1677** | `_buildReviewBanner()` + 间距 | `_hasActivity && !_bannerDismissed` |
| 20 | 1678 | `SizedBox(height: 14)` | 恒在 |
| 21 | **1680–1693** | `_buildFoldSection('资金与配置', ...)`（内含 `_buildSnapshotCard` 1684 · `_buildCashSection` 1686 · 条件 `_buildMarketStageCard` 1690） | 恒在（默认收起） |
| 22 | **1694–1695** | `_buildFoldSection('今天的操作', [_buildDailySummary()])` | `_dailySummary != null` |
| 23 | **1697** | `_buildFoldSection('明天的计划', [_buildPlanSection()])` | 恒在 |

**首屏现状（对照目标）**：进屏可见序列 = 隐私条 →（可能两条横幅）→ 入账区（含四个条件卡）→ **今天时态行** → 持仓标题 → **持仓整列** → 折叠区。**没有「先说结论/判断句」的一行**；多只持仓会把「今天的操作 / 明天的计划」压到第 2~3 屏（与 `plan-app-20261009.md` §一 判断一致）。

---

## ② 关键构建器台账（起始行 · 职责 · 依赖状态字段）

> 全部在 `_TradingPageState` 内；状态字段定义集中在 **37–173**（可回查）。

| 构建器 | 起始行 | 职责一句话 | 依赖状态字段 |
|:--|--:|:--|:--|
| `_buildDayStatusRow` | **409** | 「今天」一行：轻说明 + 两个一键盘点 chip | `_todayDayStatus` 67 · `_dayStatusSaving` 68 · `_dayStatusMsg` 69 |
| `_buildPlanSection` | **655** | 次日计划：写 / 看已记 / 收盘对账（含计划外操作） | `_planView` 61 · `_planLoading` 62 · `_planMsg` 63 · `_planDate` 49 · `_planLinesCtrl` 47 · `_planNoteCtrl` 48 |
| `_buildMarketHealthBanner` | **744** | 行情链路降级横幅（橙，可展开看取数链/时刻） | `_marketHealth` 172 · `_marketHealthExpanded` 173 |
| `_buildIntegrityBanner` | **806** | 账实不符横幅（drift / gaps / 未进持仓，可展开） | `_integrity` 165 · `_integrityExpanded` 166 |
| `_buildMarketStageCard` | **873** | 活跃市值区间切换（多头红 / 空头绿） | `_marketStage` 158 · `_marketStageExists` 159 · `_marketStageLoaded` 160 · `_marketStageSaving` 161 |
| `_buildEntrySection` | **1719** | **焦点 1** 每日入账：截图入账按钮 + 精确填写入口 + NL 输入条 | `_shotsUploading` 131 · `_showForm` 109 · `_nlCtrl` 98 · `_parsing` 99 |
| `_buildPrivacyBar` | **1811** | 隐私条：`MM-dd · 今天` + 金额揭开开关 | `_amountsRevealed` 149 |
| `_buildUploadPlaceholder` | **1837** | 上传在途占位卡（VLM 最坏 90 秒） | `_shotsUploading` 131 |
| `_buildFoldSection` | **1858** | 折叠容器（默认收起，标题行摘要**不含金额**） | `_foldOpen` 151 |
| `_buildCandidatesCard` | **1899** | 当日截图候选（全部确认 / 全部忽略 / 收起 / 逐行） | `_candidates` 127 · `_candidatesCollapsed` 135 · `_candidatesConfirming` 132 · `_candidatesDiscarding` 136 · `_confirmDialogOpen` 140 |
| `_buildConfirmReceipt` | **1991** | 上一次确认的逐笔回执（可关闭） | `_lastConfirmReceipt` 143 |
| `_buildDroppedNotice` | **2029** | 「这张截图有 N 行没记」橙色可展开 | `_dropped` 154 · `_droppedExpanded` 155 |
| `_buildConfirmCard` | **2308** | NL 解析结果确认卡（数量/价格/方向可改） | `_draft` 102 · `_confirmVolumeCtrl` 103 · `_confirmPriceCtrl` 104 · `_confirmDirection` 105 · `_confirming` 106 |
| `_buildExactForm` | **2380** | 精确填写表单（含隐藏式止损/买点 + 双按钮） | `_symbolCtrl` 110 · `_priceCtrl` 111 · `_volumeCtrl` 112 · `_showPlan` 113 · `_stopLossCtrl` 114 · `_buyPoint` 115 · `_submitting` 117 |
| `_buildDailySummary` | **2556** | 当日交易复盘聚合（今日 N 笔 · 买卖 · 时段） | `_dailySummary` 94 |
| `_buildSnapshotCard` | **2590** | 账户快照卡（总资产/总盈亏/可用/可取/市值/本金 + 日周月盈亏） | `_account` 79 · `_snapshot` 70 · `_pnlPeriods` 81 · `_amountsRevealed` 149 |
| `_buildCashSection` | **2744** | 资金区（转入 / 转出 / 设置本金） | `_account` 79 · `_transferBusy` 84 · `_amountsRevealed` 149 |
| `_buildPositionCards` | **2958** | 持仓列表（空 → `_buildEmptyPositions`） | `_positions` 39 |
| `_buildDailyPositionsHeader` | **2967** | 持仓区头部：仓位% · 现金% + notes 轻提示 | 参数 `PositionsDailyResponse`（`_positionsDaily` 76） |
| `_buildPositionCard` | **2994** | 单只持仓行（比例主位 / 金额次位 + 数量成本现价止损） | 参数 `PositionItem` · `_amountsRevealed` 149 |
| `_buildPositionDailyRow` | **3057** | 持仓行第三行：当日盈亏 / 今日涨跌 / 仓位 | `_positionsDaily` 76 · `_amountsRevealed` 149 |
| `_buildLotRow` | **3212** | 批次简版行（批次数 + 最近买入 + 含底仓 / 破止损徽标） | `_lots` 88 |
| `_buildEmptyPositions` | **3253** | 空态：暂无持仓 + 引导去电脑端导入 | — |
| `_buildManageHint` | **3277** | 持仓标题右侧「管理」入口（跳 web 指引） | — |
| `_buildReviewBanner` | **3290** | 复盘横幅（生成 / 查看 / 关闭） | `_hasActivity` 120 · `_bannerDismissed` 121 · `_reviewGenerated` 122 · `_reviewing` 123 · `_lastReview` 124 |
| `_buildError` | **3366** | 整页错误态 + 重试 | `_error` 72 |
| `_buildReviewDialog` | **3482** | 复盘结果对话框 | 参数 `ReviewResponse` |

> 非 `ListView` 直接子项的相邻件：`_sectionTitle`（1726）· `_candidateRow`（2086）· `_showLots`（3095 批次弹层入口）· `_openProfitCalendar`（2919，跳 `profit_calendar_page.dart`）· `_showAdvice`（1501，弹 `_AdviceSheet`）· `_showWebGuide`（1518）· `_openPushSettings`（3393）。
>
> **已废弃状态（重构可顺手清）**：`_expandedPositions`（42，声明后全文件无引用）与 `_watchlist`（44，仅在 343 赋值、build 里无渲染）——后者是 2026-08-22「自选挪 web」的残留；前者正是目标「持仓一行 + 就地展开」要用的语义位，目前空置。

---

## ③ 打码 / 隐私：全部状态与开关

**只有一个金额隐私开关**（比例不受影响，见下）：

| 项 | 位置 | 说明 |
|:--|:--|:--|
| 状态字段 | `_amountsRevealed` **trading_page.dart:149**（`bool`，**默认 `false` = 打码**） | 「金额私密，比例无所谓」（用户原话见 145–148 注释） |
| 唯一写点 | `_buildPrivacyBar` **1819** `setState(() => _amountsRevealed = !_amountsRevealed)` | 点 👁 切换 |
| 遮罩助手 | `_moneyHome(double)` **1808**：`_amountsRevealed ? _fmtMoneyFull(v) : '••••'` | 首页金额统一入口 |
| **持久化** | **无** | 全文件无 `SharedPreferences` / 无落盘——**纯会话内**，退出页面即恢复打码（grep `SharedPreferences` 命中 0） |

**谁读 `_amountsRevealed`**（共 8 处）：

- `_buildSnapshotCard`：总资产 2624（经 `_moneyHome`）· 总盈亏 2637–2639 · 可用/可取/市值 2653–2655（经 `_moneyHome`）· 当日盈亏 2657–2659 · 本金 2663
- `_buildCashSection`：现金 / 总资产 2768（经 `_moneyHome`）
- 收益日历摘要：**2949**（`'••••$pct'`，比例保留）
- `_buildPositionCard`：累计盈亏 3000 · **数量/成本 3033–3034**（数量也打码，防「数量×现价=市值」反推；止损价 3036 保留）
- `_buildPositionDailyRow`：当日盈亏 3062
- `_buildPrivacyBar` 自身 1819/1824/1827（图标 + 文案「看金额 / 收起金额」）

**「两层」口径（注释 145–148、1806–1808）**：① 首页（含折叠区展开态）默认 `••••`，点 👁 会话内揭开；② 「主动进入」的容器（**批次明细弹窗 `_LotDetailSheet`、转账/本金对话框**）**无条件显示**真实金额，不经 `_moneyHome`。**比例（涨跌% / 盈亏% / 仓位%）始终明文**（`_trendColor` 3145 · `_buildPositionDailyRow` 3063–3064）。

---

## ④ 持仓数据模型 `PositionItem`

| 项 | 位置 |
|:--|:--|
| **定义** | `apps/adai-app/lib/services/api_service.dart:2282`（`class PositionItem`） |
| 构造 | `api_service.dart:2295` · `fromJson` `api_service.dart:2307` |

| 字段 | 类型 | 语义 / 注意 |
|:--|:--|:--|
| `symbol` | `String` | 代码 |
| `name` | `String` | 名称 |
| `quantity` | `int` | 持仓股数 |
| `avgCost` | `double` | 成本价 |
| `currentPrice` | `double` | 现价 |
| `marketValue` | `double` | 市值 |
| `pnl` | `double` | 累计盈亏（金额） |
| `pnlPercent` | `double?` | 浮盈%：**负/零成本时为 null** → 前端渲染「—」，**不回落 0** |
| `stopLossPrice` | `double?` | 止损位（可空 → 文案「未设止损」） |

**来源端点 / DTO**：

| DTO | 位置 | 端点 | 说明 |
|:--|:--|:--|:--|
| `PositionsResponse` | `api_service.dart:2178` | `GET /api/v1/trading/positions` | `{positions: [PositionItem...]}`（旧口径） |
| `PositionsDailyResponse` | `api_service.dart:2204` | `GET /api/v1/trading/positions/daily` | `positions` 元素形状同 `PositionItem`；另有 `daily{symbol→DailyPositionDto}` · `totalPositionRatio` · `cashRatio` · `notes` |
| `DailyPositionDto` | `api_service.dart:2266` | （同上 `daily` 值） | `todayPnl` · `yesterdayClose` · `dayChangePct` · `positionRatio`（**每个字段都可能 null**） |

**取数策略**：`_fetchPositions`（**trading_page.dart:245**）**优先 daily**，失败静默降级回旧端点，并把 `_positionsDaily` 置 null（宁可不说，也不拿陈值）；持仓主数据必须可见。

---

## ⑤ 测试触点（首屏重排会变红的锚点）

> 全仓只有 **2 个测试文件** import 本页（`grep -rl trading_page.dart test/`）：**本页回归网很薄**（与 `plan-app` §九 风险判断一致）。
> **本页没有任何 `Key(...)` 锚点**（`grep "Key(" trading_page.dart` 命中 0）——所有断言都靠**文案 / 语义**，重排即有碎片化风险。

| 文件 | 相关行 | 锚点（文案 / 语义） | 重排风险 |
|:--|:--|:--|:--|
| `apps/adai-app/test/pages_widget_test.dart` | 12（import）· `group('TradingPage')` **322–2017** | — | 整组高耦合 |
| 〃 | **400 / 1082** | `tap(find.text('看金额'))` / 「收起金额」 | **无 `ensureVisible` 直接 tap** → 隐私条若离开首屏即失败 |
| 〃 | 403–407 · 421–423 · 434–436 · 444–446 | `'+756.00'` `'+2.14%'` `'33.27%'` `'仓位 62.24% · 现金 37.76%'` · `'—'`×3 | 断言具体数值文案与总仓位行 |
| 〃 | 490–517 | `find.byTooltip('推送设置')` · `SwitchListTile`×12 | 依赖 AppBar 铃铛入口仍在 |
| 〃 | **519–546** | `ensureVisible`+`tap('资金与配置')` · `'总资产'` `'贵州茅台'` `'持仓明细'` | 折叠标题文案 + 展开手势 |
| 〃 | 552–584 | `'阿呆发现账对不上'` `'看明细'` `'收起'` | 账实横幅文案 |
| 〃 | 628–700 | 同上折叠区 + `'今天的操作'` | 第二个折叠标题 |
| 〃 | 1397–1512 | 持仓卡：名称/代码 + 盈亏大字 + 盈亏% + 批次简版 | 持仓行文案结构 |
| 〃 | 1708 · 1745 · 1763 · 1776 | 建议弹层 · 复盘横幅 · `'暂无持仓'` · 活跃市值「空头/多头」 | 各区块文案 |
| 〃 | **1930–2014** | 今天时态：`'今天没动'` `'想动，没动'` `'今天记的是：没动'` | 今天行的 chip 文案与回执 |
| `apps/adai-app/test/market_data_health_test.dart` | 8（import）· `pumpTrading` **127** | — | — |
| 〃 | 139–160 | `'阿呆最近拿不到行情'` `'看明细'` `'取数链：…'` | 行情横幅文案 |
| 〃 | **182** | `expect(find.textContaining('· 今天'), findsOneWidget)` | **依赖隐私条首屏可见**（重排若移走隐私条即失败） |
| 〃 | 190–215 | 账户卡大数不溢出：`tap('看金额')` + `ensureVisible`+`tap('资金与配置')` | 隐私条 + 折叠区 |

**最脆的三处**：① 隐私条（`看金额`）——多个用例无滚动直接点；② 两个折叠标题（`资金与配置` / `今天的操作`）——`ensureVisible` 依赖它们在**同一 ListView** 里；③ `'持仓明细'` / `'持仓'` 标题与「共 N 只」——标题文案随 `_positions` 空/非空切换。

---

## ⑥ 入口：谁进的 `TradingPage`、有没有传参

| 项 | 位置 | 说明 |
|:--|:--|:--|
| **唯一入口** | `apps/adai-app/lib/pages/launcher_page.dart:555–559` | `_pluginSlot(...)` 的 `onTap`：`Navigator.push(context, MaterialPageRoute(builder: (_) => TradingPage(api: widget.api)))` |
| 槽位定义 | `launcher_page.dart:549–560` | 「交易」槽位（`icon: Icons.show_chart` · `subtitle: '持仓 · 记录'`），仅**启用 trading 插件**时可见（`_pluginGroup` 门控 540–547） |
| 传参 | **仅 `api`（必填）** · `debugPickImages`（测试钩子，`@visibleForTesting`，仅测试注入） | 构造见 **trading_page.dart:32** |
| `main_page.dart` | **不引用** | `grep TradingPage main_page.dart` 命中 0（World A 聊天壳不直达） |
| Launcher 的来源 | `apps/adai-app/lib/main.dart:479–495` | `RootApp` 切到插件世界（World B）时构建 `LauncherPage(...)`，与 `main_page` 共用同一 `ApiService` 实例 |
| 本页再往下的跳转 | `_openProfitCalendar` 2919 → `profit_calendar_page.dart`；`_showAdvice` 1501 → `_AdviceSheet`；`_showLots` 3095 → `_LotDetailSheet`；`_openPushSettings` 3393 → `_PushSettingsDialog` | 均在本文件内或相邻页，无路由参数透传 |

**给重构的一句话**：改首屏形态**不影响入口签名**（仍是 `TradingPage(api:)`），但「首屏四层」的新骨架要么落进 `_TradingPageState.build` 的 `ListView.children`（1605–1700），要么把四层抽成独立 widget 文件后再挂——两种都不需要动 `launcher_page.dart`。
