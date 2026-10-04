---
title: 项目级上下文（Project Context）—— 已退役
description: 早期「项目级 AI 上下文」模板：整体定位、必读文档、架构摘要、项目状态、关键决策。2026-10-03 退役——占位符从未填充，内容已由 AGENTS.md / ARCHITECTURE.md / AGENTS.local.md / assets/adr 承担。
version: 1
created: 2026-08-20
updated: 2026-10-03
status: superseded
lines: 66
depends-on: []
related:
  - ../../../AGENTS.md
  - ../../../ARCHITECTURE.md
tags: [ai, context, superseded]
---

> ⛔ **已退役（2026-10-03）**：本文属早期「项目级 AI 上下文」设计（`ai/context/`），**内容已被现有体系覆盖**——项目定位与规则见 `AGENTS.md`、架构红线见 `ARCHITECTURE.md`、状态快照见 `AGENTS.local.md`（由 `ai-guard-context.sh --write-local` 自动生成）。本文宣称的「会话启动自动加载」**从未有实现**（`{{占位符}}` 从未被填充）。**保留仅作历史痕迹，请勿据此执行。**

# Project Context

> 项目级上下文，描述 AdaiOS 项目的整体定位、架构与状态。
> 每次 AI 会话启动时自动加载。

## 项目标识

- **项目名称**：AdaiOS
- **项目定位**：Personal AI Operating System（个人 AI 操作系统）— 不是 CRUD 应用，是 AI Native OS
- **版本**：{{version}}
- **仓库地址**：{{repository_url}}
- **技术栈**：Java 17 + Spring Boot 3 + Gradle Monorepo + Modular Monolith

## 必读文档

- [VISION.md](../../.agents/direction/VISION.md) — ⚡ 项目愿景与核心理念（每个 AI 会话必须首先阅读）
- [AGENTS.md](../../AGENTS.md) — 完整架构与开发规则
- [.agents/knowledge/reference/system-architecture.md](../../.agents/knowledge/reference/system-architecture.md) — v0.2 系统架构

## 架构摘要

- **Monorepo**：`apps/` `services/` `os/` `data/` `ai/` `infra/` `docs/`
- **核心模块**：`services/adai-core`（根包 `com.adaiadai.core`）
- **架构模式**：Modular Monolith（不提前微服务化）
- **最高设计原则**：**File First, Database Second, Context Always**（详见 VISION.md §3.5）
- **核心能力**：Context Engine（Kernel 层，非 AI 辅助模块）
- **Kernel Domain**：identity / record / timeline / context / memory / knowledge
- **Domain OS**：trading / life / research / project

## 项目状态

- **当前阶段**：{{current_phase}}（初始化/开发中/测试/迭代）
- **活跃迭代**：{{active_iteration}}
- **重点事项**：{{focus_items}}
- **已知约束**：{{known_constraints}}

## 关键决策记录

| 日期 | 决策 | 背景 |
|------|------|------|
| {{date}} | Kernel/Domain 分层 | 两类 Domain 分离，Context 移入 Kernel |
| {{date}} | AI 归入 Infrastructure | AI 不是业务层，LLM 调用/路由/适配归 infrastructure |
| {{date}} | Timeline 是 Record 投影 | 不是独立实体，Record 时间组织 |

## 相关文档

- [VISION.md](../../.agents/direction/VISION.md) — ⚡ 项目愿景与核心理念（每个 AI 会话必须首先阅读）
- [AGENTS.md](../../AGENTS.md) — 完整架构与开发规则
- [README.md](../../README.md) — 项目总览
