---
title: 设计文档模板
description: 模板 A——设计文档（设计文档编写者产出）：本轮回应 · 设计 · 取舍 · 未决 · 自评风险；每轮一份，不覆盖历史
version: 1
created: 2026-10-03
updated: 2026-10-04
status: active
lines: 34
depends-on: []
related: [./_directory.md, ./_template-review.md]
tags: [design, workspace, template]
---

# <需求id> 设计文档 v<N>

**轮次**：v<N> · <YYYY-MM-DD> · **编写**：设计文档编写者（subagent）
**上游**：`../../requirements/<需求id>.md`（需求定稿）· 上一轮 `review-v<N-1>-<YYYYMMDD>.md`（v1 无）

> **用法**：`cp _template-design.md <需求id>/design-v<N>-<YYYYMMDD>.md`。**每轮一份、不覆盖历史**——可追溯性就靠它。

## 本轮回应
（针对上一轮审核结论的**哪几条**做了什么改动；v1 写「首轮」）

## 设计
（方案主体：数据流 / 模块 / 接口 / 边界 —— 写「怎么做」，不写「做完了什么」）

## 取舍
（做过哪些选择、**为什么**；两个方案都行时选了哪个及理由）

## ★ 未决（需人拍板）
- （AI 不该自己决定的项：取值取舍 / 与既有边界冲突 / 成本与范围权衡）

## 自评风险
（我认为哪里最可能被审核者打回）
