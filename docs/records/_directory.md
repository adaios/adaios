---
title: docs/records 目录契约
description: docs/records/ 的职责边界 · 依赖关系 · 约束 · 维护方式（机器可校验的目录级元数据）
version: 2
created: 2026-10-04
updated: 2026-10-06
status: active
lines: 43
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# docs/records 目录契约

**职责**：**历史存档**——封存的原始记录。**AI 运行时不需要它**（读它是为了考古，不是为了干活）。

## 职责边界
- **放**：走查存档（audits）· 事故处置记录 · 发布史 · 已归档的问题清单 · 一次性报告
- **不放**：**活账本** → `.agents/records/`（REVIEW / change-log / task-log——那些 AI 每次读写的）· 退役文档 → `../archive/`

> **判据**：这份记录**还会被 AI 在干活时读 / 写吗**？会 → `.agents/records/`；不会 → 本区。

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 被引用 | `.agents/records/REVIEW.md` | 未修项的**证据出处**（REVIEW 里的编号常指向某次走查） |
| 被引用 | `.agents/records/change-log.md` | 批次历史的**详情出处**（change-log 一行 → 本区一份报告） |

## 约束
- **只读不新增**：本区是"已封存"的；新的走查报告先归口到 `.agents/records/REVIEW.md`，报告原文再存这里
- **含"当时"的路径属正常**：`ai-guard-meta` 的 M4 正文路径检查对本区**豁免**（否则会强制历史记录"指向现在"）
- frontmatter：新增文件需 10 字段；**存量存档豁免**

## 守卫（谁保证这里不腐烂）
- `ai-guard-structure`：两件套齐备 · 清单⇄实际
- `ai-guard-unfixed`：检查 audits 是否**归口**（游离报告会被点出）

## 维护动作
1. 新的走查 / 深审报告 → 先归口 `.agents/records/REVIEW.md`，原文存 `audits/`
2. 新增存档 → 补 `_index.md`
3. 确需清除 → 走一次显式提交并在 `_index.md` 注明（不静默删）
