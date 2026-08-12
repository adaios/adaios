---
title: 项目审核全量状态报告
updated: 2026-08-12
last-review: 2026-08-12
baseline: 7aecf9d
mode: deep 增量（R1 AI 日志 + 图片追问 + 多账号选号 + CORS + 08-12 三连修）
---

> 2026-08-12 deep 审核（范围 `7aecf9d..HEAD`，128 文件）：**P0×2（隐私红线）+ 战略×1 + P1×8 + P2×15 + P3 若干**。review 约定只报告不修复；其中 P0-2（ai-logs 补 .gitignore）修复路径明确可确认后立即修。
> 2026-08-09 修复批（批 K）：#180/#181（P1）+ #182/#183/#185/#186/#187/#188/#189/#190（P2）+ #191-197（P3）已修复，见已修复区。

# 项目审核状态报告

常驻全量状态。每次 `/review` 更新本文件——未修复项滚动保留，已修复标 ✅ 移入已修复区，新问题追加。git 历史天然保留每次更新前的快照。

## 最近审核

| 日期 | 模式 | 基线 | 派发角色 | 新增 | 修复 |
|:-----|:-----|:-----|:---------|:-----|:-----|
| 2026-08-12 | deep 增量（R1 AI 日志 + 图片追问 + 多账号 + CORS + 三连修）| 7aecf9d..HEAD | backend/frontend/docs/product/knowledge ×5 | P0×2 + 战略×1 + P1×8 + P2×15 + P3 若干 | 0 |
| 2026-08-09 | deep 增量（多账号 + wasm + 数据迁移）| 7aecf9d..HEAD | backend/frontend/docs/product/knowledge ×5 | 战略×3 + P1×2 + P2×10 + P3×14 | 0 |
| 2026-08-09 | 验证修复（updatedAt 时间基准 + #175 分页）| b657f21..HEAD | 主会话（后端）| 0 | 2（updatedAt 归日 + #175 分页首屏）|
| 2026-08-06 | 双轨修复（批 H + #127）| 89feaf1..HEAD | subagent(adai-web) + 主会话(后端) | 0 | 13（adai-web 9 项 + #127 4 项）|
| 2026-08-03 | full 全量（v0.3.0 前）| — | backend/frontend/docs/product/knowledge ×5 | P0×1 + 战略×7 + P1×13 + P2/P3×30 | 0 |
| 2026-08-03 | 修复批 A-D | — | — | 0 | 22（数据安全/状态机/契约/数据+文档）|
| 2026-08-02 | deep 增量（adai-web）| cc537db..HEAD | frontend/product/docs ×3 | P0×1 + 战略×3 + P1×9 + P2×8 + P3 打磨若干 | 0 |
| 2026-08-02 | full 全量（v0.1.0 发布前）| — | backend/frontend/docs/product/knowledge ×5 | 前端 3 项 + 后端 P1 4 项 + 文档 | 后端 P1 4 项 + 文档契约 |
| 2026-08-01 | 全量（初始） | — | backend / frontend / arch ×3 | 32 | 23 |
| 2026-08-01 | deep 增量 | a4a7c12..cd1231b | docs / knowledge | 11 新 / 1 升级 | 0 |

## 🔴 战略缺口（未修复）

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| 101 | Feed 无「加载更早」分页：只拉 `size:20`，更早记录不可达 | `feed_page.dart:55-56` | 📋 待办 |
| 103 | Timeline/Memory 保活数据陈旧：initState 只拉一次 + IndexedStack 保活，无刷新入口 | `timeline_page.dart` / `memory_page.dart` | 📋 待办 |
| 129 | 知识反哺闭环缺前端入口：promote 后端闭环 ✅（v1.0.0 验证走通，已产出 `99-inbox/2026-08-09_交易复盘.md` 真实复盘），但交易页无「反哺入库」按钮，UI 无法操作 promote | `os/trading-os/99-inbox/` | 📋 待办 |
| 177 | 多账号前端全链路零测试覆盖：v1.0.0 提前的核心功能（首屏选号 / 切换重建 ValueKey / UserStore 条件导出双实现 / available DTO / 持久化降级 / 双击防重入）全部无 widget 测试。本次 deep 前端/产品双角色确认仍为 0——而 P1-204 双 pop 崩溃恰好藏在无测试的选号回调里 | `apps/adai-app/test/` / `apps/adai-web/test/` | 📋 待办 |
| 179 | `/accounts/available` 无鉴权暴露账号枚举面：返回全部启用账号 `userId/role/createdAt`（含 admin `role` 标记，本次再确认 P3-215），与用户层 X-User-Id 零鉴权（#127 延迟项）组合成目录键枚举面；设计上知情（选号提前），多账号正式开放时须随用户层鉴权收紧 | `AccountController.java:52-60` / `WebConfig.java:46` | 📋 待办 |

## 🔴 P0（隐私红线 / 数据安全）

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| 226 | **`.gitignore` 未覆盖 `data/*/ai-logs/`——prompt 全文（个人隐私）可被 `git add -A` 提交进仓库历史**：AiInteractionLogger 落盘 `data/{userId}/ai-logs/.../ai-log-{date}.jsonl`，每行含完整 prompt（档案/记忆/持仓/内容）。隐私段 `data/*/records/`、`data/*/memory/`、`data/*/trading/`、`data/*/index/` 均在，唯独缺 ai-logs。已实测 `git check-ignore` 确认未忽略。本地/生产跑真实 AI 后 `/ship` 的 `git add -A` 会把整份明文个人上下文提交进 git 历史且无法抹除。**修复路径明确（补一行通配），确认后立即修** | `.gitignore` 隐私段 | ✅ 2026-08-12（已补 `data/*/ai-logs/` + identity 白名单加固，见已修复区）|

> 2026-08-12 修复批：#184（promote 脱敏）+ #204-#209/#211/#212（P1 全）+ #219/#220 + #206/#207 已修复出表，见已修复区。P1 未修复当前清零。

## 🔴 P1（未修复）

> 2026-08-12 修复批（批 A-D + #184）已全部修复出表：#204（双 pop）/ #205（firstWhere）/ #206（updatedAt 回退）/ #207（recorded 哨兵）/ #208（原图可见）/ #209（气泡持久化）/ #211（文件名约定）/ #212（迁移脚本）。P1 当前清零。

## 🔴 P2（未修复）

> 2026-08-12 修复批 L 已出表：#214（图片追问长度上界）/ #215（available 最小集）/ #221（降级 emoji 按时段）；文档项 #224（端点数/测试数/api-spec 升版）/ #225（issue-log R1 状态与调用点数）同步收敛。剩余见下表。

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| 19 | Feed/Context/Memory 每次全量遍历 data 目录 | `RecordFileRepository.findAll` | 📋 待办（数据量小）|
| 22 | kernel 反向依赖 infrastructure（现 4 处：IntentRecognizer/ContextEngine/MemoryService/Memory.java）| 多处 | 📋 待办 |
| 115 | Feed 右栏（简报/标签云/任务快照）不随操作/刷新更新，数据陈旧 | `feed_page.dart:76-88` | 📋 待办 |
| 117 | 测试覆盖缺口：✅ Feed 状态机 12 测试（`feed_state_machine_test.dart`）+ 6 页面 widget 测试（`pages_widget_test.dart`：memory/timeline/search/trading/task/profile 数据渲染 + 错误态 + 重试）；缓存 key 分桶未测（价值低，留待多账号批）| `test/` | ✅ 主体 |
| 149 | 多账号细节：accounts.json 无锁 / 删号不清理数据 / 允许创建 default | `AccountFileRepository` / `AccountController` | 📋 待办（v1.0.1）|
| 153 | 数据形态失衡：08 月 131/133 条为对话摘要，原始 note <2% | `data/adai/records/2026/08/` | 📋 观察 |
| 176 | 交易录入无严格校验：`TradeRequest` 仅 `@NotBlank`/`@Positive`，可录入错误代码（如 000300 当贵州茅台）→ 行情/持仓/复盘/反哺全污染；建议三层校验（格式 6 位数字+市场前缀 / `quote` 存在性 / 名称匹配模糊比对）；用户指出**输入校验 + 持仓分析 + 反哺流程**整体待打磨（v1.0.0 后批次）| `TradeRequest` / `TradingAppService.recordTrade` / 交易表单 | 📋 待办 |
| 216 | **CardMigrationService 从"复制"改"移动"（写新+删旧）**：误判即删原文件（`parseAsCard` 判定"有 frontmatter 且 body 含 `## `"即视为卡片），且旧文件无 `id` 时并入 `card_unknown`（findAll 去重后合并为一条，数据淹没）| `CardMigrationService.java:84-88` | 📋 待办 |
| 217 | `rewriteIdInFrontmatter` 正则未锚定 frontmatter（`(?m)^id:\s*.+$` 全文件首处匹配），可能改写 body 中的 `id:` 行 → frontmatter 保留旧无前缀 id → save() 按旧 id 写回旧路径 → 双文件复发 | `CardMigrationService.java:149-157` | 📋 待办 |
| 218 | `LoggingVisualAiClient` 视觉调用 durationMs 恒为 null（与文本调用不一致）；ai-log 落盘日期用 `LocalDate.now()`（写入时刻）而非调用开始时刻（跨午夜长调用落错天文件）；`getAiLogs` 无日期上界校验可扫任意历史（后两项 #210 已修）| `LoggingVisualAiClient.java:74` / `AiInteractionLogger.java:37` / `AdminController` | 📋 待办（剩 durationMs）|
| 219 | **图片卡「提问」后不输入直接关闭 → 卡片永久卡在 waiting 态**（双端）：`_onAskCard` 置 waiting 后不发起任何异步请求（等用户输入），若用户改主意通过「结束对话」关闭，`_closeChat` 走 `!hasNewTurns && !needsSummary` 早退分支不复位 mode → 回到 Feed 卡片以 active 样式常驻，只能真发一个问题才能跳出 | `apps/adai-app/lib/main_page.dart:165-178` / `:240-250`；adai-web `feed_page.dart:263-344` | ✅ 2026-08-12（早退分支无条件复位 waiting→idle，双端）|
| 220 | **adai-app 图片 `_onAskCard` 分支缺 `_deactivateOtherCards`**（与 adai-web 不一致，双端模型漂移）：已有一个 chatting/waiting 卡时再点另一张图片卡提问，旧卡留在聊天态，关闭新卡后 Feed 出现两张 active 样式卡 | `apps/adai-app/lib/main_page.dart:167-175` vs adai-web `feed_page.dart:269` | ✅ 2026-08-12（adai-app 图片分支补 `_deactivateOtherCards`，与 adai-web 对齐）|
| 222 | 问候语机械切分（#169 具体化）：午间 12 归"下午好"（`hour < 18`）、5:59 深夜 / 6:00 突然早上硬切；降级路径机械感强于 AI 成功路径 | `BriefAppService.java:120-125` | 📋 待办 |
| 223 | adai-core CLAUDE.md 声称 os/ 只读与实际 promote 写入矛盾（K4/K13 漂移）：`TradingController.promoteToInbox` 实际写 `os/trading-os/99-inbox/`，但 CLAUDE.md:131 声明"只读，不写入"未记录该例外；`.gitignore:69` 只保护 `profile.md` 单文件，identity 下新非 sample 文件会进 git（后半已随 #226 改白名单）| `adai-core/CLAUDE.md:131` / `.gitignore:69` | 📋 待办（剩 CLAUDE.md 例外登记）|
| 228 | 端点计数双实现（Java 扫源码 `split("@GetMapping",-1)` + Gradle 生成 `endpoints.txt`）口径不同：Java 侧会数注释/字符串中的注解名，dev 与生产显示数字可能不一致（#187 修复未完全收敛）| `ProjectStatusAppService.java:139-190` / `build.gradle.kts:61-86` | 📋 待办 |

## 🔴 P3（未修复，打磨）

| # | 问题 | 位置 |
|:-:|:-----|:-----|
| 121 | 无最小宽度/响应式保护：窄窗下 nav200+侧栏300 挤压主区（批 H 已评估：桌面端专用产品、常规宽度无问题，极窄窗口才压缩，低优先级）| `desktop_shell.dart` |
| 122 | frontend-reference 颜色表旧色值 + API 速查表缺 adai-web 消费的 8 端点 | `frontend-reference.md` |
| 125 | 打磨：README 默认模板 / aiNote 死代码 / hover 无手型 / 圆角 token 散落 / 记忆页日期无年份 | 多处 |
| 163 | adai-admin 记录页只看得到今天（Feed 契约只返回当天）| `data_api_store.dart:60-76` |
| 166 | MediaController 上传超限走 500（应 413）、title 50 字符 substring 拆断 emoji、market id 同秒碰撞 | 多处 |
| 168 | 知识 P3 杂项：空文件 / 重复 JSON / PNG 入库 / life-os 引用漂移 / project-os 路径漂移 / 未索引标签 / gitignore 单层 / decision 死分支 | `os/` 多处 |
| 169 | 问候语机械：按小时硬编码切分（`hour < 12 → morning`），凌晨也算 morning，不感知人类作息；建议按作息智能化（深夜/凌晨单独问候）| `BriefAppService.java:129` / `:105` |
| 170 | 待办建议第三人称：action 待办卡文案（`Memory.suggestion`，如「提醒用户休息」）用第三人称，应直接面向用户（「该休息了」）| `FeedAppService.toActionEntry` → 记忆生成 prompt |
| 171 | 优化方向（非问题）：项目分类记录无聚合——domain=project 记录散在 Feed/时间线，项目页只有状态+任务；用户以"日志记录问题/优化建议"为入口，建议项目页增加「项目记录」聚合视图 + 记录可标记类型（问题/建议），并可流转为任务 | `adai-app` 项目页 + domain 体系 |
| 172 | 记忆页 superseded 记忆仍显示「待办/已完成」标记（语义矛盾：被取代的历史版本不是当前待办）+「已取代」仅靠卡片变浅、区分度低含义不明；建议 superseded 记忆隐藏 actionable 标记，已取代状态加明确灰角标说明（如「已被新记忆取代」）| `memory_page.dart:239-261` |
| 173 | 优化方向（L4 演进）：带图提问——图片上传固定 intent=log（`MediaRecordAppService.recordImage` 硬编码），不支持"发图+问句→AI 基于图回答"；建议加 intent=question 通道 + AI 对话带图上下文 | `MediaController.uploadImage` / `MediaRecordAppService.recordImage` |
| 174 | 图片上传无进度反馈：`_onSendMedia` 逐张 `await uploadImage`，期间无 loading/进度条/占位卡，多图干等只盯接口；建议上传中显示逐张进度 | `main_page.dart:278-304` |
| 198 | 选号页 loading 态仅灰字无 spinner；切换账号后无确认反馈（SnackBar「已切换至 @xxx」）；账号行无按压反馈（InkWell）| `account_select_page.dart`（双端）|
| 199 | 时间线全图 Dialog 无 errorBuilder/loadingBuilder：后端 404 或慢加载显示异常占位 | `timeline_page.dart:100-114` |
| 200 | `serve_web.sh` bootstrap 补丁无回归校验：perl 注入 `canvasKitBaseUrl` 若与 wasm 产物原有 `config` 重复键（后写者胜）→ 仍从 CDN 拉（被墙白屏）| 三端 `serve_web.sh` |
| 201 | 桌面底部账号 Row 无溢出保护：超长 userId 在 200px 导航内横向溢出 | `desktop_shell.dart:147-157` |
| 202 | 后端 P3 打磨：`findTodayCards` 过滤 `updatedAt != null` 旧版存量卡消失（低影响）/ `generate` 不剥 JSON 代码块（AI 违抗时复盘渲染破坏）/ `userTradeLocks` 按 userId 无界累积 / `AiClient.generate(ctx, null)` 默认 system 仍是 JSON 分析指令与生成语义矛盾 / 旧数组账号日期读取兼容无回归测试 | 后端多处 |
| 203 | knowledge 入库候选小瑕疵：候选文件尾无换行符 / 文件名 `review-YYYY-MM-DD.md` 不符 trading-os 约定（应 `YYYY-MM-DD_主题.md`）/ 「R35)」半角括号混排 / `migrate-data-to-user-layer.sh` 目标仍是 `data/default/`（被 default→adai 取代）/ `.gitignore` 的 `data/*/identity/profile.md` 只保护单文件（identity 下新非 sample 文件会被提交）| 多处 |
| 229 | 图片追问首轮把「图片摘要文本」渲染成用户气泡（应居中「图片上下文」提示而非冒充用户消息）；折叠渐隐遮罩色与半透明卡背景不一致；#15 折叠对超长 active 卡不设上限（几十轮全量渲染，桌面端布局压力）；`askMedia` 缓存清理缺 `_tagsCache`（image_qa 带 tags，标签页陈旧）；`main()` runApp 前 await `UserStore.loadUserId()` 首帧延迟；桌面底部 @userId 入口无 tooltip/hover | `main_page.dart:926-930` / `feed_card.dart:633-646,617` / `api_service.dart:124-125` / `main.dart:11-22` / `desktop_shell.dart` |
| 230 | 选号页空态文案「请先在后台创建账号」与改名「阿呆控制台」不对应（应「请先在阿呆控制台创建账号」）；选号页 loading 态仅灰字无 spinner（与全项目 CircularProgressIndicator 基线不一致）、账号行无按压反馈 | `account_select_page.dart:84-86,116`（双端）|
| 231 | 后端打磨：`LoggingAiClient.generate` 不记录 systemPrompt（复盘模板指令缺失，日志无法完整还原"提示词怎么组装的"）；`readDay` 与 append 并发可能读到半行 JSON（解析跳过不崩，仅管理端少一条，建议共用锁）| `LoggingAiClient.java:57-65` / `AiInteractionLogger.java:60-70` |
| 232 | 部署文档模型名不一致：`backend-deployment.md:199` 写 `deepseek-chat`，`application.yml:52` 实际 `deepseek-v4-pro` | `docs/deployment/backend-deployment.md:199` |
| 233 | adai-core CLAUDE.md API 端点表缺 3 个新端点（`media/{id}/ask` / `cards/cleanup` / `accounts/available` 未注无鉴权）；data-format-freeze §2.13 prompt 字段说明缺 intent 为用户原文与视觉占位符细节 | `adai-core/CLAUDE.md:104-119` / `data-format-freeze.md` §2.13 |

## ✅ 已修复区（最近 10 条，旧条目随滚动删除）

| # | 问题 | 修复 |
|:-:|:-----|:-----|
| #214 + #215 + #221 | **2026-08-12 P2 修复批 L**：**#214 图片追问长度上界**（`MediaRecordAppService.askImage` question 超 500 字符 → 400，防超大 prompt/记录/日志行）+ 2 测试；**#215 available 最小集**（`GET /accounts/available` 改返回 `List<String>` 纯 userId，不再暴露 role/enabled/createdAt——无鉴权端点去 admin 标记枚举面；双端选号页去角色渲染 + 删 `AccountModel` 死代码）+ 1 测试；**#221 问候语降级 emoji 按时段**（`emojiForHour` 凌晨 🌙/早上 ☀️/下午 🌤️/晚上 ✨，不再固定 ☀️ 配深夜好）+ 1 测试 | ✅ 2026-08-12 |
| #210 | **2026-08-12 AI 日志隐私治理（R1 遗留）**：prompt 全文明文落盘缺生命周期/隐私面治理（无限明文堆积 + 读取面扫任意历史）→ **retention** `AiInteractionLogger` 默认保留 30 天（`adai.ai-log.retention-days`，`<=0` 关闭），写入时惰性清理（每用户每日一次）过期日志文件；**读取治理** `GET /admin/ai-logs` 加 `page`/`size`（上限 500，响应带 `total`）+ `date` 早于保留期返回 400；api-spec v3.11；7 测试（AiInteractionLogger 4 + AdminController 3）。保留全文记录能力（R1 目标），未做 prompt 脱敏开关 | ✅ 2026-08-12 |
| #227 + #213 + #178 | **2026-08-12 数据/隐私 + 反哺闭环加固**：**#227** RecordRetryService 过滤禁用账号（`accountRepository.findAll()` 加 `.filter(Account::enabled)`，与 MarketAlertService 口径一致；无启用账号不再 fallback "default"——#212 后 default 已迁移移除）+ 2 测试；**#213** 新增 `AiTraceCleanupInterceptor` 请求级清理（每个 HTTP 请求 `afterCompletion` 无条件 `AiTraceContext.restore(null)`，消灭 Tomcat 线程复用下的 ThreadLocal 跨请求残留——漏 set trace 的调用不再把日志落进上一个请求的用户目录）+ 2 测试；**#178 A 档** promote 响应加 `message` 字段提示「入库候选不自动融入 AI context，需在 trading-os 工作流融合后重建 11-context」（api-spec 同步；融合本身属 trading-os 收敛流程，能力边界内不自动化）。后端 344 全绿 | ✅ 2026-08-12 |
| #184 | **2026-08-12 P0 隐私修复（promote 内容脱敏）**：复盘入库候选含真实持仓（茅台 100 股/14 万/1400）且已被 git 追踪（commit f3ca035），违反 K8 红线。用户决策「promote 脱敏」→ `TradingController.sanitizeReviewContent` 生成源替换股数/市值/成本/现价/现金→占位符（标的名保留公开信息+规则引用需要，不误伤大盘指数）；旧候选文件改名 `2026-08-09_交易复盘.md` + 内容重写脱敏版；2 新测试（脱敏 + null 边界）。已 grep 验证无持仓数字残留、9 规则引用 + 标的名完整。git 历史旧版仍含数据（单人不重写历史，未来不再暴露）| ✅ 2026-08-12 |
| P1 批 A-D | **2026-08-12 P1 修复批（A-D 四批）**：**批 A 前端** #204 双 pop（守卫包住闭包 nav.pop，双端）+ #205 firstWhere→indexWhere（双端）+ 选号页 widget 测试 5 个（#177 战略落地）；**批 B 后端** #206 updatedAt 缺失回退 createdAt（损坏卡跳过）+ #207 recorded 哨兵（长摘要截断、rebuild 回写真实摘要）+ 3 测试；**批 C 图片追问** #208 active 态原图可见（缩略图+全图 Dialog）+ #209 Q/A 持久化到图片卡 card 文件 + FeedAppService 合并 turns + #219 waiting 卡死复位（双端）+ #220 双端对齐；**批 D** #211 候选文件名 `YYYY-MM-DD_主题.md` + 已入库候选改名 + #212 迁移脚本 default→adai 参数化。后端 338 · adai-app 68 · adai-web 30 全绿 | ✅ 2026-08-12 |
| #226 + #223 | **2026-08-12 P0 隐私加固（.gitignore）**：#226 ai-logs 落盘目录未进 .gitignore（prompt 全文含档案/记忆/持仓可被 `git add -A` 提交进历史）→ 补 `data/*/ai-logs/`；#223 identity 只保护 profile.md 单文件 → 改 `data/*/identity/*` + `!*.sample.md` 白名单。已实测 `git check-ignore` 命中、sample 仍被追踪、git status 无泄漏 | ✅ 2026-08-12 |
| 阿呆系统必现崩溃 | **2026-08-09 v1.0.0 验证修复（阿呆系统页 CanvasKit 必现崩溃）**：点击「阿呆系统」release minify **必现** `PictureRecorder` wasm 崩溃（`canvaskit.wasm` 内存分配失败，`core_patch.dart:293 Uncaught Error`）且控制台持续输出——该页首帧绘制 + 路由过渡动画并发 + 加载 spinner 无限重绘，触发 CanvasKit 绘制密集不稳定（**非项目 bug**，同路由动画的记忆/时间线/任务/交易页均正常，仅该页绘制密度触发）。修复：阿呆系统入口改无动画跳转（`PageRouteBuilder` duration zero，去并发动画帧）+ 加载 spinner 换静态「加载中…」（去持续重绘源）；adai-app analyze 0 · 60 测试全绿 | ✅ 2026-08-09 |
| TimelinePage 缩略图 | **2026-08-09 v1.0.0 验证修复（World B 时间线缩略图）**：批 2"时间线页缩略图"只做了 TopBar TimelineModal，World B `TimelinePage`（launcher 入口"时间都去哪了"）漏了缩略图 → 补 `_buildDayEntries` mediaPath 缩略图（96px）+ `_showFullImage` 原图 Dialog（复用 TimelineModal 模式）；adai-app analyze 0 · 60 测试全绿 | ✅ 2026-08-09 |
| 复盘生成 | **2026-08-09 v1.0.0 验证修复（复盘生成语义）**：复盘走 `understand`（JSON 摘要语义）——默认 system"输出 JSON summary 3-5 词"压制复盘 5 节正文模板 → AI 只回"交易复盘，持仓不变"一句话；根因复盘是生成型任务却复用理解型接口。修复：AiClient 新增 `generate(ContextPackage, systemPrompt)` 生成语义（自定义 system 引导正文格式 + 0.7 temp/2048 tokens），`TradingReviewAppService` 改走 generate；验证产出完整 5 节复盘且引用真实规则（R4/R117/R119/E20/R1）。适配 DeepSeekAiClient + TestAiClient + 2 匿名实现 + TradingReviewAppServiceTest（后端 298 全绿）| ✅ 2026-08-09 |
| updatedAt + #175 | **2026-08-09 v1.0.0 验证修复（Feed 首屏 + 时间基准）**：卡片时间基准改 updatedAt——跨日续接对话归最后活跃日（`CardFileRepository.findTodayCards` 由按目录查创建日改为全量扫 + 按 updatedAt 过滤；`FeedAppService.toCardFeedEntry` 卡片 time/date 用 updatedAt）/ #175 分页 page 0 返回完整 size 条核心、余数放末页（`FeedAppService.getFeed` 分页改新在前切片）；api-spec 分页/时间基准说明同步；新增 CardFileRepositoryTest 2 + FeedAppServiceTest 2（后端 298 全绿）；前端 `_loadMore` 顺序自洽零改动 | ✅ 2026-08-09 |
| 批 J | **2026-08-09 P1 清理（v1.0.0 核心闭环）**：#144 rebuild 幂等（intent 落盘 + summary 处理标记 + 降级记录仍重跑升级，`RecordFileRepository`/`MemoryController`/`QuestionAppService`）/ #147 SELL 未持有与超额报错（`TradingException` + GlobalExceptionHandler 400）+ 持仓读改写加锁 + 清仓 0 行不落盘 / #106 api-spec portfolio 契约对齐（补后端 `positionCount` 派生字段，adai-web 持仓数不再恒 0）/ #112 CANCELLED 任务看板可见（adai-web 补第四列）/ #150 apiEndpoints 动态统计（硬编码 21 → 实际 46）+ FeedAppService 死依赖移除；后端 300 · adai-web 27 测试全绿 | ✅ 2026-08-09 |
| 批 K | **2026-08-09 deep 审核修复（多账号 + 数据迁移 + 契约）**：**P1** #180 freeze 契约漂移（intent 落盘声明 + 单用户路径 adai）+ #181 rebuild 幂等漏聊天首问（RecordController 首问带新 cardId 补写 intent=question，新增回归测试）；**P2** #182 前端默认 userId 有效化（savedUserId=='default' 视为无效强制选号）+ #183 MarketAlert 轮询去硬编码 default（enabled 账号）+ #185 切换防重入（_handlingSelect）+ #186 URL ?userId 刷新回退（切换后 clearUrlUserId 清 URL）+ #187 端点计数生产恒 0（Gradle 生成 META-INF/endpoints.txt 资源，dev 回退扫源码）+ #188 回写保留用户 tags + #189 persist 先于 summary 落盘（失败留空供 rebuild 重跑）+ #190 空态可执行重试；**P3** #191 api-spec 升版 v3.8 + #192 Release Notes 日期改待定 + #193 REVIEW 302→300 / #153 路径 + #194 api-spec default 说明 + #195 sample 路径 + #196 alpha 越界 + #197 clearUserId 死代码删除；后端 300 · adai-app 60 · adai-web 27 全绿 | ✅ 2026-08-09 |
| 批 I | **2026-08-07 adai-app 对话体验收尾**：#13+#11 card 写入剥离 AI 原始 JSON（`LlmResponseParser.extractNaturalText` + `QuestionAppService` 写卡与返回均剥 JSON，实时=刷新，card 文件不再混入游离 JSON）/ #148 Feed ai_note 按记录日期归属（`MemoryService.findByRecordIds` 跨日补齐 + `toAiEntry` 用记录时间，重补/升级跨日不错日不丢失）/ MD1 世界切回 Feed 刷新（`DualWorldShell` ValueNotifier → `MainPage.refreshTick` 重载，覆盖 admin 记忆重建后 Feed 陈旧）；后端 286 测试 · 前端 60 测试 | ✅ 2026-08-07 |
| 批 H | **2026-08-06 adai-web 桌面残留清理 9 项**：#102 交易页复盘入口（markdown 复盘弹窗）/ #132 红涨绿亏（A股语义，快照+DataTable）/ #161 时间线 type 徽标中文化（13 类映射+未知兜底）/ #131 桌面文案全量中文化（shell/feed_card/task/feed/profile/project/网络错误）/ #124 CLAUDE.md 端口 8082 / #158 记忆页待办完成按钮 / #159 Feed 空态快速引导 chips（prefill 聚焦）/ #118 `_check` utf8 解码 / #165 type 硬转换兜底；#120 确认已实现未重复、#121 评估低优先级不修 | ✅ 2026-08-06 |
| 127 | **2026-08-06 最小封闭鉴权**：admin/accounts 端点 `X-Admin-Token` 拦截（常量时间比较、未配置 fail-closed 503）+ CORS `*`→配置化 origin 白名单（默认 localhost:*）；adai-admin 前端 `ADMIN_TOKEN` dart-define 注入；4 鉴权测试 + api-spec v3.6 | ✅ 2026-08-06 |
| 批 G | **2026-08-05 adai-app 6 页面 widget 测试（#117 剩余）**：`pages_widget_test.dart` 14 测试——memory/timeline/search/trading/task/profile 六页数据渲染 + #108 错误态人话 + 重试按钮（复用批 F MockClient 基建）| ✅ 2026-08-05 |

## 执行成本

| 日期 | 模式 | 派发角色 | agent 数 | 耗时 | 新增 | 修复 |
|:-----|:-----|:---------|:--------:|:-----|:----:|:----:|
| 2026-08-12 | P2 修复批 L（#214/#215/#221 + #224/#225 文档）| — | 0 | ~30min | 0 新 | 5（#214 P2 + #215 P2 + #221 P2 + #224 P3 + #225 P3）|
| 2026-08-12 | AI 日志隐私治理（#210）| — | 0 | ~25min | 0 新 | 1（#210 战略）|
| 2026-08-12 | 数据/隐私 + 反哺加固（#227/#213/#178 A 档）| — | 0 | ~20min | 0 新 | 3（#227 P2 + #213 P2 + #178 战略 A 档）|
| 2026-08-12 | deep 增量（R1 AI 日志 + 图片追问 + 多账号 + CORS + 三连修）| backend/frontend/docs/product/knowledge | 5 | ~20min | P0×2 + 战略×1 + P1×8 + P2×15 + P3×5 | 0 |
| 2026-08-12 | P1 修复批 A-D + #184 promote 脱敏 | — | 0 | ~90min | 0 新 | 13（#184 P0 + #204/205/206/207/208/209/211/212 P1×8 + #219/220 P2×2 + #177 测试）|
| 2026-08-12 | P0 隐私加固（.gitignore：#226 ai-logs + #223 identity 白名单）| — | 0 | ~3min | 0 新 | 2（#226 P0 + #223 部分）|
| 2026-08-09 | deep 审核修复（批 K）| — | 0 | ~60min | 0 新 | 16（#180/#181 P1 + #182-190 P2 9 项 + #191-197 P3 7 项）|
| 2026-08-09 | deep 增量（多账号 + wasm + 数据迁移）| backend/frontend/docs/product/knowledge | 5 | ~20min | 战略×3 + P1×2 + P2×10 + P3×14 | 0 |
| 2026-08-09 | 验证修复（updatedAt + #175 + 复盘生成 + 时间线缩略图）| — | 0 | ~70min | 0 新 | 4 |
| 2026-08-09 | 批 J（P1 清理 v1.0.0 核心闭环）| — | 0 | ~40min | 0 新 | 5（#144/#147/#106/#112/#150）|
| 2026-08-07 | light 增量（批 I 对话体验收尾 + ideas）| — | 0 | ~5min | 0 新（P3 观察×3）| 4（#13/#11/#148/MD1）|
| 2026-08-06 | light 增量（Phase 2 行情推送快扫）| — | 0 | ~5min | 0 新 | 0 |
| 2026-08-06 | light 增量（批H + #127 快扫）| — | 0 | ~5min | 0 新 | 0 |
| 2026-08-06 | 双轨修复（批 H + #127）| general-purpose（adai-web）| 1 | ~15min | 0 新 | 13 |
| 2026-08-06 | light 增量（批F/#123/批G 快扫）| — | 0 | ~3min | 0 新 | 0 |
| 2026-08-03 | full 全量（v0.3.0 前）| backend/frontend/docs/product/knowledge | 5 | ~30min | P0×1+战略×7+P1×13+P2/P3×30 | 0 |
| 2026-08-03 | 修复批 A-D | — | 0 | 4 批 | 0 | 22 |
| 2026-08-02 | full 全量（v0.1.0）| backend/frontend/docs/product/knowledge | 5 | ~25min | 前端 3 项 + 后端 P1 4 项 + 文档若干 | 后端 P1 4 项 + 文档契约 |
| 2026-08-02 | deep 增量（adai-web）| frontend/product/docs | 3 | ~10min | P0×1+战略×3+P1×9+P2×8+P3 若干 | 0 |
| 2026-08-02 | light 增量（多账号预留）| — | 0 | ~5min | 0 新 | 0 |
| 2026-08-04 | light 增量（批1/批2/语音/批E 快扫）| — | 0 | ~5min | 0 新 | 0 |
| 2026-08-02 | light 增量（v0.2.0）| — | 0 | ~3min | 0 新 | 0 |
| 2026-08-01 | light 增量 | — | 0 | ~2min | 0 新 | 0 |
| 2026-08-01 | deep 增量 | docs/knowledge | 2 | ~25min | 11+1升级 | 0 |
| 2026-08-01 | 全量 | backend/frontend/arch | 3 | ~2h | 32 | 23 |

> 跑几轮后用成本数据决定全量审核频率（目前建议：增量随时，全量 1-2 周一次）。
