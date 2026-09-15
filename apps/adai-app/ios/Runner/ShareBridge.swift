import Flutter
import Foundation

/// ShareBridge — 把「分享面板已就绪」所需的凭据搬进共享容器（RFC 20260914 iOS 分享扩展批）。
///
/// **为什么需要这个桥**：分享扩展（`ShareExtension` target）是**独立进程**，有自己的沙箱，
/// 读不到主 App 的 `UserDefaults` / `shared_preferences`。而扩展要在无人值守下把链接提交给
/// 后端，就必须有那把**限权令牌**（`learn:digest`，只放行整理相关四条端点）。
/// 令牌明文只在签发响应里出现一次（后端只存 SHA-256），所以只能在**签发那一刻**
/// 由主 App 顺手写进 App Groups —— 这就是本文件的全部职责。
///
/// **刻意不做的事**：
/// - 不共享登录会话：会话能改密码、能读全部数据、能看交易。扩展进程只该拿到限权令牌，
///   这是 `TokenScope` 当初立起来的目的（见 `docs/architecture/api-spec.md` §外部工具令牌）。
/// - 不在 App Groups 里放用户数据：容器里只有「令牌明文 + 它的 id + 写入时间」三样。
///
/// ⚠️ **suiteName 是三处契约**：本文件、`ShareExtension/ShareAuth.swift`、
/// 两个 entitlements 文件里的 group id 必须逐字一致。拼错的表现不是崩溃，而是
/// **扩展读不到令牌**（提交时给人话「还没拿到钥匙」）——排查时先核对这三处。
enum ShareBridge {
    /// Dart ↔ Swift 通道名（Dart 侧 `lib/services/share_extension_service.dart` 同名）。
    static let channelName = "adai/share"

    /// App Group id（形态 `group.<主 App Bundle ID>`）。
    static let appGroupId = "group.com.adaiadai.adaiApp"

    /// 共享容器里的键。扩展侧读同样的键（`ShareAuth.swift`）。
    static let tokenKey = "adai_share_token"
    static let tokenIdKey = "adai_share_token_id"
    static let savedAtKey = "adai_share_saved_at"

    /// App Groups 的 UserDefaults。`suiteName` 拼错 / 能力没开时可能返回一个「看着能用但
    /// 不共享」的实例，所以调用方一律配合回读校验与 `containerURL` 判定。
    static func suite() -> UserDefaults? {
        UserDefaults(suiteName: appGroupId)
    }
}

/// 通道处理器：Dart 调用的三个方法（保存 / 清除 / 查状态）。
enum ShareBridgeHandler {

    /// 注册通道并挂上方法表；返回通道对象由 AppDelegate 持有（本项目其它通道同构）。
    static func register(messenger: FlutterBinaryMessenger) -> FlutterMethodChannel {
        let channel = FlutterMethodChannel(name: ShareBridge.channelName, binaryMessenger: messenger)
        channel.setMethodCallHandler { call, result in
            switch call.method {
            case "saveToken":
                result(saveToken(call.arguments))
            case "clearToken":
                result(clearToken())
            case "status":
                result(status())
            default:
                result(FlutterMethodNotImplemented)
            }
        }
        return channel
    }

    // MARK: - 方法实现

    /// 保存令牌明文 + 它的 id（撤销时用来判断「扩展用的是不是这把」）。
    /// 返回 `true` 才算真的写进去了——**回读校验**，不靠「写 API 没报错」下结论。
    private static func saveToken(_ arguments: Any?) -> Bool {
        guard let args = arguments as? [String: Any],
              let token = (args["token"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !token.isEmpty,
              let defaults = ShareBridge.suite()
        else { return false }

        defaults.set(token, forKey: ShareBridge.tokenKey)
        defaults.set(args["id"] as? String, forKey: ShareBridge.tokenIdKey)
        defaults.set(Date().timeIntervalSince1970, forKey: ShareBridge.savedAtKey)

        // 回读校验：写进去但读不出来（Entitlements 没配好 / 容器不可用）必须当场暴露，
        // 否则用户会以为「已就绪」，直到某天在 B站分享时才失败。
        return defaults.string(forKey: ShareBridge.tokenKey) == token
    }

    /// 清除共享容器里的令牌（撤销令牌 / 登出 / 换账号时调用，**不留孤儿钥匙**）。
    private static func clearToken() -> Bool {
        guard let defaults = ShareBridge.suite() else { return false }
        defaults.removeObject(forKey: ShareBridge.tokenKey)
        defaults.removeObject(forKey: ShareBridge.tokenIdKey)
        defaults.removeObject(forKey: ShareBridge.savedAtKey)
        return defaults.string(forKey: ShareBridge.tokenKey) == nil
    }

    /// 查状态：Dart 侧用它决定弹窗里说「分享面板已就绪」还是「还没接上」。
    /// `available=false` 表示 App Group 容器根本不可用（Entitlements/签名问题），
    /// 与「容器可用但里面没令牌」是**两种不同的状态**，UI 要给不同的人话。
    private static func status() -> [String: Any] {
        let container = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: ShareBridge.appGroupId) != nil
        guard let defaults = ShareBridge.suite() else {
            return ["available": false, "hasToken": false]
        }
        let token = defaults.string(forKey: ShareBridge.tokenKey)
        var out: [String: Any] = [
            "available": container,
            "hasToken": (token?.isEmpty == false),
            "appGroup": ShareBridge.appGroupId,
        ]
        if let id = defaults.string(forKey: ShareBridge.tokenIdKey) { out["id"] = id }
        let savedAt = defaults.double(forKey: ShareBridge.savedAtKey)
        if savedAt > 0 { out["savedAt"] = savedAt }
        return out
    }

}
