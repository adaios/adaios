---
title: docs/ideas 目录索引
description: ideas 文档区目录治理——职责、文件清单、过期判断（文件自理机制）
version: 1
created: 2026-08-15
updated: 2026-08-15
status: active
lines: 32
depends-on: []
related:
  - ../_index.md
tags: [meta, index, ideas]
---

# docs/ideas 目录索引

**职责**：想法/方案归档区——未定型但有价值的想法（暂不参与主流程，可能孵化成 RFC）。

## 文件清单

| 文件 | 职责 | 状态 |
|:-----|:-----|:----:|
| README.md | 归档说明（含子目录/文件登记） | active |
| 20260812-ai-interaction-log.md | AI 交互日志需求（R1，已实现） | active |
| 20260812-camera-sports-analysis.md | 相机运动分析想法 | active |
| 20260812-record-task-association.md | 记录↔任务模块关联（R2，已实现） | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- 新增文档：补本索引 + frontmatter
