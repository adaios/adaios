---
title: checklists/ 目录契约
description: checklists/ 的职责边界 · 依赖关系 · 触发关系 · 约束 · 守卫 · 维护方式（机器可校验的目录级元数据）
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 50
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# checklists/ 目录契约

**职责**：逐条可执行的核对清单——**人与审查官共用**（8 客观官 + 1 对抗官 + 3 外部视角官 + 守护/成本）

## 职责边界
- **放**：按角色或主题的勾选清单，每条可执行、可判定
- **不放**：审查官的 prompt → `roles/`；流程步骤 → `process/`

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 依赖 | `../assets/` | 清单条目引用规范与边界作判据 |
| 被依赖 | `../roles/` | 审查官执行时逐步对照清单 |
| 被依赖 | `../process/review.md` | 派官时指定该官读哪份清单 |

## 触发关系

| 时机 | 谁触发 | 读 / 执行什么 |
|:--|:--|:--|
| 派官审查时 | `../process/review.md` 派官表 | 对应 `review-<官>.md` |
| 收尾自检 | `../process/ship.md` | `guard.md`（G1–G7） |
| 成本判断 | `ai-guard-cost.sh` 提示 | `cost.md` |

## 约束

- **一名官一份清单**，命名 `review-<角色>.md`
- 条目必须**可判定**（能回答是/否），禁写原则口号
- 清单**不重复** `roles/` 里的步骤描述——只列检查项

## 守卫（谁保证这里不腐烂）

- `ai-guard-meta`：frontmatter / lines / 引用

## 维护动作

1. 新增审查官 → 同时新增 `review-<官>.md` + 在 `../roles/` 建对应官 + 补本目录 `_index.md`
