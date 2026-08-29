# 功能参考文档（Feature Reference）

> **定位：** AdaiOS 功能完整参考。按前端模块划分，每个模块覆盖功能、API、前端实现、后端处理、AI 提示词。
> **用途：** 问题定位、新功能开发、重构时的基准对照。
>
> **文档版本：** v1.5 | **最后更新：** 2026-08-25（RFC 20260825：逐笔批次跟踪与行为纠偏）

---

## 目录

1. [主页 Feed 流](#1-主页-feed-流)
2. [记录提交流](#2-记录提交流)
3. [问答会话流](#3-问答会话流)
4. [FeedCard 卡片组件](#4-feedcard-卡片组件)
5. [简报模块](#5-简报模块)
6. [时间线模块](#6-时间线模块)
7. [记忆模块](#7-记忆模块)
8. [Launcher 导航模块](#8-launcher-导航模块)
9. [交易模块](#9-交易模块)
10. [项目管理模块](#10-项目管理模块)
11. [搜索模块](#11-搜索模块)
12. [身份资料模块](#12-身份资料模块)
13. [标签模块](#13-标签模块)
14. [定时补完服务（RecordRetryService）](#14-定时补完服务recordretryservice)
15. [多模态 / 多账号 / adai-admin](#15-多模态--多账号--adai-admin)
16. [Domain=插件模型（RFC 20260814）](#16-domain插件模型rfc-20260814)

---

## 1. 主页 Feed 流

### 功能描述

- 展示**今日** Feed 卡片列表（记录 + 卡片），分页加载，每页 5 条
- 单独展示今日摘要（Brief 卡片）
- 支持 ↑ top / ↓ latest / "load more" 滚动控制
- 支持标签过滤（从 Launcher 传递）
- 支持双指滑动切换到 World B
- 整体入场动画 600ms
- **历史数据不走 Feed**，通过 TopBar 日期 → 时间线弹窗查看

### 前端文件

| 文件 | 类/方法 | 职责 |
|:-----|:---------|:------|
| `main.dart` | `DualWorldShell` | World A (MainPage) / World B (Launcher) 双指切换 |
| `main_page.dart` | `_MainPageState` | 核心状态管理 |
| `main_page.dart` | `_TopBar` | 日期 + 时间线入口 + 个人资料入口 |

### 对应 API

| API | 前端方法 | 说明 |
|:----|:---------|:------|
| `GET /api/v1/feed?page=0&size=5` | `_loadFeed()` | 加载 Feed 流（分页） |
| `GET /api/v1/brief` | `_loadFeed()` | 独立获取今日摘要 |

### API 请求/响应

**请求：** `GET /api/v1/feed?date=2026-07-29&page=0&size=5`

**响应：**
```json
{
  "entries": [
    {
      "type": "record" / "card" / "ai_note" / "push",
      "id": "rec_...",
      "time": "14:30",
      "title": "标题",
      "content": "内容",
      "tags": ["标签"],
      "intent": "log" / "question",
      "summary": "AI摘要",
      "turns": [{"isUser": true, "text": "...", "time": "14:30"}],
      "domain": "life" / "trading" / "project"
    }
  ],
  "totalToday": 28
}
```

### 前端逻辑

**数据加载：**
1. `_loadFeed()` → `ApiService.getBrief()`（摘要）+ `ApiService.getFeed(page=0)`（今日第 1 页）
2. 过滤掉 `type=ai_note` 的条目
3. 将 `FeedEntryResponse` 通过 `toFeedData()` 转为 `FeedCardData`
4. 简报（`_brief`）在 `_buildBriefCard()` 渲染

**下拉刷新（`RefreshIndicator`）：**
- `_refreshFeed()` 重置分页页码，重新拉取第 1 页
- 在 Feed 列表顶端（最新消息区域）下拉触发

**分页加载：**
- 初始加载今日第 1 页（5 条）
- 滚到顶部出现 "load more" → 调 `ApiService.getFeed(page=N)` → 追加到列表
- 当 `_cards.length >= _totalToday` 时，不再显示 "load more"
- 历史数据不通过 Feed 加载

**标签过滤：**
- `filterTag` 从 `DualWorldShell` 传入
- 只显示包含该 tag 的卡片
- 过滤激活时顶部显示标签 pill + 清除按钮

**UI 状态：**
| 状态 | 显示 |
|:-----|:------|
| 加载中 | `CircularProgressIndicator` 居中 |
| 空状态 | "还没有记录" + 快捷输入提示 |
| 数据 | 反向 ListView |
| 更多加载 | "load more" / "loading..." |
| 滚动控制 | "↑ top" / "↓ latest" / "just now" |

### 后端处理

**FeedController.getFeed(date, page, size) → FeedAppService.getFeed(date, page, size)**

合并流程：
1. 加载当天 ContentRecord（`RecordRepository`，按日期过滤）
2. 加载当天 CardRecord（`CardFileRepository.findTodayCards()`）
3. 加载当天 Memory/AI 理解（`MemoryService.findByDate()`）
4. 去重：跳过内容与卡片轮次匹配的记录
5. 按时间排序
6. 从排序后列表截取尾部（page 0 = 最新条目，page N = 更早条目）
7. 返回 `{ entries: [...], totalToday: N }`

### AI 提示词

Feed 流本身**不调用 AI**。简报的 AI 调用见 [简报模块](#5-简报模块)。

### 主动推送（Layer 2，RFC 20260816）

| 推送 | 触发 | 内容 | 渠道 |
|:-----|:-----|:-----|:-----|
| **真止损异动** | 现价跌破用户预设止损位（R66 硬判定，G-3 引擎口径，当日去重）| 「京东方现价 4.8 已跌破你的止损位 4.9——按纪律（R66）该清仓了，要我给出建议吗？」| PushChannel 插件化：Feed（默认）+ iOS 原生推送（Bark，未配置 key 自动跳过）|
| **早盘计划** | 工作日 9:15（cron 可配）| 持仓总览 + 各票止损/买点 + 择时关注（读 `current.md`）| 同上 |
| **午间跟踪** | 工作日 12:00 | 上午涨跌 + 是否触发止损 + 计划更新 | 同上 |
| **尾盘建议** | 工作日 14:50 | 逐票建议（R66 止损 / R81 仓位）+ 明日关注 + 复盘提醒 | 同上 |
| loss/gain/break-cost | 交易时段轮询（可配间隔）| 单日跌幅/涨幅/破成本线 | 同上 |

- **内容两阶段**：阶段二 LLM 自然语言生成（阿呆口吻，无第三视角），LLM 失败降级模板（阶段一）
- **外部渠道**：`adai.push.bark.key`（env `ADAI_PUSH_BARK_KEY`）——iOS 原生推送（Bark，免费无限条数），未配置自动禁用，Feed 不受影响；可选 `adai.push.bark.base-url`（env `ADAI_PUSH_BARK_BASE_URL`，默认公共服务器 `https://api.day.app`，支持自托管）
- **微信渠道已停用**（2026-08-25）：Server酱免费版每天 5 条额度不够 AdaiOS 推送量，生产 `ADAI_PUSH_WECHAT_SENDKEY` 已删除；`WeChatPushChannel` 代码保留（未配置即禁用），如恢复需配置 key
- **渠道插件化**：`PushChannel` 接口（kernel/push）+ `FeedPushChannel`/`BarkPushChannel`（infrastructure/push），新增渠道不动主流程

---

## 2. 记录提交流

### 功能描述

- 用户输入文本 → 自动识别意图（log / question）
- 陈述句（log）→ 保存记录 + AI 总结 + 记忆沉淀
- 疑问句（question）→ 激活对话模式
- 支持指定 intent（`log` / `question`）
- 支持 `cardId` 续接已有对话

### 前端文件

| 文件 | 类/方法 | 职责 |
|:-----|:---------|:------|
| `widgets/input_bar.dart` | `InputBar` / `InputBarState` | 输入控件 |
| `main_page.dart` | `_createNewCard()` | 创建新记录卡片 |
| `main_page.dart` | `_onSend()` | 输入发送入口 |
| `services/api_service.dart` | `createRecord()` | HTTP 调用 |

### 对应 API

| API | 前端方法 | 说明 |
|:----|:---------|:------|
| `POST /api/v1/records` | `createRecord(content, intent, cardId)` | 统一入口 |
| `DELETE /api/v1/records/{id}` | `deleteRecord(id)` | 删除记录或卡片（`card_` 前缀走卡片存储） |
| `POST /api/v1/records/retry` | — | 手动触发 RecordRetryService 补完 |

### API 请求/响应

**请求：**
```json
{
  "content": "今天买了立昂微",
  "intent": null,
  "cardId": null
}
```

**响应（log）：**
```json
{
  "intent": "log",
  "recordId": "rec_...",
  "summary": "建仓了半导体",
  "tags": ["投资", "半导体"],
  "domain": "trading"
}
```

**响应（question）：**
```json
{
  "intent": "question",
  "recordId": "rec_...",
  "summary": "3-5词简短概括",
  "tags": ["日常"],
  "rawResponse": "AI 的完整回复文本（含末尾 JSON）",
  "domain": "life"
}
```

> **注意：** `summary` 是 3-5 词简短标签，`rawResponse` 是 AI 完整回复。前端对话轮次显示 `rawResponse`（优先），兜底 `summary`。

### 前端逻辑

**`_onSend(text)` 入口：**
1. 如果 `_activeCardId != null` → `_appendToActiveCard()`（续接对话）
2. 否则 → `_createNewCard()`（新记录）

**`_createNewCard()` 流程：**
1. 生成临时 `cardId`（`card_{timestamp}`）
2. 创建 `FeedCardData`（`mode: idle`, `loading: true`）
3. 调 `POST /api/v1/records`（intent 为 null 由后端 AI 判断）
4. 响应处理：
   - `intent=question` → 激活对话模式（`activeCard`, `mode: chatting`, `turns`）
   - `intent=log` → 更新卡片（`summary`, `tags`, `domain`, `mode: idle`）
5. 失败 → 卡片保留原内容进入**错误态**（底部橙色错误提示 + `[重试]` 按钮）
   - 点重试 → 删除旧卡片 → 用同样内容重发
   - 用户不需要重新输入文字

### 后端处理

**RecordController.createRecord() → 分流逻辑**

```
POST /api/v1/records
  ├── cardId != null → handleQuestion()     // 已有卡片，续接对话
  ├── intent = "question" → handleQuestion()
  ├── intent = "log" → handleStatem()
  └── intent = null → IntentRecognizer.recognize()
       ├── "ask" → handleQuestion()
       └── "log" → handleStatem()
```

**handleStatem()（陈述句）：**
1. 保存 `ContentRecord` 到文件
2. `ContextEngine.compose()` 组装上下文
3. `AiClient.understand(ContextPackage)` → `AiUnderstanding`
4. 用 AI 返回的 tags/summary/domain 更新 `ContentRecord`
5. `MemoryService.persist()` 保存记忆（含 insight / patterns / preferences）

**handleQuestion()（疑问句）：**
1. 新卡片 → 创建 `CardRecord`；已有卡片 → 追加轮次
2. 保存 `ContentRecord`（携带 cardId）
3. `ContextEngine.compose()`（含对话历史）
4. `AiClient.understand(ContextPackage)` → `AiUnderstanding`
5. AI 回复追加到卡片轮次
6. `MemoryService.persist()` 保存记忆

**IntentRecognizer.recognize()（AI 意图识别）：**
- 调 `AiClient.recognizeIntent(content)` 返回 `"ask"` 或 `"log"`
- 失败直接抛异常（不降级）

### AI 提示词

#### 意图识别 prompt（`DeepSeekAiClient.recognizeIntent`）

```
判断以下用户输入是否需要 AI 回复。
需要回复（提问、命令、要求等）→ 返回 ask
不需要回复（纯记录、日记、随想）→ 返回 log
只需返回一个词：ask 或 log。

输入：{content}
结果：
```

- `max_tokens: 50`, `temperature: 0.3`, 超时 15 秒

#### 陈述句分析 prompt（`ContextEngine.buildPrompt()` → STATEMENT 场景）

完整上下文组装后，输出指令为：

```
请分析这条记录，输出 JSON 格式（不要包裹 markdown 代码块）：
{
  "summary": "3-5个词客观概括，不要人称代词（不用你/我/用户），像标签一样简洁",
  "insight": "一句话客观理解，不要复述原文，避免人称代词",
  "patterns": "（可选）如果这条记录揭示了用户的长期行为模式，输出数组，每项包含 content(模式描述) 和 confidence(0-1置信度)；否则不输出此字段",
  "preferences": "（可选）如果这条记录揭示了用户的明确偏好，输出数组，每项包含 content(偏好描述) 和 confidence(0-1置信度)；否则不输出此字段",
  "tags": ["标签1", "标签2", "标签3"],
  "sentiment": "positive 或 negative 或 neutral",
  "domain": "life(生活)/trading(交易)/project(项目)",
  "actionable": true 或 false,
  "actionSuggestion": "如果需要后续操作，写建议；否则写 null"
}
```

**domain 判定优先级（AI 输出规则）：**
- 指标、K线、持仓、走势、复盘、买入、卖出、仓位 → `trading`
- 任务、进度、bug、需求、RFC、项目、待办、计划 → `project`
- 日常、想法、记录、心情、问题 → `life`

**模型参数：** `temperature: 0.3`, `max_tokens: 1024`, 分析模式

#### 疑问句/对话 prompt（`DeepSeekAiClient` 聊天模式）

System prompt（CHAT 模式，`DeepSeekAiClient.java`）：
```
你是阿呆的个人 AI 助手。用中文回复，语气温暖。
回复结束后在末尾另起一行输出 JSON（不要包裹 markdown 代码块）：
{
  "summary": "3-5个词概括本次问答主题，避免人称代词，像标签一样简洁",
  "tags": ["标签1", "标签2"],
  "sentiment": "positive 或 negative 或 neutral",
  "domain": "life(生活)/trading(交易)/project(项目)",
  "actionable": true 或 false,
  "actionSuggestion": "需要后续操作写建议，否则写 null"
}
不要使用 emoji 和 unicode 转义码。
```

> **注意：** 前端 `_stripDomainJson()` / `_removeTrailingJson()` 会自动剥离回复末尾的 JSON 块（兼容单行 `{"domain":"..."}` 和多行完整 JSON 两种格式）。

背景知识（单独 system 消息）：相关记录 + 记忆摘要（不含用户称呼）

对话历史：user/assistant 对

**模型参数：** `temperature: 0.7`, `max_tokens: 4096`, 聊天模式

---

## 3. 问答会话流

### 功能描述

- 记录卡片点击 "ask" → 进入对话模式（waiting → chatting）
- 多轮续问（每轮走 POST /api/v1/records + cardId）
- 点击 "end conversation" → 总结对话 → 回到列表
- 已结束卡片可再次 ask → 继续对话

### 前端文件

| 文件 | 类/方法 | 职责 |
|:-----|:---------|:------|
| `main_page.dart` | `_onAskCard()` | Ask 入口 |
| `main_page.dart` | `_doAskRequest()` | 发第一条 ask 消息 |
| `main_page.dart` | `_appendToActiveCard()` | 续接对话 |
| `main_page.dart` | `_closeChat()` | 结束对话 |
| `main_page.dart` | `_buildActiveLayout()` | 全屏对话视图 |

### 对应 API

| API | 前端方法 | 说明 |
|:----|:---------|:------|
| `POST /api/v1/records` | `createRecord(content, intent:"question", cardId)` | 发问/续接 |
| `POST /api/v1/conversations/end` | `endConversation(turns, cardId)` | 结束对话 |

### 前端逻辑

**Ask 流程（_onAskCard）：**
1. 卡片已有 turns → 直接激活对话（`activeCardId`, `mode: chatting`）
2. 无 turns → `_doAskRequest()`：
   - 设置 `mode: waiting`, `loading: true`
   - `POST /api/v1/records`（`intent: "question"`, `cardId: card.id`）
   - 返回后 → `mode: chatting`, `turns: [用户内容, AI 回复]`
   - 保存 `resp.tags`, `resp.domain`

**续问流程（_appendToActiveCard）：**
1. 追加用户轮次到 `turns`
2. `POST /api/v1/records`（`cardId: _activeCardId`）
3. AI 回复追加到 `turns`

**结束流程（_closeChat）：**
1. 检测是否有新轮次（`currentTurns > _chatEnterTurnCount`）
2. 无新轮次 → 直接关闭（`activeCardId = null`）
3. 有新轮次：
   - 关闭视图，卡片显示 `loading: true`
   - `POST /api/v1/conversations/end`（传全部 turns 文本列表）
   - 返回后更新卡片：`summary`, `tags`, `loading: false`, `mode: ended`
   - ended 态显示绿色边框 + summary banner + tags + `── ask ──`

**关键状态变量：**
| 变量 | 用途 |
|:-----|:------|
| `_activeCardId` | 当前活跃对话的卡片 ID |
| `_hasActiveChat` | 是否在对话模式 |
| `_chatEnterTurnCount` | 进入对话时的轮次数（用于检测新增轮次） |

### 后端处理

**ConversationController.endConversation()：**
1. 构建 AI 总结 prompt（含所有 turns）
2. 调 `aiClient.understand()` → 返回总结
3. 保存总结为 ContentRecord（无 domain，自动判定）
4. cardId 存在时 → 更新卡片状态为 "ended" + 记录摘要
5. `MemoryService.persist()` 沉淀记忆
6. 返回 `{recordId, summary, tags}`

### AI 提示词

#### 结束对话 prompt（ConversationController）

```
客观总结这段对话（不超过40字），避免人称代词。
输出 JSON（不要包裹 markdown 代码块）：
{
  "summary": "对话总结",
  "tags": ["标签1", "标签2"],
  "sentiment": "neutral",
  "actionable": false,
  "actionSuggestion": null
}

对话内容：
我：{turn1}
你：{turn2}
我：{turn3}
...
```

---

## 4. FeedCard 卡片组件

### 功能描述

- 5 态状态机：idle / waiting / chatting / ended / **error**
- 折叠显示（对话轮次超过 200 字符时折叠）
- 标签 pills 显示
- Domain 徽章（📝 生活 / 📈 交易 / 📑 项目）
- 更多菜单（标记为、删除）
- Markdown 渲染 AI 回复
- 加载动画（`_LoadingDots`）
- 错误态：底部橙色错误信息 + `[重试]` 按钮

### 前端文件

| 文件 | 类/方法 | 职责 |
|:-----|:---------|:------|
| `widgets/feed_card.dart` | `FeedCard` | 卡片 widget |
| `widgets/feed_card.dart` | `FeedCardData` | 数据模型（17 字段） |
| `widgets/feed_card.dart` | `CardMode` 枚举 | idle / waiting / chatting / ended |
| `widgets/feed_card.dart` | `IntentType` 枚举 | log / question |

### 对应 API

不直接。交互通过 `main_page.dart` 回调触发 API 调用。

### FeedCardData 模型

| 字段 | 类型 | 说明 |
|:-----|:-----|:------|
| `id` | String | 卡片 ID |
| `type` | FeedCardType | record / aiNote / push / dateSeparator |
| `time` | String | HH:mm |
| `content` | String | 原始内容 |
| `tags` | List\<String\>? | 标签列表 |
| `summary` | String? | AI 摘要 |
| `turns` | List\<ConversationTurn\>? | 对话轮次 |
| `mode` | CardMode | idle / waiting / chatting / ended |
| `loading` | bool | 加载中 |
| `intent` | IntentType | log / question |
| `expanded` | bool | 折叠展开 |
| `domain` | String | life / trading / project |
| `error` | String? | API 调用失败时的错误信息（非 null 进入错误态） |
| `updatedAt` | DateTime | 更新时间 |

### 前端逻辑

**状态判定：**
```
_buildCardContent():
  ├── dateSeparator → 纯文本日期标签
  └── record/aiNote/push:
       ├── mode == chatting → 对话气泡布局
       ├── mode == ended → 绿色边框 + 总结 + 标签 + "ask" 按钮
       ├── loading == true → 域徽章位置显示 LoadingDots
       └── idle:
            ├── intent == question + turns → 聊天风格（摘要 + "ask"）
            └── log → 普通记录卡片 + "── ask ──"
```

**折叠逻辑：**
- `turns` 总字符 > 200 → 折叠，显示前 1 条 + 后 2 条 + "展开全部"
- 折叠时 `ConstrainedBox(250px)` + `ClipRect` + 渐隐
- 点"展开全部" → `expanded = true`

**AI 回复清理（`_removeTrailingJson` + `decodeUnicodeEscapes`）：**
- 去掉 AI 回复末尾的 JSON 残留（兼容 `{"domain":"..."}` 旧格式和多行完整 JSON 新格式）
- 解码 `\uXXXX` 转义序列，正确处理代理对（surrogate pair），如 `🌿` → 🌿

### 后端处理

FeedCard 是纯前端组件，后端不直接参与。后端卡片状态通过 `CardFileRepository` 维护。

---

## 5. 简报模块

### 功能描述

- 每日问候语 + 智能摘要
- 包含：当日记录回顾、交易活动提醒、领域活跃度
- 显示在 Feed 顶部（`_buildBriefCard`）
- 5 分钟缓存

### 前端文件

| 文件 | 类/方法 | 职责 |
|:-----|:---------|:------|
| `main_page.dart` | `_buildBriefCard()` | 简报 UI 渲染 |
| `services/api_service.dart` | `getBrief()` | 独立获取今日摘要 |

### 对应 API

| API | 前端方法 | 说明 |
|:----|:---------|:------|
| `GET /api/v1/brief` | `getBrief()` | 独立接口，不含在 Feed 中 |

前端 `_loadFeed()` 并行调用 `getBrief()` + `getFeed(page=0)`，简报单独渲染。

### 前端逻辑

- `_buildBriefCard()`：取 `_brief` 字符串，按行分割，首行大字显示，后续行加 `•` 前缀
- Feed 加载为空时简报不显示

### 后端处理

**BriefController → BriefAppService.generateBrief()**

流程：
1. 检查 5 分钟缓存 → 命中直接返回
2. `buildBriefPrompt()` 组装提示词
3. 调 `AiClient.understand()` → AI 回复
4. 缓存结果 5 分钟

### AI 提示词

#### 简报 prompt（`BriefAppService.buildBriefPrompt()`，英文指令）

```
You are a personal AI assistant. Generate a warm, concise greeting.

Date: {date} {weekday}
User: {name}

Recent records:
{records (标注 today/yesterday)}

AI Understanding:
{memories}

{ifep: no today records → "Keep it simple."}

{Pattern/habit injection prompt}

Trading activity: {hasActivity → "提醒生成复盘"}

Domain activity (7-day):
- life: {count}条 {trend}
- trading: {count}条 {trend}
- project: {count}条 {trend}

Hot tags: {tags (3天内使用)}
Cold tags: {tags (14天未用)}

Strict format:
1. First line: {name} {greeting}!
2. Each line starts with a relevant emoji
3. Chinese, max 3 lines
4. Each line max 30 chars
5. No JSON output, plain text only
```

**模型参数：** `temperature: 0.7`, 纯文本模式（非 JSON）

---

## 6. 时间线模块

### 功能描述

- 两种入口：TopBar 下拉底部弹窗（`TimelineModal`）、Launcher 导航（`TimelinePage`）
- 月视图日历 + 选定日期条目列表
- 日历上日期带绿色圆点标记（有条目）

### 前端文件

| 文件 | 类/方法 | 职责 |
|:-----|:---------|:------|
| `widgets/timeline_modal.dart` | `TimelineModal` | 底部弹窗日历 |
| `pages/timeline_page.dart` | `TimelinePage` | 全页时间线 |

### 对应 API

| API | 前端方法 | 说明 |
|:----|:---------|:------|
| `GET /api/v1/timeline?type=&limit=` | `getTimeline()` | 拉取全量，客户端过滤当月 |

### API 响应

```json
[
  {
    "id": "rec_...",
    "type": "note",
    "title": "今天买了立昂微",
    "tags": ["投资", "半导体"],
    "dateTime": "2026-07-18T14:30:00"
  }
]
```

### 前端逻辑

- 客户端缓存 `_timelineCache`（页面切换不丢）
- TimelineModal：拉取全量数据 → 客户端过滤选定月份 → 渲染日历格子
- TimelinePage：拉取全量数据 → 逐月导航 → 选择日显示条目
- **空状态：** "这天没有记录"

### 后端处理

**TimelineController → TimelineAppService → TimelineProjection**

无 AI 调用。直接从 RecordRepository 读取所有 ContentRecord 投影为 TimelineEntry。

---

## 7. 记忆模块

### 功能描述

- 按日查看 AI 对记录的理解（Memory）
- 标签过滤（水平滚动标签栏，取前 8 个标签 + "全部"）
- 情绪图标标记（正面 / 负面 / 中性）
- 打开后自动跳转到最近有数据的日期

### 前端文件

| 文件 | 类/方法 | 职责 |
|:-----|:---------|:------|
| `pages/memory_page.dart` | `MemoryPage` | 记忆页面 |

### 对应 API

| API | 前端方法 | 说明 |
|:----|:---------|:------|
| `GET /api/v1/memory/dates` | `getMemoryDates()` | 有记忆的日期列表 |
| `GET /api/v1/memory?date=` | `getMemory(date:)` | 按日获取记忆条目 |

### API 响应

```json
{
  "id": "mem_20260718_143000",
  "recordId": "rec_20260718_143000",
  "summary": "AI摘要/insight",
  "tags": ["标签"],
  "sentiment": "positive",
  "createdAt": "2026-07-18T14:30:00"
}
```

### 前端逻辑

1. `initState` → `getMemoryDates()` → 取最近日期 → `getMemory(date:)`
2. 水平标签栏：取所有记忆的 tags 统计，top 8
3. 点标签 → 过滤显示
4. 情绪映射：positive → 😊 绿色, negative → 😟 橙色, neutral → 😐 灰色
5. **空状态：** "今天没有记忆" / "没有匹配 "{tag}" 的结果"

### 后端处理

**MemoryController → MemoryService**

- 文件存储：`data/memory/YYYY/MM.md`
- 每个条目用 `--- frontmatter --- body` 分隔
- Frontmatter 含：id, recordId, tags, sentiment, actionable, patterns(JSON), preferences(JSON), createdAt

---

## 8. Launcher 导航模块

### 功能描述

- World B — 应用导航中心
- 搜索栏入口
- 5 个导航入口（关于我、脑瓜子、时间线、阿呆系统、交易）
- 标签宇宙（图谱视图 / 列表视图切换）
- 统计数据：标签总数、记录总数、记忆总数

### 前端文件

| 文件 | 类/方法 | 职责 |
|:-----|:---------|:------|
| `pages/launcher_page.dart` | `LauncherPage` / `_LauncherPageState` | World B 页面 |

### 对应 API

| API | 前端方法 | 说明 |
|:----|:---------|:------|
| `GET /api/v1/identity` | `getIdentity()` | 获取用户名称 + 偏好 |
| `GET /api/v1/tags` | `getTags()` | 标签统计 |
| `GET /api/v1/timeline?limit=999` | `getTimeline(limit:999)` | 记录总数 |
| `GET /api/v1/memory/count` | `getMemoryCount()` | 记忆总数 |

### 前端逻辑

- `_loadAll()` 四请求并发（`Future.wait`）
- 图谱视图：`CustomPainter` + `_GraphLinePainter` 画连线，标签按频率大小分布
- 列表视图：`Wrap` 布局，标签按频率缩放
- 导航：全部 `Navigator.push` → `Scaffold(backgroundColor: darkBg, body: Page)`
- 标签点击 → `SearchPage(initialQuery: tag)`

---

## 9. 交易模块

### 功能描述

- 持仓列表（表格：代码、名称、数量、成本、现价、盈亏——现价实时行情注入，2026-08-16）
- 投资组合快照（总市值、总盈亏、现金余额、持仓数）
- 交易录入表单（代码、名称、方向、价格、数量、止损位、买点、目标价、原因；**输入 6 位代码自动带出名称**）
- 建议引擎（`POST /trading/advice`，R66 止损 / R81 仓位硬判定）
- 批量导入（通达信导出自动识别：持仓快照 / **历史成交** / 交易 CSV 三格式；选择文件上传留存或粘贴）
- 主动推送（真止损异动 / 早盘计划 / 午间跟踪 / 尾盘建议 → PushChannel Feed+微信，见 §1）
- **推送体验（RFC 20260817）**：推送卡专属样式（类型徽章：早盘蓝/午间紫/尾盘橙/买点绿/预警红）+ 结构化内容（总结+持仓逐行+建议）；**推送开关**（per-user 8 类型：时段/买点/止损/接近止损/大跌/放飞/破成本/行情条，`data/{userId}/trading/push-settings.json`，写读双侧门控）；app 左滑删单条/右滑进设置，web 交易页设置入口
  > ⚠️ 2026-08-23 标注：徽章配色受 **P1-推送1（标题契约断裂）** 阻断——后端 `FeedPushChannel` 落库丢标题 → 前端按标题 switch 全落灰「行情」，修复前展示与上文不符
- **图片对话流（RFC 20260817）**：图片对话卡图置顶、turns 跟随滚动——聊天态与刷新态渲染一致（不再退化为固定附件）
- **交易日志自动归集（RFC 20260817）**：成交截图（VLM 识别）/文字（「清仓了XX」宽松解析）→ 当日候选去重（symbol+方向）→ 收盘 15:15 推送「今日操作汇总」→ 用户「确认并入账」→ recordTrade 链路落库；仅 trading 插件用户触发；不完整候选（无数量/价格）确认跳过引导补全；`data/{userId}/trading/trade-log/{yyyy-MM-dd}.json`
- **历史成交 Tab（RFC 20260823）**：web 交易页常驻第 5 Tab（取代页头交易历史 Dialog）——日期范围查询 + 按日分组流水（方向/时间/代码/名称/数量/价格/成交金额/发生金额/成交编号 + 系统计算的费用放最后——2026-08-25 删止损/买点/原因三列）+ 独立导入入口（只认通达信历史成交导出）；导入幂等 + **缺失成交时间回填**（`updated` 计数）
- **逐笔批次跟踪与行为纠偏（RFC 20260825）**：持仓从「一只股票」细化到「每一笔买入」——批次 = 同标的+同方向+同日合并（一天最多一个买批，成本=当日加权含费），卖出 **LIFO** 先扣最近批次（底仓不动、先走短线），批次清仓 = 回合总账；**批次视图 `GET /trading/lots`**（注入现价 + 流水对账，web 持仓 Tab 批次弹窗 + app 持仓卡简版）；**导入双模式**（当日成交 → 同步持仓/现金/流水 + **每日操作总结**（买卖聚合 + 批次 diff + 行为标注六类：亏损加仓/追高/短线新开/破止损未走/浮盈回吐/短线超期）；历史 → 只补流水）；**批次级止损推送**（批次破自己的止损（未设默认 −7%）单独提醒，不跟底仓混）；**推送定时消失**（`expiresAt`：行情类次日 09:30、汇总类次日 23:59）

### 交易数据智能（RFC 20260816，2026-08-16 落地）

| 区块 | 数据 | 导入 | 展示 |
|:-----|:-----|:-----|:-----|
| **自选股** | `data/{userId}/trading/watchlist.json` | 通达信自选导出（代码/名称/细分行业/长期中期短期形态/近日指标提示）| 表格：代码/名称/行业/长中短形态/指标提示（金叉红）/删除 |
| **清仓股复盘** | `data/{userId}/trading/sold.json` | 通达信清仓导出（介入/清仓日期/持仓天数/买卖次数/持仓期涨幅%）| 表格：代码/名称/介入→清仓/天数/涨幅/心理标注（点击标注追高/恐慌等）|
| **资金股份查询** | 存账户快照 `account.json`（**现金唯一真源 = AccountSnapshot.cash，S5**——不再写 positions.md cashBalance）+ 精确成本（4 位）| 通达信「资金股份查询」导出 | 现金/总资产展示（R81 分母=总资产）|

### 前端文件

| 文件 | 类/方法 | 职责 |
|:-----|:---------|:------|
| `pages/trading_page.dart`（adai-web） | `TradingPage` | 交易管理端（持仓 + 自选 + 清仓 + 资金 + 历史成交 + **规则 六 Tab**——规则 Tab 2026-08-30 第三阶段：我的交易规则参数展示 + 编辑弹窗）|
| `pages/trading_page.dart`（adai-app） | `TradingPage` | 手机交易页（账户卡 + 记录双通道 + 持仓卡 + 阿呆建议弹层；**2026-08-22 移除自选/清仓只读区块**，管理归 web）|

### 对应 API

| API | 前端方法 | 说明 |
|:----|:---------|:------|
| `GET /api/v1/trading/positions` | `getPositions()` | 持仓列表（实时行情注入盈亏）|
| `GET /api/v1/trading/portfolio` | `getPortfolio()` | 组合快照 |
| `POST /api/v1/trading/trades` | `recordTrade()` | 录入交易 |
| `GET /api/v1/trading/lookup` | `lookupSymbol()` | 代码→名称（输入带出）|
| `POST /api/v1/trading/positions/import` | `importPositions()` | 持仓初始化导入（通达信快照）|
| `POST /api/v1/trading/imports/save` | `saveImportFile()` | 上传留存 + GBK 转码 |
| `GET/POST/DELETE /api/v1/trading/watchlist*` | `getWatchlist/importWatchlist/removeWatchlist` | 自选股 |
| `GET/POST /api/v1/trading/sold*` | `getSold/importSold/updateSoldPsychology` | 清仓股复盘 |
| `POST /api/v1/trading/imports/cash` | `importCash()` | 资金查询（现金+精确成本）|
| `GET /api/v1/trading/account` | `getAccount()` | 账户总体快照（总盈亏=资产-本金）|
| `GET /api/v1/trading/buy-points` | `getBuyPoints()` | 自选股买点信号（B1/B2）|
| `GET /api/v1/trading/sold/score` | `getSoldScore()` | 清仓复盘三维打分 |
| `POST /api/v1/trading/transfer` | `recordTransfer()` | 银证转账（净投入跟踪）|
| `GET /api/v1/trading/transfers` | `transferList()` | 转账流水 |
| `PUT /api/v1/trading/positions/{symbol}` | `updatePosition()` | 持仓元信息（止损/角色）|
| `GET /api/v1/trading/rules` | `getTradingRules()` | **规则参数（第三阶段 2026-08-30）**：用户自己的交易系统参数（仓位上限/止损/行为标注/买点/打分权重/硬约束），无规则 → 默认 |
| `PUT /api/v1/trading/rules` | `updateTradingRules()` | **规则参数更新**：部分字段覆盖，落 `data/{userId}/trading/rules.yaml` |

> **第三阶段（2026-08-30，trading-plugin-architecture.md）**：交易插件从 adai 专属演进为「通用能力 + 个性化规则」。规则参数按用户隔离，驱动止损/仓位/买点/行为标注/清仓 verdict/打分权重/建议硬约束/知识注入（`data/{userId}/trading/knowledge.md` 用户私有优先）。**web 交易页第 6 Tab「规则」**：参数中文标签展示 + 编辑弹窗（表单化 PUT）。无规则用户 → 全部默认 = adai 现状（降级不坏）。

### 前端逻辑

- **web**：致命请求（positions + portfolio + account）`Future.wait` + 可降级请求（watchlist/sold/buy-points/score）独立异步 + 代际守卫；切页/点记录交易自动刷新（2026-08-16；P1-交易7 致命/可降级分离）
- **app**：账户卡 + 记录双通道（一句话 → 确认卡 / 精确表单含隐藏式止损买点）+ 持仓卡 + 阿呆建议弹层；30 分钟定时刷新；**2026-08-22 移除自选/清仓只读区块**（管理归 web）
- 空状态：web "暂无持仓 / 暂无自选股 / 暂无清仓记录"；app "暂无持仓 + 引导去 web 导入"
- 错误状态：红色文字 "加载失败\n..."
- 录入交易后刷新持仓

### 后端处理

**TradingController → TradingAppService**

- 纯计算，无 AI
- 持仓数据从 `data/trading/positions.md` 读取（freeze §2.6）；自选/清仓 `watchlist.json`/`sold.json`
- 交易录入实时更新文件
- 通达信三格式解析：`TradingImportParser`（表头定位列，GBK 已转码）

---

## 10. 项目管理模块

### 10.1 项目状态

#### 功能描述

- 系统概览（项目名、架构模式）
- 内核组件状态网格（6 个：identity / record / timeline / context / memory / knowledge）
- Domain OS 状态（trading / life / project）
- 统计数据（commit 数、RFC 数、API 端点）
- RFC 状态列表

#### 前端文件

| 文件 | 类/方法 | 职责 |
|:-----|:---------|:------|
| `pages/project_status_page.dart` | `ProjectStatusPage` | 项目仪表盘 |

#### 对应 API

| API | 前端方法 | 说明 |
|:----|:---------|:------|
| `GET /api/v1/project/status` | `getProjectStatus()` | 项目状态 |
| `GET /api/v1/project/tasks/stats` | `getTaskStats()` | 任务统计 |

#### 后端处理

**ProjectStatusController → ProjectStatusAppService**

- 纯数据聚合，**不调用 AI**
- 从 git log、RFC frontmatter、文件系统统计聚合

### 10.2 任务管理

#### 功能描述

- 任务 CRUD（创建、编辑、状态流转、删除）
- 状态过滤（TODO / DOING / DONE / CANCELLED）
- 优先级（P0-P3）
- 标签、RFC 引用
- 统计行

#### 前端文件

| 文件 | 类/方法 | 职责 |
|:-----|:---------|:------|
| `pages/project_task_page.dart` | `ProjectTaskPage` | 任务管理页面 |

#### 对应 API

| API | 前端方法 | 说明 |
|:----|:---------|:------|
| `GET /api/v1/project/tasks?status=&tag=` | `getTasks()` | 任务列表 |
| `POST /api/v1/project/tasks` | `createTask()` | 创建 |
| `PUT /api/v1/project/tasks/{id}` | `updateTask()` | 更新 |
| `DELETE /api/v1/project/tasks/{id}` | `deleteTask()` | 删除 |
| `GET /api/v1/project/tasks/stats` | `getTaskStats()` | 统计 |

#### 后端处理

**ProjectStatusController → ProjectTaskAppService**

- File First 存储：`data/project/tasks/YYYY/MM.md`
- 无 AI 调用

---

## 11. 搜索模块

### 功能描述

- 全文搜索记录内容
- 搜索关键词高亮（绿色 + 粗体）
- 不区分大小写

### 前端文件

| 文件 | 类/方法 | 职责 |
|:-----|:---------|:------|
| `pages/search_page.dart` | `SearchPage` | 搜索页面 |

### 对应 API

| API | 前端方法 | 说明 |
|:----|:---------|:------|
| `GET /api/v1/search?q=` | `search(query)` | 全文搜索 |

### 前端逻辑

- `_search()` → 防抖 300ms → API 调用
- 搜索结果用 `RichText` + `TextSpan` 绿色高亮匹配部分
- **状态：** 未搜索 / 加载中 / 空结果 / 有结果

### 后端处理

**SearchController → SearchService**

- 线性扫描所有记录文件
- 对 title / content / tags / summary 做不区分大小写的子串匹配
- 无 AI

---

## 12. 身份资料模块

### 功能描述

- 查看/编辑个人资料
- 名称、语言、沟通风格、专注领域
- AI 协作规则（交易需确认、日常自动处理）
- 关注标签管理

### 前端文件

| 文件 | 类/方法 | 职责 |
|:-----|:---------|:------|
| `pages/profile_page.dart` | `ProfilePage` | 身份资料页面 |
| `services/models/identity_models.dart` | `IdentityResponse` / `IdentityRequest` | DTO |

### 对应 API

| API | 前端方法 | 说明 |
|:----|:---------|:------|
| `GET /api/v1/identity` | `getIdentity()` | 读取 |
| `PUT /api/v1/identity` | `updateIdentity(request)` | 全量覆盖更新 |

### 前端逻辑

- **View 模式：** 信息卡片 + 规则开关 + 标签展示 + 编辑按钮
- **Edit 模式：** TextField 输入 + SwitchListTile + 标签添加/删除 + 保存/取消

### 后端处理

**IdentityController → IdentityFileRepository**

- 文件：`data/identity/profile.md`（YAML frontmatter）
- identity 数据被 **ContextEngine** 用于组装 AI prompt 的用户画像部分

---

## 13. 标签模块

### 功能描述

- 标签统计：每个标签的使用次数、最后使用时间
- 供 Launcher 标签宇宙、Memory 标签过滤使用

### 前端文件

| 文件 | 类/方法 | 职责 |
|:-----|:---------|:------|
| `services/models/tag_models.dart` | `TagSummary` / `TagsResponse` | DTO |

### 对应 API

| API | 前端方法 | 说明 |
|:----|:---------|:------|
| `GET /api/v1/tags` | `getTags()` | 标签统计 |

### 响应格式

```json
{
  "tags": [
    {"name": "半导体", "count": 12, "lastAt": "2026-07-22T10:00:00"}
  ],
  "total": 12,
  "updatedAt": "2026-07-22T12:00:00"
}
```

### 后端处理

**TagIndexController → TagIndexService**

- 文件：`data/index/tags.json`
- 记录保存时自动更新标签索引
- 被 ContextEngine 用于通过标签查找相关记录
- 无 AI

---

## 14. 定时补完服务（RecordRetryService）

### 功能描述

- 每 15 分钟自动扫描一次，补完因 AI 调用失败而未处理的记录和卡片
- 支持手动触发：`POST /api/v1/records/retry`
- 每条间隔 3 秒，每次最多 10 条，避免 DeepSeek 限流

### 后端文件

| 文件 | 说明 |
|:-----|:------|
| `application/RecordRetryService.java` | `@Scheduled(fixedDelayString = "PT15M")` 定时任务 |
| `AdaiCoreApplication.java` | `@EnableScheduling` 启用 Spring 定时 |

### 补完范围

| 类型 | 条件 | 处理 |
|:-----|:------|:------|
| **ContentRecord**（log + ask） | 创建 > 5 分钟且无对应 Memory | ContextEngine → AI → 更新 tags/domain/summary + Memory |
| **CardRecord** | 有对话轮次且 summary/tags 为空 | 提取 turns 调 AI 总结 → 写回卡片 + 创建记录 + Memory |

### 手动触发

```bash
POST /api/v1/records/retry

响应：
{"status":"ok","memoriesBefore":115,"memoriesAfter":125,"newMemories":10}
```

---

## 15. 多模态 / 多账号 / adai-admin

- **多模态图片记录（L4）**：`POST/GET /api/v1/records/media`（multipart，图片 → GLM-4.1V-Thinking-Flash VLM 理解 → 文本化进现有闭环：Timeline/Memory/Search 零改动）。图片落 `data/{userId}/records/YYYY/MM/media/`。详见 RFC `20260802-multimodal-image-glm`。
- **多账号（v1.0.0 预留）**：全链路 `X-User-Id` header → `data/{userId}/` 分层。账号由 adai-admin 管理（seed `adai`），无注册/口令（鉴权后补，REVIEW #127）。
- **adai-admin 产品后台**：账号/内容/数据/系统/知识五模块，接真实 API（`/api/v1/accounts`、`/api/v1/admin/**`）。定位：独立产品后台（类企业管理系统），非产品入口。

---

## 16. Domain=插件模型（RFC 20260814）

> 详见 RFC `20260814-domain-plugin-model` + `docs/reference/task-plugin-model.md`。

- **插件定义**：插件 = adai 拥有并受控开放的 Domain（`trading` / `project`）。Kernel 基础服务（记录/问答/记忆/档案/时间线/搜索/待办）不是插件，人人都有。`life` 是基础服务不是插件。
- **载体**：`Account.plugins`（`data/accounts/accounts.json`），adai-admin 后台控制（账号卡插件开关，W-P2-13 2026-08-17：走**服务端合并语义** `PATCH /accounts/{userId}/plugins` body `{add[], remove[]}`——S-R2 根治全量 PATCH read-modify-write 并发互覆；清空插件须传空数组 `[]`）。新账号默认空 = 只有基础服务；seed `adai` = `[trading, project]`（owner）。未知插件名过滤，脏数据 `"plugins":[null]` 构造器过滤不 NPE（REVIEW P2-3）。
- **查询**：`GET /api/v1/me/plugins`（无鉴权，当前用户启用插件 → 前端模块显隐）。
- **门控面**（读写侧对称，REVIEW S-3/S-4）：
  - 读侧：ContextEngine 知识源/贡献者按 `enabledPlugins` 过滤注入；Feed 行情条/异动推送仅 trading 插件用户；promote 反哺仅 trading 插件用户（否则 403）
  - 写侧：`RecordRetryService` 重补路径 domain 走 `gateDomain`（无插件用户不落盘 trading/project 标注）；`MarketAlertService` 定时轮询仅 trading 插件用户
  - D5 domain 收敛：AI 判定 domain 属未启用插件 → 收敛 `life`；prompt 的 domain 枚举/判定规则按启用插件生成（单一真相源，关键词与 `detectDomainScene` 常量一致，REVIEW P2-2）；CHAT 模式 system prompt 枚举随 ContextPackage 下发（REVIEW P2-4）
- **前端显隐**：adai-app World B Launcher（交易/阿呆系统按插件显隐）、adai-web 桌面壳（导航/IndexedStack/页面同一可见列表，按 label 重解析索引防错位，REVIEW P1-5）、adai-admin 账号卡插件开关。
- **账号迁移**：老文件无 `plugins` 字段 → 启动补默认（仅 seed adai）；PATCH 显式清空（字段存在）不被迁移推翻（REVIEW P1-4）。

## 附录：API 全集

| # | 方法 | 路径 | 用途 | AI |
|:--|:-----|:-----|:-----|:--:|
| 1 | POST | `/api/v1/records` | 统一入口（log / question） | ✅ |
| 2 | DELETE | `/api/v1/records/{id}` | 删除记录 | ❌ |
| 3 | PATCH | `/api/v1/records/{id}/domain` | 更新 domain | ❌ |
| 4 | POST | `/api/v1/conversations/end` | 结束对话 | ✅ |
| 5 | GET | `/api/v1/feed` | Feed 流 | ❌（简报缓存的） |
| 6 | GET | `/api/v1/brief` | 每日简报 | ✅ |
| 7 | GET | `/api/v1/timeline` | 时间线 | ❌ |
| 8 | GET | `/api/v1/memory` | 记忆查询 | ❌ |
| 9 | GET | `/api/v1/memory/dates` | 有记忆的日期 | ❌ |
| 10 | GET | `/api/v1/memory/count` | 记忆统计 | ❌ |
| 11 | GET | `/api/v1/memory/record/{recordId}` | 单条记忆 | ❌ |
| 12 | POST | `/api/v1/memory/rebuild` | 重建记忆 | ✅ |
| 13 | GET | `/api/v1/identity` | 个人档案 | ❌ |
| 14 | PUT | `/api/v1/identity` | 更新档案 | ❌ |
| 15 | GET | `/api/v1/tags` | 标签统计 | ❌ |
| 16 | GET | `/api/v1/search?q=` | 全文搜索 | ❌ |
| 17 | GET | `/api/v1/project/status` | 项目状态 | ❌ |
| 18 | GET | `/api/v1/project/tasks` | 任务列表 | ❌ |
| 19 | POST | `/api/v1/project/tasks` | 创建任务 | ❌ |
| 20 | PUT | `/api/v1/project/tasks/{id}` | 更新任务 | ❌ |
| 21 | DELETE | `/api/v1/project/tasks/{id}` | 删除任务 | ❌ |
| 22 | GET | `/api/v1/project/tasks/stats` | 任务统计 | ❌ |
| 23 | GET | `/api/v1/trading/positions` | 持仓 | ❌ |
| 24 | GET | `/api/v1/trading/portfolio` | 组合快照 | ❌ |
| 25 | POST | `/api/v1/trading/trades` | 录入交易 | ❌ |
| 26 | POST | `/api/v1/trading/review` | 生成复盘 | ✅ |
| 27 | GET | `/api/v1/trading/review` | 查询复盘 | ❌ |
| 28 | GET | `/api/v1/trading/reviews` | 复盘日期列表 | ❌ |
| 29 | GET | `/api/v1/trading/has-activity` | 交易活跃检测 | ❌ |
| 30 | POST | `/api/v1/trading/reviews/{date}/promote` | 知识反哺 | ✅ |
| 31 | POST | `/api/v1/records/retry` | 手动触发补完（RecordRetryService） | ✅ |
| 31 | GET | `/api/v1/trading/knowledge/conflicts` | 规则矛盾检测 | ✅ |
| 32 | POST | `/api/v1/cards/migrate` | 卡片迁移 | ❌ |
| 33 | POST | `/api/v1/cards/cleanup` | 卡片清理 | ❌ |
| 34 | POST | `/api/v1/records/media` | 图片记录（multipart → VLM 理解） | ✅ |
| 35 | GET | `/api/v1/records/media/{id}` | 取回原图 | ✅ |
| 36 | GET / POST | `/api/v1/accounts` | 账号查询/创建（adai-admin） | ✅ |
| 37 | DELETE | `/api/v1/accounts/{userId}` | 删除账号（adai-admin） | ✅ |
| 38 | GET | `/api/v1/admin/**` | 数据/系统/知识管理（adai-admin） | ✅ |
| 39 | GET | `/api/v1/accounts/available` | 启用账号列表（无鉴权，最小集 userId） | ✅ |
| 40 | GET | `/api/v1/me/plugins` | 当前用户启用插件（无鉴权，前端模块显隐） | ✅ |
| 41 | POST | `/api/v1/records/media/ask-batch` | 多图问答（Phase 1 带图 ask，1-3 张一次提问） | ✅ |
