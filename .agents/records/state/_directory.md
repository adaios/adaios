---
title: state/ 目录契约
description: state/ 的职责边界 · 依赖关系 · 触发关系 · 约束 · 守卫 · 维护方式（机器可校验的目录级元数据）
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 47
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# state/ 目录契约

**职责**：**本机状态**（不入 git）——协作节奏的游标与账本

## 职责边界
- **放**：`cadence.json`（上次巡检/收工/周审/发版到哪）· `cost-log.jsonl` 成本账 · 各定时任务日志
- **不放**：任何需要跨机共享的东西（本目录**每台机器一份**）

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 被依赖 | `../../mechanism/scripts/task-cadence.sh` · `../../mechanism/scripts/lib/cadence-lib.sh` | 读写游标 |
| 被依赖 | `../../mechanism/guards/ai-guard-cost.sh` | 成本账 |

## 触发关系

| 时机 | 谁触发 | 读 / 执行什么 |
|:--|:--|:--|
| 每次收工或巡检或周审 | task-cadence.sh | 读上次位置、写本次位置 |
| 开工时 | `ai-guard-context.sh` | 读成本缓存（当日成本现跑） |

## 约束

- **整个目录被 `.gitignore` 忽略**（`_index.md` 除外）
- **唯一一本账**：worktree 里它 **link 主仓库**（复制一份等于劈成两半，见 `.agents/guides/worktree-workflow.md`）

## 守卫（谁保证这里不腐烂）

- `ai-guard-tools` T2（快照）· `task-cadence.sh` 自检

## 维护动作

1. **不手工改**（由 task-cadence 与 lib 维护）；若需重来，删对应 json 后重跑
