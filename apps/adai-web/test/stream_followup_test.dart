import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:adai_web/pages/feed_page.dart';
import 'package:adai_web/services/api_service.dart';
import 'package:adai_web/services/sse_client.dart';

/// 续问流式定稿回归（2026-08-30 用户实测重复回答）：
/// meta 定稿必须原位替换流式草稿 turn，不得追加——否则同一回答渲染两遍，
/// 且 end 会话时客户端上报 turns 携带重复（生产实证：服务端 6 turn / 客户端 8 turn）。
///
/// 门控回放：delta 渲染出草稿后挂起，放行才回 meta——草稿 turn 必须真实存在，
/// 否则（同步完成时 90ms 节流 Timer 被 cancel）草稿从未渲染，复现不了追加型重复。
void main() {
  testWidgets('续问流式：meta 定稿原位替换草稿 turn，回答不重复', (tester) async {
    await tester.binding.setSurfaceSize(const Size(1200, 2000));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    final firstRound = _Round('首问草稿', '首问定稿回答');
    final followRound = _Round('续问草稿回答', '续问定稿回答');
    final api = ApiService(
      baseUrl: 'http://test',
      client: MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/brief' || path == '/api/v1/brief/cached') {
          return _json({'content': '今日概览'});
        }
        if (path == '/api/v1/feed') {
          return _json({
            'entries': [_feedEntry('r1', '怎么玩铜')],
            'totalToday': 1,
          });
        }
        if (path == '/api/v1/tags') return _json({'tags': [], 'total': 0, 'updatedAt': ''});
        if (path == '/api/v1/project/tasks/stats') {
          return _json({'total': 0, 'todo': 0, 'doing': 0, 'done': 0, 'cancelled': 0});
        }
        return http.Response('not found', 404);
      }),
      sseClient: _SequentialHoldSse([firstRound, followRound]),
    );
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: FeedPage(api: api))));
    await tester.pumpAndSettle();

    // 首问：点提问 → delta 草稿渲染 → 放行 meta 定稿（激活对话态）
    await tester.tap(find.text('提问'));
    await tester.pump(const Duration(milliseconds: 90));
    expect(find.textContaining('首问草稿', findRichText: true), findsOneWidget,
        reason: '前置：草稿 turn 已真实渲染');
    firstRound.gate.complete();
    await tester.pumpAndSettle();
    expect(find.textContaining('首问定稿回答', findRichText: true), findsOneWidget);

    // 续问：输入栏直接发送 → 走 _appendToActiveCard + ask-stream
    await tester.enterText(find.byType(TextField), '接着问');
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await tester.pump(const Duration(milliseconds: 90));
    expect(find.textContaining('续问草稿回答', findRichText: true), findsOneWidget,
        reason: '前置：续问草稿 turn 已真实渲染');

    // 放行 meta → 定稿必须原位替换草稿：回答只出现一遍
    followRound.gate.complete();
    await tester.pumpAndSettle();
    expect(find.textContaining('续问定稿回答', findRichText: true), findsOneWidget,
        reason: 'meta 定稿原位替换草稿 turn——回答只出现一遍（修复前草稿+定稿各渲染一遍）');
    expect(find.textContaining('续问草稿回答', findRichText: true), findsNothing,
        reason: '草稿被定稿替换，不残留');
  });
}

http.Response _json(Map<String, dynamic> body) => http.Response(
    jsonEncode(body), 200,
    headers: {'content-type': 'application/json; charset=utf-8'});

Map<String, dynamic> _feedEntry(String id, String content) => {
      'type': 'record',
      'id': id,
      'title': content,
      'content': content,
      'tags': <String>[],
      'time': '10:00',
    };

/// 一轮流式问答：delta 草稿（渲染后挂起在 [gate]）→ 放行 → meta 定稿。
class _Round {
  _Round(this.draftText, this.finalText) : gate = Completer<void>();

  final String draftText;
  final String finalText;
  final Completer<void> gate;
}

/// 按调用次序回放多轮流式的 SSE 替身：每轮回 draftText 后挂起等 gate，放行回 meta。
class _SequentialHoldSse extends SseClient {
  _SequentialHoldSse(this.rounds) : super();

  final List<_Round> rounds;
  int _cursor = 0;

  @override
  Future<void> post(
    Uri url, {
    Map<String, String>? headers,
    Object? body,
    required void Function(String data) onData,
  }) async {
    if (_cursor >= rounds.length) {
      throw SseHttpException(503, 'test: no more scripted rounds');
    }
    final round = rounds[_cursor++];
    onData(jsonEncode({'type': 'text', 'content': round.draftText}));
    await round.gate.future;
    onData(jsonEncode({
      'type': 'meta', 'recordId': 'rec_$_cursor', 'summary': round.finalText,
      'tags': ['日常'], 'domain': 'life', 'content': round.finalText,
    }));
    onData('[DONE]');
  }
}
