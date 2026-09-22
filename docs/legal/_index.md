---
title: docs/legal 目录索引
description: 法务文档区目录治理——职责、文件清单、过期判断（文件自理机制）
version: 1
created: 2026-09-17
updated: 2026-09-21
status: active
lines: 29
depends-on: []
related:
  - ../_index.md
tags: [meta, index, legal]
---

# docs/legal 目录索引

**职责**：对外法务文档区——需要公开访问的正文（隐私政策、用户协议等），供 App Store / TestFlight 提交与网站引用。

## 文件清单

| 文件 | 职责 | 状态 |
|:-----|:-----|:----:|
| privacy-policy.md | 隐私政策正文（App Store 与 TestFlight 提交材料；已发布于 `https://adaiadai.com/privacy`，页面源文件 `apps/adai-app/web/privacy.html`）| active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- 新增文档：补本索引 + frontmatter
