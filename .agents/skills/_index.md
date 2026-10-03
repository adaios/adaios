---
title: skills/ 目录索引
description: skills/ 的文件清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 31
depends-on: []
related: [./_directory.md]
tags: [meta, index]
---

# skills/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

## 文件清单（4 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `learn-digest/SKILL.md` | 当用户要求整理外部内容（B站视频/YouTube/文章/字幕/图片）为学习文档时加载——抓取→转写/取文→结构化… | active |
| `new-api.md` | 当需要新增/修改 API 端点时加载——从代码到契约同步的完整闭环（api-spec/status/测试/插件门… | active |
| `new-domain.md` | 当需要新增 Domain OS / 重大架构能力时加载——RFC+六维 → 插件模型 → 数据流设计 → 分层落… | active |
| `ship.md` | 当开发批次完成需要收尾（/ship）时加载——五件套完成标准→契约同步→登记→门禁→规范提交 | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- **清单必须与实际文件一致**（`guard-structure` S2 双向校验；新增文件后跑 `guard-structure.sh --fix`）
