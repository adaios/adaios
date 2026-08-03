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

---
**追加方式**：新发现前端问题 → 追加一行，注明日期。
