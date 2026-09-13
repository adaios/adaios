import AppIntents
import Foundation

/// RecordIntent — 「记一笔」App Intent（RFC 20260913 外部入口批，iOS 16+）。
///
/// 让记录这件事从「解锁 → 找 App → 点开 → 打字」四步缩成**一句话**：
/// - 「嘿 Siri，用阿呆记一笔」（`AppShortcutsProvider` 提供的短语，装好即可用，无需手动配捷径）
/// - 快捷指令 App 里选「记一笔」动作（App Intent 会自动出现在那里）
/// - 聚焦搜索 / 侧边按钮 等系统入口
///
/// **为什么 `openAppWhenRun = true`**：记录要经过阿呆的理解与落盘（会生成一张 Feed 卡），
/// 这依赖 App 的会话与后端；让系统把 App 拉起来再处理，比在后台硬塞一条更符合产品语义，
/// 也让用户当场看见「记下了」而不是怀疑有没有成功。
///
/// **投递方式**：`perform()` 把内容交给 `ExternalEntry`（落 UserDefaults + 发同进程通知），
/// 由 AppDelegate / Dart 侧消费。不直接调 Flutter 通道——冷启动时引擎可能还没起来，
/// 落了盘才不怕时序（详见 `ExternalEntry` 注释）。
@available(iOS 16.0, *)
struct RecordIntent: AppIntent {

    static var title: LocalizedStringResource = "阿呆阿呆"

    static var description = IntentDescription("把一句话交给阿呆阿呆，它会替你理解、归类、留档。")

    /// 让系统先把阿呆拉到前台——见类注释的取舍说明。
    static var openAppWhenRun: Bool = true

    @Parameter(title: "内容", requestValueDialog: "要记什么？")
    var text: String

    static var parameterSummary: some ParameterSummary {
        Summary("让阿呆阿呆记下 \(\.$text)")
    }

    func perform() async throws -> some IntentResult {
        ExternalEntry.stash(action: .record, text: text, source: "siri")
        return .result()
    }
}

/// AdaiAppShortcuts — 免配置的 Siri 短语（iOS 16+）。
///
/// **命名（2026-09-13 用户拍板）**：这个动作就叫「**阿呆阿呆**」——不要「阿呆记一笔」那种
/// 把 App 名和动作名拼起来的叫法。动作名、短语、shortTitle 一律用「阿呆阿呆」。
///
/// 短语里必须含 `\(.applicationName)`（系统要求，用于消歧）。三个变体从短到长：
/// 只叫名字、「用」+名字、「让」+名字+记下——**只叫名字那条最自然（「嘿 Siri，阿呆阿呆」），
/// 但风险是与「打开 App」的默认语义撞车**（系统对「App 名」的默认动作是启动 App）；
/// 因此同时给带动词的两条兜底，用户挑顺口的用。
@available(iOS 16.0, *)
struct AdaiAppShortcuts: AppShortcutsProvider {

    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: RecordIntent(),
            phrases: [
                "用\(.applicationName)",
                "\(.applicationName)",
                "让\(.applicationName)记下",
            ],
            shortTitle: "阿呆阿呆",
            systemImageName: "square.and.pencil"
        )
    }
}
