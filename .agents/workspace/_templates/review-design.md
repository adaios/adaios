---
title: 设计审核文件模板
description: 审核文件模板（设计 / 需求共用）：审核对象 · 结论 · 问题清单（P0–P3）· 收敛判定；每轮一份，与稿成对
version: 1
created: 2026-10-03
updated: 2026-10-04
status: active
lines: 36
depends-on: []
related: [./design.md, ./requirement.md]
tags: [template, review, workspace]
---

# <分支名> 审核 v<N>

**轮次**：v<N> · <YYYY-MM-DD> · **审核**：设计文档审核者（subagent）
**审核对象**：同目录 `design-v<N>-<日期>.md`（或 `requirement.md`）

> **用法**：`cp _templates/review-design.md <分支名>/review-v<N>-<日期>.md`。**每轮一份，与同轮稿成对**。
> **硬要求**：**落盘（文件不存在 = 未完成）+ 登记 `workspace/_index.md` + 跑两道守卫 PASS**——**只回报结论不算交付**。

## 结论
（择一填写：**✅ 通过** / **⚠️ 有条件通过** / **❌ 打回**）

## 问题清单

| # | 级别 | 问题 | 依据 | 建议 |
|:--|:--|:--|:--|:--|
| 1 | P? | | （引用规范 / 边界 / 需求条款）| |

> 级别沿用现有体系：**P0 阻断**（设计有硬伤，必须重做）· **P1 必改**（不修就不能进编码）· **P2 应改**（可带着走，记进 REVIEW）· **P3 建议**。

## 收敛判定
- **无 P0/P1 → 收敛**：下一轮直接出 `design-final.md`，进编码阶段
- **有 P0/P1 → 进入 v<N+1>**：编写者按本清单修订
- ★ 若问题属「**取值取舍**」类 → **升级给人决策**，审核者不自行拍板
