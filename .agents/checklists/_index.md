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
| `cost.md` | DeepSeek 峰谷定价后的烧钱动作清单 + 省钱原则——DSH/后端/日常开发哪些操作会烧钱、怎么省、怎么盯… | active |
| `guard.md` | 每次 /review 必跑的 G1-G10 防 P0 复发清单（数据丢失/契约破坏/口径漂移），执行器为 doc… | active |
| `review-backend.md` | backend-reviewer 逐条检查项（人也能用）——数据流水线/存储健壮性/分层/AI 集成/测试 | active |
| `review-context.md` | context-reviewer 逐条检查项（人也能用）——Purpose/Trigger/Action/Con… | active |
| `review-docs.md` | docs-reviewer 逐条检查项（人也能用）——契约真相源/RFC 决策漂移/文档资产健康 | active |
| `review-frontend.md` | frontend-reviewer 逐条检查项（人也能用）——DTO 契约/生命周期/状态管理/测试 | active |
| `review-knowledge.md` | knowledge-reviewer 逐条检查项（人也能用）——os/ 消费链路/data/ 健康/隐私红线/闭… | active |
| `review-perf.md` | app/web/admin 加载慢时的轻量快查——按加载链路分阶段逐条核对，15 分钟出结果（AI 触发 /pe… | active |
| `review-product.md` | product-arch 逐条检查项（人也能用）——五层架构/数据流/Roadmap/原则/功能归属/叙事 | active |
| `review-social.md` | social-reviewer 逐条检查项（人也能用）——通知暴露面/递手机/付出门槛/人情/退出口/体面 | active |
| `review-stranger.md` | stranger-reviewer 逐条检查项（人也能用）——三端入口可达/开号/空世界首屏/第一件事/五问/术… | active |
| `review-support.md` | support-reviewer 逐条检查项（人也能用）——五类必问问题 + 归属判定，答不上来的即缺陷 | active |
| `review-ui.md` | ui-reviewer 逐条检查项（人也能用）——触达/层级/间距/三端/深色/空态 | active |
| `review-ux.md` | ux-reviewer 逐条检查项（人也能用）——操作路径/状态机/异常流/反馈/跨端/误触 | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- **清单必须与实际文件一致**（`guard-structure` S2 双向校验；新增文件后跑 `guard-structure.sh --fix`）
