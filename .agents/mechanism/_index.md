---
title: mechanism/ 目录索引
description: .agents/mechanism/ 的文件清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 1
depends-on: []
related: [./_directory.md]
tags: [meta, index]
---

# mechanism/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

**职责**：见 [`_directory.md`](./_directory.md)（此处不重复——S5 判据：同一知识只在一处详述）

## 文件清单（39 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `guards/ai-guard-align.sh` | 文档自动对齐守护 — 代码 ↔ 文档内容一致性检查 | active |
| `guards/ai-guard-context.sh` | 任务前上下文注入（进攻侧核心）— 生成"开工前必读清单" | active |
| `guards/ai-guard-cost.sh` | 成本监控（防守侧）— 读 DSH 会话日志，按天/月算 DeepSeek 实际消费 | active |
| `guards/ai-guard-feature.sh` | 功能索引守护检查（ai-guard-feature）—— .agents/knowledge/features/… | active |
| `guards/ai-guard-health.sh` | AI 上下文工程 · 健康总检（ai-guard-health）—— 一条命令回答「整个 .agents/ 健不… | active |
| `guards/ai-guard-meta.sh` | 元治理守护检查 — ai-engineering/ + AGENTS.md 的 frontmatter 契约自检 | active |
| `guards/ai-guard-prod.sh` | 生产日报（每日流程）— 生产日志 + 真实对话卡片，一条命令看全 | active |
| `guards/ai-guard-release.sh` | 发版体检（发布前随时问一句：现在欠着什么没发） | active |
| `guards/ai-guard-roadmap.sh` | 规划状态对拍（roadmap 体检）— 回答「未来规划如何 / 规划是否可信」 | active |
| `guards/ai-guard-sediment.sh` | 沉淀检查器（进攻侧 ②③）— 提交前检查"该沉淀的有没有沉淀" | active |
| `guards/ai-guard-skills.sh` | 技能包质量校验（S3 / S4 / S5 / S7）—— 补 ai-guard-tools T3 之外的部分 | active |
| `guards/ai-guard-structure.sh` | 目录级自洽守卫（ai-guard-structure）—— 补 ai-guard-meta（文件级）之外的那一半 | active |
| `guards/ai-guard-tools.sh` | 工具接入自检（防守侧）— 检测「AI 上下文工程体系」在各工具侧是否真的被加载 | active |
| `guards/ai-guard-unfixed.sh` | 未修复问题总清单（聚合 4 个维护点）— 用户问「还有哪些未修」一条命令拿全 | active |
| `guards/guard.sh` | 守护检查执行器 — /review 每次必跑，防 P0 复发（数据丢失/契约破坏） | active |
| `guards/tests/guard-feature-fixture.py` | — | active |
| `scripts/ai-domain-view.py` | 域视图 —— 一条命令答「某个域现在怎么样 · 还欠什么 · 往哪走」 | active |
| `scripts/ai-link-skills.sh` | 注册「用户直触发」技能到本机 AI 工具（换机器 clone 后执行一次） | active |
| `scripts/ai-lint-shell-vars.py` | 为什么需要它 | active |
| `scripts/ai-scan-secrets.py` | 为什么需要它 | active |
| `scripts/ai-setup-hooks.sh` | 启用 git hooks（core.hooksPath → .githooks/）+ 恢复仓库级 git 设置 | active |
| `scripts/ai-setup-launchd.sh` | 安装/自检 定时任务（LaunchAgent）— 2026-09-14 建 | active |
| `scripts/ai-sync-agents.sh` | 把「审查官」真相源生成为各工具的 subagent 定义（换机 / 改动后执行） | active |
| `scripts/ai-worktree-prep.sh` | worktree 外挂补齐（AdaiOS 专属）——新建 worktree 后第一件事 | active |
| `scripts/code-backup-prod.sh` | 生产数据定期备份脚本（服务器迁移/到期用，2026-08-14 创建，2026-08-20 更新， | active |
| `scripts/code-deploy-gate.sh` | 部署门禁 + 部署后验证（触发侧：部署前强制 review，部署后自动 smoke） | active |
| `scripts/data-migrate-user-layer.sh` | 数据迁移脚本：data/ → data/{userId}/（多用户架构，2026-08-02） | active |
| `scripts/data-prewarm-adj-factors.py` | -*- coding: utf-8 -*- | active |
| `scripts/data-sync-stock-names.py` | -*- coding: utf-8 -*- | active |
| `scripts/data-sync-tdx.sh` | ========================================================… | active |
| `scripts/data-verify-tdx.py` | -*- coding: utf-8 -*- | active |
| `scripts/lib/cadence-lib.sh` | 游标库（task-cadence state）— 「上次到哪了」的唯一存储 | active |
| `scripts/lib/release-units.sh` | 发版单元映射（唯一真相源）：「哪些路径被改了」→「要发布哪几端」 | active |
| `scripts/serve_static.py` | — | active |
| `scripts/task-cadence.sh` | 协作默契执行器（task-cadence）— 2026-09-26 用户「我们需要某种默契」落地 | active |
| `scripts/task-check-deadlines.py` | -*- coding: utf-8 -*- | active |
| `scripts/task-noon.d/.gitkeep` | — | active |
| `scripts/task-noon.sh` | 午间谷时任务壳（工作日 12:01 触发）— 2026-09-16 建 | active |
| `scripts/task-weekly-audit.sh` | 定时审查（触发侧：每周自动跑，防审查休眠） | active |

## 子目录

| 目录 | 职责 | 索引 | 契约 |
|:--|:--|:--:|:--:|
| `guards/` | — | [→](./guards/_index.md) | [→](./guards/_directory.md) |
| `scripts/` | — | [→](./scripts/_index.md) | [→](./scripts/_directory.md) |

## 过期判断

- 新增子目录 → 必须同时有 `_index.md` 与 `_directory.md`
- **清单须与实际一致**（`ai-guard-structure` S2；跑 `--fix` 刷新）
