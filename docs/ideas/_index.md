---
title: docs/ideas 目录索引
description: ideas 文档区目录治理——职责、文件清单、过期判断（文件自理机制）
version: 1
created: 2026-08-15
updated: 2026-09-19
status: active
lines: 35
depends-on: []
related:
  - ../_index.md
tags: [meta, index, ideas]
---

# docs/ideas 目录索引

**职责**：想法/方案归档区——未定型但有价值的想法（暂不参与主流程，可能孵化成 RFC）。

## 文件清单

| 文件 | 职责 | 状态 |
|:-----|:-----|:----:|
| README.md | 归档说明（含子目录/文件登记） | active |
| 20260812-ai-interaction-log.md | AI 交互日志需求（R1，已实现） | active |
| 20260812-camera-sports-analysis.md | 相机运动分析想法 | active |
| 20260812-record-task-association.md | 记录↔任务模块关联（R2，已实现） | active |
| 20260919-external-briefing-for-ai.md | 现状简报（供外部 AI 讨论发展方向的事实底稿） | active |
| 20260919-adai-state-snapshot-for-ai.md | 最新状态快照（对外 AI 交接件：是什么/变化/真问题/方向候选） | active |
| 20260919-ai-2.0-plan-absorption.md | 外部 2.0 规划汲取（采纳/不采纳/待补三空洞，draft） | draft |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- 新增文档：补本索引 + frontmatter
