---
title: mechanism/ 目录契约
description: .agents/mechanism/ 的职责边界 · 依赖 · 约束 · 守卫 · 维护（2026-10-04 六顶层重构）
version: 1
created: 2026-10-04
updated: 2026-10-06
status: active
lines: 38
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# mechanism/ 目录契约

**职责**：**⑤ 执行机制**——**能跑的东西**——把规范变成机器能拦的门（guards）与做事的手（scripts）。

## 职责边界
- **放**：guards/（守卫・含 tests/）· scripts/（执行器・含 lib/）
- **不放**：其他五类（`../` 下的兄弟顶层）

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 入口 | `../../AGENTS.md` | 任何工具的第一入口 |
| 守卫 | `../mechanism/guards/` | 本目录由 `ai-guard-meta`（文件级）+ `ai-guard-structure`（目录级）保障 |
| 健康 | `../mechanism/guards/ai-guard-health.sh` | 六维总检 |

## 约束
- **单一权威来源**：同一知识只在一处详述，别处引用
- 每个子目录必须有 `_index.md` + `_directory.md`
- frontmatter **10 字段**（`ai-guard-meta` 查）

## 维护动作
1. 新增子目录 → 建两件套 → 登记到本目录 `_index.md`
2. 移动文件 → 同步改引用（`ai-guard-meta` M1/M4 会报断链）
3. 跑 `bash ../mechanism/guards/ai-guard-health.sh` 复核
