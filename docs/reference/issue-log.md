# 问题修复文档（Issue Log）

> **定位：** 按功能模块组织的问题跟踪。对照 `feature-reference.md` 使用。
> **格式：** 每个问题标注所属模块、现象、根因（区分本质类型）、修复方案、当前状态。
> **状态由你判定** — 此文档只记录代码事实，不替你做是否已修复的结论。
>
> **本质类型：**
> - **逻辑设计** — 流程/算法/状态机设计有缺陷，改代码不够，需要改设计
> - **实现遗漏** — 设计正确但代码写漏了某一步（忘了保存 tags、忘了清缓存）
> - **prompt 冲突** — 多层 prompt 指令互相矛盾，模型行为不确定
> - **prompt 缺陷** — prompt 没要求某项输出（漏了 tags 字段）
> - **展示问题** — UI 渲染效果不符合预期
> - **性能问题** — 卡顿、渲染阻塞
> - **死代码** — 定义的逻辑从未被使用
>
> **文档版本：** v5.0 | **最后更新：** 2026-08-01

---

## 模块对照表

| 编号 | 模块名 | 参考文档章节 |
|:----:|:-------|:------------|
| M1 | 主页 Feed 流 | [§1](feature-reference.md#1-主页-feed-流) |
| M2 | 记录提交流 | [§2](feature-reference.md#2-记录提交流) |
| M3 | 问答会话流 | [§3](feature-reference.md#3-问答会话流) |
| M4 | FeedCard 卡片组件 | [§4](feature-reference.md#4-feedcard-卡片组件) |
| M5 | 简报模块 | [§5](feature-reference.md#5-简报模块) |
| M6 | 时间线模块 | [§6](feature-reference.md#6-时间线模块) |
| M7 | 记忆模块 | [§7](feature-reference.md#7-记忆模块) |
| M14 | 定时补完 | [§14](feature-reference.md#14-定时补完服务recordretryservice) |
| M-AI | AI 提示词/解析 | [§2A](feature-reference.md#ai-提示词)、[§3A](feature-reference.md#ai-提示词-1) |

---

## 已修复/已实施（本轮/上轮已完成代码改动）

> 记录代码层面的改动事实。是否算"已修复"由你判断。

### #1 — summary 人称代词（M-AI/M2）

**代码改动：** `DeepSeekAiClient.java:264` — system prompt 从 `"用一句话表达你对用户的理解"` 改为 `"用一句话客观概括，有信息增量，不要复述原文，避免人称代词"`。

**当前代码：**
```
系统层：  "...insight用一句话客观概括...避免人称代词"
用户层：  "...避免人称代词（不用你/我/用户）"
```

**待你判断：** 两层指令现在一致了，但 AI 是否不再输出人称代词？需要 DeepSeek 模式运行验证。

---

### #3/#4 — 结束对话 mode=ended（M4/M3）

**代码改动：** `main_page.dart:205` — `_closeChat` 成功路径改为 `mode: CardMode.ended`。

**当前渲染：** `feed_card.dart` 对 `ended` 态：绿边框 + summary banner + tags + `── ask ──`。

**待你判断：** 前端验证绿边框是否正常显示。

---

### ND1 — QUESTION 场景输出指令统一（M-AI）

**代码改动：** `DeepSeekAiClient.java:173-174` — CHAT system prompt 从 `{"domain":"..."}` 升级为完整 JSON（含 summary/tags/sentiment/domain/actionable）。

**当前代码：**
```
系统层：  "{ summary, tags, sentiment, domain, actionable, actionSuggestion }"
```

**待你判断：** 需要 DeepSeek 模式运行验证 JSON 输出是否稳定。

---

### ND2 — handleDecision 死代码（M2）

**代码改动：** `RecordController.java` — `handleDecision()` 方法（80 行）和 `DecisionResponse` record 已删除。

**当前代码：** 已不存在。

---

### #5 — 任务解析正则跨行 bug + 任务文件数据损坏（Project OS）

**代码改动：**
- `ProjectFileRepository.java` — `ENTRY_PATTERN` 的字段匹配 `.+`/`.*` 在 `Pattern.DOTALL` 下贪婪跨行，`findAll()` 只能解析出 1 条任务。改为 `[^\n]*` 强制单行字段值。
- 同文件 `formatTaskEntry()` 新增 `singleLine()` — title/description 换行转空格，防止多行标题再次写坏条目。
- `project_task_page.dart` — 创建任务时 title 换行/连续空白压成单空格（源头拦截）。
- 新增 `ProjectFileRepositoryTest`（10 个测试）覆盖多条目解析、单行化、CRUD、统计。

**根因（本质类型：逻辑设计）：** 正则开启 `DOTALL` 后 `.` 匹配换行，`title:\s*(.+)\n` 的 `.+` 贪婪吞掉整个文件，多条目文件只解析出第一条。7-30 某次多行 title 写入 `data/project/tasks/2026/07.md`，文件膨胀到 6146 行/219KB（10 个任务重复 512 次）。

**数据处理：** `07.md` 已按 id 去重重建（6146 → 122 行），10 个任务字段完整保留。

**待你判断：** `findAll()` 现在返回全部任务。07.md 中 10 个任务全为 TODO，但对应提交均已存在（实际已完成），是否需要批量标记 DONE。

---

### 其他本轮改动（供你参考）

| 改动 | 本质 | 说明 |
|:-----|:-----|:------|
| **方案 B：错误卡片+重试** | 新功能 | API 失败时保留用户输入，卡片进入错误态，底部 `[重试]` 按钮 |
| **RefreshIndicator 下拉刷新** | 新功能 | Feed 列表支持下拉刷新 |
| **RecordRetryService 定时任务** | 新功能 | 每 15 分钟补无 Memory 的记录和卡片 |
| **RecordRetryService 卡片处理** | 实现补全 | 同时补 CardRecord 的 summary/tags 并沉淀 Memory |
| **endConversation 加 Memory** | 实现遗漏 | `ConversationController.endConversation()` 现在会沉淀 Memory |
| **卡片删除修复** | Bug 修复 | `DELETE /api/v1/records/card_xxx` 会从 `CardFileRepository` 删除 |
| **MockAiClient 删除** | 清理 | DeepSeek 唯一实现，不再维护 mock |
| **Unicode 代理对解码** | Bug 修复 | `🌿` 正确解码为 🌿，前后端双修 |
| **@EnableScheduling** | 配置 | 启用 Spring 定时任务支持 |

---

## 供你判断的待确认问题

> 以下是我观察到但不确定是否算问题的现象，由你决定是否处理。

| # | 模块 | 现象 | 可能的根因 | 你判定 |
|:-:|:-----|:------|:-----------|:------:|
| N1 | M1 | 删除卡片后刷新又出现（之前） | `RecordFileRepository.deleteById()` 不处理 `card_` 前缀 — **已修** | —— |
| N2 | M3 | `endConversation` 不产生 Memory | `ConversationController` 没调 `MemoryService.persist()` — **已修** | —— |
| N3 | M14 | 手动触发 retry HTTP 响应慢 | 串行处理+3秒间隔+AI调用耗时，大批量时可能超时 | ❓ |
| N4 | M4 | Web 端部分 emoji 仍不显示 | CanvasKit 渲染器对部分 emoji 字体支持不完整（手机正常） | ❓ |
| N5 | M3 | 卡片只有用户提问无 AI 回复的（AI 完全没通） | 之前 retryCards() 过滤掉了无 AI 回复的卡片 — **已修** | —— |

## 新增未修问题（2026-07-29）

### #6 — ↑ top 按钮滚动后不触发 "load more"（M1）

**现象：** 点击 "↑ top" 按钮，进度条滚到顶部，但"load more"不出现。需要再手动滚动一下才出现。

**根因：** `animateTo(maxScrollExtent, curve: Curves.easeOut)` 配合 Flutter scroll physics，动画终点可能比 `maxScrollExtent` 差几个像素。`_onScroll` 判断条件 `atTop = pos >= max - 20` 阈值 20px，差几像素就不满足。

**修法：** top 点击改用 `jumpTo(maxScrollExtent)`，瞬时到位。

**涉及文件：** `main_page.dart` — `_buildMoreBanner` 中的 onTap 回调

**状态：** ✅ 已修

---

### #7 — AI 回复称呼用户为"阿呆"（M-AI）

**现象：** AI 回复中经常出现"阿呆"称呼用户，如"又迟到了呀阿呆""好的阿呆，你慢慢想"。

**根因：**
1. `data/identity/profile.md` 中 `name: 阿呆`
2. `DeepSeekAiClient.extractName()` 读取到"阿呆"后注入背景上下文 `"用户称呼：阿呆"`
3. 系统 prompt 说"你是阿呆的个人 AI 助手" → AI 认为自己是"阿呆的助手"
4. 背景说"用户称呼：阿呆" → AI 认为用户叫"阿呆"
5. 两层结合，AI 自然称呼用户为"阿呆"

**修法：** `data/identity/profile.md` 中 `name: 阿呆` → `name: 小王`

**涉及文件：** `data/identity/profile.md`

**状态：** ✅ 已修（需重启后生效）

---

### #8 — 删除记录后刷新又重新出现（M1/M2）

**现象：** 点击删除后，前端卡片消失，但刷新后记录重新出现。`card_` 和 `rec_` 两种前缀的记录都有此问题。

**根因（更正）：** 文件存在两个仓库，但删除只查了一个。

`rec_` 文件可能经 `endConversation` 流程被 `CardFileRepository` 存到 `records/cards/` 目录下。所以同一个 ID 的文件可能同时在：
- `records/YYYY/MM/rec_xxx.md`（RecordFileRepository）
- `records/cards/YYYY/MM/DD/rec_xxx.md`（CardFileRepository）

旧代码只根据 ID 前缀决定删哪个仓库：
```
ID = rec_xxx  → 只删 RecordFileRepository → 漏删 cards/ 下的文件
ID = card_xxx → 只删 CardFileRepository → 不对，已是最全
```

另外 `CardFileRepository.deleteById()` 只删了第一个匹配文件就 return，同 ID 跨日期的多份文件删不干净。

**修法：**
1. `RecordController.deleteRecord()` 无论前缀，两个仓库都删
2. `CardFileRepository.deleteById()` 遍历所有匹配文件，删干净再返回

**涉及文件：** `RecordController.java`（deleteRecord）、`CardFileRepository.java`（deleteById）

**状态：** ✅ 已修（需重启后生效）

---

### #9 — 刷新后最新记录"丢失"（M1）

**现象：** 新增一条记录后，刷新主页，最新添加的记录不显示。预期应显示在 feed 最底部。

**根因：** 分页方向与 UI 逆向。Feed 使用 `reverse: true` 的 ListView（最新内容在视觉底部），但分页按升序掐头（page 0 = 最早条目）。当 `totalToday > pageSize` 时，最新的条目落在第 1+ 页，刷新加载 page 0 后看不到。

**修法：**
1. 后端 `FeedAppService.getFeed()`：page 从尾往前翻，page 0 = 最新条目（tail），page 1 = 更早条目
2. 前端 `_loadMore()`：`_cards.addAll()` → prepend（更早条目插前面，reverse 后才在视觉顶部）

**涉及文件：** `FeedAppService.java`（分页索引）、`main_page.dart`（load more prepend）

**状态：** ✅ 已修（需重启后生效）



## 文档版本记录

| 版本 | 日期 | 改动 |
|:-----|:-----|:------|
| v1.0 | 07-28 | 初始问题跟踪 |
| v2.0 | 07-28 | 两轮修复 + 所有问题 P0/P1 解决 |
| v3.0 | 07-29 | 本质分类 + prompt 冲突发现 |
| v4.0 | 07-29 | 本轮 4 个遗留全修 + 9 项新改动，状态交你判定 |
| v6.0 | 07-29 | 新增 #9 刷新后最新记录丢失 |
| v5.0 | 07-29 | 新增 #6 top按钮 #7 AI称呼阿呆 #8 删除刷新重现 |
