---
title: workspace/designs/ 目录契约
description: 设计与审核多轮记录（L1 在制品）的职责边界 · 依赖关系 · 触发关系 · 约束 · 守卫 · 维护方式——设计文档编写者与设计文档审核者交叉，一需求一目录，可追溯
version: 1
created: 2026-10-03
updated: 2026-10-04
status: active
lines: 53
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# workspace/designs/ 目录契约

**职责**：**设计与审核的多轮记录**——设计文档编写者与设计文档审核者的交叉产物，**一需求一目录**，每轮两份文件、**不覆盖历史**（这正是可追溯的来源）。它是审核驱动开发主链的**第二段**（见 `docs/architecture/ai-context-engineering.md` §4.6）。

## 职责边界
- **放**：`<需求id>/design-v<N>-<YYYYMMDD>.md`（编写者）· `<需求id>/review-v<N>-<YYYYMMDD>.md`（审核者）· 两个 `_template-*.md`
- **不放**：需求文稿（→ `../requirements/`）· 任务账本（→ `../tasks/`）· **设计定稿**（归档到 `docs/architecture/` + ADR）

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 上游 | `../requirements/<需求id>.md` | **需求定稿是设计阶段的输入** |
| 角色 | `../../roles/` | 设计文档审核者复用现有审查官（产品架构 / 交互 / 后端…）|
| 归档去向 | `../../../docs/architecture/` · `../../assets/adr/` | 收敛后 `design-final.md` 搬过去 |

## 触发关系

| 时机 | 谁触发 | 读 / 执行什么 |
|:--|:--|:--|
| 需求定稿后 | AI（自主）| 建 `<需求id>/` → 出 `design-v1-<日期>.md` |
| 每轮审核 | 设计文档审核者 | 出 `review-v<N>-<日期>.md` |
| **无 P0/P1** | — | **收敛** → `design-final.md` → 归档 |
| **有取舍类问题** | **升级给人** | 人拍板 → 下一轮（人的介入点 ②）|

## 约束
- **轮次命名**：`design-v<N>-<YYYYMMDD>.md` · `review-v<N>-<YYYYMMDD>.md`（同日多轮靠 `v<N>` 区分）
- **每轮两份文件**（编写者 + 审核者），**不覆盖历史**
- **一需求一目录**；目录内**刻意不放 `_index.md` / `_directory.md`**（否则每个需求都要两件套，太重）——由本目录索引以 `rglob` 覆盖
- **收敛判据**：审核报「**无 P0/P1**」；**取舍类问题强制升级给人**，AI 不自行拍板

## 守卫（谁保证这里不腐烂）
- `guard-structure`：本目录两件套 + 清单⇄实际（递归覆盖各 `<需求id>/`）
- `guard-meta`：各设计稿与审核稿的 frontmatter / lines / 断链

## 维护动作
1. 需求定稿 → 建 `<需求id>/` → `cp _template-design.md <需求id>/design-v1-<YYYYMMDD>.md`
2. 审核 → `cp _template-review.md <需求id>/review-v1-<YYYYMMDD>.md`
3. 收敛 → 出 `design-final.md` → 归档 `docs/architecture/` + ADR → `rm -r <需求id>/`
4. 跑 `guard-structure.sh --fix` 刷清单
