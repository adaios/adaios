import 'package:flutter_test/flutter_test.dart';

import 'package:adai_app/services/api_service.dart';

/// 账实一致性「降级流水」解析（2026-09-21，P1-交易61）。
///
/// 背景：锚定日**当天**的成交会被判成「已含在券商快照内」→ 只落流水、不进持仓；
/// 而派生持仓与落地持仓同源于那份快照，结构上必然相等 → 老实现对账只会报「账实一致」（假绿）。
/// 后端新增 `degraded`（每行带 `inferred`）：锚定日明确时是事实说明（不出横幅），
/// 锚定日**被推断**时才是需要用户核对的警告。
void main() {
  group('IntegrityReportDto 降级流水（P1-交易61）', () {
    test('解析 degraded：inferred 才计入 hasIssue（锚定日被推断 = 需要核对）', () {
      final r = IntegrityReportDto.fromJson({
        'holdingsKnown': true,
        'drift': [],
        'gaps': [],
        'degraded': [
          {
            'symbol': '600206',
            'name': '有研新材',
            'direction': 'BUY',
            'volume': 100,
            'price': 46.85,
            'entryDate': '2026-09-17',
            'inferred': true,
            'reason': '成交日 = 锚定日，而锚定日是由文件日期推断的',
          },
        ],
        'note': '账实一致；⚠️ 另有 1 笔成交只记了流水、没进持仓',
      });

      expect(r.degraded.length, 1);
      expect(r.degraded.first.symbol, '600206');
      expect(r.degraded.first.name, '有研新材');
      expect(r.degraded.first.volume, 100);
      expect(r.degraded.first.price, 46.85);
      expect(r.degraded.first.entryDate, '2026-09-17');
      expect(r.degraded.first.inferred, isTrue);
      expect(r.hasIssue, isTrue, reason: '锚定日被推断 + 有降级成交 → 必须让用户看见');
    });

    test('降级流水非推断 → 只是事实说明，不出横幅（不制造噪音）', () {
      final r = IntegrityReportDto.fromJson({
        'holdingsKnown': true,
        'drift': [],
        'gaps': [],
        'degraded': [
          {
            'symbol': '600206',
            'name': '有研新材',
            'direction': 'SELL',
            'volume': 200,
            'inferred': false,
            'reason': '成交日 = 锚定日，已含在券商快照内',
          },
        ],
        'note': '账实一致',
      });

      expect(r.degraded.single.inferred, isFalse);
      expect(r.degraded.single.direction, 'SELL');
      expect(r.hasIssue, isFalse, reason: '锚定日明确 → 这些成交确实在快照里，不该天天报警');
    });

    test('旧后端不返回 degraded → 空列表、不崩，hasIssue 仍只看 drift/gaps', () {
      final r = IntegrityReportDto.fromJson(
          {'holdingsKnown': true, 'drift': [], 'gaps': [], 'note': ''});
      expect(r.degraded, isEmpty);
      expect(r.hasIssue, isFalse);
    });

    test('degraded 行字段缺失 → 防御式解析不抛异常', () {
      final r = IntegrityReportDto.fromJson({
        'holdingsKnown': true,
        'drift': [],
        'gaps': [],
        'degraded': [
          {'symbol': '600206'},
        ],
        'note': '',
      });
      expect(r.degraded.single.symbol, '600206');
      expect(r.degraded.single.volume, 0);
      expect(r.degraded.single.inferred, isFalse);
      expect(r.degraded.single.price, isNull);
    });
  });
}
