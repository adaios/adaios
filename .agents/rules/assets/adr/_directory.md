---
title: adr/ 目录契约
description: adr/ 的职责边界 · 依赖 · 约束（2026-10-04 补：原为守卫盲区）
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 35
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# adr/ 目录契约

**职责**：记录**已拍板的架构/机制决策**及其理由——回答「**当初为什么这么定**」

## 职责边界
- **放**：ADR-001 ~ ADR-006：逐条架构决策（编号不可复用；废弃走 superseded 不删）
- **不放**：规范 → `../`（父目录）· 事实手册 → `../../../knowledge/reference/`

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 父目录 | `../_index.md` | 本目录在其清单中登记 |
| 守卫 | `../../../mechanism/guards/ai-guard-meta.sh` | frontmatter / 断链 / 孤儿 |

## 约束
- frontmatter **10 字段**（`ai-guard-meta` 查）
- **单一权威来源**：同一知识只在一处详述

## 维护动作
1. 新增文件 → 跑 `ai-guard-structure.sh --fix` 刷清单
2. 移动/改名 → 同步改引用（`ai-guard-meta` M1/M4 报断链）
