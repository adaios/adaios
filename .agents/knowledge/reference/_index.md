---
title: reference/ 目录索引
description: .agents/knowledge/reference/ 的文件清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 58
depends-on: []
related: [./_directory.md]
tags: [meta, index, reference]
---

# reference/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

**职责**：**② 事实（按需查）**——回答「**现在是什么样**」。改哪一块就读哪一份；`status.md` 例外，它每次开工都被自举读。

## 子目录（3）

| 子目录 | 类 | 装什么 | 索引 | 契约 |
|:--|:--|:--|:--:|:--:|
| `contracts/` | 契约 | `api-spec` · `data-format-freeze`——由 `ai-guard-align` 与源码**逐一对拍** | [→](./contracts/_index.md) | [→](./contracts/_directory.md) |
| `manuals/` | 手册 | `feature-reference` · `trading-features` · `admin-features` · `task-plugin-model`——改某模块前查 | [→](./manuals/_index.md) | [→](./manuals/_directory.md) |
| `designs/` | 设计 | 架构 4 份 + 领域设计 6 份——改架构前读 | [→](./designs/_index.md) | [→](./designs/_directory.md) |

## 文件清单（17 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `contracts/api-spec.md` | 📋 **API 接口契约（唯一真相源）**——`ai-guard-align` A1 与源码逐一对拍 | active |
| `contracts/data-format-freeze.md` | 📦 `data/` 文件格式契约 + 变更规则（v1.0.0 冻结） | active |
| `designs/framework-plus-plugin-model.md` | ★ **形态总纲**「一个框架 + 各种插件」（**含现状对账附录**） | active |
| `designs/frontend-reference.md` | 前端统一参考（术语对照 + 布局视觉） | active |
| `designs/memory-os-design.md` | 记忆 OS 设计规约（职责 / 数据模型 / 与 Context Engine 的关系） | active |
| `designs/product-architecture.md` | 五层产品架构详解（Layer 1-6） | active |
| `designs/system-architecture.md` | 系统架构、Kernel/Domain 分层、Context Engine | active |
| `designs/trading-case-library-design.md` | 完美买点案例库设计（**含数据使用方案附录**） | active |
| `designs/trading-data-adjustment.md` | 行情数据前复权设计（TDX 本地正确性） | active |
| `designs/trading-market-stage.md` | 活跃市值区间开关方案（多头/空头手动判定） | active |
| `designs/trading-pending-decisions.md` | 交易模块待决事项清单 | active |
| `designs/trading-plugin-architecture.md` | 交易插件架构设计（通用能力层 vs 个性化规则层） | active |
| `manuals/admin-features.md` | adai-admin 管理后台功能手册 | active |
| `manuals/feature-reference.md` | 功能参考（明细）——与 `../features/_index.md` 主轴的关系**待显式决策** | active |
| `manuals/task-plugin-model.md` | 任务插件模型说明 | active |
| `manuals/trading-features.md` | 交易模块功能手册——端点总表 / 定时任务 / 双端功能 / 知识底座 | active |
| `status.md` | 📌 **数字真相源**——测试数/端点数/运行环境/发布态（**开工自举读**，刻意留根） | active |

## 过期判断

- `status != active` → 候选清理
- `api-spec` / `status` 由 `ai-guard-align` **机器对拍**（不靠人记得）
- 新增文件 → 补本索引 + 跑 `bash .agents/mechanism/guards/ai-guard-structure.sh --fix`

## 来源

**2026-10-04 二批**：自 docs 区的 architecture（13 份）与 reference（6 份）收归——判据＝「AI 上下文运行时是否需要」。
