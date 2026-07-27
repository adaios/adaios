---
title: Memory 升级路线 — 从复读机到真记忆
date: 2026-07-27
status: revised
relation: 详见 [memory-os-design.md](../architecture/memory-os-design.md) 定义 Memory OS 长期架构
---

> Memory 是 AdaiOS 的长期记忆层，是 Layer 3（数字身份 + 记忆持久化）的核心。
> 当前实现只是"复读"——把用户的话用完整句子说一遍，完全没有信息增量。
>
> 本 RFC 定义从"修复复读机"到"建设真正的 Memory OS"的完整路线，分 5 个 Phase 渐进落地。
>
> **架构总纲**：[memory-os-design.md](../architecture/memory-os-design.md) 定义了 Memory OS 的职责边界、7 种数据类型、与 Context Engine / Domain OS 的关系。本 RFC 是它的实现路线图。

---

## 目录

- [Phase 0 — 修复复读机（短期战术）](#phase-0--修复复读机短期战术)
- [与 Memory OS Design 的关系](#与-memory-os-design-的关系)
- [Phase 1 — Memory 多类型化](#phase-1--memory-多类型化)
- [Phase 2 — Memory 生命周期 + 评价机制](#phase-2--memory-生命周期--评价机制)
- [Phase 3 — Context Engine 深度集成](#phase-3--context-engine-深度集成)
- [Phase 4 — 知识反哺闭环](#phase-4--知识反哺闭环)
- [不做的事](#不做的事)
- [路线总览](#路线总览)

---

## Phase 0 — 修复复读机（短期战术）

**目标**：把当前"原文复述"升级为"一句话洞察"，可独立上线。

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

### 新架构：两条线

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

### 具体改动清单

#### Phase 0-A — 数据模型 + Prompt（后端核心）

| 文件 | 改动 |
|:-----|:------|
| `infrastructure/ai/llm/AiUnderstanding.java` | **新增 `insight` 字段** |
| `kernel/context/engine/ContextEngine.java` | STATEMENT prompt 要求同时输出 `summary`(3-5词) 和 `insight`(一句话理解) |
| `infrastructure/ai/llm/LlmResponseParser.java` | 解析 `insight` 字段 |
| `kernel/memory/Memory.java` | **`summary` 字段不再存 record 复述，改为存 insight** |
| `infrastructure/ai/llm/DeepSeekAiClient.java` | ANALYSIS system prompt 增加 insight 指导 |
| `kernel/record/ContentRecord.java` | `summary` 字段类型不变（仍存简短标记） |

#### Phase 0-B — Feed 和 Memory 页面（前后端联动）

| 文件 | 改动 |
|:-----|:------|
| `application/FeedAppService.java` | Feed 记录的 summary 展示用简短格式；卡片型（type=card）不变 |
| `interfaces/MemoryController.java` | Memory 返回完整 insight 文本 |
| `pages/memory_page.dart` | 每条 Memory 展示 insight（而非 summary），支持点开查看详情 |
| `widgets/feed_card.dart` | log 卡片不再显示 `summary` banner，只留 `insight` 入口 |
| `services/api_service.dart` | DTO 对齐 |

### Prompt 改造（核心）

**现在**：
```
分析这条记录，输出 JSON：
{
  "summary": "一句话摘要",
  "tags": [...],
  "domain": "..."
}
```

**改后 — STATEMENT**：
```
分析这条记录，输出 JSON：
{
  "summary": "3-5个词概括，简短标记，不要完整句子",
  "insight": "一句话表达你对用户的理解，有信息增量，不要复述原文",
  "tags": [...],
  "domain": "..."
}
```

**CHAT 模式（QUESTION）**：
QUESTION 的回答本身是 AI 的回复，不需要额外生成 insight。但每次回答末尾的 JSON 标注中的 `summary` 仍然保留，用于 Memory 沉淀。

### Memory 的新展示方式

**Feed 卡片变化**：
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

**Memory 页面变化**：
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

### Memory 回读方式不变

Context Engine 已有的 `loadMemorySummary()` 方法——把近 7 天记忆按标签聚合回读给 AI——**这个机制不变**，只是读取的内容从原来的"复述"升级为"洞察"，AI 获得的上下文质量更高。

---

## 与 Memory OS Design 的关系

[Memory OS Design](../architecture/memory-os-design.md) 是长期架构纲领。它与本 RFC 的关系：

| 层面 | Memory OS Design | 本 RFC |
|:-----|:-----------------|:-------|
| 定位 | 理想架构：Memory 是"个人长期认知系统" | 实现路线图：从复读机到真记忆的 5 个 Phase |
| 数据 | 7 种类型：identity/preference/behavior/pattern/experience/decision/goal | Phase 0 只做 flat insight；Phase 1 开始引入多类型 |
| 流程 | Event → Domain OS + Memory Extraction → Evaluation → 沉淀 | Phase 2 引入 Candidate → Evaluation 生命周期 |
| 影响 AI | Memory 不直接暴露，而是让 AI 内部认知个性化 | Phase 3 开始深度集成到 Context Engine |

**关键判断**：Memory OS Design 中定义的 7 种数据类型，不是一上来就要全部实现。路线图的节奏是——先用 Phase 0 修复最痛的问题（复读机），再逐步引入类型化、生命周期、深度集成。

---

## Phase 1 — Memory 多类型化

**目标**：从 flat Memory 升级为 7 种类型沉淀，先做 pattern + preference。

### 为什么先做 pattern 和 preference

| 类型 | 优先级 | 理由 |
|:-----|:------:|:------|
| **pattern** | P0 | Memory OS Design 强调"Pattern 是最重要资产"——长期规律直接影响 AI 个性化 |
| **preference** | P0 | 影响 AI 表达方式，改动量小见效快 |
| behavior | P1 | 和 pattern 有重叠，可以等 pattern 稳定后再细化 |
| experience | P1 | 人生经历沉淀，重要但不紧急 |
| decision | P2 | 避免重复讨论历史决定，但当前单人有记忆就够了 |
| identity | P2 | 已有 `data/identity/` 存在，后续统一 |
| goal | P2 | 长期目标需要 AI 主动识别，目前没有这个能力 |

### 数据模型变化

```
当前（Phase 0）:
data/memory/2026/07/2026-07-27.md
├── summary: "通勤受路况影响，上班易迟到"
├── tags: ["迟到", "堵车"]
└── insight: "..."

改后（Phase 1）:
data/memory/2026/07/2026-07-27.md
├── summary: "通勤受路况影响，上班易迟到"
├── tags: ["迟到", "堵车"]
├── insight: "..."
├── patterns:                          ← 新增
│   └── - type: pattern
│         content: "用户面对复杂问题时倾向先建体系"
│         confidence: 0.85
│         source_events: [...]
├── preferences:                       ← 新增
│   └── - type: preference
│         content: "用户喜欢系统化分析"
│         confidence: 0.9
```

### 改动清单

| 文件 | 改动 |
|:-----|:------|
| `kernel/memory/Memory.java` | 新增 `patterns` / `preferences` 字段 |
| `kernel/memory/MemoryService.java` | `persist()` 支持类型化写入，`loadMemorySummary()` 回读时按类型聚合 |
| `infrastructure/ai/llm/AiUnderstanding.java` | 新增 `patterns` / `preferences` 可选字段 |
| `kernel/context/engine/ContextEngine.java` | Prompt 增加 pattern/preference 识别要求 |
| `infrastructure/ai/llm/LlmResponseParser.java` | 解析 pattern/preference 结构 |
| `infrastructure/ai/llm/DeepSeekAiClient.java` | ANALYSIS prompt 增加 pattern 发现指令 |

### 何时做

当 Phase 0 上线并验证 insight 质量稳定后。预计上线后观察 1-2 周，确认 AI 能稳定输出有意义的 insight，再进入 Phase 1。

---

## Phase 2 — Memory 生命周期 + 评价机制

**目标**：引入 Candidate → Evaluation → 沉淀/淘汰 流程，防止 Memory 膨胀和低质量记忆。

### 生命周期

```
                Candidate Memory
                       |
                 Evaluation（评分）
                       |
              ┌────────┴────────┐
              │                 │
          Temporary          淘汰
          Memory
              │
        再次 Evaluation
              │
         ┌────┴────┐
         │         │
      Stable     淘汰
      Memory
```

### 评分公式

```
Memory Score = 出现频率 × 0.3
             + 时间相关性 × 0.2
             + 用户确认 × 0.3
             + 长期影响 × 0.2
```

| 因子 | 说明 |
|:-----|:------|
| 出现频率 | 同一 pattern 在多少条 record 中出现 |
| 时间相关性 | 近期出现的权重更高 |
| 用户确认 | 用户是否点赞/采纳了 AI 的 insight（Phase 2 暂不实现 UI，预留字段） |
| 长期影响 | 该记忆对 AI 个性化的贡献度（AI 自评） |

### 改动清单

| 文件 | 改动 |
|:-----|:------|
| `kernel/memory/Memory.java` | 新增 `status`（candidate/temporary/stable）、`confidence`、`importance`、`source_events` 字段 |
| `kernel/memory/MemoryService.java` | 新增 `evaluateMemory()`、`promoteMemory()`、`demoteMemory()` 方法 |
| `kernel/memory/MemoryEvaluationService.java` | **新服务** — 评分计算 + 定时评估 |
| `kernel/memory/MemoryRepository.java` | 支持按 status 查询、按评分排序 |

### 何时做

Phase 1 运行一个月左右，积累了足够多的 typed memory 后。需要先有数据，评价才有意义。

---

## Phase 3 — Context Engine 深度集成

**目标**：让 AI 回复时真正"记得你是谁"——Memory 不再是事后记录，而是实时影响 AI 表达。

### 改造后的 AI 回复流程

```
User Query
    ↓
Intent Detection
    ↓
Context Engine ───→ Memory Retrieval（按类型 + 相关性）
    │                      ├── pattern: 当前场景匹配的行为模式
    │                      ├── preference: 表达方式偏好
    │                      └── decision: 相关历史决策
    ↓                           ↓
Domain Retrieval          Memory 加入 prompt 但不暴露来源
    ↓                           ↓
Prompt Assembly ───────────→ "结合你过去的经验…"
    ↓
AI Response
```

### 关键规则

**Memory 不直接暴露**——错误示范：
```
根据你的 Memory，你容易追涨。
```

正确示范：
```
结合你过去几次类似交易复盘，这里需要注意追高风险。
```

### 改动清单

| 文件 | 改动 |
|:-----|:------|
| `kernel/context/engine/ContextEngine.java` | `loadMemorySummary()` 升级为按场景类型化召回 |
| `kernel/context/engine/ContextContributor.java` | 接口可选新增 `memoryFilter()` 回调 |
| `kernel/memory/MemoryService.java` | 新增 `queryRelevant(pattern, domain, limit)` 方法 |
| `infrastructure/ai/llm/DeepSeekAiClient.java` | System prompt 增加"Memory 是内部认知不暴露原文"指令 |
| `infrastructure/ai/llm/AiResponse.java` | 新增可选的 `memoryInfluence` 元信息（调试用） |

### 何时做

Phase 2 稳定后，且 Memory 数据量足够（至少数百条 typed memory）。这是"系统真正个性化"的关键阶段，需要前几个 Phase 沉淀的数据作基础。

---

## Phase 4 — 知识反哺闭环

**目标**：长期 pattern 积累到一定程度后，自动固化为 KnowledgeSource，供给 Context Engine。

### 流程

```
Phase 3: Context Engine 运行时读取 pattern
    ↓
pattern 累积
    ↓
置信度超过阈值（如 confidence > 0.9，持续 30 天）
    ↓
自动写入 KnowledgeSource
    ↓
Context Engine 的 globalContext 包含该知识
    ↓
即使没有明确 memory 召回，AI 也"自然知道"
```

### 示例

```
Pattern: "用户倾向先建立框架再执行"
    ↓（持续 30 天，confidence > 0.9）
    ↓
KnowledgeSource: "用户的工作风格是框架先行"
    ↓
AI 默认按此风格回复——先给框架，再谈执行。
```

### 改动清单

| 文件 | 改动 |
|:-----|:------|
| `kernel/knowledge/KnowledgeSource.java` | 接口新增 `fromMemory(Memory memory)` 工厂方法 |
| `kernel/memory/MemoryToKnowledgeService.java` | **新服务** — 定期扫描高置信度 pattern，生成 KnowledgeSource |
| `kernel/context/engine/ContextEngine.java` | `globalContext()` 包含 KnowledgeSource 中来自 Memory 的部分 |

### 何时做

Phase 3 稳定运行后，且已有 pattern 累积到足够置信度。这是 Layer 6（知识反哺）的具体实现。

---

## 路线总览

```
Phase 0 ─── 修复复读机（1-2 周）
   ↓              summary/insight 拆分，Prompt 改造
Phase 1 ─── Memory 多类型化（Phase 0 + 1-2 周）
   ↓              先做 pattern + preference
Phase 2 ─── 生命周期 + 评价（Phase 1 + 2-3 周）
   ↓              Candidate → Evaluation → 沉淀/淘汰
Phase 3 ─── Context Engine 深度集成（Phase 2 + 2-3 周）
   ↓              AI 回复时按场景类型化召回 Memory
Phase 4 ─── 知识反哺闭环（Phase 3 + 1-2 周）
                   Pattern → KnowledgeSource
```

### 改动总览

| 阶段 | 文件数 | 改动量 | 风险 | 前置条件 |
|:-----|:------:|:------:|:----:|:---------|
| **Phase 0** | ~10 前后端 | 小～中 | 低 | 无 |
| **Phase 1** | ~6 后端 | 中 | 低 | Phase 0 验证通过 |
| **Phase 2** | ~4 后端 | 中 | 中 | Phase 1 数据积累 |
| **Phase 3** | ~5 后端 | 中 | 中 | Phase 2 稳定 |
| **Phase 4** | ~3 后端 | 小 | 低 | Phase 3 稳定 + 数据充足 |

> **每个 Phase 可独立上线，无需等全部完成**。Phase 0 最痛、改动最小、风险最低，应该立即开始。

### 不做的事（所有 Phase）

| 功能 | 原因 |
|:-----|:------|
| 向量化检索 | 标签聚合 + 关键词匹配在 MVP 阶段够用 |
| 记忆修改/删除 UI | 第一卷不涉及记忆管理，只做展示 |
| 记忆自动过期 | 留待 Layer 3 整体建设 |
| 每日总结自动推送 | 等 Brief 系统升级时再一起做 |
| 知识图谱 | OOS（超出当前范围），留待 Phase 4 之后评估 |
| 自动 Agent 管理 Memory | 需要先有 Agent 基础设施 |
| 用户手动编辑 Memory | 需要先有 Memory 管理 UI |
