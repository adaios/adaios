---
title: scripts/ 目录契约
description: scripts/ 的职责边界 · 依赖关系 · 触发关系 · 约束 · 守卫 · 维护方式（机器可校验的目录级元数据）
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 50
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# scripts/ 目录契约

**职责**：环境与注册脚本——**换机或新工作区**时要跑的那些（不是门禁）

## 职责边界
- **放**：hook 安装 · 技能与 subagent 注册 · worktree 外挂补齐 · 备份 · 数据同步
- **不放**：会被 pre-commit 调的检查 → `../guards/`；可复用函数 → `../lib/`

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 依赖 | `../lib/` | 共用库 |
| 被依赖 | `../../docs/guides/development.md` | 换机必跑清单 |
| 被依赖 | `setup-launchd.sh` → LaunchAgent | 定时任务指向这里 |

## 触发关系

| 时机 | 谁触发 | 读 / 执行什么 |
|:--|:--|:--|
| 换机 clone 后 | 人（见 development.md） | setup-hooks · link-skills · sync-agents · setup-launchd |
| 新建 worktree | 人 | `worktree-prep.sh` |
| 定时触发 | launchd | backup_prod.sh · noon-task.sh · weekly-audit.sh |

## 约束

- **幂等**：重复跑不产生副作用（换机流程会重复执行）
- **ROOT 推导用 `cd "$(dirname "$0")/../.."`**（迁到 `.agents/scripts/` 后层级变了，历史坑）
- 被 launchd 引用时**路径必须存在**（`setup-launchd.sh --check` 会验）

## 守卫（谁保证这里不腐烂）

- `shell-lint` · `ai-guard-tools`（T1/T2/T7）· `setup-launchd.sh --check`

## 维护动作

1. 新增脚本 → 补 `_index.md` → 若是换机必需，补 `docs/guides/development.md`
