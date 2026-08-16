---
title: docs/rfc 目录索引
description: 决策记录区目录治理——RFC 清单 + 状态（draft/approved/implemented），过期判断（文件自理机制）
version: 1
created: 2026-08-15
updated: 2026-08-16
status: active
lines: 64
depends-on: []
related:
  - ../_index.md
tags: [meta, index, rfc]
---

# docs/rfc 目录索引

**职责**：AdaiOS 决策记录区（RFC）。每个 RFC 有 frontmatter（title/date/status/decided-by），状态驱动后续会话判断「是否已决策」。

## 文件清单

| 文件 | 职责 | 状态 |
|:-----|:-----|:----:|
| 20260718-context-memory-knowledge-loop.md | Context 闭环 — 记忆回读 + 知识召回 | implemented |
| 20260720-context-architecture.md | Context 架构重构：三层上下文 + 标签索引 + 记录晋升 | implemented |
| 20260721-ai-chat-quality.md | AI 对话质量修复：从分析模式到对话模式 | implemented |
| 20260722-dual-world.md | 双主页（Dual World） | implemented |
| 20260722-features-memory-timeline-search.md | 记忆页 + 时间线页 + 搜索 | implemented |
| 20260722-identity-page.md | 身份页（Identity Page） | implemented |
| 20260723-launcher-polish.md | 导航幽默化 + 标签云点击 + 色彩 | implemented |
| 20260723-tagcloud-gesture.md | 标签图谱 + 双视图切换 | implemented |
| 20260725-frontend-project-trading-pages.md | 前端项目状态页 + 交易页 | implemented |
| 20260725-layer6-knowledge-feedback-loop.md | Layer 6 知识反哺闭环 | implemented |
| 20260725-life-project-os-skeleton.md | Life OS + Project OS 骨架 | implemented |
| 20260726-project-status-and-roadmap.md | AdaiOS 项目现状与三大方向规划 | superseded |
| 20260727-memory-upgrade.md | Memory 升级路线 — 从复读机到真记忆 | revised |
| 20260728-project-development-suggestions.md | AdaiOS 项目发展建议 | draft |
| 20260729-development-retrospective.md | 开发复盘：近期 Bug 反复的根因分析 | completed |
| 20260730-health-management-scenario.md | 20260730-health-management-scenario.md | active |
| 20260730-market-data-and-push.md | Layer 5 行情接入与主动推送 MVP | implemented（Phase |
| 20260801-memory-system-evolution.md | 记忆系统进化 — 元记忆对比与落地方案 | implemented |
| 20260801-release-versioning.md | 产品发布版本机制（Release Versioning） | accepted |
| 20260801-review-skill.md | 审核流程 Skill 化（Review Skill） | implemented |
| 20260802-adai-admin.md | adai-admin 管理后台规划 | approved |
| 20260802-multi-account-prep.md | 多账号架构预留 — 数据路径 userId 分层（v1.0.0 前置） | implemented |
| 20260802-multimodal-image-glm.md | 多模态图片记录（GLM-VLM）— 图片 → 文本闭环（L4） | implemented |
| 20260813-record-task-and-sports-analysis.md | 记录↔任务关联 + 相机动作分析（想法升级 RFC） | implemented |
| 20260814-domain-plugin-model.md | Domain=插件模型（Kernel 基础服务 / Domain 受控插件） | approved |
| 20260815-docs-governance.md | 文档治理——瘦身 + 单一事实源（先于功能开发） | approved |
| 20260815-trading-interaction-redesign.md | 交易模块交互重设计（app 说人话 / web 详细管理）| draft |
| 20260816-trading-os-engine.md | trading-engine 领域引擎化（从插件到独立可复用的交易引擎）| draft |
| 20260816-trading-data-model.md | 交易数据模型分层（用户提供 vs 可查询，trading domain 可执行化）| draft |
| 20260815-ai-engineering-layer.md | AI 工程层——从「文档子目录」到「一等公民」（草案）| draft |
| 20260815-media-event-unification.md | 图文一体——媒体事件数据层统一（一次输入 = 一条记录） | approved |
| 20260815-image-chat-interaction.md | 带图交流——发图即对话（交互方案：AI 判定 log/ask 分流，ask 直进对话态） | draft |
| 20260816-framework-plus-plugin-model.md | 框架+插件——AdaiOS 形态总纲（决策记录，已提升为正式架构文档 `architecture/framework-plus-plugin-model.md`） | approved |
| 20260816-trading-agent-plugin-model.md | 交易 Agent 三阶段插件模型（裸问答 → +行情插件 → +规则插件，能力按用户叠加） | approved |
| 20260816-trading-session-push.md | 交易时段节奏推送——早盘计划/午间跟踪/尾盘建议 + 微信渠道（PushChannel 插件化）| draft |
| 20260816-trading-data-intelligence.md | 交易数据智能——自选股买点提示 + 清仓复盘闭环 + 打分系统（K线为核，B1 完美图参照系）| draft |

## 过期判断

- `status: draft` 长期未决策 → 候选清理（或催决策）
- `status != implemented` 且 `updated` 超 3 个月 → 候选归档
- 新增 RFC：补本索引 + frontmatter（title/date/status）
