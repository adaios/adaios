# AI 交互日志需求（R1，2026-08-12 登记）

> 来源：阿呆 2026-08-12 凌晨生产反馈（`rec_20260812_003303368` / `rec_20260812_003400325`，两次提及）。
> 状态：✅ **已实现**（2026-08-12，Phase 1+Phase 2 后端一步到位；管理端查看页顺延）。登记时状态为需求登记（未立项）。

## 需求原文

> 项目阿呆需求：记录每次和大模型交流的入参和响应，包括什么情况下。我想要了解提示词的构建。

## 拆解

用户希望系统**自动记录每次 AI 交互的完整轨迹**，用于事后复盘"提示词是怎么构建的"：

| 维度 | 内容 |
|:-----|:-----|
| **入参** | 触发场景（意图/domain）、Context Package（标签关联/搜索/预估 tokens）、最终发给模型的 system/user prompt 全文 |
| **响应** | 模型、tokens 预估 vs 实际、耗时、状态码、响应原文（长度 + 内容） |
| **触发条件** | "包括什么情况下"——记录是哪个行为（记录提交 / 问答 / 结束对话 / 图片理解 / 重补）触发了这次调用 |

## 现状

- 日志已部分具备：`DeepSeekAiClient` 打 `[DeepSeek] 请求 model=... | 模式=... | tokens 预估=...` 和 `[DeepSeek] 响应 received | status=... | 长度=...`；`ContextEngine` 打 `ContextPackage 组装完成 | ... | 预估 tokens=...`
- 缺口：**prompt 全文不入日志**（只有 token 预估）、**无结构化落盘**（只能从 systemd journal 捞）、**无法按记录/卡片反查**是哪次交互

## 方向（候选）

1. **结构化交互日志落盘**：`infrastructure/ai` 增加 `AiInteractionLogger`，每次调用落盘 `data/{userId}/ai-logs/{date}.jsonl`（或 md）——场景 / 入参 prompt 全文 / 响应摘要 / 耗时 / 关联 recordId
2. **保留 Context 组装快照**：记录 ContextEngine 组装出的 package（标签、搜索命中、tokens），与最终 prompt 关联
3. **前端可视化（后续）**：阿呆的原意是"了解提示词的构建"，可能需要一个查看入口（管理端/调试页）

## 已实现（2026-08-12）

- **装饰器方案**：`LoggingAiClient`/`LoggingVisualAiClient` 包装 DeepSeek/GLM 客户端（`@Primary`），全部 9 处 AI 调用点零改动自动记录
- **落盘**：`data/{userId}/ai-logs/YYYY/MM/ai-log-YYYY-MM-DD.jsonl`（`AiInteractionLogger` + `FileStorage.append`，同步锁防并发丢行）
- **记录内容**：kind/scene/prompt 全文/预估 tokens/耗时/状态/响应摘要 + `AiTraceContext` 挂载 recordId/cardId/source（Question/Record/Media/TradingReview/Conversation/Retry 6 处调用点）
- **读取端点**：`GET /api/v1/admin/ai-logs?userId=&date=`（X-Admin-Token 鉴权）
- **顺延**：管理端可视化页（等日志积累真实数据后做）；Context 组装快照细粒度（装饰器已含 prompt/预估 tokens，标签关联等见 `ContextEngine` 日志）

## 归属

- **AI 基础设施**（`infrastructure/ai` 或 `kernel/context` 旁路），非业务功能
- 落盘走 File First（`data/{userId}/ai-logs/`），遵守 data-format-freeze 变更规则

## 关联

- 已实现日志字段：`DeepSeekAiClient`、`ContextEngine`、`GlmVisualAiClient`
- 审核检查点：B24（部署后跑真实功能 E2E）
