---
title: 项目审核全量状态报告
updated: 2026-08-03
last-review: 2026-08-03
baseline: ce3f19f
mode: --full 全量（v0.3.0 发布前）
---

# 项目审核状态报告

常驻全量状态。每次 `/review` 更新本文件——未修复项滚动保留，已修复标 ✅ 移入已修复区，新问题追加。git 历史天然保留每次更新前的快照。

## 最近审核

| 日期 | 模式 | 基线 | 派发角色 | 新增 | 修复 |
|:-----|:-----|:-----|:---------|:-----|:-----|
| 2026-08-03 | full 全量（v0.3.0 前）| — | backend/frontend/docs/product/knowledge ×5 | P0×1 + 战略×7 + P1×13 + P2/P3×30 | 0 |
| 2026-08-02 | deep 增量（adai-web）| cc537db..HEAD | frontend/product/docs ×3 | P0×1 + 战略×3 + P1×9 + P2×8 + P3 打磨若干 | 0 |
| 2026-08-02 | full 全量（v0.1.0 发布前）| — | backend/frontend/docs/product/knowledge ×5 | 前端 3 项 + 后端 P1 4 项 + 文档 | 后端 P1 4 项 + 文档契约 |
| 2026-08-01 | 全量（初始） | — | backend / frontend / arch ×3 | 32 | 23 |
| 2026-08-01 | deep 增量 | a4a7c12..cd1231b | docs / knowledge | 11 新 / 1 升级 | 0 |

## 🔴 P0（数据丢失 / 崩溃）

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| 126 | **Memory 月文件并发读-改-写丢失**：`persist`/`touchActive`/`replaceEntry`/`removeFromFile` 全部无锁 + `LocalFileStorage.write` 非原子（TRUNCATE_EXISTING）。两个并发写者命中同一月文件 → 后写基于过期快照覆盖先写 → **记忆静默永久丢失**。触发场景真实：定时重补（15min 每 3 秒 sleep）+ 用户发消息并发。与历史 P0「同毫秒覆盖」同族，只是从 ID 碰撞换成 RMW 覆盖 | `MemoryService.java:51,375,471,534` / `LocalFileStorage.java:41-50` | 📋 待修（优先，与 #127 同批次）|
| 100 | **Feed `_appendToActiveCard` 异步竞态崩溃**：对话中发消息 → AI 回复到达前点 end（`_closeChat` 置 `_activeCardId=null`）→ 回复返回 `setState(_updateCard(_activeCardId!))` 空值断言崩溃 + 回复丢失。adai-app `main_page.dart` 同有 | `feed_page.dart:181-210` / `main_page.dart:357` | 📋 待修（优先）|

## 🔴 战略缺口

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| 101 | Feed 无「加载更早」分页：只拉 `size:20`，`totalToday>20` 更早记录不可达且无入口 | `feed_page.dart:55-56` | 📋 待办 |
| 102 | 交易页无复盘入口（**升级**：adai-app 移动端同样没有——两个产品主入口的「交易系统反哺」都不可达，复盘成了 admin 后台功能而非个人 OS 能力）| `trading_page.dart`（web+app）| 📋 待办 |
| 103 | Timeline/Memory 保活数据陈旧：initState 只拉一次 + IndexedStack offstage 保活，切页不重载且无刷新入口 | `timeline_page.dart:26-29` / `memory_page.dart:22-26` | 📋 待办 |
| 127 | **多账号零鉴权**：`X-User-Id` 纯客户端头 + `/admin`、`/accounts` 端点裸奔 + CORS `*`。任何调用者带 `X-User-Id: victim` 即读写任意用户 `data/`（含隐私资产）。生产 49.235.37.220 公网 IP | `AdminController`/`AccountController` / `WebConfig.java:16-20` | 📋 待办（v1.0.0 前硬缺口）|
| 128 | 定时重补只处理 `default` 用户：多账号启用后其他用户 AI 失败记录永不自动重补 | `RecordRetryService.java:61-64` | 📋 待办 |
| 129 | 知识反哺闭环零产物：复盘→promote→99-inbox 代码链路完整但从未产出真实文件，Layer 6 从未运转 | `os/trading-os/08-review/.gitkeep` / `data/default/trading/` | 📋 待办 |
| 130 | **VISION/architecture 五层状态表过期**：Layer4 多模态 ❌、Layer5 未开始 ❌、DECISION ✅——与 2026-08-02 现实矛盾，VISION 是每会话必读，会持续误导 | `VISION.md:208-210` / `product-architecture.md:94,101` | 📋 待办 |
| 131 | 三端文案语言策略不一致：移动端大量英文微文案（`memory`/`no memories today`）、桌面中文夹杂英文（#123）、管理端全中文——核心词「记忆」三端不同 | `adai-app/pages/memory_page.dart:122` 等 | 📋 待办 |
| 132 | 涨跌/盈亏颜色三套语义并存：桌面红=亏/绿=盈，移动与管理端橙=亏/绿=盈，#120 行情卡要求红涨绿跌（语义相反）| `adai-web/pages/trading_page.dart:129` / `adai-app:273` / `adai-admin:83` | 📋 待办 |

## 🔴 P1

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| 104 | 删除 active 卡 `_activeCardId` 残留 → 后续输入走 append 到已删卡 no-op 但照常 POST + hint 卡死「继续对话」| `feed_page.dart:302-310` | 📋 待办 |
| 105 | `_onAskCard` 重开已有 turns 卡不 `_deactivateOtherCards` → 双卡 chatting 互踩，点旧卡 end 杀死新会话 | `feed_page.dart:216-224` | 📋 待办 |
| 106 | api-spec portfolio 契约失真：示例 `totalMarketValue/totalPnlPercent` 后端不存在（实际 `totalValue/cashBalance/totalPnl/totalCost`）；adai-web `PositionItem.positionCount` 后端无此字段 → 「持仓数」恒 0 | `api-spec.md:322-329` / `api_service.dart:769-775` | 📋 待办 |
| 107 | API 缓存失效不全：`markMemoryDone`/`deleteRecord` 不清 `_memoryCache` → 记忆页「待办」陈旧 | `api_service.dart:59-75` | 📋 待办 |
| 108 | 后端故障与「无数据」混淆（memory/timeline/task/search 四页）+ 保活下无重试按钮 → 失败态死路（**扩展**：档案页是第五页，`profile_page.dart:56-59`）| 四页 catch 分支 | 📋 待办 |
| 109 | 记录删除无确认：`DELETE /records/{id}` 连带清理 record+card+memory，桌面 Feed 裸删 | `feed_page.dart:302` | 📋 待办 |
| 110 | 卡片重试用全新 cardId 重新 POST → 半失败场景可能重复入账 | `feed_page.dart:445-451` | 📋 待办 |
| 111 | 对话恢复不置 `mode:chatting` → 状态机与 UI 不同步（底部仍 ask）| `feed_page.dart:216-224` | 📋 待办 |
| 112 | CANCELLED 任务在看板不可见（`_statusOrder` 只含 TODO/DOING/DONE）| `task_page.dart:21` | 📋 待办 |
| 133 | **记忆 kind/actionable 三端体系失真**：后端真实 kind 为 `fact/insight/preference/pattern/decision`，adai-web 徽标 switch 只处理 `action/question`（永不命中 → 待办/已完成不可见、decision 显示英文）、adai-admin 筛选值写 `actionable/summary/meta`（待办筛选恒空）。adai-app 正确 | `adai-web/pages/memory_page.dart:185-198` / `adai-admin/pages/data/memory_tab.dart:31-37` | 📋 待办 |
| 134 | **错误态技术串泄漏蔓延 6+ 处**：`图片上传失败: $e`/`记录交易失败: $e`/`保存失败: $e`/`记录失败: $e` 直拼原始 ApiException，绕过各端已有的 `_extractApiError` 人话映射（#113 只记交易页一处，实际蔓延）| `adai-web/pages/feed_page.dart:120` / `trading_page.dart:71` / `profile_page.dart:80` / `adai-app/main_page.dart:277` 等 | 📋 待办 |
| 135 | **frontmatter 多行泄漏（record summary + memory suggestion 两处）**：AI 返回 JSON/含换行内容时 `toMarkdown` 不单行化直接写入 → summary 退化为 `{`、suggestion 后续行被拆成伪字段，行式解析失败条目静默不可见。**08 月已有 3 条记录中招（含 `rec_20260802_213837678`），持续复发** | `RecordFileRepository.java:175-198` / `MemoryService.java:574-611` | 📋 待办（活跃 bug）|
| 136 | **deleteById/findMediaPath 从 ID 内嵌时间戳推导路径**，与 save 从 createdAt 推导不一致 → 月边界（7-31 23:59 vs 08-01）静默删除失败，前端删后记录复活 | `RecordFileRepository.java:87-100,139-143` | 📋 待办 |
| 137 | **删除记录不清理 TagIndex**：tags.json 幽灵计数、`findRelatedIds` 返回已删 ID、firstAt/lastAt 失真 | `RecordFileRepository.java:87-100` / `TagIndexService`（无删除钩子）| 📋 待办 |
| 138 | **PositionFileRepository.saveAll 硬编码 cashBalance=0**：每次 recordTrade 重写文件清除手工维护的现金余额 | `PositionFileRepository.java:119-135` | 📋 待办 |
| 139 | **卡片数据重复**：`records/cards/2026/07/22/` 3 对同对话双副本（raw + `card_` 前缀快照），`findAll` 读成 6 张卡 → findActiveCard 可能选中陈旧快照、retryCards 双处理 | `data/default/records/cards/2026/07/22/` | 📋 待办（需确认活跃副本后清一次）|
| 140 | adai-admin 任务优先级 round-trip 失真：后端 P0/P1 都映射 `high`，`high` 只回 P0 → P1 任务保存后降级为 P0 | `adai-admin/services/data_api_store.dart:227-238` | 📋 待办 |
| 141 | README 索引缺 3 篇 RFC：`20260802-multimodal-image-glm`（本版重点，/ship 漏登记）、`20260729-development-retrospective`、`20260730-health-management-scenario`（23 篇只登 20）| `docs/README.md:42-66` | 📋 待办 |
| 142 | roadmap 模型名过期：还写 `GLM-4.6V-Flash`，代码/RFC 已改 `glm-4.1v-thinking-flash`（40edfe5 早于模型修正）| `product-roadmap.md:71` | 📋 待办 |
| 143 | **多模态 RFC 缺 YAML frontmatter** → `ProjectStatusAppService.parseRfcFrontmatter` 失败，`/project/status` 显示 `status=unknown`，与其余 22 篇结构不一致 | `docs/rfc/20260802-multimodal-image-glm.md:1-6` | 📋 待办 |
| 144 | intent 字段不落盘 + fact 记忆被 Phase5 跳过 → rebuild 对 fact-only 记录永不幂等，每次全量重跑烧 AI | `RecordFileRepository.java:224` / `MemoryController.java:136-139` | 📋 待办 |
| 145 | 移动端 API 全量 `resp.body` 解码（非 utf8）→ 中文乱码隐患（#118 只修了 web 的 `_check`，移动端是整层缺口）| `adai-app/services/api_service.dart:31,46,100...` | 📋 待办 |

## 🔴 P2

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| 19 | Feed/Context/Memory 每次全量遍历 data 目录（本轮佐证：persist 单次扫 365+30 天、TagIndex 无删除钩子，遍历面扩大）| `RecordFileRepository.findAll` | 📋 待办（数据量小）|
| 22 | kernel 反向依赖 infrastructure（**已增第 4 处**：`kernel/memory/Memory.java` 依赖 `AiUnderstanding`，原 3 处之外新出现）| `IntentRecognizer`/`ContextEngine`/`MemoryService`/`Memory.java:3,69` | 📋 待办 |
| 113 | 交易页错误态暴露原始 `ApiException(500):...` 技术串（#134 主项）| `trading_page.dart:47,111` | 📋 待办 |
| 114 | 记忆页切日期先清空列表 → 闪烁「该日暂无记忆」| `memory_page.dart:43-51` | 📋 待办 |
| 115 | Feed 右栏（简报/标签云/任务快照）不随操作/刷新更新，数据陈旧 | `feed_page.dart:76-88` | 📋 待办 |
| 116 | 记录交易成功无反馈（静默重载）| `trading_page.dart:53-67` | 📋 待办 |
| 117 | 测试覆盖缺口：Feed 状态机零覆盖；缓存 key 分桶未测；6 页面无 widget 测试 | `test/` | 📋 待办 |
| 118 | `_check` 用 `resp.body`（非 utf8）构造 ApiException body → 中文可能乱码（#145 移动端整层）| `api_service.dart:377` | 📋 待办 |
| 119 | `_totalToday` 本地不更新（发消息/删除后 header 计数陈旧）| `feed_page.dart:104` | 📋 待办 |
| 146 | HEIC/未知图片 content-type 落 `.png` 扩展名但字节原格式 → GET 错误 MIME 预览坏 + VLM 降级 | `MediaRecordAppService.java:118-125` / `MediaController.java:73-78` | 📋 待办 |
| 147 | SELL 未持有 symbol 静默 no-op：既不报错也不落痕迹（交易记录消失）；positions saveAll 写无锁 | `TradingAppService.java:40-66` | 📋 待办 |
| 148 | Feed ai_note 按记忆沉淀日期展示：重补/升级跨日后归属错日（7/22 记录洞察出现在 8/2 Feed）| `FeedAppService.java:65,92,194-201` | 📋 待办 |
| 149 | 多账号细节三件套：accounts.json 读改写无锁 / 删号不清理 `data/{userId}/`（重建「复活」旧数据）/ 允许创建 `default` | `AccountFileRepository.java:81-107` / `AccountController.java:48-100` | 📋 待办 |
| 150 | `/project/status` 的 `apiEndpoints=21` 硬编码（实际 44+）；FeedAppService 死依赖 BriefAppService | `ProjectStatusAppService.java:136-140` / `FeedAppService.java:40-47` | 📋 待办 |
| 151 | 记忆悬空 recordId：`mem_20260802_104626498` → `rec_20260802_104616531` 无对应记录文件（144 引用中唯一悬空）| `data/default/memory/2026/08.md` | 📋 待办 |
| 152 | tags.json count 字段漂移：「三体」count=64 vs recordIds=50（MAX_RECORDS_PER_TAG 截断 + 重复累加）| `data/default/index/tags.json` | 📋 待办 |
| 153 | 数据形态失衡：08 月 131/133 条为对话摘要，原始 note 仅 2 条（<2%）| `data/default/records/2026/08/` | 📋 观察 |
| 154 | CLAUDE.md 架构树缺 MediaController/AccountController/AdminController + `ai/vision/`（树列 12 vs 实际 15 Controller）| 根 `CLAUDE.md:121-133` | 📋 待办 |
| 155 | adai-core/CLAUDE.md 遗留 DECISION 意图 + API 表缺 identity/search/tags/cards/accounts/admin 端点 | `services/adai-core/CLAUDE.md:102` | 📋 待办 |
| 156 | 计数过期：实际 15 Controller 46 端点（media +2 未重计）；测试 254→256 | `CLAUDE.md` + `adai-core/CLAUDE.md:117` | 📋 待办 |
| 157 | system-architecture §七 未实现清单过期（行情 Phase1 已落地、RFC 11→23 篇）| `system-architecture.md:340,332` | 📋 待办 |
| 158 | 桌面记忆页「待办」无完成操作：actionable 记忆页只展示无法就地完成（完成动作只在 Feed action 卡）| `adai-web/pages/memory_page.dart:124-183` | 📋 待办 |
| 159 | 桌面 Feed 空态缺移动端的快速开始引导 chips | `adai-web/pages/feed_page.dart:464-478` | 📋 待办 |

## 🔴 P3（打磨）

| # | 问题 | 位置 |
|:-:|:-----|:-----|
| 120 | 行情卡纯文本无红涨绿跌着色；action/market 简单卡不显示时间戳 | `desktop_feed_card.dart` |
| 121 | 无最小宽度/响应式保护：窄窗下 nav200+侧栏300 挤压主区 | `desktop_shell.dart` |
| 122 | frontend-reference 颜色表旧色值 + API 速查表缺 adai-web 消费的 8 端点 | `frontend-reference.md` |
| 123 | 中英混排：Feed 导航 / quick add / commits·APIs / network error / 时间线 type 英文原文 | 三页文案 + `timeline_page.dart:203` |
| 124 | adai-web/CLAUDE.md 端口写 `:8081`（实际 `:8082`）| `apps/adai-web/CLAUDE.md:32` |
| 125 | 打磨：README 默认模板 / aiNote 死代码 / hover 无手型 / 圆角 token 散落 11 种 / 无快捷键 / CLAUDE.md apps/ 块重复 / 记忆页日期无年份 | 多处 |
| 160 | api-spec mediaPath 示例含日级 `/08/02/media/`，实际月级 `records/2026/08/media/`（契约失真）| `api-spec.md:165` / `MediaRecordAppService.java:139-143` |
| 161 | 时间线 type 徽标直接显示后端英文原文（log/question/card 无中文映射）| `timeline_page.dart:203` |
| 162 | Feed push 类型未映射：`FeedEntryType.push` 落默认 `record`，L5 推送上线会被渲染成普通卡 | `feed_models.dart:134-145` |
| 163 | adai-admin 记录页只看得到今天：从 Feed（page 0）拉记录，Feed 契约只返回当天 → 无法管理历史 | `data_api_store.dart:60-76` |
| 164 | adai-app 语音入口误导性 stub：可切语音态 + 长按录音 → 松手才弹「功能开发中」| `input_bar.dart:84-91,288-295` |
| 165 | adai-web `FeedEntryResponse.type` 硬转换 `as String`（无兜底），后端新 type 整页挂（admin 有 `as String? ?? 'record'`）| `api_service.dart:473` |
| 166 | 文件写入非原子已列 #126；此处补：MediaController 上传超限走 500（应 413）、title 50 字符 substring 拆断 emoji、market id 同秒碰撞 | `MediaController.java:50-51` 等 |
| 167 | feature-reference.md 自诩「唯一功能真相源」未覆盖多模态/多账号/adai-admin | `feature-reference.md:6` |
| 168 | 知识 P3 杂项：空文件 `不要对股票有感情.md` / 99-inbox 重复 JSON / 知行模块 PNG 359KB 入库 / life-os README 引用漂移 / project-os 路径漂移 / 2 条未索引标签 / gitignore 单层回归 / decision 死分支 | `os/` 多处 |

## ✅ 已修复区（最近 10 条，旧条目随滚动删除）

| # | 问题 | 修复 |
|:-:|:-----|:-----|
| 60/61/62 | v0.2.0 前端 actionable UI 消费：action 待办卡+完成按钮（`ca2d4a8`）、Feed 分页终止修复（totalToday 只计核心，`ca2d4a8`）、memory 页 kind/superseded/待办展示（`7d9b607`）| ✅ 2026-08-02 |
| 后端 P1 ×4 | actionable 筛选豁免（P1-1）+ 无限重补修复（重补筛选改已处理判定）+ ID 单调统一 IdGenerator（P1-2）+ rebuild 幂等（P1-3）+ 跨日升级（P1-4，findByRecordId 365 天）（`c41c2b7`）| ✅ 2026-08-02 |
| 13 | interfaces 层编排重复三处 → RecordUnderstandingService 统一 compose→understand（`bdb83da`）| ✅ 2026-08-02 |
| 33/38/39/41/21/23 | 第三批 6 项：review 路由表补 `.claude/**`；docs/README 索引登记；os definition 架构愿景声明；data-flow 对齐；ProjectFileRepository 保留手写注释；TradingController 解析真实 rules.md | ✅ 2026-08-01 |
| 12/24/14 | 第二批代码修复 3 项：记忆沉淀断裂（AI 失败降级 + 洞察升级覆盖 + 重补过滤）、复盘改走 ContextEngine、测试缺口 9 个 | ✅ 2026-08-01 |
| 16a/34/35/36/37/40/42/43 | 第一批快修 8 项：孤儿卡片迁移；ship grep 路径修正；RFC 滚动；guard.md 对齐 guard.sh；trading README；CLAUDE.md 焦点；guard.sh G1 防挂起；零碎拼写 | ✅ 2026-08-01 |
| 25-32 | 前端 P3 打磨 8 项（URL 编码/文本清理统一/日期硬编码/静默刷新/方向图常量/死代码/light主题） | ✅ 2026-08-01 |
| 15-20 | 数据目录 + api-spec v3.1 + CLAUDE.md 对齐 + 任务扫全部月份 | ✅ 2026-08-01 |
| 7-11 | P1 功能 bug 5 项（emoji 代理对/行情缓存键/AI失败保数据/mounted守卫/PnL序列化）| ✅ 2026-08-01 |
| 3-6 | 战略缺口（场景路由打通/Life知识注入/大盘始终注入）| ✅ 2026-08-01 |

## 执行成本

| 日期 | 模式 | 派发角色 | agent 数 | 耗时 | 新增 | 修复 |
|:-----|:-----|:---------|:--------:|:-----|:----:|:----:|
| 2026-08-03 | full 全量（v0.3.0 前）| backend/frontend/docs/product/knowledge | 5 | ~30min | P0×1+战略×7+P1×13+P2/P3×30 | 0 |
| 2026-08-02 | full 全量（v0.1.0）| backend/frontend/docs/product/knowledge | 5 | ~25min | 前端 3 项 + 后端 P1 4 项 + 文档若干 | 后端 P1 4 项 + 文档契约 |
| 2026-08-02 | light 增量（多账号预留）| — | 0 | ~5min | 0 新 | 0 |
| 2026-08-02 | light 增量（v0.2.0）| — | 0 | ~3min | 0 新 | 0 |
| 2026-08-02 | deep 增量（adai-web）| frontend/product/docs | 3 | ~10min | P0×1+战略×3+P1×9+P2×8+P3 若干 | 0 |
| 2026-08-01 | light 增量 | — | 0 | ~2min | 0 新 | 0 |
| 2026-08-01 | deep 增量 | docs/knowledge | 2 | ~25min | 11+1升级 | 0 |
| 2026-08-01 | 全量 | backend/frontend/arch | 3 | ~2h | 32 | 23 |

> 跑几轮后用成本数据决定全量审核频率（目前建议：增量随时，全量 1-2 周一次）。
