---
title: docs/architecture 目录索引
description: architecture 文档区目录治理——职责、文件清单、过期判断（文件自理机制）
version: 1
created: 2026-08-15
updated: 2026-08-30
status: active
lines: 39
depends-on: []
related:
  - ../_index.md
tags: [meta, index, architecture]
---

# docs/architecture 目录索引

**职责**：AdaiOS 架构文档区——roadmap 唯一蓝图 / 五层架构 / 系统架构 / 契约（api-spec / data-format-freeze）。

## 文件清单

| 文件 | 职责 | 状态 |
|:-----|:-----|:----:|
| product-roadmap.md | 🚩 产品路线唯一蓝图（路线驱动开发） | active |
| product-architecture.md | 五层产品架构 | active |
| framework-plus-plugin-model.md | ★ 形态总纲——一个框架 + 各种插件（框架装「你是谁」，插件装「你能做什么」）| active |
| system-architecture.md | 系统架构细节 | active |
| api-spec.md | API 接口契约（唯一真相源） | active |
| data-format-freeze.md | data/ 文件格式契约 + 变更规则 | active |
| frontend-reference.md | 前端统一参考（术语对照 + 布局视觉） | active |
| memory-os-design.md | 记忆 OS 设计 | active |
| memory-frameworks-borrow.md | 开源记忆方案借鉴分析（Mem0/Letta/Zep/File-First 生态 → 可借鉴清单） | active |
| ai-calling-governance.md | AI 调用治理方案（模型路由 / 流式 / 超时矩阵 / 上下文瘦身，分三批落地） | draft |
| trading-plugin-architecture.md | ★ 交易插件架构设计（第二阶段：通用能力层 vs 个性化规则层，规则按用户隔离/可导入导出，多用户开放蓝图） | draft |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- 新增文档：补本索引 + frontmatter
