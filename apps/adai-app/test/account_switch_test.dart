import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:adai_app/main.dart';
import 'package:adai_app/pages/account_select_page.dart';
import 'package:adai_app/services/api_service.dart';
import 'package:adai_app/services/user_store.dart';

// ────────────────────────────────────────────────────────────────
// 多账号切换链路测试（#177 战略剩余）：切换账号重建整树（ValueKey 换 ApiService、
// 缓存清空）/ UserStore 条件导出双实现 / available DTO / 持久化降级 / 双击防重入。
//
// RootApp/DualWorldShell 新增 apiFactory 注入（REVIEW #177），
// 用 MockClient 假后端驱动完整切换链路，不依赖真实 HTTP。
// ────────────────────────────────────────────────────────────────

http.Response _json(Object data, {int status = 200}) => http.Response.bytes(
      utf8.encode(jsonEncode(data)),
      status,
      headers: {'content-type': 'application/json'},
    );

/// 全量假后端：记录每个请求的 X-User-Id，覆盖选号页 + 壳层（Feed/Brief/Launcher）路由。
/// 返回一个新 ApiService 实例（每次调用 = 新缓存 = 缓存清空语义）。
ApiService _makeApi(String userId, List<String> seen) {
  return ApiService(
    baseUrl: 'http://test',
    userId: userId,
    client: MockClient((req) async {
      seen.add(req.headers['X-User-Id'] ?? '');
      final path = req.url.path;
      if (path.endsWith('/accounts/available')) return _json(['adai', 'bob']);
      if (path.endsWith('/brief')) return _json({'content': '今日概览'});
      if (path.endsWith('/feed')) return _json({'entries': [], 'totalToday': 0});
      if (path.endsWith('/identity')) return _json({'name': '测试', 'preferences': <String, dynamic>{}});
      if (path.endsWith('/tags')) return _json({'tags': [], 'total': 0});
      if (path.endsWith('/timeline')) return _json([]);
      if (path.endsWith('/memory/count')) return _json({'count': 0});
      return _json({'error': 'not found'}, status: 404);
    }),
  );
}

void main() {
  group('#177 首屏选号 → 切换账号重建整树', () {
    testWidgets('选定账号 → DualWorldShell 以新 ValueKey + 新 ApiService（userId 换、缓存清空）', (tester) async {
      SharedPreferences.setMockInitialValues({});
      final seen = <String>[];
      await tester.pumpWidget(RootApp(
        userId: 'default',
        needsSelect: true,
        apiFactory: (userId) => _makeApi(userId, seen),
      ));
      await tester.pumpAndSettle();

      // 首屏选号页
      expect(find.byType(AccountSelectPage), findsOneWidget);

      // 选 bob → 重建整树：home 从选号页切到 DualWorldShell，ValueKey 换 bob
      await tester.tap(find.text('bob'));
      await tester.pumpAndSettle();

      expect(find.byType(AccountSelectPage), findsNothing);
      expect(find.byType(DualWorldShell), findsOneWidget);
      final shell = tester.widget<DualWorldShell>(find.byType(DualWorldShell));
      expect(shell.userId, 'bob');
      expect(shell.key, const ValueKey('bob'));
      // 新 ApiService(userId: bob) 实例 → 后续请求带 X-User-Id: bob
      expect(seen, contains('bob'));
    });
  });

  group('#177 双击防重入', () {
    testWidgets('快速双击账号 → 只切一次（guard 挡住第二次），不重复建树/崩溃', (tester) async {
      SharedPreferences.setMockInitialValues({});
      final seen = <String>[];
      await tester.pumpWidget(RootApp(
        userId: 'default',
        needsSelect: true,
        apiFactory: (userId) => _makeApi(userId, seen),
      ));
      await tester.pumpAndSettle();
      expect(find.byType(AccountSelectPage), findsOneWidget);

      // 双击同一行：第二次被 _handlingSelect 短路
      await tester.tap(find.text('bob'));
      await tester.tap(find.text('bob'));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
      expect(find.byType(DualWorldShell), findsOneWidget);
      expect(tester.widget<DualWorldShell>(find.byType(DualWorldShell)).userId, 'bob');
    });
  });

  group('#177 持久化降级（resolveUserSelection）', () {
    test('URL ?userId= 优先于持久化', () {
      final s = resolveUserSelection(urlUserId: 'alice', savedUserId: 'bob');
      expect(s.userId, 'alice');
      expect(s.needsSelect, isFalse);
      // 即使 saved 为 default（无效）也压不过 URL
      final s2 = resolveUserSelection(urlUserId: 'alice', savedUserId: 'default');
      expect(s2.userId, 'alice');
      expect(s2.needsSelect, isFalse);
    });

    test('无 URL：saved 有效 → 用 saved 且不选号', () {
      final s = resolveUserSelection(urlUserId: 'default', savedUserId: 'bob');
      expect(s.userId, 'bob');
      expect(s.needsSelect, isFalse);
    });

    test('无 URL：saved 为 default（迁移后移除）→ 视为无效 → 强制首屏选号', () {
      final s = resolveUserSelection(urlUserId: 'default', savedUserId: 'default');
      expect(s.userId, 'default');
      expect(s.needsSelect, isTrue);
    });

    test('无 URL：无 saved → 强制首屏选号', () {
      final s = resolveUserSelection(urlUserId: 'default', savedUserId: null);
      expect(s.userId, 'default');
      expect(s.needsSelect, isTrue);
    });
  });

  group('#177 UserStore 条件导出（io 分支）', () {
    test('save/load round-trip（无记录 → null；保存后读回）', () async {
      SharedPreferences.setMockInitialValues({});
      expect(await UserStore.loadUserId(), isNull);
      await UserStore.saveUserId('alice');
      expect(await UserStore.loadUserId(), 'alice');
    });

    test('clearUrlUserId 在 io 平台为空实现，不抛异常（web 分支由 dart.library.js_interop 条件导出）', () async {
      await UserStore.clearUrlUserId();
    });
  });
}
