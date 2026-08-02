import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:adai_entry/entry_page.dart';
import 'package:adai_entry/services/api_config.dart';
import 'package:adai_entry/services/api_service.dart';

const _jsonHeaders = {'content-type': 'application/json; charset=utf-8'};

http.Response _ok(List<Map<String, dynamic>> accounts) =>
    http.Response(jsonEncode(accounts), 200, headers: _jsonHeaders);

EntryApiService _apiWith(List<Map<String, dynamic>> accounts) =>
    EntryApiService(client: MockClient((req) async => _ok(accounts)));

void main() {
  final accounts = [
    {'userId': 'adai', 'role': 'admin', 'enabled': true, 'createdAt': '2026-08-02'},
    {'userId': 'alice', 'role': 'user', 'enabled': true, 'createdAt': '2026-08-02'},
    {'userId': 'bob', 'role': 'user', 'enabled': false, 'createdAt': '2026-08-02'},
  ];

  /// 泵出 EntryPage（注入 fake api + capture 跳转），等两帧让异步加载完成。
  Future<void> pumpEntry(
    WidgetTester tester, {
    required EntryApiService api,
    required List<String> captured,
  }) async {
    await tester.pumpWidget(MaterialApp(
      home: EntryPage(api: api, onNavigate: captured.add),
    ));
    await tester.pump();
    await tester.pump();
  }

  testWidgets('只显示 enabled 账号，disabled 不渲染', (tester) async {
    await pumpEntry(tester, api: _apiWith(accounts), captured: <String>[]);

    expect(find.text('adai'), findsOneWidget);
    expect(find.text('alice'), findsOneWidget);
    expect(find.text('bob'), findsNothing);
  });

  testWidgets('点 admin 账号 → 跳 adai-admin', (tester) async {
    final captured = <String>[];
    await pumpEntry(tester, api: _apiWith(accounts), captured: captured);

    await tester.tap(find.text('adai'));
    await tester.pump();
    expect(captured, ['${ApiConfig.adminUrl}/?userId=adai']);
  });

  testWidgets('点普通用户 → 跳 adai-app', (tester) async {
    final captured = <String>[];
    await pumpEntry(tester, api: _apiWith(accounts), captured: captured);

    await tester.tap(find.text('alice'));
    await tester.pump();
    expect(captured, ['${ApiConfig.appUrl}/?userId=alice']);
  });

  testWidgets('加载失败显示错误态，重试成功后出账号列表', (tester) async {
    var fail = true;
    final api = EntryApiService(client: MockClient((req) async {
      if (fail) {
        fail = false;
        return http.Response('boom', 500);
      }
      return _ok(accounts);
    }));
    final captured = <String>[];

    await pumpEntry(tester, api: api, captured: captured);
    expect(find.textContaining('加载账号失败'), findsOneWidget);
    expect(find.text('重试'), findsOneWidget);
    expect(find.text('adai'), findsNothing);

    await tester.tap(find.text('重试'));
    await tester.pump();
    await tester.pump();
    expect(find.text('adai'), findsOneWidget);
  });
}
