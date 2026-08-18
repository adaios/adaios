---
title: 全维度走查流程
description: /audit 通用版——8 审查官独立并行走查 + 交叉印证（默认增量，全量仅里程碑级），沉淀到 REVIEW.md「走查区」
version: 1
created: 2026-08-15
updated: 2026-08-18
status: active
lines: 66
depends-on:
  - ../frontmatter-spec.md
related:
  - review.md
  - ../../docs/review/REVIEW.md
tags: [ai, process]
---

# 全维度走查流程（audit）

> 目的：8 个审查官**从各自角度独立**走查产品，交叉印证发现。**成本纪律（2026-08-18）**：全量走查 = 8 官 × 全仓库并行读入，是全项目最贵的 AI 流程；**默认走增量，全量只留里程碑级**（如 v1.0.0 发布前），平时走查走 `review.md` 增量深审（成本低约一个数量级）。

## 1. 定基线

> **默认增量**：不指定范围时按 `git diff <基线>..HEAD`（同 review.md），避免 8 官 × 全仓库的全量默认执行（2026-08-18 成本修订）。

- 默认：`git diff <基线>..HEAD`（增量，同 `review.md`）
- 全量（无 diff 边界）**仅限**：里程碑/发布前（如 v1.0.0）或用户显式 `audit --full`；每次全量 = 8 官 × 全仓库读入，是全项目最贵流程，高峰时段（9-12/14-18）禁跑
- 指定范围：`audit <commit-range>`（如里程碑区间）

## 2. 守护检查

```bash
bash docs/review/guard.sh        # G1-G7 代码级守护
bash ai-engineering/guard-meta.sh       # 元治理：frontmatter 图谱/lines/孤儿（D30/D34）
```

## 3. 8 官独立并行

每个审查官**独立**执行（互不参考，保证视角纯净），按各自 `roles/*.md` 定义：

| 官 | 视角 | 重点 |
|:---|:-----|:-----|
| product-arch | 全局 | 五层架构、数据流、Roadmap、原则符合度（第一原则）、功能归属 |
| ux-reviewer | 流程 | 操作路径、状态机、异常流、反馈完整性、跨端一致、误触 |
| ui-reviewer | 视觉 | 布局触达、视觉层级、间距、三端一致、深色模式、空态/加载态 |
| backend-reviewer | 代码 | 分层、数据安全、健壮性、测试 |
| frontend-reviewer | 代码 | 状态管理、生命周期、契约、测试 |
| docs-reviewer | 契约 | 文档-代码一致、断链、数字漂移 |
| knowledge-reviewer | 资产 | os/ 知识、data/ 数据健康、跨层闭环 |
| context-reviewer | Context | ai/context/ 模板与 os/*/11-context/ 的 Purpose/Trigger/Action/Consistency |

## 4. 汇总与交叉印证

- 各官独立输出（P0 → 战略 → P1 → P2/P3，含位置/问题/建议）
- **交叉印证**：同一问题被 ≥2 官命中 → 标注 ⭐（多视角确认 = 优先级高）
- 汇总进 `docs/review/REVIEW.md` 新增「全维度走查」区

## 5. 沉淀检查点 + 记录成本

同 `review.md` 第 6/7 步。

## 6. 首轮种子案例（校准用）

已发现并应在走查中校准的真实问题：
- **ui**：背面主页（Launcher）位置太靠上，容易误触手机顶部界面——触达区设计
- **ux**：图片预览选择后打字闪烁/加载失败——交互稳定性（已修，防复发）
- **product**：第三视角标签（问：/答：/图片记录：/【备注】）——第一原则（已修，防复发）
