# AdaiOS API 文档

> 前后端接口契约。前端 Flutter、后端 Spring Boot，所有 API 返回 JSON。

**文档版本：v3.22 | 最后更新：2026-08-17**

---

## 变更记录

| 日期 | 版本 | 变更 |
|:----|:----|:------|
| 2026-08-15 | v3.20 | **合并插件端点（S-R2）**：新增 `PATCH /accounts/{userId}/plugins`（body `{add[], remove[]}`，服务端账号级锁原子合并——根治前端全量 PATCH read-modify-write 并发互覆）；内置管理员插件受保护（400）|
| 2026-08-15 | v3.19 | **展示层聚合（S-2 图文一体）**：`mediaPath` 语义扩展——`type=image_qa` 记录（带图 ask 聚合后的图文事件）也返回媒体路径（引用首图，前端渲染缩略图）；`type=image` 原语义不变；多轮 chat 时间线按会话聚合为单条（记录层不变，仅展示口径）|
| 2026-08-15 | v3.18 | **Domain=插件模型（RFC 20260814 第二步，插件门控）**：新增 `GET /me/plugins`（当前用户启用插件，前端模块显隐）；Account 新增 `plugins` 字段（`POST` 建号可选 / `PATCH` 可改，仅 `trading`/`project`，非法 400）；domain 判定规则按用户启用插件收敛（D5：无插件用户只判 `life`，AI 判定若属未启用插件 → 收敛 `life`）；`POST /trading/reviews/{date}/promote` 仅启用 trading 插件用户可用（否则 403）；Feed 行情条/异动推送仅注入启用 trading 插件用户；**R2 D1 通用化**：记录自动转任务去 domain 门槛（任何可执行记录即转，`sourceRecordId` 不再限 domain=project）|
| 2026-08-14 | v3.17 | **Phase 1 带图 ask（多图问答）**：新增 `POST /records/media/ask-batch`（已上传 1-3 张图片一次提问 → VLM 综合多图回答 → `image_qa` 记录引用全部图片 ID + Q/A 追加首图卡）；intent 分流与文本记录一致（`IntentRecognizer` 判定，问句 → VLM 多图回答 / 陈述 → 纯记录；AI 失败降级问号启发式）；图片数量上限 3 张 |
| 2026-08-13 | v3.16 | **R2 记录↔任务关联**：任务模型新增可选 `sourceRecordId`（domain=project 记录自动转任务时关联源记录 `rec_xxx`）；非破坏性字段新增，前端手动建任务为 null |
| 2026-08-12 | v3.15 | **正文与 changelog 对齐（REVIEW #238）**：`POST /records/media` 错误列表 400（非图片）/ 413（超限）拆分；`POST /records/media/{id}/ask` 补「问题超过 500 字符 → 400」（v3.12 已声明，正文同步）|
| 2026-08-12 | v3.14 | **收官批 O（#166/#170/#202/#231/#122 等）**：AI 交互日志响应新增 `systemPrompt` 字段（generate 的复盘模板指令，understand/intent 为 null，#231）；上传超限改 413（`MaxUploadSizeExceededException` → PAYLOAD_TOO_LARGE，原 500，#166）；`/accounts/available` 契约补充无鉴权说明（#215 已最小集，此条再确认）；待办建议 prompt 改第二人称（#170）；复盘生成剥代码块围栏（#202）|
| 2026-08-12 | v3.13 | **REVIEW #129/#218/#222**：promote 前端入口说明（交易页复盘弹窗「反哺入库」按钮，`POST` body 传 `{}`）；AI 交互日志视觉调用补真实耗时（`LoggingVisualAiClient.durationMs`）；Brief 问候加中午段（11-13 → 中午好，#222）|
| 2026-08-12 | v3.12 | **REVIEW #214/#215/#221**：`POST /records/media/{id}/ask` 的 `question` 加长度上界（500 字符，超限 400）；`GET /accounts/available` 响应由账号对象改为 **userId 最小集**（`List<String>`，不暴露 role/enabled/createdAt）；Brief 降级问候 emoji 按时段（#221） |
| 2026-08-12 | v3.11 | **AI 日志隐私治理（REVIEW #210）**：`GET /admin/ai-logs` 新增 `page`/`size`（上限 500，响应带 `total`）；`date` 早于保留期（`adai.ai-log.retention-days` 默认 30 天）返回 400（已清理不可查）|
| 2026-08-12 | v3.10 | **R1 AI 交互日志契约登记**：新增 §17 `GET /admin/ai-logs?userId=&date=`（X-Admin-Token 鉴权，读 `data/{userId}/ai-logs/YYYY/MM/ai-log-{date}.jsonl`）；图片追问持久化（`POST /records/media/{id}/ask` 追问 Q/A 追加进图片卡 card 文件，Feed 图片记录 entry 带 turns）|
| 2026-08-11 | v3.9 | **图片追问（L4 图片问答）**：新增 `POST /records/media/{id}/ask`（图+问题 → GLM 自然语言回答 → 沉淀 `image_qa` 记录）；管理端点 CORS 预检修复（`OPTIONS` 放行，8082/8083 可正常访问 admin/accounts）|
| 2026-08-09 | v3.8 | **多账号前端选号 + 契约对齐**：新增 §16 `GET /accounts/available`（无鉴权选号）/ portfolio `positionCount` 派生字段（#106）/ Feed 分页 page0 完整核心 + 卡片时间基准 `updatedAt`（#175）/ `X-User-Id` 默认说明更新（v1.0.0 起前端必须携带所选账号）|
| 2026-08-06 | v3.7 | **行情异动主动推送（Phase 2）**：FeedEntry 新增 `type=push`（止损预警/放飞提示/跌破成本线/真止损 R66（现价跌破止损位，2026-08-16），`MarketAlertService` 交易时段轮询落盘 `data/{userId}/trading/pushes/{date}.json`，阈值可配 `adai.market.alert.*`）|
| 2026-08-06 | v3.6 | **管理端点鉴权（REVIEW #127）**：§账号、§管理端全部端点要求 `X-Admin-Token` 请求头（配置 `ADAI_ADMIN_TOKEN`，缺失 401 / 未配置 503 fail-closed）；CORS 由 `*` 收窄为配置化 origin 白名单（默认 localhost）|
| 2026-08-02 | v3.5 | **多模态图片记录（L4）**：新增 `POST /records/media`（multipart 上传 → GLM 视觉理解 → 记录+记忆）、`GET /records/media/{id}`（原图预览）|
| 2026-08-02 | v3.4 | **多账号功能层 + adai-admin**：新增 §账号（accounts CRUD）、§管理端（admin 文件树/知识浏览）；Memory 新增 `PATCH /memory/{id}` 手动修正 |
| 2026-08-02 | v3.3 | **多账号架构预留**：全 API 支持可选请求头 `X-User-Id`（默认 `default`），数据按用户分层 `data/{userId}/` |
| 2026-08-02 | v3.2 | **记忆进化 Phase 3**：新增 `PATCH /memory/{id}/done`（actionable 闭环完成标记）；Memory 条目新增 kind/topic/superseded/evolvedTo/doneAt 字段 |
| 2026-08-17 | v3.22 | **交易 A-E 全部端点**：`/trading/account`（账户快照，含 principal/totalPnl）、`/trading/transfer` + `/trading/transfers`（银证转账净投入）、`/trading/imports/cash`（资金查询）、`/trading/imports/save`（文件留存）、`/trading/buy-points`（买点信号）、`/trading/sold/score`（复盘三维打分）、`PUT /trading/positions/{symbol}`（持仓编辑）——合计 15 端点 + 修订（手续费费率、account 语义、示例修正）|
| 2026-08-16 | v3.21 | **P-be-01 安全修复**：5 个维护端点（records/retry、memory/rebuild、memory/{id} PATCH、cards/cleanup、knowledge/conflicts）从 per-user 路径迁入 `/api/v1/admin/**`（需 X-Admin-Token），目标用户改 userId 查询参数；`GET /trading/has-activity` 保留产品路径（app 复盘横幅，只读）|
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

**domain 判定规则（AI 输出，RFC 20260814 D5）**

按优先级匹配关键词，**只在用户已启用插件间判定**（无对应插件 → 该关键词不判该域）：
- 指标、K线、持仓、走势、复盘、买入、卖出、仓位 → `trading`（需启用 trading 插件）
- 任务、进度、bug、需求、RFC、项目、待办、计划 → `project`（需启用 project 插件）
- 日常、想法、记录、心情、问题 → `life`

> 无插件用户一律 `life`（单一 domain）。即使 AI 输出 `trading`/`project`，若该用户未启用对应插件，后端也会收敛为 `life`（`PluginService.gateDomain`）。插件名见 §16 `GET /me/plugins`。

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

### `POST /api/v1/admin/records/retry` — 手动触发重补（需 X-Admin-Token）

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

- `400` — 非图片（非 jpeg/png/webp/gif）
- `413` — 超过大小上限（`spring.servlet.multipart.max-file-size`，默认 5MB；REVIEW #166/#238 由 400 拆分，v3.14 同步）

### `GET /api/v1/records/media/{id}` — 取回原图（预览）

**Response 200** — 图片字节流（Content-Type 按扩展名：jpeg/png/webp/gif）

- `404` — 无此媒体文件

### `POST /api/v1/records/media/{id}/ask` — 图片追问（L4 图片问答）

就一张已记录的图片提问：重新取原图字节 → GLM 视觉模型自然语言回答 → 沉淀 `image_qa` 记录（时间线/搜索可见，content 含图片记录 ID 溯源）。前端图片卡底部 `── 提问 ──` 进入追问。

**Request Body**

```json
{ "question": "这是什么股票？" }
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `question` | String | ✅ | 用户对图片的追问（非空）|
| Header `X-User-Id` | String | 否 | 用户 ID（默认 `default`）|

**Response 200**

```json
{
  "recordId": "rec_20260811_143200456",
  "answer": "这是浦发银行，持仓约 1000 股。",
  "imageRecordId": "rec_20260811_091500123"
}
```

- `400` — 问题为空 / 问题超过 500 字符（REVIEW #214，防超大 prompt/记录/日志行）/ 图片记录不存在 / 图片文件缺失

### `POST /api/v1/records/media/ask-batch` — 多图问答（Phase 1 带图 ask，2026-08-14）

对已上传的 1-3 张图片一次提问：VLM 综合多图回答（一次请求看全部图）→ 沉淀 `image_qa` 记录（content 引用全部图片 ID）+ Q/A 追加到首图卡 card 文件（Feed 刷新后首图卡显示问答气泡）。前端输入栏附图 + 文本，逐张上传完成后调用。

**intent 分流（与文本记录「入口统一，后台分流」一致）**：Controller 用 `IntentRecognizer` 判定附带的文本——问句（`question`）→ VLM 多图回答；陈述（`log`）→ 图片已在逐张上传时以 caption 记录，直接返回不调 VLM。AI 判定失败降级问号启发式（文本以 ？/? 结尾）。

**Request Body**

```json
{ "imageRecordIds": ["rec_..", "rec_.."], "question": "这两张图分别是什么？" }
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `imageRecordIds` | String[] | ✅ | 已上传的图片记录 ID（1-3 张，上限 Phase 1 拍板）|
| `question` | String | ✅ | 附图文本（后端按此判定 question/log 分流）|
| Header `X-User-Id` | String | 否 | 用户 ID（默认 `default`）|

**Response 200（question 分支）**

```json
{
  "intent": "question",
  "answer": "左图是持仓截图，右图是分时走势。",
  "recordId": "rec_20260814_143200456",
  "imageRecordIds": ["rec_..", "rec_.."]
}
```

**Response 200（log 分支）**

```json
{ "intent": "log", "imageRecordIds": ["rec_..", "rec_.."] }
```

- `400` — 图片为空 / 超过 3 张 / 问题为空 / 问题超过 500 字符 / 图片记录不存在
- 注：多图问答 `image_qa` 记录 content 格式 `【多图问答】图片记录：a, b … / 问：… / 答：…`（单图追问为 `【图片问答】`）

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
> **插件门控（RFC 20260814）**：`market`（行情条）与 `push`（异动推送）条目仅注入启用 **trading 插件** 的用户；无插件用户 Feed 无行情卡。

| 字段 | 类型 | 说明 |
|:-----|:-----|:------|
| `type` | String | `record` / `card` / `ai_note` / `action`（未完成行动提醒，Phase 3）/ `market`（大盘行情，v0.2.0）/ `push`（行情异动主动推送，Phase 2：止损预警/放飞提示/跌破成本线/真止损 R66（2026-08-16））|
| `time` | String | `HH:mm` 格式（后端已格式化，无小数秒），卡片取首条用户消息时间 |
| `date` | String | `MM-dd` 格式，条目所属日期（每张卡片都带日期，前端展示）|
| `mediaPath` | String? | 媒体记录才有：`type=image`（图片记录原图）与 `type=image_qa`（S-2 展示层聚合：图文事件缩略图取引用首图）——媒体文件相对路径（GET `/api/v1/records/media/{id}` 取文件）；其余类型为 `null` |
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

### `POST /api/v1/admin/cards/cleanup` — 清理卡片冗余记录（需 X-Admin-Token）

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
  "volume": 100,
  "entryDate": "2026-08-16",
  "stopLossPrice": 24.50,
  "buyPoint": "B1",
  "targetPrice": 30.00,
  "reason": "回踩支撑买入"
}
```

> `name` **可选**（≤32 字符，RFC 20260815）：缺省时后端以 symbol 兜底。`direction` 必填（BUY/SELL），`price`/`volume` 必须 > 0（`@Positive`）。
> **RFC 20260816（数据分层）**：`entryDate` 可空缺省今天；**BUY 必填 `stopLossPrice` + `buyPoint`**（缺失 → 400 人话消息）；`targetPrice`/`reason` 可选（SELL 时止损/买点可空）。recordTrade 成功后**同步写逐笔流水**（`data/{userId}/trading/trades/{yyyy-MM}.json`）+ **写一条 domain=trading 记录**（5 分钟窗口去重）——交易进 timeline/记忆 + 复盘提醒闭环。

**Response**：`Position[]` — 更新后的全部持仓

---

### `GET /api/v1/trading/trades` — 查询交易逐笔流水（RFC 20260816）

**Query Parameters**

| 参数 | 类型 | 必填 | 说明 |
|:-----|:-----|:----:|:------|
| `from` | String | 否 | 起始日期 `yyyy-MM-dd` |
| `to` | String | 否 | 结束日期 `yyyy-MM-dd` |

**Response**：`TradeRecord[]` — 逐笔流水（按 timestamp 倒序）

```json
[{
  "id": "trade_1723700000000", "symbol": "000725", "name": "京东方A",
  "direction": "BUY", "price": 5.2, "volume": 1000, "amount": 5200.0,
  "entryDate": "2026-08-16", "stopLossPrice": 4.9, "buyPoint": "B1",
  "targetPrice": null, "reason": null, "fee": null,
  "timestamp": "2026-08-16T09:30:00", "sourceRecordId": "rec_..."
}]
```

---

### `GET /api/v1/trading/lookup` — 按代码查询名称（代码输入带出 + 二次确认，2026-08-16）

| 参数 | 类型 | 说明 |
|:-----|:-----|:-----|
| `symbol` | String | 六位股票代码 |

**响应**：`{"symbol":"000725","name":"京东方A"}`（name 查询失败为空串，前端可手填）。需 trading 插件（403）。

### `GET /api/v1/trading/watchlist` — 自选股列表（RFC 20260816 交易数据智能）

返回自选条目（symbol/name/industry/industry2/longForm/midForm/shortForm/signal/addedAt，通达信形态与指标提示为买点判定原料）。需 trading 插件（403）。

### `POST /api/v1/trading/watchlist/import` — 自选股导入（通达信导出文本）

**body**：`{"content":"通达信自选导出文本（GBK 已转码）"}`。表头定位列（代码/名称/细分行业/一二级行业/长期/中期/短期形态/近日指标提示），按 symbol upsert。**响应**：`{"imported":27}`。

### `DELETE /api/v1/trading/watchlist/{symbol}` — 删除自选股

### `GET /api/v1/trading/buy-points` — 自选股买点信号（C2 盯盘买点，2026-08-16）

对全部自选股拉 K 线（东财主源 → 腾讯降级）→ `BuyPointDetector` 判定 → 命中返回信号列表（**判定是提示不是指令**，买不买人决策）。

**响应**（P1-交易10 修正 2026-08-17：score 是 0-100 分，signals 是 detector 实际文案）：
```json
[{"symbol":"000725","name":"京东方A","buyPoint":"B1","score":87,
  "signals":["回调 52%","缩量 0.6x","KDJ.J=12"]}]
```

- **B1 回调买点**：距前高回调 ≥ 50% + 缩量（3 日均量 < 5 日均量 × 0.7）+ KDJ.J < 20
- **B2 突破买点**：放量（5 日均量 × 1.5）+ 收盘破前 20 日高点
- **参数可配**（默认建议值：回调 0.5 / 缩量 0.7 / KDJ 20 / 放量 1.5 / 前高 20 日，待用户确认）
- 收盘 15:10 定时任务自动扫描 + 命中推送「到买点了」（`TradingSessionPushService.buyPointScan`）；web 自选 Tab 显示信号列
- 需 trading 插件（403）。

### `GET /api/v1/trading/sold` — 清仓股列表（复盘闭环）

返回已了结交易（symbol/name/buyDate/sellDate/holdDays/tradeCount/holdPnlPct/verdict/psychology）。

### `POST /api/v1/trading/sold/import` — 清仓股导入（通达信导出文本）

**body**：`{"content":"..."}`。表头定位列（代码/名称/介入日期/清仓日期/持仓天数/买卖次数/持仓期涨幅%），按 symbol upsert（保留已有 verdict/psychology）。**响应**：`{"imported":42}`。

### `PUT /api/v1/trading/sold/{symbol}/psychology` — 清仓股心理标注

**body**：`{"psychology":"追高后恐慌割肉"}`（用户复盘素材，个人数据隐私保护）。

### `GET /api/v1/trading/sold/score` — 清仓复盘三维打分（D3，2026-08-16）

对全部清仓交易复盘打分（**分数是参考不是指令**，复盘用，买不买永远人决定）。三维：买点（买入日回溯 K 线 → B1/B2 完美图匹配度）/ 执行（verdict 纪律对照）/ 选股（关注后表现，数据积累后返回 null）。

**响应**：
```json
[{"symbol":"600519","name":"贵州茅台","buyPointScore":88,"buyPointSignal":"B1",
  "buyPointExplain":"回调 52%、缩量 0.6x、KDJ.J=12",
  "executionScore":90,"executionExplain":"盈利了结，执行到位",
  "totalScore":89,"verdict":"盈利了结"}]
```

- 买点维度：B2 突破 85-100 / B1 低吸 70-100 / B1? 候选 50 / 无形态（追高）25；买入日超出 K 线回溯范围或回溯 K 线不足 25 根 → `buyPointScore=null`（总分为 null 不糊弄，数据不足不误判追高）
- 执行维度：盈利了结 90 / 其他亏损按纪律 65 / 违反 R53 45 / 违反 R66 15
- 需 trading 插件（403）。

### `POST /api/v1/trading/transfer` — 银证转账（转入/转出，净投入跟踪，2026-08-16）

**body**：`{"type":"IN|OUT","amount":10000,"date":"2026-08-17","note":"补仓"}`

- `type` IN=转入（银行卡→证券）/ OUT=转出（证券→银行卡）；`amount` > 0；`date` 可空缺省今天
- **模型**：本金（净投入）+= 转入 - 转出；现金/资产同步 ±；总盈亏 = 资产 - 本金（转账本身不变盈亏）
- **响应**：`{"id":"transfer_...","type":"IN","amount":10000,"date":"2026-08-17"}`
- 需 trading 插件（403）

### `GET /api/v1/trading/transfers` — 转账流水

### `POST /api/v1/trading/trades`（手续费自动）— 2026-08-16 起手续费按费率自动计算，表单不再填：

| 费 | 费率 | 规则 |
|:---|:-----|:-----|
| 佣金 | 万 0.854 | 买入卖出都收，四舍五入到分，无最低 5 元 |
| 印花税 | 万 5 | 仅卖出，去尾到分 |
| 过户费 | 万 0.1 | 仅沪市（6/9 开头），四舍五入到分 |

买入成本 = 价×量 + 佣金 + 过户费（摊薄成本价落盘）；卖出回款 = 价×量 - 佣金 - 印花税 - 过户费；买卖后现金自动推导（account.json）。

### `GET /api/v1/trading/account` — 账户总体快照（顶层账户卡，2026-08-16）

资金股份查询导入后返回券商口径账户：`{"assets":110504.88,"cash":292.88,"available":292.88,"withdrawable":292.88,"marketValue":110212.00,"pnl":15235.55,"todayPnl":0.0,"principal":150000,"snapshotDate":"2026-08-16"}`。**字段语义（2026-08-16 修正）**：`pnl` = 持仓浮动盈亏（券商口径，非总盈亏）；**总盈亏 = `assets - principal`**（本金由用户提供，累计投入 15 万 → 当前总盈亏 -39,495.12）。顶层展示总资产/可用/可取/参考市值/当日盈亏/总盈亏/本金。数据依赖导入；收盘 15:05 自动更新行情相关字段（参考市值/当日盈亏/浮盈，P2-交易19 修订），现金/本金保持券商导入+转账推导。需 trading 插件（403）。

### `POST /api/v1/trading/imports/cash` — 资金股份查询导入（现金 + 精确成本）

**body**：`{"content":"资金股份查询导出文本"}`。解析首行「余额/资产」→ 更新 cashBalance；明细「证券代码/成本价」→ 更新持仓精确成本（4 位，盈亏% 与通达信一致）。**响应**：`{"cash":292.88,"assets":110504.88,"updatedCost":5}`。

### `POST /api/v1/trading/imports/save` — 导入文件上传留存（通达信导出，2026-08-16）

**multipart**：`file`（通达信导出 txt，GBK/UTF-8 均可）

- **留存**：原始文件存 `data/{userId}/trading/imports/{yyyy-MM}/{ts}_{filename}`（可追溯）
- **转码**：GBK 自动转 UTF-8（UTF-8 严格解码失败按 GBK）
- **响应**：`{"path":"trading/imports/...","content":"转码后的 UTF-8 文本"}`——前端填充解析导入
- 需 trading 插件（403）。

### `POST /api/v1/trading/positions/import` — 持仓初始化导入（通达信导出 → 持仓快照，2026-08-16）

**body**（数组，可空）：
```json
[{"symbol":"600519","name":"贵州茅台","quantity":100,"avgCost":1400,
  "stopLossPrice":1350,"buyPoint":"B1","role":"基石","entryDate":"2026-08-01"}]
```
- `symbol`/`quantity`/`avgCost` 必填；`name` 缺失时后端按代码行情补全
- `stopLossPrice`/`buyPoint`/`role`/`entryDate` 可选——通达信导出无止损/买点，导入后**必须补设**（R68）建议引擎才按纪律判定

**响应**：`{"imported":2,"missingStopLoss":["600519 贵州茅台",...]}`（未设止损列表，前端提示补设）。按 symbol upsert（已存在更新，不存在新增）。需 trading 插件（403）。

### `PUT /api/v1/trading/positions/{symbol}` — 更新持仓元信息（web 持仓编辑，2026-08-17 补端点）

**body**（只带非空字段）：`{"role":"防守","stopLossPrice":1302}`（role/止损位可选）

- 之前前端与测试在调但后端从未实现（编辑一直 404）——2026-08-17 补上；`targetPrice` 后端 Position 无字段落盘（前端目标价编辑是既有无效功能，另记 P3）
- **响应**：更新后持仓对象（symbol/name/quantity/avgCost/stopLossPrice/buyPoint/role）；symbol 不存在 404；止损位非数字 400
- 需 trading 插件（403）。

---

### `POST /api/v1/trading/trades/parse` — 解析一句话交易（RFC 20260815 通道 A）

把自然语言（「买了 1000 股京东方 @5.2」）结构化为交易入参，供前端确认卡回显。**只解析不落库**——写入仍走 `POST /trades`（正确性由确认步拦截）。

**Request Body**

```json
{ "text": "买了 1000 股京东方 @5.2" }
```

**Response**

```json
{
  "matched": true,
  "symbol": "000725",
  "name": "京东方A",
  "direction": "BUY",
  "price": 5.2,
  "volume": 1000,
  "stopLossPrice": 4.9,
  "buyPoint": "B1",
  "targetPrice": null,
  "reason": null
}
```

> `matched=false` 时其余字段为 null（前端转精确表单）。LLM 结构化优先，失败降级正则兜底。需 trading 插件（403）。

---

### `POST /api/v1/trading/advice` — 生成持仓建议（交易模块核心：建议引擎）

读用户持仓 + 实时行情 + 只读 `os/trading-engine/knowledge/context/rules.md` 与 `strategy.md`，将止损规则（R66-R80）与仓位规则（R81-R95）作为决策硬约束注入 LLM，结构化生成逐票建议（suggestion/reason/rules 必须引用规则号）。**建议是输出不是指令**，本端点不做任何执行动作。

**Request**：仅 `X-User-Id` header（body 空）

**Response**

```json
{
  "advice": [
    {
      "symbol": "000725",
      "name": "京东方A",
      "position_percent": 3.70,
      "suggestion": "reduce",
      "reason": "自然语言理由，必须引用规则号（如 R81）",
      "rules": ["R81", "R66"]
    }
  ],
  "summary": "持仓总览一句话"
}
```

> `suggestion` 取值：buy / hold / reduce / clear。`position_percent` 后端按市值占比计算（确定性）。LLM 失败时降级返回基础数据（无建议字段），不抛错。需 trading 插件（403）。空仓返回空 advice。

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

将复盘笔记中的经验写入 `os/trading-engine/99-inbox/`，供用户在 trading-engine 工作焦点下审核入库。

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
  "path": "/path/to/os/trading-engine/99-inbox/2026-07-25_交易复盘.md",
  "message": "已写入入库候选。该内容不会自动进入 AI 上下文：请在交易知识库工作流（os/trading-engine）审核后归入正式目录，并在收敛时重建 knowledge/context。"
}
```

> **#178（2026-08-12）**：`message` 字段提示入库候选不会自动融入 AI context——promote 只写入 `99-inbox/`，融合需在 trading-engine 工作流收敛重建 `knowledge/context/` 后由 `TradingKnowledgeSource` 注入。`path` 文件名遵循 #211 约定 `YYYY-MM-DD_主题.md`。
>
> **#129（2026-08-12）**：前端入口已补——adai-app / adai-web 交易页复盘弹窗新增「反哺入库」按钮（`POST` body 传 `{}`，note/sections 可空），成功后展示 `message` 提示。知识反哺闭环前后端打通。
>
> **插件门控（RFC 20260814，v3.18）**：promote 写入共享 os/ 知识库 → 仅启用 trading 插件的用户可用；未启用 → `403`（`{"error":"trading 插件未启用，无法反哺知识"}`）。

### `GET /api/v1/admin/trading/knowledge/conflicts` — 检测规则矛盾（需 X-Admin-Token）

从 `os/trading-engine/knowledge/context/rules.md` 解析真实规则，与当前持仓状态对比，标记可能违反的规则（规则名/描述取自真实规则内容，非硬编码）。

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

> 内容为 AI 生成的问候 + 当日要点：**首行问候、每行以 emoji 开头、最多 5 行**（后端 `truncateLines(…, 5)` + prompt「max 5 lines」）。前端直接渲染，无额外前缀（去绿点，避免与 AI emoji 双重前缀冲突）。AI 失败降级为按时段问候（🌙/☀️/🌤️/🌇/✨）+ 💬 引导行。

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

> `mediaPath`：媒体记录才有——`type=image`（图片记录原图）与 `type=image_qa`（S-2 展示层聚合：图文事件缩略图取引用首图），指向媒体文件相对路径（GET `/api/v1/records/media/{id}` 取文件）；其余类型为 `null`。

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

### `POST /api/v1/admin/memory/rebuild` — 重建记忆（需 X-Admin-Token）

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

### `PATCH /api/v1/memory/{id}` — 手动修正记忆（用户端，P-role-02）

个人记忆修正归用户端（adai-app 记忆页「修正」）。走 `X-User-Id` 用户隔离（**非** admin 鉴权）。

**Request Body**（任一字段缺省保持原值）

```json
{ "kind": "fact", "summary": "修正后的内容", "tags": ["生活"], "actionable": true }
```

**Response**：`{"success": true}`；找不到 → 404

---

### `PATCH /api/v1/admin/memory/{id}` — 手动修正记忆（管理端，需 X-Admin-Token）

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
| `apiEndpoints` | Integer? | API 端点总数；`null` = endpoints.txt 资源缺失（REVIEW #247，与「真 0 个」区分），前端显示「未知」 |
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
| 规则冲突检测 | `GET /api/v1/admin/trading/knowledge/conflicts` |
| 加载项目状态 | `GET /api/v1/project/status` |
| 任务列表 | `GET /api/v1/project/tasks` |
| 创建任务 | `POST /api/v1/project/tasks` |
| 更新任务 | `PUT /api/v1/project/tasks/{id}` |
| 删除任务 | `DELETE /api/v1/project/tasks/{id}` |
| 任务统计 | `GET /api/v1/project/tasks/stats` |
| 卡片迁移 | `POST /api/v1/cards/migrate` |
| 卡片清理 | `POST /api/v1/admin/cards/cleanup` |

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
| `sourceRecordId` | String | 源记录 ID（R2，可选）：记录自动转任务（D1 通用化：任何可执行记录即转，不限 domain=project）时关联的 `rec_xxx`；前端手动建任务为 null |
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
> **管理鉴权（REVIEW #127）**：本节除 `GET /api/v1/accounts/available` 与 `GET /api/v1/me/plugins`（产品端，无鉴权）外，其余端点与 §17 管理端所有端点要求请求头 `X-Admin-Token`（值 = 后端配置 `ADAI_ADMIN_TOKEN`）。缺失或不匹配 → `401`；服务端未配置令牌 → fail-closed `503`（防生产误部署裸奔）。adai-admin 前端通过 `--dart-define=ADMIN_TOKEN=<令牌>` 注入，与后端一致。
>
> **插件模型（RFC 20260814）**：Account 带 `plugins`（`["trading","project"]`）。`trading`/`project` 是 adai 拥有并受控开放的插件（Domain），启用载体 = 账号 plugins 字段；Kernel 基础服务（记录/问答/记忆/档案/时间线/搜索/待办）人人都有，不在插件表。seed admin `adai` 默认 `["trading","project"]`；新账号默认空。plugins 决定：知识/行情注入、模块显隐（前端 `GET /me/plugins`）、promote 权限。

### `GET /api/v1/me/plugins` — 当前用户启用插件（前端模块显隐）

**无鉴权**（凭 `X-User-Id` 头）。返回当前用户启用的插件名列表；账号不存在 → 空列表。adai-app / adai-web 据此显隐插件模块（交易页 / 阿呆系统 / 项目仪表盘），基础服务模块不依赖此端点。

**Request Headers**

- `X-User-Id` — 当前用户 ID

**Response**（`List<String>`）

```json
[ "project", "trading" ]
```

- 新用户（无插件）→ `[]`

### `GET /api/v1/accounts` — 账号列表

**Response**

```json
[
  {
    "userId": "adai",
    "role": "admin",
    "enabled": true,
    "createdAt": "2026-08-02",
    "plugins": ["trading", "project"]
  }
]
```

### `GET /api/v1/accounts/available` — 可用账号列表（产品端选号）

> **无鉴权**（WebConfig 从 AdminAuthInterceptor 拦截范围 exclude）——adai-app / adai-web 首屏选号与切换调用。仅返回 `enabled=true` 账号的 **userId 最小集**（REVIEW #215：无鉴权端点不暴露 role/enabled/createdAt，避免 admin 标记等枚举面）。

**Response**（`List<String>`，纯 userId）

```json
[ "adai", "alice" ]
```

- 空列表 → `200 []`（前端展示「去 adai-admin 创建账号」空态）

### `POST /api/v1/accounts` — 建号（adai-admin 后台）

**Request Body**

```json
{ "userId": "alice", "role": "user" }
```

- `role` 可选，默认 `user`（`admin` / `user`）
- `plugins` 可选，默认 `[]`（新用户只有基础服务）；仅允许 `trading` / `project`，非法 → 400
- `400` — userId 已存在 / 格式非法（仅 `[a-zA-Z0-9_-]+`）/ role 非法 / plugins 非法

### `PATCH /api/v1/accounts/{userId}` — 更新账号

**Request Body**

```json
{ "enabled": false }
```

- `enabled` / `role` / `plugins` 均可选，缺省保持原值（只改 enabled 不清空 plugins）
- `plugins` 传全量列表（如 `["trading"]`），仅允许 `trading` / `project`，非法 → 400
- **内置管理员 `adai` 不可禁用、不可降级**（400）
- `404` — 账号不存在

### `DELETE /api/v1/accounts/{userId}` — 删除账号

- **内置管理员 `adai` 不可删除**（400）
- `204` — 删除成功；`404` — 账号不存在

### `PATCH /api/v1/accounts/{userId}/plugins` — 合并插件（S-R2 服务端原子语义）

> REVIEW S-R2（2026-08-15）：根治前端全量 PATCH read-modify-write 并发互覆（快速连点两个开关不再丢）。服务端账号级锁内读改写合并。

**Request body**

| 字段 | 类型 | 说明 |
|:-----|:-----|:-----|
| `add` | String[] | 要启用的插件名（`trading`/`project`，可选，默认空）|
| `remove` | String[] | 要停用的插件名（可选，默认空）|

**Response** — `200` 合并后的 `Account`；`400` — 插件名非法 / **内置管理员插件受保护**；`404` — 账号不存在

```json
{
  "userId": "alice",
  "role": "user",
  "enabled": true,
  "createdAt": "2026-08-02",
  "plugins": ["trading", "project"]
}
```

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
| `domain` | String | 是 | `trading-engine` / `life-os` / `project-os`（白名单校验）|
| `path` | String | 否 | 相对 os/ 的目录路径，如 `trading-engine/knowledge/context` |

**Response** — 同 `/admin/files` 条目数组

### `GET /api/v1/admin/knowledge/content?path=` — os/ 文件内容

**Response** — 同 `/admin/files/content`

### `GET /api/v1/admin/ai-logs?userId=&date=` — AI 交互日志（R1）

**Query Parameters**

| 参数 | 类型 | 必填 | 默认 | 说明 |
|:-----|:-----|:----:|:----:|:-----|
| `userId` | String | 否 | `adai` | 用户 ID（多账号下指定；非法字符 400）|
| `date` | String | 否 | 今天 | 日期 `YYYY-MM-DD`（格式错误 400）|
| `page` | int | 否 | 1 | 页码（从 1 起，<1 归 1）|
| `size` | int | 否 | 200 | 每页条数（上限 500，超出截断）|

**Response** — 当日 AI 交互日志条目列表（JSONL 解析后，按写入顺序，分页切片）

```json
{
  "userId": "adai",
  "date": "2026-08-12",
  "page": 1,
  "size": 200,
  "total": 3,
  "count": 2,
  "logs": [
    {
      "traceId": "uuid",
      "ts": "2026-08-12T10:00:00.123",
      "durationMs": 856,
      "userId": "adai",
      "kind": "understand",
      "scene": "trading",
      "recordId": "rec_xxx",
      "cardId": null,
      "source": "question",
      "model": "deepseek",
      "prompt": "完整组装 prompt 全文",
      "systemPrompt": null,
      "estimatedTokens": 1200,
      "status": "ok",
      "error": null,
      "responseLength": 240,
      "responseSummary": "summary=买入 | tags=[trading]"
    }
  ]
}
```

- **数据源**：`data/{userId}/ai-logs/YYYY/MM/ai-log-YYYY-MM-DD.jsonl`（File First，见 `data-format-freeze.md`）
- **kind**：`understand` / `generate` / `recognizeIntent` / `visual.understand` / `visual.ask`
- **systemPrompt**（#231）：仅 `generate` 有值（自定义 system 指令，如复盘模板），understand/intent/visual 为 null——完整还原"提示词怎么组装的"
- **关联**：`recordId`/`cardId`/`source` 由调用点在 AI 调用前通过 `AiTraceContext` 挂载（无关联时靠 `scene`+`prompt` 追溯）
- **落盘失败不影响业务**：日志 best-effort，AI 调用结果正常返回
- **REVIEW #210 隐私治理（2026-08-12）**：日志保留 `adai.ai-log.retention-days`（默认 30 天）——写入时惰性清理过期文件；`date` 早于保留期返回 **400**（已清理不可查，防扫任意历史明文）；`size` 上限 500 防单次拉全量
