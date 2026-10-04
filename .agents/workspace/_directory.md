---
title: workspace/ 目录契约
description: .agents/workspace/ 的职责边界 · 生命周期 · 使用规则（**全工程最活跃目录**）；2026-10-04 提为顶层
version: 1
created: 2026-10-04
updated: 2026-10-04
status: active
lines: 55
depends-on: []
related: [./_index.md, ../rules/process/review-driven.md, ../rules/process/review.md]
tags: [meta, directory, workspace]
---

# workspace/ 目录契约

**职责**：**在制品容器（Work-in-Progress）**——回答「**正在发生什么**」。

> **2026-10-04 提为顶层**（原 `records/workspace/`）：用户指出「**机制成熟后这是最活跃的目录**」。
> 与 `records/` 的区别是**性质相反**：`records/` 是**只追加的历史账本**（REVIEW / change-log），
> 本目录是**会清空的在制品**（需求稿 / 设计稿 / 任务卡）。混在一起会掩盖"这里应该有活"的信号。

## 怎么用（**本节是重点**）

| 何时 | 动作 |
|:--|:--|
| **开工一件需求** | 从 `requirements/_template.md` 复制到 `requirements/<需求id>/`（一需求一目录），先写**需求文稿** |
| **设计对打** | 在 `designs/<需求id>/` 按轮次落 `design-v<N>-<YYYYMMDD>.md` 与 `review-v<N>.md`（轮次 ≤3，不收敛**升级给人**） |
| **拆任务** | 在 `tasks/` 落任务卡（当批要做的事），完成后**删除**（不是标记完成——避免堆积） |
| **需求验收后** | **归档**：把定稿移入 `../records/`（账本）或 `docs/`（档案馆），**然后清空本需求的目录** |
| **收工前** | 扫一遍：**本目录里还有没有"该结没结"的东西**？有 → 要么推进、要么登记到 `../records/REVIEW.md` |

**判据**：**本目录应当"会变空"**。如果它长期堆着一堆文件不动 → 说明**有任务卡在半路**（这本身就是一个信号，比任何报表都直接）。

## 职责边界
- **放**：需求文稿 · 设计稿（含对家评审稿）· 任务卡 · 一切**尚未定稿**的在制品
- **不放**：定稿的（→ `../records/` 或 `docs/`）· 只追加的历史（→ `../records/`）· 事实/规则（→ `../knowledge/` `../rules/`）

## 依赖关系

| 方向 | 对象 | 说明 |
|:--|:--|:--|
| 规则 | `../rules/process/review-driven.md` | **审核驱动主链**：需求 → 设计 → 编码，双角色真对打（在制品放本目录） |
| 规则 | `../rules/process/review.md` | 按改动派审查官 |
| 出口 | `../records/` | 定稿后归档为账本 |
| 守卫 | `../mechanism/guards/ai-guard-meta.sh` | frontmatter / 断链 / 孤儿 |

## 约束
- **一需求一目录**（避免同名覆盖）
- **轮次命名固定**（`design-v<N>-<YYYYMMDD>.md` / `review-v<N>.md`）
- 子目录级 `_index.md` + `_directory.md` **保留**（模板与契约常在）；**在制品本身不入清单**（它们是动态的）

## 维护动作
1. 需求定稿 → 归档 → **清空该需求目录**
2. 收工前扫「该结没结」→ 推进或登记 REVIEW
3. 本目录的理想状态是**接近空**（只有模板与两件套）
