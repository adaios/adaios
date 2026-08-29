import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:adai_app/services/api_service.dart';
import 'package:adai_app/services/sse_client.dart';

/// 流式问答批 2（REVIEW P2-用户2）：askStream 协议解析 / meta 定稿 / 双层降级。
/// SseClient 注入 fake 驱动事件序列，http.Client 注入 MockClient 验证降级回退。
void main() {
  // ── fake SSE：按脚本回放 data: 行 ──

  test('askStream 成功：text 增量回调 + meta 定稿', () async {
    final deltas = <String>[];
    final sse = _ScriptedSse([
      _Event(jsonEncode({'type': 'text', 'content': '你好'})),
      _Event(jsonEncode({'type': 'text', 'content': '，世界'})),
      _Event('[DONE]'),
      _Event(jsonEncode({
        'type': 'meta', 'recordId': 'rec_1', 'summary': '摘要',
        'tags': ['日常'], 'domain': 'life', 'content': '你好，世界',
      })),
      _Event('[DONE]'),
    ]);
    final api = ApiService(baseUrl: 'http://test', sseClient: sse);

    final resp = await api.askStream('如何玩铜？', onDelta: deltas.add);

    expect(deltas, ['你好', '，世界'], reason: '正文增量逐段回调（JSON 回执已由后端剥离）');
    expect(resp.intent, 'question');
    expect(resp.recordId, 'rec_1');
    expect(resp.rawResponse, '你好，世界', reason: 'rawResponse=meta.content（权威定稿）');
    expect(resp.tags, ['日常']);
    expect(resp.domain, 'life');
    expect(sse.lastBody?['content'], '如何玩铜？');
    expect(sse.lastPath, '/api/v1/records/ask-stream');
  });

  test('askStream 流开始前失败 → 降级同步 createRecord 一次', () async {
    final sse = _ScriptedSse([], error: SseHttpException(503, 'backend down'));
    http.Request? fallbackReq;
    final api = ApiService(
      baseUrl: 'http://test',
      client: MockClient((req) async {
        fallbackReq = req;
        return http.Response(
            jsonEncode({
              'intent': 'question', 'recordId': 'r-fb', 'summary': '同步回答',
              'rawResponse': '同步回答', 'tags': [], 'domain': 'life',
            }),
            200,
            headers: {'content-type': 'application/json'});
      }),
      sseClient: sse,
    );

    final resp = await api.askStream('问题', intent: 'question');

    expect(fallbackReq, isNotNull, reason: '未收到任何增量 → 回退旧同步端点');
    expect(fallbackReq!.url.path, '/api/v1/records');
    expect(jsonDecode(fallbackReq!.body)['intent'], 'question',
        reason: '降级与原调用同构：intent 透传');
    expect(resp.recordId, 'r-fb');
  });

  test('askStream 未传 intent → 降级也不带 intent（续问 auto-intent 口径）', () async {
    final sse = _ScriptedSse([], error: SseHttpException(503, 'backend down'));
    http.Request? fallbackReq;
    final api = ApiService(
      baseUrl: 'http://test',
      client: MockClient((req) async {
        fallbackReq = req;
        return http.Response(
            jsonEncode({'intent': 'question', 'recordId': 'r-fb3', 'summary': '兜底',
              'tags': [], 'domain': 'life'}),
            200,
            headers: {'content-type': 'application/json'});
      }),
      sseClient: sse,
    );

    await api.askStream('追问', cardId: 'card_1');

    expect(jsonDecode(fallbackReq!.body)['intent'], isNull,
        reason: '追加续问原走 auto-intent，降级不得强加 question');
  });

  test('askStream 已收增量后中途失败 → 原样抛出（保留草稿可重试）', () async {
    final sse = _ScriptedSse(
      [_Event(jsonEncode({'type': 'text', 'content': '半截'}))],
      error: SseServerException('阿呆说到一半断线了，请重试'),
    );
    var fallbackCalled = false;
    final api = ApiService(
      baseUrl: 'http://test',
      client: MockClient((req) async {
        fallbackCalled = true;
        return http.Response('{}', 200);
      }),
      sseClient: sse,
    );

    await expectLater(
      api.askStream('问题'),
      throwsA(isA<SseServerException>()
          .having((e) => e.toString(), 'message', '阿呆说到一半断线了，请重试')),
      reason: 'error 事件的人话 message 直接透出（无 Exception: 前缀）',
    );
    expect(fallbackCalled, isFalse, reason: '已收增量 → 不降级（避免重复回答）');
  });

  test('askStream error 事件未收增量 → 降级同步端点', () async {
    final sse = _ScriptedSse(
      [_Event(jsonEncode({'type': 'error', 'message': '流式通道不可用'}))],
      complete: true,
    );
    final api = ApiService(
      baseUrl: 'http://test',
      client: MockClient((req) async => http.Response(
          jsonEncode({
            'intent': 'question', 'recordId': 'r-fb2', 'summary': '兜底',
            'tags': [], 'domain': 'life',
          }),
          200,
          headers: {'content-type': 'application/json'})),
      sseClient: sse,
    );

    final resp = await api.askStream('问题');
    expect(resp.recordId, 'r-fb2', reason: 'error 事件视为流开始前失败 → 降级');
  });
}

class _Event {
  _Event(this.data);
  final String data;
}

/// 按脚本回放事件的 SseClient 替身。
class _ScriptedSse extends SseClient {
  _ScriptedSse(this.script, {this.error, this.complete = false}) : super();

  /// complete=true 时脚本回放完正常返回；否则抛 [error]。
  final List<_Event> script;
  final Object? error;
  final bool complete;

  String? lastPath;
  Map<String, dynamic>? lastBody;

  @override
  Future<void> post(
    Uri url, {
    Map<String, String>? headers,
    Object? body,
    required void Function(String data) onData,
  }) async {
    lastPath = url.path;
    if (body is Map) lastBody = Map<String, dynamic>.from(body);
    for (final e in script) {
      onData(e.data);
    }
    if (complete) return;
    final err = error;
    if (err != null) throw err;
  }
}
