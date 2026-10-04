---
title: reference/ 目录索引
description: .agents/knowledge/reference/ 的文件清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 69
depends-on: []
related: [./_directory.md]
tags: [meta, index, reference]
---

# reference/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

**职责**：**② 事实（按需查）**——回答「**现在是什么样**」。改哪一块就读哪一份；`status.md` 例外，它每次开工都被自举读。

## 文件清单（19 项）

### 状态与总纲

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `status.md` | 📌 **数字真相源**——测试数 / 端点数 / 运行环境 / 发布态（开工自举读） | active |
| `product-architecture.md` | 五层产品架构详解（Layer 1-6） | active |
| `system-architecture.md` | 系统架构、Kernel/Domain 分层、Context Engine | active |
| `framework-plus-plugin-model.md` | ★ **形态总纲**——一个框架 + 各种插件 | active |
| `framework-plugin-gap.md` | 框架+插件形态的现状差距与迁移路径（回答"会不会重构"） | active |

### 契约（改代码必查）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `api-spec.md` | 📋 **API 接口契约（唯一真相源）**——`ai-guard-align` A1 与源码逐一对拍 | active |
| `data-format-freeze.md` | 📦 `data/` 文件格式契约 + 变更规则（v1.0.0 冻结） | active |
| `frontend-reference.md` | 前端统一参考（术语对照 + 布局视觉） | active |

### 功能手册

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `feature-reference.md` | 功能参考（**与 `../features/_index.md` 功能主轴的关系待显式决策**） | active |
| `trading-features.md` | 交易模块功能手册——端点总表 / 定时任务 / 双端功能 / 知识底座 | active |
| `admin-features.md` | adai-admin 管理后台功能手册 | active |
| `task-plugin-model.md` | 任务插件模型说明 | active |

### 领域设计

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `memory-os-design.md` | 记忆 OS 设计规约（职责 / 数据模型 / 与 Context Engine 的关系） | active |
| `trading-plugin-architecture.md` | 交易插件架构设计（通用能力层 vs 个性化规则层） | draft |
| `trading-case-library-design.md` | 完美买点案例库设计（案例沉淀 → 判定当下） | draft |
| `trading-case-data-usage.md` | 案例数据取用口径 | active |
| `trading-data-adjustment.md` | 行情数据前复权设计（TDX 本地正确性） | draft |
| `trading-market-stage.md` | 活跃市值区间开关方案（多头/空头手动判定） | active |
| `trading-pending-decisions.md` | 交易模块待决事项清单 | active |

## 过期判断

- `status != active` → 候选清理
- `api-spec` / `status` 由 `ai-guard-align` **机器对拍**（不靠人记得）
- 新增文件 → 补本索引 + 跑 `bash .agents/mechanism/guards/ai-guard-structure.sh --fix`

## 来源

**2026-10-04 二批**：自 docs 区的 architecture（13 份）与 reference（6 份）收归——判据＝「AI 上下文运行时是否需要」。
