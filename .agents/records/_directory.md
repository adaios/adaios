---
title: records/ 目录契约
description: .agents/records/ 的职责边界 · 依赖关系 · 触发关系 · 约束 · 守卫 · 维护方式（机器可校验的目录级元数据）
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 55
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# records/ 目录契约

**职责**：**④ 记录（账本）**——AI 工作流的**读写热点**：开工读它、收尾写它。

## 职责边界
- **放**：未修项（REVIEW）· 批次历史（change-log）· 待办（task-log）
- **不放**：一次性报告 / 走查存档 → `../../docs/records/` · 发布说明 → `../../docs/records/` · 现状数字 → `../knowledge/reference/status.md`（那是**事实**，不是记录）

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 依赖 | `../knowledge/reference/status.md` | 记录与数字互为指针 |
| 被依赖 | `../rules/process/ship.md` | 收尾时写 change-log / REVIEW |
| 被依赖 | `../mechanism/guards/ai-guard-unfixed.sh` / `ai-guard-sediment.sh` / `ai-guard-context.sh` / `ai-guard-roadmap.sh` | **以路径常量直接读取**——改名即坏守卫 |
| 被依赖 | `../knowledge/features/_index.md` | 「欠着」列的编号须在 REVIEW 中存在 |

## 触发关系

| 时机 | 谁触发 | 读 / 执行什么 |
|:--|:--|:--|
| **每次开工** | `ai-guard-context.sh` | `REVIEW.md`（C2 未修项）+ `task-log.md`（C6 待办） |
| 每次收工 | `/ship` | 追加 `change-log.md` + 更新 `REVIEW.md` |
| 用户说「待办」 | `task-cadence.sh todo` | 聚合本区两份 |
| 审查发现 P0/P1 | `/review` | 登记进 `REVIEW.md` |

## 约束
- **指针化，不复制**：同一事实只在一处详述（ADR-002）
- **热文件不动位置**：本区三份被多个守卫与脚本以**路径常量**引用——改名＝同时改 8+ 处
- **追加不删**：历史条目保留原样（含当时的路径）
- frontmatter **10 字段**（`ai-guard-meta` 查；但 M4 正文检查按文件豁免）

## 守卫（谁保证这里不腐烂）
- `ai-guard-meta`：frontmatter / lines（**M4 豁免三份账本**）
- `ai-guard-unfixed`：REVIEW ↔ task-log 交叉对账 + audits 归口
- `ai-guard-sediment`：检查收尾是否登记了 change-log / REVIEW
- `ai-guard-structure`：两件套齐备 · 清单⇄实际

## 维护动作
1. 新增记录类文档 → 补 `_index.md`
2. **改本区路径＝高危**：必须同步改 `../mechanism/guards/ai-guard-unfixed.sh` / `ai-guard-roadmap.sh` / `ai-guard-context.sh` / `ai-guard-sediment.sh` 与 `../mechanism/scripts/task-cadence.sh` 里的常量
3. 记录退役 → 移入 `../../docs/archive/`，不删除
