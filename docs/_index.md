---
title: docs 目录索引
description: 人类文档区目录治理——职责、子目录/关键文件清单、过期判断
version: 1
created: 2026-08-15
updated: 2026-08-15
status: active
lines: 55
depends-on: []
related:
  - ../ai-engineering/_index.md
  - architecture/_index.md
  - deployment/_index.md
  - guides/_index.md
  - ideas/_index.md
  - inbox/_index.md
  - reference/_index.md
  - releases/_index.md
  - review/_index.md
  - rfc/_index.md
  - README.md
tags: [meta, index]
---

# docs 目录索引

**职责**：AdaiOS 人类文档区（面向人阅读）。AI 协作标准在 `ai-engineering/`（面向 AI 执行），本区不含协作协议。

## 子目录与关键文件

| 路径 | 职责 |
|:-----|:-----|
| `VISION.md` | 项目愿景与理念（唯一理念真相源）|
| `README.md` | 文档总索引（分层：必读→架构→功能→API→决策→部署）|
| `ai-engineering/_index.md` | ★ AI 协作协议区（工具无关标准，目录治理索引）|
| `architecture/_index.md` | 架构文档（roadmap 唯一蓝图 / product-architecture / api-spec / data-format-freeze / system-architecture / frontend-reference）|
| `reference/_index.md` | 状态与历史（status.md 数字真相源 / change-log.md 批次 / feature-reference 功能真相源 / task-log 待办 / task-plugin-model）|
| `review/_index.md` | 审核（REVIEW.md 未修项滚动区 + audits/ 走查存档）|
| `rfc/_index.md` | 决策记录（RFC，status: draft/approved/implemented）|
| `releases/_index.md` | 发布记录（Release Notes）|
| `ideas/_index.md` | 想法归档区（未定型但有价值）|
| `inbox/_index.md` | 待处理/待归档区 |
| `guides/_index.md` | 使用指南 |
| `deployment/_index.md` | 部署文档 |

## 过期判断

- `status != active`（frontmatter）→ 候选归档
- 被 `docs/ideas/` 或 `docs/inbox/` 覆盖的重复文档 → 按各自 README 规则处理
- 与代码契约不一致的文档（api-spec/feature-reference）→ P1 级待修

## 关联

- AI 协作标准入口：`AGENTS.md` + `ai-engineering/README.md`
- 目录治理约定：`ai-engineering/frontmatter-spec.md` §五（_index.md 机制）
