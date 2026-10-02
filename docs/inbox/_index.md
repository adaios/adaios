---
title: docs/inbox 目录索引
description: inbox 文档区目录治理——职责、文件清单、过期判断（文件自理机制）
version: 1
created: 2026-08-15
updated: 2026-08-15
status: active
lines: 30
depends-on: []
related:
  - ../_index.md
tags: [meta, index, inbox]
---

# docs/inbox 目录索引

**职责**：待处理/待归档区——结构精简时暂存的内容（保留可追溯，不直接删除）。

## 文件清单

| 文件 | 职责 | 状态 |
|:-----|:-----|:----:|
| README.md | 收件箱规则 + 归档记录 | active |
| （当前为空）| 2026-10-03 最后一篇已归位 `../archive/ai-context/`——**空态是 inbox 的正常状态**（待处理区本就该是空的）| — |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- 新增文档：补本索引 + frontmatter
