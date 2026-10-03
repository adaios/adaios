---
title: workspace/designs/ 目录索引
description: 设计与审核多轮记录的清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-03
updated: 2026-10-04
status: active
lines: 31
depends-on: []
related: [./_directory.md]
tags: [meta, index]
---

# workspace/designs/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

## 文件清单

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `_template-design.md` | 模板 A：设计文档（设计文档编写者产出）| active |
| `_template-review.md` | 模板 B：设计审核文件（设计文档审核者产出）| active |

> **各需求的设计稿与审核稿**在 `<需求id>/` 子目录下（每轮两份：`design-v<N>-<YYYYMMDD>.md` + `review-v<N>-<YYYYMMDD>.md`）。

## 过期判断

- **收敛归档后整个 `<需求id>/` 删除**（`design-final.md` 搬进 `docs/architecture/` + ADR）
- `status != active` → 候选清理
- **清单须与实际一致**（`guard-structure` S2；跑 `bash .agents/guards/guard-structure.sh --fix` 刷新）
