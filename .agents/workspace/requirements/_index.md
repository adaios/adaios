---
title: workspace/requirements/ 目录索引
description: 需求文稿目录的文件清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-03
updated: 2026-10-04
status: active
lines: 28
depends-on: []
related: [./_directory.md]
tags: [meta, index]
---

# workspace/requirements/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

## 文件清单

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `_template.md` | 需求文稿模板——有新需求时复制为 `<需求id>.md` | active |

## 过期判断

- **定稿归档后必须删除**（定稿搬进 `docs/rfc/` 或 `docs/features/`，`workspace/` 只留在制品）
- `status != active` → 候选清理
- **清单须与实际一致**（`ai-guard-structure` S2 双向校验；跑 `bash .agents/guards/ai-guard-structure.sh --fix` 刷新）
