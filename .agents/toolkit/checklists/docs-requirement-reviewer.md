---
title: 需求文稿审查检查清单
description: docs-requirement-reviewer 逐条检查项（人也能用）——13 条需求质量判据（含 design-independent 与可验证性 + 路线对齐）+ 弱词表
version: 1
created: 2026-10-06
updated: 2026-10-06
status: active
lines: 50
depends-on: []
related:
  - ../roles/docs-requirement-reviewer.md
  - ../../rules/process/review-driven.md
tags: [review, checklist, requirement]
---

# 需求文稿审查检查清单（Requirement）

> 需求文稿写完、人签字之前逐条对。判据出处见 ISO/IEC/IEEE 29148 §5.2.5–5.2.6。

## 主检查表

| # | 检查项 | 判定 |
|:-:|:-------|:----:|
| R1 | 必要：每条能追到动机，无「因为能做所以做」 | PASS/FAIL |
| R2 | 动机：为什么做 + 不做的代价写清 | PASS/FAIL |
| R3 | 谁 / 什么时刻：用户与场景，不是功能清单 | PASS/FAIL |
| R4 | 范围三档：做 / 先不做 / 不做 三档齐 | PASS/FAIL |
| R5 | 可验证：验收标准可判定，未命中弱词表 | PASS/FAIL |
| R6 | 无歧义 · 单一：一条只说一件事，术语唯一 | PASS/FAIL |
| R7 | 完整：边界与异常（缺数据 / 错数据 / 没权限 / 新用户 / 换设备）| PASS/FAIL |
| R8 | 一致：前后不矛盾 | PASS/FAIL |
| R9 | 设计无关（★）：无端点 / 字段 / 算法 / 表名，出现即越界 | PASS/FAIL |
| R10 | 可追踪：需求有编号（R-01…），能追到设计 / 测试 | PASS/FAIL |
| R11 | 分系统清楚：每功能属谁（admin / web / app）+ 两端呈现差异 | PASS/FAIL |
| R12 | 未决：清空或升级给人，不带未决定稿 | PASS/FAIL |
| R13 | 路线对齐（★）：需求追得到 roadmap / VISION；改方向显式开 RFC | PASS/FAIL |

## 弱词表（命中即报）

尽量 · 友好 · 快速 · 合理 · 智能 · 流畅 · 优雅 · 等 · 若干 · 可能 · 较好

## 沉淀检查点（带上次发现）

| # | 检查项 | 上次发现 |
|:-:|:-------|:---------|
| L1 | 需求里不得混入设计（端点 / 字段 / 表名）| 2026-10-06 交易插件需求初稿混入设计，拖到设计审核才爆 |
| L2 | 需求侧扩容 / 砍功能必须在需求阶段对路线 | 定位扩容 / L6 反哺被砍拖到设计审核才爆（→ A2）|

---
**追加方式**：新发现需求质量类问题 → 追加一行，注明日期。
