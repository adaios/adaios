import 'dart:convert';

import 'package:adai_app/services/api_service.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

/// 记录来源标记（P1-安全1 剩余项，2026-09-17 B4 批）。
///
/// 背景：Siri「记一笔」/ 快捷指令 / `adai://record` 这些外部入口**本身无凭据**，
/// 检测不出内容是谁塞进来的；`POST /records` 现在支持带一个 `source` 标记留痕。
/// 本用例钉住两件事：① 外部入口要真的把 `external_entry` 发出去；② 手输**不带该字段**
/// ——存量口径不变（用户拍板 D2「只给非手输入口加，不动存量」）。
void main() {
  test('createRecord：外部入口带 source，手输不带（存量口径不变）', () async {
    final bodies = <Map<String, dynamic>>[];
    final api = ApiService(
      baseUrl: 'http://test',
      userId: 'adai',
      client: MockClient((req) async {
        bodies.add(jsonDecode(req.body) as Map<String, dynamic>);
        return http.Response(
          jsonEncode({
            'intent': 'log',
            'recordId': 'rec_1',
            'summary': '记下了',
            'tags': <String>[],
            'domain': 'life',
          }),
          200,
          headers: {'content-type': 'application/json; charset=utf-8'},
        );
      }),
    );

    await api.createRecord('Siri 塞进来的一句', source: 'external_entry');
    await api.createRecord('手输的一句');

    expect(bodies, hasLength(2));
    expect(bodies[0]['source'], 'external_entry', reason: '外部入口要留痕');
    expect(bodies[1].containsKey('source'), isFalse,
        reason: '手输不带该字段 → 后端按 user_input 记（不改变存量语义）');
  });
}
