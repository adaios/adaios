---
title: docs/ai 目录索引
description: AI 协作协议区目录治理——职责、文件清单、过期判断（文件自理机制）
version: 1
created: 2026-08-15
updated: 2026-08-15
status: active
lines: 30
depends-on: []
related: [frontmatter-spec.md]
tags: [ai, meta, index]
---

# docs/ai 目录索引

**职责**：AdaiOS 开发期 AI 协作标准（工具无关）。新增协作类标准文档放此区。

## 文件清单

| 文件 | 职责 | 状态 |
|:-----|:-----|:----:|
| README.md | 入口：定位 + 接入指南 | active |
| frontmatter-spec.md | 文档元数据契约 | active |
| roles/product-arch.md | 产品架构师（全局/原则）| active |
| roles/ux-reviewer.md | 交互体验师（流程/异常）| active |
| roles/ui-reviewer.md | 界面设计师（视觉/触达）| active |
| roles/backend-reviewer.md | 后端代码官 | active |
| roles/frontend-reviewer.md | 前端代码官 | active |
| roles/docs-reviewer.md | 文档契约官 | active |
| roles/knowledge-reviewer.md | 知识数据官 | active |
| roles/context-reviewer.md | AI Context 审查官 | active |
| process/audit.md | 全维度走查流程 | active |
| process/review.md | 增量深审流程 | active |
| checklists/review-ux.md | 交互检查清单 | active |
| checklists/review-ui.md | 界面检查清单 | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- 新增角色/流程：补本索引 + frontmatter
