---
title: records/ 目录索引
description: .agents/records/ 的文件清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 38
depends-on: []
related: [./_directory.md]
tags: [meta, index, records]
---

# records/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

**职责**：**④ 记录（账本）**——回答「**做到哪了 · 欠着什么 · 一路发生过什么**」。三份都是 AI **开工读 / 收尾写**的活账本。

## 文件清单（3 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `REVIEW.md` | 📌 **未修项滚动区**——战略 + P0–P2 未修复 + 最近审核摘要（开工自举读） | active |
| `change-log.md` | 📜 **批次变更日志**——每批一行（日期 · 批次 · 摘要 · 测试数变化），`/ship` 时顶部追加 | active |
| `task-log.md` | ✅ **待办迁移区**——从产品路线拆任务 + REVIEW 的 P3/观察项（开工自举读） | active |

> **三份都是 append-only 历史**：正文含"当时"的路径属正常，按文件豁免 `ai-guard-meta` 的 M4 正文路径检查。

## 过期判断

- **热文件，位置不再移动**——它们是全仓引用密度最高的一类（`REVIEW.md` 曾被 95 个文件引用）
- 由 `ai-guard-unfixed` / `ai-guard-sediment` / `ai-guard-context` / `ai-guard-roadmap` 直接读取
- 新增记录 → 补本索引 + frontmatter

## 来源

**2026-10-04 二批**：自 docs 区的 review/ 与 records/ 收归——判据＝「AI 上下文运行时是否需要」（开工必读 / 收尾必写）。
