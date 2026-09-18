import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:adai_web/pages/feed_page.dart';
import 'package:adai_web/services/api_service.dart';

/// 对话流空态（2026-09-17 REVIEW P1-UI14）。
///
/// 背景：空 Feed 的判据只看「当前 Feed 有没有卡片」，于是**老用户今天恰好没记录**
/// 也被当成新用户，收到 onboarding 口径的「第一次见」+ 自我介绍（真实用户反馈：
/// 「我只是今天没有数据，但我不是新用户」）。
///
/// 本用例钉住双端口径：空态文案**不假设新用户**，三个开场问句仍在且点击直接发问。
/// 与 adai-app `feed_state_machine_test.dart` 的同名分组保持同一份文案。
///
/// 2026-09-18 空态分流（本批）：P1-UI14 只是把文案改中立，老用户跨日打开仍看到
/// 「你能干什么？」这类上手引导。现在按服务端 `hasHistory` 分流：
/// - `hasHistory=false`（含**旧后端不返回该字段**的降级）：新账号口径，维持原文案 + 三个开场问句；
/// - `hasHistory=true`：老用户口径，去掉三个开场问句，正文改「接着上次的聊也行，我记着」。
/// 双端文案逐字一致（同一个人换端打开不该看到两种阿呆）。

/// UTF-8 JSON 响应（MockClient 默认 Latin-1，中文会炸 → 显式 charset=utf-8）。
http.Response _json(Object body, {int status = 200}) => http.Response(
      jsonEncode(body),
      status,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );

void main() {
  /// 空 Feed 的最小 mock；[records] 记录每次 POST /api/v1/records 的 body。
  /// [hasHistory] 为 null 时**不返回该字段**——模拟旧后端，前端应降级为 false（新账号口径）。
  ApiService emptyApi(List<String> records, {bool? hasHistory}) {
    return ApiService(
      baseUrl: 'http://test',
      userId: 'adai',
      client: MockClient((req) async {
        final p = req.url.path;
        if (p == '/api/v1/feed') {
          return _json({
            'entries': <Object>[],
            'totalToday': 0,
            // null 时不落该 key —— 模拟旧后端（前端应降级为 false）
            'hasHistory': ?hasHistory,
          });
        }
        if (p == '/api/v1/brief') return _json({'content': ''});
        if (p == '/api/v1/brief/cached') return _json({'content': ''});
        if (p == '/api/v1/tags') return _json({'tags': [], 'total': 0, 'updatedAt': ''});
        if (p == '/api/v1/records' && req.method == 'POST') {
          records.add(utf8.decode(req.bodyBytes));
          return _json({
            'intent': 'question',
            'recordId': 'rec_1',
            'summary': '我能记录、回答、也记得住你告诉我的事。',
            'rawResponse': '我能记录、回答、也记得住你告诉我的事。',
            'tags': <String>[],
            'domain': 'life',
          });
        }
        return _json({'error': 'not mocked: $p'}, status: 404);
      }),
    );
  }

  Future<void> pump(WidgetTester tester, ApiService api) async {
    await tester.binding.setSurfaceSize(const Size(1200, 900));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: FeedPage(api: api))));
    await tester.pumpAndSettle();
  }

  testWidgets('空态：不假设新用户（无「第一次见」/自我介绍）+ 三个开场问句仍在', (tester) async {
    await pump(tester, emptyApi(<String>[]));

    expect(find.text('今天还没听你说点什么，随便问我一句——点下面的也行。'), findsOneWidget);
    expect(find.textContaining('第一次见'), findsNothing,
        reason: 'P1-UI14：空 Feed ≠ 新用户，不得出现「第一次见」这类假设新用户的说法');
    expect(find.textContaining('新用户'), findsNothing);
    expect(find.textContaining('我是阿呆'), findsNothing, reason: '空态不再自我介绍（老用户不是陌生人）');
    expect(find.text('你能干什么？'), findsOneWidget);
    expect(find.text('你有什么特别的能力？'), findsOneWidget);
    expect(find.text('我该怎么用你？'), findsOneWidget);

    // 顺带确认空态错误页/旧冷词没有回归
    expect(find.textContaining('加载失败'), findsNothing);
    expect(find.text('还没有记录'), findsNothing);
  });

  testWidgets('空态（老用户 hasHistory=true）：无三个开场问句，只留「接着上次的聊」', (tester) async {
    await pump(tester, emptyApi(<String>[], hasHistory: true));

    expect(find.text('今天还没听你说点什么。接着上次的聊也行，我记着。'), findsOneWidget,
        reason: '老用户空态只提醒「接着聊」，不再给上手引导');
    expect(find.text('你能干什么？'), findsNothing,
        reason: 'hasHistory=true：开场问句是给真·新账号的，老用户看到就像被当成新来的');
    expect(find.text('你有什么特别的能力？'), findsNothing);
    expect(find.text('我该怎么用你？'), findsNothing);
    expect(find.text('今天还没听你说点什么，随便问我一句——点下面的也行。'), findsNothing,
        reason: '两种口径的正文互斥，不能同屏');

    // 两态共有部分仍在；且依旧不得出现系统视角/假设新用户的说法
    expect(find.text('也可以直接说点什么，或者丢张图给我。'), findsOneWidget);
    expect(find.textContaining('第一次见'), findsNothing);
    expect(find.textContaining('新用户'), findsNothing);
    expect(find.textContaining('我是阿呆'), findsNothing);
  });

  testWidgets('空态开场问句：点击直接发问（不是预填），欢迎卡让位', (tester) async {
    final records = <String>[];
    await pump(tester, emptyApi(records));

    await tester.tap(find.text('你能干什么？'));
    await tester.pumpAndSettle();

    expect(records, isNotEmpty, reason: '开场问句必须真的发问，不是塞进输入框');
    expect(jsonDecode(records.last)['content'], '你能干什么？');
    expect(find.text('今天还没听你说点什么，随便问我一句——点下面的也行。'), findsNothing,
        reason: '有了第一条记录后欢迎卡应让位');
  });
}
