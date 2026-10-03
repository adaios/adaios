---
title: checklists/ 目录索引
description: checklists/ 的文件清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 41
depends-on: []
related: [./_directory.md]
tags: [meta, index]
---

# checklists/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

## 文件清单（14 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `ai-context-reviewer.md` | ai-context-reviewer 逐条检查项（人也能用）——Purpose/Trigger/Action/… | active |
| `ai-cost-checklist.md` | DeepSeek 峰谷定价后的烧钱动作清单 + 省钱原则——DSH/后端/日常开发哪些操作会烧钱、怎么省、怎么盯… | active |
| `ai-guard-checklist.md` | 每次 /review 必跑的 G1-G10 防 P0 复发清单（数据丢失/契约破坏/口径漂移），执行器为 doc… | active |
| `code-backend-reviewer.md` | code-backend-reviewer 逐条检查项（人也能用）——数据流水线/存储健壮性/分层/AI 集成/… | active |
| `code-frontend-reviewer.md` | code-frontend-reviewer 逐条检查项（人也能用）——DTO 契约/生命周期/状态管理/测试 | active |
| `code-perf-reviewer.md` | app/web/admin 加载慢时的轻量快查——按加载链路分阶段逐条核对，15 分钟出结果（AI 触发 /pe… | active |
| `data-knowledge-reviewer.md` | data-knowledge-reviewer 逐条检查项（人也能用）——os/ 消费链路/data/ 健康/隐… | active |
| `docs-contract-reviewer.md` | docs-contract-reviewer 逐条检查项（人也能用）——契约真相源/RFC 决策漂移/文档资产健… | active |
| `docs-product-reviewer.md` | docs-product-reviewer 逐条检查项（人也能用）——五层架构/数据流/Roadmap/原则/功… | active |
| `ux-interaction-reviewer.md` | ux-interaction-reviewer 逐条检查项（人也能用）——操作路径/状态机/异常流/反馈/跨端/… | active |
| `ux-social-reviewer.md` | ux-social-reviewer 逐条检查项（人也能用）——通知暴露面/递手机/付出门槛/人情/退出口/体面 | active |
| `ux-stranger-reviewer.md` | ux-stranger-reviewer 逐条检查项（人也能用）——三端入口可达/开号/空世界首屏/第一件事/五… | active |
| `ux-support-reviewer.md` | ux-support-reviewer 逐条检查项（人也能用）——五类必问问题 + 归属判定，答不上来的即缺陷 | active |
| `ux-visual-reviewer.md` | ux-visual-reviewer 逐条检查项（人也能用）——触达/层级/间距/三端/深色/空态 | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- **清单必须与实际文件一致**（`ai-guard-structure` S2 双向校验；新增文件后跑 `ai-guard-structure.sh --fix`）
