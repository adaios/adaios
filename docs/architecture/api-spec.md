# AdaiOS API 文档

> 前后端接口契约。前端 Flutter、后端 Spring Boot，所有 API 返回 JSON。

**文档版本：v2.9 | 最后更新：2026-07-29**

---

## 变更记录

| 日期 | 版本 | 变更 |
|:----|:----|:------|
| 2026-07-29 | v2.9 | **Feed 分页方向修复**：page 0 从最早条目改为最新条目，优化刷新后新数据可见性 |
| 2026-07-29 | v2.8 | **Feed 分页**：`GET /api/v1/feed` 加 `page`/`size` 参数，移除 `brief`/`earlierCount`，新增 `totalToday`；Brief 独立接口 |
| 2026-07-27 | v2.6 | **移除 DECISION 意图**；意图识别改为纯 AI（无正则兜底，AI 失败抛异常）；新增 `POST /api/v1/cards/cleanup` |
| 2026-07-26 | v2.5 | 任务系统 (5 个 API) + RFC tracking，ProjectStatus.rfcCount→rfcItems[]，前端任务页 |
| 2026-07-25 | v2.3 | 新增交易复盘 API（生成/查询/列表），知识反哺 API（promote/conflicts），简报集成交易检测 |
| 2026-07-25 | v2.2 | 新增 DECISION 意图，Knowledge 集成到 Context Engine |
| 2026-07-24 | v2.1 | Feed 新增 `type: "card"` 带 turns，卡片文件隔离到 `records/cards/`，新增迁移 API |

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
                                  // null = 后端 AI 自动判断
  "cardId": null                  // optional: 会话卡片 ID，有值则视为对话延续
}
```

**Response — 陈述句（intent="log"）**

```json
{
  "intent": "log",
  "recordId": "rec_20260718_143000",
  "content": "今天买了立昂微",
  "tags": ["投资", "半导体"],
  "summary": "建仓了半导体"
}
```

前端行为：→ 展示记录卡片（内容 + 标签 + 底部 `── ask ──`）

**Response — 疑问句（intent="question"）**

```json
{
  "intent": "question",
  "recordId": "rec_20260718_143100",
  "summary": "今天多云转晴，20-28℃…",
  "tags": ["天气", "日常"],
  "rawResponse": "{...}"
}
```

前端行为：→ 展示聊天卡片，激活会话模式

**意图识别逻辑**

```
1. 前端指定 intent → 直接使用 — 支持 "log" / "question"
2. AI 识别意图（ask → QUESTION，其余 → STATEMENT）
3. AI 失败 → 抛异常，不静默降级
```

**domain 判定规则（AI 输出）**

按优先级匹配关键词：
- 指标、K线、持仓、走势、复盘、买入、卖出、仓位 → `trading`
- 任务、进度、bug、需求、RFC、项目、待办、计划 → `project`
- 日常、想法、记录、心情、问题 → `life`
---

## 2. 对话总结（Conversations）

### `POST /api/v1/conversations/end` — 结束对话

**Request Body**

```json
{
  "turns": ["用户说", "AI答", "用户再说"],
  "cardId": "card_143000"
}
```

**Response**

```json
{
  "recordId": "rec_20260718_143200",
  "summary": "讨论了天气，建议带伞出门",
  "tags": ["天气", "出行"]
}
```

---

## 3. Feed 流

### `GET /api/v1/feed` — 获取今日 Feed（分页）

**Query Parameters**

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|:----:|:----:|------|
| `date` | String | 否 | 当天 | 日期 `yyyy-MM-dd` |
| `page` | int | 否 | 0 | 页码，从 0 开始，page 0 = 最新条目 |
| `size` | int | 否 | 5 | 每页条数 |

**Response**

```json
{
  "entries": [
    {
      "type": "card",
      "id": "card_1784902336974",
      "time": "22:12",
      "title": "现在饿了，吃点什么呢",
      "content": "现在饿了，吃点什么呢",
      "tags": [],
      "intent": "question",
      "summary": "饿了推荐了夜宵选择",
      "turns": [
        {"isUser": true,  "text": "现在饿了，吃点什么呢", "time": "22:12"},
        {"isUser": false, "text": "哈哈饿了呀，那得看你想吃啥", "time": "22:12"}
      ]
    },
    {
      "type": "record",
      "id": "rec_...",
      "time": "14:30",
      "title": "标题",
      "content": "内容",
      "tags": ["标签"],
      "intent": "log",
      "summary": "AI摘要",
      "turns": null
    }
  ],
  "totalToday": 28
}
```

> feed 只返回今天的数据，历史数据走时间线（`GET /api/v1/timeline`）。
> 每日摘要单独调用 `GET /api/v1/brief`。

| 字段 | 类型 | 说明 |
|:-----|:-----|:------|
| `type` | String | `record` / `card` / `ai_note` / `push` |
| `time` | String | `HH:mm` 格式（后端已格式化，无小数秒），卡片取首条用户消息时间 |
| `turns` | TurnDto[] | 仅 `type=card` 时有值，卡片对话轮次 |
| `domain` | String | `life` / `trading` / `project` — AI 按关键词规则判定 |
| `totalToday` | int | 今天一共多少条记录（不分页的总数） |

---

## 4. 卡片管理（Cards）

### `POST /api/v1/cards/migrate` — 迁移历史卡片文件

将旧路径 `records/YYYY/MM/DD/xxx.md` 的卡片迁移到 `records/cards/` 子目录。

**Response**

```json
{
  "totalScanned": 25,
  "migrated": 25,
  "failed": 0,
  "migratedFiles": ["旧路径 → 新路径"]
}
```

### `POST /api/v1/cards/cleanup` — 清理卡片冗余记录

删除卡片对话对应的冗余 ContentRecord（卡片内容已存储在 `records/cards/` 下，无需单独保留）。

**Response**

```json
{
  "deleted": 15
}
```

---

## 5. 交易复盘

### `POST /api/v1/trading/review` — 生成交易复盘

AI 基于当日交易记录 + 持仓变化生成复盘笔记，输出写入 `data/trading/reviews/YYYY-MM-DD_review.md`。

**Query Parameters**

| 参数 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:------|
| `date` | String | 否 | 复盘日期 `yyyy-MM-dd`，默认当天 |

**Response**

```json
{
  "date": "2026-07-25",
  "content": "## 2026-07-25 交易复盘\n\n### 1. 今日交易执行情况\n..."
}
```

### `GET /api/v1/trading/review` — 查询复盘笔记

**Query Parameters**：同 POST

**Response**：同 POST（不存在则 404）

### `GET /api/v1/trading/reviews` — 列出所有复盘日期

**Response**

```json
["2026-07-25", "2026-07-24"]
```

### `GET /api/v1/trading/has-activity` — 检测交易活动

**Query Parameters**：`date`，默认当天

**Response**

```json
{ "date": "2026-07-25", "hasActivity": true }
```

---

## 6. 知识反哺

### `POST /api/v1/trading/reviews/{date}/promote` — 提升复盘为入库候选

将复盘笔记中的经验写入 `os/trading-os/99-inbox/`，供用户在 trading-os 工作焦点下审核入库。

**Path Parameters**

| 参数 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:------|
| `date` | String | 是 | 复盘日期 `yyyy-MM-dd` |

**Request Body**

```json
{
  "note": "R33 这次 B1 入场很标准",
  "sections": ["3. 与系统规则对照", "4. 今日教训与心得"]
}
```

**Response**

```json
{
  "status": "ok",
  "path": "/path/to/os/trading-os/99-inbox/review-2026-07-25.md"
}
```

### `GET /api/v1/trading/knowledge/conflicts` — 检测规则矛盾

读取交易系统规则，与当前持仓状态对比，标记可能违反的规则。

**Response**

```json
{
  "conflicts": [
    {
      "rule": "R96 不单吊原则",
      "description": "当前持有 1 个标的。若只有一个，违反四不原则中的不单吊。",
      "category": "仓位"
    }
  ]
}
```

---

## 7. 简报

简报中会自动检测当日是否有交易活动，若有则提醒用户生成复盘。

### `GET /api/v1/brief` — 今日简报

**Response**

```json
{
  "content": "小王晚上好！\n🍜 刚聊过饿了想吃啥\n💧 睡前记得喝水哦"
}
```

---

## 8. 时间线

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

## 9. 记忆

### `GET /api/v1/memory` — 按日期查询记忆

**Query Parameters**

| 参数 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `date` | String | 否 | `yyyy-MM-dd`，默认当天 |

**Response**：`Memory[]`

### `GET /api/v1/memory/record/{recordId}` — 按记录 ID 查询

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

**Query Parameters**

| 参数 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:------|
| `date` | String | 否 | 重建指定日期；不传则重建全部 |

**Response**

```json
{
  "success": 63,
  "failed": 0,
  "total": 63,
  "errors": []
}
```

---

## 10. 用户身份（Identity）

### `GET /api/v1/identity` — 读取个人档案

**Response**

```json
{
  "name": "小王",
  "preferences": {"style": "简洁、直接"},
  "rules": {"confirmation": "交易类操作需确认"},
  "tags": ["投资", "半导体"]
}
```

### `PUT /api/v1/identity` — 更新个人档案

**Request Body**：同 GET Response（全量覆盖）

**Response**：更新后的完整 Identity（200 OK）

---

## 11. 标签

### `GET /api/v1/tags` — 获取所有标签统计

**Response**

```json
{
  "tags": [
    {"name": "半导体", "count": 12, "lastAt": "2026-07-22T10:00:00"}
  ],
  "total": 12,
  "updatedAt": "2026-07-22T12:00:00"
}
```

---

## 12. 搜索

### `GET /api/v1/search?q=xxx` — 全文搜索

**Response**

```json
{
  "results": [
    {
      "id": "rec_...",
      "type": "note",
      "title": "今天买了立昂微",
      "content": "...买了立昂微...",
      "tags": ["投资"],
      "dateTime": "2026-07-22T14:30:00"
    }
  ],
  "total": 1
}
```

---

## 13. 项目状态

### `GET /api/v1/project/status` — 项目状态摘要

返回 AdaiOS 项目的元信息：Kernel 组件、Domain OS 进度、RFC 状态列表等。
**不调用 AI，纯数据聚合，快速响应。**

**Response**

```json
{
  "project": "AdaiOS",
  "architecture": "modular-monolith",
  "kernelComponents": {
    "identity": "done",
    "record": "done",
    "timeline": "done",
    "context": "done",
    "memory": "done",
    "knowledge": "done"
  },
  "domainStatus": {
    "trading": "complete",
    "life": "skeleton",
    "project": "skeleton"
  },
  "rfcItems": [
    {"title": "Context 闭环", "date": "2026-07-18", "status": "implemented"},
    {"title": "双主页设计",  "date": "2026-07-22", "status": "implemented"}
  ],
  "commitCount": 27,
  "apiEndpoints": 21
}
```

| 字段 | 类型 | 说明 |
|:-----|:-----|:------|
| `rfcItems` | RfcItem[] | RFC 状态列表，每项含 title / date / status |
| `rfcItems[].status` | String | `proposed` / `approved` / `implemented` / `deprecated` / `unknown` |
```

---

## 14. 前端卡片交互

### 卡片核心状态

| 字段 | 值 | 含义 |
|:----|:---|:------|
| `mode` | `idle` | 非聊天态 |
| `mode` | `chatting` | 聊天态 |
| `ended` | `true` / `false` | 对话是否已结束 |
| `intent` | `"question"` / `"log"` | 卡片类型 |

### 交互流程

```
list 模式
  ├── record（intent="log"）
  │     └── 点卡片 → 进入聊天模式
  │
  └── card（intent="question"，带 turns）
        └── 点卡片 → 进入聊天模式，可继续提问

chat 模式（全屏）
  ├── 输入 → API → AI 回复 → 继续对话
  └── [end conversation] → POST /conversations/end → 回到 list 模式
```

### 前端 API 映射

| 前端操作 | API 调用 |
|:---------|:---------|
| 新输入（自动意图） | `POST /api/v1/records` `intent: null, cardId: null` |
| 聊天输入 | `POST /api/v1/records` `cardId: "...", intent: "question"` |
| 结束对话 | `POST /api/v1/conversations/end` `cardId: "..."` |
| 加载 Feed | `GET /api/v1/feed` |
| 加载简报 | `GET /api/v1/brief` |
| 生成复盘 | `POST /api/v1/trading/review` |
| 查询复盘 | `GET /api/v1/trading/review?date=` |
| 检测交易活动 | `GET /api/v1/trading/has-activity` |
| 复盘入库候选 | `POST /api/v1/trading/reviews/{date}/promote` |
| 规则冲突检测 | `GET /api/v1/trading/knowledge/conflicts` |
| 加载项目状态 | `GET /api/v1/project/status` |
| 任务列表 | `GET /api/v1/project/tasks` |
| 创建任务 | `POST /api/v1/project/tasks` |
| 更新任务 | `PUT /api/v1/project/tasks/{id}` |
| 删除任务 | `DELETE /api/v1/project/tasks/{id}` |
| 任务统计 | `GET /api/v1/project/tasks/stats` |
| 卡片迁移 | `POST /api/v1/cards/migrate` |
| 卡片清理 | `POST /api/v1/cards/cleanup` |

---

## 15. 项目任务

轻量任务系统，File First 存储于 `data/project/tasks/YYYY/MM.md`。

### 任务模型

| 字段 | 类型 | 说明 |
|:-----|:-----|:------|
| `id` | String | 自动生成的唯一 ID，格式 `task_YYYYMMDD_HHmmss` |
| `title` | String | 任务标题（必填） |
| `description` | String | 任务描述（可选） |
| `status` | String | `TODO` / `DOING` / `DONE` / `CANCELLED` |
| `priority` | String | `P0` / `P1` / `P2` / `P3` (默认 P2) |
| `tags` | String[] | 标签列表（可选） |
| `rfcRef` | String | 关联 RFC 文件名（可选，如 `20260725-layer6`） |
| `createdAt` | String | 创建日期 `yyyy-MM-dd` |
| `updatedAt` | String | 更新日期 `yyyy-MM-dd` |

### `GET /api/v1/project/tasks` — 获取任务列表

**Query Parameters**

| 参数 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:------|
| `status` | String | 否 | 按状态筛选：`TODO` / `DOING` / `DONE` / `CANCELLED` |
| `tag` | String | 否 | 按标签筛选 |

**Response** — `Task[]`

```json
[
  {
    "id": "task_20260726_043000",
    "title": "接入 A 股行情",
    "description": "实现东方财富行情接口",
    "status": "DOING",
    "priority": "P1",
    "tags": ["kernel", "market"],
    "rfcRef": null,
    "createdAt": "2026-07-26",
    "updatedAt": "2026-07-26"
  }
]
```

### `POST /api/v1/project/tasks` — 创建任务

**Request Body**

```json
{
  "title": "接入 A 股行情",
  "description": "实现东方财富行情接口",
  "priority": "P1",
  "tags": ["kernel", "market"],
  "rfcRef": null
}
```

**Response** — 完整的 `Task` 对象（201 Created）

### `PUT /api/v1/project/tasks/{id}` — 更新任务

**Request Body**（所有字段可选，仅传需要更新的字段）

```json
{
  "title": "接入 A 股行情（含缓存）",
  "status": "DOING",
  "priority": "P0"
}
```

**Response** — 更新后的完整 `Task` 对象（200 OK）

### `DELETE /api/v1/project/tasks/{id}` — 删除任务

**Response** — 204 No Content

### `GET /api/v1/project/tasks/stats` — 任务统计

**Response**

```json
{
  "total": 10,
  "todo": 4,
  "doing": 2,
  "done": 3,
  "cancelled": 1
}
```
