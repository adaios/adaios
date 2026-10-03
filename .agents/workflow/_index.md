---
title: workflow/ 目录索引
description: workflow/ 的文件清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 31
depends-on: []
related: [./_directory.md]
tags: [meta, index]
---

# workflow/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

## 文件清单（4 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `design.md` | RFC 骨架标准化——问题→方案→决策点→验收标准；方案通过后决策入 ADR | active |
| `develop.md` | 工作流开发段——直改代码的入口/出口/沉淀触发；量级匹配见 design.md | active |
| `discuss.md` | 想法/讨论的登记与过滤器——什么值得沉淀（入 ideas/ 或 RFC 前身）、什么是一次性对话；沉淀过滤器由 … | active |
| `overview.md` | AdaiOS AI 协作工作流一张图——约束模型（文档约束代码、审核约束两者）+ 五段闭环（讨论→方案→开发→s… | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- **清单必须与实际文件一致**（`guard-structure` S2 双向校验；新增文件后跑 `guard-structure.sh --fix`）
