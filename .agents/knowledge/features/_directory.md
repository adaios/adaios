---
title: features/ 目录契约
description: .agents/knowledge/features/ 的职责边界 · 依赖关系 · 触发关系 · 约束 · 守卫 · 维护方式（机器可校验的目录级元数据）
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 52
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# features/ 目录契约

**职责**：**② 事实（功能主轴）**——一功能一行，回答「**项目有什么功能 · 各自什么状态 · 欠着什么**」。

## 职责边界
- **放**：`_index.md`（功能主轴，一行一功能）· `<插件>.md`（**意图卡**：只写意图，禁写实现细节）
- **不放**：实现细节 → 代码 · API 定义 → `../reference/contracts/api-spec.md` · 功能手册 → `../reference/manuals/trading-features.md` / `../reference/manuals/admin-features.md`

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 依赖 | `../../direction/product-roadmap.md` | 功能来自路线拆解 |
| 依赖 | `../../records/REVIEW.md` | 「欠着」列的编号须在 REVIEW 中存在 |
| 被依赖 | `../../rules/process/ship.md` · `../../rules/workflow/develop.md` | 每批动到的功能要回写状态 |

## 触发关系

| 时机 | 谁触发 | 读 / 执行什么 |
|:--|:--|:--|
| 开工（看有什么功能） | `ai-guard-context.sh` | `_index.md`（C1.4 功能主轴） |
| 开发某个功能 | 人 + AI | 该功能的意图卡 |
| 提交前 | `.githooks/pre-commit` | `ai-guard-feature` 校验索引真实性与「欠着」编号 |

## 约束
- **一行一功能**：ID / 插件 / 状态 / 需求出处 / 实现出处 / 欠着
- **意图卡 ≤12 行**，**卡内禁写实现细节**（实现在代码里，卡只留意图）
- **状态由脚本对拍**，不靠人记得
- frontmatter **10 字段**（`ai-guard-meta` 查）

## 守卫（谁保证这里不腐烂）
- **`ai-guard-feature`**：字段 / 链接 / 状态枚举 / 欠着编号存在性 / 卡 ≤12 行 / 卡文件非孤儿 / **F0 防解析失败假绿**
- `ai-guard-meta`：frontmatter / lines / 断链
- `ai-guard-structure`：两件套齐备 · 清单⇄实际

## 维护动作
1. 新增功能 → 在 `_index.md` 加一行；需要意图卡时在同目录插件文件里生长
2. 功能状态变更 → 改那一行（**状态由脚本对拍，别手写实现细节**）
3. 规格见 RFC 20261001（`../../direction/rfc/20261001-feature-index-and-authoring-gate.md`）
