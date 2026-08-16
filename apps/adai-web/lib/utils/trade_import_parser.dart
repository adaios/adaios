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

// ─────────────────────────── 通达信持仓导入 ───────────────────────────

/// 通达信持仓快照行（导出「资金股票」→ 持仓快照，无方向/无止损——止损/买点需补设）。
class TdxPositionRow {
  final String symbol;
  final String name;
  final int quantity;
  final double avgCost;

  TdxPositionRow({
    required this.symbol,
    required this.name,
    required this.quantity,
    required this.avgCost,
  });

  /// 转 POST /trading/positions/import 请求项。
  Map<String, dynamic> toJson() => {
        'symbol': symbol,
        'name': name,
        'quantity': quantity,
        'avgCost': avgCost,
      };
}

/// 通达信导出是否可识别（表头含「证券代码/代码」「股票余额/数量」等特征）。
bool isTdxExport(String text) {
  final first = text.split(RegExp(r'[\r\n]+')).firstWhere(
        (l) => l.trim().isNotEmpty,
        orElse: () => '',
      );
  final low = first.toLowerCase();
  // 通达信表头特征：含「代码/证券代码」且含「成本价」（交易 CSV 无成本价列）
  return (low.contains('证券代码') || low.contains('代码')) && low.contains('成本价');
}

/// 通达信解析结果：持仓快照行 + 错误列表。
class TdxParseResult {
  final List<TdxPositionRow> rows;
  final List<String> errors;

  TdxParseResult({required this.rows, required this.errors});

  bool get hasErrors => errors.isNotEmpty;
}

/// 解析通达信持仓导出 → 持仓快照行 + 错误列表。
///
/// 表头定位列（版本差异容忍）：证券代码/代码 → symbol；证券名称/名称 → name；
/// 股票余额/持仓数量/数量 → quantity；成本价/成本 → avgCost。
/// 分隔：制表符或连续空格（通达信导出通常制表符）。
TdxParseResult parseTdxPositions(String text) {
  final rows = <TdxPositionRow>[];
  final errors = <String>[];
  final lines = text.split(RegExp(r'[\r\n]+'));
  List<int>? col;

  for (var i = 0; i < lines.length; i++) {
    final raw = lines[i].trim();
    if (raw.isEmpty || raw.startsWith('#')) continue; // # 注释行（通达信「#数据来源」）
    final cells = raw.split(RegExp(r'[\t\s]+'));
    if (col == null) {
      // 表头行：定位列索引
      final idx = <String, int>{};
      for (var c = 0; c < cells.length; c++) {
        final h = cells[c].toLowerCase();
        if (h.contains('证券代码') || h == '代码') idx['symbol'] = c;
        if (h.contains('证券名称') || h == '名称') idx['name'] = c;
        if (h.contains('股票余额') || h.contains('证券数量') || h.contains('持仓') || h == '数量' || h.contains('余额')) {
          idx['quantity'] ??= c;
        }
        if (h.contains('成本价') || h == '成本') idx['cost'] = c;
      }
      if (idx.containsKey('symbol') && idx.containsKey('quantity') && idx.containsKey('cost')) {
        col = [idx['symbol']!, idx['name'] ?? -1, idx['quantity']!, idx['cost']!];
        continue; // 表头本身跳过
      }
      // 首行无表头特征 → 按固定顺序尝试：代码 名称 数量 成本
      errors.add('无法识别通达信表头（需要 证券代码/股票余额/成本价 列），请确认是持仓导出');
      break;
    }
    if (cells.length <= col[2]) {
      errors.add('第 ${i + 1} 行：字段不足');
      continue;
    }
    final symbol = cells[col[0]].toUpperCase();
    if (symbol.isEmpty || !RegExp(r'^\d{6}$').hasMatch(symbol)) {
      errors.add('第 ${i + 1} 行：代码「${cells[col[0]]}」不是六位数字');
      continue;
    }
    final name = col[1] >= 0 && col[1] < cells.length ? cells[col[1]] : '';
    final quantity = int.tryParse(cells[col[2]].replaceAll(',', ''));
    if (quantity == null || quantity <= 0) {
      errors.add('第 ${i + 1} 行：数量「${cells[col[2]]}」不是有效正整数');
      continue;
    }
    final cost = double.tryParse(cells[col[3]].replaceAll(',', ''));
    if (cost == null || cost <= 0) {
      errors.add('第 ${i + 1} 行：成本价「${cells[col[3]]}」不是有效正数');
      continue;
    }
    rows.add(TdxPositionRow(symbol: symbol, name: name, quantity: quantity, avgCost: cost));
  }
  return TdxParseResult(rows: rows, errors: errors);
}
