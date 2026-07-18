# AdaiOS API 文档

> 前后端接口契约。前端 Flutter、后端 Spring Boot，所有 API 返回 JSON。

---

## 1. 记录（Records）

### `POST /api/v1/records` — 提交记录

单一入口。所有用户输入统一走此接口，后端自动分流。

**Request Body**

```json
{
  "content": "今天买了立昂微",      // required, 1-10000 字符
  "type": "note",                 // optional, 默认 "note"
  "tags": ["投资", "半导体"],       // optional
  "intent": null                  // optional: "log" | "question" | null
                                  // null = 后端自动判断
}
```

**Response — 陈述句（intent="log"）**

```json
{
  "intent": "log",
  "recordId": "rec_20260718_143000",
  "content": "今天买了立昂微",        // 原内容回传
  "tags": ["投资", "半导体"],        // AI 打标签
  "summary": "25块建仓半导体龙头"     // AI 摘要
}
```

前端行为：→ 展示记录卡片（内容 + 标签 + 底部 `── ask ──`）

**Response — 疑问句（intent="question"）**

```json
{
  "intent": "question",
  "recordId": "rec_20260718_143100",
  "summary": "今天多云转晴，20-28℃…",  // AI 直接回答
  "tags": ["天气", "日常"],
  "rawResponse": "{...}"             // 原始 LLM 回复
}
```

前端行为：→ 展示聊天卡片（一问一答，底部 `[end]`）

**意图识别逻辑**（后端决策链）

```
1. 前端指定 intent → 直接使用（优先级最高）
2. 5 分钟内有 QUESTION 记录 → 会话感知，当前视为 QUESTION
3. 非会话 → DeepSeek 识别意图
4. DeepSeek 失败 → 正则兜底
5. 仍不明确 → STATEMENT
```

---

## 2. 对话总结（Conversations）

### `POST /api/v1/conversations/end` — 结束对话

用户点击 `[end]` 时前端调用。后端总结整个对话，保存为记录。

**Request Body**

```json
{
  "turns": [
    "今天天气如何呢",
    "今天多云转晴，20-28℃…",
    "那明天呢",
    "明天预计有雨，建议带伞"
  ]
}
```

**Response**

```json
{
  "recordId": "rec_20260718_143200",
  "summary": "咨询了最近两天天气，注意防晒和带伞",
  "tags": ["天气", "出行", "防晒"]
}
```

前端行为：→ 卡片切换到 ended 态（绿色边框 + 总结 + 标签 + `── ask ──`）

---

## 3. Feed 流

### `GET /api/v1/feed` — 获取 Feed

**Query Parameters**

| 参数 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `date` | String | 否 | 日期 `yyyy-MM-dd`，默认当天 |
| `since` | String | 否 | ISO 时间戳，此时间之后=当前会话 |

**Response**

```json
{
  "brief": "早上好，今天已有3条记录…",     // 今日简报（Daily Brief）
  "entries": [
    {
      "type": "record",         // record | ai_note | push
      "id": "rec_...",
      "sourceRecordId": null,
      "title": "标题",
      "content": "内容",
      "tags": ["标签"],
      "time": "14:30"           // HH:mm
    }
  ],
  "earlierCount": 5              // since 之前有多少条（已折叠）
}
```

**`since` 会话机制**：每次打开 App 记录 `_openTime`，传为 `since` 参数。后端只返回该时间之后的条目，之前的计数为 `earlierCount`。

---

## 4. 简报

### `GET /api/v1/brief` — 今日简报

每天首次打开 App 时调用。

**Response**

```json
{
  "content": "早上好！昨天有3条记录，关于立昂微和天气…有什么想记下来的吗？"
}
```

---

## 5. 时间线

### `GET /api/v1/timeline` — 时间线查询

**Query Parameters**

| 参数 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `type` | String | 否 | 筛选类型 |
| `limit` | int | 否 | 条数限制，默认 50 |

**Response**

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

---

## 6. 记忆

### `GET /api/v1/memory` — 按日期查询记忆

**Query Parameters**

| 参数 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `date` | String | 否 | 日期 `yyyy-MM-dd`，默认当天 |

**Response**：`Memory[]`

### `GET /api/v1/memory/record/{recordId}` — 按记录 ID 查询

**Path Parameters**

| 参数 | 说明 |
|------|------|
| `recordId` | 记录 ID |

**Response**：`Memory` 或 `404`

```json
{
  "id": "mem_20260718_143000",
  "recordId": "rec_20260718_143000",
  "summary": "AI摘要",
  "tags": ["标签"],
  "sentiment": "positive",
  "actionable": false,
  "suggestion": null,
  "createdAt": "2026-07-18T14:30:00"
}
```

---

## 7. 交易（预留）

### `GET /api/v1/trading/positions` — 持仓查询
### `GET /api/v1/trading/portfolio` — 组合概览
### `POST /api/v1/trading/trades` — 手动记录交易

---

## 前端 FeedCard 状态机

```
                        ┌─────────────────────────────┐
                        │         用户输入              │
                        └─────────────┬───────────────┘
                                      │
                        ┌─────────────┴─────────────┐
                        │      POST /api/v1/records  │
                        └─────────────┬───────────────┘
                                      │
                    ┌─────────────────┴──────────────────┐
                    │          后端返回 JSON               │
                    │                                    │
              intent="log"                         intent="question"
                    │                                    │
                    ▼                                    ▼
        ┌──────────────────────┐           ┌──────────────────────────┐
        │    idle 态            │           │   chatting 态            │
        │  ── 内容 ──            │           │  ── 你/AI一问一答 ──      │
        │  ✓ 摘要 (10字以内)    │           │  ... loading 跳动点      │
        │  [标签]               │           │  底部 [end]              │
        │  灰色边框             │           │  灰色边框 + 左侧绿线     │
        │  底部 ──── ask ────   │           │  底部直角 无底部边框      │
        └──────────┬───────────┘           └──────────┬───────────────┘
                   │ 点 ask                            │ 点 end
                   ▼                                    ▼
        ┌──────────────────────┐           ┌──────────────────────────┐
        │  waiting 态           │           │   ended 态               │
        │  (瞬间过渡)            │  ──→     │  ✓ 对话总结               │
        │  输入框 placeholder   │           │  [标签]                  │
        │  = "ask your question"│           │  绿色边框 + 标签 + 总结   │
        │  输入框自动聚焦        │           │  底部 ──── ask ────      │
        └──────────────────────┘           └──────────┬───────────────┘
                                                       │ 点 ask → 回到 waiting
```

---

## 前端 API 映射

| 前端操作 | API 调用 |
|:---------|:---------|
| 新输入（不指定意图） | `POST /api/v1/records` `intent: null` |
| 点 [ask] → 用户输入 | `POST /api/v1/records` `intent: "question"` |
| 点 [end] | `POST /api/v1/conversations/end` |
| 加载 Feed | `GET /api/v1/feed` |
| 加载简报 | `GET /api/v1/brief` |
| 加载时间线 | `GET /api/v1/timeline` |

---

**文档版本：v1.0 | 最后更新：2026-07-18**
