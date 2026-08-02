/// 通用格式化工具。
library;

String _two(int n) => n.toString().padLeft(2, '0');

/// 日期 → `2026-08-01`。
String formatDate(DateTime dt) => '${dt.year}-${_two(dt.month)}-${_two(dt.day)}';

/// 日期时间 → `2026-08-01 21:30`。
String formatDateTime(DateTime dt) =>
    '${formatDate(dt)} ${_two(dt.hour)}:${_two(dt.minute)}';

/// 价格 → 保留两位小数。
String formatPrice(double v) => v.toStringAsFixed(2);

/// 涨跌幅 → `+1.01%` / `-0.35%`。
String formatPercent(double v) =>
    '${v >= 0 ? '+' : ''}${v.toStringAsFixed(2)}%';
