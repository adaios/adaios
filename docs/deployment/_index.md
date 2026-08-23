---
title: docs/deployment 目录索引
description: deployment 文档区目录治理——职责、文件清单、过期判断（文件自理机制）
version: 1
created: 2026-08-15
updated: 2026-08-20
status: active
lines: 30
depends-on: []
related:
  - ../_index.md
tags: [meta, index, deployment]
---

# docs/deployment 目录索引

**职责**：部署文档区——后端/前端部署操作说明。

## 文件清单

| 文件 | 职责 | 状态 |
|:-----|:-----|:----:|
| backend-deployment.md | 后端部署（deploy.sh + 生产环境） | active |
| icp-filing.md | 域名备案（ICP）资料整理与填报指引（adaiadai.com） | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- 新增文档：补本索引 + frontmatter
