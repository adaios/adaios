import 'dart:convert';

import 'package:adai_app/services/api_service.dart';
import 'package:adai_app/widgets/share_token_dialog.dart';
import 'package:flutter/foundation.dart' show debugDefaultTargetPlatformOverride;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

// ────────────────────────────────────────────────────────────────
// ShareTokenDialog（2026-09-13 外部入口批）
//
// 这页是用户**唯一**能拿到外部密钥的地方（后端只存哈希，明文只在签发响应里出现一次）。
// 所以要锁住三件事：
// ① 明文确实被展示出来、且明确告诉用户「只显示这一次」；
// ② 收回（撤销）真的发请求；
// ③ 失败时说人话，不甩状态码。
// ────────────────────────────────────────────────────────────────

http.Response _json(Object data, {int status = 200}) => http.Response.bytes(
      utf8.encode(jsonEncode(data)),
      status,
      headers: {'content-type': 'application/json'},
    );

class _Backend {
  List<Map<String, dynamic>> tokens = [];
  bool issueFails = false;
  bool listFails = false;
  bool issueWithoutToken = false;
  Duration listDelay = Duration.zero;
  int deleteCalls = 0;

  /// 换一把的调用次数（P2-审查4，2026-09-17 B5 批）。
  int rotateCalls = 0;

  final List<http.Request> requests = [];

  static const String plainToken =
      'adai_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef';

  Future<http.Response> handle(http.Request req) async {
    requests.add(req);
    final path = req.url.path;

    if (path.endsWith('/api/v1/auth/tokens') && req.method == 'GET') {
      if (listDelay > Duration.zero) await Future<void>.delayed(listDelay);
      if (listFails) {
        return _json({'error': '这次没读到你的钥匙'}, status: 500);
      }
      return _json({
        'tokens': tokens,
        'availableScopes': [
          {'id': 'learn:digest', 'description': '整理链接', 'allowedRequests': []},
        ],
      });
    }

    if (path.endsWith('/api/v1/auth/tokens') && req.method == 'POST') {
      if (issueFails) {
        return _json({'error': '至少要给它一项权限，不然它什么也做不了'}, status: 400);
      }
      const prefix = 'adai_abcd1234';
      tokens = [
        {
          'id': _idFor(prefix),
          'prefix': prefix,
          'label': '快捷指令',
          'scopes': ['learn:digest'],
          'createdAt': '2026-09-13T10:00:00Z',
          'lastUsedAt': null,
          'expiresAt': null,
        }
      ];
      return _json({
        if (!issueWithoutToken) 'token': plainToken,
        'id': _idFor(prefix),
        'prefix': prefix,
        'label': '快捷指令',
        'scopes': ['learn:digest'],
        'createdAt': '2026-09-13T10:00:00Z',
        'notice': '这串令牌只会显示这一次，请现在就复制走；丢了就撤销重发一把。',
      });
    }

    if (path.endsWith('/rotate') && req.method == 'POST') {
      rotateCalls++;
      const newPrefix = 'adai_99998888';
      tokens = [
        {
          'id': _idFor(newPrefix),
          'prefix': newPrefix,
          'label': '系统分享与快捷指令',
          'scopes': ['learn:digest'],
          'createdAt': '2026-09-17T10:00:00Z',
          'lastUsedAt': null,
          'expiresAt': '2026-12-16T10:00:00Z',
        }
      ];
      return _json({
        'token': plainToken,
        'id': _idFor(newPrefix),
        'prefix': newPrefix,
        'label': '系统分享与快捷指令',
        'scopes': ['learn:digest'],
        'createdAt': '2026-09-17T10:00:00Z',
        'expiresAt': '2026-12-16T10:00:00Z',
        'notice': '换好了：旧的已经立刻失效，这串新令牌只会显示这一次，请现在复制走；有效期重新算 90 天。',
      });
    }

    if (path.contains('/api/v1/auth/tokens/') && req.method == 'DELETE') {
      deleteCalls++;
      final target = path.split('/').last;
      tokens = tokens
          .where((t) => t['prefix'] != target && t['id'] != target)
          .toList();
      return _json({'message': '已撤销，这把令牌立刻失效'});
    }

    return _json({'error': 'not mocked'}, status: 404);
  }

  /// 假 id：64 位十六进制（后端契约：id = 令牌哈希）。
  static String _idFor(String prefix) {
    final hex = prefix.replaceAll(RegExp(r'[^0-9a-f]'), '');
    return (hex * 8).substring(0, 64);
  }
}

/// 假的 App Groups 共享容器（模拟原生 `ShareBridgeHandler`）。
///
/// `saveToken` 的返回值按**回读校验**语义给：写进去读得出来才算 true——
/// 原生侧就是这么做的，测试跟着这个语义走才能锁住「写失败 ≠ 已就绪」。
class _ShareContainer {
  bool available = true;
  String? token;
  String? id;
  int saveCalls = 0;
  int clearCalls = 0;

  Future<Object?> handle(MethodCall call) async {
    switch (call.method) {
      case 'saveToken':
        saveCalls++;
        final args = (call.arguments as Map?) ?? const {};
        if (!available) return false;
        token = args['token'] as String?;
        id = args['id'] as String?;
        return token != null && token!.isNotEmpty;
      case 'clearToken':
        clearCalls++;
        token = null;
        id = null;
        return true;
      case 'status':
        return <String, Object?>{
          'available': available,
          'hasToken': token != null && token!.isNotEmpty,
          if (id != null) 'id': id,
          'appGroup': 'group.com.adaiadai.adaiApp',
        };
    }
    return null;
  }
}

void main() {
  late _Backend backend;
  late ApiService api;
  late _ShareContainer container;
  String? clipboard;

  setUp(() {
    backend = _Backend();
    container = _ShareContainer();
    clipboard = null;
    // 分享扩展的共享容器（App Groups）走独立通道（RFC 20260914）：测试环境装假实现，
    // 否则 iOS 分支一调用就抛 MissingPluginException。
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
            const MethodChannel('adai/share'), (call) => container.handle(call));
    // 剪贴板走 flutter/platform 通道：测试环境必须装假实现，否则 Clipboard.setData
    // 抛 MissingPluginException，复制分支永远走不到。
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'Clipboard.setData') {
        clipboard = (call.arguments as Map)['text'] as String?;
      }
      return null;
    });
    api = ApiService(
      baseUrl: 'http://test',
      userId: 'adai',
      client: MockClient(backend.handle),
    );
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(const MethodChannel('adai/share'), null);
    // 平台覆盖是全局的：不复位会污染后面所有测试（第 ㉔ 条正靠默认平台为「非 iOS」）
    debugDefaultTargetPlatformOverride = null;
  });

  Future<void> pumpDialog(WidgetTester tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: Builder(builder: (context) {
        return TextButton(
          onPressed: () => ShareTokenDialog.show(context, api),
          child: const Text('open'),
        );
      })),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
  }

  /// 弹窗内容较长，按钮常在测试视口（800×600）之外——先滚到可见再点，
  /// 否则 tap 会打在空处（`derived an Offset that would not hit test`）而测试看起来是「逻辑没生效」。
  Future<void> tapText(WidgetTester tester, String text) async {
    final finder = find.text(text);
    await tester.ensureVisible(finder);
    await tester.pumpAndSettle();
    await tester.tap(finder);
    await tester.pumpAndSettle();
  }

  testWidgets('① 还没有钥匙时：说清用途 + 给出「给我一把钥匙」', (tester) async {
    await pumpDialog(tester);

    expect(find.text('把分享接到阿呆'), findsOneWidget);
    expect(find.textContaining('配一次就行'), findsOneWidget,
        reason: '要说清这件事的价值，而不是丢一个技术名词');
    expect(find.textContaining('不是你的登录密码'), findsOneWidget,
        reason: '要让用户明白这是另一类凭据（限权、可收回）');
    expect(find.text('给我一把钥匙'), findsOneWidget);
    expect(find.text('还没有。'), findsOneWidget);
  });

  testWidgets('② 点生成 → 明文被展示 + 明确「只显示这一次」+ 四步配置说明', (tester) async {
    await pumpDialog(tester);

    await tapText(tester, '给我一把钥匙');

    expect(find.text(_Backend.plainToken), findsOneWidget, reason: '明文必须给到用户眼前');
    expect(find.textContaining('只显示这一次'), findsOneWidget,
        reason: '后端只存哈希，不提醒用户复制走就等于弄丢了');
    expect(find.textContaining('还原不出来'), findsOneWidget);

    // 配置步骤可照做：真实地址与请求头字段名都要出现
    expect(find.textContaining('api.adaiadai.com/api/v1/learn/digest'), findsOneWidget);
    expect(find.textContaining('Authorization'), findsOneWidget);
    expect(find.textContaining('在共享表单中显示'), findsOneWidget);
  });

  testWidgets('③ 已有钥匙 → 列表显示用途与最近使用，可「再要一把」', (tester) async {
    backend.tokens = [
      {
        'prefix': 'adai_deadbeef',
        'label': '快捷指令',
        'scopes': ['learn:digest'],
        'createdAt': '2026-09-01T10:00:00Z',
        'lastUsedAt': '2026-09-13T08:30:00Z',
      }
    ];
    await pumpDialog(tester);

    expect(find.textContaining('adai_deadbeef'), findsOneWidget);
    expect(find.textContaining('最近用过：2026-09-13'), findsOneWidget);
    expect(find.text('再要一把'), findsOneWidget);
    expect(find.text('给我一把钥匙'), findsNothing, reason: '已有钥匙时不该再显示首次引导按钮');
  });

  testWidgets('④ 尚未用过 → 如实显示「还没用过」（而不是留空让人猜）', (tester) async {
    backend.tokens = [
      {
        'prefix': 'adai_deadbeef',
        'label': '快捷指令',
        'scopes': ['learn:digest'],
        'createdAt': '2026-09-01T10:00:00Z',
        'lastUsedAt': null,
      }
    ];
    await pumpDialog(tester);

    expect(find.text('还没用过'), findsOneWidget);
  });

  testWidgets('⑤ 点「收回」→ 真的发 DELETE，且这把从列表消失', (tester) async {
    backend.tokens = [
      {
        'prefix': 'adai_deadbeef',
        'label': '快捷指令',
        'scopes': ['learn:digest'],
        'createdAt': '2026-09-01T10:00:00Z',
        'lastUsedAt': null,
      }
    ];
    await pumpDialog(tester);

    await tapText(tester, '收回');

    expect(backend.deleteCalls, 1);
    final deletes = backend.requests.where((r) => r.method == 'DELETE').toList();
    expect(deletes.single.url.path, endsWith('/api/v1/auth/tokens/adai_deadbeef'));
    expect(find.textContaining('adai_deadbeef'), findsNothing, reason: '撤销后列表要刷新掉');
  });

  testWidgets('P2-审查4（2026-09-17 B5 批）：点「换一把」→ 发 POST /rotate，新明文照样只显示这一次',
      (tester) async {
    // 背景：钥匙 90 天到期，而此前只能「先收回、再签发」（中间有空窗，撤完忘签发就断链）；
    // 后端 `rotate` 端点存在但**三端零调用**。本用例钉住：入口真的发 POST，且新明文落到眼前
    // （后端只存哈希，被 spinner 顶掉就是真丢——与签发同一条展示路径）。
    backend.tokens = [
      {
        'id': _Backend._idFor('adai_deadbeef'),
        'prefix': 'adai_deadbeef',
        'label': '快捷指令',
        'scopes': ['learn:digest'],
        'createdAt': '2026-09-01T10:00:00Z',
        'lastUsedAt': null,
        'expiresAt': '2026-09-20T10:00:00Z',
      }
    ];
    await pumpDialog(tester);

    await tapText(tester, '换一把');

    expect(backend.rotateCalls, 1);
    final posts = backend.requests
        .where((r) => r.method == 'POST' && r.url.path.endsWith('/rotate'))
        .toList();
    expect(posts.single.url.path,
        endsWith('/api/v1/auth/tokens/${_Backend._idFor('adai_deadbeef')}/rotate'),
        reason: '优先用 id 轮换（与撤销同一套定位口径）');
    expect(find.textContaining('adai_0123456789abcdef'), findsOneWidget,
        reason: '新明文必须落到用户眼前');
  });

  testWidgets('⑥ 生成失败 → 透出后端人话，不甩状态码', (tester) async {
    backend.issueFails = true;
    backend.tokens = [
      {
        'prefix': 'adai_deadbeef',
        'label': '快捷指令',
        'scopes': ['learn:digest'],
        'createdAt': '2026-09-01T10:00:00Z',
        'lastUsedAt': null,
      }
    ];
    await pumpDialog(tester);

    await tapText(tester, '再要一把');

    expect(find.textContaining('至少要给它一项权限'), findsOneWidget);
  });

  /// 点第 [index] 个「收回」（ensureVisible 后再点，避免视口外点击打空）。
  Future<void> tapRevoke(WidgetTester tester, int index) async {
    final finder = find.text('收回').at(index);
    await tester.ensureVisible(finder);
    await tester.pumpAndSettle();
    await tester.tap(finder);
    await tester.pumpAndSettle();
  }

  /// 不发请求的纯文本/文案断言。
  testWidgets('⑦ 说清代价与边界：整理会花转写额度（会花钱）+ 别把快捷指令分享给别人', (tester) async {
    await pumpDialog(tester);

    expect(find.textContaining('会花钱'), findsOneWidget,
        reason: '这是花钱的动作，必须事先说清');
    expect(find.textContaining('转写额度'), findsOneWidget);
    expect(find.textContaining('别把配好的快捷指令分享给别人'), findsOneWidget);
  });

  testWidgets('⑧ 明文不被 loading 顶掉：签发后 _load 置 loading，明文仍在', (tester) async {
    await pumpDialog(tester);

    // 让签发成功后的那次 _load 慢下来，制造「明文拿到 + 列表在转圈」的窗口
    backend.listDelay = const Duration(seconds: 2);
    final button = find.text('给我一把钥匙');
    await tester.ensureVisible(button);
    await tester.pumpAndSettle();
    await tester.tap(button);
    await tester.pump(); // POST 发出
    await tester.pump(const Duration(milliseconds: 20)); // POST 回包 → 明文 + loading=true

    expect(find.text(_Backend.plainToken), findsOneWidget,
        reason: '明文只出现这一次，渲染顺序必须排在 loading 之前——被 spinner 顶掉就真丢了');

    await tester.pump(const Duration(seconds: 3)); // 放行被延迟的 GET
    await tester.pumpAndSettle();
  });

  testWidgets('⑨ 撤回失败/空 id 之外：收回一把后按钮不灰（_busy 成功分支也复位）', (tester) async {
    backend.tokens = [
      {
        'id': _Backend._idFor('adai_aaaa1111'),
        'prefix': 'adai_aaaa1111',
        'label': '快捷指令',
        'scopes': ['learn:digest'],
        'createdAt': '2026-09-01T10:00:00Z',
        'lastUsedAt': null,
      },
      {
        'id': _Backend._idFor('adai_bbbb2222'),
        'prefix': 'adai_bbbb2222',
        'label': '快捷指令',
        'scopes': ['learn:digest'],
        'createdAt': '2026-09-01T10:00:00Z',
        'lastUsedAt': null,
      },
    ];
    await pumpDialog(tester);

    await tapRevoke(tester, 0);
    expect(backend.deleteCalls, 1);

    // 若 _busy 没有在成功分支复位，按钮会永久置灰 → 第二次点不动
    await tapRevoke(tester, 0);
    expect(backend.deleteCalls, 2, reason: '收回一把之后按钮必须还能用');
    expect(find.textContaining('adai_bbbb2222'), findsNothing);
  });

  testWidgets('⑩ 优先用 id 撤销（后端契约：id 唯一，前缀可能非唯一被拒）', (tester) async {
    final id = _Backend._idFor('adai_deadbeef');
    backend.tokens = [
      {
        'id': id,
        'prefix': 'adai_deadbeef',
        'label': '快捷指令',
        'scopes': ['learn:digest'],
        'createdAt': '2026-09-01T10:00:00Z',
        'lastUsedAt': null,
      }
    ];
    await pumpDialog(tester);

    await tapRevoke(tester, 0);

    final deletes = backend.requests.where((r) => r.method == 'DELETE').toList();
    expect(deletes.single.url.path, endsWith('/api/v1/auth/tokens/$id'));
    expect(deletes.single.url.path, isNot(endsWith('/adai_deadbeef')));
  });

  testWidgets('⑪ 缺 id 时回退前缀撤销（兼容旧后端）', (tester) async {
    backend.tokens = [
      {
        'prefix': 'adai_deadbeef',
        'label': '快捷指令',
        'scopes': ['learn:digest'],
        'createdAt': '2026-09-01T10:00:00Z',
        'lastUsedAt': null,
      }
    ];
    await pumpDialog(tester);

    await tapRevoke(tester, 0);

    final deletes = backend.requests.where((r) => r.method == 'DELETE').toList();
    expect(deletes.single.url.path, endsWith('/api/v1/auth/tokens/adai_deadbeef'));
  });

  testWidgets('⑫ 列表读取失败 ≠ 没有钥匙：说「没读到」+ 重试，绝不渲染「还没有。」', (tester) async {
    backend.listFails = true;
    await pumpDialog(tester);

    expect(find.text('这不代表你一把都没发过，只是这次没读到。'), findsOneWidget);
    expect(find.textContaining('这次没读到你的钥匙'), findsOneWidget, reason: '透出后端人话');
    expect(find.text('还没有。'), findsNothing, reason: '失败不能伪装成空态');
    expect(find.text('再试一次'), findsOneWidget);

    // 重试成功 → 回到正常列表
    backend.listFails = false;
    backend.tokens = [
      {
        'prefix': 'adai_deadbeef',
        'label': '快捷指令',
        'scopes': ['learn:digest'],
        'createdAt': '2026-09-01T10:00:00Z',
        'lastUsedAt': null,
      }
    ];
    await tapText(tester, '再试一次');
    expect(find.textContaining('adai_deadbeef'), findsOneWidget);
    expect(find.text('还没有。'), findsNothing);
  });

  testWidgets('⑬ 签发响应缺 token 明文 → 明确人话（不静默留空）', (tester) async {
    backend.issueWithoutToken = true;
    await pumpDialog(tester);

    await tapText(tester, '给我一把钥匙');

    expect(find.textContaining('没拿到钥匙的明文'), findsOneWidget);
    expect(find.text(_Backend.plainToken), findsNothing);
  });

  testWidgets('⑭ 有效期：expiresAt 有值显示「有效期至 X」，为 null 显示「长期有效」', (tester) async {
    backend.tokens = [
      {
        'prefix': 'adai_aaaa1111',
        'label': '快捷指令',
        'scopes': ['learn:digest'],
        'createdAt': '2026-09-01T10:00:00Z',
        'lastUsedAt': '2026-09-13T08:30:00Z',
        'expiresAt': '2026-12-31T00:00:00Z',
      },
      {
        'prefix': 'adai_bbbb2222',
        'label': '快捷指令',
        'scopes': ['learn:digest'],
        'createdAt': '2026-09-01T10:00:00Z',
        'lastUsedAt': null,
        'expiresAt': null,
      },
    ];
    await pumpDialog(tester);

    expect(find.text('有效期至 2026-12-31'), findsOneWidget);
    expect(find.text('长期有效'), findsOneWidget);
  });

  testWidgets('⑮ 畸形/短日期不崩弹窗（长度守卫）', (tester) async {
    backend.tokens = [
      {
        'prefix': 'adai_aaaa1111',
        'label': '快捷指令',
        'scopes': ['learn:digest'],
        'createdAt': 'bad',
        'lastUsedAt': '99',
        'expiresAt': '20',
      }
    ];
    await pumpDialog(tester);

    expect(find.textContaining('最近用过：99'), findsOneWidget);
    expect(find.text('有效期至 20'), findsOneWidget);
  });

  testWidgets('⑯ 明文没复制就关窗（关闭按钮）→ 二次确认；「再看看」留下明文', (tester) async {
    await pumpDialog(tester);
    await tapText(tester, '给我一把钥匙');
    expect(find.text(_Backend.plainToken), findsOneWidget);

    await tapText(tester, '知道了');
    expect(find.text('钥匙还没复制走'), findsOneWidget, reason: '未复制的明文关窗必须二次确认');

    await tapText(tester, '再看看');
    expect(find.text(_Backend.plainToken), findsOneWidget, reason: '取消关窗 → 明文还在');
    expect(find.text('钥匙还没复制走'), findsNothing);

    // 再关一次，这次确认关掉
    await tapText(tester, '知道了');
    await tapText(tester, '关掉');
    expect(find.text('把分享接到阿呆'), findsNothing);
  });

  testWidgets('⑰ 复制过明文再关窗 → 不再二次确认', (tester) async {
    await pumpDialog(tester);
    await tapText(tester, '给我一把钥匙');

    await tapText(tester, '复制');
    expect(clipboard, _Backend.plainToken, reason: '复制的是明文本身');
    expect(find.textContaining('钥匙已复制'), findsOneWidget, reason: '内联提示（不是被 barrier 遮挡的 SnackBar）');
    expect(find.byType(SnackBar), findsNothing);

    await tapText(tester, '知道了');
    expect(find.text('钥匙还没复制走'), findsNothing);
    expect(find.text('把分享接到阿呆'), findsNothing);
  });

  testWidgets('⑱ 返回键（系统 back）同样走二次确认', (tester) async {
    await pumpDialog(tester);
    await tapText(tester, '给我一把钥匙');
    expect(find.text(_Backend.plainToken), findsOneWidget);

    // 系统返回键（flutter/navigation popRoute）→ 走 PopScope 的二次确认
    await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
      'flutter/navigation',
      const JSONMethodCodec().encodeMethodCall(const MethodCall('popRoute')),
      (_) {},
    );
    await tester.pumpAndSettle();

    expect(find.text('钥匙还没复制走'), findsOneWidget);
    expect(find.text(_Backend.plainToken), findsOneWidget, reason: '确认前明文不能被关掉');
  });

  testWidgets('⑲ 收回成功 → 弹窗内内联提示（不用被 barrier 遮挡的 SnackBar）', (tester) async {
    backend.tokens = [
      {
        'prefix': 'adai_deadbeef',
        'label': '快捷指令',
        'scopes': ['learn:digest'],
        'createdAt': '2026-09-01T10:00:00Z',
        'lastUsedAt': null,
      }
    ];
    await pumpDialog(tester);

    await tapRevoke(tester, 0);

    expect(find.textContaining('收回来了'), findsOneWidget);
    expect(find.byType(SnackBar), findsNothing);
  });
  // ── 2026-09-14 分享扩展批（RFC 20260914）──
  //
  // iOS 的「分享面板里的阿呆阿呆」是**独立进程**，读不到 App 的存储，只能读 App Groups
  // 共享容器。所以这一页在 iOS 上多了「把钥匙搬进容器」这一步，这里锁四件事：
  // 签发→写入、撤销→清空、容器不可用→如实说、非 iOS→整段不出现。
  //
  // ⚠️ 平台覆盖必须用 try/finally 在**测试体内部**复位：Flutter 的测试框架在测试体
  // 结束时就断言「foundation 调试变量已复位」，addTearDown 执行得太晚（2026-09-14 实测踩到）。

  testWidgets('⑳ iOS：签发成功后把明文写进共享容器（用户零操作）', (tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
    try {
      await pumpDialog(tester);

      await tapText(tester, '给我一把钥匙');

      expect(container.saveCalls, 1,
          reason: '签发成功就该顺手把系统分享接上，不该再要用户多做一步');
      expect(container.token, _Backend.plainToken,
          reason: '搬进容器的必须是那把真正的令牌明文');
      expect(container.id, _Backend._idFor('adai_abcd1234'),
          reason: 'id 也必须一起进去——撤销时靠它判断「扩展用的是不是这一把」');
    } finally {
      debugDefaultTargetPlatformOverride = null;
    }
  });

  testWidgets('㉑ iOS：容器里有钥匙 → 说「分享面板已就绪」并指路', (tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
    try {
      container.token = 'adai_existing_token';
      await pumpDialog(tester);

      expect(find.text('分享面板已就绪'), findsOneWidget);
      expect(find.textContaining('那一排里找「阿呆阿呆」'), findsOneWidget);
    } finally {
      debugDefaultTargetPlatformOverride = null;
    }
  });

  testWidgets('㉒ iOS：容器可用但没钥匙 → 说「还差一步」，不谎报已就绪', (tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
    try {
      await pumpDialog(tester);

      expect(find.text('还差一步'), findsOneWidget);
      expect(find.text('分享面板已就绪'), findsNothing,
          reason: '没接上就说没接上——谎报会让用户去分享面板白找一趟');
    } finally {
      debugDefaultTargetPlatformOverride = null;
    }
  });

  testWidgets('㉓ iOS：共享容器不可用 → 如实说 + 保留快捷指令退路', (tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
    try {
      container.available = false;
      await pumpDialog(tester);

      expect(find.text('系统分享这条路还没接上'), findsOneWidget);
      expect(find.textContaining('先用下面的快捷指令'), findsOneWidget,
          reason: '路断了要给另一条路，不能只说「不行」');
      // 快捷指令引导仍在（这条退路是真的可用，不是安慰话）
      expect(find.text('怎么用在快捷指令里'), findsOneWidget);

      // **真的点一次**「发一把」：容器不可用时写进去会失败，必须如实说「没接上」——
      // 不能因为签发本身成功就渲染成「已就绪」（原用例只开窗没点按钮，等于没验证）。
      await tapText(tester, '给我一把钥匙');
      expect(find.textContaining('系统分享那边没接上'), findsOneWidget);
      expect(find.text('分享面板已就绪'), findsNothing);
    } finally {
      debugDefaultTargetPlatformOverride = null;
    }
  });

  testWidgets('㉔ 非 iOS：整段不出现（别让用户找不存在的东西）', (tester) async {
    await pumpDialog(tester);

    expect(find.text('分享面板已就绪'), findsNothing);
    expect(find.text('还差一步'), findsNothing);
    expect(find.textContaining('那一排里找'), findsNothing);
    expect(container.saveCalls, 0, reason: '非 iOS 不该去碰原生通道');
  });

  testWidgets('㉕ iOS：撤销的是扩展那把 → 清空共享容器（不留孤儿钥匙）', (tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
    try {
      const prefix = 'adai_deadbeef';
      final id = _Backend._idFor(prefix);
      backend.tokens = [
        {
          'id': id,
          'prefix': prefix,
          'label': '快捷指令',
          'scopes': ['learn:digest'],
          'createdAt': '2026-09-01T10:00:00Z',
          'lastUsedAt': null,
        }
      ];
      container.token = 'adai_existing_token';
      container.id = id;
      await pumpDialog(tester);

      await tapText(tester, '收回');

      expect(container.clearCalls, 1, reason: '撤销了正在用的那把，容器里不能留');
      expect(container.token, isNull);
    } finally {
      debugDefaultTargetPlatformOverride = null;
    }
  });

  testWidgets('㉗ iOS：容器里有钥匙但已被撤销 → 不谎报「已就绪」', (tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
    try {
      // 容器里还躺着字符串，但它已经不在令牌列表里了（被撤销 / 网页端撤销 / 删号）。
      container.token = 'adai_orphan_token';
      container.id = 'a' * 64;
      backend.tokens = [];
      await pumpDialog(tester);

      expect(find.text('接过一次，但好像已经失效了'), findsOneWidget);
      expect(find.text('分享面板已就绪'), findsNothing,
          reason: '容器里有字符串 ≠ 那把钥匙还有效——谎报已就绪会让用户去分享面板白找一趟');
    } finally {
      debugDefaultTargetPlatformOverride = null;
    }
  });

  testWidgets('㉖ iOS：撤销的是别的把 → 容器里的钥匙不动（不误伤）', (tester) async {
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
    try {
      const prefix = 'adai_deadbeef';
      backend.tokens = [
        {
          'id': _Backend._idFor(prefix),
          'prefix': prefix,
          'label': '快捷指令',
          'scopes': ['learn:digest'],
          'createdAt': '2026-09-01T10:00:00Z',
          'lastUsedAt': null,
        }
      ];
      // 容器里躺着的是**另一把**（id 不同）——撤销它不该误伤扩展那条链路
      container.token = 'adai_existing_token';
      container.id = 'f' * 64;
      await pumpDialog(tester);

      await tapText(tester, '收回');

      expect(container.clearCalls, 0);
      expect(container.token, 'adai_existing_token');
    } finally {
      debugDefaultTargetPlatformOverride = null;
    }
  });
}
