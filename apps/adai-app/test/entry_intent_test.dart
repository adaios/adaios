import 'package:adai_app/services/entry_intent_service.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

// ────────────────────────────────────────────────────────────────
// EntryIntentService 测试（RFC 20260913 外部入口批：Siri / 快捷指令 / adai://）
//
// 覆盖：非 iOS 不碰原生通道（PWA/Android 不能被外部入口弄崩）、冷启动主动取、
// 原生推送投递、消费一次不重复、空内容不猜（只准备输入框）、未知动作不硬塞、
// 旧版本 App 无通道时降级不抛。
//
// 原生侧（App Intent / SceneDelegate URL）不在此覆盖——那是 Swift 的职责，
// 真机验证见 docs/deployment/backend-deployment.md §11 与 change-log。
// ────────────────────────────────────────────────────────────────

const MethodChannel _channel = MethodChannel('adai/entry');

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late List<MethodCall> calls;

  /// 装原生假通道；[pending] 为 takePendingEntry 的返回值。
  void installChannel({Map<String, Object?>? pending}) {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, (call) async {
      calls.add(call);
      if (call.method == 'takePendingEntry') return pending;
      return null;
    });
  }

  /// 模拟原生 → Dart 推送。
  Future<void> emitFromNative(String method, Object? args) async {
    await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .handlePlatformMessage(
      'adai/entry',
      const StandardMethodCodec().encodeMethodCall(MethodCall(method, args)),
      (_) {},
    );
    await Future<void>.delayed(Duration.zero);
  }

  setUp(() {
    calls = [];
    EntryIntentService.resetForTest();
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
  });

  tearDown(() {
    debugDefaultTargetPlatformOverride = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, null);
  });

  // ── 平台守卫（PWA/Android 安全）──

  test('非 iOS 环境：不注册也不取，pending 保持空', () async {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    installChannel(pending: {'action': 'record', 'text': '不该被取到'});

    await EntryIntentService.init();

    expect(EntryIntentService.pending.value, isNull);
    expect(calls, isEmpty, reason: 'Android/Web 不该调用 adai/entry 通道');
  });

  test('旧版本 App（原生无此通道）：MissingPluginException 降级不抛', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, null);

    await EntryIntentService.init(); // 不抛即通过

    expect(EntryIntentService.pending.value, isNull);
  });

  // ── 冷启动：Dart 主动取 ──

  test('冷启动：Siri 拉起 App 时入口已排队 → init 取到', () async {
    installChannel(pending: {'action': 'record', 'text': '今天减仓了立昂微', 'source': 'siri'});

    await EntryIntentService.init();

    final entry = EntryIntentService.pending.value;
    expect(entry, isNotNull);
    expect(entry!.hasText, isTrue);
    expect(entry.text, '今天减仓了立昂微');
    expect(entry.source, 'siri');
    expect(entry.action, ExternalEntryAction.record);
    expect(calls.map((c) => c.method), ['takePendingEntry']);
  });

  test('无排队入口：init 后 pending 为空（不产生空记录）', () async {
    installChannel(pending: null);
    await EntryIntentService.init();
    expect(EntryIntentService.pending.value, isNull);
  });

  // ── warm：原生推送 ──

  test('运行中被唤起（onEntry）→ pending 有值', () async {
    installChannel();
    await EntryIntentService.init();
    expect(EntryIntentService.pending.value, isNull);

    await emitFromNative('onEntry', {'action': 'record', 'text': '记一笔测试', 'source': 'url'});

    expect(EntryIntentService.pending.value?.text, '记一笔测试');
    expect(EntryIntentService.pending.value?.source, 'url');
  });

  test('无关的原生方法不产生入口', () async {
    installChannel();
    await EntryIntentService.init();

    await emitFromNative('somethingElse', {'action': 'record', 'text': '不该生效'});

    expect(EntryIntentService.pending.value, isNull);
  });

  // ── 消费一次 ──

  test('take 消费一次：第二次返回 null（不重复记两条）', () async {
    installChannel(pending: {'action': 'record', 'text': '只该记一次', 'source': 'siri'});
    await EntryIntentService.init();

    expect(EntryIntentService.take()?.text, '只该记一次');
    expect(EntryIntentService.take(), isNull);
    expect(EntryIntentService.pending.value, isNull);
  });

  test('无入口时 take 返回 null', () {
    expect(EntryIntentService.take(), isNull);
  });

  // ── 内容解析：不猜 ──

  test('空/空白文本 → hasText=false（只准备输入框，不落成记录）', () {
    final empty = ExternalEntry.fromNative({'action': 'record', 'text': '', 'source': 'url'});
    expect(empty, isNotNull);
    expect(empty!.hasText, isFalse);
    expect(empty.text, isNull, reason: '空白文本应归一为 null，而不是空串');

    final blank = ExternalEntry.fromNative({'action': 'record', 'text': '   ', 'source': 'url'});
    expect(blank!.hasText, isFalse);

    final missing = ExternalEntry.fromNative({'action': 'record', 'source': 'url'});
    expect(missing!.hasText, isFalse);
  });

  test('文本两侧空白被裁掉', () {
    final e = ExternalEntry.fromNative({'action': 'record', 'text': '  记一笔  ', 'source': 'siri'});
    expect(e!.text, '记一笔');
  });

  test('未知/缺失 action → null（不硬塞成记录）', () {
    expect(ExternalEntry.fromNative({'action': 'delete', 'text': 'x'}), isNull);
    expect(ExternalEntry.fromNative({'text': 'x'}), isNull);
    expect(ExternalEntry.fromNative('不是 map'), isNull);
    expect(ExternalEntry.fromNative(null), isNull);
  });

  test('source 缺失 → unknown（不崩）', () {
    final e = ExternalEntry.fromNative({'action': 'record', 'text': 'x'});
    expect(e!.source, 'unknown');
  });
}
