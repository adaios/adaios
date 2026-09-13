import 'dart:convert';

import 'package:adai_app/services/api_service.dart';
import 'package:adai_app/widgets/share_token_dialog.dart';
import 'package:flutter/material.dart';
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
  int deleteCalls = 0;
  final List<http.Request> requests = [];

  static const String plainToken =
      'adai_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef';

  Future<http.Response> handle(http.Request req) async {
    requests.add(req);
    final path = req.url.path;

    if (path.endsWith('/api/v1/auth/tokens') && req.method == 'GET') {
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
          'prefix': prefix,
          'label': '快捷指令',
          'scopes': ['learn:digest'],
          'createdAt': '2026-09-13T10:00:00Z',
          'lastUsedAt': null,
        }
      ];
      return _json({
        'token': plainToken,
        'prefix': prefix,
        'label': '快捷指令',
        'scopes': ['learn:digest'],
        'createdAt': '2026-09-13T10:00:00Z',
        'notice': '这串令牌只会显示这一次，请现在就复制走；丢了就撤销重发一把。',
      });
    }

    if (path.contains('/api/v1/auth/tokens/') && req.method == 'DELETE') {
      deleteCalls++;
      final prefix = path.split('/').last;
      tokens = tokens.where((t) => t['prefix'] != prefix).toList();
      return _json({'message': '已撤销，这把令牌立刻失效'});
    }

    return _json({'error': 'not mocked'}, status: 404);
  }
}

void main() {
  late _Backend backend;
  late ApiService api;

  setUp(() {
    backend = _Backend();
    api = ApiService(
      baseUrl: 'http://test',
      userId: 'adai',
      client: MockClient(backend.handle),
    );
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
}
