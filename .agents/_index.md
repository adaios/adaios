---
title: .agents/ 目录索引
description: AI 上下文工程容器的顶层索引——12 个子目录 + 顶层文件的清单；根契约见 ./_directory.md
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 48
depends-on: []
related: [./_directory.md]
tags: [meta, index, ai]
---

# .agents/ 目录索引

**职责**：AdaiOS AI 上下文工程容器（工具中立）——**真相源进 git**，出口与本机状态不入库。

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

## 子目录（12）

| 目录 | 职责 | 索引 | 契约 |
|:--|:--|:--:|:--:|
| `assets/` | AI 工程静态知识——规范（怎么做）· 边界（不做什么）· ADR（为什么这么定）· 已知坑（别踩什么） | [→](./assets/_index.md) | [→](./assets/_directory.md) |
| `checklists/` | 逐条可执行的核对清单——**人与审查官共用**（8 客观官 + 1 对抗官 + 3 外部视角官 + 守护/成本） | [→](./checklists/_index.md) | [→](./checklists/_directory.md) |
| `guards/` | 守卫与自检脚本——**机制层**：把规范变成机器能拦的门 | [→](./guards/_index.md) | [→](./guards/_directory.md) |
| `lib/` | 被复用的 shell 库——**不含业务判断**，只提供稳定能力 | [→](./lib/_index.md) | [→](./lib/_directory.md) |
| `method/` | **元方法层**——怎么从零搭一套 AI 工程（给新项目用，不是日常流程） | [→](./method/_index.md) | [→](./method/_directory.md) |
| `process/` | 具体动作的流程定义——**天天用**：审查（audit/review）· 收尾（ship）· 节奏（cadence） | [→](./process/_index.md) | [→](./process/_directory.md) |
| `roles/` | 审查官定义——**subagent 的真相源**（8 客观官 + 1 对抗官 + 3 外部视角官） | [→](./roles/_index.md) | [→](./roles/_directory.md) |
| `scripts/` | 环境与注册脚本——**换机或新工作区**时要跑的那些（不是门禁） | [→](./scripts/_index.md) | [→](./scripts/_directory.md) |
| `skills/` | 建设与流程技能——**加载即执行**的工作流封装（工具的 skill 出口直连这里） | [→](./skills/_index.md) | [→](./skills/_directory.md) |
| `state/` | **本机状态**（不入 git）——协作节奏的游标与账本 | [→](./state/_index.md) | [→](./state/_directory.md) |
| `tests/` | **守卫反例回归区**——用坏样本证明守卫真的会抓（防「看起来在查、其实查不到」的假绿） | [→](./tests/_index.md) | [→](./tests/_directory.md) |
| `workflow/` | **单任务生命周期**——讨论 → 方案 → 开发 → 审核 → 验收 的六节点闭环 | [→](./workflow/_index.md) | [→](./workflow/_directory.md) |

## 顶层文件

| 文件 | 职责 |
|:--|:--|
| `README.md` | AdaiOS 的 AI 工程层入口——资产（规范/边界/ADR/坑）+ 工作流（讨论→方案→开发→审核→验收）+… |
| `frontmatter-spec.md` | AdaiOS 全项目文档 YAML frontmatter 契约——字段定义、维护职责、图谱与治理机制 |

## 过期判断

- `status != active` → 候选清理
- **清单须与实际一致**（`ai-guard-structure` S1–S3 校验）
- 新增子目录 → **必须同时有 `_index.md` 与 `_directory.md`**
