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
> **文档版本：** v8.0 | **最后更新：** 2026-08-02

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

---

### #10 — endConversation 500（DeepSeek 空内容）+ 前端结束失败无法重试（M3/M4）

**现象：** 点 [end] 结束对话 → `POST /api/v1/conversations/end` 返回 500（DeepSeek 返回空内容）。且卡片关闭后无法再次触发总结——重开卡片再点 end，总结永远生成不了。前端控制台伴随 `Another exception was thrown: Instance of 'minified:hf<void>'`（release 构建兜底打印，无实质信息）。

**根因（本质类型：实现遗漏 + 逻辑设计）：**
1. **后端无降级**：`ConversationController.endConversation` 直接调 `aiClient.understand()`，DeepSeek 返回空内容即抛 `RuntimeException` → 500。且 `summary` 为 null 时 `substring` 会 NPE（第二个 500 隐患）。— **实现遗漏**
2. **前端无法重试**：`main_page.dart._closeChat` 用 `hasNewTurns`（会话内新增轮数）决定是否调 end 接口。end 失败后重开卡片，`_chatEnterTurnCount` 被重置为当前 turns 数 → 再点 end 被短路为"无新对话"，只关闭不调接口，总结永远生成不了。— **逻辑设计**

**修法：**
1. 后端 `ConversationController.java`：AI 调用 try-catch 降级——失败用对话原文兜底 summary（`fallbackSummary` 截断 50 字）、tags 空、card 仍标记 `ended`、记忆降级沉淀（`Memory.fromContentFallback` 标 DEGRADED，AI 恢复后由重补升级）。summary null 也走兜底。end 永不 500。
2. 前端 `main_page.dart`：`_closeChat` 加 `needsSummary = card.summary == null && turns 非空`——有对话但从未总结成功 → 点 end 一定调接口，不再被 `hasNewTurns` 短路。
3. 测试：`ConversationControllerTest` 新增 `endConversation_aiFailure_degradesToOriginalText`（AI 抛异常 → 200 + 原文兜底 + card 标记 ended）。

**涉及文件：** `ConversationController.java`、`main_page.dart`、`ConversationControllerTest.java`

**数据处理：** 失败时数据未丢——card 文件 6 轮对话完好（`status: active`），总结失败仅 card 未标 ended、无 summary record。修复后刷新页面重开卡片点 [end] 即可恢复。

**状态：** ✅ 已修（前后端已重启生效）

---

### #11 — 对话内容刷新后显示"减少"（M3/M4）

**现象：** 首次聊天 active chat 视图显示全部对话；刷新后通过 ask 进入 chat 模式，发现对话内容明显减少/不对劲。

**根因排查：** 数据完整——`data/records/cards/2026/08/02/card_1785637891768.md` 6 轮对话齐全，后端 `GET /api/v1/feed` 返回 card turns 也是全量 6 条。问题在**前端显示**：
1. `feed_card.dart:593` `_truncateTurns`：对话 >4 轮且内容 >200 字符时折叠，只显示**第一轮 + 倒数第二轮 + 最后一轮**（3 条）+ 底部渐隐遮罩 + "展开"按钮。6 轮对话折叠后看起来"大减"。
2. 折叠判定以内容总长度为准，折叠态需用户点"展开"才显示全部。

**根因（已确认，本质类型：实现遗漏）：** 与 #13 同源——AI 的 QUESTION/CHAT 回复是**自然语言 + JSON 混合**（LlmResponseParser 靠 `extractJson` 从混合中提取字段）。实时对话时前端 `_doAskRequest` 用 `resp.rawResponse` 显示 AI 气泡 → 用户看到"自然语言 + JSON"；刷新后 card 从文件 `parseTurns` 解析，AI turn 只保留 `AI：` 后第一行自然语言，JSON 被忽略 → 对话内容"减少"。另有次要因素：`feed_card.dart:593` `_truncateTurns` 折叠（>4 轮且 >200 字符只显示 3 条 + 渐隐，可点展开）。

**修法：** 后端写 card 与返回给前端时剥离 JSON（`LlmResponseParser` 提取自然语言部分），实时显示与刷新后一致。

**状态：** 📋 待修复（方案已定，等 #13 一起改）

---

### #12 — 切换背面主页（LauncherPage）大量异常 + CanvasKit 崩溃（M-Launcher）

**现象：** 切换背面主页（MainPage ↔ LauncherPage 的 AnimatedSwitcher 250ms 动画）期间控制台刷 100+ `Another exception was thrown: Instance of 'minified:hf<void>'`，堆栈为 AnimationController/Ticker/framework 每帧 build 重复；最终 `core_patch.dart:293 Uncaught Error at canvaskit.wasm:0x30185 at Picture._cullRect`——CanvasKit 渲染层崩溃。

**根因（疑似，本质类型：展示问题）：** `launcher_page.dart` 用大量 **emoji 做行图标**（👤 🧠 📅 📊 📋 📈 🏷️ 等，`_buildRow('📋', '任务', ...)`）。Flutter Web + CanvasKit 渲染 emoji 需要 emoji 彩色字体（NotoColorEmoji），仓库未提供（serve_web.sh 注释明确"emoji 走系统 fallback"），CanvasKit WASM 不认系统字体 → 渲染 emoji 的 Picture 时崩溃（`Picture._cullRect`）。切换动画触发 launcher 重建 → 每帧崩一次 → 100+ 异常。与既知 N4（Web 端部分 emoji 不显示）同源。

**修法（候选）：** launcher_page 的 emoji 行图标全部换成 Material Icons（`_buildRow(String emoji)` → `_buildRow(IconData icon)`，`Text(emoji)` → `Icon(icon)`），顺带解决 N4。

**修法（已实施）：** `launcher_page.dart` — 7 处行图标 + 标签宇宙 🏷️ 全部换 Material Icons（👤→person_outline / 🧠→psychology_outlined / 📅→calendar_today_outlined / 📊→query_stats / 📋→task_alt / 📈→show_chart / 🏷️→tag），`_buildRow` 签名改 `IconData icon`，`Icon` 用 accentColor 着色。遗留 `☰/✦` 为单色文本符号（非彩色 emoji）暂保留。

**状态：** ✅ 已修（2026-08-02，前端需重建生效）

---

### #13 — card 文件混入 AI 原始 JSON 块（数据卫生，M3）

**现象：** `card_1785637891768.md` 对话正文末尾混入一段游离 JSON：`{"summary":"星期天天气查询","tags":[...],"sentiment":...,"domain":...,"actionable":...}`（AI 的原始 JSON 回复），不属于任何 `## 时间 + 用户：/AI：` turn，也无 `## ` 前缀。

**影响：** `CardFileRepository.parseTurns` 只认 `## + 用户：/AI：` 前缀行，JSON 块**不会被解析成 turn**（不影响 turns 读取），但污染文件格式，且说明某个写入路径把 AI 原始响应直接 append 进 card 文件。

**根因（已确认，本质类型：实现遗漏）：** `QuestionAppService.answer` 第 105 行 `String aiText = understanding.rawResponse()` — 把 AI 完整原始回复（自然语言 + 末尾 JSON）作为 AI turn 写入 card 文件。`CardFileRepository.format` 直接 `AI：` + turn.text() 落盘 → JSON 结构混进文件。`parseTurns` 只认 `AI：` 第一行，JSON 被忽略（不影响 turns 读取，但污染文件 + 与实时显示不一致）。

**修法：** 写 card 前用 `LlmResponseParser` 提取自然语言部分（剥离 JSON），`rawResponse` 为空时回退 `summary`。

**状态：** 📋 待修复



## 文档版本记录

| 版本 | 日期 | 改动 |
|:-----|:-----|:------|
| v8.0 | 08-02 | 新增 #11 对话折叠显示减少 + #12 切换 LauncherPage CanvasKit 崩溃（emoji）+ #13 card 混入 AI 原始 JSON |
| v7.0 | 08-02 | 新增 #10 endConversation 500（AI 空内容无降级）+ 前端结束失败无法重试 |
| v1.0 | 07-28 | 初始问题跟踪 |
| v2.0 | 07-28 | 两轮修复 + 所有问题 P0/P1 解决 |
| v3.0 | 07-29 | 本质分类 + prompt 冲突发现 |
| v4.0 | 07-29 | 本轮 4 个遗留全修 + 9 项新改动，状态交你判定 |
| v6.0 | 07-29 | 新增 #9 刷新后最新记录丢失 |
| v5.0 | 07-29 | 新增 #6 top按钮 #7 AI称呼阿呆 #8 删除刷新重现 |
