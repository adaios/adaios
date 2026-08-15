---
title: 方案设计流程（design）
description: RFC 骨架标准化——问题→方案→决策点→验收标准；方案通过后决策入 ADR
version: 1
created: 2026-08-15
updated: 2026-08-15
status: active
lines: 56
depends-on:
  - discuss.md
related:
  - ../../docs/rfc/20260815-ai-engineering-layer.md
tags: [ai, workflow, design]
---

# 方案设计（design）

> **定位**：工作流第二段——从「想法」到「可执行方案」。产出 RFC（`docs/rfc/`），通过后关键决策入 `assets/adr/`。

## RFC 骨架（7 段）

| 段 | 内容 | 长度 |
|:---|:-----|:-----|
| 1. 背景 | 为什么现在做（实测证据优先）| 3-5 句 |
| 2. 问题 | 病灶/缺口清单（有证据）| 表格 |
| 3. 方案 | 目标模型 + 具体设计 | 主体 |
| 4. 决策点 | 需要人拍板的取舍（编号）| 列表 |
| 5. 风险与对策 | 每个风险有对策 | 表格 |
| 6. 落地路径 | 分批次，每批可独立验收 | 表格 |
| 7. 验收标准 | 可测量、可 grep | 列表 |

## 量级匹配（沿用 CLAUDE.md 开发流程）

| 量级 | 流程 |
|:-----|:-----|
| 新增 Domain / 架构选型 | 完整 RFC |
| 新 API | 直接改代码，api-spec 事后同步 |
| 复杂 UI 交互 | UI Flow（半页）|
| 字段增删/重构 | 直接改代码 |
| Bug 修复 | 直接改 |

## 决策沉淀（验收时）

- 方案通过 → 关键决策（背景/备选/结论/代价）入 `assets/adr/ADR-00N.md`
- RFC frontmatter：`status: draft → approved`（含 decided-by）+ `decided-by` 措辞清理（D28：正文残留「等你拍板」要同步清）
- RFC 变更记录：`revised` 字段追加

## 质量门

1. 每个决策点都有明确的推荐 + 理由（不只列选项）
2. 风险表每个风险有对策（不写「暂无」）
3. 验收标准可测量（数字/命令/文件）
4. 与边界表核对：不触碰原则级边界；解除功能级边界须在本 RFC 声明

---
**追加方式**：新发现方案质量问题 → 补入质量门。
