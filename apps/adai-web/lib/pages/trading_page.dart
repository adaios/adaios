import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';
import '../utils/trade_import_parser.dart';
import '../widgets/case_kline_chart.dart';
import '../widgets/page_header.dart';
import 'dart:async';
import 'package:file_picker/file_picker.dart';

// ── RFC 20260825 共用格式化/配色（批次弹窗 + 导入总结，独立 State 类共享） ──

/// 金额千分位（与页面账户卡同口径）：-39495.12 → -39,495.12。
String _fmtThousands(double v) {
  final neg = v < 0;
  final s = v.abs().toStringAsFixed(2);
  final parts = s.split('.');
  final buf = StringBuffer();
  final intPart = parts[0];
  for (var i = 0; i < intPart.length; i++) {
    buf.write(intPart[i]);
    final remaining = intPart.length - 1 - i;
    if (remaining > 0 && remaining % 3 == 0) buf.write(',');
  }
  return '${neg ? '-' : ''}$buf.${parts[1]}';
}

/// 整数千分位（数量列）：10000 → 10,000。
String _fmtThousandsInt(int v) {
  final s = v.abs().toString();
  final buf = StringBuffer();
  for (var i = 0; i < s.length; i++) {
    buf.write(s[i]);
    final remaining = s.length - 1 - i;
    if (remaining > 0 && remaining % 3 == 0) buf.write(',');
  }
  return '${v < 0 ? '-' : ''}$buf';
}

/// 短日期（M/d，如 8/22）：导入总结标题用——sync 窗口可能跨 10 天，成交日未必是今天。
/// 非 yyyy-MM-dd（缺省/空）原样返回，标题回落「今日操作」。
String _fmtShortDate(String yyyyMmDd) {
  if (yyyyMmDd.length < 10) return yyyyMmDd;
  final month = int.tryParse(yyyyMmDd.substring(5, 7));
  final day = int.tryParse(yyyyMmDd.substring(8, 10));
  if (month == null || day == null) return yyyyMmDd;
  return '$month/$day';
}

/// RFC 20260825：行为标注配色——亏损加仓/追高/破止损未走 = 红（纪律问题），
/// 浮盈回吐/短线超期 = 橙（提醒），短线新开 = 蓝（中性信息）。
Color _behaviorColor(String type) {
  switch (type) {
    case 'loss-avg-down':
    case 'chase-high':
    case 'stop-loss-ignored':
      return AppColors.darkRed;
    case 'giveback':
    case 'short-overdue':
      return AppColors.darkOrange;
    case 'short-new':
      return AppColors.darkBlue;
    default:
      return AppColors.darkOrange;
  }
}

/// 交易桌面形态 — web = 详细管理（RFC 20260816 §4.2）：
/// 快照 stat 卡 + DataTable 持仓（红涨绿亏 / 数字右对齐 + 逐行「编辑」）
/// + 记录交易 Dialog（止损位/买点类型/目标价/原因）+ 批量导入 + 交易历史 + 复盘历史。
/// 与 app（保持简单）分化：详细管理都在 web 端。
class TradingPage extends StatefulWidget {
  final ApiService api;
  /// 当前可见页 label（桌面壳传入）——切到交易页时自动刷新（行情/盈亏实时，2026-08-16）。
  final String currentPage;

  const TradingPage({super.key, required this.api, this.currentPage = 'trading'});

  @override
  State<TradingPage> createState() => _TradingPageState();
}

class _TradingPageState extends State<TradingPage> {
  PortfolioSnapshotResponse? _portfolio;
  List<PositionItem> _positions = [];
  List<WatchlistItemDto> _watchlist = [];
  List<BuyPointDto> _buyPoints = []; // C2 自选股买点信号（B1/B2 命中）
  List<SoldScoreDto> _soldScores = []; // D3 清仓复盘三维打分
  bool _scoreLoading = false; // P2-10 打分请求在途标记（防重叠）
  bool _auxLoading = false; // 可降级请求在途标记（自选/买点/清仓，防并发覆盖）
  int _auxGen = 0; // 代际令牌：_loadDegradable 防乱序旧响应覆盖新数据
  List<SoldTradeDto> _sold = [];
  AccountSnapshotDto? _account;
  double? _cash;
  double? _assets;
  String? _lastUpdated; // 顶部「上次更新」时间戳
  DailyTradeSummaryDto? _dailySummary; // RFC 20260822：当日交易复盘（今日 N 笔 · 时段分布）
  bool _loading = true;
  String? _error;
  bool _reviewing = false; // 复盘生成中（#102 交易系统反哺入口）
  bool _lotsDialogOpen = false; // P2-批次1：批次弹窗在途守卫——连点/双击防叠两层 dialog

  Timer? _autoRefresh;

  @override
  void initState() {
    super.initState();
    _loadAll();
    _loadRules();
    _loadCases();
    // B3（2026-08-16）定时刷新：每 30 分钟自动更新行情/盈亏（跟随交易时段节奏）
    // P3-11（2026-08-17）：IndexedStack offstage 时（切到别的页）不再空转发请求——仅当前页为交易页才刷
    // 注：P1-1 修复后 shell 传中文 label（'交易'），判断须用 label 而非插件标识 'trading'
    _autoRefresh = Timer.periodic(const Duration(minutes: 30), (_) {
      if (mounted && widget.currentPage == '交易') _loadAll();
    });
  }

  @override
  void dispose() {
    _autoRefresh?.cancel();
    super.dispose();
  }

  @override
  void didUpdateWidget(TradingPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    // 每次切到交易页 → 自动刷新（保活缓存不显示旧数据）
    // P1-1 修复：shell 传中文 label '交易'；oldWidget.currentPage 初始为默认 'trading'
    if (oldWidget.currentPage != widget.currentPage && widget.currentPage == '交易') {
      _loadAll();
    }
  }

  Future<void> _loadAll() async {
    // P2-交易8（2026-08-17）：入口首行 mounted 守卫——await 期间页面销毁不再 setState
    if (!mounted) return;
    // E2（2026-08-16）静默刷新：已有数据时刷新不闪整页 loading（首次加载才转圈）
    setState(() {
      _loading = _positions.isEmpty && _portfolio == null;
      _error = null;
    });
    // P1-交易7（2026-08-17）：致命请求（组合/持仓/账户）失败才影响页面；已有数据时保留旧数据 + 提示
    try {
      final results = await Future.wait([
        widget.api.getPortfolio(),
        widget.api.getPositions(),
        widget.api.getAccount(),
      ]);
      if (!mounted) return;
      setState(() {
        _portfolio = results[0] as PortfolioSnapshotResponse;
        _positions = (results[1] as PositionsResponse).positions;
        _account = results[2] as AccountSnapshotDto;
        // 资金区块：账户快照（资金股份查询导入，券商口径）
        _cash = _account?.cash;
        _assets = _account?.assets;
        _lastUpdated = DateTime.now().toString().substring(11, 19);
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      // 有旧数据：保留展示（静默刷新失败不整页变白），仅首载失败才错误页
      final hasData = _positions.isNotEmpty || _portfolio != null;
      if (hasData) {
        _toast('刷新失败：${_extractApiError(e)}');
        setState(() => _loading = false);
      } else {
        setState(() { _error = _extractApiError(e); _loading = false; });
      }
    }
    // 可降级请求（自选/买点/清仓/打分）：异步拉取，失败静默（显示 '—'），不阻塞主数据
    _loadDegradable();
    // RFC 20260822：当日交易复盘（今日 N 笔 · 时段分布）——纯客观，失败静默不显示
    _loadDaily();
  }

  /// RFC 20260822：当日交易复盘聚合。失败/无成交静默（不显示今日节奏行），不阻塞页面。
  Future<void> _loadDaily() async {
    try {
      final daily = await widget.api.getDailyTrades();
      if (!mounted) return;
      setState(() {
        _dailySummary = (daily != null && daily.count > 0) ? daily : null;
      });
    } catch (_) {
      // 静默：后端旧版本 / 网络抖动时页面不受影响
    }
  }

  /// 可降级请求：watchlist/buy-points/sold/sold-score（K 线重计算/数据展示），失败不打断页面。
  /// 锁 + 代际令牌（2026-08-17 走查 P2）：防并发触发时慢的旧响应覆盖新数据；
  /// 先判锁再递增——早退分支不得先递增代际，否则在途请求 finally 不复位锁 → 次级数据永久不刷新。
  Future<void> _loadDegradable() async {
    if (_auxLoading) return;
    _auxLoading = true;
    final gen = ++_auxGen;
    try {
      final watch = await widget.api.getWatchlist();
      final sold = await widget.api.getSold();
      final bps = await widget.api.getBuyPoints();
      if (!mounted || gen != _auxGen) return; // 旧代丢弃
      setState(() {
        _watchlist = watch;
        _sold = sold;
        _buyPoints = bps;
      });
    } catch (_) {
      // 自选/买点失败静默（信号列显示 —）
    } finally {
      _auxLoading = false; // 无条件复位（锁只被本请求持有，串行安全）
    }
    if (gen == _auxGen) _loadSoldScore(); // 打分独立：162 笔 K 线耗时，失败也不影响
  }

  /// D3 清仓三维打分（异步拉取，失败不打断页面——分数是参考）。
  /// P2-交易10（2026-08-17）：空列表短路（无清仓不打空请求）+ 进行中标记（避免重叠请求）
  Future<void> _loadSoldScore() async {
    if (_sold.isEmpty) return; // 无清仓 → 不打空请求
    if (_scoreLoading) return; // 已有请求在途 → 不重复发起
    _scoreLoading = true;
    try {
      final scores = await widget.api.getSoldScore();
      if (!mounted) return;
      setState(() => _soldScores = scores);
    } catch (_) {
      // 打分失败静默：主数据已展示，打分列显示 —（数据不足不糊弄）
    } finally {
      _scoreLoading = false;
    }
  }

  // ── 记录交易（扩展：止损位/买点/目标价/原因，RFC 20260816） ──

  Future<void> _recordTrade() async {
    // 点击记录交易：先刷新（持仓/盈亏最新再录入，2026-08-16）
    await _loadAll();
    if (!mounted) return;
    final form = await showDialog<_TradeFormResult>(
      context: context,
      builder: (_) => _TradeDialog(api: widget.api),
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
      if (!mounted) return;
      // 2026-08-17 走查：记录交易成功无反馈——补自然回执（第一原则，无系统视角）
      final action = form.direction == 'buy' ? '买入' : '卖出';
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text('$action ${form.volume} 股 ${form.name.isEmpty ? form.symbol : form.name}，已记下',
            style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
        backgroundColor: AppColors.darkSurface2,
        duration: const Duration(seconds: 2),
      ));
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
      // 2026-08-23 修复：保存后本地乐观更新——不依赖 _loadAll（行情注入可能慢/超时，
      // 用户曾看到「修改止损后半天刷不出来、超时、页面显示旧值」；后端其实已落盘）
      // 只替换编辑字段（止损/角色/目标价），保留本地行情注入后的现价/盈亏（避免回退到存储价）
      setState(() {
        _positions = _positions.map((pos) {
          if (pos.symbol != p.symbol) return pos;
          return PositionItem(
            symbol: pos.symbol,
            name: pos.name,
            quantity: pos.quantity,
            avgCost: pos.avgCost,
            currentPrice: pos.currentPrice,
            marketValue: pos.marketValue,
            pnl: pos.pnl,
            pnlPercent: pos.pnlPercent,
            entryDate: pos.entryDate,
            stopLossPrice: result.stopLossPrice ?? pos.stopLossPrice,
            buyPoint: pos.buyPoint,
            role: result.role.isEmpty ? pos.role : result.role,
            targetPrice: result.targetPrice ?? pos.targetPrice,
          );
        }).toList();
      });
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text('${p.name.isEmpty ? p.symbol : p.name} 已更新',
            style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
        backgroundColor: AppColors.darkSurface2,
        duration: const Duration(seconds: 2),
      ));
      // 后台静默刷新行情/盈亏；失败不覆盖本地已更新的止损（_loadAll 内部已有旧数据保留逻辑）
      unawaited(_loadAll());
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('更新失败: ${_extractApiError(e)}', style: const TextStyle(fontSize: 13, color: AppColors.darkOrange)),
          backgroundColor: AppColors.darkSurface2,
        ));
      }
    }
  }

  // ── 交易历史 / 复盘历史 入口 ──

  /// RFC 20260825：持仓批次明细——点击「批次」拉取该股全部批次（含回合/初始底仓），弹窗展示。
  /// state=all 一次拿全（持有中 + 已清仓回合），symbol 由后端过滤；失败透出人话不打断页面。
  Future<void> _showLots(PositionItem p) async {
    // P2-批次1：连点/双击无幂等守卫（F20/F22 同类）——慢响应逐个 showDialog 叠两层
    if (_lotsDialogOpen) return;
    _lotsDialogOpen = true;
    try {
      LotsResponse? resp;
      String? err;
      try {
        resp = await widget.api.getLots(state: 'all', symbol: p.symbol);
      } catch (e) {
        err = _extractApiError(e);
      }
      if (!mounted) return;
      await showDialog(
        context: context,
        builder: (_) => _LotsDialog(
          symbol: p.symbol,
          name: p.name,
          lots: resp?.lots ?? const [],
          reconcile: resp?.reconcile ?? const [],
          error: err,
        ),
      );
    } finally {
      _lotsDialogOpen = false; // 弹窗关闭后复位，允许下次打开
    }
  }

  /// RFC 20260817：推送设置对话框（逐类型开关）。
  Future<void> _showPushSettings() async {
    Map<String, bool> settings = {};
    try {
      settings = await widget.api.getPushSettings();
    } catch (_) {
      settings = {};
    }
    if (!mounted) return;
    final messenger = ScaffoldMessenger.of(context);
    await showDialog<void>(
      context: context,
      builder: (_) => _PushSettingsDialog(
        settings: settings,
        onToggle: (type, on) async {
          try {
            await widget.api.updatePushSetting(type, on);
            return null; // 成功无错误
          } catch (e) {
            // B5-6（2026-08-23，P2-推送5 半修残留）：失败透出原因，不再静默
            return extractApiErrorMessage(e);
          }
        },
        // 失败提示走 dialog 外层的 messenger（dialog builder 内无页面 context 安全）
        onToggleFailed: (msg) {
          messenger.showSnackBar(SnackBar(
            content: Text('推送设置失败：$msg', style: const TextStyle(fontSize: 13)),
            backgroundColor: AppColors.darkSurface2,
          ));
        },
      ),
    );
  }

  Future<void> _showReviewHistory() {
    return showDialog<void>(
      context: context,
      builder: (_) => _ReviewHistoryDialog(api: widget.api),
    );
  }

  String _extractApiError(dynamic e) => extractApiErrorMessage(e);

  /// P2-交易15（2026-08-17）：打分列颜色——中性色阶（蓝/紫/灰），不借盈亏色（红涨绿亏）；
  /// 空值 '—' 固定灰（不渲染成警告橙）。
  Color _scoreColor(int? score) {
    if (score == null) return AppColors.darkGrey5;
    if (score >= 70) return AppColors.darkBlue;
    if (score >= 50) return AppColors.darkPurple;
    return AppColors.darkGrey3;
  }

  /// P2-14：千分位格式化（-39495.12 → -39,495.12）。
  static String _thousands(double v) {
    final neg = v < 0;
    final s = v.abs().toStringAsFixed(2);
    final parts = s.split('.');
    final buf = StringBuffer();
    final intPart = parts[0];
    for (var i = 0; i < intPart.length; i++) {
      buf.write(intPart[i]);
      final remaining = intPart.length - 1 - i;
      if (remaining > 0 && remaining % 3 == 0) buf.write(',');
    }
    return '${neg ? '-' : ''}$buf.${parts[1]}';
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
          // RFC 20260823：交易历史 Dialog 已升级为第 5 Tab「历史成交」，页头入口移除
          // 2026-08-23 用户确认：页头「批量导入」入口移除——清仓/资金/自选/历史成交各 Tab
          // 已有专属导入按钮（导入清仓/导入资金/导入自选/导入历史成交），持仓导入在持仓 Tab 内，
          // 避免用户把清仓/资金文本误塞进批量导入对话框（被交易 CSV 解析器校验「买点」拦截）
          // RFC 20260817：推送设置入口（早盘/午间/尾盘/买点/预警/行情条开关）
          IconButton(
            onPressed: _showPushSettings,
            icon: const Icon(Icons.notifications_outlined, size: 18),
            color: AppColors.darkGrey4,
            tooltip: '推送设置',
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
                      if (_dailySummary != null) ...[
                        const SizedBox(height: 4),
                        _buildDailySummaryRow(),
                      ],
                      const SizedBox(height: 6),
                      Row(children: [
                        Text(_lastUpdated != null
                            ? '上次更新 $_lastUpdated · 每 30 分钟自动刷新 · 账户快照 ${_account?.snapshotDate ?? '-'}'
                            : '数据加载中…',
                            style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
                        const Spacer(),
                        TextButton.icon(
                          onPressed: _loadAll,
                          icon: const Icon(Icons.refresh, size: 14),
                          label: const Text('点击更新', style: TextStyle(fontSize: 11)),
                          style: TextButton.styleFrom(
                              foregroundColor: AppColors.darkGrey4,
                              padding: const EdgeInsets.symmetric(horizontal: 6),
                              minimumSize: const Size(0, 28)),
                        ),
                      ]),
                      const SizedBox(height: 12),
                      // E1（2026-08-16）：Tab 工作区替代纵向堆叠（UI/UX 审查方案）
                      _buildTabWorkspace(),
                    ],
                  ),
      ),
    ]);
  }

  /// RFC 20260822：当日交易复盘行（纯客观数字）——今日 N 笔 · 买/卖 · 时段分布 · 首末笔时间。
  Widget _buildDailySummaryRow() {
    final d = _dailySummary!;
    final sessionText = d.sessions
        .where((s) => s.count > 0)
        .map((s) => '${s.name} ${s.count} 笔')
        .join(' · ');
    final timeText = (d.firstTradeTime != null && d.lastTradeTime != null)
        ? '${d.firstTradeTime!.substring(0, 5)}-${d.lastTradeTime!.substring(0, 5)}'
        : '';
    return Row(children: [
      Icon(Icons.schedule, size: 12, color: AppColors.darkGreen),
      const SizedBox(width: 6),
      Flexible(
        child: Text(
          '今日 ${d.count} 笔 · 买 ${d.buyCount} / 卖 ${d.sellCount}'
          '${sessionText.isEmpty ? '' : ' · $sessionText'}'
          '${timeText.isEmpty ? '' : ' · $timeText'}',
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(fontSize: 11, color: AppColors.darkGrey3),
        ),
      ),
    ]);
  }

  /// 账户总览卡（RFC 20260816：资金股份查询导入的券商口径 + 组合快照）。
  /// 展示：总资产（主）/ 可用资金 / 可取 / 参考市值 / 当日盈亏 / 盈亏 / 快照日期。
  Widget _buildSnapshotRow() {
    final a = _account;
    final p = _portfolio;
    final hasAccount = a != null && a.assets > 0;
    return Row(children: [
      _statCard('总资产', hasAccount ? a.assets : (p?.totalValue ?? 0) + (p?.cashBalance ?? 0),
          format: '¥', color: AppColors.darkBlue, big: true),
      const SizedBox(width: 12),
      _statCard('可用资金', hasAccount ? a.available : (p?.cashBalance ?? 0), format: '¥', color: AppColors.darkGrey3),
      const SizedBox(width: 12),
      _statCard('可取', hasAccount ? a.withdrawable : 0, format: '¥', color: AppColors.darkGrey5),
      const SizedBox(width: 12),
      _statCard('参考市值', hasAccount ? a.marketValue : (p?.totalValue ?? 0), format: '¥', color: AppColors.darkPurple),
      const SizedBox(width: 12),
      // #132 红涨绿亏（A股）：盈=红、亏=绿
      _statCard('当日盈亏', hasAccount ? a.todayPnl : 0,
          color: (hasAccount ? a.todayPnl : 0) >= 0 ? AppColors.darkRed : AppColors.darkGreen),
      const SizedBox(width: 12),
      // 总盈亏 = 资产 - 本金（用户确认：累计投入 15 万，当前亏 3.9 万——券商浮盈不是总盈亏）
      // P2-交易31（2026-08-29，U32）：本金未设（principal=0）→ totalPnl null → 「—」不给误导数值
      // （旧回落浮盈漏已实现盈亏：清仓后显示 0 盈亏仍是误导）
      _statCard('总盈亏', hasAccount ? a.totalPnl : (p?.totalPnl ?? 0),
          color: ((hasAccount ? a.totalPnl : (p?.totalPnl ?? 0)) ?? 0) >= 0 ? AppColors.darkRed : AppColors.darkGreen,
          sub: hasAccount && a.principal > 0 ? '本金 ¥${_thousands(a.principal)}' : (hasAccount ? '未设本金，设后显示' : null)),
      const SizedBox(width: 12),
      _statCard('持仓浮盈', hasAccount ? a.pnl : 0,
          color: (hasAccount ? a.pnl : 0) >= 0 ? AppColors.darkRed : AppColors.darkGreen),
      const SizedBox(width: 12),
      _statCard('持仓数', (p?.positionCount ?? 0).toDouble(), format: '', color: AppColors.darkGrey2),
    ]);
  }

  Widget _statCard(String label, double? value, {String format = '¥', required Color color, bool big = false, String? sub}) {
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
            if (sub != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 2),
                child: Text(sub, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
              ),
            // P2-交易14（2026-08-17）：大数值（如 ¥-39495.12 22px 粗体）在窄卡溢出 → FittedBox 缩放 + 千分位
            // P2-交易31（2026-08-29）：value null（本金未设）→ 灰色「—」，不给误导数值
            FittedBox(
              fit: BoxFit.scaleDown,
              alignment: Alignment.centerLeft,
              child: Text(
                value == null
                    ? '—'
                    : isCount ? value.toInt().toString() : '$format${_thousands(value)}',
                style: TextStyle(fontSize: big ? 22 : 16, fontWeight: big ? FontWeight.w700 : FontWeight.w600,
                    color: value == null ? AppColors.darkGrey5 : color),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildPositionTable() {
    // 2026-08-23：持仓 Tab 内导入入口（通达信持仓导出，全量覆盖）——页头「批量导入」已移除，
    // 持仓导入不再与清仓/资金/交易 CSV 混在一个对话框（此前清仓/资金文本被交易 CSV 校验「买点」拦截）
    final header = Row(children: [
      Text('持仓 ${_positions.length} 只', style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
      const SizedBox(width: 8),
      Text('通达信持仓导出 · 全量覆盖 · 止损需导入后补设', style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
      const Spacer(),
      OutlinedButton.icon(
        onPressed: () => _openImportDialog('持仓',
            '粘贴通达信持仓导出（或选择文件）：证券代码/股票余额/成本价 自动识别，全量覆盖，止损需导入后补设',
            (c) async {
              final parsed = parseTdxPositions(c);
              if (parsed.rows.isEmpty) {
                throw Exception('无法识别通达信持仓导出——请确认表头含「证券代码/股票余额/成本价」');
              }
              final result = await widget.api.importPositions(
                parsed.rows.map((r) => r.toJson()).toList(),
                replace: true,
              );
              await _loadAll();
              if (mounted) {
                var msg = '持仓导入 ${result.imported} 只';
                if (result.missingStopLoss.isNotEmpty) {
                  msg += ' · 未设止损 ${result.missingStopLoss.length} 只（${result.missingStopLoss.join('、')}）';
                }
                _toast(msg);
              }
            }),
        icon: const Icon(Icons.upload_file, size: 14),
        label: const Text('导入持仓', style: TextStyle(fontSize: 12)),
        style: OutlinedButton.styleFrom(
            foregroundColor: AppColors.darkGrey1,
            side: const BorderSide(color: AppColors.darkGrey4),
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4)),
      ),
    ]);
    if (_positions.isEmpty) {
      return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        header,
        const SizedBox(height: 12),
        const Center(
          child: Padding(
            padding: EdgeInsets.only(top: 40),
            child: Text('暂无持仓', style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
          ),
        ),
      ]);
    }
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      header,
      const SizedBox(height: 8),
      Container(
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
              // RFC 20260825：批次明细入口（一买一批跟踪）+ 编辑
              DataCell(Row(mainAxisSize: MainAxisSize.min, children: [
                TextButton(
                  onPressed: () => _showLots(p),
                  style: TextButton.styleFrom(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                    minimumSize: Size.zero,
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  ),
                  child: const Text('批次', style: TextStyle(fontSize: 12, color: AppColors.darkBlue)),
                ),
                TextButton(
                  onPressed: () => _editPosition(p),
                  style: TextButton.styleFrom(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                    minimumSize: Size.zero,
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  ),
                  child: const Text('编辑', style: TextStyle(fontSize: 12, color: AppColors.darkGreen)),
                ),
              ])),
            ]);
          }).toList(),
          ),
        ),
      ),
    ]);
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

  // ── 自选股 / 清仓股 / 资金查询区块（RFC 20260816 交易数据智能）──

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(msg, style: const TextStyle(fontSize: 13)),
      backgroundColor: AppColors.darkSurface2,
      duration: const Duration(seconds: 2),
    ));
  }

  /// Tab 工作区（E1）：持仓（默认）/ 自选 / 清仓 / 资金 / 历史成交 五分区。
  /// 2026-08-23：历史成交从页头 Dialog 升级为常驻第 5 Tab（RFC 20260823，取代 _HistoryDialog）。
  /// B6-5（2026-08-23，P1-交易17）：DefaultTabController + 监听组件——历史成交 keepAlive 防重建后
  /// 切回 Tab 时主动刷新（防收盘/他端变更后陈旧，复发信号：保活页陈旧）。
  final GlobalKey<_HistorySectionState> _historyKey = GlobalKey<_HistorySectionState>();

  Widget _buildTabWorkspace() {
    return DefaultTabController(
      length: 7,
      child: _TabHistoryRefreshListener(
        onHistorySelected: () => _historyKey.currentState?.refreshSilently(),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Container(
            decoration: BoxDecoration(
              color: AppColors.darkSurface,
              borderRadius: BorderRadius.circular(10),
              border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.5)),
            ),
            child: const TabBar(
              isScrollable: true,
              tabAlignment: TabAlignment.start,
              indicatorColor: AppColors.darkGreen,
              labelColor: AppColors.darkGrey1,
              unselectedLabelColor: AppColors.darkGrey5,
              labelStyle: TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
              tabs: [
                Tab(text: '持仓'),
                Tab(text: '自选'),
                Tab(text: '清仓'),
                Tab(text: '资金'),
                Tab(text: '历史成交'),
                Tab(text: '规则'),
                Tab(text: '案例'),
              ],
            ),
          ),
          const SizedBox(height: 10),
          SizedBox(
            height: 380,
            child: TabBarView(children: [
              SingleChildScrollView(child: _buildPositionTable()),
              SingleChildScrollView(child: _buildWatchlistSection()),
              SingleChildScrollView(child: _buildSoldSection()),
              SingleChildScrollView(child: _buildCashSection()),
              _HistorySection(key: _historyKey, api: widget.api),
              SingleChildScrollView(child: _buildRuleSection()),
              SingleChildScrollView(child: _buildCaseSection()),
            ]),
          ),
        ]),
      ),
    );
  }

  Widget _buildWatchlistSection() {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        const Text('自选股', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
        const SizedBox(width: 8),
        Text('${_watchlist.length} 只 · 阿呆帮你盯买点', style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
        const Spacer(),
        OutlinedButton.icon(
          onPressed: () => _openImportDialog('自选股',
              '粘贴通达信自选导出（或选择文件）：代码/名称/细分行业/长期中期短期形态/近日指标提示',
              (c) async {
                final n = await widget.api.importWatchlist(c);
                await _loadAll();
                if (mounted) _toast('自选股导入 $n 只');
              }),
          icon: const Icon(Icons.upload_file, size: 14),
          label: const Text('导入自选', style: TextStyle(fontSize: 12)),
          style: OutlinedButton.styleFrom(
              foregroundColor: AppColors.darkGrey1,
              side: const BorderSide(color: AppColors.darkGrey4),
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4)),
        ),
      ]),
      // P2-UX2（2026-08-29）：规则术语图例——移动端/桌面只读展示不再零解释
      const SizedBox(height: 4),
      const Text('买点信号：B1=回调缩量低吸 · B2=放量突破右侧（判定是提示不是指令）',
          style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
      const SizedBox(height: 8),
      if (_watchlist.isEmpty)
        const Text('暂无自选股——导入通达信自选导出，阿呆帮你盯买点',
            style: TextStyle(fontSize: 12, color: AppColors.darkGrey5))
      else
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: DataTable(
            headingRowHeight: 30, dataRowMinHeight: 32, dataRowMaxHeight: 32,
            columns: const [
              DataColumn(label: Text('代码')), DataColumn(label: Text('名称')),
              DataColumn(label: Text('行业')), DataColumn(label: Text('长/中/短')),
              DataColumn(label: Text('指标提示')), DataColumn(label: Text('买点信号')), DataColumn(label: Text('')),
            ],
            rows: _watchlist.map((w) {
              // C2 买点信号：命中 B1/B2 显示红色徽标（判定是提示不是指令）
              final bp = _buyPoints.where((b) => b.symbol == w.symbol).toList();
              return DataRow(cells: [
                DataCell(Text(w.symbol, style: const TextStyle(fontSize: 12))),
                DataCell(Text(w.name, style: const TextStyle(fontSize: 12))),
                DataCell(Text(w.industry, style: const TextStyle(fontSize: 12))),
                DataCell(Text('${w.longForm}/${w.midForm}/${w.shortForm}',
                    style: const TextStyle(fontSize: 12))),
                DataCell(Text(w.signal, style: TextStyle(fontSize: 12,
                    color: w.signal.contains('金叉') ? AppColors.darkRed : AppColors.darkGrey4))),
                DataCell(bp.isEmpty
                    ? const Text('—', style: TextStyle(fontSize: 12, color: AppColors.darkGrey5))
                    : ConstrainedBox(
                        // P2-UI4（2026-08-29）：多条件 '、' 拼接限宽 + ellipsis，防撑宽整列/窄窗溢出
                        constraints: const BoxConstraints(maxWidth: 170),
                        child: Text(bp.map((b) => '${b.buyPoint} ${b.score.toStringAsFixed(0)}%').join('、'),
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.darkRed)))),
                DataCell(IconButton(
                  icon: const Icon(Icons.close, size: 14, color: AppColors.darkGrey5),
                  onPressed: () async {
                    // P2-13 + P3（2026-08-17）：删除带确认 + 失败反馈
                    final ok = await showDialog<bool>(
                      context: context,
                      builder: (_) => AlertDialog(
                        backgroundColor: AppColors.darkSurface2,
                        title: Text('删除自选 ${w.name}？', style: const TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
                        content: const Text('删除后不再盯这只票的买点', style: TextStyle(fontSize: 12, color: AppColors.darkGrey4)),
                        actions: [
                          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('取消')),
                          FilledButton(
                            onPressed: () => Navigator.pop(context, true),
                            style: FilledButton.styleFrom(backgroundColor: AppColors.darkOrange),
                            child: const Text('删除'),
                          ),
                        ],
                      ),
                    );
                    if (ok != true) return;
                    try {
                      await widget.api.removeWatchlist(w.symbol);
                      await _loadAll();
                    } catch (e) {
                      _toast('删除失败：${_extractApiError(e)}');
                    }
                  },
                )),
              ]);
            }).toList(),
          ),
        ),
    ]);
  }

  /// D2 纪律统计 + 行为模式（2026-08-16）：清仓按结果/纪律聚合 + 心理标注归类。
  Widget _buildSoldStats() {
    final total = _sold.length;
    final profit = _sold.where((s) => s.holdPnlPct >= 0).length;
    final loss = total - profit;
    final r66 = _sold.where((s) => s.verdict.contains('R66')).length;
    final r53 = _sold.where((s) => s.verdict.contains('R53')).length;
    // D2 行为模式：心理标注按关键词归类（P2-交易12 2026-08-17：单字键误配「不贪/着急」→ 改双字词组 + 否定排除）
    const patterns = <String, String>{
      '追高': '追高',
      '恐慌': '恐慌割肉',
      '贪婪': '贪心没走',
      '贪心': '贪心没走',
      '死扛': '套牢死扛',
      '犹豫': '犹豫错过',
      '急躁': '急躁操作',
      '急于': '急躁操作',
    };
    final marked = _sold.where((s) => s.psychology.isNotEmpty).toList();
    final patternCounts = <String, int>{};
    for (final s in marked) {
      for (final e in patterns.entries) {
        if (e.key.startsWith('贪') && s.psychology.contains('不贪')) continue; // 否定排除
        if (s.psychology.contains(e.key)) {
          patternCounts[e.value] = (patternCounts[e.value] ?? 0) + 1;
        }
      }
    }
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.5)),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        // P2-UI4（2026-08-29）：统计标题行改 Wrap——窄窗口自动换行不再 RenderFlex 溢出，
        // 且保留各段独立 Text（R66/R53 橙色重点）
        Wrap(crossAxisAlignment: WrapCrossAlignment.center, spacing: 12, runSpacing: 4, children: [
          Text('$total 笔 · 盈 $profit / 亏 $loss',
              style: const TextStyle(fontSize: 12, color: AppColors.darkGrey4)),
          if (r66 > 0)
            // P2-交易5（2026-08-17）：阈值已改 -5%（课程止损幅度 3-5%），文案同步
            Text('扛单超5%（R66）$r66 笔',
                style: const TextStyle(fontSize: 12, color: AppColors.darkOrange)),
          if (r53 > 0)
            // B3-5（2026-08-23）：R53 含短持仓亏损与持有较久亏损（后端 verdict 均标 R53）
            Text('违反 R53 $r53 笔',
                style: const TextStyle(fontSize: 12, color: AppColors.darkOrange)),
          if (total > 0) ...[
            // P2-交易11（2026-08-17）：旧「纪律遵守率」实为胜率（profit/total 且 >=0 计盈）——口径错标；
            // 改：纪律遵守率 = (总笔数 - 违R66 - 违R53) / 总笔数；胜率单独展示（>0 才算盈）
            Text('胜率 ${((profit / total) * 100).toStringAsFixed(0)}%',
                style: TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
            Text('纪律遵守率 ${(((total - r66 - r53) / total) * 100).toStringAsFixed(0)}%',
                style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600,
                    color: (total - r66 - r53) / total >= 0.5 ? AppColors.darkGreen : AppColors.darkOrange)),
          ],
        ]),
        // D2 行为模式（心理标注聚合，标注后自动归类；P3：Wrap 防窄窗口溢出，无命中不显示该行）
        if (marked.isNotEmpty && patternCounts.isNotEmpty) ...[
          const SizedBox(height: 6),
          Wrap(spacing: 12, runSpacing: 4, crossAxisAlignment: WrapCrossAlignment.center, children: [
            Text('你的行为模式 · 已标 ${marked.length} 笔：',
                style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
            ...patternCounts.entries.map((e) => Text('${e.key} ${e.value} 笔',
                    style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppColors.darkOrange))),
          ]),
        ],
      ]),
    );
  }

  Widget _buildSoldSection() {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        const Text('清仓股复盘', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
        const SizedBox(width: 8),
        Text('${_sold.length} 笔 · B/S 对照规则判对错', style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
        const Spacer(),
        OutlinedButton.icon(
          onPressed: () => _openImportDialog('清仓股',
              '粘贴通达信清仓导出（或选择文件）：代码/名称/介入日期/清仓日期/持仓天数/买卖次数/持仓期涨幅%',
              (c) async {
                final n = await widget.api.importSold(c);
                await _loadAll();
                if (mounted) _toast('清仓股导入 $n 笔');
              }),
          icon: const Icon(Icons.upload_file, size: 14),
          label: const Text('导入清仓', style: TextStyle(fontSize: 12)),
          style: OutlinedButton.styleFrom(
              foregroundColor: AppColors.darkGrey1,
              side: const BorderSide(color: AppColors.darkGrey4),
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4)),
        ),
      ]),
      // P2-UX2（2026-08-29）：规则术语图例——R66/R53/三维打分不再零解释
      const SizedBox(height: 4),
      const Text('规则对照：R66=亏超5%扛单没走 · R53=短持/久持亏损；买点分=入场时机 · 执行分=纪律执行 · 总分=综合',
          style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
      const SizedBox(height: 8),
      if (_sold.isNotEmpty) _buildSoldStats(),
      const SizedBox(height: 8),
      if (_sold.isEmpty)
        const Text('暂无清仓记录——导入通达信清仓导出，阿呆对照规则给你判对错',
            style: TextStyle(fontSize: 12, color: AppColors.darkGrey5))
      else
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: DataTable(
            headingRowHeight: 30, dataRowMinHeight: 32, dataRowMaxHeight: 32,
            columns: const [
              DataColumn(label: Text('代码')), DataColumn(label: Text('名称')),
              DataColumn(label: Text('介入→清仓')), DataColumn(label: Text('天数')),
              DataColumn(label: Text('持仓期涨幅')), DataColumn(label: Text('规则对照')),
              DataColumn(label: Text('买点分')), DataColumn(label: Text('执行分')), DataColumn(label: Text('总分')),
              DataColumn(label: Text('心理标注')),
            ],
            rows: _sold.asMap().entries.map((e) {
              // D3 三维打分：按列表顺序索引匹配（P1-交易8 修复，2026-08-17）
              // 后端 SoldScoreService.score 按 sold 列表顺序逐笔返回；同代码多笔时
              // 旧实现按 symbol .first 会把两笔的分数都挂到第一笔上（错挂）
              final s = e.value;
              final score = e.key < _soldScores.length ? _soldScores[e.key] : null;
              return DataRow(cells: [
                DataCell(Text(s.symbol, style: const TextStyle(fontSize: 12))),
                DataCell(Text(s.name, style: const TextStyle(fontSize: 12))),
                DataCell(Text('${s.buyDate ?? '?'}→${s.sellDate ?? '?'}',
                    style: const TextStyle(fontSize: 12))),
                DataCell(Text('${s.holdDays}天', style: const TextStyle(fontSize: 12))),
                DataCell(Text('${s.holdPnlPct.toStringAsFixed(2)}%', style: TextStyle(fontSize: 12,
                    color: s.holdPnlPct >= 0 ? AppColors.darkRed : AppColors.darkGreen))),
                DataCell(Text(s.verdict, style: TextStyle(fontSize: 11,
                    color: s.verdict.contains('R66') ? AppColors.darkOrange
                        : s.verdict.contains('盈利') ? AppColors.darkGrey4 : AppColors.darkGrey5))),
                DataCell(Text(score?.buyPointScore?.toString() ?? '—',
                    style: TextStyle(fontSize: 12,
                        color: _scoreColor(score?.buyPointScore)))),
                DataCell(Text(score?.executionScore?.toString() ?? '—',
                    style: TextStyle(fontSize: 12,
                        color: _scoreColor(score?.executionScore)))),
                DataCell(Text(score?.totalScore?.toStringAsFixed(0) ?? '—',
                    style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600,
                        color: _scoreColor(score?.totalScore?.toInt())))),
                DataCell(InkWell(
                  onTap: () => _markPsychology(s),
                  child: Text(s.psychology.isEmpty ? '＋ 标注心理' : s.psychology,
                      style: TextStyle(fontSize: 12,
                          color: s.psychology.isEmpty ? AppColors.darkGrey5 : AppColors.darkOrange)),
                )),
              ]);
            }).toList(),
          ),
        ),
    ]);
  }

  Future<void> _markPsychology(SoldTradeDto s) async {
    final controller = TextEditingController(text: s.psychology);
    final result = await showDialog<String>(
      context: context,
      builder: (_) => AlertDialog(
        backgroundColor: AppColors.darkSurface2,
        title: Text('标注当时心理 · ${s.name}', style: const TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
        content: TextField(
          controller: controller,
          autofocus: true,
          maxLines: 3,
          decoration: const InputDecoration(hintText: '如：追高后恐慌割肉 / 套牢死扛 / 贪心没走'),
          style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('取消')),
          FilledButton(
            onPressed: () => Navigator.pop(context, controller.text.trim()),
            style: FilledButton.styleFrom(backgroundColor: AppColors.darkGreen),
            child: const Text('保存'),
          ),
        ],
      ),
    );
    if (result == null) return;
    try {
      await widget.api.updateSoldPsychology(s.symbol, result);
      await _loadAll();
    } catch (e) {
      _toast('标注失败：${_extractApiError(e)}');
    }
  }

  Widget _buildCashSection() {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        const Text('资金股份查询', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
        const SizedBox(width: 8),
        if (_cash != null)
          Text('现金 ¥${_cash!.toStringAsFixed(2)} · 总资产 ¥${_assets?.toStringAsFixed(2) ?? '-'}',
              style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
        const Spacer(),
        OutlinedButton.icon(
          onPressed: () => _openTransferDialog(true),
          icon: const Icon(Icons.south_west, size: 14, color: AppColors.darkGreen),
          label: const Text('转入', style: TextStyle(fontSize: 12)),
          style: OutlinedButton.styleFrom(
              foregroundColor: AppColors.darkGrey1,
              side: const BorderSide(color: AppColors.darkGrey4),
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4)),
        ),
        const SizedBox(width: 6),
        OutlinedButton.icon(
          onPressed: () => _openTransferDialog(false),
          icon: const Icon(Icons.north_east, size: 14, color: AppColors.darkOrange),
          label: const Text('转出', style: TextStyle(fontSize: 12)),
          style: OutlinedButton.styleFrom(
              foregroundColor: AppColors.darkGrey1,
              side: const BorderSide(color: AppColors.darkGrey4),
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4)),
        ),
        const SizedBox(width: 6),
        OutlinedButton.icon(
          onPressed: () => _openImportDialog('资金股份查询',
              '粘贴通达信「资金股份查询」导出（或选择文件）：更新现金余额 + 精确成本价（4 位）',
              (c) async {
                final r = await widget.api.importCash(c);
                await _loadAll();
                if (mounted) _toast('资金已更新：现金 ¥${r.cash.toStringAsFixed(2)} · 成本更新 ${r.updatedCost} 只');
              }),
          icon: const Icon(Icons.upload_file, size: 14),
          label: const Text('导入资金', style: TextStyle(fontSize: 12)),
          style: OutlinedButton.styleFrom(
              foregroundColor: AppColors.darkGrey1,
              side: const BorderSide(color: AppColors.darkGrey4),
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4)),
        ),
      ]),
      const SizedBox(height: 10),
      // 2026-08-18：本金独立行（此前塞在按钮行最右，窄窗口被挤出看不到）——
      // 本金 = 累计净投入（历史事实，只写本金不动现金）；充值/提现走上方「转入/转出」
      Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        decoration: BoxDecoration(
          color: AppColors.darkSurface,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: AppColors.darkGrey4, width: 0.5),
        ),
        child: Row(children: [
          const Icon(Icons.savings_outlined, size: 16, color: AppColors.darkGreen),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              '本金（累计净投入）¥${_thousands(_account?.principal ?? 0)}'
              // B2-2（2026-08-23）+ P2-交易31（2026-08-29，U32）：总盈亏 = 资产 - 本金；
              // 本金未设（principal=0）→ 不给误导数值（旧回落浮盈漏已实现盈亏），显示「—（设本金后显示）」
              ' · 总盈亏 ${_account != null ? (_account!.totalPnl == null ? '—（设置本金后显示）' : '¥${_thousands(_account!.totalPnl!)}') : '-'}',
              style: const TextStyle(fontSize: 12, color: AppColors.darkGrey2),
            ),
          ),
          OutlinedButton(
            onPressed: _openPrincipalDialog,
            style: OutlinedButton.styleFrom(
                foregroundColor: AppColors.darkGreen,
                side: const BorderSide(color: AppColors.darkGrey4),
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4)),
            child: const Text('设置本金', style: TextStyle(fontSize: 12)),
          ),
        ]),
      ),
      const SizedBox(height: 6),
      const Text('现金余额是 R81 仓位判定的分母（总资产=持仓+现金）——资金查询导入后占比判定更准',
          style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
    ]);
  }

  /// 银证转账 Dialog（转入/转出，净投入跟踪，2026-08-16）。
  Future<void> _openTransferDialog(bool isIn) async {
    final amount = TextEditingController();
    final note = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        backgroundColor: AppColors.darkSurface2,
        title: Text(isIn ? '转入（银行卡→证券）' : '转出（证券→银行卡）',
            style: const TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
        content: Column(mainAxisSize: MainAxisSize.min, children: [
          TextField(
            controller: amount,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            autofocus: true,
            decoration: const InputDecoration(labelText: '金额（元）'),
            style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
          ),
          const SizedBox(height: 8),
          TextField(
            controller: note,
            decoration: const InputDecoration(labelText: '备注（可选，如：补仓/提现）'),
            style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
          ),
          const SizedBox(height: 8),
          const Text('转入/转出会更新净投入本金与现金——总盈亏 = 资产 - 本金自动算',
              style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
        ]),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('取消')),
          FilledButton(
            onPressed: () {
              final v = double.tryParse(amount.text.trim());
              // P3（2026-08-17）：NaN/Infinity 也拦截（tryParse 对 NaN 恒 true 的 v<=0 会放行）+ 提交有反馈
              if (v == null || !v.isFinite || v <= 0) {
                ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
                  content: Text('请输入大于 0 的有效金额', style: TextStyle(fontSize: 13)),
                  backgroundColor: AppColors.darkSurface2,
                ));
                return;
              }
              Navigator.pop(context, true);
            },
            style: FilledButton.styleFrom(backgroundColor: AppColors.darkGreen),
            child: const Text('提交'),
          ),
        ],
      ),
    );
    if (ok != true) return;
    final v = double.tryParse(amount.text.trim());
    if (v == null || v <= 0) {
      _toast('请输入有效金额');
      return;
    }
    try {
      await widget.api.recordTransfer(
        type: isIn ? 'IN' : 'OUT',
        amount: v,
        note: note.text.trim().isEmpty ? null : note.text.trim(),
      );
      await _loadAll();
      if (mounted) _toast('${isIn ? '转入' : '转出'} ¥${v.toStringAsFixed(2)} 已记录');
    } catch (e) {
      if (mounted) _toast('转账记录失败');
    }
  }

  /// 本金设置 Dialog（2026-08-18）：累计净投入——只改本金（总盈亏 = 资产 - 本金），不动现金。
  Future<void> _openPrincipalDialog() async {
    final amount = TextEditingController(
        text: _account != null && _account!.principal > 0
            ? _account!.principal.toStringAsFixed(0)
            : '');
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        backgroundColor: AppColors.darkSurface2,
        title: const Text('设置本金（累计净投入）', style: TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
        content: Column(mainAxisSize: MainAxisSize.min, children: [
          TextField(
            controller: amount,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            autofocus: true,
            decoration: const InputDecoration(labelText: '本金（元）'),
            style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
          ),
          const SizedBox(height: 8),
          const Text('总盈亏 = 资产 − 本金。本金是历史累计投入，只写本金字段，不影响现金/持仓。',
              style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
        ]),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('取消')),
          FilledButton(
            onPressed: () {
              final v = double.tryParse(amount.text.trim());
              if (v == null || !v.isFinite || v <= 0) {
                ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
                  content: Text('请输入大于 0 的有效金额', style: TextStyle(fontSize: 13)),
                  backgroundColor: AppColors.darkSurface2,
                ));
                return;
              }
              Navigator.pop(context, true);
            },
            style: FilledButton.styleFrom(backgroundColor: AppColors.darkGreen),
            child: const Text('保存'),
          ),
        ],
      ),
    );
    if (ok != true) return;
    final v = double.tryParse(amount.text.trim());
    if (v == null || v <= 0) {
      _toast('请输入有效金额');
      return;
    }
    try {
      await widget.api.setPrincipal(v);
      await _loadAll();
      if (mounted) _toast('本金已设为 ¥${v.toStringAsFixed(0)}');
    } catch (e) {
      if (mounted) _toast('本金设置失败，请检查网络后重试');
    }
  }

  // ── 第三阶段：交易规则（用户自己的交易系统参数）──

  Map<String, dynamic> _ruleParams = {};
  bool _rulesLoaded = false;
  bool _rulesLoadFailed = false;
  bool _ruleExists = false; // P1-6：区分「默认 adai 包」vs「已自定义」

  /// 加载规则参数（GET /trading/rules；失败可重试——P1-6 不再永久失败文案）。
  Future<void> _loadRules() async {
    try {
      final resp = await widget.api.getTradingRules();
      final p = (resp['params'] as Map<String, dynamic>?) ?? {};
      if (mounted) {
        setState(() {
          _ruleParams = p;
          _ruleExists = resp['exists'] == true;
          _rulesLoaded = true;
          _rulesLoadFailed = false;
        });
      }
    } catch (e) {
      // P1-6：失败显示重试（原静默永久失败文案，C4「保活页陈旧」同类信号）
      if (mounted) setState(() => _rulesLoadFailed = true);
    }
  }

  Widget _buildRuleSection() {
    if (!_rulesLoaded && !_rulesLoadFailed) {
      return const Padding(
        padding: EdgeInsets.all(12),
        child: Text('规则加载中…', style: TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
      );
    }
    if (_rulesLoadFailed || _ruleParams.isEmpty) {
      return Padding(
        padding: const EdgeInsets.all(12),
        child: Row(children: [
          const Text('规则加载失败，请检查后端连接',
              style: TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
          const SizedBox(width: 8),
          TextButton(
            onPressed: _loadRules,
            child: const Text('重试', style: TextStyle(fontSize: 12, color: AppColors.darkGreen)),
          ),
        ]),
      );
    }
    // 参数中文标签（表单化展示，D4 决策：表单优先）
    const labels = <String, String>{
      'positionLimitPercent': '单票仓位上限 %',
      'defaultStopLossRatio': '默认止损比例（0.93 = −7%）',
      'givebackPeakPct': '浮盈回吐：峰值浮盈 %',
      'givebackRatioPct': '浮盈回吐：回吐比例 %',
      'shortOverdueDays': '短线超期天数',
      'soldStopLossPct': '清仓止损阈值 %',
      'soldShortHoldDays': '清仓短持仓天数',
      'buyPullbackPct': '买点：回调幅度',
      'buyShrinkRatio': '买点：缩量阈值',
      'buyKdjLow': '买点：KDJ 低位',
      'buyVolumeSurge': '买点：放量倍数',
      'buyPriorHighDays': '买点：前高窗口',
      'scoreBuyWeight': '打分：买点权重',
      'scoreExecWeight': '打分：执行权重',
      'constraintRuleMin': '建议硬约束：规则号下限',
      'constraintRuleMax': '建议硬约束：规则号上限',
    };
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        Text('我的交易规则${_ruleExists ? '（已自定义）' : '（默认）'}',
            style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
        const SizedBox(width: 8),
        // P1-6（2026-08-30 审查）：exists 消费——区分「默认 adai 包」vs「已自定义」，
        // 用户知道当前跑的是默认参数还是自己的规则
        Text(_ruleExists ? '改这里 = 改你的交易系统，不影响别人' : '当前用默认参数（adai 规则包）——编辑保存后就是你自己的交易系统',
            style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
        const Spacer(),
        OutlinedButton.icon(
          onPressed: () => _openRuleEditDialog(labels),
          icon: const Icon(Icons.edit, size: 14, color: AppColors.darkGreen),
          label: const Text('编辑规则', style: TextStyle(fontSize: 12)),
          style: OutlinedButton.styleFrom(
              foregroundColor: AppColors.darkGrey1,
              side: const BorderSide(color: AppColors.darkGrey4),
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4)),
        ),
      ]),
      const SizedBox(height: 8),
      Wrap(
        spacing: 8,
        runSpacing: 6,
        children: _ruleParams.entries.map((e) {
          final label = labels[e.key] ?? e.key;
          return Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            decoration: BoxDecoration(
              color: AppColors.darkSurface,
              borderRadius: BorderRadius.circular(6),
              border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.5)),
            ),
            child: Text('$label：${e.value}',
                style: const TextStyle(fontSize: 11, color: AppColors.darkGrey2)),
          );
        }).toList(),
      ),
    ]);
  }

  /// 规则编辑弹窗（表单化：数字输入 + 保存 PUT /trading/rules）。
  Future<void> _openRuleEditDialog(Map<String, String> labels) async {
    final controllers = <String, TextEditingController>{};
    for (final e in _ruleParams.entries) {
      controllers[e.key] = TextEditingController(text: e.value.toString());
    }
    // P1-6（2026-08-30 审查）：默认值（= TradingRuleSettings.defaults()，恢复默认按钮用）
    const defaults = <String, String>{
      'positionLimitPercent': '25', 'defaultStopLossRatio': '0.93',
      'givebackPeakPct': '20', 'givebackRatioPct': '50',
      'shortOverdueDays': '5', 'soldStopLossPct': '5.0', 'soldShortHoldDays': '5',
      'buyPullbackPct': '0.5', 'buyShrinkRatio': '0.7', 'buyKdjLow': '13',
      'buyVolumeSurge': '1.5', 'buyPriorHighDays': '20',
      'scoreBuyWeight': '0.5', 'scoreExecWeight': '0.5',
      'constraintRuleMin': '66', 'constraintRuleMax': '95',
    };
    final saved = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.darkSurface,
        title: const Text('编辑交易规则', style: TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
        content: SizedBox(
          width: 420,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: _ruleParams.keys.map((key) {
                final label = labels[key] ?? key;
                return Padding(
                  padding: const EdgeInsets.symmetric(vertical: 4),
                  child: Row(children: [
                    SizedBox(width: 170, child: Text(label, style: const TextStyle(fontSize: 12, color: AppColors.darkGrey3))),
                    Expanded(
                      child: TextField(
                        controller: controllers[key],
                        keyboardType: const TextInputType.numberWithOptions(decimal: true),
                        style: const TextStyle(fontSize: 12, color: AppColors.darkGrey1),
                        decoration: const InputDecoration(
                          isDense: true,
                          contentPadding: EdgeInsets.symmetric(horizontal: 8, vertical: 6),
                          border: OutlineInputBorder(),
                        ),
                      ),
                    ),
                  ]),
                );
              }).toList(),
            ),
          ),
        ),
        actions: [
          // P1-6：恢复默认（填默认值 → 保存）
          TextButton(
            onPressed: () {
              for (final e in controllers.entries) {
                final d = defaults[e.key];
                if (d != null) e.value.text = d;
              }
            },
            child: const Text('恢复默认', style: TextStyle(fontSize: 12)),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('取消', style: TextStyle(fontSize: 12)),
          ),
          FilledButton(
            onPressed: () async {
              // P1-6（2026-08-30 审查）：NaN/Infinity/非法输入不提交（原 tryParse 放行 NaN 且静默跳过）
              final params = <String, dynamic>{};
              for (final e in controllers.entries) {
                final text = e.value.text.trim();
                if (text.isEmpty) continue; // 清空字段保持原值
                final v = double.tryParse(text);
                if (v == null || !v.isFinite) {
                  if (ctx.mounted) {
                    ScaffoldMessenger.of(ctx).showSnackBar(SnackBar(
                      content: Text('「${labels[e.key] ?? e.key}」不是有效数字', style: const TextStyle(fontSize: 13)),
                      backgroundColor: AppColors.darkSurface2,
                    ));
                  }
                  return; // 保留弹窗让用户改
                }
                params[e.key] = v;
              }
              if (params.isEmpty) {
                if (ctx.mounted) {
                  ScaffoldMessenger.of(ctx).showSnackBar(const SnackBar(
                    content: Text('没有要更新的参数', style: TextStyle(fontSize: 13)),
                    backgroundColor: AppColors.darkSurface2,
                  ));
                }
                return;
              }
              try {
                await widget.api.updateTradingRules(params);
                await _loadRules();
                if (ctx.mounted) Navigator.pop(ctx, true);
              } catch (e) {
                // P1-6（2026-08-30 审查）：保存失败给反馈（原 catch 空块零反馈）
                if (ctx.mounted) {
                  ScaffoldMessenger.of(ctx).showSnackBar(const SnackBar(
                    content: Text('保存失败，请检查网络后重试', style: TextStyle(fontSize: 13)),
                    backgroundColor: AppColors.darkSurface2,
                  ));
                }
              }
            },
            child: const Text('保存', style: TextStyle(fontSize: 12)),
          ),
        ],
      ),
    );
    if (saved == true && mounted) _toast('交易规则已更新');
  }

  // ── 第四阶段（2026-08-30）：完美买点案例库（环 1-2）──

  List<Map<String, dynamic>> _cases = [];
  bool _casesLoaded = false;
  bool _casesLoadFailed = false;

  /// 加载案例列表（GET /trading/cases；失败显示重试，C4「保活页陈旧」同类信号）。
  Future<void> _loadCases() async {
    try {
      final list = await widget.api.listCases();
      if (mounted) {
        setState(() {
          _cases = list;
          _casesLoaded = true;
          _casesLoadFailed = false;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _casesLoadFailed = true);
    }
  }

  Widget _buildCaseSection() {
    if (!_casesLoaded && !_casesLoadFailed) {
      return const Padding(
        padding: EdgeInsets.all(12),
        child: Text('案例加载中…', style: TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
      );
    }
    if (_casesLoadFailed) {
      return Padding(
        padding: const EdgeInsets.all(12),
        child: Row(children: [
          const Text('案例加载失败，请检查后端连接',
              style: TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
          const SizedBox(width: 8),
          TextButton(
            onPressed: _loadCases,
            child: const Text('重试', style: TextStyle(fontSize: 12, color: AppColors.darkGreen)),
          ),
        ]),
      );
    }
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        const Text('完美买点案例', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
        const SizedBox(width: 8),
        Text('${_cases.length} 个 · 案例是手段，判定当下是价值',
            style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
        const Spacer(),
        OutlinedButton.icon(
          onPressed: _openMatchDialog,
          icon: const Icon(Icons.radar, size: 14, color: AppColors.darkGreen),
          label: const Text('匹配买点', style: TextStyle(fontSize: 12)),
          style: OutlinedButton.styleFrom(
              foregroundColor: AppColors.darkGrey1,
              side: const BorderSide(color: AppColors.darkGrey4),
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4)),
        ),
        const SizedBox(width: 8),
        OutlinedButton.icon(
          onPressed: _openAnnotateCaseDialog,
          icon: const Icon(Icons.add, size: 14, color: AppColors.darkGreen),
          label: const Text('标注案例', style: TextStyle(fontSize: 12)),
          style: OutlinedButton.styleFrom(
              foregroundColor: AppColors.darkGrey1,
              side: const BorderSide(color: AppColors.darkGrey4),
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4)),
        ),
      ]),
      const SizedBox(height: 8),
      if (_cases.isEmpty)
        Padding(
          padding: const EdgeInsets.all(16),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: const [
            Text('还没有案例——标注第一个完美买点（代码 + 日期），系统自动拉 60+30 日 K 还原画面、算特征和后验。',
                style: TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
            SizedBox(height: 6),
            Text('例如：000725 / 2026-08-03 / B1 回踩 60 日线 + 地量',
                style: TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
          ]),
        )
      else
        ..._cases.map((c) => _buildCaseRow(c)),
    ]);
  }

  Widget _buildCaseRow(Map<String, dynamic> c) {
    final id = '${c['id']}';
    final name = '${c['name'] ?? ''}';
    final symbol = '${c['symbol'] ?? ''}';
    final buyDate = '${c['buyDate'] ?? ''}';
    final buyType = '${c['buyType'] ?? ''}';
    final verify = (c['verify'] as Map<String, dynamic>?) ?? const {};
    final plus5 = verify['+5dReturnPct'];
    final plus5Text = plus5 == null ? '后验—' : '${(plus5 as num).toStringAsFixed(1)}%';
    final features = (c['features'] as Map<String, dynamic>?) ?? const {};
    final desc = '${c['description'] ?? ''}';
    return Container(
      margin: const EdgeInsets.only(bottom: 6),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.5)),
      ),
      child: Row(children: [
        SizedBox(
          width: 150,
          child: Text(name.isNotEmpty ? '$name($symbol)' : symbol,
              style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
        ),
        SizedBox(
          width: 92,
          child: Text(buyDate, style: const TextStyle(fontSize: 12, color: AppColors.darkGrey2)),
        ),
        SizedBox(
          width: 64,
          child: Text(buyType.isNotEmpty ? buyType : '未知',
              style: const TextStyle(fontSize: 11, color: AppColors.darkGreen)),
        ),
        SizedBox(
          width: 76,
          child: Text('+5d $plus5Text', style: const TextStyle(fontSize: 11, color: AppColors.darkGrey2)),
        ),
        Expanded(
          child: Text(
            desc.isNotEmpty ? desc : '回撤 ${features['drawdownFromHighPct'] ?? '—'}% · 量比 ${features['volumeShrinkRatio'] ?? '—'} · J ${features['kdjJ'] ?? '—'}',
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5),
          ),
        ),
        IconButton(
          tooltip: '查看详情（K 线还原 + 特征）',
          icon: const Icon(Icons.insert_chart_outlined, size: 16, color: AppColors.darkGrey2),
          onPressed: () => _openCaseDetailDialog(c),
        ),
        IconButton(
          tooltip: '删除案例',
          icon: const Icon(Icons.delete_outline, size: 16, color: AppColors.darkGrey5),
          onPressed: () => _deleteCase(id),
        ),
      ]),
    );
  }

  /// 匹配买点弹窗（环 4：核心价值）——输入代码 → 当前形态 vs 案例库相似度 Top N。
  Future<void> _openMatchDialog() async {
    final symbolCtrl = TextEditingController();
    final dateCtrl = TextEditingController();
    Map<String, dynamic>? result;
    var loading = false;
    String? error;
    await showDialog<void>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDlg) => AlertDialog(
          backgroundColor: AppColors.darkSurface2,
          title: const Text('匹配买点', style: TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
          content: SizedBox(
            width: 460,
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('输入任意 6 位代码（日期留空 = 最近交易日）——系统算当前形态特征，与你的完美买点案例库做相似度匹配。',
                  style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
              const SizedBox(height: 12),
              Row(children: [
                Expanded(
                  child: TextField(controller: symbolCtrl,
                      decoration: _caseInput('标的代码（如 000725）')),
                ),
                const SizedBox(width: 8),
                SizedBox(
                  width: 140,
                  child: TextField(controller: dateCtrl,
                      decoration: _caseInput('日期（可空）')),
                ),
              ]),
              const SizedBox(height: 12),
              if (error != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Text(error!, style: const TextStyle(fontSize: 11, color: AppColors.darkRed)),
                ),
              if (loading)
                const Center(
                  child: Padding(
                    padding: EdgeInsets.all(8),
                    child: SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.darkGreen),
                    ),
                  ),
                )
              else if (result != null) ...[
                if (((result!['matches'] as List<dynamic>?) ?? const []).isEmpty)
                  Padding(
                    padding: const EdgeInsets.all(12),
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: const [
                      Text('当前形态与案例库无相似买点。', style: TextStyle(fontSize: 12, color: AppColors.darkGrey2)),
                      SizedBox(height: 4),
                      Text('先标注几个完美买点案例（案例 Tab「标注案例」），匹配才有料。',
                          style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
                    ]),
                  )
                else
                  ...(result!['matches'] as List<dynamic>).map<Widget>((m) {
                    final mm = m as Map<String, dynamic>;
                    final sim = (mm['similarityPercent'] as num?)?.toDouble() ?? 0;
                    final plus5 = mm['plus5dReturnPct'];
                    return Container(
                      margin: const EdgeInsets.only(bottom: 6),
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                      decoration: BoxDecoration(
                        color: AppColors.darkSurface,
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(
                            color: sim >= 80
                                ? AppColors.darkGreen.withValues(alpha: 0.6)
                                : AppColors.darkBorder.withValues(alpha: 0.5)),
                      ),
                      child: Row(children: [
                        Text('${mm['name'] ?? mm['symbol']}（${mm['symbol']}）',
                            style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
                        const SizedBox(width: 8),
                        Text('${mm['buyDate'] ?? ''} · ${mm['buyType'] ?? ''}',
                            style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
                        const Spacer(),
                        Text('相似 ${sim.toStringAsFixed(1)}%',
                            style: TextStyle(
                                fontSize: 12,
                                fontWeight: FontWeight.w600,
                                color: sim >= 80 ? AppColors.darkGreen : AppColors.darkGrey2)),
                        const SizedBox(width: 8),
                        Text('+5d ${plus5 == null ? '—' : '${(plus5 as num).toStringAsFixed(1)}%'}',
                            style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
                      ]),
                    );
                  }),
                if ((result!['matches'] as List<dynamic>?)?.isNotEmpty ?? false)
                  Padding(
                    padding: const EdgeInsets.only(top: 4),
                    child: Text('相似 ≥80% 绿框提示——形态与库中完美买点高度接近（AI 理解见案例详情）。',
                        style: const TextStyle(fontSize: 10, color: AppColors.darkGrey5)),
                  ),
              ],
            ]),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('关闭', style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
            ),
            TextButton(
              onPressed: loading
                  ? null
                  : () async {
                      final symbol = symbolCtrl.text.trim();
                      if (symbol.isEmpty) return;
                      setDlg(() {
                        loading = true;
                        error = null;
                        result = null;
                      });
                      try {
                        final resp = await widget.api
                            .matchCases(symbol, date: dateCtrl.text.trim());
                        if (ctx.mounted) setDlg(() {
                          result = resp;
                          loading = false;
                        });
                      } catch (e) {
                        if (ctx.mounted) setDlg(() {
                          error = '匹配失败：${_extractApiError(e)}';
                          loading = false;
                        });
                      }
                    },
              child: Text(loading ? '匹配中…' : '匹配',
                  style: const TextStyle(fontSize: 13, color: AppColors.darkGreen)),
            ),
          ],
        ),
      ),
    );
  }

  /// 标注弹窗：代码 + 日期 + 买点类型 + 描述 → POST /trading/cases。
  Future<void> _openAnnotateCaseDialog() async {
    final symbolCtrl = TextEditingController();
    final dateCtrl = TextEditingController(text: '');
    final typeCtrl = TextEditingController(text: 'B1');
    final descCtrl = TextEditingController();
    final saved = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.darkSurface2,
        title: const Text('标注完美买点案例', style: TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
        content: SizedBox(
          width: 380,
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('系统自动拉「前 60 + 后 30 交易日」日 K，还原 K 线画面、计算特征画像和后验窗口。',
                style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
            const SizedBox(height: 12),
            TextField(controller: symbolCtrl, decoration: _caseInput('标的代码（6 位，如 000725）')),
            const SizedBox(height: 8),
            TextField(controller: dateCtrl, decoration: _caseInput('买点日期（yyyy-MM-dd，如 2026-08-03）')),
            const SizedBox(height: 8),
            TextField(controller: typeCtrl, decoration: _caseInput('买点类型（B1/B2/B3/SB1/其他，可空）')),
            const SizedBox(height: 8),
            TextField(controller: descCtrl, decoration: _caseInput('为什么完美（可选，如：回踩 60 日线 + 地量）')),
          ]),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('取消', style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('标注', style: TextStyle(fontSize: 13, color: AppColors.darkGreen)),
          ),
        ],
      ),
    );
    if (saved != true || !mounted) return;
    final symbol = symbolCtrl.text.trim();
    final date = dateCtrl.text.trim();
    if (symbol.isEmpty || date.isEmpty) {
      _toast('代码和日期必填');
      return;
    }
    try {
      await widget.api.annotateCase(
        symbol: symbol,
        buyDate: date,
        buyType: typeCtrl.text.trim(),
        description: descCtrl.text.trim(),
      );
      await _loadCases();
      if (mounted) _toast('案例已标注，画面已还原');
    } catch (e) {
      if (mounted) _toast('标注失败：${_extractApiError(e)}');
    }
  }

  /// 详情弹窗：K 线图（三区）+ 特征 + 后验（GET /trading/cases/{id}?kline=true）。
  Future<void> _openCaseDetailDialog(Map<String, dynamic> c) async {
    showDialog<void>(
      context: context,
      builder: (ctx) => _CaseDetailDialog(api: widget.api, caseId: '${c['id']}'),
    );
  }

  InputDecoration _caseInput(String hint) {
    return InputDecoration(
      hintText: hint,
      hintStyle: const TextStyle(fontSize: 12, color: AppColors.darkGrey4),
      isDense: true,
      contentPadding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
      enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(6), borderSide: const BorderSide(color: AppColors.darkBorder)),
      focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(6), borderSide: const BorderSide(color: AppColors.darkGreen)),
    );
  }

  Future<void> _deleteCase(String caseId) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.darkSurface2,
        title: const Text('删除案例？', style: TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
        content: Text('$caseId 将被删除（K 线/特征/后验一并移除）。',
            style: const TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('取消', style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('删除', style: TextStyle(fontSize: 13, color: AppColors.darkRed)),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    try {
      await widget.api.deleteCase(caseId);
      await _loadCases();
      if (mounted) _toast('案例已删除');
    } catch (e) {
      if (mounted) _toast('删除失败：${_extractApiError(e)}');
    }
  }


  /// 通用导入 Dialog：粘贴文本 或 选择文件（上传留存 + GBK 转码）→ 回调导入。
  Future<void> _openImportDialog(String title, String hint, Future<void> Function(String) onImport) async {
    final controller = TextEditingController();
    await showDialog<void>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDlg) => AlertDialog(
          backgroundColor: AppColors.darkSurface2,
          title: Text('导入$title', style: const TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
          content: SizedBox(
            width: 480,
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                OutlinedButton.icon(
                  onPressed: () async {
                    final result = await FilePicker.platform.pickFiles(type: FileType.any, withData: true);
                    if (result == null || result.files.isEmpty) return;
                    final f = result.files.first;
                    if (f.bytes == null) return;
                    final saved = await widget.api.saveImportFile(f.name, f.bytes!);
                    if (!ctx.mounted) return;
                    setDlg(() => controller.text = saved.content);
                  },
                  icon: const Icon(Icons.upload_file, size: 14),
                  label: const Text('选择文件（通达信导出）', style: TextStyle(fontSize: 12)),
                  style: OutlinedButton.styleFrom(
                      foregroundColor: AppColors.darkGrey1,
                      side: const BorderSide(color: AppColors.darkGrey4)),
                ),
                const SizedBox(width: 8),
                const Text('或直接粘贴', style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
              ]),
              const SizedBox(height: 8),
              Text(hint, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
              const SizedBox(height: 6),
              TextField(
                controller: controller,
                maxLines: 7, minLines: 4,
                style: const TextStyle(fontSize: 12, color: AppColors.darkGrey1),
              ),
            ]),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
            FilledButton(
              onPressed: () async {
                if (controller.text.trim().isEmpty) return;
                Navigator.pop(ctx);
                // 2026-08-17（P1-交易5）：导入失败必须反馈——后端解析失败会 400 + 人话消息，这里透出
                try {
                  await onImport(controller.text);
                } catch (e) {
                  _toast('导入失败：${_extractApiError(e)}');
                }
              },
              style: FilledButton.styleFrom(backgroundColor: AppColors.darkGreen),
              child: const Text('导入'),
            ),
          ],
        ),
      ),
    );
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
  final ApiService api;

  const _TradeDialog({required this.api});

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
  bool _nameAutoFilled = false; // 名称是否由代码自动带出（二次确认用）
  bool _lookingUp = false;
  Timer? _lookupDebounce; // P3 lookup 防抖

  /// 输入 6 位数字代码 → 调后端带出名称（二次确认：名称显示在框里，可改）。
  Future<void> _lookupName(String raw) async {
    final symbol = raw.trim().toUpperCase();
    if (!RegExp(r'^\d{6}$').hasMatch(symbol)) return;
    // P3（2026-08-17）：300ms 防抖——连续击键不每键都发网络请求
    _lookupDebounce?.cancel();
    _lookupDebounce = Timer(const Duration(milliseconds: 300), () async {
      if (!mounted) return;
      setState(() => _lookingUp = true);
      final name = await widget.api.lookupSymbol(symbol);
      if (!mounted) return;
      setState(() {
        _lookingUp = false;
        if (name != null && name.isNotEmpty && !_nameAutoFilled) {
          _name.text = name;
          _nameAutoFilled = true;
        } else if (name == null) {
          _nameAutoFilled = false;
        }
      });
    });
  }

  @override
  void dispose() {
    _lookupDebounce?.cancel();
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
                decoration: const InputDecoration(labelText: '代码', hintText: '如 600519'),
                style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
                onChanged: (v) {
                  _nameAutoFilled = false;
                  _lookupName(v);
                },
              ),
              const SizedBox(height: 8),
              TextField(
                controller: _name,
                decoration: InputDecoration(
                  labelText: _lookingUp ? '名称（查码中…）' : '名称（自动带出，可改）',
                ),
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
                    onChanged: (v) {
                      // 默认止损：买入价 -7%（用户 2026-08-17 设定），手动填过就不再覆盖
                      if (_direction == 'BUY' && _stopLoss.text.trim().isEmpty) {
                        final price = double.tryParse(v.trim());
                        if (price != null && price > 0) {
                          _stopLoss.text = (price * 0.93).toStringAsFixed(2);
                        }
                      }
                    },
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
                  decoration: const InputDecoration(labelText: '止损位', hintText: '默认按买入价 -7%，可改'),
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

// ─────────────────────────── 持仓批次明细 Dialog（RFC 20260825） ───────────────────────────

/// 批次明细弹窗：一只股票每一笔买入一个批次——买入日期 | 剩余/买入 | 成本 | 现价 | 盈亏(红涨绿亏)
/// | 止损 | 距止损% | 买点 | 角色 | 状态（初始底仓 / 持有中 / 已清仓-回合盈亏）。
/// reconcile 对账提示：note 含「≠」= 流水与持仓不一致 → 橙色警告行（以持仓快照为准）。
class _LotsDialog extends StatelessWidget {
  final String symbol;
  final String name;
  final List<LotItem> lots;
  final List<ReconcileLine> reconcile;
  final String? error;

  const _LotsDialog({
    required this.symbol,
    required this.name,
    required this.lots,
    required this.reconcile,
    this.error,
  });

  /// 盈亏%：持有中/初始底仓用后端浮动 pnlPct；已清仓回合 = realizedPnl / (成本×买入量)（后端无回合百分比字段，前端算）。
  String _lotPnlPctText(LotItem l) {
    if (!l.closed) return '${l.pnlPct.toStringAsFixed(2)}%';
    final cost = l.costPrice * l.volume;
    if (cost <= 0) return '—';
    return '回合 ${(l.realizedPnl / cost * 100).toStringAsFixed(2)}%';
  }

  @override
  Widget build(BuildContext context) {
    // 防御：后端已按 symbol 过滤，前端再按 symbol 双保险（旧后端可能忽略参数返回全部）
    final visible = lots.where((l) => l.symbol == symbol).toList();
    return Dialog(
      backgroundColor: AppColors.darkSurface,
      insetPadding: const EdgeInsets.all(24),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 1000, maxHeight: 600),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(children: [
                const Icon(Icons.view_agenda_outlined, size: 18, color: AppColors.darkGreen),
                const SizedBox(width: 8),
                Text('批次明细 · $symbol${name.isEmpty ? '' : ' $name'}',
                    style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
                const Spacer(),
                GestureDetector(
                  onTap: () => Navigator.pop(context),
                  child: const Icon(Icons.close, size: 18, color: AppColors.darkGrey5),
                ),
              ]),
              const SizedBox(height: 4),
              const Text('每一笔买入一个批次 · 一买一批跟踪（含回合盈亏）',
                  style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
              const SizedBox(height: 10),
              // 整块可纵向滚动（批次多时防溢出），表格横向滚动
              Flexible(
                child: SingleChildScrollView(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      if (error != null)
                        Text('批次明细加载失败：$error',
                            style: const TextStyle(fontSize: 12, color: AppColors.darkOrange))
                      else if (visible.isEmpty)
                        const Text('这只股票还没有批次记录',
                            style: TextStyle(fontSize: 12, color: AppColors.darkGrey5))
                      else
                        SingleChildScrollView(
                          scrollDirection: Axis.horizontal,
                          child: DataTable(
                            headingRowColor: WidgetStatePropertyAll(AppColors.darkSurface2.withValues(alpha: 0.5)),
                            dataRowColor: WidgetStatePropertyAll(Colors.transparent),
                            headingTextStyle: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppColors.darkGrey5),
                            columnSpacing: 24,
                            horizontalMargin: 12,
                            columns: const [
                              DataColumn(label: Text('买入日期')),
                              DataColumn(label: Text('剩余/买入'), numeric: true),
                              DataColumn(label: Text('成本'), numeric: true),
                              DataColumn(label: Text('现价'), numeric: true),
                              DataColumn(label: Text('盈亏'), numeric: true),
                              DataColumn(label: Text('盈亏%'), numeric: true),
                              DataColumn(label: Text('止损'), numeric: true),
                              DataColumn(label: Text('距止损%'), numeric: true),
                              DataColumn(label: Text('买点')),
                              DataColumn(label: Text('角色')),
                              DataColumn(label: Text('状态')),
                            ],
                            rows: visible.map((l) {
                              // 已清仓回合：盈亏列显示整批已实现盈亏；持有中/初始底仓显示剩余部分浮动盈亏
                              final pnl = l.closed ? l.realizedPnl : l.pnl;
                              // #132 红涨绿亏（A股）：盈=红、亏=绿
                              final pnlColor = pnl >= 0 ? AppColors.darkRed : AppColors.darkGreen;
                              final stop = l.stopLossPrice;
                              final distance = l.stopLossDistancePct;
                              // 已清仓优先（含 initial&&closed 的初始底仓被卖完——状态与盈亏列口径一致，都按回合）
                              final statusText = l.closed
                                  ? '已清仓'
                                  : l.initial
                                      ? '初始底仓'
                                      : '持有中';
                              final statusColor = l.closed
                                  ? AppColors.darkGrey4
                                  : l.initial
                                      ? AppColors.darkPurple
                                      : AppColors.darkBlue;
                              return DataRow(cells: [
                                DataCell(Text(l.buyDate,
                                    style: const TextStyle(fontSize: 12, color: AppColors.darkGrey1))),
                                DataCell(Text('${_fmtThousandsInt(l.remaining)} / ${_fmtThousandsInt(l.volume)}',
                                    style: const TextStyle(fontSize: 12, color: AppColors.darkGrey3))),
                                DataCell(Text(l.costPrice.toStringAsFixed(3),
                                    style: const TextStyle(fontSize: 12, color: AppColors.darkGrey3))),
                                DataCell(Text(l.currentPrice.toStringAsFixed(3),
                                    style: const TextStyle(fontSize: 12, color: AppColors.darkGrey1))),
                                DataCell(Text(l.closed ? '回合 ${_fmtThousands(l.realizedPnl)}' : _fmtThousands(l.pnl),
                                    style: TextStyle(fontSize: 12, color: pnlColor, fontWeight: FontWeight.w600))),
                                DataCell(Text(_lotPnlPctText(l),
                                    style: TextStyle(fontSize: 12, color: pnlColor))),
                                DataCell(Text(stop != null && stop > 0 ? stop.toStringAsFixed(3) : '—',
                                    style: const TextStyle(fontSize: 12, color: AppColors.darkGrey3))),
                                DataCell(Text(distance != null ? '${distance.toStringAsFixed(2)}%' : '—',
                                    style: TextStyle(fontSize: 12,
                                        color: distance != null && distance < 0 ? AppColors.darkOrange : AppColors.darkGrey3))),
                                DataCell(Text(l.buyPoint ?? '—',
                                    style: const TextStyle(fontSize: 12, color: AppColors.darkGrey3))),
                                DataCell(Text(l.role ?? '—',
                                    style: const TextStyle(fontSize: 12, color: AppColors.darkGrey3))),
                                DataCell(Text(statusText,
                                    style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: statusColor))),
                              ]);
                            }).toList(),
                          ),
                        ),
                      // 对账提示：只显示当前股票的对账行（后端可能返回全量，按 symbol 过滤防串股）；
                      // note 含「≠」= 流水与持仓不一致（黄色/橙色警告行，以持仓快照为准）
                      if (reconcile.any((r) => r.symbol == symbol)) ...[
                        const SizedBox(height: 10),
                        const Text('对账提示（流水净增减 vs 当前持仓，以持仓快照为准）：',
                            style: TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
                        const SizedBox(height: 2),
                        for (final r in reconcile.where((r) => r.symbol == symbol))
                          Padding(
                            padding: const EdgeInsets.only(bottom: 2),
                            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                              if (r.note.contains('≠'))
                                const Padding(
                                  padding: EdgeInsets.only(right: 4, top: 1),
                                  child: Icon(Icons.warning_amber_rounded, size: 12, color: AppColors.darkOrange),
                                ),
                              Expanded(
                                child: Text('${r.name}（${r.symbol}）：${r.note}',
                                    style: TextStyle(fontSize: 11,
                                        color: r.note.contains('≠') ? AppColors.darkOrange : AppColors.darkGrey2)),
                              ),
                            ]),
                          ),
                      ],
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ─────────────────────────── 历史成交 Tab（RFC 20260823，取代交易历史 Dialog） ───────────────────────────

/// B6-5（2026-08-23，P1-交易17）：监听 DefaultTabController 的 index——切到历史成交 Tab（index 4）
/// 时回调 onHistorySelected（TradingPage 用它驱动 _HistorySection.refreshSilently，防 keepAlive 陈旧）。
/// 案例详情弹窗（第四阶段环 3：K 线还原 + 特征/后验 + AI 理解）。
/// 独立顶层 StatefulWidget（Dart 禁类内嵌类）；「生成 AI 理解」→ POST /cases/{id}/insight。
class _CaseDetailDialog extends StatefulWidget {
  const _CaseDetailDialog({required this.api, required this.caseId});
  final ApiService api;
  final String caseId;

  @override
  State<_CaseDetailDialog> createState() => _CaseDetailDialogState();
}

class _CaseDetailDialogState extends State<_CaseDetailDialog> {
  Map<String, dynamic>? _record;
  List<Map<String, dynamic>> _kline = const [];
  bool _loading = true;
  String? _error;
  bool _generating = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final detail = await widget.api.getCaseDetail(widget.caseId, kline: true);
      if (!mounted) return;
      setState(() {
        _record = (detail['caseRecord'] as Map<String, dynamic>?) ?? detail;
        _kline = ((detail['kline'] as List<dynamic>?) ?? const []).cast<Map<String, dynamic>>();
        _loading = false;
        _error = null;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = extractApiErrorMessage(e);
      });
    }
  }

  Future<void> _generate() async {
    setState(() => _generating = true);
    try {
      final updated = await widget.api.generateCaseInsight(widget.caseId);
      if (!mounted) return;
      setState(() {
        _record = updated;
        _generating = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _generating = false);
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('AI 理解失败：${extractApiErrorMessage(e)}'),
          backgroundColor: AppColors.darkSurface2));
    }
  }

  String fmt(dynamic v, {String suffix = ''}) => v == null ? '—' : '$v$suffix';

  Widget _chip(String text, {bool highlight = false}) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.circular(6),
        border: Border.all(
            color: highlight
                ? AppColors.darkGreen.withValues(alpha: 0.6)
                : AppColors.darkBorder.withValues(alpha: 0.5)),
      ),
      child: Text(text, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey2)),
    );
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const AlertDialog(
        backgroundColor: AppColors.darkSurface2,
        content: SizedBox(
          width: 520,
          child: Text('案例加载中…', style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
        ),
      );
    }
    if (_error != null) {
      return AlertDialog(
        backgroundColor: AppColors.darkSurface2,
        content: SizedBox(
          width: 520,
          child: Text('加载失败：$_error',
              style: const TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('关闭', style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
          ),
        ],
      );
    }
    final record = _record ?? const <String, dynamic>{};
    final features = (record['features'] as Map<String, dynamic>?) ?? const {};
    final verify = (record['verify'] as Map<String, dynamic>?) ?? const {};
    final insight = (record['aiInsight'] as Map<String, dynamic>?) ?? const {};
    final buyDate = '${record['buyDate'] ?? ''}';
    final insightSummary = '${insight['summary'] ?? ''}';
    final hasInsight = insightSummary.isNotEmpty;
    return AlertDialog(
      backgroundColor: AppColors.darkSurface2,
      title: Text('${record['name'] ?? record['symbol']}（${record['symbol']}）· ${record['buyType'] ?? ''} · $buyDate',
          style: const TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
      content: SizedBox(
        width: 620,
        child: SingleChildScrollView(
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            CaseKlineChart(kline: _kline, buyDate: buyDate),
            const SizedBox(height: 10),
            if ('${record['description'] ?? ''}'.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Text('「${record['description']}」',
                    style: const TextStyle(fontSize: 12, color: AppColors.darkGrey2)),
              ),
            Wrap(
              spacing: 8,
              runSpacing: 6,
              children: [
                _chip('回撤 ${fmt(features['drawdownFromHighPct'], suffix: '%')}'),
                _chip('量比 ${fmt(features['volumeShrinkRatio'])}'),
                _chip('KDJ.J ${fmt(features['kdjJ'])}'),
                _chip('距60日线 ${fmt(features['distToMa60Pct'], suffix: '%')}'),
                _chip('黄白线 ${features['yellowLineState'] ?? '—'}'),
                _chip('盘整 ${fmt(features['sidewaysDays'], suffix: '天')}'),
                _chip('破前高 ${features['breakoutFromHigh'] == true ? '是' : '否'}'),
              ],
            ),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 6,
              children: [
                _chip('+5d ${fmt(verify['+5dReturnPct'], suffix: '%')}', highlight: true),
                _chip('+10d ${fmt(verify['+10dReturnPct'], suffix: '%')}', highlight: true),
                _chip('最大回撤 ${fmt(verify['maxDrawdownAfterBuyPct'], suffix: '%')}', highlight: true),
                _chip('破止损 ${verify['stopLossHit'] == true ? '是' : '否'}', highlight: true),
              ],
            ),
            const SizedBox(height: 12),
            // 环 3：AI 理解（aiInsight）
            if (hasInsight) ...[
              Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: AppColors.darkSurface,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: AppColors.darkGreen.withValues(alpha: 0.4)),
                ),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Row(children: [
                    const Text('阿呆的理解',
                        style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.darkGreen)),
                    const Spacer(),
                    Text('置信度 ${fmt(insight['confidence'])}',
                        style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
                  ]),
                  const SizedBox(height: 6),
                  Text(insightSummary,
                      style: const TextStyle(fontSize: 12, color: AppColors.darkGrey2, height: 1.5)),
                  if ((insight['keyFeatures'] as List<dynamic>?)?.isNotEmpty ?? false) ...[
                    const SizedBox(height: 6),
                    Wrap(
                      spacing: 6,
                      runSpacing: 4,
                      children: (insight['keyFeatures'] as List<dynamic>)
                          .map((k) => Container(
                                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                                decoration: BoxDecoration(
                                  color: AppColors.darkSurface2,
                                  borderRadius: BorderRadius.circular(4),
                                  border: Border.all(color: AppColors.darkBorder),
                                ),
                                child: Text('$k',
                                    style: const TextStyle(fontSize: 10, color: AppColors.darkGrey4)),
                              ))
                          .toList(),
                    ),
                  ],
                ]),
              ),
            ] else
              OutlinedButton.icon(
                onPressed: _generating ? null : _generate,
                icon: _generating
                    ? const SizedBox(
                        width: 12,
                        height: 12,
                        child: CircularProgressIndicator(strokeWidth: 1.5, color: AppColors.darkGreen),
                      )
                    : const Icon(Icons.auto_awesome, size: 14, color: AppColors.darkGreen),
                label: Text(_generating ? '理解中…' : '生成 AI 理解',
                    style: const TextStyle(fontSize: 12)),
                style: OutlinedButton.styleFrom(
                    foregroundColor: AppColors.darkGrey1,
                    side: const BorderSide(color: AppColors.darkGrey4),
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4)),
              ),
          ]),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('关闭', style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
        ),
      ],
    );
  }
}

class _TabHistoryRefreshListener extends StatefulWidget {
  final VoidCallback onHistorySelected;
  final Widget child;

  const _TabHistoryRefreshListener({required this.onHistorySelected, required this.child});

  @override
  State<_TabHistoryRefreshListener> createState() => _TabHistoryRefreshListenerState();
}

class _TabHistoryRefreshListenerState extends State<_TabHistoryRefreshListener> {
  TabController? _controller;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _controller?.removeListener(_onChanged);
    _controller = DefaultTabController.of(context);
    _controller?.addListener(_onChanged);
  }

  @override
  void dispose() {
    _controller?.removeListener(_onChanged);
    super.dispose();
  }

  void _onChanged() {
    if (_controller?.index == 4) widget.onHistorySelected();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}

/// 历史成交 Tab 内容：日期范围查询 + 按日分组全字段流水列表 + 导入历史成交入口。
/// 2026-08-23：从页头 Dialog 升级为常驻第 5 Tab（RFC 20260823-trading-history-tab-backfill）；
/// 进 Tab 自动加载 + 手动刷新，不做定时轮询（保活页陈旧问题，切页刷新兜底）。
class _HistorySection extends StatefulWidget {
  final ApiService api;

  const _HistorySection({super.key, required this.api});

  @override
  State<_HistorySection> createState() => _HistorySectionState();
}

class _HistorySectionState extends State<_HistorySection>
    with AutomaticKeepAliveClientMixin {
  late DateTime _from;
  late DateTime _to;
  List<TradeRecordItem>? _trades;
  bool _loading = true;
  String? _error;
  int _loadGen = 0; // 代际令牌（2026-08-17 走查）：快速切换起止日期时旧响应不覆盖新查询
  HistoricalTradeImportResult? _importResult; // 最近一次导入结果（导入后 inline 展示，含 updated）

  // B5-6（2026-08-23）：历史成交 Tab keepAlive——切 Tab 不再 dispose/重建重复发 _load() 请求；
  // 数据可变（导入后手动刷新/切页刷新兜底），不引入定时轮询
  @override
  bool get wantKeepAlive => true;

  @override
  void initState() {
    super.initState();
    final now = DateTime.now();
    _to = now;
    _from = now.subtract(const Duration(days: 30));
    _load();
  }

  /// B6-5（2026-08-23，P1-交易17）：切回 Tab 静默刷新——keepAlive 不重建，
  /// 收盘/他端变更后靠此防陈旧（不闪 loading，旧数据保留到新数据到达）。
  void refreshSilently() {
    final gen = ++_loadGen;
    widget.api.getTrades(from: _fmt(_from), to: _fmt(_to)).then((trades) {
      if (!mounted || gen != _loadGen) return;
      setState(() {
        _trades = trades;
        _loading = false;
        _error = null;
      });
    }).catchError((e) {
      if (!mounted || gen != _loadGen) return;
      // 静默失败保留旧数据（与主数据刷新同口径：不整页错误态）
      setState(() => _loading = false);
    });
  }

  static String _fmt(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

  Future<void> _load() async {
    final gen = ++_loadGen;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final trades = await widget.api.getTrades(from: _fmt(_from), to: _fmt(_to));
      if (!mounted || gen != _loadGen) return; // 旧代丢弃（快速改日期时）
      setState(() {
        _trades = trades;
        _loading = false;
      });
    } catch (e) {
      if (!mounted || gen != _loadGen) return;
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

  /// RFC 20260823：历史成交导入（独立入口，只认通达信历史成交导出格式）。
  Future<void> _showImport() async {
    await showDialog<void>(
      context: context,
      builder: (_) => _HistoryImportDialog(
        api: widget.api,
        onImported: (result) {
          if (!mounted) return;
          setState(() => _importResult = result);
          _load();
        },
      ),
    );
  }

  /// 一键按流水重建持仓（2026-08-25）：历史成交导入后持仓快照可能过期——
  /// 已清仓股票（如中电电机）从持仓移除，流水解释不了的真实底仓保留。
  Future<void> _syncPositions() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('一键同步持仓'),
        content: const Text('以流水为准重建持仓：已清仓的股票会自动从持仓移除，流水解释不了的真实底仓会保留。确认同步？'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('取消')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('确认同步')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    try {
      final r = await widget.api.syncPositions();
      if (!mounted) return;
      final sb = StringBuffer('同步完成：持仓 ${r.positionCount} 只');
      if (r.removed.isNotEmpty) {
        sb.write('；已移除已清仓残留 ${r.removed.length} 只（${r.removed.join('、')}）');
      }
      if (r.keptInitial.isNotEmpty) {
        sb.write('；保留真实底仓 ${r.keptInitial.join('、')}');
      }
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(sb.toString())));
      _load(); // 刷新持仓/批次
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('同步失败：${extractApiErrorMessage(e)}')));
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

  /// 金额千分位（与页面账户卡同口径）。
  static String _thousands(double v) {
    final neg = v < 0;
    final s = v.abs().toStringAsFixed(2);
    final parts = s.split('.');
    final buf = StringBuffer();
    final intPart = parts[0];
    for (var i = 0; i < intPart.length; i++) {
      buf.write(intPart[i]);
      final remaining = intPart.length - 1 - i;
      if (remaining > 0 && remaining % 3 == 0) buf.write(',');
    }
    return '${neg ? '-' : ''}$buf.${parts[1]}';
  }

  @override
  Widget build(BuildContext context) {
    super.build(context); // B5-6：keepAlive 必须调用
    final trades = _trades ?? <TradeRecordItem>[];
    // P2-批次6：股息类资金事件（volume=0）不算买卖笔数——统计口径只计真实成交
    final buyCount = trades.where((t) => t.isBuy && !t.isDividendEvent).length;
    final sellCount = trades.where((t) => !t.isBuy && !t.isDividendEvent).length;
    final dividendCount = trades.where((t) => t.isDividendEvent).length;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      // 工具行：日期范围 + 刷新 + 导入历史成交
      Row(children: [
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
        const SizedBox(width: 4),
        IconButton(
          onPressed: _load,
          icon: const Icon(Icons.refresh, size: 16),
          color: AppColors.darkGrey4,
          tooltip: '重新加载',
        ),
        const Spacer(),
        OutlinedButton.icon(
          onPressed: _showImport,
          icon: const Icon(Icons.upload_file, size: 14),
          label: const Text('导入历史成交', style: TextStyle(fontSize: 12)),
          style: OutlinedButton.styleFrom(
              foregroundColor: AppColors.darkGrey1,
              side: const BorderSide(color: AppColors.darkGrey4),
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4)),
        ),
        const SizedBox(width: 6),
        // 2026-08-25：一键按流水重建持仓（历史成交导入后，已清仓残留自动移除——如中电电机）
        OutlinedButton.icon(
          onPressed: _syncPositions,
          icon: const Icon(Icons.sync, size: 14),
          label: const Text('一键同步', style: TextStyle(fontSize: 12)),
          style: OutlinedButton.styleFrom(
              foregroundColor: AppColors.darkBlue,
              side: const BorderSide(color: AppColors.darkBlue),
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4)),
        ),
      ]),
      const SizedBox(height: 8),
      // 区间统计行：共 N 笔 · 买 X 卖 Y（纯客观）
      if (!_loading && _error == null && trades.isNotEmpty)
        Padding(
          padding: const EdgeInsets.only(bottom: 6),
          child: Text('共 ${trades.length} 笔 · 买 $buyCount 卖 $sellCount'
              '${dividendCount > 0 ? ' · 股息/红利 $dividendCount' : ''}',
              style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
        ),
      // 最近导入结果（inline，含 updated 回填计数）
      if (_importResult != null) ...[
        Container(
          width: double.infinity,
          padding: const EdgeInsets.all(8),
          decoration: BoxDecoration(
            color: AppColors.darkSurface,
            borderRadius: BorderRadius.circular(8),
            border: Border.all(color: AppColors.darkGrey4, width: 0.5),
          ),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('导入完成：新增 ${_importResult!.imported} 笔'
                '${_importResult!.updated > 0 ? ' · 回填成交时间 ${_importResult!.updated} 笔' : ''}'
                ' · 跳过 ${_importResult!.skipped} 笔'
                '${_importResult!.nonTrades > 0 ? ' · 非交易事件 ${_importResult!.nonTrades} 行' : ''}',
                style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.darkGreen)),
            // RFC 20260825：syncMode + 每日操作总结（sync=总结卡+行为标注；append=补录提示）
            _ImportResultSummary(result: _importResult!),
            if (_importResult!.lines.isNotEmpty) ...[
              const SizedBox(height: 4),
              Text('对账提示（流水净增减 vs 当前持仓，以持仓快照为准）：',
                  style: const TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
              const SizedBox(height: 2),
              for (final l in _importResult!.lines)
                Padding(
                  padding: const EdgeInsets.only(bottom: 2),
                  child: Text('${l.name}（${l.symbol}）：${l.netVolume > 0 ? '+' : ''}${l.netVolume} 股 → ${l.note}',
                      style: const TextStyle(fontSize: 11, color: AppColors.darkGrey2)),
                ),
            ],
          ]),
        ),
        const SizedBox(height: 6),
      ],
      const SizedBox(height: 4),
      Expanded(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : _error != null
                ? Center(child: Text('加载失败\n$_error', style: const TextStyle(fontSize: 13, color: AppColors.darkGrey5)))
                : trades.isEmpty
                    ? Center(
                        child: Column(mainAxisSize: MainAxisSize.min, children: [
                          const Text('这段时间还没有历史成交', style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
                          const SizedBox(height: 6),
                          OutlinedButton.icon(
                            onPressed: _showImport,
                            icon: const Icon(Icons.upload_file, size: 14),
                            label: const Text('导入通达信历史成交导出', style: TextStyle(fontSize: 12)),
                            style: OutlinedButton.styleFrom(
                                foregroundColor: AppColors.darkGrey1,
                                side: const BorderSide(color: AppColors.darkGrey4),
                                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4)),
                          ),
                        ]),
                      )
                    : SingleChildScrollView(
                        // 全字段列较多 → 横向滚动；外层纵向滚动
                        child: SingleChildScrollView(
                          scrollDirection: Axis.horizontal,
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              _buildListHeader(),
                              ..._grouped().entries.map((e) => _buildDateGroup(e.key, e.value)),
                            ],
                          ),
                        ),
                      ),
      ),
    ]);
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
        cell('时间', 48), // RFC 20260822：成交时间（客观数据，旧数据 '—'）
        cell('代码', 72),
        cell('名称', 88),
        cell('数量', 60, right: true),
        cell('价格', 70, right: true),
        // 2026-08-25 列设计（用户拍板）：源文件原生字段在前——成交金额/发生金额（通达信原始，
        // 买入为负扣款）；成交编号（源文件标识）；「费用」= |发生金额−成交金额| 系统计算放最后
        cell('成交金额', 90, right: true),
        cell('发生金额', 100, right: true),
        cell('成交编号', 110),
        cell('费用', 60, right: true),
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

  /// 发生金额（源文件原生）：买入为负（扣款），卖出为正（到账）。
  /// 系统存储 fee = |发生金额 − 成交金额|，据此反推；fee 缺失（手动记录/旧数据）→ '—'。
  /// 股息类事件（P2-批次6）：amount 即发生金额绝对值，入账为正（现金流入）/ 税为负。
  static String _occurredAmount(TradeRecordItem t) {
    if (t.isDividendEvent) {
      return _thousands(t.isBuy ? t.amount : -t.amount);
    }
    if (t.fee == null) return '—';
    final occurred = t.isBuy ? -(t.amount + t.fee!) : (t.amount - t.fee!);
    return _thousands(occurred);
  }

  Widget _buildTradeRow(TradeRecordItem t) {
    Widget cell(String text, double width, {bool right = false, Color? color}) => SizedBox(
          width: width,
          child: Text(text,
              textAlign: right ? TextAlign.right : TextAlign.left,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(fontSize: 12, color: color ?? AppColors.darkGrey3)),
        );
    // P2-批次6：股息类资金事件（volume=0）不走买卖行——方向列显示类型标签，
    // 数量/价格/成交编号/费用为 '—'，发生金额 = ±amount（入账正 / 税负）。
    if (t.isDividendEvent) {
      // 红涨绿亏（买红卖绿同语义）：现金流入（股息入账，BUY）红、现金流出（红利税，SELL）绿
      final dirColor = t.isBuy ? AppColors.darkRed : AppColors.darkGreen;
      return Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
        child: Row(children: [
          cell(t.dividendLabel, 44, color: dirColor),
          cell('—', 48, color: AppColors.darkGrey5),
          cell(t.symbol, 72, color: AppColors.darkGrey1),
          cell(t.name, 88),
          cell('—', 60, right: true),
          cell('—', 70, right: true),
          cell('—', 90, right: true),
          cell(_occurredAmount(t), 100, right: true),
          cell('—', 110, color: AppColors.darkGrey5),
          cell('—', 60, right: true),
        ]),
      );
    }
    final dirColor = t.isBuy ? AppColors.darkGrey1 : AppColors.darkGrey3;
    // RFC 20260822：成交时间（HH:mm），旧数据无 → '—'
    final timeStr = (t.tradeTime != null && t.tradeTime!.length >= 5)
        ? t.tradeTime!.substring(0, 5)
        : '—';
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
      child: Row(children: [
        cell(t.isBuy ? '买入' : '卖出', 44, color: dirColor),
        cell(timeStr, 48, color: AppColors.darkGrey5),
        cell(t.symbol, 72, color: AppColors.darkGrey1),
        cell(t.name, 88),
        cell('${t.volume}', 60, right: true),
        cell(t.price.toStringAsFixed(3), 70, right: true),
        cell(_thousands(t.amount), 90, right: true), // 成交金额（源文件）
        cell(_occurredAmount(t), 100, right: true), // 发生金额（源文件原生，推导自 fee）
        cell(t.orderId ?? '—', 110, color: AppColors.darkGrey5),
        cell(t.fee != null ? t.fee!.toStringAsFixed(2) : '—', 60, right: true), // 系统计算放最后
      ]),
    );
  }
}

// ─────────────────────────── 历史成交导入结果补充（RFC 20260825：syncMode + 每日操作总结） ───────────────────────────

/// 导入结果补充展示（Dialog 内与历史成交 Tab inline 共用）：
/// syncMode=sync → 当日操作总结卡（标题带成交日期，如「8/22 操作」）+ 行为标注（亏损加仓/追高等醒目色）；
/// syncMode=append → 补录提示（只补流水，持仓未动）；summary 缺失（append）不报错。
class _ImportResultSummary extends StatelessWidget {
  final HistoricalTradeImportResult result;

  const _ImportResultSummary({required this.result});

  @override
  Widget build(BuildContext context) {
    final summary = result.summary;
    if (result.syncMode == 'sync' && summary != null) {
      return Container(
        width: double.infinity,
        margin: const EdgeInsets.only(top: 6),
        padding: const EdgeInsets.all(8),
        decoration: BoxDecoration(
          color: AppColors.darkGreen.withValues(alpha: 0.10),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: AppColors.darkGreen.withValues(alpha: 0.35)),
        ),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          // 标题带成交日期（sync 窗口跨多日，未必是今天；date 缺失回落「今日操作」）
          Text('${summary.date.isEmpty ? '今日操作' : '${_fmtShortDate(summary.date)} 操作'}'
              '：买 ${summary.buyCount} 笔 ¥${_fmtThousands(summary.buyAmount)}'
              ' · 卖 ${summary.sellCount} 笔 ¥${_fmtThousands(summary.sellAmount)}'
              ' · 新增批次 ${summary.newLots} · 扣减批次 ${summary.deductedLots}',
              style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.darkGreen)),
          if (summary.behaviors.isNotEmpty) ...[
            const SizedBox(height: 5),
            for (final b in summary.behaviors)
              Padding(
                padding: const EdgeInsets.only(bottom: 3),
                child: Text.rich(
                  TextSpan(children: [
                    TextSpan(text: '${b.label} · ',
                        style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: _behaviorColor(b.type))),
                    TextSpan(text: '${b.name}（${b.symbol}）',
                        style: const TextStyle(fontSize: 11, color: AppColors.darkGrey2)),
                    TextSpan(text: '：${b.message}',
                        style: const TextStyle(fontSize: 11, color: AppColors.darkGrey3)),
                  ]),
                ),
              ),
          ],
        ]),
      );
    }
    if (result.syncMode == 'append') {
      return const Padding(
        padding: EdgeInsets.only(top: 4),
        child: Text('已按历史补录处理（只补流水，持仓未动）',
            style: TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
      );
    }
    return const SizedBox.shrink();
  }
}

// ─────────────────────────── 历史成交导入 Dialog（RFC 20260823，只认历史成交格式） ───────────────────────────

/// 历史成交导入（独立入口，2026-08-23）：只认通达信「历史成交查询」导出格式——
/// 粘贴或选文件 → isTdxHistoryExport 识别 → POST /trades/import（幂等 + 缺失成交时间回填）。
class _HistoryImportDialog extends StatefulWidget {
  final ApiService api;
  /// 导入成功（含 updated 回填）后回调：父 Tab 刷新列表并展示结果。
  final void Function(HistoricalTradeImportResult result) onImported;

  const _HistoryImportDialog({required this.api, required this.onImported});

  @override
  State<_HistoryImportDialog> createState() => _HistoryImportDialogState();
}

/// 导入队列任务（P2-批次4/5，2026-08-29）：一份文件或一段粘贴文本 = 一个 job，
/// 逐份处理并实时展示状态（第 N/共 M、处理中、耗时、成功/跳过/失败）。
class _ImportJob {
  _ImportJob.file(this.name, this.bytes) : content = null;
  _ImportJob.text(this.name, this.content) : bytes = null;

  final String name;
  final List<int>? bytes; // 文件（未上传转码）；粘贴文本为 null
  String? content; // 上传转码后的内容（文件）或直接粘贴的文本
  bool processing = false;
  bool done = false;
  bool failed = false;
  String? error;
  HistoricalTradeImportResult? result;
  int elapsedMs = 0;

  bool get finished => done || failed;
}

/// 多份历史成交导入结果聚合（P2-批次4，2026-08-29 多文件批量）：
/// 计数求和、对账行按 (symbol, netVolume, note) 去重、syncMode 任一 sync 即 sync、
/// summary 取首份非空（多份时以第一份 sync 的操作总结为代表）。
HistoricalTradeImportResult aggregateImportResults(List<HistoricalTradeImportResult> results) {
  final lines = <ReconcileLine>[];
  final seen = <String>{};
  for (final r in results) {
    for (final l in r.lines) {
      final key = '${l.symbol}|${l.netVolume}|${l.note}';
      if (seen.add(key)) lines.add(l);
    }
  }
  TradeImportSummary? summary;
  for (final r in results) {
    if (r.summary != null) { summary = r.summary; break; }
  }
  return HistoricalTradeImportResult(
    imported: results.fold(0, (s, r) => s + r.imported),
    updated: results.fold(0, (s, r) => s + r.updated),
    skipped: results.fold(0, (s, r) => s + r.skipped),
    nonTrades: results.fold(0, (s, r) => s + r.nonTrades),
    lines: lines,
    syncMode: results.any((r) => r.syncMode == 'sync') ? 'sync' : 'append',
    summary: summary,
  );
}

class _HistoryImportDialogState extends State<_HistoryImportDialog> {
  final _text = TextEditingController();
  bool _queueRunning = false;
  final List<_ImportJob> _jobs = [];
  HistoricalTradeImportResult? _result;
  String? _error;

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  /// 选择通达信历史成交导出（可多选）→ 每份入队 → 逐份上传转码 + 导入。
  Future<void> _pickFile() async {
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.any,
        allowMultiple: true, // P2-批次4：一次选/粘贴多份文件
        withData: true,
      );
      if (result == null || result.files.isEmpty) return;
      if (!mounted) return;
      setState(() {
        _result = null;
        _error = null;
        for (final f in result.files) {
          if (f.bytes == null) continue;
          _jobs.add(_ImportJob.file(f.name, f.bytes!));
        }
      });
      await _runQueue();
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = '文件读取失败，请重试');
    }
  }

  /// 粘贴文本导入：单 job 入队（保持原「导入」按钮路径）。
  Future<void> _import() async {
    if (_text.text.trim().isEmpty) {
      setState(() => _error = '请粘贴通达信「历史成交查询」导出文本，或选择文件');
      return;
    }
    setState(() {
      _result = null;
      _error = null;
      _jobs.clear();
      _jobs.add(_ImportJob.text('粘贴文本', _text.text));
    });
    await _runQueue();
  }

  /// 逐份处理队列：上传留存（文件）→ 格式识别 → 导入 → 实时更新状态与耗时。
  Future<void> _runQueue() async {
    if (_queueRunning) return;
    _queueRunning = true;
    for (var i = 0; i < _jobs.length; i++) {
      final job = _jobs[i];
      if (job.finished) continue;
      if (!mounted) { _queueRunning = false; return; }
      setState(() {
        job.processing = true;
        job.failed = false;
        job.error = null;
      });
      final sw = Stopwatch()..start();
      try {
        String content = job.content ?? '';
        if (job.bytes != null) {
          final saved = await widget.api.saveImportFile(job.name, job.bytes!);
          content = saved.content;
        }
        if (!mounted) { _queueRunning = false; return; }
        job.content = content;
        // RFC 20260823：只认通达信历史成交导出——其他格式直接人话拒绝，不静默落零
        if (!isTdxHistoryExport(content)) {
          setState(() {
            job.processing = false;
            job.failed = true;
            job.error = '无法识别——需通达信「历史成交查询」导出（表头含成交日期/证券代码/买卖标志/成交编号）';
            job.elapsedMs = sw.elapsedMilliseconds;
          });
          continue;
        }
        final result = await widget.api.importTradesHistory(content);
        if (!mounted) { _queueRunning = false; return; }
        setState(() {
          job.processing = false;
          job.done = true;
          job.result = result;
          job.elapsedMs = sw.elapsedMilliseconds;
        });
      } catch (e) {
        if (!mounted) { _queueRunning = false; return; }
        // B2-3（2026-08-23）：透出后端人话 error（原 contains('无法识别') 恒 false 吞掉人话）
        setState(() {
          job.processing = false;
          job.failed = true;
          job.error = extractApiErrorMessage(e);
          job.elapsedMs = sw.elapsedMilliseconds;
        });
      }
    }
    _queueRunning = false;
    if (!mounted) return;
    final doneJobs = _jobs.where((j) => j.done).toList();
    if (doneJobs.isNotEmpty) {
      final agg = _aggregate(doneJobs);
      setState(() => _result = agg);
      widget.onImported(agg);
    }
  }

  /// 多份结果聚合（P2-批次4，2026-08-29）：计数求和、对账行去重、summary 取首份 sync
  /// （多份时展示聚合数字 + 汇总对账）。独立顶层函数便于测试。
  static HistoricalTradeImportResult _aggregate(List<_ImportJob> jobs) {
    return aggregateImportResults(jobs.map((j) => j.result!).toList());
  }

  static String _fmtMs(int ms) {
    if (ms < 1000) return '${ms}ms';
    return '${(ms / 1000).toStringAsFixed(1)}s';
  }

  @override
  Widget build(BuildContext context) {
    final finishedCount = _jobs.where((j) => j.finished).length;
    final processing = _jobs.where((j) => j.processing).toList();
    return AlertDialog(
      backgroundColor: AppColors.darkSurface2,
      title: const Text('导入历史成交', style: TextStyle(fontSize: 16, color: AppColors.darkGrey1)),
      content: SizedBox(
        width: 520,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('只认通达信「历史成交查询」导出：可一次选择多份文件，逐份处理；'
                '补逐笔流水不重算持仓（成交编号幂等；缺成交时间自动回填）',
                style: TextStyle(fontSize: 12, color: AppColors.darkGrey4)),
            const SizedBox(height: 8),
            Row(children: [
              OutlinedButton.icon(
                onPressed: _queueRunning ? null : _pickFile,
                icon: const Icon(Icons.upload_file, size: 16),
                label: Text(_queueRunning ? '处理中…' : '选择文件（可多选，通达信导出 txt）',
                    style: const TextStyle(fontSize: 12, color: AppColors.darkGrey1)),
                style: OutlinedButton.styleFrom(
                  foregroundColor: AppColors.darkGrey1,
                  side: const BorderSide(color: AppColors.darkGrey4),
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                ),
              ),
              const SizedBox(width: 8),
              const Expanded(
                child: Text('或直接粘贴导出文本', style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
              ),
            ]),
            const SizedBox(height: 8),
            TextField(
              controller: _text,
              maxLines: 8,
              minLines: 4,
              style: const TextStyle(fontSize: 12, color: AppColors.darkGrey1),
              decoration: const InputDecoration(
                hintText: '粘贴通达信「历史成交查询」导出文本…',
                alignLabelWithHint: true,
              ),
            ),
            // P2-批次5：逐份处理状态实时可见（第 N/共 M、处理中、耗时、成功/跳过/失败）
            if (_jobs.isNotEmpty) ...[
              const SizedBox(height: 10),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: AppColors.darkSurface,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: AppColors.darkGrey4, width: 0.5),
                ),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text('共 ${_jobs.length} 份 · 已完成 $finishedCount'
                      '${processing.isNotEmpty ? ' · 处理中：${processing.first.name}' : ''}',
                      style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppColors.darkGrey2)),
                  const SizedBox(height: 4),
                  for (final j in _jobs)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 2),
                      child: Row(children: [
                        if (j.processing)
                          const SizedBox(width: 10, height: 10, child: CircularProgressIndicator(strokeWidth: 2))
                        else if (j.done)
                          const Icon(Icons.check_circle, size: 12, color: AppColors.darkGreen)
                        else if (j.failed)
                          const Icon(Icons.error, size: 12, color: AppColors.darkOrange)
                        else
                          const SizedBox(width: 10),
                        const SizedBox(width: 6),
                        Expanded(
                          child: Text(j.name,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(fontSize: 11, color: AppColors.darkGrey3)),
                        ),
                        if (j.processing)
                          const Text('导入中…', style: TextStyle(fontSize: 11, color: AppColors.darkGrey4))
                        else if (j.done && j.result != null)
                          Text('新增 ${j.result!.imported} · 跳过 ${j.result!.skipped}'
                              '${j.result!.updated > 0 ? ' · 回填 ${j.result!.updated}' : ''}'
                              ' · ${_fmtMs(j.elapsedMs)}',
                              style: const TextStyle(fontSize: 11, color: AppColors.darkGrey4))
                        else if (j.failed)
                          Flexible(
                            child: Text(j.error ?? '失败',
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(fontSize: 11, color: AppColors.darkOrange)),
                          ),
                      ]),
                    ),
                ]),
              ),
            ],
            const SizedBox(height: 10),
            if (_result != null) ...[
              Text('导入完成：新增 ${_result!.imported} 笔'
                  '${_result!.updated > 0 ? ' · 回填成交时间 ${_result!.updated} 笔' : ''}'
                  ' · 跳过 ${_result!.skipped} 笔'
                  '${_result!.nonTrades > 0 ? ' · 非交易事件 ${_result!.nonTrades} 行' : ''}',
                  style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.darkGreen)),
              // RFC 20260825：syncMode + 每日操作总结（sync=总结卡+行为标注；append=补录提示）
              _ImportResultSummary(result: _result!),
              if (_result!.lines.isNotEmpty) ...[
                const SizedBox(height: 4),
                const Text('对账提示：', style: TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
                const SizedBox(height: 2),
                for (final l in _result!.lines)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 2),
                    child: Text('${l.name}（${l.symbol}）：${l.netVolume > 0 ? '+' : ''}${l.netVolume} 股 → ${l.note}',
                        style: const TextStyle(fontSize: 11, color: AppColors.darkGrey2)),
                  ),
              ],
            ],
            if (_error != null)
              Text(_error!, style: const TextStyle(fontSize: 12, color: AppColors.darkOrange)),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('关闭', style: TextStyle(fontSize: 13, color: AppColors.darkGrey4)),
        ),
        FilledButton(
          onPressed: _queueRunning ? null : _import,
          style: FilledButton.styleFrom(backgroundColor: AppColors.darkGreen, foregroundColor: AppColors.darkBg),
          child: Text(_queueRunning ? '导入中…' : '导入', style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
        ),
      ],
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

/// RFC 20260817：推送设置对话框——逐类型开关（早盘/午间/尾盘/买点/预警/行情条）。
class _PushSettingsDialog extends StatefulWidget {
  final Map<String, bool> settings;
  /// 切换回调：返回 null=成功；返回字符串=失败原因（B5-6，P2-推送5 半修残留——失败不再静默）。
  final Future<String?> Function(String type, bool on) onToggle;
  /// 失败时提示（在 dialog 外的 messenger 上弹，避免 dialog 内无页面 context）。
  final void Function(String message)? onToggleFailed;

  const _PushSettingsDialog({required this.settings, required this.onToggle, this.onToggleFailed});

  @override
  State<_PushSettingsDialog> createState() => _PushSettingsDialogState();
}

class _PushSettingsDialogState extends State<_PushSettingsDialog> {
  late Map<String, bool> _settings = Map.of(widget.settings);

  static const List<(String, String)> _items = [
    ('session', '时段节奏（早盘/午间/尾盘/收盘确认）'), // B11-3：注明含 15:15 收盘操作确认
    ('buy-point', '买点提醒'),
    ('close-summary', '收盘小结（当日成交+破止损+待确认）'), // P2-用户3 2026-08-29
    ('stop-loss', '止损预警'),
    ('near-stop-loss', '接近止损'),
    ('loss', '单日大跌提醒'),
    ('gain', '放飞提示'),
    ('break-cost', '跌破成本线'),
    ('market', '大盘行情条'),
  ];

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      backgroundColor: AppColors.darkSurface2,
      title: const Text('推送设置',
        style: TextStyle(fontSize: 16, color: AppColors.darkGrey1)),
      content: SizedBox(
        width: 320,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (final (type, label) in _items)
              SwitchListTile(
                dense: true,
                contentPadding: EdgeInsets.zero,
                title: Text(label,
                  style: const TextStyle(fontSize: 13, color: AppColors.darkGrey2)),
                value: _settings[type] ?? true,
                activeTrackColor: AppColors.darkGreen,
                onChanged: (on) async {
                  // B5-6（2026-08-23，P2-推送5 半修残留）：成功才翻转 + 失败透出原因
                  final err = await widget.onToggle(type, on);
                  if (!mounted) return;
                  if (err == null) {
                    setState(() => _settings[type] = on);
                  } else {
                    widget.onToggleFailed?.call(err);
                  }
                },
              ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('完成', style: TextStyle(color: AppColors.darkGrey3)),
        ),
      ],
    );
  }
}
