---
title: Context 架构重构：三层上下文 + 标签索引 + 记录晋升
date: 2026-07-20
status: implemented  # 最后更新：2026-07-20
---

# Context 架构重构方案

## 1. 问题汇总

当前 Context Engine 的三大断层：

| 断层 | 表现 | 原因 |
|:----|:-----|:-----|
| 短期断层 | AI 只记得当天的 5 条记录，睡一觉就失忆 | ContextEngine 只读当前日期的 Record |
| 中期断层 | AI 对你没有持续理解，"每次都是第一次见" | Memory 只存不读，从未拼回 Prompt |
| 领域断层 | 聊交易时 AI 不知道你有持仓（除非明确标为 trading 场景） | ContextContributor 只在 scene 匹配时触发 |

## 2. 核心设计

### 2.1 基本原则

1. **不丢数据** — `data/records/` 只追加，永不删 ✅
2. **标签是索引** — 每条记录已有 AI 自动打的标签（部署、后端、交易、生活…），用来替代时间窗口或向量检索 ✅
3. **晋升不迁移** — 记录毕业为 Domain OS 后，原始记录仍留在 `data/records/`，Domain OS 只做结构化的知识投影 ⏳（架构定好但晋升流程未实现）
4. **不依赖数据库** — 延续 File First，索引也基于文件系统 ✅

### 2.2 三层上下文（实现状态）

```
┌─────────────────────────────────────────────────────────┐
│  Layer 1: 当前对话（对话轮次 + 关联记录）                  │
│  范围：当前卡片的对话 + 同主题最近记录                      │
│  索引：cardId（对话分组）+ 标签匹配                         │ ✅ 已实现
├─────────────────────────────────────────────────────────┤
│  Layer 2: 阶段画像（近期关注 + 行为趋势）                   │
│  范围：当前活跃标签下的所有记录（跨天）                      │
│  索引：标签聚合 → 按标签取最新记录                          │ ✅ 已实现
├─────────────────────────────────────────────────────────┤
│  Layer 3: 领域上下文（Domain OS 贡献）                     │
│  范围：每个活跃 Domain OS 的结构化知识                       │
│  索引：Domain 自身的文件资产（os/*/）                       │ ⚠️ 部分实现
│  - globalContext() 接口 ✅                                 │
│  - Trading OS 持仓贡献 ✅                                  │
│  - Life OS / Project OS 预留 ❌                           │
│  - Domain OS 从 os/*/ 读取知识资产 ❌                      │
└─────────────────────────────────────────────────────────┘
```

三条路径独立组装，最终合并为一个 ContextPackage 发给 AI。

## 3. 标签索引（替代 SQL / 向量库）✅ 已实现

### 3.1 索引文件 ✅

当前实现：`TagIndexService` 维护 `data/index/tags.json`（自动维护）。

```json
{
  "tags": {
    "部署": {
      "count": 3,
      "recentRecords": ["rec_20260720_190457", "rec_20260720_180001"],
      "firstAt": "2026-07-20T18:00:00",
      "lastAt": "2026-07-20T19:04:57"
    },
    "交易": {
      "count": 12,
      "recentRecords": ["rec_20260719_090012", ...],
      "firstAt": "2026-07-10T09:00:00",
      "lastAt": "2026-07-20T10:30:00"
    }
  }
}
```

### 3.2 索引维护时机 ✅

每次记录保存时更新：

```
Record 保存 → RecordFileRepository.save()
                ↓
           TagIndexService.update(record)
                ↓
           读取 tags.json → 更新对应标签的 count / recentRecords / lastAt
                ↓
           写回 tags.json
```

### 3.3 索引如何用于 Context 组装 ✅

ContextEngine 现在通过 TagIndexService 按标签检索，不再全量扫描：

```
当前记录内容 → 提取标签（已有，AI 自动生成）
                 ↓
            TagIndexService.lookup(当前标签)
                 ↓
            返回相关记录 ID 列表（跨天）
                 ↓
            组装到 ContextPackage
```

**效果**：聊"部署"时，AI 能看到之前聊部署的记录；聊"交易"时，看到所有交易记录。不按时间窗口切，按主题聚合。

### 3.4 多个标签的交集 ✅

代码实现完全一致。

## 4. 对话分组（Card / Session）✅ 已实现

### 4.1 当前问题

记录是扁平的，没有"这是一组对话"的概念。

### 4.2 已有基础

前端已经传 `cardId`，后端已经支持 `CardRecord`（卡片文件）。

### 4.3 当前实现

Context Engine 组装时，如果传了 `cardId`：

1. 从 `CardFileRepository` 加载该卡片的全部对话轮次 ✅
2. 拼入 Prompt 的"当前会话对话历史"部分 ✅
3. 结合标签索引找到关联历史记录 ✅

当前限制：Card 和 Record 是独立文件存储，没有交叉引用关系。

## 5. 记录晋升（Graduation）❌ 未实现

### 5.1 什么时候触发

设计目标（未实现）：

```
一个标签在 7 天内出现次数 ≥ 当前周平均的 3 倍
  → TagIndexService 标记为 "活跃标签"
  → 连续 3 周标记为 "活跃标签"
  → 触发晋升建议
```

晋升不是自动的，是**建议 → 你确认**。

### 5.2 晋升流程

```
1. Context Engine 检测到标签 "交易" 持续高频
2. AI 提示："你最近聊交易的频率很高，要不要建交易知识库？"
3. 你回复："好"
4. 系统：
   a. 扫描 data/records/ 下所有含 "交易" 标签的记录
   b. 在 data/knowledge/trading/ 下生成摘要文件（不迁移原始记录）
   c. 提示你去 os/trading-os/ 审核
5. 你去 os/trading-os/ 审核后，知识落地到 11-context/
6. TradingContextContributor 自动包含新的知识摘要
```

### 5.3 不迁移原则

晋升**不移动原始记录**，只在 `data/knowledge/` 下生成一份结构化摘要。

```
data/records/2026/07/rec_...md  ← 永远在这，不删不移

data/knowledge/trading/
  summary.md                    ← 晋升后自动生成的摘要（引用原始记录的 ID）
  reviews/                      ← 沉淀的复盘

os/trading-os/                  ← 你审核后的知识，手动管理
  11-context/
```

任何时候回溯：`rec_xxx` → 去 `data/records/` 读原始文件。

## 6. Memory 的反哺 ✅ 已实现

### 6.1 当前状态 → 已修复

Memory 已接入 ContextEngine，按标签聚合后拼入 Prompt。

### 6.2 改进

ContextEngine 注入 `MemoryService`，在组装 Prompt 时加入：

```
## AI 对你的理解（近 7 天）

- 2026-07-19：部署了后端服务，关注生产环境稳定性
- 2026-07-18：在研究交易系统反哺机制
- 2026-07-17：偏好晚上活动，常在 22:00 后提问
```

这样 AI 对你的了解不会"睡一觉就清零"。Memory 就是桥接 Layer 2 和 Layer 3 的通道。

### 6.3 Memory 聚合

不是把 7 天的记忆原文全丢给 AI（太多），而是按标签聚合：

```
标签 "交易" 下的记忆摘要 → "你最近在关注 NVDA 和 AI 板块，对低风险策略感兴趣"
标签 "部署" 下的记忆摘要 → "你刚上线了后端服务，用的是 Temurin 17 + CentOS"
```

## 7. Domain OS 交叉场景 ✅ 已实现

### 7.1 当前问题（已修复）

`TradingContextContributor` 之前只有 `supports("trading")` 才贡献。

### 7.2 当前实现

ContextContributor 接口新增 `globalContext()` 方法，ContextEngine 每次组装时收集所有 Domain 贡献者的全局上下文。当前贡献者：

| 贡献者 | enrich（场景特定） | globalContext（全局） | 状态 |
|:-------|:-----------------|:--------------------|:-----|
| TradingContextContributor | trading 场景下全量持仓表 | 简短持仓概览 | ✅ |
| DefaultContextContributor | 兜底，不贡献 | 空 | ✅ |
| Life OS | - | - | ❌ 预留 |
| Project OS | - | - | ❌ 预留 |

## 8. 实现路线与状态对照

### Phase 1（已完成 ✅）

实际改动与文档计划的差异：

| 文档计划 | 实际实现 | 差异说明 |
|:--------|:---------|:---------|
| 注入 TimelineProjection 替代 RecordRepository | 注入 TagIndexService + 保留 RecordRepository 备用 | 标签索引替代了时间窗口，不用 TimelineProjection |
| 注入 MemoryService，拉近 7 天记忆拼入 Prompt | ✅ 已实现 | 按标签聚合，每个标签取最新 2 条 |
| 对话卡片上下文拼入 Prompt | ✅ 已实现 | 全部轮次拼入 |
| 加日期星期信息 | ✅ 已实现 | |

### Phase 2（已完成 ✅）

| 文档计划 | 实际实现 | 差异说明 |
|:--------|:---------|:---------|
| TagIndexService（标签索引文件维护） | ✅ TagIndexService.java | 字段名 `recentRecords` → `recordIds` |
| RecordFileRepository.save() 触发索引更新 | ✅ setter 注入避免循环依赖 | 通过 TagIndexConfig 在启动时注入 |
| ContextEngine 改用 TagIndexService 取关联记录 | ✅ 已实现 | ContextEngine.loadRelatedRecords() |

### Phase 3（部分完成）

| 文档计划 | 状态 | 文件 |
|:--------|:----|:-----|
| ContextContributor.globalContext() | ✅ 已完成 | ContextContributor.java |
| TradingContributor 实现 globalContext() | ✅ 已完成 | TradingContextContributor.java |
| 晋升检测逻辑（活跃标签判断） | ❌ 未实现 | TagIndexService.getActiveTags() 方法签名存在但未在流程中使用 |
| 晋升自动摘要生成（GraduationService） | ❌ 未实现 | 未创建 |
| `data/knowledge/` 目录创建 | ❌ 未实现 | 未创建 |
| Life OS / Project OS 预留 | ❌ 未实现 | 等待后续创建 |

### Phase 1（当前）

| 改动 | 文件 | 工作量 |
|:----|:-----|:-------|
| 注入 TimelineProjection 替代 RecordRepository | ContextEngine.java | 1 行 |
| 注入 MemoryService，拉近 7 天记忆拼入 Prompt | ContextEngine.java | 5 行 |
| 对话卡片上下文拼入 Prompt | ContextEngine.java | 3 行 |
| 加日期星期信息 | ContextEngine.java | 2 行 |

**Phase 1 不改架构，只是让 AI 不再"失忆"。**

### Phase 2

| 改动 | 文件 | 工作量 |
|:------|:-----|:-------|
| TagIndexService（标签索引文件维护） | 新增 | 50 行 |
| RecordFileRepository.save() 触发索引更新 | 改 1 行 | 1 行 |
| ContextEngine 改用 TagIndexService 取关联记录 | ContextEngine.java | 5 行 |

### Phase 3

| 改动 | 文件 |
|:------|:-----|
| ContextContributor.globalContext() | 接口 + TradingContributor 实现 |
| 晋升检测逻辑 | TagIndexService 新增方法 |
| 晋升自动摘要生成 | 新增 GraduationService |
| `data/knowledge/` 目录创建 | 目录结构 |

## 9. 不丢数据的保障

| 操作 | 保障机制 |
|:----|:--------|
| 晋升 | 不移动原始记录，只读不写 |
| 归档 | 打包 `data/records/YYYY/MM/` 为 tar.gz，不解压可读前几条记录索引 |
| 标签索引 | 索引文件可以从 records/ 目录全量重建，不依赖索引文件 |
| 数据损坏 | Git 追踪 `data/`，可回滚到任意 commit |
