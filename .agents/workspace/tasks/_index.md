---
title: workspace/tasks/ 目录索引
description: 任务账本目录的文件清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 28
depends-on: []
related: [./_directory.md]
tags: [meta, index]
---

# workspace/tasks/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

## 文件清单

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `_template.md` | 任务账本模板——开分支时复制为 `<分支名>.md` | active |

## 过期判断

- **分支合并后对应账本必须归档并删除**（`main` 上残留 = 未收尾）
- `status != active` → 候选清理
- **清单须与实际一致**（`guard-structure` S2 双向校验；跑 `bash .agents/guards/guard-structure.sh --fix` 刷新）
