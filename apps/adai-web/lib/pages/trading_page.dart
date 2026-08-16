import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';
import '../utils/trade_import_parser.dart';
import '../widgets/page_header.dart';

/// 交易桌面形态 — web = 详细管理（RFC 20260816 §4.2）：
/// 快照 stat 卡 + DataTable 持仓（红涨绿亏 / 数字右对齐 + 逐行「编辑」）
/// + 记录交易 Dialog（止损位/买点类型/目标价/原因）+ 批量导入 + 交易历史 + 复盘历史。
/// 与 app（保持简单）分化：详细管理都在 web 端。
class TradingPage extends StatefulWidget {
  final ApiService api;

  const TradingPage({super.key, required this.api});

  @override
  State<TradingPage> createState() => _TradingPageState();
}

class _TradingPageState extends State<TradingPage> {
  PortfolioSnapshotResponse? _portfolio;
  List<PositionItem> _positions = [];
  bool _loading = true;
  String? _error;
  bool _reviewing = false; // 复盘生成中（#102 交易系统反哺入口）

  @override
  void initState() {
    super.initState();
    _loadAll();
  }

  Future<void> _loadAll() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final results = await Future.wait([
        widget.api.getPortfolio(),
        widget.api.getPositions(),
      ]);
      if (!mounted) return;
      setState(() {
        _portfolio = results[0] as PortfolioSnapshotResponse;
        _positions = (results[1] as PositionsResponse).positions;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  // ── 记录交易（扩展：止损位/买点/目标价/原因，RFC 20260816） ──

  Future<void> _recordTrade() async {
    final form = await showDialog<_TradeFormResult>(
      context: context,
      builder: (_) => const _TradeDialog(),
    );
    if (form == null || !mounted) return;
    try {
      await widget.api.recordTrade(
        symbol: form.symbol,
        name: form.name,
        direction: form.direction,
        price: form.price,
        volume: form.volume,
        stopLossPrice: form.stopLossPrice,
        buyPoint: form.buyPoint,
        targetPrice: form.targetPrice,
        reason: form.reason,
      );
      await _loadAll();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('记录交易失败: ${_extractApiError(e)}', style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
          backgroundColor: AppColors.darkSurface2,
        ));
      }
    }
  }

  // ── 持仓编辑（web 独有，PUT /positions/{symbol}） ──

  Future<void> _editPosition(PositionItem p) async {
    final result = await showDialog<_EditPositionResult>(
      context: context,
      builder: (_) => _EditPositionDialog(position: p),
    );
    if (result == null || !mounted) return;
    try {
      await widget.api.updatePosition(
        p.symbol,
        role: result.role,
        stopLossPrice: result.stopLossPrice,
        targetPrice: result.targetPrice,
      );
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text('${p.name.isEmpty ? p.symbol : p.name} 已更新',
            style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
        backgroundColor: AppColors.darkSurface2,
        duration: const Duration(seconds: 2),
      ));
      await _loadAll();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('更新失败: ${_extractApiError(e)}', style: const TextStyle(fontSize: 13, color: AppColors.darkOrange)),
          backgroundColor: AppColors.darkSurface2,
        ));
      }
    }
  }

  // ── 批量导入 / 交易历史 / 复盘历史 入口 ──

  Future<void> _showImport() {
    return showDialog<void>(
      context: context,
      builder: (_) => _ImportDialog(api: widget.api, onImported: _loadAll),
    );
  }

  Future<void> _showHistory() {
    return showDialog<void>(
      context: context,
      builder: (_) => _HistoryDialog(api: widget.api),
    );
  }

  Future<void> _showReviewHistory() {
    return showDialog<void>(
      context: context,
      builder: (_) => _ReviewHistoryDialog(api: widget.api),
    );
  }

  String _extractApiError(dynamic e) {
    final str = e.toString();
    if (str.contains('API 请求失败')) {
      final codeMatch = RegExp(r'HTTP (\d+)').firstMatch(str);
      final code = codeMatch?.group(1) ?? '?';
      return '请求失败 ($code)';
    }
    if (str.contains('TimeoutException') || str.contains('timed out')) return '请求超时，请检查网络';
    if (str.contains('Connection refused') || str.contains('SocketException')) return '无法连接服务器';
    return '网络异常，请重试';
  }

  @override
  Widget build(BuildContext context) {
    return Column(children: [
      PageHeader(
        title: '交易',
        subtitle: '持仓与组合快照 · 详细管理（编辑 / 导入 / 历史）',
        actions: [
          IconButton(
            onPressed: _showReviewHistory,
            icon: const Icon(Icons.calendar_month_outlined, size: 18),
            color: AppColors.darkGrey4,
            tooltip: '复盘历史',
          ),
          IconButton(
            onPressed: _showHistory,
            icon: const Icon(Icons.receipt_long_outlined, size: 18),
            color: AppColors.darkGrey4,
            tooltip: '交易历史',
          ),
          IconButton(
            onPressed: _showImport,
            icon: const Icon(Icons.upload_file_outlined, size: 18),
            color: AppColors.darkGrey4,
            tooltip: '批量导入',
          ),
          // #102 交易系统反哺入口：生成复盘（AI 基于当日交易记录 + 持仓）
          IconButton(
            onPressed: _reviewing ? null : _showReview,
            icon: _reviewing
                ? const SizedBox(width: 16, height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.darkGreen))
                : const Icon(Icons.article_outlined, size: 18, color: AppColors.darkGreen),
            color: AppColors.darkGreen,
            tooltip: '复盘',
          ),
          IconButton(
            onPressed: _loadAll,
            icon: const Icon(Icons.refresh, size: 18),
            color: AppColors.darkGrey4,
            tooltip: '刷新',
          ),
          Padding(
            padding: const EdgeInsets.only(right: 8),
            child: FilledButton.icon(
              onPressed: _recordTrade,
              icon: const Icon(Icons.add, size: 16),
              label: const Text('记录交易'),
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.darkGreen,
                foregroundColor: AppColors.darkBg,
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                textStyle: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
              ),
            ),
          ),
        ],
      ),
      Expanded(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : _error != null
                ? Center(child: Text('加载失败\n$_error', style: const TextStyle(color: AppColors.darkGrey5)))
                : ListView(
                    padding: const EdgeInsets.fromLTRB(20, 16, 20, 20),
                    children: [
                      _buildSnapshotRow(),
                      const SizedBox(height: 20),
                      _buildPositionTable(),
                    ],
                  ),
      ),
    ]);
  }

  Widget _buildSnapshotRow() {
    final p = _portfolio;
    return Row(children: [
      _statCard('总市值', p?.totalValue ?? 0.0, format: '\$', color: AppColors.darkBlue),
      const SizedBox(width: 12),
      // #132 红涨绿亏（A股）：盈=红、亏=绿，与行情卡一致
      _statCard('总盈亏', p?.totalPnl ?? 0.0, color: (p?.totalPnl ?? 0.0) >= 0 ? AppColors.darkRed : AppColors.darkGreen),
      const SizedBox(width: 12),
      _statCard('现金', p?.cashBalance ?? 0.0, format: '\$', color: AppColors.darkGrey3),
      const SizedBox(width: 12),
      _statCard('持仓数', (p?.positionCount ?? 0).toDouble(), format: '', color: AppColors.darkPurple),
    ]);
  }

  Widget _statCard(String label, double value, {String format = '\$', required Color color}) {
    final isCount = format.isEmpty;
    return Expanded(
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 16),
        decoration: BoxDecoration(
          color: AppColors.darkSurface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.6)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(label, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
            const SizedBox(height: 8),
            Text(
              isCount ? value.toInt().toString() : '$format${value.toStringAsFixed(2)}',
              style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700, color: color),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildPositionTable() {
    if (_positions.isEmpty) {
      return const Center(
        child: Padding(
          padding: EdgeInsets.only(top: 40),
          child: Text('暂无持仓', style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
        ),
      );
    }
    return Container(
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.6)),
      ),
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: DataTable(
          headingRowColor: WidgetStatePropertyAll(AppColors.darkSurface2.withValues(alpha: 0.5)),
          dataRowColor: WidgetStatePropertyAll(Colors.transparent),
          headingTextStyle: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppColors.darkGrey5),
          columnSpacing: 28,
          horizontalMargin: 16,
          columns: const [
            DataColumn(label: Text('代码')),
            DataColumn(label: Text('名称')),
            DataColumn(label: Text('数量'), numeric: true),
            DataColumn(label: Text('成本'), numeric: true),
            DataColumn(label: Text('现价'), numeric: true),
            DataColumn(label: Text('市值'), numeric: true),
            DataColumn(label: Text('盈亏'), numeric: true),
            DataColumn(label: Text('盈亏%'), numeric: true),
            DataColumn(label: Text('止损'), numeric: true),
            DataColumn(label: Text('买点')),
            DataColumn(label: Text('角色')),
            DataColumn(label: Text('操作')),
          ],
          rows: _positions.map((p) {
            // #132 红涨绿亏（A股）：盈=红、亏=绿
            final pnlColor = p.pnl >= 0 ? AppColors.darkRed : AppColors.darkGreen;
            return DataRow(cells: [
              DataCell(Text(p.symbol, style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1, fontWeight: FontWeight.w600))),
              DataCell(Text(p.name, style: const TextStyle(fontSize: 13, color: AppColors.darkGrey3))),
              DataCell(Text('${p.quantity}', style: const TextStyle(fontSize: 13, color: AppColors.darkGrey3))),
              DataCell(Text(p.avgCost.toStringAsFixed(3), style: const TextStyle(fontSize: 13, color: AppColors.darkGrey3))),
              DataCell(Text(p.currentPrice.toStringAsFixed(3), style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1))),
              DataCell(Text(p.marketValue.toStringAsFixed(2), style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1))),
              DataCell(Text(p.pnl.toStringAsFixed(2), style: TextStyle(fontSize: 13, color: pnlColor, fontWeight: FontWeight.w600))),
              DataCell(Text('${p.pnlPercent.toStringAsFixed(2)}%', style: TextStyle(fontSize: 13, color: pnlColor))),
              DataCell(Text(p.stopLossPrice?.toStringAsFixed(3) ?? '—', style: const TextStyle(fontSize: 13, color: AppColors.darkGrey3))),
              DataCell(Text(p.buyPoint ?? '—', style: const TextStyle(fontSize: 13, color: AppColors.darkGrey3))),
              DataCell(Text(p.role ?? '—', style: const TextStyle(fontSize: 13, color: AppColors.darkGrey3))),
              DataCell(TextButton(
                onPressed: () => _editPosition(p),
                style: TextButton.styleFrom(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                  minimumSize: Size.zero,
                  tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                ),
                child: const Text('编辑', style: TextStyle(fontSize: 12, color: AppColors.darkGreen)),
              )),
            ]);
          }).toList(),
        ),
      ),
    );
  }

  /// #102 复盘入口：生成今日复盘 → 弹窗展示（交易系统反哺可达）。
  Future<void> _showReview() async {
    setState(() => _reviewing = true);
    try {
      final review = await widget.api.generateReview();
      if (!mounted) return;
      setState(() => _reviewing = false);
      showDialog(
        context: context,
        builder: (_) => _buildReviewDialog(review),
      );
    } catch (e) {
      if (!mounted) return;
      setState(() => _reviewing = false);
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text('复盘生成失败: ${_extractApiError(e)}',
            style: const TextStyle(fontSize: 13, color: AppColors.darkOrange)),
        backgroundColor: AppColors.darkSurface2,
      ));
    }
  }

  Widget _buildReviewDialog(ReviewResponse review) {
    return Dialog(
      backgroundColor: AppColors.darkSurface,
      insetPadding: const EdgeInsets.all(24),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 20),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            const Icon(Icons.article_outlined, size: 18, color: AppColors.darkGreen),
            const SizedBox(width: 8),
            Text('${review.date} 复盘',
                style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
            const Spacer(),
            GestureDetector(
              onTap: () => Navigator.pop(context),
              child: const Icon(Icons.close, size: 18, color: AppColors.darkGrey5),
            ),
          ]),
          const SizedBox(height: 12),
          Flexible(
            child: SingleChildScrollView(
              child: MarkdownBody(
                data: review.content.isEmpty ? '今天暂无复盘内容' : review.content,
                selectable: true,
                styleSheet: MarkdownStyleSheet.fromTheme(ThemeData(
                  textTheme: const TextTheme(
                      bodyMedium: TextStyle(fontSize: 14, height: 1.6, color: AppColors.darkGrey1)),
                )).copyWith(
                  strong: const TextStyle(fontSize: 14, height: 1.6, color: AppColors.darkGrey1, fontWeight: FontWeight.w700),
                  p: const TextStyle(fontSize: 14, height: 1.6, color: AppColors.darkGrey1),
                ),
              ),
            ),
          ),
          const SizedBox(height: 14),
          // #129：知识反哺闭环前端入口——复盘内容提升为入库候选（写 os/trading-os/99-inbox/）
          Align(
            alignment: Alignment.centerRight,
            child: GestureDetector(
              onTap: () => _promote(review.date),
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 9),
                decoration: BoxDecoration(
                  color: AppColors.darkGreen.withValues(alpha: 0.15),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: AppColors.darkGreen.withValues(alpha: 0.4)),
                ),
                child: Row(mainAxisSize: MainAxisSize.min, children: [
                  Icon(Icons.inbox_outlined, size: 14, color: AppColors.darkGreen),
                  const SizedBox(width: 6),
                  Text('反哺入库', style: const TextStyle(fontSize: 13, color: AppColors.darkGreen)),
                ]),
              ),
            ),
          ),
        ]),
      ),
    );
  }

  /// #129：反哺入库——复盘内容提升为候选，展示 #178 融合提示。
  Future<void> _promote(String date) async {
    try {
      final result = await widget.api.promoteReview(date: date);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(result.message.isEmpty ? '已写入入库候选' : result.message,
                style: const TextStyle(fontSize: 12)),
            backgroundColor: AppColors.darkSurface2,
            duration: const Duration(seconds: 4)),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('反哺入库失败: ${_extractApiError(e)}',
                style: TextStyle(fontSize: 12, color: AppColors.darkOrange)),
            backgroundColor: AppColors.darkSurface2),
      );
    }
  }
}

// ─────────────────────────── 记录交易 Dialog ───────────────────────────

class _TradeFormResult {
  final String symbol;
  final String name;
  final String direction;
  final double price;
  final int volume;
  final double? stopLossPrice;
  final String? buyPoint;
  final double? targetPrice;
  final String? reason;
  _TradeFormResult(
    this.symbol,
    this.name,
    this.direction,
    this.price,
    this.volume, {
    this.stopLossPrice,
    this.buyPoint,
    this.targetPrice,
    this.reason,
  });
}

class _TradeDialog extends StatefulWidget {
  const _TradeDialog();

  @override
  State<_TradeDialog> createState() => _TradeDialogState();
}

class _TradeDialogState extends State<_TradeDialog> {
  final _symbol = TextEditingController();
  final _name = TextEditingController();
  final _price = TextEditingController();
  final _volume = TextEditingController();
  final _stopLoss = TextEditingController();
  final _targetPrice = TextEditingController();
  final _reason = TextEditingController();
  String _direction = 'BUY';
  String _buyPoint = 'B1';

  @override
  void dispose() {
    _symbol.dispose();
    _name.dispose();
    _price.dispose();
    _volume.dispose();
    _stopLoss.dispose();
    _targetPrice.dispose();
    _reason.dispose();
    super.dispose();
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(msg, style: const TextStyle(fontSize: 13)),
      backgroundColor: AppColors.darkSurface2,
    ));
  }

  void _submit() {
    final isBuy = _direction == 'BUY';
    final symbol = _symbol.text.trim().toUpperCase();
    final price = double.tryParse(_price.text.trim());
    final volume = int.tryParse(_volume.text.trim());
    if (symbol.isEmpty || price == null || price <= 0 || volume == null || volume <= 0) {
      _toast('请填写代码、价格和数量，价格和数量都要大于 0');
      return;
    }
    double? stopLoss;
    if (isBuy) {
      stopLoss = double.tryParse(_stopLoss.text.trim());
      if (stopLoss == null || stopLoss <= 0) {
        _toast('买入请填止损位（如 4.90），跌破就按计划处理');
        return;
      }
    }
    double? targetPrice;
    if (_targetPrice.text.trim().isNotEmpty) {
      targetPrice = double.tryParse(_targetPrice.text.trim());
      if (targetPrice == null || targetPrice <= 0) {
        _toast('目标价需要是大于 0 的数字');
        return;
      }
    }
    final reason = _reason.text.trim();
    Navigator.pop(context, _TradeFormResult(
      symbol,
      _name.text.trim(),
      _direction,
      price,
      volume,
      // SELL 不带止损/买点（RFC 20260816 §2.1：SELL 可空）
      stopLossPrice: isBuy ? stopLoss : null,
      buyPoint: isBuy ? _buyPoint : null,
      targetPrice: targetPrice,
      reason: reason.isEmpty ? null : reason,
    ));
  }

  @override
  Widget build(BuildContext context) {
    final isBuy = _direction == 'BUY';
    return AlertDialog(
      backgroundColor: AppColors.darkSurface2,
      title: const Text('记录交易', style: TextStyle(fontSize: 16, color: AppColors.darkGrey1)),
      content: SizedBox(
        width: 380,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: _symbol,
                decoration: const InputDecoration(labelText: '代码', hintText: '如 AAPL / 600519'),
                style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _name,
                decoration: const InputDecoration(labelText: '名称（可选）'),
                style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
              ),
              const SizedBox(height: 8),
              SegmentedButton<String>(
                segments: const [
                  ButtonSegment(value: 'BUY', label: Text('买入')),
                  ButtonSegment(value: 'SELL', label: Text('卖出')),
                ],
                selected: {_direction},
                onSelectionChanged: (s) => setState(() => _direction = s.first),
                style: ButtonStyle(visualDensity: VisualDensity.compact),
              ),
              const SizedBox(height: 8),
              Row(children: [
                Expanded(
                  child: TextField(
                    controller: _price,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(labelText: '价格'),
                    style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: TextField(
                    controller: _volume,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(labelText: '数量'),
                    style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
                  ),
                ),
              ]),
              if (isBuy) ...[
                const SizedBox(height: 8),
                TextField(
                  controller: _stopLoss,
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(labelText: '止损位', hintText: '止损价，如 4.90'),
                  style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
                ),
                const SizedBox(height: 8),
                DropdownButtonFormField<String>(
                  initialValue: _buyPoint,
                  decoration: const InputDecoration(labelText: '买点类型'),
                  style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
                  dropdownColor: AppColors.darkSurface2,
                  items: kBuyPointOptions
                      .map((o) => DropdownMenuItem(value: o, child: Text(o, style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1))))
                      .toList(),
                  onChanged: (v) => setState(() => _buyPoint = v ?? 'B1'),
                ),
              ],
              const SizedBox(height: 8),
              TextField(
                controller: _targetPrice,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(labelText: '目标价（可选）'),
                style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _reason,
                maxLines: 2,
                decoration: const InputDecoration(labelText: '交易原因（可选）', hintText: '一句话，如：突破平台回踩，预期放量上攻'),
                style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('取消', style: TextStyle(fontSize: 13, color: AppColors.darkGrey4)),
        ),
        FilledButton(
          onPressed: _submit,
          style: FilledButton.styleFrom(backgroundColor: AppColors.darkGreen, foregroundColor: AppColors.darkBg),
          child: const Text('提交', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
        ),
      ],
    );
  }
}

// ─────────────────────────── 持仓编辑 Dialog（web 独有） ───────────────────────────

/// 角色组合：防守/前锋/中场/机动 × 主仓/副仓（RFC 20260816 §2.2）。
const List<String> _kRoleOptions = [
  '防守·主仓', '防守·副仓',
  '前锋·主仓', '前锋·副仓',
  '中场·主仓', '中场·副仓',
  '机动·主仓', '机动·副仓',
];

String? _matchRole(String? role) {
  if (role == null || role.isEmpty) return null;
  return _kRoleOptions.contains(role) ? role : null;
}

class _EditPositionResult {
  final String role;
  final double? stopLossPrice;
  final double? targetPrice;
  _EditPositionResult({required this.role, this.stopLossPrice, this.targetPrice});
}

class _EditPositionDialog extends StatefulWidget {
  final PositionItem position;

  const _EditPositionDialog({required this.position});

  @override
  State<_EditPositionDialog> createState() => _EditPositionDialogState();
}

class _EditPositionDialogState extends State<_EditPositionDialog> {
  late String _role;
  late final TextEditingController _stopLoss;
  late final TextEditingController _targetPrice;

  @override
  void initState() {
    super.initState();
    _role = _matchRole(widget.position.role) ?? '机动·副仓';
    _stopLoss = TextEditingController(
        text: widget.position.stopLossPrice != null ? _trimNum(widget.position.stopLossPrice!) : '');
    _targetPrice = TextEditingController(
        text: widget.position.targetPrice != null ? _trimNum(widget.position.targetPrice!) : '');
  }

  /// 去掉多余小数位：1500.0 → 1500，4.90 → 4.9。
  static String _trimNum(double v) {
    var s = v.toStringAsFixed(4);
    while (s.contains('.') && (s.endsWith('0') || s.endsWith('.'))) {
      s = s.substring(0, s.length - 1);
    }
    return s;
  }

  @override
  void dispose() {
    _stopLoss.dispose();
    _targetPrice.dispose();
    super.dispose();
  }

  void _submit() {
    double? stopLoss;
    if (_stopLoss.text.trim().isNotEmpty) {
      stopLoss = double.tryParse(_stopLoss.text.trim());
      if (stopLoss == null || stopLoss <= 0) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text('止损位需要是大于 0 的数字', style: TextStyle(fontSize: 13)),
          backgroundColor: AppColors.darkSurface2,
        ));
        return;
      }
    }
    double? targetPrice;
    if (_targetPrice.text.trim().isNotEmpty) {
      targetPrice = double.tryParse(_targetPrice.text.trim());
      if (targetPrice == null || targetPrice <= 0) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text('目标价需要是大于 0 的数字', style: TextStyle(fontSize: 13)),
          backgroundColor: AppColors.darkSurface2,
        ));
        return;
      }
    }
    Navigator.pop(context, _EditPositionResult(
      role: _role,
      stopLossPrice: stopLoss,
      targetPrice: targetPrice,
    ));
  }

  @override
  Widget build(BuildContext context) {
    final p = widget.position;
    return AlertDialog(
      backgroundColor: AppColors.darkSurface2,
      title: Text('编辑持仓 · ${p.symbol}${p.name.isEmpty ? '' : ' ${p.name}'}',
          style: const TextStyle(fontSize: 16, color: AppColors.darkGrey1)),
      content: SizedBox(
        width: 380,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            DropdownButtonFormField<String>(
              initialValue: _role,
              decoration: const InputDecoration(labelText: '持仓角色'),
              style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
              dropdownColor: AppColors.darkSurface2,
              items: _kRoleOptions
                  .map((o) => DropdownMenuItem(value: o, child: Text(o, style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1))))
                  .toList(),
              onChanged: (v) => setState(() => _role = v ?? _role),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _stopLoss,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: '止损位', hintText: '止损价，如 4.90'),
              style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _targetPrice,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: '目标价（可选）'),
              style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
            ),
            const SizedBox(height: 4),
            Text('清空止损/目标价 = 不修改，保持原值', style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('取消', style: TextStyle(fontSize: 13, color: AppColors.darkGrey4)),
        ),
        FilledButton(
          onPressed: _submit,
          style: FilledButton.styleFrom(backgroundColor: AppColors.darkGreen, foregroundColor: AppColors.darkBg),
          child: const Text('保存', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
        ),
      ],
    );
  }
}

// ─────────────────────────── 批量导入 Dialog（web 独有） ───────────────────────────

class _ImportDialog extends StatefulWidget {
  final ApiService api;
  final Future<void> Function() onImported;

  const _ImportDialog({required this.api, required this.onImported});

  @override
  State<_ImportDialog> createState() => _ImportDialogState();
}

class _ImportDialogState extends State<_ImportDialog> {
  final _text = TextEditingController();
  bool _importing = false;
  int? _successCount;
  final List<String> _errors = [];

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  Future<void> _import() async {
    final parsed = parseImportTrades(_text.text);
    if (parsed.rows.isEmpty && parsed.errors.isEmpty) {
      setState(() {
        _successCount = 0;
        _errors
          ..clear()
          ..add('还没有可导入的内容，请按格式粘贴或填写');
      });
      return;
    }
    setState(() {
      _importing = true;
      _successCount = null;
      _errors
        ..clear()
        ..addAll(parsed.errors);
    });
    if (parsed.rows.isEmpty) {
      setState(() => _importing = false);
      return;
    }
    try {
      final result = await widget.api.importTrades(
        parsed.rows.map((r) => r.toJson()).toList(),
      );
      if (!mounted) return;
      setState(() {
        _importing = false;
        _successCount = result.success;
        _errors.addAll(result.failures.map(
          (f) => f.row > 0 ? '第 ${f.row} 行：${f.message}' : f.message,
        ));
      });
      // 有成功条目（含部分成功）→ 刷新持仓表格
      if (result.success > 0) {
        widget.onImported();
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _importing = false;
        _successCount = 0;
        _errors.add('导入请求失败，请检查网络后重试');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      backgroundColor: AppColors.darkSurface2,
      title: const Text('批量导入交易', style: TextStyle(fontSize: 16, color: AppColors.darkGrey1)),
      content: SizedBox(
        width: 520,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('每行一笔，逗号分隔：代码,名称,方向,价格,数量,止损,买点[,原因]',
                style: TextStyle(fontSize: 12, color: AppColors.darkGrey4)),
            const SizedBox(height: 4),
            const Text('例：600519,贵州茅台,BUY,1500,100,1350,B1,季报前埋伏',
                style: TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
            const SizedBox(height: 8),
            TextField(
              controller: _text,
              maxLines: 9,
              minLines: 5,
              style: const TextStyle(fontSize: 12, color: AppColors.darkGrey1),
              decoration: const InputDecoration(
                hintText: '粘贴 CSV 或多行文本…\n600123,立昂微,买,25.30,200,22.8,B2',
                alignLabelWithHint: true,
              ),
            ),
            if (_successCount != null || _errors.isNotEmpty) ...[
              const SizedBox(height: 10),
              if (_successCount != null)
                Text('成功导入 $_successCount 条',
                    style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.darkGreen)),
              if (_errors.isNotEmpty) ...[
                const SizedBox(height: 6),
                Container(
                  constraints: const BoxConstraints(maxHeight: 110),
                  width: double.infinity,
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: AppColors.darkSurface,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: ListView.builder(
                    shrinkWrap: true,
                    itemCount: _errors.length,
                    itemBuilder: (_, i) => Text(_errors[i],
                        style: const TextStyle(fontSize: 12, color: AppColors.darkOrange)),
                  ),
                ),
              ],
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('关闭', style: TextStyle(fontSize: 13, color: AppColors.darkGrey4)),
        ),
        FilledButton.icon(
          onPressed: _importing ? null : _import,
          icon: _importing
              ? const SizedBox(width: 14, height: 14,
                  child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.darkBg))
              : const Icon(Icons.upload_file, size: 16),
          label: Text(_importing ? '导入中…' : '导入'),
          style: FilledButton.styleFrom(backgroundColor: AppColors.darkGreen, foregroundColor: AppColors.darkBg),
        ),
      ],
    );
  }
}

// ─────────────────────────── 交易历史 Dialog（web 独有） ───────────────────────────

class _HistoryDialog extends StatefulWidget {
  final ApiService api;

  const _HistoryDialog({required this.api});

  @override
  State<_HistoryDialog> createState() => _HistoryDialogState();
}

class _HistoryDialogState extends State<_HistoryDialog> {
  late DateTime _from;
  late DateTime _to;
  List<TradeRecordItem>? _trades;
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    final now = DateTime.now();
    _to = now;
    _from = now.subtract(const Duration(days: 30));
    _load();
  }

  static String _fmt(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final trades = await widget.api.getTrades(from: _fmt(_from), to: _fmt(_to));
      if (!mounted) return;
      setState(() {
        _trades = trades;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  Future<void> _pickFrom() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _from,
      firstDate: DateTime(2020),
      lastDate: _to,
    );
    if (picked != null && mounted) {
      setState(() => _from = picked);
      _load();
    }
  }

  Future<void> _pickTo() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _to,
      firstDate: _from,
      lastDate: DateTime.now().add(const Duration(days: 1)),
    );
    if (picked != null && mounted) {
      setState(() => _to = picked);
      _load();
    }
  }

  /// 按日期分组（日期降序；无日期归「未标注日期」）。
  Map<String, List<TradeRecordItem>> _grouped() {
    final map = <String, List<TradeRecordItem>>{};
    for (final t in _trades ?? <TradeRecordItem>[]) {
      final key = t.entryDate.isEmpty ? '未标注日期' : t.entryDate;
      map.putIfAbsent(key, () => []).add(t);
    }
    final keys = map.keys.toList()..sort((a, b) {
      if (a == '未标注日期') return 1;
      if (b == '未标注日期') return -1;
      return b.compareTo(a);
    });
    return {for (final k in keys) k: map[k]!};
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      backgroundColor: AppColors.darkSurface,
      insetPadding: const EdgeInsets.all(24),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: SizedBox(
        width: 760,
        height: 520,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 16),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              const Icon(Icons.receipt_long_outlined, size: 18, color: AppColors.darkGreen),
              const SizedBox(width: 8),
              const Text('交易历史',
                  style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
              const Spacer(),
              // 日期范围选择
              OutlinedButton(
                onPressed: _pickFrom,
                style: OutlinedButton.styleFrom(
                  foregroundColor: AppColors.darkGrey3,
                  side: const BorderSide(color: AppColors.darkBorder),
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                  visualDensity: VisualDensity.compact,
                ),
                child: Text(_fmt(_from), style: const TextStyle(fontSize: 12)),
              ),
              const Padding(
                padding: EdgeInsets.symmetric(horizontal: 6),
                child: Text('至', style: TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
              ),
              OutlinedButton(
                onPressed: _pickTo,
                style: OutlinedButton.styleFrom(
                  foregroundColor: AppColors.darkGrey3,
                  side: const BorderSide(color: AppColors.darkBorder),
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                  visualDensity: VisualDensity.compact,
                ),
                child: Text(_fmt(_to), style: const TextStyle(fontSize: 12)),
              ),
              const SizedBox(width: 8),
              IconButton(
                onPressed: _load,
                icon: const Icon(Icons.refresh, size: 16),
                color: AppColors.darkGrey4,
                tooltip: '重新加载',
              ),
              GestureDetector(
                onTap: () => Navigator.pop(context),
                child: const Icon(Icons.close, size: 18, color: AppColors.darkGrey5),
              ),
            ]),
            const SizedBox(height: 10),
            const Divider(color: AppColors.darkBorder, height: 1),
            const SizedBox(height: 6),
            Expanded(
              child: _loading
                  ? const Center(child: CircularProgressIndicator())
                  : _error != null
                      ? Center(child: Text('加载失败\n$_error', style: const TextStyle(fontSize: 13, color: AppColors.darkGrey5)))
                      : (_trades?.isEmpty ?? true)
                          ? const Center(
                              child: Text('这段时间还没有交易记录', style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)))
                          : SingleChildScrollView(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  _buildListHeader(),
                                  ..._grouped().entries.map((e) => _buildDateGroup(e.key, e.value)),
                                ],
                              ),
                            ),
            ),
          ]),
        ),
      ),
    );
  }

  Widget _buildListHeader() {
    Widget cell(String label, double width, {bool right = false}) => SizedBox(
          width: width,
          child: Text(label,
              textAlign: right ? TextAlign.right : TextAlign.left,
              style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
        );
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(children: [
        cell('方向', 44),
        cell('代码', 72),
        cell('名称', 88),
        cell('数量', 60, right: true),
        cell('价格', 70, right: true),
        cell('止损', 70, right: true),
        cell('买点', 56),
        Expanded(child: cell('原因', 0)),
      ]),
    );
  }

  Widget _buildDateGroup(String date, List<TradeRecordItem> trades) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Row(children: [
            Text(date, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.darkGrey2)),
            const SizedBox(width: 8),
            Text('${trades.length} 笔', style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
          ]),
        ),
        Container(
          decoration: BoxDecoration(
            color: AppColors.darkSurface2.withValues(alpha: 0.4),
            borderRadius: BorderRadius.circular(8),
          ),
          child: Column(
            children: trades.map((t) => _buildTradeRow(t)).toList(),
          ),
        ),
      ],
    );
  }

  Widget _buildTradeRow(TradeRecordItem t) {
    Widget cell(String text, double width, {bool right = false, Color? color}) => SizedBox(
          width: width,
          child: Text(text,
              textAlign: right ? TextAlign.right : TextAlign.left,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(fontSize: 12, color: color ?? AppColors.darkGrey3)),
        );
    final dirColor = t.isBuy ? AppColors.darkGrey1 : AppColors.darkGrey3;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
      child: Row(children: [
        cell(t.isBuy ? '买入' : '卖出', 44, color: dirColor),
        cell(t.symbol, 72, color: AppColors.darkGrey1),
        cell(t.name, 88),
        cell('${t.volume}', 60, right: true),
        cell(t.price.toStringAsFixed(3), 70, right: true),
        cell(t.stopLossPrice?.toStringAsFixed(3) ?? '—', 70, right: true),
        cell(t.buyPoint ?? '—', 56),
        Expanded(
          child: Text(t.reason ?? '',
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
        ),
      ]),
    );
  }
}

// ─────────────────────────── 复盘历史 Dialog ───────────────────────────

class _ReviewHistoryDialog extends StatefulWidget {
  final ApiService api;

  const _ReviewHistoryDialog({required this.api});

  @override
  State<_ReviewHistoryDialog> createState() => _ReviewHistoryDialogState();
}

class _ReviewHistoryDialogState extends State<_ReviewHistoryDialog> {
  List<String>? _dates;
  String? _selected;
  String? _content;
  bool _loadingDates = true;
  bool _loadingContent = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _loadDates();
  }

  Future<void> _loadDates() async {
    setState(() {
      _loadingDates = true;
      _error = null;
    });
    try {
      final dates = await widget.api.getReviewDates();
      if (!mounted) return;
      setState(() {
        _dates = dates;
        _loadingDates = false;
      });
      // 默认打开最新一份复盘
      if (dates.isNotEmpty) _loadContent(dates.first);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _loadingDates = false;
      });
    }
  }

  Future<void> _loadContent(String date) async {
    setState(() {
      _selected = date;
      _loadingContent = true;
      _content = null;
    });
    try {
      final review = await widget.api.getReview(date: date);
      if (!mounted) return;
      setState(() {
        _content = review?.content ?? '这份复盘暂时没有内容';
        _loadingContent = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _content = '加载失败：${e.toString()}';
        _loadingContent = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      backgroundColor: AppColors.darkSurface,
      insetPadding: const EdgeInsets.all(24),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: SizedBox(
        width: 760,
        height: 480,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 16),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              const Icon(Icons.calendar_month_outlined, size: 18, color: AppColors.darkGreen),
              const SizedBox(width: 8),
              const Text('复盘历史',
                  style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
              const Spacer(),
              IconButton(
                onPressed: _loadDates,
                icon: const Icon(Icons.refresh, size: 16),
                color: AppColors.darkGrey4,
                tooltip: '重新加载',
              ),
              GestureDetector(
                onTap: () => Navigator.pop(context),
                child: const Icon(Icons.close, size: 18, color: AppColors.darkGrey5),
              ),
            ]),
            const SizedBox(height: 10),
            Expanded(
              child: _loadingDates
                  ? const Center(child: CircularProgressIndicator())
                  : _error != null
                      ? Center(child: Text('加载失败\n$_error', style: const TextStyle(fontSize: 13, color: AppColors.darkGrey5)))
                      : (_dates?.isEmpty ?? true)
                          ? const Center(
                              child: Text('还没有复盘记录，点页头「复盘」生成第一份',
                                  style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)))
                          : Row(
                              crossAxisAlignment: CrossAxisAlignment.stretch,
                              children: [
                                // 左：日期列表
                                Container(
                                  width: 170,
                                  decoration: BoxDecoration(
                                    color: AppColors.darkSurface2.withValues(alpha: 0.5),
                                    borderRadius: BorderRadius.circular(10),
                                  ),
                                  child: ListView.builder(
                                    itemCount: _dates!.length,
                                    itemBuilder: (_, i) {
                                      final d = _dates![i];
                                      final selected = d == _selected;
                                      return InkWell(
                                        onTap: () => _loadContent(d),
                                        child: Container(
                                          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
                                          color: selected ? AppColors.darkGreen.withValues(alpha: 0.15) : Colors.transparent,
                                          child: Text(d,
                                              style: TextStyle(
                                                  fontSize: 12,
                                                  fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
                                                  color: selected ? AppColors.darkGreen : AppColors.darkGrey3)),
                                        ),
                                      );
                                    },
                                  ),
                                ),
                                const SizedBox(width: 12),
                                // 右：内容
                                Expanded(
                                  child: _loadingContent
                                      ? const Center(child: CircularProgressIndicator())
                                      : SingleChildScrollView(
                                          child: MarkdownBody(
                                            data: _content ?? '',
                                            selectable: true,
                                            styleSheet: MarkdownStyleSheet.fromTheme(ThemeData(
                                              textTheme: const TextTheme(
                                                  bodyMedium: TextStyle(fontSize: 13, height: 1.6, color: AppColors.darkGrey1)),
                                            )).copyWith(
                                              strong: const TextStyle(fontSize: 13, height: 1.6, color: AppColors.darkGrey1, fontWeight: FontWeight.w700),
                                              p: const TextStyle(fontSize: 13, height: 1.6, color: AppColors.darkGrey1),
                                            ),
                                          ),
                                        ),
                                ),
                              ],
                            ),
            ),
          ]),
        ),
      ),
    );
  }
}
