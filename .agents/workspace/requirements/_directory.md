---
title: workspace/requirements/ 目录契约
description: 需求文稿（L1 在制品）的职责边界 · 依赖关系 · 触发关系 · 约束 · 守卫 · 维护方式——从想法到「人拍板的需求文稿」
version: 1
created: 2026-10-03
updated: 2026-10-04
status: active
lines: 51
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# workspace/requirements/ 目录契约

**职责**：需求讨论与定稿——**从想法到「人拍板的需求文稿」**的全过程记录。它是**审核驱动开发主链的第一段**（见 `docs/architecture/ai-context-engineering.md` §4.6）。

## 职责边界
- **放**：`<需求id>.md`（讨论中或已定稿的需求文稿）与 `_template.md`
- **不放**：设计（→ `../designs/`）· 任务账本（→ `../tasks/`）· **定稿后的需求**（归档到 `docs/rfc/` 或 `docs/features/`）

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 被依赖 | `../../../AGENTS.md` | 规则 7「讨论与实施分离」——只约束代码/数据，本目录属工程维护 |
| 下游 | `../designs/<需求id>/` | **需求定稿是设计阶段的输入** |
| 归档去向 | `../../../docs/rfc/` · `../../../docs/features/` | 定稿后搬走 |

## 触发关系

| 时机 | 谁触发 | 读 / 执行什么 |
|:--|:--|:--|
| 有新想法 | 人 / AI | `cp _template.md <需求id>.md` |
| 讨论中 | 人 + AI | `<需求id>.md`（改范围 / 验收 / 未决）|
| **★ 人拍板定稿** | **人** | 归档 `docs/` → 进入设计阶段（人的介入点 ①）|

## 约束
- **需求只能由人定稿**——AI 不得自行宣布「需求已定」（这是人的介入点 ①）
- **一份需求一个文件**，`<需求id>` 与 RFC / feature 编号一致
- **定稿后必须归档**：`workspace/` 只留在制品，不留已定稿的
- frontmatter **10 字段**（`ai-guard-meta` 查）

## 守卫（谁保证这里不腐烂）
- `ai-guard-structure`：两件套齐备 · 清单⇄实际 · 契约依赖 · 守卫引用 · 两件套不重复
- `ai-guard-meta`：frontmatter / lines / 断链

## 维护动作
1. 有新需求 → `cp _template.md <需求id>.md`
2. 讨论中更新；**只有人**能把它标成定稿
3. 定稿 → 归档 `docs/rfc/` 或 `docs/features/` → `rm <需求id>.md` → 跑 `ai-guard-structure.sh --fix`
