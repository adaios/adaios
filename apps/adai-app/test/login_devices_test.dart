import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:adai_app/pages/login_devices_page.dart';
import 'package:adai_app/services/api_service.dart';

/// 登录设备页（RFC 20260914 L2）：看得见 + 撤得掉。
void main() {
  testWidgets('列表渲染：设备名 / 当前设备标记 / 只有非当前设备能撤销', (tester) async {
    await tester.pumpWidget(MaterialApp(home: LoginDevicesPage(api: _api())));
    await tester.pumpAndSettle();

    expect(find.text('iPhone 15'), findsOneWidget);
    expect(find.text('当前设备'), findsOneWidget);
    // 两台设备：当前那台不给「撤销」（后端也会拒），另一台可以
    expect(find.text('撤销'), findsOneWidget);
  });

  testWidgets('撤销：确认弹窗 → 调 DELETE → 刷新列表', (tester) async {
    final deleted = <String>[];
    await tester.pumpWidget(MaterialApp(
      home: LoginDevicesPage(api: _api(onDelete: deleted.add)),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.text('撤销'));
    await tester.pumpAndSettle();
    expect(find.text('撤销这台设备？'), findsOneWidget, reason: '撤销是不可逆动作，必须先确认');

    await tester.tap(find.text('撤销').last); // 弹窗里的确认按钮
    await tester.pumpAndSettle();

    expect(deleted, ['oldhash']);
  });

  testWidgets('撤销失败：把后端人话原样给用户（不吞成「操作失败」）', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: LoginDevicesPage(api: _api(
          deleteStatus: 400, deleteError: '这个标识对应多台设备，请用完整的设备 id')),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.text('撤销'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('撤销').last);
    await tester.pumpAndSettle();

    expect(find.text('这个标识对应多台设备，请用完整的设备 id'), findsOneWidget);
  });

  testWidgets('加载失败：错误态 + 可重试', (tester) async {
    await tester.pumpWidget(MaterialApp(home: LoginDevicesPage(api: _api(listStatus: 500))));
    await tester.pumpAndSettle();

    expect(find.text('重试'), findsOneWidget);
  });
}

/// 造一个只会回答 /auth/sessions 的 ApiService。
ApiService _api({
  int listStatus = 200,
  int deleteStatus = 200,
  String deleteError = '',
  void Function(String id)? onDelete,
}) {
  return ApiService(
    userId: 'adai',
    token: 'tok_1',
    client: MockClient((request) async {
      final path = request.url.path;
      if (path == '/api/v1/auth/sessions' && request.method == 'GET') {
        if (listStatus != 200) {
          return http.Response('{"error":"服务器出问题了"}', listStatus,
              headers: {'content-type': 'application/json; charset=utf-8'});
        }
        return http.Response(
          '{"sessions":['
          '{"id":"currhash","device":{"name":"iPhone 15","platform":"ios","appVersion":"3.67.0"},'
          '"createdAt":"2026-09-14T02:00:00Z","lastSeenAt":"2026-09-14T04:00:00Z",'
          '"expiresAt":"2026-10-14T02:00:00Z","current":true},'
          '{"id":"oldhash","device":{"name":"iPad","platform":"ios","appVersion":"3.60.0"},'
          '"createdAt":"2026-09-01T02:00:00Z","lastSeenAt":"2026-09-02T02:00:00Z",'
          '"expiresAt":"2026-10-01T02:00:00Z","current":false}'
          ']}',
          200,
          headers: {'content-type': 'application/json; charset=utf-8'},
        );
      }
      if (path.startsWith('/api/v1/auth/sessions/') && request.method == 'DELETE') {
        final id = Uri.decodeComponent(path.split('/').last);
        onDelete?.call(id);
        if (deleteStatus != 200) {
          return http.Response('{"error":"$deleteError"}', deleteStatus,
              headers: {'content-type': 'application/json; charset=utf-8'});
        }
        return http.Response('{"message":"已撤销，这台设备需要重新登录"}', 200,
            headers: {'content-type': 'application/json; charset=utf-8'});
      }
      return http.Response('not found', 404);
    }),
  );
}
