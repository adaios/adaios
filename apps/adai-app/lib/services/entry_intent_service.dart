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

  /// 来源（`siri` / `url`），只用于日志与排查。
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
/// - **消费一次**：原生侧 drain 即清空，通知路径与主动取路径同时发生也只有一个能拿到内容
///   （不会重复记两条）——这里再用 [take] 做一次本地清零，防同一实例被消费两次。
class EntryIntentService {

  EntryIntentService._();

  static const MethodChannel _channel = MethodChannel('adai/entry');

  /// 待处理的入口（null = 无）。UI 层监听它即可，不必轮询。
  static final ValueNotifier<ExternalEntry?> pending = ValueNotifier<ExternalEntry?>(null);

  /// 仅 iOS 原生（App Intents / URL scheme 都是 iOS 侧能力）。
  static bool get supported => !kIsWeb && defaultTargetPlatform == TargetPlatform.iOS;

  static bool _handlerInstalled = false;

  /// 待处理入口是否为空——壳层在消费前用它判断，避免无谓建卡。
  static bool get hasPending => pending.value != null;

  @visibleForTesting
  static void resetForTest() {
    _handlerInstalled = false;
    pending.value = null;
  }

  /// 接入：装原生回调 + 取一次冷启动期间排队的入口。
  ///
  /// 调用时机由壳层决定（会话就绪后）。理由：记录要带会话 token 才能落盘，
  /// 未登录时先攒着（原生侧也不会消费），登录后自然会消费掉。
  static Future<void> init() async {
    if (!supported) return;
    _installHandler();
    try {
      final raw = await _channel.invokeMethod<Map<dynamic, dynamic>>('takePendingEntry');
      _emit(raw);
    } on MissingPluginException {
      // 旧版本 App（原生侧还没这个通道）→ 静默降级
    } on PlatformException {
      // 原生侧异常不该影响启动
    }
  }

  /// 取走当前待处理入口（取走即清空，幂等）。
  static ExternalEntry? take() {
    final entry = pending.value;
    if (entry == null) return null;
    pending.value = null;
    return entry;
  }

  static void _installHandler() {
    if (_handlerInstalled) return;
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'onEntry') _emit(call.arguments);
      return null;
    });
    _handlerInstalled = true;
  }

  static void _emit(dynamic raw) {
    final entry = ExternalEntry.fromNative(raw);
    if (entry == null) return;
    pending.value = entry;
  }
}

