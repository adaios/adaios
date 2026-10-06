---
title: 新项目脚手架（scaffold）
description: init-ai-engineering.sh 设计——新项目一条命令搭好 AI 工程流水线（hooks + guard + 流程文件 + CI 模板）
version: 1
created: 2026-08-16
updated: 2026-10-06
status: active
lines: 82
depends-on:
  - README.md
  - pipeline.md
related:
  - ../../mechanism/guards/ai-guard-meta.sh
  - ../../mechanism/guards/ai-guard-align.sh
tags: [ai, method, scaffold]
---

# 新项目脚手架

> **目标**：新项目启动 = 跑一次 `init-ai-engineering.sh`，一条命令搭好整条流水线。之后 AI 直接开写功能，不用研究"怎么搭"。让"先搭 AI 工程，再写内容"成为标准范式。

## 脚手架产出（一条命令）

```
init-ai-engineering.sh <项目名>
│
├── .agents/                    ← 7 顶层结构（照参考实现，按需裁剪）
│   ├── README.md               定位 + 七顶层速查
│   ├── frontmatter-spec.md     元数据契约
│   ├── direction/              方向与决策（VISION · roadmap · rfc/）
│   ├── knowledge/              事实（reference/ · features/）
│   ├── rules/                  规则（assets/ · guides/ · process/ · workflow/ · method/）
│   ├── records/                活账本（REVIEW · change-log · state/）
│   ├── toolkit/                能力（roles/ · skills/ · checklists/）
│   └── mechanism/              机制（guards/ · scripts/）
├── AGENTS.md                   AI 入口（模板）
├── .githooks/pre-commit        **多层**门禁（模板；层数不写死）
└── .gitlab-ci.yml              CI 模板（可选）
```

> **模板 ⇄ 参考实现的关系**：`.agents/` 的**顶层同名**、子目录按项目需要裁剪（新项目不必一步到位）。
> ⚠️ **旧稿曾把目录名写成 `ai-engineering/`（三层）**——2026-10-03 已收进 `.agents/` 并演进为 **7 顶层**，
> 此处同步（2026-10-06 体系体检 **H1** 收口）。

## 脚手架流程

```
┌──────────┐   ┌──────────────┐   ┌───────────────┐   ┌─────────────┐
│ 跑脚手架  │ → │ 填项目元信息   │ → │ 复制模板+适配   │ → │ ai-setup-hooks  │
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
1. 跑完脚本 → `.agents/` 完整（ai-guard-meta 对模板 PASS）
2. ai-setup-hooks → 提交时四层自动跑
3. 首个功能批次 → 走通 discuss→ship→guard 全链
4. 人只在三处介入：方案确认 / 审核内容 / 部署决策
```

## 状态

- **adaios = 参考实现**：本仓库的 `.agents/` 是脚手架要复制的"黄金模板"（已实践验证）
- **脚手架脚本**：待写（从 adaios 提取模板 + 参数化）
