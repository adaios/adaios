---
title: 新项目脚手架（scaffold）
description: init-ai-engineering.sh 设计——新项目一条命令搭好 AI 工程流水线（hooks + guard + 流程文件 + CI 模板）
version: 1
created: 2026-08-16
updated: 2026-08-16
status: active
lines: 45
depends-on:
  - README.md
  - pipeline.md
related:
  - ../../guard-meta.sh
  - ../../guard-align.sh
tags: [ai, method, scaffold]
---

# 新项目脚手架

> **目标**：新项目启动 = 跑一次 `init-ai-engineering.sh`，一条命令搭好整条流水线。之后 AI 直接开写功能，不用研究"怎么搭"。让"先搭 AI 工程，再写内容"成为标准范式。

## 脚手架产出（一条命令）

```
init-ai-engineering.sh <项目名>
│
├── ai-engineering/           ← 三层结构（模板）
│   ├── README.md              定位 + 切入点图谱
│   ├── frontmatter-spec.md    元数据契约
│   ├── guard-meta.sh          结构门禁（模板，自动适配项目）
│   ├── guard-align.sh         内容对齐（模板，自动适配项目）
│   ├── assets/                规范/边界/ADR/坑（空模板）
│   ├── workflow/              discuss→design→develop（模板）
│   ├── process/               ship→review→audit（模板）
│   └── state/                 状态指针（模板）
├── AGENTS.md                  AI 入口（模板）
├── .githooks/pre-commit       四层门禁（模板）
├── scripts/setup-hooks.sh     hooks 启用
└── .gitlab-ci.yml             CI 模板（可选）
```

## 脚手架流程

```
┌──────────┐   ┌──────────────┐   ┌───────────────┐   ┌─────────────┐
│ 跑脚手架  │ → │ 填项目元信息   │ → │ 复制模板+适配   │ → │ setup-hooks  │
│ init-xxx │   │ (名/栈/语言)   │   │ (guard 路径)   │   │ + 首个提交   │
└──────────┘   └──────────────┘   └───────────────┘   └─────────────┘
                                                              │
                                                              ▼
                                                    ┌─────────────────┐
                                                    │ 流水线已就绪      │
                                                    │ 直接开写功能      │
                                                    │ 提交自动检查      │
                                                    └─────────────────┘
```

## 模板 vs 实例（复用边界）

| 层 | 可复制 | 说明 |
|:---|:------:|:-----|
| 机制（hooks/guard/流程文件）| ✅ | 复制的是"如何建"，与语言/栈无关 |
| 骨架（三层目录/_index）| ✅ | 空模板，新项目填内容 |
| 资产（ADR/坑/规范）| ❌ | 项目土壤里长出来的，不可搬运 |
| 检查点（checklists 具体条目）| ⚠️ | 通用模式可带（防复发类），项目特定需重写 |

## 脚手架验收

```
1. 跑完脚本 → ai-engineering/ 完整（guard-meta 对模板 PASS）
2. setup-hooks → 提交时四层自动跑
3. 首个功能批次 → 走通 discuss→ship→guard 全链
4. 人只在三处介入：方案确认 / 审核内容 / 部署决策
```

## 状态

- **adaios = 参考实现**：本仓库的 ai-engineering/ 是脚手架要复制的"黄金模板"（已实践验证）
- **脚手架脚本**：待写（从 adaios 提取模板 + 参数化）
