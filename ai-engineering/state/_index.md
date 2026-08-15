---
title: state 状态层索引
description: AI 工程状态层目录治理——完成度/测试数/未修项动态真相（指针化，物理文件在 docs/）
version: 1
created: 2026-08-15
updated: 2026-08-15
status: active
lines: 33
depends-on: []
related:
  - ../README.md
tags: [ai, meta, index, state]
---

# state 状态层索引

**职责**：AI 工程动态真相——「做到哪了」。物理文件在 `docs/`（单一事实源），本层是指针视图，不复制内容（ADR-002）。

## 状态真相源（指针）

| 状态 | 物理文件 | 维护时机 |
|:-----|:---------|:---------|
| 测试数/端点数/运行环境 | `docs/reference/status.md` | /ship 时更新 |
| 未修项（战略/P0-P2）| `docs/review/REVIEW.md` | /review /audit 后滚动 |
| 批次历史 | `docs/reference/change-log.md` | 每批落地后追加 |
| 产品蓝图 | `docs/architecture/product-roadmap.md` | 路线变更时 |
| 待办（P2/P3）| `docs/reference/task-log.md` | 走查后迁移 |

## 过期判断

- 指针指向的文件不存在 → guard-meta M1 断链（立即修）
- 状态与代码不一致（status.md 数字 vs 实测）→ P1 级待修（D20 测试数三方对拍）
- 本层只是视图：**不要在 state/ 复制数字/状态**（违反 ADR-002）
