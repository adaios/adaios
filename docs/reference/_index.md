---
title: docs/reference 目录索引
description: reference 文档区目录治理——职责、文件清单、过期判断（文件自理机制）
version: 1
created: 2026-08-15
updated: 2026-08-22
status: active
lines: 36
depends-on: []
related:
  - ../_index.md
tags: [meta, index, reference]
---

# docs/reference 目录索引

**职责**：状态与历史区——数字真相源 / 批次历史 / 功能真相源 / 待办。

## 文件清单

| 文件 | 职责 | 状态 |
|:-----|:-----|:----:|
| status.md | 测试数/端点数/运行环境唯一真相 | active |
| change-log.md | 批次变更日志（历史归档） | active |
| feature-reference.md | 功能真相源（唯一） | active |
| trading-features.md | 交易模块（trading 插件）功能手册——端点总表/定时任务/双端功能/知识底座/已知缺陷 | active |
| task-log.md | 走查/待办日志 | active |
| task-plugin-model.md | 任务插件模型说明 | active |
| framework-plugin-gap.md | 框架+插件形态的现状差距与迁移路径（对账清单，回答"会不会重构"） | active |
| issue-log.md | 问题跟踪 | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- 新增文档：补本索引 + frontmatter
