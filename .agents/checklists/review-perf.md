---
title: 前端加载慢专项审查清单
description: app/web/admin 加载慢时的轻量快查——按加载链路分阶段逐条核对，15 分钟出结果（AI 触发 /perf，人也能用）
version: 1
created: 2026-08-22
updated: 2026-08-22
status: active
lines: 71
depends-on: []
related:
  - ../roles/frontend-reviewer.md
  - review-frontend.md
tags: [review, checklist, frontend, perf]
---

# 前端加载慢专项审查清单（perf）

> 用途：三端（adai-app / adai-web / adai-admin）「加载慢 / 转圈久」时的**轻量快查**，不是 8 官全量走查。
> **时间盒 15 分钟**：按阶段从上到下查，超时即停，只报最影响体感的 ≤10 条。
> **输出格式**：每条 ≤3 行（位置 / 一句问题 / 一句建议），按阶段标注「卡首屏 / 卡内容 / 卡交互」；疑似后端慢 → 一句转派 backend-reviewer，不深挖。
> **触发**：`/perf <端名>`（AI）；人用 = 逐条核对下表。

## 阶段 A 启动 → 首帧

| # | 检查方法 | 卡 | 上次发现 |
|:-:|:---------|:--|:---------|
| A1 | 主包体积：三端 `grep "deferred as" lib` —— 无懒加载则插件/大页全打主包，首帧 JS 膨胀 | 首屏 | 三端均无 deferred import（2026-08-22 实测）|
| A2 | 字体资产：`web/fonts/`、`assets/` 字体体积（adai-web 须用 NotoSansSC-Subset.woff2 63KB TrueType 轮廓，勿回退 CFF 致中文全框）| 首屏 | 已修：1.8MB Hiragino CFF → 63KB Noto TrueType（2026-08-22）|
| A3 | 保活页构建时机：IndexedStack children 是否全量实例化（lazy 只建已访问页）→ 非 lazy 则启动即建全部页面 | 首屏 | web 已 lazy（desktop_shell.dart:144）；app 单页 + Navigator.push 无保活栈；admin 两处 IndexedStack 待对拍 |
| A4 | 保活页数据拉取：offstage 页 initState-only 是否全拉、后台定时刷新与 offstage 页是否并发竞争 | 首屏/交互 | 通过：web 记忆/时间线已补刷新入口（#103）；交易页 30min Timer 有可见性检查（P3-11）|

## 阶段 B 首屏请求编排

| # | 检查方法 | 卡 | 上次发现 |
|:-:|:---------|:--|:---------|
| B1 | 门控/元数据请求（/me/plugins 等）是否与内容合并进同一致命 `Future.wait` | 首屏 | 已修：app 拆出 unawaited(_loadPlugins()) 独立失败面（launcher_page.dart:73，P1-6/F31）|
| B2 | 重计算/慢端点是否留在主数据链路上（buy-points K 线重算、sold-score 全量打分）| 首屏 | trading_page.dart:85 六请求合并 + buy-points 阻塞首屏（P1-交易7 / P2-交易9，待修）|
| B3 | 缓存端点是否消费：后端 /brief/cached 等缓存端点，前端是否命中而非每次重拉 | 首屏 | 通过：app/web 均消费 getBriefCached（api_service.dart:61，5 分钟缓存）|
| B4 | 首屏请求数/串行链：启动到首帧发几个请求、能否并行、有无重复/超大请求 | 首屏 | app LauncherPage 5 并行（identity/tags/timeline/memoryCount/plugins）；`getTimeline(limit: 999)` 全量拉且只用 length（launcher_page.dart:78 + timeline_page.dart:33）→ 建议收紧 limit 或加 count 端点（2026-08-22）|

## 阶段 C 内容渲染

| # | 检查方法 | 卡 | 上次发现 |
|:-:|:---------|:--|:---------|
| C1 | Image.network 降采样：grep 全部 `Image.network`，无 cacheWidth 即全分辨率解码 | 内容 | 通过：app input_bar/feed_card + web desktop_feed_card/feed_page 均带 cacheWidth（F42 已修）|
| C2 | 列表更新策略：整体重建（覆盖替换 _cards）vs 增量；大列表重建是否 setState 全页 | 内容 | Feed 三入口覆盖替换（F29）|
| C3 | 分页/终止口径：附加条目（action/market/push）是否通胀导致加载更早消失 | 内容 | Feed 分页附加条目通胀（F26，战略 #234）|

## 阶段 D 交互响应

| # | 检查方法 | 卡 | 上次发现 |
|:-:|:---------|:--|:---------|
| D1 | 主线程阻塞：同步大 JSON 解析、构建期重计算（清仓打分渲染、千分位格式化）是否阻塞交互 | 交互 | 账户总览 8 卡同行大数值溢出（P2-交易14）|
| D2 | 失败整页错误态：静默刷新失败是否整页替换丢已展示数据（应保留旧数据 + 人话提示）| 交互 | app _loadData catch 整页错误态（P1-前端1，待修）；web 同型已修（feed_page.dart:109 错误态+重试）|

## 阶段 E 网络层

| # | 检查方法 | 卡 | 上次发现 |
|:-:|:---------|:--|:---------|
| E1 | 超时/重试：http 客户端超时值、连接复用 vs 每次新建 | 首屏 | 通过：三端 _TimeoutClient 15s / AI 90s，客户端单例注入复用 |
| E2 | 鉴权头注入：Image.network 显式传头 vs 每图重建客户端 | 内容 | 媒体请求鉴权头已实现（api_service.dart:701）|

## 阶段 F 转派

| # | 检查方法 |
|:-:|:---------|
| F1 | 接口响应本身慢（快照计算、K 线扫描未缓存）→ 一句转派 backend-reviewer，专项不深挖 |

---

**追加方式**：新发现加载慢问题 → 在对应阶段追加一行（位置 / 现象 / 日期）。与 F 系列不重复登记（perf 只收加载链路问题）。
