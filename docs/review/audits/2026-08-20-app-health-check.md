---
title: app 全面体检走查报告 2026-08-20（用户体感导向）
description: 用户反馈三大体感（排序乱/World B 切回误触搜索/输入框上滑搜索下滑翻页）驱动的 app 全量体检——ui/ux/frontend/product 4 官并行 + 主会话独立核实交叉印证
version: 1
created: 2026-08-20
updated: 2026-08-20
status: active
lines: 135
depends-on:
  - ../../../ai-engineering/process/audit.md
related:
  - ../../review/REVIEW.md
  - ../../reference/task-log.md
tags: [review, audit, app]
---

# app 全面体检走查报告 2026-08-20

> 起因：用户反馈「排序有问题 / 两个主页来回切换，尤其 World B 切回主页很容易点到搜索 / 看看能不能输入框上滑、搜索框下滑翻页」——明确要求全面体检并出最新报告。
> 方式：ui-reviewer + ux-reviewer + frontend-reviewer + product-arch ×4 官独立并行全量走查 apps/adai-app + 主会话代码级独立核实 + 交叉印证 ⭐。
> 范围：apps/adai-app/lib/** 全部（26 文件）+ services/adai-core FeedAppService 分页契约 + 对照 adai-web 双端一致性。
> 守护：G1-G7 7 PASS / 0 HIT + META-GUARD PASS（99 文件）。
> 结果：**P0 无。战略×5 + P1×17 + P2/P3×24（合并去重后）**。审查只报告未改代码（B7）。

---

## 一、用户三大体感问题专项结论（⭐ 4 官交叉印证）

### ①「排序有问题」——部分属实：实现无 bug，但有 4 处实锤缺陷 + 1 处产品口径问题

**产品口径**（product-arch，独立核实）：Feed「最新在底部、更早在顶部」的聊天式倒序与 DESIGN.md 图例（07:00→21:01 自上而下）**完全一致**，非实现 bug；用户体感「乱」= 日记式顺读（DESIGN 默认）与信息流式「新在上」（用户预期）的认知错位。是否改为新在上属 DESIGN 修订，需用户拍板（走 RFC/讨论）。

**实锤缺陷**（ux/frontend 双官 ⭐）：

| # | 缺陷 | 证据 | 影响 |
|:-:|:-----|:-----|:-----|
| A1 | `_loadMore` 合并**无按 id 去重** | `main_page.dart:983-986` `[...moreCards, ..._cards]` | 后端实时分页（`FeedAppService.java:78`）翻页间隙新增/删除 → 重复卡/漏条 |
| A2 | 切 World 返回自动 `_refreshFeed` **重置回 page0** | `main.dart:204-207` → `main_page.dart:135` | 已加载的更早记录全消失、翻页进度清零，列表整体位移 = 「排序变来变去」主因 |
| A3 | 时间线今天无记录时**默认选中最早一天** | `timeline_page.dart:50-52` / `timeline_modal.dart:60-62`（后端倒序 `TimelineProjection.java:74`，`keys.last`=最早） | 打开时间线落在最早日期，与直觉相反 |
| A4 | 附加卡（待办/行情/推送）固定压底 + `updatedAt` 默认 now | `feed_card.dart:79` + `main_page.dart:1256-1262`（toFeedData 未传时间） | 「刚刚」恒显、几小时前卡像刚发生；09:00 待办夹在 14:00 与 14:05 之间（后端 page0 附加块整拼 `FeedAppService.java:174-176`，页内非全局时间序）|

另：删除/标记完成后 `_totalToday` 不回落（`main_page.dart:966,786-788`）→ 已加载全量后「加载更早」卡死；双端 Feed 方向相反（app 聊天式 vs web 流式 `feed_page.dart:130,837`）跨端感知不一致。

### ②「World B 切回主页误触搜索」——属实，根因是手势语义冲突（4 官 ⭐⭐⭐⭐）

搜索栏是「下滑返回」手势区内的 tap 大目标，误触是**结构必然**非操作失误：

1. **手势竞技场**：`launcher_page.dart:170-183` 顶部 opaque GestureDetector（velocity>300 才返回）包住 `:442-451` 搜索栏（40px 全宽 onTap→SearchPage）——慢滑/轻点（位移<touchSlop）tap 赢 → 误开搜索页（且 `search_page.dart:33` initState 自动 requestFocus 弹键盘）；
2. **双阈值死区**：速度 300–400 时 launcher 不返回、shell 也差 100 → 无任何反馈；三处阈值不一致（TopBar 200 `main_page.dart:1005` / launcher 300 / shell 400 `main.dart:228-231`）；
3. **返回语义被吞**：返回 World A 的唯一显式控件是搜索条内左缘 18px 灰箭头（`:459-465`），其余 80% 面积 = 开搜索；
4. **过渡期可点**：AnimatedSwitcher 250ms（`main.dart:239-269`）新旧页叠放，过渡中点击命中进场搜索栏；键盘动画窗口 `viewInsets` 滞后使 140px 排除失效（`main.dart:224-225`）。

### ③「输入框上滑 / 搜索框下滑翻页」——输入框上滑可行且推荐；搜索下滑语义冲突暂不建议（2 官 ⭐）

- **输入框上滑**：现状 InputBar 无上滑手势（#16 后移除，`main_page.dart:1045-1046` 注释）。可行且推荐：子 GestureDetector 赢竞技场后收窄误触面（shell 不再接输入栏拖拽），与 RefreshIndicator/反走 ListView 无直接冲突；需把键盘态/140px 排除内聚到输入栏自身，并实测单行 TextField 垂直拖拽不抢占（文本选中/光标拖拽）。
- **搜索框下滑翻页**：同区域同方向「下滑=返回」已双绑定（launcher 300 + shell 400），再绑「翻页」必产生意图歧义；launcher 列表是普通 ListView 无分页语义，「翻页」目标未定义。**先修①④ 与误触根因，再评估该交互**。
- **前置条件（两官一致）**：实现前必须先统一「单区域单下滑语义」——现下滑方向有壳层 400 / TopBar 200 / Launcher 300 / RefreshIndicator 四种语义叠加。

## 二、交叉印证表（多官命中 = 高优先）

| 问题 | 命中官 | 状态 |
|:-----|:-------|:----:|
| World B 误触搜索（竞技场/双阈值/返回被吞/过渡期）| ux + ui + frontend + product ⭐⭐⭐⭐ | 未修 |
| 切 World 丢输入现场（草稿/对话态/上传进度）| ux U14 + product（P-app-15 升 P1）⭐⭐ | 未修 |
| 首载 Feed 失败伪装空态（无重试）| ux U25 + frontend + ui P1-1 ⭐⭐⭐ | 未修（web 已修，app 漏）|
| 排序：`_loadMore` 无 id 去重 | ux + frontend ⭐⭐ | 未修 |
| 排序：切回重置 page0 丢已加载页 | ux + frontend + 主会话 ⭐⭐⭐ | 未修 |
| 排序：时间线默认最早日期（keys.last）| ux + frontend ⭐⭐ | 未修 |
| 排序：`updatedAt=now`「刚刚」恒显 | ux + frontend + ui ⭐⭐⭐ | 未修 |
| 排序：双端 Feed 方向相反 | ux 战略 + product 口径 ⭐⭐ | 产品口径待拍板 |
| 错误文案退化为状态码/英文裸奔 | ui P1-8 + ux U26 ⭐⭐ | 未修（main_page 已修，两端漏）|
| trading 30 分钟刷新失败整页错误态 | ux U31 + frontend（P1-前端1 仍存在）⭐⭐ | REVIEW 已有 |
| P2-UI1~5（买点绿/伪0/恒绿/溢出/小字号）| ui 逐条引用确认 + REVIEW | REVIEW 已有未修 |
| 任务「编辑」实为新建（P-app-08）| product P1-1（独立核实）| app-polish 首批 P0 未修 |
| launcher 行排序（切换账号置顶等）| product P2-2 + 主会话 | 未修 |

## 三、按角色发现清单（合并去重后，每条 ≤3 行）

### 战略（5）

| # | 问题 | 位置 |
|:-:|:-----|:-----|
| S-1 | **双主页形态违背 DESIGN「一个页面」原则**：World B 是与 Feed 对等的全屏主页（独立搜索条/返回箭头/列表 + AnimatedSwitcher 整页对换），用户认知即「两个主页来回切换」；建议降级为覆盖层（BottomSheet/抽屉，同 Timeline 处理）或补齐返回一致性/系统返回键/草稿保活 | `main.dart:171-273` |
| S-2 | **第一原则泄漏**：阿呆系统页向普通用户展示 Kernel 组件/RFC/API 端点/Git（`project_status_page.dart` 全页 + launcher 副标题「Kernel · Domain · 数据」`launcher_page.dart:224`）——建议迁 admin/web 或降级为「项目概览」 | `project_status_page.dart` |
| S-3 | **双端 Feed 阅读方向相反**（app 聊天式最新在底 vs web 流式最新在顶）——需用户拍板方向并记录到 frontend-reference | `main_page.dart:1092-1096` vs `feed_page.dart:130,837` |
| S-4 | **下滑手势四语义叠加**（壳层 400/TopBar 200/Launcher 300/RefreshIndicator）——制定「单区域单下滑语义」原则，阈值统一单一常量 | `main.dart:218-238` |
| S-5 | **roadmap 蓝图漂移**：选号已实现仍标「v1.0.1 顺延」；双主页/launcher/搜索形态 roadmap 无条目（P25 复发）| `product-roadmap.md` vs `main.dart:13-19,55-167` |

### P1（17，去重后选录 12）

| # | 问题 | 位置 | 建议 |
|:-:|:-----|:-----|:-----|
| P1-1 | **误触搜索（根因组）**：搜索栏在返回手势区内是 tap 大目标 + 双阈值死区 + 18px 返回箭头 + 过渡期可点 | `launcher_page.dart:170-183,442-465` / `main.dart:239-269` | 下滑语义只挂拖拽条；搜索栏 tap 加位移判定或延迟消歧；返回热区扩 1/4 条；过渡期 IgnorePointer |
| P1-2 | **切 World 丢输入现场**：MainPage dispose → 草稿/对话/上传进度丢失（注释自认）| `main.dart:239-268` / `main_page.dart:1045-1047` | 草稿提升壳层或 IndexedStack 保活；至少切换前拦截提示 |
| P1-3 | **排序 A2：切回重置 page0** | `main.dart:204-207` → `main_page.dart:135` | 已有数据时保留旧列表 + 增量合并/角标提示新增 |
| P1-4 | **排序 A1：`_loadMore` 无 id 去重** | `main_page.dart:983-986` | 合并时按 id 去重（Set seen） |
| P1-5 | **排序 A4：`updatedAt=now`「刚刚」恒显** | `feed_card.dart:79` / `main_page.dart:1256-1262` | 透传后端 createdAt/updatedAt |
| P1-6 | **首载 Feed 失败伪装空态**（无重试）| `main_page.dart:169-173,1133` | 加 `_feedError` 分支，失败显错误态+重试（对齐 trading `_buildError`）|
| P1-7 | **P1-前端1 仍存在**：trading 30 分钟刷新失败整页错误态丢持仓 | `trading_page.dart:112-115,427-429` | 已有数据保留旧值 + 非阻塞提示 |
| P1-8 | **任务「编辑」实为新建**（P-app-08，app-polish 首批 P0 至今未修）| `project_task_page.dart:485-493` | 记 `_editingTaskId`，保存走 PUT |
| P1-9 | **时间线默认最早日期**（排序 A3）| `timeline_page.dart:50-52` / `timeline_modal.dart:60-62` | 改 `keys.first`（最近一天），today 优先保留 |
| P1-10 | **launcher 计数伪 0**：核心数据失败后「已存 0 条/已记 0 条/0个」冒充真实值 | `launcher_page.dart:88-96,204,213,259` | 失败显「—」+ 中性色（对齐 V9-10）|
| P1-11 | **ProjectStatus 三态残缺**：原始异常裸奔 + 无重试 + loading 无 spinner | `project_status_page.dart:42,66,65` | 统一错误三件套 + spinner |
| P1-12 | **错误文案退化**：trading/task 只回状态码，task 兜底英文 `network error` | `trading_page.dart:1000-1010` / `project_task_page.dart:154-164` | 复用 main_page `_extractApiError` 提取 json error |

### P2/P3（24，选录 10）

| # | 问题 | 位置 |
|:-:|:-----|:-----|
| P2-1 | 附加卡（action/market/push）整块拼 page0 尾，页内非全局时间序 + 昨日待办混入今日 | `FeedAppService.java:174-176,133` |
| P2-2 | 删除/完成后 `_totalToday` 不回落 → 「加载更早」卡死 | `main_page.dart:966,786-788` |
| P2-3 | launcher 行排序：切换账号（低频高风险）置顶、插件行显隐位置漂移 | `launcher_page.dart:191-249` |
| P2-4 | 标签图谱外圈气泡文字 4.5-7.8px 不可读 + 28px 触达 <44pt | `launcher_page.dart:337,345,380` |
| P2-5 | 触达目标 <44pt 成片（拖拽条 24px/关闭钮 28px/删除钮 16px/日历格 32×36/18px 箭头）| `launcher_page.dart:430` / `main_page.dart:1339` / `input_bar.dart:277` / `timeline_page.dart:263` |
| P2-6 | 超小字号 9-10px 扩散 27+ 处（REVIEW P2-UI5 仍存在 + 扩散 9 文件）| `feed_card.dart` ×5 / `trading_page.dart` ×6 / `main_page.dart:1195,1208,1242,1270,1334,1492` 等 |
| P2-7 | `0xFF2A2826` 代码块背景 4 处 2 份重复硬编码未走 token；`Colors.orange`/`white38` 漏网 | `feed_card.dart:816` / `main_page.dart:1481,1483` / `memory_page.dart:293` |
| P2-8 | 搜索页/时间线连点无代际令牌，旧响应覆盖新 query/月份 | `search_page.dart:44-63` / `timeline_page.dart:141-157` |
| P2-9 | task 页 tasks+stats `Future.wait` 任一失败整页错误（trading 已分离、task 未改）| `project_task_page.dart:56-73` |
| P2-10 | 切 World 键盘动画窗口 `viewInsets` 滞后 → 140px 排除失效复发窗口（#16）| `main.dart:224-225` |

## 四、新增检查点建议（供清单追加）

1. **C-app-双主页**：launcher/World B 形态与 DESIGN「一个页面」对拍（覆盖层 vs 对等主页；返回语义独立于搜索）。
2. **C-app-切换保活**：世界切换不丢输入现场（草稿/对话/上传进度），切换前有保护。
3. **C-app-系统视角**：app 任何页面不得出现系统结构标签（Kernel/RFC/API 端点/Domain OS）。
4. **C-app-手势**：单区域单下滑语义；切世界/返回/刷新按区域分档，阈值单一常量。
5. **C-app-排序**：Feed 分页按 id 去重 + 删除同步计数 + 时间线默认最近一天 + 相对时间透传。
6. **C-roadmap-app**：app 交互形态（双主页/launcher/搜索/选号）在 roadmap 有条目且状态对拍。

## 五、待用户拍板项

1. **Feed 方向**：日记式（最新在底，现状，符合 DESIGN）还是信息流式（最新在顶）？——决定是否走 DESIGN 修订。
2. **双主页形态**：降级为覆盖层（BottomSheet/抽屉）还是保留对等主页补齐保活/返回？
3. **P2-UI1 买点绿**：徽章 darkGreen vs 红涨绿亏冲突（RFC 20260817 定「买点绿」）——随本批一并拍板。
4. **输入框上滑/搜索下滑**：若采纳，先按「单区域单下滑语义」重构手势分层再实现（输入框上滑推荐，搜索下滑暂缓）。
