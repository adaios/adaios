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
    static var token: String? {
        guard let raw = UserDefaults(suiteName: appGroupId)?.string(forKey: tokenKey) else {
            return nil
        }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
