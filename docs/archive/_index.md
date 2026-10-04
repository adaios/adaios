---
title: docs/archive 目录索引
description: 归档区——已退役但保留作历史痕迹的文档（不再被引用、不参与现行决策）
version: 2
created: 2026-10-03
updated: 2026-10-04
status: active
lines: 47
depends-on: []
related:
  - ../_index.md
tags: [meta, index, archive]
---

# docs/archive 目录索引

**职责**：存放**已退役**的文档——保留历史痕迹，但**不再作为任何现行决策或执行的依据**。

## 文件清单

### 退役 RFC（`superseded`，3 份）

| 文件 | 职责 | 状态 |
|:-----|:-----|:----:|
| 20260726-project-status-and-roadmap.md | 早期「项目状态与路线」——**已被 `.agents/direction/product-roadmap.md` 取代**（2026-10-04 随二批归档） | superseded |
| 20260815-trading-interaction-redesign.md | 交易交互重设计——**已被取代**（2026-10-04 随二批归档） | superseded |
| 20260829-learn-plugin.md | learn 插件设计——**已被取代**（2026-10-04 随二批归档） | superseded |

### 退役文档

| 文件 | 职责 | 状态 |
|:-----|:-----|:----:|
| project-os-usage.md | Project OS 使用指南（**已退役**：2026-09-17 RFC 20260917 撤除 project 插件与「项目阿呆」注入；现仅作 `os/project-os/` 知识目录的阅读说明） | superseded |

### 早期 AI Context 模板（`ai-context/`，4 份）

| 文件 | 职责 | 状态 |
|:-----|:-----|:----:|
| ai-context/20260722-ai-context-design.md | 阿呆 App 早期 AI Context 设计（2026-07-22，早期设计历史） | active |
| ai-context/project-context.md | 早期「项目级 AI 上下文」模板（占位符从未填充） | superseded |
| ai-context/architecture-context.md | 早期架构上下文（包树 / Context Engine，内容已被覆盖） | superseded |
| ai-context/developer-context.md | 早期「开发者上下文」模板（全字段占位符） | superseded |

## 过期判断

- 本区文档**永久归档**，退役依据见 `.agents/records/change-log.md` 与 `.agents/rules/assets/ai-context-layer-spec.md` §十。
- 归档**不删文件**（保留决策痕迹）；若要彻底清除，走一次显式提交并在此注明。
