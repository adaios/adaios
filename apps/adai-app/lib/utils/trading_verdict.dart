/// 交易首页「判断句」与「与你的线的关系」——纯逻辑（design-app §三 I-1 / I-2，RFC 20260924 §4.1）。
///
/// 为什么要独立成文件：这句判断是 app 端形态的第一步（**先说结论**），也是全页最容易「编话」的
/// 一处。放进纯函数有两个好处——① 每个分支都能被单测钉死；② 强制每一句都来自**真实取到的数据**：
/// **取不到就不说**（与 web 端「行情正常」和「拿不到信息」都零显示同一口径，见坑「缺数据不编」）。
///
/// 纪律（不许违反）：
/// - 常态给结论，**有事换句**；小字只承载异常（账 / 行情 / 待入账 / 破线）；
/// - 取不到（null）→ **不显示、也不编成 0**，更不写成「正常」；
/// - 本函数**不产生任何 LLM 调用**：输入全是确定性数据（持仓 / 硬判定 / 待入账数 / 账实与行情状态）。
library;

/// 判断句的语气——仅供 UI 上色用（attention 用警示色）。
enum TradingVerdictTone { ok, attention, unknown }

class TradingVerdict {
  /// 主句（首屏最大字号那一行）
  final String headline;

  /// 小字（**仅异常时出现**；null = 不渲染这一行）
  final String? subline;

  final TradingVerdictTone tone;

  const TradingVerdict({required this.headline, this.subline, required this.tone});
}

/// 判断句的输入——全部来自页面已拉到的确定性数据。
class TradingVerdictInput {
  /// 持仓只数；**null = 这次没取到**（不编成 0）
  final int? positionCount;

  /// 破了止损的只数（反向提醒）
  final int brokeStopCount;

  /// 到放飞线的只数（正向提醒）；无目标线数据时为 0
  final int reachedTargetCount;

  /// 破线标的的**名称**（进小字点名）；空 = 不显示
  final List<String> brokeStopNames;

  /// 对账是否有问题；**null = 没取到**（不显示、也不说「没问题」）
  final bool? integrityIssue;

  /// 后端拟好的人话（差额 / 哪几只）
  final String? integrityNote;

  /// 行情链路是否有问题；null = 没取到
  final bool? marketIssue;

  /// 待入账笔数（截图识别出的候选）
  final int pendingCandidates;

  const TradingVerdictInput({
    this.positionCount,
    this.brokeStopCount = 0,
    this.reachedTargetCount = 0,
    this.brokeStopNames = const [],
    this.integrityIssue,
    this.integrityNote,
    this.marketIssue,
    this.pendingCandidates = 0,
  });
}

/// 汇成一句：先说结论（形态的第一步）。
TradingVerdict composeTradingVerdict(TradingVerdictInput input) {
  final sub = <String>[];
  if (input.integrityIssue == true) {
    final note = (input.integrityNote ?? '').trim();
    sub.add(note.isEmpty ? '账对不上，差多少我还没算出来' : '账对不上：$note');
  }
  if (input.marketIssue == true) sub.add('行情没取到，数字可能不是最新');
  if (input.pendingCandidates > 0) sub.add('${input.pendingCandidates} 笔等着你确认');
  if (input.brokeStopNames.isNotEmpty) sub.add('破了你的线：${input.brokeStopNames.join('、')}');
  final subline = sub.isEmpty ? null : sub.join(' · ');

  final n = input.positionCount;
  if (n == null) {
    return TradingVerdict(
      headline: '这次没取到你的持仓',
      subline: subline,
      tone: TradingVerdictTone.unknown,
    );
  }
  if (n == 0) {
    return TradingVerdict(headline: '还没有持仓', subline: subline, tone: TradingVerdictTone.ok);
  }

  final parts = <String>['持仓 $n 只'];
  if (input.brokeStopCount > 0) {
    parts.add('${input.brokeStopCount} 只破了你的线');
  } else if (input.reachedTargetCount > 0) {
    parts.add('${input.reachedTargetCount} 只到了你的线');
  } else {
    parts.add('不用动');
  }
  if (input.integrityIssue == true) parts.add('账对不上');

  final attention = input.brokeStopCount > 0 ||
      input.reachedTargetCount > 0 ||
      input.integrityIssue == true ||
      input.marketIssue == true ||
      input.pendingCandidates > 0;

  return TradingVerdict(
    headline: parts.join(' · '),
    subline: subline,
    tone: attention ? TradingVerdictTone.attention : TradingVerdictTone.ok,
  );
}

/// 「与你的线的关系」的状态
enum HoldLineState { brokeStop, aboveStop, noLine, unknown }

class HoldLineReading {
  final HoldLineState state;

  /// 行内那半句（design-app I-2）
  final String text;

  const HoldLineReading(this.state, this.text);
}

/// 持仓行里「与你的线」的半句：破了你的 X / 离你的 X 还有 Y% / 没设线 / 没取到现价。
///
/// 只有**有止损线且现价取到**时才给距离——缺一样就如实说，不给假距离。
HoldLineReading readHoldLine({double? currentPrice, double? stopLossPrice}) {
  final c = currentPrice;
  final s = stopLossPrice;
  if (s == null || s <= 0) return const HoldLineReading(HoldLineState.noLine, '没设线');
  if (c == null || c <= 0) return const HoldLineReading(HoldLineState.unknown, '没取到现价');
  if (c <= s) return HoldLineReading(HoldLineState.brokeStop, '破了你的 ${_price(s)}');
  final pct = (c - s) / s * 100;
  return HoldLineReading(
    HoldLineState.aboveStop,
    '离你的 ${_price(s)} 还有 ${pct.toStringAsFixed(1)}%',
  );
}

String _price(double v) => v.toStringAsFixed(2);
