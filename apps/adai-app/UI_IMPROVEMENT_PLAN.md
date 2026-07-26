# AdaiApp UI 改进方案

> 2026-07-27 · 基于全面 UI Review 产出的四优先级改进计划。
> 评价结论：6.7/10，有个性但不够精致，有理念但执行参差。

---

## 总览

| 优先级 | 方案 | 类型 | 文件数 | 预估改动 | 风险 |
|:------|:-----|:----|:------:|:--------:|:----:|
| 🔴 P0 | 双列表一致性 + 错误处理 | 工程 | 1 | ~30 行 | 低 |
| 🟠 P1 | Brief 降级 + 空状态 | 产品 | 3 | ~80 行 | 低 |
| 🟡 P2 | 色温校准 + 对话框改造 | 设计 | 3 | ~120 行 | 中 |
| 🟢 P3 | 入场动画 + hover + 锚点感 | 增值 | 4 | ~150 行 | 低-中 |

**涉及文件**（按改动量排序）：

| 文件 | P0 | P1 | P2 | P3 |
|:----|:--:|:--:|:--:|:--:|
| `main_page.dart` | ✅ | ✅ | ✅ | ✅ |
| `feed_card.dart` | | | ✅ | ✅ |
| `app_colors.dart` | | | ✅ | |
| `input_bar.dart` | | ✅ | | |
| `BriefAppService.java`（后端） | | ✅ | | |
| `widgets/hoverable.dart`（新建） | | | | ✅ |

---

## 🔴 P0：双列表一致性 + 错误处理

### 问题

**① 删除后复活** — `_deleteCard` 只从 `_cards` 移除，不从 `_allCards` 移除。缓存失效后 `_loadFeed` 从 `_allCards` 重建，已删卡片复活。

**② Date separator 排序异常** — `_loadOlder` 插入 separator 时 `updatedAt = DateTime.now()`，而 `_updateCard` 按 `updatedAt` 排序，新 separator 跑到列表末尾。

**③ 五个 catch 块沉默吞错** — `_loadFeed` / `_loadOlder` / `_closeChat` / `_deleteCard` / `_createNewCard` 的 catch 要么只 setState 不提示，要么空块。

### 方案

#### ① `_deleteCard` 同步清理 `_allCards`

```dart
// main_page.dart:231
void _deleteCard(String id) async {
  try {
    await _api.deleteRecord(id);
    if (!mounted) return;
    setState(() {
      _cards.removeWhere((c) => c.id == id);
      _allCards.removeWhere((c) => c.id == id);  // ← 新增
    });
  } catch (e) {
    if (mounted) _showError('删除失败');
  }
}
```

#### ② 排序基准改为 `id`

`FeedCardData` 的 `id` 格式为 `card_{timestamp}`，天然有序。`_updateCard` 不再改排序：

```dart
// main_page.dart:223-229
void _updateCard(String id, FeedCardData Function(FeedCardData) updater) {
  final idx = _cards.indexWhere((c) => c.id == id);
  if (idx >= 0) {
    _cards[idx] = updater(_cards[idx]);
  }
  // 删除 _cards.sort(...) —— id 顺序就是正确的插入顺序
}
```

date separator 的 `id` 设置为 `sep_{dateStr}`，天然按日期排序。

#### ③ 错误处理统一

所有 catch 块按以下模式统一：

```dart
catch (e) {
  if (mounted) {
    _showError('操作描述失败: ${e.toString().length > 60 ? e.toString().substring(0, 60) : e.toString()}');
  }
}
```

`_loadOlder` 的空白 catch（第 357 行）加 `_hasOlder = false` + `_showError`。

### 验证

- 删除卡片 → 刷新页面不复活
- 加载更多 → separator 在正确位置
- 网络断开时操作 → SnackBar 提示具体错误

---

## 🟠 P1：Brief 降级 + 空状态设计

### 问题

**Brief AI 报错原文展示给用户** — `BriefAppService.java` 第 99 行返回 `"⚠️ Brief 生成失败: Connection timed out"`。用户看到 Java 异常原文。

**空 Feed 无状态** — 第一天用户看到黑屏，无引导、无提示。

### 方案

#### ① Brief 后端降级（`BriefAppService.java`）

```java
// BriefAppService.java:98-104
} catch (Exception e) {
    log.warn("Brief AI failed: {}", e.getMessage());
    // 降级文案：温暖但简短，不暴露异常
    String name = identityName;
    int hour = LocalDateTime.now().getHour();
    String greeting = hour < 12 ? "早上好" : hour < 18 ? "下午好" : "晚上好";
    cachedBrief = "☀️ " + name + " " + greeting + "！\n• 今天有什么想记录的吗？";
    cachedBriefAt = LocalDateTime.now();
    return cachedBrief;
}
```

#### ② 前端空状态（`main_page.dart`）

在 `_buildNormalLayout` 的 `visibleCards` 为空时渲染 `_buildEmptyState()`：

```dart
// main_page.dart 新增方法
Widget _buildEmptyState() {
  return Center(
    child: Padding(
      padding: const EdgeInsets.symmetric(horizontal: 40),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text('✦ ✦ ✦', style: TextStyle(fontSize: 24, color: AppColors.darkGrey6)),
          const SizedBox(height: 20),
          Text('还没有记录', style: TextStyle(fontSize: 18, color: AppColors.darkGrey4, fontWeight: FontWeight.w500)),
          const SizedBox(height: 8),
          Text('在下方输入你的第一条记录\n或语音、或文字，随你', textAlign: TextAlign.center,
            style: TextStyle(fontSize: 14, color: AppColors.darkGrey6, height: 1.6)),
          const SizedBox(height: 32),
          Row(mainAxisSize: MainAxisSize.min, children: [
            _emptyChip('📝 记录心情', () => _prefillInput('今天心情')),
            const SizedBox(width: 12),
            _emptyChip('🤔 问个问题', () => _prefillInput('')),
          ]),
        ],
      ),
    ),
  );
}

Widget _emptyChip(String label, VoidCallback onTap) {
  return GestureDetector(
    onTap: onTap,
    child: Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      decoration: BoxDecoration(
        border: Border.all(color: AppColors.darkBorder.withAlpha(150)),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(label, style: TextStyle(fontSize: 13, color: AppColors.darkGrey3)),
    ),
  );
}
```

`_prefillInput` 需要 InputBar 暴露预设文本能力：

```dart
// input_bar.dart 新增方法
void prefillText(String text) {
  _textCtrl.text = text;
  _textCtrl.selection = TextSelection.collapsed(offset: text.length);
  _focusNode.requestFocus();
}
```

在 `InputBar` widget 上通过 `GlobalKey<_InputBarState>` 暴露，或在 MainPage 用回调方式。

### 验证

- 断开网络 → Brief 显示温暖中文降级文案，非异常
- 全新用户（无记录）→ 显示空状态引导，非黑屏

---

## 🟡 P2：色温校准 + 对话框改造

### 问题

**色温偏差** — DESIGN.md 说"暖灰中性偏暖"，实际 `#0E0E0E` 背景 + `#2BC457` 荧光绿 = 冷。暖灰不暖，绿色刺眼。

**对话框割裂** — Chatting 态使用聊天气泡（左上/右上非对称圆角 + 绿色半透明底），和 FeedCard 卡片式设计语言完全不一致，用户在同一 App 内被切换了两种交互模型。

**信息密度偏低** — 同屏 3-4 条，可压缩到 4-5 条。

### 方案

#### ① 色值调整（`app_colors.dart`）

暖灰校准方向（建议值，需实机预览确认）：

| Token | 当前 | 建议 | 效果 |
|:------|:----:|:----:|:-----|
| `darkBg` | `#0E0E0E` | `#131211` | 极微暖黑 |
| `darkSurface` | `#1A1A1A` | `#1D1B1A` | 暖灰 |
| `darkSurface2` | `#232326` | `#252220` | 暖灰 |
| `darkBorder` | `#2C2C2E` | `#2D2926` | 暖棕灰 |
| `darkGrey1` | `#F0EDE9` | `#F0EDE9` | ✅ 不动（已经是暖的白） |
| **`darkGreen`** | **`#2BC457`** | **`#3AB75A`** | **降饱和度，去荧光感** |

最小可行修改：**只动 `darkGreen` 和 `darkBg`** 两个，其他保持。

#### ② 对话框改造（`feed_card.dart`）

**去掉独立气泡**，对话轮次改用 FeedCard 内部流式布局：

```
┌───────────────────────────┐
│ 14:30   ask        📈  ⋮ │  ← 标准 header
│                           │
│ 今天半导体板块怎么样？      │  ← 用户消息：w500，无背景
│                           │
│ │ 半导体板块近期有回踩     │  ← AI 消息：左侧 2px 绿竖线
│ │ 支撑位的趋势，建议关注   │     保留 Markdown 渲染
│ │ ...                    │
│                           │
│ 立昂微走势如何？           │  ← 用户消息：w500，无背景
│                           │
│ ◌ ◌ ◌                    │  ← Loading dots（保持当前）
│                           │
│ ────────── end ────────── │
└───────────────────────────┘
```

```dart
// feed_card.dart _buildTurns() 改造
Widget _buildTurns() {
  return Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      ...data.turns!.map((turn) => Padding(
        padding: const EdgeInsets.only(bottom: 6),
        child: turn.isUser
            ? Text(turn.text, style: TextStyle(
                fontSize: 15, height: 1.6,
                fontWeight: FontWeight.w500,
                color: AppColors.darkGrey1))
            : _buildAiMessage(turn.text),
      )),
      if (data.loading) ... // loading dots 保持
    ],
  );
}

Widget _buildAiMessage(String text) {
  return Row(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Container(
        width: 2,
        margin: const EdgeInsets.only(top: 4, right: 10),
        decoration: BoxDecoration(
          color: AppColors.darkGreen.withAlpha(100),
          borderRadius: BorderRadius.circular(1),
        ),
      ),
      Expanded(
        child: MarkdownBody(
          data: text,
          selectable: true,
          styleSheet: buildMarkdownStyle(),  // 保持当前
        ),
      ),
    ],
  );
}
```

**移除 `main_page.dart` 中的 `_buildChatBubble`**（第 645-678 行），对话态不再渲染气泡，FeedCard 全权负责。

#### ③ 信息密度压缩

| 元素 | 当前 | 调整后 |
|:----|:----:|:------:|
| FeedCard bottom line height | 36px | 28px |
| `_buildHeader` padding bottom（feed_card.dart:209） | 14px | 12px |
| `SizedBox(height: 9)`（feed_card.dart:214） | 9px | 6px |
| 标签 padding vertical（feed_card.dart:268） | 3px | 2px |
| 卡片 margin vertical（feed_card.dart:173） | 6px | 5px |
| `SizedBox(height: 4)`（feed_card.dart:235/238） | 4px | 3px |

### 验证

- 色值调整后截图对比，确认暖灰和绿色不再刺眼
- 对话态截图，确认内嵌流和卡片视觉语言一致
- 同屏卡片数从 3-4 提升到 4-5

---

## 🟢 P3：入场动画 + hover + "今天"锚点感

### 问题

**无逐项入场动画** — 只有 MainPage 整体有 FadeIn，新卡片直接 setState 闪现。

**桌面端无 hover 反馈** — 桌面 Web 用户鼠标悬停卡片无视觉反馈。

**"今天"锚点感弱** — TopBar 只有 `7/27·周日`（13px 灰字），不构成页面标题。

### 方案

#### ① 卡片逐项入场（`main_page.dart`）

```dart
// _buildNormalLayout() 中 visibleCards 的 map 修改
children: visibleCards.reversed.toList().asMap().entries.map((entry) {
  final idx = entry.key;
  final card = entry.value;
  return TweenAnimationBuilder<double>(
    key: ValueKey('enter_${card.id}'),
    tween: Tween(begin: 0.0, end: 1.0),
    duration: Duration(milliseconds: 400),
    curve: Curves.easeOutCubic,
    builder: (context, value, child) => Opacity(
      opacity: value,
      child: Transform.translate(
        offset: Offset(0, 20 * (1 - value)),
        child: child,
      ),
    ),
    child: FeedCard(...),
  );
})
```

**注意**：仅全新加载的卡片触发动画。`TweenAnimationBuilder` 从 0→1 自动运行，已有卡片重新 build 时 `tween` 不变不触发。用 `ValueKey` 确保 widget 复用正确。

初始加载时错开：每条延迟 `index * 50ms`（可选，先不加，如果感觉太整齐再加）。

#### ② 桌面 hover（新建 `widgets/hoverable.dart` + `feed_card.dart`）

```dart
// widgets/hoverable.dart
import 'package:flutter/material.dart';

/// 桌面端 hover 效果包装器。
/// 非桌面平台（Android/iOS）无效果，直接返回 child。
class Hoverable extends StatefulWidget {
  final Widget Function(BuildContext context, bool isHovered) builder;

  const Hoverable({super.key, required this.builder});

  @override
  State<Hoverable> createState() => _HoverableState();
}

class _HoverableState extends State<Hoverable> {
  bool _isHovered = false;

  @override
  Widget build(BuildContext context) {
    return MouseRegion(
      onEnter: (_) => setState(() => _isHovered = true),
      onExit: (_) => setState(() => _isHovered = false),
      child: widget.builder(context, _isHovered),
    );
  }
}
```

`feed_card.dart` 外层包裹 `Hoverable`：

```dart
// 在 build() 返回的 Padding 外层包一层
Hoverable(
  builder: (context, isHovered) => AnimatedContainer(
    duration: Duration(milliseconds: 200),
    transform: isHovered ? Matrix4.translationValues(0, -2, 0) : Matrix4.identity(),
    child: Padding(/* 当前 Padding 内容 */),
  ),
)
```

Hover 时效果：
- `translateY: -2px`（轻微上浮）
- 可选：border color 从 `darkBorder` → 略亮（如 alpha 从 100→180）

**平台检测**：`MouseRegion` 在触屏平台无效果，可直接在所有平台使用，非桌面端 hover 事件不触发。

#### ③ "今天"锚点感（`main_page.dart`）

**TopBar 日期改造：**

```dart
// _TopBar build()
String get _todayLabel {
  final now = DateTime.now();
  const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
  return '${now.month}月${now.day}日 ${weekdays[now.weekday - 1]}';
}

// 显示
Text(_todayLabel, style: TextStyle(
  fontSize: 20,                    // 当前 13 → 20
  fontWeight: FontWeight.w600,     // 当前 w500 → w600
  color: AppColors.darkGrey1,      // 当前 darkGrey5 → darkGrey1
  letterSpacing: -0.3,
))
```

**去掉** `keyboard_arrow_down` 图标（当前 line 705-707），日期本身就指示可点进 Timeline。

**Brief 卡片内 `today` 标签改造：**

```dart
// current:
Text('today', style: TextStyle(fontSize: 9, color: darkGrey4, letterSpacing: 1.5))
// → 改为：
Text('今天', style: TextStyle(fontSize: 11, color: darkGrey3, letterSpacing: 0.5))
```

#### ④ 底部"最新"状态条

在 `_buildLatestBar` 同级增加一条 `──── last record ${X} ────`，显示最新卡片的时间距离：

```dart
// 在 build() 中，_buildLatestBar 上方/下方
if (_cards.isNotEmpty && _scrollAtBottom) {
  final newest = _cards.last;
  final minutesAgo = DateTime.now().difference(newest.updatedAt).inMinutes;
  final label = minutesAgo < 1 ? 'just now'
      : minutesAgo < 60 ? '$minutesAgo min ago'
      : newest.time;
  _buildLastRecordBar(label);
}
```

```dart
Widget _buildLastRecordBar(String label) {
  return Container(
    padding: const EdgeInsets.symmetric(vertical: 4),
    margin: const EdgeInsets.symmetric(horizontal: 20),
    child: Row(children: [
      Expanded(child: Container(height: 1, color: AppColors.darkBorder.withAlpha(50))),
      Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8),
        child: Text(label, style: TextStyle(fontSize: 9, color: AppColors.darkGrey6)),
      ),
      Expanded(child: Container(height: 1, color: AppColors.darkBorder.withAlpha(50))),
    ]),
  );
}
```

### 验证

- 页面加载 → 卡片从下往上逐项淡入，400ms 内有序列
- 桌面 Web → 鼠标悬停卡片上浮 2px，移出恢复
- TopBar → 显示 `7月27日 周日`（20px, 亮色），点击进入 Timeline
- 底部 → 显示 `──── just now ────`

---

## 实施顺序建议

```
P0 ───── 2-3 天 ────→ 必须第一轮做
P1 ───── 1-2 天 ────→ 和 P0 并行（不依赖 P0）
P2 ───── 3-4 天 ────→ 需要设计师确认色值，对话改造可以单独实施
P3 ───── 2-3 天 ────→ 和 P2 并行（不依赖 P2）
```

P0+P1（工程 + 产品问题）优先上线。P2+P3（设计 + 增值）第二轮。

---

## 效果预览（文本模拟）

### 修改后 Feed 卡片（P2 对话改造后）

```
┌───────────────────────────────┐
│ 14:30   ask            📈  ⋮  │
│                               │
│ 今天半导体板块怎么样？         │
│                               │
│ │ 半导体板块近期有回踩         │
│ │ 支撑位的趋势，中期可以关注   │
│                               │
│ ────────── end ────────────── │
└───────────────────────────────┘

──── just now ────
```

### 空状态（P1 空状态后）

```
┌───────────────────────────────┐
│   7月27日 周日           👤   │
│                               │
│ ☀️ 阿呆 晚上好！              │
│ • 今天有什么想记录的吗？       │
│                               │
│        ✦  ✦  ✦               │
│                               │
│        还没有记录              │
│     在下方输入你的第一条记录    │
│                               │
│  [📝 记录心情]  [🤔 问个问题]  │
│                               │
│ ───────────────────────────── │
│ [🎤]┌──────────────────┐[⊕]  │
│     │ record something…│      │
│     └──────────────────┘      │
└───────────────────────────────┘
```
