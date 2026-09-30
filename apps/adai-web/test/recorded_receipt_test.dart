import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:adai_web/models/feed_models.dart';
import 'package:adai_web/pages/feed_page.dart';
import 'package:adai_web/services/api_service.dart';
import 'package:adai_web/widgets/desktop_feed_card.dart';

/// D4「说出来」（2026-09-30 用户拍板）：与 adai-app 同口径、同文案。
/// 详情见 `apps/adai-app/test/recorded_receipt_test.dart` 顶部注释与 REVIEW P2-对话3。
///
/// 两层覆盖（对抗审查 P2-2 指出第一层不够）：
///   ① 组件级：`justRecorded` true 显示 / false 不显示 / 转成对话后消失；
///   ② **接线级**：走真实提交路径（`FeedPage` → `_createNewCard` 的 log 分支）——
///      组件级直接造数据，**防不住「log 分支忘置标记」**，而那才是本批真正的新接线。
void main() {
  FeedCardData card({required bool justRecorded}) => FeedCardData(
        id: 'rec_1',
        type: FeedCardType.record,
        time: '10:00',
        content: '今天很开心帮我记一下',
        summary: '记录今日好心情',
        mode: CardMode.idle,
        intent: IntentType.log,
        domain: 'life',
        justRecorded: justRecorded,
      );

  const receipt = '记下了 · 想接着说就点「提问」';

  testWidgets('刚提交的记录卡：显示如实回执', (tester) async {
    await tester.binding.setSurfaceSize(const Size(900, 900));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: DesktopFeedCard(data: card(justRecorded: true))),
    ));

    expect(find.text(receipt), findsOneWidget);
  });

  testWidgets('普通/历史记录卡：不显示回执', (tester) async {
    await tester.binding.setSurfaceSize(const Size(900, 900));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: DesktopFeedCard(data: card(justRecorded: false))),
    ));

    expect(find.text(receipt), findsNothing);
  });

  testWidgets('转成对话后：回执消失（copyWith 会保留标记，故不能只靠标记）', (tester) async {
    await tester.binding.setSurfaceSize(const Size(900, 900));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    // 模拟：记录卡被点「提问」→ mode=chatting + turns 非空，而 justRecorded 仍为 true
    // （copyWith 不传该字段即保留原值——这是自查发现的残留路径）
    final c = card(justRecorded: true).copyWith(
      mode: CardMode.chatting,
      turns: [ConversationTurn(isUser: true, text: '接着聊刚才那句', time: '10:01')],
    );

    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: DesktopFeedCard(data: c)),
    ));

    expect(find.text(receipt), findsNothing);
  });

  testWidgets('D4 接线：提交一句被判「记录」→ 卡片立刻给如实回执（防 log 分支忘置标记）', (tester) async {
    await tester.binding.setSurfaceSize(const Size(1200, 2000));
    addTearDown(() => tester.binding.setSurfaceSize(null));

    final api = ApiService(
      baseUrl: 'http://test',
      client: MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/brief' || path == '/api/v1/brief/cached') {
          return _json({'content': ''});
        }
        if (path == '/api/v1/feed') {
          return _json({
            'entries': [_feedEntry('r1', '第一条')],
            'totalToday': 1,
          });
        }
        if (path == '/api/v1/tags') return _json({'tags': [], 'total': 0, 'updatedAt': ''});
        if (path == '/api/v1/records') {
          return _json({
            'intent': 'log',
            'recordId': 'r-l',
            'summary': '记录完成',
            'tags': ['生活'],
            'domain': 'life',
          });
        }
        return http.Response('not found', 404);
      }),
    );

    await tester.pumpWidget(MaterialApp(home: Scaffold(body: FeedPage(api: api))));
    await tester.pumpAndSettle();

    await tester.enterText(find.byType(TextField), '今天很开心帮我记一下');
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await tester.pumpAndSettle();

    expect(find.text(receipt), findsOneWidget,
        reason: '判成记录时必须出声（D4：不再让用户「说了话它不吭声」）');
  });
}

/// UTF-8 安全的 JSON 响应（ApiService 用 utf8.decode，直接给 String 会损坏中文）。
http.Response _json(Map<String, dynamic> body) => http.Response.bytes(
      utf8.encode(jsonEncode(body)),
      200,
      headers: {'content-type': 'application/json'},
    );

Map<String, dynamic> _feedEntry(String id, String content) => {
      'type': 'record',
      'id': id,
      'title': content,
      'content': content,
      'tags': <String>[],
      'time': '10:00',
    };
