---
title: iOS 分享扩展（Share Extension）——B站/抖音分享面板直达阿呆
date: 2026-09-14
status: approved
decided-by: adai（2026-09-14：① 分享后的反馈 = 显示「已交给阿呆，正在读…」约 1 秒自动关，不当场弹确认；② 令牌 = 签发时由 App 自动写入 App Groups，用户零操作；③ 分享面板**只放「整理」一个动作**，不放「记一笔」）
---

# iOS 分享扩展：从「少说两个字就失败」到「看到就丢」

> **方向 RFC**：把 2026-09-13「分享与整理动作批」剩下的最后一公里补完——那批做的是**动作语义**（`DigestIntent`），
> 但保留了 `openAppWhenRun = true`，用户真实痛点「**要跳一下 App，打断了看到就丢**」并未解决。
> 本批做 Share Extension：**扩展在自己进程里把链接投给后端，主 App 全程不被拉起**。

---

## 一、背景（现状与问题，实测证据）

| # | 现状 | 问题 |
|:-:|:-----|:-----|
| A | **2026-09-12 RFC 拍板「iOS 分享扩展后置（先不做）」**（`20260912-learn-product-digest.md` §决策 1、§阶段 3） | 这是当时刻意的范围收敛，不是遗漏——现在用户明确要它了 |
| B | **2026-09-13 分享与整理动作批**落地 `DigestIntent` + `adai://digest`：动作语义不再靠关键词猜 | **`openAppWhenRun = true` 是固有代价**：分享一次必跳一次 App。这批文档自己写明「**只是地基不是答案**」 |
| C | **2026-09-14 用户实测反馈**：「B站分享面板里压根没有阿呆阿呆」 | 确认：`apps/adai-app/ios/` 下只有 `Runner`，**没有任何 Extension target** → iOS 分享面板里**永远不会**出现阿呆阿呆。**与手机包版本无关**（用户当时怀疑「是不是手机没更新包」，方向错了） |
| D | 学习页「把分享接到阿呆」弹窗走的是**四步手工配快捷指令**（`share_token_dialog.dart`） | ① 分享面板里显示的是「快捷指令」的图标与名字，不是阿呆；② 明文令牌要用户手动复制粘贴；③ 必跳 App |

## 二、问题（病灶/缺口清单）

| # | 缺口 | 证据/影响 |
|:-:|:-----|:----------|
| 1 | **无 Share Extension target** | `ios/` 下只有 `Runner` + `RunnerTests`；`ls ios/Runner/` 无扩展目录。iOS 通过 Extension 注册分享目标，没有 target 就没有入口 |
| 2 | **无跨进程凭据共享容器** | 现有 `Runner.entitlements` **只有 `aps-environment`**（实测 `strings` 描述文件确认），没有 App Groups。令牌明文只在签发弹窗里出现一次、后端只存 SHA-256 → 扩展拿不到任何凭据，无从提交 |
| 3 | **快捷指令路径不可替代** | 快捷指令是**用户自建**的外部自动化，面板里挂着的是「快捷指令」的牌子；扩展是**产品自己**的分享目标，面板里挂的是「阿呆阿呆」。两者在系统里是不同的东西 |
| 4 | **真机分享链路从未验证** | `feature-reference.md` §19 原话：「**在此之前不要当它已经能用**」。本批必须把这条验证补上（含装机） |

## 三、方案

### 3.1 一句话

**新增 iOS Share Extension target：B站/抖音分享面板第一排出现「阿呆阿呆」→ 点一下 → 扩展在自进程内择出链接、用限权令牌 `POST /api/v1/learn/digest` → 显示「已交给阿呆，正在读…」→ 约 1 秒自动关闭。主 App 全程不被拉起。**

### 3.2 为什么能秒回（关键前提）

`POST /api/v1/learn/digest` 是**提交式**端点（2026-09-10 喂入入口批起）：立即返回 `status=pending/running`，
抓取 + 按需转写 + 结构化在**后端后台线程**跑（`LearnSubmitConfig` 独立执行器）。
因此扩展**不需要**等待抓取/转写（可能几分钟），发完即可关闭——这正是扩展（有严格完成时限）能承载它的原因。

### 3.3 结构

| 项 | 内容 |
|:---|:-----|
| 新 target | `ShareExtension`，Bundle ID `com.adaiadai.adaiApp.ShareExtension`（Team `4G3D37YKSB`，自动签名） |
| 类型 | **Share Extension**（`com.apple.share-services`）。只有这个 point 出现在分享面板第一排；Action Extension（`com.apple.ui-services`）只会在「更多」里 |
| 接收 | `NSExtensionActivationRule` 同时接受 URL / 网页 URL / 纯文本（B站分享常是「标题 + 链接」混合文本） |
| 代码 | `ios/ShareExtension/`，纯 Swift，**不经过 Flutter**（扩展进程是独立进程，装不下 Flutter 引擎） |
| 请求 | `URLSession` → `POST https://api.adaiadai.com/api/v1/learn/digest`，body `{"url": "…"}`，头 `Authorization: Bearer <令牌>`、`Content-Type: application/json` |
| 链接择取 | 从分享文本里正则择出 http(s) 链接（与 Dart 侧 `_httpLinkOf` **同口径**：夹在口令文本里也要能择出）；择不出 → 人话拒绝 |
| 反馈 | 提交中 → 「交给阿呆…」；成功 → 「已交给阿呆，正在读…」约 1 秒自动关；失败 → 停留显示人话 + 关闭按钮 |

### 3.4 凭据：App Groups 共享限权令牌

| 方面 | 做法 |
|:---|:---|
| 共享容器 | 新增 **App Groups `group.com.adaiadai.adaiApp`**（主 App 与扩展两个 target 都挂） |
| 写入时机 | 主 App 签发外部令牌**成功后**，由原生侧（MethodChannel）把明文写进 group 的 `UserDefaults(suiteName:)`；**用户零操作**（决策 ②） |
| 用哪把钥匙 | **同一类 `learn:digest` 限权令牌**（`TokenScope` 精确白名单：提交整理 / 确认转写 / 查状态 / 查额度四条）。**绝不是登录会话**——它读不到记录、交易、记忆，也碰不到 `/api/v1/auth/**`，无法自造更大的钥匙 |
| 清除联动 | 撤销令牌 / 登出 / 账号切换 → 同步清空 group 里的明文（**不留孤儿钥匙**）；令牌 90 天到期或失效时，扩展提交失败走人话提示，不静默 |
| 为什么要共享容器 | 扩展进程有独立沙箱，读不到主 App 的 `UserDefaults`/`shared_preferences`；没有 App Groups 就只能每次手输令牌（等于把摩擦换了个地方） |

### 3.5 主 App 侧改动

| 项 | 改动 |
|:---|:---|
| 签发流程 | `ShareTokenDialog._issue` 成功后 → 调原生写 App Groups（新增 MethodChannel，如 `adai/share`） |
| 撤销流程 | `_revoke` 成功后 → 清 group |
| 引导文案 | iOS 上从「四步配快捷指令」改为「**分享面板已就绪**，去 B站/抖音分享时找阿呆阿呆」；快捷指令引导**保留**（web / Android / 不想用扩展的场景） |
| 回 App 后 | 无需新增：学习页已有「进页任务态恢复入口」（v3.60），会看到「正在读…」 |

### 3.6 明确不做（防范围膨胀，B 的前车之鉴）

| 不做 | 理由 |
|:-----|:-----|
| **扩展内的「付费转写确认」** | 长视频转写要花钱那一步仍需回 App 点头（`needs_confirmation`），App 已有恢复入口。在分享面板里塞一个花钱确认 UI，会把「看到就丢」重新变复杂 |
| **分享面板里的「记一笔」** | 决策 ③。分享 = 去把这个链接读明白，职责单一；分享纯文字（无链接）→ 人话拒绝，**不落成记录**（回落成记录正是 09-13 要根除的失败模式） |
| **Android 分享入口（`ACTION_SEND` intent-filter）** | 本次只解决 iOS（用户设备是 iPhone）。Android 侧成本低（只改 manifest + 一个接收 Activity），按需另开 |
| **iOS 分享扩展的「批量多选」** | 单条分享是本批口径；多选链接的语义（合成一张卡？多张？）未定义，不预设 |

## 四、已知风险与前置

| # | 风险/前置 | 处置 |
|:-:|:---------|:-----|
| 1 | **iOS 26（设备 26.6.1）对扩展生命周期有强制要求** | 实施首步以调研结论落定 UI 形态（`SLComposeServiceViewController` vs 自定义 `UIViewController` + 是否需要 scene manifest），不拍脑袋 |
| 2 | **App Groups 能力需在 Apple 侧新开** | 自动签名下 Xcode 会去 Apple 侧开启并重签描述文件（APNs 能力的先例：2026-09-13 实测成功）；报错时加 `-allowProvisioningUpdates`，或用 Xcode 打开 workspace 点一次 Run |
| 3 | **`$(FLUTTER_BUILD_NUMBER)` 在扩展 target 为空** | 扩展 target 默认不 include Flutter 的 `Generated.xcconfig`；`CFBundleVersion` 若引用该变量会构建失败。按调研结论处理（字面值或显式 include） |
| 4 | **真机装机依赖手机可达** | 2026-09-14 `devicectl list devices` 显示 `Adai的iPhone … unavailable`。装机那步需手机连上 |
| 5 | **真机分享链路是唯一验收标准** | 编译通过 ≠ 能用（`feature-reference.md` §19 的教训）。必须在 B站分享面板真实走一次，才算完成 |

## 五、验收标准

1. `flutter test` 全绿（含链接择取 / 去重 / 错误人话的新用例）。
2. `flutter build ios --release` 通过，产物含 `PlugIns/ShareExtension.appex`，且扩展 entitlements 含 `group.com.adaiadai.adaiApp`。
3. **真机**：B站 App → 分享 → 面板第一排出现「阿呆阿呆」→ 点一下 → 显示「已交给阿呆，正在读…」自动关闭 → **主 App 未被拉起** → 稍后打开阿呆，学习页出现正在读/新卡片。
4. 撤销令牌后，扩展提交失败并给人话（不是静默无反应）。

## 六、验证取证（实施后回填）

**已完成（2026-09-14）**

| 项 | 取证 |
|:---|:-----|
| 静态检查 | `flutter analyze` → **0 issue** |
| 单测 | app **327 全绿**（本批 +7：签发写入 · 三态文案 · 非 iOS 整段不出现 · 撤销清空 · 不误伤其它把；`share_token_dialog_test` 19→26） |
| iOS 构建 | `flutter build ios --release --dart-define=API_BASE_URL=https://api.adaiadai.com` → **✓ Built Runner.app (21.7MB)** |
| 扩展已嵌入 | 产物 `Runner.app/PlugIns/ShareExtension.appex` 存在 |
| **App Groups 能力真签上了** | 主 App entitlements：`com.apple.security.application-groups = [group.com.adaiadai.adaiApp]`（与既有 `aps-environment` 并存）；扩展 entitlements：同一个 group + `application-identifier = 4G3D37YKSB.com.adaiadai.adaiApp.ShareExtension`。**自动签名成功去 Apple 侧开启并重签描述文件**（本轮最大的前置风险已排除） |
| 版本号对齐 | 扩展 `CFBundleShortVersionString = 1.0.0` / `CFBundleVersion = 1`（`$(FLUTTER_BUILD_NAME/NUMBER)` + Base Configuration 生效，与主 App 一致） |
| 扩展注册 | `NSExtensionPointIdentifier = com.apple.share-services` · `NSExtensionPrincipalClass = ShareExtension.ShareViewController` · 激活规则 WebURL / WebPage / Text 三开 + `NSExtensionActivationDictionaryVersion = 2` |

**独立调研结论（2026-09-14，落实处已进代码与注释）**

按 7 个问题做了外部核实，四处按官方做法修正：① iOS 26 **不**强制 App Extension 采用 UIScene（强制从 iOS 27 起且官方只表述 "app"）→ 扩展**不加** `UIApplicationSceneManifest`；② 用自定义 `UIViewController` + `NSExtensionPrincipalClass`（不用 `SLComposeServiceViewController`）；③ `Embed Foundation Extensions` 阶段移到 `Run Script` **之上** + `CodeSignOnCopy`；④ `completeRequest` 后系统立即终止扩展进程，**成功提示的 1 秒延迟必须排在它之前**（本实现正是如此），并补 `viewDidDisappear` → `cancelRequest`（官方示例的已知内存泄漏崩溃）。

**对抗审查与修复（2026-09-14，独立对抗审查官，未改任何文件）**

审查产出 P0×1 / P1×4 / P2×8 / P3×6。**除下表「登记未修」两行外，全部当场修复并取证**：

| 级别 | 问题 | 处置 |
|:---|:---|:---|
| P0 | 登出 / 会话失效 / 换账号都**不清共享容器** → 换账号后 B 分享的链接写进 **A 的账号** | ✅ 已修：`_handleUnauthorized`（`_handleLogout` 转调它）加 `clearToken()`；新增回归用例锁住。这条与主会话自查同时命中 |
| P1 | **扩展真的链上了 Flutter 引擎**：Flutter 的 `Debug/Release.xcconfig` 在 CocoaPods 集成后 include 了 `Pods-Runner.*.xcconfig`，扩展白捡 `OTHER_LDFLAGS` → `flutter_secure_storage.framework` → `Flutter.framework`（`otool -L` 实证） | ✅ 已修：新增扩展专属 `ShareExtension.xcconfig`（只 `#include "../Flutter/Generated.xcconfig"`，不 include Pods）。**取证**：`otool -L` 扩展 binary 的 flutter 相关依赖 **0 个**，且 entitlements 与版本号均未受影响 |
| P1 | Swift 链接正则比 Dart 少一个**全角 `）`（U+FF09）**，「逐字同口径」不成立（带全角括号的分享文本会择出垃圾 URL） | ✅ 已修：补齐字符类 |
| P1 | `NSItemProvider.loadItem` 回调**线程不确定**，却在其中直接改 UIKit | ✅ 已修（主会话自查同时命中）：输入处理一律切回主线程，并加 5 秒兜底（回调不回来不再永远停在「交给阿呆…」） |
| P1 | 已有任务在跑时后端返回 200 但**新链接被丢弃**，扩展却显示「已交给阿呆」 | ✅ 已修：读响应体 `status`——`running` / `needs_confirmation` 时如实说「这条没排上」。**后端丢弃语义本身未改**，已登记 REVIEW P2-分享4 |
| P2 | 清容器失败静默 · 「已就绪」不看到期/被撤销（假就绪）· `_shareStatus` 查不到时不清 · 只读 `attachments` · 测试 mock 缺 `id` · ㉓ 没真点按钮 | ✅ 全部已修（假就绪改用令牌列表的真实 `id`/`expiresAt` 核对；择链接改成多来源 + `attributedContentText` 回退） |
| P2 | 明文令牌落 App Groups（无 Keychain；扩展不受 App 侧 Face ID 门禁约束）· 扩展 API 基址硬编码生产域名 | ⏸ **登记未修**：REVIEW P2-分享2 / P2-分享3（设计取舍与待排项，不擅自改） |
| P3 | 文案「发一把」与按钮名不符 · 签发 label 固定「快捷指令」· `ShareAuth` 注释提到不存在的键 · `_busy` 早复位导致可并发签发两把 | ✅ 已修 |

**⏳ 未完成（唯一）**

- **真机分享链路实测**：`xcrun devicectl list devices` 报 `Adai的iPhone … unavailable`，`device install` 报 `unable to locate a device matching the requested device identifier`（error 1011）。**装机需手机可达**（USB 连接或同一网络且已解锁）。
  装机命令（§9 口径）：`xcrun devicectl device install app --device 0DA85EE6-5FF0-56CD-B647-DB4B27C60D89 build/ios/iphoneos/Runner.app`
- 按验收标准第 3 条，**在真机走通之前不要当它已经能用**（与 2026-09-13 那批同一个教训）。
- 备查：分享面板「第一排」由系统按使用习惯排序，新装的扩展可能先落在「更多」里——不是失败，用一次会自己浮上来。
