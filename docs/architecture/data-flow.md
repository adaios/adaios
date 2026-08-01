# AdaiOS 数据流（当前实际实现）

> 基于代码实现，非设计文档。最后更新：2026-07-20

---

## 总体数据流

```
用户输入（App）
    │
    ▼
POST /api/v1/records   ──── 统一入口
    │
    ▼
IntentRecognizer
    ├── 手动指定（intent 字段）
    ├── AI 识别（DeepSeek）
    └── AI 失败 → 抛异常（不静默降级）
    │
    ├── Intent.STATEMENT ────────────────────────────── Intent.QUESTION
    │                                                      │
    ▼                                                      ▼
handleStatem()                                    handleQuestion()
    │                                                      │
    │                                              ContextEngine.compose("question", record)
    │                                                  │
    │                                          ┌───────┴────────┐
    │                                          │  Identity       │
    │                                          │  + 今日会话历史  │
    │                                          │  + Domain贡献   │
    │                                          │  + 对话历史(card)│
    │                                          └───────┬────────┘
    │                                                  │
    ▼                                                  ▼
AiClient.understand()                          AiClient.understand() / .chat()
(ANALYSIS 模式, 0.3 temp)                      (CHAT 模式, 0.7 temp, 多轮 messages)
    │                                                  │
    │  LLM 返回 JSON:                                  │  LLM 返回自然语言 + 末尾 JSON
    │  {summary, tags}                                 │
    │                                                  │
    ▼                                                  ▼
RecordFileRepository.save()                      QuestionAppService.answer()
    │  → data/records/YYYY/MM/rec_xxx.md               │
    │                                                  │
    │  (STATEMENT 不建卡)                               ├── CardFileRepository（追加 AI 轮次）
    │                                                  │
    ▼                                                  ▼
MemoryService.persist()  ───── 所有意图都走 ────── MemoryService.persist()
    │                                                  │
    ▼                                                  ▼
data/memory/YYYY/MM.md                            data/memory/YYYY/MM.md
    │                                                  │
    ▼                                                  ▼
TagIndexService.update()                           TagIndexService.update()
    │                                                  │
    ▼                                                  ▼
data/index/tags.json                               data/index/tags.json
```

---

## STATEMENT 与 QUESTION 对比

| 阶段 | STATEMENT（记录） | QUESTION（提问） |
|:----|:-----------------|:-----------------|
| **入口** | `POST /api/v1/records` | 同上 |
| **意图识别** | 纯 AI（失败抛异常） | 手动指定或 cardId 延续 |
| **Context** | 简版（Identity + 今日历史） | 完整版（Identity + 历史 + 对话记录 + Domain） |
| **AI 模式** | ANALYSIS（0.3 temp，JSON 输出） | CHAT（0.7 temp，自然对话） |
| **存储** | `data/records/` + `data/memory/` + `data/index/` | `data/records/` + `data/memory/` + card 文件 + `data/index/` |
| **前端表现** | 普通记录卡片（摘要 + 标签） | 聊天气泡（一问一答） |

---

## 数据存储位置

| 数据 | 存储位置 | 读写方 |
|:----|:---------|:-------|
| 原始记录 | `data/records/YYYY/MM/rec_xxx.md` | RecordFileRepository |
| 对话轮次 | `data/records/cards/YYYY/MM/DD/card_xxx.md` | CardFileRepository |
| AI 记忆 | `data/memory/YYYY/MM.md` | MemoryService |
| 用户身份 | `data/identity/profile.md` | IdentityRepository |
| 标签索引 | `data/index/tags.json` | TagIndexService |
| 交易知识 | `os/trading-os/11-context/` | TradingKnowledgeSource（读取） |
| 项目管理知识 | `os/project-os/11-context/` | ProjectKnowledgeSource（读取） |
| 持仓数据 | `data/trading/positions.md` | PositionFileRepository |
| 项目任务 | `data/project/tasks/YYYY/MM.md` | ProjectFileRepository |
| 复盘笔记 | `data/trading/reviews/` | TradingReviewFileRepository |

---

## Context Engine 组装流程

```
ContextEngine.compose(scene, record, cardId)
    │
    ├── loadIdentitySummary()
    │       → IdentityRepository.load() → IdentityProfile
    │
    ├── loadCardContext(cardId)        （QUESTION + cardId 时加载全部对话轮次）
    │       → CardFileRepository.findById(cardId) → 轮次列表
    │
    ├── loadRelatedRecords(record)     （标签关联历史；无标签回退最近记录）
    │       → TagIndexService.findRelatedIds(tags, 20) / findAll()
    │
    ├── loadSearchResults(record)      （全文搜索，内容关键词匹配）
    │       → SearchService.search(前50字) → 最多 10 条
    │
    ├── loadMemorySummary()            （近 7 天记忆按标签聚合）
    │       → MemoryService.recent(7)
    │
    ├── detectDomainScene(record)      （内容关键词 → trading/project/life）
    │
    ├── loadKnowledgeContext(domainScene)
    │       → 遍历 KnowledgeSource（Trading/Life/Project）globalContext() + enrich(scene)
    │
    ├── enrichFromContributors(domainScene, identityRef, record)
    │       → contributor.supports(scene) → enrich()
    │       → Trading/Life/Project ContextContributor
    │
    ├── loadGlobalContext()            （所有非 default Contributor 的 globalContext，含行情）
    │
    └── buildPrompt(...)
            → 组合 identity + 卡片 + 历史 + 搜索 + 记忆 + 知识 + 领域 + 全局 + 当前记录
            → 场景指令（QUESTION: 自然对话 + 末尾 JSON；其他: JSON 输出）
            → ContextPackage {scene, prompt, recordTitle, recordContent, relatedRefs, ...}
```

---

## 历史断裂点（均已修复 / 待办标注）

1. ~~adai-core 不读 `os/trading-os/`~~ ✅ 已修复 — TradingKnowledgeSource 读取 11-context/，交易知识进 Context
2. **Layer 6 反馈闭环不完整** 📋 — 反哺依赖真实 conflicts 数据（见 REVIEW.md #23）
3. ~~Search 不存在~~ ✅ 已修复 — `kernel/search/SearchService` 全文搜索已实现
4. ~~Knowledge 是空占位~~ ✅ 已修复 — Trading/Life/Project KnowledgeSource 均已实现
