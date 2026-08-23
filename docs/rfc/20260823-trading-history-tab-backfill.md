---
title: 历史成交 Tab 页 + 导入缺失字段回填（成交时间）
date: 2026-08-23
status: implemented
decided-by: adai（2026-08-23 会话：① 页头多余入口可去掉——历史成交 Tab 取代页头「交易历史」Dialog；② 导入回填语义——幂等保留，已存在 orderId 且 tradeTime 为空则回填；③ Tab 行显示全部列——金额/费用/成交编号 + 止损/买点/原因；④ 先写本文档审核后开工；⑤ 导入只认历史成交格式；⑥ 不做定时轮询）
implementation: 2026-08-23 已实施——后端回填 + updated 计数 + 前端第 5 Tab「历史成交」（全字段列）+ 独立导入 Dialog（只认历史成交格式）+ 移除页头交易历史 Dialog；后端测试 + web 104 测试全绿
---

# 历史成交 Tab 页 + 导入缺失字段回填（成交时间）

> **方向（用户 2026-08-23 确认）**：历史成交从页头 Dialog 升级为交易页**常驻第 5 个 Tab**；历史成交导入从「纯幂等跳过」升级为「幂等去重 + 缺失字段回填」。
> **流程**：先写本文档（draft），审核确认后开工（代码 + 数据格式 MINOR 变更）。

---

## 一、问题与现状（实测证据）

### P1 全量重传不更新——导入是幂等跳过，不是回填（用户实测发现）

- 用户重传全量历史成交文件，期望 8 月记录被「更新/补全」；实际 8 月一行未动。
- 根因：`TradingAppService.importHistoricalTrades`（第 797-839 行）按 orderId 去重：
  `orderIds.contains(oid) → skipped++`，**已存在记录不更新任何字段**（不补 tradeTime、不改 fee）。
- 实测 `data/adai/trading/trades/2026-08.json`：48 条流水**全部有 orderId、全部 `tradeTime: null`**——
  首次导入时文件未含成交时间或格式未解析上；幂等逻辑导致重传也无法补回。

### P2 历史成交只有 Dialog 入口，无常驻视图

- 交易页 Tab 工作区 4 个（持仓/自选/清仓/资金）；历史成交仅页头 receipt 按钮弹 `_HistoryDialog`（760×520 弹窗）。
- 历史成交是**数据底座**（逐笔流水真相源、对账提示的落点），Dialog 形态弱于常驻 Tab：
  切页/刷新即关，看不到「导入后对账」的持续性。

### P3 前端 DTO 未解析 fee / orderId（「显示全部」的前提缺口）

- 后端 `TradeRecord` 已含 `fee`（券商实扣）与 `orderId`（成交编号）字段，`GET /trades` 会序列化输出；
- 前端 `TradeRecordItem`（api_service.dart 第 1291 行）**只解析到 amount，未解析 fee / orderId**——
  「显示全部列」需补 DTO 字段（无后端改动）。

---

## 二、目标

1. **历史成交 Tab**：交易页新增第 5 个 Tab「历史成交」，常驻列表（默认近 30 天 + 日期范围查询），取代页头「交易历史」Dialog
2. **回填**：重传全量历史成交文件时，已存在 orderId 且 `tradeTime` 为空的记录**回填成交时间**；返回体透出 `updated` 计数
3. **显示全部**：Tab 行展示完整字段——方向/时间/代码/名称/数量/价格/金额/费用/成交编号/止损/买点/原因
4. **边界**：仍**不重算持仓/现金**（历史成交"只补流水"设计取舍不变）；只回填缺失字段，不做「文件为准整体覆盖」；不做关键词搜索

---

## 三、方案

### 3.1 后端：导入回填（`TradingAppService.importHistoricalTrades`）

| 项 | 值 |
|:--|:--|
| 语义 | orderId 已存在 → 检查旧记录 `tradeTime`：为空则用新文件值回填；非空则跳过（幂等保持） |
| 无 orderId | 指纹去重不变（symbol/direction/entryDate/price/volume），不落重复流水 |
| 返回体 | 加 `updated` 计数（回填笔数）；`imported/skipped/nonTrades` 语义不变 |
| 存储 | 保持按月 JSON（`trades/{yyyy-MM}.json`）不动；回填 = 读当月文件 → 按 id 更新 tradeTime → 原子写回 |

**实现要点**：

- `TradingHistoryFileRepository` 补一个按 id 回填字段的方法（如 `updateTradeTime(userId, tradeId, tradeTime)`），
  读-改-写当月文件，找不到 id 静默（防并发/文件漂移）
- 回填循环在 `synchronized (tradeLock(userId))` 内（与现导入一致，防并发互覆）
- `HistoricalTradeImportResult` record 加 `updated` 字段；`TradingController` 返回体透出
- 现有测试断言第二次导入全跳过（TradingAppServiceTest 第 874 行）需同步调整：同文件回传但旧记录 tradeTime 已非空 → 仍全跳过；**新增用例**：旧记录 tradeTime=null 时重传带时间的文件 → updated=N 且旧记录时间被回填

### 3.2 前端：历史成交 Tab（`trading_page.dart`）

| 区块 | 设计 |
|:--|:--|
| Tab 结构 | `DefaultTabController` length 4→5，新增 `Tab(text: '历史成交')` 置于「资金」之后；`TabBarView` 加第 5 个 `SingleChildScrollView(_buildHistorySection())` |
| 顶部工具行 | 日期范围（起/止 DatePicker，默认近 30 天，对齐现 Dialog `_fmt`/`_pickFrom`/`_pickTo`）+ 刷新按钮 + 「导入历史成交」按钮（**独立入口，只认通达信历史成交导出格式**，不复用三格式批量导入 Dialog——用户 2026-08-23 确认） |
| 数据源 | `GET /trading/trades?from=&to=`（已支持跨月/倒序），**后端零新端点** |
| 列表 | 按日分组（日期降序、未标注置底，复用现 `_grouped` 逻辑）；组头「日期 + N 笔」 |
| 行列（显示全部） | 方向 / 时间 HH:mm / 代码 / 名称 / 数量 / 价格 / 金额 / 费用 / 成交编号 / 止损 / 买点 / 原因——横向滚动 DataTable 或对齐 Row（列多，横向滚动） |
| 顶部统计行 | 「共 N 笔 · 买 X 卖 Y」（区间内计数；当日可加节奏行复用 `?date=` daily，纯客观） |
| 空态 | 「还没有历史成交——点击导入通达信历史成交导出」+ 导入按钮 |
| 导入结果 | 导入后 inline 展示 `imported/updated/skipped/nonTrades` + 对账提示行（复用现 `_historyResult` 展示，加 updated） |
| 导入入口 | 独立小 Dialog（`_HistoryImportDialog`）：粘贴或选文件 → 只走 `isTdxHistoryExport` 识别 → `POST /trades/import`；非历史成交格式直接报「无法识别」人话（用户 2026-08-23 确认：限制格式，只认历史成交） |
| 自动刷新 | 进 Tab 自动加载 + 手动刷新；**不做定时轮询**（保活页陈旧问题——切页刷新兜底，用户 2026-08-23 确认） |

### 3.3 前端：移除页头「交易历史」Dialog

| 项 | 值 |
|:--|:--|
| 移除 | 页头 receipt 按钮（`_showHistory` 入口）+ `_HistoryDialog` 类 + `_buildListHeader/_buildDateGroup/_buildTradeRow`（逻辑并入 Tab 后删除） |
| 保留 | 复盘历史按钮（日历图标）不变；批量导入、推送设置、复盘、刷新不变 |
| 复用 | Tab 列表组件从 `_HistoryDialog` 抽离（`_fmt/_pickFrom/_pickTo/_grouped` 上移为页面方法或独立 StatefulWidget） |

### 3.4 前端 DTO 补字段（`api_service.dart`）

- `TradeRecordItem` 补 `fee`（double?）、`orderId`（String?）解析——显示全部列的前置；**无后端改动**

---

## 四、边界与风险

| # | 项 | 说明 |
|:--|:--|:--|
| 1 | 不重算持仓/现金 | 历史成交「只补流水」是设计取舍（缺窗口前基线，回放重建算不出券商口径）；持仓/成本/现金仍以全量覆盖导入为准 |
| 2 | 不做「文件为准覆盖」 | 用户确认回填语义（只补缺失字段），不做整体覆盖（会动账目） |
| 3 | 不做关键词搜索 | 交易记录量级小，按日期看足够；避免过度设计 |
| 4 | 回填并发 | 读-改-写在 tradeLock 内；文件损坏单月跳过（现兜底逻辑不变） |
| 5 | 旧数据 tradeTime=null | 兼容展示 '—'（现 `_buildTradeRow` 已处理）；回填后自动显示 HH:mm |
| 6 | P1-交易7 六请求合并 | Tab 数据加载独立于 `_loadAll`，单端点失败不拖垮整页（对齐现 Dialog 独立加载） |

---

## 五、改动清单（确认后开工）

| # | 层 | 文件 | 改动 |
|:--|:--|:--|:--|
| 1 | 后端 application | `TradingAppService.java` | `importHistoricalTrades`：已存在 orderId 且 tradeTime 为空 → 回填；`HistoricalTradeImportResult` 加 `updated` |
| 2 | 后端 infrastructure | `TradingHistoryFileRepository.java` | 补按 id 回填 tradeTime 方法（读-改-写当月文件） |
| 3 | 后端 interfaces | `TradingController.java` | 返回体透出 `updated` |
| 4 | 后端 测试 | `TradingAppServiceTest.java` | 新增：重传带时间文件 → 旧记录 tradeTime 回填 + updated 计数；调整现有幂等断言 |
| 5 | 前端 services | `api_service.dart` | `TradeRecordItem` 补 fee/orderId 解析；`HistoricalTradeImportResult` 加 updated |
| 6 | 前端 pages | `trading_page.dart` | 新增第 5 Tab「历史成交」+ `_buildHistorySection`（含工具行/分组列表/空态/导入结果）+ 独立 `_HistoryImportDialog`（只认历史成交格式）；移除 receipt 按钮 + `_HistoryDialog` |
| 7 | 文档 | `trading-features.md` / `api-spec.md` | Tab 结构、导入 updated 语义、Dialog→Tab 变更同步 |

**顺序**：后端回填 + 测试 → 前端 DTO → 前端 Tab + 移除 Dialog → 文档同步。
**门禁**：`flutter analyze` 0 issues、`flutter test`、`./gradlew test` 全绿；数据格式变更按 MINOR 登记 `data-format-freeze.md`。
