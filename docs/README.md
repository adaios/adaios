# 文档索引

> AdaiOS 项目文档。按"必读 → 架构 → 功能 → API → 决策 → 部署"分层组织。

---

## ⚡ 必读

| 文档 | 说明 |
|:-----|:------|
| [VISION.md](VISION.md) | 项目愿景、五层产品架构、工程原则。**每个 AI 会话首先阅读。** |
| [product-roadmap.md](architecture/product-roadmap.md) | 🚩 **产品路线 v1.0.0**——唯一蓝图，路线驱动开发：从这里拆任务、确认目标 |

## 🏗 架构

| 文档 | 说明 |
|:-----|:------|
| [product-architecture.md](architecture/product-architecture.md) | 五层产品架构详解（Layer 1-6） |
| [system-architecture.md](architecture/system-architecture.md) | 系统架构、Kernel/Domain 分层、Context Engine |
| [memory-os-design.md](architecture/memory-os-design.md) | Memory OS 设计规约：职责、数据模型与 Context Engine / Domain OS 的关系 |

## 🔧 功能手册

| 文档 | 说明 |
|:-----|:------|
| [project-os-usage.md](guides/project-os-usage.md) | 📌 Project OS 使用指南：输入框问"项目阿呆"、任务管理、场景示例 |

## 🎨 前端参考

| 文档 | 说明 |
|:-----|:------|
| [frontend-reference.md](architecture/frontend-reference.md) | 前端统一参考：UI 术语对照 + 布局视觉参考（ASCII 图） |
| [frontend-ui-reference.md](../apps/adai-app/UI_REFERENCE.md) | 📌 UI 元素精确对照：每个按钮→代码行 |

## 📋 API 契约

| 文档 | 说明 |
|:-----|:------|
| [api-spec.md](architecture/api-spec.md) | 前后端接口契约。**所有 API 的定义、请求/响应结构在此。** |

## 🎯 决策记录 (RFC)

| 文档 | 日期 | 说明 |
|:-----|:-----|:------|
| [20260718-context-memory-knowledge-loop.md](rfc/20260718-context-memory-knowledge-loop.md) | 2026-07-18 | Context 闭环：记忆回读 + 知识召回 |
| [20260720-context-architecture.md](rfc/20260720-context-architecture.md) | 2026-07-20 | Context 架构设计 |
| [20260721-ai-chat-quality.md](rfc/20260721-ai-chat-quality.md) | 2026-07-21 | AI 对话质量修复：从分析模式到对话模式 |
| [20260722-dual-world.md](rfc/20260722-dual-world.md) | 2026-07-22 | 双主页（Dual World）设计 |
| [20260722-features-memory-timeline-search.md](rfc/20260722-features-memory-timeline-search.md) | 2026-07-22 | 记忆页 + 时间线页 + 搜索设计 |
| [20260722-identity-page.md](rfc/20260722-identity-page.md) | 2026-07-22 | 身份页设计 |
| [20260723-launcher-polish.md](rfc/20260723-launcher-polish.md) | 2026-07-23 | 启动器打磨 |
| [20260723-tagcloud-gesture.md](rfc/20260723-tagcloud-gesture.md) | 2026-07-23 | 标签云手势交互 |
| [20260725-frontend-project-trading-pages.md](rfc/20260725-frontend-project-trading-pages.md) | 2026-07-25 | 前端项目状态页 + 交易页 |
| [20260725-layer6-knowledge-feedback-loop.md](rfc/20260725-layer6-knowledge-feedback-loop.md) | 2026-07-25 | Layer 6 知识反哺闭环 |
| [20260725-life-project-os-skeleton.md](rfc/20260725-life-project-os-skeleton.md) | 2026-07-25 | Life OS + Project OS 骨架 |
| [20260726-project-status-and-roadmap.md](rfc/20260726-project-status-and-roadmap.md) | 2026-07-26 | 项目状态与路线 |
| [20260727-memory-upgrade.md](rfc/20260727-memory-upgrade.md) | 2026-07-27 | Memory 升级路线：Phase 0-4，从复读机到真记忆 |
| [20260728-project-development-suggestions.md](rfc/20260728-project-development-suggestions.md) | 2026-07-28 | 项目发展建议：产品/前端/UI 三方视角 |
| [20260730-market-data-and-push.md](rfc/20260730-market-data-and-push.md) | 2026-07-30 | Layer 5 行情接入 + 主动推送 MVP 设计 |
| [20260801-review-skill.md](rfc/20260801-review-skill.md) | 2026-08-01 | 审核流程 Skill 化：/review + 5 角色 + 检查点清单 |
| [20260801-memory-system-evolution.md](rfc/20260801-memory-system-evolution.md) | 2026-08-01 | 记忆系统进化：元记忆对比 + 主题合并/actionable 闭环落地方案 |
| [20260801-release-versioning.md](rfc/20260801-release-versioning.md) | 2026-08-01 | 产品发布版本机制：版本号规则、发布流程、Release Notes、v0.1.0 规划 |
| [20260802-adai-admin.md](rfc/20260802-adai-admin.md) | 2026-08-02 | adai-admin 管理后台规划（草案）：数据/系统/知识管理范围 + 技术选型候选 |
| [20260802-multi-account-prep.md](rfc/20260802-multi-account-prep.md) | 2026-08-02 | 多账号架构预留：数据路径 userId 分层 + 全链路透传 + 数据迁移 |

> 早期决策 `20260726-next-phase-direction.md` 已归档至 `docs/inbox/`（见 📥 收件箱）

## 💡 想法归档

| 文档 | 说明 |
|:-----|:------|
| [ideas/](ideas/) | 未定型但有价值的想法/方案（ai-terms 文稿等，含归档规则）|

## 🔍 审核

| 文档 | 说明 |
|:-----|:------|
| [REVIEW.md](review/REVIEW.md) | 📌 审核全量状态报告（常驻，`/review` 更新）。未修复项滚动保留 |
| [checklists/](review/checklists/) | 检查点清单（活文档）：guard 守护 + docs/backend/frontend/product/knowledge 五角色 |

## 🐛 问题跟踪

| 文档 | 说明 |
|:-----|:------|
| [issue-log.md](reference/issue-log.md) | 项目级问题清单（唯一问题记录）|

## 📚 参考文档

| 文档 | 说明 |
|:-----|:------|
| [feature-reference.md](reference/feature-reference.md) | 功能参考文档（唯一功能真相源） |
| [task-log.md](reference/task-log.md) | 任务开发文档（从产品路线拆任务）|

## 🚀 部署

| 文档 | 说明 |
|:-----|:------|
| [backend-deployment.md](deployment/backend-deployment.md) | 后端部署指南 |

## 📥 收件箱（inbox）

| 文档 | 说明 |
|:-----|:------|
| [inbox/](inbox/) | 待处理/待归档文档：影响结构或与主文档重复的文件，保留可追溯，定期清理 |

---

**文档版本：v2.0 | 最后更新：2026-08-02**
