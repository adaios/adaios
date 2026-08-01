---
title: 项目审核全量状态报告
updated: 2026-08-01
last-review: 2026-08-01
baseline: a4a7c12
mode: 初始建立（迁移自 20260801-project-review.md）
---

# 项目审核状态报告

常驻全量状态。每次 `/review` 更新本文件——未修复项滚动保留，已修复标 ✅ 移入已修复区，新问题追加。git 历史天然保留每次更新前的快照。

## 最近审核

| 日期 | 模式 | 基线 | 派发角色 | 新增 | 修复 |
|:-----|:-----|:-----|:---------|:-----|:-----|
| 2026-08-01 | 全量（初始） | — | backend / frontend / arch ×3 | 32 | 23 |

## 🔴 未修复项（当前待办）

| # | 优先级 | 问题 | 位置 | 状态 |
|:-:|:------:|:-----|:-----|:----:|
| 12 | P1 | 复盘绕过 ContextEngine 手拼 prompt，交易规则从不进 prompt | `TradingReviewAppService.java` | 📋 待办 |
| 13 | P2 | interfaces 层编排重复三处（compose→understand→persist）| `RecordController`/`RecordFlowAppService`/`RecordRetryService` | 📋 待办（高风险重构，保留长期）|
| 14 | P2 | 测试缺口：行情/Memory/Feed分页/ContextEngine/Phase4 页面零测试 | `src/test` | 📋 待办 |
| 19 | P2 | Feed/Context/Memory 每次全量遍历 data 目录 | `RecordFileRepository.findAll` | 📋 待办（数据量小）|
| 21 | P2 | save/delete 重建文件丢弃手写注释 | `ProjectFileRepository`/`MemoryService` | 📋 待办 |
| 22 | P2 | kernel 反向依赖 infrastructure 类型 | `IntentRecognizer`/`ContextEngine`/`MemoryService` | 📋 待办（有意跳过，高风险）|
| 23 | P2 | Layer 6 反馈闭环从未运转（conflicts 硬编码）| `TradingController.java` | 📋 待办 |
| 24 | P2 | **记忆沉淀断裂**（records 46 文件 vs memory 1 文件）。① `handleStatem` AI 失败时 `understanding=null` → 记忆跳过不降级沉淀 ② `handleQuestion→QuestionAppService.answer()` 从不 persist Memory | `RecordController.handleStatem` / `QuestionAppService` | 📋 待办（程序修复：AI 失败降级沉淀 + QUESTION 也沉淀）|
| 16a | P3 | 迁移 3 个历史对话卡片到 `records/cards/` | 卡片 `1784699856587`/`1784700029268`/`1784700598749` | 📋 待办 |

## ✅ 已修复区（最近 10 条，旧条目随滚动删除）

| # | 问题 | 修复 |
|:-:|:-----|:-----|
| 25-32 | 前端 P3 打磨 8 项（URL 编码/文本清理统一/日期硬编码/静默刷新/方向图常量/死代码/light主题） | ✅ 2026-08-01 |
| 15-20 | 数据目录 + api-spec v3.1 + CLAUDE.md 对齐 + 任务扫全部月份 | ✅ 2026-08-01 |
| 7-11 | P1 功能 bug 5 项（emoji 代理对/行情缓存键/AI失败保数据/mounted守卫/PnL序列化）| ✅ 2026-08-01 |
| 3-6 | 战略缺口（场景路由打通/Life知识注入/大盘始终注入）| ✅ 2026-08-01 |
| 1-2 | P0 数据丢失（ID 毫秒/Card 路径按 createdAt）| ✅ 2026-08-01 |

## 执行成本

| 日期 | 模式 | 派发角色 | agent 数 | 耗时 | 新增 | 修复 |
|:-----|:-----|:---------|:--------:|:-----|:----:|:----:|
| 2026-08-01 | 全量 | backend/frontend/arch | 3 | ~2h | 32 | 23 |

> 跑几轮后用成本数据决定全量审核频率（目前建议：增量随时，全量 1-2 周一次）。
