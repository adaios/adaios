---
title: 派单 D-20261006-05 · design-final 收敛性审核（产品官）
description: 派给 docs-product-reviewer 的任务书——只判上轮 1 战略 + 2 P1 + 3 点是否闭环 + 是否新引 P0/P1；材料全前置
version: 1
created: 2026-10-06
updated: 2026-10-06
status: active
lines: 46
depends-on: []
related:
  - ./inbox.md
  - ../trading-plugin/design-final-20261006.md
  - ../trading-plugin/review-v3-product-20261006.md
tags: [workspace, meta, dispatch, trading]
---

# 派单 D-20261006-05 · design-final 收敛性审核（**产品官**）

角色真相源：`.agents/toolkit/roles/docs-product-reviewer.md` + `.agents/toolkit/checklists/docs-product-reviewer.md`。

**审核对象**：`.agents/workspace/trading-plugin/design-final-20261006.md`（585 行 · **合并正文**，已取代 v2 / v3）。
**上轮判定**：`.agents/workspace/trading-plugin/review-v3-product-20261006.md`（3 点）+ `.agents/workspace/trading-plugin/review-v2-20261006.md`（1 战略 + 2 P1）。

## 只判两件事

1. **上轮未闭合项是否真闭环**——逐条核：
   - **战略 S2**：本稿 §13.3「编码准入条件」是否把**待落产物**（定位扩容 RFC 要点 + roadmap 条目）写清、且**不再宣称已闭环**；
   - **产品 P1-1**：§5 两条 app 输入的分流是否保住「截图入账**不进 Feed / 时间线**」这条刻意口径；
   - **产品 P1-2**：§8.1 / §8.2 / §9 三处对 `TradingContextContributor` 的口径是否一致；
   - **上轮 3 点**（S2 落点 · 增量稿↔正文自相矛盾 · 数量声明）：本稿是否已治（尤其"**双真源**"——本稿自称唯一正文，请核是否真的不需要再看 v2 / v3）。
2. **本轮是否新引 P0 / P1**（只限产品面）。

**不判**：P2 / P3 · 措辞 · 后端实现细节（那归后端官）。

## 产出

`.agents/workspace/trading-plugin/review-v4-20261006.md`
四段：① 结论（收敛 / 不收敛）② 闭环判定表（原问题 | 设计稿的改动 | 是否闭环 | 证据）③ 新引问题 ④ 收敛判定。

## 纪律（硬要求）

- **第一动作**：`cp .agents/workspace/_templates/review-design.md` 成上面的产出文件——**先落空文件再填**；
- **3 分钟内必须看到文件**；**15 分钟内补完**；写一节落一节；
- frontmatter 10 字段 + `lines:` 与 `wc -l` 一致；
- 中文；**只报告不改**；**不要改** `_index.md` · `design-*` · `review-*`（已有文件一律不动，只写自己那份）；
- 收工把本单从 `inbox.md` 待处理移到已完成。
