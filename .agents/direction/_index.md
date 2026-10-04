---
title: direction/ 目录索引
description: .agents/direction/ 的文件清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 35
depends-on: []
related: [./_directory.md]
tags: [meta, index, direction]
---

# direction/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

**职责**：**① 方向**——回答「**为什么做 · 往哪走**」。本区是 AI 每次会话的**首读**（`AGENTS.md` 规则 1）。

## 文件清单（2 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `VISION.md` | 项目愿景与理念（**唯一理念真相源**）——Personal AI OS 的定位、五层产品架构、工程原则 | active |
| `product-roadmap.md` | 🚩 **产品路线唯一蓝图**（v1.0.0）——路线驱动开发：从这里拆任务、确认目标 | active |

## 过期判断

- `status != active` → 候选清理
- **蓝图是"活"的**：与实现产生漂移时由 `ai-guard-roadmap` 报出（不靠人记得）
- 新增文件 → 补本索引 + 跑 `bash .agents/guards/ai-guard-structure.sh --fix`

## 来源

**2026-10-04 二批**（判据＝「AI 上下文运行时是否需要」）：自 docs 区收归（原 VISION 与 product-roadmap，位置见本目录）——它们是 AI **每次会话必读**的方向锚点。
