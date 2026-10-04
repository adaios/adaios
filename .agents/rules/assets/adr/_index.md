---
title: adr/ 目录索引
description: 记录**已拍板的架构/机制决策**及其理由——回答「**当初为什么这么定**」；契约见 ./_directory.md
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 33
depends-on: []
related: [./_directory.md]
tags: [meta, index]
---

# adr/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

**职责**：见 [`_directory.md`](./_directory.md)（此处不重复——S5 判据）

## 文件清单（6 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `ADR-001.md` | 决策：ai-engineering/ 与代码工程平级，为驱动层而非附属文档（decides）；关联 RFC 20… | accepted |
| `ADR-002.md` | 决策：数字/未修项/批次/蓝图各有一处真相源，其余指针引用（decides）；关联 RFC 20260815-d… | accepted |
| `ADR-003.md` | 决策：Kernel 常驻 + Domain 受控插件（decides）；关联 RFC 20260814-doma… | accepted |
| `ADR-004.md` | 决策：推送 per-user 开关（写读双侧门控）+ 交易日志归集三步流水线（识别→候选→审核落库，仅插件用户）… | accepted |
| `ADR-005.md` | 决策：全库 CLAUDE.md→AGENTS.md 迁移（工具无关统一入口）+ 建设/收尾/审查技能封装为 SK… | accepted |
| `ADR-006.md` | 决策：所有用户可见能力分三层——core（记录/问答/记忆/上下文/身份/存储，不可关）/ builtin（待办… | accepted |

## 过期判断

- **清单须与实际一致**（`ai-guard-structure` S2；跑 `--fix` 刷新）
