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
    /// 令牌到期时间（ISO8601 字符串，RFC 20260915 自动续期批）——
    /// 主 App 靠它判断「还剩不到 7 天就换把新的」；扩展不读这个键。
    static let expiresAtKey = "adai_share_expires_at"

    /// 主 App 当前使用的 API 基址（REVIEW P2-分享3，2026-09-16）——
    /// 扩展据此提交分享链接，不再硬编码生产域名（否则连局域网后端的调试构建必然 401）。
    /// 非敏感，放 App Groups 容器即可，不必进 Keychain。
    static let apiBaseUrlKey = "adai_api_base_url"

    /// App Groups 的 UserDefaults。`suiteName` 拼错 / 能力没开时可能返回一个「看着能用但
    /// 不共享」的实例，所以调用方一律配合回读校验与 `containerURL` 判定。
    static func suite() -> UserDefaults? {
        UserDefaults(suiteName: appGroupId)
    }
}

/// 共享 Keychain 读写（REVIEW P2-分享2，2026-09-16）。
///
/// **为什么从 App Groups `UserDefaults` 挪过来**：容器里的明文本就是**明文**，同 group 的
/// 任何 target 都能读，而且随 iTunes/iCloud 设备备份一起走（换机等于把上一台机器上那把
/// 限权钥匙带过去）。Keychain 用 `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`：
/// **不随备份走**、只在设备解锁后可读。
///
/// **老装机兼容**：升级前写进 UserDefaults 的明文仍能读（`migrateLegacyIfNeeded`），
/// 首次读到就迁移进 Keychain 并清掉旧键——用户**不需要**重新签发一次。
///
/// ⚠️ **access group 是跨 target 契约（第五处）**：两个 entitlements 必须声明同一个
/// `keychain-access-groups`（值 `$(AppIdentifierPrefix)group.com.adaiadai.adaiApp`），
/// 否则扩展读不到（`errSecMissingEntitlement`），表现与旧坑一样：**「扩展说没拿到钥匙」**。
enum ShareKeychain {
    /// 与 App Group 同名的 keychain access group（entitlements 里带 `$(AppIdentifierPrefix)` 前缀）。
    static let groupSuffix = "group.com.adaiadai.adaiApp"
    /// Team ID 兜底：本项目只有一个团队（见 docs/deployment/ios-release.md）；换团队要重签，本就要改。
    static let fallbackTeamId = "4G3D37YKSB"
    /// 一条 item 装一个 JSON（token/id/savedAt/expiresAt 一起走，避免四项互相不一致）。
    static let service = "com.adaiadai.adaiApp.share"

    /// 完整 access group = `<TeamID>.<group id>`。
    private static var accessGroup: String {
        (teamIdentifier() ?? fallbackTeamId) + "." + groupSuffix
    }

    /// 从包内 `embedded.mobileprovision` 读 TeamIdentifier（与 AppDelegate 的 aps-environment 同一份权威来源，
    /// 不靠硬编码猜）。
    private static func teamIdentifier() -> String? {
        guard let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
              let data = try? Data(contentsOf: url),
              let start = data.range(of: "<?xml".data(using: .utf8)!),
              let end = data.range(of: "</plist>".data(using: .utf8)!),
              let plist = try? PropertyListSerialization.propertyList(
                  from: data.subdata(in: start.lowerBound..<end.upperBound),
                  options: [], format: nil) as? [String: Any],
              let teams = plist["TeamIdentifier"] as? [String]
        else { return nil }
        return teams.first
    }

    private static func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: groupSuffix,
            kSecAttrAccessGroup as String: accessGroup,
        ]
    }

    /// 读整条 JSON（无 item / 读不出来 → nil）。
    static func read() -> [String: Any]? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data,
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        return obj
    }

    /// 覆盖写（先删后加，避免 `errSecDuplicateItem`），成功才返回 true。
    @discardableResult
    static func write(_ payload: [String: Any]) -> Bool {
        guard let data = try? JSONSerialization.data(withJSONObject: payload) else { return false }
        SecItemDelete(baseQuery() as CFDictionary)
        var add = baseQuery()
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    @discardableResult
    static func delete() -> Bool {
        let status = SecItemDelete(baseQuery() as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }

    /// 读**旧位置**（App Groups 容器里的明文）。读到就迁移进 Keychain 并清掉旧键。
    /// 写不进 Keychain 也照样返回——老装机不至于当场失去功能。
    static func migrateLegacyIfNeeded() -> [String: Any]? {
        guard let payload = legacyPayload() else { return nil }
        if write(payload) {
            clearLegacy()
        }
        return payload
    }

    /// 只读旧位置，不做迁移（扩展侧用：迁移由主 App 负责，免得两个进程同时写）。
    static func legacyPayload() -> [String: Any]? {
        guard let defaults = ShareBridge.suite(),
              let token = defaults.string(forKey: ShareBridge.tokenKey), !token.isEmpty
        else { return nil }
        var payload: [String: Any] = ["token": token]
        if let id = defaults.string(forKey: ShareBridge.tokenIdKey) { payload["id"] = id }
        let savedAt = defaults.double(forKey: ShareBridge.savedAtKey)
        if savedAt > 0 { payload["savedAt"] = savedAt }
        if let expiresAt = defaults.string(forKey: ShareBridge.expiresAtKey) { payload["expiresAt"] = expiresAt }
        return payload
    }

    /// 清掉旧位置的明文（迁移完成 / 撤销 / 登出时都要做——容器里不该再留明文）。
    static func clearLegacy() {
        guard let defaults = ShareBridge.suite() else { return }
        defaults.removeObject(forKey: ShareBridge.tokenKey)
        defaults.removeObject(forKey: ShareBridge.tokenIdKey)
        defaults.removeObject(forKey: ShareBridge.savedAtKey)
        defaults.removeObject(forKey: ShareBridge.expiresAtKey)
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
    ///
    /// P2-分享2（2026-09-16）：明文写**共享 Keychain**，不再落 App Groups 明文容器；
    /// 写完回读成功后顺手清掉旧位置残留的明文。
    private static func saveToken(_ arguments: Any?) -> Bool {
        guard let args = arguments as? [String: Any],
              let token = (args["token"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !token.isEmpty
        else { return false }

        var payload: [String: Any] = [
            "token": token,
            "savedAt": Date().timeIntervalSince1970,
        ]
        if let id = args["id"] as? String { payload["id"] = id }
        // 到期时间：空值**不写**——否则上一把钥匙的过期时间会留下来，骗过自动续期的判断
        //（以为还没临期，实际新钥匙没有记录）。
        if let expiresAt = args["expiresAt"] as? String, !expiresAt.isEmpty {
            payload["expiresAt"] = expiresAt
        }

        // REVIEW P2-分享3（2026-09-16）：顺手把主 App 当前用的 API 基址写进共享容器——
        // 扩展据此提交分享链接，不再硬编码生产域名（调试构建立即不再 401）。
        if let base = args["apiBaseUrl"] as? String, !base.isEmpty,
           let defaults = ShareBridge.suite() {
            defaults.set(base, forKey: ShareBridge.apiBaseUrlKey)
        }

        guard ShareKeychain.write(payload) else { return false }
        // 回读校验：写进去但读不出来（access group 没配对 / entitlements 缺失）必须当场暴露，
        // 否则用户会以为「已就绪」，直到某天在 B站分享时才失败。
        let ok = (ShareKeychain.read()?["token"] as? String) == token
        if ok { ShareKeychain.clearLegacy() }
        return ok
    }

    /// 清除共享的令牌（撤销令牌 / 登出 / 换账号时调用，**不留孤儿钥匙**）。
    /// Keychain 与旧容器两处都清——只清一处就是「用户以为撤掉了，扩展还能用」。
    private static func clearToken() -> Bool {
        ShareKeychain.delete()
        ShareKeychain.clearLegacy()
        return ShareKeychain.read() == nil
    }

    /// 查状态：Dart 侧用它决定弹窗里说「分享面板已就绪」还是「还没接上」。
    /// `available=false` 表示共享能力根本不可用（App Group 容器不可用且 Keychain 里也没有），
    /// 与「可用但里面没令牌」是**两种不同的状态**，UI 要给不同的人话。
    private static func status() -> [String: Any] {
        let container = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: ShareBridge.appGroupId) != nil
        // 先读新位置（Keychain）；读不到再看旧容器——老装机读到时顺手迁移进 Keychain。
        var payload = ShareKeychain.read()
        if payload == nil { payload = ShareKeychain.migrateLegacyIfNeeded() }
        // 如实标注钥匙到底躺在哪：迁移失败时仍是 legacy，不能让排查的人以为已经升级完成。
        let storage: String = payload == nil ? "none" : (ShareKeychain.read() != nil ? "keychain" : "legacy")
        var out: [String: Any] = [
            "available": container || payload != nil,
            "hasToken": ((payload?["token"] as? String)?.isEmpty == false),
            "appGroup": ShareBridge.appGroupId,
            // P2-分享2：UI/排查能看出这把钥匙存在哪儿（keychain = 已升级；legacy = 还在旧容器）
            "storage": storage,
        ]
        if let id = payload?["id"] as? String { out["id"] = id }
        if let savedAt = payload?["savedAt"] as? Double, savedAt > 0 { out["savedAt"] = savedAt }
        if let expiresAt = payload?["expiresAt"] as? String { out["expiresAt"] = expiresAt }
        return out
    }

}
