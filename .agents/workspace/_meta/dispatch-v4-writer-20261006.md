---
title: 派单 D-20261006-03 · 设计 final 稿（合并正文）
description: 派给设计文档编写者的任务书——产出 design-final 合并正文，收掉 4 类阻塞项（材料全前置）；单号 D-20261006-03
version: 1
created: 2026-10-06
updated: 2026-10-06
status: active
lines: 55
depends-on: []
related:
  - ./inbox.md
  - ../trading-plugin/design-v2-20261006.md
  - ../trading-plugin/review-v3-backend-20261006.md
  - ../trading-plugin/review-v3-product-20261006.md
tags: [workspace, meta, dispatch, trading]
---

# 派单 D-20261006-03：设计 final 稿（**合并正文** · 收 4 类阻塞项）

你是 AdaiOS 的**设计文档编写者**。工作目录 `/Users/adai/Projects/adaios/.worktrees/trading`。
真相源（先读）：`.agents/toolkit/roles/docs-design-writer.md`。模板：`.agents/workspace/_templates/design.md`。

## 背景（材料已前置，不必自己去找）

- **需求**（已定稿）：`.agents/workspace/trading-plugin/requirement.md`
- **现行正文**：`.agents/workspace/trading-plugin/design-v2-20261006.md`（415 行）
- **上一轮增量**：`.agents/workspace/trading-plugin/design-v3-20261006.md`（70 行，只是"本轮改了什么"）
- **两份独立审核（都判「不收敛」）**：`review-v3-backend-20261006.md` · `review-v3-product-20261006.md`
- **人拍板**：`.agents/workspace/trading-plugin/decisions.md`

## 交付（唯一）

**`.agents/workspace/trading-plugin/design-final-20261006.md` —— 合并正文（不是增量）**

以 v2 正文为底，按下表逐节改写。**写完后它取代 v2 / v3 成为唯一正文**（v2 / v3 退为历史，保留可追溯）。
**这就是本轮要治的病**：v3 只改增量、不回写正文 → 同一份设计自相矛盾（§8.1 已改口、§9 仍写"删除"）。

## 必须收掉的阻塞项（逐条给"改在哪节 + 改成什么"）

1. **§9 去留表落成四列**：`端点 | 去留（保留 / 改造 / 删除）| 理由 | 判据`。**补回漏掉的一组** `data/{userId}/trading/memory-cards/`（v3 声称"11 组"、实际只有 10 行）。**删除类必须给反向判据**（有测试 / 有生产调用 / 有前端入口 → 不许默认删）。
2. **§8.1 / §8.2 / §9 三处口径一致**：`TradingContextContributor` 的 `globalContext()` **是活的**（正在注入成本 / 总市值 / 浮盈 / 现金余额）· `MarketContextContributor` 在 `scene=trading` **整表注入**数量 / 成本 / 市值——**两者都写成「改造」项**，各自给**逐字段白名单**（出什么 / 不出什么）。**全文不许再出现"死代码 → 删除"**。
3. **关插件闸门补面 + 验收**：v3 只列了 Feed / 时间线 / 搜索 / 领域活动度 四面，**补两面**——**记忆面**（`BriefAppService` 的 `memoryService.findByDate` 注入段）与**上下文注入面**（`ContextEngine.loadRelatedRecords`）；`/trading/has-activity` 给出**定性**（门控 **或** 只读例外，**二选一，不许留"或"**）。给**逐面验收清单（6 面）**+ 一条「关 → 开可逆」断言（数据保留不删）。
4. **§10 promote 三件**：① 脱敏规则**枚举**（含 `request.sections()`，不只 `note`）② 落点**定死一种**（去"或"）③ 非 owner 的处置口径（403 人话 **或** 落自己的候选区——选定一个）。
5. **§4.1 目录表三列**：`现状 | 目标 | 迁移`，逐项对齐 `knowledge/reference/contracts/data-format-freeze.md`。
6. **S2 的落点写清**：单独一节「**编码准入条件**」，列出**待落产物**——① 定位扩容 RFC 的**要点**（定位变更 + 与 87 课知识库的关系）② roadmap 要增 / 改的条目。**本分支不创建 RFC 文件**，只把要点写进设计并登记待交接。
7. **数量声明必须可机械核对**：凡出现「N 组 / N 条 / N 项」，**紧跟可数清单**（表行数或列表项数必须**等于** N）。

## 纪律（硬要求）

- **第一动作**：`cp .agents/workspace/_templates/design.md .agents/workspace/trading-plugin/design-final-20261006.md`——**先落空文件，再往里填**（这是主链唯一能看到你还活着的方式）；
- **3 分钟内必须看到文件**（骨架：标题 + 目录 + 第一节）；**15 分钟内补完**；**写一节落一节**；
- frontmatter **10 字段**齐全 + `lines:` 与 `wc -l` 一致；
- 中文；**只在** `.agents/workspace/trading-plugin/` 内写文件；
- **不要改** `design-v2/v3` · `requirement.md` · `review-*` · `_index.md`（索引登记由主链统一做）；
- 收工前把本单从 `inbox.md` 的「待处理」移到「已完成」。
