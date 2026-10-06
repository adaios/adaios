---
title: 设计审核 v4 · 交易插件重做（design-final 收敛性 · 后端官）
description: 后端面收敛性审核——只判上轮 2 条战略（S1 闸门 / S2 §9 基线）+ 6 条 P1 是否真闭环 + 本轮是否新引 P0/P1；含 promote 落点变动专答
version: 1
created: 2026-10-06
updated: 2026-10-06
status: active
lines: 96
depends-on:
  - ./design-final-20261006.md
related:
  - ./review-v3-backend-20261006.md
  - ./review-v4-20261006.md
  - ../../knowledge/reference/contracts/data-format-freeze.md
tags: [workspace, design, review, backend, trading]
---

# 设计审核 v4 · 交易插件重做（后端官 · design-final 收敛性）

**轮次**：v4 · 2026-10-06 · **审核官**：`code-backend-reviewer`（独立子代理 · 派单 `D-20261006-04`）
**审核对象**：`design-final-20261006.md`（585 行 · 合并正文 · 自称取代 v2 / v3 成为唯一正文）
**上轮判定**：`review-v3-backend-20261006.md`（战略 2 条 + P1×6 + 新引 P1×2）
**方法**：设计承诺 × **现状代码 / 磁盘实测**（只读；未改任何代码、设计或契约文件）；凡标「实测」者给 `文件:行号`。
**范围**：只判「上轮 2 战略 + 6 P1 是否真闭环」与「本轮是否新引 P0/P1」（后端面）。P2/P3 · 措辞 · 产品面不判。

## 一、结论

**❌ 不收敛（后端面）。** 无 P0。

一句话：v3 两轮的病（**增量稿不回写正文** / **不完备的表当基线**）在 final 稿里**已治大半**——合并正文真做了、§9 真落成四列 31 行、Contributor 三处口径真一致、promote 三件真定死；**但「表与实体不一致」这一同型病在另两张表上复发**：§4.1 路径表漏 3 条实存落盘、§10.1 脱敏正则漏掉自己举的例。二者都不阻断设计方向，但都会在编码期**静默失真**（前者漏搬迁移对象，后者给脱敏测试假绿）。

| 维度 | 判定 |
|:--|:--|
| 数据安全（最高权重）| **部分 FAIL**——§10.1 规则集可核对，但与自带示例不符（新引 P1-2）|
| 分层依赖 | **PASS**（无新增越层；`TradingKnowledgeSource` 在 kernel 包已如实登记为技术债）|
| 数据流闭环 | **PASS**（六面闸门 + 两条非门控路径均已点名处置）|
| 插件门控（B36）| **PASS**（`/has-activity` 确为唯一无门控端点：实测 60 端点 / 58 走 `requireTradingPlugin` + `promote` 内联门控）|
| 表完备性（基线可信度）| **FAIL**——§4.1 漏 3 条实存路径（新引 P1-1）|
| 测试覆盖 | **部分**——反向回归已给，但覆盖不到它自己举的例子 |

## 二、闭环判定表（原问题 | 设计稿的改动 | 是否闭环 | 证据）

| # | 原问题（v3）| 设计稿的改动（节）| 闭环 | 证据（实测）|
|:--:|:--|:--|:--:|:--|
| **S1** | 关插件闸门扩面仍漏「记忆 / 上下文注入」两面；`/has-activity` 二选一未定 | §11.3 落成**六面闸门表**（新增第 5 面记忆 `BriefAppService`、第 6 面上下文注入 `ContextEngine.loadRelatedRecords`）+ 逐面验收 6 条 + 「关→开」可逆断言（文件数三次相等）+ 写侧端点清单；`/has-activity` **定性为门控** | ✅ **闭环** | §11.3（`design-final:442-476`）；六面读侧落点**逐个实测存在且确无门控**：`ContextEngine.java:356/360`（`loadRelatedRecords`→`findAll(userId)`）· `BriefAppService.java:259`（`What AI understands about this user`）· `FeedAppService.java:326-327`（只认 `domain=trading`）· `TimelineProjection.java:60-61` · `SearchService.java:37-40` · `DomainActivityService.java:62`；`TradingController.java:1700` 的 `has-activity` 无 `requireTradingPlugin` |
| **S2** | §9 基线不可靠（补的 10 行无去留；「留 backlog」与「§9 是基线」冲突）| §9 落成**四列 31 行** + 退役类**反向判据** + 删除「完整对拍留 backlog」，改为「本表即基线（31 组），发现表外端点先补表再动手」 | ✅ **闭环** | awk 逐行计数 **31**；#31 = `data/{userId}/trading/memory-cards/`（v3 漏的那组已补回）；#4 `/principal` 退役给出三项反向判据（有测试 / 有生产调用 / 有前端入口 → 只退役写侧）；与 §13.1「§9 即验收基线」**同稿**（矛盾载体消失）|
| **新引 P1-1** | 增量稿未回写正文 → 同一份设计自相矛盾 | 本稿 = `design-final` 合并正文；§8.1 / §8.2 / §9 **三处同口径** | ✅ **闭环** | grep 全文「死代码」仅 4 处，**均为「不再出现 / 反证不成立」语境**（`design-final:45/47/304/378`），无一处再主张删除；§8.1（`:302-311`）· §8.2（`:313-341`）· §9#28（`:378`）三处互指一致 |
| **新引 P1-2** | §9「补 11 组」实为 10 行（假精确）| 补回 `memory-cards/`；**每处「N 组」后紧跟可数清单** | ✅ **闭环** | §9 = **31 行**、§4.1 = **24 行**（awk 计数）；数量声明与表行逐一对齐 |
| **P1-1** | Contributor 被误标死代码（部分闭环）| §8.1 更正 + §8.2 白名单 + §9#28 同步 | ✅ **闭环** | `TradingContextContributor.java:43`（`supports` 恒 false）· `:49`（`enrich` 恒空）· **`:54 globalContext` 活**——设计如实改成「活的 · 改造，删的只有两个空方法」 |
| **P1-2** | `MarketContextContributor` 在 `scene=trading` 整表注入规模 | §8.2 白名单②：表列→`代码\|名称\|现价\|盈亏%`、删汇总行 | ✅ **闭环** | 现状确如所述：`MarketContextContributor.java:42-43`（`supports("trading")`）· `:144`（表头含 数量/成本价/市值）· `:180-183`（总市值/浮动盈亏/现金余额）→ 改造后逐字段可断言 |
| **P1-3** | §10 promote「已脱敏」不成立 | §10.1 六类规则枚举（含此前完全不过滤的 `request.note()` + `request.sections()`）+ 反向回归 | ⚠️ **部分闭环** | 规则集**已可核对**（不再是类别词），但**与自带示例不符** → 见 §三 新引 P1-2 |
| **P1-4** | promote 落全局共享路径、同日覆盖 + 非 owner 静默丢弃 | §10.2 **唯一落点** `data/{userId}/trading/reviews/promote/`（去「或」、序号防覆盖、服务端不再写 `os/`）；§10.3 非 owner **落自己的候选区**（非 403）| ✅ **闭环** | 现状确如所述：`TradingController.java:1740-1748`（`fileName = date+"_交易复盘.md"` + `REPLACE_EXISTING`）· `TradingKnowledgeSource.java:79/97-98`（非 owner `return ""` → 静默丢弃）；新落点按 `userId` 隔离 + `.gitignore:83` 覆盖（`git check-ignore` 实测通过）|
| **P1-6** | §4.1 与现状 + 冻结契约不符 | §4.1 落成三列 24 行，前稿 `ledger/` `positions/` `watchlist/` `imports/save/` **显式撤回** | ⚠️ **部分闭环** | 撤回正确、对齐冻结契约正确（`data-format-freeze.md:173/290/368/452`），**但仍漏 3 条实存路径** → 见 §三 新引 P1-1 |

## 三、新引问题（仅 P0 / P1）

### 新引 P1-1 · §4.1「24 行」仍**不完备**——漏 3 条实存落盘，且它被当迁移路径口径

- **位置**：`design-final:177-211`（§4.1 表）· 被引用处 `:516-519`（§13.1「§4.1 三列表给路径口径」）· `:527`（§13.2 契约补录计数）。
- **问题**：§4.1 自称「逐项对齐冻结契约 · 共 24 行」，但**代码在写、磁盘实存**的下列 3 条路径**均不在表内**（全文 grep `cash-adjustments` / `candidates/` / `lot-stoploss` **零命中**）：

| 漏项 | 实存证据 | 为什么必须进表 |
|:--|:--|:--|
| `data/{userId}/trading/cash-adjustments.json` | `infrastructure/storage/CashAdjustmentFileRepository.java:31`（`PATH`）| §9#4 明写「存量 `principal` **迁移为一次性出入金调整事件**」——**迁移目标文件**自身却没进路径表 |
| `data/{userId}/trading/candidates/` | `infrastructure/storage/LearnTradingCandidateFileRepository.java:36`（`CANDIDATE_DIR`）| 交易域下实存落盘目录（learn 侧写入、供交易知识库审核）|
| `data/{userId}/trading/lot-stoploss.json` | `infrastructure/storage/LotStopLossOverrideRepository.java:40`（`OVERRIDE_PATH`）| §9#9「批次止损」的用户覆盖数据，U2 必须一起搬 |

- **后果（同一根因、两处失真）**：① §13.1 把 §4.1 定为「U2 迁移的路径口径」→ 照表迁移会**静默漏搬**（`cash-adjustments.json` 是本金语义的承载物）；② §13.2「冻结契约补录（§4.1 里 **5 项**未收录 + 2 项新增 + 1 项差异）」的**计数直接由 §4.1 推出**——表不全则补录清单与计数一起失真（这三条在冻结契约里实测**未收录**，应与 `account.json` 等同列）。这正是 v3 的 S2 同型病（**已知不完整的表当基线**）在 §4.1 上的复发。
- **建议**：§4.1 补 3 行（现状 / 目标 / 迁移 + 「契约未收录 → 登记补契约」），§13.2 补录计数随之改为 **8 项未收录**；或在表首**显式声明「本表只覆盖本设计涉及的路径，非穷举」**并给出穷举入口（避免被当迁移基线使用）。

### 新引 P1-2 · §10.1 脱敏规则**与其自带命中示例不符**——反向回归会给出假绿

- **位置**：`design-final:389-402`（§10.1 规则表）。
- **问题**：规则 1 的正则族写作 `(持有|买入|卖出|成交)\s*[\d,]+(\.\d+)?\s*股`、规则 4 写作 `持有\s*[\d,]+\s*股`；而**规则 5 自己举的例子**是「备注里写『今天**卖了** 500 股』」，落盘结果栏声称「同规则 1–4 过一遍」。**「卖了」不在 `(持有|买入|卖出|成交)` 任一分支内**，规则 4 也不匹配 → 按本表实现的清洗器**不会命中它自己举的这个例子**。
- **后果**：① 隐私红线（需求「约束 · 隐私」/ B3）——promote 候选里按口语写的股数 / 金额**原样落盘**；② **反向回归（`:401`）只塞「卖出 500 股，成交金额 5200 元」**（恰好命中规则 1 / 3）→ 测试绿，而 `note` 路径的口语写法漏网 = **假绿**——与 v3「脱敏不成立」是同一张脸。
- **建议**：二选一——① 把规则族补全口语变体（`买了` / `卖了` / `抛出` / `购入` 等），并让反向回归**逐条覆盖 6 类规则的示例**；② 若刻意只覆盖书面语，则**改掉 `:397` 的示例**，不要用规则覆盖不到的句子自证「已过一遍」。

**判定**：两条均为 **P1**（不阻断设计方向，**阻断编码**——会在迁移与脱敏两处静默失真）。

> **非判定备忘（不构成 P1/P2 判定）**：§11.3「写侧清单」只列 17 个写入口，实际 `TradingController` 尚有 30+ 写端点未列（如 `PUT /positions/{symbol}` · `PUT|DELETE /lots/{lotId}/stop-loss` · `PUT /sold/{symbol}/psychology` · `PUT /market-stage` · `PUT /trade-log/date|meta` · `POST /review` 等）。因**实测这些端点各自已调 `requireTradingPlugin`**（60 端点 / 58 经 helper + `promote` 内联门控，唯一例外正是 `/has-activity`），**不构成新的泄漏面**，仅作清单完备性备查，按派单不判级。

## 四、专答（派单指定的两处自评风险）

1. **`TradingContextContributor` 三处口径是否真一致** —— **一致**。§8.1（`:302-311`）· §8.2（`:313-341`）· §9#28（`:378`）均写「**活的 · 改造 · 不删除**」，全文无「死代码 → 删除」残留；且与代码实测相符（`globalContext():54` 在跑）。
2. **promote 落点从 `os/trading-engine/99-inbox/` 改到 `data/{userId}/trading/reviews/promote/` 是否自洽** —— **自洽，且优于原落点**。① 与现状自洽：现状确实是「服务端直写 `os/` + 同日覆盖」（`TradingController.java:1742-1748`），改后**服务端不再写 git 跟踪目录**（`TradingAdviceAppService.java:59` 那句「唯一例外是 promote 写 99-inbox」可随之消失）；② 与 `data-format-freeze.md` 自洽：契约 `§2.12` 只规定 `trading/reviews/{yyyy-MM-dd}_review.md`，**新增子目录 `promote/` 不与之冲突**，且 `.gitignore:83`（`data/*/trading/`）已覆盖（实测）；③ 与「非 owner」处置自洽：落点按 `userId` 隔离后，§10.3「落自己候选区」是零额外成本的正确解（原实现非 owner 是 `return ""` 静默丢弃）。**唯一提请人确认**：S1 拍板原文是「保留闭环」、未钉死第一跳路径，本改动属**实现层落点变更**——若人认为「S1 = 落点也必须留在 `os/`」，则退回设计稿「自评风险 1」的退路（`{date}_{userId}_{主题}.md` + 序号）。**本审核官意见：现方案更优，建议维持。**

## 五、收敛判定

- **有 P0/P1 → 不收敛**：后端面进入 **v5**（或编写者**当场修订**后由本审核官复核差异）。
- **需修的最小集（2 条）**：① §4.1 补 3 条实存路径（连带 §13.2 计数）；② §10.1 规则族补齐口语变体（或改示例 + 反向回归逐类覆盖）。
- **不必重做**：S1 读侧六面 · §9 四列 31 行 · Contributor 三处口径 · promote 三件事——**均已真闭环**。
- ★ **升级给人的一点**（不自行拍板）：promote 第一跳落点 `os/` → `data/{userId}/` 属**实现层落点变更**，虽经本审核官判「自洽且更优」，仍建议在编码准入前由人**一句话确认**（详见 §四.2）。

---

**声明**：本稿只做「设计 × 现状代码 / 磁盘」对拍，**未改任何代码或设计文件**；`design-*` · `review-*` 一律未动。本稿**未自行登记** `_index.md`（按派单「索引登记由主链统一做」）；**收工复核时主链已完成登记**，`ai-guard-structure` 与 `ai-guard-meta` 均 **PASS**。
