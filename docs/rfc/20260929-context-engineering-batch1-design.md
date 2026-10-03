---
title: 对话模式上下文工程——批 1 实施设计（文件级）
description: 主方案 `20260929-conversation-context-engineering.md` 的**批 1（P0 止血）**落地设计：把「少注入」的五项改动精确到文件与行、列出新增配置项、测试计划、影响面（含 10 处 `new ContextEngine(...)` 测试调用点）、灰度与回滚、以及必须先验证的 DeepSeek usage 解析。本件不含批 2/3；**未授权前不改代码**。
date: 2026-09-29
status: draft
decided-by: adai（2026-09-29 三项拍板：**D1 保留极少核心**——≤3 轮仍注入身份 + 高置信偏好/规则（≤200 token）· **D2 只对新记忆生效**——不动存量记忆、不回扫打标 · **D3 先出实施设计**——本件即该产出，代码未动）
tags: [对话, 上下文工程, 实施设计, token预算, 批1]
related:
  - ./20260929-conversation-context-engineering.md
  - ./20260923-rhythm-and-memory-temporality.md
  - ./20260801-memory-system-evolution.md
---

# 批 1 实施设计

> **上游**：主方案 [20260929-conversation-context-engineering.md](./20260929-conversation-context-engineering.md)（问题证据、行业对标、三批划分）。
> **本件范围**：只做**批 1（P0「少注入」）五项**——按轮次分档 · 收掉「最近 20 条」· 去重复注入 ·
> 单条 system 与顺序 · 真实 token 计量与每层用量日志。
> **不做**：两臂召回、检索按需触发、指纹去重（配额）、三个包落地、bi-temporal —— 那是批 2/3。

---

## 一、已拍板口径（本设计的输入）

| # | 决策 | 落地含义 |
|:--|:-----|:---------|
| **D1** | **保留极少核心** | ≤3 轮：`L0 身份/契约 + L1 对话前文 + 核心记忆`；**核心记忆 = `kind=preference` 的最新 2 条**，总长 ≤200 token（另受开关约束）。不注入：相关记录、检索、知识源 |
| **D2** | **只对新记忆生效** | **不回扫存量**；`data/` 与记忆文件**一个字节都不动**。批 2 的写路径改动同样只影响此后新写入 |
| **D3** | **先出实施设计** | 本件；代码、配置、测试**均未改动** |

---

## 二、改动面清单（文件级）

### 2.1 五项改动 → 文件映射

| # | 改动 | 主改文件 | 具体改法 | 影响面 |
|:--|:-----|:---------|:---------|:-------|
| **① 按轮次分档** | `kernel/context/engine/ContextEngine.java` | `compose()` 内**先取一次卡片**（拿 turns 数），再按档决定是否调用 `loadRelatedRecords` / `loadSearchResults` / `loadMemorySummary` / `loadKnowledgeContext`；档位与阈值由新配置决定（§三） | 构造函数 **+1 参数**（策略/配置）→ **10 处测试调用点**（`ContextEngineTest` 8 · `PluginIsolationTest` 2）机械更新 |
| **② 收掉「最近 20 条」** | 同上（`loadRelatedRecords` 的 fallback 分支，`ContextEngine.java:242-267`） | 批 1 **不引入打分**：fallback 由「最近 20 条」改为「**最近 2 条**」；有标签时保持既有标签关联；**≤3 轮档直接整层不调用** | 同文件；`MAX_RELATED_RECORDS=20` 保留给批 2 复用 |
| **③ 去重复注入** | `infrastructure/ai/llm/DeepSeekAiClient.java` | `buildBackground()` 里**去掉 `cardContext`**（对话前文已由 `conversationHistory` 承载）；`buildContextFromPrompt()` 截取时**排除 `## 当前会话对话历史` 段**——同一场对话不再以「摘要行 / 文本块」二次出现 | 中；`DeepSeekAiClientTest` 需补断言 |
| **④ 单条 system + 顺序** | 同上（`buildChatRequestBody`，`DeepSeekAiClient.java:327-388`） | 3 条 system 合并为 **1 条**（角色 + JSON 契约 + 能力边界 + 插件清单 + 长期画像，Markdown 分节）；**记忆/检索下移**为最后一条 user 消息的「本次参考」段（位置在主问句之前） | 中；兼容性收益（Gemini 取最后一条、Qwen/Mistral 报错） |
| **⑤ 真实计量 + 每层用量** | `kernel/context/engine/ContextPackage.java` · `DeepSeekAiClient.java` | ① `estimateTokens()` **修正**：把 `relatedRefs` 与 `conversationHistory` 计入（旧算法只算 identity+recordContent+prompt）；② 新增**分层用量**（L0/L1/L2/L3 各多少字符→token），随组装日志输出；③ **解析响应 `usage`**（见 §七 待验证项） | `ContextPackageTest` **2 例**要同步更新（`estimateTokens_withFullContent` / `estimateTokens_emptyIdentity`）；`ContextPackage` 构造调用点 **3 处**（`simple()` / `ContextEngine` / `TradingReviewAppService`）——**用重载兼容，不改调用方** |

### 2.2 顺带修掉的一处性能问题（同批、零额外风险）

现在 `compose()` 里 `loadCardContext()` 与 `buildConversationHistory()` **各调一次 `cardRepository.findById`**，
而 `findById` 的实现是 **`findAll` 全量遍历 + 逐个解析文件**（`CardFileRepository.java:77-92`、`:121-137`）——
单次对话要**全量解析卡片目录 2~3 遍**（生产 86 张卡，仍在增长；`REVIEW #19` 已把它记为已知待办）。

**改动**：`compose()` 只读一次卡片，把 `CardRecord` 传给两个内部方法 → 本轮对话的卡片解析从 **2~3 次降到 1 次**。
不引入缓存、不改 `CardRepository` 接口（避免影响面扩散到批 2）。

### 2.3 路由层（本次生产自查新增，**需 D4 决策**，默认**不**进批 1）

**问题**（主方案 §F9）：带 `cardId` 的请求里 **47%（31/66）**被判成 `STATEMENT` → `cardId` 被丢弃、
不建卡、**阿呆不回应**（今天 16:01 的两句实测如此）。

**为什么列在这里而不直接改**：修法涉及**产品口径**——用户输入默认算「对话」还是「记录」？
这是 D 类决策，**不该由实现代定**。

| 方案 | 做法 | 代价 |
|:-----|:-----|:-----|
| **R1 只补可见性**（最小、建议先做） | 非对话态且被判记录时给一句如实回执（「这句我记下了」）；并让"是否还在对话里"可见——**app 端对齐 web 已修的 P1-前端4**（app 的 `_syncActiveCard` 至今静默，`main_page.dart:1371-1376`） | 不改路由口径；用户仍需自己点「提问」才能聊 |
| **R2 输入即对话** | 输入默认 `intent=question`，只有显式「记一笔」才落记录 | 改变既有行为，可能把流水账也变成对话 |
| **R3 自动分流 + 一句话确认** | 保持自动分流，但若这句像在延续刚结束的会话（如 5 分钟内同卡）→ 回一句「你是想接着说刚才那段吗」 | 需要新判据 + 一次可能的往返 |

**建议**：**R1 与批 1 同批上线**（零风险、不改口径）；R2/R3 等 **D4** 拍板后再排。

### 2.3.1 D4 拍板结果（2026-09-30 用户决策）与落地

> 用户拍板：**「同意推荐」** —— 即 **A「说出来」**（＝ R1 的加强版：不改路由口径，但**必须出声**）
> ＋ 误判方向取 **「宁可它多说一句」**。

| # | 落点 | 落地内容 |
|:--|:-----|:---------|
| 1 | **后端** `DeepSeekAiClient.INTENT_SYSTEM` / `intentPrompt` | 判据加倾向：**只有明确的纯记录才 log；带一点提问/求助/征求看法/想接话的意味 → ask；模棱两可、看不出意图、情绪化短句一律 ask**。user prompt 抽成包级可见的 `intentPrompt(content)`，供测试钉住口径 |
| 2 | **app** `FeedCardData.justRecorded` + `feed_card.dart` 回执行 + `main_page.dart` 的 log 分支 | 判成记录时，卡片底部一行浅灰小字：**「记下了 · 想接着说就点「提问」」** |
| 3 | **web** 同上（`feed_models.dart` / `desktop_feed_card.dart` / `feed_page.dart`） | 同口径、同文案（双端各自实现，值复制不同源） |

**为什么是「卡片内一行」而不是 SnackBar**（用户同意推荐）：静默的根因是**事后回想不确定它收到没**，
3 秒就消失的提示治不了这个；且本行只在**刚提交那一刻**出现（`justRecorded`），
Feed 刷新后由服务端数据重建即消失——**翻历史卡时不会被打扰**（已用组件级回归钉住"显示"与"不显示"两个方向）。

**费用影响（如实登记）**：「宁可多说一句」会让原本判成记录的输入更容易走 AI 回答 → 调用量上升。
这是该口径的已知代价，用户已在拍板时确认。

**未随本批**：R2（输入即对话）与 R3（延续时追问）**不做**——A 已覆盖用户提出的问题（不再静默），
且 R2 会改变「随手记」这一核心用法的形态，R3 需新判据 + 额外往返；两者留待 A 上线观察后再议。

### 2.3.2 独立对抗审查与随修（2026-09-30）

派独立子代理（对抗视角、只报告不改代码）深审本批：**P0 未发现；P1×2 / P2×3 / P3×3**。

| 级别 | 发现（证据） | 处理 |
|:-----|:-------------|:-----|
| **P1-1** | **倾向会波及图片链路**：`MediaController.isQuestion` 走**同一个** `recognizeIntent` → 「配文 + 图」从综合总结变成「对陈述句作答并开一段对话」（多一次 VLM）；更严重的是该分支有 `MAX_QUESTION_LENGTH=500`，**>500 字配文原本可作 log 落盘，判 ask 后直接 400**（图已落盘、记录未建） | ✅ **已修**：`AiClient` 新增 default 方法 `recognizeIntentLeanAsk`（默认委托旧方法 → **任何既有实现与测试桩零改动**）；`IntentRecognizer.recognizeWithAi(content, leanAsk)` 重载；**文本入口传 true、媒体入口零改动回到旧口径**；加守门用例 `leanAskPrompt_isSeparateFromLegacy_mediaKeepsOldWording`（谁把倾向塞回旧常量就红） |
| **P1-2** | 误判 ask 的代价不止一次问答：`QuestionAppService.answer`（ContextEngine + 一次生成 + 记忆按对话轮落盘）＋ 用户点「结束」时可能再烧一次总结 ≈ **+2 次 LLM**，且记录不再以记录卡形态消费 | 📋 **属决策的固有代价**（用户拍板「宁可它多说一句」时已确认）；已在本节与 `change-log` 如实登记 |
| **Q1** | 措辞可无限外延（「带一点…意味」）· 零 log 正例 · 温度 0.3 对分类偏高 | ✅ **部分采纳**：改为**可列举的索取信号**（提问／求助／征求看法／想接话）＋ **log 正例**（「今天天气不错」「帮我记一下」）＋ 倾向路径**温度 0.3 → 0.1**；「后验规则（判 log 但含？/吗 → ask）」登记 `REVIEW P2-对话4` |
| **P2-1** | `justRecorded` 无过期位：只有服务端重建能清，而文本提交后两端都不刷新 → 会话内长期显示；app 的 `_refreshFeed` **保留 older 段** → 该卡永不消失（与 web 整表替换不一致）；原注释「只在刚提交那一刻」与事实不符 | ✅ **已修**：app 在合并 older 时统一清标记；**双端注释按事实改写**（保留到「转成对话」或「Feed 刷新」） |
| **P2-2** | 3 个组件级用例**都直接造数据**，防不住「log 分支忘置 `justRecorded`」这条**本批真正的新接线**；后端用例是字符串自比较 | ✅ **已修**：双端各补**接线级**用例（走真实提交路径 `_createNewCard`）；后端用例改为「倾向版必须含 log 正例」＋**新旧口径分离守门** |
| **P2-3 / P3-1 / P3-2** | 失败兜底仍偏向 log（与 D4 方向相反，当前判为有意的 fail-safe）· 双端记录卡行数不一致（**既有差异**，非本批引入）· Siri「记一笔」走同一判据、可能被答一段话（入口语义漂移） | 📋 **登记 `REVIEW P2-对话4`**（待用户拍板 / 单独排期） |

> **审查同时确认**：三落点未超范围；`?? this.justRecorded` 语义正确；未发现跨卡复制或分页带入旧标记；
> 组件级三个用例双端实跑 3/3 全绿。
>
> **审查过程的一处如实记录**：审查官指出其审查期间目标文件被改（我先自查出「转对话后回执残留」并修了
> `!_hasTurns`），因此结论以其**最终快照**为准——这也说明「边审边修」会让审查目标移动，**后续批次应先冻结再审**。

---

## 三、新增配置项

跟随本项目现有惯例：**构造参数 `@Value`**（`DeepSeekAiClient.java:76-79` 即此写法；项目无 `@ConfigurationProperties` 类）。

| 键 | 默认 | 作用 |
|:---|:-----|:-----|
| `adai.context.assembly-mode` | `legacy` | **总开关**：`legacy`（现状行为）/ `v1`（批 1 新口径）——灰度与回滚都靠它（§六） |
| `adai.context.tier.short-max-turns` | `3` | ≤该轮数 → 只 L0+L1+核心记忆 |
| `adai.context.tier.mid-max-turns` | `9` | ≤该轮数 → 加 L2 记忆；之上才允许 L3 检索 |
| `adai.context.core-memory-max` | `2` | 核心记忆条数（`kind=preference`） |
| `adai.context.core-memory-max-tokens` | `200` | 核心记忆 token 上限（按字符/2 估算截断） |
| `adai.context.fallback-recent-max` | `2` | 批 1 的 fallback 条数（替掉原来的 20）；批 2 换两臂召回后此键退役 |

> 全部**带默认值**，不改 `.env`、不改 `application.yml` 也能先跑（默认 `legacy` = 行为不变）。

---

## 四、测试计划

### 4.1 新增（钉住新口径，反向可验证）

| 用例 | 断言 | 反向可验证性 |
|:-----|:-----|:-------------|
| `tier_shortDialogue_skipsMemoryAndRelated` | 2 轮对话：`relatedRefs` 不含相关记录；`memory.recent*` **未被调用**（Mockito `verify(never())`） | 改前必红 |
| `tier_shortDialogue_keepsCorePreference` | D1：≤3 轮仍带 `kind=preference` 的 2 条（且超 200 token 被截断） | 改前必红 |
| `tier_boundary_3_4_and_9_10` | 阈值边界（3/4 轮、9/10 轮）分档正确 | 边界回归 |
| `fallback_recent_isTwoNotTwenty` | 无标签 fallback 只取 2 条 | 改前必红（现在是 20） |
| `conversationHistory_notDuplicatedInSystem` | 同一场对话的文本**不再出现在 system**（只在 messages） | 改前必红 |
| `chatRequest_hasSingleSystemMessage` | `buildChatRequestBody` 产出的 messages 里 **system 恰好 1 条**，且动态内容位于最后一条 user | 改前必红（现在是 3 条） |
| `estimateTokens_includesHistoryAndRefs` | 真实计量含 `relatedRefs` + `conversationHistory` | 改前必红 |

### 4.2 修改（存量测试的既有断言）

- `ContextPackageTest`：2 例 `estimateTokens_*` 的期望值按新口径更新；
- `ContextEngineTest`（8 处）+ `PluginIsolationTest`（2 处）：`new ContextEngine(...)` 补第 10 个参数（机械改动）；
- `DeepSeekAiClientTest`：若既有用例断言了 3 条 system / background 含 cardContext，需按新结构更新。

### 4.3 不在本批的测试

回归用例（20~40 条、三臂对照、LLM-as-judge 门禁）属 **批 2 前置**，本批不建——本批用**单测 + 真链观察**（§六）验证。

---

## 五、验收与观察（上线后第一周看什么）

| 指标 | 现状基线 | 批 1 目标 | 怎么取 |
|:-----|:---------|:----------|:-------|
| **prompt 分块占比**（本次生产实测，30 条样本） | **L4 知识源 46.0%**（P90 **87%**）· **L2 记忆 34.9%** · 相关历史 12.6% · **当前记录 4.3%** · 前文 0.7% | L4 降到 ≤10%（命中领域才注入）；「当前记录」占比自然上升 | 分层用量日志；本次的一次性分析脚本可固化为巡检项（§七 V4） |
| 对话模式里 `标签关联=22条` | **108/151 = 72%** | **归零**（fallback 不再满额） | 组装日志（沿用现有那行，加分层用量） |
| 带 `cardId` 请求的 STATEMENT 占比 | **31/66 = 47%** | 取决于 **D4**（R1 只改提示与可见性，不改这个比例） | `Intent` 日志 |
| `CHAT(N轮)` 的 N 分布 | ≤5 轮占 60% | 不变（这是用户行为，不是缺陷指标） | 同上 |
| 短对话（≤3 轮）注入的无关块数 | 20 条记录 + 最多 10 条搜索 | **0** | 分层用量日志 |
| 空转检索占比 | 93.4% | ≤3 轮档 **不再发起检索**（占比自然下降） | 搜索计数 |
| 真实输入 token | **不可知**（旧算法失真） | 每笔可见，且含 history/refs | 新计量 + 服务端 usage |
| 缓存命中 token | 未记录 | 可观测（§七 通过后） | `prompt_cache_hit_tokens` |

**人工体感验证**（最重要）：在你 16:00 那类连续短问句场景下复测——第一句之后紧接着问第二句，
看阿呆是否还"像没看前文"。

---

## 六、灰度与回滚

| 环节 | 做法 |
|:-----|:-----|
| **灰度** | `adai.context.assembly-mode` 默认 `legacy`；本地/生产切 `v1` 生效。**同一批代码两种行为**，可随时对比 |
| **回滚** | 改回 `legacy` + 重启 `adai-core`（`@Value` 为启动期读取，**回滚需要一次重启**；不涉及数据迁移，无残留） |
| **数据风险** | **零**——本批不写 `data/`、不改任何落盘格式（D2：存量一字不动） |
| **契约风险** | **零**——`ContextPackage` 对外形状不变（仅内部装配与顺序）；API 响应字段不变；**前端零改动** |

---

## 七、待验证项（**动代码前必须先跑一次**）

| # | 待验证 | 为什么必须先验 | 验证方式 |
|:--|:-------|:---------------|:---------|
| **V1** | **DeepSeek 流式响应是否返回 `usage`** | 现在 `DeepSeekAiClient` **完全没有解析 `usage`**（grep 零命中）——`prompt_cache_hit_tokens` / `prompt_cache_miss_tokens` 拿不到，F5"真实 token"只能停在**本地估算** | 用生产 key 发一次带 `stream_options: {"include_usage": true}` 的请求，看最后一块是否给 usage；非流式路径直接读响应 JSON 的 `usage` 即可 |
| **V2** | **短对话的实际前缀长度** | 决定缓存收益是否值得记录——调研结论是**短对话本来就不够门槛，不要为它做设计妥协**（主方案 §4.6） | 组装日志打出 `L0` 段的实际字符数 |
| **V3** | **`kind=preference` 的存量条数与质量** | D1 的核心记忆取它；若存量为 0 或全是噪音，D1 要回退成"只给身份" | 只读统计：677 条里 `kind: preference` 共 **25 条**（F8 已初测），需抽样看内容是否适合常驻 |
| **V4** | **把「prompt 分块占比」固化成巡检项** | 用户 2026-09-29 指出「**生产对话数据可以查询，或者监控呀**」——本次已用它一次性取证（L4 **46%** / L2 **34.9%** / 当前记录 **4.3%**），但**每次都要临时写脚本**；固化后改动前后可直接对比，也能防将来悄悄退化 | 把本次分析脚本落进 `ai-engineering/`（`ai-guard-prod.sh` 加一节，或独立 `ai-guard-context.sh`）；`ai-logs` 保留 30 天，随时可跑 |
| **V5** | **`ai-logs` 有两处关键缺口，补上才算真正的"对话上下文监控"** | 本次取证时发现：① question 记录**只记 `prompt`，不记 `messages`（对话前文）**——CHAT 模式最核心的输入**从来没被记录**；② 只有 `responseLength`/`responseSummary`（54 字符），**没有回答全文**。所以现在**无法回答"那一轮阿呆到底看到了什么"** | ① 在 `DeepSeekAiClient` 组装后把 messages 摘要（条数 + 各条长度 + 指纹）写进 ai-log（全文可选、注意体积）；② 记 `usage`（V1 通过后）与缓存命中；③ 回答全文已在卡片文件里，可不重复 |

---

## 八、批 1 明确不做

- 不做两臂召回、不做相关性打分（批 2）——本批**只是把 20 收成 2 并在短对话整层关闭**；
- 不做指纹去重、不做配额硬上限（批 2）；
- 不建 `context/token`、`context/policy`、`context/prompt` 三个包（批 2）——本批把逻辑留在 `ContextEngine` 内，**避免一次改动跨两处**；
- 不碰 `MemoryService` 的写路径与 `data/`（D2）；
- 不建 LLM-as-judge 评测门禁（批 2 前置）。

---

## 附：开工顺序（若获批）

1. **V1/V3 先验**（只读、半小时内）→ 若 V1 拿不到 usage，⑤ 的"真实 token"降级为纯本地估算（其余不变）；
2. 改 `ContextEngine`（① ② + 一次读卡）→ 跑 `ContextEngineTest`（含新增 4 例）；
3. 改 `DeepSeekAiClient`（③ ④）→ 跑 `DeepSeekAiClientTest`；
4. 改 `ContextPackage`（⑤）→ 更新 2 例既有断言；
5. 本地 `assembly-mode=v1` 用生产同构卡片跑一次对比（同一段对话，legacy vs v1 的 messages 结构）；
6. `code-deploy-gate.sh` 前置三门 + 派审查官（含对抗官）→ **部署等你点头**（B8）。

---

## 九、实施状态（2026-09-29 当日）

**已完成**（代码 + 测试；**未部署**）：

| 项 | 落点 | 关键点 |
|:---|:-----|:-------|
| ① 按轮次分档 | `ContextEngine.compose` + 新增 `ContextAssemblyPolicy` | 短/中/长三档；**短档不读完整记忆、不检索**（测试用 `verify(never())` 钉住——省的不只是 token，还有全量扫文件） |
| ② 收掉「最近 20 条」 | `ContextEngine.loadRelatedRecords` 的 fallback | 条数由策略决定：v1=2 · legacy=20（回归保护） |
| ③ 去重复注入 | `ContextEngine`（v1 下不再产出 `cardContext`） | 同一段对话只以 messages 形式出现一次 |
| ④ 单条 system + 顺序 | `DeepSeekAiClient.buildChatRequestBody` | v1 = 唯一一条 system（稳定前缀 + 输出契约）；**动态参考垫在最后一条 user**；legacy 分支逐字保留 |
| ⑤ 真实计量 + 分段用量 | `ContextPackage.estimateTokens` + 组装日志 | 计量补上 `conversationHistory` / `relatedRefs` / `stableSystem`；日志新增 `分段字数 L0..L4 当前记录` |
| 附带 · usage 解析 | `DeepSeekAiClient`（非流式 + 流式） | 流式加 `stream_options.include_usage`；`prompt_cache_hit_tokens` / `miss` 落日志（此前**零解析**） |
| 附带 · 一次读卡 | `ContextEngine.compose` | 单次对话的卡片解析由 **2~3 次**降到 1 次（`findById` 是 `findAll` 全量遍历） |

**与设计的偏离（如实记录）**：

1. **新建了 `kernel/context/policy/ContextAssemblyPolicy`**——设计 §八 原写「批 1 不建三个包」。理由：6 个配置项若都做成 `ContextEngine` 的构造参数，11 处调用点会非常难读；收敛成一个策略对象更可测（`legacy()` / `v1()` 两个工厂）。**只落了 policy 这一小块**（分档与配额），时效与去重仍留批 2。
2. **`ContextPackage` 新增 `stableSystem` 字段**——设计未提。理由：让「v1 单条 system」的判据**随包下发**，`infrastructure` 层无需注入策略（依赖方向不变）；**旧构造签名全部保留**，另 2 处调用点（`simple()` / `TradingReviewAppService`）零改动。
3. **`buildChatRequestBody` 改为包级可见**——供测试直接断言 messages 结构，与既有 `parseChatCompletion` 同一惯例。
4. **usage 解析直接实现**（未停在 V1 验证）——查官方文档确认 `stream_options.include_usage` 与「`[DONE]` 前最后一块必带 usage」后，认为无需先验；实现本身零风险（解析失败只记 debug，绝不影响主流程）。
5. **L4 只收紧 trading**（设计已写明）——`learn` 保持原行为，不擅自扩大范围。

**测试**：新增 `ContextAssemblyV1Test`（**7 例**，逐条反向可验证——含「短档不读完整记忆、不检索」的 `verify(never())`、fallback 2 vs 20 的对照、以及**缓存前缀跨轮次逐字稳定**）+ `ContextPackageTest` 1 例 + `DeepSeekAiClientTest` 3 例 = **本批 +11**；**11 处** `new ContextEngine(...)` 调用点补策略参数（既有测试**一律 `legacy()`**，保证旧行为断言不变）。

**全量后端测试**：**2244 全绿**（0 失败 / 0 错误 / 2 跳过，2026-09-29 本机实跑）。

> ⚠️ `docs/reference/status.md` 第 12 行的后端测试数（**2234**）是**并发的另一批**（资金曲线取数批，工作区未提交）写在同一行的——本批**不去抢那一行**（避免与并发会话争同一处文档），留待收工 / `/ship` 时按最终口径统一回写。

**待完成**：`/ship` 门禁 · **部署待用户点头**（B8）。

### 9.2 独立对抗审查与随修（2026-09-29）

派独立子代理（**对抗视角**，只报告不改代码）深审本批：**P0 未发现；两处 P1 当场修复**。

| 级别 | 发现（证据） | 处理 |
|:-----|:-------------|:-----|
| **P1-1** | **v1 把 L3 检索 + L4 知识/领域上下文「算完即丢」**：`buildDynamicRefs` 只拼 `relatedRefs` 的三元素，而 v1 **不调用** `buildContextFromPrompt`（`DeepSeekAiClient.java:521` 只有 legacy 用）→ 对话里交易哲学 / 学习卡 / 生活知识 **0 注入**，本批新加的 L4 门控在对话径**完全失效** | ✅ **已修**：v1 下 `relatedRefs` 扩为五元素（补 `searchResults` 与「知识 + 领域 + 全局」块），检索与命中领域的知识**显式随包下发**；新增回归用例**断言实际下发通道**（不再只测 `prompt`） |
| **P1-2** | **无卡片场景（随手记 / 复盘 / 重补）恒为 SHORT 档**：三参 `compose` 无 cardId → turns=0 → 相关历史与近期记忆被误砍；更严重的是 `loadMemorySummary` 停调 → `touchActive`（记忆进化 Phase 4 的 `lastConfirmed` 累积）**对新记录彻底停止** | ✅ **已修**：`cardId == null` 一律按 **LONG**；补 `noCard_treatedAsLongTier_notShort` 回归（含 `verify(memory).touchActive(...)`） |
| P2-1 | 两个矛盾的 token 数并存：引擎日志仍旧 `prompt.length()/2`，而 v1 下 prompt 根本不是发送内容 → 从「漏算」变「高估」，设计 §六「误差 <5%」不成立 | ✅ **已修**：日志改为 `注入合计=…字符`（真实分段合计），不再冒充 token；精确 token 以 DeepSeek 的 `usage` 为准 |
| P2-3 | `loadCoreMemory` 用 `findByKind`（**逐日 30 次查询**）比中档 `recentActive(7)` **更贵**，与「短档省开销」相反；且「长期偏好」名不符实 | ✅ **已修**：改用 `recentActive(30)` 一次取 + kind 过滤（与中档同路径）；文案改「**近期**偏好（近 30 天）」 |
| P2-4 | **测试假绿 2 处**：① `tradingKnowledge` 只断言 `prompt`（v1 根本不发）；② 客户端「前缀稳定」用例两侧硬编码**同一常量**（≈自比较） | ✅ **已修**：① 改断言实际下发通道；② 新增**引擎级**稳定性用例 `stableSystem_fromEngine_isIdenticalAcrossTurns`，客户端用例注明其局限 |
| P3-1 / P3-2 | `estimateTokens` 遍历 `relatedRefs` 无 null 保护；`loadCoreMemory` 预算不足时**只留一个光标题** | ✅ **已修**（紧凑构造补 null 兜底；装不下就不注入） |
| P2-2 / P3 | ai-log 记的不是实际发送内容（V5 未达成）· 缓存前缀的实际边界窄于宣传 · R1 未随批 | 📋 **已登记 REVIEW**（`P2-对话2` / `P2-对话3`），留批 2 或 D4 拍板 |

**审查同时确认（可作为交付背书）**：legacy 的**模型侧逐字不变**（system 拼接、`chatOutputContract` 抽取、3 条 system 顺序、`relatedRefs` 三元素、20 条 fallback，逐项核对）；11 处 `new ContextEngine(...)` **无遗漏无误用**；边界（卡不存在 / turns 空 / 历史空 / 末条非 user / `stableSystem=null`）**均不抛不畸形**；SSE 的 usage-only 块不会污染 delta。

> ⚠️ 审查指出的一处**文档口径**：`ContextPackage` 增至第 11 个组件——本文 §六「对外形状不变」指的是**API 契约与前端**（确实零改动），**不是** record 内部形状；措辞以本条为准。
>
> ⛔ **未随批（如实登记）**：R1（app 端对话态可见性）属 `apps/adai-app` 改动，本批只做后端 → 登记 `P2-对话3`。

### 9.3 欠账随修（2026-09-30，跨日续批）

| 项 | 落点 | 说明 |
|:---|:-----|:-----|
| **R1 落地**（原 §2.3 说「与批 1 同批」，09-29 当天只做了后端） | `apps/adai-app/lib/main_page.dart` `_syncActiveCard` | 活动卡被刷新挤出列表时**如实告知**（对齐 web 2026-09-26 批的文案与做法：帧后弹「刚才那段对话已经翻出当前列表了，再发就是新的一段」）。此前 app 这一支**静默** → 用户「说着说着下一句就变成新的一条」 |
| **V5 落地**（原 §七 待验证项，属可观测性缺口） | `DeepSeekAiClient` v1 分支 | 每次 v1 装配落一行**实际结构**：`system=1条(x字符) \| 历史=n条 \| 动态参考=y字符`。原因：ai-log 记的是 `ctx.prompt()`，而 v1 下 prompt **不是发送内容**（检索/知识改由 `relatedRefs` 下发）——只看 ai-log 会得出「阿呆看到了交易知识」的假象（`REVIEW P2-对话2`） |

**R1 的取舍（如实记录）**：**未加 widget 回归**——web 端同一提示（2026-09-26 批）也没有专门回归，本批按同一口径处理；行为依据写在 `_syncActiveCard` 的注释里（含生产实据 47% STATEMENT）。真要做，需构造「活动卡被 `_loadFeed` 挤出 page0」的 widget 场景，成本大于本条收益，留待批 2 统一补。

**验证**：`flutter analyze` **0 issue** · app **423 全绿** · 后端 **2249 全绿**（`--rerun-tasks --no-build-cache` 真跑）。

### 9.1 预期收益（**估算，非实测**——仅供设预期）

按实测分块占比（L4 知识源 46.0% · L2 记忆 34.9% · 相关历史 12.6% · system 内前文 0.7% · 当前记录 4.3%）
与档位分布（短 ≤3 轮占 45% · 中 4~9 轮占 24% · 长 ≥10 轮占 31%）推算：

| 档位 | 变化 | 估算降幅 |
|:-----|:-----|:---------|
| **短** | L3 相关历史/检索 → **0**；L4 知识源（生活话题）→ **0**；L2 从「完整近期记忆」降到「核心偏好 ≤200 token」；system 内重复前文 → **0** | **约 −90%**（10699 → ~1000 字符量级） |
| **中** | 省 L3 检索 + 重复前文 | 约 −13% |
| **长** | 省重复前文 + 未命中领域的 L4 | 视内容而定 |

**加权后，对话模式的注入量约降一半**；对占比最大的短对话（45%）则是数量级的变化。

> ⚠️ 以上是**推算式预期**，不是实测。真正的数字以部署后日志里的 `分段字数 L0=… L1=… L2=… L3=… L4=… 当前记录=…` 为准——
> 那行日志就是本批为「可验收」而加的（此前只有两个不完整的计数）。
