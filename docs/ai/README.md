---
title: AI 协作协议区
description: AdaiOS 开发期 AI 协作标准（工具无关）——审查官定义、审查流程、检查清单、元数据规范；任何 AI 工具读此区即可执行
version: 1
created: 2026-08-15
updated: 2026-08-15
status: active
lines: 40
depends-on:
  - frontmatter-spec.md
related:
  - ../../AGENTS.md
  - ../review/REVIEW.md
tags: [ai, meta]
---

# AI 协作协议区（docs/ai/）

> **定位**：AI 深度参与项目的协作标准，与工具无关。人类文档在 `docs/`（面向人），本区是**面向 AI 执行的标准**（谁来看、怎么审、按什么清单）。

## 目录

| 文件 | 说明 |
|:-----|:-----|
| `frontmatter-spec.md` | 文档元数据契约（图谱/治理/归档基础）|
| `roles/` | 8 个审查官定义（产品架构/交互体验/界面设计/后端/前端/文档/知识数据/Context）|
| `process/audit.md` | 全维度走查流程（8 官独立并行 + 交叉印证）|
| `process/review.md` | 增量深审流程（按改动派官）|
| `process/ship.md` | 收尾闭环流程（测试→契约→登记→guard-meta 门禁→提交）|
| `checklists/` | 检查清单（执行细节，人也能用：8 官清单 + guard 守护）|
| `guard-meta.sh` | 元治理自检：frontmatter 图谱/lines/孤儿，`--fix` 回写 lines |

## 任何 AI 工具如何接入

1. 读本区 `README.md`（定位）→ `frontmatter-spec.md`（元数据）
2. 执行审查：读 `process/audit.md`（全维度）或 `process/review.md`（增量）
3. 按 `roles/` 找到对应审查官定义，逐项执行
4. 结果汇总到 `docs/review/REVIEW.md`

> 工具侧入口（Claude/Qoder/DSH 的一行配置）在工具自己的配置里，**不在本项目**——换工具零迁移。
