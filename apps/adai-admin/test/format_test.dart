// 格式化工具测试（2026-09-06 审查 P1-D 回归）：
// V9-10——0 值不得带正负号（±0.00 判平不判涨/跌），只正数带 +。

import 'package:flutter_test/flutter_test.dart';

import 'package:adai_admin/utils/format.dart';

void main() {
  test('formatPercent：正数带 +、负数带 -、0 不带符号（P1-D）', () {
    expect(formatPercent(1.234), '+1.23%');
    expect(formatPercent(-0.35), '-0.35%');
    expect(formatPercent(0), '0.00%');
    expect(formatPercent(0.0), '0.00%');
    expect(formatPercent(-0.001), '-0.00%'); // 负向趋零舍入仍为负（不在此 bug 范围）
  });

  test('formatDate：yyyy-MM-dd 无空格/毫秒（P1-B 相关）', () {
    expect(formatDate(DateTime(2026, 7, 31)), '2026-07-31');
    expect(formatDate(DateTime(2026, 9, 4)), '2026-09-04');
  });
}
