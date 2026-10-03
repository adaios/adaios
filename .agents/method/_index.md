---
title: method/ 目录索引
description: method/ 的文件清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 32
depends-on: []
related: [./_directory.md]
tags: [meta, index]
---

# method/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

## 文件清单（5 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `README.md` | 方法论放回仓库——AI 工程切入点图谱：流程机制替人记得（流程约定 > 内容编写）；新项目 = 搭一条流水线 | active |
| `pipeline-sequence.mmd` | — | active |
| `pipeline.md` | AI 工程流水线每个切入点的职责/脚本/触发/拦截——从 adaios 实践提炼，可复制到新项目 | active |
| `pipeline.mmd` | — | active |
| `scaffold.md` | init-ai-engineering.sh 设计——新项目一条命令搭好 AI 工程流水线（hooks + gu… | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- **清单必须与实际文件一致**（`guard-structure` S2 双向校验；新增文件后跑 `guard-structure.sh --fix`）
