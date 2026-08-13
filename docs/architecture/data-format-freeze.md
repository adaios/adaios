# 数据格式冻结（Data Format Freeze）— v1.0.0

> **状态：v1.0.0 数据契约（定稿）** ｜ 2026-08-07
> 本文档冻结 AdaiOS 个人数据层（`data/`）的全部文件格式。**v1.0.0 发布后**，格式变更必须遵循 §三「变更规则」；破坏性变更需写迁移说明。
> 关联：`docs/architecture/api-spec.md`（API 契约）· `docs/VISION.md` §File First · `docs/rfc/20260801-release-versioning.md`（版本机制）

---

## 一、定位与原则

1. **代码 writer 是格式真相源**。冻结的是「代码定义并写入的格式」，不是「磁盘现状」——磁盘上存在的手写/旧版内容，在对应文件下一次被写入时归一化为代码格式（见 §四差异项）。
2. **路径分层**：除账号外，所有数据在 `data/{userId}/...`（本机账号 = `data/adai/`，default 已随多账号迁移移除）。`data/accounts/accounts.json` 是**唯一系统级例外**（无 userId 层，全局共享）。
3. **ID 规范**：所有实体 ID 由 `IdGenerator.monotonic()` 生成，格式 `{前缀}_yyyyMMdd_HHmmssSSS`（**含毫秒**，13 位时间戳段）。
4. **写入机制**：所有文本/二进制写走 `FileStorage`（`LocalFileStorage`，UTF-8，tmp + ATOMIC_MOVE 原子写）。JSON 类文件按各仓库的 ObjectMapper 序列化。

---

## 二、格式目录（v1.0.0 冻结契约）

### 2.1 记录 `records/`

| 项 | 值 |
|:--|:--|
| 路径 | `records/{yyyy}/{MM}/{id}.md`（`{id}` = `rec_yyyyMMdd_HHmmssSSS`）|
| 格式 | Markdown + YAML frontmatter + 正文 |
| 真相源 | `RecordFileRepository.toMarkdown()` |

```
---
id: rec_20260802_104250945
type: note
source: user_input
tags: [投资, 半导体]
createdAt: 2026-08-02T10:42:50.945
summary: 建仓了半导体
domain: life
---
正文内容（Markdown）
```

- 字段：`id` / `type`（note/image/conversation…）/ `source` / `tags`（`[a, b]` 逗号+空格）/ `createdAt`（ISO `LocalDateTime`）/ `summary`（单行化，可空）/ `domain`（默认 `life`）/ `intent`（`question`/`log`/空，可空）
- 正文 = `content`；解析端取首行 <100 字符作 title
- `intent` **落盘**（REVIEW #144）：question 记录写 `question`，rebuild 借此排除避免重跑烧 AI；log 写 `log`，未处理写空。旧文件无该字段 = 空（向后兼容）。

### 2.2 图片媒体 `records/.../media/`

| 项 | 值 |
|:--|:--|
| 路径 | `records/{yyyy}/{MM}/media/{id}.{ext}` |
| 格式 | **二进制原样**（`writeBytes`），扩展名按 contentType：`jpeg→jpg`，其余按 subtype |
| 真相源 | `RecordFileRepository.saveMedia()` |

### 2.3 卡片对话 `records/cards/`

| 项 | 值 |
|:--|:--|
| 路径 | `records/cards/{yyyy}/{MM}/{dd}/card_{id}.md`（按卡片创建日，跨日续接写回原文件）|
| 格式 | Markdown + frontmatter + 轮次 body |
| 真相源 | `CardFileRepository.toMarkdown()` |

```
---
id: card_...
type: conversation
status: active            # idle | active | ended
tags: [标签1, 标签2]
createdAt: 2026-07-19T14:00:00
updatedAt: 2026-07-19T14:05:00
summary: ...              # ended 后才有
---
## 14:00
用户：男人本色？
AI：这是一个复杂的文化概念...
```

- 轮次 body：`## HH:mm` + `用户：...` / `AI：...`；`parseTurns` 只认 `## ` 前缀 + `用户：/AI：` 行
- **契约约束**：AI 轮只写自然语言（JSON 元数据已剥离，REVIEW #13/#11）

### 2.4 记忆 `memory/`

| 项 | 值 |
|:--|:--|
| 路径 | `memory/{yyyy}/{MM}.md`（单月单文件，多条目）|
| 格式 | Markdown，条目间 `---` 分隔 + frontmatter + 正文 |
| 真相源 | `MemoryService.formatMemoryEntry()` |

```
---
id: mem_...
recordId: rec_...
kind: insight            # fact | insight | preference | pattern | decision
topic:                   # 主题 id（Phase 2，可空）
superseded: false        # Phase 2
evolvedTo:               # 演变链指针（可空）
doneAt:                  # 行动完成时间（Phase 3，可空）
lastConfirmed:           # 最近回读（Phase 4，可空）
tags: [a, b]
sentiment: neutral
actionable: false
patterns: []
preferences: []
suggestion:              # 行动建议；降级记忆 = "DEGRADED"
createdAt: 2026-08-02T10:42:50.945
---
记忆正文（AI 洞察/降级原文）
```

- 降级记忆：`suggestion: DEGRADED`（AI 失败保底沉淀，可由洞察升级覆盖）

### 2.5 个人档案 `identity/`

| 项 | 值 |
|:--|:--|
| 路径 | `identity/profile.md` |
| 格式 | Markdown + YAML frontmatter（name/preferences/rules/tags）+ 固定 body |
| 真相源 | `IdentityFileRepository.serializeProfile()` |

```
---
name: 阿呆
preferences:
  作息: 早睡
rules:
  AI称呼: 直接回答问题
tags:
  - 标签1
---
# 个人档案

阿呆的个人 AI 协作档案。
```

- body 为固定模板 `BODY_TEMPLATE`（freeze #2：磁盘手写「如何填写 frontmatter」说明已挪入本文档，下次 `save()` 归一化为模板）
- **手动维护指引**（原 profile.md body 手写内容）：
  - `preferences`：静态偏好（缩进子键值对）
  - `rules`：AI 协作规则（缩进子键值对，如 `AI称呼: 直接回答问题，不要称呼名字`）
  - `tags`：标签列表（`- 标签`）
  - AI 每次对话都会读取此档案注入上下文

### 2.6 持仓 `trading/positions.md`

| 项 | 值 |
|:--|:--|
| 路径 | `trading/positions.md` |
| 格式 | Markdown 表格 + 尾部键值 |
| 真相源 | `PositionFileRepository.toMarkdown()` |

```
# 当前持仓

| symbol | name | quantity | avgCost | currentPrice |
|--------|------|----------|---------|--------------|
| 600519 | 贵州茅台 | 100 | 25.3 | 26.1 |

cashBalance: 100000
lastUpdated: 2026-08-07T09:00:00
```

- 数值用 `stripTrailingZeros().toPlainString()`（`25.3` 而非 `25.30`）
- `cashBalance`/`lastUpdated` 为文件尾部键值行
- **手动维护**（freeze #1：原文件内 `<!-- -->` 注释已挪入本文档，代码 `toMarkdown()` 不写注释，下次写入自动归一化）：
  - 表格由系统维护（`POST /api/v1/trading/trades` 记录交易后自动更新）
  - 手动编辑格式：`| symbol | name | quantity | avgCost | currentPrice |`
  - 示例：`| 600123 | 立昂微 | 200 | 25.30 | 26.10 |`

### 2.7 标签索引 `index/tags.json`

| 项 | 值 |
|:--|:--|
| 路径 | `index/tags.json` |
| 格式 | JSON（缩进，ISO 日期字符串），**非真相源**——可从 `records/` 全量重建 |
| 真相源 | `TagIndexService` + `recordId → TagIndex(TagEntry(count, recordIds, firstAt, lastAt), updatedAt)` |

### 2.8 行情异动推送 `trading/pushes/`

| 项 | 值 |
|:--|:--|
| 路径 | `trading/pushes/{yyyy-MM-dd}.json`（按日分片）|
| 格式 | JSON 数组（紧凑）|
| 真相源 | `MarketPushRepository` |

```
[{"id":"push_...","symbol":"600519","name":"贵州茅台",
  "message":"📉 ... 触发止损预警","type":"loss","time":"14:05"}]
```

- `type`：`loss`（止损）/ `gain`（放飞）/ `break-cost`（跌破成本线）等

### 2.9 行情去重快照 `trading/market_snapshot.json`

| 项 | 值 |
|:--|:--|
| 路径 | `trading/market_snapshot.json` |
| 格式 | JSON 对象（紧凑）|
| 真相源 | `MarketSnapshotRepository` |

```
{"date":"2026-08-06","signatures":["600519:2026-08-06:loss"]}
```

- `signatures` 元素 `{symbol}:{yyyy-MM-dd}:{type}`，跨日自动重置

### 2.10 账号 `accounts/accounts.json`（系统级）

| 项 | 值 |
|:--|:--|
| 路径 | `data/accounts/accounts.json`（**无 userId 层**，唯一例外）|
| 格式 | JSON 数组（pretty）|
| 真相源 | `AccountFileRepository`（原生 `Files.writeString`，不走 FileStorage）|

```
[ {
  "userId" : "adai",
  "role" : "admin",
  "enabled" : true,
  "createdAt" : [ 2026, 8, 2 ]
} ]
```

- `createdAt` 为 `LocalDate`，**序列化为 `[年, 月, 日]` 数组**（未禁用 WRITE_DATES_AS_TIMESTAMPS）
- 首次启动 seed 管理员 `adai`

### 2.11 任务 `project/tasks/`

| 项 | 值 |
|:--|:--|
| 路径 | `project/tasks/{yyyy}/{MM}.md`（单月单文件，多条目，保留文件头手写注释）|
| 格式 | Markdown，每条目一个 frontmatter 块 + body = title |
| 真相源 | `ProjectFileRepository.formatTaskEntry()` |

```
# 任务 - 2026-08

---
id: task_20260807_123456
title: ...
description: ...
status: DOING              # TODO | DOING | DONE | CANCELLED
priority: P0
tags: [后端, 架构]
rfcRef: 20260725-layer6
sourceRecordId: rec_20260813_...   # R2（2026-08-13 新增，MINOR 可空）：domain=project 记录自动转任务时关联源记录；旧文件无此行 → 解析 null，向后兼容
createdAt: 2026-08-07
updatedAt: 2026-08-07
---
{title}
```

### 2.12 交易复盘 `trading/reviews/`

| 项 | 值 |
|:--|:--|
| 路径 | `trading/reviews/{yyyy-MM-dd}_review.md` |
| 格式 | 纯 Markdown 文本（无 frontmatter，无固定 schema）|
| 真相源 | `TradingReviewFileRepository`（内容由 AI 复盘生成）|

### 2.13 AI 交互日志 `ai-logs/`（R1，2026-08-12 新增）

> 记录每次 AI 调用（DeepSeek 文本 + GLM 视觉）的入参/响应，回答"提示词怎么组装的"。日志型数据，可滚动清理（非长期资产）。

| 项 | 值 |
|:--|:--|
| 路径 | `ai-logs/{yyyy}/{MM}/ai-log-{yyyy-MM-dd}.jsonl` |
| 格式 | JSONL（每行一条 `AiInteractionLog`，见下）；字段为 null 时省略 |
| 真相源 | `AiInteractionLogger`（`FileStorage.append` 追加写，装饰器 `LoggingAiClient`/`LoggingVisualAiClient` 打点）|

**字段契约**：

| 字段 | 类型 | 说明 |
|:--|:--|:--|
| `traceId` | String | 调用 ID（UUID）|
| `ts` | String | 调用结束时间（ISO-8601）|
| `durationMs` | Long | 耗时毫秒（#218 视觉 understand/ask 已测真实耗时）|
| `userId` | String | 用户 ID |
| `kind` | String | `understand` / `generate` / `recognizeIntent` / `visual.understand` / `visual.ask` |
| `scene` | String | 场景（trading/project/life/note/question/brief/conversation/intent/media）|
| `recordId` / `cardId` | String | 关联记录/卡片 ID（可为 null）|
| `source` | String | 调用来源（question/log/retry/brief/trading_review/conversation/media/intent）|
| `model` | String | `deepseek` / `glm` |
| `prompt` | String | 发给模型的完整 prompt 全文——understand/generate 为 `ContextPackage.prompt()`；`recognizeIntent` 为用户输入原文；`visual.*` 无备注时为占位符「（图片理解，无备注）」（#233 说明）|
| `systemPrompt` | String | 自定义 system 指令（仅 `generate` 有，如复盘模板；understand/intent/visual 为 null，REVIEW #231）|
| `estimatedTokens` | Integer | 预估输入 tokens |
| `status` | String | `ok` / `error` |
| `error` | String | 错误信息（status=error 时）|
| `responseLength` | Integer | 响应字符数 |
| `responseSummary` | String | 响应摘要（截断）|

> **隐私注意**：`prompt` 含用户输入原文，属个人数据，按 `data/{userId}/` 分层隔离；不做对外接口暴露（仅管理端 `X-Admin-Token` 可读，见 api-spec §17）。

---

## 三、变更规则（v1.0.0 发布后）

| 类型 | 定义 | 要求 |
|:--|:--|:--|
| **MINOR（向后兼容）** | 新增可选字段、新增文件、新增实体 | 直接改，旧文件仍可解析 |
| **MAJOR（破坏性）** | 字段改名/删除、语义变更、重命名文件 | **必须**写迁移说明 + 迁移脚本（先例：2026-08-02 `data/` → `data/default/` 多用户分层；2026-08-09 `data/default/` → `data/adai/` 账号迁移），并在 api-spec / 本文件登记 |
| **格式冻结期** | v1.0.0 发布 → v1.1.0 | 除 Bug 修复（如字段缺失兜底）外不做 MAJOR 变更 |

**解析兜底原则**：读取端必须对缺失字段提供默认值（已普遍遵循：`unknown`/`life`/空列表等），确保旧文件不因字段缺失而崩溃。

---

## 四、发布前已知差异（freeze 核对项）

以下为「代码 writer 输出」与「磁盘现状」的差异，发布前需确认处理：

| # | 差异 | 影响 | 处理 |
|:--|:--|:--|:--|
| 1 | `positions.md` 磁盘含手写 `<!-- -->` 注释块（代码 `toMarkdown` 不写）| `saveAll()` 下一次写入会抹掉注释 | ✅ 2026-08-09 已处理：注释挪入 §2.6 手动维护，接受归一化 |
| 2 | `identity/profile.md` 磁盘 body 与 `BODY_TEMPLATE` 不同（手写说明）| 下一次 `save()` 会覆盖为模板 body | ✅ 2026-08-09 已处理：说明挪入 §2.5 手动维护，接受归一化 |
| 3 | 账号 `createdAt` 序列化为 `[2026,8,2]` 数组（LocalDate）| 与其他 JSON 的 ISO 字符串风格不一致 | ✅ 2026-08-09 已处理：`AccountFileRepository` 禁用 `WRITE_DATES_AS_TIMESTAMPS`，统一 ISO 字符串；读取兼容旧数组格式 |

> 处理决策：3 项均已在 v1.0.0 前解决——freeze #1/#2 内容挪入本文档并接受代码归一化，freeze #3 代码配置统一 + 磁盘迁移。发布时磁盘与契约一致。

---

## 五、关联文档

| 文档 | 角色 |
|:--|:--|
| `docs/architecture/api-spec.md` | API 契约（字段与数据格式互为表里）|
| `docs/rfc/20260802-multi-account-prep.md` | 多账号路径分层（userId 预留）|
| `docs/rfc/20260801-release-versioning.md` | 版本机制 + 数据格式变更流程 |
| `docs/architecture/system-architecture.md` | File First 存储原则 |
