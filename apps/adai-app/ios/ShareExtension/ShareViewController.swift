import UIKit
import UniformTypeIdentifiers

/// ShareViewController — 分享扩展主界面（RFC 20260914 iOS 分享扩展批）。
///
/// **它在系统里的位置**：B站/抖音等 App 点「分享」→ 分享面板第一排出现「阿呆阿呆」→
/// 用户点它 → **本控制器在扩展自己的进程里**跑 → 把链接 `POST /api/v1/learn/digest` →
/// 显示「已交给阿呆，正在读…」→ 约 1 秒后自动关闭。
///
/// **为什么不拉起主 App（本批的全部意义）**：
/// 上一批的 `DigestIntent` 是 `openAppWhenRun = true`，分享一次必跳一次 App，
/// 而用户的期望是**看到就丢、不被打断**。扩展是独立进程，提交完全在这里发生，
/// 主 App 全程不被唤醒——这是快捷指令与 App Intent 都给不了的能力。
///
/// **为什么不需要等抓取/转写**：`/learn/digest` 是**提交式**端点（立即返回
/// `status=pending/running`），抓取、按需转写、结构化都在后端后台线程跑。
/// 扩展有严格的完成时限，正因如此它才承载得起这条流水线——发完即可关。
///
/// **为什么这里不再要一次确认**：用户在分享面板里**主动点了阿呆阿呆**，这本身就是确认；
/// 这与 `adai://digest?url=…` 那种「任何 App/网页都能无凭据触发」的入口不同——
/// 后者在 App 内保留一次点击确认（REVIEW P1-安全1），本扩展走的是限权令牌、且有明确用户动作。
///
/// **刻意不做**：付费转写确认（长视频要花钱那一步仍回 App 点头，App 已有恢复入口）、
/// 「记一笔」（分享 = 去把这个链接读明白，职责单一）、批量多选。
final class ShareViewController: UIViewController {

    // MARK: - 常量

    /// 整理端点。域名读 Info.plist 的 `AdaiApiBaseUrl`——与项目既有口径一致
    /// （换服务器永不重编译，见 docs/deployment/backend-deployment.md §9）。
    private static let digestPath = "/api/v1/learn/digest"

    /// 从分享文本里择链接。**与 Dart 侧 `main_page._httpLinkRe` 逐字同口径**
    /// （`RegExp(r'https?://[^\s，。；、）)】」]+')`）：B站/抖音分享出来的常常是
    /// 「夹着口令的一整段文本」，不能当纯 URL 用。
    /// ⚠️ 两份要一起改——只改一侧会出现「App 里能整理、分享进来却说找不到链接」。
    private static let linkPattern = #"https?://[^\s，。；、）)】」]+"#

    // MARK: - UI

    private let spinner = UIActivityIndicatorView(style: .medium)
    private let symbolLabel = UILabel()
    private let titleLabel = UILabel()
    private let detailLabel = UILabel()
    private let closeButton = UIButton(type: .system)

    /// 防重复收尾：成功分支的延迟自动关闭与用户点「知道了」可能撞在一起。
    private var finished = false

    /// 「已经处理过输入」——超时兜底与 `NSItemProvider` 回调都可能先到，
    /// 谁先到谁干活，后到的不再改 UI（否则会「成功之后又弹一句失败」）。
    private var handledInput = false

    // MARK: - 生命周期

    override func viewDidLoad() {
        super.viewDidLoad()
        buildUI()
        start()
    }

    /// 收尾没走完就被关掉 → 补一次 `cancelRequest`。
    ///
    /// **这不是可有可无的清理**：扩展被 dismiss 后控制器若仍活着，反复打开会耗尽扩展内存
    /// 并崩溃（Flutter 官方分享扩展示例的已确认缺陷，2026-09-14 调研核实；官方文档的修正
    /// 做法就是在这里补 `cancelRequest`）。
    /// ⚠️ error 的 **domain 必须是 bundle id**——用别的字符串会 crash（同族坑已在调研中记）。
    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        guard !finished else { return }
        finished = true
        let domain = Bundle.main.bundleIdentifier ?? "com.adaiadai.adaiApp.ShareExtension"
        extensionContext?.cancelRequest(withError: NSError(domain: domain, code: 0))
    }

    // MARK: - 主流程

    /// 取分享内容 → 择链接 → 提交。
    private func start() {
        setSubmitting()

        // **兜底**：`NSItemProvider` 的回调不保证一定回来（对方 App 给的 provider 出问题时），
        // 没有这条就会永远停在「交给阿呆…」，用户只能干等系统把扩展杀掉。
        // 5 秒没拿到内容就如实说失败——**宁可说没读到，也不要装作还在读**。
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
            guard let self, !self.handledInput else { return }
            self.handledInput = true
            self.setFailure("没能从分享内容里读到东西——再分享一次试试。")
        }

        loadSharedText { [weak self] text in
            // ⚠️ **回调线程不确定**（Apple 未承诺主线程）：所有 UI 更新与后续提交都必须
            // 切回主线程，否则是「扩展偶发卡住/崩溃」这类最难查的形态（2026-09-14 自查修）。
            DispatchQueue.main.async {
                guard let self, !self.handledInput else { return }
                self.handledInput = true
                guard let text, let link = Self.firstLink(in: text) else {
                    // 没有链接就**不提交、也不退化成一条记录**——回落成「记一笔」正是
                    // 2026-09-13 要根除的失败模式（用户的动作不该被悄悄做错）。
                    self.setFailure("这次分享来的内容里我没找到链接。")
                    return
                }
                self.submit(link)
            }
        }
    }

    /// 从 `NSExtensionItem` 里取一段**能择出链接**的文本。
    ///
    /// **为什么不是「取第一个 provider 就完事」**：那样会在两种情况下假阴性报「没找到链接」——
    /// ① URL provider 给的是 `bilibili://` 这类**非 http scheme**（择不出 http 链接）；
    /// ② URL provider 载入失败或压根是空的。所以按「URL → 纯文本」逐个尝试，
    /// **第一个能择出 http 链接的才算数**；都没命中时回退到 `attributedContentText`
    /// （有些 App 的正文只放在这里，attachments 是空的）。
    private func loadSharedText(completion: @escaping (String?) -> Void) {
        let items = (extensionContext?.inputItems as? [NSExtensionItem]) ?? []
        let providers = items.compactMap { $0.attachments }.flatMap { $0 }

        var sources: [(String, NSItemProvider)] = []
        if let urlProvider = providers.first(where: {
            $0.hasItemConformingToTypeIdentifier(UTType.url.identifier)
        }) {
            sources.append((UTType.url.identifier, urlProvider))
        }
        if let textProvider = providers.first(where: {
            $0.hasItemConformingToTypeIdentifier(UTType.text.identifier)
        }) {
            sources.append((UTType.text.identifier, textProvider))
        }
        let attributeFallback = items
            .compactMap { $0.attributedContentText?.string }
            .first { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

        func attempt(_ index: Int, _ fallback: String?) {
            guard index < sources.count else {
                completion(fallback)
                return
            }
            let (identifier, provider) = sources[index]
            provider.loadItem(forTypeIdentifier: identifier, options: nil) { item, _ in
                let raw = Self.text(from: item)
                if let raw, Self.firstLink(in: raw) != nil {
                    completion(raw)
                    return
                }
                attempt(index + 1, raw ?? fallback)
            }
        }
        attempt(0, attributeFallback)
    }

    /// 把 `NSItemProvider` 交回的东西统一成字符串。它可能给 URL、String、Data，
    /// URL 还可能给 bookmark data（`Data` 形态）——三种都要接，否则某些 App
    /// 分享过来会「看着成功、其实没内容」。
    private static func text(from item: NSSecureCoding?) -> String? {
        if let url = item as? URL { return url.absoluteString }
        if let url = item as? NSURL { return url.absoluteString }
        if let text = item as? String { return text }
        if let data = item as? Data {
            if let text = String(data: data, encoding: .utf8) { return text }
            var stale = false
            if let url = try? URL(resolvingBookmarkData: data, options: [],
                                  relativeTo: nil, bookmarkDataIsStale: &stale) {
                return url.absoluteString
            }
        }
        return nil
    }

    private static func firstLink(in text: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: linkPattern) else { return nil }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        guard let match = regex.firstMatch(in: text, range: range),
              let found = Range(match.range, in: text) else { return nil }
        return String(text[found])
    }

    // MARK: - 提交

    private func submit(_ link: String) {
        guard let token = ShareAuth.token else {
            // RFC 20260915：主 App 现在会在登录/启动时**自动**把钥匙放好，
            // 所以这里不该再让用户去翻学习页——只要给一个动作：「打开阿呆一次」。
            setFailure("分享还没接通——打开阿呆一次，就会自动接上。")
            return
        }
        guard let base = Self.apiBaseUrl, let url = URL(string: base + Self.digestPath) else {
            setFailure("我这边的地址没配好，这次先记下，回头我来修。")
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.httpBody = try? JSONSerialization.data(withJSONObject: ["url": link])

        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            DispatchQueue.main.async {
                guard let self else { return }
                if let error {
                    self.setFailure(Self.humanMessage(error: error))
                    return
                }
                let httpStatus = (response as? HTTPURLResponse)?.statusCode ?? 0
                if (200..<300).contains(httpStatus) {
                    self.handleAccepted(data)
                } else {
                    self.setFailure(Self.humanMessage(status: httpStatus, body: data))
                }
            }
        }.resume()
    }

    /// 2xx **不等于**「我收下了」。
    ///
    /// 后端 `LearnDigestAppService.submit` 在**已有任务在跑**时也会回 200，但那条新链接
    /// **根本不会入队**（`jobs.compute` 抢占失败方直接复用当前 job）：响应体 `status` 为
    /// `running`（在跑）或 `needs_confirmation`（等确认花钱）；**只有真正入队的新任务**才回
    /// `pending`。所以必须读这个字段——否则先分享 A、再分享 B 时，扩展显示「已交给阿呆」，
    /// 而 B 永远不会被处理，用户却以为成功了（对抗审查 P1-5）。
    private func handleAccepted(_ data: Data?) {
        let state = Self.statusField(in: data)
        if state == "running" || state == "needs_confirmation" {
            setFailure("我正在读上一条，这条没排上——等它读完，再分享一次。")
            return
        }
        setSuccess()
    }

    private static func statusField(in data: Data?) -> String? {
        guard let data,
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        return obj["status"] as? String
    }

    private static var apiBaseUrl: String? {
        guard let raw = Bundle.main.object(forInfoDictionaryKey: "AdaiApiBaseUrl") as? String,
              !raw.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespaces)
        return trimmed.hasSuffix("/") ? String(trimmed.dropLast()) : trimmed
    }

    /// 失败人话：后端错误体是 `{"error": "人话"}`（GlobalExceptionHandler 口径），
    /// **原样透出**，不自己编一句更模糊的。
    private static func humanMessage(status: Int, body: Data?) -> String {
        if let body,
           let obj = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
           let message = obj["error"] as? String, !message.isEmpty {
            return message
        }
        switch status {
        case 400: return "这个链接我没法读（换个链接试试）。"
        case 401: return "这把钥匙已失效——打开阿呆一次，会自动换一把新的。"
        case 403: return "「学习」这件事我这儿还关着，去插件设置里打开再来。"
        case 404: return "没找到整理的服务，可能是我这边地址不对。"
        case 500...599: return "我这边出了点问题，稍后再试一次。"
        default: return "没能交出去（HTTP \(status)）。"
        }
    }

    private static func humanMessage(error: Error) -> String {
        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain {
            switch nsError.code {
            case NSURLErrorNotConnectedToInternet, NSURLErrorNetworkConnectionLost,
                 NSURLErrorCannotConnectToHost:
                return "手机好像没网，等有网了再分享一次。"
            case NSURLErrorTimedOut:
                return "等太久没回应，稍后再分享一次。"
            default:
                break
            }
        }
        return "没能交出去——\(error.localizedDescription)"
    }

    // MARK: - 状态与收尾

    private func setSubmitting() {
        spinner.isHidden = false
        spinner.startAnimating()
        symbolLabel.isHidden = true
        titleLabel.text = "交给阿呆…"
        detailLabel.text = "正在把链接递过去"
        closeButton.isHidden = true
    }

    /// 用户拍板的口径：成功显示「交出去了」约 1.6 秒自动关。
    /// 提交式端点秒回，抓取/转写在后台跑——没有任何理由让用户在这里等。
    ///
    /// P1-分享7（2026-09-16）：**提交成功 ≠ 整理成功**——这里原先说「正在读…」，读起来像保证
    /// 会成功；而后台消化失败时（实测微博分享两次都失败）用户事后去学习页才发现什么都没有。
    /// 所以文案改成如实指路：结果去「学习」页看，成功没成功都在那。
    private func setSuccess() {
        spinner.stopAnimating()
        spinner.isHidden = true
        symbolLabel.isHidden = false
        symbolLabel.text = "✅"
        titleLabel.text = "交出去了"
        detailLabel.text = "结果去「学习」页看（成没成都写在那）"
        closeButton.isHidden = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) { [weak self] in
            self?.finish()
        }
    }

    /// 失败时**停在界面上说清楚**：静默关闭会让用户以为成功了，
    /// 而这一次分享其实白丢了（且他不会知道）。
    private func setFailure(_ message: String) {
        spinner.stopAnimating()
        spinner.isHidden = true
        symbolLabel.isHidden = false
        symbolLabel.text = "⚠️"
        titleLabel.text = "这次没交出去"
        detailLabel.text = message
        closeButton.isHidden = false
    }

    private func finish() {
        guard !finished else { return }
        finished = true
        extensionContext?.completeRequest(returningItems: nil)
    }

    @objc private func onCloseTapped() {
        finish()
    }

    // MARK: - UI 搭建（纯代码，不依赖 storyboard）

    private func buildUI() {
        view.backgroundColor = .systemBackground

        spinner.hidesWhenStopped = false
        spinner.color = .secondaryLabel
        spinner.translatesAutoresizingMaskIntoConstraints = false

        symbolLabel.font = .systemFont(ofSize: 34)
        symbolLabel.textAlignment = .center
        symbolLabel.isHidden = true

        titleLabel.font = .systemFont(ofSize: 17, weight: .semibold)
        titleLabel.textAlignment = .center
        titleLabel.numberOfLines = 0

        detailLabel.font = .systemFont(ofSize: 14)
        detailLabel.textColor = .secondaryLabel
        detailLabel.textAlignment = .center
        detailLabel.numberOfLines = 0

        closeButton.setTitle("知道了", for: .normal)
        closeButton.titleLabel?.font = .systemFont(ofSize: 16, weight: .medium)
        closeButton.isHidden = true
        closeButton.addTarget(self, action: #selector(onCloseTapped), for: .touchUpInside)

        let stack = UIStackView(arrangedSubviews: [spinner, symbolLabel, titleLabel, detailLabel, closeButton])
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.setCustomSpacing(18, after: detailLabel)
        view.addSubview(stack)

        NSLayoutConstraint.activate([
            stack.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 32),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -32),
        ])
    }
}
