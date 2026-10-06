---
title: roles/ 目录契约
description: roles/ 的职责边界 · 依赖关系 · 触发关系 · 约束 · 守卫 · 维护方式（机器可校验的目录级元数据）
version: 1
created: 2026-10-03
updated: 2026-10-06
status: active
lines: 49
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# roles/ 目录契约

**职责**：审查官定义——**subagent 的真相源**（**16 个**：产作者 1 · 审核者 14 · 流程官 1——全景见 `../../rules/process/review-driven.md` **§0**）

## 职责边界
- **放**：每个官的立场、执行步骤、约束、输出要求、参考资料（五段）
- **不放**：勾选清单 → `checklists/`；派官规则 → `process/review.md`

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 依赖 | `../../rules/assets/` · `../checklists/` | 判据与勾选项 |
| 被依赖 | `../../rules/process/review.md` | 按 diff 派官 |
| 工具 | `../../mechanism/scripts/ai-sync-agents.sh` | 生成 `.qoder/agents/` 与 `.codex/agents/` |

## 触发关系

| 时机 | 谁触发 | 读 / 执行什么 |
|:--|:--|:--|
| deep 审查派官时 | `../../rules/process/review.md` 派官表 | 对应 `<name>.md` |
| 生成 subagent 时 | `ai-sync-agents.sh` | 全部 `roles/*.md` |

## 约束

- **保持扁平 `<name>.md`，刻意不目录化**（不进技能出口；目录化要付 44 处引用代价而零收益，见 `../../rules/assets/ai-context-layer-spec.md` §三）
- **五段结构**：触发条件 / 执行步骤 / 约束与规则 / 输出要求 / 参考资料
- 命名**多数**以 `-reviewer` 结尾；例外：`docs-design-writer`（产作者）· `process-reviewer`（流程官）· `docs-*` 系列为历史命名

## 守卫（谁保证这里不腐烂）

- `ai-guard-skills`（S3 name / S4 description / S5 五段 / S7 偏离）· `ai-guard-meta`

## 维护动作

1. 新增官 → 写 `roles/<name>.md` → 建 `checklists/review-<name>.md` → 加进 `scripts/ai-sync-agents.sh` 的 `REGISTER` → 补 `_index.md`
