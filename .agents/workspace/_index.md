---
title: workspace/ 目录索引
description: .agents/workspace/ 的清单与过期判断——**按「分支任务」组织**：一个分支一个目录（需求 / 设计 / 审核 / 决策都在它名下，需要才有）；模板在 _templates/、跨任务的在 _meta/
version: 2
created: 2026-10-04
updated: 2026-10-06
status: active
lines: 80
depends-on: []
related: [./_directory.md]
tags: [meta, index, workspace]
---

# workspace/ 目录索引

> 本文件只列**有什么**；**规则与依赖**见 [`_directory.md`](./_directory.md)。

**职责**：见 [`_directory.md`](./_directory.md)（此处不重复——S5 判据）

## 子目录（3）

| 子目录 | 装什么 | 生命周期 |
|:--|:--|:--|
| `_templates/` | 模板（需求 / 设计 / 审核 / 账本） | 常驻 |
| `_meta/` | **跨任务**的：流程问题 / 规范草案 | 交 ① 线后清 |
| `trading-plugin/` | **分支任务目录**（= 分支名 `feat/trading-plugin`）——一个任务的全部产物 | **合并后整目录归档并删除** |

> **规则**：**一个分支 = 一个目录 = 一个任务**（主键唯一 ⇒ **零冲突**）；目录里**需要什么才有什么**（轻活就一个 `LEDGER.md`）。

## 文件清单（40 项）

| 文件 | 职责 | 状态 |
|:--|:--|:--:|
| `_templates/design.md` | 设计文档模板——本轮回应 / 设计 / 取舍 / 未决 / 自评风险；一轮只收敛一类 | active |
| `_templates/dispatch.md` | **派单模板**——回执 · 材料前置 · 先落文件 · 3 分钟闸门（协议见 review-driven §9） | active |
| `_templates/ledger.md` | 分支账本模板——目标 / 进度 / 风险 / 待交接 | active |
| `_templates/requirement.md` | 需求文稿模板——问题动机 / 业务规则 / 范围 / 验收 / 未决 / 定稿记录 | active |
| `_templates/review-design.md` | 审核文件模板（设计 / 需求共用）——审核对象 / 结论 / 问题清单 / 收敛判定 | active |
| `_meta/process-issues-20261006.md` | 流程问题与改进 · 汇总（本次实跑实测 **12 条**） | active |
| `_meta/inbox.md` | **派单收件箱**——消息通道丢载荷时的**可靠投递通道**（`AGENTS.md` 规则 **0c**） | active |
| `_meta/dispatch-v3-product-20261006.md` | 派单样板：设计 v3 产品视角 3 点复核（材料全前置） | active |
| `_meta/dispatch-v4-writer-20261006.md` | 派单：设计 **final 稿（合并正文）**——收 4 类阻塞项（材料全前置） | active |
| `_meta/dispatch-v4-backend-review-20261006.md` | 派单 D-04：design-final 收敛性审核（**后端官**） | active |
| `_meta/dispatch-v4-product-review-20261006.md` | 派单 D-05：design-final 收敛性审核（**产品官**） | active |
| `_meta/dispatch-v4-process-20261006.md` | 派单 D-06：**流程官**记录本轮（流程事实 + 改进提案） | active |
| `_meta/process-log-20261006-v4.md` | **流程官首跑记录（v4 轮）**——时间台账 · 违反判据 P-04/P-05/P-08/P-11/P-12 · 7 条改进提案 · 健康度 C | active |
| `_meta/dispatch-v5-writer-fix-20261006.md` | 派单 D-07：design-final **就地修订**（收 v4 新引 3 条 P1） | active |
| `_meta/dispatch-s2-handover-20261006.md` | 派单 D-08：**S2 落点**两份可搬移草稿（RFC + roadmap 改动） | active |
| `_meta/dispatch-s2-revise-20261006.md` | 派单 D-09：**修订** S2 两份草稿（87 课 = 规则层 · 私人财产 → 四处修正） | active |
| `_meta/rfc-draft-trading-positioning-expansion-20261006.md` | **定位扩容 RFC**（建议引擎 → 人人可用）——★1–★4 **已拍板** · 合并后搬进 `rfc/` | approved |
| `_meta/roadmap-diff-trading-20261006.md` | **roadmap 三条改动提案**（L6 / Trading OS / 未修项）——合并后由主会话落盘 | draft |
| `_meta/inbox-receipt.md` | 通道探针回执——**E3 证据**（0c 通道可用） | active |
| `trading-plugin/LEDGER.md` | `feat/trading-plugin` 分支账本——本批目标 · 并行纪律 · **待交接** | active |
| `trading-plugin/thinking-log.md` | **思考记录**（中文）——每轮推理链：看到 / 判断 / 选择 / 放弃的 | active |
| `trading-plugin/requirement.md` | 交易插件重做 **需求文稿（已定稿）** | active |
| `trading-plugin/requirement-review-20261006.md` | 需求审核（第一轮）——不收敛（P1×2 / P2×3 / P3×2） | active |
| `trading-plugin/requirement-review-20261006-r2.md` | 需求审核（第二轮）——收敛 | active |
| `trading-plugin/requirement-review-20261006-r3.md` | 需求审核（第三轮 · 归位后）——收敛 | active |
| `trading-plugin/overview.md` | 交易插件全景梳理（定位 / 功能 / RFC / 未修 / 未优化） | active |
| `trading-plugin/blueprint.md` | 交易插件概念与流程（按讨论顺序） | active |
| `trading-plugin/input.md` | 设计输入（讨论产物 · 只剩"怎么做"） | active |
| `trading-plugin/decisions.md` | **设计决策记录（人拍板）**——S1/S2 · U1–U6 · V1–V3 · V2 拆两层 | active |
| `trading-plugin/design-v1-20261006.md` | 设计稿 v1（编写者） | active |
| `trading-plugin/review-v1-20261006.md` | 设计审核 v1（产品）——不收敛；**主会话代誊**（审核者未落盘） | active |
| `trading-plugin/design-v2-20261006.md` | 设计稿 v2（编写者） | active |
| `trading-plugin/review-v2-20261006.md` | 设计审核 v2（产品）——不收敛 | active |
| `trading-plugin/review-v2-backend-20261006.md` | 设计审核 v2（**后端代码**对拍）——不收敛；含 §9 实测差异表 | active |
| `trading-plugin/design-v3-20261006.md` | 设计稿 v3（**增量稿** · 只收敛 P0/P1） | active |
| `trading-plugin/design-final-20261006.md` | **设计 final 稿（合并正文 · 585 行）**——取代 v2/v3，成为唯一正文 | active |
| `trading-plugin/review-v4-backend-20261006.md` | 设计审核 v4（**后端面**）——不收敛；新引 P1×2（§4.1 漏 3 条实存路径 · §10.1 正则漏自举例） | active |
| `trading-plugin/review-v4-20261006.md` | 设计审核 v4（**产品面**）——不收敛；上轮 6 项全闭环，新引 P1×1（§1 数量声明与实体不符） | active |
| `trading-plugin/review-r2-backend-20261006.md` | **差异复核 r1→r2**——三条改动**全部真到位** · 无连带新引 P0/P1（→ 设计**收敛**） | active |
| `trading-plugin/review-v3-backend-20261006.md` | 设计审核 v3（**后端代码**对拍）——不收敛；S1/S2 未闭环 · 新引 P1×2 | active |
| `trading-plugin/review-v3-product-20261006.md` | 设计审核 v3（**产品视角 · 独立子代理**）——只判 3 点；新引 P1×2（增量稿声明↔载体不一致） | active |

## 过期判断

- **合并后整个 `<分支名>/` 归档并删除**（`main` 上残留 = 未收尾）
- `_meta/` 的内容交 ① 线后清空
- **清单须与实际一致**（跑 `bash .agents/mechanism/guards/ai-guard-structure.sh --fix` 刷新）
