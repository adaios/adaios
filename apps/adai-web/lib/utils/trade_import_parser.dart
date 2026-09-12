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

/// 从通达信导出**文件名**解析快照自身日期（2026-09-12 账实一致性批）。
///
/// 后端拿它当**锚定日**（positions/import 的 `snapshotDate` query、imports/cash 的 body 字段），
/// **优先于导入日**：补导几天前的快照文件时若把锚定日写成今天，锚定日之后、快照之前的成交会被
/// 误判成「已含在快照口径内」而丢掉增量（RFC 20260912-trading-ledger-integrity）。
///
/// 支持 `20260912`（8 位连写）、`2026-09-12`、`2026_09_12`，可带前后缀
/// （如 `持仓股20260912.txt`、`资金股份查询-2026-09-12.csv`）。
/// 取不到（无日期 / 非法日期如 20261345）→ null，调用方不传该字段（后端退回导入日）。
String? parseSnapshotDateFromFilename(String filename) {
  if (filename.isEmpty) return null;
  final m = RegExp(r'(20\d{2})[-_/]?(0\d|1[0-2])[-_/]?(0[1-9]|[12]\d|3[01])').firstMatch(filename);
  if (m == null) return null;
  final y = int.parse(m.group(1)!);
  final mo = int.parse(m.group(2)!);
  final d = int.parse(m.group(3)!);
  // 逐字段回验（20260230 这类假日期必须判掉，不能把锚定日写歪）
  final dt = DateTime(y, mo, d);
  if (dt.year != y || dt.month != mo || dt.day != d) return null;
  return '${m.group(1)}-${m.group(2)}-${m.group(3)}';
}

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
  // 通达信表头特征：含「代码/证券代码」且含「成本价/成本」（P3 2026-08-17：表头为「成本」的真实导出
  // 此前会被误路由进交易 CSV 解析——与 parseTdxPositions 的「成本价|成本」列识别统一）
  return (low.contains('证券代码') || low.contains('代码')) && (low.contains('成本价') || low.contains('成本'));
}

/// 历史成交查询导出是否可识别（第五份文件，2026-08-18）。
/// 表头特征：含「成交日期」「证券代码」「买卖标志」「成交编号」。
/// 与持仓导出（成本价）/ 交易 CSV（symbol,name,direction…）区分开。
/// 通达信历史成交文件表头前有 `----` 分隔线 → 跳过以 `-` 开头的行再判断。
bool isTdxHistoryExport(String text) {
  for (final l in text.split(RegExp(r'[\r\n]+'))) {
    final t = l.trim();
    if (t.isEmpty || t.startsWith('-')) continue;
    return t.contains('成交日期') &&
        t.contains('证券代码') &&
        t.contains('买卖标志') &&
        t.contains('成交编号');
  }
  return false;
}

/// 通达信解析结果：持仓快照行 + 错误列表 + 已清空跳过行。
///
/// [errors] 与 [skipped] 的区别（2026-09-13 负成本批）：
/// - [errors] = **没看懂**的行（数量/成本列取不到数、代码不是 6 位）→ 调用方必须**拒绝整份导入**：
///   持仓导入是 `replace=true` 全量覆盖，漏掉一行 = 那只持仓被静默删除。
/// - [skipped] = **看懂了、但本来就不是持仓**的行（0 股残留）→ 正常导入，只需告知。
class TdxParseResult {
  final List<TdxPositionRow> rows;
  final List<String> errors;
  final List<String> skipped;

  TdxParseResult({required this.rows, required this.errors, this.skipped = const []});

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
  final skipped = <String>[];
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
    // 2026-09-13 负成本批修复：原实现只校验到数量列（col[2]），却继续读成本列（col[3]）
    // → 行比表头短时 cells[col[3]] 抛 RangeError（粘贴半截文件即触发），
    // 异常穿过解析器被上层 catch 成人话「导入失败：Invalid value...」，既难查也不体面。
    // 这里按「实际要读的最大列下标」校验。
    final needMax = [col[0], col[2], col[3]].reduce((a, b) => a > b ? a : b);
    if (cells.length <= needMax) {
      errors.add('第 ${i + 1} 行：字段不足（需要至少 ${needMax + 1} 列，实际 ${cells.length} 列）');
      continue;
    }
    final symbol = cells[col[0]].toUpperCase();
    if (symbol.isEmpty || !RegExp(r'^\d{6}$').hasMatch(symbol)) {
      errors.add('第 ${i + 1} 行：代码「${cells[col[0]]}」不是六位数字');
      continue;
    }
    final name = col[1] >= 0 && col[1] < cells.length ? cells[col[1]] : '';
    final quantity = int.tryParse(cells[col[2]].replaceAll(',', ''));
    if (quantity == null) {
      errors.add('第 ${i + 1} 行 $symbol $name：数量「${cells[col[2]]}」不是整数');
      continue;
    }
    // 0 股 = 券商文件里保留的已清空标的（不是持仓，也不是脏数据）→ 跳过但不报错
    if (quantity == 0) {
      skipped.add('$symbol $name（0 股，已清空）');
      continue;
    }
    if (quantity < 0) {
      errors.add('第 ${i + 1} 行 $symbol $name：数量「$quantity」为负，不是有效持仓');
      continue;
    }
    // 成本价：**负数合法**（反复做 T / 分红把成本摊到 0 以下是真实存在且券商就这么记的，
    // 实测 600601 方正科技 成本 −5.078 / 100 股，券商那行盈亏 +134% 正说明成本在 0 以下）。
    // 2026-09-13 负成本批修复：原实现 cost <= 0 一律当脏数据丢弃 → 用户 3 只持仓只进来 2 只，
    // 且因 replace 全量覆盖语义，若该票原本在持仓里会被**静默删除**。
    // 只有「取不到数」（空列/非数字）才是真错误。
    final cost = double.tryParse(cells[col[3]].replaceAll(',', ''));
    if (cost == null) {
      errors.add('第 ${i + 1} 行 $symbol $name：成本价「${cells[col[3]]}」不是数字');
      continue;
    }
    rows.add(TdxPositionRow(symbol: symbol, name: name, quantity: quantity, avgCost: cost));
  }
  return TdxParseResult(rows: rows, errors: errors, skipped: skipped);
}
