---
title: reference/contracts/ 目录索引
description: reference/contracts/ 的文件清单与过期判断；契约见 ./_directory.md
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 29
depends-on: []
related: [./_directory.md]
tags: [meta, index]
---

# reference/contracts/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

**职责**：见 [`_directory.md`](./_directory.md)（此处不重复——S5 判据）

## 文件清单（2 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `api-spec.md` | 📋 **API 接口契约（唯一真相源）**——全部端点定义与请求/响应结构；`ai-guard-align` A… | active |
| `data-format-freeze.md` | 📦 `data/` 全部文件格式契约 + 变更规则（v1.0.0 冻结） | active |

## 过期判断

- **清单须与实际一致**（`ai-guard-structure` S2；跑 `--fix` 刷新）
