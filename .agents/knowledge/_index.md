---
title: knowledge/ 目录索引
description: .agents/knowledge/ 的文件清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 1
depends-on: []
related: [./_directory.md]
tags: [meta, index]
---

# knowledge/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

**职责**：见 [`_directory.md`](./_directory.md)（此处不重复——S5 判据：同一知识只在一处详述）

## 文件清单（20 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `features/kernel.md` | Kernel 域功能的意图卡——按需生长，只写意图（干什么用/不做什么/选型理由/验收），实现细节一律链接出去 | active |
| `features/learn.md` | learn 插件的意图卡——按需生长，只写意图（干什么用/不做什么/选型理由/验收），实现细节一律链接出去 | active |
| `features/trading.md` | trading 插件的意图卡——按需生长，只写意图（干什么用/不做什么/选型理由/验收），实现细节一律链接出去 | active |
| `reference/contracts/api-spec.md` | 📋 **API 接口契约（唯一真相源）**——全部端点定义与请求/响应结构；`ai-guard-align` A… | active |
| `reference/contracts/data-format-freeze.md` | 📦 `data/` 全部文件格式契约 + 变更规则（v1.0.0 冻结） | active |
| `reference/designs/framework-plus-plugin-model.md` | AdaiOS 的形态定义——一个框架 + 各种插件；框架装「你是谁」，插件装「你能做什么」；能力按用户叠加。五层… | active |
| `reference/designs/frontend-reference.md` | 前端统一参考——UI 术语对照 + 布局视觉（含 adai-web 桌面端章节） | active |
| `reference/designs/memory-os-design.md` | Memory OS 设计规约——职责边界、数据模型，以及与 Context Engine / Domain OS… | active |
| `reference/designs/product-architecture.md` | 五层产品架构详解（Layer 1-6）——Kernel / Domain OS / 插件的分层与职责 | active |
| `reference/designs/system-architecture.md` | 系统架构细节——Kernel / Domain 分层、Context Engine、数据流与关键机制 | active |
| `reference/designs/trading-case-library-design.md` | 规划 RFC 20260830 的可落地设计——案例数据模型、特征提取算法、归一化相似度引擎、API 契约草案、… | draft |
| `reference/designs/trading-data-adjustment.md` | 通达信 .day 不复权原始价在除权日跳空导致回撤/特征/相似度失真——用东财除权因子表 + 本地换算实现前复权… | draft |
| `reference/designs/trading-market-stage.md` | 把「活跃市值=一切的前提」的区间判定权还给用户——App/Web 手动切多头/空头，落 market-stage… | active |
| `reference/designs/trading-pending-decisions.md` | 三项交易需求的口径/参数待用户拍板——按批次止损编辑闭环（数据模型）、资金曲线图（口径/周期/端点）、买点三重校… | draft |
| `reference/designs/trading-plugin-architecture.md` | 交易插件第三阶段蓝图——通用能力层与个性化规则层分离，规则按用户隔离、可自定义/可导入/可导出，为多用户 + 插… | draft |
| `reference/manuals/admin-features.md` | AdaiOS 管理后台（adai-admin）的完整功能参考——定位与边界、登录鉴权与会话、四区页面清单、每页功… | active |
| `reference/manuals/feature-reference.md` | 功能参考——各模块功能明细（**与 `../features/_index.md` 功能主轴的关系待显式决策**… | active |
| `reference/manuals/task-plugin-model.md` | 任务插件模型说明——Plugin 模型的实施任务拆分与依赖关系 | active |
| `reference/manuals/trading-features.md` | AdaiOS trading 插件的完整功能参考——模块定位、后端端点总表与定时任务、Web/App 双端功能清… | active |
| `reference/status.md` | 📌 **数字真相源**——测试数 / 端点数 / 运行环境 / 发布态；`ai-guard-align` A2 … | active |

## 子目录

| 目录 | 职责 | 索引 | 契约 |
|:--|:--|:--:|:--:|
| `reference/` | — | [→](./reference/_index.md) | [→](./reference/_directory.md) |
| `features/` | — | [→](./features/_index.md) | [→](./features/_directory.md) |

## 过期判断

- 新增子目录 → 必须同时有 `_index.md` 与 `_directory.md`
- **清单须与实际一致**（`ai-guard-structure` S2；跑 `--fix` 刷新）
