---
title: AdaiOS 架构骨架（架构红线）
description: AI 进项目直读的架构红线单文件——技术栈、五层架构、分层依赖、数据流、红线清单（违反即越界）
version: 1
created: 2026-08-19
updated: 2026-08-19
status: active
lines: 62
depends-on: []
related:
  - AGENTS.md
  - docs/architecture/product-architecture.md
  - docs/architecture/system-architecture.md
  - ai-engineering/assets/boundaries.md
tags: [ai, architecture, governance]
---

# AdaiOS 架构骨架（ARCHITECTURE.md）

> **架构红线单文件**——AI 进项目直读，防止随意变更技术栈/数据流向/权限逻辑。详细设计见 `docs/architecture/`；本文件只承载红线。

## 一句话

AdaiOS 是 **Personal AI Operating System**：以 Kernel（Context + Memory + Knowledge）为核心、个人文件（`data/`）为资产、Domain OS（`os/`）为能力边界。**不是 CRUD 应用**。

## 技术栈（红线：不随意变更）

| 层面 | 选型 |
|:-----|:-----|
| 后端 | Java 17 + Spring Boot 3.3.x，根包 `com.adaiadai.core`（`services/adai-core/`）|
| 前端 | Flutter（`apps/`：adai-app 移动 / adai-web 桌面 / adai-admin 管理，三端独立 UI 值复制非适配）|
| 数据库 | MySQL 8.0 (dev) / H2 (test) |
| 仓库形态 | Monorepo；架构风格 **Modular Monolith** |

## 五层产品架构

L1 AI 问答 / L2 主动推送 / L3 数字身份 / L4 通用记录 / L5 外部信息 / L6 交易反哺——**任何新功能必须明确归属某层**（详见 `docs/architecture/product-architecture.md`）。

## 分层依赖（红线）

```
interfaces → application → domain/kernel ← infrastructure
```

- Kernel 内数据流水线：Record → Timeline → Context → Memory
- **Domain 之间禁止直接依赖**；跨域协作经 `application` 层编排
- `infrastructure` 实现 `kernel`/`domain` 定义的接口（依赖倒置）

## 数据流（红线）

```
Human → Record/File → Kernel (Context + Memory + Knowledge) → Domain OS → AI Model
```

- **File First**：`os/` 与 `data/` 长期知识以文件为准，数据库为查询存在；`data/` 隐私受 gitignore 保护不提交
- **入口统一**：`POST /api/v1/records` 是唯一输入入口，IntentRecognizer 后台分流
- **AI 是基础设施层，不是业务层**：LLM 调用归 `infrastructure/ai`，Prompt 管理归 `kernel/context/prompt`

## 红线清单（违反即越界）

1. **不提前微服务化**：Modular Monolith 是默认，直到满足独立生命周期/数据边界/独立部署/多人维护四条
2. **不混合代码和知识**：Prompt 模板归 `kernel/context/prompt`，不散落业务代码
3. **新能力必须明确所属 Domain**：先回答「属于 Kernel 还是 Domain OS」，找不到归属先讨论架构
4. **先设计数据流再写代码**：Record 文件格式 → Timeline 投影 → Context 组合 → Memory 沉淀
5. **不绕过 ContextEngine**：禁止手拼 prompt / 跳过内核直接访问数据库
6. **数据丢失不可接受（P0）**：data/ 是唯一真相源，任何写操作防覆盖/防降级删除
7. **讨论与实施分离**：未获「开工」不写代码、不动 data/ 数据资产
8. **审查只报告不直接修**（除 P0 数据丢失可与用户确认后修）
9. **外向动作默认不做**：部署/推送/外发网络请求须人确认

> 原则级边界完整版（B1-B9）见 `ai-engineering/assets/boundaries.md`。
