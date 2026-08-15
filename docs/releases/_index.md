---
title: docs/releases 目录索引
description: releases 文档区目录治理——职责、文件清单、过期判断（文件自理机制）
version: 1
created: 2026-08-15
updated: 2026-08-15
status: active
lines: 30
depends-on: []
related:
  - ../_index.md
tags: [meta, index, releases]
---

# docs/releases 目录索引

**职责**：发布记录区——Release Notes（发布时登记）。

## 文件清单

| 文件 | 职责 | 状态 |
|:-----|:-----|:----:|
| _template.md | 发布记录模板 | active |
| v1.0.0.md | v1.0.0 发布记录 | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- 新增文档：补本索引 + frontmatter
