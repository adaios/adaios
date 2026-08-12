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

---
**追加方式**：新发现前端问题 → 追加一行，注明日期。
