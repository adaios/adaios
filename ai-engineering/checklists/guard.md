---
title: 守护检查清单
description: 每次 /review 必跑的 G1-G10 防 P0 复发清单（数据丢失/契约破坏/口径漂移），执行器为 docs/review/guard.sh
version: 1
created: 2026-08-15
updated: 2026-09-14
status: active
lines: 59
depends-on: []
related: []
tags: [review, checklist, guard]
---

# 守护检查清单（每次 /review 必跑）

防 P0 复发（数据丢失/契约破坏）。**执行器：`docs/review/guard.sh`**（一条命令跑完 G1-G10，输出 PASS/HIT，内部自动 cd 到仓库根，免疫 cwd 漂移）。本清单是"查什么 + 上次发现"的说明文档，实际执行以脚本为准。任何模式都不跳过。

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
| M4 | `bash ai-engineering/guard-meta.sh`（M4 项）— 扫描强制区文档正文中的仓库内路径引用 + bash 命令路径，断言目标存在（防 docs/ai 类迁移残留复发）| docs/ai→ai-engineering 迁移 16 处残留（自伤自查 6 官 ⭐，2026-08-15）|

## 推送 / 配置 / 入口契约（G8-G10，2026-09-14 批次追加）

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| G8 | `bash docs/review/guard.sh` 的 G8 — 每个 `PushMessage` 构造点必须显式给锁屏版（`lockScreenContent` / `lockScreenTitle`）；漏传即回落中性文案（fail-closed），**绝不允许回落完整正文** | 锁屏脱敏只覆盖「收盘小结」→ 早中尾盘/买点/操作确认/行情异动/批次止损/学习复习全回落完整正文（P0，已修）|
| G9 | guard.sh 的 G9 — 默认值是相对路径（`../`）的 `@Value` 属性，必须在 `application.yml` 显式声明同名的 `adai.*` 键（否则 `.env` 里名字相近的环境变量静默不生效，落回相对默认值再按 WorkingDirectory 解析到错位置）| `adai.market.adj-path` 从未声明 → 生产「除权因子读取失败」、前复权静默失效 16 天（P2-交易49，已修）|
| G10 | guard.sh 的 G10 — 「从分享文本里择链接」的正则**双端逐字同口径**（Dart `_httpLinkRe` ↔ Swift `linkPattern`）；也可单跑 `sh apps/adai-app/scripts/check_link_pattern.sh` | Swift 侧漏全角 `）`（U+FF09）→ 带全角括号的分享文本择出垃圾 URL（`…BV1xx411c7mD）讲得不错`），而同一内容在 App 内正常 = 「App 能整理、分享进来却说找不到链接」（对抗审查 P1-3，2026-09-14 已修）|

> ⚠️ G8/G9 的执行器（`guard.sh`）在 2026-09-14 就已加上，但当时**没有回到本清单登记**——同一批行为，清单滞后了。本次随 G10 一并补齐。

---
**追加方式**：发现新的 P0 级风险模式 → 在对应分组下追加一行，注明日期。
