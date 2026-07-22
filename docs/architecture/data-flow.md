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
    └── 正则兜底
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
| **意图识别** | 正则/AI 兜底 | 手动指定或 cardId 延续 |
| **Context** | 简版（Identity + 今日历史） | 完整版（Identity + 历史 + 对话记录 + Domain） |
| **AI 模式** | ANALYSIS（0.3 temp，JSON 输出） | CHAT（0.7 temp，自然对话） |
| **存储** | `data/records/` + `data/memory/` + `data/index/` | `data/records/` + `data/memory/` + card 文件 + `data/index/` |
| **前端表现** | 普通记录卡片（摘要 + 标签） | 聊天气泡（一问一答） |

---

## 数据存储位置

| 数据 | 存储位置 | 读写方 |
|:----|:---------|:-------|
| 原始记录 | `data/records/YYYY/MM/rec_xxx.md` | RecordFileRepository |
| 对话轮次 | `data/records/YYYY/MM/card_xxx.md` | CardFileRepository |
| AI 记忆 | `data/memory/YYYY/MM.md` | MemoryService |
| 用户身份 | `data/identity/profile.md` | IdentityRepository |
| 标签索引 | `data/index/tags.json` | TagIndexService |
| 交易知识 | `os/trading-os/11-context/` | adai-core 不读取（仍隔离） |
| 持仓数据 | `data/trading/positions.md` | PositionFileRepository |

---

## Context Engine 组装流程

```
ContextEngine.compose(scene, record)
    │
    ├── loadIdentitySummary()
    │       → IdentityRepository.load() → IdentityProfile
    │
    ├── loadSessionHistory(record)
    │       → RecordRepository.findAll()
    │       → 过滤当天、排除当前、取最近 5 条
    │       → "## 今日会话历史"
    │
    ├── enrichFromContributors(scene, identityRef, record)
    │       → 遍历 ContextContributor 列表
    │       → contributor.supports(scene) → contributor.enrich()
    │       → 当前只有 TradingContextContributor（持仓数据）
    │
    └── buildPrompt(...)
            → 组合 identity + sessionHistory + 当前记录 + domainContext
            → 场景相关指令（JSON 输出 / 自然对话）
            → ContextPackage {scene, prompt, recordTitle, recordContent, ...}
```

---

## 当前断裂点

1. **adai-core 不读 `os/trading-os/`** — 16 课的交易知识（87 条规则、678 行术语）存在但不可用
2. **Feedback Loop 不存在** — AI 给建议 → 用户执行 → 结果不回系统
3. **Search 不存在** — 标签索引已有，但没有搜索接口
4. **Knowledge 是空占位** — `kernel/knowledge/` 只有 package-info.java
