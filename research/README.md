# 研究区（Research）

> **定位：外部/历史 AI Context 与 AI 软件工程理念研究。仅供借鉴，不参与 AdaiOS 主流程**（`/review`、`/ship`、`docs/README.md` 索引均不包含）。
> 来源：2026-08-15 从 `docs/inbox/` 移出（外部项目研究 + 未定型框架，与 AdaiOS 自身文档分离）。

## 与 AdaiOS 的关系（理念验证）

这些研究文档不是 AdaiOS 的知识资产，但其方法论与 AdaiOS 的实践**互相验证**：

| 研究理念 | AdaiOS 对应实践 |
|:---------|:----------------|
| 决策/事实分离（`.ai` 管约束、`.qoder` 管事实）| CLAUDE.md/RFC/REVIEW = 决策约束；`os/*/11-context` + `data/` = 事实 |
| L1/L2/L3 分层加载（任务层/核心层/参考层，禁止全量灌入）| CLAUDE.md「当前焦点批」(L1) → 主体 (L2) → 相关文档 (L3) |
| 一个知识只有一个权威来源 | `docs/reference/status.md`（测试数/端点唯一真相）|
| Context Router（按任务自动加载相关上下文）| `ContextEngine.compose()`（标签关联 + 搜索 + 记忆回读 + domain 场景）|
| 架构先冻结，真实任务验证 | 2026-08-15 用户决策：先治理流程，v1.0.0 发布顺延 |

## 目录

| 文件 | 说明 |
|:-----|:-----|
| `AI_Native_Software_Workspace_Architecture_v0.1.md` | AI 原生软件工程工作区架构（单项目 Context → Workspace 多项目 → 团队 Git 共享）|
| `AI4SE 项目级知识库建设方案.md` | 保险行业多模块项目 AI 知识库建设（5 基线 + 6 补充维度）|
| `项目级 AI 上下文建设哲学与体系原理.md` | `.ai`/`.qoder` 体系提炼（决策/事实分离 + L1/L2/L3 + 硬卡点）|
| `当前 AI Context 体系下一步建议.md` | 架构冻结 + Context Router 方向 |
| `context-reviewer.md` | AI Context 审查框架（已收编为 `.claude/agents/review-context.md`，本文件为原始材料）|
| `ai-native/` | AI Native Engineering Framework v1.0 系列（01-06 + README，旧框架研究）|
| `ai-native-team-framework.md` | 团队框架 Draft（与 ai-native/03 同源）|
| `AI_CONTEXT_STRUCTURE.md` | AI Context 体系早期结构（未定型）|
| `ai-context-design.md` | AI Context 设计（与 AI_CONTEXT_STRUCTURE 同源）|
