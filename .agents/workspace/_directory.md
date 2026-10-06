---
title: workspace/ 目录契约
description: .agents/workspace/ 的职责与规则——**按「分支任务」组织**：一个分支一个目录（需求 / 设计 / 审核 / 决策都挂在它名下，需要才有）；分支上只动 workspace、归档留给合并后；**在制品也要登记索引**
version: 2
created: 2026-10-04
updated: 2026-10-06
status: active
lines: 58
depends-on: []
related: [./_index.md]
tags: [meta, directory, workspace]
---

# workspace/ 目录契约

**职责**：**分支在制品容器**——每条活跃分支**一个目录**（`.agents/workspace/<分支名>/`），装这条任务的**全部产物**（需求 / 设计 / 审核 / 决策 / 账本）；**需要什么才有什么**。

## 职责边界

- **放**：`<分支名>/`（一条活跃分支一个目录）· `_templates/`（模板）· `_meta/`（**跨任务**的：流程问题 / 规范草案）
- **不放**：全局账本（`change-log` / `REVIEW` / `status`）· **已归档的定稿**（→ `.agents/direction/rfc/` 或 `knowledge/features/`）

## 依赖关系

- **上游**：`AGENTS.md`（协作规则）· `_templates/`（开分支时复制模板）；
- **下游**：`ai-guard-structure`（清单 ⇄ 实际）· `ai-guard-meta`（frontmatter / `lines` / 孤儿）；
- **对等**：各 `<分支名>/` **互不读写**；跨任务的共享放 `_meta/`。

## 目录命名

- **分支名去类型前缀**：分支 `feat/trading-plugin` → 目录 **`trading-plugin/`**（`/` 不能做目录名）；
- **一个目录 = 一个任务**；目录里**既有需求也有设计也有审核**（按需）——**轻活就一个 `LEDGER.md`**。

## 约束

- **分支上只动 `.agents/workspace/`**：外围（`.agents/rules` / `knowledge` / `records` / `docs`）**一律登记待交接**，留给合并后的主会话；
- **归档 ≠ 定稿**：**定稿 / 设计收敛在分支内**；**归档（搬进 `rfc/` / `knowledge/` / `records/`）在合并后**；
- **在制品也要登记**——`_index.md` 的文件清单用 rglob **逐个列**（原「**在制品本身不入清单**」的说法**已废弃**：它与 `ai-guard-structure` 的 S2 双向校验、`ai-guard-meta` 的 M3 孤儿判据**直接冲突**）；
- frontmatter **10 字段**（`ai-guard-meta` 查）。

## 触发关系

| 时机 | 做什么 |
|:--|:--|
| **开分支** | `cp _templates/ledger.md <分支名>/LEDGER.md`；要跑主链就再 `cp _templates/requirement.md <分支名>/requirement.md` |
| **开发中** | 只改**自己那一个目录** |
| **合并** | 按 `LEDGER.md` 的「待交接」搬完 → **删整目录** → 跑 `ai-guard-structure.sh --fix` 刷清单 |

## 守卫（谁保证这里不腐烂）

- `ai-guard-structure`：`workspace/` **根**的两件套 · 清单⇄实际 · 依赖引用有效
- `ai-guard-meta`：frontmatter / `lines` / 断链 / 孤儿

## 维护动作

1. 开分支 → 建 `<分支名>/` + 复制 `_templates/ledger.md` 为 `LEDGER.md`；
2. 开发中 → 只改本目录；每个产出物**落盘 + 登记 `_index.md` + 跑两道守卫**；
3. 合并 → 搬完「待交接」→ 删整目录 → `ai-guard-structure.sh --fix`。
