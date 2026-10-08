/// 交易类推送深链的解析（2026-10-09 · design-app §三 I-7「提醒落点」）。
///
/// 后端行情告警带的深链形如 `trading:600206`（REVIEW P2-APNs1 定的口径）；
/// 壳层拿到点击后要判断「这条要不要**落到那只票**」——不是所有推送都有落点
/// （收盘小结/计划提醒就只回 Feed），所以单独抽一个纯函数、好单测。
///
/// 返回 null = 不是交易类深链（保持原有「回 Feed 定位那条」的行为）。
String? parseTradingDeepLink(String? deepLink) {
  if (deepLink == null) return null;
  final s = deepLink.trim();
  if (s.isEmpty) return null;
  final i = s.indexOf(':');
  if (i <= 0) return null;
  if (s.substring(0, i) != 'trading') return null;
  final symbol = s.substring(i + 1).trim();
  return symbol.isEmpty ? null : symbol;
}
