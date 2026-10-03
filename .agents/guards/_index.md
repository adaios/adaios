---
title: guards/ 目录索引
description: guards/ 的文件清单与过期判断；目录的职责边界与依赖契约见 ./_directory.md
version: 1
created: 2026-10-03
updated: 2026-10-03
status: active
lines: 40
depends-on: []
related: [./_directory.md]
tags: [meta, index]
---

# guards/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

## 文件清单（13 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `guard-align.sh` | 文档自动对齐守护 — 代码 ↔ 文档内容一致性检查 | active |
| `guard-context.sh` | 任务前上下文注入（进攻侧核心）— 生成"开工前必读清单" | active |
| `guard-cost.sh` | 成本监控（防守侧）— 读 DSH 会话日志，按天/月算 DeepSeek 实际消费 | active |
| `guard-feature.sh` | 功能索引守护检查（guard-feature）—— docs/features/ 功能主轴自检 | active |
| `guard-meta.sh` | 元治理守护检查 — ai-engineering/ + AGENTS.md 的 frontmatter 契约自检 | active |
| `guard-prod.sh` | 生产日报（每日流程）— 生产日志 + 真实对话卡片，一条命令看全 | active |
| `guard-release.sh` | 发版体检（发布前随时问一句：现在欠着什么没发） | active |
| `guard-roadmap.sh` | 规划状态对拍（roadmap 体检）— 回答「未来规划如何 / 规划是否可信」 | active |
| `guard-sediment.sh` | 沉淀检查器（进攻侧 ②③）— 提交前检查"该沉淀的有没有沉淀" | active |
| `guard-skills.sh` | 技能包质量校验（S3 / S4 / S5 / S7）—— 补 guard-tools T3 之外的部分 | active |
| `guard-structure.sh` | 目录级自洽守卫（guard-structure）—— 补 guard-meta（文件级）之外的那一半 | active |
| `guard-tools.sh` | 工具接入自检（防守侧）— 检测「AI 上下文工程体系」在各工具侧是否真的被加载 | active |
| `guard-unfixed.sh` | 未修复问题总清单（聚合 4 个维护点）— 用户问「还有哪些未修」一条命令拿全 | active |

## 过期判断

- `status != active` → 候选清理
- `updated` 超 3 个月未动且无人引用 → 候选归档
- **清单必须与实际文件一致**（`guard-structure` S2 双向校验；新增文件后跑 `guard-structure.sh --fix`）
