---
title: 派单 D-20261006-04 · design-final 收敛性审核（后端官）
description: 派给 code-backend-reviewer 的任务书——只判上轮 2 战略 + 6 P1 是否闭环 + 是否新引 P0/P1；材料全前置
version: 1
created: 2026-10-06
updated: 2026-10-06
status: active
lines: 47
depends-on: []
related:
  - ./inbox.md
  - ../trading-plugin/design-final-20261006.md
  - ../trading-plugin/review-v3-backend-20261006.md
tags: [workspace, meta, dispatch, trading]
---

# 派单 D-20261006-04 · design-final 收敛性审核（**后端官**）

角色真相源：`.agents/toolkit/roles/code-backend-reviewer.md` + `.agents/toolkit/checklists/code-backend-reviewer.md`。

**审核对象**：`.agents/workspace/trading-plugin/design-final-20261006.md`（585 行 · **合并正文**，已取代 v2 / v3）。
**上轮你自己的判定**：`.agents/workspace/trading-plugin/review-v3-backend-20261006.md`（2 战略 + 6 P1 + 新引 P1×2）。

## 只判两件事

1. **上轮 2 条战略（S1 关插件闸门漏 kernel 面 / S2 §9 基线不可靠）+ 6 条 P1，是否真的闭环**——必须指到设计稿的**具体节** + **可核证据**（文件:行号或具体文字）；不接受"已改"这种声明；
2. **本轮是否新引 P0 / P1**（只限后端面）。

**不判**：P2 / P3 · 措辞 · 产品面（那归产品官）。

## 编写者自己标出的两处「自评风险」——重点核

- `TradingContextContributor` 由「死代码 · 删除」更正为「**活的 · 改造**」：请核 **§8.1 / §8.2 / §9 三处口径是否真的一致**（上轮的病就是"只改增量、不回写正文"）；
- promote 落点从 `os/trading-engine/99-inbox/` 改到 `data/{userId}/trading/reviews/promote/`：**这是实现层改动**，请核它与现状、`data-format-freeze.md`、以及"非 owner"处置是否自洽。

## 产出

`.agents/workspace/trading-plugin/review-v4-backend-20261006.md`
四段：① 结论（收敛 / 不收敛）② 闭环判定表（原问题 | 设计稿的改动 | 是否闭环 | 证据）③ 新引问题 ④ 收敛判定。

## 纪律（硬要求）

- **第一动作**：`cp .agents/workspace/_templates/review-design.md` 成上面的产出文件——**先落空文件再填**；
- **3 分钟内必须看到文件**；**15 分钟内补完**；写一节落一节；
- frontmatter 10 字段 + `lines:` 与 `wc -l` 一致；
- 中文；**只报告不改**；**不要改** `_index.md` · `design-*` · `review-*`（已有文件一律不动，只写自己那份）；
- 收工把本单从 `inbox.md` 待处理移到已完成。
