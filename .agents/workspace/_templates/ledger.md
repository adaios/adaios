---
title: 分支账本模板
description: 分支账本模板——本分支的目标 · 进度 · 风险 · 待交接；放在 .agents/workspace/<分支名>/LEDGER.md；合并时按「待交接」搬进全局账本后删目录
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 35
depends-on: []
related: [./requirement.md]
tags: [template, branch, ledger]
---

# <分支名> 分支账本

> **用法**：开分支时复制为 `.agents/workspace/<分支名>/LEDGER.md`（**一个分支一个目录**，主键唯一 ⇒ 零冲突）。
> **分支上只改这一份**、不碰全局账本；合并时按「待交接」搬完即删整目录。

## 目标
（一句话：这条分支要交付什么）

## 进度
- [ ] 
- [ ] 

## 风险 / 阻塞
- 

## 待交接（合并时搬进全局账本）
| 内容 | 归档到 |
|:--|:--|
| （本轮做了什么）| `.agents/records/change-log.md` |
| （新发现的问题）| `.agents/records/REVIEW.md` |
| （新增的文件）| 各目录 `_index.md`（跑 `bash .agents/mechanism/guards/ai-guard-structure.sh --fix`）|
| （新坑 / 新决策）| `.agents/rules/assets/pitfalls.md` / `.agents/rules/assets/adr/` |
