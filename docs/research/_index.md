---
title: docs/research 目录索引
description: research/ 的文件清单与过期判断（研究、调研与诊断材料——一次性产物，不参与 AI 运行时）
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 36
depends-on: []
related: [./_directory.md]
tags: [meta, index, research]
---

# docs/research 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

**职责**：**研究 · 调研 · 诊断材料**——有分析价值但**不参与 AI 运行时**（改代码时不需要读它）。

## 文件清单（4 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `memory-frameworks-borrow.md` | 开源记忆方案借鉴分析（Mem0 / Letta / Zep / File-First 生态 → 可借鉴清单） | active |
| `memory-fidelity.md` | 记忆底座质量诊断——原文保真与证据链（三层存储 / E-A 读取侧保真 / 失真审计，2026-09-05） | draft |
| `trading-journal-benchmark.md` | 交易日志竞品调研基准——TradeZella / TraderSync / Edgewonk（RFC 20260905 支撑） | active |
| `trading-risk-plan.md` | 交易风险计划（产品内容，非工程规则） | active |

## 过期判断

- 诊断材料**有时效**：`updated` 超 3 个月且结论已被后续实现覆盖 → 候选归档
- 新增材料 → 补本索引

## 来源

**2026-10-04 二批**：自 `docs/architecture/{memory-frameworks-borrow,memory-fidelity}.md` 与 `docs/reference/{trading-journal-benchmark,trading-risk-plan}.md` 归集——判据＝「AI 上下文运行时**不需要**」（调研/诊断属一次性产物）。
