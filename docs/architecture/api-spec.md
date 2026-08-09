# AdaiOS API 文档

> 前后端接口契约。前端 Flutter、后端 Spring Boot，所有 API 返回 JSON。

**文档版本：v3.8 | 最后更新：2026-08-09**

---

## 变更记录

| 日期 | 版本 | 变更 |
|:----|:----|:------|
| 2026-08-09 | v3.8 | **多账号前端选号 + 契约对齐**：新增 §16 `GET /accounts/available`（无鉴权选号）/ portfolio `positionCount` 派生字段（#106）/ Feed 分页 page0 完整核心 + 卡片时间基准 `updatedAt`（#175）/ `X-User-Id` 默认说明更新（v1.0.0 起前端必须携带所选账号）|
| 2026-08-06 | v3.7 | **行情异动主动推送（Phase 2）**：FeedEntry 新增 `type=push`（止损预警/放飞提示/跌破成本线，`MarketAlertService` 交易时段轮询落盘 `data/{userId}/trading/pushes/{date}.json`，阈值可配 `adai.market.alert.*`）|
| 2026-08-06 | v3.6 | **管理端点鉴权（REVIEW #127）**：§账号、§管理端全部端点要求 `X-Admin-Token` 请求头（配置 `ADAI_ADMIN_TOKEN`，缺失 401 / 未配置 503 fail-closed）；CORS 由 `*` 收窄为配置化 origin 白名单（默认 localhost）|
| 2026-08-02 | v3.5 | **多模态图片记录（L4）**：新增 `POST /records/media`（multipart 上传 → GLM 视觉理解 → 记录+记忆）、`GET /records/media/{id}`（原图预览）|
| 2026-08-02 | v3.4 | **多账号功能层 + adai-admin**：新增 §账号（accounts CRUD）、§管理端（admin 文件树/知识浏览）；Memory 新增 `PATCH /memory/{id}` 手动修正 |
| 2026-08-02 | v3.3 | **多账号架构预留**：全 API 支持可选请求头 `X-User-Id`（默认 `default`），数据按用户分层 `data/{userId}/` |
| 2026-08-02 | v3.2 | **记忆进化 Phase 3**：新增 `PATCH /memory/{id}/done`（actionable 闭环完成标记）；Memory 条目新增 kind/topic/superseded/evolvedTo/doneAt 字段 |
| 2026-08-01 | v3.1 | **补全缺失端点**：`DELETE /records/{id}`、`POST /records/retry`、`GET /memory/dates`、`GET /memory/count`、`GET /trading/positions`、`GET /trading/portfolio`、`POST /trading/trades`；§5 改为"交易" |
| 2026-07-31 | v3.0 | **行情数据注入**：ContextEngine 注入大盘指数+持仓实时行情；修复 CHAT 模式未注入上下文 Bug（市场/知识/记忆丢失） |
| 2026-07-29 | v2.9 | **Feed 分页方向修复**：page 0 从最早条目改为最新条目，优化刷新后新数据可见性 |
| 2026-07-29 | v2.8 | **Feed 分页**：`GET /api/v1/feed` 加 `page`/`size` 参数，移除 `brief`/`earlierCount`，新增 `totalToday`；Brief 独立接口 |
| 2026-07-27 | v2.6 | **移除 DECISION 意图**；意图识别改为纯 AI（无正则兜底，AI 失败抛异常）；新增 `POST /api/v1/cards/cleanup` |
| 2026-07-26 | v2.5 | 任务系统 (5 个 API) + RFC tracking，ProjectStatus.rfcCount→rfcItems[]，前端任务页 |
| 2026-07-25 | v2.3 | 新增交易复盘 API（生成/查询/列表），知识反哺 API（promote/conflicts），简报集成交易检测 |
| 2026-07-25 | v2.2 | 新增 DECISION 意图，Knowledge 集成到 Context Engine |
| 2026-07-24 | v2.1 | Feed 新增 `type: "card"` 带 turns，卡片文件隔离到 `records/cards/`，新增迁移 API |

---

## 0. 通用请求头（多账号预留）

> v3.3 起，**所有** API 支持可选请求头 `X-User-Id`，按用户隔离数据。

| Header | 类型 | 必填 | 默认 | 说明 |
|:-------|:-----|:----:|:----:|:-----|
| `X-User-Id` | String | 否 | `default` | 用户标识，数据路径按 `data/{userId}/` 分层隔离。**v1.0.0 起前端必须携带所选账号**（选号/切换所得 userId）；后端兜底值 `default` 仅用于测试/兼容，本机真实账号为 `data/adai/`（default 已迁移移除，省略请求会落到空分支）|

**约束**：`userId` 仅允许 `[a-zA-Z0-9_-]+`（后端校验，防路径注入）。不合法的 userId 返回 400。

> 本期仅**架构预留**：不做登录/注册/账号管理，账号由后台管理系统维护（v1.0.0 功能层）。

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
  "rawResponse": "今天多云转晴，20-28℃，适合出行…"
}
```

前端行为：→ 展示聊天卡片，激活会话模式

> `rawResponse` 为 AI 自然语言回复，**已剥离 JSON 元数据**（2026-08-07，#13/#11：实时显示与刷新后一致，card 文件不混入游离 JSON）；自然语言为空时回退 `summary`。

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

### `DELETE /api/v1/records/{id}` — 删除记录

同时清理两个仓库（`rec_` 文件可能在 `records/` 或 `cards/` 目录）+ 关联 Memory。

**Response**

- `204 No Content` — 删除成功（清理 record + card + memory 关联）
- `404` — 不存在也返回 204（幂等）

### `PATCH /api/v1/records/{id}/domain` — 修改记录所属领域

**Request Body**

```json
{ "domain": "trading" }
```

**Response**

- `204 No Content` — 修改成功
- `400` — domain 非法（仅 `life` / `trading` / `project`）

### `POST /api/v1/records/retry` — 手动触发重补

调用 `RecordRetryService`，为没有 Memory 的历史记录补齐 AI 摘要与标签。

**Response**

```json
{
  "status": "ok",
  "memoriesBefore": 2,
  "memoriesAfter": 15,
  "newMemories": 13
}
```

### `POST /api/v1/records/media` — 上传图片记录（多模态 L4）

`multipart/form-data`：图片 → 存原图（`records/{yyyy}/{MM}/media/{id}.{ext}`）→ GLM 视觉模型理解 → 沉淀 ContentRecord（type=image）+ Memory。

**Request（multipart）**

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `file` | 二进制 | ✅ | 图片（jpeg/png/webp/gif，≤5MB）|
| `caption` | String | 否 | 用户备注（VLM 理解失败时降级为记录内容）|
| Header `X-User-Id` | String | 否 | 用户 ID（默认 `default`）|

**Response 200**

```json
{
  "recordId": "rec_20260802_143200123",
  "intent": "log",
  "summary": "持仓截图：浦发银行",
  "tags": ["交易", "持仓"],
  "mediaPath": "records/2026/08/media/rec_20260802_143200123.png"
}
```

- `400` — 非图片或超 5MB

### `GET /api/v1/records/media/{id}` — 取回原图（预览）

**Response 200** — 图片字节流（Content-Type 按扩展名：jpeg/png/webp/gif）

- `404` — 无此媒体文件

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
| `page` | int | 否 | 0 | 页码，从 0 开始，page 0 = 最新条目（REVIEW #175：返回完整 `size` 条核心，余数放末页）|
| `size` | int | 否 | 5 | 每页条数 |

**Response**

```json
{
  "entries": [
    {
      "type": "card",
      "id": "card_1784902336974",
      "time": "22:12",
      "date": "08-03",
      "mediaPath": null,
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
      "date": "08-03",
      "mediaPath": "records/2026/08/media/rec_20260803_143200123.jpg",
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
> **时间基准（updatedAt）**：卡片（`type=card`）的 `time`/`date` 按最后更新时间 `updatedAt`，跨日续接的对话归最后活跃日；`findTodayCards` 按 `updatedAt` 过滤。分页（REVIEW #175）：核心条目（record/card）按时间从新到旧切块，page 0 返回完整 `size` 条最新核心，余数放末页；附加条目（ai_note/action/market/push）只在 page 0 末尾附加。

| 字段 | 类型 | 说明 |
|:-----|:-----|:------|
| `type` | String | `record` / `card` / `ai_note` / `action`（未完成行动提醒，Phase 3）/ `market`（大盘行情，v0.2.0）/ `push`（行情异动主动推送，Phase 2：止损预警/放飞提示/跌破成本线）|
| `time` | String | `HH:mm` 格式（后端已格式化，无小数秒），卡片取首条用户消息时间 |
| `date` | String | `MM-dd` 格式，条目所属日期（每张卡片都带日期，前端展示）|
| `mediaPath` | String? | 图片记录才有：媒体文件相对路径（前端据此渲染原图/缩略图，GET `/api/v1/records/media/{id}` 取文件）；其余类型为 `null` |
| `turns` | TurnDto[] | 仅 `type=card` 时有值，卡片对话轮次 |
| `domain` | String | `life` / `trading` / `project` — AI 按关键词规则判定 |
| `totalToday` | int | **核心输入条数**（record/card，不含 ai_note/action/market/push 附加）；分页终止基准 |

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

## 5. 交易

### `GET /api/v1/trading/positions` — 查询持仓

返回当前持仓列表。价格为实时行情（MarketDataSource 注入）。

**Response**

```json
[
  {
    "symbol": "600123",
    "name": "立昂微",
    "quantity": 200,
    "avgCost": 25.30,
    "currentPrice": 25.30,
    "lastUpdated": "2026-08-01"
  }
]
```

### `GET /api/v1/trading/portfolio` — 查询投资组合快照

返回持仓列表 + 汇总（总市值/总成本/总盈亏/现金/持仓数）。字段与 `PortfolioSnapshot` 对齐（REVIEW #106：原示例含不存在的 `totalMarketValue`/`totalPnlPercent`，已修正）。

**Response**

```json
{
  "positions": [
    {
      "symbol": "600123",
      "name": "立昂微",
      "quantity": 200,
      "avgCost": 25.30,
      "currentPrice": 26.10,
      "marketValue": 5220.00,
      "pnl": 160.00,
      "pnlPercent": 3.16,
      "lastUpdated": "2026-08-07T09:00:00"
    }
  ],
  "totalPnl": 160.00,
  "totalCost": 5060.00,
  "totalValue": 5220.00,
  "cashBalance": 2000.00,
  "snapshotTime": "2026-08-07T09:00:00",
  "positionCount": 1
}
```

### `POST /api/v1/trading/trades` — 记录一笔交易

**Request Body**

```json
{
  "symbol": "600123",
  "name": "立昂微",
  "direction": "BUY",
  "price": 25.30,
  "volume": 100
}
```

**Response**：`Position[]` — 更新后的全部持仓

---

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

从 `os/trading-os/11-context/rules.md` 解析真实规则，与当前持仓状态对比，标记可能违反的规则（规则名/描述取自真实规则内容，非硬编码）。

**Response**

```json
{
  "conflicts": [
    {
      "rule": "R119 空仓也是交易策略",
      "description": "当前无持仓。规则：仓位0到100，0也是交易。确认当前空仓是否符合择时信号（活跃市值绿柱下降期空仓 = 正确执行 R4）。",
      "category": "择时"
    },
    {
      "rule": "R96 四不原则",
      "description": "当前仅持有 1 个标的（xxx）。规则：不追 → 不动 → 不慌 → 不乱摸。若未分仓，检查是否违反四不原则。",
      "category": "仓位"
    }
  ]
}
```

> 无持仓 → 空仓检查（R119/R4）；仅持 1 个标的 → 单吊检查（R96 四不原则）。rules.md 不可读时返回空 conflicts。

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
    "dateTime": "2026-07-18T14:30:00",
    "mediaPath": null
  },
  {
    "id": "rec_...",
    "type": "image",
    "title": "图片摘要",
    "tags": ["photo"],
    "dateTime": "2026-08-03T09:15:00",
    "mediaPath": "records/2026/08/media/rec_20260803_091500123.jpg"
  }
]
```

> `mediaPath`：图片记录（`type=image`）才有，指向媒体文件相对路径（GET `/api/v1/records/media/{id}` 取文件）；其余类型为 `null`。

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
  "kind": "insight",
  "summary": "AI摘要",
  "tags": ["标签"],
  "sentiment": "positive",
  "actionable": false,
  "suggestion": null,
  "createdAt": "2026-07-18T14:30:00",
  "topic": null,
  "superseded": false,
  "evolvedTo": null,
  "doneAt": null,
  "lastConfirmed": null
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

### `GET /api/v1/memory/dates` — 查询所有有记忆的日期

**Response**

```json
["2026-07-18", "2026-07-20", "2026-07-23"]
```

### `GET /api/v1/memory/count` — 记忆总数

**Response**

```json
{ "count": 15 }
```

### `PATCH /api/v1/memory/{id}/done` — 标记行动类记忆为已完成

记忆进化 Phase 3（Reality→Knowledge→Action→Reality 闭环）：将 actionable 记忆标记为已完成（`actionable=false` + 记录完成时间）。完成后不再出现在"待行动事项"与 Feed 待办提醒。

**Path Parameters**

| 参数 | 类型 | 说明 |
|:-----|:-----|:-----|
| `id` | String | 记忆 id（`mem_xxx`）|

**Response**

```json
{ "success": true }
```

- `404 Not Found` — 记忆不存在

### `PATCH /api/v1/memory/{id}` — 手动修正记忆（adai-admin）

adai-admin 数据管理：更新记忆的 kind/summary/tags/actionable/suggestion。任一字段缺省表示保持原值。

**Request Body**

```json
{
  "kind": "insight",
  "summary": "修正后的摘要",
  "tags": ["半导体", "交易"],
  "actionable": false,
  "suggestion": null
}
```

**Response**

```json
{ "success": true }
```

- `404 Not Found` — 记忆不存在

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

---

## 16. 账号（多账号功能层）

> v1.0.0 多账号：账号由 adai-admin 后台创建（**不做注册**），adai-app / adai-web 前端从可用账号列表选择进入（`GET /api/v1/accounts/available`，**无鉴权**，仅返回 enabled 账号）；前端记住上次账号（web 用 localStorage / io 用 shared_preferences，wasm 下 shared_preferences 插件不注册）+ 随时切换。seed 管理员 `adai` 由后端首次启动自动预置。
>
> **管理鉴权（REVIEW #127）**：本节除 `GET /api/v1/accounts/available`（产品端选号，无鉴权）外，其余端点与 §17 管理端所有端点要求请求头 `X-Admin-Token`（值 = 后端配置 `ADAI_ADMIN_TOKEN`）。缺失或不匹配 → `401`；服务端未配置令牌 → fail-closed `503`（防生产误部署裸奔）。adai-admin 前端通过 `--dart-define=ADMIN_TOKEN=<令牌>` 注入，与后端一致。

### `GET /api/v1/accounts` — 账号列表

**Response**

```json
[
  {
    "userId": "adai",
    "role": "admin",
    "enabled": true,
    "createdAt": "2026-08-02"
  }
]
```

### `GET /api/v1/accounts/available` — 可用账号列表（产品端选号）

> **无鉴权**（WebConfig 从 AdminAuthInterceptor 拦截范围 exclude）——adai-app / adai-web 首屏选号与切换调用。仅返回 `enabled=true` 的账号。

**Response**

```json
[
  { "userId": "adai", "role": "admin", "enabled": true, "createdAt": "2026-08-02" }
]
```

- 空列表 → `200 []`（前端展示「去 adai-admin 创建账号」空态）

### `POST /api/v1/accounts` — 建号（adai-admin 后台）

**Request Body**

```json
{ "userId": "alice", "role": "user" }
```

- `role` 可选，默认 `user`（`admin` / `user`）
- `400` — userId 已存在 / 格式非法（仅 `[a-zA-Z0-9_-]+`）/ role 非法

### `PATCH /api/v1/accounts/{userId}` — 更新账号

**Request Body**

```json
{ "enabled": false }
```

- `enabled` / `role` 均可选，缺省保持原值
- **内置管理员 `adai` 不可禁用、不可降级**（400）
- `404` — 账号不存在

### `DELETE /api/v1/accounts/{userId}` — 删除账号

- **内置管理员 `adai` 不可删除**（400）
- `204` — 删除成功；`404` — 账号不存在

---

## 17. 管理端（adai-admin）

> 系统级浏览端点（读取 `data/` 全部用户层 + `os/` 知识库），**不走 `X-User-Id` 用户层**，仅供 adai-admin 使用。路径一律防目录遍历（`normalize + startsWith` 校验）。
>
> **鉴权**：全部端点要求 `X-Admin-Token` 请求头（同 §16，REVIEW #127）。

### `GET /api/v1/admin/files?path=` — data/ 目录浏览

**Query Parameters**

| 参数 | 类型 | 必填 | 默认 | 说明 |
|:-----|:-----|:----:|:----:|:-----|
| `path` | String | 否 | 空（data/ 根）| 相对 data/ 的目录路径，如 `default/records` |

**Response** — 目录条目数组

```json
[
  { "name": "records", "path": "records", "isDir": true },
  { "name": "notes.md", "path": "notes.md", "isDir": false, "size": 123 }
]
```

- `404` — 目录不存在

### `GET /api/v1/admin/files/content?path=` — data/ 文件内容

**Response**

```json
{ "path": "notes.md", "size": 123, "content": "文件内容" }
```

- `404` — 文件不存在；`400` — 文件 >512KB 或路径非法

### `GET /api/v1/admin/knowledge?domain=&path=` — os/ 知识资产浏览

**Query Parameters**

| 参数 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:-----|
| `domain` | String | 是 | `trading-os` / `life-os` / `project-os`（白名单校验）|
| `path` | String | 否 | 相对 os/ 的目录路径，如 `trading-os/11-context` |

**Response** — 同 `/admin/files` 条目数组

### `GET /api/v1/admin/knowledge/content?path=` — os/ 文件内容

**Response** — 同 `/admin/files/content`
