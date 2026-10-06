---
title: direction/ 目录索引
description: .agents/direction/ 的文件清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-04
updated: 2026-10-06
status: active
lines: 103
depends-on: []
related: [./_directory.md]
tags: [meta, index, direction]
---

# direction/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

**职责**：**① 方向**——回答「**为什么做 · 往哪走**」。本区是 AI 每次会话的**首读**（`AGENTS.md` 规则 1）。

## 文件清单（70 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `VISION.md` | 项目愿景与理念（**唯一理念真相源**）——Personal AI OS 的定位、五层产品架构、Context … | active |
| `product-roadmap.md` | 🚩 **产品路线唯一蓝图**——路线驱动开发：版本目标、里程碑与状态；从这里拆任务、确认目标（v1.0.0 为当… | active |
| `rfc/20260718-context-memory-knowledge-loop.md` | — | implemented |
| `rfc/20260720-context-architecture.md` | — | implemented  # 最后更新：2026-07-20 |
| `rfc/20260721-ai-chat-quality.md` | — | implemented |
| `rfc/20260722-dual-world.md` | — | implemented |
| `rfc/20260722-features-memory-timeline-search.md` | — | implemented |
| `rfc/20260722-identity-page.md` | — | implemented |
| `rfc/20260723-launcher-polish.md` | — | implemented |
| `rfc/20260723-tagcloud-gesture.md` | — | implemented |
| `rfc/20260725-frontend-project-trading-pages.md` | — | implemented |
| `rfc/20260725-layer6-knowledge-feedback-loop.md` | — | implemented |
| `rfc/20260725-life-project-os-skeleton.md` | — | implemented |
| `rfc/20260727-memory-upgrade.md` | — | revised |
| `rfc/20260728-project-development-suggestions.md` | — | draft |
| `rfc/20260729-development-retrospective.md` | — | completed |
| `rfc/20260730-health-management-scenario.md` | — | draft |
| `rfc/20260730-market-data-and-push.md` | — | implemented（Phase 1 ✅ / Phase 2 ✅ 2026-08-06） |
| `rfc/20260801-memory-system-evolution.md` | — | implemented |
| `rfc/20260801-release-versioning.md` | — | accepted |
| `rfc/20260801-review-skill.md` | — | implemented |
| `rfc/20260802-adai-admin.md` | — | approved |
| `rfc/20260802-multi-account-prep.md` | — | implemented |
| `rfc/20260802-multimodal-image-glm.md` | — | implemented |
| `rfc/20260813-record-task-and-sports-analysis.md` | — | implemented |
| `rfc/20260814-domain-plugin-model.md` | — | approved |
| `rfc/20260815-ai-engineering-layer.md` | — | approved |
| `rfc/20260815-docs-governance.md` | — | approved |
| `rfc/20260815-image-chat-interaction.md` | adai-app 带图交流模块交互设计——发图=一次完整"说"，AI 判定 log/ask 分流，ask 直进对… | approved |
| `rfc/20260815-media-event-unification.md` | — | implemented |
| `rfc/20260816-framework-plus-plugin-model.md` | — | approved |
| `rfc/20260816-trading-agent-plugin-model.md` | — | approved |
| `rfc/20260816-trading-data-intelligence.md` | — | draft |
| `rfc/20260816-trading-data-model.md` | — | draft |
| `rfc/20260816-trading-os-engine.md` | — | draft |
| `rfc/20260816-trading-session-push.md` | — | draft |
| `rfc/20260817-trading-push-image-trade-log.md` | — | approved |
| `rfc/20260822-memory-plugin-isolation.md` | — | draft |
| `rfc/20260822-trading-trade-time-review.md` | — | approved |
| `rfc/20260823-trading-history-tab-backfill.md` | — | implemented |
| `rfc/20260824-trading-company-insight.md` | — | draft |
| `rfc/20260825-trading-lot-tracking-behavior.md` | — | implemented |
| `rfc/20260830-trading-perfect-case-library.md` | 用用户标注的「完美买点」真实案例（日 K + 指标画面）沉淀结构化案例库，让 AI 从案例理解买点概念，最终产出… | draft |
| `rfc/20260901-auth-login.md` | 账号密码登录 + 服务端会话，userId 由会话推导、客户端伪造无效；Controller 层 92 处 X-… | implemented |
| `rfc/20260902-trading-memory-positioning.md` | 取代 RFC 20260815「建议是目的」定位——交易插件 = 记忆内核在交易域的投影：历史成交沉淀为对自己的… | approved |
| `rfc/20260905-trading-cognition-layer.md` | 把 RFC 20260902 定义的「⑤认知层（缺口）」落地为两层：A 个人交易画像（客观系统推导 + 主观情绪… | approved |
| `rfc/20260909-trading-clearance-derivation.md` | 把「清仓股 sold.json」从纯镜像（只认清仓股导出）升级为双轨：历史成交/手动成交把某股卖到 0 且买卖均… | approved |
| `rfc/20260912-learn-product-digest.md` | — | approved |
| `rfc/20260912-trading-ledger-integrity.md` | 交易持仓/现金的三条真源（券商快照 / 逐笔流水 / 派生持仓）在生产实测互斥的根治契约——锚点快照 fail-… | implemented |
| `rfc/20260914-ios-share-extension.md` | — | approved |
| `rfc/20260914-login-credential-experience.md` | 现状是「账号+密码+30 天滑动会话」，登录页无 autofill 语义、token 存 NSUserDefau… | approved |
| `rfc/20260915-share-extension-credentials.md` | 现状是分享扩展的限权令牌必须由用户在学习页手点一次「签发」才写进 App Group 容器，入口还是一个无提示的… | draft |
| `rfc/20260916-first-meeting.md` | 用户「明天见人」前的体检发现三件事：①「学习」插件在 admin 后台根本无法勾选（前端硬编码漏项，后端早已就绪… | implemented |
| `rfc/20260917-learn-representation.md` | 把「一键分享 → 阿呆消化 → 更懂你的产物 → 积累」这条链路打通。核心三件：① 门控拆分 B（接收是基础能力… | approved |
| `rfc/20260917-todo-kernel-retire-project-plugin.md` | 用户在生产里问「任务模块的作用是干嘛的、项目插件的作用」，调研钉出三件事：①同一个「任务」，前端/文档/用户拍板… | implemented |
| `rfc/20260918-trading-app-restructure.md` | 用户「目前交易 app UI 和交互不咋地」触发的专项审查（ux-visual-reviewer + ux-in… | draft |
| `rfc/20260922-trading-decision-copilot.md` | 用户 2026-09-21 亲述交易插件的用法与不满（「需要阿呆提醒我，给我意见，尤其给我铁证，通过我之前的操作… | approved |
| `rfc/20260923-market-data-resilience.md` | 2026-09-22 深夜生产实测三条 K 线来源同时失效（腾讯 K 线域名被 WAF 拦 501 · 东财长期… | implemented |
| `rfc/20260923-rhythm-and-memory-temporality.md` | 用户 2026-09-23 反馈「阿呆 App 概览卡片，天天提醒我」。根因钉到三处：① `RecordToTo… | approved |
| `rfc/20260924-trading-app-form.md` | 用户 2026-09-24「我对 app 端的交易插件还是不满意，web 端目前还行」，并以 Shopify 移… | draft |
| `rfc/20260928-market-source-consolidation.md` | 2026-09-28 逐源实测（腾讯 quote 200 · 腾讯 K 线新域名 200 · 新浪 200 · … | approved |
| `rfc/20260929-context-engineering-batch1-design.md` | 主方案 `20260929-conversation-context-engineering.md` 的**批 … | draft |
| `rfc/20260929-conversation-context-engineering.md` | 用户 2026-09-29 反馈「对话模式会丢失上下文」，自查后落成此件。本件不只修 bug：把「对话模式」的上… | draft |
| `rfc/20261001-feature-index-and-authoring-gate.md` | 2026-10-01 用户提出「补齐需求/设计文档，每个过程安排角色（编写+审核对抗），能从文档树上看每个功能」… | approved |
| `rfc/20261003-project-level-ai-context-layer.md` | 目标（用户 2026-10-03）＝一个中间层：项目级 AI 上下文（AGENTS.md / skill / s… | draft |
| `rfc/20261003-skill-conformance-and-supply-chain.md` | 2026-10-03 行业调研（AGENTS.md / Agent Skills / MCP 现状）结论：技能包… | draft |
| `rfc/20261003-trading-cash-position-linkage.md` | 用户 2026-10-03「资金和持仓是强关联的——转入后有现金才能买，卖出后才有现金才能转出」的账本契约。把「… | draft |
| `rfc/20261003-trading-plan-and-review-loop.md` | 用户 2026-10-03「我要整理第二天的操作规则（买和卖），到时候结合起来提醒我；必须像开公司一样认真对待每… | draft |
| `rfc/20261006-knowledge-reflow.md` | 落地 `ai-context-engineering` §4.5「一次知识回流（对话 → L2）」的设计（该节自… | draft |
| `rfc/20261006-trading-positioning-expansion.md` | 承接 RFC 20260902「交易记忆」定位的增量提案——把 trading 从 owner 专属扩为「人人可… | approved |

## 过期判断

- `status != active` → 候选清理
- **蓝图是"活"的**：与实现产生漂移时由 `ai-guard-roadmap` 报出（不靠人记得）
- 新增文件 → 补本索引 + 跑 `bash .agents/mechanism/guards/ai-guard-structure.sh --fix`

## 来源

**2026-10-04 二批**（判据＝「AI 上下文运行时是否需要」）：自 docs 区收归（原 VISION 与 product-roadmap，位置见本目录）——它们是 AI **每次会话必读**的方向锚点。
