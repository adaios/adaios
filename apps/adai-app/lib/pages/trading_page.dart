import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';

/// TradingPage — 建议引擎，不是记账工具（RFC 20260815）。
///
/// 记录真实交易是手段（喂数据），建议是目的：阿呆结合 trading domain 规则
/// 给买/卖/持仓建议。本页没有「平仓/减仓」执行按钮——建议是输出，不是操作。
///
/// 记录区两条通道共用同一确认/写入链路：
/// - 通道 A：一句话（NL）→ POST /trading/trades/parse → 确认卡回显（AI 错误在此拦截）
/// - 通道 B：精确填写折叠表单（标的 | 价格 | 数量 + 底部 [买入][卖出] 双按钮，方向由按钮承担）
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

  // ── 2026-08-17 对齐 web：账户快照 + 自选/买点 + 清仓/打分（异步加载不阻塞主数据）──
  AccountSnapshotDto? _account;
  List<WatchlistItemDto> _watchlist = [];
  List<BuyPointDto> _buyPoints = [];
  List<SoldTradeDto> _sold = [];
  List<SoldScoreDto> _soldScores = [];
  bool _auxLoading = false; // 次级数据加载中（自选/清仓，不转圈整页）

  // ── 通道 A：NL 输入条 ──
  final _nlCtrl = TextEditingController();
  bool _parsing = false;

  // ── 确认卡片（NL 解析结果草稿，可编辑回显）──
  ParseTradeResponse? _draft;
  final _confirmVolumeCtrl = TextEditingController();
  final _confirmPriceCtrl = TextEditingController();
  String _confirmDirection = 'BUY';
  bool _confirming = false;

  // ── RFC 20260816 P0：止损位 + 买点类型（BUY 必填，SELL 隐藏）──
  final _confirmStopLossCtrl = TextEditingController();
  String _confirmBuyPoint = 'B1';

  // ── 通道 B：精确表单（3 字段 + 双按钮 + 止损/买点）──
  bool _showForm = false;
  final _symbolCtrl = TextEditingController();
  final _priceCtrl = TextEditingController();
  final _volumeCtrl = TextEditingController();
  final _stopLossCtrl = TextEditingController();
  String _buyPoint = 'B1';
  bool _submitting = false;

  /// 买点类型白名单（RFC 20260816 §2.1：B1/B2/B3/SB1/暴力特噗/深水炸弹/单针/其他）。
  static const List<String> _buyPointOptions = [
    'B1', 'B2', 'B3', 'SB1', '暴力特噗', '深水炸弹', '单针', '其他',
  ];

  // ── 复盘横幅（P1：has-activity 检测 + 生成）──
  bool _hasActivity = false;
  bool _bannerDismissed = false;
  bool _reviewGenerated = false;
  bool _reviewing = false;
  ReviewResponse? _lastReview;

  @override
  void initState() {
    super.initState();
    _loadAll();
  }

  @override
  void dispose() {
    _nlCtrl.dispose();
    _confirmVolumeCtrl.dispose();
    _confirmPriceCtrl.dispose();
    _confirmStopLossCtrl.dispose();
    _symbolCtrl.dispose();
    _priceCtrl.dispose();
    _volumeCtrl.dispose();
    _stopLossCtrl.dispose();
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
      _checkActivity(); // 复盘横幅检测（静默，失败不影响页面）
      _loadAux();       // 账户/自选/清仓/打分（异步，不阻塞主数据）
    } catch (e) {
      if (!mounted) return;
      setState(() { _error = _extractApiError(e); _loading = false; }); // #113 人话
    }
  }

  /// 次级数据（账户快照/自选/买点/清仓/打分）：独立拉取，失败静默不影响主数据。
  Future<void> _loadAux() async {
    if (_auxLoading) return;
    _auxLoading = true;
    try {
      final acct = await widget.api.getAccount();
      final watch = await widget.api.getWatchlist();
      final bps = await widget.api.getBuyPoints();
      final sold = await widget.api.getSold();
      final scores = await widget.api.getSoldScore();
      if (!mounted) return;
      setState(() {
        _account = acct;
        _watchlist = watch;
        _buyPoints = bps;
        _sold = sold;
        _soldScores = scores;
      });
    } catch (_) {
      // 次级数据失败静默（账户卡退回组合快照口径）
    } finally {
      _auxLoading = false;
    }
  }

  /// 复盘横幅检测：GET /trading/has-activity → 今日有交易则出现横幅。
  Future<void> _checkActivity() async {
    try {
      final has = await widget.api.hasTradingActivity();
      if (!mounted) return;
      setState(() => _hasActivity = has);
    } catch (_) {
      // 检测失败不阻塞页面（横幅不出现）
    }
  }

  void _showSnack(String msg, Color color) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(msg, style: TextStyle(color: color, fontSize: 13)),
        backgroundColor: AppColors.darkSurface2,
        duration: const Duration(seconds: 3),
      ),
    );
  }

  // ────────────────────────────────────────────────────────────
  // 校验（RFC §3 V 规则）：前端人话提示，后端铁律兜底
  // ────────────────────────────────────────────────────────────

  /// V1：标的非空 + 格式（6 位数字 或 2-6 个汉字/字母）。
  String? _validateSymbol(String symbol) {
    final s = symbol.trim();
    if (s.isEmpty) return '请输入股票代码或名称';
    final ok = RegExp(r'^(\d{6}|[A-Za-z\u4e00-\u9fa5]{2,6})$').hasMatch(s);
    if (!ok) return '标的格式不对：6 位代码（如 600519）或股票名称';
    return null;
  }

  /// V4：价格必填、数字、>0、≤1e7。
  String? _validatePrice(double? price) {
    if (price == null) return '请输入成交价格';
    if (price <= 0) return '价格必须大于 0';
    if (price > 1e7) return '价格过大，请检查是否多打了 0';
    return null;
  }

  /// V5：数量必填、整数、>0、≤1e7。
  String? _validateVolume(int? volume) {
    if (volume == null) return '请输入数量';
    if (volume <= 0) return '数量必须大于 0';
    if (volume > 1e7) return '数量过大，请检查是否多打了 0';
    return null;
  }

  /// V6：SELL 前端预检——未持有不可卖、超持仓拦截。
  String? _validateSell(String symbol, int volume) {
    final pos = _positions.where((p) => p.symbol == symbol).firstOrNull;
    if (pos == null) return '未持有 $symbol，无法卖出';
    if (volume > pos.quantity) return '卖出 $volume 股超过持仓 ${pos.quantity} 股';
    return null;
  }

  /// V7（RFC 20260816）：BUY 止损位必填、>0、不过大（对齐后端 400 语义，人话）。
  String? _validateStopLoss(double? stopLoss) {
    if (stopLoss == null) return '买入请填止损位，跌破就按计划处理';
    if (stopLoss <= 0) return '止损位必须大于 0';
    if (stopLoss > 1e7) return '止损位过大，请检查是否多打了 0';
    return null;
  }

  /// V8（RFC 20260816）：买点类型白名单（下拉只会产出白名单值，此处兜底后端回填/异常值）。
  String? _validateBuyPoint(String buyPoint) {
    if (!_buyPointOptions.contains(buyPoint)) return '买点类型不认识，选一个吧';
    return null;
  }

  // ────────────────────────────────────────────────────────────
  // 记录交易（通道 A：NL 解析 → 确认卡）
  // ────────────────────────────────────────────────────────────

  /// 通道 A 第一步：一句话 → POST /trading/trades/parse。
  /// matched=true → 确认卡回显；matched=false（V10）→ 提示 + 自动展开精确表单。
  Future<void> _parseTrade() async {
    final text = _nlCtrl.text.trim();
    if (text.isEmpty) {
      _showSnack('先输入一句，比如：买了 1000 股京东方 @5.2', AppColors.darkGrey4);
      return;
    }
    setState(() => _parsing = true);
    try {
      final parsed = await widget.api.parseTrade(text);
      if (!mounted) return;
      setState(() => _parsing = false);
      if (parsed.matched) {
        _confirmVolumeCtrl.text = parsed.volume?.toString() ?? '';
        _confirmPriceCtrl.text = parsed.price?.toStringAsFixed(2) ?? '';
        // RFC 20260816：NL 带回止损/买点 → 回填确认卡（用户可改；异常值兜底 B1）
        _confirmStopLossCtrl.text = parsed.stopLossPrice?.toStringAsFixed(2) ?? '';
        final buyPoint =
            _buyPointOptions.contains(parsed.buyPoint) ? parsed.buyPoint! : 'B1';
        setState(() {
          _draft = parsed;
          _confirmDirection = parsed.direction == 'SELL' ? 'SELL' : 'BUY';
          _confirmBuyPoint = buyPoint;
        });
      } else {
        // V10：没听懂 → 人话提示 + 自动展开精确表单（没有死路）
        _showSnack('没听懂这句交易，试试：买了 X 股 名称 @价格', AppColors.darkOrange);
        setState(() => _showForm = true);
      }
    } catch (e) {
      if (!mounted) return;
      setState(() => _parsing = false);
      _showSnack('解析失败: ${_extractApiError(e)}', AppColors.darkOrange);
    }
  }

  /// 通道 A 第二步：确认卡片 → 校验（AI 错误在此拦截）→ POST /trading/trades。
  Future<void> _confirmTrade() async {
    final draft = _draft;
    if (draft == null) return;
    final price = double.tryParse(_confirmPriceCtrl.text.trim());
    final volume = int.tryParse(_confirmVolumeCtrl.text.trim());
    final priceErr = _validatePrice(price);
    if (priceErr != null) { _showSnack(priceErr, AppColors.darkOrange); return; }
    final volErr = _validateVolume(volume);
    if (volErr != null) { _showSnack(volErr, AppColors.darkOrange); return; }
    // RFC 20260816：BUY 必填止损位 + 买点（对齐后端 400 语义，前端先拦人话）
    double? stopLoss;
    if (_confirmDirection == 'BUY') {
      stopLoss = double.tryParse(_confirmStopLossCtrl.text.trim());
      final slErr = _validateStopLoss(stopLoss);
      if (slErr != null) { _showSnack(slErr, AppColors.darkOrange); return; }
      final bpErr = _validateBuyPoint(_confirmBuyPoint);
      if (bpErr != null) { _showSnack(bpErr, AppColors.darkOrange); return; }
    }
    if (_confirmDirection == 'SELL') {
      final sellErr = _validateSell(draft.symbol, volume!);
      if (sellErr != null) { _showSnack(sellErr, AppColors.darkOrange); return; }
    }
    setState(() => _confirming = true);
    try {
      await widget.api.recordTrade(
        symbol: draft.symbol,
        name: draft.name,
        direction: _confirmDirection,
        price: price!,
        volume: volume!,
        stopLossPrice: _confirmDirection == 'BUY' ? stopLoss : null,
        buyPoint: _confirmDirection == 'BUY' ? _confirmBuyPoint : null,
      );
      if (!mounted) return;
      _onTradeSuccess(
        symbol: draft.symbol, name: draft.name,
        direction: _confirmDirection, price: price, volume: volume,
      );
    } catch (e) {
      if (!mounted) return;
      setState(() => _confirming = false);
      _showSnack('记录失败: ${_extractApiError(e)}', AppColors.darkOrange);
    }
  }

  /// 通道 B：精确表单提交。direction 由按钮承担（买入/卖出），结构上不可能漏选。
  Future<void> _submitExactTrade(String direction) async {
    final symbol = _symbolCtrl.text.trim();
    final price = double.tryParse(_priceCtrl.text.trim());
    final volume = int.tryParse(_volumeCtrl.text.trim());
    final symErr = _validateSymbol(symbol);
    if (symErr != null) { _showSnack(symErr, AppColors.darkOrange); return; }
    final priceErr = _validatePrice(price);
    if (priceErr != null) { _showSnack(priceErr, AppColors.darkOrange); return; }
    final volErr = _validateVolume(volume);
    if (volErr != null) { _showSnack(volErr, AppColors.darkOrange); return; }
    // RFC 20260816：BUY 必填止损位 + 买点（SELL 时这两项不参与校验、不发送）
    double? stopLoss;
    if (direction == 'BUY') {
      stopLoss = double.tryParse(_stopLossCtrl.text.trim());
      final slErr = _validateStopLoss(stopLoss);
      if (slErr != null) { _showSnack(slErr, AppColors.darkOrange); return; }
      final bpErr = _validateBuyPoint(_buyPoint);
      if (bpErr != null) { _showSnack(bpErr, AppColors.darkOrange); return; }
    }
    if (direction == 'SELL') {
      final sellErr = _validateSell(symbol, volume!);
      if (sellErr != null) { _showSnack(sellErr, AppColors.darkOrange); return; }
    }
    setState(() => _submitting = true);
    try {
      await widget.api.recordTrade(
        symbol: symbol,
        direction: direction,
        price: price!,
        volume: volume!,
        stopLossPrice: direction == 'BUY' ? stopLoss : null,
        buyPoint: direction == 'BUY' ? _buyPoint : null,
      );
      if (!mounted) return;
      _onTradeSuccess(
        symbol: symbol, name: '',
        direction: direction, price: price, volume: volume,
      );
    } catch (e) {
      if (!mounted) return;
      setState(() => _submitting = false);
      _showSnack('记录失败: ${_extractApiError(e)}', AppColors.darkOrange);
    }
  }

  /// 交易成功公共出口：人话 SnackBar + 清空记录区 + 持仓静默刷新 + 复盘横幅触发。
  void _onTradeSuccess({
    required String symbol, required String name,
    required String direction, required double price, required int volume,
  }) {
    final dirLabel = direction == 'BUY' ? '买入' : '卖出';
    final label = name.isEmpty ? symbol : name;
    _showSnack('已$dirLabel $label $volume 股 @${_fmtPrice(price)}', AppColors.darkGreen);
    setState(() {
      _draft = null;
      _confirmVolumeCtrl.clear();
      _confirmPriceCtrl.clear();
      _confirmStopLossCtrl.clear();
      _confirmBuyPoint = 'B1';
      _nlCtrl.clear();
      _stopLossCtrl.clear();
      _buyPoint = 'B1';
      _showForm = false;
      _submitting = false;
      _confirming = false;
      _reviewGenerated = false; // 有交易 → 复盘横幅重新可生成
    });
    _refresh();       // 持仓卡即时刷新（无整页 loading）
    _checkActivity(); // 横幅即时出现（"交易完回来就看到"）
  }

  // ────────────────────────────────────────────────────────────
  // 持仓建议（阿呆自然对话，无第三视角；不做执行按钮）
  // ────────────────────────────────────────────────────────────

  /// 点按资产卡 → 阿呆建议弹层（POST /trading/advice）。
  void _showAdvice(PositionItem p) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: AppColors.darkSurface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (_) => _AdviceSheet(
        api: widget.api,
        position: p,
        onWebGuide: _showWebGuide,
      ),
    );
  }

  /// 「详细管理去 web」引导（RFC §5 三处统一入口）。
  void _showWebGuide() {
    showModalBottomSheet(
      context: context,
      backgroundColor: AppColors.darkSurface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (sheetCtx) => Padding(
        padding: const EdgeInsets.fromLTRB(20, 18, 20, 24),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Icon(Icons.open_in_new, size: 16, color: AppColors.darkGreen),
            const SizedBox(width: 8),
            Text('详细管理去电脑端', style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
          ]),
          const SizedBox(height: 10),
          Text('批量导入、持仓编辑、历史明细、K线等详细管理请在电脑端打开交易页。手机端负责日常记录和阿呆建议。',
              style: TextStyle(fontSize: 12, height: 1.6, color: AppColors.darkGrey3)),
          const SizedBox(height: 18),
          SizedBox(
            width: double.infinity,
            height: 38,
            child: ElevatedButton(
              onPressed: () => Navigator.pop(sheetCtx),
              style: ElevatedButton.styleFrom(
                backgroundColor: AppColors.darkGreen.withValues(alpha: 0.15),
                foregroundColor: AppColors.darkGreen,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
              ),
              child: const Text('知道了', style: TextStyle(fontWeight: FontWeight.w500)),
            ),
          ),
        ]),
      ),
    );
  }

  // ────────────────────────────────────────────────────────────
  // build
  // ────────────────────────────────────────────────────────────

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
          // #102 交易系统反哺入口：生成复盘（被动通道，主动通道见复盘横幅）
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
                if (_hasActivity && !_bannerDismissed) ...[
                  const SizedBox(height: 10),
                  _buildReviewBanner(),
                ],
                const SizedBox(height: 16),
                _buildQuickRecord(),
                if (_draft != null) ...[
                  const SizedBox(height: 10),
                  _buildConfirmCard(),
                ],
                if (_showForm) ...[
                  const SizedBox(height: 10),
                  _buildExactForm(),
                ],
                const SizedBox(height: 16),
                Row(children: [
                  _sectionTitle(_positions.isEmpty ? '持仓' : '持仓明细'),
                  if (_positions.isNotEmpty) ...[
                    const SizedBox(width: 6),
                    Text('共 ${_positions.length} 只', style: TextStyle(fontSize: 10, color: AppColors.darkGrey5)),
                  ],
                  const Spacer(),
                  _buildManageHint(),
                ]),
                const SizedBox(height: 8),
                _buildPositionCards(),
                // 2026-08-17 对齐 web：自选股（买点信号）+ 清仓股（复盘打分）——只读展示
                const SizedBox(height: 16),
                _buildWatchlistSection(),
                const SizedBox(height: 16),
                _buildSoldSection(),
              ],
            ),
    );
  }

  // ── 自选股 + 买点信号（2026-08-17 对齐 web，只读）──

  Widget _buildWatchlistSection() {
    if (_watchlist.isEmpty) return const SizedBox.shrink();
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        _sectionTitle('自选股 · 买点信号'),
        if (_buyPoints.isNotEmpty) ...[
          const SizedBox(width: 6),
          Text('${_buyPoints.length} 只到买点', style: TextStyle(fontSize: 10, color: AppColors.darkRed)),
        ],
      ]),
      const SizedBox(height: 6),
      ..._watchlist.map((w) {
        final bp = _buyPoints.where((b) => b.symbol == w.symbol).toList();
        return Container(
          margin: const EdgeInsets.only(bottom: 6),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          decoration: BoxDecoration(
            color: AppColors.darkSurface2,
            borderRadius: BorderRadius.circular(8),
          ),
          child: Row(children: [
            Expanded(
              child: Text('${w.name} ${w.symbol}',
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 12, color: AppColors.darkGrey2)),
            ),
            if (bp.isNotEmpty)
              Text(bp.map((b) => '${b.buyPoint} ${(b.score * 100).toStringAsFixed(0)}%').join('、'),
                  style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.darkRed))
            else
              Text(w.signal.isEmpty ? '—' : w.signal,
                  style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
          ]),
        );
      }),
    ]);
  }

  // ── 清仓股 + 复盘打分（2026-08-17 对齐 web，只读）──

  Widget _buildSoldSection() {
    if (_sold.isEmpty) return const SizedBox.shrink();
    // D2 纪律统计（对齐 web）：盈/亏 + R66/R53
    final total = _sold.length;
    final profit = _sold.where((s) => s.holdPnlPct >= 0).length;
    final r66 = _sold.where((s) => s.verdict.contains('R66')).length;
    final r53 = _sold.where((s) => s.verdict.contains('R53')).length;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        _sectionTitle('清仓复盘'),
        const SizedBox(width: 6),
        Text('$total 笔 · 盈 $profit / 亏 ${total - profit}'
            '${r66 > 0 ? ' · 扛单$r66' : ''}${r53 > 0 ? ' · 短亏$r53' : ''}',
            style: TextStyle(fontSize: 10, color: AppColors.darkGrey5)),
      ]),
      const SizedBox(height: 6),
      ..._sold.take(20).map((s) {
        final sc = _soldScores.where((x) => x.symbol == s.symbol).toList();
        final score = sc.isEmpty ? null : sc.first;
        return Container(
          margin: const EdgeInsets.only(bottom: 6),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          decoration: BoxDecoration(
            color: AppColors.darkSurface2,
            borderRadius: BorderRadius.circular(8),
          ),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Expanded(
                child: Text('${s.name} ${s.symbol}',
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 12, color: AppColors.darkGrey2)),
              ),
              Text('${s.holdPnlPct >= 0 ? '+' : ''}${s.holdPnlPct.toStringAsFixed(1)}%',
                  style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600,
                      color: s.holdPnlPct >= 0 ? AppColors.darkRed : AppColors.darkGreen)),
            ]),
            const SizedBox(height: 3),
            Text(s.verdict.isEmpty ? '（未判对错）' : s.verdict,
                style: TextStyle(fontSize: 10, color: AppColors.darkGrey5)),
            if (score?.totalScore != null)
              Text('买点 ${score!.buyPointScore ?? '—'} · 执行 ${score.executionScore ?? '—'} · 总分 ${score.totalScore!.toStringAsFixed(0)}',
                  style: const TextStyle(fontSize: 10, color: AppColors.darkOrange)),
          ]),
        );
      }),
      if (_sold.length > 20)
        Text('仅显示最近 20 笔，完整请用桌面端', style: TextStyle(fontSize: 10, color: AppColors.darkGrey5)),
    ]);
  }

  Widget _sectionTitle(String title) {
    return Text(title, style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.darkGrey5));
  }

  // ── 记录区：NL 输入条（默认主入口）──

  Widget _buildQuickRecord() {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
        decoration: BoxDecoration(
          color: AppColors.darkSurface2,
          borderRadius: BorderRadius.circular(10),
        ),
        child: Row(children: [
          Icon(Icons.chat_bubble_outline, size: 16, color: AppColors.darkGrey5),
          const SizedBox(width: 8),
          Expanded(
            child: TextField(
              controller: _nlCtrl,
              style: const TextStyle(fontSize: 13, color: AppColors.darkGrey2),
              decoration: const InputDecoration(
                isDense: true,
                hintText: '说一句，比如：买了 1000 股京东方 @5.2',
                hintStyle: TextStyle(fontSize: 12, color: AppColors.darkGrey6),
                border: InputBorder.none,
              ),
              onSubmitted: (_) => _parseTrade(),
            ),
          ),
          GestureDetector(
            onTap: _parsing ? null : _parseTrade,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
              decoration: BoxDecoration(
                color: AppColors.darkGreen.withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(6),
              ),
              child: _parsing
                  ? const SizedBox(width: 14, height: 14,
                      child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.darkGreen))
                  : const Text('解析', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w500, color: AppColors.darkGreen)),
            ),
          ),
        ]),
      ),
      const SizedBox(height: 4),
      GestureDetector(
        onTap: () => setState(() => _showForm = !_showForm),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
          child: Text(_showForm ? '收起精确填写' : '精确填写',
              style: TextStyle(fontSize: 11, color: AppColors.darkGrey5, decoration: TextDecoration.underline)),
        ),
      ),
    ]);
  }

  // ── 确认卡片：NL 结果回显（AI 错误在此拦截，可改）──

  Widget _buildConfirmCard() {
    final draft = _draft!;
    final isBuy = _confirmDirection == 'BUY';
    final dirColor = isBuy ? AppColors.darkRed : AppColors.darkGreen;
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: AppColors.darkGreen.withValues(alpha: 0.25), width: 0.5),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
            decoration: BoxDecoration(
              color: dirColor.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(5),
              border: Border.all(color: dirColor.withValues(alpha: 0.35), width: 0.5),
            ),
            child: Text(isBuy ? '买入' : '卖出',
                style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: dirColor)),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text('${draft.name.isEmpty ? draft.symbol : draft.name} (${draft.symbol})',
                style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
          ),
        ]),
        const SizedBox(height: 10),
        Row(children: [
          Expanded(child: _formField('数量', _confirmVolumeCtrl, keyboardType: TextInputType.number, hintText: '股数')),
          const SizedBox(width: 10),
          Expanded(child: _formField('价格', _confirmPriceCtrl, keyboardType: TextInputType.number, hintText: '成交单价',
              onChanged: (v) => _autoStopLoss(v, _confirmStopLossCtrl))),
          const SizedBox(width: 10),
          _dirChip('BUY', '买'),
          const SizedBox(width: 4),
          _dirChip('SELL', '卖'),
        ]),
        // RFC 20260816：BUY 显示止损位/买点（NL 带回则回填，可改）；SELL 隐藏
        if (isBuy) ...[
          const SizedBox(height: 10),
          Row(children: [
            Expanded(child: _formField('止损位', _confirmStopLossCtrl, keyboardType: TextInputType.number, hintText: '止损价')),
            const SizedBox(width: 10),
            Expanded(child: _buyPointField('买点', _confirmBuyPoint, (v) => setState(() => _confirmBuyPoint = v))),
          ]),
        ],
        const SizedBox(height: 12),
        Row(children: [
          Expanded(
            child: SizedBox(
              height: 36,
              child: ElevatedButton(
                onPressed: _confirming ? null : _confirmTrade,
                style: ElevatedButton.styleFrom(
                  backgroundColor: AppColors.darkGreen.withValues(alpha: 0.2),
                  foregroundColor: AppColors.darkGreen,
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                ),
                child: Text(_confirming ? '提交中...' : '确认记录', style: const TextStyle(fontWeight: FontWeight.w500)),
              ),
            ),
          ),
          const SizedBox(width: 6),
          TextButton(
            onPressed: () => setState(() => _draft = null),
            child: const Text('取消', style: TextStyle(fontSize: 13, color: AppColors.darkGrey4)),
          ),
        ]),
      ]),
    );
  }

  // ── 精确表单：标的 | 价格 | 数量 + 止损位/买点（RFC 20260816）+ 底部 [买入][卖出] ──

  Widget _buildExactForm() {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        _formField('标的（代码或名称）', _symbolCtrl, hintText: '如 600519 或 贵州茅台'),
        const SizedBox(height: 8),
        Row(children: [
          Expanded(child: _formField('价格', _priceCtrl, keyboardType: TextInputType.number, hintText: '成交单价',
              onChanged: (v) => _autoStopLoss(v, _stopLossCtrl))),
          const SizedBox(width: 10),
          Expanded(child: _formField('数量', _volumeCtrl, keyboardType: TextInputType.number, hintText: '股数')),
        ]),
        // RFC 20260816：买入计划字段（BUY 必填止损；SELL 提交时忽略不发送）
        const SizedBox(height: 8),
        _formField('止损位', _stopLossCtrl, keyboardType: TextInputType.number, hintText: '默认按买入价 -7%，可改'),
        const SizedBox(height: 8),
        _buyPointField('买点', _buyPoint, (v) => setState(() => _buyPoint = v)),
        const SizedBox(height: 12),
        Row(children: [
          Expanded(child: _tradeButton('买入', 'BUY')),
          const SizedBox(width: 10),
          Expanded(child: _tradeButton('卖出', 'SELL')),
        ]),
      ]),
    );
  }

  /// 买点类型下拉（RFC 20260816 §2.1 白名单，默认 B1）。
  Widget _buyPointField(String label, String value, ValueChanged<String> onChanged) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(label, style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
      const SizedBox(height: 4),
      Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 2),
        decoration: BoxDecoration(
          color: AppColors.darkBg,
          borderRadius: BorderRadius.circular(6),
          border: Border.all(color: AppColors.darkBorder, width: 0.5),
        ),
        child: DropdownButtonHideUnderline(
          child: DropdownButton<String>(
            value: value,
            isExpanded: true,
            dropdownColor: AppColors.darkSurface2,
            style: const TextStyle(fontSize: 13, color: AppColors.darkGrey2),
            icon: Icon(Icons.arrow_drop_down, size: 18, color: AppColors.darkGrey5),
            items: _buyPointOptions.map((p) => DropdownMenuItem<String>(
              value: p,
              child: Text(p, style: const TextStyle(fontSize: 13, color: AppColors.darkGrey2)),
            )).toList(),
            onChanged: (v) { if (v != null) onChanged(v); },
          ),
        ),
      ),
    ]);
  }

  /// 底部双按钮：买入=红、卖出=绿（A股红涨绿跌，与方向徽标一致）。
  Widget _tradeButton(String label, String direction) {
    final isBuy = direction == 'BUY';
    final color = isBuy ? AppColors.darkRed : AppColors.darkGreen;
    return SizedBox(
      height: 38,
      child: ElevatedButton(
        onPressed: _submitting ? null : () => _submitExactTrade(direction),
        style: ElevatedButton.styleFrom(
          backgroundColor: color.withValues(alpha: 0.16),
          foregroundColor: color,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
          side: BorderSide(color: color.withValues(alpha: 0.35), width: 0.5),
        ),
        child: Text(_submitting ? '提交中...' : label, style: const TextStyle(fontWeight: FontWeight.w600)),
      ),
    );
  }

  /// 默认止损：买入价 -7%（用户 2026-08-17 设定，对齐 web）——手动填过就不再覆盖。
  void _autoStopLoss(String priceText, TextEditingController stopLossCtrl) {
    if (stopLossCtrl.text.trim().isNotEmpty) return;
    final price = double.tryParse(priceText.trim());
    if (price != null && price > 0) {
      stopLossCtrl.text = (price * 0.93).toStringAsFixed(2);
    }
  }

  Widget _formField(String label, TextEditingController ctrl,
      {TextInputType keyboardType = TextInputType.text, String? hintText, ValueChanged<String>? onChanged}) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(label, style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
      const SizedBox(height: 4),
      TextField(
        controller: ctrl,
        keyboardType: keyboardType,
        onChanged: onChanged,
        style: const TextStyle(fontSize: 13, color: AppColors.darkGrey2),
        decoration: InputDecoration(
          isDense: true,
          contentPadding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
          filled: true,
          fillColor: AppColors.darkBg,
          hintText: hintText,
          hintStyle: const TextStyle(fontSize: 11, color: AppColors.darkGrey6),
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

  /// 确认卡内方向小切换（买/卖）。
  Widget _dirChip(String value, String label) {
    final selected = _confirmDirection == value;
    return GestureDetector(
      onTap: () => setState(() => _confirmDirection = value),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
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

  // ── 快照卡 ──

  Widget _buildSnapshotCard() {
    final a = _account;
    final s = _snapshot;
    final hasAccount = a != null && a.assets > 0;
    // 2026-08-17 对齐 web：券商口径账户快照（总资产/可用/可取/市值/当日盈亏/总盈亏=资产-本金）
    final totalAssets = hasAccount ? a.assets : (s?.totalValue ?? 0) + (s?.cashBalance ?? 0);
    final totalPnl = hasAccount ? a.totalPnl : (s?.totalPnl ?? 0);
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('总资产', style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
              const SizedBox(height: 2),
              Text(_fmtMoney(totalAssets),
                  style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w700, color: AppColors.darkGrey1)),
            ]),
            Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
              Text('总盈亏', style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
              const SizedBox(height: 2),
              Text('${totalPnl >= 0 ? '+' : ''}${_fmtMoney(totalPnl)}',
                  style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700,
                      color: totalPnl >= 0 ? AppColors.darkRed : AppColors.darkGreen)),
            ]),
          ],
        ),
        const SizedBox(height: 10),
        Wrap(spacing: 14, runSpacing: 6, children: [
          _snapshotItem('可用', _fmtMoney(hasAccount ? a.available : 0)),
          _snapshotItem('可取', _fmtMoney(hasAccount ? a.withdrawable : 0)),
          _snapshotItem('市值', _fmtMoney(hasAccount ? a.marketValue : (s?.totalValue ?? 0))),
          _snapshotItem('当日盈亏', '${(hasAccount ? a.todayPnl : 0) >= 0 ? '+' : ''}${_fmtMoney(hasAccount ? a.todayPnl : 0)}',
              (hasAccount ? a.todayPnl : 0) >= 0 ? AppColors.darkRed : AppColors.darkGreen),
          if (hasAccount && a.principal > 0)
            _snapshotItem('本金', _fmtMoney(a.principal)),
        ]),
      ]),
    );
  }

  Widget _snapshotItem(String label, String value, [Color? color]) {
    return Row(mainAxisSize: MainAxisSize.min, children: [
      Text(value, style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: color ?? AppColors.darkGrey3)),
      const SizedBox(width: 4),
      Text(label, style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
    ]);
  }

  // ── 持仓区：资产卡（盈亏大字为主角）──

  Widget _buildPositionCards() {
    if (_positions.isEmpty) return _buildEmptyPositions();
    return Column(
      children: [
        ..._positions.map((p) => Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: _buildPositionCard(p),
            )),
      ],
    );
  }

  Widget _buildPositionCard(PositionItem p) {
    final isGain = p.pnl > 0.01;   // 盈=红
    final isLoss = p.pnl < -0.01;  // 亏=绿
    final pnlColor = isGain
        ? AppColors.darkRed
        : (isLoss ? AppColors.darkGreen : AppColors.darkGrey3);
    final pnlStr = '${p.pnl >= 0 ? '+' : ''}${_fmtMoney(p.pnl)}';
    final pctStr = '${p.pnlPercent >= 0 ? '+' : ''}${p.pnlPercent.toStringAsFixed(1)}%';
    return GestureDetector(
      onTap: () => _showAdvice(p),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        decoration: BoxDecoration(
          color: AppColors.darkSurface2,
          borderRadius: BorderRadius.circular(10),
        ),
        child: Column(children: [
          Row(children: [
            Expanded(
              child: Row(children: [
                Flexible(
                  child: Text(p.name.isEmpty ? p.symbol : p.name,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
                ),
                const SizedBox(width: 6),
                Text(p.symbol, style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
              ]),
            ),
            Text(pnlStr, style: TextStyle(fontSize: 19, fontWeight: FontWeight.w700, color: pnlColor)),
          ]),
          const SizedBox(height: 6),
          Row(children: [
            Expanded(
              child: Text(
                '${p.quantity}股 · 成本 ${_fmtPrice(p.avgCost)} · 现价 ${_fmtPrice(p.currentPrice)}'
                '${p.stopLossPrice != null ? ' · 止损 ${_fmtPrice(p.stopLossPrice!)}' : ' · 未设止损'}',
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: 11,
                    color: p.stopLossPrice == null ? AppColors.darkOrange : AppColors.darkGrey5),
              ),
            ),
            Text(pctStr, style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: pnlColor)),
          ]),
        ]),
      ),
    );
  }

  Widget _buildEmptyPositions() {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 28, horizontal: 16),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Column(children: [
        Icon(Icons.account_balance_wallet_outlined, size: 26, color: AppColors.darkGrey5),
        const SizedBox(height: 8),
        Text('暂无持仓', style: TextStyle(fontSize: 13, color: AppColors.darkGrey4)),
        const SizedBox(height: 6),
        Text('在上方记录第一笔交易，阿呆就能开始给你建议',
            style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
        const SizedBox(height: 10),
        GestureDetector(
          onTap: _showWebGuide,
          child: Text('有历史持仓？到电脑端导入',
              style: TextStyle(fontSize: 12, color: AppColors.darkGreen, decoration: TextDecoration.underline)),
        ),
      ]),
    );
  }

  Widget _buildManageHint() {
    return GestureDetector(
      onTap: _showWebGuide,
      child: Row(children: [
        Icon(Icons.open_in_new, size: 13, color: AppColors.darkGrey5),
        const SizedBox(width: 3),
        Text('管理', style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
      ]),
    );
  }

  // ── 复盘横幅（P1 主动通道）──

  Widget _buildReviewBanner() {
    final generated = _reviewGenerated && _lastReview != null;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: AppColors.darkGreen.withValues(alpha: 0.3), width: 0.5),
      ),
      child: Row(children: [
        Icon(generated ? Icons.check_circle_outline : Icons.auto_awesome,
            size: 16, color: AppColors.darkGreen),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            generated ? '今日复盘已生成 ✓' : '今日有交易 · 生成今日复盘？',
            style: TextStyle(fontSize: 12, color: AppColors.darkGrey2),
          ),
        ),
        const SizedBox(width: 8),
        GestureDetector(
          onTap: _reviewing
              ? null
              : (generated
                  ? () => showDialog(context: context, builder: (_) => _buildReviewDialog(_lastReview!))
                  : _showReview),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            decoration: BoxDecoration(
              color: AppColors.darkGreen.withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(6),
            ),
            child: _reviewing
                ? const SizedBox(width: 14, height: 14,
                    child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.darkGreen))
                : Text(generated ? '查看复盘' : '生成复盘',
                    style: TextStyle(fontSize: 12, fontWeight: FontWeight.w500, color: AppColors.darkGreen)),
          ),
        ),
        const SizedBox(width: 4),
        GestureDetector(
          onTap: () => setState(() => _bannerDismissed = true),
          child: Icon(Icons.close, size: 16, color: AppColors.darkGrey5),
        ),
      ]),
    );
  }

  // ── 错误态 ──

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

  // ── 复盘（被动入口 + 横幅共用）──

  /// 生成今日复盘 → 弹窗展示（交易系统反哺可达）。
  Future<void> _showReview() async {
    setState(() => _reviewing = true);
    try {
      final review = await widget.api.generateReview();
      if (!mounted) return;
      setState(() {
        _reviewing = false;
        _lastReview = review;
        _reviewGenerated = true;
        _hasActivity = true;
      });
      showDialog(
        context: context,
        builder: (_) => _buildReviewDialog(review),
      );
    } catch (e) {
      if (!mounted) return;
      setState(() => _reviewing = false);
      _showSnack('复盘生成失败: ${_extractApiError(e)}', AppColors.darkOrange);
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
      _showSnack(result.message.isEmpty ? '已写入入库候选' : result.message, AppColors.darkGrey2);
    } catch (e) {
      if (!mounted) return;
      _showSnack('反哺入库失败: ${_extractApiError(e)}', AppColors.darkOrange);
    }
  }
}

// ────────────────────────────────────────────────────────────────
// 阿呆建议弹层：逐票建议（买入/持有/减仓/清仓 + 理由 + 依据规则号）。
// 无第三视角：阿呆自然对话输出；不做平仓/减仓执行按钮。
// ────────────────────────────────────────────────────────────────

class _AdviceSheet extends StatefulWidget {
  final ApiService api;
  final PositionItem position;
  final VoidCallback onWebGuide;

  const _AdviceSheet({
    required this.api,
    required this.position,
    required this.onWebGuide,
  });

  @override
  State<_AdviceSheet> createState() => _AdviceSheetState();
}

class _AdviceSheetState extends State<_AdviceSheet> {
  AdviceResponse? _advice;
  bool _loading = true;
  String? _error;
  bool _showRules = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final advice = await widget.api.getAdvice();
      if (!mounted) return;
      setState(() { _advice = advice; _loading = false; });
    } catch (e) {
      if (!mounted) return;
      setState(() { _error = e.toString(); _loading = false; });
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = widget.position;
    return Padding(
      padding: EdgeInsets.fromLTRB(20, 16, 20, 20 + MediaQuery.of(context).viewInsets.bottom),
      child: SingleChildScrollView(
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Text('阿呆说 · ${p.name.isEmpty ? p.symbol : p.name}',
                style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
            const Spacer(),
            GestureDetector(
              onTap: () => Navigator.pop(context),
              child: const Icon(Icons.close, size: 18, color: AppColors.darkGrey5),
            ),
          ]),
          const SizedBox(height: 4),
          Text('${p.symbol} · 持有 ${p.quantity} 股 · 成本 ${_fmtPrice(p.avgCost)} · 现价 ${_fmtPrice(p.currentPrice)}',
              style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
          const SizedBox(height: 12),
          if (_loading)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 28),
              child: Center(child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.darkGreen)),
            )
          else if (_error != null)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 12),
              child: Column(children: [
                Text('阿呆暂时说不上来', style: TextStyle(fontSize: 13, color: AppColors.darkGrey4)),
                const SizedBox(height: 8),
                GestureDetector(
                  onTap: () { setState(() { _error = null; _loading = true; }); _load(); },
                  child: Text('重试', style: TextStyle(fontSize: 13, color: AppColors.darkGreen, decoration: TextDecoration.underline)),
                ),
              ]),
            )
          else
            _buildAdviceContent(),
          const SizedBox(height: 14),
          SizedBox(
            width: double.infinity,
            height: 38,
            child: OutlinedButton(
              onPressed: () { Navigator.pop(context); widget.onWebGuide(); },
              style: OutlinedButton.styleFrom(
                foregroundColor: AppColors.darkGreen,
                side: BorderSide(color: AppColors.darkGreen.withValues(alpha: 0.4)),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
              ),
              child: const Text('管理持仓（去 web）', style: TextStyle(fontSize: 13)),
            ),
          ),
        ]),
      ),
    );
  }

  Widget _buildAdviceContent() {
    final advice = _advice!;
    final p = widget.position;
    // 逐票：按 symbol 匹配；后端未带 symbol 时按名称兜底匹配
    final items = advice.items
        .where((a) => a.symbol == p.symbol || (a.symbol.isEmpty && a.name == p.name))
        .toList();
    final allRules = items.expand((a) => a.rules).toSet().toList();
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      if (advice.summary.isNotEmpty) ...[
        _adviceBubble(advice.summary),
        const SizedBox(height: 10),
      ],
      if (items.isEmpty)
        _adviceBubble('阿呆看完了你的持仓，这只暂时没有特别要说的。')
      else
        ...items.map((a) => _adviceItemCard(a)),
      if (allRules.isNotEmpty) ...[
        const SizedBox(height: 4),
        GestureDetector(
          onTap: () => setState(() => _showRules = !_showRules),
          child: Text(_showRules ? '收起建议依据' : '查看建议依据',
              style: const TextStyle(fontSize: 12, color: AppColors.darkGreen)),
        ),
        if (_showRules) ...[
          const SizedBox(height: 8),
          ...allRules.map((r) => Padding(
                padding: const EdgeInsets.only(bottom: 4),
                child: Row(children: [
                  Icon(Icons.gavel, size: 12, color: AppColors.darkYellow),
                  const SizedBox(width: 6),
                  Text(r, style: TextStyle(fontSize: 11, color: AppColors.darkGrey3)),
                ]),
              )),
        ],
      ],
    ]);
  }

  Widget _adviceBubble(String text) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Text(text, style: const TextStyle(fontSize: 13, height: 1.6, color: AppColors.darkGrey2)),
    );
  }

  Widget _adviceItemCard(AdviceItem a) {
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          _actionBadge(a.action),
          const SizedBox(width: 8),
          Expanded(
            child: Text(a.name.isEmpty ? a.symbol : a.name,
                style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.darkGrey2)),
          ),
        ]),
        const SizedBox(height: 6),
        Text(a.advice.isEmpty ? '阿呆暂时没有具体建议。' : a.advice,
            style: const TextStyle(fontSize: 13, height: 1.5, color: AppColors.darkGrey2)),
      ]),
    );
  }

  /// 建议动作徽标：买入=红（A股红涨），减仓/清仓/卖出=绿，持有=蓝。
  Widget _actionBadge(String action) {
    final Color color;
    switch (action) {
      case '买入':
        color = AppColors.darkRed;
        break;
      case '减仓':
      case '清仓':
      case '卖出':
        color = AppColors.darkGreen;
        break;
      case '持有':
        color = AppColors.darkBlue;
        break;
      default:
        color = AppColors.darkGrey4;
    }
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.14),
        borderRadius: BorderRadius.circular(4),
      ),
      child: Text(action.isEmpty ? '建议' : action,
          style: TextStyle(fontSize: 10, fontWeight: FontWeight.w600, color: color)),
    );
  }
}

// ── 金额格式化（页面与建议弹层共用）──

String _fmtMoney(double v) {
  if (v.abs() >= 10000) return '${(v / 10000).toStringAsFixed(1)}万';
  return v.toStringAsFixed(0);
}

String _fmtPrice(double v) => v.toStringAsFixed(2);
