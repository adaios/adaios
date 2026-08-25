---
title: AI 调用治理方案文档审查报告 2026-08-24
description: 对 docs/architecture/ai-calling-governance.md（新增方案稿）+ _index.md（登记）的跨域深审——docs/backend/frontend/adversarial 四官独立隔离并行，材料按角色裁剪、互不可见；汇总去重 + ⭐ 交叉加权
version: 1
created: 2026-08-24
updated: 2026-08-24
status: active
lines: 83
depends-on:
  - ../../../ai-engineering/process/review.md
related:
  - ../../review/REVIEW.md
  - ../../../ai-engineering/roles/adversarial-reviewer.md
tags: [review, audit, ai-governance, design]
---

# AI 调用治理方案文档审查报告 2026-08-24

> 起因：用户要求先审核新写的 `docs/architecture/ai-calling-governance.md`（AI 调用治理方案设计稿，135 行）。
> 方式：**docs-reviewer + backend-reviewer + frontend-reviewer + adversarial-reviewer** 四官独立子代理并行，材料按角色裁剪（frontmatter 规范/后端代码事实/前端代码事实/pitfalls+ux 清单）、官间互不可见、主会话只做汇总去重。审查范围仅本次两份文档（工作区其他会话的 os/ 交易研究改动与本审查无关）。
> 守护：`guard-meta.sh` PASS（109 文件）；`docs/review/guard.sh` G1-G7 **7 PASS / 0 HIT / 1 NOTE**。
> 结果：**P0×1 + 战略×7 + P1×11 + P2×13（合并去重后）**。⭐ 交叉命中 3 处多官独立证据（超时矩阵自相矛盾 ⭐⭐⭐⭐ 四官全中 / SseEmitter async timeout ⭐⭐ / 调用点计数 ⭐⭐）。方案整体**方向可行**（backend 核实 9 项事实全部属实），但实施前必须修订战略级缺陷。审查只报告未改文档（B7）。

---

## P0（会炸，实施前必须定义）

- **P0-1 流式→非流式降级重试的数据一致性全文未定义**（adversarial）：落库时机 / 重试幂等 / `RecordRetryService` 对新端点是否生效 / 重补与降级重试是否双写，方案只字未提 → 同一提问渲染两遍、同消息落库两次、15min 再补一次（Feed 重复事件；**复发信号：降级路径数据坑**）。frontend S3（降级半截文本丢弃/保留拼接）同源补充。

## 战略级（方案级缺陷，实施前必须定）

- **S-1 超时矩阵自相矛盾（⭐⭐⭐⭐ 四官全中）**：症状 B 自证「前端 90s vs 后端最坏 120s（60s×2）」打架，治理表同步档却仍写「90s+」——120s 最坏路径没消灭，在网旧客户端唯一路径（旧同步端点）照旧超时，方案核心卖点未兑现。修复：二选一并写死推导——前端 ≥120s，或后端去重试/降单次（45s×2=90s 自洽）；流式行补前端总预算。
- **S-2 路由键 scene() 不可行（backend）**：`TradingParse/Advice/Review/SessionPush` 全部 `scene="trading"`（代码实锤），快/深模型无法靠 scene 区分，「不改 16 个调用方」不成立。改用已存在的 `AiTraceContext.source`（trading_parse/trading_advice/trading_review/trading_session_*）。
- **S-3 快模型兜底无熔断（adversarial）**：快模型失败→v4-pro 兜底重试无熔断/次数上限，快模型系统级挂时 C 档批量（意图每次新记录、15min 重补）全走最贵最慢路径 = 成本/延迟雪崩，方案自引入新成本。
- **S-4 路由依赖意图识别同源退化（adversarial）**：意图识别正是批 1 要换的快模型——同源退化；trading 问题被 miss 判成普通聊天 → 走错模型 + 交易知识跳过 = 资金风险。
- **S-5 上下文摘要压缩丢精确数字（adversarial）**：价格/止损位被摘要吃掉 → trading 长会话建议基于残缺上下文；摘要生成时机/成本未定义（每次提问重摘要？）。
- **S-6 流式与状态机衔接缺失（frontend）**：thinking 事件与现有 `loading` 布尔（main_page.dart:1453）职责边界、meta 事件与 tags/`endConversation` 总结路径（:303/:351）边界未定义——end 归 B 档同步 vs meta 承载总结自相矛盾。
- **S-7 流式长连接期间用户操作取消契约缺失（frontend）**：end/发媒体/刷新/切 World 时在飞流式请求如何处理未定义（现有代码从不 cancel，F29 挤出窗口从秒级拉长到分钟级）。

## P1（重要）

- **P1-1 调用点计数 13 vs 实际 12（⭐⭐ docs+backend）**：交易归集行与「解析×2」的 parseLoose 是同一调用点（TradeLogCollectService 不直连 AI）重复计数；无「批量解析」端点；漏 AdminController rebuild 流。「13 处全部收口」的完整性承诺与批 1 验收口径失真。
- **P1-2 SseEmitter/Tomcat async timeout 未配置（⭐⭐ adversarial+backend）**：application.yml 无 async timeout，Tomcat 默认 ~30s 掐断流式 → 必须 `emitter.setTimeout(≥60s)`，否则批 2 流式必降级、验收翻车。
- **P1-3 CHAT 上下文双份注入（backend）**：ContextEngine:140 relatedRefs（cardContext+relatedRecords+memorySummary）经 `buildBackground` 与 `buildContextFromPrompt` 各注入一份 → CHAT 请求相关/记忆/卡片历史×2，只截 conversationHistory 白忙。
- **P1-4 旧轮摘要无现成数据源（backend）**：CardRecord.summary 仅 end/retry 写入 → 批 3「旧轮压缩成摘要」改动量被低估。
- **P1-5 流式端点必须复用 answer 尾部后处理（backend）**：card turn/memory/标签写回（QuestionAppService:123-149）不复用则会话连续性断。
- **P1-6 AiTraceContext ThreadLocal 在流式异步线程失效（backend）**：拿不到 trace + worker 线程不受 AiTraceCleanupInterceptor 清理（REVIEW #213 同类泄漏）；LoggingAiClient.around() 是同步阻塞实现。
- **P1-7 流式 meta 双生产者无落库定义（adversarial）**：与 RecordUnderstandingService 双写标签、无落库定义 → 标签刷新即消失（U30）。
- **P1-8 耗时口径矛盾（frontend）**：api_service.dart:31 注释「实测 7~27s」vs 方案「聊天 30~60s+」——方案唯一实锤 45~60s 是 /trading/advice（B 档），聊天无实锤，需压测定口径。
- **P1-9 _TimeoutClient 包级超时对 SSE 无效（frontend）**：`send().timeout(90s)` 首字节即完成，方案未写 Stream.timeout 空闲超时/首字节超时/独立 stream 客户端的实现路径。
- **P1-10 sse_client 三端缺验证步骤（frontend）**：pubspec 未引入过；Web 流式依赖 fetch 流式行为有历史坑 + CORS 需放行 event-stream，违背 AGENTS.md 三端门。
- **P1-11 批 1 验收「5~10 倍」不成立且无质量门槛（adversarial）**：长会话（批 3 才治上下文）下收益打折；无质量量化门槛 → 脏标签进记忆、产品退化。

## P2（次要）

- P2-1 `_index.md` 登记后 updated 未同步（08-22 未更新；docs）｜P2-2 api-spec 应移 depends-on（图谱语义；docs）｜P2-3 docs/README.md 登记待确认（docs）
- P2-4 C 档「重试 2 次」≠ MAX_ATTEMPTS=2（实际重试 1 次；backend）｜P2-5 trading 单记录最坏串行 4 次 LLM（collect+isTradeStatement 双 parseLoose 可合并；backend）｜P2-6 HttpRequest.timeout 整包语义 vs 空闲超时需自建 watchdog（backend）
- P2-7 LoggingAiClient model 硬编码 "deepseek" 失真 + B28 接口同步坑（backend）｜P2-8 配置键名 `adai.ai.vision-model` 实为 `adai.ai.vision.model`（backend）｜P2-9 SseEmitter 并发 send 需串行化（backend）
- P2-10 chunk 高频 setState+scroll 抖动 / MarkdownBody 半截闪烁（frontend）｜P2-11 [DONE]/断连/meta 时序契约未定义（frontend）｜P2-12 批 2 验收缺前端测试项（frontend）
- P2-13 「三端统一」vs「web 二期」措辞矛盾；「四维矩阵」标题含 5 小节（docs P3）

## 核查通过（backend 逐条验证属实）

超时 60s / 重试 1 次、意图识别 15s、相关 20 条 / 搜索 10 条 / 记忆 7 天 / 知识全量注入、历史无截断、@Primary 装饰器链路、vision-model 已有配置、LlmResponseParser 末尾 JSON 机制可平移到流式收尾。frontmatter 十字段合规、lines 135 与 wc -l 一致、depends-on/related 四引用零断链。

## 修订建议（等用户拍板后改文档）

1. 修超时矩阵（S-1，写死 120s 推导或改后端超时）——唯一 ⭐⭐⭐⭐ 必须修
2. 路由键 scene() → AiTraceContext.source（S-2）
3. 补降级一致性定义（P0-1：落库时机/幂等/RecordRetryService 衔接）
4. 补 SseEmitter async timeout（P1-2）+ 状态机衔接（S-6）+ 取消契约（S-7）
5. 修调用点计数 13→12（P1-1）+ 耗时口径待实测（P1-8）
6. 补熔断（S-3）、摘要数字保留（S-5）、meta 落库（P1-7）等定义
7. 小修：_index updated / api-spec 移 depends-on / 措辞（P2-1/2/13）

## 新增检查点建议

| # | 检查内容 |
|:-:|:---------|
| D61 | 方案/设计文档「自述调用点计数」必须与正文盘点全表行数对拍（防 13 vs 12 口径漂移）|
| D62 | 方案内「原则-数值」自洽校验：原则须逐条代入矩阵数值验证不等式（防照抄错误数值）|
| D63 | 流式/SSE 事件格式不在 guard-align 端点对齐覆盖范围：批次验收须显式含「api-spec 升版 + changelog 行」|
| B62 | 路由/分档类方案先核对 `AiTraceContext.source` 等既有键是否已可区分调用语义，再设计新路由键 |
