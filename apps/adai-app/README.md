# adai-app

AdaiOS 前端应用 — Flutter Web / Android / iOS。

## 启动

```bash
# Web
flutter run -d chrome

# Android（需连接设备）
flutter run -d android

# 构建静态文件（走代理时用）
flutter build web
python -m http.server 8000 -d build/web
```

后端地址：`http://localhost:8080`（在 `lib/services/api_config.dart` 中配置）

## UI 术语（用于对话交流）

| 词 | 英文 | 含义 |
|:---|:-----|:-----|
| **顶栏** | TopBar | 顶部日期 + 星期，点击打开时间线 |
| **今日摘要** | Brief | 固定卡片，AI 生成的问候，带 emoji 列表 |
| **卡片** | Card | 内容的基本单元（一条记录或一个会话） |
| **卡头** | Card Header | 卡片顶部：`log` / `ask` 标签 + 更新时间 |
| **正文** | Body | 卡片的文字内容 |
| **底部操作栏** | Bottom Line | 卡片底部：`ask` 按钮（双击也进 chat） |
| **对话轮次** | Turn | 一问一答，`you` / `ai` 交替 |
| **列表模式** | List Mode | 全部卡片纵向排列，最新在底部，可翻页 |
| **激活模式** | Active Mode | 某张卡片全屏展开聊天，无折叠条 |
| **展开区** | Expanded Area | 激活模式下的聊天气泡区 |
| **输入栏** | InputBar | 底部输入框 |
| **我** | User | 标签 `you` |
| **AI** | AI | 标签 `ai` |

### 两种模式

| 模式 | 含义 |
|:----|:------|
| **list** | 卡片列表（浏览），可双击 / 点 `ask` 进入 chat |
| **chat** | 激活模式（全屏对话），可继续输入、点 ✕ / `end` 返回 list |

### 卡片类型

| 类型 | 标签 | 条件 | 底部按钮 |
|:----|:----|:-----|:--------|
| **log** | 灰底 | 无对话轮次（纯记录） | `ask` |
| **ask** | 绿底 | 有对话轮次（至少一问一答） | `ask` |

两种类型都可以通过双击 / 点 `ask` 进入 chat 模式。

### 底部操作栏按钮

| 按钮 | 含义 |
|:----|:-----|
| `ask` | 进入 chat 模式 |
| `✕` | 折叠 / 关闭，回到列表 |
| `end` | 结束对话，AI 重新总结，回到列表 |

### 卡片左上角时间

显示**最后更新时间**，排序也按最后更新时间升序排列（最新在底部）。

### 分页

- 默认显示最近 **5 条**卡片
- 点击列表顶部的 `load more` 加载更早的 5 条（叠加，不替换）
- 当天全部加载完后自动加载前一天

### 卡片生命周期

```
输入 ─→ 后端判断意图
         │
         ├─ STATEMENT ─→ log（创建时 AI 摘要 + tags ✅）
         │               摘要持久化到文件，Feed API 返回
         │
         └─ QUESTION ─→ ask（创建时 AI 回答 ✅）
                         自动进入 chat

list（卡片列表）
 │
 └─ 双击 / 点 ask ─→ chat

chat（激活模式，卡头仅显示 [chat]）
 │
 ├─ 输入 → 追加 turns → AI 回答 ✅ → 标签自动变为 ask
 │       log 首次输入自动带出原始 content 作为第一条消息
 │
 ├─ ✕ / 双击空白 / end
 │     └─ 判断是否有新 turns？
 │         ├─ 有新 turns → AI 重新总结（原文 + turns）✅
 │         │              覆盖摘要 + 标签 → 回 list（ask 标签）
 │         └─ 无新 turns → 仅折叠，不调 AI ❌
 │                        有 turns → ask / 无 turns → log
```

### AI 调用判断

> **核心规则：是否调 AI 取决于 chat 内容是否有新变化（新的 turns）。**

| 时机 | 调 AI？ | 条件 | 输入 | 输出 |
|:----|:-------:|:-----|:-----|:-----|
| log 创建 | ✅ | — | 用户原文 | 摘要 + 标签（写回文件） |
| ask 创建 | ✅ | — | 用户原文 | AI 回答 |
| 进入 chat | ❌ | — | — | — |
| 输入（追加 turns） | ✅ | — | 原文 + turns | AI 回答 |
| ✕ / 双击关闭 | ❌ | 无新 turns | — | 仅改标签 |
| ✕ / 双击关闭 | ✅ | 有新 turns | 原文 + 全部 turns | 重新总结，覆盖 |
| end | ✅ | 有新 turns | 原文 + 全部 turns | 重新总结，覆盖 |
| end | ❌ | 无新 turns | — | 仅折叠 |

end 失败时：报错提示 + 本地兜底摘要（方案 B）。

### 今日摘要

- 后端根据当前时间自动判断 `早上好` / `下午好` / `晚上好`
- 输出格式为带 emoji 的换行列表
- 第一行（问候语）纯文字，其余行左侧渲染 emoji
- 摘要卡片固定在页面顶部，不随列表滚动

### 示例对话

> "chat 模式卡头只显示 `[chat]`，不加用户原文标题"

> "log 进 chat 一个字没写就关 → 不调 AI → 保持 log"

> "log 进 chat 聊了几句再关 → AI 总结 → 变 ask"

> "end 失败时弹提示，卡片标记 ended"

## 技术栈

| 层面 | 选型 |
|:----|:-----|
| 框架 | Flutter 3.44.6 / Dart 3.12.2 |
| 状态管理 | StatefulWidget（无外部依赖） |
| HTTP | `package:http` |
| 构建 | `flutter build web` / `flutter build apk` |

## 三端兼容约束

本项目同时运行于 **Android / iOS / Web** 三端，引入任何依赖、工具、资源前需确认三端兼容。

| 约束 | 说明 |
|:----|:------|
| **第三方依赖** | pub.dev 确认包含 Android / iOS / Web 三端 platform support 标签，缺一不可 |
| **字体/资源** | 路径和格式三端一致，避免平台特定文件格式 |
| **平台 API** | 禁止直接使用 `dart:io` 或 `dart:html`；平台差异用 `kIsWeb`（`import 'dart:io' show Platform;` 需做条件导入） |
| **Web 无文件系统** | Web 端无 `dart:io` 的 File/Directory 访问，文件读写相关功能必须三端统一走 HTTP API |
| **通信方式** | 统一走 HTTP/WebSocket，不引入平台特定 IPC |

> 添加依赖前先确认三端支持。发现平台差异用 `if (kIsWeb)` 做条件分支。

## 当前状态

已接入 adai-core HTTP API（非 Mock）。
