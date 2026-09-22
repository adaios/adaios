import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:file_picker/file_picker.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:adai_web/models/feed_models.dart';
import 'package:adai_web/pages/feed_page.dart';
import 'package:adai_web/services/api_service.dart';
import 'package:adai_web/widgets/desktop_feed_card.dart';

/// UTF-8 JSON 响应（MockClient 默认 Latin-1，中文会炸 → 显式 charset=utf-8）。
http.Response _json(Object body, {int status = 200}) => http.Response(
      jsonEncode(body),
      status,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );

/// 选图替身：测试里不走真文件选择器（不弹系统窗口）。
class _FakePicker extends FilePicker {
  _FakePicker(this.names);
  final List<String> names;

  @override
  Future<FilePickerResult?> pickFiles({
    String? dialogTitle,
    String? initialDirectory,
    FileType type = FileType.any,
    List<String>? allowedExtensions,
    Function(FilePickerStatus)? onFileLoading,
    bool allowCompression = true,
    int compressionQuality = 30,
    bool allowMultiple = false,
    bool withData = false,
    bool withReadStream = false,
    bool lockParentWindow = false,
    bool readSequential = false,
  }) async =>
      FilePickerResult([
        for (var i = 0; i < names.length; i++)
          PlatformFile(
            name: names[i],
            size: 4,
            bytes: Uint8List.fromList([65 + i, 66, 67, 68]),
          ),
      ]);
}

/// 注入选图替身（跑完还原）。
void _useFakePicker(List<String> names) {
  FilePicker? original;
  try {
    original = FilePicker.platform;
  } catch (_) {
    original = null;
  }
  FilePicker.platform = _FakePicker(names);
  addTearDown(() {
    if (original != null) FilePicker.platform = original;
  });
}

/// FeedPage 端到端的公共 mock：feed 空 / brief / tags 都返回最小数据；
/// 其余（媒体相关端点）转交 [onApi] 处理。
MockClient _feedPageMock(Future<http.Response> Function(http.Request req) onApi) {
  return MockClient((req) async {
    final path = req.url.path;
    if (path == '/api/v1/brief') return _json({'content': '今日概览'});
    if (path == '/api/v1/brief/cached') return _json({'content': '今日概览'});
    if (path == '/api/v1/feed') {
      return _json({'entries': <Map<String, dynamic>>[], 'totalToday': 0, 'hasHistory': true});
    }
    if (path == '/api/v1/tags') return _json({'tags': [], 'total': 0, 'updatedAt': ''});
    if (path.startsWith('/api/v1/records/media')) return onApi(req);
    return _json({'error': 'not mocked: $path'}, status: 404);
  });
}

Map<String, dynamic> _batchJson({
  String recordId = 'rec_1',
  List<String>? mediaIds,
  String type = 'image',
  String intent = 'log',
  String summary = '三张照片：持仓、走势和收益',
  String? answer,
  List<String> tags = const ['持仓'],
  bool duplicated = false,
}) =>
    {
      'recordId': recordId,
      'mediaIds': mediaIds ?? ['rec_1', 'rec_2', 'rec_3'],
      'type': type,
      'intent': intent,
      'summary': summary,
      'answer': answer,
      'tags': tags,
      'domain': 'life',
      'duplicated': duplicated,
    };

Future<void> _pickAndSend(WidgetTester tester) async {
  await tester.tap(find.byTooltip('选择图片'));
  await tester.pumpAndSettle();
  await tester.tap(find.byIcon(Icons.arrow_upward));
}

void main() {
  group('P1-多图2 超时修复：VLM 类请求走长超时（AI）客户端', () {
    test('uploadImage 打的是 aiClient（120s），不是 15s 的 _client', () async {
      var fastCalls = 0;
      var aiCalls = 0;
      final fast = MockClient((_) async {
        fastCalls++;
        return _json({});
      });
      final ai = MockClient((req) async {
        aiCalls++;
        expect(req.url.path, '/api/v1/records/media');
        return _json({
          'recordId': 'rec_1',
          'intent': 'log',
          'summary': '一张截图',
          'tags': <String>[],
          'mediaPath': 'records/2026/09/media/rec_1.png',
        });
      });
      final api = ApiService(baseUrl: 'http://test', client: fast, aiClient: ai);
      final resp = await api.uploadImage(bytes: [1, 2, 3], filename: 'a.png', mimeType: 'image/png');
      expect(resp.recordId, 'rec_1');
      expect(aiCalls, 1, reason: 'uploadImage（VLM 看图）必须走长超时客户端');
      expect(fastCalls, 0, reason: '15s 客户端不该收到 VLM 请求（客户端先超时=重复落盘推手）');
    });

    test('超时常量口径：AI 120s / 普通 15s（防回退）', () {
      // 回归防线：这两条常量是「web 发图客户端先超时、服务端还在跑」的直接开关
      expect(ApiService.aiTimeout, const Duration(seconds: 120));
      expect(ApiService.defaultTimeout, const Duration(seconds: 15));
    });

    test('uploadImages 批量投递也走 aiClient', () async {
      var aiCalls = 0;
      final ai = MockClient((_) async {
        aiCalls++;
        return _json(_batchJson());
      });
      final api = ApiService(baseUrl: 'http://test', client: MockClient((_) async => _json({})), aiClient: ai);
      await api.uploadImages(
        files: const [MediaUploadFile(bytes: [1], filename: 'a.png', mimeType: 'image/png')],
      );
      expect(aiCalls, 1);
    });
  });

  group('一次投递一次请求：POST /records/media/batch 契约', () {
    test('multipart files×N + text + Idempotency-Key + 响应解析', () async {
      String? body;
      http.Request? seen;
      final ai = MockClient((req) async {
        seen = req;
        body = utf8.decode(req.bodyBytes);
        return _json(_batchJson(duplicated: true, summary: '三张图一起看：持仓、走势、收益'));
      });
      final api = ApiService(
        baseUrl: 'http://test',
        userId: 'adai',
        token: 'tk-test',
        client: MockClient((_) async => _json({})),
        aiClient: ai,
      );
      final resp = await api.uploadImages(
        files: const [
          MediaUploadFile(bytes: [1], filename: 'a.png', mimeType: 'image/png'),
          MediaUploadFile(bytes: [2], filename: 'b.jpg', mimeType: 'image/jpeg'),
          MediaUploadFile(bytes: [3], filename: 'c.webp', mimeType: 'image/webp'),
        ],
        text: '这三张分别是啥？',
        idempotencyKey: 'web_media_123',
      );

      // 端点 + 方法 + multipart
      expect(seen!.method, 'POST');
      expect(seen!.url.path, '/api/v1/records/media/batch');
      expect(seen!.headers['content-type'], contains('multipart/form-data'));
      // 字段名必须是复数 files（旧单图端点是 file）
      expect('name="files"'.allMatches(body!).length, 3);
      expect(body, isNot(contains('name="file"')));
      expect(body, contains('a.png'));
      expect(body, contains('b.jpg'));
      expect(body, contains('c.webp'));
      // text 字段（可空但显式带上）
      expect(body, contains('name="text"'));
      expect(body, contains('这三张分别是啥？'));
      // 幂等键 + 鉴权头
      expect(seen!.headers['idempotency-key'], 'web_media_123');
      expect(seen!.headers['authorization'], 'Bearer tk-test');
      expect(seen!.headers['x-user-id'], 'adai');

      // 响应解析
      expect(resp.recordId, 'rec_1');
      expect(resp.mediaIds, ['rec_1', 'rec_2', 'rec_3']);
      expect(resp.type, 'image');
      expect(resp.summary, '三张图一起看：持仓、走势、收益');
      expect(resp.duplicated, isTrue);
      expect(resp.isQa, isFalse);
    });

    test('image_qa 响应：answer 有值 + isQa 为真', () {
      final resp = BatchMediaResponse.fromJson(
          _batchJson(type: 'image_qa', intent: 'question', answer: '左边是持仓，右边是走势。'));
      expect(resp.isQa, isTrue);
      expect(resp.answer, '左边是持仓，右边是走势。');
    });

    test('缺字段兜底（旧后端/异常响应不崩）', () {
      final resp = BatchMediaResponse.fromJson(const {});
      expect(resp.recordId, '');
      expect(resp.mediaIds, isEmpty);
      expect(resp.type, 'image');
      expect(resp.intent, 'log');
      expect(resp.answer, isNull);
      expect(resp.duplicated, isFalse);
      expect(resp.isQa, isFalse);
    });
  });

  group('Feed mediaPaths 消费（多图并列）', () {
    test('mediaPaths 解析 + 单值 mediaPath 降级兼容', () {
      final multi = FeedEntryResponse.fromJson(jsonDecode(jsonEncode({
        'type': 'record',
        'id': 'rec_a',
        'content': '',
        'mediaPaths': [
          'records/2026/09/media/rec_a.png',
          'records/2026/09/media/rec_b.png',
        ],
      })) as Map<String, dynamic>);
      expect(multi.mediaPaths, [
        'records/2026/09/media/rec_a.png',
        'records/2026/09/media/rec_b.png',
      ]);

      // 旧后端只有单值 mediaPath → 降级为单元素列表（单值兼容）
      final single = FeedEntryResponse.fromJson(jsonDecode(jsonEncode({
        'type': 'record',
        'id': 'rec_x',
        'content': '',
        'mediaPath': 'records/2026/09/media/rec_x.jpg',
      })) as Map<String, dynamic>);
      expect(single.mediaPaths, ['records/2026/09/media/rec_x.jpg']);

      // 无图条目
      final none = FeedEntryResponse.fromJson(
          jsonDecode(jsonEncode({'type': 'record', 'id': 'rec_y', 'content': 'hi'})) as Map<String, dynamic>);
      expect(none.mediaPaths, isEmpty);
    });

    test('mediaPath → 记录 id → 原图 URL（多图按顺序）；认不出时回退 entry.id', () {
      final api = ApiService(baseUrl: 'http://test');
      expect(ApiService.recordIdOfMediaPath('records/2026/09/media/rec_a.png'), 'rec_a');
      expect(ApiService.recordIdOfMediaPath('records/2026/09/media/broken.png'), isNull);
      expect(ApiService.recordIdOfMediaPath(null), isNull);

      expect(
        api.mediaUrlsForPaths([
          'records/2026/09/media/rec_a.png',
          'records/2026/09/media/rec_b.jpg',
        ]),
        [
          'http://test/api/v1/records/media/rec_a',
          'http://test/api/v1/records/media/rec_b',
        ],
      );
      // 路径格式不符 → 单图兼容回退
      expect(api.mediaUrlsForPaths(['legacy/unknown.png'], fallbackRecordId: 'rec_z'),
          ['http://test/api/v1/records/media/rec_z']);
    });

    test('toFeedData 把 mediaPaths 变成 mediaUrls（多图）+ mediaUrl 取首图', () {
      final api = ApiService(baseUrl: 'http://test');
      final entry = FeedEntryResponse.fromJson(jsonDecode(jsonEncode({
        'type': 'record',
        'id': 'rec_a',
        'content': '三张图',
        'mediaPaths': [
          'records/2026/09/media/rec_a.png',
          'records/2026/09/media/rec_b.png',
          'records/2026/09/media/rec_c.png',
        ],
      })) as Map<String, dynamic>);
      final card = entry.toFeedData(api: api);
      expect(card.mediaUrls.length, 3);
      expect(card.mediaUrl, 'http://test/api/v1/records/media/rec_a');
      expect(card.mediaHeaders, isNotNull);
    });
  });

  group('卡片多图渲染（P1-多图1）', () {
    Future<void> pumpCard(WidgetTester tester, FeedCardData data) => tester.pumpWidget(
          MaterialApp(
            home: Scaffold(
              body: SingleChildScrollView(child: DesktopFeedCard(data: data)),
            ),
          ),
        );

    testWidgets('3 张图并列渲染 + 「共 3 张」角标 + 点击开全图', (tester) async {
      await pumpCard(tester, FeedCardData(
        id: 'rec_a',
        type: FeedCardType.record,
        time: '14:00',
        content: '三张照片：持仓、走势和收益',
        mediaUrls: const [
          'http://test/api/v1/records/media/rec_a',
          'http://test/api/v1/records/media/rec_b',
          'http://test/api/v1/records/media/rec_c',
        ],
        mediaHeaders: const {'X-User-Id': 'adai'},
      ));
      expect(find.byType(Image), findsNWidgets(3));
      expect(find.text('共 3 张'), findsOneWidget);

      await tester.tap(find.byType(Image).first);
      await tester.pumpAndSettle();
      expect(find.byType(Dialog), findsOneWidget);
    });

    testWidgets('单图不显示张数角标（旧单图卡零回归）', (tester) async {
      await pumpCard(tester, FeedCardData(
        id: 'rec_a',
        type: FeedCardType.record,
        time: '14:00',
        content: '一张截图',
        mediaUrl: 'http://test/api/v1/records/media/rec_a',
      ));
      expect(find.byType(Image), findsOneWidget);
      expect(find.textContaining('共 '), findsNothing);
    });

    testWidgets('上传在途占位卡：并列 N 个占位缩略图 + 张数角标（不逐张插卡）', (tester) async {
      await pumpCard(tester, FeedCardData(
        id: 'media_1',
        type: FeedCardType.record,
        time: '14:00',
        content: '三张图',
        loading: true,
        pendingMediaCount: 3,
      ));
      expect(find.byIcon(Icons.image_outlined), findsNWidgets(3));
      expect(find.text('共 3 张'), findsOneWidget);
      expect(find.byType(Image), findsNothing); // 还没上传完，不发原图请求
    });
  });

  group('FeedPage 一次投递 = 一个回合 = 一张卡', () {
    testWidgets('3 张图一次请求 → 1 张卡（多图角标 + summary 作为阿呆回执）', (tester) async {
      _useFakePicker(['a.png', 'b.png', 'c.png']);
      var batchCalls = 0;
      var singleCalls = 0;
      String? body;
      http.Request? seen;
      final api = ApiService(
        baseUrl: 'http://test',
        client: _feedPageMock((req) async {
          batchCalls++;
          seen = req;
          body = utf8.decode(req.bodyBytes);
          return _json(_batchJson());
        }),
      );
      await tester.pumpWidget(MaterialApp(home: Scaffold(body: FeedPage(api: api))));
      await tester.pumpAndSettle();

      await _pickAndSend(tester);
      await tester.pumpAndSettle();

      // 一次投递 = 一次请求（不再逐张串行）
      expect(batchCalls, 1);
      expect(singleCalls, 0);
      expect('name="files"'.allMatches(body!).length, 3);
      // 幂等键每次都带（同一次投递只生成一次）
      expect(seen!.headers['idempotency-key'], isNotNull);
      expect(seen!.headers['idempotency-key']!.isNotEmpty, isTrue);

      // 一张卡：多图并列 + 张数角标 + summary 作内容（无 caption 时 = 阿呆的综合总结）
      expect(find.byType(DesktopFeedCard), findsOneWidget);
      expect(find.text('共 3 张'), findsOneWidget);
      expect(find.text('三张照片：持仓、走势和收益'), findsOneWidget);
      expect(find.byType(Image), findsNWidgets(3));
      // 自然回执（第一原则：不出现系统标签）
      expect(find.textContaining('这 3 张都记下了'), findsOneWidget);
    });

    testWidgets('带提问的一次投递（image_qa）→ 卡内 Q/A + 直接可继续追问', (tester) async {
      _useFakePicker(['a.png', 'b.png']);
      final api = ApiService(
        baseUrl: 'http://test',
        client: _feedPageMock((req) async {
          expect(utf8.decode(req.bodyBytes), contains('这两张有什么关系？'));
          return _json(_batchJson(
            mediaIds: ['rec_1', 'rec_2'],
            type: 'image_qa',
            intent: 'question',
            summary: '两张图对照',
            answer: '左边是持仓，右边是它的走势。',
          ));
        }),
      );
      await tester.pumpWidget(MaterialApp(home: Scaffold(body: FeedPage(api: api))));
      await tester.pumpAndSettle();

      await tester.tap(find.byTooltip('选择图片'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), '这两张有什么关系？');
      await tester.tap(find.byIcon(Icons.arrow_upward));
      await tester.pumpAndSettle();

      // 同一张卡里：图 + 我的问 + 阿呆的答
      expect(find.byType(DesktopFeedCard), findsOneWidget);
      expect(find.text('这两张有什么关系？'), findsOneWidget);
      expect(find.text('左边是持仓，右边是它的走势。'), findsOneWidget);
      // 已进入对话态 → 可在这张卡上继续追问
      expect(find.text('结束'), findsOneWidget);
    });
  });

  group('批次锁 + 上传进度（P1-多图2）', () {
    testWidgets('在途再投递 → 人话拒绝 + 只发一次请求；进度条显示本批张数', (tester) async {
      _useFakePicker(['a.png', 'b.png', 'c.png']);
      final gate = Completer<void>();
      var batchCalls = 0;
      final api = ApiService(
        baseUrl: 'http://test',
        client: _feedPageMock((req) async {
          batchCalls++;
          await gate.future; // 挂住，模拟还在跑
          return _json(_batchJson());
        }),
      );
      await tester.pumpWidget(MaterialApp(home: Scaffold(body: FeedPage(api: api))));
      await tester.pumpAndSettle();

      await _pickAndSend(tester);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));
      expect(batchCalls, 1);
      // 上传进度（阿呆正在看图）
      expect(find.text('阿呆正在看这 3 张图…'), findsOneWidget);
      // 占位卡：1 张卡 + 3 个占位缩略图
      expect(find.byType(DesktopFeedCard), findsOneWidget);
      expect(find.text('共 3 张'), findsOneWidget);

      // 在途再发一批 → 批次锁拒绝（人话提示，不静默）
      FilePicker.platform = _FakePicker(['d.png']);
      await tester.tap(find.byTooltip('选择图片'));
      await tester.pump();
      await tester.tap(find.byIcon(Icons.arrow_upward));
      await tester.pump();
      expect(find.text('上一批图片还在上传，稍等片刻'), findsOneWidget);
      expect(batchCalls, 1, reason: '批次锁：在途时不得再发第二个请求');

      gate.complete();
      await tester.pumpAndSettle();
      expect(find.text('阿呆正在看这 3 张图…'), findsNothing); // 完成 → 收起进度
      expect(find.byType(DesktopFeedCard), findsOneWidget);
    });
  });

  group('幂等键复用 + 失败重试补跑（P1-多图2 / #8）', () {
    testWidgets('首次失败 → 重试复用同一个 Idempotency-Key（后端不重复入库）', (tester) async {
      _useFakePicker(['a.png', 'b.png']);
      final keys = <String>[];
      final api = ApiService(
        baseUrl: 'http://test',
        client: _feedPageMock((req) async {
          keys.add(req.headers['idempotency-key'] ?? '');
          if (keys.length == 1) return _json({'error': '图片记录失败'}, status: 500);
          return _json(_batchJson(mediaIds: ['rec_1', 'rec_2'], duplicated: true));
        }),
      );
      await tester.pumpWidget(MaterialApp(home: Scaffold(body: FeedPage(api: api))));
      await tester.pumpAndSettle();

      await _pickAndSend(tester);
      await tester.pumpAndSettle();

      // 失败 → 这张卡可重试（不静默吞）
      expect(find.text('重试'), findsOneWidget);
      await tester.tap(find.text('重试'));
      await tester.pumpAndSettle();

      expect(keys.length, 2);
      expect(keys[0].isNotEmpty, isTrue);
      expect(keys[1], keys[0], reason: '重试必须复用同一个幂等键，否则服务端会重复落盘');
      // 重试成功 → 原位变记录卡（title 用 summary 兜底展示）
      expect(find.text('重试'), findsNothing);
      expect(find.byType(Image), findsNWidgets(2));
    });
  });

  group('多图追问走 ask-batch（P1-多图1 / #5）', () {
    testWidgets('会话内多图卡的追问 → POST /records/media/ask-batch（带全部图 id）', (tester) async {
      _useFakePicker(['a.png', 'b.png', 'c.png']);
      List<String>? askIds;
      String? askQuestion;
      var singleAskCalls = 0;
      final api = ApiService(
        baseUrl: 'http://test',
        client: _feedPageMock((req) async {
          final path = req.url.path;
          if (path == '/api/v1/records/media/ask-batch') {
            final body = jsonDecode(req.body) as Map<String, dynamic>;
            askIds = (body['imageRecordIds'] as List).cast<String>();
            askQuestion = body['question'] as String;
            return _json({
              'intent': 'question',
              'answer': '三张一起看：持仓在涨，走势向上，收益为正。',
              'recordId': 'qa_1',
              'imageRecordIds': askIds,
            });
          }
          if (path.endsWith('/ask')) {
            singleAskCalls++;
            return _json({'recordId': 'qa_x', 'answer': '单图回答', 'imageRecordId': 'rec_1'});
          }
          return _json(_batchJson());
        }),
      );
      await tester.pumpWidget(MaterialApp(home: Scaffold(body: FeedPage(api: api))));
      await tester.pumpAndSettle();

      await _pickAndSend(tester);
      await tester.pumpAndSettle();
      // 等投递成功的那条 SnackBar 自己消失（3s + 淡出动画）——否则它盖住底部发送按钮，tap 打空
      await tester.pump(const Duration(seconds: 4));
      await tester.pumpAndSettle();

      // 点「提问」→ 输入追问 → 发送
      // 注：图片卡点「提问」后进入 waiting 态（卡内「正在思考…」spinner 常驻）→
      // 不能 pumpAndSettle（无限动画），用单帧 pump 推进。
      await tester.tap(find.text('提问'));
      await tester.pump();
      await tester.enterText(find.byType(TextField), '这三张放一起说明什么？');
      await tester.tap(find.byIcon(Icons.arrow_upward));
      await tester.pumpAndSettle();

      expect(askIds, ['rec_1', 'rec_2', 'rec_3'], reason: '多图追问必须带该回合全部图 id');
      expect(askQuestion, '这三张放一起说明什么？');
      expect(singleAskCalls, 0, reason: '多图回合不再走单图 askMedia');
      // 回答落在同一张卡上（带图对话）
      expect(find.text('三张一起看：持仓在涨，走势向上，收益为正。'), findsOneWidget);
      expect(find.byType(DesktopFeedCard), findsOneWidget);
    });
  });
}
