---
title: method/ 目录契约
description: method/ 的职责边界 · 依赖关系 · 触发关系 · 约束 · 守卫 · 维护方式（机器可校验的目录级元数据）
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 47
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# method/ 目录契约

**职责**：**元方法层**——怎么从零搭一套 AI 工程（给新项目用，不是日常流程）

## 职责边界
- **放**：切入点图谱 · 流水线设计 · 脚手架设计
- **不放**：单任务怎么做 → `workflow/`；具体动作 → `process/`

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 依赖 | `../assets/` | 方法论沉淀自 ADR 与坑 |
| 相关 | 仓库外 `ai-context-research/` | 方法论与研究区互为来处 |

## 触发关系

| 时机 | 谁触发 | 读 / 执行什么 |
|:--|:--|:--|
| 开新项目时 | 人 | `README.md` · `pipeline.md` · `scaffold.md` |
| 改流程机制时 | 人 + AI | `pipeline.md` 对照本期实践 |

## 约束

- **低频目录**：日常任务不需要读（引用度低不等于可删）
- 写「可复制到新项目」的通用形态，不写 adaios 专属细节

## 守卫（谁保证这里不腐烂）

- `guard-meta`

## 维护动作

1. 实践有新提炼 → 更新 `pipeline.md`；新增文件 → 补 `_index.md`
