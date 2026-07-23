# AdaiOS API 文档

> 前后端接口契约。前端 Flutter、后端 Spring Boot，所有 API 返回 JSON。

---

## 1. 记录（Records）

### `POST /api/v1/records` — 提交记录

单一入口。所有用户输入统一走此接口，后端自动分流。
支持会话卡片：当已有活跃聊天时，`cardId` 传当前卡片 ID，新输入作为对话延续。

**Request Body**

```json
{
  "content": "今天买了立昂微",      // required, 1-10000 字符
  "type": "note",                 // optional, 默认 "note"
  "tags": ["投资", "半导体"],       // optional
  "intent": null,                 // optional: "log" | "question" | null
                                  // null = 后端自动判断
  "cardId": null                  // optional: 会话卡片 ID，有值则视为对话延续
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

前端行为：→ 展示聊天卡片，激活会话模式

**意图识别逻辑**（后端决策链）

```
1. 前端指定 intent → 直接使用（优先级最高）
2. cardId 存在且对应活跃卡片 → 直接视为 QUESTION（对话延续）
3. AI 识别意图
4. AI 失败 → 正则兜底
5. 仍不明确 → STATEMENT
```

---

## 2. 对话总结（Conversations）

### `POST /api/v1/conversations/end` — 结束对话

用户关闭聊天会话时前端调用。后端总结整个对话，保存为记录，更新卡片状态。

**Request Body**

```json
{
  "turns": [
    "今天天气如何呢",
    "今天多云转晴，20-28℃…",
    "那明天呢",
    "明天预计有雨，建议带伞"
  ],
  "cardId": "card_143000"          // optional: 会话卡片 ID（后端同步更新卡片状态）
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

前端行为：→ 卡片切换到 ended 态（显示总结 + 标签）

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
      "type": "record",           // record | ai_note | push
      "id": "rec_...",
      "sourceRecordId": null,
      "title": "标题",
      "content": "内容",
      "tags": ["标签"],
      "time": "14:30",             // HH:mm
      "intent": "log",             // "question" | "log" | null
      "summary": "AI摘要"          // AI生成的摘要（如果有）
    }
  ],
  "earlierCount": 5               // since 之前有多少条（已折叠）
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

### `POST /api/v1/memory/rebuild` — 重建记忆

遍历没有记忆的历史记录，逐个生成 AI 摘要+标签并沉淀为记忆。用于修复因升级/迁移导致的记忆缺失。

**Query Parameters**

| 参数 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `date` | String | 否 | 日期 `yyyy-MM-dd`，只重建该日期的记忆；不传则重建全部 |

**Response**

```json
{
  "success": 63,
  "failed": 0,
  "total": 63,
  "errors": []
}
```

| 字段 | 类型 | 说明 |
|:-----|:-----|:------|
| `success` | int | 成功重建的记录数 |
| `failed` | int | 失败的记录数 |
| `total` | int | 尝试处理的记录总数 |
| `errors` | String[] | 失败记录的 ID 和错误信息 |

---

## 7. 交易（预留）

### `GET /api/v1/trading/positions` — 持仓查询
### `GET /api/v1/trading/portfolio` — 组合概览
### `POST /api/v1/trading/trades` — 手动记录交易

---

## 8. 用户身份（Identity）

### `GET /api/v1/identity` — 读取个人档案

前端身份页加载时调用。返回当前个人档案的完整数据。

**Response**

```json
{
  "name": "阿呆",
  "preferences": {
    "language": "中文",
    "style": "简洁、直接",
    "focus": "半导体、国产替代、成长股投资"
  },
  "rules": {
    "confirmation": "交易类操作需确认",
    "auto": "日常记录可自动处理"
  },
  "tags": ["投资", "半导体", "科技", "个人成长"]
}
```

### `PUT /api/v1/identity` — 更新个人档案

用户编辑身份页点击保存时调用。全量覆盖（前端传完整对象）。

**Request Body**

```json
{
  "name": "阿呆",
  "preferences": {
    "language": "中文",
    "style": "简洁、直接",
    "focus": "半导体、国产替代、成长股投资"
  },
  "rules": {
    "confirmation": "交易类操作需确认",
    "auto": "日常记录可自动处理"
  },
  "tags": ["投资", "半导体", "科技", "个人成长"]
}
```

**Response**

返回更新后的完整 identity（同 GET Response，200 OK）。

**错误响应**

| 状态码 | 场景 |
|:------|:-----|
| 400 | name 为空、tags 为空列表 |
| 500 | 文件写入失败 |

---

## 9. 标签（Tags）

### `GET /api/v1/tags` — 获取所有标签统计

Launcher 页标签云 + 功能行右侧预览使用。

**Response**

```json
{
  "tags": [
    {"name": "半导体", "count": 12, "lastAt": "2026-07-22T10:00:00"},
    {"name": "投资", "count": 8, "lastAt": "2026-07-21T14:30:00"},
    {"name": "天气", "count": 5, "lastAt": "2026-07-22T09:15:00"}
  ],
  "total": 12,
  "updatedAt": "2026-07-22T12:00:00"
}
```

| 字段 | 类型 | 说明 |
|:-----|:-----|:------|
| `tags[].name` | String | 标签名 |
| `tags[].count` | int | 命中记录数 |
| `tags[].lastAt` | String (ISO) | 最后一次使用时间 |
| `total` | int | 标签总数 |
| `updatedAt` | String (ISO) | 索引最后更新时间 |

---

## 10. 搜索（Search）

### `GET /api/v1/search?q=xxx` — 全文搜索

全文搜索个人记录。线性扫描所有 records 文件，匹配标题、正文、标签。

**Request Parameters**

| 参数 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:------|
| `q` | String | 是 | 搜索关键词 |

**Response**

```json
{
  "results": [
    {
      "id": "rec_20260722_143000",
      "type": "note",
      "title": "今天买了立昂微",
      "content": "...买了立昂微，25块建仓半导体龙头...",
      "tags": ["投资", "半导体"],
      "dateTime": "2026-07-22T14:30:00"
    }
  ],
  "total": 1
}
```

| 字段 | 类型 | 说明 |
|:-----|:-----|:------|
| `results[].id` | String | 记录 ID |
| `results[].type` | String | 记录类型 |
| `results[].title` | String | 标题 |
| `results[].content` | String | 匹配片段（前后 30 字） |
| `results[].tags` | String[] | 标签 |
| `results[].dateTime` | String (ISO) | 记录时间 |
| `total` | int | 总结果数 |

---

## 11. 前端卡片交互

### 卡片核心状态

卡片在 `FeedCardData` 中有两个核心状态字段：

| 字段 | 值 | 含义 |
|:----|:---|:------|
| `mode` | `idle` | 非聊天态，显示记录内容或已结束的对话 |
| `mode` | `chatting` | 聊天态，一问一答中 |
| `ended` | `true` / `false` | 对话是否已结束 |
| `intent` | `"question"` / `"log"` | 卡片类型，决定渲染样式 |

### 交互流程

```
list 模式（无活跃聊天）
  │
  ├── 普通记录（intent="log"）
  │     └── 点卡片 → 进入活跃聊天模式（chatting 态）
  │
  └── 已结束的对话（intent="question" + ended=true）
        └── 点卡片 → 进入活跃聊天模式，可继续提问

chat 模式（活跃聊天，全屏）
  │
  ├── 输入文字 → API 提问 → AI 回复 → 继续对话
  │
  └── 点 [end conversation] → POST /conversations/end → 回到 list 模式，卡片显示摘要
```

### 前端 API 映射

| 前端操作 | API 调用 |
|:---------|:---------|
| 新输入（不指定意图） | `POST /api/v1/records` `intent: null, cardId: null` |
| 聊天输入 | `POST /api/v1/records` `cardId: "...", intent: "question"` |
| 点 [end] | `POST /api/v1/conversations/end` `cardId: "..."` |
| 加载 Feed | `GET /api/v1/feed` |
| 加载简报 | `GET /api/v1/brief` |
| 加载时间线 | `GET /api/v1/timeline` |

---

**文档版本：v2.0 | 最后更新：2026-07-20**
