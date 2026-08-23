---
title: 增量深审流程
description: /review 的通用版——按改动范围派对应审查官，滚动更新 REVIEW.md
version: 1
created: 2026-08-15
updated: 2026-08-22
status: active
lines: 85
depends-on:
  - ../frontmatter-spec.md
related:
  - audit.md
  - ../../docs/review/REVIEW.md
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
bash ai-engineering/guard-meta.sh       # 元治理：frontmatter 图谱/lines/孤儿（D30/D34）
```

- G1-G7 有 HIT 即记录为问题（复发信号）。清单见 `ai-engineering/checklists/guard.md`。
- guard-meta 有 FAIL 时先 `bash ai-engineering/guard-meta.sh --fix` 回写 lines，再人工处理断链/孤儿（D34 回写门禁）。

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
| ai/**、ai-engineering/** | context-reviewer |
| 跨多目录 | 多官并行 |

> **模型分层（成本纪律 2026-08-18）**：deep 派官默认 Flash；仅深审场景（product-arch 全局视角、大重构）可切 Pro——差价 3 倍（见 `checklists/cost.md` S6）。`--full` 已默认不跑，见 `audit.md` 成本纪律。

### 性能专项（/perf，2026-08-22）

加载慢类问题**不派 8 官、不跑 guard、不滚动 REVIEW.md**——症状型快查：

- 触发：`/perf <端名>`（adai-app / adai-web / adai-admin）
- 执行：按 `checklists/review-perf.md` 阶段 A→F 逐条核对，默认 Flash
- 时间盒 15 分钟，超时即停；输出 ≤10 条（位置 / 一句问题 / 一句建议），标注「卡首屏 / 卡内容 / 卡交互」
- 疑似后端慢（快照计算/K 线扫描）→ 一句转派 backend-reviewer，不深挖
- 沉淀：只追加一行到 review-perf.md 对应阶段，不进 REVIEW.md 滚动区

## 4. 汇总排序

收集各官结果 → 去重合并 → P0（数据丢失）→ 战略 → P1 → P2/P3。守护命中并入。

- **输出控字（成本纪律 2026-08-18）**：每条问题 ≤3 行（位置/一句问题/一句建议），仅 P0/战略级展开，P1-P3 列表直出（输出是 9-27 元/M 最贵通道）。

## 5. 滚动更新 REVIEW.md

- 未修项逐条核对（本次已修 → 移已修复区；未修 → 保留）
- 新问题追加对应优先级区；更新头部（日期/基线/模式）
- 已修复区只留最近 10 条

## 6. 沉淀检查点

各官报告附「新增检查点建议」→ 补进对应 `checklists/`。

## 7. 记录成本

REVIEW.md 末尾成本表追加一行（日期/模式/派官/agent 数/耗时/新增/修复）。
