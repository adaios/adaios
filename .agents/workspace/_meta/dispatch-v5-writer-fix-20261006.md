---
title: 派单 D-20261007-01 · design-final 就地修订（收 v4 新引 3 条 P1）
description: 派给设计文档编写者的任务书——就地修订 design-final，收掉后端 2 条 + 产品 1 条新引 P1，并加「修订记录」；材料全前置
version: 1
created: 2026-10-06
updated: 2026-10-06
status: active
lines: 62
depends-on: []
related:
  - ./inbox.md
  - ../trading-plugin/design-final-20261006.md
  - ../trading-plugin/review-v4-backend-20261006.md
  - ../trading-plugin/review-v4-20261006.md
tags: [workspace, meta, dispatch, trading]
---

# 派单 D-20261007-01 · design-final **就地修订**（收 3 条新引 P1）

你是 AdaiOS 的**设计文档编写者**。真相源：`.agents/toolkit/roles/docs-design-writer.md`。

**修订对象（就地改这一个文件，不要新建版本）**：`.agents/workspace/trading-plugin/design-final-20261006.md`（585 行）。
**人已拍板**：`decisions.md` 第三轮 **W1**（promote 落点**维持** `data/{userId}/trading/reviews/promote/`）· **W2**（下面三条按审核建议修）。

## 要改的三条（证据都给你了，不用自己找）

### ① 后端 P1-1 · §4.1 漏 3 条**实存**落盘路径

§4.1 表（`design-final:177-211`）自称"逐项对齐冻结契约 · 共 24 行"，但**代码在写、磁盘实存**的这 3 条全文零命中：

| 漏项 | 实存证据 |
|:--|:--|
| `data/{userId}/trading/cash-adjustments.json` | `infrastructure/storage/CashAdjustmentFileRepository.java:31`（`PATH`）|
| `data/{userId}/trading/candidates/` | `infrastructure/storage/LearnTradingCandidateFileRepository.java:36`（`CANDIDATE_DIR`）|
| `data/{userId}/trading/lot-stoploss.json` | `infrastructure/storage/LotStopLossOverrideRepository.java:40`（`OVERRIDE_PATH`）|

**要求**：§4.1 补这 3 行（现状 / 目标 / 迁移；契约未收录的标「登记补契约」），**表行数随之改对**；**连带把 §13.2 的「5 项未收录」计数改成实际值（8 项）**——那三条在冻结契约里实测未收录。

### ② 后端 P1-2 · §10.1 脱敏规则**命中不了它自己举的例子**

§10.1（`:389-402`）：规则 1 是 `(持有|买入|卖出|成交)\s*[\d,]+(\.\d+)?\s*股`、规则 4 是 `持有\s*[\d,]+\s*股`；而**规则 5 自己举的例**是「备注里写『今天**卖了** 500 股』」，落盘结果栏写「同规则 1–4 过一遍」——**「卖了」不在任何分支里，规则 4 也不匹配** ⇒ 按本表实现的清洗器**不会命中它自己举的这个例子**；反向回归（`:401`）只塞「卖出 500 股，成交金额 5200 元」（恰好命中 1/3）→ **测试假绿**。

**要求**：二选一（**必须选一个，不留"或"**）——① **补全口语变体**（`买了` / `卖了` / `抛出` / `购入` …），并让**反向回归逐条覆盖 6 类规则的示例**；② 或**改掉那个示例**、不要用规则覆盖不到的句子自证"已过一遍"。

### ③ 产品 P1-1 · §1 数量声明与实体不符

§1（`design-final:90`）写 `domain/trading` 有「**两个 `ContextContributor`**」，实际**三个**（`TradingContextContributor.java:25` · `MarketContextContributor.java:24` · `TradingProfileContributor.java:23`），而 §8.1（`:302-311`）自己逐行列了三个。

**要求**：改为「**三个**（`Trading` / `Market` / `TradingProfile`）」；若本意是"两个**改造项**"，就明写「其中**两个为改造项**、一个**保留**」——**二者择一，不留对不上的计数**。

## 另外（必做）

- 在文件**开头加一节「修订记录」**：`r1 → r2`，逐条写「改了哪一节 · 依据哪份审核的哪条（`review-v4-backend` P1-1 / P1-2 · `review-v4` P1-1）」；
- 改完**自查数量声明**：全文每处「N 组 / N 条 / N 项 / N 个」后必须紧跟可数清单，**数一遍对得上**；
- `lines:` 字段与 `wc -l` 一致。

## 纪律（硬要求）

- **第一动作**：先往 `design-final-20261006.md` 里写入「修订记录」骨架（**先落可见改动，再逐条改**）；
- **3 分钟内必须看到文件被改动**；**15 分钟内完成**；
- 中文；**只改这一个文件**——**不要动** `design-v1/v2/v3` · `review-*` · `requirement.md` · `_index.md` · `decisions.md`；
- 收工把本单从 `inbox.md` 待处理移到已完成。
