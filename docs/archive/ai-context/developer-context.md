---
title: 开发者上下文（Developer Context）—— 已退役
description: 早期「开发者上下文」模板（当前会话/迭代/待办/技术债/进行中变更/已知问题）。2026-10-03 退役——全部字段为未填占位符，实际承担者是 AGENTS.local.md / task-log.md / REVIEW.md / git status + cadence.sh ship。
version: 1
created: 2026-08-01
updated: 2026-10-03
status: superseded
lines: 79
depends-on: []
related:
  - ../../../AGENTS.md
  - ../../../ARCHITECTURE.md
tags: [ai, context, superseded]
---

> ⛔ **已退役（2026-10-03）**：本文属早期「项目级 AI 上下文」设计（`ai/context/`），**内容已被现有体系覆盖**——项目定位与规则见 `AGENTS.md`、架构红线见 `ARCHITECTURE.md`、状态快照见 `AGENTS.local.md`（由 `ai-guard-context.sh --write-local` 自动生成）。本文宣称的「会话启动自动加载」**从未有实现**（`{{占位符}}` 从未被填充）。**保留仅作历史痕迹，请勿据此执行。**

# Developer Context

> 开发者上下文，描述当前开发任务、分支状态、待办事项与技术债务。
> 每次开发会话开始时加载，结束时更新。

## 当前会话

- **日期**：{{date}}
- **分支**：{{branch}}
- **任务**：{{current_task}}
- **关联 Issue/PR**：{{references}}

## 开发环境

- **IDE**：{{ide}}
- **JDK**：Java 17（{{jdk_version}}）
- **Gradle**：{{gradle_version}}
- **MySQL**：{{mysql_version}}
- **OS**：{{operating_system}}

## 当前迭代

- **迭代目标**：{{sprint_goal}}
- **状态**：{{status}}
- **开始时间**：{{start_date}}
- **结束时间**：{{end_date}}

## 待办工作

### {{priority_high}}

- [ ] {{task_item}} — {{assignee}}（{{deadline}}）

### {{priority_medium}}

- [ ] {{task_item}} — {{assignee}}

## 技术债务

| 条目 | 严重程度 | 引入时间 | 计划处理 |
|------|---------|---------|---------|
| {{item}} | {{severity}} | {{date}} | {{planned_date}} |

## 进行中的变更

### 已修改文件

- {{file_path}} — {{change_description}}

### 待提交

- {{change_summary}}

## 已知问题

- {{issue_description}}

## 相关资源

- 领域文档：`os/*-os/definition/`
- 架构上下文：`architecture-context.md`
- 项目上下文：`project-context.md`
