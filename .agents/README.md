---
title: 规则与机制层（.agents/）
description: AdaiOS 的规则与机制层入口——规范/边界/ADR/坑 · 流程（审查/收尾/节奏/审核驱动主链）· 工程与运维规则（guides/deployment）· 守卫脚本；边界判据＝按性质（回答「怎么做」的进本层）
version: 5
created: 2026-08-15
updated: 2026-10-06
status: active
lines: 84
depends-on:
  - frontmatter-spec.md
related:
  - ../AGENTS.md
  - ../docs/README.md
  - rules/assets/_index.md
  - rules/guides/_index.md
  - rules/process/_index.md
  - records/state/_index.md
tags: [ai, meta, engineering]
---

# 规则与机制层（`.agents/`）

> **定位**：回答「**怎么做 / 禁止什么 / 谁在自动保障**」。代码工程（`services/` `apps/` `os/`）是**结果集**；本层是**驱动层**——怎么讨论、怎么定方案、怎么开发、怎么审核、怎么验收，以及一路沉淀的规范 / 边界 / 决策 / 坑。

> **边界判据（2026-10-04 起）**：由「**按读者**」（AI 读 / 人读）改为「**按性质**」——凡定义「怎么做 / 禁止什么」的**规则类**文档，一律归本层；其余（① 方向 / ② 事实 / ④ 记录 / 对外材料）归 [`docs/`](../docs/README.md)。完整判定表见 [`../docs/README.md`](../docs/README.md) §归属判定表。
>
> **一批**据此自 docs 区收归：`rules/guides/`（7 份工程规则）· `rules/deployment/`（5 份运维规则）· `rules/assets/ai-context-engineering.md` + `rules/assets/ai-calling-governance.md`（2 份协作规则）。
> **二批**判据收紧为「**AI 上下文运行时是否需要**」：收成 `direction/` · `knowledge/` · `rules/` · `records/` · `toolkit/` · `mechanism/` 六顶层（2026-10-04）——**AI 运行需要的一切都在本容器**；`docs/` 自此为**档案馆**。
> **三批**（2026-10-04 当天）：`workspace/`（在制品）**从 `records/` 提为独立顶层** ⇒ **7 个顶层**。

> **行业定位**：本层是 **Harness Engineering（护栏工程）**的个人落地实例——围绕自主 AI Agent 构建完整可控执行环境（标准化文档 + 强制约束 + 多层校验 + 反馈修正闭环）。**哲学定位：AI 上下文工程是驱动层，代码工程是结果集——上下文资产高于代码层。**

## 顶层结构（**7 个**）

> **层级**：`.agents/<顶层>/<子目录>/`。顶层按**知识性质**分；每个顶层都有 `_index.md` + `_directory.md`（**两件套**，由 `ai-guard-structure` 双向校验）。

| 顶层 | 类 | 装什么 |
|:--|:--:|:--|
| `direction/` | ① 方向与决策 | VISION · roadmap · `rfc/`（**67** 份方案决策）|
| `knowledge/` | ② 事实 | `reference/`（契约 / 状态 / 手册 / 设计）· `features/`（功能主轴）|
| `rules/` | ③ 规则 | `assets/`（规矩）· `guides/`（操作）· `deployment/`（运维）· `process/`（流程）· `workflow/`（生命周期）· `method/`（元方法）|
| `records/` | ④ 记录 | 活账本（`REVIEW` / `change-log` / `task-log`）· `state/`（**本机状态，不入 git**）|
| `workspace/` | ④ 记录（**在制品**）| **会变空的在制品容器**——一条分支/任务一个目录（`_templates/` 模板 · `_meta/` 跨任务）|
| `toolkit/` | ⑤ AI 能力 | `roles/`（**15 个角色**）· `skills/`（建设技能）· `checklists/`（与角色一一对应，人也能用）|
| `mechanism/` | ⑤ 执行机制 | `guards/`（守卫 **15** 个 · 含 `tests/`）· `scripts/`（执行器 · 含 `lib/`）|

> **`workspace/` 为什么单列**：它与 `records/` **性质相反**——`records/` 是**只追加的历史账本**，
> `workspace/` 是**会清空的在制品**；混在一起会掩盖「这里应该有活」的信号（2026-10-04 用户拍板）。

### 顶层内的关键子目录

| 子目录 | 说明 |
|:--|:--|
| `deployment/` | **运维与发布规则**——后端部署 · iOS 发布 · ICP / 公安联网备案 |
| `features/` | **② 事实·功能主轴**——一功能一行（ID / 插件 / 状态 / 出处 / 欠着）|
| `guides/` | **工程与协作规则**——开发 · Git · 分支 · worktree · 固定动作 · 技能使用 |
| `guards/` | 守卫与自检脚本（15 个）：**health（健康总检）** · meta · align · feature · skills · structure · tools · unfixed · release · prod · roadmap · sediment · cost · context · guard（产品侧）|
| `lib/` | 被复用的 shell 库（**不含业务判断**）|
| `method/` | 元方法层——怎么从零搭一套 AI 工程（给新项目用，不是日常流程）|
| `process/` | 流程定义：audit（走查）· review（深审）· ship（收尾）· cadence（节奏）· **review-driven（需求 → 设计 → 编码主链）** |
| `reference/` | **② 事实（按需查）**——契约 / 状态 / 手册 / 领域设计 |
| `rfc/` | **① 决策记录**——方案决策（要不要做 / 怎么做，含备选与理由）|
| `roles/` | **15 个角色定义**（**subagent 的真相源**）：产作者 1 · 审核者 13（需求评审 1 + 域客观官 8 + 对抗官 1 + 外部视角 3）· 流程官 1 —— 关系见 `process/review-driven.md` **§0** |
| `scripts/` | 环境与注册脚本（换机 / 新工作区要跑的那些）|
| `skills/` | 建设与流程技能（工具 skill 出口直连这里）|
| `state/` | **本机状态**（不入 git）：协作游标 / 成本账 / 心跳缓存 |
| `tests/` | 守卫反例回归区（用坏样本证明守卫真的会抓）|
| `workflow/` | 单任务生命周期六节点（讨论 → 方案 → 开发 → 审核 → 验收）|

## 任何 AI 工具如何接入

0. **先跑开工自举**：`bash .agents/mechanism/guards/ai-guard-context.sh` —— 输出状态 / 未修项 / 边界 / 坑 / 规范 / 待办 / 成本
1. 读本 `README.md`（定位）→ [`../AGENTS.md`](../AGENTS.md)（协作规则）→ [`frontmatter-spec.md`](frontmatter-spec.md)（元数据契约）
2. **动工前查资产**：`rules/assets/boundaries.md` + `rules/assets/pitfalls.md` + `rules/assets/conventions.md`
3. 开发：`rules/workflow/`（讨论 → 方案 → 开发）
4. 收尾：`rules/process/ship.md`
5. 审查：`rules/process/audit.md`（全维度走查）或 `rules/process/review.md`（增量深审）；
   **审核驱动主链**（需求 → 设计 → 编码）见 `rules/process/review-driven.md` —— **角色全景与派官关系在它的 §0**，按 `toolkit/roles/` 派官
6. 沉淀：决策入 `rules/assets/adr/`，坑入 `rules/assets/pitfalls.md`，结果更新 `records/state/`

> 工具侧入口（Claude / Qoder / DSH 的一行配置）在**工具自己的设置里**，不在本项目——换工具零迁移。接入状态用 `bash .agents/mechanism/guards/ai-guard-tools.sh` 自检。
> **AI 资产的布局、多工具出口与维护规则**见 [`rules/assets/ai-context-layer-spec.md`](rules/assets/ai-context-layer-spec.md)；**体系总览**（L0–L3 + 工具层）见 [`rules/assets/ai-context-engineering.md`](rules/assets/ai-context-engineering.md)。

> **跨项目方法论**：本层是可复制的实例；通用骨架在 [`rules/method/`](rules/method/README.md)。
