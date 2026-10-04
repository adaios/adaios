---
title: .agents/ 目录索引
description: AdaiOS AI 运行时容器的顶层索引——**6 个顶层目录**（按知识性质分）+ 顶层文件清单；判据＝「AI 上下文运行时是否需要」；根契约见 ./_directory.md
version: 2
created: 2026-10-03
updated: 2026-10-04
status: active
lines: 48
depends-on: []
related: [./_directory.md]
tags: [meta, index, ai]
---

# .agents/ 目录索引

**职责**：AdaiOS 的**规则与机制层**（工具中立）——回答「**怎么做 / 禁止什么 / 谁在自动保障**」。**真相源进 git**，出口与本机状态不入库。

> **2026-10-04 两批归纳**：
> **一批**——边界判据由「按读者」改为**「按性质」**（规则类归本容器）；
> **二批**——判据进一步收紧为「**AI 上下文运行时是否需要**」：**AI 运行需要的一切**（方向 / 事实 / 决策 / 活账本）全部收进本容器，`docs/` 自此为**档案馆**（史 / 存档 / 对外 / 未定型）。
> 详见 [`../docs/README.md`](../docs/README.md) 的归属判定表。
> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

## 子目录（**6 个顶层** · 2026-10-04 六顶层重构）

> **层级**：`.agents/<顶层>/<子目录>/...`。顶层按**知识性质**分（① 方向 · ② 事实 · ③ 规则 · ④ 记录 · ⑤ 能力/机制）；每个顶层都有 `_index.md` + `_directory.md`。

| 顶层 | 类 | 装什么 | 索引 | 契约 |
|:--|:--:|:--|:--:|:--:|
| `direction/` | ① | 方向与决策——`VISION.md` · `product-roadmap.md` · `rfc/`（68 份） | [→](./direction/_index.md) | [→](./direction/_directory.md) |
| `knowledge/` | ② | 事实——`reference/`（契约 / 状态 / 手册 / 设计）· `features/`（功能主轴 + 意图卡） | [→](./knowledge/_index.md) | [→](./knowledge/_directory.md) |
| `rules/` | ③ | 规则——`assets/` · `guides/` · `deployment/` · `process/` · `workflow/` · `method/` | [→](./rules/_index.md) | [→](./rules/_directory.md) |
| `records/` | ④ | 记录——活账本（REVIEW / change-log / task-log）· `state/`（本机）· `workspace/`（在制品） | [→](./records/_index.md) | [→](./records/_directory.md) |
| `toolkit/` | ⑤ | AI 能力——`roles/`（审查官）· `skills/`（技能）· `checklists/`（清单） | [→](./toolkit/_index.md) | [→](./toolkit/_directory.md) |
| `mechanism/` | ⑤ | 执行机制——`guards/`（守卫 · 含 `tests/`）· `scripts/`（执行器 · 含 `lib/`） | [→](./mechanism/_index.md) | [→](./mechanism/_directory.md) |

## 顶层文件

| 文件 | 职责 |
|:--|:--|
| `README.md` | 本容器的入口——定位（规则与机制层）+ 三层结构 + 任何 AI 工具如何接入 |
| `frontmatter-spec.md` | AdaiOS 全项目文档 YAML frontmatter 契约——字段定义、维护职责、图谱与治理机制 |

## 过期判断

- `status != active` → 候选清理
- **清单须与实际一致**（`ai-guard-structure` S1–S3 校验）
- 新增子目录 → **必须同时有 `_index.md` 与 `_directory.md`**
