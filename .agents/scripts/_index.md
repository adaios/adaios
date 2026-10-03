---
title: scripts/ 目录索引
description: scripts/ 的文件清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 46
depends-on: []
related: [./_directory.md]
tags: [meta, index]
---

# scripts/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

## 文件清单（19 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `backup_prod.sh` | 生产数据定期备份脚本（服务器迁移/到期用，2026-08-14 创建，2026-08-20 更新， | active |
| `cadence.sh` | 协作默契执行器（cadence）— 2026-09-26 用户「我们需要某种默契」落地 | active |
| `check_deadlines.py` | -*- coding: utf-8 -*- | active |
| `deploy-gate.sh` | 部署门禁 + 部署后验证（触发侧：部署前强制 review，部署后自动 smoke） | active |
| `link-skills.sh` | 注册「用户直触发」技能到本机 AI 工具（换机器 clone 后执行一次） | active |
| `lint-shell-vars.py` | 为什么需要它 | active |
| `migrate-data-to-user-layer.sh` | 数据迁移脚本：data/ → data/{userId}/（多用户架构，2026-08-02） | active |
| `noon-task.d/.gitkeep` | — | active |
| `noon-task.sh` | 午间谷时任务壳（工作日 12:01 触发）— 2026-09-16 建 | active |
| `prewarm-adj-factors.py` | -*- coding: utf-8 -*- | active |
| `scan-secrets.py` | 为什么需要它 | active |
| `setup-hooks.sh` | 启用 git hooks（core.hooksPath → .githooks/）+ 恢复仓库级 git 设置 | active |
| `setup-launchd.sh` | 安装/自检 定时任务（LaunchAgent）— 2026-09-14 建 | active |
| `sync-agents.sh` | 把「审查官」真相源生成为各工具的 subagent 定义（换机 / 改动后执行） | active |
| `sync-stock-names.py` | -*- coding: utf-8 -*- | active |
| `sync_tdx_data.sh` | ========================================================… | active |
| `verify-tdx-data.py` | -*- coding: utf-8 -*- | active |
| `weekly-audit.sh` | 定时审查（触发侧：每周自动跑，防审查休眠） | active |
| `worktree-prep.sh` | worktree 外挂补齐（AdaiOS 专属）——新建 worktree 后第一件事 | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- **清单必须与实际文件一致**（`guard-structure` S2 双向校验；新增文件后跑 `guard-structure.sh --fix`）
