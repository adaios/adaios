---
title: 增量深审流程
description: /review 的通用版——按改动范围派对应审查官，滚动更新 REVIEW.md
version: 1
created: 2026-08-15
updated: 2026-08-15
status: active
lines: 71
depends-on:
  - ../frontmatter-spec.md
related:
  - audit.md
  - ../../review/REVIEW.md
tags: [ai, process]
---

# 增量深审流程（review）

> 对应原 `/review` skill，工具无关化。轻量（默认）/ 深度（--deep）/ 全量（--full）三档。

## 1. 定基线

```bash
git log --oneline -20          # 找上次审查 commit 作为基线（REVIEW.md 头部 baseline）
```

- 默认：`git diff <基线>..HEAD`（增量）
- --full：全仓库，不设 diff 边界

## 2. 守护检查（每次必跑）

```bash
bash docs/review/guard.sh        # G1-G7 代码级守护
```

G1-G7，有 HIT 即记录为问题（复发信号）。清单见 `docs/ai/checklists/guard.md`。

## 3. 按模式派官

- **light**：不派官，守护 + `git diff --stat` 快扫，列风险点
- **deep**：按 diff 触及目录派对应审查官（见下表），每个官并行独立审查
- **full**：8 官全派

| 改动位置 | 派官 |
|:---------|:-----|
| services/adai-core/** | backend-reviewer |
| apps/**（Flutter）| frontend-reviewer（+ ui-reviewer 若涉视觉、+ ux-reviewer 若涉流程）|
| docs/**、*.md | docs-reviewer |
| os/**、data/** | knowledge-reviewer |
| ai/**、docs/ai/** | context-reviewer |
| 跨多目录 | 多官并行 |

## 4. 汇总排序

收集各官结果 → 去重合并 → P0（数据丢失）→ 战略 → P1 → P2/P3。守护命中并入。

## 5. 滚动更新 REVIEW.md

- 未修项逐条核对（本次已修 → 移已修复区；未修 → 保留）
- 新问题追加对应优先级区；更新头部（日期/基线/模式）
- 已修复区只留最近 10 条

## 6. 沉淀检查点

各官报告附「新增检查点建议」→ 补进对应 `checklists/`。

## 7. 记录成本

REVIEW.md 末尾成本表追加一行（日期/模式/派官/agent 数/耗时/新增/修复）。
