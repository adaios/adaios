---
title: docs/review 目录索引
description: review 文档区目录治理——职责、文件清单、过期判断（文件自理机制）
version: 1
created: 2026-08-15
updated: 2026-08-15
status: active
lines: 29
depends-on: []
related:
  - ../_index.md
tags: [meta, index, review]
---

# docs/review 目录索引

**职责**：审核结果区——未修项滚动区 + 走查存档。

## 文件清单

| 文件 | 职责 | 状态 |
|:-----|:-----|:----:|
| REVIEW.md | 审核全量状态报告（未修项滚动区） | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- 新增文档：补本索引 + frontmatter
