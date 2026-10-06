---
title: roadmap 改动提案 · 交易定位扩容三条（L6 / Trading OS / 未修项关联）
description: product-roadmap.md 的三条改动提案（现状原文 → 改后原文 + 理由），与 RFC 草稿同口径；供主会话在合并后落盘
version: 2
created: 2026-10-06
updated: 2026-10-06
status: draft
lines: 89
depends-on: []
related:
  - ./rfc-draft-trading-positioning-expansion-20261006.md
  - ./dispatch-s2-handover-20261006.md
  - ../../direction/product-roadmap.md
tags: [workspace, roadmap, trading]
---

# roadmap 改动提案 · 交易定位扩容三条

> **性质**：**提案**——`product-roadmap.md` 属**外围**（分支上只动 `workspace/`），本分支**不改它**，由合并后的主会话按本清单落盘。
> **口径**：与 `rfc-draft-trading-positioning-expansion-20261006.md` **同口径**（人人可用 + 规则自生长）。
> **原文来源**：下文「现状原文」**逐字抄自 `.agents/direction/product-roadmap.md`**（行号以本分支现件为准），**未凭记忆**。
> **修订**：`r2`（2026-10-06）—— 按人 2026-10-06 口头修正：**87 课 = 规则层 · 私人财产**（改动 2 / 改动 3 的「仅对 owner 注入」补成与 RFC 同口径的措辞）。

---

## 改动 1 · L6 行（`product-roadmap.md:90`）

**现状原文**（`§3.1 五层产品架构` 表内）：

```
| **L6 交易闭环** | 持仓 / 复盘 / 知识反哺（promote/conflicts）/ 意图识别（STATEMENT/QUESTION）| ✅ | 完整 |
```

**改后原文**：

```
| **L6 交易闭环** | 持仓 / 复盘 / 知识反哺（promote/conflicts）/ 意图识别（STATEMENT/QUESTION）/ **目标用户扩容（人人可用）** | ✅ | 完整 |
```

**理由**：与 RFC 同口径——交易闭环不再只服务 owner，**人人是本次扩容的主轴**；不写上，roadmap 会把「owner 专属」当终点，与 RFC 打架。

---

## 改动 2 · Trading OS 行（`product-roadmap.md:96`）

**现状原文**（`§3.2 Domain OS` 表内）：

```
| **Trading OS** | ✅ | 87 课知识库 → knowledge/context → KnowledgeSource → Context Engine 全链路 |
```

**改后原文**：

```
| **Trading OS** | ✅ | 87 课**规则层**（**owner 的私人财产**）→ knowledge/context → KnowledgeSource → Context Engine 全链路（**仅对 owner 注入**，非 owner 一律不注入 87 课）；**候选规则自生长**（从用户自己的数据长规则，规则分享先不做） |
```

**理由**：两处必须同改——① 补上 **87 课 = owner 的规则层（私人财产）、只对 owner 注入**（RFC ★1 的合规边界，roadmap 现状把它写成对所有人可用）；② 补「**候选规则自生长**」并把「**规则分享**」标**先不做**（`N-01`，RFC §六 的对外承诺）。

---

## 改动 3 · 未修项关联（增挂 `P2-交易81`）

**现状**：`product-roadmap.md` **没有「未修项关联」字段/列**——`§3.2 Domain OS` 的 Trading OS 行只有 `Domain | 状态 | 说明` 三列（`:96`），全表未挂任何 `P2-*` 关联项（`REVIEW.md` 仅作为「质量如何」的文档级引用出现在 `§七 关联文档` `:201`）。

**提案（二选一，落盘时定；推荐 A）**

**A（推荐）· 挂进 Trading OS 行的「说明」列**——现状原文（同改动 2 的起点行）：

```
| **Trading OS** | ✅ | 87 课知识库 → knowledge/context → KnowledgeSource → Context Engine 全链路 |
```

改后原文（在改动 2 基础上追加关联项）：

```
| **Trading OS** | ✅ | 87 课**规则层**（**owner 的私人财产**）→ knowledge/context → KnowledgeSource → Context Engine 全链路（**仅对 owner 注入**，非 owner 一律不注入 87 课）；**候选规则自生长**（从用户自己的数据长规则，规则分享先不做）；**未修项关联：`P2-交易81`**（新用户初始化顺序未产品化，是扩容落地的第一道坎） |
```

**B · 在 `§3.2 Domain OS` 表后新增一行「关联未修项」小表**（不改现有表结构）：

```
| Domain | 关联未修项 |
|:-------|:-----------|
| **Trading OS** | `P2-交易81`（新用户初始化顺序未产品化 → 第一次用处处撞墙） |
```

**理由**：`P2-交易81`（`REVIEW.md:181`，2026-10-03 登记 · 未修）正是「**人人可用**」这条定位在**新用户第一条路径**上的具体缺口——不挂上，扩容定位在 roadmap 里没有可追溯的欠账抓手，也违背「所有任务可回溯到路线」。
**待主链定**：roadmap 现无「关联未修项」标准字段，**新增字段属结构改动**——建议由主链在落盘时按 A/B 之一拍板并同步 `§七 关联文档` 的口径说明。
