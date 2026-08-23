---
title: ai-engineering 目录索引
description: AI 工程层目录治理——职责、文件清单、过期判断（文件自理机制）
version: 1
created: 2026-08-15
updated: 2026-08-23
status: active
lines: 73
depends-on: []
related: [frontmatter-spec.md]
tags: [ai, meta, index]
---

# ai-engineering 目录索引

**职责**：AdaiOS AI 工程层（工具无关）——资产 + 工作流 + 状态三层。新增 AI 工程类文档放此区。

## 文件清单

| 文件 | 职责 | 状态 |
|:-----|:-----|:----:|
| README.md | 入口：定位 + 接入指南 | active |
| frontmatter-spec.md | 文档元数据契约 | active |
| roles/product-arch.md | 产品架构师（全局/原则）| active |
| roles/ux-reviewer.md | 交互体验师（流程/异常）| active |
| roles/ui-reviewer.md | 界面设计师（视觉/触达）| active |
| roles/backend-reviewer.md | 后端代码官 | active |
| roles/frontend-reviewer.md | 前端代码官 | active |
| roles/docs-reviewer.md | 文档契约官 | active |
| roles/knowledge-reviewer.md | 知识数据官 | active |
| roles/context-reviewer.md | AI Context 审查官 | active |
| roles/adversarial-reviewer.md | 对抗找茬官（deep 默认附加，找炸点/骂点/边界漏）| active |
| skills/new-api.md | 建设技能：新建/修改 API（代码→契约→测试→门控闭环）| active |
| skills/new-domain.md | 建设技能：新增 Domain（RFC+六维→插件→数据流→落地）| active |
| skills/ship.md | 建设技能：/ship 收尾闭环（五件套→契约→登记→门禁→提交）| active |
| process/audit.md | 全维度走查流程 | active |
| process/review.md | 增量深审流程 | active |
| process/ship.md | 收尾闭环流程（guard-meta + guard-align 门禁）| active |
| guard-meta.sh | 元治理自检（frontmatter 图谱/lines/孤儿/正文路径，`--fix` 回写）| active |
| guard-roadmap.sh | 规划状态对拍（roadmap 体检 + 漂移检查）| active |
| guard-unfixed.sh | 未修复问题总清单（REVIEW/task-log/audits 四源聚合 + 对账）| active |
| guard-tools.sh | 工具接入自检（T1 hook/T2 快照/T3 技能/T4 注册/T5 入口，跨工具互通可验证）| active |
| guard-align.sh | 文档自动对齐（端点/测试数，pre-commit 触发）| active |
| method/_index.md | 方法论层（切入点图谱/流水线/脚手架）| active |
| guard-context.sh | 任务上下文注入（开工前清单，进攻侧）| active |
| guard-sediment.sh | 沉淀检查（坑/ADR/出表/登记，进攻侧②③）| active |
| guard-cost.sh | 成本监控（读 DSH 会话日志按天/会话算钱，防守侧）| active |
| deploy-gate.sh | 部署门禁+smoke（触发侧，最硬闸门）| active |
| weekly-audit.sh | 每周审查（cron，防休眠）| active |
| checklists/review-ux.md | 交互检查清单 | active |
| checklists/review-ui.md | 界面检查清单 | active |
| checklists/review-product.md | 产品架构检查清单 | active |
| checklists/review-context.md | AI Context 检查清单 | active |
| checklists/review-backend.md | 后端代码检查清单 | active |
| checklists/review-frontend.md | 前端代码检查清单 | active |
| checklists/review-docs.md | 文档契约检查清单 | active |
| checklists/review-knowledge.md | 知识数据检查清单 | active |
| checklists/review-perf.md | 加载性能专项（阶段 A→F 快查）| active |
| checklists/guard.md | 守护检查清单（G1-G7）| active |
| checklists/cost.md | 成本纪律（烧钱动作清单 + 省钱原则 + 盯账）| active |
| assets/_index.md | 资产层索引（规范/边界/ADR/坑）| active |
| assets/skills-spec.md | 技能包规范（SKILL.md 融合规则：name + 10 字段、五段结构、新增流程）| active |
| workflow/_index.md | 工作流层索引（讨论→方案→开发→审核→验收）| active |
| state/_index.md | 状态层索引（指针化真相源）| active |
| assets/adr/ADR-001.md | AI 工程层为一等公民 | accepted |
| assets/adr/ADR-002.md | 单一事实源 | accepted |
| assets/adr/ADR-003.md | Domain=插件模型 | accepted |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- 新增角色/流程：补本索引 + frontmatter
