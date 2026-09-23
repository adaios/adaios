# 功能参考文档（Feature Reference）

> **定位：** AdaiOS 功能完整参考。按前端模块划分，每个模块覆盖功能、API、前端实现、后端处理、AI 提示词。
> **用途：** 问题定位、新功能开发、重构时的基准对照。
>
> **文档版本：** v2.2 | **最后更新：** 2026-09-14（账本可见性批：历史成交/截图丢行上报 + 资金导入数值 fail-closed + 画像空串拒绝 + 账户卡当日盈亏标来源 + 负成本口径核对 + 全仓 JSON 读路径严格化；前值：部署前深审修复批——锁屏脱敏补全 + 外部令牌有效期/按 id 撤销 + 抓取健壮性 + 入口不丢件）

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
17. [learn 学习沉淀模块（RFC 20260829）](#17-learn-学习沉淀模块rfc-20260829)

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
      "domain": "life" / "trading"
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
| **早盘计划（含买点，RFC 20260922 B1）** | 工作日 9:15（cron 可配）| 持仓概览（昨收/数量/成本/止损/择时，读 `current.md`）+ **自选买点逐条四要素铁证**（① 本人历史统计〔样本 N≥5，不足直说「样本还不够」〕② 数字证据链 ③ 规则**逐字原文** ④ 位置）；**正文确定性渲染、不再走 LLM**；原 15:10 独立「买点提醒」已取消并入本条，买点段受 `buy-point` 开关门控 | 同上 |
| **午间知会（RFC 20260922 B5，用户拍板保留但降级）** | 工作日 12:00 | **只报事实与位置**：各票现价/今日涨跌 + 是否已到用户设的止损位（即使到了也只陈述位置、**不催操作**，该不该卖归尾盘那条）；**无异常（没触止损、没持仓）不发**；行情取不到不发（不发即不误导）| 同上 |
| **尾盘卖点（RFC 20260922 B2）** | 工作日 14:50 | **只列触发卖出条件的持仓**（R66 破止损 / R81 超仓），逐票**四要素铁证** + **账日期标注**（「按你 09-19 的账」）；没触发不发；行情双源失败 → 显式降级「这条我暂时给不了」；建议照旧落 `advice-history`（A3 依据快照）| 同上 |
| loss/gain/break-cost | 交易时段轮询（可配间隔）| 单日跌幅/涨幅/破成本线 | 同上 |

- **正文渲染（RFC 20260922 B 批起）**：时段推送**一律确定性渲染**（`TradingDecisionNarrator` 拼四要素），**不再调 LLM**——「四要素铁证」的全部价值在于逐条可指认，而 LLM 既保证不了数字与规则原文逐字、也保证不了「缺证据就不说」；**缺证据不发**（③ 规则原文取不到 → 该条不发；行情取不到 → 显式降级，绝不回落存储旧价冒充「昨收/今日」）
- **收盘复盘（RFC 20260922 B3）**：**不是到点硬发**，而是「数据同步完成后」的产物——导入持仓/资金/历史成交成功且 ≥15:00 即出；15:30 兜底（未同步则如实说「今天的持仓/成交快照我还没看到」且不落「已发」标记）；每天至多一条；正文 = 记账 / 账实 / 只记流水的 / 复盘 / 明天
- **锁屏脱敏（D1，2026-09-13 拍板 A；2026-09-14 晚间批补全 + fail-closed）**：外部通知渠道（APNs/Bark/微信）渲染**锁屏精简版**（`PushMessage.lockScreenContent` / `lockScreenTitle`），站内 Feed 仍收完整正文——锁屏是「放在桌上旁人能看见」的场合。**凡正文/标题含标的、金额、成本、止损价的推送都必须给锁屏版**（早中尾盘/买点/今日操作确认/当日盈亏附注/行情异动/批次止损/学习复习全覆盖；行情与批次止损的**标题**也不带股票名）；**未显式给锁屏版时发中性文案「阿呆有新的提示，打开看看。」，绝不回落完整正文**（fail-open 的兜底就是 P0-1 的机制根因）；`guard.sh G8` 机械要求每个推送构造点显式声明锁屏版
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
  "domain": "life(生活)/trading(交易)",
  "actionable": true 或 false,
  "actionSuggestion": "如果需要后续操作，写建议；否则写 null"
}
```

**domain 判定优先级（AI 输出规则）：**
- 指标、K线、持仓、走势、复盘、买入、卖出、仓位 → `trading`
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
  "domain": "life(生活)/trading(交易)",
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
| `domain` | String | life / trading |
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

（RFC `20260923-rhythm-and-memory-temporality` A 批：原「发现习惯就自然提及」注入**已删除**）

Open todos: {OPEN 待办 ≤3 条；**周期性习惯不注入**（`isRhythmLike` 分流：每周/每月/每天/例会/定期 或 周X＋固定）}

Trading activity: {hasActivity → "提醒生成复盘"}

Domain activity (7-day):
- life: {count}条 {trend}
- trading: {count}条 {trend}

Hot tags: {tags (3天内使用)}
Cold tags: {tags (14天未用)}

Strict format:
1. First line: {name} {greeting}!
2. Each line starts with a relevant emoji
3. Chinese, max 3 lines
4. Each line max 30 chars
5. No JSON output, plain text only
6. Use actual emoji characters (NOT \uXXXX escape codes)
7. Only if the "Open todos" section above is non-empty, mention 1-2 of them
8. Never invent reminders; do NOT bring up habits, routines or recurring events as things to do
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
- **「阿呆对你的了解」（2026-09-16「第一次见面」批）**：个人档案页把长期沉淀的
  patterns / preferences 端出来（`GET /memory/insights`，365 天长期窗口、按置信度降序、
  行为模式与偏好各取 3 条）；用户点「✓ 对」→ 写回 `identity.preferences`（此后随档案
  注入 prompt），形成「观察 → 确认 → 记住」闭环。数据本就一直在长，此前只是没有出口

### 前端文件

| 文件 | 类/方法 | 职责 |
|:-----|:---------|:------|
| `pages/memory_page.dart` | `MemoryPage` | 记忆页面 |

### 对应 API

| API | 前端方法 | 说明 |
|:----|:---------|:------|
| `GET /api/v1/memory/dates` | `getMemoryDates()` | 有记忆的日期列表 |
| `GET /api/v1/memory?date=` | `getMemory(date:)` | 按日获取记忆条目 |
| `GET /api/v1/memory/insights` | `getMemoryInsights()` | 「阿呆对你的了解」——长期观察聚合（patterns/preferences，365 天窗口，双端档案页消费） |

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
- 原生能力组（关于我 / 脑瓜子 / 时间线 / **待办**）+ 插件组（交易 / 学习，按 `GET /me/plugins` 显隐）——RFC 20260917 撤 project 后「阿呆系统」入口删除、「任务」改名「待办」并固定为原生（builtin）
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

> **模块定位（RFC 20260902 用户拍板，取代 RFC 20260815 定位条款）**：trading 插件 = 记忆内核在交易域的投影——**历史成交沉淀为对自己交易的理解**，阿呆以你的历史为镜照见持仓/自选/复盘，为未来决策提供依据（五层记忆模型见 RFC 20260902 §二，①事件②模式③案例④规则已建成、⑤认知层待建）。建议引擎等确定性判定是已建能力（非模块目的），现状功能如实列下。

### 功能描述

- 持仓列表（表格：代码、名称、数量、成本、现价、盈亏——现价实时行情注入，2026-08-16）
- 投资组合快照（总市值、总盈亏、现金余额、持仓数）
- 交易录入表单（代码、名称、方向、价格、数量、止损位、买点、目标价、原因；**输入 6 位代码自动带出名称**）
- 建议引擎（`POST /trading/advice`，R66 止损 / R81 仓位硬判定）
- 批量导入（通达信导出自动识别：持仓快照 / **历史成交** / 交易 CSV 三格式；选择文件上传留存或粘贴）
- **持仓导入 fail-closed + 负成本（2026-09-13 负成本持仓批，用户实测事故）**：用户 3 只持仓只进来 2 只（被丢的 600601 成本 −5.078），根因是解析器把负成本当脏数据丢弃、且导入结果从不展示被丢的行——而 `replace=true` 是**全量覆盖**，漏一行 = 那只持仓被静默删除。现行为：**负/零成本合法**（反复做 T/分红把成本摊到 0 下是真实且券商就这么记的；只有「取不到数」才算错误）+ **0 股残留行归「已清空跳过」**（券商文件保留的已清仓标的，不算错误，但导入后如实告知「另有 N 行已清空未计入」）+ **有看不懂的行 → 拒绝覆盖**（不发请求、弹窗「先不动你的持仓」逐行摆原因、并告知看懂了几行）——解析器越界崩溃（短行）一并修掉；**负/零成本下盈亏% 显示「—」**（`pnlPercent=null`，不给 0%——那是谎报「不赚不亏」），盈亏**金额**照常且与券商一致
- **交易决策的铁证底座（v3.81，2026-09-22，RFC 20260922 A 批）**：把「阿呆凭什么这么说」变成用户可核对的数据——**四要素铁证**：① **本人历史操作统计**（`GET /trading/evidence/history`：清仓回合按「持仓时长 / 盈亏区间 / 清仓判定 / **买点形态**」分桶，给次数 · 胜率 · 平均盈亏 · 平均持天；买点形态记在**批次**上，用 `symbol+buyDate` join，**对不上就归「未标形态」、不猜**；**样本门槛 5**——不足的组 `sufficient=false`，全组不足时直说「样本还不够」，**不许拿 1-2 次巧合当规律**）· ② **当时的数字**（现价 / 持仓占比 / 止损位）· ③ **规则依据原文**（`GET /trading/evidence/rule/{ruleRef}`：**逐字**取自 `os/trading-engine/knowledge/context/rules.md`，`R66`/`r66`/`66` 都认；找不到 → **404，不替你编一条**）· ④ **可追责**（建议留痕新增 `basis` 依据快照 + `outcome` 结果回填：`POST /trading/evidence/backfill` 按 **N 个交易日**（默认 5，`adai.trading.advice-outcome-days` 可配）写回实际走势 `priceThen`/`priceAfter`/`pct` 与用户**有没有操作** `traded`/`none`/`unknown`〔流水读不到记 unknown，不谎报 none〕；**幂等** · **只记事实、不判对错** · 数据不全**不写半成品**）。`POST /trading/advice` 响应新增 **`evidence`**（四要素结构；出口统一补齐，**补不出留空**）。设计口径见 RFC `20260922-trading-decision-copilot.md`
- **交易账实一致性（v3.61，2026-09-12 账实一致性批，RFC 20260912）**：账本三条真源（券商快照锚点 / 逐笔流水 / 派生持仓）收口——导入支持 **`dryRun` 预检**（只返回 `plan`，不写任何文件，改账前先让人看见）、**锚定 fail-closed**（锚定缺失而本次需回放持仓/现金 → 400 人话 + 逃生路径：先导「持仓股」/「资金股份查询」快照建锚定，或 `mode=append` 仅补流水）、**卖超可见**（`rejected` 行级明细 + ERROR 日志，真实成交不再「WARN 后消失」）、**对账闸门**（`GET /trading/integrity` 报 `drift`/`gaps`，`GET /trading/anchor` 查状态、`PUT /trading/anchor` 存量显式回填）；**账实不符当天可见**——15:30 收盘小结（`close-summary` 推送）在有 `drift`/`gaps` 时追加一行「⚠️ 阿呆对不上账：N 只标的的持仓和流水对不上、M 笔成交没能并进持仓——打开交易页，我把明细列给你看」（无差异不推、自检失败静默降级）——口径见 `trading-features.md` §八 14
- **账本「丢行/丢值必须可见」（v3.65，2026-09-14 账本可见性批）**：把「解析或读盘出了问题、系统却按一切正常继续」的四处收口——①**历史成交导入丢行可见**：解析层五类丢弃（代码不是 6 位 / 买卖标志不是买卖 / 价格非数字 / 日期格式坏 / 有量无价）现在带**行号 + 原文 + 原因**回前端（`unparsed`），不再只显示「识别出 N 笔」；②**截图入账丢行可见**：被表格规则没记的行（未成交状态 / 申购配号 / 占位代码 / 0 价）在响应 `dropped` 里逐行说明（顺手把「申购」判定提到状态判定之前，原因才说得准）；③**资金股份导入 fail-closed**：首行「余额/可用/可取/参考市值/资产/盈亏」任一项读不成数字 → **拒绝导入并说明是哪一项**（原来 `null` 会写进账户快照，资金显示变空），明细没看懂的行 → `unparsedRows` 计数（这些持仓的精确成本本次不更新）；④**账户卡标注当日盈亏来源与日期**：`todayPnlSource` = `broker`（券商「当日盈亏」列求和）/ `calc`（系统精确计算）/ 未知（老数据不标注），双端显示「券商口径 · 09-11」「系统计算 · 09-14（已过期）」——起因是 2026-09-13 用户实测「屏幕上的 −2837 是周六算的」，而当时 UI 只有一个数字、三个可能来源无法分辨。另：`PUT /trading/profile` **空串与 null 同拒**（原来空串能把整份画像清空）；全仓 18 个文件仓储的 JSON 读路径开启严格模式（截断/尾部垃圾不再被读成「合法前半段」）
- 主动推送（真止损异动 / 早盘计划 / 午间跟踪 / 尾盘建议 → PushChannel Feed+微信，见 §1）
- **推送体验（RFC 20260817）**：推送卡专属样式（类型徽章：早盘蓝/午间紫/尾盘橙/买点绿/预警红）+ 结构化内容（总结+持仓逐行+建议）；**推送开关**（per-user 10 类型：时段/买点/止损/接近止损/大跌/放飞/破成本/行情条/收盘小结 close-summary/复习提醒 learn-review——2026-08-29/09-07 增，`data/{userId}/trading/push-settings.json`，写读双侧门控；learn-review 另经 `GET/PUT /learn/push-settings` learn 侧可达，纯 learn 用户可自关）；app 左滑删单条/右滑进设置，web 交易页设置入口（learn 页头铃铛开关复习提醒）
  > ⚠️ 2026-08-23 标注：徽章配色受 **P1-推送1（标题契约断裂）** 阻断——后端 `FeedPushChannel` 落库丢标题 → 前端按标题 switch 全落灰「行情」，修复前展示与上文不符
- **图片对话流（RFC 20260817）**：图片对话卡图置顶、turns 跟随滚动——聊天态与刷新态渲染一致（不再退化为固定附件）
- **交易日志自动归集（RFC 20260817）**：成交截图（VLM 识别）/文字（「清仓了XX」宽松解析）→ 当日候选去重（symbol+方向）→ 收盘 15:15 推送「今日操作汇总」→ 用户「确认并入账」→ recordTrade 链路落库；仅 trading 插件用户触发；不完整候选（无数量/价格）确认跳过引导补全；`data/{userId}/trading/trade-log/{yyyy-MM-dd}.json`
- **历史成交 Tab（RFC 20260823）**：web 交易页常驻第 5 Tab（取代页头交易历史 Dialog）——日期范围查询 + 按日分组流水（方向/时间/代码/名称/数量/价格/成交金额/发生金额/成交编号 + 系统计算的费用放最后——2026-08-25 删止损/买点/原因三列）+ 独立导入入口（只认通达信历史成交导出）；导入幂等 + **缺失成交时间回填**（`updated` 计数）
- **逐笔批次跟踪与行为纠偏（RFC 20260825）**：持仓从「一只股票」细化到「每一笔买入」——批次 = 同标的+同方向+同日合并（一天最多一个买批，成本=当日加权含费），卖出 **LIFO** 先扣最近批次（底仓不动、先走短线），批次清仓 = 回合总账；**批次视图 `GET /trading/lots`**（注入现价 + 流水对账，web 持仓 Tab 批次弹窗 + app 持仓卡简版 + **批次明细弹窗 2026-08-28**）；**导入双模式**（当日成交 → 同步持仓/现金/流水 + **每日操作总结**（买卖聚合 + 批次 diff + 行为标注六类：亏损加仓/追高/短线新开/破止损未走/浮盈回吐/短线超期）；历史 → 只补流水）；**批次级止损推送**（批次破自己的止损（未设默认 −7%）单独提醒，不跟底仓混）；**推送定时消失**（`expiresAt`：行情类次日 09:30、汇总类次日 23:59）
- **截图入账（2026-08-26）**：交易页「📷 截图入账」→ 1-3 张成交截图 → VLM 识别 → 当日候选逐笔确认/丢弃；**候选缺成交日期禁落库 + 「补日期」入口**（v3.32，`PUT /trading/trade-log/date`）；`POST /trading/screenshots`
- **交易规则层（第三阶段，2026-08-30）**：规则 = 用户私有内容——确定性判定（止损/仓位/买点/行为标注/打分/硬约束区间）全部从 `data/{userId}/trading/rules.yaml`（16 参数，`GET/PUT /trading/rules` 表单化编辑，web 规则 Tab）读取，无规则 → 默认值兜底（= adai 现状）；知识注入用户私有 `data/{userId}/trading/knowledge.md` 优先，os/ 作 adai 默认（owner 白名单）
- **活跃市值区间开关（v3.41，2026-09-04）**：用户**亲手判定**当前活跃市值多空区间（指南针活跃市值口径，两档：多头/空头），App/Web 交易页红绿切换（红涨绿亏：多头=红、空头=绿）；落 `data/{userId}/trading/market-stage.json`；**推送择时状态三级读取 = 用户判定优先 → current.md → 「择时状态未知」**——用户设了就不再被 OAMV 规则推断覆盖（解决 current.md 旧规则永久锁死空头、用户无法表达判断的问题，2026-09-03「指南针活跃市值=一切的前提」对话确立）
- **收盘小结推送（2026-08-29）**：15:30 `close-summary` 类型推送（当日成交 + 破止损 + 待确认），双端开关
- **流式问答（2026-08-30，非交易专属但交易页在用）**：`POST /records/ask-stream` SSE（text 增量/meta 定稿/error 事件），app/web 交易页阿呆问答走流式，后端降级同步

### 交易数据智能（RFC 20260816，2026-08-16 落地）

| 区块 | 数据 | 导入 | 展示 |
|:-----|:-----|:-----|:-----|
| **自选股** | `data/{userId}/trading/watchlist.json` | 通达信自选导出（代码/名称/细分行业/长期中期短期形态/近日指标提示）| 表格：代码/名称/行业/长中短形态/指标提示（金叉红）/删除 |
| **清仓股复盘** | `data/{userId}/trading/sold.json` | 通达信清仓导出（介入/清仓日期/持仓天数/买卖次数/持仓期涨幅%）| 表格：代码/名称/介入→清仓/天数/涨幅/心理标注（点击标注追高/恐慌等）|
| **资金股份查询** | 存账户快照 `account.json`（**现金唯一真源 = AccountSnapshot.cash，S5**——不再写 positions.md cashBalance）+ 精确成本（4 位）| 通达信「资金股份查询」导出 | 现金/总资产展示（R81 分母=总资产）|

### 前端文件

| 文件 | 类/方法 | 职责 |
|:-----|:---------|:------|
| `pages/trading_page.dart`（adai-web） | `TradingPage` | 交易管理端（持仓 + 自选 + 清仓 + 资金 + 历史成交 + 规则 + **案例 七 Tab**——规则 Tab 2026-08-30 第三阶段：规则参数展示 + 编辑弹窗；**案例 Tab 2026-08-30 第四阶段**：完美买点案例列表/标注/详情弹窗（K 线图还原））|
| `widgets/case_kline_chart.dart`（adai-web） | `CaseKlineChart` | 案例 K 线图（三区 CustomPaint：蜡烛+MA10/MA60（黄线）+买点标记 / 量 / KDJ+MACD；指标前端重算，A 股红涨绿亏）|
| `pages/trading_page.dart`（adai-app） | `TradingPage` | 手机交易页（账户卡 + 记录双通道 + **📷 截图入账（2026-08-26）** + 持仓卡（批次简版 + **批次明细弹窗 2026-08-28**）+ 阿呆建议弹层；**2026-08-22 移除自选/清仓只读区块**，管理归 web）|

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
| `GET /api/v1/trading/market-stage` | `getMarketStage()` | **活跃市值区间（v3.41，2026-09-04）**：读用户手动判定的多空区间（指南针活跃市值口径，两档 bull/bear，红涨绿亏）|
| `PUT /api/v1/trading/market-stage` | `setMarketStage()` | **设定活跃市值区间**：用户亲手切多头/空头，落 `data/{userId}/trading/market-stage.json`，推送择时状态改以用户判定优先 |
| `POST /api/v1/trading/screenshots` | `uploadScreenshots()` | **截图入账（2026-08-26）**：1-3 张成交截图 → VLM → 当日候选 |
| `PUT /api/v1/trading/trade-log/date` | `patchTradeLogDate()` | **补写候选成交日期（v3.32）**：缺日期候选补日期后允许确认 |
| `POST /api/v1/trading/cases` | `annotateCase()` | **完美买点案例标注（第四阶段）**：symbol+buyDate → 自动拉 60+30 日 K → 特征+后验 |
| `GET /api/v1/trading/cases` | `listCases()` | 案例列表（buyDate 倒序）|
| `GET /api/v1/trading/cases/{id}` | `getCaseDetail()` | 案例详情（kline=true 附 90 根 K 线供画图）|
| `DELETE /api/v1/trading/cases/{id}` | `deleteCase()` | 删除案例 |
| `POST /api/v1/trading/cases/{id}/insight` | `generateCaseInsight()` | **环 3 AI 理解**：LLM 读特征+K 线 → aiInsight（summary/keyFeatures/confidence）落盘 |
| `POST /api/v1/trading/cases/match` | `matchCases()` | **环 4 判定当下（核心价值）**：当前形态 vs 案例库相似度 Top 5 |
| `POST /api/v1/trading/cases/import` | `importCases()` | **批量导入（2026-08-31）**：粘贴 B1/B2 笔记 → 解析 + 名称表转代码 → 逐条标注 |

> **第三阶段（2026-08-30，trading-plugin-architecture.md）**：交易插件从 adai 专属演进为「通用能力 + 个性化规则」。规则参数按用户隔离，驱动止损/仓位/买点/行为标注/清仓 verdict/打分权重/纪律硬约束/知识注入（`data/{userId}/trading/knowledge.md` 用户私有优先）。**web 交易页第 6 Tab「规则」**：参数中文标签展示 + 编辑弹窗（表单化 PUT）。无规则用户 → 全部默认 = adai 现状（降级不坏）。

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

## 10. 待办模块（Kernel builtin，RFC 20260917）

> **三层定位**（RFC `20260917-todo-kernel-retire-project-plugin.md` §三）：**core 内核**（记录/问答/记忆/上下文/身份/存储，不可关）· **builtin 内置能力**（**待办** / 搜索 / 时间线 / 简报，默认开、用户侧可关）· **optional 可选插件**（trading / learn，默认关）。判据一句话：没它别的跑不起来 → core；第一次用就需要 → builtin；只有一部分人需要 → optional。
>
> **原「项目管理模块」已退役（2026-09-17）**：project 插件撤销——项目状态仪表盘（`ProjectStatusPage`）、任务看板（`ProjectTaskPage`）、`/api/v1/project/**` 6 个端点、admin 账号页 project 开关全部删除（breaking，无兼容别名）。**待办归 Kernel builtin**（人人有、无插件门控），形态从「看板」改为「纯清单」。`os/project-os/` 知识文件保留在仓库（File First），但不再注入任何用户上下文。

### 功能描述

- **纯清单两态**：未完成（`OPEN`）在上、已完成（`DONE`）折叠；每条 = 一句话 +（可选）到期日 + 完成 + 删除；顶部直接加一条
- **到期提醒**：可选到期日（`due`）→ 到期当天 **08:00 与 18:00** 各推一次（推送类型 `todo-due`，进 `PushSettings.ALL_TYPES`，默认开、可关）；通知深链 `todo:today` 打开待办页
- **收集**：记录里可执行 → 自动进待办（`RecordToTodoLinker`，保留 `sourceRecordId`）+ 清单页手动加
- **记忆联动（单向）**：建待办**不动记忆**；完成待办 → `markDone(记忆)`；删除待办 → 清记忆 actionable；`#备忘/#想法` 排除判断前移到记忆写入侧
- **Feed**：不再出现待办卡（Feed 回归纯对话流）

### 前端文件

| 文件 | 类/方法 | 职责 |
|:-----|:---------|:------|
| `pages/todo_page.dart`（app）/ `pages/todo_page.dart`（web）| `TodoPage` | 待办清单页（两态 + 可选到期日 + 完成/删除 + 已完成折叠）|

### 对应 API

| API | 前端方法 | 说明 |
|:----|:---------|:------|
| `GET /api/v1/todos?status=` | `getTodos()` | 列表（app `TodoItem` / web `TodoResponse`）|
| `POST /api/v1/todos` | `createTodo()` | 创建 |
| `PUT /api/v1/todos/{id}` | `updateTodo()` | 更新（`null` 保持原值；`due:""` 清除到期日）|
| `DELETE /api/v1/todos/{id}` | `deleteTodo()` | 删除（取消即删除）|
| `GET /api/v1/todos/stats` | `getTodoStats()` | 统计（total / open / done）|

### 后端处理

**TodoController → TodoAppService（`kernel/todo/` + `application/TodoAppService`）**

- File First 存储：`data/{userId}/todos/YYYY/MM.md`（旧 `data/{userId}/project/tasks/` **原样留存、不迁不删**）
- **无插件门控**（旧 project 插件写端点的三处 403 已随插件退役）
- 无 AI 调用；`TodoReminderService` 定时（08:00 / 18:00）推 `todo-due`

---

## 10b. 节律模块（Kernel builtin，RFC 20260923 B 批）

**定位**：与待办同级的内置能力，**但节律不是待办**——待办是一次性、有终点的动作（`OPEN`/`DONE`）；节律是周期性复现的习惯（每周四发版、每天跑步），**没有「完成」这个状态**（用户 2026-09-23 原话：「这是我的工作周期习惯，不是待办」）。

**表示**：`recurrence` 用 **RRULE**（iCalendar / RFC 5545 子集）——`FREQ=DAILY|WEEKLY|MONTHLY`（必填）+ `INTERVAL` / `BYDAY` / `BYMONTHDAY` / `UNTIL`（可选）；非法周期当场 400 人话，**未知部件拒绝而非静默忽略**（静默忽略会造出「看起来对、其实不是你说的那个周期」）。

**状态**：`ACTIVE` / `PAUSED` / `RETIRED`（**刻意无 DONE**）；带 `validFrom`（生效日，兼 RRULE 的 anchor）与 `validUntil`（失效日，含当日）——「这周四不用加班」改 `validUntil` 或转 `PAUSED`，**不删条目**（历史保留）。

**存储**：`data/{userId}/rhythm/YYYY/MM.md`（File First；条目格式与兼容性见 `data-format-freeze.md` §2.24）。

**入口**：
- **记录自动分流**：可执行记录里含周期表述 → **先试节律**（`RecordToRhythmLinker`），推不出 RRULE 才回落待办；
- **端点**：`GET|POST /api/v1/rhythms` + `PUT|DELETE /api/v1/rhythms/{id}`（**无插件门控**）；
- App / Web **暂无独立入口**（RFC §七 T3 拍板：暂不给入口，命中时在概览卡作为背景出现）。

**在概览卡的形态**：**只在 RRULE 命中当天**注入，且口径写明「背景，不要提醒、不要问要不要做」（`BriefAppService` 闸 1）——节律是概率不是承诺。

**已知边界**：`detectRrule` 只覆盖 每天 / 每周 / 每月——「每季度 / 每年 / 定期 / 例行」能通过周期性判据（因此不会被当待办天天催），但**不转节律**，回落为待办。

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
- **多账号（v1.0.0 预留）**：全链路 `X-User-Id` header → `data/{userId}/` 分层。账号由 adai-admin 管理（seed `admin`，2026-09-04 由 adai 迁移——admin=后台管理专用、adai=产品主账号），无注册/口令（鉴权后补，REVIEW #127）。
- **adai-admin 产品后台**：账号/数据/系统/知识四区（早期表述「五模块」，MD 收敛后「内容」并入数据区——详见 `docs/reference/admin-features.md` 功能手册），接真实 API（`/api/v1/accounts`、`/api/v1/admin/**`）。定位：独立产品后台（类企业管理系统），非产品入口。**系统→维护「行情数据导入」（2026-09-04 MD17）**：上传通达信 .zip 数据包 → 校验 + 原子解压更新 TDX 行情目录（`POST /admin/market/tdx-import`，替代手工 scp）。

---

## 16. Domain=插件模型（RFC 20260814）

> 详见 RFC `20260814-domain-plugin-model` + `docs/reference/task-plugin-model.md`。

- **插件定义**（RFC 20260814；RFC 20260917 三层定位）：插件 = adai 拥有并受控开放的 Domain/能力，**只有 `trading` / `learn` 两个**（project 插件已于 2026-09-17 撤除；`learn` 为 2026-09-06 注册，RFC 20260829 外部内容学习沉淀，详见 §17）。`life` 是基础服务不是插件。**三层定位**：core 内核（记录/问答/记忆/上下文/身份/存储，不可关）· builtin 内置能力（**待办**/搜索/时间线/简报，默认开、用户侧可关）· optional 可选插件（trading/learn，默认关）。
- **载体**：`Account.plugins`（`data/accounts/accounts.json`），adai-admin 后台控制（账号卡插件开关，W-P2-13 2026-08-17：走**服务端合并语义** `PATCH /accounts/{userId}/plugins` body `{add[], remove[]}`——S-R2 根治全量 PATCH read-modify-write 并发互覆；清空插件须传空数组 `[]`）。新账号默认空 = 只有基础服务；seed `admin` 预置 = `[trading]`（新环境兜底）。**历史文件里的残留 `"project"` 由 `PluginRegistry.isValid` 自动过滤（不迁移）**，admin 再写入 `"project"` → 400；未知插件名过滤，脏数据 `"plugins":[null]` 构造器过滤不 NPE（REVIEW P2-3）。
- **查询**：`GET /api/v1/me/plugins`（需登录，会话账号 = 当前用户启用插件 → 前端模块显隐）。
- **门控面**（读写侧对称，REVIEW S-3/S-4）：
  - 读侧：ContextEngine 知识源/贡献者按 `enabledPlugins` 过滤注入；Feed 行情条/异动推送仅 trading 插件用户；promote 反哺仅 trading 插件用户（否则 403）
  - 写侧：`RecordRetryService` 重补路径 domain 走 `gateDomain`（无插件用户不落盘 trading 标注；`project` 已撤除，一律收敛 `life`）；`MarketAlertService` 定时轮询仅 trading 插件用户
  - D5 domain 收敛：AI 判定 domain 属未启用插件 → 收敛 `life`（白名单只放行 `trading` + `life`；RFC 20260917 起 `project` 等未知值一律收敛 `life`）；prompt 的 domain 枚举/判定规则按启用插件生成（单一真相源，关键词与 `detectDomainScene` 常量一致，REVIEW P2-2）；CHAT 模式 system prompt 枚举随 ContextPackage 下发（REVIEW P2-4）
- **前端显隐**：adai-app World B Launcher（原生能力 / 插件两组，插件组只有交易/学习，按 `GET /me/plugins` 显隐——「待办」是原生能力不在插件表）、adai-web 桌面壳（导航/IndexedStack/页面同一可见列表，按 label 重解析索引防错位，REVIEW P1-5）、adai-admin 账号卡插件开关（project 开关已删除）。
- **账号迁移**：老文件无 `plugins` 字段 → 启动补默认（仅 seed adai）；PATCH 显式清空（字段存在）不被迁移推翻（REVIEW P1-4）。

## 17. learn 学习沉淀模块（RFC 20260829）

> **状态：V1 后端流水线（2026-09-06）+ L2 呈现层（2026-09-07）已落地**：LearnKnowledgeSource 问答注入（web 端**资产页**——导航「学习」learn 插件门控 + 目录树 + 单篇全文渲染；app 端「最近学习」入口 + 单篇全文，双端分工对齐 RFC 3.7）。**V2 消化闭环（2026-09-07）已落地**：批 1 复习状态流转 + 编辑（PATCH /cards/status + /cards，retell 复述建模）；批 3 trading 候选联动（反哺候选 + learn_card_id 回链）；批 4 复习提醒推送（每晚 20:00 learn-review）+ **web 资产页 V2 交互接线**（状态徽标 + 去复习/标记完成 + 写复述弹窗 + 反哺候选按钮；app 详情页状态徽标 + 复述段只读呈现）。**learn V2 审查修复批（2026-09-07 learn V2 增量深审，用户拍板）**：流转只允许相邻（new↔review / review→done / done→review，跳变 400）；进入 review 写 `review_at`、提醒按进入队列满 7 天计时 + 同卡 7 天节流（不再按消化日误判、不再每晚 nag）；同 type+title 任意日期同名拒绝 + 残留歧义读侧 400（寻址不再改错卡）；learn_card_id = 源卡真实文件路径；复习提醒开关 learn 页可达（纯 learn 用户可自关，web/app 双端铃铛）；候选管理 UI（web 页头收件箱：列表/删除）；反哺按钮按 trading 插件二次门控。**喂入入口批（2026-09-10，用户拍板「先页面后对话流」）**：`POST /learn/cards` 改**提交式消化**（后台 learnSubmitExecutor 执行 + `GET /learn/digest/status` 轮询 done/failed——对齐复盘 submitReview 先例，杜绝同步 LLM 几十秒超时「看似没反应」；inflight 去重只烧一次 AI）；**web 资产页页头「＋」弹窗喂入 + app「最近学习」页头「＋」喂入页**双端页面入口（粘贴字幕/文章原文 → 让阿呆消化 → 完成后自动定位打开新卡；失败人话可重试；轮询超时提示后台继续、素材留存 `_raw/` 兜底）；空态引导改指页面入口（对话流喂入 = 批 2 待排）。**抓取批（2026-09-12，RFC 20260912 D 形态阶段 1 抓取主干，v3.57）——learn 从「用户自己搞素材」升级为「服务端自己抓」**：这是 B 形态失败的根本原因（把最费力的一步留给了用户），也是 D 与 B 的分水岭。① **链接喂入**：`POST /learn/digest` 支持 `url`——B站视频走「view 元数据 + player/v2 字幕 + playurl 音频线索」，文章走「抓 HTML → 去脚本/样式/导航转正文」，反爬 403 → **Web Archive 快照兜底**，仍失败则人话引导「把正文粘进来」；② **无字幕 → 报价 → 确认 → 转写**：实测多数视频确实没有可获取字幕（首例 BV12LR1B3EUt 字幕列表为空），所以云端转写是**必经路径**：抓到无字幕视频先回「37 分钟，预计约 0.18 元（本月剩余额度 10 小时）」，用户 `POST /learn/digest/confirm` 点头后才真调阿里云百炼 fun-asr；取消则**一分钱不花**；③ **费用可控六条**：字幕优先（有字幕就不转写）／同一素材只转一次（转写稿落 `_raw/`，重整理零费用）／只在你明确发话时花钱（无任何批量后台转写）／月度配额硬闸 + 记账（**默认 108,000 秒/月 = 30 小时**，月初重置，超限拒绝并说明剩余；前 36,000 秒落在云端免费额度内=0 元，超出按 0.288 元/小时）——**这 36,000 秒与云端的免费额度对齐**：百炼的**语音识别模型**有每月 1 日重置、长期有效的 36,000 秒（10 小时）免费额度（2026-09-13 用户控制台核对原文「每月1日额度重置 · 长期有效」），本产品默认的 `paraformer-v2`（0.288 元/小时）就在其中 → **额度内转写实际 0 元**；注意额度**按模型快照绑定**（如 `fun-asr` 0.792 元/小时、`fun-asr-flash-*` 仅支持 ≤5 分钟短音频，额度不通用），超出 10 小时或换模型才按量计费／单次前置报价确认／ASR 走端口（可换更便宜通道）；④ **进度可见**：轮询响应加 `stage`（正在抓取原文/正在转写/正在整理成卡片）；⑤ **源必留痕**：元数据 json、字幕、文章全文、转写稿全部落 `learn/_raw/`（文章会失效、原音频丢了不可重建），也让「同一素材只转一次」天然成立；⑥ **首期不做清单**：YouTube（服务器网络不可达）与公众号/知乎/小红书/X/微博/抖音（反爬与登录墙）**明确告知 + 给替代路径**，不假装能抓、不绕登录墙（B8）；⑦ **出站白名单（2026-09-12 对抗审查 P0-1 修复）**：抓取目标来自用户输入 → 出站前先过策略：拒私有网段/回环/链路本地/云元数据地址与非标准端口，**关自动重定向改逐跳复检**，第三方响应给的地址（字幕/快照）也收敛域名白名单，响应体有上限（正文 4MB/音频 64MB）；⑨ **读侧对齐（2026-09-12）**：learn 目录里有两个写入方——产品卡（扁平 `{type}/{date}_{title}.md`）与 Mac 上 DSH 技能 A 的手工卡（主题子目录 `{type}/{topic}/NN-{slug}.md`，段名带括号后缀）。读侧已容错：段名归一（编号前缀/括号后缀都识别，`## 内容脉络` 不冒充「关键要点」）、问答召回正则放宽（A 的卡不再只剩标题）；**写侧守卫**：产品之外的卡一律只读，四个写入口统一人话拒绝（不会改动别人的原始文件）。**完整契约迁移（topic 归并/编号/README/`_raw/` 层级）已于 2026-09-12 完整升级批落地**（见下条），REVIEW P2-learn12 出表。
- ⑧ **转写费用按实际时长结算 + 先预留后花钱**（转写失败退回预留，不留「花了钱记不上账」的洞）；账本损坏时拒绝转写（fail-closed）。

- **插件注册**：PluginRegistry 的 `learn` 插件（RFC 20260917 后与 `trading` 并列，注册表共两个；`Account.plugins` 可含；`GET /me/plugins` 显隐；未启用用户访问 learn 端点 → 403）。
- **定位**：外部内容（B站视频/YouTube/文章/字幕）→ AI 结构化卡片 → 个人知识资产。**与 A 方向会话技能（learn-digest skill）同源**：技能是 DSH 会话内执行版（独立落盘 `data/adai/learn/`），本插件是阿呆产品内版（按用户落 `data/{userId}/learn/`）；两者格式同构（frontmatter + 渐进式摘要）；**2026-09-12 抓取批把 A 的能力（抓取 → 转写 → 六段结构化 → `_raw/` 留痕）下沉为产品能力**——产物契约不新建第二套，A 技能与产品写入同一份格式（RFC 20260912 §3.3 原则 2）。
- **喂入（独立端点，2026-09-06 用户拍板）**：`POST /api/v1/learn/cards`——仿截图入账先例，learn 消化是动作不是记录：不建记录、不沉淀记忆、不污染 Feed/时间线。素材留痕 `learn/_raw/`（LLM 失败时素材不丢）。
- **卡片**：`data/{userId}/learn/{type}/{topic}/NN-{slug}.md`（File First md 即真相源，**2026-09-12 完整升级批起按主题目录归档 → 与 Mac 侧技能同一契约**）——frontmatter（title/type/topic/origin/platform/author/url/published/created/status/trade_related/trade_note/tags）+ 正文四段（核心观点/关键要点/我的疑问/复述）；同主题多源按 NN 续号，主题 `README.md` 自动维护索引（产品只**追加** `## 阿呆整理记录（自动维护）` 段，**不重写**手工 README），原始素材归位到 `{type}/{topic}/_raw/`。老式扁平 `{type}/{yyyy-MM-dd}_{title}.md` **照旧可读可写、不强制迁移**。type=ai/trading/other 是文件分类非 domain 收敛对象；trade_related 仅 trading 内容有意义（防 LLM 幻觉：非 trading 强制 false；V1 只记录不联动规则库，规则候选改动须用户拍板）。
- **卡片流「一页一单元」（2026-09-15，v3.69）**：卡片正文新增第五段 `## 卡片页`（fenced JSON 数组）——消化时把同一份素材拆成 8-14 个视觉单元，每页一句结论 + 一种载荷（`points` 要点 / `table` 对照表 / `numbers` 数字卡 / `compare` 正反栏 / `diagram` 结构图 / `quote` 原话），App/Web 详情页按页渲染、**原文退到折叠区**——治的正是「6 条 200 字长句 = 段落墙，一个字不想看」。**老卡没有该段 → `pages: []` → 双端自动降级为原有渲染（零迁移）**；Mac 侧技能产物、问答召回、原四段、编辑手术全部不受影响（页段按未知段原样保留，改状态/编辑正文都不毁页）。历史卡回填走 `POST /learn/cards/repages`：拿该卡 `_raw/` 里已留痕的素材重排页，**只补呈现层、正文一字不动**；没素材/只读卡/AI 失败都给人话且不写盘。
- **卡片归属与只读（2026-09-12）**：卡片带 `topic`（主题目录名，缺省「未归类」）与 `writable`（`origin: product` 标记决定）——`writable=false` = **在 Mac 上用 DSH 技能整理的原始卡**：列表/全文照常可见（全文走 `GET /learn/content`），但编辑/流转/反哺一律 400 人话拒绝（产品不改动别处整理的原始文件）。同名消解：**本产品卡优先**，别处手工卡同名不再拦住新建（P2-learn20 修复）。
- **失败降级（fail-visible）**：LLM 失败/输出不可解析/缺标题 → 原始素材留存 `_raw/` + 400 人话（不产半成品卡片）；同日同 type 同标题重复 → 400（防覆盖）；type 越界回落 other。
- **并发/健壮性**：per-user 条带锁（16 条带）；损坏文件跳过不中断列表；fileStem 清洗防路径逃逸（`..`/`/` 归一）。
- **端点**：`POST /learn/digest`（喂入链接或素材，**推荐端点**；`POST /learn/cards` 保留为兼容别名）、**`POST /learn/digest/image`（图片源：书页/PPT/讲义/截图 1~3 张 → 视觉模型忠实提取 → 同一流水线；2026-09-12 完整升级批）**、`POST /learn/digest/confirm`（**转写费用确认**，`{confirm:true|false}`，取消不花钱）、`GET /learn/digest/quota`（本月转写用量/费用/剩余额度 + 单价 + ASR 可用性）、`GET /learn/digest/status`（消化任务状态 idle/pending/running/needs_confirmation/done/failed/cancelled + stage[fetching/transcribing/**reading**/structuring]/source/cost）、`GET /learn/cards`（`?type=` 列表，含 topic/writable）、`GET /learn/card`（`?type=&title=` 单篇卡片字段）、**`GET /learn/content`（`?type=&title=` 按 md 原文读全文——手工卡独有的段也读得到，2026-09-12）**、**`GET /learn/find`（`?q=&limit=` 找卡片：「打开那篇」与学习页搜索，纯规则打分不烧 AI，2026-09-12）**、`GET /learn/tree`（资产树，ai/trading/other 分组）、**`POST /learn/migrate`（老式扁平卡一次性迁移，幂等，2026-09-12）**、**`DELETE /learn/cards`（删卡片：软删除到 `_trash/` + 级联清理反哺候选，2026-09-13 缺口批）**、**`PATCH /learn/cards/topic`（改主题：移文件 + 双主题 README 同步，2026-09-13）**、**`POST /learn/cards/repages`（历史卡回填页序列：用 `_raw/` 素材重排「一页一单元」，只补呈现层，2026-09-15）**、`PATCH /learn/cards/status`（复习状态流转 new/review/done，V2）、`PATCH /learn/cards`（正文编辑补丁，?type=&title= 定位，V2）、`POST /learn/cards/candidate`（trading 反哺候选，V2 批 3）、`GET /learn/cards/candidates`（候选列表）、`DELETE /learn/cards/candidates`（?title= 删除候选）、`GET/PUT /learn/push-settings[/learn-review]`（复习提醒开关，S-learn2）。全需 learn 插件（403）。
- **复习状态（V2 2026-09-07）**：`status` new→review→done，流转只改 frontmatter（正文/手写复述段原样保留，File First 不重建文件）。
- **编辑与复述（V2 2026-09-07）**：`retell` 复述段建模（V1 模板四段的空段补齐读写对称——24h 内自己写 100-200 字是消化关键，AI 不代写）；`PATCH /learn/cards` 支持正文/要点/疑问/复述/trade_related/trade_note/tags 字段级补丁（type/title/created 不可改——改=移动文件拒绝，改名走新建）。「对话流让阿呆改」的后端能力就绪（前端接线随 UI 批）。
- **trading 反哺候选（V2 批 3 2026-09-07）**：trading + trade_related 卡片经 `POST /learn/cards/candidate` 反哺 → 候选落 `data/{userId}/trading/candidates/`（建议卡 + `learn_card_id` 回链 learn 源卡——只存提炼建议不复制整卡，跨域无双写，守 B6）；候选**不自动入库**，需在交易知识库工作流审核后融合归正式目录并重建 knowledge/context（对齐复盘 promote 哲学 + 规则改动人工闸，P1-交易9 教训）；`GET /learn/cards/candidates` 列表审核、`DELETE /learn/cards/candidates?title=` 删除（幂等）。
- **复习提醒推送（V2 批 4 + S-learn1 修复 2026-09-07）**：每晚 20:00（cron 配置 `adai.learn.review-cron`）LearnReviewPushService 遍历启用 learn 插件的用户 → 聚合「status=review 且 **review_at（进入复习队列日）** ≥ 7 天未 done」的卡片（跨 ai/trading/other）→ 推一条汇总（type=learn-review，标题「学习复习提醒」，PushChannel 渠道化进 Feed/Bark；不自动改状态）；**同卡 7 天内不重复推**（reminded_at 节流，防搁置卡每晚 nag）；推送类型 `learn-review` 入 PushSettings.ALL_TYPES（默认开；**learn 侧 `GET/PUT /learn/push-settings` 可关——纯 learn 用户也能自关，不再只藏交易设置页**，S-learn2）；Feed push 注入按事件类型级门控（learn-review 条目只需 learn 插件、交易类条目需 trading 插件——防残留交易 push 漏给纯 learn 用户）。
- **抓取与转写边界（2026-09-12 完整升级后口径）**：**已做**服务端抓取（**B站视频 + 通用文章 + 微博 + 微信公众号 + 今日头条**，域名白名单内）、**图片源**（书页/PPT/讲义/截图 → 视觉模型忠实提取，与链接同一条流水线）、云端转写（fun-asr，费用闸 + 用户确认前置）、**对话流入口**（对话里说「整理 <链接>」走同一流水线；「打开那篇」按关键词找卡并回全文）；**不做** 知乎 / 小红书 / X / 抖音 / YouTube 的自动抓取（每条都有实测依据，见下条）——一律人话告知并引导用户粘正文/截图，不绕付费墙与登录墙；不做批量/后台自动转写（只在你明确发话时花钱）。**字幕优先的实际可达性（2026-09-12 实测）**：未登录时 B站 字幕接口一律返回空（连试 6 个视频 `player/v2` 的 `subtitle.subtitles` 全空），所以「先找免费字幕、找不到才转写」里的前半段默认走不到——要让省钱路径真正生效需配 `ADAI_BILIBILI_COOKIE`（可选，等于把 B站 登录态放服务器上，按需开启）；否则视频内容基本都需要付费转写，或用户直接把字幕/正文粘进来（零成本路径）。原「素材由用户喂入」表述作废（那是 B 形态的做法，已证明不可用）。- **其余已知边界（L1）**：**喂入双端页面入口（2026-09-10 提交式：web 资产页「＋」弹窗 + app「最近学习」页头「＋」）**——app 可随手粘贴素材消化（移动端主场），卡片编辑/复习流转仍在 web（app 只读呈现，双端分工）；trading 候选联动 V2 已落地（promote 融合仍走 trading-engine 工作流人工闸）；对话流喂入（阿呆对话里说「整理 <链接>」/「打开那篇」）已于 2026-09-12 完整升级批接线（双端）；A 技能产物与插件产物**同目录同契约**（产品卡 `origin: product` 可写、技能卡只读）。
- **平台抓取放开（2026-09-13）：纠正三条基于错误假设的保守判断** —— **微博 / 微信公众号 / 今日头条从「要登录，抓不了」改为已支持**。生产实测表明三者都免登录可读，只是各有各的请求头要求：**微博**要移动端 XHR 头（缺 `X-Requested-With` → 302 到访客系统、看着像「要登录」；缺 `Referer` → 403 errno 100015）；**公众号**要伪装 UA（换 `curl` UA 立刻返回验证码页——旧结论很可能就是当年用默认 UA 试了一次得出的）；**头条**要走移动版 `m.toutiao.com`（PC 站是 JS VM 反爬页，移动版服务端渲染）。那三条「人话拒绝」曾长期存在且**看起来像安全边界**，实际只是把用户挡在门外。**仍不做**（各有实测依据）：知乎（403 + `zh-zse-ck` JS 挑战）、抖音（内容接口需 `a_bogus + timestamp + x-secsdk-web-signature` 三件套签名；短链能解析出 ID 但拿不到内容，注册游客 ttwid 的流行偏方实测无效）、小红书（`xsec_token` 必需且会过期）、X / YouTube（大陆直连全超时，连 `web.archive.org` 也不可达）。
- **同批修掉一个静默失效（值得记住的形状）**：`ArticleFetcher` 的 Web Archive 快照兜底**一直在空转**——`archive.org` 在大陆服务器 8 秒超时（DNS 返回 `2001::1` 污染地址），于是「每次抓取失败都白等 8 秒、然后再失败」。它看起来是一条兜底，实际只贡献延迟。现改为**默认关闭**（`adai.learn.fetch.wayback-api` 显式配置才启用，配境外中转时才有意义）。**教训**：兜底路径也要有实测，否则「有兜底」本身会掩盖「兜底不可用」。
- **抓取器的结构（2026-09-13 起）**：出站纪律（逐跳白名单 / 手工跟跳转 / 退避重试 / 响应体上限）收敛为 `HopFetch`，由 B站 / 微博 / 公众号 / 头条 / 通用文章五个抓取器共用——避免「出站安全」出现五份各自漂移的实现；顺序为 B站(`@Order 0`) → 微博(10) → 公众号(20) → 头条(30) → **通用文章(100，兜底)**。微博 URL id → mid 的 62 进制换算落在 `WeiboMid`，用公开算法的**已知样本对**钉死字母表顺序（`0-9a-zA-Z`，不是常见的 `0-9A-Za-z`——写错会得到「看着合理但完全错误」的 mid，从现象上会一路伪装成「平台反爬」）。

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
| 17 | GET | `/api/v1/todos` | 待办列表（Kernel builtin，`?status=OPEN\|DONE`；RFC 20260917） | ❌ |
| 18 | POST | `/api/v1/todos` | 创建待办（Kernel builtin） | ❌ |
| 19 | PUT | `/api/v1/todos/{id}` | 更新待办（`null` 保持原值；`due:""` 清除到期日） | ❌ |
| 20 | DELETE | `/api/v1/todos/{id}` | 删除待办（取消即删除，204） | ❌ |
| 21 | GET | `/api/v1/todos/stats` | 待办统计（total / open / done） | ❌ |
| 22 | GET | `/api/v1/trading/positions` | 持仓 | ❌ |
| 23 | GET | `/api/v1/trading/portfolio` | 组合快照 | ❌ |
| 24 | POST | `/api/v1/trading/trades` | 录入交易 | ❌ |
| 25 | POST | `/api/v1/trading/review` | 生成复盘 | ✅ |
| 26 | GET | `/api/v1/trading/review` | 查询复盘 | ❌ |
| 27 | GET | `/api/v1/trading/reviews` | 复盘日期列表 | ❌ |
| 28 | GET | `/api/v1/trading/has-activity` | 交易活跃检测 | ❌ |
| 29 | POST | `/api/v1/trading/reviews/{date}/promote` | 知识反哺 | ✅ |
| 30 | POST | `/api/v1/records/retry` | 手动触发补完（RecordRetryService） | ✅ |
| 31 | GET | `/api/v1/trading/knowledge/conflicts` | 规则矛盾检测 | ✅ |
| 32 | POST | `/api/v1/cards/migrate` | 卡片迁移 | ❌ |
| 33 | POST | `/api/v1/cards/cleanup` | 卡片清理 | ❌ |
| 34 | POST | `/api/v1/records/media` | 图片记录（multipart → VLM 理解） | ✅ |
| 35 | GET | `/api/v1/records/media/{id}` | 取回原图 | ✅ |
| 36 | GET / POST | `/api/v1/accounts` | 账号查询/创建（adai-admin，需登录 + role=admin） | ✅ |
| 37 | DELETE | `/api/v1/accounts/{userId}` | 删除账号（adai-admin，需登录 + role=admin） | ✅ |
| 38 | GET | `/api/v1/admin/**` | 数据/系统/知识管理（adai-admin，需登录 + role=admin） | ✅ |
| 39 | GET | `/api/v1/accounts/available` | 启用账号列表（需登录，最小集 userId；产品端遗留选号） | ✅ |
| 40 | GET | `/api/v1/me/plugins` | 当前用户启用插件（需登录，前端模块显隐） | ✅ |
| 41 | POST | `/api/v1/records/media/ask-batch` | 多图问答（Phase 1 带图 ask，1-3 张一次提问） | ✅ |
| 42 | POST | `/api/v1/learn/cards` | learn 喂入消化（learn 插件，2026-09-06 learn V1） | ✅ |
| 43 | GET | `/api/v1/learn/cards` | learn 卡片列表（learn 插件，?type= 筛选） | ❌ |
| 44 | GET | `/api/v1/learn/card` | learn 单篇卡片全文（learn 插件，?type=&title=） | ❌ |
| 45 | GET | `/api/v1/learn/tree` | learn 资产树（learn 插件，ai/trading/other 分组） | ❌ |
| 46 | PATCH | `/api/v1/learn/cards/status` | learn 复习状态流转 new/review/done（learn 插件，2026-09-07 V2） | ❌ |
| 47 | PATCH | `/api/v1/learn/cards` | learn 卡片正文编辑补丁（learn 插件，?type=&title=，2026-09-07 V2） | ❌ |
| 48 | POST | `/api/v1/learn/cards/candidate` | learn trading 卡片反哺成规则候选（learn 插件，2026-09-07 V2 批 3） | ❌ |
| 49 | GET | `/api/v1/learn/cards/candidates` | learn 反哺候选列表（learn 插件，created 倒序） | ❌ |
| 50 | DELETE | `/api/v1/learn/cards/candidates` | learn 删除反哺候选（learn 插件，?title= 幂等；同名歧义 400） | ❌ |
| 51 | GET | `/api/v1/learn/push-settings` | learn 复习提醒开关读（learn 插件，S-learn2 2026-09-07） | ✅ |
| 52 | PUT | `/api/v1/learn/push-settings/learn-review` | learn 复习提醒开关写（learn 插件，S-learn2） | ✅ |
| 53 | GET | `/api/v1/learn/digest/status` | learn 消化任务状态（learn 插件，2026-09-10 提交式喂入配套：idle/running/done{type,title}/failed{message}） | ❌ |
| 54 | GET | `/api/v1/learn/content` | learn 卡片全文（md 原文，含手工卡独有段；learn 插件，2026-09-12 完整升级批） | ❌ |
| 55 | GET | `/api/v1/learn/find` | learn 找卡片（「打开那篇」+ 学习页搜索，纯规则打分；learn 插件，2026-09-12） | ❌ |
| 56 | POST | `/api/v1/learn/digest/image` | learn 图片喂入（书页/PPT/截图 1~3 张 → 视觉提取 → 同一流水线；learn 插件，2026-09-12） | ✅ |
| 57 | POST | `/api/v1/learn/migrate` | learn 老式扁平卡一次性迁移到主题目录（幂等；learn 插件，2026-09-12） | ❌ |
| 58 | DELETE | `/api/v1/learn/cards` | learn 删卡片（**软删除**到 `_trash/` + 级联清理反哺候选；只读卡拒绝；learn 插件，2026-09-13） | ❌ |
| 59 | PATCH | `/api/v1/learn/cards/topic` | learn 改主题（移文件 + 双主题 README 同步，新主题内续号；只读卡拒绝；learn 插件，2026-09-13） | ❌ |

---

## 18. 推送设备与 APNs 渠道（RFC 20260913）

**定位**：把「阿呆的消息送到你的手机」打通。**推送内容**由各推送生产方决定（时段/买点/止损/行情异动/收盘小结/复习提醒，共 10 类），本节只管**推到哪台设备**与**链路通不通**。

### 与既有推送开关的分工

| 端点 | 管什么 |
|:-----|:-------|
| `GET/PUT /api/v1/trading/push-settings` | 「哪些**类型**要推」（用户偏好开关，逐类型） |
| `GET/POST/DELETE /api/v1/push/devices` | 「推到**哪台设备**」（通道与目标） |

两者正交，互不覆盖。

### 渠道模型

`PushChannel`（kernel 端口）→ 推送生产方注入 `List<PushChannel>` 遍历所有 `enabled()` 渠道扇出。现有渠道：

| 渠道 | 说明 |
|:-----|:-----|
| `feed` | App 内 Feed（永远开启）——**不打开 App 就等于没推送** |
| `bark` | 第三方 App Bark（生产已配 key；APNs 上线后可留作冗余或退役） |
| `apns` | **阿呆自己**（本批新增）：直连 APNs，通知上是阿呆的名字、点击回到阿呆 |
| `wechat` | Server酱，已停用（免费 5 条/天不够），保留代码未配置即禁用 |

### 后端处理

- **凭据**：APNs Auth Key（`.p8`，ES256）。**不随年过期**、sandbox+production 两套网关通用。配置 `ADAI_PUSH_APNS_KEY_PATH` + `ADAI_PUSH_APNS_KEY_ID`（+ `TEAM_ID`/`BUNDLE_ID`/`TYPES`）。未配 → `enabled()=false` 静默跳过，Feed 与其它渠道不受影响。
- **JWT**：`{alg:ES256, kid:keyId}` + `{iss:teamId, iat}`，缓存 40 分钟（Apple 要求 20~60 分钟内刷新且不得快于 20 分钟）。
- **API 调用**：HTTP/2 `POST /3/device/{token}`，头 `apns-topic` / `apns-push-type: alert` / `apns-priority: 10` / `apns-expiration`（+1 小时，手机离线时 APNs 代为存储；0 = 不存储会丢）。
- **负载**：`{"aps":{"alert":{title,body},"sound":"default","thread-id":"adai-<type>"}}` —— 用 Jackson 序列化（不手拼 JSON：本项目吃过「LLM 多行正文裸换行 → 服务端 400 丢弃」的事故）+ 同类推送归组。
- **环境分流**：deviceToken 分属 sandbox / production 两套互不相通的网关，`environment` **跟着 token 存**；送错只回 `BadDeviceToken` 静默丢弃，故日志记录 reason + 处置提示。
- **失败策略**：一律不抛（外部网关抖动不得打断推送生产方的定时任务）；`410 Unregistered` → 自动清理设备登记。
- **落盘**：`data/{userId}/push/devices.json`（per-user 条带锁原子写；损坏文件**读路径降级**、**写路径拒绝写回**，防覆盖丢设备）。
- **灰度**：`adai.push.apns.types` 逗号白名单，留空 = 全量。

### iOS 端

- `ios/Runner/Runner.entitlements`：`aps-environment=development`（沙箱，对应 development 签名；上架时 Xcode 自动改写为 production）。
- `Runner.xcodeproj`：Runner target **三个构建配置**（Debug/Release/Profile）都挂 `CODE_SIGN_ENTITLEMENTS`。
- `AppDelegate.swift`：`UNUserNotificationCenter` delegate（复用 FlutterAppDelegate 既有的协议遵循，方法加 `override`）；注册远程通知；token/失败/点击三个回调经 MethodChannel `adai/push` 交给 Dart；**前台也弹横幅**；点击通知 → Dart 切回 Feed 并刷新。
- `PushService`（Dart）：仅 iOS 原生生效（`supported` 守卫），非 iOS 平台（Web / Android）降级为 unavailable；登录后 init（申请权限 → 取 token → 上报）；登出注销本机设备；权限被拒时给「去开启」引导。

### 验证方法（配完 .p8 后）

1. `GET /api/v1/push/status` → `channels[].name=apns` 且 `enabled=true`、`deviceCount≥1`；
2. App 打开一次（登录态）→ `GET /api/v1/push/devices` 应出现本机 token（`environment=sandbox`）；
3. 白名单只留 `close-summary,learn-review` 时，触发一次复习提醒（或等 15:30 收盘小结）→ 手机上应出现**阿呆自己的**通知；
4. 反向验证：`TYPES` 白名单外的类型（如止损）不应有通知，但 Feed 里照常看得到（渠道隔离）。

---

> **安全口径（2026-09-14 晚间批）**：①**`adai://` 是无凭据入口**——任意 App/网页都能 `openURL` 触发，因此「记一笔」保持直达（低摩擦是它的全部价值），而**「整理」在提交前多一次点击确认**（它会去抓原文、可能需要花转写额度）；②入口**不丢件**：UserDefaults/AppDelegate/Dart 侧三处单槽改**队列**，冷启动连发两条都会落；队列**绑定 userId**、登出清空（防多账号串号）；③3 秒时间窗去重命中时**留日志**（不再静默吞掉合法重复）。

## 19. 外部入口（Siri / 快捷指令 / `adai://`，RFC 20260913 + 2026-09-13 整理动作批）

**定位**：把阿呆的核心动作搬到 App 外面——**记录**（把一句话记下来）与**整理**（把一个链接读明白），
都从「解锁 → 找 App → 点开 → 打字」降到**一句话 / 一次分享点击**。
这是付费开发者账号解锁的第二项能力（App Intents，iOS 16+）。

### 系统分享：分享面板里的阿呆阿呆（RFC 20260914，2026-09-14）

**这是「看到就丢」的正解**：B站/抖音点分享 → 分享面板**第一排**出现「阿呆阿呆」→ 点一下 →
**扩展在自己的进程里**择出链接、用限权令牌提交 `/api/v1/learn/digest` → 显示「已交给阿呆，
正在读…」约 1 秒自动关闭。**主 App 全程不被拉起。**

三条入口的差别（前两条做到了「少说话」，只有这条做到「不打断」）：

| 入口 | 分享面板里有「阿呆阿呆」 | 会跳 App | 要先配吗 |
|:-----|:---:|:---:|:---|
| **系统分享**（Share Extension，2026-09-14） | ✅ 有 | ❌ **不跳** | ❌ 不用 |
| 快捷指令 | ❌ 显示的是「快捷指令」 | ✅ 跳 | ✅ 要配四条动作 |
| `adai://digest` | ❌ | ✅ 跳 | ✅ 得有别的东西调它 |

**凭据怎么过去的**：扩展是**独立进程、独立沙箱**，读不到 App 的存储；而令牌明文只在签发响应里
出现一次（后端只存哈希），过了那一刻谁也还原不出来 → 只能由 App 在**签发成功那一刻**顺手写进
App Groups 共享容器（`group.com.adaiadai.adaiApp`）。所以「把分享接到阿呆」这一页在 iOS 上多了
一步自动搬运：**用户零操作**（2026-09-14 拍板）。用的是同一类 `learn:digest` 限权令牌，**绝不是
登录会话**——扩展读不到记录、交易、记忆。

**撤销也跟着管**：撤销的若正是扩展在用的那把（按 `id` 比对）→ 同步清空容器；判不准（旧后端按
前缀撤销 / 容器里没记 id）→ **保守清空**（宁可让用户重新点一次「发一把」，也不留一把可能已失效的钥匙）。

**实现位置**：

| 层 | 文件 | 职责 |
|:---|:-----|:-----|
| Extension | `ios/ShareExtension/`（`ShareViewController.swift` · `ShareAuth.swift` · `Info.plist`） | 收分享内容（URL / 网页 URL / 纯文本）→ 择链接 → `URLSession` POST → 人话结果 → 自动关；`viewDidDisappear` 补 `cancelRequest`（防扩展内存泄漏崩溃） |
| 凭据桥 | `ios/Runner/ShareBridge.swift` | App Groups 写入 / 清除 / 查状态（**回读校验**，不靠「调用没报错」下结论） |
| 通道 | `ios/Runner/AppDelegate.swift` | `adai/share` 通道注册 |
| Dart | `lib/services/share_extension_service.dart` | 平台守卫（**仅 iOS 原生**）、三个方法、失败返回安全默认值 |
| 接线 | `lib/widgets/share_token_dialog.dart` | 签发成功→写入；撤销→按 id 清空；iOS 显示「分享面板已就绪 / 还差一步 / 还没接上」三态 |

**已验证 / 待验证（如实）**：
- ✅ 单测：Dart 侧 9 项（签发写入 · 三态文案 · 非 iOS 整段不出现 · 撤销清空 · 不误伤其它把 · 假就绪 · 登出清容器）。
- ✅ 构建：`flutter build ios --release` 产物含 `PlugIns/ShareExtension.appex`，扩展与主 App 同挂 App Groups。
- ✅ **真机实测通过（2026-09-15）**：B站分享 → 面板「更多」里的「阿呆阿呆」→ 后端日志
  13:49:24 签发令牌 → 13:50:12 抓取完成（`BV1CrKA6YETu`，613s，无字幕走转写）→ 13:50:53 卡片落盘
  `learn/ai/harness-engineering/03-八种Agent设计模式：从ReAct到自主循环.md`。
  **顺带证实 fail-visible 有效**：没在 App 里点过「给我一把钥匙」时，扩展如实说「阿呆还没拿到钥匙」，
  而不是静默失败或谎报「已交给阿呆」。
- ℹ️ **「第一排」不由我们决定**：系统按激活规则 + 使用习惯排序，新装的扩展常先落在「更多」里，
  用过一次会自己浮上来。激活规则已同时开 WebURL / WebPage / Text（只开 URL 的话，分享纯文本的
  App 里**根本不会出现**这个图标）。

### 分享之后的回话（2026-09-23，REVIEW P1-分享8）

**问题不是「没做」，是「做了没说」**。2026-09-23 用户原话「我刚才通过微博分享了两次到阿呆，
没反应呀」——而生产日志显示两次都成功了（两次 `POST /learn/digest` 200、两次抓取落卡、`learn/`
下多出两张卡）。缺的是一句回话：扩展提交完 **1 秒就关窗**、主 App **全程不被拉起**，而这中间
后端的 `done` 结果原只保留 **60 秒**——用户走到学习页时早过期，后端已回 `idle`，**「我整理好了」
这句话根本没有机会说出口**。

三处一起补，缺一不可：

| 环节 | 改法 | 为什么不能省 |
|:-----|:-----|:-----|
| **后端** | `done` 结果保留 60s → **30 分钟**（与 `failed` 同档） | 决定「这句话还有没有机会说」——用户从微博切到阿呆、进学习页，往往超过 60 秒 |
| **App 学习页** | 进页查一次任务态，`done` 就在列表顶部摆「**你刚分享的那条，我整理好了《标题》**」+ 点开直达卡片 | 扩展那一秒之后，这是唯一一句「我读完了」；卡片标题由 AI 起，不给用户**指不出是哪一条** |
| **分享扩展** | 收到 `status=done` 改说「**这条我早整理过了**」（不再是「交出去了」） | 新入队一律回 `pending`，回 `done` 只可能来自去重命中——如实说，别让人以为又出了一张新卡 |

**顺带治掉重复卡**：`POST /learn/digest` 现在按**来源链接**去重（去空白、去尾斜杠后逐字比对）——
同一条内容再分享一次，**不抓取、不烧模型、不建任务**，直接以已有卡片回执。这不是可选的优化：
**看不到反馈的用户会再分享一次**（2026-09-23 那条微博就被分享了两次，落出 `02-`/`03-` 两张同源卡），
两个缺陷会互相放大。**素材（粘贴正文）路径不参与去重**——正文没有可比的来源链接，而用户可能
有意重做一遍（换类型、补来源）。

> **已知边界（如实）**：去重只认「链接完全相同」。同一内容的不同分享形态（带不带 tracking 参数、
> 短链 vs 原始链接、不同平台的转发链）仍会各落一张卡；要做这一层得引入「抓取后按 mid 判重」，
> 那要求先抓一次（视频还会先付转写费），去重的意义就没了。

### 分享之后的追踪：从「丢进去」到「读好了」（2026-09-23，REVIEW P1-分享9）

用户原话：「我还没办法追踪呀……我看不到任何追踪信息，比如我分享了什么到阿呆，目前分别是什么情况了，
是否完成了呢」。查证结果：他 23:06:05 分享的那篇公众号 **19 秒就跑完了**——缺的从来不是执行，是**过程可见性**。

**与「分享之后的回话」（上节）的边界**：上节修的是「**完成后**那一句话」；本节补的是「**从分享那一刻到完成**
的整段可见性」——包括**处理中**（抓取/读图/转写/整理）、**多条分别如何**、**失败的那条为什么**。

**一份账，两处呈现**（同一个数据源，不会各说各话）：

| 层 | 做什么 |
|:---|:-------|
| **后端 · 账** | 每次提交落一条记录到 `data/{userId}/learn/_digest-tasks.json`（滚动最近 **50** 条）：**提交 / 抓到源 / 等确认 / 完成 / 失败 / 取消六个节点各写一次**——内存任务态几分钟就过期，账不会（重启不丢、失败也留痕） |
| **后端 · 端点** | `GET /learn/digest/jobs`：「我分享过哪些、分别什么情况、成了没有」 |
| **Feed** | 注入 `type=digest` 条目，**主页就有回话**：「你给我的这篇，我正在读」→「读好了《…》」／「这篇我没读成」。不必去学习页找 |
| **学习页** | 顶部**「整理进度」区**：进行中的全部 + 最近 3 条结局；`读好了` 的那条**点一下直达卡片** |

**两条 fail-open 纪律**（都是刻意的、与配额账本 fail-closed 相反）：

- **写账失败只记 WARN，不抛**——记不上账最多是「这条查不到踪迹」，而把**已经落盘的整理报成失败**才是真事故；
- **受理失败不入账**——抢占任务位失败的那条（同账号同时只能跑一个任务）根本没被受理，写进账里就是假账：
  用户会看到「我分享了两条」，而其中一条从来不会有人处理。

**刻意不做（用户 2026-09-23 拍板「暂时不用，单任务够」）**：不做**排队**——单任务约束不变，
第二条分享仍会被拒且如实告知（扩展说「我正在读上一条，这条没排上」）。

### 两个动作（不是两条入口）

「入口」是从哪来，「动作」是要做什么。两者分开之后，**新增入口不再需要改原生侧结构**——
`ExternalEntryAction` 是一张表（`ExternalEntry.swift`），加一个入口 = 加一个枚举值 + Dart 侧一个分支。

| 动作 | 语义 | 入口 | 冷启动可靠 |
|:-----|:-----|:-----|:----------:|
| **记一笔** | 把这句话记下来（内容即终点） | Siri「用阿呆」· 快捷指令/聚焦搜索的「阿呆阿呆」动作 · `adai://record?text=…` | ✅ |
| **整理** | 去把这个链接读明白（抓取 → 按需转写 → 结构化学习卡） | 快捷指令的「阿呆阿呆整理」动作 · `adai://digest?url=…` | ✅（cold+warm 两条都接） |

> **为什么「整理」必须独立成一个动作（2026-09-13 真机实测的失败）**：此前它只能靠 Dart 侧的关键词正则去猜
> （含 http 链接 **且** 含「整理/消化/学习留存/归档」才算整理）。用户在快捷指令里把共享表单的链接
> 直接接进「记一笔」动作 → 文本里**没有触发词** → **静默落成一条普通记录，并没有整理**。
> 让「整理」成为一个动作，这类「少说两个字就走错分支」的失败就不再可能——**动作不该靠自然语言去猜**。

### 行为约定

- **记一笔 · 有内容 → 直接落成记录**：语音输入的意图已经明确（你亲口说的），再要求摸手机点一次发送等于把 Siri 的价值抵消掉；
  记录照常出现在 Feed，是一张普通卡，**可见、可删**（既有能力）。
- **记一笔 · 空内容 → 不猜**：只把输入框准备好并聚焦，等你自己写（例如只说了「打开阿呆」）。
- **整理 → 直达 learn 流水线**：不经过关键词判定，也**不受「正在对话中不抢话」限制**——
  后者是为**插话**设计的约束，而这是明确动作，没有抢话问题。但**保留 learn 插件门控**
  （插件没开时先给人话）；插件状态**查不到 ≠ 没开**：只在确知关闭时拦，查不到就照常提交、由后端兜底。
  分享文本里夹着口令也能择出链接（抖音那种「8.88 复制打开抖音…https://v.douyin.com/x」）。
  分享内容里没有链接 → 人话告知，**不发请求、也不退化成一条记录**。
- **认不出的动作 → 既不认领也不回落**：未知 action 一律丢弃（原生 `handle(url:)` 返回 false、
  Dart `fromNative` 返回 null）。回落成「记一笔」会把「整理」悄悄变成「记一条」，正是本批要根除的失败模式。
- **未登录时**：入口会攒着（原生侧不消费），登录进主界面后自然被消费。

### 实现位置

| 层 | 文件 | 职责 |
|:---|:-----|:-----|
| App Intent | `ios/Runner/RecordIntent.swift` | `RecordIntent`（带「内容」参数、`openAppWhenRun`）+ `AdaiAppShortcuts`（免配置 Siri 短语） |
| App Intent | `ios/Runner/DigestIntent.swift` | `DigestIntent`（带「链接」参数；**参数用 String 不用 URL 类型**——分享出来常是夹着链接的口令文本，URL 强校验会直接拒收。**刻意不进 `AdaiAppShortcuts`**：用嘴念 URL 不现实，加进去只会给 Siri 添噪声） |
| 投递桥 | `ios/Runner/ExternalEntry.swift` | `ExternalEntryAction` **动作表**（record/digest）；落 UserDefaults + 同进程通知**双路径**（冷启动引擎未就绪也不丢）；`adai://record`·`adai://digest` 解析（digest 主参数 `url`，`text` 作别名）；drain 即清空 |
| 生命周期 | `ios/Runner/SceneDelegate.swift` | `openURLContexts`（warm）+ `willConnectTo` 的 `connectionOptions.urlContexts`（cold），**两条都调 `super`** |
| 通道 | `ios/Runner/AppDelegate.swift` | `adai/entry` 通道（`onEntry` 推送 + `takePendingEntry` 兜底取） |
| Dart | `lib/services/entry_intent_service.dart` | 平台守卫（仅 iOS 原生）、解析、消费一次 |
| 消费 | `lib/main_page.dart` | `_consumeExternalEntry` → 有文本走 `_onSend`，无文本只预填 |

### 凭据：给外部工具的是一把「可收回的钥匙」，不是登录密码

快捷指令的 token 是**明文写在 plist 里**的，且 `.shortcut` 文件本身会被分享出去——因此**绝不用登录会话**，另立一类凭据（`ApiToken`，`adai_` 前缀，2026-09-13 外部工具令牌批）：

| 方面 | 做法 |
|:---|:---|
| 签发 | `POST /api/v1/auth/tokens`（需会话）→ 明文**只返回这一次**；落盘只存 SHA-256 哈希 |
| 限权 | `TokenScope` 白名单 + **精确匹配**（`learn:digest` = 提交整理 / 确认转写 / 查状态 / 查额度四条）。**刻意不做前缀匹配**——前缀匹配会让将来新增的子端点自动获得权限，那是一次不改代码就发生的权限扩张 |
| 边界 | 任何 scope 都**不含 `/api/v1/auth/**`** → 外部令牌**无法自造一把权限更大的钥匙**（否则限权形同虚设） |
| 撤销 | `DELETE /api/v1/auth/tokens/{idOrPrefix}`（限定本账号）→ 立即失效，不影响登录会话与其它设备。**优先用 `id`（令牌哈希）**：显示前缀只有 8 位十六进制，同账号碰撞时无法区分是哪一把，前缀**非唯一命中会被拒绝撤销**（2026-09-14 晚间批：旧实现按前缀删会一次删掉两把）|
| 有效期 | **新签发令牌默认 90 天到期**（`expiresAt`；存量老令牌为 `null` = 不过期），到期即失效；列表与签发响应都带 `expiresAt`/`id`，界面显示「有效期至 X」。理由：这把钥匙的明文会躺在**可能被转发出去**的 `.shortcut` 里，不能只靠人记得回来撤（2026-09-14 晚间批）|
| 失效联动 | 改密 / 管理员重置密码 / 禁用 / 删号都会**连带撤销该账号全部外部令牌**，且校验时复核账号仍在且未禁用（fail-closed）——用户「发现不对劲就改密码」必须真的踢得掉这把钥匙（2026-09-14 部署前 P0 修复批）|
| 可辨认 | 带 `label`（用途备注）与 `lastUsedAt`（**节流 5 分钟才写盘**——校验在每个请求上发生，每次回写会引发多轮全局锁 RMW），能看出「这把是谁的、还在用吗」 |
| 通路 | 与登录会话**同一条 `AuthFilter`**：会话不认时再试外部令牌，X-User-Id 覆盖机制共用（外部令牌一律以所属账号行事，伪造 header 无效）。不另开通路——鉴权只能有一个入口 |
| 存储 | `data/accounts/api-tokens.json`：全局锁 + 原子写 + **损坏 fail-fast**（凭证降级成「没有令牌」会让外部工具静默失效且写路径再覆盖损坏文件、令牌永久丢失） |
| 界面 | app 学习页页头 → 「把分享接到阿呆」弹窗：生成 / 复制 / 四步快捷指令配置 / 收回 |

### 已验证 / 待验证

- **⏳ 待真机验证（2026-09-13 整理动作批）**：`DigestIntent` 与 `adai://digest?url=…` 的**编译与单测已过**
  （iOS 包编译 + Dart 侧 8 项），但**真机分享链路尚未实测**——需装机后在 B站/抖音分享面板真实走一次。
  **在此之前不要当它已经能用。**
- **已验证（真机）**：`adai://record?text=…` 的 **warm** 与 **cold** 两条路径都实测落成记录（阿呆自动打标签）；
  同一链接 1 秒内连发两次只落 1 条（去重窗口）；**iOS 会把冷启动的启动 URL 送两遍**——修复前同一次唤起落 2 条，已在入口去重（见 pitfalls 十四）。
- **已验证（单测）**：iOS 包编译通过（含 App Intents 与 `CFBundleURLTypes`）、Dart 侧 12 项（平台守卫/冷启动取/推送投递/消费一次/空内容不猜/未知动作不硬塞/旧版本降级）。
- **已验证（Siri 亲验，2026-09-13）**：口述一句 → 记录落成 + 阿呆理解归类（`domain=life` / 自动摘要 / 4 个标签）+ 生成 `ai_note` 记忆（把这条和 3 天前的深夜进食记录关联起来），且无重复。
