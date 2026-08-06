import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';
import '../widgets/page_header.dart';

/// 交易桌面形态 — 快照 stat 卡 + 真 DataTable 持仓（红涨绿亏 / 数字右对齐）+ 记录交易 Dialog + 复盘。
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
        subtitle: '持仓与组合快照',
        actions: [
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
        ]),
      ),
    );
  }
}

class _TradeFormResult {
  final String symbol;
  final String name;
  final String direction;
  final double price;
  final int volume;
  _TradeFormResult(this.symbol, this.name, this.direction, this.price, this.volume);
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
  String _direction = 'BUY';

  @override
  void dispose() {
    _symbol.dispose();
    _name.dispose();
    _price.dispose();
    _volume.dispose();
    super.dispose();
  }

  void _submit() {
    final symbol = _symbol.text.trim().toUpperCase();
    final price = double.tryParse(_price.text.trim());
    final volume = int.tryParse(_volume.text.trim());
    if (symbol.isEmpty || price == null || volume == null || volume <= 0) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('请填写完整且合法的交易信息', style: TextStyle(fontSize: 13)),
      ));
      return;
    }
    Navigator.pop(context, _TradeFormResult(symbol, _name.text.trim(), _direction, price, volume));
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      backgroundColor: AppColors.darkSurface2,
      title: const Text('记录交易', style: TextStyle(fontSize: 16, color: AppColors.darkGrey1)),
      content: SizedBox(
        width: 380,
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
          child: const Text('提交', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
        ),
      ],
    );
  }
}
