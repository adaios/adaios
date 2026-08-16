/// 批量导入解析器（web 独有详细管理，RFC 20260816 §4.2）。
///
/// 支持多行粘贴或 CSV，每行格式：
///   symbol,name,direction,price,volume,stopLoss,buyPoint[,reason]
/// 例：600519,贵州茅台,BUY,1500,100,1350,B1,季报前埋伏
///
/// 规则：
/// - 逗号分隔（兼容中文逗号），空行跳过；
/// - 首行若是表头（第一格含 代码/symbol/标的/名称 等）自动跳过；
/// - direction 兼容 BUY/SELL/买入/卖出/买/卖；
/// - 价格/数量必须 > 0；BUY 必填止损位（> 0）与买点类型；
/// - 解析错误逐行收集人话原因（带行号），不整批失败。
library;

/// 解析成功的一行导入交易。
class ImportTradeRow {
  final String symbol;
  final String name;
  final String direction; // BUY / SELL
  final double price;
  final int volume;
  final double? stopLossPrice;
  final String? buyPoint;
  final String? reason;

  ImportTradeRow({
    required this.symbol,
    required this.name,
    required this.direction,
    required this.price,
    required this.volume,
    this.stopLossPrice,
    this.buyPoint,
    this.reason,
  });

  /// 转 POST /trading/trades/batch 请求项（与 recordTrade 字段名一致）。
  Map<String, dynamic> toJson() => {
        'symbol': symbol,
        'name': name,
        'direction': direction,
        'price': price,
        'volume': volume,
        'stopLossPrice': ?stopLossPrice,
        'buyPoint': ?buyPoint,
        'reason': ?reason,
      };
}

/// 解析结果：成功行 + 失败原因列表（人话，含行号）。
class ImportParseResult {
  final List<ImportTradeRow> rows;
  final List<String> errors;

  ImportParseResult({required this.rows, required this.errors});

  bool get hasErrors => errors.isNotEmpty;
}

/// 买点类型白名单（RFC 20260816 §2.1，与记录交易 Dialog 下拉一致）。
const List<String> kBuyPointOptions = [
  'B1', 'B2', 'B3', 'SB1', '暴力特噗', '深水炸弹', '单针', '其他',
];

/// 方向归一化：买/BUY/买入 → BUY；卖/SELL/卖出 → SELL；其余 null。
String? _normalizeDirection(String raw) {
  final v = raw.trim().toUpperCase();
  if (v == 'BUY' || v == '买' || v == '买入') return 'BUY';
  if (v == 'SELL' || v == '卖' || v == '卖出') return 'SELL';
  return null;
}

bool _isHeaderLine(List<String> cells) {
  if (cells.isEmpty) return false;
  final first = cells.first.trim().toLowerCase();
  return first.contains('代码') ||
      first == 'symbol' ||
      first.contains('标的') ||
      first.contains('名称') ||
      first.contains('方向') ||
      first == 'name';
}

/// 解析批量导入文本 → 成功行 + 失败原因。
ImportParseResult parseImportTrades(String text) {
  final rows = <ImportTradeRow>[];
  final errors = <String>[];
  final lines = text.split(RegExp(r'[\r\n]+'));
  for (var i = 0; i < lines.length; i++) {
    final raw = lines[i].trim();
    if (raw.isEmpty) continue;
    // 中文逗号兼容：统一转半角
    final cells = raw
        .replaceAll('，', ',')
        .split(',')
        .map((c) => c.trim())
        .toList();
    if (_isHeaderLine(cells)) continue;

    final lineNo = i + 1;
    if (cells.length < 6) {
      errors.add('第 $lineNo 行：字段不足，需要 代码,名称,方向,价格,数量,止损[,买点,原因]');
      continue;
    }
    final symbol = cells[0].toUpperCase();
    if (symbol.isEmpty) {
      errors.add('第 $lineNo 行：代码为空');
      continue;
    }
    final direction = _normalizeDirection(cells[2]);
    if (direction == null) {
      errors.add('第 $lineNo 行：方向「${cells[2]}」无法识别，请用 买/卖 或 BUY/SELL');
      continue;
    }
    final price = double.tryParse(cells[3]);
    final volume = int.tryParse(cells[4]);
    if (price == null || price <= 0) {
      errors.add('第 $lineNo 行：价格「${cells[3]}」不是有效正数');
      continue;
    }
    if (volume == null || volume <= 0) {
      errors.add('第 $lineNo 行：数量「${cells[4]}」不是有效正整数');
      continue;
    }
    double? stopLoss;
    if (cells[5].isNotEmpty) {
      stopLoss = double.tryParse(cells[5]);
      if (stopLoss == null || stopLoss <= 0) {
        errors.add('第 $lineNo 行：止损位「${cells[5]}」不是有效正数');
        continue;
      }
    }
    String? buyPoint;
    if (cells.length > 6 && cells[6].isNotEmpty) buyPoint = cells[6].trim();
    if (direction == 'BUY' && stopLoss == null) {
      errors.add('第 $lineNo 行：买入必须填止损位（第 6 列），这是盯风险的下限');
      continue;
    }
    if (direction == 'BUY' && (buyPoint == null || buyPoint.isEmpty)) {
      errors.add('第 $lineNo 行：买入必须填买点类型（第 7 列，如 B1）');
      continue;
    }
    if (buyPoint != null && !kBuyPointOptions.contains(buyPoint)) {
      errors.add('第 $lineNo 行：买点「$buyPoint」不在可选范围（${kBuyPointOptions.join('/')}）');
      continue;
    }
    final reason = cells.length > 7 ? cells[7].trim() : '';
    rows.add(ImportTradeRow(
      symbol: symbol,
      name: cells.length > 1 ? cells[1] : '',
      direction: direction,
      price: price,
      volume: volume,
      stopLossPrice: stopLoss,
      buyPoint: buyPoint,
      reason: reason.isEmpty ? null : reason,
    ));
  }
  return ImportParseResult(rows: rows, errors: errors);
}
