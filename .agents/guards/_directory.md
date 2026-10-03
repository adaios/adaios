---
title: guards/ 目录契约
description: guards/ 的职责边界 · 依赖关系 · 触发关系 · 约束 · 守卫 · 维护方式（机器可校验的目录级元数据）
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 50
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# guards/ 目录契约

**职责**：守卫与自检脚本——**机制层**：把规范变成机器能拦的门

## 职责边界
- **放**：`guard-*.sh`：任何能在提交或收工时自动发现问题的检查脚本
- **不放**：一次性脚本 → `../scripts/`；库函数 → `../lib/`

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 依赖 | `../lib/` | 共用库（游标等） |
| 被依赖 | `../../.githooks/pre-commit` | 提交门禁依次调用 |
| 被依赖 | `../process/` | 流程文档引用守卫作为门禁节点 |

## 触发关系

| 时机 | 谁触发 | 读 / 执行什么 |
|:--|:--|:--|
| 提交时 | `.githooks/pre-commit` | align / meta / feature / skills / tools / sediment |
| 收工时 | `cadence.sh ship` | meta（--fix 回写） |
| 开工时 | AGENTS.md 规则 0 | `ai-guard-context.sh` |

## 约束

- 命名 `guard-<域>.sh`；**必须可被反例触发**（见 `../tests/`）
- **ROOT 推导用 `cd "$(dirname "$0")/../.."` 或 `git rev-parse --show-toplevel`**（层级变动是历史坑）
- 报错要**可操作**（指出修法与依据）

## 守卫（谁保证这里不腐烂）

- 自身：`shell-lint`（$VAR 花括号）· `ai-guard-tools` T1–T7

## 维护动作

1. 新增守卫 → 写脚本 → 加进 `.githooks/pre-commit`（按触发条件）→ 在 `../tests/` 造反例 → 补 `_index.md`
