---
title: 项目审核全量状态报告
updated: 2026-08-15
last-review: 2026-08-15
baseline: 7b0a527..HEAD（33 commits，批 Q/R + 展示层聚合 + 插件模型 + 文档治理 + research 整合）
mode: deep 增量（批 Q/R + 展示层聚合 + 发布核对）
---

> **结构（RFC `20260815-docs-governance` 减负）**：本文件只留「战略 + P0-P2 未修复 + 最近审核摘要 + 执行成本」；已修复详情见 `docs/reference/change-log.md` + git log；P3/观察项已迁移 `docs/reference/task-log.md`。

> 2026-08-15 deep 审核（范围 `7b0a527..HEAD`，33 commits / 181 文件——批 Q/R + 展示层聚合 + 插件模型 + 文档治理）：守护 7 PASS / 0 HIT；派 backend/frontend/docs ×3。**P0 无。战略×2 + P1×5 + P2×7 + P3×21（P3 迁移 task-log）**。核心：P1-B1（CHAT domain 枚举双重引号，每轮必触发）+ P1-B2/B3（时间线聚合跨天/跨会话误删）+ P1-B4（图片记录 domain 未 gateDomain）+ P1-D1（review-context 断链）；**P2-R1 reopen：P2-6 竞态实际未修复（「P2 清零」误报，已回滚）**；已沉淀检查点 B34-36 / F33-36 / D27-29。
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
| S-R1 | **插件门控失败降级与恢复路径双端不对拍**：web 端 P2-5 有 SnackBar+重试；app 端 `_loadPlugins` 失败静默吞错且 launcher 无任何重试/刷新路径——移动端插件接口瞬时失败 → 交易/阿呆系统入口静默消失，重启前不可恢复（F25 双端对拍违反） | `launcher_page.dart:102-111` vs `desktop_shell.dart:93-101` | 🔴 未修（app 加重试/刷新入口，双端降级统一）|
| S-R2 | **PATCH 全量替换（read-modify-write）是 admin toggle 竞态根因**：`updateAccount` PATCH 全量 plugins，任何「读→本地改→全量写回」并发必然互覆——前端怎么防都是治标 | `api_service.dart:118-127` + `accounts_page.dart:94-123` | 🔴 未修（服务端合并语义 `plugins:{add/remove}` 或专用 toggle 端点，可排插件模型二期）|

> S-2（展示层聚合）已出表（`eb5e707`）；数据层整体化 RFC `20260815-media-event-unification` approved 排 v1.0.1。S-3/S-4 已出表（批 Q，但见 P1-B4/P2-B1/P2-B2 边界漏网）。

## 🔴 P1（未修复）

| # | 问题 | 位置 | 建议 |
|:-:|:-----|:-----|:-----|
| P1-B1 | **CHAT 模式 system prompt domain 枚举双重引号（P2-4 实现缺陷，每轮必触发）**：`buildDomainEnum()` 返回带首尾引号的枚举（供 `"domain": %s` 嵌入），`DeepSeekAiClient` 手拼 `"domain": \"" + ctx.domainEnum() + "\""` 又包一层 → `"domain": ""life(生活)/trading(交易)"",` 非法 JSON 模板——LLM 照模板输出即解析失败降级（summary 截断/tags 丢失/domain 恒 life）；`PluginIsolationTest` 只断言中间值未覆盖消费方拼接 | `DeepSeekAiClient.java:224` + `ContextEngine.java:576` | 统一 `domainEnum` 为不带引号纯枚举，两处消费方各自显式包引号，补最终拼接文本测试 |
| P1-B2 | **时间线聚合跨天误删**：`fullTimeline` 全量收集 image_qa 引用图，8/14 传图 8/15 追问（跨天常态）→ 8/14 历史图片事件被过滤消失（Feed 只查当天无此问题，口径不同步）——违背「一次输入=一个事件」（跨天传图与追问是两个输入） | `TimelineProjection.java:60,146` | 聚合限定同日期：image 记录仅当与引用它的 image_qa 同天才合并；补跨天测试 |
| P1-B3 | **时间线聚合跨会话/跨类型误匹配**：按「用户轮次文本前 60 字符」匹配无卡片身份限定——① 跨会话同文本（都问"帮我总结"）记录归并错误桶被全 drop、该会话时间线整体消失；② 匹配不要求 `intent=question`，普通 log 记录内容恰与轮次文本相同也被误 drop | `TimelineProjection.java:102-136` | 匹配加 `intent=question` 过滤 + 卡片身份/就近限定（宁缺勿误删）；补跨会话同文本测试 |
| P1-B4 | **图片记录 domain 未走 gateDomain（S-3 持久化入口漏网）**：`recordImage` 落盘 `ImageUnderstanding.domainOf(category)` 直接写（VLM 判 category=trading → 无插件用户落盘 `domain=trading` image 记录），违反 D5「无插件用户不落盘 trading 标注」；`askImage/askImages` 硬编码 life 安全 | `MediaRecordAppService.java:100` | 落盘前 `pluginService.gateDomain(userId, ...)`；补 PluginIsolationTest 图片路径用例 |
| P1-D1 | **`.claude/agents/review-context.md` 引用已移出路径断链**：引用 `research/context-reviewer.md` + `research/项目级 AI 上下文建设哲学与体系原理.md`，research/ 已移出仓库（57e7193）且方法论已合并改名——agent 加载即失效 | `.claude/agents/review-context.md:47-48` | 改指 `../ai-context-research/`（仓库外）或改写说明 |

## 🔴 P2（未修复）

| # | 问题 | 位置 | 建议 |
|:-:|:-----|:-----|:-----|
| P2-R1 | **P2-6 竞态修复未达成目标（reopen，回滚「P2 清零」）**：重取 `_accounts` 与旧闭包在全部可达场景等价（`_accounts` 变更必伴随 setState+rebuild）；真实时序 t0 点 trading→PATCH 在飞、t1 点 project→`_accounts` 仍旧→PATCH 互覆仍丢一个开关——新代码仅修复亚帧窗口；当前 32 测试无一覆盖双连点 | `accounts_page.dart:94-123` | per-account 并发守卫/串行队列或服务端合并语义（S-R2）；补「延迟 store + 双开关连点 → 两个都开」widget 测试 |
| P2-B1 | **trading 写入口未门控（S-4 写侧不完整）**：`POST /trades`（录持仓）、`POST /review`（存复盘）未加 `hasPlugin(trading)`——无插件用户可直接写持仓/复盘残留（与 promote 403 不对称） | `TradingController.java:74,93` | 与 promote 同口径门控（403）或明确「读放行、写需插件」决策并 api-spec 注明 |
| P2-B2 | **S-4 脏数据 NPE 全局中断**：`hasPlugin(a.userId())` 遇 userId=null 账号在 filter 阶段（try-catch 外）NPE → 整个定时轮询中断（S-4 前该 NPE 在 poll(userId) 内被 catch 单用户跳过，行为回归）；`Account` 紧凑构造器只防 plugins null 未防 userId null | `MarketAlertService.java:95` + `Account.java:26-29` | Account 拒绝/归一 null userId；`findById` 改 `Objects.equals`；filter 阶段 null 防护 |
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

## 执行成本

| 日期 | 模式 | 派发角色 | agent 数 | 耗时 | 新增 | 修复 |
|:-----|:-----|:---------|:--------:|:-----|:----:|:----:|
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
