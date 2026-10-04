---
title: docs 目录索引
description: AdaiOS 档案馆索引——史 / 存档 / 对外 / 未定型；AI 运行需要的文档一律在 .agents/
version: 3
created: 2026-08-15
updated: 2026-10-04
status: active
lines: 48
depends-on: []
related:
  - ./README.md
  - archive/_index.md
  - ideas/_index.md
  - legal/_index.md
  - records/_index.md
  - research/_index.md
tags: [meta, index]
---

# docs 目录索引

**职责**：**档案馆**——存放 **AI 上下文运行时不需要**的文档：**历史 · 存档 · 对外材料 · 未定型想法**。

> **2026-10-04 二批**：判据＝「**AI 上下文运行时是否需要**」。**需要的一律在 `.agents/`**（方向 · 事实 · 决策 · 活账本）；本区只留**不需要**的。
> **要认识项目 / 参与开发** → 见**根 `AGENTS.md`**（任何工具的第一入口）——它指向两个容器。

## 子目录（5）

| 路径 | 职责 | 类 |
|:--|:--|:--|
| `archive/_index.md` | 退役文档——3 份 `superseded` RFC + 早期 AI Context 模板 + project-os-usage | 史 |
| `records/_index.md` | 历史存档——走查存档 27 份 · 事故记录 · 发布史 · 归档问题清单 | 史 |
| `research/_index.md` | 研究 · 调研 · 诊断材料（一次性产物） | 研究 |
| `ideas/_index.md` | 未定型但有价值的想法（成熟后升级为 RFC） | 想法 |
| `legal/_index.md` | 对外材料——隐私政策正文（App Store / TestFlight 提交材料） | 对外 |

## 判据（本区与 `.agents/` 的唯一分界）

| 问题 | 答 | 去向 |
|:--|:--|:--|
| **AI 干活时会读 / 写它吗？** | 会 | `.agents/` |
| | 不会 | **本区（档案馆）** |

## 过期判断

- `status != active`（frontmatter）→ 候选归档
- 本区文件**原则上不删**（保留历史痕迹）；退役依据见 `.agents/records/change-log.md`
- **不适用"引用必须指向现在"**：历史存档含"当时"的路径属正常
