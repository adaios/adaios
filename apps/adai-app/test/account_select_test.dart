import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:adai_app/pages/account_select_page.dart';
import 'package:adai_app/services/api_service.dart';

// ────────────────────────────────────────────────────────────────
// 账号选号页 widget 测试（#177 战略：多账号前端零测试缺口）
//
// 覆盖：loading 态 / 空态可执行重试 / 错误态重试回调 / 选择回调触发。
// ApiService 注入 MockClient 走假后端，不依赖真实 HTTP。
// ────────────────────────────────────────────────────────────────

http.Response _json(Object data, {int status = 200}) => http.Response.bytes(
      utf8.encode(jsonEncode(data)),
      status,
      headers: {'content-type': 'application/json'},
    );

/// 假后端：/api/v1/accounts/available 返回可配置账号 userId 列表（#215 最小集）。
MockClientHandler _availableHandler(List<String> userIds) {
  return (req) async {
    if (req.url.path.endsWith('/accounts/available')) {
      return _json(userIds);
    }
    return _json({'error': 'not found'}, status: 404);
  };
}

Widget _wrap(ApiService api, {String? currentUserId, required void Function(String) onSelect}) {
  return MaterialApp(
    home: AccountSelectPage(
      api: api,
      currentUserId: currentUserId,
      onSelect: onSelect,
    ),
  );
}

void main() {
  testWidgets('loading 态显示加载中（请求挂起时不退出 loading）', (tester) async {
    // 永不 resolve 的 future：锁定 loading 帧，验证 loading 文字渲染
    final never = Completer<http.Response>().future;
    final api = ApiService(baseUrl: 'http://test', client: MockClient((req) => never));
    await tester.pumpWidget(_wrap(api, onSelect: (_) {}));
    await tester.pump(); // 只渲染一帧，不等 settle（请求挂起）

    expect(find.text('加载中…'), findsOneWidget);

    // 清理：卸载 widget 树，避免遗留挂起状态
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('有账号 → 显示账号行 + 当前标记（#215 无角色标记）', (tester) async {
    final api = ApiService(baseUrl: 'http://test', client: MockClient(
      _availableHandler(['adai', 'alice']),
    ));
    await tester.pumpWidget(_wrap(api, currentUserId: 'adai', onSelect: (_) {}));
    await tester.pumpAndSettle();

    expect(find.text('adai'), findsOneWidget);
    expect(find.text('alice'), findsOneWidget);
    // #215：available 最小集只返回 userId，无 admin/普通用户角色标记
    expect(find.text('管理员'), findsNothing);
    expect(find.text('普通用户'), findsNothing);
    expect(find.text('当前'), findsOneWidget);     // 当前账号标绿
  });

  testWidgets('点击账号行 → 触发 onSelect 回调', (tester) async {
    final api = ApiService(baseUrl: 'http://test', client: MockClient(
      _availableHandler(['adai']),
    ));
    String? selected;
    await tester.pumpWidget(_wrap(api, onSelect: (u) => selected = u));
    await tester.pumpAndSettle();

    await tester.tap(find.text('adai'));
    expect(selected, 'adai');
  });

  testWidgets('空账号 → 空态文案 + 「重新加载」可点击（P18 可执行）', (tester) async {
    final api = ApiService(baseUrl: 'http://test', client: MockClient(
      _availableHandler([]),
    ));
    await tester.pumpWidget(_wrap(api, onSelect: (_) {}));
    await tester.pumpAndSettle();

    expect(find.text('暂无可用账号，请先在后台创建账号'), findsOneWidget);
    // 空态提供可执行重试动作：点击重载不抛异常，仍回空态
    await tester.tap(find.text('重新加载'));
    await tester.pumpAndSettle();
    expect(find.text('暂无可用账号，请先在后台创建账号'), findsOneWidget);
  });

  testWidgets('错误态 → 人话错误文案 + 重试回调重新加载成功', (tester) async {
    var calls = 0;
    final api = ApiService(baseUrl: 'http://test', client: MockClient((req) async {
      calls++;
      if (calls == 1) return _json({'error': 'boom'}, status: 500);
      return _json(['adai']);
    }));
    await tester.pumpWidget(_wrap(api, onSelect: (_) {}));
    await tester.pumpAndSettle();

    expect(find.text('加载失败，请重试'), findsOneWidget);
    await tester.tap(find.text('重试'));
    await tester.pumpAndSettle();
    expect(find.text('adai'), findsOneWidget); // 重试后加载成功
  });
}
