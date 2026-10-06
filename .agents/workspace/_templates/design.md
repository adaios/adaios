---
title: 设计文档模板
description: 设计文档模板——本轮回应 · 设计 · 取舍 · 未决 · 自评风险；每轮一份、不覆盖；**一轮只收敛一类**（P0/P1 本轮改，P2/P3 只登记）
version: 1
created: 2026-10-03
updated: 2026-10-04
status: active
lines: 36
depends-on: []
related: [./review-design.md, ./requirement.md]
tags: [template, workspace, design]
---

# <分支名> 设计文档 v<N>

**轮次**：v<N> · <YYYY-MM-DD> · **编写**：设计文档编写者（subagent）
**上游**：同目录 `requirement.md`（需求定稿）· 上一轮 `review-v<N-1>-<日期>.md`（v1 无）

> **用法**：`cp _templates/design.md <分支名>/design-v<N>-<日期>.md`。**每轮一份、不覆盖历史**——可追溯性就靠它。
> **硬要求**：**落盘（文件不存在 = 未完成）+ 登记 `workspace/_index.md` + 跑两道守卫 PASS**。
> **一轮只收敛一类**：**P0/P1 本轮必修；P2/P3 只登记**（防"一轮回改全部"把单轮成本推爆）。

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
