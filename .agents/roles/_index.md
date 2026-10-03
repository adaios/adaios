---
title: roles/ 目录索引
description: roles/ 的文件清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 41
depends-on: []
related: [./_directory.md]
tags: [meta, index]
---

# roles/ 目录索引

**职责**：审查官定义——**subagent 的真相源**（8 客观官 + 1 对抗官 + 3 外部视角官）

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

## 文件清单（12 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `adversarial-reviewer.md` | 当需要以对抗视角挑刺改动时加载——假设改动一定有问题，从「哪里会炸 / 用户哪里会骂 / 边界哪里漏」三个方向攻… | active |
| `backend-reviewer.md` | 当需要对 services/adai-core/ 后端代码做审查时加载——分层、数据安全、AI 集成健壮性、测试… | active |
| `context-reviewer.md` | 当需要审查 AI 上下文结构（AGENTS.md 加载结构、os/*/11-context/）时加载——四问：P… | active |
| `docs-reviewer.md` | 当需要审查文档一致性/断链/数字漂移/frontmatter 合规时加载——api-spec、REVIEW、AG… | active |
| `frontend-reviewer.md` | 当需要对 apps/（Flutter 三端）前端代码做审查时加载——状态管理、生命周期、DTO 契约、跨端对拍、… | active |
| `knowledge-reviewer.md` | 当需要审查 os/ 知识资产与 data/ 数据健康时加载——跨层闭环、隐私面、数据格式契约 | active |
| `product-arch.md` | 当需要从产品全局审视改动/功能归属/路线对齐时加载——五层架构符合度、数据流完整、Roadmap 对齐、第一原则 | active |
| `social-reviewer.md` | 当产品要从「自己用」变成「给身边人用」时加载——查递手机/被邀请者视角的隐私外露、人情成本、退出口 | active |
| `stranger-reviewer.md` | 当产品要交给没听过 AdaiOS 的人用之前加载——零上下文、禁读源码，只判「第一次打开的人能不能自己用起来」 | active |
| `support-reviewer.md` | 当产品要交给人用之前加载——预演「他一定会问你的问题」，把每条答不上来的问题变成一条待修缺陷 | active |
| `ui-reviewer.md` | 当需要审查页面布局/触达/视觉层级/三端一致/深色模式/空态加载态时加载——误触风险、可读性 | active |
| `ux-reviewer.md` | 当需要走查功能操作流程/状态机/异常流/跨端一致性时加载——反馈完整性、误触、时间线聚合 | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- **清单必须与实际文件一致**（`guard-structure` S2 双向校验；新增文件后跑 `guard-structure.sh --fix`）
