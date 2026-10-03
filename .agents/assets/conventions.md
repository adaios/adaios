---
title: 规范归集（Conventions）
description: 代码/文档/协作三组规范集中声明——从原根 CLAUDE.md（2026-08-19 删除）与 AI 工程层归集；单一事实源，不复制到子项目文档
version: 1
created: 2026-08-15
updated: 2026-08-18
status: active
lines: 55
depends-on: []
related:
  - ../../AGENTS.md
  - ../frontmatter-spec.md
tags: [ai, assets, conventions]
---

# 规范归集

> **定位**：代码/文档/协作三组规范的**单一事实源**。子项目 AGENTS.md 只保留指针（不复制全文——避免双源漂移，REVIEW P3 docs×7）。改规范改这里，同步指针。

## 一、代码规范

| # | 规范 | 说明 |
|:-:|:-----|:-----|
| C1 | Java 17 特性优先 | Record / Sealed Class / Pattern Matching / Text Block |
| C2 | 禁止 `@Autowired` 字段注入 | 统一 Constructor Injection |
| C3 | 日志 SLF4J + Lombok `@Slf4j` | |
| C4 | 异常继承 `RuntimeException` 业务异常 | 在 Domain 内定义 |
| C5 | Module 命名小写 kebab-case | |
| C6 | Package 根 `com.adaiadai.core` | 提交前确认 |
| C7 | 分层依赖：`interfaces → application → domain/kernel ← infrastructure` | kernel 不得反向依赖 infrastructure 类型 |
| C8 | 优先设计数据流 | Record 格式 → Timeline 投影 → Context 组合 → Memory 沉淀，再写代码 |

## 二、文档规范

| # | 规范 | 说明 |
|:-:|:-----|:-----|
| D1 | frontmatter 契约 | 见 `frontmatter-spec.md`（10 字段；语义字段人维护，updated/lines 工具维护）|
| D2 | 目录治理 | 每个目录 `_index.md`（职责+清单+过期判断）|
| D3 | 单一事实源 | 数字（测试/端点）→ status.md；未修项 → REVIEW.md；批次 → change-log.md；蓝图 → roadmap |
| D4 | 文档登记 | 新增/移动文档 → 更新对应 `_index.md` |
| D5 | 人/AI 双读者分离 | AGENTS.md 给 AI、docs/README.md 给人，不合并 |

## 三、协作规范

| # | 规范 | 说明 |
|:-:|:-----|:-----|
| W1 | 入口统一 | `POST /api/v1/records` 是唯一输入入口 |
| W2 | 工作焦点分离 | 在哪个子目录工作只看哪个领域（子项目独立 AGENTS.md，分层就近原则）|
| W3 | 批次收尾 | `/ship`：测试→契约→登记→guard-meta 门禁→提交（见 `process/ship.md`）|
| W4 | 决策沉淀 | 方案通过 → 决策入 `assets/adr/`；踩坑修复 → 入 `checklists/` + `assets/pitfalls.md` |
| W5 | 提交规范 | 按批次主题（feat:/fix:/docs:），一提交一主题，不混合 |
| W6 | 讨论与实施分离 | **只约束代码与 `data/` 数据修改**：讨论方向/方案/口径时不动代码，用户明确「开工/做/改」后才改；AI 工程文档（`ai-engineering/`、AGENTS.md）可直接修订（2026-08-16 确立，2026-08-18 明确范围）|

---
**追加方式**：新规范出现 → 补对应分组一行；改规范 → 本表为主，同步子项目指针。
