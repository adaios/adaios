/// 交易类推送深链的解析（2026-10-09 · design-app §三 I-7「提醒落点」）。
///
/// 后端行情告警带的深链形如 `trading:600206`（REVIEW P2-APNs1 定的口径）；
/// 壳层拿到点击后要判断「这条要不要**落到那只票**」——不是所有推送都有落点
/// （收盘小结/计划提醒就只回 Feed），所以单独抽一个纯函数、好单测。
///
/// 返回 null = 不是「某只票」的深链（保持原有「回 Feed 定位那条」的行为）。
///
/// **⚠️ 只有 6 位股票代码才算落点**（2026-10-09 前端官 P1-1）：
/// 后端 `PushChannel.deepLink()` 的**默认分支是哨兵 `trading:today`**——早盘/午间/尾盘/收盘小结/计划提醒
/// 等**不带标的名**的推送全走它。早前实现把「任意非空尾巴」当代码，于是这些每天都在发的时段推送
/// 全部被当成「落到某只票」，把既有的「回 Feed 高亮那条」静默改道（而且哨兵永远匹配不到持仓＝落点等于没落）。
/// 现在按**形态**判定（A 股代码就是 6 位数字），对未来的深链格式漂移也免疫。
String? parseTradingDeepLink(String? deepLink) {
  if (deepLink == null) return null;
  final s = deepLink.trim();
  if (s.isEmpty) return null;
  final i = s.indexOf(':');
  if (i <= 0) return null;
  if (s.substring(0, i) != 'trading') return null;
  final symbol = s.substring(i + 1).trim();
  // 哨兵（today / review 等）与任何非 6 位代码一律不是落点
  return RegExp(r'^\d{6}$').hasMatch(symbol) ? symbol : null;
}
