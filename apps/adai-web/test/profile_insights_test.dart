import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:adai_web/pages/profile_page.dart';
import 'package:adai_web/services/api_service.dart';

/// 「阿呆对你的了解」测试（2026-09-16「第一次见面」批）。
///
/// 覆盖：观察渲染（含置信度/起点）、零观察引导、确认回流 identity.preferences。
/// 这些数据一直在 memory 里自动生长，此前零出口（REVIEW P2-认知3）。
void main() {
  testWidgets('档案页「阿呆对你的了解」：渲染观察 + 置信度 + 起点', (tester) async {
    final api = _api();

    await tester.pumpWidget(MaterialApp(home: Scaffold(body: ProfilePage(api: api))));
    await tester.pumpAndSettle();

    expect(find.text('阿呆对你的了解'), findsOneWidget);
    expect(find.text('已经留意到 2 件事 · 从 2026-07-22 开始'), findsOneWidget);
    expect(find.text('用户常把科幻概念和现实人物类比推演'), findsOneWidget);
    expect(find.text('对《三体》战略思想有持续兴趣'), findsOneWidget);
    expect(find.text('90%'), findsOneWidget);
    expect(find.text('行为模式'), findsOneWidget);
    expect(find.text('偏好'), findsOneWidget);
    // 两条都还没确认过
    expect(find.text('✓ 对'), findsNWidgets(2));
    expect(find.text('✓ 已记进档案'), findsNothing);
  });

  testWidgets('档案页：确认一条观察 → 写回 identity.preferences（观察→确认→记住）', (tester) async {
    final requests = <http.Request>[];
    final api = _api(record: requests);

    await tester.pumpWidget(MaterialApp(home: Scaffold(body: ProfilePage(api: api))));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(
        const ValueKey('confirm-insight-用户常把科幻概念和现实人物类比推演')));
    await tester.pumpAndSettle();

    final put = requests.where((r) => r.method == 'PUT').toList();
    expect(put.length, 1, reason: '确认必须真的写回档案（PUT /identity）');
    final body = jsonDecode(put.first.body) as Map<String, dynamic>;
    final prefs = body['preferences'] as Map<String, dynamic>;
    expect(prefs['用户常把科幻概念和现实人物类比推演'], '已确认',
        reason: '确认后该观察要进 preferences，从此随档案注入 prompt');
    // 原有的 name/tags 不能被确认动作冲掉
    expect(body['name'], '小明');
    expect(body['tags'], ['投资']);

    expect(find.text('✓ 已记进档案'), findsOneWidget);
    expect(find.text('✓ 对'), findsOneWidget); // 另一条仍待确认
  });

  testWidgets('档案页：全新用户零观察 → 引导文案而非空壳', (tester) async {
    final api = _apiEmpty();

    await tester.pumpWidget(MaterialApp(home: Scaffold(body: ProfilePage(api: api))));
    await tester.pumpAndSettle();

    expect(find.text('我还不认识你。多聊几句，这里会长出我对你的了解。'), findsOneWidget);
    expect(find.text('✓ 对'), findsNothing);
  });

  testWidgets('档案页头像：预设可选 + 保存写进 preferences.avatar（2026-09-16 预设方案）',
      (tester) async {
    final requests = <http.Request>[];
    final api = _api(record: requests);

    await tester.pumpWidget(MaterialApp(home: Scaffold(body: ProfilePage(api: api))));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const ValueKey('avatar-moon')));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('保存档案'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('保存档案'));
    await tester.pumpAndSettle();

    final put = requests.where((r) => r.method == 'PUT').toList();
    expect(put, isNotEmpty, reason: '选头像后保存要真的写回档案');
    final prefs =
        (jsonDecode(put.last.body) as Map<String, dynamic>)['preferences']
            as Map<String, dynamic>;
    expect(prefs['avatar'], 'moon');
  });

  testWidgets('档案页头像：选了预设则身份卡显示该头像（而非名字首字）', (tester) async {
    final api = _api(identityPrefs: {'avatar': 'whale'});

    await tester.pumpWidget(MaterialApp(home: Scaffold(body: ProfilePage(api: api))));
    await tester.pumpAndSettle();

    expect(find.text('🐳'), findsWidgets, reason: '身份卡要显示选中的预设头像');
  });

  testWidgets('档案页：insights 端点不可用（旧后端 404）→ 静默降级，档案主体照常', (tester) async {
    final api = _api(insightsStatus: 404);

    await tester.pumpWidget(MaterialApp(home: Scaffold(body: ProfilePage(api: api))));
    await tester.pumpAndSettle();

    expect(find.text('阿呆对你的了解'), findsNothing, reason: '拉不到就不占位');
    // 档案主体（姓名输入框）不受影响
    expect(find.text('阿呆怎么称呼你'), findsOneWidget);
  });
}

ApiService _api({
  List<http.Request>? record,
  int insightsStatus = 200,
  Map<String, String> identityPrefs = const {},
}) {
  return ApiService(
    userId: 'adai',
    token: 'tok_1',
    client: MockClient((request) async {
      record?.add(request);
      switch (request.url.path) {
        case '/api/v1/identity':
          if (request.method == 'PUT') {
            final body = jsonDecode(request.body) as Map<String, dynamic>;
            return _json({
              'name': body['name'] ?? '',
              'preferences': body['preferences'] ?? <String, String>{},
              'rules': body['rules'] ?? <String, String>{},
              'tags': body['tags'] ?? <String>[],
            });
          }
          return _json({
            'name': '小明',
            'preferences': identityPrefs,
            'rules': <String, String>{},
            'tags': ['投资'],
          });
        case '/api/v1/memory/insights':
          if (insightsStatus != 200) {
            return http.Response('{"error":"not found"}', insightsStatus);
          }
          return _json({
            'total': 2,
            'patternCount': 1,
            'preferenceCount': 1,
            'observedSince': '2026-07-22',
            'insights': [
              {
                'kind': 'pattern',
                'content': '用户常把科幻概念和现实人物类比推演',
                'confidence': 0.9,
              },
              {
                'kind': 'preference',
                'content': '对《三体》战略思想有持续兴趣',
                'confidence': 0.85,
              },
            ],
          });
      }
      return http.Response('{}', 404);
    }),
  );
}

ApiService _apiEmpty() {
  return ApiService(
    userId: 'newbie',
    token: 'tok_1',
    client: MockClient((request) async {
      if (request.url.path == '/api/v1/identity') {
        return _json({
          'name': '',
          'preferences': <String, String>{},
          'rules': <String, String>{},
          'tags': <String>[],
        });
      }
      if (request.url.path == '/api/v1/memory/insights') {
        return _json({
          'total': 0,
          'patternCount': 0,
          'preferenceCount': 0,
          'observedSince': null,
          'insights': <Map<String, dynamic>>[],
        });
      }
      return http.Response('{}', 404);
    }),
  );
}

http.Response _json(Map<String, dynamic> body) => http.Response(
      jsonEncode(body),
      200,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );
