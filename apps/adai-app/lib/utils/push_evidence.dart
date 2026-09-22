/// RFC 20260922 C 批 C3：把决策推送正文拆成「结论」与「依据」两段。
///
/// B 批（后端）把早盘/尾盘的推送正文渲染成固定形态——**一句话结论 + 逐票四要素**：
/// ```text
/// 📉 尾盘卖点
/// 按你 09-19 的账（持仓 2 只，1 只要看一眼）：
/// · 贵州茅台（600519） 现价 1370（今日 -2.1%） → 清仓参考（R66）
///   ① 你的历史：你过去 8 次在「+5~10%」了结，7 次盈利、平均 +7.1%
///   ② 证据：成本 1400 · 占比 96.5% · 止损 1380 · 持有 100 股
///   ③ 规则：《止损位跌破即离场》R66 原文「现价跌破你设的止损位 → 按纪律离场，不扛单。」
///   ④ 位置：这笔若现在了结，约 +6.2%（未扣手续费） · 距你的止损位 1380 还有 +7.74%
/// ```
///
/// 手机上全文铺开太长，而四要素是「**要核对时才看**」的细节（用户原话「尤其给我铁证，
/// 通过我之前的操作」）——所以卡片默认只露结论，依据按需展开（RFC §六 C3）。
///
/// **解析不出来就原样返回**：旧形态推送（行情异动/操作确认/学习复习等没有四要素的）
/// 拿到的 `evidence` 为空，调用方全文照显，行为与改动前完全一致。
library;

/// 依据行的判据：去掉左侧缩进后以 ①②③④ 开头（B 批渲染器固定用两个空格 + 圈号缩进）。
const List<String> _evidenceMarks = ['①', '②', '③', '④'];

/// 拆分结果。
class PushContentParts {
  /// 结论（标题 / 账日期 / 逐票结论行 / 收尾句）——默认展示这一段。
  final String head;

  /// 依据行（已去掉缩进、按原顺序）；空 = 这条推送没有四要素（旧形态）。
  final List<String> evidence;

  const PushContentParts(this.head, this.evidence);

  bool get hasEvidence => evidence.isNotEmpty;

  /// 有几只标的带了依据（按 ① 的条数数）。
  int get targetCount => evidence.where((l) => l.startsWith('①')).length;
}

/// 拆分推送正文；**无法拆分时返回 (content, [])**，调用方渲染全文即可。
PushContentParts splitPushContent(String content) {
  if (content.isEmpty) return const PushContentParts('', []);

  final head = <String>[];
  final evidence = <String>[];
  for (final line in content.split('\n')) {
    final trimmed = line.trimLeft();
    if (trimmed.isNotEmpty && _evidenceMarks.any(trimmed.startsWith)) {
      evidence.add(trimmed);
    } else {
      head.add(line);
    }
  }
  final headText = head.join('\n').trimRight();
  // 只有依据、没有结论（不该出现，但别把卡片渲染成空白）→ 原样返回
  if (evidence.isNotEmpty && headText.trim().isEmpty) {
    return PushContentParts(content, const []);
  }
  return PushContentParts(headText, evidence);
}
