---
title: process/ 目录契约
description: process/ 的职责边界 · 依赖关系 · 触发关系 · 约束 · 守卫 · 维护方式（机器可校验的目录级元数据）
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 50
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# process/ 目录契约

**职责**：具体动作的流程定义——审查（audit/review）· **审核驱动主链（review-driven：需求→设计→编码）** · 收尾（ship）· 节奏（cadence）

## 职责边界
- **放**：可执行的流程：触发条件 → 步骤 → 门禁节点 → 产出
- **不放**：角色 prompt → `roles/`；勾选项 → `checklists/`

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 依赖 | `../guards/` | 流程里的门禁节点由守卫实现 |
| 依赖 | `../roles/` · `../checklists/` | 派官与清单 |
| 被依赖 | `../../AGENTS.md` | 审查体系导航指向这里 |

## 触发关系

| 时机 | 谁触发 | 读 / 执行什么 |
|:--|:--|:--|
| 用户说「收工」 | `cadence.sh ship` | `ship.md` |
| 要全量走查 | 人 | `audit.md` |
| 按 diff 深审 | 人 / AI | `review.md` |
| 固定节奏 | 用户触发词 | `cadence.md`（总表） |

## 约束

- 每个流程必写**触发条件 / 门禁 / 产出**三要素
- **审查只报告不直接修**（B7）——流程不得含自动修复步骤（`--fix` 类除外）

## 守卫（谁保证这里不腐烂）

- `ai-guard-meta` · 流程引用的守卫须存在

## 维护动作

1. 新增流程 → 写文件 → 补 `_index.md` → 在 `AGENTS.md` 审查体系表登记
