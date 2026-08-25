---
title: AI 调用治理方案（模型路由 / 流式 / 超时矩阵 / 上下文瘦身）
description: 全场景 AI 调用治理设计稿——13 处 LLM + 3 处视觉调用按体验敏感度分档，四维治理（模型路由/传输模式/超时重试/上下文控制）+ 降级矩阵，分批落地路线图
version: 1
created: 2026-08-24
updated: 2026-08-24
status: draft
lines: 135
depends-on:
  - ./system-architecture.md
related:
  - ./api-spec.md
  - ../reference/change-log.md
  - ../../ai-engineering/checklists/cost.md
tags: [architecture, ai, performance, design]
---

# AI 调用治理方案

> **问题一句话**：系统 13 处 LLM + 3 处视觉调用**全部共用 `deepseek-v4-pro` 推理模型 + 同步整包返回**——聊天转半天（30~60s+）、生产实测可能超时（前后端超时配置打架）、思维链白烧 token。需要从「每个点打补丁」升级为「全局治理」。
>
> **方案一句话**：按体验敏感度把调用分三档（实时等 / 可等 / 后台），四维治理——**模型路由**（小任务换快模型，大任务留推理模型）、**传输模式**（实时档流式 SSE）、**超时/重试矩阵**（消灭前后端打架）、**上下文控制**（治越聊越慢）+ 降级矩阵。分三批落地，每批独立可 /ship。

---

## 一、背景与问题（实锤数据）

| # | 症状 | 实锤 |
|:-:|:-----|:-----|
| A | 聊天「转半天」 | 单次回答 30~60s+；生产日志登记：`POST /trading/advice` 实测 **45~60s**（08-23 部署批，本地复现同样耗时） |
| B | 「可能超时」 | 前端 AI 请求超时 **90s**（`api_service.dart` `_aiClient`）vs 后端单次 60s + 重试 1 次最坏 **120s**（`DeepSeekAiClient`）——后端一重试前端必断 |
| C | 越聊越慢 | CHAT 模式**全量**对话历史无截断 + 每次提问**全量重组装**背景（20 相关 + 10 搜索 + 7 天记忆 + 交易知识 11KB+）→ 输入 token 线性涨 → 推理时间线性涨 |
| D | 白烧钱 | 标签/意图识别/解析等小任务也走推理模型，思维链（reasoning）**也计费**且拖时间 |

## 二、现状盘点：调用点全表（按体验敏感度分档）

| 档 | 场景 | 调用点（文件） | 现状 |
|:-:|:-----|:---------------|:-----|
| **A 实时等** | 聊天回答 | `QuestionAppService` | 同步 30~60s+，转圈，可能超时 |
| | 图片追问 / 多图问答 ×3 | `MediaRecordAppService`（VLM） | 同步 |
| **B 可等** | 交易建议 advice | `TradingAdviceAppService` | 同步，生产 45~60s 重灾区 |
| | 一句话解析 / 批量解析 ×2 | `TradingParseAppService` | 同步 |
| | 复盘 review | `TradingReviewAppService` | 同步 |
| | 结束聊天总结 end | `ConversationController` | 同步 |
| **C 后台** | 意图识别（**每次新记录都调**）| `IntentRecognizer` | 同步 15s |
| | 标签/摘要理解 | `RecordUnderstandingService` | 同步 |
| | 15min 重试补齐 | `RecordRetryService` | 同步 |
| | 简报 brief | `BriefAppService` | 同步 |
| | 时段推送 | `TradingSessionPushService` | 同步 |
| | 交易归集 | `TradeLogCollectService` | 同步 |

**统一出口**：`kernel/ai` 的 `AiClient` 端口（`understand` / `generate` / `recognizeIntent`，REVIEW #22 依赖倒置）+ `infrastructure/ai` 的 `DeepSeekAiClient`（LLM）+ `GlmVisualAiClient`（VLM）+ `LoggingAiClient` 装饰器（R1 交互日志）。治理点在端口处收口，**不用改 16 个调用方**。

## 三、核心洞察

1. **多数任务不需要推理模型**：标签/意图/解析/总结是「小任务」，v4-pro 的长思维链是白烧时间+白烧钱。统一模型打天下是根因。
2. **等待主体是模型生成**（>95%），本地组装/网络毫秒级——先治模型侧，本地（全量扫描无缓存）是次要矛盾，数据大了才显著。
3. **体验敏感度决定治理手段**：A 档要流式 + 快模型；B/C 档换快模型即可，无需流式。

## 四、治理方案（四维矩阵）

### ① 模型路由（按场景分模型）

| 配置项（新增） | 用途 | 选型原则 |
|:--------------|:-----|:---------|
| `adai.ai.chat-model` | 聊天 / 总结 / 标签 / 意图 / 解析（A 档 + B 档小任务 + C 档）| 非推理快模型：首字快、省 token |
| `adai.ai.analysis-model` | 交易建议 / 复盘 / 简报 / 推送（需要深度推理）| 保留 `deepseek-v4-pro` |
| `adai.ai.vision-model` | 视觉（现状免费 GLM）| 不动 |

落地：`application.yml` 拆配置；`AiClient` 实现按 `scene`（`ContextPackage.scene()` / 调用语义）路由到对应模型。**结束聊天总结、标签正是最大受益者**——它们现在是「用户触发可等」，换快模型后等 3~5s 而不是 30s。

### ② 传输模式

- **A 档（聊天）**：新增流式端点，SSE 逐块转发（DeepSeek `stream=true`，`SseEmitter`）。事件格式：
  ```
  data: {"type":"thinking"}                            ← 思维链阶段（前端显示占位）
  data: {"type":"text","content":"第一段"}             ← 正文增量，边到边显示
  data: {"type":"meta","summary":"…","tags":["…"]}     ← 末尾 JSON 解析结果（更新卡片标签）
  data: [DONE]
  ```
- **B/C 档**：保持同步，靠换快模型提速（45~60s → 个位数秒），不引入流式复杂度。
- **降级**：流式失败 → 自动降级非流式重试一次（复用现有同步端点）。

### ③ 超时/重试矩阵（消灭前后端打架）

| 路径 | 超时 | 重试 |
|:-----|:-----|:-----|
| 流式（A 档聊天）| 首字节超时 + 空闲 60s 无新字 | 降级非流式 1 次 |
| 同步 A/B | 90s+（前端 `_aiClient` 对齐后端上限）| 1 次 |
| 同步 C（后台）| 15~30s | 2 次（保留现有 `MAX_ATTEMPTS` 逻辑）|

原则：**前端超时 ≥ 后端最坏路径**，前端不得先于后端断。

### ④ 上下文控制（治越聊越慢）

| 项 | 现状 | 治理后 |
|:---|:-----|:-------|
| 会话历史 | 全部轮次无截断 | 最近 N 轮（默认 10）+ 旧轮压缩成一段摘要 |
| 相关记录 | 20 条 | 10 条 |
| 搜索结果 | 10 条 | 5 条 |
| 交易知识 | 每次全量 11KB+ | 仅 trading 场景全量，其他场景只注入摘要或跳过 |
| 本地读取 | 全文搜索全量扫描无缓存、知识文件每次重读 | 知识/记忆文件缓存；搜索待数据量显著后再上索引 |

### ⑤ 降级矩阵（保留并补齐）

- 保留：AI 失败记录保留待 15min 重补（`RecordRetryService`）；`conversation/end` 失败兜底对话原文；图片问答失败降级问号启发式。
- 补齐：流式 → 非流式降级；快模型失败 → 分析模型兜底重试（路由层加 try/catch）。

## 五、分批落地路线图（每批独立可 /ship）

| 批 | 范围 | 验收 | 风险 |
|:-:|:-----|:-----|:-----|
| **批 1 · 模型路由** | 拆三模型配置 + 按 scene 路由；顺带 A/B 实测选型（候选快模型同问题对比耗时/质量/成本）| 标签/意图/解析/总结耗时降 5~10 倍；后端测试 +N | 低——纯配置 + 路由，不动端点 |
| **批 2 · 聊天流式** | 后端 SSE 端点 + `DeepSeekAiClient` 流式 + 前端流式渲染（`sse_client` 三端）+ 超时矩阵 | 聊天首字后边出边显示，超时消失；api-spec 补契约 | 中——前端流式渲染 + 状态机配合 |
| **批 3 · 上下文治理** | 历史截断/摘要 + 背景瘦身 + 知识按需 + 缓存 | 长会话不再线性变慢；输入 token 下降 | 低-中——摘要质量需回归测试 |

## 六、契约与架构影响

- **kernel/ai**：`AiClient` 端口保持 `understand/generate/recognizeIntent`；流式能力新增 `StreamingAiClient`（或 `understandStream`），端口仍在 kernel、实现在 infra（不违 REVIEW #22）。
- **interfaces**：新增 `POST /api/v1/records/ask-stream`（独立端点，语义清晰，旧同步端点不动、兼容在网客户端）。
- **配置**：`adai.ai.chat-model` / `adai.ai.analysis-model`（`vision-model` 已有）。
- **契约**：`api-spec.md` 补流式端点 + SSE 事件格式（guard-align 门禁随批通过）。
- **前端**：app 加 `sse_client` 依赖（三端统一）；web（React 桌面壳）二期接。

## 七、决策点（实施前拍板）

1. **聊天快模型具体型号**：开工实测（候选：deepseek-chat 系列），A/B 耗时/质量/成本后定。
2. **流式端点形态**：倾向独立端点 `/records/ask-stream`（不动旧端点）；备选 `/records` 加 `Accept: text/event-stream`。
3. **历史轮数上限**：默认 10 轮，配置化可调。

## 八、风险与回滚

- 快模型质量下降（交易/深度推理问题）→ 分级路由保底（trading 场景仍走 analysis-model），可单场景回退。
- SSE 与代理兼容：生产直连 8080 无反向代理，风险低；若后续加网关需验证。
- 每批独立回滚：批 1 回退配置即可；批 2 回退到旧同步端点（前端保留 fallback）。
