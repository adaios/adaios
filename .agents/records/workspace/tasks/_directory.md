---
title: workspace/tasks/ 目录契约
description: 任务账本（L1 任务层）的职责边界 · 依赖关系 · 触发关系 · 约束 · 守卫 · 维护方式——每条活跃分支一本本地账本，使分支产出不直接改全局账本
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 52
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# workspace/tasks/ 目录契约

**职责**：**L1 任务层**——每条活跃分支一本本地账本（本轮做什么 · 进度 · 风险 · 待归档），使分支产出**不直接改全局账本**，从而消除「两条线必冲同一账本」。

## 职责边界
- **放**：`<分支名>.md`（一条活跃分支一份）与 `_template.md`
- **不放**：全局账本（`.agents/records/change-log.md` · `.agents/records/REVIEW.md` · 各 `_index.md`）——那些是**合并时归档的目的地**

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 被依赖 | `../../../../AGENTS.md` | 规则 9（收工）与分支流程引用 |
| 归档去向 | `../../../records/change-log.md` · `../../../records/REVIEW.md` | 合并时把「待归档」搬进去 |
| 工具 | `../../../mechanism/scripts/ai-worktree-prep.sh` | 新 worktree 就位后创建账本 |

## 触发关系

| 时机 | 谁触发 | 读 / 执行什么 |
|:--|:--|:--|
| 开一条分支 | 人 / AI | `cp _template.md <分支名>.md` |
| 分支开发中 | AI（开工时读）| 对应的 `<分支名>.md` |
| 合并到 main | 人 / AI | 按「待归档」搬进全局账本 → **删除本文件** |

## 约束
- **一分支一文件**（文件名 = 分支名，kebab-case）——不同分支文件名不同 ⇒ **零冲突**
- **分支上只改自己这一份**，**禁止**直接改全局账本（那正是要避免的冲突源）
- **合并后必须归档并删除**：`main` 上残留任务账本 = 未收尾
- frontmatter **10 字段**（`ai-guard-meta` 查）

## 守卫（谁保证这里不腐烂）
- `ai-guard-structure`：两件套齐备 · 清单⇄实际 · 契约依赖 · 守卫引用 · 两件套不重复
- `ai-guard-meta`：frontmatter / lines / 断链
- 归档检查：`task-cadence.sh ship` 提示 `main` 上残留的账本（见 `.agents/rules/process/ship.md`）

## 维护动作
1. 开分支 → `cp _template.md <分支名>.md`（填「目标」）
2. 开发中 → 更新「进度」「风险」「待归档」
3. 合并 → 按「待归档」搬进 4 个去向 → `rm <分支名>.md` → 跑 `ai-guard-structure.sh --fix` 刷清单
