---
title: docs/guides 目录索引
description: guides 文档区目录治理——职责、文件清单、过期判断（文件自理机制）
version: 1
created: 2026-08-15
updated: 2026-09-17
status: active
lines: 33
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
| development.md | 开发指南：全局构建/测试/运行/部署命令 + 换机必跑的三件事（hooks/技能注册/定时任务） | active |
| routine.md | **固定动作清单（每天/每周/到期）**——周期性人肉工作总清单 + 2026-09-14 盘点的三个静默失效缺口 | active |
| skills-usage.md | 技能使用指南：技能何时用、怎么触发、怎么维护（给人看） | active |
| project-os-usage.md | Project OS 使用指南：输入框问"项目阿呆"、任务管理、场景示例 | active |
| qoder-parallel-workflow.md | **Qoder CN 并行工作流实验手册（worktree 多任务）**——跨项目通用：环境配置、IDEA 协同、验证清单、坑与回滚（2026-09-17） | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- 新增文档：补本索引 + frontmatter
