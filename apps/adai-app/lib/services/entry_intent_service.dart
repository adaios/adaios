import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// 外部入口的动作类型。
///
/// ⚠️ **动作表是两份**：本枚举与原生侧 `ExternalEntryAction`
/// （`ios/Runner/ExternalEntry.swift`）是同一份契约的两端，**必须同步增删**——
/// 只加一侧的后果是「原生发得出去、Dart 认不出来」，表现为点了没反应且日志干净
/// （同族坑见 pitfalls「条件导出的两份实现 API 面不一致」）。
enum ExternalEntryAction {
  /// 记一笔（Siri / 快捷指令 / `adai://record`）——把内容直接落成一条记录。
  record,

  /// 整理（快捷指令 / `adai://digest`）——把链接交给 learn 流水线，产物是一张学习卡。
  ///
  /// 与 [record] 是**动作语义**之别，不是内容特征之别：record 是「把这句话记下来」，
  /// digest 是「去把这个链接读明白」。此前没有这个动作，只能靠文本里的「整理」二字去猜
  /// （2026-09-13 真机实测：共享链接直接接进「记一笔」→ 文本里没有触发词
  /// → 静默落成一条普通记录，并没有整理）。
  digest,
}

/// 一条来自 App 外部（Siri / 快捷指令 / URL scheme）的入口请求。
@immutable
class ExternalEntry {
  final ExternalEntryAction action;

  /// 要记录的内容；空 = 用户只说了「打开阿呆」，把输入框准备好即可。
  final String? text;

  /// 来源（`siri` / `url-cold` / `url-warm` …），用于日志、去重与排查。
  final String source;

  const ExternalEntry({required this.action, this.text, this.source = 'unknown'});

  /// 内容是否真的可以落成记录。
  bool get hasText => text != null && text!.trim().isNotEmpty;

  /// 从原生载荷解析；无法识别 → null（不猜、不硬塞）。
  static ExternalEntry? fromNative(dynamic raw) {
    if (raw is! Map) return null;
    final action = _actionOf(raw['action']?.toString());
    if (action == null) return null;
    final text = raw['text']?.toString();
    return ExternalEntry(
      action: action,
      text: (text == null || text.trim().isEmpty) ? null : text.trim(),
      source: raw['source']?.toString() ?? 'unknown',
    );
  }

  /// 解析原生载荷：**队列形态（List）与单条形态（Map）都接受**。
  ///
  /// 队列形态是本批新增（原生侧 `ExternalEntry.drain()` 一次给出整条队列）；
  /// 单条形态保留兼容——用户升级 App 时若原生侧还是旧版（单槽），Dart 也要认得。
  /// 认不出的条目**逐条丢弃**（不猜、不硬塞），不会因为一条坏数据丢掉整批。
  static List<ExternalEntry> listFromNative(dynamic raw) {
    if (raw is List) {
      return raw.map(fromNative).whereType<ExternalEntry>().toList(growable: false);
    }
    final single = fromNative(raw);
    return single == null ? const <ExternalEntry>[] : <ExternalEntry>[single];
  }

  /// 原生动作字符串 → 枚举。**认不出来返回 null，不回落成 [ExternalEntryAction.record]**——
  /// 回落会把「整理」悄悄变成「记一条」，那正是本批要根除的失败模式
  /// （宁可无反应，也不要把用户的动作做错，且 unknown action 显然来自更新版的原生侧）。
  static ExternalEntryAction? _actionOf(String? raw) => switch (raw) {
        'record' => ExternalEntryAction.record,
        'digest' => ExternalEntryAction.digest,
        _ => null,
      };
}

/// EntryIntentService — 外部入口接入（RFC 20260913 外部入口批）。
///
/// 解决的问题：阿呆的核心动作是**记录**，而记录的摩擦一直是「解锁 → 找 App → 点开 → 打字」。
/// 本服务把「App 外面发起的一句话」接进产品：Siri「用阿呆记一笔」、快捷指令、
/// 甚至 `adai://record?text=…` 链接，都能直接落成一条记录。
///
/// 设计取舍：
/// - **内容直接落成记录**（不是「预填等你点发送」）：语音输入的意图已经明确——是你亲口说的，
///   再要求摸手机点一次发送，等于把 Siri 的价值抵消掉。卡片照常可见、可删（既有能力）。
///   **空内容不猜**：只把输入框准备好，等你写。
/// - **三端安全**：只在 iOS 原生生效（[supported]），Web/PWA/Android 一律不注册、
///   不触碰原生通道（app 的 web 构建还要部署到 `adaiadai.com/m/`）；所有调用包 try/catch。
/// - **队列而不是单槽**（REVIEW P1-入口1）：外部入口可能连发（Siri 连说两次、冷启动两条 URL），
///   单槽是**覆盖写**——后者到、前者静默消失。这里与原生侧同步改成 FIFO 队列，取用逐条/整批都行，
///   消费一次即出队（原生 drain 即清空，本地再出队，双保险防重复记两条）。
/// - **队列绑定 userId**（REVIEW P1-入口2）：登出期间（登录页）到达的入口不能被**下一个登录的账号**
///   消费——那是串号。取用/入队都校验绑定账号，换人（[bindUser]）或登出（[clearForLogout]）即清空。
/// - **被吞要有痕**（REVIEW P1-入口4）：3 秒时间窗去重仍保留（它修的是冷启动同一次 URL 被
///   系统送两遍的真 bug），但命中时**必须打日志**，不再零反馈。
class EntryIntentService {
  EntryIntentService._();

  static const MethodChannel _channel = MethodChannel('adai/entry');

  /// 待处理入口条数（0 = 无；>0 = 有）。UI 层监听它即可，不必轮询。
  static final ValueNotifier<int> pending = ValueNotifier<int>(0);

  /// 仅 iOS 原生（App Intents / URL scheme 都是 iOS 侧能力）。
  static bool get supported => !kIsWeb && defaultTargetPlatform == TargetPlatform.iOS;

  /// FIFO 队列（队首 = 最早到达的入口）。
  static final List<ExternalEntry> _queue = <ExternalEntry>[];

  /// 队列绑定的账号；null = 未登录（此时不排队、不消费，防跨账号串号）。
  static String? _boundUserId;

  /// 最近一次已接受的入口 + 时间，仅用于 **URL 来源**的 3 秒去重。
  static ExternalEntry? _lastAccepted;
  static DateTime? _lastAcceptedAt;

  /// URL 去重窗口（与原生侧 `ExternalEntry.urlDedupeWindow` 对齐）。
  static const Duration _urlDedupeWindow = Duration(seconds: 3);

  /// 时钟（测试注入）；生产即 [DateTime.now]。
  @visibleForTesting
  static DateTime Function() clock = DateTime.now;

  static bool _handlerInstalled = false;

  /// 待处理入口是否为空——壳层在消费前用它判断，避免无谓建卡。
  static bool get hasPending => _queue.isNotEmpty;

  /// 待处理入口条数。
  static int get pendingCount => _queue.length;

  /// 当前绑定的账号（测试与排查用）。
  static String? get boundUserId => _boundUserId;

  @visibleForTesting
  static void resetForTest() {
    _handlerInstalled = false;
    _queue.clear();
    _boundUserId = null;
    _lastAccepted = null;
    _lastAcceptedAt = null;
    pending.value = 0;
    clock = DateTime.now;
  }

  /// 绑定当前登录账号。**换人即清空**——上一个账号没消费完的入口不能带给新账号
  /// （REVIEW P1-入口2：登出期间的 Siri 入口被下一个登录账号消费，就是串号）。
  static void bindUser(String userId) {
    if (_boundUserId == userId) return;
    if (_queue.isNotEmpty) {
      debugPrint('[AdaiEntry] 换账号（${_boundUserId ?? '未登录'} → $userId）：'
          '清掉上一个账号的 ${_queue.length} 条待处理入口，不带给新账号');
    }
    _boundUserId = userId;
    _queue.clear();
    _lastAccepted = null;
    _lastAcceptedAt = null;
    pending.value = 0;
  }

  /// 登出：解绑并清空队列（登出期间到达的入口不再排队，见 [_enqueue]）。
  static void clearForLogout() {
    _boundUserId = null;
    _queue.clear();
    _lastAccepted = null;
    _lastAcceptedAt = null;
    pending.value = 0;
  }

  /// 接入：绑定账号 + 装原生回调 + 取一次冷启动期间排队的入口（整批）。
  ///
  /// 调用时机由壳层决定（会话就绪后）。理由：记录要带会话 token 才能落盘，
  /// 未登录时先不取（原生侧攒着），登录后自然会消费掉。
  static Future<void> init({String? userId}) async {
    if (userId != null) bindUser(userId);
    if (!supported) return;
    _installHandler();
    try {
      final raw = await _channel.invokeMethod<dynamic>('takePendingEntry');
      _emitPayload(raw);
    } on MissingPluginException {
      // 旧版本 App（原生侧还没这个通道）→ 静默降级
    } on PlatformException {
      // 原生侧异常不该影响启动
    }
  }

  /// 一次性取走整批（FIFO 顺序，取走即清空）。未绑定账号 = 没有消费方 → 不消费。
  static List<ExternalEntry> takeAll() {
    if (_boundUserId == null || _queue.isEmpty) return const <ExternalEntry>[];
    final entries = List<ExternalEntry>.unmodifiable(_queue);
    _queue.clear();
    pending.value = 0;
    return entries;
  }

  /// 取走队首一条（FIFO，取走即清空，幂等）。未绑定账号 = 不消费，返回 null。
  ///
  /// 页面逐条消费时用它：**取一条处理一条**，页面中途销毁也不会把剩余入口一起拿走丢掉。
  static ExternalEntry? take() {
    if (_boundUserId == null || _queue.isEmpty) return null;
    final entry = _queue.removeAt(0);
    pending.value = _queue.length;
    return entry;
  }

  /// 测试用：直接入队（绕过原生通道），仍走绑定校验与 URL 去重。
  @visibleForTesting
  static void debugEnqueue(ExternalEntry entry) => _enqueue(entry);

  static void _installHandler() {
    if (_handlerInstalled) return;
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'onEntry') _emitPayload(call.arguments);
      return null;
    });
    _handlerInstalled = true;
  }

  static void _emitPayload(dynamic raw) {
    for (final entry in ExternalEntry.listFromNative(raw)) {
      _enqueue(entry);
    }
  }

  static void _enqueue(ExternalEntry entry) {
    // 未登录（登出期间）到达的入口不进队：它没有归属账号，
    // 攒着就会被下一个登录的账号消费——宁可不接，也不串号（REVIEW P1-入口2）。
    if (_boundUserId == null) {
      debugPrint('[AdaiEntry] 当前没有登录账号 → 这条入口不排队（不跨账号串号）：'
          '${entry.action.name}${entry.hasText ? ' text=${entry.text}' : ''}');
      return;
    }

    final now = clock();
    final last = _lastAccepted;
    final lastAt = _lastAcceptedAt;
    // 只对 **URL 来源** 去重：冷启动时 iOS 会把同一个 URL 同时经
    // `willConnectTo` 与 `openURLContexts` 送两遍（真 bug）；App Intent / Siri 是
    // 用户亲口说的，同一句话说两遍也必须记两条（各行动作不在此列）。
    if (entry.source.startsWith('url') &&
        last != null &&
        lastAt != null &&
        last.action == entry.action &&
        last.text == entry.text &&
        now.difference(lastAt) < _urlDedupeWindow) {
      // 被吞要有痕（REVIEW P1-入口4）：去重本身要保留，但不再零日志零反馈。
      debugPrint('[AdaiEntry] URL 入口 3 秒内重复（同一次唤起被系统送了两次）→ 忽略第二条：'
          '${entry.action.name} text=${entry.text ?? '<无>'} source=${entry.source}');
      return;
    }

    _lastAccepted = entry;
    _lastAcceptedAt = now;
    _queue.add(entry);
    pending.value = _queue.length;
  }
}
