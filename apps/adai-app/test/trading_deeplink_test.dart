import 'package:flutter_test/flutter_test.dart';
import 'package:adai_app/utils/trading_deeplink.dart';

// 2026-10-09（批 5 · design-app §三 I-7 提醒落点）：深链解析纯逻辑。
// 口径：只有 `trading:<代码>` 才落到某只票；其余（含空、畸形）一律回 Feed 定位那条。

void main() {
  group('parseTradingDeepLink', () {
    test('标准格式：trading:600206 → 600206', () {
      expect(parseTradingDeepLink('trading:600206'), '600206');
    });

    test('容忍空格与前后空白', () {
      expect(parseTradingDeepLink('  trading: 600519 '), '600519');
    });

    test('非交易类深链 → null（保持回 Feed 的行为）', () {
      expect(parseTradingDeepLink('learn:card-1'), isNull);
      expect(parseTradingDeepLink('profile'), isNull);
    });

    test('空 / null / 畸形一律 null（不猜）', () {
      expect(parseTradingDeepLink(null), isNull);
      expect(parseTradingDeepLink(''), isNull);
      expect(parseTradingDeepLink('   '), isNull);
      expect(parseTradingDeepLink('trading:'), isNull);
      expect(parseTradingDeepLink(':600206'), isNull);
    });
  });
}
