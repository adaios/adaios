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
///
/// ⚠️ **动作表是两份**：本枚举与 Dart 侧 `ExternalEntryAction`（`lib/services/entry_intent_service.dart`）
/// 是一份契约的两端，**必须同步增删**——只加一侧会让新入口「原生发得出去、Dart 认不出来」，
/// 表现为点了没反应且日志干净（同族坑见 pitfalls「条件导出的两份实现 API 面不一致」）。
enum ExternalEntryAction: String, CaseIterable {

    /// 记一笔：内容直接落成一条记录（Siri / 快捷指令 / `adai://record`）。
    case record

    /// 整理：把链接交给 learn 流水线（抓取 → 按需转写 → 结构化学习卡片；`adai://digest`）。
    ///
    /// **为什么要独立成一个动作**：它与 `record` 是**动作语义**之别，不是内容特征之别——
    /// record 是「把这句话记下来」，digest 是「去把这个链接读明白」。此前没有这个动作，
    /// 只能退而求其次让用户把「整理」二字写进文本、靠 Dart 侧的关键词正则去猜；
    /// 2026-09-13 真机实测：用户在快捷指令里把共享链接直接接进「记一笔」动作，
    /// 文本里没有触发词 → **静默落成一条普通记录，并没有整理**。
    /// 让「整理」成为一个动作，这类「少说两个字就走错分支」的失败就不再可能。
    case digest
}

enum ExternalEntry {

    /// MethodChannel 名（Dart 侧 `entry_intent_service.dart` 同名）。
    static let channelName = "adai/entry"

    /// 待处理入口的存储键。
    private static let pendingKey = "adai_pending_entry"

    /// 自定义 scheme（Info.plist 的 CFBundleURLTypes 声明）。
    static let scheme = "adai"

    /// 存一条待处理入口（App Intent 与 URL 两条路都走这里）。
    static func stash(action: ExternalEntryAction, text: String?, source: String) {
        var entry: [String: Any] = ["action": action.rawValue, "source": source]
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

    // MARK: - URL 去重（实测必需，见下）

    /// 最近一次已接受的 URL 动作，用于去重。
    private static var lastURLAction: ExternalEntryAction?
    private static var lastURLText: String?
    private static var lastURLAt: Date?

    /// 同一 (action, text) 在该窗口内视为**同一次外部动作**。
    private static let urlDedupeWindow: TimeInterval = 3

    /// 解析并接受 `adai://` URL。
    ///
    /// 支持的形态（动作取自 [ExternalEntryAction]，**新增动作不必改本方法**）：
    /// - `adai://record?text=今天减仓了立昂微` → 直接落成一条记录
    /// - `adai://record`                        → 打开 App 并把记录入口准备好
    /// - `adai://digest?url=https%3A%2F%2Fb23.tv%2Fx` → 让阿呆去整理这个链接
    ///
    /// **为什么要去重**：2026-09-13 真机实测——**冷启动**时 iOS 会把「启动用的那个 URL」
    /// 同时经 `scene(_:willConnectTo:options:)` 与 `scene(_:openURLContexts:)` 送达，
    /// 于是同一次唤起**落了两条一模一样的记录**（实测两条相隔 516ms）。
    /// 这是 OS 的送达方式，不是用户动作，所以在**入口处**去重（而不是在 `stash` 里）：
    /// App Intent 的 `stash` 是用户亲口说的，哪怕内容相同也必须每次都记。
    /// 窗口 3 秒——人不可能在 3 秒内用同一条链接说两遍一模一样的话，
    /// 但两次送达必然在 1 秒内。
    ///
    /// @return 是否识别并接受（未识别返回 false，不干扰其它 URL 处理）
    @discardableResult
    static func handle(url: URL, source: String) -> Bool {
        guard url.scheme?.lowercased() == scheme else { return false }
        // adai://record → host = "record"；adai:///record → path = "/record"，两种都容忍
        let rawAction = (url.host?.isEmpty == false ? url.host : url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/")))
            ?? ExternalEntryAction.record.rawValue
        // 认不出来就**不认领**（返回 false，不干扰别的 URL 处理）。
        // 不认识的 action 一律回落成 record 会把「整理」悄悄变成「记一条」——正是本批要根除的失败模式。
        guard let action = ExternalEntryAction(rawValue: rawAction) else { return false }
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems
        // digest 的主参数叫 `url`（语义直白）；`text` 作为别名一并接受，
        // 这样同一条快捷指令把参数名写成 text 也不会静默丢内容。
        let text = items?.first(where: { $0.name == "url" })?.value
            ?? items?.first(where: { $0.name == "text" })?.value

        // 场景回调都在主线程 → 这几个静态变量无需加锁
        let now = Date()
        if action == lastURLAction, text == lastURLText,
           let last = lastURLAt, now.timeIntervalSince(last) < urlDedupeWindow {
            return true // 同一次外部动作的第二次送达，静默忽略
        }
        lastURLAction = action
        lastURLText = text
        lastURLAt = now

        stash(action: action, text: text, source: source)
        return true
    }
}

extension Notification.Name {
    /// 同进程「有待处理外部入口」信号（AppDelegate 监听后投给 Dart）。
    static let adaiExternalEntry = Notification.Name("adaiExternalEntry")
}
