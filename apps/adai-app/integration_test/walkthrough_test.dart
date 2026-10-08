// 2026-10-09 · app 端交易插件「形态重构」的真机走查驱动脚本（**只用于走查取证，不参与产品逻辑**）。
//
// 为什么用 integration_test 而不是 GUI 自动化：本机 macOS 未授予 Computer Use 权限，
// 且 Xcode 27 下没有可点的 Simulator.app 窗口——而 integration_test 跑在**设备内**，
// 用真实点击驱动真实 App（真实后端、真实 `data/` 快照），外部再用
// `xcrun simctl io booted screenshot` 逐屏截图，绕开 GUI 授权。
//
// 用法：
//   cd apps/adai-app
//   flutter test integration_test/walkthrough_test.dart -d <sim-udid> \
//     --dart-define=API_BASE_URL=http://localhost:8080 --dart-define=WL_PW=<密码>
//
// 每个阶段都会**停留若干秒**，好让外部截屏循环拍到稳定画面（金额类截图入库前必须脱敏）。
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

import 'package:adai_app/main.dart' show RootApp;
import 'package:adai_app/services/biometric_service.dart';

const String kPassword = String.fromEnvironment('WL_PW');

/// 走查专用：把「本地解锁闸」替换成**不可用**（等价于这台设备没有 Face ID）——
/// 于是启动校验不会去调系统生物识别/设备密码（模拟器上那会弹「输入 iPhone 密码」，而
/// 模拟器没设设备密码、`simctl` 也点不了）。走查只看交易页形态，不需要过门禁。
class _NoGate implements BiometricGate {
  const _NoGate();

  @override
  Future<bool> isAvailable() async => false;

  @override
  Future<bool> authenticate({String reason = ''}) async => true;
}

/// 停一会儿，让外部截屏拍到这一屏（走查专用，不进产品代码）。
Future<void> hold(WidgetTester tester, int seconds) async {
  final deadline = DateTime.now().add(Duration(seconds: seconds));
  while (DateTime.now().isBefore(deadline)) {
    await tester.pump(const Duration(milliseconds: 300));
    await Future<void>.delayed(const Duration(milliseconds: 300));
  }
}

Future<void> tapIfPresent(WidgetTester tester, Finder f) async {
  if (f.evaluate().isEmpty) return;
  await tester.tap(f.first);
  await tester.pumpAndSettle(const Duration(milliseconds: 500));
}

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('app 交易页走查：登录 → 首屏四层 → 显形 → 就地展开 → 下滚', (tester) async {
    // ── 先把推送通道 mock 成「权限已决定（已授权）」──
    // 为什么：壳层 initState 会调 `PushService.init`（main.dart:384），首次会弹**系统**通知授权框；
    // 那是 SpringBoard 的弹窗，`simctl` 不能点击、也**不能预授权**（notifications 不在 privacy 服务表里），
    // 而本机没给 Codex 开 Computer Use 权限 ⇒ 弹窗会一直盖在 App 上、毁掉逐屏实拍。
    // 这里在**测试侧**应答原生通道（不动任何产品代码）：授权态=authorized、无 token（不上报设备），
    // 于是系统不再弹框、走查可以拍干净画面。
    const pushChannel = MethodChannel('adai/push');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(pushChannel, (call) async {
      switch (call.method) {
        case 'getStatus':
          return <String, dynamic>{
            'authorization': 'authorized',
            'token': null,
            'environment': 'sandbox',
            'registered': false,
            'lastError': null,
            'pendingTap': null,
          };
        case 'requestPermission':
          return true;
        default:
          return null;
      }
    });

    // ── 再把生物识别门禁 mock 成「本机不支持」──
    // 壳层对「已记住的设备」会先过一道 Face ID/设备密码本地门禁（main.dart:159），
    // 模拟器上没有可用生物识别 → 系统会弹「输入 iPhone 密码」挡住屏幕。
    // `isDeviceSupported = false` ⇒ 门禁整体跳过（代码里的诚实降级分支），走查画面干净。
    const localAuthChannel = MethodChannel('plugins.flutter.io/local_auth');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(localAuthChannel, (call) async {
      switch (call.method) {
        case 'isDeviceSupported':
          return false;
        case 'canCheckBiometrics':
          return false;
        case 'getAvailableBiometrics':
          return <String>[];
        default:
          return null;
      }
    });

    // ── 让钥匙串读不到「已记住的 token」──
    // 壳层启动时若从钥匙串读到 token，就会走「本地门禁」那条路（Face ID / 设备密码，见上）。
    // 这里把 secure storage 的 `read` 应答成 null ⇒ 与本机首次使用一致：回登录页、直接用
    // 密码登录（走查就是这个状态），门禁自然不触发。
    const secureChannel = MethodChannel('plugins.flutter.io/flutter_secure_storage');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(secureChannel, (call) async {
      switch (call.method) {
        case 'read':
          return null;
        case 'readAll':
          return <String, String>{};
        case 'containsKey':
          return false;
        default:
          return null; // write / delete / deleteAll 等：当成功处理
      }
    });

    // 直接构造壳层（不走 `app.main()`）：显式传 **initialToken: null + forceLogin: true** ⇒ 一定落登录页；
    // 并用 **biometric: _NoGate()** 从源头上跳过本地解锁闸（App 自带的测试注入点，见 main.dart:84）。
    runApp(const RootApp(
      userId: 'adai',
      initialToken: null,
      forceLogin: true,
      biometric: _NoGate(),
    ));
    await tester.pumpAndSettle(const Duration(seconds: 2));

    // ── ① 登录（账号已预填；密码从 --dart-define 传入，不落盘、不进日志）──
    // ⚠️ 按钮文案是「登 录」（中间有空格，见 login_page.dart:247）——按精确文案点，
    //    点完**断言真的离开了登录页**（上一版按 '登录' 找不到、静默跳过，白跑一轮）。
    final fields = find.byType(TextField);
    if (fields.evaluate().isNotEmpty && kPassword.isNotEmpty) {
      await tester.enterText(fields.last, kPassword);
      await tester.pumpAndSettle();
    }
    final loginBtn = find.text('登 录');
    if (loginBtn.evaluate().isNotEmpty) {
      await tester.tap(loginBtn);
      await tester.pumpAndSettle(const Duration(seconds: 4));
    }
    expect(find.text('登 录'), findsNothing,
        reason: '登录没过去（密码错 / 后端不可达 / 按钮没点中）——看外部截图定位');
    await hold(tester, 6); // 屏 1：登录后落地（World A = Feed）

    // ── ② 切到 World B（Launcher）→ 进「交易」──
    // 双 World 靠纵向快速拖拽切换（main.dart 的 onVerticalDragEnd，速度阈值 400）。
    var launcherShown = find.text('交易').evaluate().isNotEmpty;
    for (var i = 0; i < 3 && !launcherShown; i++) {
      await tester.fling(find.byType(IndexedStack).first, const Offset(0, -600), 1500);
      await tester.pumpAndSettle(const Duration(seconds: 2));
      launcherShown = find.text('交易').evaluate().isNotEmpty;
    }
    expect(launcherShown, isTrue, reason: '没能切到 Launcher（双 World 手势没生效）');
    await hold(tester, 6); // 屏 2：Launcher
    // 注意：「交易」在 Launcher 上有两处（插件槽 + 下方标签宇宙里的同名标签），取第一个（插件槽）。
    await tester.tap(find.text('交易').first);
    await tester.pumpAndSettle(const Duration(seconds: 3));
    await hold(tester, 12); // 屏 3：交易首屏（判断句 + 入账 + 持仓行 + 自选 + 今天）

    // ── ③ 点 👁 看金额（打码两层：数量与成本显形）──
    await tapIfPresent(tester, find.text('看金额'));
    await tester.pumpAndSettle(const Duration(seconds: 2));
    await hold(tester, 8); // 屏 4：显形态
    await tapIfPresent(tester, find.text('收起金额'));
    await tester.pumpAndSettle(const Duration(seconds: 1));

    // ── ④ 点持仓行 → 就地展开（按「与你的线」那半句定位，不依赖具体标的）──
    for (final probe in ['没设线', '离你的', '破了你的']) {
      final f = find.textContaining(probe);
      if (f.evaluate().isNotEmpty) {
        await tester.tap(f.first);
        await tester.pumpAndSettle(const Duration(seconds: 2));
        break;
      }
    }
    await hold(tester, 12); // 屏 5：展开态（成本与仓位 · 批次 · 这只票阿呆怎么说）

    // ── ⑤ 下滚一屏（自选段 / 今天区 / 折叠区）──
    final scrollable = find.byType(Scrollable);
    if (scrollable.evaluate().isNotEmpty) {
      await tester.drag(scrollable.first, const Offset(0, -600));
      await tester.pumpAndSettle(const Duration(seconds: 2));
    }
    await hold(tester, 10); // 屏 6：下滚（自选 / 今天 / 折叠区）
  });
}
