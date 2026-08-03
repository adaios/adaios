import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';

/// TradingPage — 持仓 + 组合快照 + 记录交易。
class TradingPage extends StatefulWidget {
  final ApiService api;

  const TradingPage({super.key, required this.api});

  @override
  State<TradingPage> createState() => _TradingPageState();
}

class _TradingPageState extends State<TradingPage> {
  List<PositionItem> _positions = [];
  PortfolioSnapshotResponse? _snapshot;
  bool _loading = true;
  String? _error;
  bool _showForm = false;
  bool _reviewing = false; // 复盘生成中（#102 反哺入口）

  // 表单
  final _symbolCtrl = TextEditingController();
  final _nameCtrl = TextEditingController();
  String _direction = 'BUY';
  final _priceCtrl = TextEditingController();
  final _volumeCtrl = TextEditingController();
  bool _submitting = false;

  @override
  void initState() {
    super.initState();
    _loadAll();
  }

  @override
  void dispose() {
    _symbolCtrl.dispose();
    _nameCtrl.dispose();
    _priceCtrl.dispose();
    _volumeCtrl.dispose();
    super.dispose();
  }

  /// 首次加载 / 下拉刷新：显示整页加载指示。
  Future<void> _loadAll() async {
    setState(() { _loading = true; _error = null; });
    await _loadData();
  }

  /// 操作成功后静默刷新：不置 _loading，避免每次操作整页闪 Spinner。
  Future<void> _refresh() async {
    if (!mounted) return;
    await _loadData();
  }

  Future<void> _loadData() async {
    try {
      final positionsResp = await widget.api.getPositions();
      final snapshotResp = await widget.api.getPortfolio();
      if (!mounted) return;
      setState(() {
        _positions = positionsResp.positions;
        _snapshot = snapshotResp;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() { _error = _extractApiError(e); _loading = false; }); // #113 人话
    }
  }

  Future<void> _submitTrade() async {
    final symbol = _symbolCtrl.text.trim();
    final name = _nameCtrl.text.trim();
    final price = double.tryParse(_priceCtrl.text.trim());
    final volume = int.tryParse(_volumeCtrl.text.trim());
    if (symbol.isEmpty || name.isEmpty || price == null || volume == null || volume <= 0) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('请填写完整信息', style: TextStyle(color: AppColors.darkGrey1)),
            backgroundColor: AppColors.darkSurface2),
      );
      return;
    }

    setState(() => _submitting = true);
    try {
      await widget.api.recordTrade(symbol: symbol, name: name, direction: _direction, price: price, volume: volume);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('交易已记录', style: TextStyle(color: AppColors.darkGreen)),
            backgroundColor: AppColors.darkSurface2),
      );
      _symbolCtrl.clear(); _nameCtrl.clear(); _priceCtrl.clear(); _volumeCtrl.clear();
      setState(() { _showForm = false; _submitting = false; });
      _refresh();
    } catch (e) {
      if (!mounted) return;
      setState(() => _submitting = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('记录失败: ${_extractApiError(e)}', style: TextStyle(color: AppColors.darkOrange)),
            backgroundColor: AppColors.darkSurface2),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.darkBg,
      appBar: AppBar(
        backgroundColor: AppColors.darkBg,
        elevation: 0,
        leading: IconButton(
          icon: Icon(Icons.arrow_back, color: AppColors.darkGrey4),
          onPressed: () => Navigator.pop(context),
        ),
        title: Text('交易', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
        actions: [
          // #102 交易系统反哺入口：生成复盘（AI 基于当日交易记录 + 持仓）
          IconButton(
            icon: _reviewing
                ? const SizedBox(width: 18, height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.darkGrey5))
                : Icon(Icons.article_outlined, size: 18, color: AppColors.darkGreen),
            onPressed: _reviewing ? null : _showReview,
            tooltip: '复盘',
          ),
          IconButton(
            icon: Icon(Icons.refresh, size: 18, color: AppColors.darkGrey5),
            onPressed: _loadAll,
          ),
        ],
      ),
      body: _loading ? const Center(child: CircularProgressIndicator())
          : _error != null ? _buildError()
          : ListView(
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
              children: [
                _buildSnapshotCard(),
                const SizedBox(height: 16),
                Row(children: [
                  _sectionTitle('持仓明细'),
                  const Spacer(),
                  _buildAddButton(),
                ]),
                if (_showForm) _buildTradeForm(),
                _buildPositionTable(),
              ],
            ),
    );
  }

  Widget _sectionTitle(String title) {
    return Text(title, style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.darkGrey5));
  }

  Widget _buildAddButton() {
    return GestureDetector(
      onTap: () => setState(() => _showForm = !_showForm),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        decoration: BoxDecoration(
          color: AppColors.darkGreen.withValues(alpha: 0.1),
          borderRadius: BorderRadius.circular(6),
          border: Border.all(color: AppColors.darkGreen.withValues(alpha: 0.25), width: 0.5),
        ),
        child: Text(_showForm ? '收起' : '+ 记录交易',
            style: TextStyle(fontSize: 11, fontWeight: FontWeight.w500, color: AppColors.darkGreen)),
      ),
    );
  }

  Widget _buildTradeForm() {
    return Container(
      margin: const EdgeInsets.only(top: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        _formField('代码', _symbolCtrl),
        const SizedBox(height: 8),
        _formField('名称', _nameCtrl),
        const SizedBox(height: 8),
        Row(children: [
          _sectionTitle('方向'),
          const SizedBox(width: 10),
          _dirChip('BUY', '买入'),
          const SizedBox(width: 6),
          _dirChip('SELL', '卖出'),
        ]),
        const SizedBox(height: 8),
        Row(children: [
          Expanded(child: _formField('单价', _priceCtrl, keyboardType: TextInputType.number)),
          const SizedBox(width: 10),
          Expanded(child: _formField('数量', _volumeCtrl, keyboardType: TextInputType.number)),
        ]),
        const SizedBox(height: 12),
        SizedBox(
          width: double.infinity,
          height: 36,
          child: ElevatedButton(
            onPressed: _submitting ? null : _submitTrade,
            style: ElevatedButton.styleFrom(
              backgroundColor: AppColors.darkGreen.withValues(alpha: 0.2),
              foregroundColor: AppColors.darkGreen,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
            ),
            child: Text(_submitting ? '提交中...' : '记录交易', style: const TextStyle(fontWeight: FontWeight.w500)),
          ),
        ),
      ]),
    );
  }

  Widget _formField(String label, TextEditingController ctrl, {TextInputType keyboardType = TextInputType.text}) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(label, style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
      const SizedBox(height: 4),
      TextField(
        controller: ctrl,
        keyboardType: keyboardType,
        style: TextStyle(fontSize: 13, color: AppColors.darkGrey2),
        decoration: InputDecoration(
          isDense: true,
          contentPadding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
          filled: true,
          fillColor: AppColors.darkBg,
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(6),
            borderSide: BorderSide(color: AppColors.darkBorder, width: 0.5),
          ),
          enabledBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(6),
            borderSide: BorderSide(color: AppColors.darkBorder, width: 0.5),
          ),
          focusedBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(6),
            borderSide: BorderSide(color: AppColors.darkGreen, width: 0.5),
          ),
        ),
      ),
    ]);
  }

  Widget _dirChip(String value, String label) {
    final selected = _direction == value;
    return GestureDetector(
      onTap: () => setState(() => _direction = value),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
        decoration: BoxDecoration(
          color: selected ? AppColors.darkGreen.withValues(alpha: 0.15) : AppColors.darkBg,
          borderRadius: BorderRadius.circular(6),
          border: Border.all(
            color: selected ? AppColors.darkGreen.withValues(alpha: 0.3) : AppColors.darkBorder,
            width: 0.5,
          ),
        ),
        child: Text(label, style: TextStyle(fontSize: 12, fontWeight: FontWeight.w500,
            color: selected ? AppColors.darkGreen : AppColors.darkGrey5)),
      ),
    );
  }

  Widget _buildSnapshotCard() {
    if (_snapshot == null) return const SizedBox.shrink();
    final s = _snapshot!;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceAround,
        children: [
          _snapshotItem('总市值', _fmtMoney(s.totalValue), AppColors.darkGrey1),
          // #132 红涨绿跌（A股）：盈=红、亏=绿，与行情卡一致
          _snapshotItem('总盈亏', '${s.totalPnl >= 0 ? '+' : ''}${_fmtMoney(s.totalPnl)}',
              s.totalPnl >= 0 ? AppColors.darkRed : AppColors.darkGreen),
          _snapshotItem('现金', _fmtMoney(s.cashBalance), AppColors.darkBlue),
        ],
      ),
    );
  }

  Widget _snapshotItem(String label, String value, Color color) {
    return Column(children: [
      Text(value, style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: color)),
      const SizedBox(height: 2),
      Text(label, style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
    ]);
  }

  Widget _buildPositionTable() {
    if (_positions.isEmpty) {
      return Padding(
        padding: const EdgeInsets.only(top: 40),
        child: Center(child: Text('暂无持仓', style: TextStyle(fontSize: 13, color: AppColors.darkGrey5))),
      );
    }

    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      const SizedBox(height: 8),
      // 表头
      Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 10),
        decoration: BoxDecoration(
          color: AppColors.darkSurface2,
          borderRadius: const BorderRadius.vertical(top: Radius.circular(10)),
        ),
        child: Row(children: [
          SizedBox(width: 56, child: _th('代码')),
          SizedBox(width: 64, child: _th('名称')),
          SizedBox(width: 28, child: _th('数量', align: TextAlign.right)),
          SizedBox(width: 48, child: _th('成本', align: TextAlign.right)),
          SizedBox(width: 48, child: _th('现价', align: TextAlign.right)),
          Expanded(child: _th('盈亏', align: TextAlign.right)),
        ]),
      ),
      // 行
      ...List.generate(_positions.length, (i) {
        final p = _positions[i];
        final last = i == _positions.length - 1;
        final isGreen = p.pnl >= 0;
        return Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 10),
          decoration: BoxDecoration(
            color: AppColors.darkSurface2.withValues(alpha: i.isEven ? 0.5 : 0.3),
            borderRadius: last ? const BorderRadius.vertical(bottom: Radius.circular(10)) : null,
          ),
          child: Row(children: [
            SizedBox(width: 56, child: Text(p.symbol, style: TextStyle(fontSize: 12, color: AppColors.darkGrey2))),
            SizedBox(width: 64, child: Text(p.name, style: TextStyle(fontSize: 12, color: AppColors.darkGrey2), overflow: TextOverflow.ellipsis)),
            SizedBox(width: 28, child: Text('${p.quantity}', style: TextStyle(fontSize: 12, color: AppColors.darkGrey3), textAlign: TextAlign.right)),
            SizedBox(width: 48, child: Text(_fmtPrice(p.avgCost), style: TextStyle(fontSize: 12, color: AppColors.darkGrey3), textAlign: TextAlign.right)),
            SizedBox(width: 48, child: Text(_fmtPrice(p.currentPrice), style: TextStyle(fontSize: 12, color: AppColors.darkGrey3), textAlign: TextAlign.right)),
            Expanded(child: Text('${isGreen ? "+" : ""}${_fmtMoney(p.pnl)} (${p.pnlPercent.toStringAsFixed(1)}%)',
                // #132 红涨绿跌（A股）：盈=红、亏=绿
                style: TextStyle(fontSize: 12, color: isGreen ? AppColors.darkRed : AppColors.darkGreen), textAlign: TextAlign.right)),
          ]),
        );
      }),
    ]);
  }

  Widget _th(String text, {TextAlign align = TextAlign.left}) {
    return Text(text, style: TextStyle(fontSize: 10, fontWeight: FontWeight.w600, color: AppColors.darkGrey5), textAlign: align);
  }

  String _fmtMoney(double v) {
    if (v.abs() >= 10000) return '${(v / 10000).toStringAsFixed(1)}万';
    return v.toStringAsFixed(0);
  }

  String _fmtPrice(double v) => v.toStringAsFixed(2);

  String _extractApiError(dynamic e) {
    final str = e.toString();
    if (str.contains('API 错误') || str.contains('API 请求失败')) {
      final codeMatch = RegExp(r'(\d{3})').firstMatch(str);
      final code = codeMatch?.group(1) ?? '?';
      return '请求失败 ($code)';
    }
    if (str.contains('TimeoutException') || str.contains('timed out')) return '请求超时，请检查网络';
    if (str.contains('Connection refused') || str.contains('SocketException')) return '无法连接服务器，请确认后端已启动';
    return '加载失败，请重试';
  }

  /// #108 错误态：区分「后端故障」vs「无数据」，带重试。
  Widget _buildError() {
    return Center(
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        Icon(Icons.error_outline, size: 28, color: AppColors.darkOrange),
        const SizedBox(height: 10),
        Text(_error ?? '加载失败', style: const TextStyle(fontSize: 15, color: AppColors.darkGrey4)),
        const SizedBox(height: 14),
        GestureDetector(
          onTap: _loadAll,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
            decoration: BoxDecoration(
              color: AppColors.darkSurface2,
              borderRadius: BorderRadius.circular(10),
              border: Border.all(color: AppColors.darkGreen.withValues(alpha: 0.3)),
            ),
            child: const Text('重试', style: TextStyle(fontSize: 13, color: AppColors.darkGreen)),
          ),
        ),
      ]),
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
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('复盘生成失败: ${_extractApiError(e)}', style: TextStyle(color: AppColors.darkOrange)),
            backgroundColor: AppColors.darkSurface2),
      );
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
            Icon(Icons.article_outlined, size: 18, color: AppColors.darkGreen),
            const SizedBox(width: 8),
            Text('${review.date} 复盘', style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
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
                  textTheme: const TextTheme(bodyMedium: TextStyle(fontSize: 14, height: 1.6, color: AppColors.darkGrey1)),
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
