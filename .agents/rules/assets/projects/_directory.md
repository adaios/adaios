---
title: projects/ 目录契约
description: projects/ 的职责边界 · 依赖 · 约束（2026-10-04 补：原为守卫盲区）
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 35
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# projects/ 目录契约

**职责**：按**端或仓库**组织的能力全景——回答「**这个端有什么、职责边界在哪**」

## 职责边界
- **放**：adai-app / adai-web / adai-admin / adai-core 四张卡
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
