---
title: records/ 目录索引
description: .agents/records/ 的文件清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 49
depends-on: []
related: [./_directory.md]
tags: [meta, index, records]
---

# records/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

**职责**：**④ 记录（账本）**——回答「**做到哪了 · 欠着什么 · 一路发生过什么**」。三份都是 AI **开工读 / 收尾写**的活账本。

## 文件清单（14 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `REVIEW.md` | — | active |
| `change-log.md` | 每个开发批次一行（日期 · 批次 · 摘要 · 测试数变化）——收工 /ship 时在顶部追加；详情以 git … | active |
| `state/cadence.json` | — | active |
| `state/code-backup-prod.log` | — | active |
| `state/cost-cache.json` | — | active |
| `state/cost-log.jsonl` | — | active |
| `state/task-noon.log` | — | active |
| `state/task-weekly-audit.log` | — | active |
| `state/usage-cache.json` | — | active |
| `task-log.md` | 待办迁移区——从产品路线拆任务 + REVIEW 的 P3/观察项；ai-guard-unfixed / ai-… | active |
| `workspace/designs/_template-design.md` | 模板 A——设计文档（设计文档编写者产出）：本轮回应 · 设计 · 取舍 · 未决 · 自评风险；每轮一份，不覆… | active |
| `workspace/designs/_template-review.md` | 模板 B——设计审核文件（设计文档审核者产出）：审核对象 · 结论 · 问题清单（P0–P3）· 收敛判定；每轮… | active |
| `workspace/requirements/_template.md` | 需求文稿模板——问题动机 · 范围 · 验收标准 · 未决问题 · 定稿记录；定稿由人拍板，随后归档 docs/ | active |
| `workspace/tasks/_template.md` | 分支任务账本模板——本分支的目标 · 进度 · 风险 · 待归档条目；合并时按「待归档」搬进全局账本后删除本文件 | active |

> **三份都是 append-only 历史**：正文含"当时"的路径属正常，按文件豁免 `ai-guard-meta` 的 M4 正文路径检查。

## 过期判断

- **热文件，位置不再移动**——它们是全仓引用密度最高的一类（`REVIEW.md` 曾被 95 个文件引用）
- 由 `ai-guard-unfixed` / `ai-guard-sediment` / `ai-guard-context` / `ai-guard-roadmap` 直接读取
- 新增记录 → 补本索引 + frontmatter

## 来源

**2026-10-04 二批**：自 docs 区的 review/ 与 records/ 收归——判据＝「AI 上下文运行时是否需要」（开工必读 / 收尾必写）。
