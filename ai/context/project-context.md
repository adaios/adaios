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

- [VISION.md](../../docs/VISION.md) — ⚡ 项目愿景与核心理念（每个 AI 会话必须首先阅读）
- [CLAUDE.md](../../CLAUDE.md) — 完整架构与开发规则
- [docs/architecture/system-architecture.md](../../docs/architecture/system-architecture.md) — v0.2 系统架构

## 架构摘要

- **Monorepo**：`apps/` `services/` `domains/` `data/` `ai/` `infra/` `docs/`
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

- [VISION.md](../../docs/VISION.md) — ⚡ 项目愿景与核心理念（每个 AI 会话必须首先阅读）
- [CLAUDE.md](../../CLAUDE.md) — 完整架构与开发规则
- [README.md](../../README.md) — 项目总览
