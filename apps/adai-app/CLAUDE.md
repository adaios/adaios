# CLAUDE.md — adai-app

AdaiOS Flutter 前端（Web / Android / iOS）。

> 这是 AdaiOS monorepo 的一个子项目。在根目录下有全局 CLAUDE.md 和 VISION.md。
> **在本目录工作时，你的上下文限制在 Flutter 前端，不处理后端、交易知识等其他项目。**

---

## 技术栈

| 层面 | 选型 |
|------|------|
| 框架 | Flutter 3.44.6 / Dart 3.12.2 |
| 主题 | Material 3，深色模式 |
| 状态管理 | StatefulWidget + setState（无第三方状态库） |
| 后端通讯 | HTTP REST → `services/adai-core`（Spring Boot） |

## 构建与运行

```bash
# Flutter SDK 路径
export PATH="$PATH:/d/Software/flutter/bin"

# 运行 Web
flutter run -d chrome

# 构建
flutter build web --release      # Web release
flutter build apk --release --dart-define=API_BASE_URL=http://49.235.37.220:8080  # Android APK（生产）

# 分析
flutter analyze

# 测试
flutter test
```

## 项目结构

```
lib/
├── main.dart                    # App 入口
├── main_page.dart               # 主页面 — TopBar + Feed + InputBar
├── services/
│   ├── api_config.dart          # API 配置（后端地址）
│   └── api_service.dart         # HTTP 客户端（REST API 调用）
├── theme/
│   ├── app_colors.dart          # 调色板
│   └── app_theme.dart           # Material 3 ThemeData
└── widgets/
    ├── feed_card.dart           # FeedCard — 4 态状态机：idle/waiting/chatting/ended
    ├── input_bar.dart           # 输入栏 — 语音/文字切换 + 发送
    └── timeline_modal.dart      # 时间线 BottomSheet
```

## 当前测试状态

前端测试在 `test/widget_test.dart`，当前 **19 个测试，0 失败**。
覆盖：DTO JSON 解析（7）、FeedCardData 模型（5）、FeedCard 渲染（7，含 idle/chatting/ended 态）。

```bash
cd apps/adai-app && flutter test
```
> Flutter widget test 默认 HTTP 返回 400，ApiService 调用未覆盖——留给集成测试或 mock HTTP client。

## FeedCard 状态机

```
                用户输入（新记录）
                      │
               ┌──────┴──────┐
               │             │
           intent=log   intent=question
               │             │
               ▼             ▼
            idle 态      chatting 态
        底部 ──ask──     底部 [end]
               │             │
               │ 点 ask      │ 点 end
               ▼             ▼
           waiting 态      ended 态
         输入自动聚焦     绿色边框 + 总结标签
               │         底部 ──ask──
               │ 输入        │ 点 ask → 回到 waiting
               ▼             ▼
           chatting 态      ...
```

## API 依赖

前端需要后端 `services/adai-core` 运行中。API 契约见 `docs/architecture/api-spec.md`。

| 前端操作 | API 调用 |
|:---------|:---------|
| 新输入 | `POST /api/v1/records` |
| 点 [ask] → 用户输入 | `POST /api/v1/records` `intent: "question"` |
| 点 [end] | `POST /api/v1/conversations/end` |
| 加载 Feed | `GET /api/v1/feed` |
| 加载简报 | `GET /api/v1/brief` |
| 加载时间线 | `GET /api/v1/timeline` |

## 设计约定

- **三端兼容** — 必须同时支持 Android / iOS / Web。引入依赖前确认 pub.dev 三端支持。Web 无 `dart:io`，平台差异用 `kIsWeb` 或 `Platform.*`。
- **单页** — 无 BottomNavigation，无 tabs，无多级页面
- **深色模式优先**
- **一个卡片一次对话** — idle → waiting → chatting → ended 在同一个卡片内完成
- **激活卡片** — 左侧 3px 绿色竖线标识，底部无边框（直角的），移到最底部
- **已结束卡片** — 绿色边框 + 总结 + 标签 + `── ask ──`
- **设计 tokens** 在 `app_colors.dart` 中定义

## 相关文档

- `docs/architecture/frontend-glossary.md` — 前端中英术语对照（用中文提修改时参考）
- `docs/architecture/layout-reference.md` — 页面布局视觉参考（各状态 ASCII 图）
- `DESIGN.md` — 设计原则与核心哲学（本目录）
