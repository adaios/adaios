---
title: 派单 · v3 产品视角 3 点复核（同时是投递通道实验）
description: 单发派给一个独立子代理；材料全部前置。同时验证「fork_turns=none + 文件指针」能否修复任务书投递丢失
version: 1
created: 2026-10-06
updated: 2026-10-06
status: active
lines: 84
depends-on: []
related:
  - ./process-issues-20261006.md
related-files:
  - ../trading-plugin/design-v3-20261006.md
tags: [workspace, meta, dispatch]
---

# 派单 · v3 产品视角 3 点复核

## 一、你的身份与边界

- 你是**独立审核官**（**产品视角**）。
- **不得修改** `design-*.md` / `requirement.md`——你**只出审核意见**。
- **只审下面 3 点**：不新增需求、不做整体通读、不提 P2/P3。

## 二、交付物（唯一）

写一个文件：`.agents/workspace/trading-plugin/review-v3-product-20261006.md`

格式要求：

1. **第一行是回执**：`> 回执：收到派单，开始执行`
2. 然后 frontmatter——**10 个字段**，用下面模板（`title`/`description`/`version`/`created`/`updated`/`status`/`lines`/`depends-on`/`related`/`tags` 缺一不可）
3. 然后正文：3 点，每点写 **结论（成立 / 不成立 / 部分成立）** + **理由** + **证据（`文件:行号` 或直接引文）**
4. 最后写 `## 新引问题`：本轮有没有新引 P1；没有就写「无」

frontmatter 模板（照抄，只改 `lines`）：

```
---
title: 设计 v3 · 产品视角复核
description: 只判 3 点——S2 落点是否闭环 / 增量稿与正文是否自相矛盾 / 数量声明是否属实
version: 1
created: 2026-10-06
updated: 2026-10-06
status: draft
lines: 0
depends-on:
  - ./design-v3-20261006.md
related:
  - ./review-v3-backend-20261006.md
tags: [workspace, review, trading]
---
```

> ⚠️ `lines` 必须等于写完后 `wc -l` 的**真实行数**，否则元数据守卫会红。

## 三、时限（硬）

- **3 分钟内**：先把文件建起来，只写回执行 + 3 点小标题（骨架）。
- **10 分钟内**：补完内容。

## 四、材料（全部前置，**不用**去翻 v2 的 415 行正文）

### 点 1 · 战略 S2（RFC / roadmap）是否真闭环

- 背景：v2 审核把「RFC / roadmap 条目只存在于交接表里」判为**战略级**问题。
- v3 的处置（`design-v3-20261006.md` §一 表格首行）：把 S2「**升为「编码准入条件」**：S2 的 RFC（定位变更）+ roadmap 的 Trading 条目，**未落地前不进编码**」。
- **你要判**：「把它写成准入条件」**算不算 S2 闭环**？还是说这只是把问题**推后**——真正的 RFC 文件**仍未产出**？给出结论与理由。

### 点 2 · 增量稿与正文是否自相矛盾（新引 P1 嫌疑）

- v3 §二 P1-2 行称：`TradingContextContributor` 被**误标「死代码」**，v3 **已在 §8.1 更正**。
- 但正文 `design-v2-20261006.md` 里，**是否仍写着「死代码 / 删除」**？请去该文件搜 `TradingContextContributor` 与 `死代码` 验证。
- **你要判**：修正**只写在增量稿、没回写正文** → 是否构成「**同一份设计自相矛盾**」的新引 P1？

### 点 3 · 「11 组」数量声明是否属实

- v3 §一 称：「§9 补后端官点名的 **11 组**缺失能力」。
- 但 v3 §9 那张表**实际只有 10 行**（出入金 / 资金层 / 清仓情绪 / 候选入账 / 批量 / 推送控制 / 活跃市值 / 账户组合 / 建议留痕 / 查询）。
- **你要判**：这是不是**假精确**？是否该立一条口径——**数量声明必须可机械校验**？

## 五、回执方式

**落盘即回执**——你不需要用消息回我；我以**文件存在**为唯一凭据。
