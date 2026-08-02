---
title: 项目审核全量状态报告
updated: 2026-08-02
last-review: 2026-08-02
baseline: cc537db
mode: --deep 增量（adai-web 交付）
---

# 项目审核状态报告

常驻全量状态。每次 `/review` 更新本文件——未修复项滚动保留，已修复标 ✅ 移入已修复区，新问题追加。git 历史天然保留每次更新前的快照。

## 最近审核

| 日期 | 模式 | 基线 | 派发角色 | 新增 | 修复 |
|:-----|:-----|:-----|:---------|:-----|:-----|
| 2026-08-02 | deep 增量（adai-web）| cc537db..HEAD | frontend/product/docs ×3 | P0×1 + 战略×3 + P1×9 + P2×8 + P3 打磨若干 | 0 |
| 2026-08-02 | full 全量（v0.1.0 发布前）| — | backend/frontend/docs/product/knowledge ×5 | 前端 3 项 + 后端 P1 4 项 + 文档 | 后端 P1 4 项 + 文档契约 |
| 2026-08-01 | 全量（初始） | — | backend / frontend / arch ×3 | 32 | 23 |
| 2026-08-01 | deep 增量 | a4a7c12..cd1231b | docs / knowledge | 11 新 / 1 升级 | 0 |

## 🔴 未修复项（当前待办）

| # | 优先级 | 问题 | 位置 | 状态 |
|:-:|:------:|:-----|:-----|:----:|
| 19 | P2 | Feed/Context/Memory 每次全量遍历 data 目录 | `RecordFileRepository.findAll` | 📋 待办（数据量小）|
| 22 | P2 | kernel 反向依赖 infrastructure 类型 | `IntentRecognizer`/`ContextEngine`/`MemoryService` | 📋 待办（有意跳过，高风险）|

### adai-web 交付批次（100+，2026-08-02 deep）

| # | 优先级 | 问题 | 位置 | 状态 |
|:-:|:------:|:-----|:-----|:----:|
| 100 | P0 | **Feed `_appendToActiveCard` 异步竞态崩溃**：对话中发消息 → AI 回复到达前点 end（`_closeChat` 置 `_activeCardId=null`）→ 回复返回 `setState(_updateCard(_activeCardId!))` 空值断言崩溃 + 回复丢失。**值复制自 adai-app `main_page.dart:325` 同有** | `feed_page.dart:161,171` | 📋 待修（优先）|
| 101 | 战略 | Feed 无「加载更早」分页：只拉前 20 条，`totalToday>20` 更早记录不可达且无入口（移动端有 `_loadMore`）| `feed_page.dart:55` | 📋 待办 |
| 102 | 战略 | 交易页无复盘入口：五层架构「交易系统反哺」在桌面主入口不可达（后端有 review 端点）| `trading_page.dart` | 📋 待办 |
| 103 | 战略 | Timeline/Memory 保活数据陈旧：initState 只拉一次 + IndexedStack offstage 保活，切页不重载且无刷新入口 | `timeline_page.dart:27` / `memory_page.dart:23` | 📋 待办 |
| 104 | P1 | 删除 active 卡 `_activeCardId` 残留 → 后续输入走 append 到已删卡 no-op 但照常 POST（后端生成、前端不可见）+ hint 卡死「继续对话」| `feed_page.dart:266` | 📋 待办 |
| 105 | P1 | `_onAskCard` 重开已有 turns 卡不 `_deactivateOtherCards` → 双卡 chatting 互踩，点旧卡 end 杀死新会话 | `feed_page.dart:180` | 📋 待办 |
| 106 | P1 | api-spec portfolio 契约失真：示例 `totalMarketValue/totalPnlPercent` 后端不存在（实际 `totalValue/cashBalance/totalPnl/totalCost`）；adai-web `PositionItem.positionCount` 后端无此字段 → 交易页「持仓数」stat 卡恒 0 | `api-spec.md:291` / `api_service.dart:716` | 📋 待办 |
| 107 | P1 | API 缓存失效不全：`markMemoryDone`/`deleteRecord` 不清 `_memoryCache` → 记忆页「待办」陈旧（doneAt 过期）| `api_service.dart:59-74` | 📋 待办 |
| 108 | P1 | 后端故障与「无数据」混淆（memory/timeline/task/search 四页）+ 保活下无重试按钮 → 失败态死路，用户无法区分「真没数据」与「系统坏了」| 四页 catch 分支 | 📋 待办 |
| 109 | P1 | 记录删除无确认：`DELETE /records/{id}` 连带清理 record+card+memory，桌面 Feed 裸删（移动端任务删除有确认弹窗）| `feed_page.dart:266` | 📋 待办 |
| 110 | P1 | 卡片重试用全新 cardId 重新 POST → 半失败场景（首 POST 已成功仅响应丢失）可能重复入账（后端有 `/records/retry` 专用）| `feed_page.dart:409` | 📋 待办 |
| 111 | P1 | 对话恢复（点 ask 续聊已有 turns 卡）不置 `mode:chatting` → 状态机显示与实际不同步（底部仍 `ask` 非 `end`）| `feed_page.dart:184` | 📋 待办 |
| 112 | P1 | CANCELLED 任务在看板不可见（`_statusOrder` 只含 TODO/DOING/DONE，被 where 过滤消失）| `task_page.dart:21` | 📋 待办 |
| 113 | P2 | 交易页错误态暴露原始 `ApiException(500):...` 技术串 | `trading_page.dart:46` | 📋 待办 |
| 114 | P2 | 记忆页切日期先清空列表 → 闪烁「该日暂无记忆」| `memory_page.dart:43` | 📋 待办 |
| 115 | P2 | Feed 右栏（简报/标签云/任务快照）不随操作/刷新更新，数据陈旧 | `feed_page.dart:75` | 📋 待办 |
| 116 | P2 | 记录交易成功无反馈（静默重载）| `trading_page.dart:53` | 📋 待办 |
| 117 | P2 | 测试覆盖缺口：最复杂的 Feed 状态机（send/ask/chatting/end/retry/error/delete）零覆盖；缓存 key 分桶未测；6 页面无 widget 测试 | `test/` | 📋 待办 |
| 118 | P2 | `_check` 用 `resp.body`（非 utf8）构造 ApiException body → 中文错误可能乱码 | `api_service.dart:348` | 📋 待办 |
| 119 | P2 | `_totalToday` 本地不更新（发消息/删除后 header 计数陈旧）| `feed_page.dart:104` | 📋 待办 |
| 120 | P3 | 行情卡纯文本无红涨绿跌着色；action/market 简单卡不显示时间戳 | `desktop_feed_card.dart` | 📋 待办 |
| 121 | P3 | 无最小宽度/响应式保护：窄窗下 nav200+侧栏300 挤压主区 | `desktop_shell.dart` | 📋 待办 |
| 122 | P3 | frontend-reference 颜色表旧色值（`#0E0E0E`/`#2BC457` vs 实际 `#131211`/`#3AB75A`）+ API 速查表缺 adai-web 消费的 8 端点 | `frontend-reference.md` | 📋 待办 |
| 123 | P3 | 导航「Feed」中英混排（vs 页头「对话流」）；task quick-add「quick add」混排；project 副标题「commits/APIs」；`_extractApiError` 兜底 `network error` 英文 | 三页文案 | 📋 待办 |
| 124 | P3 | adai-web/CLAUDE.md 端口写 `:8081`（实际 serve 脚本 `:8082`）| `apps/adai-web/CLAUDE.md:32` | 📋 待办 |
| 125 | P3 | 其余：README 默认模板 / aiNote 死代码 / hover 无手型 / 圆角 token 散落 / 无快捷键 / CLAUDE.md apps/ 块重复 | — | 📋 待办 |

## ✅ 已修复区（最近 10 条，旧条目随滚动删除）

| # | 问题 | 修复 |
|:-:|:-----|:-----|
| 60/61/62 | v0.2.0 前端 actionable UI 消费：action 待办卡+完成按钮（`ca2d4a8`）、Feed 分页终止修复（totalToday 只计核心，`ca2d4a8`）、memory 页 kind/superseded/待办展示（`7d9b607`）| ✅ 2026-08-02 |
| 后端 P1 ×4 | actionable 筛选豁免（P1-1）+ 无限重补修复（重补筛选改已处理判定）+ ID 单调统一 IdGenerator（P1-2）+ rebuild 幂等（P1-3）+ 跨日升级（P1-4，findByRecordId 365 天）（`c41c2b7`）| ✅ 2026-08-02 |
| 13 | interfaces 层编排重复三处 → RecordUnderstandingService 统一 compose→understand（`bdb83da`）| ✅ 2026-08-02 |
| 33/38/39/41/21/23 | 第三批 6 项：review 路由表补 `.claude/**`（自审盲区闭合）；docs/README 索引登记 reference/decisions；os definition 加架构愿景声明+修正"os 实现接口"表述；data-flow 更新卡片路径/组装流程/断裂点；ProjectFileRepository save/delete 保留手写注释；TradingController conflicts 改解析真实 rules.md（空仓→R119、单吊→R96）| ✅ 2026-08-01 |
| 12/24/14 | 第二批代码修复 3 项：记忆沉淀断裂（AI 失败降级原文入记忆标 DEGRADED + persist 升级语义洞察覆盖 + 重补过滤防阻塞 + summary 兜底移出 try）、复盘改走 ContextEngine（trading 场景注入规则/知识/行情 + 复盘模板）、测试缺口（新增 Memory/复盘/场景路由 9 个测试；行情/Feed分页/ContextEngine 原已有覆盖，REVIEW 描述过时）| ✅ 2026-08-01 |
| 16a/34/35/36/37/40/42/43 | 第一批快修 8 项：孤儿卡片迁移 `records/cards/`+清空目录；ship grep 路径修正（api-spec 不再静默跳过）；RFC 滚动 implemented/5 角色/REVIEW.md；guard.md G5/G7 对齐 guard.sh；trading README 补 definition；CLAUDE.md 焦点更新+目录图/data 表述修正；guard.sh G1 空匹配防挂起；零碎拼写/篇数/术语格式 | ✅ 2026-08-01 |
| 25-32 | 前端 P3 打磨 8 项（URL 编码/文本清理统一/日期硬编码/静默刷新/方向图常量/死代码/light主题） | ✅ 2026-08-01 |
| 15-20 | 数据目录 + api-spec v3.1 + CLAUDE.md 对齐 + 任务扫全部月份 | ✅ 2026-08-01 |
| 7-11 | P1 功能 bug 5 项（emoji 代理对/行情缓存键/AI失败保数据/mounted守卫/PnL序列化）| ✅ 2026-08-01 |
| 3-6 | 战略缺口（场景路由打通/Life知识注入/大盘始终注入）| ✅ 2026-08-01 |
| 1-2 | P0 数据丢失（ID 毫秒/Card 路径按 createdAt）| ✅ 2026-08-01 |

## 执行成本

| 日期 | 模式 | 派发角色 | agent 数 | 耗时 | 新增 | 修复 |
|:-----|:-----|:---------|:--------:|:-----|:----:|:----:|
| 2026-08-01 | 全量 | backend/frontend/arch | 3 | ~2h | 32 | 23 |
| 2026-08-01 | deep 增量 | docs/knowledge | 2 | ~25min | 11+1升级 | 0 |
| 2026-08-02 | light 增量 | — | 0 | ~2min | 0 新 | 0 |
| 2026-08-02 | full 全量（v0.1.0）| backend/frontend/docs/product/knowledge | 5 | ~25min | 前端 3 项 + 后端 P1 4 项 + 文档若干 | 后端 P1 4 项 + 文档契约 |
| 2026-08-02 | light 增量（v0.2.0）| — | 0 | ~3min | 0 新 | 0 |
| 2026-08-02 | light 增量（多账号预留）| — | 0 | ~5min | 0 新 | 0 |
| 2026-08-02 | deep 增量（adai-web）| frontend/product/docs | 3 | ~10min | P0×1+战略×3+P1×9+P2×8+P3 若干 | 0 |

> 跑几轮后用成本数据决定全量审核频率（目前建议：增量随时，全量 1-2 周一次）。
