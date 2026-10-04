---
title: .agents/ 目录索引
description: AdaiOS 规则与机制容器的顶层索引——19 个子目录 + 顶层文件清单；判据＝「AI 上下文运行时是否需要」；根契约见 ./_directory.md
version: 2
created: 2026-10-03
updated: 2026-10-04
status: active
lines: 59
depends-on: []
related: [./_directory.md]
tags: [meta, index, ai]
---

# .agents/ 目录索引

**职责**：AdaiOS 的**规则与机制层**（工具中立）——回答「**怎么做 / 禁止什么 / 谁在自动保障**」。**真相源进 git**，出口与本机状态不入库。

> **2026-10-04 两批归纳**：
> **一批**——边界判据由「按读者」改为**「按性质」**（规则类归本容器）；
> **二批**——判据进一步收紧为「**AI 上下文运行时是否需要**」：**AI 运行需要的一切**（方向 / 事实 / 决策 / 活账本）全部收进本容器，`docs/` 自此为**档案馆**（史 / 存档 / 对外 / 未定型）。
> 详见 [`../docs/README.md`](../docs/README.md) 的归属判定表。
> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

## 子目录（19）

| 目录 | 职责 | 索引 | 契约 |
|:--|:--|:--:|:--:|
| `assets/` | 规则静态知识——规范（怎么做）· 边界（不做什么）· ADR（为什么这么定）· 已知坑（别踩什么）· **AI 上下文体系总览** | [→](./assets/_index.md) | [→](./assets/_directory.md) |
| `checklists/` | 逐条可执行的核对清单——**人与审查官共用** | [→](./checklists/_index.md) | [→](./checklists/_directory.md) |
| `deployment/` | **运维与发布规则**——怎么部署 / 怎么发布 / 怎么合规 | [→](./deployment/_index.md) | [→](./deployment/_directory.md) |
| `direction/` | **① 方向**——理念（VISION）与路线蓝图（roadmap），**每次会话首读** | [→](./direction/_index.md) | [→](./direction/_directory.md) |
| `features/` | **② 事实·功能主轴**——一功能一行（ID/插件/状态/需求出处/实现出处/欠着） | [→](./features/_index.md) | [→](./features/_directory.md) |
| `guards/` | 守卫与自检脚本——**机制层**：把规范变成机器能拦的门 | [→](./guards/_index.md) | [→](./guards/_directory.md) |
| `guides/` | **工程与协作规则**——怎么构建 / 分支 / 并行 / 固定动作 | [→](./guides/_index.md) | [→](./guides/_directory.md) |
| `lib/` | 被复用的 shell 库——**不含业务判断**，只提供稳定能力 | [→](./lib/_index.md) | [→](./lib/_directory.md) |
| `method/` | **元方法层**——怎么从零搭一套 AI 工程（给新项目用） | [→](./method/_index.md) | [→](./method/_directory.md) |
| `process/` | 具体动作的流程定义——审查 · 收尾 · 节奏 · 审核驱动主链 | [→](./process/_index.md) | [→](./process/_directory.md) |
| `records/` | **④ 记录（活账本）**——未修项 / 批次历史 / 待办（**开工读、收尾写**） | [→](./records/_index.md) | [→](./records/_directory.md) |
| `reference/` | **② 事实（按需查）**——契约（api-spec / data-format-freeze）· 状态 · 手册 · 领域设计 | [→](./reference/_index.md) | [→](./reference/_directory.md) |
| `rfc/` | **① 决策记录**——方案决策（要不要做 / 怎么做，含备选与理由） | [→](./rfc/_index.md) | [→](./rfc/_directory.md) |
| `roles/` | 审查官定义——**subagent 的真相源** | [→](./roles/_index.md) | [→](./roles/_directory.md) |
| `scripts/` | 环境与注册脚本——**换机或新工作区**时要跑的那些 | [→](./scripts/_index.md) | [→](./scripts/_directory.md) |
| `skills/` | 建设与流程技能——**加载即执行**的工作流封装 | [→](./skills/_index.md) | [→](./skills/_directory.md) |
| `state/` | **本机状态**（不入 git）——协作节奏的游标与账本 | [→](./state/_index.md) | [→](./state/_directory.md) |
| `tests/` | **守卫反例回归区**——用坏样本证明守卫真的会抓 | [→](./tests/_index.md) | [→](./tests/_directory.md) |
| `workflow/` | **单任务生命周期**——讨论 → 方案 → 开发 → 审核 → 验收 | [→](./workflow/_index.md) | [→](./workflow/_directory.md) |

## 顶层文件

| 文件 | 职责 |
|:--|:--|
| `README.md` | 本容器的入口——定位（规则与机制层）+ 三层结构 + 任何 AI 工具如何接入 |
| `frontmatter-spec.md` | AdaiOS 全项目文档 YAML frontmatter 契约——字段定义、维护职责、图谱与治理机制 |

## 过期判断

- `status != active` → 候选清理
- **清单须与实际一致**（`ai-guard-structure` S1–S3 校验）
- 新增子目录 → **必须同时有 `_index.md` 与 `_directory.md`**
