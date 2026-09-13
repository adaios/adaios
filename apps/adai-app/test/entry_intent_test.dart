import 'package:adai_app/services/entry_intent_service.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

// ────────────────────────────────────────────────────────────────
// EntryIntentService 测试（RFC 20260913 外部入口批：Siri / 快捷指令 / adai://）
//
// 覆盖：非 iOS 不碰原生通道（PWA/Android 不能被外部入口弄崩）、冷启动主动取（整批队列）、
// 原生推送投递、消费一次不重复、队列 FIFO 不丢件、队列绑定 userId 不串号、登出清空、
// 未登录不排队、URL 来源 3 秒去重（被吞有痕）、空内容不猜、未知动作不硬塞、
// 旧版本 App 无通道时降级不抛。
//
// 原生侧（App Intent / SceneDelegate URL / UserDefaults 队列）不在此覆盖——那是 Swift 的职责，
// 真机验证见 docs/deployment/backend-deployment.md §11 与 change-log；
// iOS 队列/兜底改动至少保证 `flutter build ios --no-codesign` 能编译。
// ────────────────────────────────────────────────────────────────

const MethodChannel _channel = MethodChannel('adai/entry');

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late List<MethodCall> calls;
  late List<String> logs;
  late DebugPrintCallback originalDebugPrint;

  /// 装原生假通道；[pending] 为 takePendingEntry 的返回值。
  void installChannel({Object? pending}) {
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
    logs = [];
    originalDebugPrint = debugPrint;
    debugPrint = (String? message, {int? wrapWidth}) {
      if (message != null) logs.add(message);
    };
    EntryIntentService.resetForTest();
    // 队列绑定账号（生产里由 DualWorldShell / MainPage 绑定）。
    EntryIntentService.bindUser('u1');
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
  });

  tearDown(() {
    debugPrint = originalDebugPrint;
    debugDefaultTargetPlatformOverride = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, null);
  });

  // ── 平台守卫（PWA/Android 安全）──

  test('非 iOS 环境：不注册也不取，队列保持空', () async {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    installChannel(pending: [
      {'action': 'record', 'text': '不该被取到'}
    ]);

    await EntryIntentService.init();

    expect(EntryIntentService.pendingCount, 0);
    expect(EntryIntentService.hasPending, isFalse);
    expect(calls, isEmpty, reason: 'Android/Web 不该调用 adai/entry 通道');
  });

  test('旧版本 App（原生无此通道）：MissingPluginException 降级不抛', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_channel, null);

    await EntryIntentService.init(); // 不抛即通过

    expect(EntryIntentService.pendingCount, 0);
  });

  // ── 冷启动：Dart 主动取 ──

  test('冷启动：Siri 拉起 App 时入口已排队 → init 取到', () async {
    installChannel(pending: {'action': 'record', 'text': '今天减仓了立昂微', 'source': 'siri'});

    await EntryIntentService.init();

    final entry = EntryIntentService.take();
    expect(entry, isNotNull);
    expect(entry!.hasText, isTrue);
    expect(entry.text, '今天减仓了立昂微');
    expect(entry.source, 'siri');
    expect(entry.action, ExternalEntryAction.record);
    expect(calls.map((c) => c.method), ['takePendingEntry']);
  });

  test('冷启动整批（原生队列形态 List）：两条都进队，FIFO 不丢第一条', () async {
    installChannel(pending: [
      {'action': 'record', 'text': '第一条', 'source': 'siri'},
      {'action': 'digest', 'text': 'https://b23.tv/x', 'source': 'shortcut'},
    ]);

    await EntryIntentService.init();

    expect(EntryIntentService.pendingCount, 2);
    final all = EntryIntentService.takeAll();
    expect(all.map((e) => e.text).toList(), ['第一条', 'https://b23.tv/x'],
        reason: 'FIFO：先到的先出，队列不覆盖');
    expect(all[1].action, ExternalEntryAction.digest);
    expect(EntryIntentService.takeAll(), isEmpty, reason: '取走即清空（消费一次）');
    expect(EntryIntentService.pendingCount, 0);
  });

  test('takeAll 取整批后 take 为 null；take 取队首后 takeAll 给剩余', () async {
    installChannel(pending: [
      {'action': 'record', 'text': 'a', 'source': 'siri'},
      {'action': 'record', 'text': 'b', 'source': 'siri'},
    ]);
    await EntryIntentService.init();

    expect(EntryIntentService.take()?.text, 'a');
    expect(EntryIntentService.takeAll().map((e) => e.text).toList(), ['b']);
    expect(EntryIntentService.take(), isNull);
  });

  test('队列中的坏数据逐条丢弃，不连累同批好数据', () async {
    installChannel(pending: [
      {'action': 'record', 'text': '好的', 'source': 'siri'},
      {'action': 'delete', 'text': '认不出的动作'},
      '不是 map',
    ]);

    await EntryIntentService.init();

    expect(EntryIntentService.takeAll().map((e) => e.text).toList(), ['好的']);
  });

  test('无排队入口：init 后队列为空（不产生空记录）', () async {
    installChannel(pending: null);
    await EntryIntentService.init();
    expect(EntryIntentService.pendingCount, 0);
  });

  // ── warm：原生推送 ──

  test('运行中被唤起（onEntry）→ 入队', () async {
    installChannel();
    await EntryIntentService.init();
    expect(EntryIntentService.pendingCount, 0);

    await emitFromNative('onEntry', {'action': 'record', 'text': '记一笔测试', 'source': 'url-warm'});

    expect(EntryIntentService.take()?.text, '记一笔测试');
  });

  test('无关的原生方法不产生入口', () async {
    installChannel();
    await EntryIntentService.init();

    await emitFromNative('somethingElse', {'action': 'record', 'text': '不该生效'});

    expect(EntryIntentService.pendingCount, 0);
  });

  // ── 消费一次 ──

  test('take 消费一次：第二次返回 null（不重复记两条）', () async {
    installChannel(pending: {'action': 'record', 'text': '只该记一次', 'source': 'siri'});
    await EntryIntentService.init();

    expect(EntryIntentService.take()?.text, '只该记一次');
    expect(EntryIntentService.take(), isNull);
    expect(EntryIntentService.pendingCount, 0);
  });

  test('无入口时 take 返回 null', () {
    expect(EntryIntentService.take(), isNull);
  });

  // ── 队列绑定 userId（REVIEW P1-入口2：不串号）──

  test('换账号：bindUser 清空上一个账号的待处理入口', () async {
    installChannel(pending: [
      {'action': 'record', 'text': 'u1 的入口', 'source': 'siri'},
    ]);
    await EntryIntentService.init(userId: 'u1');
    expect(EntryIntentService.pendingCount, 1);

    EntryIntentService.bindUser('u2');

    expect(EntryIntentService.pendingCount, 0, reason: '上一个账号的入口不能带给新账号');
    expect(EntryIntentService.takeAll(), isEmpty);
    expect(logs.any((l) => l.contains('换账号')), isTrue, reason: '清空要有痕，便于排查');
  });

  test('同账号重复绑定是 no-op：队列不被误清', () async {
    installChannel(pending: {'action': 'record', 'text': 'x', 'source': 'siri'});
    await EntryIntentService.init(userId: 'u1');

    EntryIntentService.bindUser('u1');

    expect(EntryIntentService.pendingCount, 1);
  });

  test('登出：clearForLogout 清空队列，且登出期间的入口不再排队', () async {
    installChannel(pending: {'action': 'record', 'text': '登出前', 'source': 'siri'});
    await EntryIntentService.init(userId: 'u1');
    expect(EntryIntentService.pendingCount, 1);

    EntryIntentService.clearForLogout();

    expect(EntryIntentService.pendingCount, 0);
    expect(EntryIntentService.boundUserId, isNull);

    // 登出期间（登录页）到达的入口：没有归属账号，直接不排队（宁可不接，也不串号）
    EntryIntentService.debugEnqueue(
        const ExternalEntry(action: ExternalEntryAction.record, text: '登出期间', source: 'siri'));
    expect(EntryIntentService.pendingCount, 0);
    expect(logs.any((l) => l.contains('没有登录账号')), isTrue, reason: '被丢弃要有痕');

    // 下一个账号登录：队列是干净的
    EntryIntentService.bindUser('u2');
    expect(EntryIntentService.takeAll(), isEmpty);
  });

  test('未绑定账号时 takeAll 不消费（没有消费方就不取出）', () {
    EntryIntentService.clearForLogout();
    EntryIntentService.bindUser('u1');
    EntryIntentService.debugEnqueue(
        const ExternalEntry(action: ExternalEntryAction.record, text: '留着我', source: 'siri'));
    expect(EntryIntentService.pendingCount, 1);

    EntryIntentService.clearForLogout(); // 解绑（登出）

    expect(EntryIntentService.takeAll(), isEmpty, reason: '未绑定 = 不消费');
  });

  // ── URL 3 秒去重（保留去重，但被吞要有痕）──
  //
  // 去重修的是真 bug：冷启动时 iOS 会把同一个 URL 经 willConnectTo 与 openURLContexts
  // 送两遍 → 落两条一模一样的记录。但去重命中必须打日志，不能零反馈。

  test('URL 来源 3 秒内重复 → 只入队一条，且打日志（被吞有痕）', () {
    var now = DateTime(2026, 9, 14, 10, 0, 0);
    EntryIntentService.clock = () => now;

    EntryIntentService.debugEnqueue(const ExternalEntry(
        action: ExternalEntryAction.record, text: '同一条', source: 'url-cold'));
    now = now.add(const Duration(milliseconds: 500));
    EntryIntentService.debugEnqueue(const ExternalEntry(
        action: ExternalEntryAction.record, text: '同一条', source: 'url-cold'));

    expect(EntryIntentService.pendingCount, 1, reason: '同一次唤起被送两遍 → 只记一条');
    expect(logs.any((l) => l.contains('重复') && l.contains('忽略第二条')), isTrue,
        reason: '不能静默：必须留下一条日志');
  });

  test('URL 重复超过 3 秒窗口 → 两条都入队（合法重复不能被吞）', () {
    var now = DateTime(2026, 9, 14, 10, 0, 0);
    EntryIntentService.clock = () => now;

    EntryIntentService.debugEnqueue(const ExternalEntry(
        action: ExternalEntryAction.record, text: '同一条', source: 'url-warm'));
    now = now.add(const Duration(seconds: 4));
    EntryIntentService.debugEnqueue(const ExternalEntry(
        action: ExternalEntryAction.record, text: '同一条', source: 'url-warm'));

    expect(EntryIntentService.pendingCount, 2);
  });

  test('Siri / 快捷指令来源不去重：同一句话连说两遍也记两条', () {
    var now = DateTime(2026, 9, 14, 10, 0, 0);
    EntryIntentService.clock = () => now;

    EntryIntentService.debugEnqueue(const ExternalEntry(
        action: ExternalEntryAction.record, text: '同一句', source: 'siri'));
    now = now.add(const Duration(milliseconds: 100));
    EntryIntentService.debugEnqueue(const ExternalEntry(
        action: ExternalEntryAction.record, text: '同一句', source: 'shortcut'));

    expect(EntryIntentService.pendingCount, 2, reason: '这是用户亲口说的，去重只能作用于 URL 送达');
  });

  test('URL 去重按 (action, text) 区分：不同内容不去重', () {
    var now = DateTime(2026, 9, 14, 10, 0, 0);
    EntryIntentService.clock = () => now;

    EntryIntentService.debugEnqueue(const ExternalEntry(
        action: ExternalEntryAction.record, text: 'A', source: 'url-cold'));
    EntryIntentService.debugEnqueue(const ExternalEntry(
        action: ExternalEntryAction.digest, text: 'A', source: 'url-cold'));
    EntryIntentService.debugEnqueue(const ExternalEntry(
        action: ExternalEntryAction.record, text: 'B', source: 'url-cold'));

    expect(EntryIntentService.pendingCount, 3);
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

  test('listFromNative：List 与单条 Map 都接受，坏条目逐条丢', () {
    expect(ExternalEntry.listFromNative(null), isEmpty);
    expect(ExternalEntry.listFromNative('x'), isEmpty);
    expect(ExternalEntry.listFromNative({'action': 'record', 'text': 'x'}).length, 1);
    expect(
        ExternalEntry.listFromNative([
          {'action': 'record', 'text': 'x'},
          {'action': 'nope'},
        ]).length,
        1);
  });

  // ── digest 动作（2026-09-13 分享与整理批）──
  //
  // 为什么单独锁：这个动作存在的唯一理由，就是**不再靠文本里的「整理」二字去猜**。
  // 若哪天有人把不认识的 action 回落成 record（那是本批要根除的失败模式），
  // 这些用例会红。

  test('digest 动作被识别（分享/整理链路）', () {
    final e = ExternalEntry.fromNative(
        {'action': 'digest', 'text': 'https://b23.tv/AbCdEf', 'source': 'shortcut'});

    expect(e, isNotNull);
    expect(e!.action, ExternalEntryAction.digest);
    expect(e.text, 'https://b23.tv/AbCdEf');
    expect(e.source, 'shortcut');
  });

  test('digest 与 record 是两种动作（不互相污染）', () {
    final r = ExternalEntry.fromNative({'action': 'record', 'text': '记一笔', 'source': 'siri'});
    final d = ExternalEntry.fromNative({'action': 'digest', 'text': 'https://b23.tv/x'});

    expect(r!.action, ExternalEntryAction.record);
    expect(d!.action, ExternalEntryAction.digest);
  });

  test('digest 空内容 → hasText=false（只准备输入框，不猜）', () {
    final e = ExternalEntry.fromNative({'action': 'digest', 'source': 'shortcut'});
    expect(e, isNotNull);
    expect(e!.hasText, isFalse);
  });

  test('冷启动取到 digest 入口（Siri/快捷指令拉起 App）', () async {
    installChannel(
        pending: {'action': 'digest', 'text': 'https://b23.tv/x', 'source': 'shortcut'});

    await EntryIntentService.init();

    final entry = EntryIntentService.take();
    expect(entry?.action, ExternalEntryAction.digest);
    expect(entry?.text, 'https://b23.tv/x');
  });

  test('source 缺失 → unknown（不崩）', () {
    final e = ExternalEntry.fromNative({'action': 'record', 'text': 'x'});
    expect(e!.source, 'unknown');
  });
}
