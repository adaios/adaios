---
title: 节律与记忆时效——「每周四发版」不是待办（概览卡天天提醒的根因与治法）
description: 用户 2026-09-23 反馈「阿呆 App 概览卡片，天天提醒我」。根因钉到三处：① `RecordToTodoLinker` 把一句状态陈述（「今天周四 固定发版日 在加班」）转成永久 OPEN 待办；② 简报 prompt 同时注入 Open todos（附「主动提醒 1-2 件」指令）与近 7 天记忆（附「发现习惯就自然提及」指令），且读的是不过滤 superseded 的 `recent()`；③ `Memory` 没有「周期」维度、没有有效期区间——「周四」只是 tags 里的字符串，机器无法判断「今天该不该提」。本 RFC 定：节律（rhythm）独立为 Kernel 一等条目（周期用 iCalendar RRULE 表示）、记忆补 bi-temporal 有效期（作废不删）、简报注入加「命中日 / 分类口径 / 硬上限」三道闸、变更走「问一句」而非静默改写。行业依据：RFC 5545 RRULE、Zep/Graphiti bi-temporal、shisad Active Attention（`recurring` 为一等类 + workflow_state）、主动性服务综述（沉默是默认项、提问有决策价值）。
date: 2026-09-23
status: approved
decided-by: adai（2026-09-23 拍板：「那就按照建议来」——T1ⓐ 独立 rhythm 通道 / T2ⓐ 命中日作背景且不提 / T3ⓐ 暂不给入口 / T4ⓐ 旧数据标完成 + 转节律 / T5ⓐ 先上 A 批止血再排 B/C）
tags: [记忆, 节律, 待办, 简报, 概览卡, 主动性, 时效, 误判]
related:
  - ./20260917-todo-kernel-retire-project-plugin.md
  - ./20260916-first-meeting.md
  - ./20260801-memory-system-evolution.md
  - ./20260727-memory-upgrade.md
  - ./20260718-context-memory-knowledge-loop.md
  - ../reference/feature-reference.md
  - ../review/REVIEW.md
---

# 节律与记忆时效——「每周四发版」不是待办

> **触发来源**：2026-09-23 用户反馈（原话）——「**阿呆 app 概览卡片，天天提醒我**」，
> 并在追述中给出定性：「**你要区分开，这是我的工作周期习惯，不是待办**」，
> 随后把设计问题问到了根上：「**是否你对用户的记忆会维护个类似日历、星期的概念来进行记忆存储呢**」。
>
> **本 RFC 的定位**：① 把「天天提醒」的完整因果链钉到行号（§一）；② 把「节律 ≠ 待办」这个定性
> 落成可实现的形态（§四）；③ 具体形态不自行发明，一律对标行业既有实现（§三）。
>
> **实施边界**：本文为**文档先行件**。获批前**不改任何代码、不动任何 `data/` 数据**（含生产上那条待办）。
> 获批后按 §五 分批开工。

---

## 一、现状事实（逐条钉到行号）

### F1 用户说的「概览卡片」= App 首页的「今天」卡

| 环节 | 证据 |
|:-----|:-----|
| 卡片本体（标题「今天」+ 首行问候 + 最多 3 行内容） | `apps/adai-app/lib/main_page.dart:2044`（`_buildBriefCard`）、`:2059`（`Text('今天')`） |
| 内容来源 | `GET /api/v1/brief` → `BriefAppService.generateBrief`（`BriefAppService.java:80`） |
| 行数预算 | 后端 `truncateLines(..., 4)`（`BriefAppService.java:127`）——1 行问候 + 3 行内容 |

即：卡片上那 3 行内容，全部由一次 AI 调用生成，**输入只有 prompt**（`:114`）。要让它少说，只能改 prompt 的输入或规则。

### F2 一句状态陈述如何变成永久待办

| 环节 | 证据 |
|:-----|:-----|
| 用户原话（2026-09-17 19:32 记录） | 生产 `data/adai/records/2026/09/rec_20260917_193216462.md`：「今天周四 固定发版日 在加班」（`intent: log`，无任何动作词） |
| 转待办的判据 | `RecordToTodoLinker.link`：`intent=log` AND `understanding.actionable()==true` AND 摘要非空（`RecordToTodoLinker.java:48-67`） |
| 调用点 | `RecordController.java:264-270`（best-effort，非交易陈述才走） |
| 产物 | 生产 `data/adai/todos/2026/09.md`：`task_20260917_193222090`「周四固定发版加班」，`status: OPEN`，`due` 空 |

**即：AI 在 2026-09-17 把「我在加班」这一句状态陈述判成了 `actionable`，系统据此建了一条没有到期日、永远不会自动结束的待办。**
一句话状态被当成了一件"要做的事"——这是本 RFC 的**第一处错位（归属错位）**。

### F3 为什么是「天天」——简报 prompt 有五路注入，其中两路在鼓励提

`buildBriefPrompt`（`BriefAppService.java:206-341`）按顺序拼入：

| # | 注入段 | 行号 | 关键措辞 |
|:--|:-------|:-----|:---------|
| 1 | 今天日期 + 星期（仅作为**文本**喂给 LLM） | `:212-219` | `Date:` / `Day of week:` —— 只是打印，**不参与任何筛选判断** |
| 2 | Recent records（近 2 天记录） | `:221-231` | — |
| 3 | What AI understands about this user（近 **7 天**记忆） | `:233-239` | 逐条 `m.summary()` 平铺 |
| 4 | **习惯注入指令** | `:249-251` | 「If you notice a pattern or habit …（e.g. they exercise on certain days）, **mention it naturally**」 |
| 5 | **Open todos** | `:312-329` | 「Open todos (not done, **should be surfaced to user**)」 |
| — | 输出规则 | `:331-338` | 规则 7：「If there are open tasks, **proactively remind 1-2 most important ones**」 |

**两路叠加**：那条 OPEN 待办永远排在前 3（`:315-317` 取 `limit(3)`），记忆条目又落在 7 天窗口内（`:102`），
再叠加第 4 条「发现习惯就自然提及」——**每天的 prompt 都在明确要求模型提这两样东西**。
所以这不是"模型偶尔跑偏"，是**输入侧的设计结果**。

### F4 Memory 层缺两样东西：周期维度、有效区间

`Memory` 的全部字段（`kernel/memory/Memory.java:36-54`）：

```
id / recordId / cardId / kind / summary / patterns[] / preferences[] / tags[] /
sentiment / actionable / suggestion / createdAt / topic / superseded / evolvedTo /
doneAt / lastConfirmed
```

- **有时间点，没有周期**：`createdAt` / `lastConfirmed` 都是"某一刻"，没有任何"每隔多久、命中哪一天"的表达；
- **有效期缺失**：只有 `superseded`（被新版取代）与 `evolvedTo`（演变指针），没有"这件事从何时为真、到何时不再为真"的区间；
- 「周四」只能以**字符串**存在，实际落点正是两个字符串字段：生产 `data/adai/memory/2026/09.md` 的
  `mem_20260917_193220075` —— `tags: [发版日, 周四, 加班, 工作节奏]`、
  `patterns: [{"content":"每周四为固定发版日，该节点伴随加班…","confidence":0.75}]`。
  **机器能读到「周四」这两个字，但不能据此判断"今天是不是周四、该不该提"。**

回读侧还有一个口径不一：简报用 `memoryService.recent(userId, 7)`（`BriefAppService.java:102`），
而 **`recent()` 不过滤 `superseded`**（`MemoryService.java:158-165`）；
过滤版是 `recentActive()`（`MemoryService.java:448-452`），已被 Context Engine 采用（`ContextEngine.java:344`）。
→ 结论：**即便把这条记忆标记作废，也压不住概览卡**（7 天窗口内仍会注入）。

### F5 缺一条反向撤销通道（误建后无法回收）

生产快照（2026-09-23 现场核对）显示：源记录对应的记忆 `mem_20260917_193220075` 当前为
`actionable: false`，而由它生成的待办仍是 `OPEN`。

这说明 RFC 20260917 确立的「**建待办不再动记忆**（单向同步，`RecordToTodoLinker.java:20-23`）」
在**误建**场景下没有回退路径：待办一旦建出，只能人工去清单里点完成。**误判成本被永久固化。**

---

## 二、问题定性

不是"少写一条待办"，是**三条错位叠在一起**：

1. **归属错位**：节律（周期性习惯）没有自己的位置，只能被塞进「记录 → 待办」这条唯一管道。
   而待办的语义是**一次性、有终点**（`OPEN → DONE`）；节律**永不结束**，天然到不了 DONE。
   → 系统里唯一的"长期事实"容器是记忆，可记忆**又不参与"该不该提"的判断**。
2. **时效错位**：`createdAt` 是点，节律是周期。**没有周期表示，就没有命中判定；没有命中判定，就只能天天注入。**
   叠加规则 7 与习惯注入指令，结果必然是每日复读。
3. **主动性错位**：简报把「有内容」当成「该开口」。行业共识恰好相反——
   主动性是一个**决策**，动作空间里「保持沉默」应当**优先于**「说出来」（见 §三 R4）。

---

## 三、行业依据（形态不自行发明）

| # | 问题 | 行业做法 | 出处 |
|:--|:-----|:---------|:-----|
| R1 | 「每周四」怎么表示 | **iCalendar `RRULE`**（工业标准，Todoist / Google Calendar 通用）：`FREQ=WEEKLY;BYDAY=TH`；隔周 `INTERVAL=2`；到某日为止 `UNTIL=`。**不自创字段名** | [RFC 5545 §3.3.10](https://www.rfc-editor.org/rfc/rfc5545.txt) |
| R2 | 节律与待办是否同一类 | 成熟系统把每轮议程编译成**多类一等条目**：`open_thread / scheduled / **recurring** / waiting_on / inbox_item`；每类带 `workflow_state`（active/waiting/blocked/stale/**closed**，closed 为终态）。节律独占 `recurring` 一类，且**永不进 closed** | [agentic-memory 横评（shisad Active Attention）](https://github.com/lhl/agentic-memory/blob/main/ANALYSIS.md) |
| R3 | 事实的「有效期」 | **bi-temporal**：`tvalid/tinvalid`（事实为真的区间）与 `t'created/t'expired`（系统何时学到/推翻）**分开记**；失效走 **invalidate 而非 delete**，历史全留 | [Zep: A Temporal Knowledge Graph Architecture](https://arxiv.org/abs/2501.13956) |
| R4 | 何时该主动说话 | 把主动性建成**部分可观测序贯决策**，动作空间 `{保持沉默, 询问, 协助, 执行}`；两个关键量：**option value of waiting**（等待本身有价值）与 **decision value of questions**（该问就问，胜过擅自断言）。并明确一句：**长期记忆不是主动性的定义条件** | [Proactive Service Agents（2026 综述）](https://arxiv.org/abs/2609.03727)、[MiPP](https://dl.acm.org/doi/10.1007/978-3-032-30860-3_23) |
| R5 | 检索/注入的闸门 | 三因子打分（**recency × importance × relevance**）+ **硬 top-K 上限**；几乎所有系统都收敛到"有上限的注入" | [Generative Agents](https://dl.acm.org/doi/fullHtml/10.1145/3586183.3606763) |
| R6 | 衰减按什么算 | 按 **activity-day**（真有交互的天），不是日历日——否则不活跃期反而被提醒得更凶 | agentic-memory 横评（MIRA-OSS） |
| R7 | 事实变了怎么办 | **strong-invalidation UX**：检测到状态变化不静默改写，而是**问一句**（「我注意到你说你已经不在 ACME 了——要更新吗？」），用户答了才改；沉默则静默过期 | agentic-memory 横评（shisad） |

**R4 直接回答本次事件**：用户「可能加班、也可能这周四不用」= 节律是**概率**不是**承诺**。
系统应当**知道**它，而不是**催**它。正确动作是：命中日作为背景注入（或不注入），变更时**询问**。

---

## 四、方案（待拍板）

### D1 节律（rhythm）独立为 Kernel 一等条目，与待办分家

- **形态**：`data/{userId}/rhythm/YYYY/MM.md`（File First，与 `todos/` 同级同构）。
- **表示**：`recurrence:` 直接存 **RRULE 字符串**（R1），如 `FREQ=WEEKLY;BYDAY=TH`。
- **状态机**：`active / paused / retired`（对标 R2 的 workflow_state），**没有 DONE**——
  节律不会被"完成"，只会被"暂停"或"退役"。
- **字段（草案）**：
  ```
  id / title / recurrence(RRULE) / status / sourceRecordId / validFrom / validUntil(可空)
  / createdAt / lastConfirmed
  ```
- **来源**：由记录或记忆升级而来（D5），也可由用户在 App 里直接建。
- **它不是提醒**：节律**永不进入**"待办 / 到期提醒"那套口径与推送通道。

### D2 Memory 补 bi-temporal 有效期（作废不删）

- 增 `validFrom` / `validUntil`（R3 的 `tvalid/tinvalid`），与 `createdAt` / `lastConfirmed` 分开：
  前者说"这事何时为真"，后者说"我何时知道/何时复核"。
- **失效 = 填 `validUntil`，不删条目**（append-only 历史，与既有 `superseded/evolvedTo` 兼容）。
- **回读统一走 `recentActive()`**（修 F4 的口径不一）：简报、Context Engine 用同一把尺子，`superseded` 与
  `validUntil < now` 都不得进入"当前有效"集合。

### D3 简报注入加三道闸（治「天天提醒」的正面）

| 闸 | 规则 |
|:---|:-----|
| **闸 1 · 命中日** | 带 `recurrence` 的条目**只在 RRULE 命中当天**进入 prompt；未命中日**不注入**（不是"注入了但别说"，是从源头不进） |
| **闸 2 · 分类口径** | prompt 里**按类型分段**：`Open todos`（该做的事）与 `Rhythm`（背景节律）分开；**「主动提醒」指令只作用于前者**；节律段的措辞是"知道即可，不要提醒、不要询问是否要做" |
| **闸 3 · 上限与去重** | 注入条数硬上限（沿用 `limit(3)` 思路并显式化）；同一 `topic` 在 N 天内已提过则不再注入（R5 的 top-K + R6 的 activity-day 口径） |

> 附带修一条：`:249-251` 的「发现习惯就自然提及」指令应**删除或改为条件触发**——
> 它是 F3 里最直接的一句"鼓励唠叨"。

### D4 变更走「问一句」（strong-invalidation UX）

用户说「这周四不用加班 / 以后不一定是周四了」时：

1. **不静默改写**记忆，也不自动 retire 节律；
2. 阿呆**问一句**：「周四这个发版日，以后还按固定算吗？」（R7）；
3. 用户答**是** → `lastConfirmed` 更新（置信度上调，R7 的 re-verification）；
   答**否/暂停** → 写 `validUntil` 或转 `paused`；**沉默** → 到期静默降权，不反复追问。

### D5 写入侧误判防护（把 F2 堵在源头）

`RecordToTodoLinker` 的判据从「`actionable==true`」收紧为「**`actionable==true` 且原文含可执行信号**」
（动作/意图词，非纯状态描述）；且**周期表述**（周几 / 每周 / 每月 / 每天）命中时**优先走节律通道**（D1），
不再建一次性待办。判据表落 `ai-engineering` 约定或 prompt，需给出**正反例**。

---

## 五、实施批次（获批后）

| 批 | 内容 | 触碰面 | 可独立验收 |
|:---|:-----|:-------|:-----------|
| **A（止血）** | D3 三道闸 + 删掉「习惯就提」指令；回读统一 `recentActive()` | `BriefAppService`（后端） | ✅ 概览卡次日不再出现该提醒 |
| **B（正解）** | D1 节律条目（模型 + 文件仓 + 端点） + D5 写入侧分流 | `kernel/rhythm`、`RecordToTodoLinker`、端点表 | ✅ 一句"每周四…"落成节律而非待办 |
| **C（时效与回流）** | D2 bi-temporal 字段 + D4 询问式变更 | `Memory`、`MemoryService`、记忆页/对话回流 | ✅ 「今天不用加班」后不再被追问 |

> 旧数据（`task_20260917_193222090` 与 `mem_20260917_193220075`）的处置在 §七 T4 单独拍板，**不随 A 批自动改**。

---

## 六、不做 / 边界

- **不给节律加"完成"按钮**（语义不成立）。
- **不把节律做成推送**：它不触发 APNs，不进 Feed 推送通道；只在"命中日 + 用户打开 App"时作为背景出现。
- **不引入日历系统**：只借 RRULE 的**表示**，不建日程/冲突/时区那一套（B4 不提前复杂化）。
- **不做全量历史迁移**：`Memory` 新字段对旧条目按"缺失=永久有效"读取（后向兼容），不做离线迁移。
- **不改第一原则**：节律出现在用户面前时仍是「我和阿呆」的自然对话，不得出现"系统已记录你的周期习惯"这类第三视角（B1）。

---

## 七、待拍板项

| # | 问题 | 备选 |
|:--|:-----|:-----|
| **T1** | 节律放哪 | ⓐ 独立 `rhythm/` 通道（本文推荐，语义最干净）· ⓑ `Memory` 加 `recurrence` 字段（改动最小，但记忆与议程仍混在一起） |
| **T2** | 命中日行为 | ⓐ 当天注入作背景、不提（推荐）· ⓑ 完全不注入，只在被问到时才提 |
| **T3** | App 可见入口 | ⓐ 暂不给入口（记忆页/档案页可见即可）· ⓑ 待办页加「节律」分区 |
| **T4** | 旧数据处置 | ⓐ 待办标完成 + 记忆补 `validUntil` 并转节律（推荐）· ⓑ 只标完成，不迁节律 · ⓒ 原样保留，仅靠 A 批闸门压住 |
| **T5** | A 批是否先做 | ⓐ 先上 A 批止血（次日见效）再排 B/C（推荐）· ⓑ 一次性 A+B+C |

---

## 八、验收口径

- **A 批**：次日打开 App，概览卡**不再出现**「周四发版加班」（也不必出现"我不再提醒你"之类的元话语——安静即可）。
- **B 批**：输入「每周四固定发版加班」→ 生成的是**节律**条目（`recurrence=FREQ=WEEKLY;BYDAY=TH`，状态 `active`），
  **待办清单里零新增**；输入「周四要交周报」→ 仍正常转待办。
- **C 批**：说一句「这周四不用加班」→ 阿呆**问一句**是否需要调整，而非静默改写；不作答则 7 天后自然从注入中消失。
- **回归**：`GET /api/v1/brief` 的注入条数不超上限；`recent()` 与 `recentActive()` 不再出现两套口径。

---

## 九、实施记录

### A 批（2026-09-23 落地，**未部署、未 push**）

| 项 | 落地内容 | 位置 |
|:---|:---------|:-----|
| 闸 2（口径） | 周期习惯不进提醒段：新增 `isRhythmLike` 判据（「每周/每月/每天/例会/定期」或「周X＋固定」组合），`OPEN` 待办先过滤再注入 | `BriefAppService.java`（`RHYTHM_LIKE` / `isRhythmLike`） |
| 闸 3（上限） | `MAX_BRIEF_TODOS = 3` 显式化（原为裸写 `limit(3)`）；挡下条数落 debug 日志 | 同上（Todo signals 段） |
| 正面成因 | **删除**「发现习惯就自然提及」指令 | 同上（原 Habit injection 段） |
| 回读口径 | 简报改用 `recentActive()`（过滤 superseded），与 Context Engine 对齐 | `BriefAppService.java` `generateBrief` |
| 规则收紧 | 规则 7 改为「只提醒列在上面的待办」；**新增规则 8**「禁止编造提醒，禁止把习惯/惯例/周期事件当要做的事」 | 同上（Rules 段） |
| 测试 | `isRhythmLike` 正反例（含「周四要交周报」不得误伤）；prompt 级断言：节律条目被挡、同批真待办仍注入、习惯指令已删、规则 8 存在、全节律时提醒段整体缺席 | `BriefAppServiceTest`（+3 用例，共 10） |

**闸 1（命中日）未在 A 批落地**——它依赖 `recurrence` 字段（B 批）。
A 批的替代手段是「整类挡下」：宁可少说，不猜（对齐 §三 R4「沉默是默认项」）。

**T4 顺延至 B 批**：ⓐ 的后半段（转节律）依赖 B 批的 rhythm 通道，前半段（标完成）在注入侧已挡下后并非必要。
两次动生产数据不如一次做完，故 A 批**不动任何 `data/`**——那条待办在待办页照常可见、可手动处理。

### B 批（2026-09-23 落地，**未部署、未 push**）

| 项 | 落地内容 | 位置 |
|:---|:---------|:-----|
| D1 节律条目 | 新领域 `kernel/rhythm/`：`Rhythm`（模型）· `RhythmStatus`（active/paused/retired，**无 DONE**）· `RhythmRepository`（端口） | `kernel/rhythm/` |
| D1 周期表示 | `RruleSchedule`：RRULE 子集解析 + 命中判定（`INTERVAL` / `BYDAY` / `BYMONTHDAY` / `UNTIL`；**未知部件拒绝**，不静默忽略；`BYMONTHDAY=31` 遇小月**跳过**） | `kernel/rhythm/RruleSchedule.java` |
| D5 判据收敛 | `RhythmDetector`：`isRhythmLike`（注入侧）+ `detectRrule`（写入侧，**推不出返回 null**）——A 批散在 `BriefAppService` 的私有副本收敛为单一真相源 | `kernel/rhythm/RhythmDetector.java` |
| D1 存储 | `RhythmFileRepository`：`data/{userId}/rhythm/YYYY/MM.md`（File First；未知 status 保守读作 PAUSED；RRULE 脏数据按不命中） | `infrastructure/storage/` |
| D1 端点 | `RhythmController`：`GET\|POST /api/v1/rhythms` + `PUT\|DELETE /api/v1/rhythms/{id}`（**无插件门控**，与待办同级 builtin） | `interfaces/RhythmController.java` |
| D5 写入侧分流 | `RecordToRhythmLinker` + `RecordController` 接线：**先试节律，再试待办** | `application/` · `interfaces/RecordController.java` |
| D3 闸 1 | 简报注入节律段：**只在 RRULE 命中当天**注入，口径 `BACKGROUND ONLY — do NOT remind, do NOT ask whether it will happen`（上限 `MAX_BRIEF_RHYTHMS=3`） | `BriefAppService.java` |
| 异常映射 | `RhythmException` → 400 人话（周期非法 / 条目不存在） | `GlobalExceptionHandler.java` |
| 测试 | RRULE 9（含小月跳过 / UNTIL 含当日 / 非法规则 8 例）· 判据 3 · 文件仓 6（含可选行缺失与未知状态）· 分流 7（含幂等与「周四要交周报」不误转）· 简报闸 1 新增 2 | `RruleScheduleTest` 等 4 个新文件 + `BriefAppServiceTest` |
| B3 边界 | `.gitignore` 补 `data/*/rhythm/`；**顺带补漏 `data/*/todos/`**（RFC 20260917 迁移时漏掉） | `.gitignore` |

**已知边界（如实登记）**：`detectRrule` 只支持 **每天 / 每周 / 每月** 三种频率——「每季度 / 每年 / 定期 / 例行」
能通过 `isRhythmLike`（注入侧不催），但**推不出 RRULE → 不转节律**，回落为待办。宁可漏判成待办，也不猜一个假周期。

**未做**：C 批（D2 记忆 bi-temporal、D4 询问式变更）与 **T4 旧数据处置**。
