---
title: 开源记忆方案借鉴分析（Mem0/Letta/Zep/File-First 生态）
description: 调研开源 LLM 记忆框架的提取/去重/遗忘/注入机制，与 AdaiOS Memory OS 现状逐项对照，产出「抄机制不抄存储」的可借鉴清单
version: 1
created: 2026-08-22
updated: 2026-08-22
status: active
lines: 196
depends-on:
  - memory-os-design.md
related:
  - ../rfc/20260822-memory-plugin-isolation.md
  - framework-plus-plugin-model.md
tags: [memory, research, architecture]
---

# 开源记忆方案借鉴分析

> 调研 Mem0 / Letta / Zep-Graphiti / basic-memory 等开源 LLM 记忆方案的**提取-去重-遗忘-注入**机制，
> 与 AdaiOS Memory OS（memory-os-design + MemoryService 代码现状）逐项对照，
> 产出「抄机制、不抄存储」的可借鉴清单。**本文只出方案不动代码**（讨论与实施分离：未拍板不写码）。

## 一、结论摘要

1. **生态已成熟**：开源记忆方案已分层——全家桶框架（Mem0/Letta/Zep/Cognee/LangMem）+ MCP 插件（basic-memory 等），「该有的都有」。
2. **AdaiOS 现状不弱**：MemoryService 已实现 Phase 1-5（类型推导/降噪/去重/主题演进/行动闭环/时效衰减/清理），机制覆盖率比想象中高（§七 映射表）。
3. **核心差距只有四处**：① 无语义检索（标签/日期聚合召回）；② 无常驻核心记忆（7 天窗口全量注入）；③ 无显式「不记/删/改」四类决策；④ 无时序关系（tags 平铺，无实体-关系-时间）。
4. **建议总原则**：**抄机制、不抄存储**——Mem0/Zep 的向量库/图库为真相源与红线 B2「File First 不倒退」冲突，不引入为真相源；机制层面的提取决策、分级存储、注入控制可低成本吸收进现有 MemoryService。

## 二、调研对象总览

| 方案 | 定位 | 存储形态 | 自托管难度 | 适用场景 |
|:-----|:-----|:---------|:----------|:---------|
| [Mem0](https://github.com/mem0ai/mem0) | 通用记忆层（记忆即服务） | 向量库（Qdrant 默认，10+ 种可选）+ v1.0 起可选 Neo4j 图记忆 | **低**（向量库 docker + embedding API） | 个人单机/团队都可行，运维最轻 |
| [Letta](https://github.com/letta-ai/letta)（原 MemGPT） | 有状态 Agent 框架 | Postgres(pgvector)：core memory 块 + archival 向量检索 | 中 | 团队/服务更合适，个人单机偏重 |
| [Zep/Graphiti](https://github.com/FalkorDB/graphiti) | 时序知识图谱记忆 | 图库（FalkorDB/Neo4j）+ 原文 episodes | **高**（图库 + 多步 LLM 写入） | 团队/平台服务，成本曲线陡 |
| [Cognee](https://www.cognee.ai/blog/guides/open-source-memory-frameworks-llm-agents) | ECM 企业上下文管理 | 图 + 向量可插拔 | 中 | 企业级管线 |
| [LangMem](https://github.com/langchain-ai/langmem) | LangChain 官方记忆 SDK | 可插拔 | 中 | 绑定 LangChain 生态 |
| [basic-memory](https://github.com/Brennall/basic-memory) | File First 记忆（MCP 插件） | **Markdown 文件为真相源** + SQLite 查询层 | 低 | 个人本地、Obsidian 互通 |
| `@modelcontextprotocol/server-memory` | 官方参考 MCP | 单 JSON 文件知识图谱 | 最低 | 演示/协议参考 |
| claude-mem | Claude Code 插件 | Markdown memory bank | 低 | 绑定 Claude Code |

> 与本项目红线的关系：Mem0/Zep/Letta 均为**数据库为真相源**，与 B2「File First 不倒退」冲突；basic-memory 与 AdaiOS 架构同构（文件为准、索引为查）。

## 三、Mem0 机制拆解

- **提取**：每次写入输入对话对 `(m_{t-1}, m_t)` + 上下文（**异步后台刷新的对话摘要** + 近期消息窗口）。第一步 LLM 抽取「关于用户的事实」候选；第二步对每条候选检索 top-10 相似旧记忆，LLM 通过函数调用做**四选一决策：ADD / UPDATE / DELETE / NOOP**。无显式评分字段，靠相似度 + 模型判断过滤（[arXiv 2504.19413](https://arxiv.org/abs/2504.19413)）。
- **去重与冲突**：写入前语义向量检索相似记忆喂给 LLM；UPDATE = 新事实与旧记忆**合并重写**（新向量覆盖旧向量、刷新 updated_at）；DELETE = 检测到矛盾/过时（如用户改地址）时**硬删除**。
- **存储**：向量库（Qdrant 默认，支持 10+ 种）+ 记忆文本/向量/元数据（scope、created/updated）。embedding 默认 OpenAI，可换 Ollama/本地。
- **遗忘**：**只有 LLM 触发的 DELETE，无 TTL/衰减/老化**——论文明确承认这是缺失项；DELETE 硬删除不保留历史（最弱一环）。
- **注入**：查询按相关性 top-k + 可选 rerank（cross-encoder 精排，+400-500ms）；**scope 隔离**（user_id/agent_id/run_id 三层互不串扰）；检索结果强调时间戳、「按 recency 解决矛盾」。
- **工程代价**：写 = 2 次 LLM 调用（抽取+决策）+ 约 2 次 embedding，p95 ≈ 1.44s；token 远低于全上下文。**三者中运维最轻，个人单机完全可行**。
- **可借鉴点**：① 对话摘要**异步后台刷新**喂给提取器（廉价全局上下文）；② **ADD/UPDATE/DELETE/NOOP 操作语义接口**——LLM 推理与确定性存储代码解耦；③ scope 三层隔离。

## 四、Letta（MemGPT）机制拆解

- **提取**：**无离线抽取，模型自主管理内存**（OS 式虚拟上下文）。core memory 分**多块**（默认 persona 人设 / human 用户画像 + 自定义块），每块含 label/value/**limit 字符上限**；系统提示渲染每块用量（**memory pressure**），模型感到块将满就主动整理。改写靠专属工具 `memory_replace/insert/append`；**heartbeat 机制**让模型请求后续唤醒做多步检索/搬运（[MemGPT 论文 arXiv 2310.08560](https://arxiv.org/abs/2310.08560)）。
- **去重与冲突**：无独立去重模块，模型改写块时自行判断；冲突 = 直接覆盖改写，无版本历史。
- **存储**：两层——**core memory**（上下文内小 JSON 块，持久化 Postgres）+ **archival memory / conversation recall**（无上限向量检索，pgvector）。
- **遗忘**：块超 limit → 模型被提示把内容**搬进 archival**（腾出热内存）；无 TTL；另有 **sleep-time compute**：agent 空闲时后台任务处理未读消息、摘要、整理记忆（不打断前台）。
- **注入**：core memory 块**整体渲染进系统提示**（limit 即 token 预算，可控）；archival 按需 `archival_memory_search`（向量 top-k + 分页 + date 过滤）。
- **工程代价**：每轮推理背着 core memory 固定 token 开销；写入是工具调用（纯推理成本）；Postgres+pgvector 运维中等，个人单机可跑但偏重。
- **可借鉴点**：① **分块 + 字符上限 + memory pressure 可视化**——把「何时淘汰」决策权交给模型（比外部 TTL 自然）；② heartbeat/睡眠期后台整合；③ **热块 ↔ 冷向量库分页**骨架——个人 AI OS 记忆模块最值得抄的结构。

## 五、Zep / Graphiti 机制拆解

- **提取**：图构建管线——ingest episode（原始消息**非损失存储**，带参考时间戳）→ 短窗口内抽实体 → **实体消歧合并** → 抽关系/事实边 → 时间抽取（valid_at/invalid_at）→ 检测矛盾**失效旧边**；Leiden 社区检测分层聚簇 + 周期性刷新社区摘要。核心是 **bi-temporal 双时间模型**（事实成立时间 + 系统学习/失效时间），支持历史时点查询（[Zep 论文 arXiv 2501.13956](https://arxiv.org/abs/2501.13956)）。
- **去重与冲突**：实体消歧 = 语义相似 + LLM judge；**冲突最强项**——新事实与旧边矛盾时给旧边打 invalid_at 失效**而非删除**，历史完整可溯源，每条边链接回源 episode（**provenance**）。
- **存储**：图库（FalkorDB/Neo4j）+ episodes 原文 + 社区摘要。
- **遗忘**：无硬删除，靠「时序」隐式遗忘——检索按 recency 加权，社区摘要滚动刷新（高层泛化），失效边不参与默认检索；图只增不减，长期需外部清理。
- **注入**：**3 阶段检索**——search（向量 + BM25 + 图 BFS 混合）→ rerank（RRF/MMR）→ construct（格式化成带时间区间的 prompt-ready 文本）；LongMemEval 平均注入约 1.6k token；社区摘要当缓存层。
- **工程代价**：**写路径最贵**（实体/关系抽取、消歧、摘要多步 LLM）+ 图库运维 + 后台社区刷新；适合团队/平台。
- **可借鉴点**：① **失效而非删除 + bi-temporal**（更正/矛盾保留审计历史——个人记忆「反悔」场景刚需）；② episodes→facts→communities 三层 + provenance 溯源；③ 混合检索 + 显式 constructor 生成带时间区间的注入文本。

## 六、File First / MCP 生态（basic-memory 等）

> 轻量级/MCP 插件级方案——与 AdaiOS B2「File First」约束最契合的一档，重点拆解。

### 6.1 basic-memory（Brennall/basic-memory，现 basicmachines-co）

- **核心做法**：Zettelkasten 卡片笔记 × 知识图谱。对话产生的新知识被抽取为实体写入独立 Markdown 笔记，笔记间引用用 `[[wiki-link]]` 表达，形成可人工审阅、可机器检索的双层记忆。
- **存储形态**：**Markdown 文件是真相源**（Obsidian 兼容 vault：`notes/` + frontmatter + backlinks）；实体抽取结果另存 SQLite（含 sqlite-vec 向量检索）作查询层——**文件为准、数据库为查询存在**，正是 File First 典型架构，与 AdaiOS B2 完全同构。
- **接入方式**：双通道——MCP server（`npx basic-memory`）暴露 `remember/observe/note/graph/search/entities` 工具，任意 MCP 客户端可挂；另有 Claude Code 官方插件深度集成。
- **成本**：完全本地优先无订阅；成本 = LLM 调用 + SQLite。
- **可借鉴**：① 文件真相源 + 可重建索引（SQLite/向量）双层的架构范式；② `[[wiki-link]]` 实体网络表达关系；③ MCP 工具按 写（remember/observe）/ 读（search/graph）/ 删（delete/forget）三族设计，命名贴近自然语言。

### 6.2 @modelcontextprotocol/server-memory（官方参考实现）

- **核心做法**：最小知识图谱，仅三类元素——`entities`（实体，带 entityType + observations）、`relations`（from/to/relationType）、`observations`（实体上的事实陈述），存单个 JSON 文件，零依赖。
- **价值**：极简可移植，但 JSON 无索引、无去重、无语义搜索，**只适合演示级**；可借鉴的是「实体-关系-观察」三元组**数据契约**本身（比裸文本易检索、易去重）。

### 6.3 claude-mem（getzep/claude-mem 等）

- **核心做法**：「**压缩 → 落盘 → 注入**」三段式——会话中自动捕获，用 AI 把冗长对话压缩成结构化记忆（省 token 关键机制），下次会话开始时把相关记忆重新注入；宣称注入开销仅 ~50 tokens/提示词。
- **可借鉴**：会话结束自动压缩落盘、开工自动注入的闭环——与本项目 `guard-context.sh --write-local` 模式同构；注入「压缩摘要 + 相关片段」而非全文，控制每轮 token 预算。

### 6.4 OpenMemory（mem0 本地 MCP 版）

- **做法**：mem0 提取-存储-检索 + MCP 协议，向量库（Qdrant）+ 可选图谱（Neo4j）+ Next.js 管理面板。
- **结论**：检索质量高、有管理 UI，但**不是 File First**（数据锁在向量库）、部署重（Docker/Qdrant 属基础设施级）——与 C1 一并排除，仅参考其管理面板「逐条可见可删」的产品形态。

### 6.5 LLM 厂商内置记忆（OpenAI / Claude / Gemini）

- **三种值得借鉴的产品形态**：① **用户可见可删**（ChatGPT Settings→Memory 逐条管理、Claude memory manager）；② **自动 vs 手动双模式**（自动记录 + 显式「记住/忘记」指令 + 临时对话隔离）；③ **摘要注入而非全文注入**、记忆工具由模型显式调用读写（Anthropic memory tool 与 context editing 解耦）。
- **结论**：封闭生态（换工具丢记忆）、云端隐私软肋；产品形态可借鉴——管理 UI、显式记住/忘记、临时会话隔离（防噪音污染记忆）。

### 6.6 File First 生态可借鉴清单（提炼）

1. **文件为准、索引为查**：Markdown 唯一真相源，索引（SQLite/向量）只做查询加速、可从文件随时重建——AdaiOS 现状已符合（MemoryService 文件读写），若上语义检索必须保持此结构。
2. **「实体-关系-观察」三元组作数据契约**：现 tags 平铺，可借鉴关系显式化（低优先级，见 B 档评估）。
3. **记忆摘要注入而非全文注入**：现状已按标签聚合摘要，符合；可加 token 预算控制。
4. **显式「记住/忘记」双通道**：现状无显式 forget 指令入口（delete 仅按 recordId），可补。
5. **临时会话隔离**：标记临时对话不进记忆写入路径，防噪音污染——现状无此概念。
6. **逐条可见可删的管理 UI**：adai-admin 已有记忆管理（update/delete），可补「来源/时间戳」展示。

## 七、AdaiOS 现状盘点（代码级，2026-08-22 核实）

### 7.1 写入侧（MemoryService，`kernel/memory/`）

| Phase | 机制 | 实现要点 |
|:------|:-----|:---------|
| 1 | 类型推导 | `deriveKind`：actionable→decision > preference > pattern > insight > fact |
| 1 | 降噪筛选 | kind=fact 且无 actionable → 无信息增量，跳过沉淀（Phase 5） |
| 1 | 去重 | 同 recordId 不重复写；降级原文 → AI 洞察升级（删除降级条目写洞察） |
| 2 | 主题演进 | 近 30 天 tags 重叠 ≥1 → 旧版本标 superseded + evolvedTo 演变链 + topic 归组 |
| 3 | 行动闭环 | actionable + doneAt 完成标记 / clearActionable 跟踪归任务 |
| 4 | 回读强化 | lastConfirmed：每次回读 touch，作为时效衰减基准 |
| 4 | 时效衰减 | `0.95^天数`（基于 lastConfirmed，回退 createdAt） |
| 4 | 过期清理 | superseded>60 天 / doneAt>30 天随 rebuild 清除 |
| — | File First | `data/{userId}/memory/YYYY/MM.md`，`---` frontmatter 条目，每用户写锁 |

### 7.2 读取侧（ContextEngine.loadMemorySummary）

- `touchActive`（回读确认）→ `recentActive(7天)` 全量回读（排除 superseded）
- 按**标签聚合**，每标签取最新 2 条摘要注入 prompt
- 待行动事项（actionable && doneAt==null）单独注入
- ⚠️ 已知缺口（RFC 20260822）：无插件过滤、Memory 无 domain 字段

### 7.3 与 memory-os-design 的差距（设计 vs 实现）

- 设计有 `confidence` / `importance` 评分字段与「Memory Score = 频率+时间+确认+长期影响」——**实现中无 importance 字段**（patterns 有 confidence，条目级无）
- 设计有 Temporary → Stable 两级记忆生命周期——实现为单级 + superseded 演变链（近似但不等价）

## 八、差距与借鉴清单（映射表）

> 外部机制 → AdaiOS 现状（代码级核实）→ 建议（编号对应 §九）。

| 外部机制 | 出处 | AdaiOS 现状 | 建议 |
|:---------|:-----|:-----------|:-----|
| ADD/UPDATE/DELETE/NOOP 显式决策 | Mem0 | kind 推导 + Phase 5 降噪跳过（无 DELETE/NOOP 显式信号） | **A1**：理解时输出显式决策，UPDATE 复用 superseded 演变链 |
| 条目级 importance 评分 | Mem0 / memory-os-design 设计 | 仅 patterns 有 confidence，条目无评分 | **A2**：补 importance 字段作注入排序权重 |
| 注入排序替代平铺 | Letta 块预算 / claude-mem | 标签聚合每标签取 2 条，无全局优先级 | **A3**：`importance × timeDecay` 排序取 top-k |
| 常驻核心记忆（core memory 块常驻系统提示） | Letta | 7 天窗口全量注入，无常驻块 | **B2**：identity + 高频 pattern 沉淀常驻块 |
| 语义检索 top-k（向量/BFS 混合） | Mem0 / Zep | 标签/日期聚合，无语义相关性 | **B1**：先全文搜索验证，再评估本地 embedding |
| 对话摘要异步刷新喂提取器 | Mem0 | 同步理解链路 | 低优先：单用户数据量小，暂不引入 |
| scope 隔离（user/agent/run） | Mem0 | 用户级隔离已实现（`data/{userId}`） | 已具备：单用户无需 agent/run 级 |
| 热/冷两层分页（core ↔ archival） | Letta | 单级文件池 + superseded 演变链 | 部分映射：常驻块=热、文件=冷；无向量分页 |
| 失效而非删除 + 溯源（bi-temporal/provenance） | Zep | superseded/evolvedTo 演变链 + recordId 溯源 | **已具备核心**：可补「失效原因/时间」字段（低优先） |
| sleep-time compute 后台整理 | Letta | 无 | **C2 不采纳**：数据量小，同步沉淀已够 |
| 记忆框架本体（向量库/图库真相源） | Mem0/Zep/Cognee | File First 文件真相源 | **C1 不采纳**：与 B2 冲突、新增基础设施 |
| 显式「记住/忘记」双通道 | 厂商内置记忆 | delete 仅按 recordId，无 forget 指令入口 | 可补：AI 对话层显式 forget 指令（低优先） |
| 临时会话隔离（不污染记忆） | 厂商内置记忆 | 无此概念 | 可补：标记临时对话不进记忆写入路径（低优先） |
| 逐条可见可删管理 UI | 厂商内置记忆 / OpenMemory | adai-admin 已有记忆 update/delete | 已具备，可补来源/时间戳展示 |

## 九、决策建议

> 分三档：**A 低成本即取**（当前架构内纯逻辑改动，零新依赖）/ **B 中成本评估**（需新增依赖或架构决策，先出方案后拍板）/ **C 不采纳**（明确排除 + 理由）。均待用户拍板，未拍板不动代码。

### A 档：低成本即取（MemoryService 内部，纯逻辑）

| # | 借鉴点 | 出处 | 当前现状 | 建议改动 |
|:-:|:-------|:-----|:---------|:---------|
| A1 | 显式「不记/删/改」决策 | Mem0 ADD/UPDATE/DELETE/NOOP | 降噪靠 `kind=fact 跳过`（Phase 5），无 DELETE/NOOP 显式信号 | AiUnderstanding 增加提取决策字段（keep/update/delete/skip），deriveKind 旁路判决策；UPDATE 复用现有 superseded 演变链 |
| A2 | 条目级 importance 评分 | Mem0 / memory-os-design 设计 | 设计有 confidence/importance，代码仅 patterns 有 confidence，条目无 | Memory 补 importance 字段（AI 理解时输出 0-1），作为注入排序权重 |
| A3 | 注入排序替代平铺 | Letta 注入控制 | 标签聚合每标签取 2 条，无全局优先级 | 注入改为 `importance × timeDecay` 排序取 top-k，标签只作分组不截断 |

### B 档：中成本评估（先讨论后拍板）

| # | 借鉴点 | 出处 | 评估点 | 边界约束 |
|:-:|:-------|:-----|:-------|:---------|
| B1 | 语义检索召回 | Mem0/Zep | 本地 embedding（bge-small 级，个人单机可跑）做相关记忆召回，替代/补充标签聚合 | **B2 红线**：向量索引只作查询加速，真相源仍是 `data/` Markdown；先可用现有全文搜索 `/api/v1/search` 验证需求再上向量 |
| B2 | 常驻核心记忆 | Letta core memory | identity + 高频 pattern 沉淀为「常驻块」长期在系统提示（而非 7 天窗口），对齐 memory-os-design 的 identity/preference 类型 | 与现有 identity 档案分工：档案存事实，常驻块存「当前最重要 N 条理解」 |

### C 档：不采纳（明确排除）

| # | 方案 | 排除理由 |
|:-:|:-----|:---------|
| C1 | 引入 Mem0/Zep/Cognee 框架本体 | DB 中心与 B2 File First 冲突；新增向量库/图库/Redis 基础设施；与 Kernel（Context+Memory+Knowledge）职责重叠，等于换架构 |
| C2 | sleep-time compute 全量异步整理（Letta） | 单用户个人 OS 数据量小，同步沉淀已够；记忆膨胀后再评估异步归并（届时可与 A1 UPDATE 决策配套） |
| C3 | 时序知识图谱（Graphiti） | 个人数据规模下 tags 平铺够用；图谱价值在跨实体推理，可后置为插件级能力而非框架底座 |

## 关联

- `memory-os-design.md`（Memory OS 设计，本文的对照基准）
- RFC 20260822 memory-plugin-isolation（记忆 domain 缺口，写侧补全）
- `framework-plus-plugin-model.md`（记忆归框架的形态总纲）
