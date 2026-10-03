---
title: skills/ 目录契约
description: skills/ 的职责边界 · 依赖关系 · 触发关系 · 约束 · 守卫 · 维护方式（机器可校验的目录级元数据）
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 48
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# skills/ 目录契约

**职责**：建设与流程技能——**加载即执行**的工作流封装（工具的 skill 出口直连这里）

## 职责边界
- **放**：把一类重复任务固化成技能：`<name>/SKILL.md`（目录布局）或 `<name>.md`（过渡）
- **不放**：审查官 → `../roles/`；流程定义 → `../process/`

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 被依赖 | 工具出口 | `.dsh/skills` · `.claude/skills` · `.qoder/skills`（软链）+ `.agents/skills`（**本目录本身**） |
| 工具 | `../scripts/ai-link-skills.sh` | 注册到各工具出口 |

## 触发关系

| 时机 | 谁触发 | 读 / 执行什么 |
|:--|:--|:--|
| 用户直触发 | 用户在工具里调用技能名 | 对应 SKILL.md |
| 注册时 | `ai-link-skills.sh` | `REGISTER` 清单内的技能 |

## 约束

- **必须目录布局 `<name>/SKILL.md`**（官方 Agent Skills 规范；S3 查 name 等于父目录名）
- **五段结构** + `description` 1–1024
- **只注册用户直触发的技能**——其余常驻 catalog 要花钱且可能误触发（listing 预算 ≈ context window 的 1%）

## 守卫（谁保证这里不腐烂）

- `ai-guard-skills`（S3/S4/S5/S7）· `ai-link-skills.sh --check`

## 维护动作

1. 新增技能 → 建 `<name>/SKILL.md` → 若是直触发，加进 `scripts/ai-link-skills.sh` 的 `REGISTER` → 补 `_index.md`
