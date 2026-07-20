# AdaiOS 系统架构

Version: 0.3

---

## 一、整体架构图

```mermaid
graph TB
    subgraph User["用户层"]
        App["Flutter App<br/>(Web / Android / iOS)"]
    end

    subgraph Interface["入站适配层 — interfaces"]
        RecordController["RecordController<br/>POST /api/v1/records"]
        FeedController["FeedController"]
        TimelineController["TimelineController"]
        IntentRecognizer["IntentRecognizer<br/>STATEMENT / QUESTION"]
    end

    subgraph Application["用例编排层 — application"]
        QuestionAppService["QuestionAppService<br/>问答处理"]
        BriefAppService["BriefAppService<br/>今日简报"]
        FeedAppService["FeedAppService<br/>Feed 流"]
        TradingAppService["TradingAppService<br/>交易用例"]
    end

    subgraph Kernel["内核层 — kernel"]
        Identity["Identity<br/>个人档案"]
        Record["Record<br/>最小事件单元"]
        Timeline["Timeline<br/>Record 时间线投影"]
        ContextEngine["Context Engine<br/>上下文引擎 ★"]
        Memory["Memory<br/>长期记忆"]
        Knowledge["Knowledge<br/>结构化知识"]
    end

    subgraph Domain["领域层 — domain"]
        TradingContributor["TradingContextContributor<br/>持仓 + 全局摘要"]
        LifeContributor["LifeContextContributor<br/>(预留)"]
        ProjectContributor["ProjectContextContributor<br/>(预留)"]
    end

    subgraph Infra["基础设施层 — infrastructure"]
        FileStorage["FileStorage<br/>文件读写"]
        TagIndex["TagIndexService<br/>标签索引"]
        CardRepo["CardFileRepository<br/>卡片对话"]
        AiClient["AiClient<br/>LLM 接入"]
    end

    subgraph External["外部知识资产"]
        TradingOS["os/trading-os/<br/>交易知识库"]
    end

    %% 连接线
    App --> RecordController
    RecordController --> IntentRecognizer
    IntentRecognizer --> QuestionAppService
    IntentRecognizer --> Record

    QuestionAppService --> ContextEngine
    QuestionAppService --> AiClient

    ContextEngine --> Identity
    ContextEngine --> Record
    ContextEngine --> TagIndex
    ContextEngine --> CardRepo
    ContextEngine --> Memory
    ContextEngine --> TradingContributor
    ContextEngine --> LifeContributor
    ContextEngine --> ProjectContributor

    TradingContributor --> FileStorage
    Record --> FileStorage
    Memory --> FileStorage
    FileStorage --> TagIndex

    TradingContributor -.->|只读| TradingOS

    %% 样式
    classDef kernel fill:#e1f5fe,stroke:#0288d1,stroke-width:2px;
    classDef domain fill:#fff3e0,stroke:#f57c00,stroke-width:2px;
    classDef infra fill:#f3e5f5,stroke:#7b1fa2,stroke-width:2px;
    class ContextEngine kernel;
    class TradingContributor,LifeContributor,ProjectContributor domain;
    class FileStorage,TagIndex,CardRepo,AiClient infra;
```

## 二、数据写入流

一次记录提交从 API 到落盘的全路径：

```mermaid
flowchart TD
    Start(["用户提交内容"]) --> API["POST /api/v1/records"]
    API --> Controller["RecordController.createRecord()"]
    Controller --> Intent{"IntentRecognizer<br/>STATEMENT / QUESTION?"}

    Intent -->|STATEMENT| SaveRecord["RecordFileRepository.save()"]
    Intent -->|QUESTION| QAService["QuestionAppService.answer()"]
    QAService --> Context["ContextEngine.compose()"]
    Context --> QAAI["AiClient.understand()<br/>AI 回答"]
    QAAI --> SaveRecord

    SaveRecord --> WriteFile["写入 Markdown 文件<br/>data/records/YYYY/MM/rec_xxx.md"]
    WriteFile --> TagIndex["TagIndexService.onRecordSaved()<br/>更新 data/index/tags.json"]
    TagIndex --> Enrich{"有 AI 理解结果?"}

    Enrich -->|有| Memory["MemoryService.persist()<br/>写入 data/memory/YYYY/MM.md"]
    Enrich -->|无| Done["返回响应给 App"]

    Memory --> Done

    %% 文件格式标注
    WriteFile -.-> FileDetail["frontmatter: id / type / tags / createdAt<br/>body: 正文内容"]
    TagIndex -.-> IndexDetail["按标签聚合记录 ID<br/>count / recordIds / firstAt / lastAt"]
    Memory -.-> MemDetail["记录 AI 理解的摘要 / 标签 / 情感"]

    style Start fill:#4caf50,color:#fff
    style Done fill:#2196f3,color:#fff
    style WriteFile fill:#fff9c4
    style TagIndex fill:#fff9c4
    style Memory fill:#fff9c4
```

## 三、Context Engine 组装流

用户提问后，Context Engine 背后拉取的信息源：

```mermaid
flowchart TD
    Start(["新记录进入<br/>ContentRecord"]) --> LoadIdentity["① loadIdentitySummary()<br/>从 data/identity/profile.md 加载"]
    Start --> LoadCard["② loadCardContext(cardId)<br/>从卡片文件加载对话轮次"]
    Start --> LoadRelated["③ loadRelatedRecords()<br/>按标签从 TagIndexService 检索"]
    Start --> LoadMemory["④ loadMemorySummary()<br/>从 MemoryService 拉近 7 天记忆"]
    Start --> LoadDomain["⑤ enrichFromContributors()<br/>场景特供上下文"]
    Start --> LoadGlobal["⑥ loadGlobalContext()<br/>所有 Domain 全局摘要"]

    LoadRelated --> RelatedDetail{"有匹配标签?"}
    RelatedDetail -->|是| FormatRelated["格式化: 相关历史记录<br/>按标签聚合，不限时间"]
    RelatedDetail -->|否| SkipRelated["跳过"]

    LoadMemory --> MemDetail{"有记忆?"}
    MemDetail -->|是| FormatMem["格式化: AI 近期理解<br/>按标签聚合，每个标签取 2 条"]
    MemDetail -->|否| SkipMem["跳过"]

    LoadDomain --> DomainDetail{"有场景贡献者?"}
    DomainDetail -->|trading| TradingCtx["TradingContextContributor<br/>全量持仓表"]
    DomainDetail -->|其他| DefaultCtx["DefaultContextContributor<br/>不贡献"]

    LoadGlobal --> GlobalCtx{"Trading globalContext()"}
    GlobalCtx -->|有持仓| FormatGlobal["格式化: 交易系统状态<br/>持仓数量 + 市值 + 盈亏概览"]
    GlobalCtx -->|无| SkipGlobal["跳过"]

    FormatRelated --> Merge["buildPrompt()<br/>合并所有上下文块"]
    FormatMem --> Merge
    TradingCtx --> Merge
    FormatGlobal --> Merge
    LoadIdentity --> Merge
    LoadCard --> Merge
    SkipRelated --> Merge
    SkipMem --> Merge
    SkipGlobal --> Merge

    Merge --> Prompt["最终 Prompt 结构:<br/>1. Identity 身份<br/>2. 日期/星期<br/>3. 场景<br/>4. 卡片对话历史<br/>5. 相关历史记录(标签)<br/>6. AI 近期理解<br/>7. 全局领域上下文<br/>8. 当前记录<br/>9. 场景指令"]
    Prompt --> AI["AiClient.understand()<br/>→ DeepSeek"]

    style Start fill:#4caf50,color:#fff
    style AI fill:#ff5722,color:#fff
    style Prompt fill:#e1f5fe,stroke:#0288d1
    style Merge fill:#fff3e0,stroke:#f57c00
```

## 四、数据流水线闭环

从记录到理解再到反哺的完整循环：

```mermaid
flowchart LR
    Input["📝 你输入一条记录"] --> Record["Record 文件<br/>data/records/YYYY/MM/"]
    Record --> Context["Context Engine<br/>组装上下文包"]
    Context --> AI["AI 模型<br/>DeepSeek"]
    AI --> Understand["AI 理解结果<br/>摘要 / 标签 / 情感"]
    Understand --> Memory["Memory 沉淀<br/>data/memory/YYYY/MM.md"]

    Memory --> Context

    Memory -.->|"⏳ 晋升检测<br/>高频主题持续 3 周"| Graduation{"晋升建议"}
    Graduation -->|你确认| Knowledge["data/knowledge/trading/<br/>结构化摘要"]
    Knowledge --> DomainOS["os/trading-os/<br/>你审核后入库"]
    DomainOS --> Context

    %% 反哺闭环
    Understand --> Trading["TradingContextContributor<br/>持仓 + 全局摘要"]
    Trading --> Context

    %% 标注
    Record -.-> TagIndex["TagIndexService<br/>data/index/tags.json"]
    TagIndex -.-> Context

    Input -.-> Card["卡片对话 (CardRecord)<br/>会话轮次"]
    Card -.-> Context

    Input -.-> IdentityFile["Identity<br/>data/identity/profile.md"]
    IdentityFile -.-> Context

    %% 样式
    style Input fill:#4caf50,color:#fff
    style AI fill:#ff5722,color:#fff
    style Context fill:#e1f5fe,stroke:#0288d1,stroke-width:2px
    style Memory fill:#fff9c4
    style Knowledge fill:#f3e5f5,stroke:#7b1fa2
    style DomainOS fill:#f3e5f5,stroke:#7b1fa2

    linkStyle 6,7,8 stroke:#ff9800,stroke-width:2px,stroke-dasharray: 5 5;
```

## 五、核心概念

### 数据原则

```
文件（Source of Truth）
  │
  ├──→ Git 版本管理
  ├──→ AI 直接读取
  ├──→ 人直接查看
  │
  └──→ 数据库（索引/查询/缓存）
         │
         └──→ App 查询
```

### 依赖规则

- `interfaces → application → {kernel, domain} ← infrastructure`
- kernel 内部：Record → Timeline → Context → Memory/Knowledge（流水线方向）
- domain 之间不直接依赖
- infrastructure 通过接口反转依赖

## 六、两类 Domain

### Kernel Domain（系统域）

所有 Domain OS 共享的基础能力：

| Domain | 职责 | 存储 |
|--------|------|------|
| identity | 个人档案（静态偏好、AI 协作规则） | File First |
| record | 最小个人事件单元 | File First |
| timeline | Record 时间序列投影 | 自动生成 |
| context | 上下文引擎（★） | 动态组合 |
| memory | 跨会话长期记忆 | File（沉淀） |
| knowledge | 结构化知识资产 | File（预留） |

### Domain OS（业务域）

挂载在 Kernel 上的领域能力：

| Domain | 职责 |
|--------|------|
| trading | 金融交易（含研究） |
| life | 个人生活管理（预留） |
| project | 项目管理（预留） |

## 七、当前阶段

### 目标

建立 AdaiOS 最小可运行内核。

### 已实现

- Record → Context → AI → Memory 闭环 ✅
- 标签索引（TagIndexService，替代时间窗口切分）✅
- 卡片对话上下文（CardRecord + cardId）✅
- Memory 回读（按标签聚合，喂回 Prompt）✅
- 全局领域上下文（ContextContributor.globalContext()）✅
- Trading OS 持仓贡献（场景 + 全局）✅

### 未实现

- 晋升检测（自动发现高频主题 → 建议毕业为 Domain OS）
- `data/knowledge/` 目录
- Life OS / Project OS 的 ContextContributor
- Domain OS 从 `os/*/` 读取知识资产

### 技术约束

- Java 17 + Spring Boot 3 + Modular Monolith
- 不提前微服务化
- 模块边界优先
- 数据资产优先
