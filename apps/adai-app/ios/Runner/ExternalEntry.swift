import Foundation
import UIKit

/// ExternalEntry — 把「App 外面发起的动作」送进 Flutter 的桥（RFC 20260913 外部入口批）。
///
/// 为什么需要它：阿呆的核心动作是**记录**，而记录的门槛一直是「解锁 → 找 App → 点开 → 打字」
/// 四步。付费开发者账号解锁的最低摩擦入口是 App Intents（Siri / 快捷指令 / 聚焦搜索）
/// 与自定义 URL scheme —— 让「嘿 Siri，用阿呆记一笔」或一次点击直接落成一条记录。
///
/// 设计要点（与推送接入同构，刻意保持一致）：
/// 1. **落 UserDefaults 而不是只发通知**：App Intent 在 App 冷启动时可能早于 Flutter 引擎执行，
///    此时没有任何通道可投递；先落盘 + 再发通知（谁先到谁消费），Dart 侧启动时再兜底取一次，
///    两个时序都不会丢。
/// 2. **drain 语义 = 取出即清空**：消费一次就删键，天然幂等——通知路径与 Dart 主动取路径
///    即使同时发生，也只有一个能拿到内容（不会重复记两条）。
/// 3. **不猜内容**：只有确实带了文本才走「直接落成记录」，空文本只把输入框准备好（见 Dart 侧）。
enum ExternalEntry {

    /// MethodChannel 名（Dart 侧 `entry_intent_service.dart` 同名）。
    static let channelName = "adai/entry"

    /// 待处理入口的存储键。
    private static let pendingKey = "adai_pending_entry"

    /// 自定义 scheme（Info.plist 的 CFBundleURLTypes 声明）。
    static let scheme = "adai"

    /// 存一条待处理入口（App Intent 与 URL 两条路都走这里）。
    static func stash(action: String, text: String?, source: String) {
        var entry: [String: Any] = ["action": action, "source": source]
        if let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            entry["text"] = text.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        UserDefaults.standard.set(entry, forKey: pendingKey)
        // 通知同进程的 AppDelegate 立刻投给 Dart（引擎已就绪时走这条，最快）；
        // 引擎未就绪时 AppDelegate 不会消费，条目留在 UserDefaults 等 Dart 启动时取。
        NotificationCenter.default.post(name: .adaiExternalEntry, object: nil)
    }

    /// 取出并清空（消费一次）。无待处理返回 nil。
    static func drain() -> [String: Any]? {
        guard let entry = UserDefaults.standard.dictionary(forKey: pendingKey) else { return nil }
        UserDefaults.standard.removeObject(forKey: pendingKey)
        return entry
    }

    /// 解析并接受 `adai://` URL。
    ///
    /// 支持的形态：
    /// - `adai://record?text=今天减仓了立昂微` → 直接落成一条记录（warm 路径）
    /// - `adai://record`                        → 打开 App 并把记录入口准备好
    ///
    /// @return 是否识别并接受（未识别返回 false，不干扰其它 URL 处理）
    @discardableResult
    static func handle(url: URL) -> Bool {
        guard url.scheme?.lowercased() == scheme else { return false }
        // adai://record → host = "record"；adai:///record → path = "/record"，两种都容忍
        let action = (url.host?.isEmpty == false ? url.host : url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/")))
            ?? "record"
        guard action == "record" else { return false }
        let text = URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?.first(where: { $0.name == "text" })?.value
        stash(action: "record", text: text, source: "url")
        return true
    }
}

extension Notification.Name {
    /// 同进程「有待处理外部入口」信号（AppDelegate 监听后投给 Dart）。
    static let adaiExternalEntry = Notification.Name("adaiExternalEntry")
}
