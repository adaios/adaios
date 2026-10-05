---
title: 交易线首批任务卡（并行开发纪律）
description: ② 交易线（feat/trading-plugin worktree）本批目标 + 并行作业纪律——给 IDEA / ChatGPT / Codex 等工具读；完成后删除（在制品不入清单）
version: 1
created: 2026-10-05
updated: 2026-10-05
status: active
lines: 52
depends-on: []
related: [../../records/REVIEW.md, ../../rules/guides/git-workflow.md, ../../rules/guides/worktree-workflow.md]
tags: [workspace, task, trading]
---

# 交易线首批任务卡

> **在制品**——完成后删除；定稿的结论入 `../../records/`（收工由主仓库会话办理）。

## 本批目标

从 `.agents/records/REVIEW.md` 取两条**口径已明确、只欠动手**的：

| 编号 | 一句话 |
|:--|:--|
| **P2-交易87** | 「今天没动」已能回填进计划记录，但**收盘复盘与 20:30 提醒还不感知它**——用户填了，当晚的复盘读起来仍像「这天什么都没有」 |
| **P2-交易81** | 新用户「初始化顺序」未产品化：正确顺序（持仓股 → 资金股份 → 历史成交）只写在 owner 私有运维手册，产品内零引导，第一次用处处撞墙 |

## 三条纪律（违反会造成账本冲突或静默污染）

1. **不写三本账**——`change-log.md` / `REVIEW.md` / `status.md` 由**主仓库统一誊写**。
   本批的「摘要 + 出表项 + 测试数变化」写在本目录（如 `trading-batch1-notes.md`）。
2. **不碰 `.agents/` 机制**——脚本、守卫、规范由 ① 线（AI 上下文工程线）统一改。
   发现需要改就登记下来交给主仓库会话；两条线同时改同一批脚本必然冲突。
3. **不跑全局状态命令**——`task-cadence.sh ship` / `mark` 在 worktree 里会被守卫**直接拒绝**（rc=2）；
   `ai-guard-context.sh --write-local` 会**覆盖主仓库的开工快照**。两者都不要跑。

## 提交

- **显式路径** + `ADAI_BATCH_PATHS="<前缀1>,<前缀2>"`，**禁 `git add -A`**（pre-commit 第 0 层范围守卫）。
- 信息格式 `type(scope): 摘要`，正文写**为什么**（不是「改了什么」——diff 已说明）。
- **分支上只提交**；收工 / 合并 / 发布在主仓库办理（说一声「合」即由主会话执行）。

## 运行环境（本 worktree 特有）

- **`data/` 是 copy 快照**（APFS 写时复制，与主仓库**双向隔离**）：这里的写入**不会回流**主仓库，
  主仓库后来新增的数据这里也看不到。需要同步真实数据时找主会话。
- **起后端换端口**：`--args='--server.port=8180'`；前端指向它：`--dart-define=API_BASE_URL=http://127.0.0.1:8180`。
- **构建错峰**：`~/.gradle` 与 pub 缓存与本仓库其余工作副本**共享**，别与主会话同时构建。

## 开工自举（按 AGENTS.md 规则 0）

读 `AGENTS.md`（项目入口）+ `AGENTS.local.md`（开工快照）即可；
**不要**在会话开头跑全量 `ai-guard-context.sh`——它的输出极大（会吃掉大量上下文），效果与读快照相同。
