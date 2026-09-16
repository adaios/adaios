import Foundation

/// ShareAuth — 从 App Groups 容器里取那把限权令牌（RFC 20260914 分享扩展批）。
///
/// **为什么需要它**：扩展是独立进程、独立沙箱，读不到主 App 的任何存储。
/// 主 App 在签发令牌成功后把明文写进共享容器（`Runner/ShareBridge.swift`），
/// 扩展在这里读出来，用于 `POST /api/v1/learn/digest` 的 `Authorization: Bearer …`。
///
/// ⚠️ **键名与 suiteName 是跨 target 契约**：本文件的 `appGroupId` / `tokenKey`
/// 必须与 `Runner/ShareBridge.swift` 的同名字段逐字一致，也必须与两个 entitlements 里的
/// group id 一致。不一致的表现**不是崩溃**，而是「扩展总说没拿到钥匙」——
/// 排查时先核对这三处（与项目里「两份动作表必须同步」同族的坑）。
/// 注：主 App 侧还写了一个 `adai_share_token_id`（供撤销时比对），扩展**只读令牌本身**，
/// 故这里不声明它。
enum ShareAuth {

    static let appGroupId = "group.com.adaiadai.adaiApp"
    static let tokenKey = "adai_share_token"

    /// 令牌明文；空 / 容器不可用 → nil（调用方给人话，不静默失败）。
    ///
    /// P2-分享2（2026-09-16）：令牌已从 App Groups 明文容器升级到**共享 Keychain**——
    /// 先读 Keychain；读不到再退回旧容器（老装机升级前写下的那一份，主 App 负责迁移）。
    static var token: String? {
        if let raw = ShareAuthKeychain.token, !raw.isEmpty { return raw }
        guard let legacy = UserDefaults(suiteName: appGroupId)?.string(forKey: tokenKey) else {
            return nil
        }
        let trimmed = legacy.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

/// ShareAuthKeychain — 扩展侧的**只读** Keychain 访问（REVIEW P2-分享2，2026-09-16）。
///
/// ⚠️ **本文件与 `Runner/ShareBridge.swift` 里的 `ShareKeychain` 是一对必须同步的实现**：
/// 扩展是独立 target，引用不到主 App 的文件，所以同一份约定写了两遍
/// （与「两份动作表必须同步」同族的坑）。逐字一致的四处：
/// `groupSuffix` / `service` / `fallbackTeamId` / `teamIdentifier` 取法；
/// 且两个 entitlements 都要声明 `keychain-access-groups`。
/// 不一致的表现仍是那句人话——**「扩展说没拿到钥匙」**。
///
/// 扩展只读不写：迁移与清理由主 App 负责，免得两个进程同时写同一条 item。
enum ShareAuthKeychain {

    static let groupSuffix = "group.com.adaiadai.adaiApp"
    static let fallbackTeamId = "4G3D37YKSB"
    static let service = "com.adaiadai.adaiApp.share"

    private static var accessGroup: String {
        (teamIdentifier() ?? fallbackTeamId) + "." + groupSuffix
    }

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

    /// 读共享 Keychain 里的令牌（无 item / 读不出来 → nil）。
    static var token: String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: groupSuffix,
            kSecAttrAccessGroup as String: accessGroup,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data,
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let token = obj["token"] as? String
        else { return nil }
        let trimmed = token.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
