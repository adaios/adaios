import 'dart:convert';
import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:adai_web/pages/trading_page.dart';
import 'package:adai_web/services/api_service.dart';

/// R-12 统一导入 + R-05 三粒度分析的 web 面验收（2026-10-06 首跑；2026-10-07 对齐右侧抽屉）。
/// 口径同 trading_page_test.dart：替身选文件 + MockClient（UTF-8 charset）。

/// 选文件替身：测试里不走真文件选择器（同 trading_page_test.dart 的 _FakePicker）。
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

http.Response _json(Object body) => http.Response(
      jsonEncode(body),
      200,
      headers: {'content-type': 'application/json; charset=utf-8'},
    );

/// multipart 文本字段断言（MockClient 已把请求 finalize 成字节；UTF-8 解码后按
/// `name="字段名"` 定位，紧随 `\r\n\r\n` 的值必须匹配）。
bool _hasField(http.Request r, String name, String value) {
  final body = utf8.decode(r.bodyBytes, allowMalformed: true);
  final i = body.indexOf('name="$name"');
  if (i < 0) return false;
  final seg = body.substring(i, i + 200 > body.length ? body.length : i + 200);
  return seg.contains('\r\n\r\n$value');
}

/// 基础端点（与 trading_page_test._tradingHandler 同口径）；未命中返回 null。
Future<http.Response?> _baseRoute(http.Request request) async {
  final path = request.url.path;
  if (path == '/api/v1/trading/portfolio') {
    return _json({'totalValue': 5220.0, 'totalPnl': 160.0, 'cashBalance': 2000.0, 'positionCount': 1});
  }
  if (path == '/api/v1/trading/positions') return _json([]);
  if (path == '/api/v1/trading/account') {
    return _json({
      'assets': 110504.88, 'cash': 292.88, 'available': 292.88, 'withdrawable': 292.88,
      'marketValue': 110212.00, 'pnl': 15235.55, 'todayPnl': 0.0, 'snapshotDate': '2026-08-16',
    });
  }
  if (path == '/api/v1/trading/watchlist') return _json([]);
  if (path == '/api/v1/trading/sold') return _json([]);
  if (path == '/api/v1/trading/buy-points') return _json([]);
  if (path == '/api/v1/trading/sold/score') return _json([]);
  if (path == '/api/v1/trading/equity-curve') {
    return _json({
      'points': [
        {'date': '2026-08-03', 'totalAssets': 95000.0, 'cash': 20000.0, 'marketValue': 75000.0, 'invested': 100000.0, 'netValue': 0.95, 'drawdown': 0.05},
        {'date': '2026-08-04', 'totalAssets': 108000.0, 'cash': 8000.0, 'marketValue': 100000.0, 'invested': 100000.0, 'netValue': 1.08, 'drawdown': 0.0},
      ],
      'skippedDays': 0,
      'startDate': '2026-08-03',
      'endDate': '2026-08-04',
    });
  }
  if (path == '/api/v1/trading/trades') return _json([]);
  if (path == '/api/v1/trading/reviews') return _json([]);
  return null;
}

/// 挂载交易页（宽视口，Tab 全可见；同 trading_page_test._pumpTrading）。
Future<void> _pumpTrading(WidgetTester tester, ApiService api) async {
  await tester.binding.setSurfaceSize(const Size(1800, 900));
  addTearDown(() => tester.binding.setSurfaceSize(null));
  await tester.pumpWidget(MaterialApp(home: Scaffold(body: TradingPage(api: api))));
  await tester.pumpAndSettle();
}

/// 全局粒度的最简响应（多数用例不细验它）。
const _globalStub = {
  'scope': 'global',
  'label': '我是什么样的交易者',
  'description': <Map<String, dynamic>>[],
  'contrast': {'hasRules': false, 'reason': '我还没有你的规则，判不了守没守', 'ruleHits': []},
  'summary': {'fact': null, 'contrast': null, 'question': null},
};

void main() {
  group('R-12 统一导入（web，2026-10-06）', () {
    testWidgets('多选 → 点虚线框即自动预检（dryRun）→ 确认入账 → 逐份回执 + 刷新数据', (tester) async {
      var portfolioCalls = 0;
      final dryRuns = <String>[];
      final client = MockClient((request) async {
        final path = request.url.path;
        if (path == '/api/v1/trading/portfolio') {
          portfolioCalls++;
          return _json({'totalValue': 5220.0, 'totalPnl': 160.0, 'cashBalance': 2000.0, 'positionCount': 1});
        }
        final base = await _baseRoute(request);
        if (base != null) return base;
        if (path == '/api/v1/trading/import' && request.method == 'POST') {
          if (_hasField(request, 'dryRun', 'true')) {
            dryRuns.add('true');
            return _json({
              'dryRun': true, 'okCount': 2, 'failedCount': 0,
              'files': [
                {
                  'filename': '历史成交.txt', 'kind': 'trades', 'kindLabel': '历史成交', 'ok': true,
                  'detail': {
                    'dryRun': true,
                    'plan': {'new': 3, 'merged': 1, 'skipped': 2, 'wouldReject': 0},
                  },
                },
                {
                  'filename': '资金股份.txt', 'kind': 'cash', 'kindLabel': '资金股份', 'ok': true,
                  'detail': {'dryRun': true, 'brokerCash': 1000.0, 'systemCash': 1000.0, 'diff': 0.0},
                },
              ],
            });
          }
          dryRuns.add('false');
          return _json({
            'dryRun': false, 'okCount': 1, 'failedCount': 1,
            'files': [
              {
                'filename': '历史成交.txt', 'savedPath': 'trading/imports/2026-10/x.txt',
                'kind': 'trades', 'kindLabel': '历史成交', 'ok': true,
                'detail': {'imported': 3, 'updated': 1, 'skipped': 2, 'syncMode': 'sync'},
              },
              {
                'filename': '乱文件.txt', 'kind': 'unknown', 'kindLabel': '无法识别', 'ok': false,
                'error': '没认出这份文件是哪类导出——支持：历史成交 / 资金股份 / 持仓股 / 清仓股 / 自选股。本份未做任何改动',
              },
            ],
          });
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);
      _useFakePicker(['历史成交.txt', '资金股份.txt']);

      // 2026-10-07（原型 web-7）：右侧抽屉只有一个入口——点虚线框一次选 2 份，
      // 选完自动预检（「选完就看计划」，不用再点一次）
      await tester.tap(find.text('导入持仓'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('importPickZone')));
      await tester.pumpAndSettle();

      expect(dryRuns, ['true'], reason: '选完文件即自动预检');
      expect(find.text('这次认出来的'), findsOneWidget);
      expect(find.text('已选 2 份——点这里重新选'), findsOneWidget);
      expect(find.text('历史成交.txt'), findsNWidgets(2), reason: '文件列表 + 回执行各一份');
      expect(find.text('资金股份.txt'), findsNWidgets(2), reason: '文件列表 + 回执行各一份');
      expect(find.textContaining('计划：2 份能处理'), findsOneWidget);
      expect(find.textContaining('新增 3 · 并入 1 · 跳过 2'), findsOneWidget);
      expect(find.textContaining('对账：券商 ¥1,000.00 vs 系统'), findsOneWidget);

      // 确认入账（dryRun=false）→ 一份失败不影响其他份（逐份如实回执）
      await tester.tap(find.byKey(const Key('importConfirmBtn')));
      await tester.pumpAndSettle();
      expect(dryRuns, ['true', 'false']);
      expect(find.text('这次交齐的'), findsOneWidget);
      expect(find.textContaining('导入完成：成功 1 份，失败 1 份'), findsOneWidget);
      expect(find.textContaining('没认出这份文件是哪类导出'), findsOneWidget);
      expect(find.textContaining('数据已更新'), findsOneWidget);
      expect(portfolioCalls, greaterThan(1), reason: '导入成功后必须刷新页面数据');
    });

    testWidgets('选 1 份 → 同样自动预检（不再分单份/多份两条路）', (tester) async {
      final dryRuns = <String>[];
      final client = MockClient((request) async {
        final base = await _baseRoute(request);
        if (base != null) return base;
        if (request.url.path == '/api/v1/trading/import' && request.method == 'POST') {
          dryRuns.add(_hasField(request, 'dryRun', 'true') ? 'dry' : 'real');
          return _json({
            'dryRun': true, 'okCount': 1, 'failedCount': 0,
            'files': [
              {
                'filename': '持仓20261001.txt', 'kind': 'positions', 'kindLabel': '持仓股',
                'ok': true,
                'detail': {'dryRun': true, 'fileCount': 1, 'systemCount': 0, 'diffs': [], 'note': '一致'},
              },
            ],
          });
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);
      _useFakePicker(['持仓20261001.txt']);

      await tester.tap(find.text('导入持仓'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('importPickZone')));
      await tester.pumpAndSettle();

      expect(dryRuns, ['dry'], reason: '单份也走统一链——选完自动预检，不再有「单份转文本框」的老路');
      expect(find.text('这次认出来的'), findsOneWidget);
      expect(find.text('确认入账 1 条'), findsOneWidget);
    });
  });

  group('R-05 三粒度分析（web，2026-10-06）', () {
    testWidgets('打开「分析」Tab → 自动全局：数字带出处、缺数据「—」、无规则明说判不了、总结三段', (tester) async {
      final client = MockClient((request) async {
        final base = await _baseRoute(request);
        if (base != null) return base;
        if (request.url.path == '/api/v1/trading/analysis/global') {
          return _json({
            'scope': 'global',
            'label': '我是什么样的交易者',
            'description': [
              {
                'key': 'winRate', 'label': '胜率', 'value': 55.5, 'unit': '%',
                'trace': {'roundIds': ['600206_2026-08-05', '600601_2026-08-08'], 'dates': [], 'note': null},
              },
              {
                'key': 'avgHoldDays', 'label': '平均持仓', 'value': null, 'unit': '天',
                'trace': {'roundIds': [], 'dates': [], 'note': '还没有已了结的笔'},
              },
            ],
            'contrast': {'hasRules': false, 'reason': '我还没有你的规则，判不了守没守', 'ruleHits': []},
            'summary': {
              'fact': '你最近 2 笔都做了主板。',
              'contrast': '对照规则：暂时比不了。',
              'question': '想先给哪条规则定个数？',
            },
          });
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('分析'));
      await tester.pumpAndSettle();

      expect(find.text('我是什么样的交易者'), findsOneWidget);
      expect(find.text('55.5%'), findsOneWidget);
      expect(find.textContaining('来自 600206_2026-08-05、600601_2026-08-08'), findsOneWidget);
      expect(find.text('—'), findsWidgets, reason: '缺数据如实说「—」，绝不渲染成 0');
      expect(find.textContaining('还没有已了结的笔'), findsOneWidget);
      expect(find.textContaining('判不了守没守'), findsOneWidget);
      expect(find.text('你最近 2 笔都做了主板。'), findsOneWidget);
      expect(find.text('想先给哪条规则定个数？'), findsOneWidget);
    });

    testWidgets('单标的：空输入拦截不发请求 → 填代码按 symbol 取数（字符串值/规则命中/哪几笔）', (tester) async {
      final requests = <String>[];
      final client = MockClient((request) async {
        final base = await _baseRoute(request);
        if (base != null) return base;
        final path = request.url.path;
        if (path == '/api/v1/trading/analysis/global') {
          requests.add('global:');
          return _json(_globalStub);
        }
        if (path == '/api/v1/trading/analysis/symbol') {
          requests.add('symbol:${request.url.queryParameters['symbol']}');
          return _json({
            'scope': 'symbol', 'label': '600206 这只票',
            'description': [
              {
                'key': 'rounds', 'label': '做了几笔', 'value': '3 次（其中被套 1 次）', 'unit': null,
                'trace': {'roundIds': ['a', 'b', 'c'], 'dates': [], 'note': null},
              },
            ],
            'contrast': {
              'hasRules': true, 'reason': null,
              'ruleHits': [
                {'rule': 'R53', 'text': '亏损单不超过 3 天', 'count': 2, 'roundIds': ['600206_2026-08-05', '600206_2026-09-01']},
              ],
            },
            'summary': {'fact': '这只票一共做了 3 笔。', 'contrast': null, 'question': null},
          });
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('分析'));
      await tester.pumpAndSettle();
      expect(requests, ['global:']);

      // 切「单标的」→ 空输入直接点 → 人话拦截，不发请求
      await tester.tap(find.text('单标的'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('看分析'));
      await tester.pumpAndSettle();
      expect(find.textContaining('先填股票代码'), findsOneWidget);
      expect(requests, ['global:'], reason: '空输入不发请求');

      // 填代码 → 出结果（字符串值直显 + 规则命中 + 哪几笔）
      await tester.enterText(find.byKey(const Key('analysisSymbol')), '600206');
      await tester.tap(find.text('看分析'));
      await tester.pumpAndSettle();
      expect(requests, ['global:', 'symbol:600206']);
      expect(find.text('600206 这只票'), findsOneWidget);
      expect(find.text('3 次（其中被套 1 次）'), findsOneWidget);
      expect(find.text('R53 · 命中 2 笔'), findsOneWidget);
      expect(find.textContaining('哪几笔：600206_2026-08-05、600206_2026-09-01'), findsOneWidget);
    });

    testWidgets('单笔：一笔的过程（RoundBrief + 分桶）+ 错编号 → 后端人话直显', (tester) async {
      final requests = <String>[];
      final client = MockClient((request) async {
        final base = await _baseRoute(request);
        if (base != null) return base;
        final path = request.url.path;
        if (path == '/api/v1/trading/analysis/global') {
          requests.add('global:');
          return _json(_globalStub);
        }
        if (path == '/api/v1/trading/analysis/round') {
          final id = request.url.queryParameters['id'] ?? '';
          requests.add('round:$id');
          if (id == '600206_2026-08-05') {
            return _json({
              'scope': 'round', 'label': '600206 这一笔',
              'description': [
                {
                  'key': 'process', 'label': '这笔的过程', 'value': [
                    {
                      'id': '600206_2026-08-05', 'start': '2026-08-05', 'end': '2026-08-22',
                      'pnl': 1234.5, 'pnlPct': 12.5, 'holdDays': 17, 'unresolved': false, 'reason': '',
                    },
                  ],
                  'unit': null,
                  'trace': {'roundIds': ['600206_2026-08-05'], 'dates': ['2026-08-05', '2026-08-22'], 'note': null},
                },
                {
                  'key': 'buckets', 'label': '分桶看', 'value': [
                    {'label': '盈利', 'count': 1, 'avgPnlPct': 12.5, 'roundIds': ['600206_2026-08-05']},
                    {'period': '2026-08', 'count': 1, 'pnl': 1234.5, 'roundIds': ['600206_2026-08-05']},
                  ],
                  'unit': null,
                  'trace': {'roundIds': [], 'dates': [], 'note': null},
                },
              ],
              'contrast': {'hasRules': false, 'reason': '我还没有你的规则，判不了守没守', 'ruleHits': []},
              'summary': {'fact': '这一笔持有 17 天。', 'contrast': null, 'question': null},
            });
          }
          return http.Response(
            jsonEncode({'error': '找不到这一笔：$id（在「历史成交」里核对编号）'}),
            400,
            headers: {'content-type': 'application/json; charset=utf-8'},
          );
        }
        return http.Response('not found', 404);
      });
      final api = ApiService(baseUrl: 'http://test', client: client);
      await _pumpTrading(tester, api);

      await tester.tap(find.text('分析'));
      await tester.pumpAndSettle();

      // 单笔：一笔的过程
      await tester.tap(find.text('单笔'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('analysisRound')), '600206_2026-08-05');
      await tester.tap(find.text('看分析'));
      await tester.pumpAndSettle();
      expect(requests.last, 'round:600206_2026-08-05');
      expect(find.text('600206 这一笔'), findsOneWidget);
      expect(find.textContaining('600206 2026-08-05 至 2026-08-22 · 12.5% · ¥1,234.50 · 持 17 天'), findsOneWidget);
      expect(find.textContaining('盈利：1 笔 · 均 12.5%'), findsOneWidget);
      expect(find.textContaining('2026-08：1 笔 · ¥1,234.50'), findsOneWidget);

      // 错编号 → 后端 400 人话直显
      await tester.enterText(find.byKey(const Key('analysisRound')), '600206_2026-09-09');
      await tester.tap(find.text('看分析'));
      await tester.pumpAndSettle();
      expect(requests.last, 'round:600206_2026-09-09');
      expect(find.textContaining('找不到这一笔'), findsOneWidget);
    });
  });
}
