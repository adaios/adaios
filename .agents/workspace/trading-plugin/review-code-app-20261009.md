---
title: app 端编码段增量深审（前端官 · 批 1–5 + 深链落点）
description: D-20261009-03 审核报告——app 端交易页形态重构（首屏四层）/ 判断句纯逻辑 / 提醒落点深链 / 推送设置与复盘历史两屏 / 截图入账三卡点；无 P0 · P1×2 必修 · P2×3 · 只报告不改（B7）
version: 1
created: 2026-10-09
updated: 2026-10-09
status: active
lines: 131
depends-on: []
related:
  - ./scope-app-20261009.md
  - ./design-app-20261009.md
  - ./review-app-uiux-20261009.md
tags: [review, frontend, trading, app]
---

# app 端编码段增量深审（前端官）

**派单** `D-20261009-03` · **角色** `code-frontend-reviewer` · **日期** 2026-10-09 · **只报告不改**（B7）
**审核对象** `apps/adai-app/` 自 `e87f926e` 起的 4 个 app 提交：`dc2ddece` 形态重构（首屏四层）+ 截图入账三卡点 · `5140691c` 批 4 两屏 · `0330da10` 批 5 提醒落点 · `61b54b03` 走查工具化
**判据** 只判改动**对不对**（正确性 · 边界 · 完备性），不判「该不该做」（已拍板）

## 一、结论：⚠️ 有条件通过 —— 无 P0 · P1×2 必修

四块基本都立得住：判断句与「与你的线」抽成了**纯函数**并被单测钉死；两个新屏的失败态是「如实说 + 重试」，没有假空壳；六条新调用的端点路径与后端逐条对得上；`flutter test` **474 全绿**（本官实跑复核，非照抄）。两处**必须改**：① 深链解析把后端的**哨兵值** `trading:today` 当成股票代码 → **每天最频繁的时段推送全部改道**；② 分张提交新补的客户端指纹去重会把「同价同量的真分单」当重复丢掉（与 P0-交易59 的 `tradeTime` 机制正面冲突）。另有 P2×3（其中 1 条属口径取舍 → 升人）。

### 已核对通过（不必再查）

| # | 项 | 证据 |
|:--|:--|:--|
| 1 | 判断句**不新增 LLM 调用**、不编数据 | `trading_page.dart:2004-2030` 只喂确定性字段；`trading_verdict.dart:67-111` 取不到换句/不说 |
| 2 | 「与你的线」缺数据不给假距离 | `trading_verdict.dart:128-139`（线缺→没设线 · 现价缺→没取到现价） |
| 3 | 展开态金额仍默认打码、现价与止损明文 | `trading_page.dart:3403-3415`（数量/成本/浮盈走 `_amountsRevealed`） |
| 4 | 新屏失败不伪装空态 | `push_settings_page.dart:41-45` · `review_history_page.dart:41-45`；用例断言「还没有复盘」**不出现** |
| 5 | 推送设置清单**唯一一份**（没被抄成两份） | `widgets/push_settings.dart:35-48`；`main_page.dart` 改调 `PushSettingsDialog`，旧私有类已删 |
| 6 | 六条端点路径与后端一致 | app `watchlist`/`reviews`/`review?date=`/`reviews/{date}/promote`/`push-settings`/`push-settings/{type}` ↔ `TradingController.java:776,1051,1060,1713,1729,1769` |
| 7 | 分张提交后端兼容（一张也收） | `TradingController.java:1311-1330` 只校验非空，不校验张数 |
| 8 | 测试是真断言、且真绿 | 实跑 `flutter test` → **474 All tests passed**；新用例断的是业务字符串（`都记上（1 笔）` / `这次过后：持仓 0 → 1 只` / `卖出 500 股，我这儿只有 100 股`） |
| 9 | 生命周期守卫齐 | 新屏每个 await 后都有 `mounted`（`push_settings_page.dart:36,42` · `review_history_page.dart:34,42,55,73,80`） |

## 二、问题清单

### P1-1 · 深链哨兵 `trading:today` 被当成股票代码 → 每天的时段推送全部改道

- **位置**：`utils/trading_deeplink.dart:12-16`（`indexOf(':')` 后把**任意非空尾巴**当代码返回）→ `main.dart:401-411`（`focusSym != null` 就 push `TradingPage` 并 `return`，**跳过**回 Feed 定位）
- **契约事实**：后端 `PushChannel.deepLink()` 的默认分支就是 `trading:today`（`PushChannel.java:119-126`）——**早盘 / 午间 / 尾盘 / 收盘小结 / 收盘确认 / 计划提醒 / 交易日志归集确认**全是这个值（`symbol` 为空即走默认分支）。
- **本仓库既有口径**：`main_page.dart:1572` 早就把 `today` / `review` 当**哨兵**（不猜、只滚到底）；`main.dart:414` 那行注释也写着「其他类型（收盘小结 / 计划提醒…）保持原行为」——**代码与自己的注释相反**。
- **影响**：① 点时段推送不再回 Feed 高亮那条卡片（P2-APNs1 的行为静默失效，`_locateAndHighlight` 对该批成死路）；② 落到交易页但 `focusSymbol='today'` 永远匹配不到持仓 → **落点等于没落**；③ `_focusedOnce` 被哨兵吃掉（`trading_page.dart:235-247`）。
- **为什么测试没拦**：`test/trading_deeplink_test.dart` 只覆盖 `trading:600206` / 空 / 畸形，**没有 `trading:today` 一例**；`main.dart` 那个分支本身无测试。
- **修法（二选一，都很小）**：① 解析器显式排除哨兵（`if (symbol == 'today') return null;`，注释指向 `PushChannel.deepLink()`）+ 补一例单测；② `main.dart` 改为**按 `type` 判定**（只有 `stop-loss / near-stop-loss / loss / gain / break-cost` 才 push 交易页），深链只当参数——这条更稳，不惧深链格式漂移。

### P1-2 · 客户端指纹去重把「同价同量的真分单」当重复丢掉（与 P0-交易59 正面冲突）

- **位置**：`trading_page.dart:1227-1230`（`_candidateFingerprint` = 方向｜代码｜价｜量｜成交日，**刻意不含 `tradeTime`**）→ 调用点 `trading_page.dart:1173-1183`（命中即 `dupInBatch++`，**不进 `merged`**）；丢弃文案 `:1183` 写「N 笔在两/多张截图里重复出现（同一笔只留一条）」
- **冲突事实**：`TradeLogCandidateDto.tradeTime` 的存在理由正是**区分同价同量的分单**——`api_service.dart:3086-3089` 写明生产实据「**000831 两笔各 200 股 @53.300，10:03:44 与 10:04:09**」，那是 P0-交易59 专门修的。新指纹丢掉这一维，等于让那条 P0 修复失效。
- **触发面**：不止跨图。**同一张截图**里两笔同价同量同日的真分单也会被折叠成一条（循环在单图内就 `seenFingerprints.add`）。
- **影响**：用户真实成交**被少记**，且提示说「同一笔只留一条」——把漏记说成了去重，与红线「宁可多标可疑，不可放过一笔」（`design-app §三`）直接冲突。
- **测试把错行为钉死了**：`test/pages_widget_test.dart` 卡点① 用例（3 张 → 1 条候选）就是这个语义；`calls==3` 那半是对的，`1 条候选` 那半把风险固化了。
- **修法（建议 ①+②）**：① 指纹**纳入 `tradeTime`**，且只在**跨图**去重（同一张图内不折叠）；② 把「指纹命中」从**丢弃**降级为**可疑**（`_candidateSuspect` 返回「这看着像已经记过的同一笔」）——候选仍留在列表上，由用户点「丢掉」还是「都记上」决定。
- **级别说明**：若按「用户成交被漏记」口径可视作 P0（数据丢失）；本官按「有明示丢弃明细、且仍在待确认态未落库」判 **P1**，是否升 P0 交主链裁。

### P2-1 · 判断句/行内「破线」用**人工止损**，web 与后端用**生效止损**（同账号可能出现相反结论）→ ★升人

- **位置**：`trading_page.dart:2004-2030`（判断句破线条数）与 `trading_verdict.dart:128-139`（行内「破了你的 X / 离你的 X 还有 Y%」）都吃 `PositionItem.stopLossPrice`
- **app 的 DTO 只解析了人工位**：`api_service.dart:2293,2316`（`stopLossPrice`）——**没有** `effectiveStopLoss`
- **两端事实**：web 的 DTO 同时有 `stopLossPrice` 与 `effectiveStopLoss`（`apps/adai-web/lib/services/api_service.dart:2662,2668,2698,2703`），web 页面一律用生效位（`apps/adai-web/lib/pages/trading_page.dart:2852,2879,2244,1491`）；后端判定（R66 / 接近止损预警 / 推送 / 复盘）也统一用生效位 `max(人工, 计算)`（`Position.java:69-74` · `DefaultTradingRuleEngine.java:45-63`）
- **影响**：当「计算止损 > 人工止损」时，app 判断句说「不用动」，同一条推送（后端按生效位）却说「破了你的线」——**首屏结论与刚收到的通知自相矛盾**，正是 I-1 最想避免的「结论不可信」。
- **口径取舍 → 不自行拍板**：产品文案一律说「**你写的**线 / 你写的止损」，按这句读，app 用人工位**更忠实于文案**；但系统判定链（web + 后端）用的是生效位。请人一句话定「**你的线 = 人工位还是生效位**」，随后 app / web / 推送文案一并统一。
- **附带小项（同一处口径）**：边界也差一点——app 用 `现价 <= 止损`（`trading_verdict.dart:133`，单测还钉了「触线即破」），后端用严格 `现价 < 止损`（`DefaultTradingRuleEngine.java:55`）。等值那一刻 app 说破、推送说没破。统一时一并定。

### P2-2 · 自选段不可折叠 → 23 行把「今天区 + 4 个直达入口」推远

- **位置**：`trading_page.dart:2041-2054`（`_buildWatchlistSection` 直接 `..._watchlist.map(...)`，没有折叠，也不设上限）
- **设计依据**：`design-app-20261009.md` §八 自评风险 4 明写「自选必须**只读、可折叠**、不参与结论」；§二 ④ 要求「今天区（含深处入口一行常驻）」紧跟持仓之后
- **实拍**：`audit-shots-app-20261009/app-3-watchlist.png` 是「自选 23 只」
- **影响**：今天区（复盘横幅 + 收益日历/资金/推送设置/复盘历史）被 23 行自选隔开，要走很久；被点名/被推送那只票的落点滚动也被拉长。
- **修法**：复用现成的 `_buildFoldSection`（`trading_page.dart:2156`）——默认收起，标题给「自选 23 只 · 管理在电脑端」；或默认前 5 行 + 「展开其余 N 只」。

### P2-3 · 落点滚动在「目标行尚未构建」时静默失效

- **位置**：`trading_page.dart:235-247`（`_applyFocusSymbol`：`_positionRowKeys[sym]?.currentContext == null` → 直接返回，**无兜底**）
- **成因**：`build()` 用的是 `ListView(children:)`（懒构建，`trading_page.dart:1766`）——被推送的票若落在首屏构建窗口之外（持仓多），该行的 `GlobalKey` 还没挂上 → 不滚动也不提示，用户仍要自己找。
- **现状**：生产持仓 2 只（实拍与用例都是 2 只）**不会踩到**，属潜伏缺陷；但它正是 I-7「点开即到，不要求再找」的判据本身。
- **修法**：`ctx == null` 时退化为按行序估算 offset 后 `jumpTo/animateTo`，或 `addPostFrameCallback` 重试若干帧到命中（上限后放弃，但不要停在「看着已落点其实没动」的假状态）。

### P3-1 · `_buildTodaySection` 的注释与实现相反（陈旧注释）

`trading_page.dart:2089-2094` 写着「推送设置与复盘历史在 app 端没有独立页……**故不放进来**」，而紧接着 `:2097-2105` 正好放了这两个入口（批 4 加的）。注释未随批 4 更新——下次改这块的人会被带偏。

### P3-2 · 「这张截图有 N 行没记」在多图/去重后语义不准

`trading_page.dart:2368-2380` 标题写死「**这张**截图」，而 `_dropped` 现在由多张图**累加**（`trading_page.dart:1191`）、还并进了去重提示（`trading_page.dart:1183`）。建议标题改「这一轮有 N 行没记」，去重那条单独一句。

### P3-3 · 展开态两个文字动作热区偏小（≈26pt）

`trading_page.dart:3438-3450`（`vertical: 5` + 12px 字）。整行热区够（≥44pt），但「这只票阿呆怎么说 / 批次明细」本身低于 44pt 口径，且与「点整行＝收起」相邻。建议 `vertical: 10` 或 `ConstrainedBox(minHeight: 44)`。
（**误触收起已实测不会**：内层 `GestureDetector` 在手势竞技场里胜出——`pages_widget_test` 里「点 `贵州茅台` → 再点 `这只票阿呆怎么说` → 出建议弹层」那条用例即是证明。）

### P3-4 · `composeTradingVerdict(positionCount: null)` 这句在页面里不可达

`trading_page.dart:2011` 传的是 `_positions.length`（`List` 恒非空，永不 null）；真正取不到持仓时走的是**整页错误态**（`trading_page.dart:250-280` + `_buildError` `:3764`）。分支留着无害，但「取不到就不说」的展示口径只有单测覆盖。建议要么在「首载失败但已有旧数据」时用这句，要么在注释里写明它只服务于纯函数完整性。

### P3-5 · `_positionRowKeys` 以 symbol 作 `GlobalKey` 键

`trading_page.dart:3359`。若后端某天返回两条同 symbol（理论上不该），同一 `GlobalKey` 挂两处会直接抛异常。建议 `putIfAbsent` 前先判重，或键改为「symbol + 序号」。

## 三、跨端与契约对拍

| 面 | app 本批 | web / 后端既有 | 一致？ |
|:--|:--|:--|:--|
| 判断句 / 首屏四层 IA | 本批独有 | web 是专业终端，无此形态（设计允许「同语言不同摆法」） | ✅ 设计口径内 |
| 涨跌配色 | `_pnlColor` / `_trendColor` 红涨绿亏 | 同（A 股口径） | ✅ |
| 打码分层 | 数量/成本/浮盈打码，现价/止损明文 | 同（2026-09-19 P2-4 口径） | ✅ |
| 「你的线」取值 | **人工止损** | **生效止损 max(人工, 计算)** | ⚠️ **P2-1** |
| 破线边界 | `<=`（触线即破） | 严格 `<` | ⚠️ P2-1 附带 |
| 深链口径 | 按 `trading:<symbol>` 落点 | 后端还会发 `trading:today` 哨兵；app 侧 `main_page.dart:1572` 早已按哨兵处理 | ⚠️ **P1-1** |
| 端点 / DTO | 6 条路径逐条对得上；`WatchlistItemDto.fromJson` 缺字段全兜底 | `TradingController` 对应映射 | ✅ |

## 四、测试与证据

- **实跑**：`flutter test` → **474 全绿**（本官复核，非照抄）；两个纯逻辑文件单独复跑亦通过（20 例）。
- **真断言**：新增用例断的是业务字符串与请求次数（`calls==3` · `都记上（N 笔）` · `这次过后：持仓 0 → 1 只` · `另外 1 笔看着没问题（点开看）`），**未见为过而改弱的断言**——唯一「改弱」是 `findsNWidgets(3)` → `findsAtLeastNWidgets(3)`，原因是收起态也新增了一个「—」，属形态变化的**合理**放宽，且同用例仍断 `0.00%` 不出现。
- **覆盖缺口**（正对应 P1-1 / P1-2）：① `trading:today` 哨兵无用例；② `main.dart` 的「点通知 → 落点」整条链无用例（现有落点用例是直接构造 `TradingPage(focusSymbol:)`，跳过了 shell 分支）；③ 「同价同量分单（`tradeTime` 不同）应保留两笔」无用例。
- **未覆盖（沿用并认可走查报告 §1.4 的声明）**：真图 VLM 链路 · 生产 APNs 真到达 · 手势惯性 · 改开关后的服务端效果——本轮未验，**不要读成已验**。

## 五、收敛判定

- **无 P0**；**P1×2 必修**（P1-1 = 解析器一行 + 一例单测；P1-2 = 指纹纳入 `tradeTime` 且去重降级为可疑），修完可随本批走。
- **P2×3**：P2-1 属**取值取舍 → ★升人**（「你的线」= 人工位还是生效位）；P2-2 / P2-3 建议记入 `LEDGER.md` 未交付项，随下一批小改带走。
- **P3×5** 建议顺手带走（尤其 P3-1 陈旧注释、P3-3 热区）。
- **纪律**：本报告**只报告不改**（B7）——未改任何 `apps/` 代码、`design-*`、`scope-*`、`_index.md`、`LEDGER.md`；未登记索引（按派单「不要改 `_index.md`」）。
