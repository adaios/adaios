---
title: AdaiOS 项目发展建议
date: 2026-07-28
status: draft
role: 产品经理 / 前端工程 / UI 设计 三方视角
---

> 在 Memory 升级 Phase 0-1 落地、FeedCard 状态机多轮迭代之后，
> 从产品、前端工程、UI 设计三个角色审视当前项目状态，提出建设性方向。

---

## 一、产品经理视角 — 从"能用"到"好用"

### 1.1 当前阶段判断

AdaiOS 处于 **MVP 后期 → 产品化早期** 的过渡阶段：

| 维度 | 状态 | 证据 |
|:-----|:-----|:------|
| 核心闭环 | ✅ 通 | 输入 → AI → Feed → Memory 已跑通 |
| 功能广度 | ✅ 够 | 记录、问答、记忆、交易、项目 5 个 Domain 可用 |
| 交互细节 | ❌ 毛边多 | 状态机不一致、错误处理粗糙、loading 散乱 |
| 视觉打磨 | ❌ 未开始 | 深色主题下对比度不足、无动效体系、字体层级不清晰 |
| 测试覆盖 | ⚠️ 开始 | 23 个前端 + 100 个后端测试，但场景覆盖不全 |

### 1.2 三个关键建议

#### 建议 A：建立"交互规范"文档

当前所有交互逻辑散落在前端代码中（4 种 CardMode、5 种意图状态、3 种 loading 形式）。
**应该**统一写一份交互规范文档，定义：

- 每种状态的视觉表现（颜色、边框、间距）
- 状态间转换条件（点击、API 返回、出错）
- 错误处理策略（何时弹窗/何时静默/何时重试）

**理由**：单人开发时靠脑记，2 周后回来改就忘干净了。

#### 建议 B：明确"数据流优先级"——提问优于记录

当前架构中 STATEMENT 走 ContextEngine 完整链路（identity + memory + tagIndex），
QUESTION 反而走简化链路。但实际使用场景中：

- **提问（QUESTION）** 最需要上下文——AI 要知道你是谁、发生过什么
- **记录（STATEMENT）** 只需要基本标签和 summary

应考虑把 ContextEngine 的"重火力"分配给 QUESTION，STATEMENT 走轻量链路。

#### 建议 C：尽早确定"哪里是核心，哪里是插件"

当前 Domain OS（trading/life/project）和 Kernel 在代码层面是平级的（都在 `com.adaiadai.core` 下）。
随着 Domain OS 增加，这种"平级"会变成耦合。

建议在 Phase 3-4 前明确：
- **Kernel**（identity/record/context/memory/search）—— 核心，必须稳定
- **Domain OS**（trading/life/project）—— 插件化，可插拔
- **接口边界**：Domain OS 只通过 `ContextContributor` + `KnowledgeSource` 影响内核

---

## 二、前端工程师视角 — 状态管理 + 工程化

### 2.1 当前架构风险

| 风险 | 严重度 | 说明 |
|:-----|:------:|:------|
| `setState` 散落 | 中 | 6 处直接修改 `_cards`，状态变更不可追踪 |
| 无错误边界 | 高 | API 500 → catch 吞掉 → 用户看到空白 |
| 无类型安全的 API 层 | 中 | `jsonDecode` 后手动 cast，字段漏掉不报编译错 |
| FeedCard 逻辑膨胀 | 高 | 一个 widget 处理 4 种 mode × 3 种 intent × 2 种 loading，条件分支 20+ |
| 卡片 key 策略脆弱 | 中 | `ValueKey(card.id)` + `copyWith` 可能导致 widget 状态复用异常 |

### 2.2 三个改进方向

#### 方向 A：引入轻量状态管理

`StatefulWidget + setState` 在卡片少于 20 条时够用。
但卡片间依赖（activate / deactivate / update）已经需要 6 个方法通信。

**推荐**：考虑使用 **Riverpod** 或 **Provider** 管理卡片集合状态。
- 卡片 CRUD 通过单一 Store
- 避免 `_updateCard` 遍历 + `_deactivateOtherCards` 手动管理
- 错误边界统一处理

**不动当前架构的替代方案**：把 `_cards` 的操作封装成 `CardStore` 类，
把状态更新逻辑从 `main_page.dart` 抽出来，至少让页面只负责渲染。

#### 方向 B：API 层加类型安全

当前 `RecordResponse.fromJson` 用 `json['field'] as String?` 手动 cast，
字段名写错不报编译错。

**推荐**：用 `freezed` 或 `json_serializable` 生成 DTO。
或者至少加单元测试覆盖每个 API 响应字段（当前已做了一部分）。

#### 方向 C：错误处理标准化

当前 `_showError` 是 SnackBar，用 `_showError` 有 7 处调用但策略不统一。
建议：

```dart
// 统一错误处理策略
enum ErrorStrategy { toast, dialog, silent, retry }
class ErrorHandler {
  static void handle(dynamic error, {ErrorStrategy strategy = ErrorStrategy.toast}) {
    switch (strategy) {
      case ErrorStrategy.toast: _showToast(_extractMessage(error));
      case ErrorStrategy.dialog: _showDialog(_extractMessage(error));
      case ErrorStrategy.silent: logError(error);
      case ErrorStrategy.retry: _showRetryDialog(error);
    }
  }
}
```

### 2.3 建议立即做的

| 事项 | 工作量 | 收益 |
|:-----|:------:|:----:|
| API 错误弹窗显示具体信息 | 小 | 高——用户不再看到"network error" |
| CardStore 抽取 | 中 | 中——降低 main_page.dart 复杂度 |
| FeedCard 拆分 | 大 | 高——LogCard / AskCard / ChatCard 各司其职 |

---

## 三、UI 设计师视角 — 深色主题 + 信息层级

### 3.1 当前视觉痛点

| 问题 | 严重度 | 位置 |
|:-----|:------:|:-----|
| AI 回复文字对比度不足 | 🔴 | 聊天气泡、FeedCard turns |
| 标签/代码块背景深色看不清 | 🔴 | `_buildAiMessage()` Markdown |
| 卡片各状态视觉差异不明显 | 🟡 | idle/ended 的边框差异只有颜色 |
| loading 指示器不统一 | 🟡 | 3 种不同风格的 loading |
| 字体层级模糊 | 🟡 | 正文/标题/标签的字号权重无体系 |

### 3.2 深色主题配色优化

现状是 green (#3AB77D) 为主色 + 灰色层级。
深色模式下需要更清晰的对比度：

| 用途 | 当前 | 建议 |
|:-----|:-----|:-----|
| 背景 | `#1A1A1A` | 维持或微调到 `#1C1C1E`（iOS 风格） |
| 卡片表面 | `#242424` | `#2C2C2E`（略亮，更好区分层级） |
| 主要文字 | `#E8E8E8` | 维持，确保 >= 7:1 对比度 |
| 次要文字 | `#999999` | `#A0A0A0`（略亮，提升可读性） |
| AI 回复背景 | `#2C2C2E` | `#333340`（区分用户/AI，但不刺眼） |
| 标签背景 | `#333333` 边框 | `#3A3A3C` 填充 + `#5A5A5C` 边框 |
| 代码/高亮背景 | 当前无统一 | `#2D2D3A`（带紫调，与普通卡片区分） |

### 3.3 信息层级体系

建议定义 4 级字号体系：

| 级别 | 字号 | 字重 | 用途 |
|:-----|:----:|:----:|:-----|
| H1 | 16px | 600 | 日期/标题、Brief 首行 |
| Body | 14-15px | 400 | 正文、AI 回复 |
| Caption | 11-12px | 400-500 | 时间、标签、summary |
| Micro | 9-10px | 400-500 | badge、OS 标记、小字 |

### 3.4 Loading 指示器统一

当前 3 种 loading：
1. `CircularProgressIndicator`（feed 首次加载 + 分页）
2. `_LoadingDots`（聊天等待 AI）
3. `CircularProgressIndicator` 在 domain badge（end 对话生成 summary）

**建议统一为 `_LoadingDots` 风格**（脉冲式圆点），
配合上下文文案或位置说明"在做什么"：
- Feed 加载 → 居中 `_LoadingDots`
- 等待 AI 回复 → 气泡左侧 `_LoadingDots`
- 生成 summary → domain badge 位置旋转（保持）
- 分页加载 → "load more" 文字旁的 `_LoadingDots`

---

## 四、综合优先级

| 时间 | 产品 | 前端工程 | UI 设计 |
|:----|:-----|:---------|:--------|
| **本周** | 修复 #7 #8 #10（色彩/tag 显示/报错） | 错误处理标准化 | 调整深色主题对比度 |
| **2 周内** | 交互规范文档 | CardStore 抽取 | 字阶体系落地 |
| **1 月内** | 明确 Kernel/Domain OS 边界 | FeedCard 拆分 + 测试 | Loading 体系统一 |
| **2 月内** | Memory Phase 2-3 | Riverpod 迁移评估 | 全局设计 tokens 审查 |

---

## 五、不做的事（当前阶段）

| 方向 | 不做原因 |
|:-----|:---------|
| 多主题（浅色模式） | 深色模式优先，且单人场景无切换需求 |
| 国际化（i18n） | 单人中文场景，无多语言需求 |
| PWA 离线支持 | 依赖后端 AI，离线意义不大 |
| 实时协作 | 单人系统 |
| 动画体系 | 当前阶段稳定性 > 动效 |
