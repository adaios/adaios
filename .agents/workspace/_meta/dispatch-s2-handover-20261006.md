---
title: 派单 D-20261007-03 · S2 落点两份产物（RFC 草稿 + roadmap 改动提案）
description: 派给文档编写者的任务书——产出一份可搬进 rfc/ 的定位扩容 RFC 草稿 + 一份 roadmap 三条改动提案；材料全前置，三处实质判断标★待人拍板
version: 1
created: 2026-10-06
updated: 2026-10-06
status: active
lines: 71
depends-on: []
related:
  - ./inbox.md
  - ../trading-plugin/design-final-20261006.md
  - ../../direction/rfc/20260902-trading-memory-positioning.md
tags: [workspace, meta, dispatch, trading]
---

# 派单 D-20261007-03 · S2 落点两份产物

你是 AdaiOS 的**文档编写者**。真相源：`.agents/toolkit/roles/docs-design-writer.md`（按它的约束做：只写交付物、不改别人文件）。

## 背景（材料已前置，不必自己找）

- **要点唯一来源**：`.agents/workspace/trading-plugin/design-final-20261006.md` 的 **§13.3「编码准入条件」**（约 `:543` 起）与 §1 定位表；
- **需求（已定稿）**：`.agents/workspace/trading-plugin/requirement.md`（定位 / 约束·隐私）；
- **人拍板**：`.agents/workspace/trading-plugin/decisions.md`（S1 / S2 / 第三轮 W1–W2）；
- **必须承接、不许打架的上一轮定位 RFC**：`.agents/direction/rfc/20260902-trading-memory-positioning.md`（`status: approved`）；
- **方向目录契约**：`.agents/direction/rfc/_directory.md`（`status` 枚举 `draft→approved→implemented`、不删不覆写、`supersedes` 规则）。

## 为什么本分支只出草稿

`rfc/` 与 `product-roadmap.md` 属**外围**——按分支纪律（分支上只动 `workspace/`），**由合并后的主会话搬移**。所以本单交付的是**可直接搬的两份草稿**，都放 `.agents/workspace/_meta/`。

## 交付 1 · RFC 草稿

`.agents/direction/rfc/20261006-trading-positioning-expansion.md`

照 `20260902` 那份的结构写（背景 / 新定位 / 边界 / 备选与理由），并**逐条落**下面六段：

1. **定位变更**：「建议引擎 / owner 专属」→「**人人可用的交易记录与照见**」（有规则的人照纪律；没规则的人先看见自己）；
2. **与 87 课知识库（`os/trading-engine`）的关系**：它是 **owner 的私有知识资产**，经 `TradingKnowledgeSource` **只对 owner 注入**；新用户规则走「**从自己的数据里长出来**」，**不注入他人知识**；
3. **与 `20260902` 的承接关系**：那份记的是「建议引擎 → 交易记忆」，本次是**增量**（交易记忆 → 人人可用 + 规则自生长）——**显式写"承接哪些条款 / 是否取代某条"**，不许含糊；
4. **不做清单（写进 RFC 即对外承诺）**：规则分享 `N-01` · 周期层 `N-02` · 用户端行情入口 `X-02`；
5. **合规口径**（沿用 `20260902` 的原则）：输出主语永远是「**你**」不是「市场」；**不给**具体品种 / 时机的建议文本；
6. **三处实质判断标 `★ 待 adai 拍板`**——见下，**不要替人拍**。

**★ 三处待拍板（必须原样标出，不许自行定）**：

- ★1 **扩容边界**：非 owner **一律不注入 87 课**——这是合规红线级的决定；
- ★2 **不做清单**：上面第 4 条三项**进 RFC 即成为对外承诺**，以后要做须先改 RFC；
- ★3 **与 `20260902` 的关系**：**承接**还是**取代某条**——定错会让方向记录自相矛盾。

**frontmatter 用 workspace 口径（10 字段）**：`title / description / version / created / updated / status（= draft）/ lines / depends-on / related / tags`。
**并在文首加一节「搬到 `rfc/` 时怎么改 frontmatter」**：给出目标格式（照 `20260902`：`title / description / date / status / decided-by / tags / supersedes`），并注明 `status: draft → approved` 由**人定稿后**改。

## 交付 2 · roadmap 改动提案

`.agents/workspace/_meta/roadmap-diff-trading-20261006.md`

**三行改动**，每条给 `现状原文 → 改后原文`（原文**从 `product-roadmap.md` 抄**，别凭记忆）+ 一句理由：

- **L6 行**（约 `:90`）：补「**目标用户扩容（人人可用）**」，与 RFC 同口径；
- **Trading OS 行**（约 `:96`）：补「**候选规则自生长**（从数据长规则）」；「规则分享」标**先不做**；
- **未修项关联**：增挂 `P2-交易81`（新用户初始化顺序）为关联项。

## 纪律（硬要求）

- **第一动作**：先建 **交付 1** 的空文件（frontmatter + 标题），再往下写——**先落文件再填**；
- **3 分钟内必须看到文件**；**15 分钟内完成两份**；
- frontmatter **10 字段**齐全 + `lines:` 与 `wc -l` 一致；
- 中文；**只写这两份新文件**——**不要改** `design-*` · `requirement.md` · `decisions.md` · `_index.md` · `rfc/` · `product-roadmap.md`；
- 收工把本单从 `inbox.md` 待处理移到已完成。
