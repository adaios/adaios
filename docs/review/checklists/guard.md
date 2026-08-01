# 守护检查清单（每次 /review 必跑）

防 P0 复发（数据丢失/契约破坏）。grep 级成本。**命中即报告，即使上次已知**——它是复发信号。

> 格式：`[命令]` 检查什么。`上次发现` 记录历史命中，用于判断复发。

## 数据安全

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| G1 | `grep -rn "ID_FORMATTER\|generateId" services/adai-core/src/main/java` — 确认所有 ID 生成含毫秒 `SSS`（`yyyyMMdd'_'HHmmssSSS` 形态）| Record/Memory 秒级精度 → 同秒覆盖（P0，已修）|
| G2 | `grep -rn "LocalDate.now()\|Instant.now()" services/adai-core/src/main/java/infrastructure/storage` — filePath/持久化不得用 `now()`，必须从实体 `createdAt` 推导 | Card 路径固定今天 → 跨日复制丢轮次（P0，已修）|
| G3 | `grep -rn "deleteById\|delete(" services/adai-core/src/main/java/interfaces services/adai-core/src/main/java/application` — 确认 AI 失败等降级路径**不删除**刚保存的用户数据 | RecordController AI 失败时删用户记录（P1，已修）|

## 正则健壮性

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| G4 | `grep -rn "Pattern.DOTALL" services/adai-core/src/main/java` — 配合 DOTALL 时字段匹配用 `[^\n]*` 而非 `.+`/`.*`（跨行贪婪）| ENTRY_PATTERN 吞文件只解析 1 条（P0，已修）|

## 契约一致性

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| G5 | `grep -rn "@JsonGetter\|@JsonIgnore" services/adai-core/src/main/java/domain` — 计算字段（PnL/市值等）必须序列化，前端 fromJson 才能读到 | Position 计算字段不序列化 → PnL 恒 0（P1，已修）|
| G6 | `grep -rn "setState" apps/adai-app/lib --include=*.dart` — 异步回调 setState 前必须有 `mounted` 守卫 | `_loadFeed`/`_loadMore` 漏守卫（P1，已修）|

## 场景路由

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| G7 | `grep -rn "compose(\|enrichFromContributors" services/adai-core/src/main/java/kernel/context` — 确认 scene 实际传入 Contributor 的 `supports()`，而非死参数 | `"trading"` scene 从未传入 → 知识注入全失效（战略缺口，已修）|

---
**追加方式**：发现新的 P0 级风险模式 → 在对应分组下追加一行，注明日期。
