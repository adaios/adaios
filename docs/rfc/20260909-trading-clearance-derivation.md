---
title: 交易清仓级联——流水证明清仓后自动收录清仓股（双轨：流水推导 + 券商导出校准）
description: 把「清仓股 sold.json」从纯镜像（只认清仓股导出）升级为双轨：历史成交/手动成交把某股卖到 0 且买卖均在流水内 → 自动补录完整清仓档案（补复盘盲区：建议闭环段/清仓三维打分漏记）；流水缺买入基线 → 只提示不写脏档案；券商导出任何时间可按 symbol 校准覆盖。持仓侧已具备（近 10 日 sync 卖光即移除），本 RFC 只补清仓侧级联，不碰自选。
date: 2026-09-09
status: approved
decided-by: 用户（2026-09-09 拍板：P1 只填空白 symbol / P2 只挂三处操作触发 / P3 主档案保持单行券商口径、多段进出以流水派生视图作附加功能）
tags: [trading, 清仓股, sold, 历史成交, 双轨真相源, File First]
related:
  - ./20260905-trading-cognition-layer.md
  - ../reference/trading-features.md
  - ../review/REVIEW.md
---

# 交易清仓级联——清仓股自动收录方案

> **一句话**：持仓卖到 0 时系统只删 positions.md 一行，清仓股列表（复盘档案）不随之更新——用户 2026-09-09 实操追问确认这是「复盘盲区」。本方案让清仓股对流水**可推导、可校准**，与持仓现有的「增量推导（recordTrade/sync）+ 全量锚定（replace/资金股份）」双轨哲学对齐。

## 一、目标与验收

| 层 | 交付 | 验收标准 |
|---|---|---|
| A 自动收录 | 流水完整证明清仓 → sold.json 自动补录完整档案 | 导入含当日清仓的历史成交 → sold.json 多一笔来源=flow 的行，字段（买卖日/持股天数/回合盈亏率）与流水一致；重复导入不翻倍 |
| B 提示不写脏 | 流水只有卖出（买入基线在窗口外）→ 不落库只提示 | 不产生残缺档案；GET /trading/sold 返回 pendingClearances 含该股，前端横幅引导导清仓股导出 |
| C 导出校准 | 券商清仓股导出按 symbol upsert 覆盖 flow 行 | 字段被券商值覆盖、psychology 等人工字段保留、来源翻转为 import |
| 回归 | 不破坏既有 sold/复盘/建议闭环 | 后端全量测试 1309 全绿 + 新增用例 |

## 二、现状核对（2026-09-09 代码实测）

| 面 | 现状 | 缺口 |
|---|---|---|
| 持仓移除 | recordTrade 卖到 0 删 positions.md 行；历史成交近 10 日 sync 同口径；replace 全量以文件为准；一键 sync 清流水已清仓残留 | ✅ 已有（本方案不动） |
| sold.json 写入口 | 仅 2 处：清仓股导出导入（按 symbol upsert）、心理标注保存 | **当日清仓不自动收录** → 复盘建议闭环段（TradingReviewAppService：sold.sellDate=复盘日）与清仓三维打分**静默漏记** |
| 推导零件 | TradingLotService.derive 已产出闭合回合（买入/卖出日期、回合收益率、LIFO）；reconcile 已能报「流水净 vs 持仓」缺口 | 未接 sold 收录 |
| 清仓表数据质量 | P2-认知2（2026-09-05 已拍板）：sold 单行「首买→末卖」口径掩盖多段进出，画像必须基于逐笔流水 | flow 自动行按回合口径标注来源，不与券商标签混同 |
| 自选 | 独立观察池；历史教训：清仓文件误导入自选 170 只污染（watchlist.json.bak-20260827-pollute） | **不在本方案范围**（语义独立，任何自动联动都反对） |

## 三、目标行为（分级，2026-09-09 讨论共识）

### 3.1 触发时机（统一走 ClearanceDetector，读锁内幂等）

1. 历史成交导入完成（sync/append 混合处理后，对当日涉及 symbol）；
2. recordTrade / recordTradeWithOrderId 卖光到 0 且流水闭合；
3. POST /trading/sync 一键按流水重建持仓后。

不新增定时任务（MVP），避免与收盘/复盘链路抢锁。

### 3.2 判定与动作

| 条件 | 判定依据 | 动作 |
|---|---|---|
| A 完整可推导 | 该 symbol 已从持仓消失；流水含其买入且全部闭合（无窗口外基线缺口，可用 TradingLotService 闭合回合核验）| 自动 upsert 一笔 SoldTrade：buyDate=最早买入日 / sellDate=最后卖出日 / holdDays / tradeCount / holdPnlPct=回合口径 / verdict 规则判 / **provenance=flow** |
| B 只卖不买 | 流水证明已清仓但该股无买入流水（基线在窗口外）| **不落库**；进 GET /trading/sold 响应 `pendingClearances`，前端清仓 Tab 横幅「检测到 X 已清仓但无买入基线——导入清仓股导出补全档案」 |
| C 导出校准 | 任何时间导入清仓股导出 | 现状 upsert 语义不变，券商字段覆盖 flow 生成字段、psychology 等人工字段保留、provenance 翻转为 import |

### 3.3 写入保护（防打架）

- **flow 自动收录只填空白**：目标 symbol 已存在于 sold.json（provenance=import 或人工行）→ flow 不覆盖，仅当流水重导/显式重建时才重新推导（后置批）。
- **幂等**：以（symbol + 最近卖出生效指纹）判重；重复导同一历史成交文件、重复 sync 均不翻倍。
- **多段进出（P3，2026-09-09 用户拍板）**：主档案 sold.json 保持**单行券商口径**（import 行权威，flow 行同取首买→末卖 + 回合盈亏，不展开）——与通达信清仓导出结构一致，不与券商口径打架；**同股多段进出以「流水派生多段视图」作附加功能**（批 2，只读展示，如清仓明细弹窗逐段行：段买入日→卖出日/段盈亏/段回合收益率，来源逐笔流水），不写主行，多段细节同时由画像承载（P2-认知2 口径一致）。

## 四、数据与契约改动

| 项 | 改动 |
|---|---|
| sold.json 行 | 加 `provenance: flow\|import`（老行缺省视为 import，读侧兼容）|
| GET /trading/sold | 响应加 `pendingClearances: [{symbol, name, sellDate}]`（条件 B 清单，空则省略）——**无新端点** |
| web/app 清仓 Tab | 横幅展示 pendingClearances + 引导导清仓股（复用 2026-08-26「无真实成交引导先导入」同款交互）|

## 五、相邻机制与红线自查

- 自选不动：不自动移出/加入 watchlist（语义独立 + 170 只污染教训）。
- 不自动删任何数据：positions 移除是既有行为；sold 只增/校准，不删。
- 数字口径：自动档案按**流水回合口径**生成并标注来源，绝不冒充券商口径；券商导出始终可覆盖。
- P2-交易34/35（锚定后增量无去重检测 → 转账/成交双扣）是**另一张单**，本 RFC 不含，仅记录关联：工作流顺序铁律（增量先、全量锚定最后）已在 2026-09-09 实操确认并建议排批。

## 六、实施分批

- **批 1（MVP）**：ClearanceDetector（A 填空白 + B 提示清单）+ soldList pendingClearances + sold.json provenance + web/app 清仓横幅 + 幂等/覆盖保护测试（重复导入不双收、导入覆盖 flow 保留 psychology、基线外不落库）。
- **批 2（后置）**：**多段进出派生视图**（P3 拍板：主档案单行券商口径，另附逐段只读明细弹窗）；UI 来源徽标；建议闭环复盘对照段对 flow 行打通；profile 画像消费 flow-provenance 行。
- **不做项**：不自动写残缺档案；不自选联动；不改清仓股导入语义；多段不在 sold 主表展开（只做派生视图）。

## 七、决议记录（2026-09-09 用户拍板）

| 点 | 决议 |
|---|---|
| P1 覆盖范围 | flow 自动收录**只填空白 symbol**——sold.json 已存在（import/人工行）绝不自动覆盖，只补缺失 |
| P2 触发范围 | **只挂三处操作触发**（历史成交导入 / recordTrade 卖光 / POST /trading/sync），不加每日巡检定时任务 |
| P3 多段表达 | 主档案保持**单行券商口径**（与通达信导出一致）；多段进出以**流水派生视图**作附加功能（批 2） |

## 八、实施状态（2026-09-09 批 1 落地，本地未部署）

- **批 1 已完成并合入**（用户「A+B 开工」自主推进）：ClearanceDetector（flow 自动收录只填空白 + pending 不落脏）+ sold.json `provenance`（老文件兼容 import）+ SoldTradeRepository.upsertFromFlow（仓储条带锁原子）+ `GET /trading/sold` 响应包装 `{sold, pendingClearances}` + web 清仓 Tab pending 横幅 + 幂等/覆盖保护测试（ClearanceDetectorTest 5 + sold round-trip 等，见 change-log 2026-09-09 行）。后端 1309→1332 / web 174→179 / 端点 124→126，三端全绿。
- **批 2 待排**（后置）：多段进出派生视图（P3 拍板附加功能）、UI 来源徽标、建议闭环复盘对照段打通 flow 行、profile 画像消费 flow-provenance 行。

## 九、验收用例（批 1 落地时写）

1. 导入含当日清仓的历史成交（该股买入在流水内）→ sold.json 自动 +1 行 flow，字段与流水一致；同文件重导不翻倍。
2. 流水只含卖出（基线外）→ sold.json 不变；GET /trading/sold.pendingClearances 含该股。
3. 同股再导清仓股导出 → 字段被券商覆盖、psychology 保留、provenance=import。
4. 卖光再买回再卖光（两段）→ sold 单行按最新段刷新，逐笔不丢。
5. 后端全量测试全绿。
