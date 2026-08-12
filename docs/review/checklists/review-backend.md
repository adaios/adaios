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

---
**追加方式**：新发现后端问题 → 追加一行，注明日期。
