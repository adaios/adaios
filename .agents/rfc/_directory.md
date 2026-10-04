---
title: rfc/ 目录契约
description: .agents/rfc/ 的职责边界 · 依赖关系 · 触发关系 · 约束 · 守卫 · 维护方式（机器可校验的目录级元数据）
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 53
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# rfc/ 目录契约

**职责**：**① 方向（决策过程）**——回答「**要不要做 · 怎么做**」。全项目 67 份方案决策的**唯一**记录地。

## 职责边界
- **放**：`<日期>-<主题>.md`（方案决策全文，**含未采纳的备选与理由**）
- **不放**：架构结论 → `../assets/adr/`（ADR）· 已退役的 → `../../docs/archive/` · 想法池 → `../../docs/ideas/`

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 依赖 | `../direction/` | 决策须服务于蓝图 |
| 被依赖 | `../assets/adr/` | **RFC 被接受后，若构成长期架构约束 → 沉淀为 ADR** |
| 被依赖 | `../reference/` | 实现与设计文档的决策出处 |

> **ADR ↔ RFC 分工**：**RFC 是过程**（要不要做 / 怎么做，含备选与理由）· **ADR 是结论**（为什么这么定，append-only）。两份 `_index` 各写同一段说明（2026-10-04 取舍②：分家不动）。

## 触发关系

| 时机 | 谁触发 | 读 / 执行什么 |
|:--|:--|:--|
| 改某模块，想知道"为什么这么定" | 人 + AI | 相关 RFC |
| 新需求定稿 | 人 | 定稿后归档为 `<日期>-<主题>.md`（主链第二段） |
| 提交前 | `.githooks/pre-commit` | `ai-guard-feature` 校验 RFC status 枚举 |

## 约束
- **status 枚举**：`draft` → `approved` → `implemented`（另有 `superseded` 表示被取代）
- **不删不覆写**：被取代的标 `superseded` + `supersededBy`，移入 `../../docs/archive/`
- frontmatter **10 字段**（`ai-guard-meta` 查；**M4 正文检查豁免**——决策记录含"当时"的路径）

## 守卫（谁保证这里不腐烂）
- `ai-guard-meta`：frontmatter / lines / 图谱（M4 豁免）
- `ai-guard-feature`：RFC status 枚举合规
- `ai-guard-structure`：两件套齐备 · 清单⇄实际

## 维护动作
1. 新 RFC → 写文件 → 补 `_index.md` → 跑 `ai-guard-structure.sh --fix`
2. 决策被取代 → 旧件标 `superseded` + `supersededBy` → 移入 `../../docs/archive/`
3. 构成长期架构约束 → 写一条 ADR（`../assets/adr/`）
