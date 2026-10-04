---
title: reference/contracts/ 目录契约
description: reference/contracts/ 的职责边界 · 依赖 · 约束 · 维护（2026-10-04 reference 分层批）
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 35
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# reference/contracts/ 目录契约

**职责**：**契约（机器对拍）**——**与代码逐一对拍**的契约类文档——`ai-guard-align` 直接读它们校验一致性。

## 职责边界
- **放**：api-spec（端点契约·唯一真相源）· data-format-freeze（data/ 格式契约）
- **不放**：数字真相源（`../status.md`，刻意留根）· 规则 → `../../../rules/` · 决策 → `../../../direction/rfc/`

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 被依赖 | `../status.md` | 状态真相源（每次开工自举读） |
| 守卫 | `../../../mechanism/guards/ai-guard-align.sh` | 契约类由它与源码对拍 |

## 约束
- **单一权威来源**：同一知识只在一处详述
- frontmatter **10 字段**（`ai-guard-meta` 查）

## 维护动作
1. 新增文件 → 补 `_index.md` → 跑 `ai-guard-structure.sh --fix`
2. 移动/改名 → 同步改引用（`ai-guard-meta` M1/M4 报断链）
