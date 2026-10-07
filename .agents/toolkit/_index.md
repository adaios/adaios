---
title: toolkit/ 目录索引
description: .agents/toolkit/ 的文件清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-04
updated: 2026-10-07
status: active
lines: 74
depends-on: []
related: [./_directory.md]
tags: [meta, index]
---

# toolkit/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

**职责**：见 [`_directory.md`](./_directory.md)（此处不重复——S5 判据：同一知识只在一处详述）

## 文件清单（38 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `checklists/ai-context-health-reviewer.md` | ai-context-health-reviewer 的逐条判据 H1–H8——全部是机械守卫覆盖不到的叙事层；… | active |
| `checklists/ai-context-reviewer.md` | ai-context-reviewer 逐条检查项（人也能用）——Purpose/Trigger/Action/… | active |
| `checklists/ai-cost-checklist.md` | DeepSeek 峰谷定价后的烧钱动作清单 + 省钱原则——DSH/后端/日常开发哪些操作会烧钱、怎么省、怎么盯… | active |
| `checklists/ai-guard-checklist.md` | 每次 /review 必跑的 G1-G10 防 P0 复发清单（数据丢失/契约破坏/口径漂移），执行器为 .ag… | active |
| `checklists/code-backend-reviewer.md` | code-backend-reviewer 逐条检查项（人也能用）——数据流水线/存储健壮性/分层/AI 集成/… | active |
| `checklists/code-frontend-reviewer.md` | code-frontend-reviewer 逐条检查项（人也能用）——DTO 契约/生命周期/状态管理/测试 | active |
| `checklists/code-perf-reviewer.md` | app/web/admin 加载慢时的轻量快查——按加载链路分阶段逐条核对，15 分钟出结果（AI 触发 /pe… | active |
| `checklists/data-knowledge-reviewer.md` | data-knowledge-reviewer 逐条检查项（人也能用）——os/ 消费链路/data/ 健康/隐… | active |
| `checklists/docs-contract-reviewer.md` | docs-contract-reviewer 逐条检查项（人也能用）——契约真相源/RFC 决策漂移/文档资产健… | active |
| `checklists/docs-product-reviewer.md` | docs-product-reviewer 逐条检查项（人也能用）——五层架构/数据流/Roadmap/原则/功… | active |
| `checklists/docs-requirement-reviewer.md` | docs-requirement-reviewer 逐条检查项（人也能用）——13 条需求质量判据（含 desi… | active |
| `checklists/process-reviewer.md` | process-reviewer 逐条检查项（人也能用）——把「做事的方式」逐条对：可观测性、交付判据、轮次纪律… | active |
| `checklists/ux-interaction-reviewer.md` | ux-interaction-reviewer 逐条检查项（人也能用）——操作路径/状态机/异常流/反馈/跨端/… | active |
| `checklists/ux-social-reviewer.md` | ux-social-reviewer 逐条检查项（人也能用）——通知暴露面/递手机/付出门槛/人情/退出口/体面 | active |
| `checklists/ux-stranger-reviewer.md` | ux-stranger-reviewer 逐条检查项（人也能用）——三端入口可达/开号/空世界首屏/第一件事/五… | active |
| `checklists/ux-support-reviewer.md` | ux-support-reviewer 逐条检查项（人也能用）——五类必问问题 + 归属判定，答不上来的即缺陷 | active |
| `checklists/ux-visual-reviewer.md` | ux-visual-reviewer 逐条检查项（人也能用）——触达/层级/间距/三端/深色/空态 | active |
| `roles/ai-adversarial-reviewer.md` | 当需要以对抗视角挑刺改动时加载——假设改动一定有问题，从「哪里会炸 / 用户哪里会骂 / 边界哪里漏」三个方向攻… | active |
| `roles/ai-context-health-reviewer.md` | 对整个 .agents/ 做「全景体检」——机械层交给守卫（health 六维 / structure S1–S… | active |
| `roles/ai-context-reviewer.md` | 当需要审查 AI 上下文结构（AGENTS.md 加载结构、os/*/11-context/）时加载——四问：P… | active |
| `roles/code-backend-reviewer.md` | 当需要对 services/adai-core/ 后端代码做审查时加载——分层、数据安全、AI 集成健壮性、测试… | active |
| `roles/code-frontend-reviewer.md` | 当需要对 apps/（Flutter 三端）前端代码做审查时加载——状态管理、生命周期、DTO 契约、跨端对拍、… | active |
| `roles/data-knowledge-reviewer.md` | 当需要审查 os/ 知识资产与 data/ 数据健康时加载——跨层闭环、隐私面、数据格式契约 | active |
| `roles/docs-contract-reviewer.md` | 当需要审查文档一致性/断链/数字漂移/frontmatter 合规时加载——api-spec、REVIEW、AG… | active |
| `roles/docs-design-writer.md` | 审核驱动主链的产作者（与设计文档审核者真对打）——读需求定稿与上一轮审核结论，产出设计文档：本轮回应 / 设计 … | active |
| `roles/docs-product-reviewer.md` | 当需要从产品全局审视改动/功能归属/路线对齐时加载——五层架构符合度、数据流完整、Roadmap 对齐、第一原则 | active |
| `roles/docs-requirement-reviewer.md` | 需求文稿写完、人签字之前派它审——判据 13 条（含 ISO/IEC/IEEE 29148 的 design-i… | active |
| `roles/process-reviewer.md` | 中立地盯「做事的方式」而不是「产物的对错」——记流程问题、出改进提案、当自迭代的门；不判内容、不代笔、不替人拍板 | active |
| `roles/ux-interaction-reviewer.md` | 当需要走查功能操作流程/状态机/异常流/跨端一致性时加载——反馈完整性、误触、时间线聚合 | active |
| `roles/ux-social-reviewer.md` | 当产品要从「自己用」变成「给身边人用」时加载——查递手机/被邀请者视角的隐私外露、人情成本、退出口 | active |
| `roles/ux-stranger-reviewer.md` | 当产品要交给没听过 AdaiOS 的人用之前加载——零上下文、禁读源码，只判「第一次打开的人能不能自己用起来」 | active |
| `roles/ux-support-reviewer.md` | 当产品要交给人用之前加载——预演「他一定会问你的问题」，把每条答不上来的问题变成一条待修缺陷 | active |
| `roles/ux-visual-reviewer.md` | 当需要审查页面布局/触达/视觉层级/三端一致/深色模式/空态加载态时加载——误触风险、可读性 | active |
| `skills/code-api-writer/SKILL.md` | 当需要新增/修改 API 端点时加载——从代码到契约同步的完整闭环（api-spec/status/测试/插件门… | active |
| `skills/code-domain-writer/SKILL.md` | 当需要新增 Domain OS / 重大架构能力时加载——RFC+六维 → 插件模型 → 数据流设计 → 分层落… | active |
| `skills/code-mockup-writer/SKILL.md` | 当用户说「画原型 / 出图 / 出可点稿 / 概念图 / 画几张看看」，或设计讨论需要「看与试」时加载——用自包… | active |
| `skills/data-learn-writer/SKILL.md` | 当用户要求整理外部内容（B站视频/YouTube/文章/字幕/图片）为学习文档时加载——抓取→转写/取文→结构化… | active |
| `skills/task-ship/SKILL.md` | 当开发批次完成需要收尾（/ship）时加载——五件套完成标准→契约同步→登记→门禁→规范提交 | active |

## 子目录

| 目录 | 职责 | 索引 | 契约 |
|:--|:--|:--:|:--:|
| `roles/` | — | [→](./roles/_index.md) | [→](./roles/_directory.md) |
| `skills/` | — | [→](./skills/_index.md) | [→](./skills/_directory.md) |
| `checklists/` | — | [→](./checklists/_index.md) | [→](./checklists/_directory.md) |

## 过期判断

- 新增子目录 → 必须同时有 `_index.md` 与 `_directory.md`
- **清单须与实际一致**（`ai-guard-structure` S2；跑 `--fix` 刷新）
