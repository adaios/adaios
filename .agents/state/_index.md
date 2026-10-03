---
title: state/ 目录索引
description: state/ 的文件清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 34
depends-on: []
related: [./_directory.md]
tags: [meta, index]
---

# state/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

## 文件清单（7 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `backup.log` | — | active |
| `cadence.json` | — | active |
| `cost-cache.json` | — | active |
| `cost-log.jsonl` | — | active |
| `noon-task.log` | — | active |
| `usage-cache.json` | — | active |
| `weekly-audit.log` | — | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- **清单必须与实际文件一致**（`ai-guard-structure` S2 双向校验；新增文件后跑 `ai-guard-structure.sh --fix`）
