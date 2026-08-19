---
title: assets 资产层索引
description: AI 工程资产层目录治理——规范/边界/ADR/已知坑，回答「为什么这么定/别踩什么/边界在哪」
version: 1
created: 2026-08-15
updated: 2026-08-20
status: active
lines: 46
depends-on: []
related:
  - ../README.md
tags: [ai, meta, index, assets]
---

# assets 资产层索引

**职责**：AI 工程静态知识——规范（怎么做）、边界（不做什么）、ADR（为什么这么定）、已知坑（别踩什么）。

## 文件清单

| 文件 | 职责 | 状态 |
|:-----|:-----|:----:|
| conventions.md | 代码/文档/协作规范归集（单一事实源）| active |
| skills-spec.md | 技能包规范（审查官/流程封装为 SKILL.md，name + frontmatter 融合）| active |
| boundaries.md | 边界声明（原则级 + 功能级「不做什么」）| active |
| pitfalls.md | 已知坑归集（按域分组，复发信号）| active |
| adr/ADR-001.md | AI 工程层为一等公民 | accepted |
| adr/ADR-002.md | 单一事实源（数字/未修项/批次）| accepted |
| adr/ADR-003.md | Domain=插件模型 | accepted |
| adr/ADR-004.md | 交易推送开关 + 交易日志归集审核流程 | accepted |
| adr/ADR-005.md | AI 协作入口标准化：AGENTS.md 统一入口 + 技能包体系（建收守闭环）| accepted |
| projects/adai-app.md | 项目资产：移动端 | active |
| projects/adai-core.md | 项目资产：后端核心 | active |
| projects/adai-web.md | 项目资产：桌面端 | active |
| projects/adai-admin.md | 项目资产：管理端 | active |

## 新增/变更规则

- 新决策 → 先过 **ADR 三问**（推翻成本高？有被否决备选？影响未来方向？全中才建）→ 建 `adr/ADR-00N.md`（决策/背景/备选/结论/代价/关联 RFC），登记本索引；不建则在 change-log 写清「为什么这么定」（2026-08-18 用户确立）
- 新坑 → ①入 checklists（活文档）②本层 pitfalls.md 补一行
- 新边界/规范 → 人确认后入对应文件 + 本索引

## 过期判断

- `status != active/accepted` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
