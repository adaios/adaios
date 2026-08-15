---
title: 项目审核全量状态报告
updated: 2026-08-15
last-review: 2026-08-15
baseline: 7b0a527..HEAD（33 commits，批 Q/R + 展示层聚合 + 插件模型 + 文档治理 + research 整合）
mode: deep 增量（批 Q/R + 展示层聚合 + 发布核对）
---

> **结构（RFC `20260815-docs-governance` 减负）**：本文件只留「战略 + P0-P2 未修复 + 最近审核摘要 + 执行成本」；已修复详情见 `docs/reference/change-log.md` + git log；P3/观察项已迁移 `docs/reference/task-log.md`。

> 2026-08-15 deep 审核（范围 `7b0a527..HEAD`，33 commits / 181 文件）：守护 7 PASS / 0 HIT；派 backend/frontend/docs ×3。**P0 无。战略×2 + P1×5 + P2×7 + P3×21（P3 迁移 task-log）**。**修复批 S + S2 共 12 项已出表**（P1-B1-B4/D1 + P2-B1/B2/R1/R2/R3/D1/D2，后端 440 · app 94 · admin 33）；**战略剩余仅 #179（v1.0.1 登录体系）**；已沉淀检查点 B34-36 / F33-36 / D27-29。
> 2026-08-15 上午 deep 审核（范围：工作树未提交改动——第二步插件系统 T2.1-T2.10 + 第一步遗留，47 文件）：守护 7 PASS / 0 HIT；派 backend/frontend/docs ×3。**战略×2 + P1×6 + P2×7 + P3×15**。P0 无。战略 S-3（重补路径 domain 未收敛）+ S-4（行情推送写侧未门控）；P1 六项；已沉淀检查点 B31-33 / F30-32 / D23-26。S-3/S-4/P1 全部出表（批 Q/R）。
> 2026-08-14 deep 审核（范围 `7b0a527..HEAD`，18 commits，带图 ask / 删除残留 / 图片交互批）：**P0×1 + 战略×2 + P1×2 + P2×2 + P3×14**。P0-1 + P1-1 + P1-2 + P2-1 + S-1 已修复出表；S-2 展示层已修（层 2 数据层另立 v1.0.1）。

# 项目审核状态报告

常驻全量状态。每次 `/review` 更新本文件——未修复项滚动保留，已修复标 ✅ 移入已修复区，新问题追加。git 历史天然保留每次更新前的快照。

## 最近审核

| 日期 | 模式 | 基线 | 派发角色 | 新增 | 修复 |
|:-----|:-----|:-----|:---------|:-----|:-----|
| 2026-08-15 | deep 增量（批 Q/R + 展示层聚合 + 发布核对）| 7b0a527..HEAD | backend/frontend/docs ×3 | 战略×2 + P1×5 + P2×7 + P3×21 | 0（审核不直接修）|
| 2026-08-15 | deep 增量（Domain=插件模型 + step-1）| 7b0a527 + 工作树 | backend/frontend/docs ×3 | 战略×2 + P1×6 + P2×7 + P3×15 | 0（审核不直接修）|
| 2026-08-14 | deep 增量（带图 ask + 删除残留 + 图片交互批）| 7b0a527..HEAD | 主会话 + docs/frontend agent×2 | P0×1 + 战略×2 + P1×2 + P2×2 + P3×14 | 5（P0-1 + P1-1 + P1-2 + P2-1 + S-1）|

> 更早审核（08-01 ~ 08-12）见「执行成本」表 + git 历史。

## 🔴 战略缺口（未修复）

| # | 问题 | 位置 | 状态 |
|:-:|:-----|:-----|:----:|
| 179 | 用户层 X-User-Id 零鉴权（任何人传任意 userId 即可读对应数据）；数据访问靠 header 注入无认证。真正收紧需登录体系 | `AccountController` / `WebConfig` | 📋 v1.0.1 立项 |
> S-R1（app 插件失败 SnackBar+重试，双端对拍）与 S-R2（服务端合并插件端点，竞态根治）已出表（2026-08-15，见已修复区）。S-2（展示层聚合）已出表；数据层整体化 RFC `20260815-media-event-unification` approved 排 v1.0.1；S-3/S-4 已出表（批 Q）。

## 🔴 P1（未修复）

| # | 问题 | 位置 | 建议 |
|:-:|:-----|:-----|:-----|
> **P1 当前清零**（2026-08-15 修复批 S + S2 全部出表：P1-B1/B2/B3/B4 + P1-D1，见已修复区）。

## 🔴 P2（未修复）

| # | 问题 | 位置 | 建议 |
|:-:|:-----|:-----|:-----|
> **P2 当前清零**（2026-08-15 修复批 S + S2 全部出表：P2-B1/B2/R1/R2/R3/D1/D2，见已修复区；历史观察项已迁移 task-log）。
| P2-R2 | **adai-app launcher 插件失败降级无测试、无反馈（F32 未闭环）**：测试缺 `['trading']` 单独分支与 500 降级分支；失败路径无任何用户反馈（与 web P2-5 不一致，见 S-R1） | `pages_widget_test.dart:366-415` + `launcher_page.dart:102-111` | 补两测试并同步 task-log 出表 |
| P2-R3 | **内置 admin 插件开关未按 isProtected 门控**：enabled/删除有保护、插件开关 Row 无——可关掉 owner 插件 | `accounts_page.dart:536-545` | 插件开关加 `isProtected` 门控（禁用态 + Tooltip）+ 断言测试 |
| P2-D1 | **docs/README RFC 状态过期**：`20260815-media-event-unification` 说明仍写「draft 待确认」，实际已 approved | `docs/README.md:73` | 改 approved 说明 |
| P2-D2 | **RFC approved 后正文决策点未清理**：frontmatter 已 decided，§七「等你拍板」措辞遗留，后续会话误读为未决策 | `docs/rfc/20260815-media-event-unification.md:65-68` | 决策点标注「已决策」或删除该节 |

> P2-7 由 status.md 单源化；历史观察项（#117/#149/#153/#176）已迁移 task-log。

## 🔴 P0 / P3

- **P0 未修复当前清零**
- **P3 打磨项全部迁移** `docs/reference/task-log.md`（2026-08-15 两轮 deep 新增 21 项：后端 6 + 前端 8 + docs 7，已入待办迁移区）

## ✅ 已修复区（最近 10 条，一行摘要；详情见 `docs/reference/change-log.md` + git log）

| # | 摘要 | 修复 |
|:-:|:-----|:----:|
| S-R1/S-R2（deep 战略项）| launcher 插件失败 SnackBar+重试（双端对拍 web）+ 服务端合并插件端点 `PATCH /accounts/{id}/plugins`（账号级锁原子 add/remove，根治 PATCH 全量并发互覆）+ admin 改走合并语义 + 内置 admin 插件服务端保护；api-spec v3.20；后端 446（+6）| ✅ 2026-08-15 |
| 修复批 S2（P2-B2/R2/R3 + P1-D1 + P2-D1/D2）| Account null userId 拒绝（全局中断）+ admin 内置插件开关 isProtected 门控 + launcher 测试补分支 + review-context 断链 + RFC/docs 状态同步；后端 440（+1）· app 94（+2）| ✅ 2026-08-15 |
| 修复批 S（P1-B1-B4 + P2-B1 + P2-R1）| deep 审核后端/前端修复：domainEnum 去引号语义（CHAT 双重引号根治，补最终拼接断言）+ 时间线聚合跨天/intent/歧义边界 + 图片 domain gateDomain + trading 写入口门控（403）+ admin 插件 toggle 串行队列；后端 439（+6）· admin 33（+1）| ✅ 2026-08-15 |
| S-2（展示层）| 「一次输入 = 一个事件」：时间线多轮 chat 每会话单条 + 带图 ask image_qa 聚合为图文事件（引用图不单独成条，缩略图取首图）；Feed 同口径；前端零改动；数据层整体化另立 v1.0.1；后端 433（+4）| ✅ 2026-08-15（层 2 另立）|
| 批 R（P1-5/P1-6/P2-5/P1-7/P2-8）| 前端+文档：adai-web 壳 label 重解析防索引错位 + 插件失败 SnackBar 重试；adai-app Launcher 插件接口拆独立 try/catch；api-spec D1 通用化同步；feature-reference 补插件模型章节；web 47（+1）| ✅ 2026-08-15（**P2-6 除外，reopen 见 P2-R1**）|
| 批 Q（S-3/S-4/P1-4/P2-2/P2-3/P2-4）| 后端插件门控/健壮性六连修：重补路径 gateDomain + MarketAlert 写侧 trading 门控 + 账号迁移读字段存在性 + domain 规则关键词单一真相源 + Account null 过滤 + ContextPackage 收敛 domainEnum；后端 429（+7）| ✅ 2026-08-15（边界漏网见 P1-B1/B4/P2-B1/B2）|
| P1-3 | `data/*/project/` 隐私面补齐（gitignore + git rm --cached）| ✅ 2026-08-15 |
| S-1 | adai-web 多图 ask 同步（askBatch + 上限 3 + `_syncActiveCard`）| ✅ 2026-08-14 |
| P0-1 + P1-1 + P1-2 | 对话态发媒体崩溃/残留错乱/部分失败问句丢（`_syncActiveCard` + `_pendingAsk`）| ✅ 2026-08-14 |
| #169 + #257 | 问候语机械 + 测试覆盖确认出表 | ✅ 2026-08-13 |
| 批 P | deep 31 项清 22：#234 分页终止 + #235-#238 P1 + #240-#246 P2 + P3 14 项 | ✅ 2026-08-12 |
| 批 O 收官 | #101/#103/#177 战略 + #19/#22/#115/#228 P2 + P3 顺手 14 项 | ✅ 2026-08-12 |
| #216 + #217 + #223 | CardMigration 判定收紧 + rewriteId 锚定 frontmatter + os/ 只读例外 | ✅ 2026-08-12 |

## 🔍 全维度走查（docs/ai/process/audit.md）

> 7 审查官独立并行全量走查，交叉印证（同一问题多官命中 = ⭐ 优先级高）。走查日期 + 摘要滚动保留。

### 2026-08-15 首轮（7 官全量）

> 守护 7 PASS / 0 HIT。**P0×1 + 战略×3 + P1×16 + P2×14 + P3×27**。**修复批 W1+W2 后端已出表**（W1：P0-W1 卡片单行化 + W10 SafeArea + W1/W2 双端重试 + W3/W4 自然化 + W16 基建自伤；W2 后端：W12 parseDateTime / W13 门控旁路 / W14 prompt 引号 / W15 标签索引重建，后端 454）；剩余前端 P1：W5 失败伪装空态 / W7 切 World 丢图 / W9 全图 Dialog / W11 对比度（W6 超时 + W8 删除确认已修）。检查点沉淀 B37-42 / F37-44 / D30-35 / K29-35 / C-P1-5 / U10-17 / V1-3。

**🔴 P0（数据丢失）**

| # | 问题 | 位置 |
|:-:|:-----|:-----|
| P0-W1 | **对话卡片多行 turn 读-写不对称 → 对话历史静默截断丢失**：`toMarkdown` 原样写多行 AI 回答，`parseTurns` 只取前缀所在行 → 下次保存时截断覆盖原文件。须补「写→读→写→读」round-trip 测试（含 \n）| `CardFileRepository.java:186-254` |

**🏛 战略**

| # | 问题 | ⭐ |
|:-:|:-----|:--:|
| S-W1 | roadmap（唯一蓝图）未收录已 approved 的 Domain=插件模型方向——已实现能力在蓝图不可见 | ⭐ |
| S-W2 | 双端「值复制」修复漂移成常态（图片重试/删除确认/全图 Dialog/状态机各落一端）——建议固定对拍项或抽共享 package | ⭐⭐ |
| S-W3 | 请求层无超时/无取消/无响应归属校验——所有等待态卡死防御靠 UI 补丁 | ⭐ |

**🔴 P1（合并同族后 13 项）**

| # | 问题 | 位置 | ⭐ |
|:-:|:-----|:-----|:--:|
| P1-W1 | **web 图片上传失败重试降级为文本记录**（占位卡无字节，重试走 createRecord 文本）——app 已修 web 漏 | `feed_page.dart:171-176,554-576` | ⭐⭐ |
| P1-W2 | **app 文本重试新建 cardId**——半失败（服务端已落库响应超时）重试重复入账；web 复用原 id 幂等 | `main_page.dart:495-507` | ⭐⭐ |
| P1-W3 | **搜索展示未自然化（第一原则）**——SearchService.highlight 原样返回【图片文字】【备注】问：/答：标签；web 搜索还暴露 type 徽标 | `SearchService.java` + `search_page.dart` | ⭐⭐ |
| P1-W4 | **时间线第三人称对话总结**——conversation/ai_summary（"用户…AI…"）原样进时间线，Feed 跳过而 Timeline 展示（口径不一致）| `TimelineProjection.java` + `timeline_page.dart` | ⭐ |
| P1-W5 | **失败伪装空态/静默吞错**（5 处：timeline_modal/web search/memory/timeline/launcher）——失败显示「无记录/全 0」误导 | 多文件 | ⭐⭐ |
| P1-W6 | **请求无超时 → waiting 无限转圈**；结束 vs 在途回复竞态复活 chatting；`TimeoutException` 分支是死代码 | `api_service.dart` + `main_page.dart` | ⭐ |
| P1-W7 | **多图上传中途切 World 丢剩余图**（!mounted 中断循环，无反馈）| `main_page.dart:376` | — |
| P1-W8 | **删除无确认（app）**——web 有确认 app 无，双端不一致 | `main_page.dart:649-659` | — |
| P1-W9 | **全图 Dialog 3 处裸 Image.network**（404=白框）——已修 4 处，3 处未接入公共 Dialog | 3 文件 | — |
| P1-W10 | **LauncherPage 无 SafeArea**——拖拽条 y=12 侵入状态栏 + 下滑返回与系统手势冲突（校准案例「背面主页误触」根因）| `launcher_page.dart` | ⭐ |
| P1-W11 | **darkGrey6 系统性对比度不足**（~2.1:1）当引导文案用（8 文件空态文案）| 多文件 | — |
| P1-W12 | **parseDateTime now() 回退复发（B29）**——脏 createdAt → 月边界删除失败 + Feed 错计 | `RecordFileRepository.java:285` | — |
| P1-W13 | **门控旁路 3 处**：PATCH /records/{id}/domain 绕 gateDomain、project tasks 写端点未门控、cards/cleanup 文本匹配误删 | `RecordController` / `ProjectStatusController` / `CardMigrationService` | — |
| P1-W14 | **STATEMENT prompt 缺闭合引号**——AI 照模板输出非法 JSON → 主流水线解析降级 | `ContextEngine.java:563` | — |
| P1-W15 | **tags.json 索引陈旧**——rebuild() 无调用者，仅覆盖 17/201 记录 → Context 标签关联注入失效 | `TagIndexService.java:156` | — |
| P1-W16 | **docs/ai 基建自伤**：roles frontmatter depends-on 14 处断链 + 审查官计数 7 vs 8 + status.md 端点 51 实为 52 | `docs/ai/roles/*` / `status.md` | — |

**🔴 P2（11 项，详见 task-log）**：双端重试/删除/文案/色值对拍（多官）、记忆页日期连点乱序、admin 队列无错误恢复、Feed 缩略图无降采样、web caption 丢失、项目写端点门控、accounts.json 非原子、TagIndex 并发 RMW、交易不落 Record 流水线、记忆序列化三缺陷、alice 越界 domain/残留、positions 错配、os/ 知识杂项、roadmap 状态漂移、feature-reference 过期等。

> P2/P3 完整清单已迁移 `docs/reference/task-log.md`（2026-08-15 首轮走查区）。P3 打磨项全部入待办。

## 执行成本

| 日期 | 模式 | 派发角色 | agent 数 | 耗时 | 新增 | 修复 |
|:-----|:-----|:---------|:--------:|:-----|:----:|:----:|
| 2026-08-15 | **全维度走查（首轮）** | 7 官全量并行 | 7 | ~1h | P0×1 + 战略×3 + P1×16 + P2×11 + P3×35 | 0（审查只报告）|
| 2026-08-15 | deep 增量（批 Q/R + 展示层聚合 + 发布核对）| backend/frontend/docs ×3 | 3 | ~40min | 战略×2 + P1×5 + P2×7 + P3×21 | 0（审核只报告）|
| 2026-08-15 | deep 增量（Domain=插件模型 + step-1）| backend/frontend/docs ×3 | 3 | ~30min | 战略×2 + P1×6 + P2×7 + P3×15 | 0（审核只报告）|
| 2026-08-14 | deep 增量（带图 ask 批）| docs/frontend ×2 + 主会话 | 3 | ~40min | P0×1 + 战略×2 + P1×2 + P2×2 + P3×14 | 5 |
| 2026-08-12 | 修复批 P | subagent×2 + 主会话 | 2 | ~2h | 0 新 | 22 |
| 2026-08-12 | deep 增量（收官批 O 深度审核）| ×5 角色 | 5 | ~20min | 战略×1 + P1×5 + P2×7 + P3×18 | 0 |
| 2026-08-12 | 收官批 O | subagent×2 + 主会话 | 2 | ~4h | 0 新 | 22 |
| 2026-08-12 | 修复批 N/M/L + 顶部摘要 + 隐私批 | — | 0 | 逐批 20-40min | 0 新 | 14 |
| 2026-08-12 | deep 增量（R1 AI 日志批）| ×5 角色 | 5 | ~20min | P0×2 + 战略×1 + P1×8 + P2×15 + P3×5 | 0 |
| 2026-08-12 | P1 修复批 A-D + #184 | — | 0 | ~90min | 0 新 | 13 |
| 2026-08-09 | 批 K + deep 增量 + 验证修复 + 批 J | ×5 / 主会话 | 5 | ~2.5h | 战略×3 + P1×2 + P2×10 + P3×14 | 22 |
| 2026-08-03 | full 全量（v0.3.0 前）| ×5 角色 | 5 | ~30min | P0×1 + 战略×7 + P1×13 + P2/P3×30 | 0 |
| 2026-08-02 | full 全量（v0.1.0）| ×5 角色 | 5 | ~25min | 前端 3 + 后端 P1 4 + 文档 | 后端 P1 4 |
| 2026-08-02 | deep 增量（adai-web）| ×3 角色 | 3 | ~10min | P0×1 + 战略×3 + P1×9 + P2×8 | 0 |
| 2026-08-01 | 全量 + deep 增量 | ×3 / docs/knowledge | 5 | ~2.5h | 43 | 23 |

> 跑几轮后用成本数据决定全量审核频率（目前建议：增量随时，全量 1-2 周一次）。
