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
sh scripts/build_web.sh https://api.adaiadai.com /m/      # 子路径构建（/m/ 入口已停用，见下）

# 本地预览（构建 + 起 python http.server:8081）
sh scripts/serve_web.sh https://api.adaiadai.com /m/

# iOS 发布（TestFlight，2026-09-15 起；无线分发，不需要数据线/手机在场）
# 完整方案见 docs/deployment/ios-release.md
export ASC_ISSUER_ID=<App Store Connect Issuer ID>   # 一次性配置
sh scripts/release_testflight.sh                     # 构建 + 导出 IPA + 上传
sh scripts/release_testflight.sh --build-number 2    # 递增构建号（同一版本重复上传会被 Apple 拒）
sh scripts/release_testflight.sh --status            # 查 Apple 侧处理结果（PROCESSING/VALID/INVALID）

# iOS 侧载（**仅本地调试**用；2026-09-15 起不再是分发手段）
flutter build ios --release --dart-define=API_BASE_URL=https://api.adaiadai.com

# 分析 / 测试
flutter analyze
flutter test
```

> ⚠️ **裸 `flutter build web` 不可用于部署**：漏打补丁 → CanvasKit 从 gstatic 拉（被墙）白屏、中文全框。
> ⚠️ **手机端网页入口已于 2026-09-23 停用**（用户拍板：手机端只认 iOS 原生 App / TestFlight）。
> 本项目的 web 构建曾部署在 `https://adaiadai.com/m/` 供 iPhone Safari「添加到主屏幕」使用，
> 但它自 2026-09-17 起未再重建、落后 6 天（REVIEW P2-工程9）→ 生产 Caddy 已改为**指路页**，
> `app-web` 也不再随发版更新（发版清单已排除）。**改 `lib/` 只为 iOS 原生包服务**；
> 若确需子路径构建（`/m/` base-href）用于本地排查，命令见上，但不要再把它当手机端入口。
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
│   ├── push_service.dart        # 推送接入（RFC 20260913）：仅 iOS 原生，登录后申请通知权限 → 上报 APNs deviceToken → 后端 ApnsPushChannel 直连 APNs；非 iOS 平台（Web / Android）降级不碰原生通道
│   └── entry_intent_service.dart # 外部入口（RFC 20260913）：接 Siri「记一笔」/ 快捷指令 / adai:// → **按动作（record/digest）分派**；record 有内容直接落成记录、空内容只预填，digest 直达 learn 整理；同样仅 iOS 原生
├── theme/
│   ├── app_colors.dart          # 调色板
│   └── app_theme.dart           # Material 3 ThemeData
├── pages/
│   ├── todo_page.dart           # 待办清单（Kernel builtin，两态 OPEN/DONE + 可选到期日）
│   └── …                        # memory / timeline / search / trading / learn / profile / launcher
└── widgets/
    ├── feed_card.dart           # FeedCard — 4 态状态机：idle/waiting/chatting/ended
    ├── input_bar.dart           # 输入栏 — 文字输入 + [+]附件（图片内联多图/文件/链接；语音 v2 方向；2026-09-16 生活快捷条下线）
    └── timeline_modal.dart      # 时间线 BottomSheet
```

## 当前测试状态

- **测试数唯一事实源：`../../docs/reference/status.md`**（RFC `20260815-docs-governance`，/ship 时更新，本文件不复制数字）
- 测试在 `test/`（widget_test / user_id_test / feed_state_machine_test / pages_widget_test / input_bar_keyboard_test / learn_page_test），覆盖：DTO JSON 解析、FeedCardData 模型、FeedCard 渲染（idle/chatting/ended/折叠/loading/对话态 + #15 chatting 不折叠回归）、userId query 解析、Feed 状态机（ask→waiting→chatting→ended/追加/错误重试/删除/加载更多/#100 竞态 + #234 分页终止口径 + #235/#245 图片上传占位卡重试 + Phase 1 带图 ask-batch 触发/分流）、6 页面（memory/timeline/search/trading/todo/profile 数据渲染 + 错误态 + 重试；待办页另有 `todo_page_test.dart` 专项：两态渲染/到期日人话/增删改/失败可重试）、输入栏（键盘收起 + Phase 1 图片数量上限/角标封顶 + **批次锁拒绝时不清空已选图**）、**图文一体多图一次投递（2026-09-22：一次 `POST /records/media/batch` + `Idempotency-Key` 重试复用同一 key + 一次投递一张卡 + 卡内多图并排 `media_thumb_strip.dart` + 多图回合追问走 `ask-batch` 带全部图 + `mediaPaths` 消费）**、学习页（分组/搜索/进度汇总/失败可见）。
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
| 加载待办 | `GET /api/v1/todos?status=OPEN\|DONE`（status 可选） |
| 新增待办 | `POST /api/v1/todos` |
| 改待办 | `PUT /api/v1/todos/{id}`（null=保持原值；`due:""`=清除到期日） |
| 删待办 | `DELETE /api/v1/todos/{id}` |
| 待办计数 | `GET /api/v1/todos/stats` |

## iOS 推送（RFC 20260913）

- **原生侧**：`ios/Runner/Runner.entitlements`（`aps-environment`）+ pbxproj 三个 Runner 配置挂 `CODE_SIGN_ENTITLEMENTS` + `ios/Runner/AppDelegate.swift`（注册远程通知、token/失败/点击回调经 MethodChannel `adai/push` 交给 Dart、前台也弹横幅）。⚠️ `FlutterAppDelegate` **本身已遵循** `UNUserNotificationCenterDelegate`，四个回调必须写成类体内的 `override`（放 extension 会报 redundant conformance，见 pitfalls 十三）。
- **Dart 侧**：`PushService` 只在 `!kIsWeb && defaultTargetPlatform == TargetPlatform.iOS` 生效；登录后由 `DualWorldShell.initState` 调用；通知点击 → 切回 Feed 并刷新；权限被拒 → 一条可点的「去开启」引导；登出注销本机设备。
- **环境别猜**：deviceToken 分属 sandbox / production 两套互不相通的网关，App 侧读包内 `embedded.mobileprovision` 的 `aps-environment` 得出环境上报（**不能用 `#if DEBUG`**：本项目装机是 `--release` + development 描述文件 = release 优化 + 沙箱环境）。
- 服务端配置与验证步骤见 `docs/deployment/backend-deployment.md` §11。

## iOS 外部入口（RFC 20260913 + 2026-09-13 整理动作批 + RFC 20260914 分享扩展批）

- **两个动作**（「入口」是从哪来，「动作」是要做什么）：**记一笔** `RecordIntent`（Siri 短语 `AdaiAppShortcuts` / 快捷指令「阿呆阿呆」动作 / `adai://record?text=…`）· **整理** `DigestIntent`（快捷指令「阿呆阿呆整理」动作 / `adai://digest?url=…`，交付 learn 流水线）。
- **新增入口改哪里**：原生 `ExternalEntryAction`（`ExternalEntry.swift`）是张**表**，加一个入口 = 加一个枚举值 + Dart 侧 `ExternalEntryAction`（`entry_intent_service.dart`）一个分支，原生侧不再动结构。⚠️ **两份动作表必须同步**——只加一侧就是「原生发得出去、Dart 认不出来」的静默无反应（同族坑见 pitfalls「条件导出的两份实现 API 面不一致」）。
- **认不出的动作既不认领也不回落**（原生 `handle` 返回 false / Dart `fromNative` 返回 null）。回落成 record 会把「整理」悄悄变成「记一条」——那正是 2026-09-13 真机实测到的失败（快捷指令里共享链接缺「整理」二字 → 静默落成普通记录）。
- **`DigestIntent` 的参数是 `String` 不是 `URL`**：分享出来常是夹着链接的口令文本（抖音那种「8.88 复制打开抖音… https://v.douyin.com/x」），URL 强校验会直接拒收；由 Dart 侧 `_httpLinkOf` 择出链接。它**刻意不进 `AdaiAppShortcuts`**（用嘴念 URL 不现实，只会给 Siri 添噪声）。
- **投递必须落盘**：App Intent 的 `perform()` 与 Flutter 引擎初始化**没有先后保证**（冷启动时引擎可能还没起来），所以 `ExternalEntry` 走「落 UserDefaults + 同进程通知」双路径，Dart 起来后再 `takePendingEntry` 兜底取；drain 即清空 → 天然消费一次。
- **URL 有两条送达路径，缺一不可**：warm → `SceneDelegate.scene(_:openURLContexts:)`；cold → `scene(_:willConnectTo:options:)` 的 `connectionOptions.urlContexts`。**两条都必须调 `super`**（`FlutterSceneDelegate` 的实现藏在 framework 里，头文件没暴露但 Swift 可覆写；`willConnectTo` 里 super 负责引擎装配，跳过会让 App 起不来）。见 pitfalls 十四。
- **scene 架构下 AppDelegate 的 `application(_:open:)` 不会被调用**——URL 处理别写在 AppDelegate。
- ✅ **「看到就丢」已实现（RFC 20260914，2026-09-14）**：`ShareExtension` target = 分享面板里的「阿呆阿呆」，扩展在**自己的进程**里提交 `/api/v1/learn/digest`，**不拉起主 App**。`DigestIntent`（`openAppWhenRun = true`，必跳 App）保留作为快捷指令入口，两条路并存。
- ⚠️ **扩展报「交出去了」不是终态——回执在 App 学习页（2026-09-23，REVIEW P1-分享8）**：扩展提交完 1 秒关窗、主 App 不被拉起，「我读完了」这句话必须由**学习页进页**说出来（`_checkDigestOutcome` 的 `done` 分支 → 顶部「你刚分享的那条，我整理好了《标题》」+ 点开直达，`_digestAcked` 保证同一次整理只说一次）。**配套契约**：后端 `done` 结果保留 **30 分钟**（`DONE_TTL_MS`，与 `failed` 同档；原 60 秒比用户从微博切到阿呆的时间还短）；`status=done` 也用于**按链接去重命中**（`findExistingCard`，不再重复抓取/烧模型），所以扩展对 `done` 说「这条我早整理过了」、对 `pending` 才说「交出去了」。**改这三处要一起改**——任何一处回退都会让用户重新变成「分享了，没反应」（见 pitfalls 十六）。
- ⚠️ **分享扩展的跨 target 契约（四处必须逐字一致）**：App Group id 与键名同时出现在 `Runner/ShareBridge.swift`、`ShareExtension/ShareAuth.swift`、`Runner/Runner.entitlements`、`ShareExtension/ShareExtension.entitlements`。不一致的表现**不是崩溃**，而是「扩展总说没拿到钥匙」（提交时给人话）——排查先核对这四处；Apple 侧要对**主 App 与扩展两个 App ID** 都开启该 App Group。
- ⚠️ **扩展不嵌 Flutter**：`ShareViewController` 是纯 UIKit（扩展内存上限 ~120MB，装不下 Flutter 引擎），也**不要** import Flutter——否则连 debug 装机都容易因引擎/系统版本敏感而闪退。
- ⚠️ **成功提示的 1 秒延迟必须排在 `completeRequest` 之前**：`completeRequest` 一调，系统立即终止扩展进程，之后的 `asyncAfter` 永远不会执行（2026-09-14 调研核实，见 `ShareViewController.setSuccess`）。
- ⚠️ **扩展的 `CFBundleVersion` 用 `$(FLUTTER_BUILD_NUMBER)`，且扩展 target 三个 configuration 都挂了 Flutter 的 `Debug/Release.xcconfig` 作 Base Configuration**：两个条件缺一不可——只写变量不挂 xcconfig 会得到空字符串（装机校验失败），只挂 xcconfig 不写变量会让版本号与主 App 漂（App Store 要求一致）。

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
