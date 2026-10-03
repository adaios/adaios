---
title: workflow/ 目录契约
description: workflow/ 的职责边界 · 依赖关系 · 触发关系 · 约束 · 守卫 · 维护方式（机器可校验的目录级元数据）
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 50
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# workflow/ 目录契约

**职责**：**单任务生命周期**——讨论 → 方案 → 开发 → 审核 → 验收 的六节点闭环

## 职责边界
- **放**：每个节点的入口与出口、沉淀触发；RFC 骨架；工作流总图
- **不放**：元方法（搭体系）→ `method/`；具体动作 → `process/`

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 依赖 | `../process/` | 节点对应的具体流程 |
| 依赖 | `../assets/` | 方案受边界约束 |
| 被依赖 | `../../AGENTS.md` | 规则 7「讨论与实施分离」由此定义 |

## 触发关系

| 时机 | 谁触发 | 读 / 执行什么 |
|:--|:--|:--|
| 有新想法 | 人 | `discuss.md`（值不值得沉淀） |
| 要写方案 | 人 / AI | `design.md`（RFC 骨架） |
| 开工写代码 | 人明说「做」 | `develop.md` |
| 看全貌 | 人 / AI | `overview.md` |

## 约束

- **低频目录**：做具体任务时才按需读其中一篇
- **「讨论与实施分离」的落点**：说「聊聊/看看」只聊，说了「做/改」才动手（AGENTS.md 规则 7）

## 守卫（谁保证这里不腐烂）

- `guard-meta`

## 维护动作

1. 流程变化 → 更新对应节点文件 + `overview.md` 的图 → 补 `_index.md`
