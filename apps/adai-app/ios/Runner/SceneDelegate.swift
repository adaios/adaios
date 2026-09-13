import Flutter
import UIKit

/// SceneDelegate — 场景生命周期 + `adai://` URL 入口（RFC 20260913 外部入口批）。
///
/// 本 App 走 scene 生命周期（Info.plist 的 `UIApplicationSceneManifest`），
/// 因此 `adai://` 链接**不会**走到 AppDelegate 的 `application(_:open:options:)`，
/// 而是由 SceneDelegate 接收——这是 iOS 的既定分工，写错地方就会「点了没反应、也不报错」。
///
/// 两条路径都要接，缺一条就会在某种状态下静默失效：
/// - **warm**（App 已在后台/前台）→ `scene(_:openURLContexts:)`
/// - **cold**（App 没在跑，点链接才起来）→ `scene(_:willConnectTo:options:)` 的
///   `connectionOptions.urlContexts`。**冷启动这条最容易被漏**：App 确实起来了，
///   但文本悄悄丢了，用户看到的是一个空的记录框。
///
/// ⚠️ **必须调 `super`**：`FlutterSceneDelegate` 的实现（头文件里没声明，但 Swift 可见并可覆写——
/// 2026-09-13 实测：不加 `override` 会编译报错）负责把事件转发给「注册为场景生命周期插件的
/// FlutterPlugin」以及 Flutter 自己的引擎装配。跳过 super 是会出事的（尤其 `willConnectTo`）。
class SceneDelegate: FlutterSceneDelegate {

    /// 冷启动：URL 藏在连接选项里。
    override func scene(_ scene: UIScene, willConnectTo session: UISceneSession,
                        options connectionOptions: UIScene.ConnectionOptions) {
        for context in connectionOptions.urlContexts {
            _ = ExternalEntry.handle(url: context.url)
        }
        super.scene(scene, willConnectTo: session, options: connectionOptions)
    }

    /// 运行中（后台/前台）被 URL 唤起。
    override func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        for context in URLContexts {
            _ = ExternalEntry.handle(url: context.url)
        }
        super.scene(scene, openURLContexts: URLContexts)
    }
}
