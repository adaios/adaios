---
title: docs/research 目录契约
description: research/ 的职责边界 · 依赖关系 · 约束 · 维护方式（机器可校验的目录级元数据）
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 33
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# docs/research 目录契约

**职责**：**研究 · 调研 · 诊断材料**——一次性分析产物，**不参与 AI 运行时**。

## 职责边界
- **放**：外部方案借鉴 · 竞品调研 · 质量诊断 · 产品内容性的计划
- **不放**：决策（→ `.agents/rfc/`）· 事实（→ `.agents/reference/`）· 记录（→ `../records/`）

## 约束
- **一次性产物**：写的时候有用，之后是"当时怎么看"的证据，**不要求与现状一致**
- 结论若被采纳 → **沉淀成 RFC / 事实文档**（本区不承担"现行真相源"角色）
- frontmatter **10 字段**（`ai-guard-meta` 查）

## 守卫（谁保证这里不腐烂）
- `ai-guard-meta`：frontmatter / lines / 断链

## 维护动作
1. 新增研究材料 → 补 `_index.md`
2. 结论被采纳 → 转成 `.agents/rfc/` 或 `.agents/reference/` 的正式文档，本区原件保留作证据
3. 时过境迁 → 移入 `../archive/`
