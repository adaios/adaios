import AppIntents
import Foundation

/// DigestIntent — 「阿呆阿呆整理」App Intent（2026-09-13 分享与整理批，iOS 16+）。
///
/// 与 `RecordIntent`（记一笔）的分工是**动作语义**之别，不是内容特征之别：
/// - **记一笔**：把一句话记下来（内容本身就是终点）
/// - **整理**：把一个**链接**交给阿呆去读（抓取 → 按需转写 → 结构化学习卡片，内容只是起点）
///
/// **为什么必须单独成一个动作**：learn 的对话流入口此前靠关键词正则识别
/// （Dart 侧 `_isLearnTrigger`：含链接 **且** 含「整理/消化/学习留存/归档」）。
/// 2026-09-13 真机实测：用户在快捷指令里把「共享表单的链接」直接接进「记一笔」动作，
/// 文本里没有触发词 → **静默落成一条普通记录，并没有整理**。
/// 把「整理」变成一个动作，这类失败就不会再发生（不再依赖用户少说两个字）。
///
/// **`openAppWhenRun = true` 的取舍（如实记录，不粉饰）**：这会拉起 App，而用户对「分享」
/// 的期望是**看到就丢、不被打断**——这正是本次实地反馈的痛点。真正的解是 Share Extension
/// （扩展在自己进程里把链接投给后端，主 App 不必被拉起，也不占用户的操作流）。
/// 本动作是那条路的前置地基（动作表 + 投递桥复用），**不是终点**，别把它当答案。
///
/// **为什么不加进 `AdaiAppShortcuts`（Siri 免配置短语）**：整理的对象是链接，
/// 用嘴念一条 URL 不现实；加进去只会给 Siri 添噪声。它出现在「快捷指令」App 的动作库里
/// 就够用了——共享表单那一步由快捷指令负责接收输入。
@available(iOS 16.0, *)
struct DigestIntent: AppIntent {

    static var title: LocalizedStringResource = "阿呆阿呆整理"

    static var description = IntentDescription("把一个链接交给阿呆阿呆，它去读内容、整理成学习卡片。")

    /// 见类注释的取舍说明：这是「跳 App」的根源，也是 Share Extension 要取代它的理由。
    static var openAppWhenRun: Bool = true

    /// 用 String 而不是 URL 类型：分享出来的链接常夹在口令文本里
    /// （例如抖音那种「8.88 复制打开抖音… https://v.douyin.com/xxx/」），
    /// URL 参数的强校验会直接拒绝这类输入；Dart 侧再用正则把链接择出来。
    @Parameter(title: "链接", requestValueDialog: "要整理哪个链接？")
    var url: String

    static var parameterSummary: some ParameterSummary {
        Summary("让阿呆阿呆整理 \(\.$url)")
    }

    func perform() async throws -> some IntentResult {
        ExternalEntry.stash(action: .digest, text: url, source: "shortcut")
        return .result()
    }
}
