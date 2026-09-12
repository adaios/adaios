import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:adai_app/pages/learn_page.dart';
import 'package:adai_app/services/api_service.dart';
import 'package:adai_app/services/models/learn_models.dart';
import 'package:adai_app/widgets/input_bar.dart' show PickedImage;

http.Response _json(Object data, {int status = 200}) => http.Response.bytes(
      utf8.encode(jsonEncode(data)),
      status,
      headers: {'content-type': 'application/json'},
    );

/// 假 learn 后端（2026-09-12 完整升级批）：tree / content / find / 消化 / 状态 / 确认 / 额度 全路由，
/// 并记录每个请求（断言 multipart 字段与 Authorization、轮询次数、确认 body 用）。
class _LearnBackend {
  final Map<String, List<Map<String, dynamic>>> _byType = {
    'ai': [], 'trading': [], 'other': [],
  };

  /// 'type/title' → md 全文（GET /learn/content）
  final Map<String, String> contents = {};

  /// 'type/title' → 卡元信息（topic / writable）
  final Map<String, Map<String, dynamic>> metas = {};

  /// 找卡命中（GET /learn/find）
  List<Map<String, dynamic>> findHits = [];

  /// 前 N 次 /learn/tree 返回空（模拟「喂入完成后新卡才出现」）
  int skipTreeCalls = 0;

  /// /learn/digest/status 的按序响应（用完后一直用最后一个）；
  /// 下标 0 留给进页时的确认恢复巡检。
  List<Map<String, dynamic>> statusSeq = [
    {'status': 'idle'}
  ];
  int statusCalls = 0;

  Map<String, dynamic> quota = const {
    'month': '2026-09', 'usedSeconds': 1800, 'usedYuan': 0.14, 'quotaSeconds': 36000,
    'remainSeconds': 34200, 'yuanPerHour': 0.29, 'asrAvailable': true,
    'unavailableReason': null,
  };
  bool quotaFails = false;

  Map<String, dynamic> imagePostError = const {};
  Map<String, dynamic> confirmResponse = const {'status': 'running'};

  /// 非空 → confirm 请求挂在这里（连点守卫用例：控制回包时机）
  Completer<void>? confirmGate;

  /// 删卡回包里的 cascadedCandidates（该卡反哺过、被一起清掉的交易候选标题）
  List<String> deleteCascaded = const [];

  /// 非空 → 删卡 / 挪主题回 400，body 即后端人话（「失败透出人话」用例）
  Map<String, dynamic> deleteError = const {};
  Map<String, dynamic> moveError = const {};

  /// 删卡 / 挪主题的状态码（200 = 正常；403 = learn 插件未启用，回空 body 走人话兜底）
  int deleteStatus = 200;
  int moveStatus = 200;

  /// 非空 → 挪主题请求挂在这里（动作级连点守卫用例：控制回包时机）
  Completer<void>? moveGate;

  final List<http.Request> requests = [];

  void addCard(
    String type,
    String title, {
    String created = '2026-09-06',
    String topic = '',
    bool writable = true,
    String coreView = '',
    List<String> keyPoints = const [],
    List<String> questions = const ['存疑点一'],
    String extra = '',
  }) {
    final view = coreView.isEmpty ? '$title 的核心观点' : coreView;
    _byType[type]!.add({
      'type': type, 'title': title, 'platform': 'bilibili', 'author': '某UP',
      'created': created, 'topic': topic, 'writable': writable, 'tags': const ['rag'],
      'coreView': view, 'keyPoints': keyPoints, 'questions': questions,
    });
    metas['$type/$title'] = {'topic': topic, 'writable': writable};
    contents['$type/$title'] = _md(title, coreView: view,
        keyPoints: keyPoints, questions: questions, extra: extra);
  }

  /// 卡片 md 原文：产品建模的四段 +（可选）Mac 侧整理的「关键内容详解」等额外段。
  static String _md(String title,
      {String coreView = '',
      List<String> keyPoints = const [],
      List<String> questions = const [],
      String extra = ''}) {
    final b = StringBuffer()
      ..writeln('# $title')
      ..writeln()
      ..writeln('## 核心观点')
      ..writeln(coreView)
      ..writeln()
      ..writeln('## 关键要点');
    for (final k in keyPoints) {
      b.writeln('- $k');
    }
    b
      ..writeln()
      ..writeln('## 我的疑问');
    for (final q in questions) {
      b.writeln('- $q');
    }
    if (extra.isNotEmpty) {
      b
        ..writeln()
        ..writeln('## 关键内容详解')
        ..writeln(extra);
    }
    return b.toString();
  }

  Map<String, dynamic> cardOf(String type, String title) =>
      _byType[type]!.firstWhere((c) => c['title'] == title);

  Map<String, dynamic> _treeBody() => {
        for (final e in _byType.entries)
          if (e.value.isNotEmpty) e.key: e.value,
      };

  List<http.Request> requestsTo(String suffix) =>
      requests.where((r) => r.url.path.endsWith(suffix)).toList();

  ApiService api({String? token}) =>
      ApiService(baseUrl: 'http://test', token: token, client: MockClient(_handle));

  Future<http.Response> _handle(http.Request req) async {
    requests.add(req);
    final p = req.url.path;
    if (p.endsWith('/api/v1/learn/tree')) {
      if (skipTreeCalls > 0) {
        skipTreeCalls--;
        return _json(const {});
      }
      return _json(_treeBody());
    }
    if (p.endsWith('/api/v1/learn/content')) {
      final type = req.url.queryParameters['type'] ?? '';
      final title = req.url.queryParameters['title'] ?? '';
      final md = contents['$type/$title'];
      if (md == null) return _json({'error': '卡片不存在：$type/$title'}, status: 400);
      final meta = metas['$type/$title'] ?? const {};
      return _json({
        'type': type, 'title': title,
        'topic': meta['topic'] ?? '', 'writable': meta['writable'] ?? true,
        'content': md,
      });
    }
    if (p.endsWith('/api/v1/learn/find')) return _json(findHits);
    // 卡片管理动作（2026-09-13 卡片管理动作批）
    if (p.endsWith('/api/v1/learn/cards/topic') && req.method == 'PATCH') {
      final gate = moveGate;
      if (gate != null) await gate.future;
      if (moveError.isNotEmpty) {
        return _json(moveError, status: moveStatus == 200 ? 400 : moveStatus);
      }
      if (moveStatus != 200) return _json(const {}, status: moveStatus);
      final body = jsonDecode(req.body) as Map<String, dynamic>;
      final type = body['type'] as String? ?? '';
      final title = body['title'] as String? ?? '';
      final topic = body['topic'] as String? ?? '';
      final card = _byType[type]?.where((c) => c['title'] == title).firstOrNull;
      if (card == null) return _json({'error': '卡片不存在：$type/$title'}, status: 400);
      card['topic'] = topic;
      metas['$type/$title'] = {...?metas['$type/$title'], 'topic': topic};
      return _json(card);
    }
    if (p.endsWith('/api/v1/learn/cards') && req.method == 'DELETE') {
      if (deleteError.isNotEmpty) {
        return _json(deleteError, status: deleteStatus == 200 ? 400 : deleteStatus);
      }
      if (deleteStatus != 200) return _json(const {}, status: deleteStatus);
      final type = req.url.queryParameters['type'] ?? '';
      final title = req.url.queryParameters['title'] ?? '';
      final existed = _byType[type]?.any((c) => c['title'] == title) ?? false;
      if (!existed) return _json({'error': '卡片不存在：$title'}, status: 400);
      _byType[type]!.removeWhere((c) => c['title'] == title);
      contents.remove('$type/$title');
      metas.remove('$type/$title');
      return _json({
        'deleted': true, 'title': title,
        'learnCardId': 'learn/$type/$title/01-$title.md',
        'cascadedCandidates': deleteCascaded,
      });
    }
    if (p.endsWith('/api/v1/learn/digest/status')) {
      final i = statusCalls < statusSeq.length ? statusCalls : statusSeq.length - 1;
      statusCalls++;
      return _json(statusSeq[i]);
    }
    if (p.endsWith('/api/v1/learn/digest/quota')) {
      if (quotaFails) return _json({'error': 'boom'}, status: 500);
      return _json(quota);
    }
    if (p.endsWith('/api/v1/learn/digest/confirm')) {
      final gate = confirmGate;
      if (gate != null) await gate.future;
      return _json(confirmResponse);
    }
    if (p.endsWith('/api/v1/learn/digest/image')) {
      if (imagePostError.isNotEmpty) return _json(imagePostError, status: 400);
      return _json(const {'status': 'running'});
    }
    if (p.endsWith('/api/v1/learn/digest')) return _json(const {'status': 'running'});
    if (p.endsWith('/api/v1/learn/push-settings')) return _json(const {'learn-review': true});
    if (p.endsWith('/api/v1/learn/push-settings/learn-review')) {
      return _json(const {'learn-review': false});
    }
    if (p.endsWith('/api/v1/me/plugins')) return _json(const ['learn']);
    return _json({'error': 'not mocked'}, status: 404);
  }
}

void main() {
  Future<void> pump(WidgetTester tester, ApiService api,
      {({String type, String title})? initialCard,
      Future<List<PickedImage>> Function()? debugPickImages}) async {
    await tester.pumpWidget(MaterialApp(
      home: LearnPage(api: api, initialCard: initialCard, debugPickImages: debugPickImages),
    ));
    await tester.pumpAndSettle();
  }

  group('LearnCardDto JSON parsing', () {
    test('parses full card', () {
      final card = LearnCardDto.fromJson(jsonDecode('''
        {"type":"trading","title":"回调一半的判定","created":"2026-09-06",
         "status":"review","tradeRelated":true,"tradeNote":"与 R66 互补","tags":["止损"],
         "coreView":"回调一半是买点","keyPoints":["02:31 回调一半"],
         "questions":["口径一致？"],"retell":"自己写了一遍：回调一半=几何口径"}
      ''') as Map<String, dynamic>);
      expect(card.type, 'trading');
      expect(card.tradeRelated, isTrue);
      expect(card.keyPoints, ['02:31 回调一半']);
      expect(card.status, 'review');
      expect(card.retell, contains('几何口径'));
    });

    test('topic / writable 解析 + 缺字段防御（topic=\'\'、writable=true）', () {
      final card = LearnCardDto.fromJson(jsonDecode('''
        {"type":"ai","title":"A 卡","created":"2026-09-12","topic":"harness","writable":false}
      ''') as Map<String, dynamic>);
      expect(card.topic, 'harness');
      expect(card.writable, isFalse);

      final old = LearnCardDto.fromJson(
          jsonDecode('{"type":"ai","title":"老卡","created":"2026-09-01"}') as Map<String, dynamic>);
      expect(old.topic, '');
      expect(old.writable, isTrue, reason: '老后端没这字段 → 当自己的卡处理');
      expect(old.topicLabel, '未归类');
    });

    test('LearnCardContentDto：全文 + 来源标注（topic/writable）', () {
      final c = LearnCardContentDto.fromJson(jsonDecode('''
        {"type":"ai","title":"手工卡","topic":"","writable":false,"content":"## 金句\\n慢即是快"}
      ''') as Map<String, dynamic>);
      expect(c.title, '手工卡');
      expect(c.writable, isFalse);
      expect(c.topicLabel, '未归类');
      expect(c.content, contains('慢即是快'));

      final empty = LearnCardContentDto.fromJson(const {});
      expect(empty.type, '');
      expect(empty.writable, isTrue, reason: '缺字段防御');
      expect(empty.content, '');
    });

    test('recentAll merged sorted desc', () {
      final tree = LearnTreeResponse.fromJson(jsonDecode('''
        {"ai":[{"type":"ai","title":"A","created":"2026-09-01"}],
         "trading":[{"type":"trading","title":"B","created":"2026-09-06"}]}
      ''') as Map<String, dynamic>);
      final all = tree.recentAll;
      expect(all.length, 2);
      expect(all.first.title, 'B', reason: 'created 倒序 → 最新在前');
      expect(tree.isEmpty, isFalse);
    });

    test('groupLearnCards：type → topic 二级分组（未归类 + 组内 created 倒序）', () {
      final tree = LearnTreeResponse.fromJson(jsonDecode('''
        {"ai":[{"type":"ai","title":"A1","created":"2026-09-01","topic":"harness"},
               {"type":"ai","title":"A2","created":"2026-09-08","topic":"harness"},
               {"type":"ai","title":"A3","created":"2026-09-05","topic":""}],
         "trading":[{"type":"trading","title":"T1","created":"2026-09-09","topic":"止损"}]}
      ''') as Map<String, dynamic>);
      final groups = groupLearnCards(tree.recentAll);
      expect(groups.map((g) => g.type).toList(), ['ai', 'trading'],
          reason: '类型顺序固定 ai → trading → other');
      expect(groups.first.label, 'AI / 技术');
      expect(groups.first.count, 3);
      final topics = groups.first.topics;
      expect(topics.map((t) => t.topic).toList(), ['harness', ''],
          reason: '主题按各自最新卡倒序：harness(09-08) 在未归类(09-05) 之前');
      expect(topics.first.cards.map((c) => c.title).toList(), ['A2', 'A1'],
          reason: '组内 created 倒序');
      expect(topics.last.label, '未归类');
    });

    test('learnTypeLabel 中文名口径统一', () {
      expect(learnTypeLabel('ai'), 'AI / 技术');
      expect(learnTypeLabel('trading'), '交易');
      expect(learnTypeLabel('other'), '其他');
      expect(learnTypeLabel('whatever'), '其他');
    });
  });

  group('LearnDigestJob 抓取批解析（stage/source/cost + 防御式 null）', () {
    test('解析 needs_confirmation 全字段（舞台/来源/费用）', () {
      final job = LearnDigestJob.fromJson(jsonDecode('''
        {"status":"needs_confirmation","type":"ai","title":"","message":"这个视频没有字幕，需要转写：37 分钟，预计约 0.18 元（本月剩余额度 10 小时）",
         "stage":"transcribing",
         "source":{"platform":"bilibili","title":"某视频","author":"某UP","durationSeconds":2244},
         "cost":{"durationSeconds":2244,"durationKnown":true,"estimatedYuan":0.1795,
                 "monthUsedSeconds":0,"quotaSeconds":36000,"remainSeconds":36000}}
      ''') as Map<String, dynamic>);
      expect(job.isAwaitingConfirm, isTrue);
      expect(job.isInProgress, isFalse);
      expect(job.stageText, '正在转写，可能要几分钟');
      expect(job.source!.platform, 'bilibili');
      expect(job.source!.summaryLine, '《某视频》 · 某UP · 37 分钟');
      expect(job.cost!.durationLabel, '37 分钟');
      expect(job.cost!.estimatedYuanLabel, '0.18');
      expect(job.confirmText, contains('需要转写'));
    });

    test('null 防御：stage/source/cost 缺省不炸，兜底人话自己拼', () {
      final job = LearnDigestJob.fromJson(jsonDecode('''
        {"status":"needs_confirmation","stage":null,"source":null,
         "cost":{"durationSeconds":null,"durationKnown":false,"estimatedYuan":null,
                 "monthUsedSeconds":null,"quotaSeconds":null,"remainSeconds":null}}
      ''') as Map<String, dynamic>);
      expect(job.stage, '');
      expect(job.stageText, '');
      expect(job.source, isNull);
      expect(job.cost!.durationLabel, '时长未知');
      expect(job.cost!.estimatedYuanLabel, '');
      expect(job.confirmText, contains('没有字幕'), reason: '后端没给 message → 自己拼费用提示');
      expect(job.confirmText, contains('时长未知'));
    });

    test('连 cost 都没有时的确认兜底 + 取消人话', () {
      final job = LearnDigestJob.fromJson(jsonDecode('{"status":"needs_confirmation"}')
          as Map<String, dynamic>);
      expect(job.cost, isNull);
      expect(job.confirmText, contains('会花一点钱'));
      final cancelled =
          LearnDigestJob.fromJson(jsonDecode('{"status":"cancelled"}') as Map<String, dynamic>);
      expect(cancelled.isCancelled, isTrue);
      expect(cancelled.isAwaitingConfirm, isFalse);
      expect(cancelled.isDone, isFalse);
      expect(cancelled.cancelledText, contains('这钱没花'));
    });

    test('四种舞台人话（含完整升级批新增 reading）+ 未知舞台给空串', () {
      String stageOf(String s) =>
          LearnDigestJob.fromJson({'status': 'running', 'stage': s}).stageText;
      expect(stageOf('fetching'), '正在抓取原文');
      expect(stageOf('reading'), '正在读图', reason: '图片喂入：正在读图');
      expect(stageOf('transcribing'), '正在转写，可能要几分钟');
      expect(stageOf('structuring'), '正在整理成卡片');
      expect(stageOf('whatever'), '');
    });

    test('LearnQuotaDto 解析 + 月度额度和单价人话（P2-learn15）', () {
      final q = LearnQuotaDto.fromJson(jsonDecode('''
        {"month":"2026-09","usedSeconds":1200,"usedYuan":0.096,"quotaSeconds":36000,
         "remainSeconds":34800,"yuanPerHour":0.288,"asrAvailable":true,"unavailableReason":null}
      ''') as Map<String, dynamic>);
      expect(q.month, '2026-09');
      expect(q.usedSeconds, 1200);
      expect(q.remainSeconds, 34800);
      expect(q.yuanPerHour, closeTo(0.288, 0.0001));
      expect(q.asrAvailable, isTrue);
      expect(q.unavailableReason, '');
      expect(q.remainLabel, '还剩 9 小时 40 分');
      expect(q.monthLabel, '本月还剩 9 小时 40 分，已用 0.10 元');
      expect(q.priceLabel, '转写 0.29 元/小时');
      expect(q.unavailableLabel, '');
    });

    test('LearnQuotaDto：通道不可用 → 人话取后端原因', () {
      final q = LearnQuotaDto.fromJson(jsonDecode('''
        {"month":"2026-09","usedYuan":0,"remainSeconds":36000,"yuanPerHour":0,
         "asrAvailable":false,"unavailableReason":"这台服务器还没接转写通道"}
      ''') as Map<String, dynamic>);
      expect(q.unavailableLabel, '这台服务器还没接转写通道');
      expect(q.priceLabel, '');
    });
  });

  group('LearnPage', () {
    testWidgets('最近学习：type → topic 二级分组，组内 created 倒序', (tester) async {
      final backend = _LearnBackend()
        ..addCard('ai', 'RAG 笔记', created: '2026-09-01', topic: 'harness')
        ..addCard('ai', 'Agent 笔记', created: '2026-09-06', topic: 'harness')
        ..addCard('trading', '回调一半', created: '2026-09-06', topic: '止损',
            keyPoints: const ['回调=(high+low)/2'])
        ..addCard('ai', '零散笔记', created: '2026-09-02');
      await pump(tester, backend.api());

      expect(find.text('最近学习'), findsOneWidget);
      expect(find.byKey(const ValueKey('learn-type-ai')), findsOneWidget);
      expect(find.byKey(const ValueKey('learn-type-trading')), findsOneWidget);
      expect(find.byKey(const ValueKey('learn-topic-ai-harness')), findsOneWidget);
      expect(find.text('未归类'), findsOneWidget, reason: '空 topic 归到「未归类」');

      // 同一主题内 created 倒序：09-06 在 09-01 之上
      final agentY = tester.getTopLeft(find.text('Agent 笔记')).dy;
      final ragY = tester.getTopLeft(find.text('RAG 笔记')).dy;
      expect(agentY < ragY, isTrue, reason: '组内 created 倒序');

      // 类型分组：AI / 技术 段在 交易 段之上
      final aiY = tester.getTopLeft(find.byKey(const ValueKey('learn-type-ai'))).dy;
      final tradingY = tester.getTopLeft(find.byKey(const ValueKey('learn-type-trading'))).dy;
      expect(aiY < tradingY, isTrue, reason: '类型顺序 ai → trading');
    });

    testWidgets('空态提示', (tester) async {
      await pump(tester, _LearnBackend().api());
      expect(find.textContaining('还没有学习卡片'), findsOneWidget);
    });

    testWidgets('加载失败降级 + 重试恢复', (tester) async {
      var calls = 0;
      final backend = _LearnBackend()..addCard('ai', '恢复的卡');
      final api = ApiService(
        baseUrl: 'http://test',
        client: MockClient((req) async {
          if (req.url.path.endsWith('/api/v1/learn/tree')) {
            calls++;
            if (calls == 1) return _json({'error': 'boom'}, status: 500);
            return _json({
              'ai': [backend.cardOf('ai', '恢复的卡')]
            });
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);
      expect(find.text('加载失败，请重试'), findsOneWidget);

      await tester.tap(find.text('重试'));
      await tester.pumpAndSettle();
      expect(find.text('恢复的卡'), findsOneWidget);
    });

    testWidgets('点卡片打开单篇全文：走 /learn/content 拿 md 原文（产品没建模的段也显示）',
        (tester) async {
      final backend = _LearnBackend()
        ..addCard('ai', 'RAG 与 Agent',
            created: '2026-09-06', topic: 'harness',
            keyPoints: const ['RAG 是检索增强', 'Agent 自主行动'],
            extra: '这一段是 Mac 侧整理的详解，产品列表里没有。');
      final api = backend.api();
      await pump(tester, api);

      await tester.tap(find.text('RAG 与 Agent'));
      await tester.pumpAndSettle();

      expect(backend.requestsTo('/api/v1/learn/content').length, 1,
          reason: '全文必须走 /learn/content（不是列表字段）');
      expect(backend.requestsTo('/api/v1/learn/content').first.url.queryParameters['title'],
          'RAG 与 Agent');
      expect(find.byKey(const ValueKey('learn-full-content')), findsOneWidget);
      expect(find.text('核心观点'), findsOneWidget);
      expect(find.text('RAG 与 Agent 的核心观点'), findsOneWidget);
      expect(find.text('RAG 是检索增强'), findsOneWidget);
      expect(find.text('Agent 自主行动'), findsOneWidget);
      expect(find.text('关键内容详解'), findsOneWidget, reason: '产品未建模的段也要看得到');
      expect(find.textContaining('Mac 侧整理的详解'), findsOneWidget);
      expect(find.text('复述'), findsOneWidget, reason: '能改的卡保留复述写入口');
    });

    testWidgets('全文读取失败 → 人话错误态 + 重试恢复', (tester) async {
      var contentCalls = 0;
      final backend = _LearnBackend()..addCard('ai', '会失败的卡');
      final api = ApiService(
        baseUrl: 'http://test',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) {
            return _json({
              'ai': [backend.cardOf('ai', '会失败的卡')]
            });
          }
          if (p.endsWith('/api/v1/learn/content')) {
            contentCalls++;
            if (contentCalls == 1) return _json({'error': 'boom'}, status: 500);
            return _json({
              'type': 'ai', 'title': '会失败的卡', 'topic': 'harness', 'writable': true,
              'content': '# 会失败的卡\n\n## 核心观点\n补上了',
            });
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);
      await tester.tap(find.text('会失败的卡'));
      await tester.pumpAndSettle();

      expect(find.text('加载失败，请重试'), findsOneWidget);
      expect(find.textContaining('接口'), findsNothing, reason: 'B1：人话，不出现系统视角标签');

      await tester.tap(find.byKey(const ValueKey('learn-content-retry')));
      await tester.pumpAndSettle();
      expect(find.text('补上了'), findsOneWidget);
    });

    testWidgets('只读卡（writable=false）：显示一行说明、不出现写入口', (tester) async {
      final backend = _LearnBackend()
        ..addCard('ai', 'Mac 上整理的原卡', created: '2026-09-12', topic: 'harness',
            writable: false, extra: '手工卡里才有的详解段');
      await pump(tester, backend.api());

      await tester.tap(find.text('Mac 上整理的原卡'));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('learn-readonly-note')), findsOneWidget);
      expect(find.textContaining('在 Mac 上整理的原始卡'), findsOneWidget);
      expect(find.byKey(const ValueKey('learn-write-actions')), findsNothing,
          reason: '只读卡不给复述/编辑/流转/反哺入口');
      expect(find.text('待复习'), findsNothing, reason: '只读卡不亮状态流转徽标');
      expect(find.textContaining('手工卡里才有的详解段'), findsOneWidget, reason: '只读也能读全文');
    });

    testWidgets('S-learn2：复习提醒铃铛可读开关并切换', (tester) async {
      var putCalled = false;
      final backend = _LearnBackend()..addCard('ai', 'RAG 笔记');
      final api = ApiService(
        baseUrl: 'http://test',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) {
            return _json({
              'ai': [backend.cardOf('ai', 'RAG 笔记')]
            });
          }
          if (p.endsWith('/api/v1/learn/push-settings') && req.method == 'GET') {
            return _json({'learn-review': true});
          }
          if (p.endsWith('/api/v1/learn/push-settings/learn-review') && req.method == 'PUT') {
            putCalled = true;
            return _json({'learn-review': false});
          }
          if (p.endsWith('/api/v1/learn/digest/status')) return _json({'status': 'idle'});
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);

      await tester.tap(find.byIcon(Icons.notifications_outlined));
      await tester.pumpAndSettle();
      expect(find.text('复习提醒'), findsOneWidget);
      expect(find.text('开启中'), findsOneWidget);

      await tester.tap(find.byType(Switch));
      await tester.pumpAndSettle();
      expect(putCalled, isTrue, reason: '切换应调用 PUT /learn/push-settings/learn-review');
    });

    testWidgets('喂入：页头＋提交素材 → 轮询 done → 回列表打开新卡', (tester) async {
      final backend = _LearnBackend()
        ..addCard('ai', '阿呆消化了新卡', created: '2026-09-10', topic: 'harness')
        ..skipTreeCalls = 1
        ..statusSeq = [
          {'status': 'idle'},
          {'status': 'running'},
          {'status': 'done', 'type': 'ai', 'title': '阿呆消化了新卡'},
        ];
      await pump(tester, backend.api());
      expect(find.textContaining('还没有学习卡片'), findsOneWidget);

      await tester.tap(find.byIcon(Icons.add_circle_outline));
      await tester.pumpAndSettle();
      expect(find.text('整理新内容'), findsOneWidget);

      await tester.enterText(
          find.byKey(const ValueKey('learn-digest-content')), '一段视频字幕素材，讲 RAG…');
      await tester.tap(find.text('让阿呆消化'));
      await tester.pump();

      expect(find.text('消化中'), findsOneWidget);
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      final posted = backend.requestsTo('/api/v1/learn/digest')
          .where((r) => r.method == 'POST').toList();
      expect(posted.length, 1, reason: '应提交 POST /learn/digest');
      expect(jsonDecode(posted.first.body)['content'], contains('字幕'));
      // 回到列表页 → 打开新卡全文详情
      expect(find.textContaining('阿呆消化了新卡'), findsWidgets);
      expect(find.text('阿呆消化了新卡 的核心观点'), findsOneWidget);
    });

    testWidgets('喂入失败：后端 400 人话透出且可重试', (tester) async {
      var postCalls = 0;
      final api = ApiService(
        baseUrl: 'http://test',
        client: MockClient((req) async {
          final p = req.url.path;
          if (p.endsWith('/api/v1/learn/tree')) return _json(const {});
          if (p.endsWith('/api/v1/learn/digest/status')) return _json({'status': 'idle'});
          if (p.endsWith('/api/v1/learn/digest') && req.method == 'POST') {
            postCalls++;
            return _json({'error': '素材已留存（learn/_raw/），可稍后重试'}, status: 400);
          }
          return _json({'error': 'not mocked'}, status: 404);
        }),
      );
      await pump(tester, api);
      await tester.tap(find.byIcon(Icons.add_circle_outline));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const ValueKey('learn-digest-content')), '素材内容');
      await tester.tap(find.text('让阿呆消化'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.textContaining('素材已留存'), findsOneWidget);
      expect(find.text('让阿呆消化'), findsOneWidget, reason: '失败后仍可重试');
      expect(postCalls, 1);
      expect(find.textContaining('后端'), findsNothing, reason: 'B1 无第三视角：不出现系统口径');
    });

    // ── 2026-09-12 抓取批：链接喂入 + 转写费用确认 ──

    testWidgets('① 链接喂入：只丢链接无素材也能提交 → 阶段人话 → 轮询 done → 回列表打开新卡',
        (tester) async {
      final backend = _LearnBackend()
        ..addCard('ai', 'B站视频整理', created: '2026-09-12', topic: 'harness')
        ..skipTreeCalls = 1
        ..statusSeq = [
          {'status': 'idle'},
          {
            'status': 'running', 'stage': 'fetching',
            'source': {'platform': 'bilibili', 'title': '某视频', 'author': '某UP',
                       'durationSeconds': 2244},
          },
          {'status': 'running', 'stage': 'structuring'},
          {'status': 'done', 'type': 'ai', 'title': 'B站视频整理'},
        ];
      await pump(tester, backend.api());

      await tester.tap(find.byIcon(Icons.add_circle_outline));
      await tester.pumpAndSettle();
      expect(find.textContaining('粘贴 B站 / 文章链接'), findsOneWidget, reason: '链接框是首选入口');

      await tester.enterText(find.byKey(const ValueKey('learn-digest-link')),
          'https://www.bilibili.com/video/BV1xx411c7mD');
      await tester.tap(find.text('让阿呆消化'));
      await tester.pump();
      expect(find.text('消化中'), findsOneWidget);

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      expect(find.text('正在抓取原文'), findsOneWidget, reason: 'stage=fetching 的人话');
      expect(find.textContaining('某视频'), findsOneWidget, reason: '抓到的来源要亮相');

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      expect(find.text('正在整理成卡片'), findsOneWidget, reason: 'stage=structuring 的人话');

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      final posted = backend.requestsTo('/api/v1/learn/digest')
          .firstWhere((r) => r.method == 'POST');
      final body = jsonDecode(posted.body) as Map<String, dynamic>;
      expect(body['url'], 'https://www.bilibili.com/video/BV1xx411c7mD');
      expect(body.containsKey('content'), isFalse, reason: '只有链接 → 不塞空素材字段');
      expect(find.text('B站视频整理 的核心观点'), findsOneWidget, reason: 'done 回列表并打开新卡');
    });

    testWidgets('② 没字幕的视频：轮询到 needs_confirmation → 亮费用提示、停轮询、不自动继续',
        (tester) async {
      final backend = _LearnBackend()
        ..statusSeq = [
          {'status': 'idle'},
          {
            'status': 'running', 'stage': 'transcribing',
            'source': {'platform': 'bilibili', 'title': '某视频', 'author': '某UP'},
          },
          {
            'status': 'needs_confirmation', 'stage': 'transcribing',
            'message': '这个视频没有字幕，需要转写：37 分钟，预计约 0.18 元（本月剩余额度 10 小时）',
            'source': {'platform': 'bilibili', 'title': '某视频', 'author': '某UP',
                       'durationSeconds': 2244},
            'cost': {'durationSeconds': 2244, 'durationKnown': true, 'estimatedYuan': 0.1795,
                     'monthUsedSeconds': 0, 'quotaSeconds': 36000, 'remainSeconds': 36000},
          },
        ];
      await pump(tester, backend.api());
      await tester.tap(find.byIcon(Icons.add_circle_outline));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const ValueKey('learn-digest-link')),
          'https://www.bilibili.com/video/BV1xx411c7mD');
      await tester.tap(find.text('让阿呆消化'));
      await tester.pump();

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      expect(find.text('正在转写，可能要几分钟'), findsOneWidget, reason: 'stage=transcribing 的人话');

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      await tester.pumpAndSettle();

      expect(find.text('转写确认'), findsOneWidget);
      expect(find.textContaining('这个视频没有字幕，需要转写：37 分钟'), findsOneWidget);
      expect(find.textContaining('预计约 0.18 元'), findsOneWidget);
      expect(find.text('继续转写'), findsOneWidget);
      expect(find.text('先不转写'), findsOneWidget);
      expect(backend.requestsTo('/api/v1/learn/digest/confirm'), isEmpty,
          reason: '用户没点头前不能替他花钱');

      final pollsAtConfirm = backend.statusCalls;
      await tester.pump(const Duration(seconds: 6));
      await tester.pump();
      expect(backend.statusCalls, pollsAtConfirm, reason: 'needs_confirmation 后应停止轮询');
      expect(find.textContaining('后台'), findsNothing, reason: '不是超时兜底，而是等用户点头');
    });

    testWidgets('③ 继续转写 → confirm(true) → 恢复轮询 → done 打开新卡', (tester) async {
      final backend = _LearnBackend()
        ..addCard('ai', '转写后整理好的卡', created: '2026-09-12', topic: 'harness')
        ..skipTreeCalls = 1
        ..statusSeq = [
          {'status': 'idle'},
          {
            'status': 'needs_confirmation', 'stage': 'transcribing',
            'message': '这个视频没有字幕，需要转写：37 分钟，预计约 0.18 元（本月剩余额度 10 小时）',
          },
          {'status': 'done', 'type': 'ai', 'title': '转写后整理好的卡'},
        ]
        ..confirmResponse = {'status': 'running', 'stage': 'transcribing'};
      await pump(tester, backend.api());
      await tester.tap(find.byIcon(Icons.add_circle_outline));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const ValueKey('learn-digest-link')),
          'https://www.bilibili.com/video/BV1xx411c7mD');
      await tester.tap(find.text('让阿呆消化'));
      await tester.pump();
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      await tester.pumpAndSettle();
      expect(find.text('继续转写'), findsOneWidget);

      await tester.tap(find.text('继续转写'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      final confirms = backend.requestsTo('/api/v1/learn/digest/confirm');
      expect(confirms.length, 1, reason: '应送达 /learn/digest/confirm');
      expect(jsonDecode(confirms.first.body)['confirm'], isTrue, reason: '继续转写 = confirm(true)');
      expect(find.text('转写确认'), findsNothing, reason: '点头后离开确认态');
      expect(find.text('正在转写，可能要几分钟'), findsOneWidget, reason: '恢复轮询并显示阶段');

      await tester.pump(const Duration(milliseconds: 2500));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      expect(find.text('转写后整理好的卡 的核心观点'), findsOneWidget, reason: 'done → 回列表打开新卡');
    });

    testWidgets('④ 先不转写 → confirm(false) → 到此为止，不进入 done', (tester) async {
      final backend = _LearnBackend()
        ..statusSeq = [
          {'status': 'idle'},
          {
            'status': 'needs_confirmation', 'stage': 'transcribing',
            'message': '这个视频没有字幕，需要转写：37 分钟，预计约 0.18 元（本月剩余额度 10 小时）',
          },
        ]
        ..confirmResponse = {
          'status': 'cancelled',
          'message': '已取消转写（没花钱），抓到的元数据我留着了，回头想整理再说一声',
        };
      await pump(tester, backend.api());
      await tester.tap(find.byIcon(Icons.add_circle_outline));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const ValueKey('learn-digest-link')),
          'https://www.bilibili.com/video/BV1xx411c7mD');
      await tester.tap(find.text('让阿呆消化'));
      await tester.pump();
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      await tester.pumpAndSettle();

      await tester.tap(find.text('先不转写'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));

      final confirms = backend.requestsTo('/api/v1/learn/digest/confirm');
      expect(jsonDecode(confirms.first.body)['confirm'], isFalse, reason: '先不转写 = confirm(false)');
      expect(find.textContaining('已取消转写（没花钱）'), findsOneWidget, reason: '展示结果人话');
      expect(find.text('继续转写'), findsNothing);
      final pollsAfterCancel = backend.statusCalls;
      await tester.pump(const Duration(seconds: 6));
      await tester.pump();
      expect(backend.statusCalls, pollsAfterCancel, reason: '取消后不再轮询');
      expect(find.text('核心观点'), findsNothing, reason: '没进入 done，不该打开新卡');
      expect(find.text('返回最近学习'), findsOneWidget, reason: '给用户一个收尾出口');
    });

    testWidgets('⑤ 链接与素材都空：当场给提示，不往外发', (tester) async {
      final backend = _LearnBackend();
      await pump(tester, backend.api());
      await tester.tap(find.byIcon(Icons.add_circle_outline));
      await tester.pumpAndSettle();

      await tester.tap(find.text('让阿呆消化'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.textContaining('给我一个链接，或者把素材内容粘进来'), findsOneWidget);
      expect(backend.requestsTo('/api/v1/learn/digest'), isEmpty, reason: '两个都空 → 先提示，不发出去');

      // 只填链接即可提交（素材框可留空）
      backend.statusSeq = [
        {'status': 'idle'},
        {'status': 'done', 'type': 'ai', 'title': '链接来的卡'},
      ];
      await tester.enterText(find.byKey(const ValueKey('learn-digest-link')), 'https://example.com/post');
      await tester.tap(find.text('让阿呆消化'));
      await tester.pump();
      expect(backend.requestsTo('/api/v1/learn/digest').length, 1);
      expect(find.text('消化中'), findsOneWidget);

      // 让这轮轮询走到头（done 收尾），不留悬挂定时器
      await tester.pump(const Duration(milliseconds: 2500));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      expect(find.text('整理新内容'), findsNothing, reason: 'done 后收起喂入页');
      expect(find.text('最近学习'), findsOneWidget);
    });

    // ── 2026-09-12 完整升级批：图片喂入 + 额度 + 确认恢复 + 搜索 ──

    testWidgets('⑥ 图片喂入：选图整理 1~3 张 → multipart（带 Bearer）→ stage=reading → done',
        (tester) async {
      final backend = _LearnBackend()
        ..addCard('ai', '书页整理', created: '2026-09-12', topic: 'harness')
        ..skipTreeCalls = 1
        ..statusSeq = [
          {'status': 'idle'},
          {'status': 'running', 'stage': 'reading'},
          {'status': 'done', 'type': 'ai', 'title': '书页整理'},
        ];
      final api = backend.api(token: 'tok_test');
      await pump(tester, api,
          debugPickImages: () async => [
                PickedImage(const [1, 2, 3], 'page1.jpg', 'jpg'),
                PickedImage(const [4, 5, 6], 'page2.png', 'png'),
              ]);

      await tester.tap(find.byIcon(Icons.add_circle_outline));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('learn-pick-images')), findsOneWidget);
      expect(find.text('选图整理（1~3 张）'), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('learn-pick-images')));
      await tester.pump();
      expect(find.text('消化中'), findsOneWidget);

      final uploads = backend.requestsTo('/api/v1/learn/digest/image');
      expect(uploads.length, 1, reason: '应提交 POST /learn/digest/image');
      final upload = uploads.first;
      expect(upload.headers['content-type'], startsWith('multipart/form-data'),
          reason: '图片走 multipart');
      expect(upload.headers['Authorization'], 'Bearer tok_test',
          reason: 'multipart 必须显式带 Bearer（2026-09-02 线上 401 实锤）');
      final body = utf8.decode(upload.bodyBytes);
      expect('name="files"'.allMatches(body).length, 2, reason: '字段名 files，两张图两个 part');
      expect(body, contains('page1.jpg'));

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      expect(find.text('正在读图'), findsOneWidget, reason: 'stage=reading 的人话');

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();
      expect(find.text('书页整理 的核心观点'), findsOneWidget, reason: 'done → 回列表打开新卡');
    });

    testWidgets('⑦ 图片喂入失败：后端 400 人话透出（不往外甩技术原话）', (tester) async {
      final backend = _LearnBackend()
        ..imagePostError = const {'error': '一次最多 3 张图，多了我看不过来，分两次发吧'};
      await pump(tester, backend.api(),
          debugPickImages: () async => [PickedImage(const [1, 2, 3], 'page1.jpg', 'jpg')]);

      await tester.tap(find.byIcon(Icons.add_circle_outline));
      await tester.pumpAndSettle();
      final statusCallsBefore = backend.statusCalls;
      await tester.tap(find.byKey(const ValueKey('learn-pick-images')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));

      expect(find.textContaining('一次最多 3 张图'), findsOneWidget);
      expect(find.text('让阿呆消化'), findsOneWidget, reason: '失败后仍可重试');
      expect(backend.statusCalls, statusCallsBefore,
          reason: '没提交成功就不该开始轮询');
    });

    testWidgets('⑧ 本月转写额度（P2-learn15）：喂入页显示人话，喂入不被挡', (tester) async {
      final backend = _LearnBackend();
      await pump(tester, backend.api());
      await tester.tap(find.byIcon(Icons.add_circle_outline));
      await tester.pumpAndSettle();

      expect(backend.requestsTo('/api/v1/learn/digest/quota').length, 1);
      expect(find.byKey(const ValueKey('learn-quota')), findsOneWidget);
      expect(find.textContaining('本月还剩 9 小时 30 分'), findsOneWidget);
      expect(find.textContaining('已用 0.14 元'), findsOneWidget);
      expect(find.textContaining('转写 0.29 元/小时'), findsOneWidget);
    });

    testWidgets('⑨ 额度查不到：静默降级，不打扰、不挡喂入', (tester) async {
      final backend = _LearnBackend()..quotaFails = true;
      await pump(tester, backend.api());
      await tester.tap(find.byIcon(Icons.add_circle_outline));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('learn-quota')), findsNothing);
      expect(find.textContaining('加载失败'), findsNothing, reason: '额度失败静默，不弹错');
      expect(find.text('让阿呆消化'), findsOneWidget, reason: '喂入照常可用');
    });

    testWidgets('⑩ 确认恢复入口（P2-learn17）：进列表看到待拍板提示条 + 继续转写（连点守卫）',
        (tester) async {
      final backend = _LearnBackend()
        ..addCard('ai', 'RAG 笔记')
        ..statusSeq = [
          {
            'status': 'needs_confirmation', 'stage': 'transcribing',
            'message': '这个视频没有字幕，需要转写：37 分钟，预计约 0.18 元（本月剩余额度 10 小时）',
          },
          {'status': 'running', 'stage': 'transcribing'},
        ]
        ..confirmGate = Completer<void>();
      await pump(tester, backend.api());

      expect(find.byKey(const ValueKey('learn-confirm-banner')), findsOneWidget);
      expect(find.text('有一件事等你拍板'), findsOneWidget);
      expect(find.textContaining('需要转写：37 分钟'), findsOneWidget, reason: '报价值得照后端 message 显示');

      // 连点守卫：回包还没到时重复点击不再送出（pump 前 tree 未重建 → 第二次点到同一个按钮）
      await tester.tap(find.text('继续转写'));
      await tester.tap(find.text('继续转写'));
      await tester.pump();
      expect(backend.requestsTo('/api/v1/learn/digest/confirm').length, 1,
          reason: '连点只送一次（重复确认 = 重复花钱）');

      backend.confirmGate!.complete();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      final confirm = backend.requestsTo('/api/v1/learn/digest/confirm').first;
      expect(jsonDecode(confirm.body)['confirm'], isTrue);
      expect(find.byKey(const ValueKey('learn-confirm-banner')), findsNothing,
          reason: '拍板后提示条收起');
      expect(find.textContaining('转写了'), findsOneWidget, reason: '给一句人话交代');
    });

    testWidgets('⑪ 确认恢复入口：先不转写 → confirm(false) + 人话', (tester) async {
      final backend = _LearnBackend()
        ..statusSeq = [
          {
            'status': 'needs_confirmation', 'stage': 'transcribing',
            'message': '这个视频没有字幕，需要转写：37 分钟，预计约 0.18 元',
          },
        ]
        ..confirmResponse = {'status': 'cancelled', 'message': '已取消转写（没花钱）'};
      await pump(tester, backend.api());

      await tester.tap(find.text('先不转写'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));

      expect(jsonDecode(backend.requestsTo('/api/v1/learn/digest/confirm').first.body)['confirm'],
          isFalse);
      expect(find.textContaining('已取消转写（没花钱）'), findsOneWidget);
      expect(find.byKey(const ValueKey('learn-confirm-banner')), findsNothing);
    });

    testWidgets('⑫ 搜索：防抖 300ms → /learn/find → 命中打开全文', (tester) async {
      final backend = _LearnBackend()
        ..addCard('ai', 'RAG 笔记', topic: 'harness', keyPoints: const ['检索增强'])
        ..addCard('trading', '回调一半', topic: '止损');
      backend.findHits = [backend.cardOf('ai', 'RAG 笔记')];
      await pump(tester, backend.api());

      await tester.enterText(find.byKey(const ValueKey('learn-search')), 'r');
      await tester.pump(const Duration(milliseconds: 80));
      await tester.enterText(find.byKey(const ValueKey('learn-search')), 'ra');
      await tester.pump(const Duration(milliseconds: 80));
      await tester.enterText(find.byKey(const ValueKey('learn-search')), 'rag');
      await tester.pump(const Duration(milliseconds: 100));
      expect(backend.requestsTo('/api/v1/learn/find'), isEmpty,
          reason: '打字中间词不发请求（防抖 300ms）');

      await tester.pump(const Duration(milliseconds: 300));
      await tester.pump();
      final finds = backend.requestsTo('/api/v1/learn/find');
      expect(finds.length, 1, reason: '防抖后只发一次');
      expect(finds.first.url.queryParameters['q'], 'rag');
      expect(finds.first.url.queryParameters['limit'], '5');

      // 结果列表：命中卡在手，未命中的不在
      expect(find.text('RAG 笔记'), findsOneWidget);
      expect(find.text('回调一半'), findsNothing, reason: '搜索结果只列命中项');

      await tester.tap(find.text('RAG 笔记'));
      await tester.pumpAndSettle();
      expect(find.text('RAG 笔记 的核心观点'), findsOneWidget);
    });

    testWidgets('⑬ 搜索没命中：人话兜底；清空搜索回到分组列表', (tester) async {
      final backend = _LearnBackend()..addCard('ai', 'RAG 笔记', topic: 'harness');
      await pump(tester, backend.api());

      await tester.enterText(find.byKey(const ValueKey('learn-search')), '不存在的词');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pump();
      expect(find.textContaining('没找到和「不存在的词」对得上的卡'), findsOneWidget);
      expect(find.textContaining('请求'), findsNothing, reason: 'B1：不出现系统视角标签');

      await tester.enterText(find.byKey(const ValueKey('learn-search')), '');
      await tester.pump();
      expect(find.text('AI / 技术'), findsOneWidget, reason: '清空 → 回到分组列表');
    });

    testWidgets('⑭ 从对话流跳进来：直接打开指定卡全文', (tester) async {
      final backend = _LearnBackend()..addCard('ai', '刚整理好的卡', topic: 'harness');
      await pump(tester, backend.api(),
          initialCard: (type: 'ai', title: '刚整理好的卡'));
      expect(find.text('刚整理好的卡 的核心观点'), findsOneWidget);
    });

    // ── 2026-09-13 卡片管理动作批：移动到主题 / 删除（只对 writable == true 的卡）──

    testWidgets('⑮ 删除：二次确认说清后果 → DELETE /learn/cards → 回列表刷新、卡不在列表里',
        (tester) async {
      final backend = _LearnBackend()
        ..addCard('ai', '要删的卡', created: '2026-09-06', topic: 'harness')
        ..addCard('ai', '留着的卡', created: '2026-09-05', topic: 'harness');
      await pump(tester, backend.api());
      await tester.tap(find.text('要删的卡'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('learn-delete-card')));
      await tester.pumpAndSettle();

      // 确认框：说清 ① 移入回收站不彻底消失 ② 反哺过的交易候选会一起清掉
      expect(find.text('删除这张卡？'), findsOneWidget);
      expect(find.textContaining('回收站'), findsOneWidget, reason: '说清不是彻底消失');
      expect(find.textContaining('交易候选'), findsOneWidget, reason: '说清级联清理的后果');
      expect(backend.requestsTo('/api/v1/learn/cards'), isEmpty,
          reason: '没点确认前不发删除请求');

      final treeCallsBefore = backend.requestsTo('/api/v1/learn/tree').length;
      await tester.tap(find.byKey(const ValueKey('learn-delete-confirm')));
      await tester.pumpAndSettle();

      final dels = backend.requestsTo('/api/v1/learn/cards')
          .where((r) => r.method == 'DELETE').toList();
      expect(dels.length, 1, reason: '应送达 DELETE /learn/cards');
      expect(dels.first.url.queryParameters['type'], 'ai');
      expect(dels.first.url.queryParameters['title'], '要删的卡', reason: '按精确标题删');
      expect(backend.requestsTo('/api/v1/learn/tree').length, greaterThan(treeCallsBefore),
          reason: '回列表要刷新');
      expect(find.text('要删的卡'), findsNothing, reason: '删掉的卡不该还在列表里');
      expect(find.text('留着的卡'), findsOneWidget, reason: '只删这一张，别的卡不动');
      expect(find.textContaining('移进回收站'), findsWidgets, reason: '给一句人话交代');
      expect(find.textContaining('接口'), findsNothing, reason: 'B1：不出现系统口径');
    });

    testWidgets('⑯ 删除级联清理：cascadedCandidates 非空 → 如实说清掉了几条交易候选',
        (tester) async {
      final backend = _LearnBackend()
        ..addCard('trading', '回调一半的判定', created: '2026-09-06', topic: '止损',
            keyPoints: const ['回调=(high+low)/2'])
        ..addCard('trading', '留着的老卡', created: '2026-09-05', topic: '止损')
        ..deleteCascaded = const ['回调一半是买点', '止损位设在结构位'];
      await pump(tester, backend.api());
      await tester.tap(find.text('回调一半的判定'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('learn-delete-card')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('learn-delete-confirm')));
      await tester.pumpAndSettle();

      expect(find.textContaining('同时清掉了 2 条交易候选'), findsWidgets,
          reason: '级联清掉的候选条数必须如实告诉用户');
      expect(find.text('回调一半的判定'), findsNothing);
      expect(find.text('留着的老卡'), findsOneWidget);
    });

    testWidgets('⑰ 只读卡（writable=false）：不出现「移动到主题 / 删除」两个入口', (tester) async {
      final backend = _LearnBackend()
        ..addCard('ai', 'Mac 上整理的原卡', created: '2026-09-12', topic: 'harness',
            writable: false, extra: '手工卡里才有的详解段');
      await pump(tester, backend.api());
      await tester.tap(find.text('Mac 上整理的原卡'));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('learn-card-actions')), findsNothing);
      expect(find.byKey(const ValueKey('learn-move-topic')), findsNothing);
      expect(find.byKey(const ValueKey('learn-delete-card')), findsNothing);
      expect(find.text('移动到主题'), findsNothing);
      expect(find.text('删除'), findsNothing);
      expect(find.byKey(const ValueKey('learn-readonly-note')), findsOneWidget,
          reason: '只读卡保持现状：一行说明，不把人送到墙上撞');
      expect(find.textContaining('手工卡里才有的详解段'), findsOneWidget, reason: '只读也能读全文');
    });

    testWidgets('⑱ 移动到主题：预填当前主题 + 提示 → PATCH → 回列表刷新并重新定位打开',
        (tester) async {
      final backend = _LearnBackend()
        ..addCard('ai', 'RAG 笔记', created: '2026-09-06', topic: 'harness',
            keyPoints: const ['检索增强']);
      await pump(tester, backend.api());
      await tester.tap(find.text('RAG 笔记'));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('learn-card-actions')), findsOneWidget,
          reason: '能改的卡才亮这两个动作');
      final treeCallsBefore = backend.requestsTo('/api/v1/learn/tree').length;
      await tester.tap(find.byKey(const ValueKey('learn-move-topic')));
      await tester.pumpAndSettle();

      final field = tester.widget<TextField>(find.byKey(const ValueKey('learn-topic-input')));
      expect(field.controller?.text, 'harness', reason: '预填当前主题');
      expect(field.decoration?.hintText, '比如 量价关系', reason: '给个新主题的样子');
      expect(backend.requestsTo('/api/v1/learn/cards/topic'), isEmpty,
          reason: '没点确认前不发请求');

      await tester.enterText(find.byKey(const ValueKey('learn-topic-input')), '量价关系');
      await tester.tap(find.byKey(const ValueKey('learn-topic-confirm')));
      await tester.pumpAndSettle();

      final patches = backend.requestsTo('/api/v1/learn/cards/topic');
      expect(patches.length, 1, reason: '应送达 PATCH /learn/cards/topic');
      expect(patches.first.method, 'PATCH');
      final body = jsonDecode(patches.first.body) as Map<String, dynamic>;
      expect(body['type'], 'ai');
      expect(body['title'], 'RAG 笔记', reason: '按精确标题定位卡片');
      expect(body['topic'], '量价关系');
      expect(backend.requestsTo('/api/v1/learn/tree').length, greaterThan(treeCallsBefore),
          reason: '回列表要刷新');
      expect(find.textContaining('已挪到「量价关系」'), findsWidgets, reason: '人话交代');
      // 重新定位打开：详情页按新的主题渲染（换了主题分组）
      expect(find.text('量价关系'), findsWidgets, reason: '详情页主题已是新值');
      expect(find.text('RAG 笔记 的核心观点'), findsOneWidget, reason: '仍旧是这张卡');

      // 返回列表：新主题分组已就位
      await tester.tap(find.byIcon(Icons.arrow_back));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('learn-topic-ai-量价关系')), findsOneWidget,
          reason: '列表按新主题归置');
      expect(find.byKey(const ValueKey('learn-topic-ai-harness')), findsNothing,
          reason: '老主题下不该还留着它');
    });

    testWidgets('⑲ 移动到主题：主题名空着 → 当场提示、不往外发；动作送达中按钮禁用（连点守卫）',
        (tester) async {
      final backend = _LearnBackend()
        ..addCard('ai', '守卫卡', created: '2026-09-06', topic: 'harness')
        ..moveGate = Completer<void>();
      await pump(tester, backend.api());
      await tester.tap(find.text('守卫卡'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('learn-move-topic')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('learn-topic-input')), '   ');
      await tester.tap(find.byKey(const ValueKey('learn-topic-confirm')));
      await tester.pumpAndSettle();
      expect(find.textContaining('主题名不能空着'), findsOneWidget);
      expect(backend.requestsTo('/api/v1/learn/cards/topic'), isEmpty,
          reason: '空主题不发请求');

      await tester.tap(find.byKey(const ValueKey('learn-move-topic')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('learn-topic-input')), '量价关系');
      await tester.tap(find.byKey(const ValueKey('learn-topic-confirm')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));

      expect(backend.requestsTo('/api/v1/learn/cards/topic').length, 1);
      final btn = tester.widget<OutlinedButton>(find.byKey(const ValueKey('learn-move-topic')));
      expect(btn.onPressed, isNull, reason: '动作送达中：按钮禁用，连点不重复提交');

      backend.moveGate!.complete();
      await tester.pumpAndSettle();
      expect(backend.requestsTo('/api/v1/learn/cards/topic').length, 1,
          reason: '一个动作只送一次');
    });

    testWidgets('⑳ 移动失败：透出后端人话（只读卡说明），不出现「请求失败 / 接口」', (tester) async {
      final backend = _LearnBackend()
        ..addCard('ai', '撞墙卡', created: '2026-09-06', topic: 'harness')
        ..moveError = const {'error': '这张《撞墙卡》不是我在产品里写的，我只当资料看、不改动它'};
      await pump(tester, backend.api());
      await tester.tap(find.text('撞墙卡'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('learn-move-topic')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('learn-topic-input')), '量价关系');
      await tester.tap(find.byKey(const ValueKey('learn-topic-confirm')));
      await tester.pumpAndSettle();

      expect(find.textContaining('我只当资料看、不改动它'), findsWidgets,
          reason: '后端 400 人话如实透出');
      expect(find.textContaining('接口'), findsNothing, reason: 'B1：不出现系统口径');
      expect(find.textContaining('请求失败'), findsNothing);
      expect(find.byKey(const ValueKey('learn-topic-input')), findsNothing,
          reason: '弹窗已收起');
      expect(find.text('撞墙卡 的核心观点'), findsOneWidget, reason: '失败不 pop，人还在这页');
    });

    testWidgets('㉑ 删除失败（403 learn 插件未启用）：透出后端人话，卡还在', (tester) async {
      final backend = _LearnBackend()
        ..addCard('ai', '删不掉的卡', created: '2026-09-06', topic: 'harness')
        // 真后端 403 回 body：{"error":"learn 插件未启用，无法使用学习功能"}
        ..deleteError = const {'error': 'learn 插件未启用，无法使用学习功能'}
        ..deleteStatus = 403;
      await pump(tester, backend.api());
      await tester.tap(find.text('删不掉的卡'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('learn-delete-card')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('learn-delete-confirm')));
      await tester.pumpAndSettle();

      expect(find.textContaining('learn 插件未启用'), findsWidgets, reason: '403 人话如实透出');
      expect(find.text('删不掉的卡 的核心观点'), findsOneWidget, reason: '没删掉，人还在这页');
      expect(find.textContaining('接口'), findsNothing, reason: 'B1：不出现系统口径');

      await tester.tap(find.byIcon(Icons.arrow_back));
      await tester.pumpAndSettle();
      expect(find.text('删不掉的卡'), findsOneWidget, reason: '列表里这张卡还在');
    });

    testWidgets('㉒ 403 没带人话时：按本页口径兜底，不甩状态码/系统原话', (tester) async {
      final backend = _LearnBackend()
        ..addCard('ai', '兜底卡', created: '2026-09-06', topic: 'harness')
        ..deleteStatus = 403; // 空 body 的 403
      await pump(tester, backend.api());
      await tester.tap(find.text('兜底卡'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('learn-delete-card')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('learn-delete-confirm')));
      await tester.pumpAndSettle();

      expect(find.textContaining('学习功能未启用（learn 插件）'), findsWidgets);
      expect(find.textContaining('403'), findsNothing, reason: '不甩状态码');
      expect(find.textContaining('接口'), findsNothing, reason: 'B1：不出现系统口径');
    });
  });
}
