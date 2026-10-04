---
title: lib/ 目录契约
description: lib/ 的职责边界 · 依赖关系 · 触发关系 · 约束 · 守卫 · 维护方式（机器可校验的目录级元数据）
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 45
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# lib/ 目录契约

**职责**：被复用的 shell 库——**不含业务判断**，只提供稳定能力

## 职责边界
- **放**：游标读写、日期算术、路径解析等**纯能力**函数
- **不放**：会被单独执行的脚本 → `../`；检查逻辑 → `../../guards/`

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 被依赖 | `../task-cadence.sh` · `../../guards/ai-guard-prod.sh` | 共用 `cadence-lib.sh` 的游标 |

## 触发关系

| 时机 | 谁触发 | 读 / 执行什么 |
|:--|:--|:--|
| 被 source 时 | 调用方脚本 | 对应函数 |

## 约束

- **只定义函数、不执行副作用**（source 即安全）
- 不引用外部状态变量（参数传入）

## 守卫（谁保证这里不腐烂）

- `shell-lint`

## 维护动作

1. 新增库 → 在调用方改 `source` 路径 → 补 `_index.md`
