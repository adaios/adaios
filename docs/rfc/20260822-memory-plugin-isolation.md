---
title: Memory 与插件隔离——现状分析与方向（记忆属于框架，不按插件隔离；补 domain 标记留过滤基础）
date: 2026-08-22
status: draft
depends-on:
  - 20260814-domain-plugin-model.md
related:
  - ../architecture/framework-plus-plugin-model.md
  - ../architecture/memory-os-design.md
  - ../reference/feature-reference.md
tags: [rfc, memory, plugin, isolation]
---

# Memory 与插件隔离

> **讨论记录 RFC**：用户提问「既然存在插件（比如交易），那么记忆是否需要隔离呢，目前现状如何」——2026-08-22 会话调研后确认：**记忆属于框架层（人人都有），不该按插件物理隔离**（形态既定决策）；但存在两个实现缺口：① domain 信息在沉淀时被丢弃；② 插件关闭后已沉淀的相关记忆仍全量召回。本 RFC 沉淀结论与待拍板点，**讨论与实施分离：未拍板前不动代码**。

---

## 一、结论摘要

| 层面 | 结论 | 现状 |
|:-----|:-----|:-----|
| 形态层 | 记忆属于框架（「你是谁」），不属于插件（「你能做什么」）——**不按插件隔离** | 设计既定（总纲 + RFC 20260814） |
| 用户级隔离 | 记忆按用户隔离 | ✅ 已实现（`data/{userId}/memory/` + 每用户写锁） |
| 插件级隔离 | 现状不隔离，且**缺 domain 标记** | ⚠️ 缺口（AiUnderstanding 有 domain，Memory 丢弃） |
| 插件关闭后 | 已沉淀的域相关记忆仍全量召回 | ⚠️ 缺口（召回侧无插件过滤） |

## 二、现状证据（代码级，2026-08-22 核实）

### 写入侧（沉淀）

1. **Memory 无 domain 字段**：`kernel/memory/Memory.java` record 仅含 `id / recordId / cardId / kind / summary / patterns / preferences / tags / sentiment / actionable / suggestion / createdAt / topic / superseded / evolvedTo / doneAt / lastConfirmed`——无领域维度。
2. **domain 被丢弃**：`AiUnderstanding` 有 `domain`（life/trading/project），但 `Memory.fromUnderstanding()` 只带 insight/patterns/preferences/tags/sentiment/actionable/actionSuggestion，**未透传 domain**。
3. **沉淀入口统一**：RecordController / ConversationController / QuestionAppService / MediaRecordAppService / RecordRetryService / RecordFlowAppService 全部走 `Memory.fromUnderstanding`（或 fromImageRecord / fromContentFallback），落同一文件池 `data/{userId}/memory/YYYY/MM.md`。
4. **交易记录进记忆**：`TradingAppService.writeTradingRecord` 写 `domain=trading` 的 Record（标题如「买入 京东方A 1000股@5.20」，tags=`[trading, 交易]`）→ 走统一 AI 理解链路 → 可能沉淀记忆（P2-9 已核实 writeTradingRecord → Timeline/Search/Memory 全链路）。

### 读取侧（召回）

5. **ContextEngine.loadMemorySummary**：`memoryService.recentActive(userId, 7天)` **全量回读**，按标签聚合注入 prompt，**无插件过滤**（`enabledPlugins` 未参与）。
6. **对比：其余注入源均已门控**——`loadKnowledgeContext`（trading/project 知识源按插件过滤）、`enrichFromContributors`（contributorAllowed）、`detectDomainScene`（D5 只在启用插件间判定）。**Memory 是全链路唯一未做插件门控的注入源**（与「记忆人人都有」的形态一致，但带来 §三 缺口）。
7. **其他消费方同样全量**：LifeContextContributor / LifeKnowledgeSource / FeedAppService（按日期全量 + findPendingActions 待办）/ BriefAppService（recent 7 天）。

## 三、是否隔离的讨论

### 3.1 不需要按插件物理隔离（设计意图，成立）

- **形态总纲**：框架装「你是谁」（方法论/记忆/纪律/流程约定），插件装「你能做什么」（交易知识/规则）——记忆明确归框架。
- **feature-reference**：「Kernel 基础服务（记录/问答/记忆/档案/时间线/搜索/待办）不是插件，人人都有」。
- **memory-os-design §3**：Memory OS 关注「人」（身份/偏好/行为模式/目标），Domain OS 关注「事」（领域数据/规则/流程）。
- **memory-os-design §11 经典用例**：用户问「我要不要加仓」→ Context Engine 同时加载 Memory（「用户容易追涨」）+ Trading OS（当前仓位/规则）——**跨域联合注入是价值，不是泄漏**。

→ 「用户容易追涨」是**用户画像**，不是交易插件的私有资产；按插件隔离反而破坏跨域理解。

### 3.2 但有两个真实缺口

1. **写侧丢 domain**：即使将来想按域召回/屏蔽，也没有数据基础——domain 信息在沉淀时已丢失。
2. **插件关闭后记忆残留**：用户关闭 trading 插件后——交易端点 403 ✅、交易知识不注入 ✅、Feed 行情卡消失 ✅，**但已沉淀的交易相关记忆（含 actionable 待办，如「今日操作确认」）仍会全量进 prompt 与 Feed 待办** ⚠️。

## 四、建议方案（讨论，未实施）

1. **写侧补全 domain**：`Memory` 加 `domain` 字段，`fromUnderstanding` 透传 `understanding.domain()`（低成本、向后兼容、一次性）。
2. **召回侧保持跨域（默认不变）**：不因 domain 存在而默认过滤——跨域理解是设计价值。
3. **插件关闭时的分类策略（待拍板）**：
   - **事实/行动类**（kind = fact / insight / decision，如「买入京东方A」「今日操作确认」）→ 随插件门控屏蔽（插件能力衍生，能力移除后不再注入）。
   - **行为模式/偏好类**（kind = pattern / preference，如「容易追涨」）→ 保留（用户画像，不因插件关闭而遗忘）。
4. **向后兼容**：旧记忆无 domain → 按用户级处理（不屏蔽）。

## 五、待拍板点

| # | 问题 | 建议 |
|:-:|:-----|:-----|
| P1 | 是否同意「Memory 加 domain 字段」（写侧一次性补全）？ | 同意——为将来任何过滤/展示留基础；不做也无需任何改动 |
| P2 | 插件关闭后，事实/行动类记忆是否随插件屏蔽？ | 同意分类处理（§四-3）；涉及「插件关闭 = 能力移除 vs 数据保留」的产品语义 |

## 六、关联

- RFC 20260814 Domain=插件模型（D5 domain 判定随插件门控）
- framework-plus-plugin-model（框架+插件形态总纲）
- memory-os-design（Memory OS 设计，draft）
- REVIEW 179（用户层鉴权——记忆用户级隔离的前提是鉴权，另行处理）
