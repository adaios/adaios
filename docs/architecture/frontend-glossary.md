# 前端 UI 中英术语对照

> AdaiOS Flutter 前端。用于代码和设计讨论时的统一术语。
>
> 视觉参考：[layout-reference.md](layout-reference.md) | 设计原则：`apps/adai-app/DESIGN.md`

---

## 页面结构

| 中文 | 代码/设计名 | 说明 |
|:----|:-----------|:-----|
| 主页面 | `MainPage` / `main_page.dart` | 唯一页面，无 Tab 无多级 |
| 顶栏 | `TopBar` / `_TopBar` | 日期（左）+ Timeline 入口（右） |
| Feed 流 | Feed / Today Feed | 统一卡片列表，从简报开始到一天所有记录 |
| 输入栏 | `InputBar` / `input_bar.dart` | 底部三列：语音/文字切换 + 输入框 + 发送/⊕ |
| 时间线弹窗 | `TimelineModal` / `timeline_modal.dart` | Timeline BottomSheet，75% 高度 |

---

## FeedCard 卡片

| 中文 | 代码/设计名 | 说明 |
|:----|:-----------|:-----|
| 卡片 | `FeedCard` / `feed_card.dart` | Feed 流中的统一卡片容器 |
| 卡片数据 | `FeedCardData` | 卡片的状态和数据模型 |
| 卡片类型 | `FeedCardType` | `record`(用户记录) / `aiNote`(AI 分析) / `push`(资讯推送) |
| 对话轮次 | `ConversationTurn` | 单条对话（`isUser` 区分用户还是 AI） |
| 激活态卡片 | active card | 左侧 3px 绿色竖线，底部无边 |
| 绿色竖线 | left accent | 激活卡片左侧绿色标识条 |

### FeedCard 状态机

| 中文 | `CardMode` | 显示样式 | 底部操作 |
|:----|:----------|:---------|:---------|
| 空闲态 | `idle` | 普通卡片，半透明背景 | `── ask ──` |
| 等待态 | `waiting` | 激活 + 输入框绿色边框 | `end` |
| 对话态 | `chatting` | 激活 + 对话记录 | `end` |
| 结束态 | `ended` | 绿色边框 + 总结摘要 | `── ask ──`（绿字） |

### 卡片显示风格

| 中文 | 代码 getter | 判定逻辑 |
|:----|:-----------|:---------|
| 记录风格 | `_isLogStyle` | `intent == log`，或后端返回无 turns 的记录 |
| 提问风格 | `_isAskStyle` | `intent == question`，或后端有 turns 的记录 |
| 激活态 | `_isActive` | `waiting` 或 `chatting` |
| 结束态 | `_isEnded` | `mode == ended` |

### 卡片头部标签

| 中文 | 显示 | 关联风格 |
|:----|:-----|:---------|
| log 标签 | 灰底灰字 `log` | `_isLogStyle` |
| ask 标签 | 绿底绿字 `ask` | `_isAskStyle` |
| 时间 | 灰色 `14:30` | 所有卡片 |
| 对话圆点 | 绿色小圆点 | `_isChatting` |

---

## 意图（后端分流）

| 中文 | `IntentType` | 后端值 | 行为 |
|:----|:------------|:-------|:-----|
| 记录 | `log` | `'log'` | 存档到 Feed，无需 AI 对话 |
| 提问 | `question` | `'question'` | 触发 AI 对话，进入 chatting 态 |

---

## 输入栏

| 中文 | 代码 | 说明 |
|:----|:----|:-----|
| 语音模式 | `_isVoice` | 输入栏切换为"按住说话" |
| 文字模式 | `!_isVoice` | 键盘输入 |
| 有输入态 | `_hasText` | 输入框非空，右按钮变为 ↑ 发送 |
| 空输入态 | `!_hasText` | 右按钮为 ⊕ 附件菜单 |
| 激活卡片态 | `hasActiveChat` | 输入框显示 `ask your question...` 绿色边框 |
| 录音中 | `_recording` | 长按语音按钮时 |

---

## 颜色系统

| 中文 | 代码 token | 色值 | 用途 |
|:----|:----------|:-----|:------|
| 背景色 | `darkBg` | `#0E0E0E` | 页面背景 |
| 表面色 | `darkSurface` | `#1A1A1A` | 卡片底色 |
| 表面色2 | `darkSurface2` | `#232326` | 输入栏、按钮 |
| 边框色 | `darkBorder` | `#2C2C2E` | 分割线 |
| 灰1（最高） | `darkGrey1` | `#F0EDE9` | 正文文字 |
| 灰4（次要） | `darkGrey4` | `#908B85` | 标签、时间 |
| 灰5（三级） | `darkGrey5` | `#66615C` | 占位符、log 标签 |
| 灰6 | `darkGrey6` | `#45423E` | placeholder |
| 绿色 | `darkGreen` | `#2BC457` | AI、激活、ask 标签 |
| 橙色 | `darkOrange` | `#E8963A` | 预留 |
| 蓝色 | `darkBlue` | `#5299FF` | 资讯推送（仅此用途） |

---

## 聊天气泡

| 中文 | 代码 | 说明 |
|:----|:----|:-----|
| 用户气泡 | `isUser == true` | 绿色半透明底，右上圆角 4 |
| AI 气泡 | `isUser == false` | 深灰底，左上圆角 4 |
| 气泡内边距 | 14h × 10v | px |
| 气泡最大宽 | `maxWidth: 320` | px |

---

## 布局尺寸

| 中文 | 值 | 代码位置 |
|:----|:---|:---------|
| 卡片圆角 | 16px | `Radius.circular(16)` |
| 卡片左右 margin | 20px | `EdgeInsets.symmetric(horizontal: 20)` |
| 卡片垂直间距 | 6px（×2=12px 呼吸感） | `vertical: 6` |
| 绿色竖线宽度 | 3px | `width: 3` |
| 输入栏高度 | 40px | `height: 40`（整栏 56px 含 padding） |
| 输入框圆角 | 14px | `Radius.circular(14)` |
| 按钮尺寸 | 40×40 | `width: 40, height: 40` |
| 时间线弹窗高度 | 75% | `MediaQuery.of(context).size.height * 0.75` |
| 入场动画时长 | 600ms | `Duration(milliseconds: 600)` |
| 滚动动画时长 | 300ms | `Duration(milliseconds: 300)` |

---

## API 接口

| 中文 | 方法 | 路径 | DTO |
|:----|:-----|:-----|:----|
| 提交记录 | POST | `/api/v1/records` | `RecordResponse` |
| 结束对话 | POST | `/api/v1/conversations/end` | `EndConversationResponse` |
| 获取 Feed | GET | `/api/v1/feed` | `FeedResponse` |
| 获取时间线 | GET | `/api/v1/timeline` | `List<TimelineEntryResponse>` |

---

## Git 和文件相关

| 中文 | 值 |
|:----|:----|
| 文件位置 | `apps/adai-app/` |
| 项目名 | `adai_app` |
| Flutter SDK | 3.44.6 |
| Dart SDK | 3.12.2 |
| 后端地址 | `http://localhost:8080`（`--dart-define=API_BASE_URL=...` 覆盖） |
