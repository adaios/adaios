---
title: guides/ 目录契约
description: .agents/rules/guides/ 的职责边界 · 依赖关系 · 触发关系 · 约束 · 守卫 · 维护方式（机器可校验的目录级元数据）
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 54
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# guides/ 目录契约

**职责**：**工程与协作规则**——回答「怎么做」。人读与 AI 读**共用同一份**（2026-10-04 起判据由「按读者」改为「按性质」）。

## 职责边界
- **放**：怎么构建/测试/运行 · 怎么开分支/合并/推送/发布 · worktree 并行 · 固定动作清单 · 技能使用方式
- **不放**：流程定义 → `../process/` · 单任务生命周期 → `../workflow/` · 运维规则 → `../deployment/` · 产品使用说明 → `../../../docs/`

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 依赖 | `../assets/` | 规则受边界（B1–B9）与已知坑（pitfalls）约束 |
| 被依赖 | `../process/ship.md` · `../process/cadence.md` | 收尾与节奏流程引用本区命令清单 |
| 被依赖 | `../../mechanism/scripts/` | 换机清单 · worktree 准备脚本 |
| 引用 | `../../records/change-log.md` | 批次历史 |

## 触发关系

| 时机 | 谁触发 | 读 / 执行什么 |
|:--|:--|:--|
| 换机 / 新 clone | 人 | `development.md`（三件必跑） |
| 开工（分支 / worktree） | 人 + AI | `git-workflow.md` · `worktree-workflow.md` · `branch-development.md` |
| 每次提交 / 合并 / 发布 | 人 + AI | `git-workflow.md` |
| 每日 / 每周固定动作 | 人 | `routine.md` |
| 用技能时 | 人 + AI | `skills-usage.md` |

## 约束
- **规则不重复**：同一规则只在本区详述一处，别处**引用**（单一权威来源，靠 `ai-guard-meta` 维持）
- **与 `docs/` 的分工**：本区是**执行规则**；`docs/` 放**方向 / 事实 / 记录**
- frontmatter **10 字段**（`ai-guard-meta` 查）

## 守卫（谁保证这里不腐烂）
- `ai-guard-meta`：frontmatter / lines / 断链 / 孤儿
- `ai-guard-structure`：两件套齐备 · 清单⇄实际 · 契约依赖
- 引用本区路径的机制脚本：`ai-guard-tools` · `ai-guard-release` · `ai-guard-prod` · `task-cadence`

## 维护动作
1. 新增规则文档 → 补 `_index.md` → 跑 `bash .agents/mechanism/guards/ai-guard-structure.sh --fix`
2. **改路径必须全仓修引用**（`git grep -l` 逐个替换；勿留幽灵引用——本项目已有先例）
3. 规则失效 → `status: superseded`，或转入 `../../../docs/archive/`
