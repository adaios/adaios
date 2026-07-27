---
title: Memory 升级 — 从复读机到真记忆
date: 2026-07-27
status: draft
---

> Memory 是 AdaiOS 的长期记忆层，是 Layer 3（数字身份 + 记忆持久化）的核心。
> 当前实现只是"复读"——把用户的话用完整句子说一遍，完全没有信息增量。
> 本 RFC 把 Memory 真正建设成 AI 对用户的"理解沉淀"。

---

## 一、现状问题

### 核心缺陷

```
用户输入 "上班迟到堵车"
                ↓
AiUnderstanding { summary: "我今天上班迟到因为堵车" }
                ↓
Record.summary  ← 同一段文本 → Memory.summary
卡片显示"我今天上班迟到因为堵车"       记忆文件存"我今天上班迟到因为堵车"
```

| 问题 | 后果 |
|:-----|:------|
| Memory.summary == Record.summary | 记忆没有信息增量，就是原文复述 |
| Record.summary 是完整句子不是简短标记 | 卡片上的"总结"毫无价值 |
| ai_note 被前端过滤 | Memory 在 Feed 里不可见 |
| 没有 insight | AI 的理解无法沉淀给用户看 |

---

## 二、新架构：两条线

```
AI 输出时分开两条线：

AiUnderstanding {
  summary: "上班迟到·堵车",        ← 简短标记（给 record 标题/检索）
  insight: "通勤受路况影响，上班易迟到",  ← 洞察理解（给 memory 沉淀）
  tags: ["迟到", "堵车", "通勤"],
  sentiment: "negative",
  domain: "life"
}
         │                     │
         ▼                     ▼
   Record.title/summary    Memory.summary
   用于卡片显示/检索        沉淀为长期记忆
   3-5词简洁概括            一句话洞察
```

### 改造后的数据流

```
用户输入
    ↓
ContextEngine + prompt（要求同时输出 summary + insight）
    ↓
AiClient.understand()
    ↓
AiUnderstanding { summary, insight, tags, domain, ... }
    ↓                                   ↓
RecordFileRepository.save()         MemoryService.persist()
    ↓                                   ↓
record.summary = "上班迟到·堵车"     memory.summary = "通勤受路况影响..."
record.insight = null                 memory.insight = 同上（冗余字段
                                     但便于前端 query）
    ↓                                   ↓
Feed 卡片显示简短 summary             Memory 页面显示 insight
（3-5 词）                            Context Engine 回读时用 insight
```

---

## 三、具体改动清单

### Phase 1 — 数据模型 + Prompt（后端核心）

| 文件 | 改动 |
|:-----|:------|
| `infrastructure/ai/llm/AiUnderstanding.java` | **新增 `insight` 字段** |
| `kernel/context/engine/ContextEngine.java` | STATEMENT prompt 要求同时输出 `summary`(3-5词) 和 `insight`(一句话理解) |
| `infrastructure/ai/llm/LlmResponseParser.java` | 解析 `insight` 字段 |
| `kernel/memory/Memory.java` | **`summary` 字段不再存 record 复述，改为存 insight** |
| `infrastructure/ai/llm/DeepSeekAiClient.java` | ANALYSIS system prompt 增加 insight 指导 |
| `kernel/record/ContentRecord.java` | `summary` 字段类型不变（仍存简短标记） |

### Phase 2 — Feed 和 Memory 页面（前后端联动）

| 文件 | 改动 |
|:-----|:------|
| `application/FeedAppService.java` | Feed 记录的 summary 展示用简短格式；卡片型（type=card）不变 |
| `interfaces/MemoryController.java` | Memory 返回完整 insight 文本 |
| `pages/memory_page.dart` | 每条 Memory 展示 insight（而非 summary），支持点开查看详情 |
| `widgets/feed_card.dart` | log 卡片不再显示 `summary` banner，只留 `insight` 入口 |
| `services/api_service.dart` | DTO 对齐 |

### Phase 3 — 跨记录聚合（可选）

| 文件 | 改动 |
|:-----|:------|
| `application/MemorySummarizerService.java` | **新服务** — 扫描当日所有 Memory，生成"今天对你的新认知" |
| `interfaces/BriefController.java` | Brief 可以引用当天的记忆洞察 |
| `pages/memory_page.dart` | 当天首行显示"今天 AI 对你的理解"聚合卡片 |

---

## 四、Prompt 改造（核心）

### 现在

```
分析这条记录，输出 JSON：
{
  "summary": "一句话摘要",
  "tags": [...],
  "domain": "..."
}
```

### 改后 — STATEMENT

```
分析这条记录，输出 JSON：
{
  "summary": "3-5个词概括，简短标记，不要完整句子",
  "insight": "一句话表达你对用户的理解，有信息增量，不要复述原文",
  "tags": [...],
  "domain": "..."
}
```

### CHAT 模式（QUESTION）

QUESTION 的回答本身是 AI 的回复，不需要额外生成 insight。但每次回答末尾的 JSON 标注中的 `summary` 仍然保留，用于 Memory 沉淀。

---

## 五、Memory 的新展示方式

### Feed 卡片变化

```
当前：                       改后：
┌─────────────────┐         ┌─────────────────┐
│ log  14:30      │         │ log  14:30      │
│ 上班迟到堵车     │         │ 上班迟到·堵车    │  ← 简短标记
│                 │         │                 │
│ ✅ 我今天上班…   │         │                 │  ← 不再显示复述
│                 │         │                 │
│ [迟到] [堵车]   │         │ [迟到] [堵车]   │
│ ─── ask ───    │         │ ─── ask ───    │
└─────────────────┘         └─────────────────┘
```

### Memory 页面变化

```
当前（无独立展示）：             改后：
                                  🧠 记忆 · 7月27日
                                   ← 2026-07-27 →
                                  
                                  通勤受路况影响，上班易迟到
                                  建议提前出门或备选路线
                                  [迟到] [堵车]   14:30
                                  
                                  今天讨论了项目 bug 修复方向
                                  主要关注 domain 判定和滚动修复
                                  [项目] [Bug]    16:20
```

---

## 六、Memory 回读方式不变

Context Engine 已有的 `loadMemorySummary()` 方法——把近 7 天记忆按标签聚合回读给 AI——**这个机制不变**，只是读取的内容从原来的"复述"升级为"洞察"，AI 获得的上下文质量更高。

---

## 七、不做的事

| 功能 | 原因 |
|:-----|:------|
| 向量化检索 | 标签聚合 + 关键词匹配在 MVP 阶段够用 |
| 记忆修改/删除 | 第一卷不涉及记忆管理，只做展示 |
| 记忆自动过期 | 留待 Layer 3 整体建设 |
| 每日总结自动推送 | 等 Brief 系统升级时再一起做 |

---

## 八、改动总览

| 阶段 | 文件数 | 改动量 | 风险 |
|:-----|:------:|:------:|:----:|
| Phase 1: 数据模型+Prompt | ~5 后端文件 | 小～中 | 低（只影响新产生的内容，历史数据不受影响） |
| Phase 2: 展示 | ~5 前后端文件 | 中 | 中（Feed 卡片展示变化需要验证） |
| Phase 3: 聚合 | ~3 文件 | 中 | 低（新服务，不破坏现有逻辑） |

> **Phase 1 即可产生效果，无需等全部完成再上线。**
