---
title: 规则与机制层（.agents/）
description: AdaiOS 的规则与机制层入口——规范/边界/ADR/坑 · 流程（审查/收尾/节奏）· 工程与运维规则（guides/deployment）· 守卫脚本；边界判据＝按性质（回答「怎么做」的进本层）
version: 4
created: 2026-08-15
updated: 2026-10-04
status: active
lines: 77
depends-on:
  - frontmatter-spec.md
related:
  - ../AGENTS.md
  - ../docs/README.md
  - assets/_index.md
  - guides/_index.md
  - process/_index.md
  - state/_index.md
tags: [ai, meta, engineering]
---

# 规则与机制层（`.agents/`）

> **定位**：回答「**怎么做 / 禁止什么 / 谁在自动保障**」。代码工程（`services/` `apps/` `os/`）是**结果集**；本层是**驱动层**——怎么讨论、怎么定方案、怎么开发、怎么审核、怎么验收，以及一路沉淀的规范 / 边界 / 决策 / 坑。

> **边界判据（2026-10-04 起）**：由「**按读者**」（AI 读 / 人读）改为「**按性质**」——凡定义「怎么做 / 禁止什么」的**规则类**文档，一律归本层；其余（① 方向 / ② 事实 / ④ 记录 / 对外材料）归 [`docs/`](../docs/README.md)。完整判定表见 [`../docs/README.md`](../docs/README.md) §归属判定表。
>
> **一批**据此自 docs 区收归：`guides/`（7 份工程规则）· `deployment/`（5 份运维规则）· `assets/ai-context-engineering.md` + `assets/ai-calling-governance.md`（2 份协作规则）。
> **二批**判据收紧为「**AI 上下文运行时是否需要**」：新增 `direction/`（方向）· `reference/`（事实）· `features/`（功能主轴）· `records/`（活账本）· `rfc/`（决策）——**AI 运行需要的一切都在本容器**；`docs/` 自此为**档案馆**。

> **行业定位**：本层是 **Harness Engineering（护栏工程）**的个人落地实例——围绕自主 AI Agent 构建完整可控执行环境（标准化文档 + 强制约束 + 多层校验 + 反馈修正闭环）。**哲学定位：AI 上下文工程是驱动层，代码工程是结果集——上下文资产高于代码层。**

## 三层结构

| 层 | 内容 | 说明 |
|:---|:-----|:-----|
| `assets/` | 静态知识：规范 / 边界 / ADR / 已知坑 / **AI 上下文体系总览与中间层规范** | 回答「为什么这么定 / 别踩什么 / 边界在哪」|
| `process/` · `workflow/` · `guides/` · `deployment/` | 过程与规则：审查 / 收尾 / 节奏 · 单任务生命周期 · 工程规则 · 运维规则 | 回答「现在怎么做」|
| `state/` · `workspace/` | 动态真相：完成度 / 游标 / 在制品（指针化） | 回答「做到哪了」|

## 目录（19 个子目录）

| 目录 | 说明 |
|:--|:--|
| `assets/` | 规范 / 边界 / ADR / 已知坑 / **AI 上下文体系总览 + 中间层规范 + AI 调用治理** |
| `checklists/` || `direction/` | **① 方向**——理念（VISION）与路线蓝图（roadmap）· **每次会话首读** |
 检查清单（人也能用）：8 客观官 + 1 对抗官 + 3 外部视角官 + guard / cost / perf |
| `deployment/` | **运维与发布规则**——后端部署 · iOS 发布 · ICP / 公安联网备案 |
| `features/` | **② 事实·功能主轴**——一功能一行（ID/插件/状态/出处/欠着） |
| `guides/` | **工程与协作规则**——开发 · Git · 分支 · worktree · 固定动作 · 技能使用 |
| `records/` | **④ 记录（活账本）**——未修项 / 批次历史 / 待办（**开工读、收尾写**） |
| `guards/` | 守卫与自检脚本（13 个）：meta · align · feature · skills · structure · tools · unfixed · release · prod · roadmap · sediment · cost · context |
| `lib/` | 被复用的 shell 库（**不含业务判断**）|
| `method/` | 元方法层——怎么从零搭一套 AI 工程（给新项目用，不是日常流程）|
| `process/` | 流程定义：audit（走查）· review（深审）· ship（收尾）· cadence（节奏）· review-driven（需求→设计→编码主链）|
| `reference/` | **② 事实（按需查）**——契约 / 状态 / 手册 / 领域设计 |
| `rfc/` | **① 决策记录**——方案决策（要不要做 / 怎么做，含备选与理由） |
| `roles/` | 审查官定义（12 个，**subagent 的真相源**）|
| `scripts/` | 环境与注册脚本（换机 / 新工作区要跑的那些）|
| `skills/` | 建设与流程技能（工具 skill 出口直连这里）|
| `state/` | **本机状态**（不入 git）：协作游标 / 成本账 / 心跳缓存 |
| `tests/` | 守卫反例回归区（用坏样本证明守卫真的会抓）|
| `workflow/` | 单任务生命周期六节点（讨论 → 方案 → 开发 → 审核 → 验收）|

## 任何 AI 工具如何接入

0. **先跑开工自举**：`bash .agents/guards/ai-guard-context.sh` —— 输出状态 / 未修项 / 边界 / 坑 / 规范 / 待办 / 成本
1. 读本 `README.md`（定位）→ [`../AGENTS.md`](../AGENTS.md)（协作规则）→ `frontmatter-spec.md`（元数据契约）
2. **动工前查资产**：`assets/boundaries.md` + `assets/pitfalls.md` + `assets/conventions.md`
3. 开发：`workflow/`（讨论 → 方案 → 开发）
4. 收尾：`process/ship.md`
5. 审查：`process/audit.md`（全维度）或 `process/review.md`（增量），按 `roles/` 派官
6. 沉淀：决策入 `assets/adr/`，坑入 `assets/pitfalls.md`，结果更新 `state/`

> 工具侧入口（Claude / Qoder / DSH 的一行配置）在**工具自己的设置里**，不在本项目——换工具零迁移。接入状态用 `bash .agents/guards/ai-guard-tools.sh` 自检。
> **AI 资产的布局、多工具出口与维护规则**见 [`assets/ai-context-layer-spec.md`](assets/ai-context-layer-spec.md)；**体系总览**（L0–L3 + 工具层）见 [`assets/ai-context-engineering.md`](assets/ai-context-engineering.md)。

> **跨项目方法论**：本层是可复制的实例；通用骨架在 [`method/`](method/README.md)。
