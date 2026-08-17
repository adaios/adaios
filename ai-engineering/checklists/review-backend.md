---
title: 后端代码审查检查清单
description: backend-reviewer 逐条检查项（人也能用）——数据流水线/存储健壮性/分层/AI 集成/测试
version: 1
created: 2026-08-15
updated: 2026-08-17
status: active
lines: 109
depends-on: []
related: [../roles/backend-reviewer.md]
tags: [review, checklist, backend]
---

# 后端审核检查点清单

> 格式：`[检查方法]` — 检查什么。`上次发现` 记录历史命中。新发现模式追加到底部。守护项（G#）见 `guard.md`，不重复。

## 数据流水线

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| B1 | Record → Timeline → Context → Memory 链路是否完整；新增功能是否绕过（手拼 prompt、跳过 ContextEngine）| 复盘手拼 prompt 规则从不进 prompt（P1 #12 待办）|
| B2 | `RecordFileRepository`/`CardFileRepository` save 时是否触发标签索引、ID 格式是否 `rec_`/`card_` 前缀 + 毫秒 | Record/Memory 漏毫秒（P0，已修）|

## Context / Knowledge

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| B3 | `ContextEngine.detectDomainScene` 关键词路由是否覆盖新场景；`supports()` 与实际 scene 匹配 | `"trading"` scene 从未传入（战略缺口，已修）|
| B4 | 各 `KnowledgeSource`（Trading/Life/Project）读取路径配置是否有效、缓存是否随文件更新 | Life-os-path 死配置（战略缺口，已修）|
| B5 | `MarketContextContributor.globalContext()` 是否始终注入大盘（不依赖 positions 非空）| 空持仓返回空上下文（战略缺口，已修）|

## 存储健壮性

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| B6 | `listFiles` 是否扫描全部月份而非硬编码范围 | 任务只扫最近 12 个月（P2，已修）|
| B7 | save/delete 重建文件是否丢手写注释 | 重建丢弃手写注释（P2 #21 待办）|
| B8 | 新增 Repository 是否有测试覆盖解析/保存/回读 | 测试缺口（P2 #14 待办）|

## 分层与死代码

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| B9 | kernel 不得反向依赖 infrastructure 类型（IntentRecognizer/ContextEngine/MemoryService 3 处为已知债）| 已知技术债（#22 待办，不新增）|
| B10 | 编排重复（RecordController/RecordFlowAppService/RecordRetryService compose→understand→persist 三处）| 已知重构项（#13 待办，不新增）|

## AI 集成

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| B11 | `LlmResponseParser` 处理 surrogate pair（emoji）是否正确推进 matcher region | emoji 代理对抛异常 → 降级丢字段（P1，已修）|
| B12 | AI 调用失败时数据不丢失、有降级路径 | AI 失败删记录（P1，已修）|
| B13 | FileStorage.write/writeBytes 必须「临时文件 + ATOMIC_MOVE」原子写，禁止直接截断写 | 崩溃/断电写坏单文件存储（P0 #126，待修）|
| B14 | 「整文件重写」类仓库（Memory/TagIndex/Position/Account/任务月文件）save 必须 synchronized / per-user 锁 | 并发 RMW 静默丢更新（P0 #126，待修）|
| B15 | 删除/媒体路径必须从持久化 createdAt 推导，禁止从 ID 内嵌时间戳推导 | 月边界静默删除失败（P1 #136，待修）|
| B16 | 索引类服务必须有 onRecordDeleted 清理钩子 | 删除不清理索引 → 幽灵计数（P1 #137，待修）|
| B17 | rebuild/重补幂等判定须覆盖「Phase5 fact 跳过」场景，不能用 hasRealMemory 作为未处理判据 | rebuild 幂等被筛选逻辑架空（P1 #144，待修）|
| B18 | 全字段序列化 round-trip 核对：实体字段是否都写 frontmatter 且读回一致 | intent 不落盘导致 rebuild 过滤失效（P1 #144，待修）|
| B19 | 新端点/新功能启用时检查鉴权：多账号下所有「读任意用户/写任意用户」入口必须校验 | 多账号零鉴权裸奔（战略 #127，待修）|
| B20 | 图片上传 content-type 白名单：未知类型不得默认 png | HEIC 落 png 预览坏（P2 #146，待修）|
| B21 | Repository `toMarkdown`/serialize 改动后 grep `data-format-freeze.md` 对应小节确认同步（格式契约漂移检测）| intent 落盘漏同步 freeze §2.1（P1 #180）|
| B22 | 「扫源码/扫文件系统」型统计/聚合必须评估 jar-only 生产形态退化 | countApiEndpoints 生产恒 0（P2 #187）|
| B23 | 幂等/处理标记类修复必须枚举所有入口路径验证（不能只测 cardId==null）| #144 漏聊天首问带 cardId 主路径（P1 #181）|
| B24 | 外部 AI 服务依赖（GLM/DeepSeek 等 key + base URL）部署后必须跑真实功能 E2E（不只是 HTTP 200），验证 key 已配齐 | 生产缺 GLM_API_KEY → 图片理解/追问一直静默降级（2026-08-11 生产事故）|
| B25 | 新增 File First 落盘目录必须同步 .gitignore（`git check-ignore` 验证），防隐私数据进 git 历史 | ai-logs 落盘新目录但漏 ignore → prompt 全文可被 git add -A 提交（P0 #226，2026-08-12）|
| B26 | 幂等/处理标记不得用「内容文本」兼任哨兵：`"recorded"` 同时被 retry/rebuild/兜底三处消费，AI 成功但长摘要也会被误判未处理 | summary="recorded" 哨兵导致记录每 15 分钟无限重补（P1 #207，2026-08-12）|
| B27 | 引入 ThreadLocal 追踪上下文（AiTraceContext 类）必须配请求级清理钩子（HandlerInterceptor.afterCompletion remove）| AiTraceContext 跨请求残留 → 漏 set trace 路径落错用户目录（P2 #213，2026-08-12）|
| B28 | 装饰器/代理新增接口方法必须同步实现（AiClient 加 generate 后 LoggingAiClient/TestAiClient 均需同步）| 本次已同步 ✓；`LoggingAiClient.generate` 未记录 systemPrompt（P3 #231，2026-08-12）|
| B29 | 防御性回退禁止用 `LocalDateTime.now()` 推导持久化字段（G2 只 grep `LocalDate.now()|Instant.now()` 漏了它）| `parseDateTime` 缺 updatedAt 回退 now() → 旧卡永久归"今天" Feed（P1 #206，2026-08-12）|
| B30 | 「生成/统计资源」类 Gradle 任务必须声明 `inputs.dir`（只声明 outputs.dir 时增量构建 up-to-date 判定不因源变化失效、生成物陈旧）| generateEndpointsFile 缺 inputs.dir → 增量构建端点数陈旧（P2 #240，2026-08-12）|
| B31 | 启动迁移/补全字段的逻辑必须枚举「管理端写路径能否把该字段显式置空」——若 PATCH 允许空值，迁移不得用 `isEmpty()` 判定补全（需读原始字段存在性），否则构成「删了又出现」（K28 删除残留镜像）| 迁移补默认 vs PATCH 显式清空 adai 插件冲突（P1-4，2026-08-15）|
| B32 | 收敛/门控类改动必须 grep 出所有持久化目标字段的写入口逐一验证，不能只测主入口（B23 扩展）——方法：`grep -rn "domain =.*understanding.domain()"` 枚举未收敛点 | D5 收敛漏 `RecordRetryService.processRecord` 重补路径（S-3，2026-08-15）|
| B33 | 新增功能使「私人记录内容」以派生形态（任务/摘要/标签）进入**已跟踪**目录时，需评估该目录 gitignore 覆盖是否仍成立 + `git status --short data/` 看新 `data/{userId}/` 目录是否裸露 | R2 通用化后生活待办流入 git 跟踪的 project/tasks，非 owner 用户目录裸露（P1-3，2026-08-15）|

---
**追加方式**：新发现后端问题 → 追加一行，注明日期。
| B34 | 展示层聚合/过滤逻辑必须评估边界：① 跨天引用——全量查询的聚合不得按「引用存在性」跨日期隐藏被引实体（跨天传图与追问是两个输入）；② 匹配键冲突——按内容文本聚合须带会话身份限定或 `intent` 过滤，宁缺勿误删 | 时间线聚合跨天/跨会话误删（P1-B2/P1-B3，2026-08-15）|
| B35 | 跨类拼接的「字段语义」必须逐消费方核对并断言**最终拼接文本**——字段自带引号/包裹字符时，`%s` 嵌入与手拼字符串的消费方必须一致（测试只断言中间值会漏网） | CHAT domain 枚举双重引号 `"domain": ""life...""`（P1-B1，2026-08-15）|
| B36 | 插件门控类改动必须按**端点清单**（grep Controller 全部 mapping）而非仅数据持久化路径枚举——promote 403 但 `/trades`/`/review` 未门控；S-3 枚举 3 条文本路径却漏图片路径 | trading 写入口/图片记录未 gateDomain（P1-B4/P2-B1，2026-08-15）|

---
**追加方式**：新发现后端问题 → 追加一行，注明日期。

| B37 | frontmatter 时间字段解析**禁止 now() 回退**——缺失返回 null + 调用方跳过/拒删（RecordFileRepository/CardMigrationService/MemoryService 3 处仍违规，CardFileRepository #206 为正确范式）| parseDateTime 回退 B29 复发（走查 P1-W12，2026-08-15）|
| B38 | 展示自然化**全路径枚举**——grep `r.content()`/`highlight(` 定位所有直接透传 content 的展示点（Search 已漏），逐路径应用 ImageQaFormatter | 搜索未自然化（走查 P1-W3，2026-08-15）|
| B39 | 卡片类多行字段 round-trip 必须配「写→读→写→读」断言测试（turns/summary/suggestion 含 \n）| 多行 turn 截断 P0（走查 P0-W1，2026-08-15）|
| B40 | 插件门控**对称性检查**——trading 与 project 两插件写端点逐对枚举（project tasks 未门控已命中）| 门控旁路（走查 P1-W13，2026-08-15）|
| B41 | 手动/管理写入口（PATCH /records/{id}/domain、cards/migrate、cards/cleanup）纳入 D5/B32 枚举 | domain 旁路（走查 P1-W13，2026-08-15）|
| B42 | Feed 与 Timeline 聚合逻辑**同源**——intent 过滤/歧义保守口径两端一致 | Feed 去重无 intent 过滤（走查 P1-W4 同族，2026-08-15）|
| B43 | 目录/路径迁移必须 grep **配置默认值**同步（application.yml、@Value 默认、deploy.sh 模板 env）——`grep -rn "旧路径" services/adai-core/src/main/resources services/adai-core/deploy.sh` 与 os/ 实际目录逐条核对 | yml 残留 11-context（框架+插件 G-4 审查 P1，2026-08-16）|
| B44 | 确定性规则引擎口径与知识真相源对拍：契约测试读 rules.md 断言关键判定词（R66 含"收盘跌破"、R81 含"1/4到1/5"），知识变更即击穿 | 引擎口径无联动校验（G-3 审查战略，2026-08-16）|
| B45 | 硬判定/硬约束信号必须配**输出侧校验**：引擎 verdict 与 LLM 输出 suggestion 冲突要覆盖或告警（prompt 指令是软的）| BREACHED 但 LLM 输出 hold 透出（G-3 审查 P2，2026-08-16）|
| B46 | 新增占比类指标必须核对**分母口径**（总资产 vs 总持仓市值）——R81 硬信号分母不含现金即此模式 | R81 占比恒 100% 错发 reduce（G-3 审查 P1，2026-08-16）|
| B47 | 新功能**测试同批**：服务层业务逻辑 + 端点测试与功能同一批完成，不后置（解析/upsert/状态保留/写回关键分支必测）| 自选/清仓/资金业务逻辑后置补测（2026-08-16 反思）|
| B48 | 交付前主动跑三件套门禁（guard-meta/align/guard.sh），不依赖 pre-commit 兜底；gradle 绿 ≠ 项目绿 | 部署后才补 feature-reference/guard 正则（2026-08-16 反思）|
| B49 | 禁硬编码路径：知识/数据路径必须经 @Value/yml 注入（grep `os/trading-engine` 硬编码相对路径必须全部配置驱动）| CURRENT_MD 硬编码 ../../os/...（交易 A-E 审查 P1，2026-08-17，3487b00 只修一半）|
| B50 | R81 占比分母=持仓市值+现金（总资产口径）；positionPercent 等新占比计算必须核对分母 | positionPercent 分母不含现金误发减仓（交易 A-E 审查 P1，2026-08-17）|
| B51 | 导入解析失败禁止落零：CASH_HEAD 等解析未命中时不得用零值覆盖已有数据（失败返回错误，不静默写盘）| importCashQuery 解析失败覆盖 account.json（交易 A-E 审查 P1，2026-08-17）|
| B52 | 现金单一真源：snapshot.cash / positions.md cashBalance / 转账推导必须收敛，账目类字段禁止多处独立推导 | 现金 3 真源（交易 A-E 审查战略，2026-08-17）|
| B53 | 线程池必须 @PreDestroy shutdown；异步批量任务失败项不得产空 symbol 占位行 | SoldScoreService 16 线程池无关闭 + 30s 超时占位（交易 A-E 审查 P2，2026-08-17）|
| B54 | 批量扫描按标的异常隔离：单只失败不中断整批（try/catch per item）| scanWatchlist 无异常隔离（交易 A-E 审查 P2，2026-08-17）|
| B55 | 共享单文件（accounts.json 等）的 RMW 必须文件级全局锁：per-user 锁挡不住跨用户并发写同一文件 | accounts.json 跨用户互覆（走查 8 官批 2，2026-08-17）|
| B56 | 文件写必须原子（tmp+move）：promote/账户等写一半即损坏全文件，禁止直接覆盖写 | promote 非原子写（走查 8 官批 2，2026-08-17）|
| B57 | 锁对称性：同文件 save 加锁则 delete/writeAll 必须同锁（漏一侧即并发损坏）| ProjectFileRepository.delete 无锁（走查 8 官批 2，2026-08-17）|
| B58 | 多线程调度的 append 类写入按 per-user+date 加锁，防 4 线程并发丢事件 | MarketPushRepository 并发丢事件（走查 8 官批 2，2026-08-17）|
| B59 | 批量任务 per-user 异常隔离：单用户抛错不得中断整批处理 | RecordRetryService 单用户中断整批（走查 8 官批 2，2026-08-17）|
| B60 | 持仓元信息更新（PUT /positions）等 RMW 写路径必须进同文件锁（tradeLock）| updatePositionMeta 未进锁（走查 8 官批 2，2026-08-17）|
| B61 | 现金/账目读取全量收敛单一真源：新增占比/快照/建议读取点必须走 AccountSnapshot，禁止再读 positions.md cashBalance | S5 现金单一真源（走查 8 官战略，2026-08-17）|
