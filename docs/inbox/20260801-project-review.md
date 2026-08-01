---
title: 项目审查报告 2026-08-01
date: 2026-08-01
status: active
scope: 全仓库（后端/前端/架构知识/数据文档）
method: 3 路并行审查 Agent（后端、前端、架构知识打通）
---

# 项目审查报告 2026-08-01

## 一、总评

代码骨架就绪度**远高于**数据与闭环就绪度。最近 12 天（7-20 → 8-01）进步明显：行情接入、任务系统修复、Memory 升级、CHAT 注入修复。本次审查发现：**2 个 P0 静默数据丢失 bug（已修复）+ 1 个核心架构缺口（交易知识 73KB 从未真正进入 AI）**。

**一句话结论：** 系统"能跑通主流程"，但知识资产（os/trading-os）与代码运行流之间存在断裂，且部分数据目录与文档契约滞后。

## 二、审查方法（可复用，下次照此执行）

### 维度与检查点

| # | 维度 | 检查方法 | 上次发现 |
|:-:|:-----|:---------|:---------|
| 1 | **ID 唯一性** | grep 所有 `generateId()`/`ID_FORMATTER`，确认含毫秒 `SSS` | Record/Memory 漏加毫秒 → 同秒覆盖（P0）|
| 2 | **文件路径一致性** | Repository 的 `filePath()` 是否从实体 `createdAt` 推导，而非 `now()` | Card 用 `LocalDate.now()` → 跨日复制（P0）|
| 3 | **正则健壮性** | 检查 DOTALL 正则的 `.+`/`.*` 是否跨行贪婪 | ENTRY_PATTERN 吞文件只解析 1 条 |
| 4 | **场景路由有效性** | `ContextEngine.compose()` 收到的 scene 与 `ContextContributor.supports()` 是否真的匹配 | `"trading"` scene 从未传入 → 知识注入全失效 |
| 5 | **缓存键一致性** | 缓存 put/get 键是否同一规范（前缀 vs 6 位代码） | 腾讯行情缓存永久 miss |
| 6 | **异步生命周期** | 前端 `setState` 前是否 `mounted` 守卫（尤其 initState 触发的加载） | `_loadFeed`/`_loadMore` 漏守卫 |
| 7 | **前后端 DTO 契约** | record component vs Jackson 序列化，前端 fromJson 期望键是否一致 | Position 计算字段不序列化 → PnL 恒 0 |
| 8 | **文档-代码一致性** | api-spec.md 端点 vs Controller vs 前端调用，三者对齐 | 缺 7 个端点 |
| 9 | **数据目录健康** | `data/` 是否存在损坏/孤儿/测试文件；`data/` 结构 vs 文档声称 | 缺 identity/trading 目录；混入 10+ 测试文件 |
| 10 | **反馈闭环** | 知识反哺链路（复盘→promote→入库）是否有真实产物 | Layer 6 从未运转 |

### 执行流程

1. 派 3 路并行审查 Agent（后端 / 前端 / 架构知识），按上表维度
2. 汇总发现，按 P0（数据丢失）→ 战略缺口 → P1 → P2/P3 排序
3. 更新本文件"状态跟踪"表：`状态` 列改为 ✅已修 / 📋待办 / 🔒暂缓
4. P0 与战略缺口立即修，其余进任务清单

## 三、本次发现与状态跟踪

### 🔴 P0 — 静默数据丢失（威胁个人数据资产）

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| 1 | Record/Memory ID 秒级精度 → 同秒覆盖 | `RecordFileRepository.java:28`、`Memory.java:69` | ✅ 已修（加 `SSS`）|
| 2 | Card 保存路径固定"今天" → 跨日复制丢轮次 | `CardFileRepository.java:145` | ✅ 已修（按 `createdAt` 推导）|

### 🔴 战略缺口 — 知识从未真正进入 AI

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| 3 | `os/trading-os` 73KB 知识（规则/教训/策略）从不注入 | `ContextEngine.compose()` 无 `"trading"` scene 调用 | ✅ 已修（内容关键词路由 domainScene）|
| 4 | 场景化 Contributor 全失效（trading/project/life）| 各 `*ContextContributor.supports()` | ✅ 已修（随 #3 路由触发）|
| 5 | Life OS 知识资产在但代码不消费（`life-os-path` 死配置）| `LifeKnowledgeSource.java` | ✅ 已修（读取 identity.md + Memory 聚合）|
| 6 | 行情注入实际为空（`positions.md` 不存在 + globalContext 空持仓返回空）| `MarketContextContributor.java` | ✅ 已修（globalContext 大盘始终注入）|

### 🟠 P1 — 功能性 bug

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| 7 | LlmResponseParser 遇 emoji 代理对抛异常 → 整段降级丢字段 | `LlmResponseParser.java:185` | ✅ 已修（region 跳过代理对 + 单测）|
| 8 | 腾讯行情缓存键不一致 → 缓存永久 miss | `TencentMarketDataSource.java:62` | ✅ 已修（解析用行前缀提取带前缀键 + 4 单测）|
| 9 | AI 失败时删除刚保存的用户记录 | `RecordController.java:95` | ✅ 已修（保留记录返回降级，RetryService 补齐）|
| 10 | 前端 `_loadFeed`/`_loadMore` 无 mounted 守卫 | `main_page.dart:92,101,446` | ✅ 已修（补 `!mounted` 守卫）|
| 11 | 持仓 PnL 恒 0（后端计算字段不序列化）| `Position.java` + `api_service.dart:642` | ✅ 已修（`@JsonGetter` + 序列化单测）|
| 12 | 复盘绕过 ContextEngine 手拼 prompt，规则从不进 prompt | `TradingReviewAppService.java` | 📋 待办 |

### 🟡 P2 — 架构/工程

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| 13 | interfaces 层编排重复三处（compose→understand→persist）| `RecordController`/`RecordFlowAppService`/`RecordRetryService` | 📋 待办 |
| 14 | 测试缺口：行情/Memory/Feed分页/ContextEngine/Phase4 页面零测试 | `src/test` | 📋 待办 |
| 15 | `data/identity/`、`data/trading/` 目录不存在 | `data/` | ✅ 已修（建目录 + sample 文件）|
| 16 | `data/records` 混入 10+ 孤儿/测试文件 | `data/records/2026/07/` | ✅ 已修（删 8 孤儿，3 个历史对话卡片保留待迁移）|
| 17 | api-spec 缺 7 个端点 | `docs/architecture/api-spec.md` | ✅ 已修（v3.1 补全）|
| 18 | 根 CLAUDE.md 描述过期（DECISION/正则兜底/B Phase 4 待做）| `CLAUDE.md`/`data-flow.md` | ✅ 已修 |
| 19 | Feed/Context/Memory 每次全量遍历 data 目录 | `RecordFileRepository.findAll` | 📋 待办 |
| 20 | 任务只扫最近 12 个月 | `ProjectFileRepository.java:78` | ✅ 已修（listFiles 扫描全部月份）|
| 21 | save/delete 重建文件丢弃手写注释 | `ProjectFileRepository`/`MemoryService` | 📋 待办 |
| 22 | kernel 反向依赖 infrastructure 类型 | `IntentRecognizer`/`ContextEngine`/`MemoryService` | 📋 待办 |
| 23 | Layer 6 反馈闭环从未运转（conflicts 硬编码）| `TradingController.java` | 📋 待办 |
| 24 | 记忆沉淀稀疏（54 记录仅 2 Memory）| `data/memory/` | 📋 待办 |

### 🟢 P3 — 打磨

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| 25 | 前端 search query 未 URL 编码 | `api_service.dart:191` | ✅ 已修（queryParameters）|
| 26 | AI 回复 JSON 解码逻辑两处重复且 emoji 处理不一致 | `main_page.dart`/`feed_card.dart` | ✅ 已修（提取 `utils/text_cleaner.dart` 统一代理对版本）|
| 27 | 时间线弹窗硬编码"7月" | `timeline_modal.dart:141` | ✅ 已修（`_baseDate.month`）|
| 28 | Memory 页"昨天"判断跨月出错 | `memory_page.dart:42` | ✅ 已修（日期整体比较）|
| 29 | 任务/交易页每次操作闪整页 Spinner | `project_task_page.dart`/`trading_page.dart` | ✅ 已修（拆 `_refresh` 静默刷新）|
| 30 | 项目状态页方向进展图硬编码 | `project_status_page.dart:202` | ✅ 已修（提为常量并注明来源）|
| 31 | `invalidateFeedCache()` 空方法死代码 | `api_service.dart:63` | ✅ 已删 |
| 32 | light 主题死代码、InputBar 语音 stub | `app_theme.dart`/`input_bar.dart` | ✅ 已修（删 light 主题；stub 灰置提示）|

## 四、改进优先级（本次执行）

1. **P0 数据丢失**（#1/#2）— ✅ 本次已修复
2. **知识注入打通**（#3/#4/#5/#6/#12）— 战略价值最高，单独任务
3. **文档契约**（#17/#18）— 补 api-spec + 修正 CLAUDE.md
4. P1 功能 bug（#7-#11）— 逐步修复

## 五、附：三路审查原始报告

- 后端：P0×2、P1×5、P2×8（含测试缺口、分层违规、死代码）
- 前端：P1×2、P2×5、P3×5
- 架构知识：严重×2、重要×7、建议×4
