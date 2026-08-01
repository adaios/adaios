# 前端 UI 精确对照

> 与 Claude 对话时使用的精确 UI 元素 → 代码引用。
> 每个按钮、输入框、交互元素都能在本文件找到对应的 widget 和行号。
>
> 配套文档：[frontend-reference.md](../../docs/architecture/frontend-reference.md) | [DESIGN.md](DESIGN.md)

**最后更新：2026-07-26**

---

## 总览：页面与文件

| 页面 | 文件路径 | 类型 |
|:-----|:---------|:-----|
| 主页面（World A） | `lib/main_page.dart` | StatefulWidget |
| 启动器（World B） | `lib/pages/launcher_page.dart` | StatefulWidget |
| 项目仪表盘 | `lib/pages/project_status_page.dart` | StatefulWidget |
| 任务管理 | `lib/pages/project_task_page.dart` | StatefulWidget |
| 交易管理 | `lib/pages/trading_page.dart` | StatefulWidget |
| 个人档案 | `lib/pages/profile_page.dart` | StatefulWidget |
| 记忆浏览 | `lib/pages/memory_page.dart` | StatefulWidget |
| 时间线 | `lib/pages/timeline_page.dart` | StatefulWidget |
| 搜索 | `lib/pages/search_page.dart` | StatefulWidget |
| App 壳 | `lib/main.dart` | StatefulWidget (DualWorldShell) |

### Widget 级组件

| 组件 | 文件路径 | 说明 |
|:-----|:---------|:------|
| InputBar | `lib/widgets/input_bar.dart` | 底部输入栏 |
| FeedCard | `lib/widgets/feed_card.dart` | 统一卡片容器 |
| TimelineModal | `lib/widgets/timeline_modal.dart` | 时间线 BottomSheet |
| LifeQuickEntry | `lib/pages/life_quick_entry.dart` | 生活记录 BottomSheet |

---

## 1. 主页面 World A（`main_page.dart`）

```
┌──────────────────────────────────────────┐
│ 7/26·周日                            ▼  │  ← TopBar：日期（左）+ 右箭头（右）
├──────────────────────────────────────────┤
│  ┌────────────────────────────────────┐  │
│  │ Today · 7月26日                     │  │  ← Brief 简报卡片
│  │ 早上好。今天下午有交易机会…          │  │
│  │ • 京东方A 回踩支撑位                │  │
│  │ • 关注半导体板块                    │  │
│  └────────────────────────────────────┘  │
│                                          │
│  ┌────────────────────────────────────┐  │
│  │ 09:30  log                         │  │  ← FeedCard（记录态）
│  │ 今天跑步了 30 分钟                   │  │     半透明背景，灰底 log 标签
│  │ [ 运动 ] [ 健康 ]                  │  │
│  │ ────────────── ask ─────────────── │  │     底部 ── ask ──
│  └────────────────────────────────────┘  │
│                                          │
│  ┌────────────────────────────────────┐  │
│  │ 10:30  ask                         │  │  ← FeedCard（对话结束态）
│  │ ✓ 聊了凤凰单丛和品茶心得            │  │     绿色边框，绿底 ask 标签
│  │ [ 茶 ] [ 生活 ]                    │  │
│  │ ────────────── ask ─────────────── │  │     底部 ── ask ──（绿字）
│  └────────────────────────────────────┘  │
│                                          │
│           [ 展开更早记录 → ]              │  ← 加载更多
│                                          │
├──────────────────────────────────────────┤
│ [🎤] ┌──────────────────────────┐  [⊕]  │  ← InputBar（空输入态）
│      │ 记一笔…                    │      │     左 mic / 中输入框 / 右 ⊕
│      └──────────────────────────┘       │
└──────────────────────────────────────────┘
```

### 顶栏 `_TopBar`（第 564 行）

| 元素 | 类型 | 行为 | 代码位置 |
|:-----|:-----|:------|:---------|
| 日期文字 | `Text` | 显示日期，"今天"高亮 | `_TopBar.build` |
| 右箭头图标 | `IconButton` | 点按：显示 Launcher（World B），切换 `_showWorldB = true`（实际触发在 `DualWorldShell` 的 `onPullUp`） | 第 578 行 |

### Feed 列表（第 26-38 行）

| 元素 | 说明 | 代码位置 |
|:-----|:------|:---------|
| 简报卡片 | Brief 内容，Markdown 渲染 | `_buildBriefCard` |
| FeedCard 列表 | 由 `_cards` 渲染，每 5 条一页 | `_cards` 列表 |
| "展开更早"按钮 | 点按：`_totalShown += _pageSize`，查看更多 | 列表底部 |

### 底部输入栏（见 InputBar 章节）

---

## 2. 启动器 World B — Launcher（`launcher_page.dart`）

```
┌──────────────────────────────────────────┐
│ ═══════════════════════════════════════    │  ← 拖拽条：下滑 → 回到 World A
│                                          │
│ ┌─ 🔍 ────────────────────────────  ← ┐ │  ← 搜索栏：点按 → SearchPage
│ │  搜索记录、标签…            返回    │ │
│ └──────────────────────────────────────┘ │
│                                          │
│  👤  关于我                         ›   │  ← 导航列表
│  🧠  脑瓜子正在装…                  ›   │
│  📅  时间都去哪了                   ›   │
│  📊  阿呆系统                       ›   │
│  📈  交易                           ›   │
│                                          │
│ ────── 标签宇宙 ──────     [列表/图谱]   │  ← 视图切换按钮
│                                          │
│        [生活]                            │
│          ╲  ╱                           │  ← 图谱模式：标签气泡+连线
│       [交易]──[茶]                       │
│          ╱  ╲                           │
│       [运动]  [代码]                     │
│        ⋮                                │
│                                          │
└──────────────────────────────────────────┘
```

### 顶部固定区

| 元素 | 类型 | 行为 | 代码位置 |
|:-----|:-----|:------|:---------|
| 拖拽条 | `Container` + 手势 | `onVerticalDragEnd` 速度 > 300→返回 World A | 第 119-132 行 |
| 搜索栏 | 伪 `TextField`（`GestureDetector`） | 点按：跳转 `SearchPage` | 第 371-406 行 |
| 返回箭头 | `Icon(arrow_back)` | 点按：`onNavigateBack` → 回到 World A | 第 388-394 行 |

### 导航列表

每项格式：emoji + 标题 + 预览文字 + `chevron_right` 箭头

| 条目 | emoji | 目标页面 | 代码位置 |
|:-----|:------|:---------|:---------|
| 关于我 | 👤 | `ProfilePage` | 第 140-147 行 |
| 脑瓜子正在装… | 🧠 | `MemoryPage` | 第 149-155 行 |
| 时间都去哪了 | 📅 | `TimelinePage` | 第 158-164 行 |
| 阿呆系统 | 📊 | `ProjectStatusPage` | 第 167-171 行 |
| 交易 | 📈 | `TradingPage` | 第 173-177 行 |

### 标签宇宙

| 元素 | 类型 | 行为 | 代码位置 |
|:-----|:-----|:------|:---------|
| 视图切换按钮 | `GestureDetector` | 点按：`_toggleView()` ↔ 图谱/列表 | 第 190-203 行 |
| 图谱视图 | `Stack` + `CustomPaint` | 标签气泡连线图，最多 15 个标签 | 第 218-285 行 |
| 标签气泡 | `GestureDetector` + 圆形 | 点按：跳转 `SearchPage(initialQuery: tagName)` | 第 297-313 行 |
| 列表视图 | `Wrap` | 标签大小随权重变化，最多 20 个标签 | 第 318-353 行 |
| 列表标签 | `GestureDetector` | 点按：跳转 `SearchPage(initialQuery: tagName)` | 第 338-349 行 |

---

## 3. App 壳 `DualWorldShell`（`main.dart`）

| 手势 | 操作 | 代码位置 |
|:-----|:------|:---------|
| 快速上滑（速度 > 400） | 从 World A → World B（Launcher） | 第 58-62 行 |
| 快速下滑（速度 > 400） | 从 World B → World A（MainPage） | 第 62-64 行 |
| 切换动画 | `AnimatedSwitcher 250ms` | 第 67-69 行 |

---

## 4. 项目仪表盘（`project_status_page.dart`）

### 顶栏

| 元素 | 类型 | 行为 | 代码位置 |
|:-----|:-----|:------|:---------|
| 返回箭头 | `IconButton(arrow_back)` | `Navigator.pop` | 第 53-56 行 |
| 标题 | `Text('阿呆系统')` | — | 第 57 行 |
| 刷新 | `IconButton(refresh)` | 重新加载所有数据 | 第 59-62 行 |

### 内容区

| 区块 | 组件 | 说明 | 代码位置 |
|:-----|:------|:------|:---------|
| 系统概览 | `_sectionTitle` + `_card` | 显示 project + architecture | 第 70-75 行 |
| Kernel 组件 | `_componentGrid` | 6 个组件：绿/橙色状态标记 | 第 76-78 行 |
| Domain OS | `_domainList` | 3 个 Domain：完整/骨架/未开始 | 第 79-81 行 |
| 统计数据 | `_card` | Git 提交数、RFC 数、API 端点 | 第 82-87 行 |
| RFC 状态 | `_buildRfcSection` | 每项含日期 + title + 彩色状态标签 | 第 89 行 |
| 任务统计 | `_buildTaskSection` | 4 个数字：待办/进行/完成/合计 | 第 91 行 |
| "管理"按钮 | `GestureDetector` | 导航至 `ProjectTaskPage` | 第 207-225 行 |

### RFC 状态颜色

| status | 颜色 | 中文标签 | 代码位置 |
|:-------|:-----|:---------|:---------|
| `implemented` | `darkGreen` | 完成 | `_rfcStatusColor` |
| `approved` | `darkBlue` | 已批准 | 同上 |
| `proposed` | `darkOrange` | 提案 | 同上 |
| `deprecated` | `darkGrey5` | 废弃 | 同上 |

---

## 5. 任务管理（`project_task_page.dart`）

### 顶栏

| 元素 | 类型 | 行为 | 代码位置 |
|:-----|:-----|:------|:---------|
| 返回箭头 | `IconButton(arrow_back)` | `Navigator.pop` | 第 144-146 行 |
| 标题 | `Text('任务')` | — | 第 148 行 |
| 刷新 | `IconButton(refresh)` | `_loadAll()` | 第 150-152 行 |
| 添加按钮 | `IconButton(add_rounded)` | 切换 `_showCreate`，显示/隐藏创建表单 | 第 154-156 行 |

### 统计行 `_buildStatsRow`

| 元素 | 说明 |
|:-----|:------|
| 全部 | `_stats.total` |
| 待办 | `_stats.todo` |
| 进行 | `_stats.doing` |
| 完成 | `_stats.done` |

### 筛选栏 `_buildFilterRow`

横向滚动，圆角矩形按钮：

| 按钮 | 筛选值 | 颜色 |
|:-----|:-------|:-----|
| 全部 | `null` | `darkGrey5` |
| 待办 | `TODO` | `darkOrange` |
| 进行 | `DOING` | `darkBlue` |
| 完成 | `DONE` | `darkGreen` |
| 取消 | `CANCELLED` | `darkGrey5` |

### 创建表单 `_buildCreateForm`

| 字段 | 控件 | 说明 | 代码位置 |
|:-----|:------|:------|:---------|
| 标题 | `TextField` | 必填 | 第 260 行 |
| 描述（可选） | `TextField` maxLines=2 | 可选 | 第 262 行 |
| 标签（逗号分隔） | `TextField` | 可选 | 第 264 行 |
| 优先级 | 4 个 `_prioChip` | P0/P1/P2(默认)/P3，椭圆选择 | 第 269-276 行 |
| 创建任务按钮 | `ElevatedButton` | 提交，标题为空时禁用 | 第 278-290 行 |

### 任务卡片 `_buildTaskCard`

每张卡片包含：

| 元素 | 说明 | 代码位置 |
|:-----|:------|:---------|
| 状态标签 | 圆角矩形色块（TODO橙/DOING蓝/DONE绿/CANCELLED灰） | 第 373-381 行 |
| 优先级标签 | 仅非 P2 时显示，橙色 | 第 383-391 行 |
| 标题 | `Text` 14px w600 | 第 409 行 |
| 描述 | `Text` 12px，最多 2 行溢出省略 | 第 410-413 行 |
| 标签列表 | `Wrap` 灰底小圆块 | 第 414-424 行 |
| 更多菜单 | `PopupMenuButton(more_horiz)` | 第 393-406 行 |

### 更多菜单项

| 菜单项 | 行为 | 条件 |
|:-------|:------|:------|
| 推进 → 进行/完成 | `_updateStatus(id, nextStatus)` | 仅当 nextStatus 非 null（TODO→DOING, DOING→DONE） |
| 编辑 | `_editTask(task)` | 填充表单字段到创建区 |
| 删除 | `_deleteTask(id)` | 弹出确认框 |

### 删除确认弹窗

| 元素 | 文本 | 行为 |
|:-----|:------|:------|
| 标题 | '删除任务' | — |
| 内容 | '确定删除？' | — |
| 取消按钮 | TextButton '取消' | `Navigator.pop(false)` |
| 删除按钮 | TextButton '删除' | `Navigator.pop(true)` → `deleteTask` |

---

## 6. 交易管理（`trading_page.dart`）

### 顶栏

| 元素 | 行为 |
|:-----|:------|
| 返回箭头 | `Navigator.pop` |
| 刷新 | 重新加载持仓 + 组合快照 |
| 记录交易按钮 | 切换 `_showForm` |

### 持仓列表

| 字段 | 说明 |
|:-----|:------|
| 代码 | `symbol` |
| 名称 | `name` |
| 持仓量 | `volume` |
| 成本价 | `avgCost` |
| 最新价 | `latestPrice` |
| 盈亏 | `pnl` / `pnlPercent` |

### 组合概览

| 字段 | 说明 |
|:-----|:------|
| 总市值 | `totalMarketValue` |
| 总盈亏 | `totalPnl` |
| 总盈亏% | `totalPnlPercent` |
| 持仓数 | `positionCount` |

### 记录交易表单

| 字段 | 控件 |
|:-----|:------|
| 代码 | `TextField` |
| 名称 | `TextField` |
| 方向 | BUY / SELL 选择 |
| 价格 | `TextField` |
| 数量 | `TextField` |
| 提交按钮 | `ElevatedButton` |

---

## 7. 个人档案（`profile_page.dart`）

### 展示模式

| 元素 | 行为 | 代码位置 |
|:-----|:------|:---------|
| 返回箭头 | `Navigator.pop` | 第 194-198 行 |
| 称号显示 | 从 `_profile.name` 读取 | 第 212 行 |
| 沟通风格 | 从 preferences 读取 | 第 213 行 |
| 关注领域 | 从 preferences 读取 | 第 214 行 |
| 规则开关 | 图标 ✓ 或 ○ | 第 221-223 行 |
| 关注标签 | `_chip` 列表 | 第 234-243 行 |
| 编辑个人档案按钮 | 绿色圆角按钮 + edit 图标 | 第 247-269 行 |

### 编辑模式

| 字段 | 控件 | 代码位置 |
|:-----|:------|:---------|
| 称呼 * | `TextField` | 第 367 行 |
| 沟通风格 | `TextField` | 第 370 行 |
| 专注领域 | `TextField` maxLines=2 | 第 373 行 |
| 交易确认规则 | `SwitchListTile` | 第 381-383 行 |
| 自动处理规则 | `SwitchListTile` | 第 384-386 行 |
| 添加标签 | `TextField` + 添加按钮(add) | 第 403-441 行 |
| 取消按钮 | `GestureDetector` | 第 447-462 行 |
| 保存按钮 | `GestureDetector`（带 loading 态） | 第 464-502 行 |

---

## 8. 记忆浏览（`memory_page.dart`）

### 顶栏

| 元素 | 行为 | 代码位置 |
|:-----|:------|:---------|
| 返回箭头 | `Navigator.pop` | 第 110-116 行 |
| 脑瓜子图标 | `Icon(psychology_outlined)` | 第 117 行 |
| 活动标签 | 绿色 `#_activeTag`（有筛选时显示） | 第 120-129 行 |
| 左箭头 | `chevron_left`，`_prevDay()` | 第 132-135 行 |
| 日期文字 | `_dateDisplay`，today/yesterday/M/d | 第 137 行 |
| 右箭头 | `chevron_right`，`_nextDay()`（今天之后禁用） | 第 139-143 行 |

### 标签筛选栏 `_buildTagBar`

横向滚动标签列表，最多 8 个 + "all"：

| 元素 | 行为 |
|:-----|:------|
| all 标签 | 清除 `_activeTag` |
| 标签（带计数） | 点按：筛选该标签的记忆 |

### 记忆卡片

| 元素 | 说明 |
|:-----|:------|
| 摘要文字 | `_profile.summary` |
| 标签列表 | 点按标签可筛选 |
| 情感图标 | positive/negative/neutral |
| 时间 | HH:mm |
| 日期 | today/yesterday/M/d |

---

## 9. 时间线（`timeline_page.dart`）

### 顶栏

| 元素 | 行为 |
|:-----|:------|
| 返回箭头 | `Navigator.pop` |
| 上个月按钮 | `chevron_left`，切换月份 |
| 年月文字 | `YYYY 年 M 月` |
| 下个月按钮 | `chevron_right`，切换月份 |
| 今日按钮 | 回到当月 |

### 日历网格

| 元素 | 说明 |
|:-----|:------|
| 星期标签 | 一 ~ 日 |
| 日期格子 | 有记录的日期右侧有绿点 |
| 选中日期 | 白底黑字 |
| 点按日期 | 显示当日记录列表 |

### 记录列表

| 元素 | 说明 |
|:-----|:------|
| 时间 | HH:mm |
| 标题 | 记录标题 |
| 标签 | 圆角标签 |

---

## 10. 搜索（`search_page.dart`）

### 顶栏

| 元素 | 行为 | 代码位置 |
|:-----|:------|:---------|
| 返回箭头 | `Navigator.pop` | 第 137-143 行 |
| 搜索输入框 | `TextField`，`onSubmitted=_search` | 第 144-165 行 |
| 搜索按钮 | `arrow_forward` 图标 | 第 168-178 行 |

### 结果列表

| 状态 | 显示 |
|:-----|:------|
| 未搜索 | "输入关键词搜索记录" |
| 加载中 | `CircularProgressIndicator` |
| 无结果 | "未找到相关记录" |
| 有结果 | "共 N 条结果" + `ListView` |

### 结果卡片

| 元素 | 说明 |
|:-----|:------|
| 标题 | `title` 14px w500 |
| 内容 | 关键词绿色高亮（`_buildHighlightedText`） |
| 标签 | 灰底圆角 |
| 时间 | HH:mm |

---

## 11. 输入栏 InputBar（`lib/widgets/input_bar.dart`）

### 三列布局

| 位置 | 元素 | 状态 | 行为 | 代码位置 |
|:-----|:------|:------|:------|:---------|
| **左按钮** | 🎤 mic_outlined / ⌨ keyboard_outlined | `_isVoice` 切换 | 点按：切换语音/文字模式 | 第 157-172 行 |
| **左二** | 🌿 emoji | 始终可见 | 点按：弹出 LifeQuickEntry BottomSheet | 第 174-181 行 |
| **中间** | `TextField` / 语音条 | 视模式而定 | 见下方 | 第 184-189 行 |
| **右按钮** | ↑ arrow_upward / ⊕ add_rounded | `_hasText` 切换 | 有文字→发送 / 无文字→⊕ 附件菜单 | 第 192-209 行 |

### 文字模式 `_buildText`

| 属性 | 值 |
|:-----|:----|
| 高度 | 40px |
| 圆角 | 14px |
| 背景色 | `darkSurface2` |
| 边框（激活态） | 绿边框 `darkGreen` 0.5px |
| placeholder（普通） | 每日轮换英文提示 |
| placeholder（激活态） | `ask your question...` 绿字 |
| 提交 | `onSubmitted` → `_send()` |

### 语音模式 `_buildVoice`

| 状态 | 显示 |
|:-----|:------|
| 空闲 | `hold to talk` 灰字 + mic_none |
| 录音 | `release to send` 绿字 + mic 绿 + 绿边框 |

### 附件菜单 `_showAttach`

BottomSheet 底部弹出，四个选项（目前都为 `Navigator.pop` 占位）：

| 选项 | 图标 |
|:-----|:------|
| image | image_outlined |
| voice | mic_outlined |
| file | description_outlined |
| link | link_outlined |

---

## 12. 生活快速记录 LifeQuickEntry（`lib/pages/life_quick_entry.dart`）

### 类型选择

| 类型 | emoji | 标签 |
|:-----|:-------|:------|
| 心情 | 😊 | `mood` |
| 运动 | 🏃 | `sport` |
| 饮食 | 🍜 | `diet` |
| 睡眠 | 😴 | `sleep` |

选中时：绿底圆角边框；未选中：`darkSurface2`。

### 表单

| 元素 | 说明 |
|:-----|:------|
| 输入框 | 3 行 maxLines，`TextField` |
| 使用模板按钮 | 点按：填入对应类型的前缀文字（如"今天心情"） |
| 取消按钮 | `Navigator.pop` |
| 记录按钮 | 有内容时绿色，无内容时灰色禁用，点按：`onSend(text)` + pop |

---

## 13. 全局手势

| 手势 | 页面 | 行为 |
|:-----|:------|:------|
| 快速上滑 > 400 | World A | 切换到 World B（Launcher） |
| 快速下滑 > 400 | World B | 切换到 World A（MainPage） |
| 下拉 > 300 | Launcher | 回到 World A（通过 `onNavigateBack`） |
| 无激活卡片时输入 | MainPage → InputBar | `POST /api/v1/records` intent=auto |
| 有激活卡片时输入 | MainPage → InputBar | `POST /api/v1/records` cardId=active |
| 点 ask | FeedCard idle 态 | 进入 waiting → 激活输入 |
| 点 end | FeedCard chatting 态 | `POST /api/v1/conversations/end` → ended 态 |

---

## 14. 导航路径

```
RootApp
  └─ DualWorldShell
       ├─ [快速上滑] → World B: LauncherPage
       │    ├─ 👤 关于我    → ProfilePage
       │    ├─ 🧠 脑瓜子    → MemoryPage
       │    ├─ 📅 时间      → TimelinePage
       │    ├─ 📊 阿呆系统  → ProjectStatusPage
       │    │    └─ 📋 管理 → ProjectTaskPage
       │    ├─ 📈 交易      → TradingPage
       │    └─ [搜索栏]     → SearchPage
       │
       └─ [快速下滑 or 默认] → World A: MainPage
            ├─ TopBar → LauncherPage
            ├─ Feed → 卡片交互
            └─ InputBar → 输入
```

---

## 15. 设计 Tokens

所有颜色、圆角、尺寸在 `lib/theme/app_colors.dart` 中定义。关键值：

| Token | 色值 | 用途 |
|:-------|:-----|:------|
| `darkBg` | #0E0E0E | 页面背景 |
| `darkSurface` | #1A1A1A | 卡片底色 |
| `darkSurface2` | #232326 | 输入栏、按钮 |
| `darkBorder` | #2C2C2E | 分割线 |
| `darkGrey1` | #F0EDE9 | 正文 |
| `darkGrey4` | #908B85 | 次要 |
| `darkGrey5` | #66615C | 三级 |
| `darkGrey6` | #45423E | placeholder |
| `darkGreen` | #2BC457 | AI/激活 |
| `darkOrange` | #E8963A | 待办 |
| `darkBlue` | #5299FF | 进行中 |
