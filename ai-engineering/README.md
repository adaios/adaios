---
title: AI 工程层
description: AdaiOS 的 AI 工程层入口——资产（规范/边界/ADR/坑）+ 工作流（讨论→方案→开发→审核→验收）+ 状态（动态真相）；代码工程是结果集，AI 工程是驱动层
version: 1
created: 2026-08-15
updated: 2026-08-15
status: active
lines: 57
depends-on:
  - frontmatter-spec.md
related:
  - ../AGENTS.md
  - ../docs/review/REVIEW.md
  - assets/_index.md
  - workflow/_index.md
  - state/_index.md
tags: [ai, meta, engineering]
---

# AI 工程层（ai-engineering/）

> **定位**：代码工程（services/ apps/ os/）是**结果集**；AI 工程是**驱动层**——怎么讨论、怎么定方案、怎么开发、怎么审核、怎么验收，以及一路沉淀的规范/边界/决策/坑。任何 AI 工具读此层即可参与项目全流程（工具无关）。

## 三层结构

| 层 | 内容 | 说明 |
|:---|:-----|:-----|
| `assets/` | 静态知识：规范 / 边界 / ADR / 已知坑 / 架构 | 回答「为什么这么定 / 别踩什么 / 边界在哪」|
| `workflow/` | 过程定义：讨论→方案→开发→审核→验收 | 回答「现在怎么做」|
| `state/` | 动态真相：完成度 / 测试数 / 未修项 | 回答「做到哪了」|

## 目录

| 文件 | 说明 |
|:-----|:-----|
| `frontmatter-spec.md` | 文档元数据契约（图谱/治理/归档基础）|
| `roles/` | 8 个审查官定义（产品架构/交互体验/界面设计/后端/前端/文档/知识数据/Context）|
| `process/` | 流程定义（audit 走查 / review 深审 / ship 收尾）|
| `checklists/` | 检查清单（执行细节，人也能用：8 官清单 + guard 守护）|
| `guard-meta.sh` | 元治理自检：frontmatter 图谱/lines/孤儿，`--fix` 回写 |
| `assets/` | 资产层：ADR 决策索引 / 已知坑 / 边界 / 规范 |
| `workflow/` | 工作流层：discuss/design/develop 前置段（review/audit/ship 在 process/）|
| `state/` | 状态层：完成度 / 测试数 / 未修项（指针化）|

## 任何 AI 工具如何接入

0. **先读现状**：`state/_index.md`（做到哪了）
1. 读本 `README.md`（定位 + 三层结构）→ `frontmatter-spec.md`（元数据契约）
2. **动工前查资产**：`assets/boundaries.md`（边界）+ `assets/pitfalls.md`（坑）+ `assets/conventions.md`（规范）
3. 开发：`workflow/`（讨论→方案→开发）→ 产出进代码工程
4. 收尾：`process/ship.md`（guard-meta 门禁）
5. 审查：`process/audit.md`（全维度）或 `process/review.md`（增量），按 `roles/` 派官
6. 沉淀：决策入 `assets/adr/`，坑入 `assets/pitfalls.md`，结果更新 `state/`

> 工具侧入口（Claude/Qoder/DSH 的一行配置）在工具自己的配置里，**不在本项目**——换工具零迁移。

> **跨项目方法论**：本层是可复制的实例；通用骨架在仓库外 `ai-engineering-method/`（同级目录）——复制的是「如何建」，不是「建好的」。
