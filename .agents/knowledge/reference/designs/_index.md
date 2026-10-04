---
title: reference/designs/ 目录索引
description: reference/designs/ 的文件清单与过期判断；契约见 ./_directory.md
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 37
depends-on: []
related: [./_directory.md]
tags: [meta, index]
---

# reference/designs/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

**职责**：见 [`_directory.md`](./_directory.md)（此处不重复——S5 判据）

## 文件清单（10 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `framework-plus-plugin-model.md` | AdaiOS 的形态定义——一个框架 + 各种插件；框架装「你是谁」，插件装「你能做什么」；能力按用户叠加。五层… | active |
| `frontend-reference.md` | 前端统一参考——UI 术语对照 + 布局视觉（含 adai-web 桌面端章节） | active |
| `memory-os-design.md` | Memory OS 设计规约——职责边界、数据模型，以及与 Context Engine / Domain OS… | active |
| `product-architecture.md` | 五层产品架构详解（Layer 1-6）——Kernel / Domain OS / 插件的分层与职责 | active |
| `system-architecture.md` | 系统架构细节——Kernel / Domain 分层、Context Engine、数据流与关键机制 | active |
| `trading-case-library-design.md` | 规划 RFC 20260830 的可落地设计——案例数据模型、特征提取算法、归一化相似度引擎、API 契约草案、… | draft |
| `trading-data-adjustment.md` | 通达信 .day 不复权原始价在除权日跳空导致回撤/特征/相似度失真——用东财除权因子表 + 本地换算实现前复权… | draft |
| `trading-market-stage.md` | 把「活跃市值=一切的前提」的区间判定权还给用户——App/Web 手动切多头/空头，落 market-stage… | active |
| `trading-pending-decisions.md` | 三项交易需求的口径/参数待用户拍板——按批次止损编辑闭环（数据模型）、资金曲线图（口径/周期/端点）、买点三重校… | draft |
| `trading-plugin-architecture.md` | 交易插件第三阶段蓝图——通用能力层与个性化规则层分离，规则按用户隔离、可自定义/可导入/可导出，为多用户 + 插… | draft |

## 过期判断

- **清单须与实际一致**（`ai-guard-structure` S2；跑 `--fix` 刷新）
