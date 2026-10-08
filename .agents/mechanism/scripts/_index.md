---
title: scripts/ 目录索引
description: scripts/ 的文件清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-03
updated: 2026-10-09
status: active
lines: 54
depends-on: []
related: [./_directory.md]
tags: [meta, index]
---

# scripts/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

## 文件清单（27 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `ai-domain-view.py` | 域视图 —— 一条命令答「某个域现在怎么样 · 还欠什么 · 往哪走」 | active |
| `ai-link-skills.sh` | 注册「用户直触发」技能到本机 AI 工具（换机器 clone 后执行一次） | active |
| `ai-lint-shell-vars.py` | 为什么需要它 | active |
| `ai-scan-secrets.py` | 为什么需要它 | active |
| `ai-setup-hooks.sh` | 启用 git hooks（core.hooksPath → .githooks/）+ 恢复仓库级 git 设置 | active |
| `ai-setup-launchd.sh` | 安装/自检 定时任务（LaunchAgent）— 2026-09-14 建 | active |
| `ai-sync-agents.sh` | 把「审查官」真相源生成为各工具的 subagent 定义（换机 / 改动后执行） | active |
| `ai-sync-all.sh` | AI 资产多工具出口：**一条命令**全量重建 + 自检 | active |
| `ai-worktree-prep.sh` | worktree 外挂补齐（AdaiOS 专属）——新建 worktree 后第一件事 | active |
| `audit-web.py` | — | active |
| `code-backup-prod.sh` | 生产数据定期备份脚本（服务器迁移/到期用，2026-08-14 创建，2026-08-20 更新， | active |
| `code-deploy-gate.sh` | 部署门禁 + 部署后验证（触发侧：部署前强制 review，部署后自动 smoke） | active |
| `data-migrate-user-layer.sh` | 数据迁移脚本：data/ → data/{userId}/（多用户架构，2026-08-02） | active |
| `data-prewarm-adj-factors.py` | -*- coding: utf-8 -*- | active |
| `data-sync-stock-names.py` | -*- coding: utf-8 -*- | active |
| `data-sync-tdx.sh` | ========================================================… | active |
| `data-verify-tdx.py` | -*- coding: utf-8 -*- | active |
| `lib/ai-export-targets.sh` | AI 资产「工具出口」清单 —— **唯一真相源**（2026-10-05 批） | active |
| `lib/cadence-lib.sh` | 游标库（task-cadence state）— 「上次到哪了」的唯一存储 | active |
| `lib/release-units.sh` | 发版单元映射（唯一真相源）：「哪些路径被改了」→「要发布哪几端」 | active |
| `serve_static.py` | — | active |
| `task-cadence.sh` | 协作默契执行器（task-cadence）— 2026-09-26 用户「我们需要某种默契」落地 | active |
| `task-check-deadlines.py` | -*- coding: utf-8 -*- | active |
| `task-noon.d/.gitkeep` | — | active |
| `task-noon.sh` | 午间谷时任务壳（工作日 12:01 触发）— 2026-09-16 建 | active |
| `task-weekly-audit.sh` | 定时审查（触发侧：每周自动跑，防审查休眠） | active |
| `trading-local-up.sh` | ========================================================… | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- **清单必须与实际文件一致**（`ai-guard-structure` S2 双向校验；新增文件后跑 `ai-guard-structure.sh --fix`）
