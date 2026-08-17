---
title: 前端代码审查检查清单
description: frontend-reviewer 逐条检查项（人也能用）——DTO 契约/生命周期/状态管理/测试
version: 1
created: 2026-08-15
updated: 2026-08-17
status: active
lines: 95
depends-on: []
related: [../roles/frontend-reviewer.md]
tags: [review, checklist, frontend]
---

# 前端审核检查点清单

> 格式：`[检查方法]` — 检查什么。`上次发现` 记录历史命中。新发现模式追加到底部。守护项（G#）见 `guard.md`，不重复。

## DTO 契约

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| F1 | `lib/services/api_service.dart` 及各页面 fromJson 期望键 ↔ 后端 DTO 序列化（`@JsonGetter` 计算字段）| Position 计算字段 PnL 恒 0（P1，已修）|
| F2 | API 调用 URL 参数是否编码（queryParameters 而非手拼）| search query 未 URL 编码（P3，已修）|

## 生命周期与状态

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| F3 | `initState` 异步加载 + 回调 `setState` 前 `mounted` 守卫 | `_loadFeed`/`_loadMore` 漏守卫（P1，已修）|
| F4 | 每页操作触发全页 Spinner 还是局部刷新（体验）| 任务/交易页闪整页 Spinner（P3，已修）|

## 整洁与硬编码

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| F5 | 死代码（空方法/未用变量/冗余缓存方法）| `invalidateFeedCache()` 空方法（P3，已删）|
| F6 | 硬编码：日期（如"7月"）、颜色、间距、文案常量 | 时间线弹窗硬编码"7月"（P3，已修）|
| F7 | 主题残留（light 主题死代码）、字体资源声明 vs 文件存在 | 缺 NotoColorEmoji.ttf 测试失败（P1，已修）|
| F8 | 日期比较（"昨天/今天"）用完整日期而非月日拼接 | Memory 页"昨天"跨月出错（P3，已修）|

## 逻辑一致性

| # | 检查方法 | 上次发现 |
|:-:|:---------|:---------|
| F9 | AI 回复 JSON 解码逻辑是否收敛到 `utils/text_cleaner.dart`（不散落多份）| main_page/feed_card 重复实现（P3，已修）|
| F10 | 异步 await 后使用共享可变单例（`_activeCardId` 等）必须重新判空——await 前后的 `!` 解引用是竞态崩溃来源 | adai-web `_appendToActiveCard` `_activeCardId!` 崩溃（P0，待修）|
| F11 | 保活页（IndexedStack/lazy）initState-only 加载需检查刷新路径——数据可变页面保活即陈旧 | adai-web Timeline/Memory 保活陈旧无刷新（战略，待修）|
| F12 | 列表变更操作（delete/done/markDone）应同步清空相关全局引用（`_activeCardId`）与 API 缓存 Map | adai-web 删 active 卡残留 + markMemoryDone 不清 `_memoryCache`（P1，待修）|
| F13 | UI 可达性推演：布局差异会让移动端不可达的路径在桌面内联布局下变可达（如内联卡删除），需重新评估 | adai-web 删除 active 卡路径新暴露（P1，待修）|
| F14 | 值复制跨工程时枚举/常量字段（Memory kind、priority、task status）必须对照后端真实常量，不凭 UI 语义自造 | adai-web/adai-admin kind 失真（P1 #133，待修）|
| F15 | 前端枚举 ↔ 后端枚举双向映射须 round-trip 无损（正反映射不丢区分度）| admin 任务 P0/P1 都映射 high、high 只回 P0（P1 #140，待修）|
| F16 | 管理后台数据源若为「当天 Feed」需评估历史可达性（管理端应覆盖历史，Feed 契约只今天会隐性截断）| adai-admin 记录页只看今天（P3 #163，待修）|
| F17 | 交互式入口不应指向未实现功能：stub 应禁用或进入即提示，而非先模拟交互再弹「开发中」| adai-app 语音 stub 误导（P3 #164，待修）|
| F18 | 账号切换/选号类新功能必须配套 widget 测试（选号页渲染/切换重建换 ApiService/条件导出双实现等价）| 多账号前端全链路 0 测试（战略 #177）|
| F19 | `Color.withValues(alpha:)` 越界审查：alpha 必须 ∈[0,1]，搜 `alpha: [>1 的字面量]`（多为 `alpha: 100` 百分比误写）| 选号页 alpha:100 → 实际 74% 透明（P3 #196）|
| F20 | 路由 push/pop 回调幂等保护：`onSelect`/`onConfirm` 先 pop 再异步 setState 需防快速双击重复 pop/push | 双击切换账号可叠两层选号页（P2 #185）|
| F21 | 条件导出双实现新增方法若无调用方，两侧都成死代码 | `clearUserId()` 双实现无调用（P3 #197）|
| F22 | 回调闭包幂等守卫必须覆盖全部副作用：onSelect/onConfirm 内若同时调 async 函数与 `nav.pop()/push()`，守卫须包住闭包整体，不能只包异步函数本身 | 双击切换账号守卫只包 `_selectAccount`、漏了闭包 `nav.pop()` → 第二次 pop 弹掉 home（P1 #204，2026-08-12）|
| F23 | 给既有安全路径（`_updateCard`/`indexWhere`）升级为 `firstWhere` 时必须确认调用时机下列表恒含目标 id，否则同步抛 StateError 且无 try 兜底 | `_appendToActiveCard` firstWhere 无缺省 → 卡不在列表同步崩（P1 #205，2026-08-12）|
| F24 | 置 `mode=waiting` 但不伴随异步任务的入口必须有复位路径：close/取消分支须无条件复位 mode | 图片卡提问后不输入直接关闭 → 永久卡 waiting 态（P2 #219，2026-08-12）|
| F25 | 双端（adai-app/adai-web）新交互逐项对拍：不止文案，行为分支（_deactivateOtherCards/loading 态/图可见性）必须一致 | 图片追问 adai-app 缺 _deactivateOtherCards（P2 #220，2026-08-12）|
| F26 | 分页「是否还有更多」判定必须与后端 totalToday 核心计数口径一致：用已加载 record/card 数比较，附加条目（action/market/push）不得参与终止判定 | Feed 分页附加条目通胀 → 加载更早消失、最旧核心不可达（战略 #234，2026-08-12）|
| F27 | error/占位卡重试路径必须与原始创建路径同构：图片卡重试必须重走 uploadImage 并携带原始字节，禁止回退文本 _createNewCard 降级 | 上传失败占位卡重试降级为文本记录（P1 #235，2026-08-12）|
| F28 | `_client` vs 全局 `http.*` 注入收敛后 grep `http\.(get|post|put|patch|delete)` 全文件确认零残留，否则 MockClient 注入在未收敛方法静默失效 | adai-web updateIdentity/updateTask 仍 http.put（P2 #243，2026-08-12）|
| F29 | 列表整体重建路径（`_loadFeed`/`_refreshFeed`/`_loadMore` 覆盖替换 `_cards`）必须校验 `_activeCardId` 仍在新列表中、不在则静默退出对话态——活动卡被挤出后残留引用是 build `activeCard!` 空值崩溃源；build 侧另需 null 兜底（双保险）| 对话态发媒体 `_loadFeed` 挤出活动卡 → `_buildActiveLayout(activeCard!)` 崩溃（P0-1，2026-08-14）|
| F30 | 动态过滤列表（按异步结果增删项）不得用裸位置索引驱动选中态/已访问集——`_items` 从 6 变 8 且中部插入时，`_current`/`_visited`/`selectedIndex` 必须按稳定标识（label/key）重解析，只加越界守卫不够 | adai-web 插件加载后 `_current` 索引漂移 → 当前页静默跳模块（P1-5，2026-08-15）|
| F31 | 门控/元数据类请求（如 `/me/plugins`）不得与内容数据请求合并进同一致命 `Future.wait`——门控失败应保守降级（默认基础服务）且不拖垮身份/标签/Feed 等无关展示 | launcher `getMyPlugins` 并入 Future.wait → 插件接口失败整页降级空数据（P1-6，2026-08-15）|
| F32 | 插件/模块显隐 widget 测试必须覆盖「失败降级」与「单一插件」两个分支（只测 `[]`/全量/单 project 不够）| launcher 门控测试缺 `['trading']` 与 500 降级（P3，2026-08-15）|

---
**追加方式**：新发现前端问题 → 追加一行，注明日期。
| F33 | 全量替换 PATCH 的配置 toggle 并发必须串行化或走服务端合并语义——「重取最新列表」不等于修复（两次请求从同一快照出发仍互覆）；验证：双开关连点 + 延迟 store 的 widget 测试 | admin 插件 toggle 竞态「P2-6 清零」误报（P2-R1，2026-08-15）|
| F34 | 门控/元数据类请求（/me/plugins）双端失败降级行为必须对拍，且降级后必须有恢复路径（重试/刷新），否则入口重启前不可达 | web 有 SnackBar+重试、app 静默无重试（S-R1，2026-08-15）|
| F35 | 异步失败 SnackBar 的重试入口需防队列堆积（show 前 `clearSnackBars()`）且成功后主动收起（`hideCurrentSnackBar()`） | 插件重试 SnackBar 堆积/残留（P3-R1/R2，2026-08-15）|
| F36 | 稳定标识（label）驱动动态列表时：IndexedStack/ListView children 需按 label 加 `ValueKey`（槽位移保活 state，防页面状态重置），tap 回调传标识而非位置索引（build↔tap 间列表变更窗口） | 插件插入后 TaskPage state 重置 + tap 索引亚帧窗口（P3-R5/R6，2026-08-15）|

---
**追加方式**：新发现前端问题 → 追加一行，注明日期。

| F37 | 图片上传失败占位卡重试必须重走 uploadImage 并携带原始字节（grep 双端 mediaBytes 字段）| web 图片重试降级文本（走查 P1-W1，2026-08-15）|
| F38 | 半失败重试幂等语义双端对拍：app 生成新 cardId 即 HIT | app 重试重复入账（走查 P1-W2，2026-08-15）|
| F39 | 日期导航异步加载须带目标标识失效守卫 | 记忆页连点乱序（走查 P2，2026-08-15）|
| F40 | `_queue = _queue.then(...)` 串行队列必须 catchError 恢复 | admin 队列单点故障（走查 P2，2026-08-15）|
| F41 | 保活 IndexedStack children 按稳定标识 ValueKey + tap 传 label（F36 闭环）+ 保活测试 | F36 未闭环（走查 P3，2026-08-15）|
| F42 | 网络缩略图（Image.network）与本地预览同策略：cacheWidth 降采样 | Feed 缩略图全分辨率解码（走查 P2，2026-08-15）|
| F43 | 失败重试 SnackBar 双端逐项对拍（show 前 clear + 成功后 hide）| F35 半闭环（走查 P3，2026-08-15）|
| F44 | 上传成功卡 content/summary 同源策略双端对拍 | web caption 丢失（走查 P2，2026-08-15）|
| F45 | 动态分支的字符串判等必须与实际值对拍：label（中文显示名）与内部标识（plugin/id）混用会产出永不命中的死分支；此类分支需壳层 widget 测试覆盖 | 切入自动刷新死代码 `entry.label=='trading'`（交易 A-E 审查 P1，2026-08-17）|
| F46 | 并行 Future.wait 必须区分「致命」与「可降级」请求：K 线类重计算端点不得阻塞主数据；任一端点失败不得丢弃已展示数据（静默刷新失败应保留旧数据+人话提示）| 六请求合并 Future.wait 整页丢数据 + buy-points 阻塞首屏（交易 A-E 审查 P1/P2，2026-08-17）|
| F47 | 按 symbol 关联的两张列表必须确认键唯一性：一对多（同 symbol 多笔）时按顺序索引或复合键匹配，禁止 .where(...).first 复用 | 清仓打分同代码多笔错挂（交易 A-E 审查 P1，2026-08-17）|
| F48 | 全量刷新函数入口（首个 setState 前）必须有 mounted 守卫；所有 await _loadAll() 调用点在 await 前置守卫，不能只守 await 之后 | _loadAll 入口无守卫 + 导入回调 await 后直接调用（交易 A-E 审查 P2，2026-08-17）|
| F49 | 页面颜色基准（红涨绿亏）必须全局一致：非盈亏语义列（评分/信号）不得借用盈亏色阶，空值占位符颜色不得由业务色推导 | 打分列绿=高分 vs 全局绿=亏 + '—' 渲染橙色（交易 A-E 审查 P2，2026-08-17）|
| F50 | 关键词归类统计须防单字误配（否定/无关词）且计数口径（标注数 vs 模式命中数）需与展示文案一致 | D2 行为模式单字 contains 误配 + 重叠计数（交易 A-E 审查 P2，2026-08-17）|
| F51 | 用户可见文案禁用无解释的内部规则编号（R81 等）与超卖描述（「行情实时」需与真实刷新机制一致）| 纪律遵守率实为胜率 + 行情实时夸大（交易 A-E 审查 P2/P3，2026-08-17）|
| F52 | 新增端点必须配套：DTO 全量 fromJson 测试（含缺字段默认值）、mock 工厂去重、关键交互分支测试（账户卡本金 sub、转账 dialog、快捷导入失败 toast、sold-score 迟到渲染、切入自动刷新）| 交易新增 DTO 仅 BuyPointDto 有测试（交易 A-E 审查 P3，2026-08-17）|
