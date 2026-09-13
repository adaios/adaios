# AGENTS.md — adai-app

AdaiOS Flutter 前端（Web / Android / iOS）。

> 这是 AdaiOS monorepo 的一个子项目。在根目录下有全局 AGENTS.md 和 VISION.md。
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
# 运行 Web（本地开发）
flutter run -d chrome

# 构建 Web（⚠️ 必须走本脚本：内置 CanvasKit + 字体本地化补丁 + 硬校验）
sh scripts/build_web.sh                                   # 本地预览（base-href=/）
sh scripts/build_web.sh https://api.adaiadai.com          # 桌面形态（根路径部署）
sh scripts/build_web.sh https://api.adaiadai.com /m/      # PWA（手机装主屏，见下）

# 本地预览（构建 + 起 python http.server:8081）
sh scripts/serve_web.sh https://api.adaiadai.com /m/

# iOS（装机需手机可达，devicectl）
flutter build ios --release --dart-define=API_BASE_URL=https://api.adaiadai.com

# 分析 / 测试
flutter analyze
flutter test
```

> ⚠️ **裸 `flutter build web` 不可用于部署**：漏打补丁 → CanvasKit 从 gstatic 拉（被墙）白屏、中文全框。
> ⚠️ **PWA（2026-09-13 起）**：手机端入口 = 本项目的 web 构建部署在 `https://adaiadai.com/m/`，
> iPhone Safari「添加到主屏幕」后全屏独立运行——用来绕开免费 Apple ID 签名 7 天过期（REVIEW P2-用户1）。
> 构建必须带 `/m/` 作为 base-href（字体补丁路径跟着走）；部署见 `docs/deployment/backend-deployment.md` §8.1。
> ⚠️ **平台分支只在 build 时暴露**：`flutter test` 跑 VM（走 `sse_client_io` / `user_store_io`），
> **覆盖不到 web 实现**——改条件导出（`if (dart.library.js_interop)`）后必须真的 `build web` 一次
> （2026-09-13 因此炸过：web 侧缺构造参数，test 全绿而 build 直接编译失败，见 pitfalls「条件导出的两份实现 API 面不一致」）。

## 项目结构

```
lib/
├── main.dart                    # App 入口
├── main_page.dart               # 主页面 — TopBar + Feed + InputBar
├── services/
│   ├── api_config.dart          # API 配置（后端地址）
│   ├── api_service.dart         # HTTP 客户端（REST API 调用）
│   ├── push_service.dart        # 推送接入（RFC 20260913）：仅 iOS 原生，登录后申请通知权限 → 上报 APNs deviceToken → 后端 ApnsPushChannel 直连 APNs；Web/PWA/Android 降级不碰原生通道
│   └── entry_intent_service.dart # 外部入口（RFC 20260913）：接 Siri「记一笔」/ 快捷指令 / adai:// → **按动作（record/digest）分派**；record 有内容直接落成记录、空内容只预填，digest 直达 learn 整理；同样仅 iOS 原生
├── theme/
│   ├── app_colors.dart          # 调色板
│   └── app_theme.dart           # Material 3 ThemeData
├── pages/
│   ├── project_status_page.dart # 项目仪表盘 + RFC 状态
│   ├── project_task_page.dart   # 任务列表 + CRUD
│   └── life_quick_entry.dart    # 生活快速记录模板（心情/运动/饮食/睡眠；模板单一事实源 + initialType 预选）
└── widgets/
    ├── feed_card.dart           # FeedCard — 4 态状态机：idle/waiting/chatting/ended
    ├── input_bar.dart           # 输入栏 — 生活快捷条（一点即开弹窗）+ 文字输入 + [+]附件（图片内联多图/文件/链接；语音 v2 方向）
    └── timeline_modal.dart      # 时间线 BottomSheet
```

## 当前测试状态

- **测试数唯一事实源：`../../docs/reference/status.md`**（RFC `20260815-docs-governance`，/ship 时更新，本文件不复制数字）
- 测试在 `test/`（widget_test / user_id_test / feed_state_machine_test / pages_widget_test / input_bar_keyboard_test / **life_quick_entry_test**），覆盖：DTO JSON 解析、FeedCardData 模型、FeedCard 渲染（idle/chatting/ended/折叠/loading/对话态 + #15 chatting 不折叠回归）、userId query 解析、Feed 状态机（ask→waiting→chatting→ended/追加/错误重试/删除/加载更多/#100 竞态 + #234 分页终止口径 + #235/#245 图片上传占位卡重试 + Phase 1 带图 ask-batch 触发/分流）、6 页面（memory/timeline/search/trading/task/profile 数据渲染 + 错误态 + 重试）、输入栏（键盘收起 + Phase 1 图片数量上限/角标封顶）、**生活快捷记录（模板单一事实源/预选类型/非法值回落/「使用模板」光标落句尾/切类型清空/快捷条渲染与对话中隐藏/端到端接回主发送流）**。
> ApiService 支持注入 `http.Client`（MockClient），所有 widget 测试不依赖真实后端。

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
| 加载项目状态 | `GET /api/v1/project/status` |
| 加载任务列表 | `GET /api/v1/project/tasks?status=...` |
| 创建任务 | `POST /api/v1/project/tasks` |
| 更新任务 | `PUT /api/v1/project/tasks/{id}` |
| 删除任务 | `DELETE /api/v1/project/tasks/{id}` |
| 任务统计 | `GET /api/v1/project/tasks/stats` |

## iOS 推送（RFC 20260913）

- **原生侧**：`ios/Runner/Runner.entitlements`（`aps-environment`）+ pbxproj 三个 Runner 配置挂 `CODE_SIGN_ENTITLEMENTS` + `ios/Runner/AppDelegate.swift`（注册远程通知、token/失败/点击回调经 MethodChannel `adai/push` 交给 Dart、前台也弹横幅）。⚠️ `FlutterAppDelegate` **本身已遵循** `UNUserNotificationCenterDelegate`，四个回调必须写成类体内的 `override`（放 extension 会报 redundant conformance，见 pitfalls 十三）。
- **Dart 侧**：`PushService` 只在 `!kIsWeb && defaultTargetPlatform == TargetPlatform.iOS` 生效；登录后由 `DualWorldShell.initState` 调用；通知点击 → 切回 Feed 并刷新；权限被拒 → 一条可点的「去开启」引导；登出注销本机设备。
- **环境别猜**：deviceToken 分属 sandbox / production 两套互不相通的网关，App 侧读包内 `embedded.mobileprovision` 的 `aps-environment` 得出环境上报（**不能用 `#if DEBUG`**：本项目装机是 `--release` + development 描述文件 = release 优化 + 沙箱环境）。
- 服务端配置与验证步骤见 `docs/deployment/backend-deployment.md` §11。

## iOS 外部入口（RFC 20260913 + 2026-09-13 整理动作批）

- **两个动作**（「入口」是从哪来，「动作」是要做什么）：**记一笔** `RecordIntent`（Siri 短语 `AdaiAppShortcuts` / 快捷指令「阿呆阿呆」动作 / `adai://record?text=…`）· **整理** `DigestIntent`（快捷指令「阿呆阿呆整理」动作 / `adai://digest?url=…`，交付 learn 流水线）。
- **新增入口改哪里**：原生 `ExternalEntryAction`（`ExternalEntry.swift`）是张**表**，加一个入口 = 加一个枚举值 + Dart 侧 `ExternalEntryAction`（`entry_intent_service.dart`）一个分支，原生侧不再动结构。⚠️ **两份动作表必须同步**——只加一侧就是「原生发得出去、Dart 认不出来」的静默无反应（同族坑见 pitfalls「条件导出的两份实现 API 面不一致」）。
- **认不出的动作既不认领也不回落**（原生 `handle` 返回 false / Dart `fromNative` 返回 null）。回落成 record 会把「整理」悄悄变成「记一条」——那正是 2026-09-13 真机实测到的失败（快捷指令里共享链接缺「整理」二字 → 静默落成普通记录）。
- **`DigestIntent` 的参数是 `String` 不是 `URL`**：分享出来常是夹着链接的口令文本（抖音那种「8.88 复制打开抖音… https://v.douyin.com/x」），URL 强校验会直接拒收；由 Dart 侧 `_httpLinkOf` 择出链接。它**刻意不进 `AdaiAppShortcuts`**（用嘴念 URL 不现实，只会给 Siri 添噪声）。
- **投递必须落盘**：App Intent 的 `perform()` 与 Flutter 引擎初始化**没有先后保证**（冷启动时引擎可能还没起来），所以 `ExternalEntry` 走「落 UserDefaults + 同进程通知」双路径，Dart 起来后再 `takePendingEntry` 兜底取；drain 即清空 → 天然消费一次。
- **URL 有两条送达路径，缺一不可**：warm → `SceneDelegate.scene(_:openURLContexts:)`；cold → `scene(_:willConnectTo:options:)` 的 `connectionOptions.urlContexts`。**两条都必须调 `super`**（`FlutterSceneDelegate` 的实现藏在 framework 里，头文件没暴露但 Swift 可覆写；`willConnectTo` 里 super 负责引擎装配，跳过会让 App 起不来）。见 pitfalls 十四。
- **scene 架构下 AppDelegate 的 `application(_:open:)` 不会被调用**——URL 处理别写在 AppDelegate。
- ⚠️ **本批只做到「跳 App 后能整理」**：`openAppWhenRun = true` 是 `DigestIntent` 的固有代价，而用户真实痛点是「**要跳一下 App，打断了看到就丢**」→ 真正的解是 **Share Extension**（扩展自己把链接投给后端），见 `docs/rfc/`（分享扩展批次）。

## 设计约定

- **三端兼容** — 必须同时支持 Android / iOS / Web。引入依赖前确认 pub.dev 三端支持。Web 无 `dart:io`，平台差异用 `kIsWeb` 或 `Platform.*`。
- **单页** — 无 BottomNavigation，无 tabs，无多级页面
- **深色模式优先**
- **一个卡片一次对话** — idle → waiting → chatting → ended 在同一个卡片内完成
- **激活卡片** — 左侧 3px 绿色竖线标识，底部无边框（直角的），移到最底部
- **已结束卡片** — 绿色边框 + 总结 + 标签 + `── ask ──`
- **设计 tokens** 在 `app_colors.dart` 中定义

## 相关文档

| 文档（根目录的需 CLI read 查看） | 位置 | 说明 |
|:-------------------------------|:----|:------|
| UI_REFERENCE.md | 本目录 | 📌 每个按钮→代码行精确对照 |
| DESIGN.md | 本目录 | 设计原则与核心哲学 |
| `frontend-reference.md` | `../../docs/architecture/` | 前端统一参考（术语对照 + 布局视觉） |
| `api-spec.md` | `../../docs/architecture/` | API 接口契约（全局唯一真相源） |
| `VISION.md` | `../../docs/` | 项目愿景与核心理念 |
