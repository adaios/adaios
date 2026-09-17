import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:adai_web/pages/feed_page.dart';
import 'package:adai_web/services/api_service.dart';

/// UTF-8 JSON 响应（MockClient 默认 Latin-1，中文会炸 → 显式 charset=utf-8）。
http.Response _json(Object body, {int status = 200}) => http.Response(
      jsonEncode(body),
      status,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );

/// 折叠卡响应（P2-UI12：同分钟同向成交 → 一条卡 + mergedIds 全量原始 id）。
Map<String, dynamic> _mergedEntry() => {
      'type': 'record',
      'id': 'rec_1',
      'title': '买入 3 笔',
      'content': '买入 A\n买入 B\n买入 C',
      'tags': const <String>[],
      'time': '09:31',
      'date': '09-16',
      'domain': 'trading',
      'mergedIds': const ['rec_1', 'rec_2', 'rec_3'],
    };

void main() {
  Future<void> pump(WidgetTester tester, ApiService api) async {
    await tester.binding.setSurfaceSize(const Size(1200, 900));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: FeedPage(api: api)),
    ));
    await tester.pumpAndSettle();
  }

  /// 卡片右上「更多」→ 删除 → 二次确认（弹窗里那个 TextButton「删除」）。
  Future<void> tapDelete(WidgetTester tester) async {
    await tester.tap(find.byIcon(Icons.more_vert_rounded).first);
    await tester.pumpAndSettle();
    await tester.tap(find.text('删除').last);
    await tester.pumpAndSettle();
    expect(find.textContaining('此操作不可恢复'), findsOneWidget, reason: '删除前二次确认');
    await tester.tap(find.widgetWithText(TextButton, '删除'));
    await tester.pumpAndSettle();
  }

  /// Feed 页首屏所需的最小 mock；delete 由调用方给（记录每次 DELETE 的 id）。
  ApiService api({
    required List<String> deleted,
    Set<String> failIds = const {},
    bool merged = true,
  }) {
    return ApiService(
      baseUrl: 'http://test',
      userId: 'adai',
      client: MockClient((req) async {
        final p = req.url.path;
        if (p == '/api/v1/brief') return _json({'content': ''});
        if (p == '/api/v1/brief/cached') return _json({'content': ''});
        if (p == '/api/v1/feed') {
          return _json({
            'entries': [merged ? _mergedEntry() : {..._mergedEntry(), 'mergedIds': null}],
            'totalToday': 1,
          });
        }
        if (p == '/api/v1/tags') return _json({'tags': [], 'total': 0, 'updatedAt': ''});
        if (p.startsWith('/api/v1/records/') && req.method == 'DELETE') {
          final id = p.split('/').last;
          if (failIds.contains(id)) return _json({'error': 'boom'}, status: 500);
          deleted.add(id);
          return _json({'deleted': true});
        }
        return _json({'error': 'not mocked'}, status: 404);
      }),
    );
  }

  testWidgets('折叠卡删除：mergedIds 逐条删全（否则刷新后又回来 = 假删除）', (tester) async {
    final deleted = <String>[];
    await pump(tester, api(deleted: deleted));

    expect(find.textContaining('买入 A'), findsOneWidget, reason: '卡片在（record 卡正文渲染的是内容，标题不上屏）');
    await tapDelete(tester);

    expect(deleted.toSet(), {'rec_1', 'rec_2', 'rec_3'}, reason: '折叠进的 3 笔都要删掉，不多不少');
    expect(deleted.length, 3, reason: '含本卡 id 在内不重复请求');
    expect(find.textContaining('买入 A'), findsNothing, reason: '删完卡片本地移除');
  });

  testWidgets('普通卡（mergedIds 为空/null）：只删自己一条', (tester) async {
    final deleted = <String>[];
    await pump(tester, api(deleted: deleted, merged: false));

    await tapDelete(tester);

    expect(deleted, ['rec_1']);
  });

  testWidgets('折叠卡删不全：如实告知 + 卡片不假消失（不许假装成功）', (tester) async {
    final deleted = <String>[];
    await pump(tester, api(deleted: deleted, failIds: {'rec_2'}));

    await tapDelete(tester);

    expect(deleted.toSet(), {'rec_1', 'rec_3'}, reason: '能删的先删');
    expect(find.textContaining('还有 1 笔没删掉'), findsOneWidget, reason: '如实说还差几笔');
    expect(find.textContaining('买入 A'), findsOneWidget, reason: '没删全 → 不本地移除（不假删除）');

    // flush SnackBar 自动关闭计时器（否则 test 结束报 pending timer）
    await tester.pump(const Duration(seconds: 4));
    await tester.pumpAndSettle();
  });
}
