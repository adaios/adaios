---
title: 项目审核全量状态报告
updated: 2026-08-12
last-review: 2026-08-12
baseline: 7b0a527
mode: deep 增量（收官批 O：战略+P2+P3 一次清完 + #22 依赖倒置 + 文档同步）
---

> 2026-08-12 deep 审核（范围 `7b0a527..HEAD`，81 文件，收官批 O）：**战略×1 + P1×5 + P2×7 + P3×18**（本批自伤：候选文件括号未闭合 / frontend-reference 虚构端点 / api-spec 正文与 changelog 不同步）。P0 无；#22 反向依赖清零验证通过。见下方各优先级区 + 成本表。
> 2026-08-12 收官批 O（一次清完剩余该做项）：战略 #101/#103/#177 出表 + #179 登记 v1.0.1；P2 #19/#22/#115/#228 出表；P3 顺手项 14 出表（详见已修复区）。
> 2026-08-12 之前批（K/L/M/N 等）见已修复区。

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

> 2026-08-12 修复批 O 已出表 #101/#103/#177（#129 批 M 已修）；deep 审核新发现 #234（分页终止口径，双端同模式）。剩余 #179 + #234。

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| 179 | 用户层 X-User-Id 零鉴权（任何人传任意 userId 即可读对应数据）+ 无鉴权选号面：`/accounts/available` 已最小化（#215 改返回 `List<String>` 纯 userId，去 role/createdAt 枚举面），但数据访问仍靠 header 注入无认证。真正收紧需引入登录体系（账号+密码+token），用户决策保持现状 | `AccountController` / `WebConfig` | 📋 v1.0.1 立项（登录体系随多账号正式开放单独做）|
| 234 | **Feed 分页终止判定口径错误（双端同模式）**：`_hasMore = _cards.length < _totalToday`——`_cards.length` 含附加条目（action/market/push，仅 page 0 附加），`totalToday` 只计核心（record/card）。一日附加条目数 ≥ totalToday − size 时 page 0 即判定无更多 → 「加载更早」消失，但最旧核心记录仍在后续页**永不可达**。交易活跃日易触发（adai-app size=5 更易命中） | `apps/adai-web/lib/pages/feed_page.dart:77,92,611` / `apps/adai-app/lib/main_page.dart:613,638` | 📋 待办（应改为按已加载核心条目数判定）|

## 🔴 P0（隐私红线 / 数据安全）

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| 226 | **`.gitignore` 未覆盖 `data/*/ai-logs/`——prompt 全文（个人隐私）可被 `git add -A` 提交进仓库历史**：AiInteractionLogger 落盘 `data/{userId}/ai-logs/.../ai-log-{date}.jsonl`，每行含完整 prompt（档案/记忆/持仓/内容）。隐私段 `data/*/records/`、`data/*/memory/`、`data/*/trading/`、`data/*/index/` 均在，唯独缺 ai-logs。已实测 `git check-ignore` 确认未忽略。本地/生产跑真实 AI 后 `/ship` 的 `git add -A` 会把整份明文个人上下文提交进 git 历史且无法抹除。**修复路径明确（补一行通配），确认后立即修** | `.gitignore` 隐私段 | ✅ 2026-08-12（已补 `data/*/ai-logs/` + identity 白名单加固，见已修复区）|

> 2026-08-12 修复批：#184（promote 脱敏）+ #204-#209/#211/#212（P1 全）+ #219/#220 + #206/#207 已修复出表，见已修复区。P1 未修复当前清零。

## 🔴 P1（未修复）

> 2026-08-12 修复批（批 A-D + #184）已出表。deep 审核新发现 5 项（含本批自伤 2 项 + 历史接受 1 项），见下表。

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| 235 | **图片上传失败占位卡「重试」是假重试（本批 #174 引入）**：`_onRetryCard` 对任何 error 卡走 `_createNewCard(content)` 文本接口——占位卡 content 是 caption 或**图片文件名**，重试把「IMG_xxx.jpg」当文本记录写进 Feed/记忆/搜索，图片字节未保留永不重传；且服务端若已建孤儿图片记录则与真实卡并存 | `apps/adai-app/lib/main_page.dart:359-372,421-429` | 📋 待办（应携带原始字节重走 uploadImage，或失败卡不渲染重试）|
| 236 | **记忆页刷新跳回最新日期 + 刷新瞬间闪空（本批 #103 引入）**：`_loadDates(force)` 恒 `_selectDate(dates.first)`，浏览旧记忆点刷新跳回顶部丢位置；`_selectDate` 先清 `_entries` 致右侧闪空 | `apps/adai-web/lib/pages/memory_page.dart:28-47,86` | 📋 待办（刷新保留当前选中日期 + 不清空等新数据替换）|
| 237 | **frontend-reference 速查表虚构端点（本批 #122 引入）**：新增「`GET /api/v1/trading/trades | List<TradeResponse>`」三项全假——后端仅 `POST /trading/trades`（TradingController.java:68，返回 PositionsResponse），无 GET、无 TradeResponse 类型；契约三方对拍 D1 命中 | `docs/architecture/frontend-reference.md:238` | 📋 待办（改 `POST /trading/trades | TradeRequest → PositionsResponse` 或删行）|
| 238 | **api-spec changelog 与正文不同步（本批 #166 升版引入）**：v3.14 声明上传超限改 413，但 § `POST /records/media` 错误列表仍写「400 — 非图片或超 5MB」；ask 段也未列「问题过长 > 500 → 400」（v3.12 已改但正文未同步）| `docs/architecture/api-spec.md:180` vs `:13` | 📋 待办（正文改「400 非图片 / 413 超 5MB」，ask 补 400）|
| 239 | **git 历史 f3ca035 残留真实持仓**（#184 脱敏只保护当前树）：旧版 `review-2026-08-09.md` 含「成本1400现价1400、持有100股市值14万」，已接受「不重写历史」决定；若仓库推送过远端（生产/GitHub）则历史中永久可读 | `f3ca035`（git 历史）| ⚠️ 需确认是否推送过远端；若推送过登记 rewrite 决策 |

## 🔴 P2（未修复）

> 2026-08-12 修复批 L/M/N 已出表（#214/#215/#221/#218/#222/#216/#217/#223 等）。修复批 O（收官批）：#19（全量遍历优化）/ #22（kernel 反向依赖倒置）/ #115（右栏陈旧）/ #228（端点计数单一口径）已修复出表，见已修复区。剩余见下表。

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| 117 | 测试覆盖缺口：✅ 主体（Feed 状态机 12 + 6 页面 widget 测试 + 批 O 补选号/切换链路 10+ 测试）；缓存 key 分桶未测（价值低，留待多账号批）| `test/` | ✅ 主体 |
| 149 | 多账号细节：accounts.json 无锁 / 删号不清理数据 / 允许创建 default | `AccountFileRepository` / `AccountController` | 📋 待办（v1.0.1）|
| 153 | 数据形态失衡：08 月 131/133 条为对话摘要，原始 note <2% | `data/adai/records/2026/08/` | 📋 观察 |
| 176 | 交易录入无严格校验：`TradeRequest` 仅 `@NotBlank`/`@Positive`，可录入错误代码（如 000300 当贵州茅台）→ 行情/持仓/复盘/反哺全污染；建议三层校验（格式 6 位数字+市场前缀 / `quote` 存在性 / 名称匹配模糊比对）；用户指出**输入校验 + 持仓分析 + 反哺流程**整体待打磨（v1.0.0 后批次）| `TradeRequest` / `TradingAppService.recordTrade` / 交易表单 | 📋 待办 |
| 240 | **generateEndpointsFile 缺 `inputs.dir` 声明**：增量构建 up-to-date 判定在源无变化时恒判 UP-TO-DATE（实测）→ 新增/删除端点后 bootJar/bootRun 不重扫，endpoints.txt 保持旧值，#228 单一口径在「数字新鲜」上失效（#187 同族变体）| `build.gradle.kts:64-86` | 📋 待办（任务体加 `inputs.dir(dir)`）|
| 241 | **候选文件括号未闭合（本批 #203 引入回归）**：`R35)` 修正为 `R35 ` 后，全角 `（` 无闭合——「三天原则（R35 需留意」，应为 `R35）`（规则 R35 = B1三天原则）| `os/trading-os/99-inbox/2026-08-09_交易复盘.md:28` | 📋 待办（改 `（R35）需留意`，K24 括号配对检查）|
| 242 | 右栏 `_loadSidebar` 无去重守卫：getTaskStats 无缓存每次网络请求，一次对话（发送→AI 回复→结束）≈3 次，连续聊天产生并发请求弱竞态（后到旧响应覆盖新）| `apps/adai-web/lib/pages/feed_page.dart:117-129` | 📋 待办（加 `_loadingSidebar` 守卫或请求序号）|
| 243 | adai-web `updateIdentity`/`updateTask` 仍用全局 `http.put`（绕过 `_client`）：#177 MockClient 注入无法拦截这两方法，widget 测试下真实 HTTP 恒 400 会莫名失败；与 adai-app 已全 `_client` 不一致 | `apps/adai-web/lib/services/api_service.dart:201,429` | 📋 待办（改 `_client.put` + grep 确认 `http\.` 零残留）|
| 244 | 图片追问 active 态全图 Dialog 无 errorBuilder（#199 只补了 timeline 页路径）：404 时空白 Dialog | `apps/adai-app/lib/main_page.dart:1052-1069` | 📋 待办（抽公共全图 Dialog 复用）|
| 245 | 图片上传成功卡 content 与 summary 同源重复渲染 + caption 丢失（本批 #174）：替换卡同设 `content: resp.summary` 与 `summary: resp.summary`，FeedCard 渲染两遍同一 AI 文本；用户 caption 被覆盖 | `apps/adai-app/lib/main_page.dart:336-347` / `feed_card.dart:251-263` | 📋 待办（content 保留 fallback，summary 单独放 AI 文本）|
| 246 | 上传占位卡挂 MainPage State，切 World B 被 AnimatedSwitcher dispose → 进度与失败反馈丢失（`if(!mounted) return` 吞掉错误 SnackBar），失败静默 | `apps/adai-app/lib/main.dart:219-249` / `main_page.dart:359-372` | 📋 待办（失败提示挂根 ScaffoldMessenger 或 MainPage 保活）|

## 🔴 P3（未修复，打磨）

> 2026-08-12 修复批 O（收官批）：#122（frontend-reference 色值 + API 表）/ #170（待办人称）/ #174（上传进度）/ #198（选号页）/ #199（时间线图 error）/ #200（serve_web 三端校验）/ #201（userId 溢出）/ #203（候选文件尾换行 + 半角括号）/ #230（选号页文案）/ #231（systemPrompt 日志）/ #232（部署文档模型名）/ #233（CLAUDE.md 端点表 + freeze §2.13）已修复出表；#125/#166/#202/#229 部分修复，见已修复区。剩余见下表。

| # | 问题 | 位置 |
|:-:|:-----|:-----|
| 121 | 无最小宽度/响应式保护：窄窗下 nav200+侧栏300 挤压主区（批 H 已评估：桌面端专用产品、常规宽度无问题，极窄窗口才压缩，低优先级）| `desktop_shell.dart` |
| 125 | 打磨（记忆页年份已修）：README 默认模板 / hover 无手型 / 圆角 token 散落 | 多处 |
| 163 | adai-admin 记录页只看得到今天（Feed 契约只返回当天）| `data_api_store.dart:60-76` |
| 166 | MediaController 上传超限 500→413 已修（GlobalExceptionHandler）+ title emoji 截断已修（按 code point）；剩余 market id 同秒碰撞 | 多处 |
| 168 | 知识 P3 杂项：空文件 / 重复 JSON / PNG 入库 / life-os 引用漂移 / project-os 路径漂移 / 未索引标签 / gitignore 单层 / decision 死分支 | `os/` 多处 |
| 169 | 问候语机械：按小时硬编码切分（`hour < 12 → morning`），凌晨也算 morning，不感知人类作息；建议按作息智能化（深夜/凌晨单独问候）| `BriefAppService.java:129` / `:105` |
| 171 | 优化方向（非问题）：项目分类记录无聚合——domain=project 记录散在 Feed/时间线，项目页只有状态+任务；用户以"日志记录问题/优化建议"为入口，建议项目页增加「项目记录」聚合视图 + 记录可标记类型（问题/建议），并可流转为任务 | `adai-app` 项目页 + domain 体系 |
| 172 | 记忆页 superseded 记忆仍显示「待办/已完成」标记（语义矛盾：被取代的历史版本不是当前待办）+「已取代」仅靠卡片变浅、区分度低含义不明；建议 superseded 记忆隐藏 actionable 标记，已取代状态加明确灰角标说明（如「已被新记忆取代」）| `memory_page.dart:239-261` |
| 173 | 优化方向（L4 演进）：带图提问——图片上传固定 intent=log（`MediaRecordAppService.recordImage` 硬编码），不支持"发图+问句→AI 基于图回答"；建议加 intent=question 通道 + AI 对话带图上下文 | `MediaController.uploadImage` / `MediaRecordAppService.recordImage` |
| 202 | 后端 P3 打磨（generate 剥代码块 + 旧数组账号日期回归测试已修）：`userTradeLocks` 按 userId 无界累积 / `AiClient.generate(ctx, null)` 默认 system 仍是 JSON 分析指令与生成语义矛盾 | 后端多处 |
| 229 | 图片追问打磨（_tagsCache 清理 + @userId tooltip/hover 已修）：首轮把「图片摘要文本」渲染成用户气泡（应居中「图片上下文」提示而非冒充用户消息）/ 折叠渐隐遮罩色与半透明卡背景不一致 / #15 折叠对超长 active 卡不设上限（几十轮全量渲染，桌面端布局压力）/ `main()` runApp 前 await `UserStore.loadUserId()` 首帧延迟 | `main_page.dart:926-930` / `feed_card.dart:633-646,617` / `main.dart:11-22` |
| 247 | `ProjectStatusAppService` 端点资源缺失返回 0（被前端呈现「0 个端点」），0 与「未知」语义混淆 | `ProjectStatusAppService.java:145-146` |
| 248 | `RecordFileRepository.parseFromFile` 损坏文件静默返回 null（#19 直读路径放大：单文件 frontmatter 损坏 → 该记录在 Feed/时间线/搜索无声消失，磁盘文件仍在）| `RecordFileRepository.java:227-255` |
| 249 | `findById` 的 `id.matches("rec_\\d{8}_\\d{9}")` 每次调用重编译正则（理解/重补/删除/Feed 热路径）| `RecordFileRepository.java:66` |
| 250 | GlobalExceptionHandler 413 消息硬编码「图片最大 5MB」，与 application.yml `max-file-size` 配置漂移时失真 | `GlobalExceptionHandler.java:26-30` |
| 251 | adai-core CLAUDE.md 包结构树未登记 `kernel/ai` 与 `kernel/storage` 端口包（新读者误以为 AiClient/FileStorage 仍在 infra）| `services/adai-core/CLAUDE.md` 树 |
| 252 | `DeepSeekAiClient` `@Value` 默认值仍 `deepseek-chat`（application.yml 为 deepseek-v4-pro，#232 只改了部署文档）| `DeepSeekAiClient.java:47` |
| 253 | 选号页 loading spinner 尺寸双端不一致（adai-app SizedBox 28×28 vs adai-web 裸 spinner 默认 ~36px）| 双端 `account_select_page.dart` |
| 254 | 记忆日期格式双端不一致（adai-app `M/d` vs adai-web `MM-dd`，跨年同）——#125 只补了年份逻辑未对齐格式 | 双端 `memory_page.dart` |
| 255 | 「加载更早/加载更多」文案双端不一致；桌面端多图上传无进度占位（#174 仅移动端）| `adai-web feed_page.dart:635-657` / `adai-app main_page.dart:629` |
| 256 | serve_web 校验假阴性：未来 Flutter 模板 load 自带 `config:` 键（无 canvasKitBaseUrl）时 perl 注入产生重复 config → JS last-wins 模板覆盖注入块，`grep -o canvasKitBaseUrl` 计数仍 1 → 校验通过却仍从 CDN 拉白屏 | 三端 `serve_web.sh:22-33` |
| 257 | 测试覆盖缺口：#234 分页终止口径无测试锁（mock 全 type:record 未混附加条目）；#174 占位卡→重试→替换状态机无 widget 测试（#235 漏网原因）| `adai-web review_fixes_test.dart` / adai-app 无 #174 测试 |
| 258 | 选号 SnackBar 双端时长不一致（移动默认 4s / 桌面 2s）+ 快速双击连弹两条（`_handlingSelect` 不挡 SnackBar）| 双端 `account_select_page.dart` |
| 259 | REVIEW 统计口径差一：成本表「21（战略 3+1+P2 4+P3 14）」分解=22；头部「P3 方向项 8+打磨 4」=12 vs 实际表 11 | `REVIEW.md:109,10` |
| 260 | adai-core README.md 仍写「DeepSeek API (deepseek-chat)」（application.yml 实际 deepseek-v4-pro）| `services/adai-core/README.md:33` |
| 261 | system-architecture UML 图未补 kernel 新端口 `TagIndexReader`（只有 TagIndex 标 infra）| `docs/architecture/system-architecture.md` class 图 |
| 262 | stripCodeFences 边界（可接受记录）：仅尾围栏不剥 / 正文天然以 ``` 结尾时误剥 3 字符 / 无头围栏整体包裹——复盘正文罕见含代码块，无需修 | `LlmResponseParser.java:40-53` |
| 263 | 99-inbox 预存在项（非本批）：`7家公司IPO...json` 与 `-gemini.json` MD5 重复；`AI 图形知识工程.md`/`outline.md` 缺尾部换行 | `os/trading-os/99-inbox/` |

## ✅ 已修复区（最近 10 条，旧条目随滚动删除）

| # | 问题 | 修复 |
|:-:|:-----|:-----|
| 批 O 收官 | **2026-08-12 收官批 O（一次清完剩余该做项，用户决策：战略+P2+P3 顺手项，方向项不动）**：**战略** #101 Feed「加载更早」分页（adai-web page 从 0 起 + `_hasMore` + 追加加载）/ #103 Timeline/Memory 保活刷新入口（PageHeader 刷新按钮 + ApiService `force` 绕过缓存）/ #177 多账号切换链路测试（选号/切换重建 ValueKey/持久化降级/双击防重入/UserStore 双实现 io 分支，adai-app +10 测试——顺带修复 DualWorldShell 主页面 ApiService 未共享的**缓存分裂 bug**）/ #179 保持现状登记 v1.0.1（用户决策：登录体系单独立项）；**P2** #19 `RecordFileRepository` 优化（findAll 收窄 `records/` 目录 + findById id 推导路径直读 + 兼容回退）/ #22 kernel 反向依赖倒置（`FileStorage`→`kernel/storage`、`AiClient`+`AiUnderstanding`→`kernel/ai` 端口在 kernel 实现在 infra、抽 `CardRepository`/`TagIndexReader` 接口，ContextEngine 只依赖 kernel 接口）/ #115 Feed 右栏联动刷新（各操作路径补 `_loadSidebar` + askMedia/endConversation 清 `_tagsCache`）/ #228 端点计数单一口径（移除 Java 扫源码回退，统一 Gradle `endpoints.txt` + 生成跳过注释行）；**P3** #166 上传 413（GlobalExceptionHandler）+ title emoji code point 截断 / #170 待办建议第二人称 prompt / #202 `LlmResponseParser.stripCodeFences`（generate 复盘剥围栏）+ 旧数组账号日期回归测试 / #231 `AiInteractionLog` 加 `systemPrompt` / #232 部署文档模型名 / #122 frontend-reference 色值对齐 token + API 表补 8 端点 / #125 记忆页跨年年份 / #198/#230 选号页 spinner/切换 SnackBar/按压反馈/阿呆控制台文案 / #199 时间线全图 error+loading / #200 serve_web 三端 canvasKitBaseUrl 唯一校验 / #201 userId 溢出保护 / #203 候选文件尾换行 + R35 半角括号 / #229 `_tagsCache` 清理 + @userId tooltip/hover / #233 CLAUDE.md 端点表 3 新端点 + freeze §2.13 systemPrompt/intent 说明。后端 **362** · adai-app **78** · adai-web **40** 全绿 | ✅ 2026-08-12 |
| #216 + #217 + #223 | **2026-08-12 修复批 N（数据安全 + 契约）**：**#216 CardMigration 误判即删/数据淹没**——`parseAsCard` 判定收紧（`type: conversation` 或 body 含「用户：」对话标记，原「含 `## ` 即视为卡片」太宽）；缺 `id` 字段的文件跳过（不再并入 `card_unknown` 被 findAll 合并淹没），+3 测试；**#217 rewriteId 锚定 frontmatter**——改写只在首对 `---` 之间的 frontmatter 段（`group(1)` 替换重拼），body 中的 `id:` 行不再被误改 → 双文件复发根除；**#223 CLAUDE.md os/ 只读例外登记**——adai-core 声明只读补 promote 写 `99-inbox/` 唯一例外说明。后端 359 全绿 | ✅ 2026-08-12 |
| #129 + #218 + #222 | **2026-08-12 修复批 M**：**#129 promote 前端入口（战略闭环）**——双端交易页复盘弹窗加「反哺入库」按钮（`promoteReview` API 传 `{}`，成功后展示 #178 message 提示），知识反哺闭环前后端打通；**#218 visual durationMs**——`LoggingVisualAiClient` understand/ask 测真实耗时（对齐 `LoggingAiClient`），不再恒 null；**#222 问候加中午段**——`greetingForHour` 加 11-13 → 中午好（`greetingEnForHour` midday、`emojiForHour` 🌤️），12 点不再机械归下午。api-spec v3.13；后端 355 · adai-app 68 · adai-web 30 全绿 | ✅ 2026-08-12 |
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
| 2026-08-12 | deep 增量（收官批 O 深度审核）| backend/frontend/docs/product/knowledge ×5 | 5 | ~20min | 战略×1 + P1×5 + P2×7 + P3×18（含本批自伤 4）| 0 |
| 2026-08-12 | 收官批 O（战略+P2+P3 一次清完）| subagent×2（adai-app/adai-web）+ 主会话（后端/文档）| 2 | ~4h | 0 新 | 21（战略 3 修+1 登记 + P2 4 + P3 14 部分修）|
| 2026-08-12 | light 增量（批 L/M/N + 顶部摘要快扫）| — | 0 | ~5min | 0 新 | 0（守护 7 PASS / 0 HIT）|
| 2026-08-12 | 修复批 N（#216/#217 CardMigration 数据安全 + #223 契约）| — | 0 | ~35min | 0 新 | 3（#216 P2 + #217 P2 + #223 P2）|
| 2026-08-12 | 顶部摘要优化（阿呆反馈：去绿点 + 行数 3→5）| — | 0 | ~25min | 0 新 | 1（今日概览卡前缀冲突 + 行数）|
| 2026-08-12 | 修复批 M（#129 promote 入口 + #218 durationMs + #222 中午段）| — | 0 | ~40min | 0 新 | 3（#129 战略 + #218 P2 + #222 P2）|
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
