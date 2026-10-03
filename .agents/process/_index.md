---
title: process/ 目录索引
description: process/ 的文件清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 33
depends-on: []
related: [./_directory.md]
tags: [meta, index]
---

# process/ 目录索引

**职责**：具体动作的流程定义——**天天用**：审查（audit/review）· 收尾（ship）· 节奏（cadence）

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

## 文件清单（4 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `audit.md` | /audit 通用版——8 审查官独立并行走查 + 交叉印证（默认增量，全量仅里程碑级），沉淀到 REVIEW.… | active |
| `cadence.md` | 用户与 AI 之间的固定节奏——每日巡检 / 收工 / 发布 / 每周 / 待办，各自「上次到哪、这次做什么、做… | active |
| `review.md` | /review 的通用版——按改动范围派对应审查官，滚动更新 REVIEW.md | active |
| `ship.md` | 开发收尾闭环——测试 → 契约同步 → 文档登记 → 元治理校验（guard-meta）→ 规范提交（**202… | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- **清单必须与实际文件一致**（`guard-structure` S2 双向校验；新增文件后跑 `guard-structure.sh --fix`）
