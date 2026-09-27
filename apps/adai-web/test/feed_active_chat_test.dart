import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:adai_web/pages/feed_page.dart';
import 'package:adai_web/services/api_service.dart';
import 'package:adai_web/services/sse_client.dart';
import 'package:adai_web/theme/app_colors.dart';

/// REVIEW P1-前端4（web 端「是否正在对话中」完全不可见 → 用户误点「结束」使对话断裂）。
/// 用户拍板 B 方案：**会话规则不动、只让状态可见**。本文件钉住两件事：
///   ① 对话态三件套可见（hint 文案 + hint 转绿 + 边框转绿，对齐 adai-app input_bar）；
///   ② learn 拦截必须带「不在对话中」前置（对齐 adai-app main_page.dart:564）——
///      否则对话里说到「打开那篇 / 整理这个链接」会在对话中途被 learn 抢走并清卡。
void main() {
  testWidgets('对话中：输入栏显示「继续对话…」且 hint 与边框转绿（此前 web 全不可见）', (tester) async {
    await tester.binding.setSurfaceSize(const Size(1200, 2000));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    final round = _Round('草稿', '定稿回答');
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
        return http.Response('not found', 404);
      }),
      sseClient: _SequentialHoldSse([round]),
    );
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: FeedPage(api: api, learnEnabled: true)),
    ));
    await tester.pumpAndSettle();

    // 前置：未在对话中 → 输入栏是默认态（灰 hint、灰边框）
    var field = tester.widget<TextField>(find.byType(TextField));
    expect(field.decoration?.hintText, '记录或提问…');
    expect(field.decoration?.hintStyle?.color, AppColors.darkGrey5);

    // 点「提问」→ 一轮问答落定 → 激活对话态
    await tester.tap(find.text('提问'));
    await tester.pump(const Duration(milliseconds: 90));
    round.gate.complete();
    await tester.pumpAndSettle();

    // 三件套（对齐 adai-app input_bar.dart:507-525）
    field = tester.widget<TextField>(find.byType(TextField));
    expect(field.decoration?.hintText, '继续对话…', reason: '对话态 hint 文案必须变（此前恒为「记录或提问…」）');
    expect(field.decoration?.hintStyle?.color, AppColors.darkGreen, reason: '对话态 hint 须转绿');

    final greenBordered = find.byWidgetPredicate((w) =>
        w is Container &&
        w.decoration is BoxDecoration &&
        (w.decoration as BoxDecoration).border?.top.color == AppColors.darkGreen);
    expect(greenBordered, findsWidgets, reason: '对话态输入栏边框须转绿（此前恒为 darkBorder）');
  });

  testWidgets('对话中「整理 + 链接」不被 learn 抢走：作为对话续写发出去', (tester) async {
    await tester.binding.setSurfaceSize(const Size(1200, 2000));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    var digestCalls = 0;
    final first = _Round('草稿', '定稿回答');
    final follow = _Round('续问草稿', '续问定稿');
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
        if (path.endsWith('/api/v1/learn/digest') && request.method == 'POST') {
          digestCalls++;
          return _json({'status': 'pending'});
        }
        return http.Response('not found', 404);
      }),
      sseClient: _SequentialHoldSse([first, follow]),
    );
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: FeedPage(api: api, learnEnabled: true)),
    ));
    await tester.pumpAndSettle();

    // 进入对话态
    await tester.tap(find.text('提问'));
    await tester.pump(const Duration(milliseconds: 90));
    first.gate.complete();
    await tester.pumpAndSettle();

    // 对话中说「整理 <链接>」：learn 判据本身是命中的，但守卫必须因「正在对话中」而放行给对话流
    await tester.enterText(find.byType(TextField), '整理 https://example.com/some-article');
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await tester.pump(const Duration(milliseconds: 90));

    expect(digestCalls, 0, reason: '对话进行中，learn 拦截不得抢走这一句（否则对话被清卡打断）');
    expect(find.textContaining('续问草稿', findRichText: true), findsOneWidget,
        reason: '这一句应作为对话续写走 ask-stream');

    follow.gate.complete();
    await tester.pumpAndSettle();
  });

  testWidgets('推送卡「确认并入账」命中快照锚定（只记流水）也必须有回执（09-19 深审 P2-5）', (tester) async {
    await tester.binding.setSurfaceSize(const Size(1200, 2000));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    final api = ApiService(
      baseUrl: 'http://test',
      client: MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/brief' || path == '/api/v1/brief/cached') {
          return _json({'content': '今日概览'});
        }
        if (path == '/api/v1/feed') {
          return _json({
            'entries': [
              {
                'type': 'push',
                'id': 'p1',
                'title': '今日操作确认',
                'content': '📋 今日操作汇总\n· 京东方A 卖出 5300 股',
                'tags': <String>[],
                'time': '15:15',
              }
            ],
            'totalToday': 1,
          });
        }
        if (path == '/api/v1/tags') return _json({'tags': [], 'total': 0, 'updatedAt': ''});
        if (path == '/api/v1/trading/trade-log/confirm') {
          // 命中券商快照锚定：只落流水、不改账（confirmed=0 是正常结局，不是「没有待确认」）
          return _json({
            'confirmed': 0, 'failed': 0, 'skipped': 0,
            'ledgerOnly': 2, 'duplicated': 0, 'failures': <String>[],
          });
        }
        return http.Response('not found', 404);
      }),
    );
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: FeedPage(api: api))));
    await tester.pumpAndSettle();

    await tester.tap(find.text('确认并入账'));
    await tester.pumpAndSettle();

    expect(find.textContaining('已经记进流水了'), findsOneWidget,
        reason: '只记流水时 web 必须说清楚——改前 ledgerOnly 未解析，落进「今天没有待确认的交易」这句假话');
    expect(find.textContaining('没有待确认的交易'), findsNothing);
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
      'type': 'meta',
      'recordId': 'rec_$_cursor',
      'summary': round.finalText,
      'tags': ['日常'],
      'domain': 'life',
      'content': round.finalText,
    }));
    onData('[DONE]');
  }
}
