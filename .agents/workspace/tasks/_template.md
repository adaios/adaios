---
title: 任务账本模板
description: 分支任务账本模板——本分支的目标 · 进度 · 风险 · 待归档条目；合并时按「待归档」搬进全局账本后删除本文件
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 34
depends-on: []
related: [./_directory.md]
tags: [task, branch, template]
---

# <分支名> 任务账本

> **用法**：开分支时复制本文件为 `<分支名>.md`；**分支上只改这一份**、不碰全局账本；合并时按「待归档」搬完即删。

## 目标
（一句话：这条分支要交付什么）

## 进度
- [ ] 
- [ ] 

## 风险 / 阻塞
- 

## 待归档（合并时搬进全局账本）
| 内容 | 归档到 |
|:--|:--|
| （本轮做了什么）| `.agents/records/change-log.md` |
| （新发现的问题）| `.agents/records/REVIEW.md` |
| （新增的文件）| 各目录 `_index.md`（跑 `bash .agents/guards/ai-guard-structure.sh --fix`）|
| （新坑 / 新决策）| `.agents/assets/pitfalls.md` / `.agents/assets/adr/` |
