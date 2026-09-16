import Flutter
import UIKit
import UserNotifications

/// 常量：Dart ↔ Swift 通道名（Swift 侧单一来源，避免字面量散落）。
enum PushBridge {
    static let channelName = "adai/push"
    /// 兜底 aps-environment 取值：侧载（development 签名）走沙箱网关。
    static let defaultEnvironment = "sandbox"
}
/// AppDelegate — 远程推送接入（RFC 20260913 APNs 批）。
///
/// 背景：付费开发者账号之前 App 拿不到 `aps-environment` 能力，后端 10 种推送
/// （盘前/买点/止损/行情异动/收盘小结/复习提醒…）只能借第三方 App（Bark）弹通知，
/// 通知上写着别人的名字、点击也回不到阿呆。本文件把「阿呆自己弹通知」这条路打通：
/// 申请通知权限 → 拿 APNs deviceToken → 经 MethodChannel 交给 Dart 层上报后端
/// （`POST /api/v1/push/devices` → 后端 `ApnsPushChannel` 直连 APNs）。
///
/// 与 Dart 的分工：**权限时机与上报由 Dart 决定**（登录后、可测、能提示「去设置开启」），
/// Swift 只负责系统交互与缓存 token。方法：
/// - `requestPermission` 申请权限（已决定过则复用系统结论，不会重复弹窗）并触发注册
/// - `getStatus`         返回 {authorization, token, environment, registered, lastError, pendingTap}
/// - `openSettings`      跳系统设置（用户点过「不允许」后的补救入口）
///
/// 回调（Swift → Dart）：`onToken`、`onRegisterFailed`、`onNotificationTap`。
@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate {

    /// Dart ↔ Swift 通道；引擎初始化后才有值（冷启动由通知唤起时可能晚于 didReceive）。
    private var pushChannel: FlutterMethodChannel?
    /// 最近的 APNs deviceToken（十六进制）；未注册成功为 nil。
    private var deviceToken: String?
    /// 注册失败原因（fail-visible：让 Dart 能把「为什么收不到通知」显示出来）。
    private var registerError: String?
    /// 通道就绪前收到的通知点击（冷启动场景）——等 Dart 调用 getStatus 时补投。
    private var pendingTap: String?
    /// 外部入口通道（Siri / 快捷指令 / `adai://` URL，RFC 20260913 外部入口批）。
    private var entryChannel: FlutterMethodChannel?
    /// 分享扩展凭据通道（RFC 20260914 分享扩展批）：把限权令牌写进 App Groups 共享容器，
    /// 好让 `ShareExtension` 在**自己的进程里**提交链接（不拉起本 App）。
    private var shareChannel: FlutterMethodChannel?

    override func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        // 必须在启动早期设好 delegate：冷启动由通知点击唤起时，didReceive 可能早于
        // Flutter 引擎初始化（那时 pushChannel 还是 nil）。
        // 注：FlutterAppDelegate 本身也遵循 UNUserNotificationCenterDelegate（头文件里不显示），
        // 它若已把 center.delegate 设成自己，这里赋的 self 是同一个对象，覆盖的是其中一组
        // 方法实现（见类体末尾的 override 说明），不构成冲突。
        UNUserNotificationCenter.current().delegate = self
        // 外部入口（App Intent / URL）：有人存了待处理入口就投给 Dart。
        // 引擎未就绪时**不消费**（条目留在 UserDefaults，等 Dart 启动时主动取）——
        // 若此时就把 drain 掉，内容会丢在没人接的地方。
        NotificationCenter.default.addObserver(
            self, selector: #selector(handleExternalEntry),
            name: .adaiExternalEntry, object: nil)
        return super.application(application, didFinishLaunchingWithOptions: launchOptions)
    }

    func didInitializeImplicitFlutterEngine(_ engineBridge: FlutterImplicitEngineBridge) {
        GeneratedPluginRegistrant.register(with: engineBridge.pluginRegistry)

        let channel = FlutterMethodChannel(
            name: PushBridge.channelName,
            binaryMessenger: engineBridge.applicationRegistrar.messenger()
        )
        channel.setMethodCallHandler { [weak self] call, result in
            self?.handle(call, result: result)
        }
        pushChannel = channel

        // 外部入口通道（Siri / 快捷指令 / adai:// URL）
        let entry = FlutterMethodChannel(
            name: ExternalEntry.channelName,
            binaryMessenger: engineBridge.applicationRegistrar.messenger()
        )
        entry.setMethodCallHandler { [weak self] call, result in
            switch call.method {
            case "takePendingEntry":
                result(self?.takePendingEntry())
            default:
                result(FlutterMethodNotImplemented)
            }
        }
        entryChannel = entry

        // 分享扩展凭据通道（RFC 20260914）：Dart 在**签发 / 撤销令牌**时把明文写进 / 清出
        // App Groups 共享容器。为什么必须由 App 来写——令牌明文只在签发响应里出现一次
        // （后端只存 SHA-256），过了那一刻谁也还原不出来，所以只能顺手搬进共享容器。
        shareChannel = ShareBridgeHandler.register(
            messenger: engineBridge.applicationRegistrar.messenger())

        // 引擎刚就绪：若冷启动期间已排了入口（Siri 拉起 App / URL 唤起），立刻补投
        handleExternalEntry()
    }

    // MARK: - 外部入口（Siri / 快捷指令 / adai:// URL）

    /// 有待处理入口且通道已就绪 → 把**整条队列**逐条投给 Dart；否则原样留在 UserDefaults
    /// 等 Dart 来取（引擎未就绪时消费掉就等于丢在没人接的地方）。
    ///
    /// 队列（REVIEW P1-入口1）：冷启动连发两条时单槽 drain 会把第一条静默吞掉，
    /// 所以这里按 FIFO 逐条 `invokeMethod`；Dart 侧入队并逐条消费。
    @objc private func handleExternalEntry() {
        guard let channel = entryChannel else { return }
        let entries = ExternalEntry.drain()
        guard !entries.isEmpty else { return }
        DispatchQueue.main.async {
            for entry in entries {
                channel.invokeMethod("onEntry", arguments: entry)
            }
        }
    }

    /// Dart 主动取一次（冷启动兜底：Dart 起来时通道才建好，此前无人消费）。
    /// 返回整条队列（可能为空数组）——单条形态已由 Dart 侧兼容。
    private func takePendingEntry() -> [[String: Any]] {
        ExternalEntry.drain()
    }

    // MARK: - Dart → Swift

    private func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "requestPermission":
            requestPermission(result: result)
        case "getStatus":
            result(notificationStatus(flushTap: true))
        case "openSettings":
            openSettings(result: result)
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    private func requestPermission(result: @escaping FlutterResult) {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { [weak self] granted, error in
            if let error = error {
                self?.registerError = "申请通知权限失败：\(error.localizedDescription)"
            }
            if granted {
                // 必须在主线程触发；token 随后由
                // didRegisterForRemoteNotificationsWithDeviceToken 异步回调
                DispatchQueue.main.async {
                    UIApplication.shared.registerForRemoteNotifications()
                }
            }
            DispatchQueue.main.async { result(granted) }
        }
    }

    private func openSettings(result: @escaping FlutterResult) {
        guard let url = URL(string: UIApplication.openSettingsURLString) else {
            result(false)
            return
        }
        UIApplication.shared.open(url, options: [:]) { ok in result(ok) }
    }

    private func notificationStatus(flushTap: Bool) -> [String: Any?] {
        var authorization = "notDetermined"
        // getNotificationSettings 是异步 API 而本方法要同步返回：用一个极短超时的信号量等它。
        // 这是本地 IPC（亚毫秒级），且 platform channel 的 Dart 回调不在主线程，
        // 不会阻塞 UI；超时兜底为 notDetermined，不挂死。
        let semaphore = DispatchSemaphore(value: 0)
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            switch settings.authorizationStatus {
            case .authorized: authorization = "authorized"
            case .denied: authorization = "denied"
            case .provisional: authorization = "provisional"
            case .ephemeral: authorization = "ephemeral"
            default: authorization = "notDetermined"
            }
            semaphore.signal()
        }
        _ = semaphore.wait(timeout: .now() + 2)

        let tap = flushTap ? pendingTap : nil
        if flushTap { pendingTap = nil }

        return [
            "authorization": authorization,
            "token": deviceToken,
            "environment": apnsEnvironment(),
            "registered": deviceToken != nil,
            "lastError": registerError,
            "pendingTap": tap,
        ]
    }

    /// 读取本包真实的 `aps-environment`（决定送 sandbox 还是 production 网关）。
    ///
    /// 为什么不能简单用 `#if DEBUG`：本项目装机用的是 `flutter build ios --release` +
    /// development 描述文件——**release 优化 + 沙箱环境**，`#if DEBUG` 会判成生产网关，
    /// 结果是每次推送都被 APNs 回 BadDeviceToken 静默丢弃。
    /// 因此直接读签名打进包里的 embedded.mobileprovision（entitlements 的权威来源）。
    private func apnsEnvironment() -> String {
        guard let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
              let data = try? Data(contentsOf: url),
              let start = data.range(of: "<?xml".data(using: .utf8)!),
              let end = data.range(of: "</plist>".data(using: .utf8)!),
              let plist = try? PropertyListSerialization.propertyList(
                  from: data.subdata(in: start.lowerBound..<end.upperBound),
                  options: [], format: nil) as? [String: Any],
              let entitlements = plist["Entitlements"] as? [String: Any],
              let env = entitlements["aps-environment"] as? String
        else {
            return PushBridge.defaultEnvironment
        }
        return env == "production" ? "production" : "sandbox"
    }

    // MARK: - Swift → Dart

    private func notifyDart(_ method: String, _ arguments: Any?) {
        guard let channel = pushChannel else { return }
        DispatchQueue.main.async {
            channel.invokeMethod(method, arguments: arguments)
        }
    }

    // MARK: - UNUserNotificationCenterDelegate
    //
    // 注意：FlutterAppDelegate **本身已遵循** UNUserNotificationCenterDelegate
    // （头文件里看不到，编译器会报 "Redundant conformance" —— 2026-09-13 实测踩到），
    // 所以这四个方法必须写成 override 放在类体内，不能放在 extension 里。

    /// 前台收到通知也要显示（iOS 默认前台不弹）。
    ///
    /// 阿呆推的是「止损预警 / 收盘小结」这类要立刻看到的信息，App 开着反而静默会很怪。
    /// **刻意不调 super**：super 的实现是把回调转发给「注册为通知类插件的 FlutterPlugin」，
    /// 本项目没有任何通知插件（pubspec 无 flutter_local_notifications / firebase_messaging）。
    /// 将来若引入通知类插件，这里要改成「先 super 转发、未处理再自己 completionHandler」，
    /// 否则插件会收不到前台通知。
    override func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound, .badge])
    }

    /// 用户点击通知：把类型（threadIdentifier = `adai-<type>`）交给 Dart，由 Dart 决定刷新/跳转。
    /// 同样不调 super（理由见上）。
    override func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let content = response.notification.request.content
        let type = content.threadIdentifier.isEmpty ? content.title : content.threadIdentifier
        // REVIEW P2-APNs1（2026-09-17）：把深链一起交给 Dart——<type>|<deepLink>（deepLink 可空）。
        // 后端在 payload 的 root 级放 adaiDeepLink（aps 之外），这里取出即可。
        // 编码成字符串而不是字典，是为了让 pendingTap（冷启动补投）那条既有通路零改动。
        let deep = (content.userInfo["adaiDeepLink"] as? String) ?? ""
        let payload = deep.isEmpty ? type : "\(type)|\(deep)"
        if pushChannel == nil {
            // 冷启动：通道还没建好，先缓存，等 Dart 首次 getStatus 时补投
            pendingTap = payload
        } else {
            notifyDart("onNotificationTap", payload)
        }
        completionHandler()
    }

    /// 注册成功：拿到 deviceToken，立刻推给 Dart 上报（不等 Dart 轮询）。
    override func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        let hex = deviceToken.map { String(format: "%02x", $0) }.joined()
        self.deviceToken = hex
        self.registerError = nil
        notifyDart("onToken", ["token": hex, "environment": apnsEnvironment()])
    }

    /// 注册失败：原因交给 Dart 显示（fail-visible，别让用户对着「没有通知」猜）。
    /// 最常见的两种：描述文件没有 aps-environment 能力；设备到 APNs 网络不通。
    override func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        self.registerError = error.localizedDescription
        notifyDart("onRegisterFailed", error.localizedDescription)
    }
}
