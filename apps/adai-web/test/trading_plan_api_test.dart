import 'dart:convert';

import 'package:adai_web/services/api_service.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

/// RFC 20261003-trading-plan-and-review-loop（2026-10-03）：次日操作计划的 API 契约。
///
/// 关键语义：**没写计划 → 后端 404 → 客户端返回 null**（不编造空壳假计划）；
/// 写入走 `POST /plans/{date}`（body = lines + note）；对账返回「触发/执行/计划外」三类事实。
void main() {
  test('getPlan：404 → null（没写就是没写，不返回空壳假计划）', () async {
    final api = ApiService(
      baseUrl: 'http://test',
      client: MockClient((req) async {
        expect(req.url.path, '/api/v1/trading/plans/2026-10-08');
        return http.Response(jsonEncode({'error': '这天还没有写操作计划'}), 404,
            headers: {'content-type': 'application/json; charset=utf-8'});
      }),
    );

    expect(await api.getPlan('2026-10-08'), isNull);
  });

  test('getPlan：200 → 解析 items（text 是用户原话，提醒时必须引用）', () async {
    final api = ApiService(
      baseUrl: 'http://test',
      client: MockClient((req) async => http.Response(
            jsonEncode({
              'date': '2026-10-08',
              'note': '只做计划内的票',
              'items': [
                {
                  'id': 'p1',
                  'action': 'SELL',
                  'symbol': '600519',
                  'name': '贵州茅台',
                  'condition': '跌破 1400',
                  'condOp': 'LT',
                  'condPrice': 1400,
                  'quantity': null,
                  'text': '600519 跌破 1400 清仓',
                  'done': false,
                }
              ],
            }),
            200,
            headers: {'content-type': 'application/json; charset=utf-8'},
          )),
    );

    final v = await api.getPlan('2026-10-08');

    expect(v!['note'], '只做计划内的票');
    expect((v['items'] as List).first['text'], '600519 跌破 1400 清仓');
    expect((v['items'] as List).first['condOp'], 'LT');
  });

  test('savePlan：POST 到 /plans/{date}，body 带 lines 与 note', () async {
    http.Request? seen;
    final api = ApiService(
      baseUrl: 'http://test',
      client: MockClient((req) async {
        seen = req;
        return http.Response(jsonEncode({'date': '2026-10-08', 'items': []}), 200);
      }),
    );

    await api.savePlan('2026-10-08', ['明天不动'], '不加仓');

    expect(seen!.method, 'POST');
    expect(seen!.url.path, '/api/v1/trading/plans/2026-10-08');
    final body = jsonDecode(seen!.body) as Map<String, dynamic>;
    expect(body['lines'], ['明天不动']);
    expect(body['note'], '不加仓');
  });

  test('reviewPlan：解析触发/执行计数与 ⚠️ 计划外成交', () async {
    final api = ApiService(
      baseUrl: 'http://test',
      client: MockClient((req) async => http.Response(
            jsonEncode({
              'date': '2026-10-08',
              'hasPlan': true,
              'items': [],
              'unplanned': ['000831 中国稀土 BUY 200股'],
              'triggeredCount': 1,
              'executedCount': 1,
            }),
            200,
            headers: {'content-type': 'application/json; charset=utf-8'},
          )),
    );

    final r = await api.reviewPlan('2026-10-08');

    expect(r['triggeredCount'], 1);
    expect(r['executedCount'], 1);
    expect((r['unplanned'] as List).first, contains('000831'));
  });

  test('getRounds：带 symbol 与 limit 查询参数', () async {
    http.Request? seen;
    final api = ApiService(
      baseUrl: 'http://test',
      client: MockClient((req) async {
        seen = req;
        return http.Response(jsonEncode({'total': 0, 'rounds': []}), 200);
      }),
    );

    await api.getRounds(symbol: '600519', limit: 20);

    expect(seen!.url.path, '/api/v1/trading/rounds');
    expect(seen!.url.queryParameters['symbol'], '600519');
    expect(seen!.url.queryParameters['limit'], '20');
  });

  test('reconcileCash：POST 带 dryRun=true（只对账、不落盘）', () async {
    http.Request? seen;
    final api = ApiService(
      baseUrl: 'http://test',
      client: MockClient((req) async {
        seen = req;
        return http.Response(
            jsonEncode({
              'dryRun': true,
              'brokerCash': 900,
              'systemCash': 500,
              'diff': 400,
              'ledgerOnlyCount': 1,
              'ledgerOnlyAmount': 8000,
              'since': [
                {'kind': '买入', 'count': 1, 'amount': -1000}
              ],
              'note': '差 400，我解释不了',
            }),
            200,
            headers: {'content-type': 'application/json; charset=utf-8'});
      }),
    );

    final rec = await api.reconcileCash('人民币: 余额:900.00');

    expect(seen!.method, 'POST');
    expect(seen!.url.path, '/api/v1/trading/imports/cash');
    expect((jsonDecode(seen!.body) as Map)['dryRun'], 'true', reason: '必须显式 dryRun 才是只读对账');
    expect(rec['diff'], 400);
    expect(rec['ledgerOnlyCount'], 1);
  });

  // ── P2-交易72（2026-10-05）：当天事后的状态回填 ──
  // 「今天没动 / 想动，没动」落的是**同一天自己的记录**（plans/{date} 的 dayStatus），不是另立一处。
  test('setPlanDayStatus：POST 到 /plans/{date}/status，body 带 status；如实解析 recorded', () async {
    http.Request? seen;
    final api = ApiService(
      baseUrl: 'http://test',
      client: MockClient((req) async {
        seen = req;
        return http.Response(
            jsonEncode({
              'date': '2026-10-12',
              'note': '',
              'dayStatus': 'NO_TRADE',
              // 幂等命中（早就记着了）→ false；调用方据此说「已经记着了」，不许假报落库。
              'recorded': false,
              'items': [],
            }),
            200,
            headers: {'content-type': 'application/json; charset=utf-8'});
      }),
    );

    final r = await api.setPlanDayStatus('2026-10-12', ApiService.dayStatusNoTrade);

    expect(seen!.method, 'POST');
    expect(seen!.url.path, '/api/v1/trading/plans/2026-10-12/status',
        reason: '落点是这一天的记录本身（与计划同址），不是另一个存储');
    expect((jsonDecode(seen!.body) as Map)['status'], 'NO_TRADE');
    expect(r['dayStatus'], 'NO_TRADE');
    expect(r['recorded'], false);
    expect(ApiService.dayStatusWantedNotActed, 'WANTED_NOT_ACTED',
        reason: '「想动，没动」与「今天没动」是两个值（与后端 TradingPlan 逐字一致）');
  });
}
