# 前端 UI 精确对照

> 与 Claude 对话时使用的精确 UI 元素 → 代码引用。
> 每个按钮、输入框、交互元素都能在本文件找到对应的 widget 和行号。
>
> 配套文档：[frontend-reference.md](../../docs/architecture/frontend-reference.md) | [DESIGN.md](DESIGN.md)

**最后更新：2026-09-17**（RFC 20260917：撤 project 插件，项目仪表盘/任务看板 → 待办清单）

---

## 总览：页面与文件

| 页面 | 文件路径 | 类型 |
|:-----|:---------|:-----|
| 主页面（World A） | `lib/main_page.dart` | StatefulWidget |
| 启动器（World B） | `lib/pages/launcher_page.dart` | StatefulWidget |
| 待办 | `lib/pages/todo_page.dart` | StatefulWidget |
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
│  ✅  待办                           ›   │
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
| 待办 | ✅ | `TodoPage` | `todo_page.dart` |
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

## 4. 待办页（`todo_page.dart`）

> RFC 20260917：原「项目仪表盘 / 任务管理」两页随 project 插件撤销；
> 待办归 Kernel builtin，形态改为纯清单——两态 `OPEN` / `DONE`，可选到期日。

### 顶栏

| 元素 | 类型 | 行为 |
|:-----|:-----|:------|
| 返回箭头 | `IconButton(arrow_back)` | `Navigator.pop` |
| 标题 | `Text('待办')` | — |

### 顶部添加行

| 元素 | 类型 | 行为 |
|:-----|:-----|:------|
| 输入框 | `TextField`（key `todo-input`） | 一句话；回车等同「加上」 |
| 到期日 | 44pt 热区 + `Icons.event_outlined`（key `todo-due-picker`） | `showDatePicker` 选到期日（可选） |
| 不设了 | `GestureDetector`（key `todo-due-clear`） | 清掉已选到期日 |
| 加上 | `ElevatedButton`（key `todo-add`） | `createTodo`；提交中禁用 |

### 清单

| 区块 | 说明 |
|:-----|:------|
| 未完成（在上） | 勾选圆圈（`todo-check-{id}`，44pt）→ `updateTodo` 翻转状态；标题；到期日；删除（`todo-delete-{id}`，44pt，确认后 `deleteTodo`） |
| 已完成（折叠） | 标题「已完成 (n)」（key `todo-done-toggle`，默认收起）；条目加删除线 |
| 空态 | 「还没有待办。想到什么就写下来，我替你记着。」 |
| 失败态 | 人话 + 「重试」（key `todo-retry`）；写操作失败走 SnackBar 透出后端 `{"error":"人话"}`，不假装成功 |

### 到期日人话（`todoDueLabel`）

| 情况 | 文案 | 颜色 |
|:-----|:-----|:-----|
| 今天 / 明天 | 「今天」/「明天」 | `darkGrey5` |
| 已过期 | 「M月d日 · 已过期」 | `darkOrange` |
| 其它 | 「M月d日」（跨年带年份） | `darkGrey5` |

---

## 6. 交易管理（`trading_page.dart`）

> 2026-08-22 更新：页面精简为「账户卡 + 复盘横幅 + 记录区（双通道）+ 持仓卡」；自选股·买点信号、清仓复盘两个只读区块已移除（管理归 web）。止损/买点改为隐藏式（非必填）。

### 顶栏

| 元素 | 行为 |
|:-----|:------|
| 返回箭头 | `Navigator.pop` |
| 复盘 | 生成今日复盘（`_showReview`，转圈禁用防双击） |
| 刷新 | 重新加载（`_loadAll` 整页 loading） |

### 页面结构（自上而下）

| 区块 | 说明 |
|:-----|:------|
| 账户总览卡 | 总资产/总盈亏（券商口径优先，失败退回组合快照）；可用/可取/市值/当日盈亏/本金小字指标行 |
| 复盘横幅 | `has-activity` 检测有交易时出现；生成后变「今日复盘已生成 ✓」 |
| 记录区 · 通道 A | NL 输入条 → `POST /trades/parse` → 确认卡回显（可改数量/价格/方向）→ 确认记录 |
| 记录区 · 通道 B | 「精确填写」折叠表单（标的/价格/数量 + 隐藏式止损/买点 + 底部买入红/卖出绿双按钮） |
| 持仓区 | 资产卡列表（盈亏大字 + 止损显示）；点卡弹「阿呆说」建议弹层；空态引导记录/去 web 导入 |

### 记录交易表单（通道 B，2026-08-22）

| 字段 | 控件 | 说明 |
|:-----|:------|:------|
| 标的（代码或名称） | `TextField` | 6 位代码或 2-6 字名称 |
| 价格 | `TextField` | 带小数点键盘 |
| 数量 | `TextField` | 整数键盘 |
| 止损/买点（可选） | 展开链接 `_showPlan` | 隐藏式：默认收起；展开后止损自动带默认 −7%（价格×0.93），可改可清；买点下拉可空选（B1/B2/B3/SB1/暴力特噗/深水炸弹/单针/其他）；仅 BUY 生效 |
| 买入/卖出 | 底部双按钮 | 买入=红、卖出=绿；方向由按钮承担，不可能漏选 |

### 持仓卡

| 字段 | 说明 |
|:-----|:------|
| 名称+代码 | 左 |
| 盈亏金额 | 大字，盈=红/亏=绿 |
| 数量·成本·现价·止损 | 小字；未设止损橙色提示 |
| 盈亏% | 右 |

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

### 单行布局（2026-09-16 更新）

| 位置 | 元素 | 状态 | 行为 | 代码位置 |
|:-----|:------|:------|:------|:---------|
| **整栏** | `Column`：图片附件预览 + 40px 输入行 | — | 无麦克风、无生活快捷条（2026-09-16 下线；语音为 v2 方向） | `build` 第 435-497 行 |
| **左** | `TextField`（`_buildText`） | 始终可见 | 输入文字；`onSubmitted` → `_send()` | 第 383-434 行 |
| **右** | ↑ arrow_upward / ⊕ add_rounded | `_hasPending \|\| _hasText` 切换 | 有图/有字→发送；否则→⊕ 附件菜单 | 第 455-491 行 |
| **上方** | 内联图片缩略图（横向，每张可移除） | `_pendingImages` 非空时 | 移除单张 / 继续追加（上限 3） | `_buildImagePreview` 第 261-341 行 |

### 文字模式 `_buildText`

| 属性 | 值 |
|:-----|:----|
| 高度 | 40px |
| 圆角 | 14px |
| 背景色 | `darkSurface2` |
| 边框（激活态） | 绿边框 `darkGreen` 0.5px |
| placeholder（普通） | 每日轮换中文提示（`_placeholders`） |
| placeholder（激活态） | `ask your question...` 绿字 |
| 提交 | `onSubmitted` → `_send()` |

### 附件菜单 `_showAttach`

BottomSheet 底部弹出：

| 选项 | 图标 | 行为 |
|:-----|:-----|:-----|
| 拍照 | photo_camera_outlined | `_pickCamera`（image_picker camera） |
| 图片 | image_outlined | `_pickImage`（相册，多选） |
| 文件 | description_outlined | 占位提示「功能开发中」 |
| 链接 | link_outlined | 占位提示「功能开发中」 |

> 2026-09-16：「生活快捷条」（心情/运动/饮食/睡眠 四个一点即开的模板按钮）与配套弹窗
> `lib/pages/life_quick_entry.dart` 一并删除——用户拍板不留这一排模板。

---

## 12. 全局手势

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

## 13. 导航路径

```
RootApp
  └─ DualWorldShell
       ├─ [快速上滑] → World B: LauncherPage
       │    ├─ 👤 关于我    → ProfilePage
       │    ├─ 🧠 脑瓜子    → MemoryPage
       │    ├─ 📅 时间      → TimelinePage
       │    ├─ ✅ 待办      → TodoPage
       │    ├─ 📈 交易      → TradingPage
       │    └─ [搜索栏]     → SearchPage
       │
       └─ [快速下滑 or 默认] → World A: MainPage
            ├─ TopBar → LauncherPage
            ├─ Feed → 卡片交互
            └─ InputBar → 输入
```

---

## 14. 设计 Tokens

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
