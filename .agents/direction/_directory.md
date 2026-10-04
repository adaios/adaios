---
title: direction/ 目录契约
description: .agents/direction/ 的职责边界 · 依赖关系 · 触发关系 · 约束 · 守卫 · 维护方式（机器可校验的目录级元数据）
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 49
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# direction/ 目录契约

**职责**：**① 方向**——回答「为什么做 · 往哪走」。全项目**唯一**的理念与路线真相源。

## 职责边界
- **放**：理念（VISION）· 路线蓝图（roadmap）
- **不放**：方案决策 → `rfc/` · 架构决策 → `../rules/assets/adr/` · 产品事实 → `../knowledge/reference/`

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 入口 | `../../AGENTS.md` | 规则 1 把本区列为**首读** |
| 被依赖 | `rfc/` · `../knowledge/reference/` | 决策与事实须与蓝图一致（`ai-guard-roadmap` 对拍） |

## 触发关系

| 时机 | 谁触发 | 读 / 执行什么 |
|:--|:--|:--|
| **每次会话开工** | 人 + AI | `VISION.md` → `product-roadmap.md`（规则 1） |
| 拆任务 / 定目标 | 人 + AI | `product-roadmap.md` → 拆到 `../records/task-log.md` |
| 发布前 | `ai-guard-release` | 对照蓝图判定该发什么 |

## 约束
- **唯一真相源**：理念与蓝图**只在本区详述**，别处只引用
- **蓝图漂移要显式**：实现与蓝图不一致时，要么补蓝图要么记进 `../records/REVIEW.md`——不许沉默
- frontmatter **10 字段**（`ai-guard-meta` 查）

## 守卫（谁保证这里不腐烂）
- `ai-guard-meta`：frontmatter / lines / 断链 / 孤儿
- `ai-guard-roadmap`：**蓝图 ↔ 实现漂移对拍**（S-1/S-5 清单 + 状态证据）
- `ai-guard-structure`：两件套齐备 · 清单⇄实际

## 维护动作
1. 新增方向类文档 → 补 `_index.md` → 跑 `ai-guard-structure.sh --fix`
2. 路线变更 → 先改蓝图，再改下游（任务表 / 功能主轴）
