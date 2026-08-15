---
title: 守护检查清单
description: 每次 /review 必跑的 G1-G7 防 P0 复发清单（数据丢失/契约破坏），执行器为 docs/review/guard.sh
version: 1
created: 2026-08-15
updated: 2026-08-15
status: active
lines: 48
depends-on: []
related: []
tags: [review, checklist, guard]
---

# 守护检查清单（每次 /review 必跑）

防 P0 复发（数据丢失/契约破坏）。**执行器：`docs/review/guard.sh`**（一条命令跑完 G1-G7，输出 PASS/HIT，内部自动 cd 到仓库根，免疫 cwd 漂移）。本清单是"查什么 + 上次发现"的说明文档，实际执行以脚本为准。任何模式都不跳过。

> 格式：`[命令]` 检查什么。`上次发现` 记录历史命中，用于判断复发。

## 数据安全

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| G1 | 所有 ID 生成含毫秒 `SSS`（`yyyyMMdd'_'HHmmssSSS` 形态）— 脚本查 `ID_FORMATTER` 常量 + `generateId()` 方法内的 `ofPattern`，排除显示格式化（`HH:mm` 等非 ID 用途）| Record/Memory 秒级精度 → 同秒覆盖（P0，已修）|
| G2 | storage 层不得用 `LocalDate.now()/Instant.now()` 推 filePath，必须从实体 `createdAt` 推导 | Card 路径固定今天 → 跨日复制丢轮次（P0，已修）|
| G3 | 删除调用发生在 **catch 降级路径内** → 危险；脚本用 awk 追踪 catch 块，正常业务删除（REST `@Mapping` 方法、`deleteTask` 等业务方法）豁免 | RecordController AI 失败时删用户记录（P1，已修）|

## 正则健壮性

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| G4 | DOTALL 下**字段级捕获**用 `[^\n]*` 而非贪婪 `.+`/`.*`（跨行吞内容）— 脚本查 `field:\s*(.` 形态；frontmatter 的 `^---\n(.+?)\n---\n(.+)` 是抓整体正文的有意跨行（无冒号前缀），豁免 | ENTRY_PATTERN 吞文件只解析 1 条（P0，已修）|

## 契约一致性

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| G5 | `grep -rn "@JsonGetter\|@JsonProperty" services/adai-core/src/main/java/domain/trading` — 计算字段（PnL/市值等）必须序列化，前端 fromJson 才能读到 | Position 计算字段不序列化 → PnL 恒 0（P1，已修）|
| G6 | `grep -rn "setState" apps/adai-app/lib --include=*.dart` — 异步回调 setState 前必须有 `mounted` 守卫 | `_loadFeed`/`_loadMore` 漏守卫（P1，已修）|

## 场景路由

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| G7 | `grep -rn "contextEngine.compose\|engine.compose" services/adai-core/src/main/java/application` — 确认 scene 实际传入 Contributor 的 `supports()`，而非死参数（允许固定字面量如 retry 的 `compose("note", record)`，但须存在传变量的调用）| `"trading"` scene 从未传入 → 知识注入全失效（战略缺口，已修）|

---
**追加方式**：发现新的 P0 级风险模式 → 在对应分组下追加一行，注明日期。
