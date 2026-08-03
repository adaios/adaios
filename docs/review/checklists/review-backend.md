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

---
**追加方式**：新发现后端问题 → 追加一行，注明日期。
