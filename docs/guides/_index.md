---
title: docs/guides 目录索引
description: guides 文档区目录治理——职责、文件清单、过期判断（文件自理机制）
version: 1
created: 2026-08-15
updated: 2026-08-15
status: active
lines: 29
depends-on: []
related:
  - ../_index.md
tags: [meta, index, guides]
---

# docs/guides 目录索引

**职责**：使用指南区——各功能/模块的人用指南。

## 文件清单

| 文件 | 职责 | 状态 |
|:-----|:-----|:----:|
| project-os-usage.md | Project OS 使用指南 | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- 新增文档：补本索引 + frontmatter
