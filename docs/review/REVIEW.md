---
title: 项目审核全量状态报告
updated: 2026-08-09
last-review: 2026-08-09
baseline: 7aecf9d
mode: deep 增量（多账号前端选号 + wasm 修复 + 数据迁移）
---

# 项目审核状态报告

常驻全量状态。每次 `/review` 更新本文件——未修复项滚动保留，已修复标 ✅ 移入已修复区，新问题追加。git 历史天然保留每次更新前的快照。

## 最近审核

| 日期 | 模式 | 基线 | 派发角色 | 新增 | 修复 |
|:-----|:-----|:-----|:---------|:-----|:-----|
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
| 129 | 知识反哺闭环缺前端入口：promote 后端闭环 ✅（v1.0.0 验证走通，已产出 `99-inbox/review-2026-08-09.md` 真实复盘），但交易页无「反哺入库」按钮，UI 无法操作 promote | `os/trading-os/99-inbox/` | 📋 待办 |
| 177 | 多账号前端全链路零测试覆盖：v1.0.0 提前的核心功能（首屏选号 needsSelect / 切换重建 ValueKey / UserStore 条件导出双实现 / getAvailableAccounts DTO / 持久化降级）全部无 widget 测试，后端补了 2 个但前端 0 个，违背"新功能必须配套测试" | `apps/adai-app/test/` / `apps/adai-web/test/` | 📋 待办 |
| 178 | 反哺闭环最后一公里未走：promote→inbox ✅，但入库→融合（rules/mistakes）→ 11-context 重建未完成，`TradingKnowledgeSource` 只读 `11-context/`，本次洞察（满仓单票违反 R81/E1）不会进 AI context | `os/trading-os/99-inbox/review-2026-08-09.md` | 📋 待办 |
| 179 | `/accounts/available` 无鉴权暴露账号枚举面：返回全部启用账号 `userId/role/createdAt`，与用户层 X-User-Id 零鉴权（#127 延迟项）组合成目录键枚举面；设计上知情（选号提前），多账号正式开放时须随用户层鉴权收紧 | `AccountController.java:52-60` / `WebConfig.java:46` | 📋 待办 |

## 🔴 P1（未修复）

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| 180 | 数据格式契约漂移（发布前红线）：#144 让 record 落盘 `intent:` frontmatter（parse 回读），但 freeze §2.1 仍写「intent 不落盘（save 不写，parse 置 null）」；且 §2 单用户路径仍写 `data/default/`（已迁 `data/adai/`）| `RecordFileRepository.java:191` / `docs/architecture/data-format-freeze.md:12,43` | 📋 待办 |
| 181 | #144 rebuild 幂等漏掉聊天首问主路径：前端新聊天首条带 `cardId`（`main_page.dart:316`），后端 `answer()` 走 `cardId != null` 分支 → **intent=question 永不落盘** → rebuild 当 log 重跑，每次烧 AI + 污染记忆索引；本次 diff 只修了 `cardId == null` 分支 | `QuestionAppService.java:92` | 📋 待办 |

## 🔴 P2（未修复）

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| 19 | Feed/Context/Memory 每次全量遍历 data 目录 | `RecordFileRepository.findAll` | 📋 待办（数据量小）|
| 22 | kernel 反向依赖 infrastructure（现 4 处：IntentRecognizer/ContextEngine/MemoryService/Memory.java）| 多处 | 📋 待办 |
| 115 | Feed 右栏（简报/标签云/任务快照）不随操作/刷新更新，数据陈旧 | `feed_page.dart:76-88` | 📋 待办 |
| 117 | 测试覆盖缺口：✅ Feed 状态机 12 测试（`feed_state_machine_test.dart`）+ 6 页面 widget 测试（`pages_widget_test.dart`：memory/timeline/search/trading/task/profile 数据渲染 + 错误态 + 重试）；缓存 key 分桶未测（价值低，留待多账号批）| `test/` | ✅ 主体 |
| 149 | 多账号细节：accounts.json 无锁 / 删号不清理数据 / 允许创建 default | `AccountFileRepository` / `AccountController` | 📋 待办（v1.0.1）|
| 153 | 数据形态失衡：08 月 131/133 条为对话摘要，原始 note <2% | `data/default/records/2026/08/` | 📋 观察 |
| 176 | 交易录入无严格校验：`TradeRequest` 仅 `@NotBlank`/`@Positive`，可录入错误代码（如 000300 当贵州茅台）→ 行情/持仓/复盘/反哺全污染；建议三层校验（格式 6 位数字+市场前缀 / `quote` 存在性 / 名称匹配模糊比对）；用户指出**输入校验 + 持仓分析 + 反哺流程**整体待打磨（v1.0.0 后批次）| `TradeRequest` / `TradingAppService.recordTrade` / 交易表单 | 📋 待办 |
| 182 | 前端默认 `userId='default'` 与数据实际 `data/adai/` 错位：绕过选号流程的请求（测试/curl/缺失 X-User-Id）落空 `data/default/` 分支，读写不可见 → 数据静默分裂（本次删除的 default 孤儿即此机制）| `api_service.dart:24`（双端）+ 后端 45 处 `defaultValue="default"` | 📋 待办 |
| 183 | 行情推送硬编码 `default` 已成"僵尸"：`MarketAlertService` 轮询 `List.of("default")`，账号变更时静默落在空目录漏检且无告警；注释仍写"数据在 data/default/"（已迁 adai）| `MarketAlertService.java:81,85` | 📋 待办 |
| 184 | 复盘入库候选 git 追踪真实持仓：`99-inbox/review-2026-08-09.md` 含"茅台 100 股/市值 14 万/现金 0"，与 `data/*/trading/` gitignore 隐私策略相反（K8 红线被绕开）| `os/trading-os/99-inbox/review-2026-08-09.md:18` | 📋 待办 |
| 185 | 切换选号 `onSelect` 非幂等：快速双击可重复 `nav.pop()`（debug 断言 / release 未定义）+ 重复 `saveUserId`；双击「切换账号」入口可叠两层选号页 | `main.dart:78-81`（双端）| 📋 待办 |
| 186 | URL `?userId=` 永久压过持久化：带参刷新后切换账号回退原账号，"记住上次账号"对带参访问失效；`?userId=default` 被当"无参数"语义分裂 | `main.dart:16-18`（双端）| 📋 待办 |
| 187 | `countApiEndpoints` 生产恒 0：扫源码目录但部署只 scp jar（无源码树）→ 动态统计从硬编码 21 退化；子串 split 会误计 Javadoc/注释 | `ProjectStatusAppService.java:136-148` | 📋 待办 |
| 188 | 无条件回写覆盖用户提交 tags：AI 返回空 tags 时 `List.of()` 抹掉 `request.tags()`（用户标签丢失）；旧代码仅 AI tags 非空才回写 | `QuestionAppService.java:96-98` | 📋 待办 |
| 189 | rebuild 过滤丢失「有 summary 无记忆」重跑路径：`handleStatem` 先写 summary 再 persist 记忆，若 persist 失败 → 记录有 summary 无记忆 → 新过滤跳过，永久无记忆 | `MemoryController.java:140` | 📋 待办 |
| 190 | 首屏选号空态死路：文案叫「刷新」但无重试按钮、无关闭（首屏无 AppBar leading），唯一出路浏览器硬刷新；错误态有重试而空态没有，行为不一致 | `account_select_page.dart:110-119`（双端）| 📋 待办 |

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
| 191 | api-spec 版本未升版：3 处实质改动（§12 portfolio / §15 分页 / §16 available）但头部仍 v3.7、变更记录缺 2026-08-09 行 | `api-spec.md:5` |
| 192 | Release Notes 发布日期过早定稿：v1.0.0.md:6 写「2026-08-09（验证通过）」但 tag/部署待用户确认（外向动作门禁）| `docs/releases/v1.0.0.md:6` |
| 193 | REVIEW 批 J 行「后端 302」与实测 300 不符（c28757f 已同步 300）；#153 观察项路径 `data/default/records/2026/08/` 已因迁移失效 | `REVIEW.md:47,75` |
| 194 | api-spec:37「当前单用户可省略（默认 default）」迁移后成陷阱：省略 X-User-Id 读不存在目录返回空 | `api-spec.md:37` |
| 195 | `profile.sample.md` body 内「真实档案是 data/identity/profile.md」路径过期（多账号分层后为 `data/{userId}/identity/`）| `data/adai/identity/profile.sample.md:15` |
| 196 | `alpha: 100` 越界（`withValues` 取值范围 0-1）：`100` 经 `toARGB32` 实际渲染 ~74% 透明，非意图全不透明（同文件 `alpha: 0.3/0.5/0.15` 惯例不一致）| `account_select_page.dart:140`（双端）|
| 197 | `clearUserId()` 死代码：UserStore 双实现均定义但无调用路径（无"清除记住"入口）| `user_store_io.dart:28-33`（双端）|
| 198 | 选号页 loading 态仅灰字无 spinner；切换账号后无确认反馈（SnackBar「已切换至 @xxx」）；账号行无按压反馈（InkWell）；空态文案中英混排「请在管理后台（adai-admin）创建账号」| `account_select_page.dart`（双端）|
| 199 | 时间线全图 Dialog 无 errorBuilder/loadingBuilder：后端 404 或慢加载显示异常占位 | `timeline_page.dart:100-114` |
| 200 | `serve_web.sh` bootstrap 补丁无回归校验：perl 注入 `canvasKitBaseUrl` 若与 wasm 产物原有 `config` 重复键（后写者胜）→ 仍从 CDN 拉（被墙白屏）| 三端 `serve_web.sh` |
| 201 | 桌面底部账号 Row 无溢出保护：超长 userId 在 200px 导航内横向溢出 | `desktop_shell.dart:147-157` |
| 202 | 后端 P3 打磨：`findTodayCards` 过滤 `updatedAt != null` 旧版存量卡消失（低影响）/ `generate` 不剥 JSON 代码块（AI 违抗时复盘渲染破坏）/ `userTradeLocks` 按 userId 无界累积 / `AiClient.generate(ctx, null)` 默认 system 仍是 JSON 分析指令与生成语义矛盾 / 旧数组账号日期读取兼容无回归测试 | 后端多处 |
| 203 | knowledge 入库候选小瑕疵：候选文件尾无换行符 / 文件名 `review-YYYY-MM-DD.md` 不符 trading-os 约定（应 `YYYY-MM-DD_主题.md`）/ 「R35)」半角括号混排 / `migrate-data-to-user-layer.sh` 目标仍是 `data/default/`（被 default→adai 取代）/ `.gitignore` 的 `data/*/identity/profile.md` 只保护单文件（identity 下新非 sample 文件会被提交）| 多处 |

## ✅ 已修复区（最近 10 条，旧条目随滚动删除）

| # | 问题 | 修复 |
|:-:|:-----|:-----|
| 阿呆系统必现崩溃 | **2026-08-09 v1.0.0 验证修复（阿呆系统页 CanvasKit 必现崩溃）**：点击「阿呆系统」release minify **必现** `PictureRecorder` wasm 崩溃（`canvaskit.wasm` 内存分配失败，`core_patch.dart:293 Uncaught Error`）且控制台持续输出——该页首帧绘制 + 路由过渡动画并发 + 加载 spinner 无限重绘，触发 CanvasKit 绘制密集不稳定（**非项目 bug**，同路由动画的记忆/时间线/任务/交易页均正常，仅该页绘制密度触发）。修复：阿呆系统入口改无动画跳转（`PageRouteBuilder` duration zero，去并发动画帧）+ 加载 spinner 换静态「加载中…」（去持续重绘源）；adai-app analyze 0 · 60 测试全绿 | ✅ 2026-08-09 |
| TimelinePage 缩略图 | **2026-08-09 v1.0.0 验证修复（World B 时间线缩略图）**：批 2"时间线页缩略图"只做了 TopBar TimelineModal，World B `TimelinePage`（launcher 入口"时间都去哪了"）漏了缩略图 → 补 `_buildDayEntries` mediaPath 缩略图（96px）+ `_showFullImage` 原图 Dialog（复用 TimelineModal 模式）；adai-app analyze 0 · 60 测试全绿 | ✅ 2026-08-09 |
| 复盘生成 | **2026-08-09 v1.0.0 验证修复（复盘生成语义）**：复盘走 `understand`（JSON 摘要语义）——默认 system"输出 JSON summary 3-5 词"压制复盘 5 节正文模板 → AI 只回"交易复盘，持仓不变"一句话；根因复盘是生成型任务却复用理解型接口。修复：AiClient 新增 `generate(ContextPackage, systemPrompt)` 生成语义（自定义 system 引导正文格式 + 0.7 temp/2048 tokens），`TradingReviewAppService` 改走 generate；验证产出完整 5 节复盘且引用真实规则（R4/R117/R119/E20/R1）。适配 DeepSeekAiClient + TestAiClient + 2 匿名实现 + TradingReviewAppServiceTest（后端 298 全绿）| ✅ 2026-08-09 |
| updatedAt + #175 | **2026-08-09 v1.0.0 验证修复（Feed 首屏 + 时间基准）**：卡片时间基准改 updatedAt——跨日续接对话归最后活跃日（`CardFileRepository.findTodayCards` 由按目录查创建日改为全量扫 + 按 updatedAt 过滤；`FeedAppService.toCardFeedEntry` 卡片 time/date 用 updatedAt）/ #175 分页 page 0 返回完整 size 条核心、余数放末页（`FeedAppService.getFeed` 分页改新在前切片）；api-spec 分页/时间基准说明同步；新增 CardFileRepositoryTest 2 + FeedAppServiceTest 2（后端 298 全绿）；前端 `_loadMore` 顺序自洽零改动 | ✅ 2026-08-09 |
| 批 J | **2026-08-09 P1 清理（v1.0.0 核心闭环）**：#144 rebuild 幂等（intent 落盘 + summary 处理标记 + 降级记录仍重跑升级，`RecordFileRepository`/`MemoryController`/`QuestionAppService`）/ #147 SELL 未持有与超额报错（`TradingException` + GlobalExceptionHandler 400）+ 持仓读改写加锁 + 清仓 0 行不落盘 / #106 api-spec portfolio 契约对齐（补后端 `positionCount` 派生字段，adai-web 持仓数不再恒 0）/ #112 CANCELLED 任务看板可见（adai-web 补第四列）/ #150 apiEndpoints 动态统计（硬编码 21 → 实际 46）+ FeedAppService 死依赖移除；后端 302 · adai-web 27 测试全绿 | ✅ 2026-08-09 |
| 批 I | **2026-08-07 adai-app 对话体验收尾**：#13+#11 card 写入剥离 AI 原始 JSON（`LlmResponseParser.extractNaturalText` + `QuestionAppService` 写卡与返回均剥 JSON，实时=刷新，card 文件不再混入游离 JSON）/ #148 Feed ai_note 按记录日期归属（`MemoryService.findByRecordIds` 跨日补齐 + `toAiEntry` 用记录时间，重补/升级跨日不错日不丢失）/ MD1 世界切回 Feed 刷新（`DualWorldShell` ValueNotifier → `MainPage.refreshTick` 重载，覆盖 admin 记忆重建后 Feed 陈旧）；后端 286 测试 · 前端 60 测试 | ✅ 2026-08-07 |
| 批 H | **2026-08-06 adai-web 桌面残留清理 9 项**：#102 交易页复盘入口（markdown 复盘弹窗）/ #132 红涨绿亏（A股语义，快照+DataTable）/ #161 时间线 type 徽标中文化（13 类映射+未知兜底）/ #131 桌面文案全量中文化（shell/feed_card/task/feed/profile/project/网络错误）/ #124 CLAUDE.md 端口 8082 / #158 记忆页待办完成按钮 / #159 Feed 空态快速引导 chips（prefill 聚焦）/ #118 `_check` utf8 解码 / #165 type 硬转换兜底；#120 确认已实现未重复、#121 评估低优先级不修 | ✅ 2026-08-06 |
| 127 | **2026-08-06 最小封闭鉴权**：admin/accounts 端点 `X-Admin-Token` 拦截（常量时间比较、未配置 fail-closed 503）+ CORS `*`→配置化 origin 白名单（默认 localhost:*）；adai-admin 前端 `ADMIN_TOKEN` dart-define 注入；4 鉴权测试 + api-spec v3.6 | ✅ 2026-08-06 |
| 批 G | **2026-08-05 adai-app 6 页面 widget 测试（#117 剩余）**：`pages_widget_test.dart` 14 测试——memory/timeline/search/trading/task/profile 六页数据渲染 + #108 错误态人话 + 重试按钮（复用批 F MockClient 基建）| ✅ 2026-08-05 |
| 批 F | **2026-08-05 adai-app 质量锁定**：#117 Feed 状态机 12 个 widget 测试（`feed_state_machine_test.dart`：ask→waiting→chatting→ended / 追加 / 错误重试 / 删除 / 加载更多 / #100 竞态，ApiService 注入 MockClient 测试性改造）/ #123 状态机文案全量中文化（ask·log·end·chat·结束对话，adai-app 零英文残留）| ✅ 2026-08-05 |
| 批 E | **2026-08-04 adai-app 主轴修复 5 项**：#108 故障 vs 无数据（memory/timeline/search/task 4 页错误态+重试，profile 已好）/ #113 错误态人话（trading+task）/ #114 确认切日期已有 spinner 覆盖 / #116 确认交易提交已有 SnackBar 反馈 / #162 Feed push 类型双端映射 | ✅ 2026-08-04 |
| 164 | adai-app 语音误导性 stub 移除（语音移入 v2 方向，砍可切态+长按录音入口，`input_bar.dart`）| ✅ 2026-08-03 |
| 160 | api-spec mediaPath 示例日级→月级（批2 契约修正）| ✅ 2026-08-03 |
| 批 A-D | **2026-08-03 连续修复 22 项**：批 A 数据安全（#126 Memory 并发写锁+原子写 / #136 删除路径 createdAt / #137 TagIndex 删除钩子 / #138 cashBalance 保留 / #128 重补遍历用户）；批 B 前端状态机（#100 竞态崩溃 / #104 删卡残留 / #105 双卡互踩 / #107 缓存失效 / #109 删除确认 / #110 retry 幂等 / #111 mode 同步 / #119 计数）；批 C 契约编码（#133 kind 三端 / #134 错误态人话 / #140 优先级透传 / #145 utf8 解码 / #146 HEIC）；批 D 数据+文档（#135 frontmatter 单行化 + 存量 5 条 / #139 卡片双副本 / #151 悬空核实保留 / #152 count 校正 / #130 VISION 状态表 / #141 README 索引 / #142 roadmap 模型名 / #143 RFC frontmatter / #154-157 CLAUDE.md 树计数 / #167 feature-reference）| ✅ 2026-08-03 |
| 60/61/62 | v0.2.0 前端 actionable UI 消费：action 待办卡+完成按钮（`ca2d4a8`）、Feed 分页终止修复、memory 页 kind/superseded/待办展示（`7d9b607`）| ✅ 2026-08-02 |
| 后端 P1 ×4 | actionable 筛选豁免 + 无限重补修复 + ID 单调统一 IdGenerator + rebuild 幂等 + 跨日升级（`c41c2b7`）| ✅ 2026-08-02 |
| 13 | interfaces 层编排重复三处 → RecordUnderstandingService 统一（`bdb83da`）| ✅ 2026-08-02 |
| 33/38/39/41/21/23 | 第三批 6 项：review 路由表补 `.claude/**`；README 索引；os definition 愿景声明；data-flow 对齐；ProjectFileRepository 注释；TradingController 解析真实 rules.md | ✅ 2026-08-01 |
| 12/24/14 | 第二批 3 项：记忆沉淀断裂、复盘走 ContextEngine、测试缺口 9 个 | ✅ 2026-08-01 |
| 16a/34/35/36/37/40/42/43 | 第一批快修 8 项：孤儿卡片迁移；ship grep 修正；RFC 滚动；guard 对齐；trading README；CLAUDE.md 焦点；guard.sh G1 防挂起；零碎 | ✅ 2026-08-01 |
| 25-32 | 前端 P3 打磨 8 项（URL 编码/文本清理/日期硬编码/静默刷新/死代码/light主题）| ✅ 2026-08-01 |
| 15-20 | 数据目录 + api-spec v3.1 + CLAUDE.md 对齐 + 任务扫全部月份 | ✅ 2026-08-01 |
| 7-11 | P1 功能 bug 5 项（emoji 代理对/行情缓存键/AI失败保数据/mounted守卫/PnL序列化）| ✅ 2026-08-01 |

## 执行成本

| 日期 | 模式 | 派发角色 | agent 数 | 耗时 | 新增 | 修复 |
|:-----|:-----|:---------|:--------:|:-----|:----:|:----:|
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
