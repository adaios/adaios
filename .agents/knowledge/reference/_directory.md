---
title: reference/ 目录契约
description: .agents/knowledge/reference/ 的职责边界 · 依赖关系 · 触发关系 · 约束 · 守卫 · 维护方式（机器可校验的目录级元数据）
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 54
depends-on: []
related: [./_index.md]
tags: [meta, directory]
---

# reference/ 目录契约

**职责**：**② 事实（按需查）**——回答「现在是什么样」。全项目**唯一**的契约、状态与手册真相源。

## 职责边界
- **放**：数字真相（status）· 接口与数据契约 · 架构与领域设计 · 功能手册
- **不放**：规则（怎么做）→ `../../rules/assets/` `../../rules/guides/` · 决策过程 → `../../direction/rfc/` · 历史 → `../../records/`

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 依赖 | `../../direction/` | 事实须与蓝图一致 |
| 被依赖 | 代码本体 | **文档与代码不一致时，`ai-guard-align` 逼你回答"谁错了"** |
| 被依赖 | `../../rules/process/ship.md` | 收尾时同步 status / api-spec |

## 触发关系

| 时机 | 谁触发 | 读 / 执行什么 |
|:--|:--|:--|
| **每次开工** | `ai-guard-context.sh` | `status.md`（C1 当前状态） |
| 写 / 改接口 | `skills/code-api-writer.md` | `api-spec.md` → 改完同步 |
| 改前端 | 人 + AI | `frontend-reference.md` |
| 改交易 / 记忆模块 | 人 + AI | 对应的领域设计文档 |
| 收尾 | `/ship` | `ai-guard-align` 对拍 |

## 约束
- **数字只在一处**：测试数 / 端点数 / 环境**只在 `status.md`** 详述，别处引用（ADR-002）
- **契约随代码同步**：`api-spec` 与 `@Mapping` 逐一对拍，不一致即 FAIL
- frontmatter **10 字段**（`ai-guard-meta` 查）

## 守卫（谁保证这里不腐烂）
- `ai-guard-meta`：frontmatter / lines / 断链 / 孤儿
- **`ai-guard-align`**：A1 端点 ↔ api-spec · A2 测试数 ↔ status（**事实对拍的核心**）
- `ai-guard-feature`：功能主轴 ↔ 实现出处
- `ai-guard-structure`：两件套齐备 · 清单⇄实际

## 维护动作
1. 新增事实文档 → 补 `_index.md` → 跑 `ai-guard-structure.sh --fix`
2. **改了代码就同步改动本区**（api-spec / status / 手册）——这是"文档跟随行为"的落点
3. 事实过期 → `status: superseded` 或移入 `../../../docs/archive/`
