import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';
import '../utils/trade_import_parser.dart';
import '../widgets/case_kline_chart.dart';
import '../widgets/page_header.dart';
import 'dart:async';
import 'dart:convert';
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

/// 当日口径数值 → 文案：null（缺昨收 / 总资产为 0 / 旧后端）一律「—」。
/// **严禁**渲染成 0 / 0.00%——那是谎报「不赚不亏」。
String _fmtDailyMoney(double? v) => v == null ? '—' : v.toStringAsFixed(2);
String _fmtDailyPct(double? v) => v == null ? '—' : '${v.toStringAsFixed(2)}%';

/// m6（2026-10-07 · 原型 .wd-eye「👁 看金额」）：**金额与数量类默认打码**——
/// 设计口径：数量与成本打码、现价与止损保留（uiux-discovery §隐私层）；
/// 页头 👁 本地解开显形（服务「递手机给人看」场景），不持久化。
/// 掩码 = 固定 `••••`（原型 .masked 点数手写随意、无算法；设计文字即 `••••`）。
/// `'—'` / 空串 = 「没有数据」，不是金额 → 原样透出（掩码与缺数据是两回事）。
const kAmountMask = '••••';
String maskIf(String s, bool revealed) =>
    (revealed || s == '—' || s.isEmpty) ? s : kAmountMask;

/// 盈亏/涨跌着色：本项目**红涨绿亏**（token 名含 darkRed）＝正红负绿，
/// 不是 A 股默认的绿涨红跌。null → 灰（「—」不借涨跌色）。
Color _dailyUpDownColor(double? v) =>
    v == null ? AppColors.darkGrey5 : (v >= 0 ? AppColors.darkRed : AppColors.darkGreen);

/// 快照日期 `yyyy-MM-dd` → `MM-dd`（账户卡来源小字用，P2-交易48）。
/// 非该格式（短/空/非数字）→ null（只报来源，不编造日期）。
String? _snapshotMonthDay(String snapshotDate) {
  if (snapshotDate.length < 10) return null;
  final md = snapshotDate.substring(5, 10);
  final month = int.tryParse(md.substring(0, 2));
  final day = int.tryParse(md.substring(3, 5));
  if (month == null || day == null) return null;
  return md;
}

/// 当日盈亏来源小字（P2-交易48，2026-09-14）：`券商口径 · 09-11` / `系统计算 · 09-14（已过期）`。
/// - 来源认不出（null/缺字段/旧后端/未知值）→ null：**不标**（宁可不说，也不编造）；
/// - 快照日不是今天 → 缀「（已过期）」——提示这不是今天的数（同名字段可能是两天前的陈值）；
/// - 日期缺失/非法 → 只报来源。
/// [now] 仅测试注入用。
String? todayPnlSourceNote(String source, String snapshotDate, {DateTime? now}) {
  final s = source.trim().toLowerCase();
  final label = s == 'broker' ? '券商口径' : (s == 'calc' ? '系统计算' : null);
  if (label == null) return null;
  final md = _snapshotMonthDay(snapshotDate);
  if (md == null) return label;
  final t = now ?? DateTime.now();
  final today = '${t.year.toString().padLeft(4, '0')}-'
      '${t.month.toString().padLeft(2, '0')}-${t.day.toString().padLeft(2, '0')}';
  return '$label · $md${snapshotDate.substring(0, 10) == today ? '' : '（已过期）'}';
}

/// 「我手上的行情到哪天」轻提示（P2-交易58 前端侧，2026-10-04）：
/// 后端 health 的 `tdxLastDate` = 本地行情最后一根日期（本地关掉/还没取过 → null）。
/// **阈值与后端同一口径**：`KlineService#tdxStale` 是
/// `ChronoUnit.DAYS.between(last, LocalDate.now()) > 3`——导入后第 1～3 天仍用本地
/// （零网络请求），第 4 天起才去网上补；所以这里也只在 `lag > 3` 时才说。
/// （原先 `lag >= 1` 就说，于是周五导完包、周末与周一（lag 1/2/3）也天天弹一句并不成立的话。）
/// - null / 认不出（旧后端没这字段、格式不是 yyyy-MM-dd、日期不存在）→ null：**不显示**（拿不到 ≠ 滞后）；
/// - 今天及未来日 → null：不制造噪音。
/// [now] 仅测试注入用。
String? tdxLagNote(String? tdxLastDate, {DateTime? now}) {
  if (tdxLastDate == null || tdxLastDate.length < 10) return null;
  final d = DateTime.tryParse(tdxLastDate.substring(0, 10));
  if (d == null) return null;
  // P3（2026-10-04）：日期必须真实存在——`DateTime` 会把 '2026-02-31' 规范化成 03-03、
  // '2026-00-00' 变成上一年 11-30；照原样显示就等于说了一个不存在的日子。回读校验不过 → 不说。
  final iso = '${d.year.toString().padLeft(4, '0')}-'
      '${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
  if (iso != tdxLastDate.substring(0, 10)) return null;
  final t = now ?? DateTime.now();
  final lag = DateTime(t.year, t.month, t.day)
      .difference(DateTime(d.year, d.month, d.day))
      .inDays;
  if (lag <= 3) return null; // 与后端同口径：前 1～3 天用的就是本地，没有缺口、也没走网络
  return '我手上的行情只到 ${iso.substring(5)}，后面几天的我去网上补上';
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

/// 持仓主数据 + 当日口径的打包结果（`_fetchPositions` 用）。
/// daily 为 null＝当日端点拿不到（降级走旧端点）——主数据照常显示，当日三列回落「—」。
class _PositionsLoad {
  final PositionsResponse positions;
  final PositionsDailyResponse? daily;

  const _PositionsLoad(this.positions, this.daily);
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
  // 当日口径（GET /trading/positions/daily）：逐票当日盈亏/今日涨跌幅/仓位占比 + 总仓位/现金比例。
  // 端点失败 → 保持空/null（增强项拿不到不拖垮持仓主数据，表格那三列回落「—」）。
  Map<String, PositionDailyItem> _dailyItems = {};
  double? _totalPositionRatio; // 总仓位 %（几成仓）；null＝总资产为 0 → 整段不显示
  double? _cashRatio; // 现金比例 %
  List<String> _dailyNotes = []; // 非空＝有未计入项，当日盈亏偏小（如实提示）
  List<WatchlistItemDto> _watchlist = [];
  List<BuyPointDto> _buyPoints = []; // C2 自选股买点信号（B1/B2 命中）
  List<SoldScoreDto> _soldScores = []; // D3 清仓复盘三维打分
  bool _scoreLoading = false; // P2-10 打分请求在途标记（防重叠）
  // 2026-10-08 清仓「卖掉之后到现在」：卖出日收盘 → 最新收盘（回答「我卖飞了没」）——
  // 独立端点（同 /sold/score 的 enrich 模式：拉 K 线慢，不拖慢 /sold 基础数据）；按清单顺序索引匹配
  List<SoldAfterCloseDto> _soldAfter = [];
  bool _afterLoading = false; // 卖后涨跌请求在途标记（防重叠）
  bool _auxLoading = false; // 可降级请求在途标记（自选/买点/清仓，防并发覆盖）
  int _auxGen = 0; // 代际令牌：_loadDegradable 防乱序旧响应覆盖新数据
  List<SoldTradeDto> _sold = [];
  // RFC 20260909 批1 清仓双轨：流水已清仓但缺买入基线的待补清单（条件 B 只提示不写脏）——
  // 清仓 Tab 横幅展示，引导导入通达信「清仓股」导出补全复盘档案
  List<PendingClearanceDto> _pendingClearances = [];
  AccountSnapshotDto? _account;
  // 2026-09-15（用户要求）：今日 / 本周 / 本月盈亏（金额 + 比例）；null = 拉取失败/旧后端 → 整行不显示
  PnlPeriodsDto? _pnlPeriods;
  String? _lastUpdated; // 顶部「上次更新」时间戳
  DailyTradeSummaryDto? _dailySummary;
  // ── 次日操作计划（RFC 20261003-trading-plan-and-review-loop §二~四，2026-10-03）──
  // 定位：系统只「记你的话 · 到点提醒 · 收盘对账」——**不生成计划、不给建议**。
  final TextEditingController _planLinesCtrl = TextEditingController();
  final TextEditingController _planNoteCtrl = TextEditingController();
  DateTime _planDate = _defaultPlanDate();

  /// 下个交易日的**默认值**（P1-2，2026-10-03 增量深审）：周五晚进来默认写「周六」，
  /// 而后端提醒与早盘都用 `nextTradingDay`（周五 → 周一）→ 写的日子与读的日子对不上、提醒白推。
  /// 这里只跳周末（节假日仍由用户自己改日期），覆盖绝大多数场景。
  static DateTime _defaultPlanDate() {
    var d = DateTime.now().add(const Duration(days: 1));
    while (d.weekday == DateTime.saturday || d.weekday == DateTime.sunday) {
      d = d.add(const Duration(days: 1));
    }
    return d;
  }
  Map<String, dynamic>? _planView; // null = 这天还没写（后端 404，不编造空壳）
  bool _planLoading = false;
  String? _planMsg; // 最近一次操作的回执（成功/失败人话） // RFC 20260822：当日交易复盘（今日 N 笔 · 时段分布）
  // ── P2-交易72（2026-10-05）：当天事后的状态回填——「今天没动 / 想动，没动」 ──
  // 用户原话：「那我今天没有买卖 怎么告诉你呢 你还在等我的数据」——系统在等一个他**没有地方填**的状态。
  // ⚠️ 必须独立用「今天」：`_planDate` 默认是**下一个交易日**（计划是前晚写的），挂在它上面会记错日子。
  String _todayDayStatus = ''; // '' = 还没记；取值与后端 TradingPlan.DAY_STATUS_* 逐字一致
  bool _dayStatusSaving = false; // 请求在途守卫（防连点并发写）
  String? _dayStatusMsg; // 最近一次回执（成功/失败/「早就记着了」人话）
  // v3.41（2026-09-04）：活跃市值区间（用户手动判定，多头/空头红绿切换）
  String? _marketStage; // bull（多头）| bear（空头）| null（未手动判定）
  bool _marketStageExists = false;
  bool _marketStageLoaded = false; // 端点可用且已加载（区别于加载失败/旧后端）
  String? _marketStageUpdatedAt;
  bool _marketStageSaving = false; // 切换请求在途守卫（防连点并发 PUT）
  bool _loading = true;
  String? _error;
  bool _reviewing = false; // 复盘生成中（#102 交易系统反哺入口）
  bool _lotsDialogOpen = false; // P2-批次1：批次弹窗在途守卫——连点/双击防叠两层 dialog
  // RFC 20260912 账实一致性对账（GET /trading/integrity）——呈现落两处：状态条「账实」格（跨区一行）
  // + 账区自证条（断点明细当场展开）；无差异零噪音
  IntegrityReportDto? _integrity;
  // 2026-10-08 账三合一：账区自证条的「断点明细」展开开关
  bool _accountProofExpanded = false;
  // m6（2026-10-07 · 原型 .wd-eye）：金额/数量打码——默认掩码（数量与成本类），
  // 页头 👁 本地解开显形（服务递手机场景）；不持久化，刷新即回掩码。
  bool _amountsRevealed = false;
  // RFC 20260923 D 批：行情（K 线）链路可用性（GET /trading/market-data/health）——ok=false 才显示横幅
  // （ok=true 或拿不到信息 = 零显示；三源全挂时用户本来只会看到资金曲线平了，毫无提示）
  MarketDataHealthDto? _marketHealth;
  bool _marketHealthExpanded = false;
  // m3（2026-10-07 · 原型持仓主屏「近 20 日」列）：逐票拉取的近 20 日收盘（迷你走势数据）。
  // 逐票复用 GET /trading/kline（window=20）；失败静默（无数据 → 「—」，绝不编走势）。
  // 幂等：已缓存 / 在途的票不重复拉——_loadAll 每次静默刷新都调，靠这两个容器去重。
  final Map<String, List<double>> _sparkCloses = {};
  final Set<String> _sparkLoading = {};

  Timer? _autoRefresh;

  @override
  void initState() {
    super.initState();
    _loadAll();
    _loadRules();
    _loadCases();
    _loadCaseCandidates();
    _loadMarketStage();
    _loadPlan();
    _loadTodayDayStatus();
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
    _planLinesCtrl.dispose();
    _planNoteCtrl.dispose();
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

  /// 持仓数据源：优先进当日口径端点（GET /trading/positions/daily），
  /// **当日口径是增强项——它失败绝不能拖垮持仓主数据**：失败即静默回落现有 getPositions()，
  /// 页面照常显示持仓，只是当日三列与顶部总仓位回落「—」/不显示。
  Future<_PositionsLoad> _fetchPositions() async {
    try {
      final d = await widget.api.getPositionsDaily();
      return _PositionsLoad(PositionsResponse(positions: d.positions), d);
    } catch (_) {
      return _PositionsLoad(await widget.api.getPositions(), null);
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
        _fetchPositions(),
        widget.api.getAccount(),
      ]);
      if (!mounted) return;
      final pos = results[1] as _PositionsLoad;
      setState(() {
        _portfolio = results[0] as PortfolioSnapshotResponse;
        _positions = pos.positions.positions;
        // 当日口径只在端点成功时更新；失败则清空为「—」，不残留上一次的陈旧数字
        _dailyItems = pos.daily?.daily ?? {};
        _totalPositionRatio = pos.daily?.totalPositionRatio;
        _cashRatio = pos.daily?.cashRatio;
        _dailyNotes = pos.daily?.notes ?? const [];
        _account = results[2] as AccountSnapshotDto;
        _lastUpdated = DateTime.now().toString().substring(11, 19);
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      // 有旧数据：保留展示（静默刷新失败不整页变白），仅首载失败才错误页
      final hasData = _positions.isNotEmpty || _portfolio != null;
      if (hasData) {
        _toast('刷新失败：${extractApiErrorMessage(e)}');
        setState(() => _loading = false);
      } else {
        setState(() { _error = extractApiErrorMessage(e); _loading = false; });
      }
    }
    // 可降级请求（自选/买点/清仓/打分）：异步拉取，失败静默（显示 '—'），不阻塞主数据
    _loadDegradable();
    // RFC 20260822：当日交易复盘（今日 N 笔 · 时段分布）——纯客观，失败静默不显示
    _loadDaily();
    // RFC 20260912：账实对账闸门（应有持仓 vs 落地持仓 / 重放缺口）——失败静默降级，不打断加载
    unawaited(_loadIntegrity());
    // RFC 20260923：行情链路可用性——与对账并行，同样可降级（失败静默、不影响页面其它数据）
    unawaited(_loadMarketHealth());
    // 2026-09-15：今日/本周/本月盈亏（增强项，失败静默——旧后端/网络抖动时整行不显示）
    unawaited(_loadPnlPeriods());
    // m3（2026-10-07）：迷你走势（原型「近 20 日」列）——持仓到手先拉；自选由 _loadDegradable 后补拉
    unawaited(_loadSparklines());
  }

  /// 2026-09-15：日 / 周 / 月盈亏（GET /trading/pnl-periods）。
  /// 与资金曲线同源（逐日总资产差分、剔除银证转账）；失败静默降级（不显示该行，不打断页面）。
  Future<void> _loadPnlPeriods() async {
    try {
      final p = await widget.api.getPnlPeriods();
      if (!mounted) return;
      setState(() => _pnlPeriods = p);
    } catch (_) {
      // 静默降级：宁可整行不显示，也不编造数字
    }
  }

  /// RFC 20260912 账实一致性闸门（GET /trading/integrity）：
  /// drift（应有持仓 ≠ 落地持仓）/ gaps（卖超·未持有的重放缺口）非空 → 顶部可展开橙色横幅。
  /// 可降级请求：失败 / 旧后端 404 → 静默（不显示横幅、不打断页面，绝不整页错误态）。
  Future<void> _loadIntegrity() async {
    try {
      final r = await widget.api.getTradingIntegrity();
      if (!mounted) return;
      setState(() => _integrity = r);
    } catch (_) {
      // 静默降级：对账闸门拿不到不影响看盘（宁可不显示，也不误报差异）
    }
  }

  /// RFC 20260923 D 批：行情（K 线）链路可用性（GET /trading/market-data/health）：
  /// 三源连续全失败 → 顶部可展开橙色横幅（后端 note 直接透出，前端不另造口径）。
  /// 可降级请求：失败 / 旧后端 404 / 网络抖动 → 静默（不显示横幅、不弹错误、不影响页面其它数据）。
  Future<void> _loadMarketHealth() async {
    try {
      final h = await widget.api.getMarketDataHealth();
      if (!mounted) return;
      setState(() => _marketHealth = h);
    } catch (_) {
      // 静默降级：拿不到健康信息 ≠ 行情坏了（宁可不显示，也不制造假警报）
    }
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

  /// v3.41（2026-09-04）：活跃市值区间加载（用户手动判定，失败静默——不阻塞交易页）。
  Future<void> _loadMarketStage() async {
    try {
      final data = await widget.api.getMarketStage();
      if (!mounted) return;
      setState(() {
        _marketStageExists = data['exists'] == true;
        _marketStage = data['stage'] as String?;
        _marketStageUpdatedAt = data['updatedAt'] as String?;
        _marketStageLoaded = true;
      });
    } catch (_) {
      // 静默：后端旧版本（无此端点）时隐藏开关条，不影响交易页
    }
  }

  /// v3.41（2026-09-04）：切换活跃市值区间（乐观更新 + PUT；失败回滚 + toast）。
  Future<void> _setMarketStage(String stage) async {
    if (_marketStageSaving) return;
    _marketStageSaving = true;
    final prev = _marketStage;
    setState(() { _marketStage = stage; _marketStageExists = true; }); // 乐观
    try {
      await widget.api.setMarketStage(stage);
      if (!mounted) return;
      setState(() {
        _marketStageUpdatedAt = DateTime.now().toString().substring(0, 19);
        _marketStageSaving = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() { _marketStage = prev; _marketStageSaving = false; }); // 失败回滚
      _toast('切换失败：${extractApiErrorMessage(e)}');
    }
  }

  /// v3.41（2026-09-04）：活跃市值区间切换条（多头=红/空头=绿，红涨绿亏）。
  Widget _buildMarketStageBar() {
    final bear = _marketStage == 'bear';
    final bull = _marketStage == 'bull';
    final stageColor = bear
        ? AppColors.darkGreen
        : bull
            ? AppColors.darkRed
            : AppColors.darkGrey4; // 未判定 → 中性灰
    final stageLabel = bear
        ? '空头区间'
        : bull
            ? '多头区间'
            : '未判定';
    final sub = _marketStageExists
        ? (_marketStageUpdatedAt != null && _marketStageUpdatedAt!.length >= 16
            ? '手动 · ${_marketStageUpdatedAt!.substring(11, 16)}'
            : '手动')
        : '未判定 · 按规则推断';
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: stageColor.withValues(alpha: 0.4)),
      ),
      child: Row(children: [
        Icon(bear
            ? Icons.trending_down
            : bull
                ? Icons.trending_up
                : Icons.help_outline,
            size: 18,
            color: stageColor),
        const SizedBox(width: 8),
        Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('活跃市值（指南针）', style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
          Text(stageLabel, style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: stageColor)),
        ]),
        const SizedBox(width: 8),
        Text('· 一切的前提 · $sub', style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
        const Spacer(),
        _stageButton('空头', 'bear', AppColors.darkGreen),
        const SizedBox(width: 8),
        _stageButton('多头', 'bull', AppColors.darkRed),
      ]),
    );
  }

  Widget _stageButton(String label, String stage, Color color) {
    final selected = _marketStage == stage;
    return OutlinedButton(
      onPressed: _marketStageSaving ? null : () => _setMarketStage(stage),
      style: OutlinedButton.styleFrom(
        foregroundColor: selected ? color : AppColors.darkGrey4,
        backgroundColor: selected ? color.withValues(alpha: 0.12) : Colors.transparent,
        side: BorderSide(color: selected ? color : AppColors.darkBorder.withValues(alpha: 0.6), width: 1),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
        minimumSize: const Size(0, 30),
        textStyle: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
      ),
      child: Text(label),
    );
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
      final ov = await widget.api.getSold();
      final bps = await widget.api.getBuyPoints();
      if (!mounted || gen != _auxGen) return; // 旧代丢弃
      setState(() {
        _watchlist = watch;
        // RFC 20260909 批1：sold 双轨对象——复盘档案 + 待补清单分开落字段
        _sold = ov.sold;
        _pendingClearances = ov.pending;
        _buyPoints = bps;
      });
    } catch (_) {
      // 自选/买点失败静默（信号列显示 —）
    } finally {
      _auxLoading = false; // 无条件复位（锁只被本请求持有，串行安全）
    }
    if (gen == _auxGen) _loadSoldScore(); // 打分独立：162 笔 K 线耗时，失败也不影响
    if (gen == _auxGen) _loadSoldAfterClose(); // 卖后涨跌同独立：也拉 K 线，与打分并行互不阻塞
    // m3（2026-10-07）：自选到手 → 补拉迷你走势（持仓那批 _loadAll 已发起；幂等去重）
    if (gen == _auxGen) unawaited(_loadSparklines());
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

  /// 2026-10-08 清仓「卖掉之后到现在」（GET /sold/after-close）：卖出日收盘 → 最新收盘。
  /// 与打分同法：空列表短路 + 在途防重叠 + 失败静默（列显示「—」，拿不到行情不编）。
  Future<void> _loadSoldAfterClose() async {
    if (_sold.isEmpty) return; // 无清仓 → 不打空请求
    if (_afterLoading) return; // 已有请求在途 → 不重复发起
    _afterLoading = true;
    try {
      final rows = await widget.api.getSoldAfterClose();
      if (!mounted) return;
      setState(() => _soldAfter = rows);
    } catch (_) {
      // 失败静默：主数据已展示，该列回落「—」
    } finally {
      _afterLoading = false;
    }
  }

  /// m3（2026-10-07 · 原型持仓主屏「近 20 日」列）：逐票拉近 20 日收盘画迷你走势。
  /// 复用 GET /trading/kline（window=20，与 K 线弹窗同一数据源）；失败静默（无数据 → 「—」）。
  /// 幂等：_sparkCloses 去重「已缓存」、_sparkLoading 去重「在途」——静默刷新重复调无副作用。
  Future<void> _loadSparklines() async {
    final symbols = <String>{
      for (final p in _positions) if (p.symbol.isNotEmpty) p.symbol,
      for (final w in _watchlist) if (w.symbol.isNotEmpty) w.symbol,
    }.where((s) => !_sparkCloses.containsKey(s) && !_sparkLoading.contains(s)).toList();
    if (symbols.isEmpty) return;
    _sparkLoading.addAll(symbols); // 同步落锁（无 await 前置），并发调用不会重复拉同一票
    final got = <String, List<double>>{};
    await Future.wait(symbols.map((s) async {
      try {
        final k = await widget.api.fetchTradingKline(s, window: 20);
        final closes = <double>[
          for (final c in k.candles)
            if (c['close'] is num) (c['close'] as num).toDouble(),
        ];
        if (closes.length >= 2) got[s] = closes; // 一根画不出走势（首尾比较没意义）
      } catch (_) {
        // 静默降级：这一列回落「—」——拿不到行情不编形状，不弹错
      }
    }));
    _sparkLoading.removeAll(symbols);
    if (!mounted || got.isEmpty) return;
    setState(() => _sparkCloses.addAll(got));
  }

  /// 「近 20 日」单元格：54×14 迷你走势（走红跌绿＝首尾比较，与原型同口径）；
  /// 拿不到 → 「—」（不编形状、不占位）。
  Widget _sparkCell(String symbol) {
    final closes = _sparkCloses[symbol];
    if (closes == null || closes.length < 2) {
      return const Text('—', style: TextStyle(fontSize: 12, color: AppColors.darkGrey5));
    }
    final up = closes.last >= closes.first;
    return SizedBox(
      key: Key('spark_$symbol'),
      width: 54,
      height: 14,
      child: CustomPaint(
          painter: _SparkPainter(
              closes: closes, color: up ? AppColors.darkRed : AppColors.darkGreen)),
    );
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
          content: Text('记录交易失败: ${extractApiErrorMessage(e)}', style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1)),
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
          content: Text('更新失败: ${extractApiErrorMessage(e)}', style: const TextStyle(fontSize: 13, color: AppColors.darkOrange)),
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
        err = extractApiErrorMessage(e);
      }
      if (!mounted) return;
      await showDialog(
        context: context,
        builder: (_) => _LotsDialog(
          api: widget.api,
          symbol: p.symbol,
          name: p.name,
          lots: resp?.lots ?? const [],
          reconcile: resp?.reconcile ?? const [],
          fee: resp?.fees[p.symbol],
          error: err,
          // m6：弹窗打开时继承页头 👁 的当前状态（浏览类明细默认掩码）
          revealed: _amountsRevealed,
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
          // RFC 20260823：交易历史 Dialog 已升级为第 5 Tab「历史成交」，页头入口移除。
          // 2026-10-06 曾裁决「页头不设导入入口」；2026-10-07 按原型（全量地图「导入 / 记一笔 /
          // 复盘 常驻顶栏」）改回：误塞顾虑已由 R-12 逐份识别 +「先看计划」预检接管——
          // 选错文件也不动数据；各 Tab 的专属导入入口继续保留。
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
          // m6（2026-10-07 · 原型 .wd-eye「👁 看金额」）：一处解开、全页显形——
          // 默认掩码（数量与成本类），点这里本地解开（不持久化）；再点回掩码。
          IconButton(
            key: const Key('revealToggle'),
            onPressed: () => setState(() => _amountsRevealed = !_amountsRevealed),
            icon: Icon(_amountsRevealed ? Icons.visibility : Icons.visibility_outlined, size: 18),
            color: _amountsRevealed ? AppColors.darkGreen : AppColors.darkGrey4,
            tooltip: '看金额',
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
          // 2026-10-07（原型「导入」常驻顶栏）：亮底 main 款（原型 `wd-btn.main` 白底深字 =
          // darkGrey1 #F0EDE9）。打开统一导入抽屉：点选文件一次多选、逐份识别、先看计划；粘贴路径同框保留。
          Padding(
            padding: const EdgeInsets.only(right: 8),
            child: FilledButton.icon(
              onPressed: () => _showImportDrawer(),
              icon: const Icon(Icons.file_download_outlined, size: 16),
              label: const Text('导入'),
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.darkGrey1,
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
                : LayoutBuilder(builder: (ctx, cons) {
                    // m5（2026-10-07 · 原型 .wd-rail）：右栏（阿呆说 / 今天 / 三条口径）固定在最右。
                    // 窄视窗（< 1080）不显示右栏——它是锦上添花，主表可用宽优先。
                    final hasRail = cons.maxWidth >= 1080;
                    final main = ListView(
                      padding: EdgeInsets.fromLTRB(20, 16, hasRail ? 12 : 20, 20),
                      children: [
                        // v3.41（2026-09-04）：活跃市值区间（用户手动判定）——一切的前提，放最顶
                        if (_marketStageLoaded) ...[
                          _buildMarketStageBar(),
                          const SizedBox(height: 10),
                        ],
                        // RFC 20260923：行情链路横幅放对账之上——它是上游根因（行情拿不到 → 曲线/信号/案例
                        // 都会不全），先让用户看到「为什么今天数据可能不对劲」，再看下面的具体对账差异
                        if (_marketHealth != null && _marketHealth!.shouldWarn) ...[
                          _buildMarketHealthBanner(_marketHealth!),
                          const SizedBox(height: 10),
                        ],
                        // m5：6 格状态条（总资产 · 当日 · 总盈亏 · 持仓市值 · 到线 · 账实）——
                        // 原「上方三坨」收敛成的一条；账实明细仍在账区自证条（跨区状态由这里承担）
                        _buildStatusStrip(),
                        if (_hasPositionRatioLine) ...[
                          const SizedBox(height: 8),
                          _buildPositionRatioLine(),
                        ],
                        // P2-交易58 前端侧（2026-10-04）：本地数据包止于哪天——滞后才说一句，
                        // 今天 / 拿不到 → 零显示（与对账闸门同一「无异常不刷存在感」口径）
                        if (_tdxLag != null) ...[
                          const SizedBox(height: 6),
                          _buildTdxLagLine(_tdxLag!),
                        ],
                        if (_dailyNotes.isNotEmpty) ...[
                          const SizedBox(height: 6),
                          _buildDailyNotesLine(),
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
                    );
                    if (!hasRail) return main;
                    return Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Expanded(child: main),
                      SizedBox(
                        width: 208,
                        child: SingleChildScrollView(
                          padding: const EdgeInsets.fromLTRB(0, 16, 20, 20),
                          child: _buildRightRail(),
                        ),
                      ),
                    ]);
                  }),
      ),
    ]);
  }

  /// 顶部总仓位/现金比例：有其一即显示（「仓位 62.24% · 现金 37.76%」）；
  /// 两者皆 null（总资产为 0 算不出比例 / 旧后端）→ 整段不显示（不编造 0%）。
  bool get _hasPositionRatioLine => _totalPositionRatio != null || _cashRatio != null;

  Widget _buildPositionRatioLine() {
    final parts = <String>[
      if (_totalPositionRatio != null) '仓位 ${_totalPositionRatio!.toStringAsFixed(2)}%',
      if (_cashRatio != null) '现金 ${_cashRatio!.toStringAsFixed(2)}%',
    ];
    return Row(children: [
      const Icon(Icons.pie_chart_outline, size: 14, color: AppColors.darkGrey4),
      const SizedBox(width: 6),
      Text(parts.join(' · '),
          style: const TextStyle(fontSize: 12, color: AppColors.darkGrey2, fontWeight: FontWeight.w600)),
    ]);
  }

  /// 有未计入项（缺昨收 / 卖出未计）时的轻提示：口语化如实说明「当日盈亏偏小」。
  /// 用中性橙（不是错误红）——这既不是故障也不是用户的错，只是今天这笔没算全。
  /// 文案直接说话（**不加「阿呆说：」这类引述前缀**——加了就成了第三方记录视角，违背 B1）。
  Widget _buildDailyNotesLine() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: AppColors.darkOrange.withValues(alpha: 0.35)),
      ),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Icon(Icons.info_outline, size: 14, color: AppColors.darkOrange),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            '有几笔今天的盈亏还没算全——${_dailyNotes.join('；')}',
            style: const TextStyle(fontSize: 11, color: AppColors.darkGrey3, height: 1.5),
          ),
        ),
      ]),
    );
  }

  /// P2-交易58 前端侧（2026-10-04）：本地行情滞后提示（null = 不显示，零噪音）。
  String? get _tdxLag => tdxLagNote(_marketHealth?.tdxLastDate);

  /// P2-交易58 前端侧（2026-10-04）：本地数据包滞后一行轻提示。
  /// 用中性灰（不是警告橙）：它是**常态信息**（用户一周导一次数据包），不是故障；
  /// 只有真滞后（>3 天，与后端 `KlineService#tdxStale` 同口径）才由 [_tdxLag] 放出来，
  /// 今天 / 拿不到 / 前 1～3 天 → 零显示。
  Widget _buildTdxLagLine(String note) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.6)),
      ),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Icon(Icons.history_rounded, size: 14, color: AppColors.darkGrey4),
        const SizedBox(width: 8),
        Expanded(
          child: Text(note,
              style: const TextStyle(fontSize: 11, color: AppColors.darkGrey3, height: 1.5)),
        ),
      ]),
    );
  }

  /// 2026-09-07（用户反馈「web 宽度足够却挤」）：DataTable 直接放进横向滚动容器会
  /// 收缩到列内容最小宽 → 宽屏右边留白、列被最长内容绑架。
  /// 2026-10-07（m4d 修复「近 20 日」列被压穿 3.3px）：改「横滚容器 + minWidth=可用宽」——
  /// Table 无上界时以 minWidth 为目标铺满（flex 列吸富余），自然宽超出可用宽时
  /// 表按自然宽溢出到横滚，**任何列都不再被压穿**。旧法靠经验阈值（1520）直出，
  /// 数据一变宽（当日盈亏 '-321.50'）自然需求 1626 > 可用 1623 即被压穿。
  Widget _scrollableTable({required DataTable table}) {
    return LayoutBuilder(builder: (ctx, cons) {
      if (!cons.maxWidth.isFinite) return table;
      return SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: ConstrainedBox(
          constraints: BoxConstraints(minWidth: cons.maxWidth),
          child: table,
        ),
      );
    });
  }

  /// 持仓表（m4：`mixed` = 「全部」视图——持仓行 + 自选轻行合成一张表，原型主屏形态）。
  /// 自选轻行缺的列一律「—」（我们没有自选的行情源；原型自选行的现价/涨跌是 mock，诚实降级不编数）；
  /// 自选表结构不同（行业/长中短/指标）→「自选」筛选下仍是完整自选表，不硬混。
  Widget _buildPositionTable({bool mixed = false}) {
    // 2026-08-23：持仓 Tab 内导入入口（通达信持仓导出，全量覆盖）——页头「批量导入」已移除，
    // 持仓导入不再与清仓/资金/交易 CSV 混在一个对话框（此前清仓/资金文本被交易 CSV 校验「买点」拦截）
    // 2026-10-06（R-12 落定 · 入口归各 Tab）：本入口「选择文件（可多选，通达信导出）」选 ≥2 份时
    // 转统一批量对话框（逐份识别 + 先看计划）；选 1 份仍走原文本框路径。
    final header = Row(children: [
      Text(mixed ? '持仓 ${_positions.length} 只 · 自选 ${_watchlist.length} 只' : '持仓 ${_positions.length} 只',
          style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
      const SizedBox(width: 8),
      Text('通达信持仓导出 · 全量覆盖 · 止损需导入后补设', style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
      const Spacer(),
      OutlinedButton.icon(
        onPressed: _openPositionsImport,
        icon: const Icon(Icons.upload_file, size: 14),
        label: const Text('导入持仓', style: TextStyle(fontSize: 12)),
        style: OutlinedButton.styleFrom(
            foregroundColor: AppColors.darkGrey1,
            side: const BorderSide(color: AppColors.darkGrey4),
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4)),
      ),
    ]);
    final isEmpty = mixed ? (_positions.isEmpty && _watchlist.isEmpty) : _positions.isEmpty;
    if (isEmpty) {
      return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        header,
        const SizedBox(height: 12),
        Center(
          child: Padding(
            padding: const EdgeInsets.only(top: 40),
            child: Text(mixed ? '暂无持仓或自选' : '暂无持仓',
                style: const TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
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
        child: _scrollableTable(
          // m4d：旧 minWidth: 1520 是经验阈值——数据一变宽（当日盈亏 '-321.50'）
          // 自然需求超过阈值即被压穿；新实现按「可用宽」自适应，不再需要。
          table: DataTable(
          headingRowColor: WidgetStatePropertyAll(AppColors.darkSurface2.withValues(alpha: 0.5)),
          dataRowColor: WidgetStatePropertyAll(Colors.transparent),
          headingTextStyle: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppColors.darkGrey5),
          columnSpacing: 28,
          horizontalMargin: 16,
          columns: const [
            // m3（2026-10-07 · 原型 .wd-name/.wd-code）：名称与代码合并一列（名在前、代码小号灰在后）
            DataColumn(label: Text('代码 / 名称')),
            DataColumn(label: Text('数量'), numeric: true),
            DataColumn(label: Text('成本'), numeric: true),
            DataColumn(label: Text('现价'), numeric: true),
            DataColumn(label: Text('市值'), numeric: true),
            // 当日口径三列（GET /trading/positions/daily）：缺值一律「—」，不给 0
            DataColumn(label: Text('仓位占比'), numeric: true),
            DataColumn(label: Text('当日盈亏'), numeric: true),
            DataColumn(label: Text('今日涨跌幅'), numeric: true),
            DataColumn(label: Text('盈亏'), numeric: true),
            DataColumn(label: Text('盈亏%'), numeric: true),
            DataColumn(label: Text('止损'), numeric: true),
            // 批 A（2026-10-08）：只显示**离你更近的那一条线**（全给会变成一堵墙）
            DataColumn(label: Text('最近的那条线')),
            // m3：近 20 日迷你走势（54×14，走红跌绿＝首尾比较；拿不到「—」）
            // m4d：压穿根因已由 _scrollableTable 兜底（表按自然宽溢出到横滚）；
            // softWrap/maxLines 改不了 TextPainter.minIntrinsicWidth（=paragraph 值），实证无效。
            DataColumn(label: Text('近 20 日')),
            DataColumn(label: Text('买点')),
            DataColumn(label: Text('角色')),
            DataColumn(label: Text('操作')),
          ],
          rows: <DataRow>[
            ..._positions.map((p) {
            // #132 红涨绿亏（A股）：盈=红、亏=绿
            final pnlColor = p.pnl >= 0 ? AppColors.darkRed : AppColors.darkGreen;
            // 当日口径：该票缺条目（端点降级/新票）→ d 为 null → 三列全「—」
            final d = _dailyItems[p.symbol];
            // 双止损位（trading-risk-plan）：主值 = 生效止损 = max(人工, 计算)；
            // 人工/计算有差异时副行标注非生效来源（系统 xx / 人工 xx）
            final slEffective = p.effectiveStopLoss;
            final slManual = p.stopLossPrice;
            final slComputed = p.computedStopLossPrice;
            final slSecondary = <String>[];
            if (slManual != null && slComputed != null && (slManual - slComputed).abs() > 0.0005) {
              final effectiveIsManual = slEffective != null && (slEffective - slManual).abs() < 0.0005;
              slSecondary.add(effectiveIsManual
                  ? '系统 ${slComputed.toStringAsFixed(3)}'
                  : '人工 ${slManual.toStringAsFixed(3)}');
            }
            // m3（2026-10-07 · 原型 tr.on）：到线行标——破止损 / 到放飞（整行浅橙）
            final onLine = _onLine(p);
            final rowColor = onLine
                ? WidgetStatePropertyAll<Color>(AppColors.darkOrange.withValues(alpha: 0.08))
                : null;
            // m3：首列合并「名称 代码」（名在前＝主色，代码小号灰在后）
            final nameCode = Row(mainAxisSize: MainAxisSize.min, children: [
              Text(p.name, style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1, fontWeight: FontWeight.w600)),
              const SizedBox(width: 6),
              Text(p.symbol, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
            ]);
            return DataRow(color: rowColor, cells: [
              // m3：到线行首橙条（原型 inset box-shadow 3px；Key 只在到线时存在＝测试锚）
              DataCell(onLine
                  ? Container(
                      key: Key('online_${p.symbol}'),
                      padding: const EdgeInsets.only(left: 6),
                      decoration: const BoxDecoration(
                          border: Border(left: BorderSide(color: AppColors.darkOrange, width: 3))),
                      child: nameCode,
                    )
                  : nameCode),
              // m6：数量/成本打码（现价保留——口径「数量与成本打码，现价与止损保留」）
              DataCell(Text(maskIf('${p.quantity}', _amountsRevealed), style: const TextStyle(fontSize: 13, color: AppColors.darkGrey3))),
              DataCell(Text(maskIf(p.avgCost.toStringAsFixed(3), _amountsRevealed), style: const TextStyle(fontSize: 13, color: AppColors.darkGrey3))),
              DataCell(Text(p.currentPrice.toStringAsFixed(3), style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1))),
              DataCell(Text(maskIf(p.marketValue.toStringAsFixed(2), _amountsRevealed), style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1))),
              // 仓位占比：中性灰（不是涨跌，不借红绿）；null → 灰「—」
              DataCell(Text(_fmtDailyPct(d?.positionRatio),
                  style: TextStyle(fontSize: 13,
                      color: d?.positionRatio == null ? AppColors.darkGrey5 : AppColors.darkGrey3))),
              // 当日盈亏（券商口径＝今天真实赚亏）：正红负绿；null（缺昨收）→ 灰「—」，绝不写 0.00
              // m6：金额打码（「—」= 缺数据，原样透出）
              DataCell(Text(maskIf(_fmtDailyMoney(d?.todayPnl), _amountsRevealed),
                  style: TextStyle(fontSize: 13, color: _dailyUpDownColor(d?.todayPnl), fontWeight: FontWeight.w600))),
              // 今日涨跌幅 %：同一套红涨绿亏；null（缺昨收）→ 灰「—」，绝不写 0.00%
              DataCell(Text(_fmtDailyPct(d?.dayChangePct),
                  style: TextStyle(fontSize: 13, color: _dailyUpDownColor(d?.dayChangePct)))),
              DataCell(Text(maskIf(p.pnl.toStringAsFixed(2), _amountsRevealed), style: TextStyle(fontSize: 13, color: pnlColor, fontWeight: FontWeight.w600))),
              // 负/零成本 → pnlPercent 为 null → 「—」（不给 0.00%，那是谎报「不赚不亏」）
              DataCell(Text(p.pnlPercent == null ? '—' : '${p.pnlPercent!.toStringAsFixed(2)}%',
                  style: TextStyle(fontSize: 13, color: pnlColor))),
              DataCell(slEffective != null
                  ? Column(
                      crossAxisAlignment: CrossAxisAlignment.end,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(slEffective.toStringAsFixed(3),
                            style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1, fontWeight: FontWeight.w600)),
                        if (slSecondary.isNotEmpty)
                          Text(slSecondary.join(' · '),
                              style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
                      ],
                    )
                  : Text(slManual?.toStringAsFixed(3) ?? '—',
                      style: const TextStyle(fontSize: 13, color: AppColors.darkGrey3))),
              // 批 A（2026-10-08）：最近的那条线 —— 破了止损 / 到了放飞 / 离止损还有多少
              DataCell(Builder(builder: (_) {
                final nl = _nearestLine(p);
                return Text(nl.text, style: TextStyle(fontSize: 12.5, color: nl.color));
              })),
              // m3：近 20 日迷你走势（54×14；拿不到「—」）
              DataCell(_sparkCell(p.symbol)),
              DataCell(Text(p.buyPoint ?? '—', style: const TextStyle(fontSize: 13, color: AppColors.darkGrey3))),
              DataCell(Text(p.role ?? '—', style: const TextStyle(fontSize: 13, color: AppColors.darkGrey3))),
              // RFC 20260825：批次明细入口（一买一批跟踪）+ 编辑
              DataCell(Row(mainAxisSize: MainAxisSize.min, children: [
                // R-04（2026-10-07）：一行一个「图」入口 —— 四个地方调出来的是同一张图
                TextButton(
                  onPressed: () => _openKline(p.symbol, p.name),
                  style: TextButton.styleFrom(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                    minimumSize: Size.zero,
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  ),
                  child: const Text('图', style: TextStyle(fontSize: 12, color: AppColors.darkOrange)),
                ),
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
            }),
            // m4「全部」：自选轻行接在持仓行后面（原型：9 行 = 5 持仓 + 4 自选）
            if (mixed) ..._watchlist.map(_watchlistMixRow),
          ],
          ),
        ),
      ),
    ]);
  }

  /// 「全部」视图里的自选轻行（原型持仓屏底部自选行）：只有名字/近 20 日/买点/操作有内容，
  /// 缺失列一律「—」（数量/成本/线在原型也是「—」；现价在原型是 mock，我们没有自选行情源）。
  DataRow _watchlistMixRow(WatchlistItemDto w) {
    final bp = _buyPoints.where((b) => b.symbol == w.symbol).toList();
    return DataRow(cells: [
      DataCell(Row(mainAxisSize: MainAxisSize.min, children: [
        Text(w.name, style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1, fontWeight: FontWeight.w600)),
        const SizedBox(width: 6),
        Text(w.symbol, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
        const SizedBox(width: 6),
        // 原型 <span class="quiet">自选</span>——一眼分清这行不是持仓
        const Text('自选', style: TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
      ])),
      // 数量/成本/现价/市值/仓位占比/当日盈亏/今日涨跌幅/盈亏/盈亏%/止损——自选没有这些口径
      _dashCell(), _dashCell(), _dashCell(), _dashCell(), _dashCell(),
      _dashCell(), _dashCell(), _dashCell(), _dashCell(), _dashCell(),
      // 最近的那条线（自选没有线）
      _dashCell(),
      // 近 20 日：自选有 spark（与自选表同一个渲染）
      DataCell(_sparkCell(w.symbol)),
      // 买点：自选的价值列——命中 B1/B2 显示（判定是提示不是指令）
      DataCell(bp.isEmpty
          ? const Text('—', style: TextStyle(fontSize: 12, color: AppColors.darkGrey5))
          : ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 170),
              child: Text(bp.map(_buyPointLabel).join('、'),
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      color: bp.first.buyPoint == 'case' ? AppColors.darkOrange : AppColors.darkRed)))),
      // 角色
      _dashCell(),
      // 操作：图 + 删（批次/编辑是持仓动作，自选轻行不给）
      DataCell(Row(mainAxisSize: MainAxisSize.min, children: [
        TextButton(
          onPressed: () => _openKline(w.symbol, w.name),
          style: TextButton.styleFrom(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
            minimumSize: Size.zero,
            tapTargetSize: MaterialTapTargetSize.shrinkWrap,
          ),
          child: const Text('图', style: TextStyle(fontSize: 12, color: AppColors.darkOrange)),
        ),
        TextButton(
          onPressed: () => _removeWatchlist(w),
          style: TextButton.styleFrom(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
            minimumSize: Size.zero,
            tapTargetSize: MaterialTapTargetSize.shrinkWrap,
          ),
          child: const Text('删', style: TextStyle(fontSize: 12, color: AppColors.darkGrey4)),
        ),
      ])),
    ]);
  }

  /// 「全部」表的空列「—」（每次新建实例——同一 Widget 实例不复用多处）。
  DataCell _dashCell() =>
      DataCell(const Text('—', style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)));

  /// m4（2026-10-07）：自选删除——原内联在自选表行内，抽出给「全部」混合表的自选轻行复用。
  Future<void> _removeWatchlist(WatchlistItemDto w) async {
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
      _toast('删除失败：${extractApiErrorMessage(e)}');
    }
  }

  /// #102 复盘入口：生成今日复盘 → 弹窗展示（交易系统反哺可达）。
  /// 2026-09-07 复盘超时修复：POST 提交即返回（AI 生成实测 77~176s，旧同步等待即使走
  /// 120s AI 客户端，超长生成仍会超时报失败、复盘却已生成）。exists 直接 GET 展示，
  /// pending/running 轮询 GET /trading/review 直到就绪（404=生成中）。
  Future<void> _showReview() async {
    if (_reviewing) return; // 在途守卫：连点不重复提交
    setState(() => _reviewing = true);
    try {
      final sub = await widget.api.submitReview();
      ReviewResponse? review;
      if (sub.status == 'exists') {
        // 已有复盘（今日已生成/他端已生成）→ 直接取来展示，不重复烧 AI
        review = await widget.api.getReview();
      } else {
        // pending / running → 轮询 GET 直到文件就绪
        review = await _waitReviewReady();
      }
      if (!mounted) return;
      setState(() => _reviewing = false);
      if (review != null) {
        final ready = review; // 闭包捕获用非空 final（防提升失效）
        showDialog(context: context, builder: (_) => _buildReviewDialog(ready));
      } else {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text('复盘生成超时（超过 4 分钟），请稍后到「复盘历史」查看或重试',
              style: TextStyle(fontSize: 13, color: AppColors.darkOrange)),
          backgroundColor: AppColors.darkSurface2,
        ));
      }
    } catch (e) {
      if (!mounted) return;
      setState(() => _reviewing = false);
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text('复盘生成失败: ${extractApiErrorMessage(e)}',
            style: const TextStyle(fontSize: 13, color: AppColors.darkOrange)),
        backgroundColor: AppColors.darkSurface2,
      ));
    }
  }

  /// 轮询 GET /trading/review 直到就绪（上限 240s，步进 3s；单次失败不中断）。
  Future<ReviewResponse?> _waitReviewReady() async {
    final deadline = DateTime.now().add(const Duration(seconds: 240));
    while (mounted && DateTime.now().isBefore(deadline)) {
      try {
        final r = await widget.api.getReview();
        if (r != null) return r;
      } catch (_) {
        // 网络抖动/后端仍在生成：不中断，继续轮询到上限
      }
      await Future.delayed(const Duration(seconds: 3));
    }
    return null;
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
        SnackBar(content: Text('反哺入库失败: ${extractApiErrorMessage(e)}',
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

  /// 导入回执（P2-交易83，2026-10-04）：**有丢行 → 弹回执 + 可展开逐条明细**；无丢行 → 原样 toast。
  /// 为什么不是 toast：丢行明细要能逐条看（行号 + 原文 + 原因），两秒就消失的 SnackBar 撑不住。
  /// 形态**复用历史成交 Tab 的 [_UnparsedBlock]**（橙色警示 + 明细可收起/展开，默认展开）——
  /// 同一产品内只该有一套「丢行警示」，不另造第二套。
  Future<void> _showImportReceipt({
    required String receipt,
    required List<String> unparsed,
    required int unparsedCount,
    required String Function(int n) header,
  }) async {
    if (unparsed.isEmpty) {
      _toast(receipt);
      return;
    }
    await showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.darkSurface2,
        title: Text(receipt, style: const TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
        content: SizedBox(
          width: 520,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _UnparsedBlock(lines: unparsed, declaredCount: unparsedCount, header: header),
              ],
            ),
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('知道了')),
        ],
      ),
    );
  }

  /// Tab 工作区（E1）：持仓（默认）/ 自选 / 清仓 / 资金 / 历史成交 五分区。
  /// 2026-08-23：历史成交从页头 Dialog 升级为常驻第 5 Tab（RFC 20260823，取代 _HistoryDialog）。
  /// B6-5（2026-08-23，P1-交易17）：DefaultTabController + 监听组件——历史成交 keepAlive 防重建后
  /// 切回 Tab 时主动刷新（防收盘/他端变更后陈旧，复发信号：保活页陈旧）。
  final GlobalKey<_HistorySectionState> _historyKey = GlobalKey<_HistorySectionState>();

  // ── P2-交易72（2026-10-05）：今天没买卖 → 最短路径的落点（今天没动 / 想动，没动） ──
  // 用户原话（2026-09-23）：「那我今天没有买卖 怎么告诉你呢 你还在等我的数据」。
  // 定性：不是数据缺失，是**状态回填的交互缺口**——「没动」本身是完整信息（R119 零仓位也是交易），
  // 所以这里只给一个一键落点，不追问、不催、不给「必须汇报」的压力。
  // 落点复用同一天的既有记录（`trading/plans/{今天}.json` 的 dayStatus），与「今天买了/卖了」同属这一天；
  // 后端只改这一个字段 → 不碰用户已写的计划条目。

  String get _todayStr =>
      '${DateTime.now().year.toString().padLeft(4, '0')}-${DateTime.now().month.toString().padLeft(2, '0')}-${DateTime.now().day.toString().padLeft(2, '0')}';

  /// 今天已记的状态：只认「自己那次独立读」的结果（不拿别的日期的计划冒充今天）。
  Future<void> _loadTodayDayStatus() async {
    try {
      final v = await widget.api.getPlan(_todayStr);
      if (!mounted) return;
      setState(() => _todayDayStatus = v?['dayStatus']?.toString() ?? '');
    } catch (_) {
      // 读不到就当「还没记」——不编造状态，也不打扰用户（他自己说过什么由他自己确认）
    }
  }

  /// 回填今天的状态。**重复点同一个 chip 不重复落**：本地已知就是它 → 连请求都不发，如实说清。
  Future<void> _setDayStatus(String status) async {
    if (_todayDayStatus == status) {
      setState(() => _dayStatusMsg = '今天已经记着了：${_dayStatusHuman(status)}。');
      return;
    }
    setState(() {
      _dayStatusSaving = true;
      _dayStatusMsg = null;
    });
    try {
      final r = await widget.api.setPlanDayStatus(_todayStr, status);
      if (!mounted) return;
      final recorded = r['recorded'] == true;
      final applied = r['dayStatus']?.toString() ?? status;
      setState(() {
        _todayDayStatus = applied;
        _dayStatusSaving = false;
        // 如实回执：后端说这次没写盘（早就记着了）就说「已经记着了」，不许假报一次落库。
        _dayStatusMsg = recorded
            ? '记下了：今天${_dayStatusHuman(applied)}。'
            : '今天已经记着了：${_dayStatusHuman(applied)}。';
      });
      // 计划区若正好看的是今天，一并刷新（两处显示同一份记录，不各说各话）
      if (_planDateStr == _todayStr) await _loadPlan();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _dayStatusSaving = false;
        _dayStatusMsg = '没记上：$e';
      });
    }
  }

  /// 状态的人话（句子用）——不出现枚举值、不出现系统味儿的词。
  String _dayStatusHuman(String s) {
    if (s == ApiService.dayStatusNoTrade) return '没动';
    if (s == ApiService.dayStatusWantedNotActed) return '想动，但没动';
    return s;
  }

  Widget _dayStatusChip(String status, String label) {
    final chosen = _todayDayStatus == status;
    return ActionChip(
      label: Text(label, style: const TextStyle(fontSize: 12)),
      backgroundColor: chosen ? AppColors.darkGreen.withValues(alpha: 0.18) : AppColors.darkSurface2,
      side: BorderSide(color: chosen ? AppColors.darkGreen : AppColors.darkBorder),
      visualDensity: VisualDensity.compact,
      onPressed: _dayStatusSaving ? null : () => _setDayStatus(status),
    );
  }

  /// 「今天」卡（m5 · 原型 .wd-rail 第二块）：原首屏「今天」行（P2-交易72 的一键盘点，
  /// 不折叠——他打开交易页就看得见）收进右栏；今日交易摘要（原 _buildDailySummaryRow）
  /// 也并进这里（mock 无数据时零显示——不刷存在感）。
  Widget _buildTodayCard() {
    final recorded = _todayDayStatus;
    final msg = _dayStatusMsg;
    final d = _dailySummary;
    String? summaryText;
    if (d != null) {
      final sessionText = d.sessions
          .where((s) => s.count > 0)
          .map((s) => '${s.name} ${s.count} 笔')
          .join(' · ');
      final timeText = (d.firstTradeTime != null && d.lastTradeTime != null)
          ? '${d.firstTradeTime!.substring(0, 5)}-${d.lastTradeTime!.substring(0, 5)}'
          : '';
      summaryText = '今日 ${d.count} 笔 · 买 ${d.buyCount} / 卖 ${d.sellCount}'
          '${sessionText.isEmpty ? '' : ' · $sessionText'}'
          '${timeText.isEmpty ? '' : ' · $timeText'}';
    }
    return _railCard(children: [
      const Text('今天',
          style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
      const SizedBox(height: 8),
      Text(
        recorded.isEmpty
            ? '今天没买卖的话，点一下就行——没动也是一天的完整记录。'
            : '今天记的是：${_dayStatusHuman(recorded)}',
        style: const TextStyle(fontSize: 11.5, height: 1.5, color: AppColors.darkGrey5),
      ),
      const SizedBox(height: 8),
      Wrap(spacing: 6, runSpacing: 6, children: [
        _dayStatusChip(ApiService.dayStatusNoTrade, '今天没动'),
        _dayStatusChip(ApiService.dayStatusWantedNotActed, '想动，没动'),
      ]),
      if (msg != null) ...[
        const SizedBox(height: 6),
        Text(msg, style: const TextStyle(fontSize: 11, height: 1.45, color: AppColors.darkGrey5)),
      ],
      if (summaryText != null) ...[
        const SizedBox(height: 6),
        Text(summaryText,
            style: const TextStyle(fontSize: 11, height: 1.45, color: AppColors.darkGrey3)),
      ],
    ]);
  }

  /// 阿呆说（m5 · 原型 .wd-rail 第一块 .wd-say）：只陈述 + 用你自己的线对照（B1：无系统视角标签）。
  /// 三段：① 账句（对上了 / 有一处对不上 / 还没取到——拿不到不编「对上了」）；
  /// ② 到线点名（原型句式`601899 现价 16.55，破了你的 16.80`——破止损 ↓ / 到放飞 ↑ 分开措辞）；
  /// ③ 收尾（其它没有要动的 / N 只都没有到线 / 还没有持仓）。
  Widget _buildAdeptSay() {
    final ir = _integrity;
    final clean = ir != null && !ir.hasIssue;
    final accountSentence = ir == null ? '对账还没取到。' : (clean ? '账对上了。' : '账有一处对不上。');
    final hitLines = <String>[];
    for (final it in _positions) {
      final sl = it.effectiveStopLoss;
      if (sl != null && sl > 0 && it.currentPrice <= sl) {
        hitLines.add('${it.symbol} 现价 ${it.currentPrice.toStringAsFixed(2)}，'
            '破了你的 ${sl.toStringAsFixed(2)}');
        continue;
      }
      final tp = it.targetPrice;
      if (tp != null && tp > 0 && it.currentPrice >= tp) {
        hitLines.add('${it.symbol} 现价 ${it.currentPrice.toStringAsFixed(2)}，'
            '到了你的 ${tp.toStringAsFixed(2)}');
      }
    }
    final tail = hitLines.isNotEmpty
        ? '其它没有要动的。'
        : (_positions.isEmpty ? '还没有持仓。' : '${_positions.length} 只都没有到线。');
    return _railCard(children: [
      const Text('阿呆说',
          style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
      const SizedBox(height: 8),
      Text(accountSentence,
          style: const TextStyle(fontSize: 12.5, height: 1.55, color: AppColors.darkGrey3)),
      if (hitLines.isNotEmpty) ...[
        const SizedBox(height: 6),
        Text(hitLines.join('；'),
            style: const TextStyle(fontSize: 12.5, height: 1.55, color: AppColors.darkGrey3)),
      ],
      const SizedBox(height: 6),
      Text(tail, style: const TextStyle(fontSize: 12, height: 1.5, color: AppColors.darkGrey5)),
    ]);
  }

  /// 三条口径（m5 · 原型 .wd-rail 第三块）：展示规则与交互的一句话。
  /// ⚠️ 第三条「每个数字点得进去」按原型全文放置——数字钻取交互属后续批次，m5/m6 先兑现前两条。
  Widget _buildCaliberCard() => _railCard(children: const [
        Text('三条口径',
            style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
        SizedBox(height: 8),
        Text('数量与成本打码 · 现价与线不打码 · 每个数字点得进去',
            style: TextStyle(fontSize: 11, height: 1.5, color: AppColors.darkGrey5)),
      ]);

  /// 右栏容器（原型 .wd-rail 宽 208）：阿呆说 / 今天 / 三条口径三卡纵排。
  Widget _buildRightRail() => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        _buildAdeptSay(),
        const SizedBox(height: 10),
        _buildTodayCard(),
        const SizedBox(height: 10),
        _buildCaliberCard(),
      ]);

  /// 右栏卡片（原型 .wd-card：surface 底 + 圆角 + 细边框）。
  Widget _railCard({required List<Widget> children}) => Container(
        width: double.infinity,
        padding: const EdgeInsets.fromLTRB(12, 11, 12, 11),
        decoration: BoxDecoration(
          color: AppColors.darkSurface,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.5)),
        ),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: children),
      );

  // ── 次日操作计划（§二~四）：前晚写 → 当日守 → 收盘对账。系统不生成计划、不给建议。 ──

  String get _planDateStr =>
      '${_planDate.year.toString().padLeft(4, '0')}-${_planDate.month.toString().padLeft(2, '0')}-${_planDate.day.toString().padLeft(2, '0')}';

  Future<void> _loadPlan() async {
    setState(() => _planLoading = true);
    try {
      final v = await widget.api.getPlan(_planDateStr);
      if (!mounted) return;
      setState(() {
        _planView = v;
        _planLoading = false;
        _planMsg = null;
        if (v != null) {
          _planLinesCtrl.text = ((v['items'] as List?) ?? const [])
              .map((e) => (e as Map)['text']?.toString() ?? '')
              .where((t) => t.isNotEmpty)
              .join('\n');
          _planNoteCtrl.text = v['note']?.toString() ?? '';
        }
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _planLoading = false;
        _planMsg = '读取失败：$e';
      });
    }
  }

  Future<void> _savePlan() async {
    final lines = _planLinesCtrl.text
        .split('\n')
        .map((e) => e.trim())
        .where((e) => e.isNotEmpty)
        .toList();
    if (lines.isEmpty) {
      setState(() => _planMsg = '计划不能是空的——写一句就行；当天不打算动手，就写「明天不动」。');
      return;
    }
    setState(() => _planLoading = true);
    try {
      await widget.api.savePlan(_planDateStr, lines, _planNoteCtrl.text.trim());
      await _loadPlan();
      if (mounted) setState(() => _planMsg = '已记下 $_planDateStr 的计划。');
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _planLoading = false;
        _planMsg = '保存失败：$e';
      });
    }
  }

  Future<void> _openPlanReview() async {
    try {
      final r = await widget.api.reviewPlan(_planDateStr);
      if (!mounted) return;
      final items = (r['items'] as List?) ?? const [];
      final unplanned = (r['unplanned'] as List?) ?? const [];
      // P2-交易72：对账里如实带上「这天你说的是什么」——它与「今天没有成交记录」互相印证，
      // 不是缺数据（口径对齐 P2-交易67）。
      final rDayStatus = r['dayStatus']?.toString() ?? '';
      // P3-14（2026-10-03 增量深审）：**不要把 `r['x']` 写在字符串插值里**——守卫 G6 用 `'[^']*'`
      // 去引号，嵌套引号会让它的括号计数错位、静态检查静默失效。先取到局部变量再用 `$var`。
      final trigCount = r['triggeredCount'];
      final execCount = r['executedCount'];
      await showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          backgroundColor: AppColors.darkSurface,
          title: Text('$_planDateStr 对账', style: const TextStyle(fontSize: 15)),
          content: SizedBox(
            width: 520,
            child: SingleChildScrollView(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (rDayStatus.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 6),
                      child: Text('这天你记的是：${_dayStatusHuman(rDayStatus)}',
                          style: const TextStyle(fontSize: 13, color: AppColors.darkGreen)),
                    ),
                  if (items.isEmpty)
                    const Text('这天没有写计划条目。', style: TextStyle(fontSize: 13)),
                  for (final it in items)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 6),
                      child: Text(_planItemLine(it), style: const TextStyle(fontSize: 12.5)),
                    ),
                  if (unplanned.isNotEmpty) ...[
                    const SizedBox(height: 10),
                    const Text('⚠️ 计划外操作',
                        style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
                    for (final u in unplanned)
                      Text('· $u', style: const TextStyle(fontSize: 12.5)),
                  ],
                  const SizedBox(height: 10),
                  Text('触发 $trigCount 条 · 执行 $execCount 条',
                      style: const TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
                ],
              ),
            ),
          ),
          actions: [TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('好'))],
        ),
      );
    } catch (e) {
      if (mounted) setState(() => _planMsg = '对账失败：$e');
    }
  }

  /// 轮次复盘（RFC 20261003-trading-plan-and-review-loop §五，2026-10-03）：
  /// 「一轮完整交易」的识别（持仓归零 + 同日买卖合并）× 你自己的规则命中——**只列事实，不作评价**。
  Future<void> _openRoundsReview() async {
    try {
      final r = await widget.api.getRounds(limit: 20);
      if (!mounted) return;
      final rounds = (r['rounds'] as List?) ?? const [];
      final roundTotal = r['total'];
      await showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          backgroundColor: AppColors.darkSurface,
          title: Text('最近 ${rounds.length} 轮（共 $roundTotal 轮）',
              style: const TextStyle(fontSize: 15)),
          content: SizedBox(
            width: 760,
            height: 440,
            child: SingleChildScrollView(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Text('一轮 = 从建仓到卖光（同一天「卖光又买回」算同一轮）；命中项来自你自己的规则库。只列事实。',
                      style: TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
                  const SizedBox(height: 10),
                  for (final x in rounds)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 10),
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text(_roundHead(x), style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
                        Text(_roundBody(x), style: const TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
                        for (final h in ((x['hits'] as List?) ?? const []))
                          Text(_hitLine(h),
                              style: const TextStyle(fontSize: 12, color: AppColors.darkRed)),
                      ]),
                    ),
                ],
              ),
            ),
          ),
          actions: [TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('好'))],
        ),
      );
    } catch (e) {
      if (mounted) setState(() => _planMsg = '轮次复盘失败：$e');
    }
  }

  /// 轮次标题（字段先取局部变量——避免字符串插值里的嵌套引号打断守卫 G6 的括号计数）。
  String _roundHead(dynamic x) {
    final m = x as Map;
    final open = m['open'] == true;
    final symbol = m['symbol'];
    final name = m['name'];
    final start = m['start'];
    final endRaw = m['end'];
    final pnlRaw = m['pnlPct'];
    final end = open ? '持仓中' : '$endRaw';
    final pnl = open ? '' : '　$pnlRaw%';
    return '$symbol $name　$start → $end$pnl';
  }

  String _roundBody(dynamic x) {
    final m = x as Map;
    final days = m['tradeDays'];
    final buys = m['buyCount'];
    final sells = m['sellCount'];
    final peak = m['peakPct'] ?? '—';
    final trough = m['troughPct'] ?? '—';
    final trapped = (m['addOnTrappedCount'] as num?)?.toInt() ?? 0;
    return '　$days 个交易日 · 买 $buys 卖 $sells　峰值 $peak%　最低 $trough%'
        '${trapped > 0 ? '　被套加仓 $trapped 次' : ''}';
  }

  /// 持仓对账一行（字段先取局部变量——避免字符串插值里的嵌套引号打断守卫 G6 的括号计数）。
  String _qtyDiffLine(dynamic d) {
    final m = d as Map;
    final symbol = m['symbol'];
    final name = m['name'];
    final fileQty = m['fileQty'];
    final systemQty = m['systemQty'];
    final why = m['why'];
    return '· $symbol $name：文件 $fileQty 股 / 系统 $systemQty 股　$why';
  }

  String _hitLine(dynamic h) {
    final m = h as Map;
    final rule = m['rule'];
    final text = m['text'];
    final data = m['data'];
    return '　· 命中 $rule：$text（$data）';
  }

  String _yn(Object? v) => v == true ? '是' : (v == false ? '否' : '—');

  /// 计划条目原话 / 对账一行——**先取到局部变量**，避免在字符串插值里写 `it['text']`
  /// 这种嵌套引号：守卫 G6 的括号计数用 `'[^']*'` 去引号，嵌套引号会让它错位（2026-10-03 实测）。
  String _planItemText(dynamic it) => (it as Map)['text']?.toString() ?? '';

  String _planItemLine(dynamic it) {
    final m = it as Map;
    final ev = m['evidence']?.toString();
    final trig = _yn(m['triggered']);
    final exec = _yn(m['executed']);
    return '· ${_planItemText(it)}　触发：$trig　执行：$exec'
        '${ev == null ? '' : '（$ev）'}';
  }

  /// 计划 Tab：一句话写 → 看已记下的 → 收盘对账（含 ⚠️ 计划外操作）。
  Widget _buildPlanSection() {
    final items = ((_planView?['items'] as List?) ?? const []);
    final note = _planView?['note']?.toString() ?? '';
    // P2-交易72：这天事后回填的状态（"今天没动" / "想动，没动"）——与计划条目同一份记录里的两条信息。
    final planDayStatus = _planView?['dayStatus']?.toString() ?? '';
    // m2b（2026-10-07）：已记下的密清单行（这天你记的 · 条目 · 自我约束）——空的项不占行。
    final planLines = <({String text, Color color})>[
      if (planDayStatus.isNotEmpty)
        (text: '这天你记的是：${_dayStatusHuman(planDayStatus)}', color: AppColors.darkGreen),
      for (final it in items) (text: _planItemText(it), color: AppColors.darkGrey1),
      if (note.isNotEmpty) (text: '（你自己写的约束：$note）', color: AppColors.darkGrey5),
    ];
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        const Text('计划日期', style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
        TextButton.icon(
          onPressed: _planLoading
              ? null
              : () async {
                  final d = await showDatePicker(
                    context: context,
                    initialDate: _planDate,
                    firstDate: DateTime.now().subtract(const Duration(days: 30)),
                    lastDate: DateTime.now().add(const Duration(days: 30)),
                  );
                  // ⚠️ await showDatePicker 之后组件可能已被销毁 → setState 前必须有 mounted 守卫
                  // （2026-10-03 提交前被守卫 G6 拦下：这是本批新写的回调，不是误报）。
                  if (d != null && mounted) {
                    setState(() => _planDate = d);
                    await _loadPlan();
                  }
                },
          icon: const Icon(Icons.event, size: 16),
          label: Text(_planDateStr),
        ),
        const Spacer(),
        TextButton(onPressed: _planLoading ? null : _loadPlan, child: const Text('刷新')),
        TextButton(onPressed: _planLoading ? null : _openPlanReview, child: const Text('收盘对账')),
        TextButton(onPressed: _planLoading ? null : _openRoundsReview, child: const Text('轮次复盘')),
      ]),
      const Text(
        '一句话一行，写清「买什么 / 卖什么、什么条件」——比如「600519 跌破 1400 清仓」；不打算动手就写「明天不动」。',
        style: TextStyle(fontSize: 12, color: AppColors.darkGrey5),
      ),
      const SizedBox(height: 8),
      TextField(
        controller: _planLinesCtrl,
        maxLines: 4,
        style: const TextStyle(fontSize: 13),
        decoration: const InputDecoration(
          hintText: '600519 跌破 1400 清仓\n000776 回到 19.5 以下买 500 股',
          isDense: true,
        ),
      ),
      const SizedBox(height: 8),
      TextField(
        controller: _planNoteCtrl,
        style: const TextStyle(fontSize: 13),
        decoration: const InputDecoration(
          hintText: '自我约束（可空）：只做计划内的票，不追高',
          isDense: true,
        ),
      ),
      const SizedBox(height: 10),
      Row(children: [
        FilledButton(onPressed: _planLoading ? null : _savePlan, child: const Text('保存计划')),
        const SizedBox(width: 12),
        if (_planMsg != null)
          Expanded(
              child: Text(_planMsg!,
                  style: const TextStyle(fontSize: 12, color: AppColors.darkGrey5))),
      ]),
      const SizedBox(height: 14),
      Text(
          _planView == null
              ? '这天还没有写计划。'
              : (items.isEmpty ? '这天没有写计划条目。' : '已记下 ${items.length} 条：'),
          style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
      // m2b（2026-10-07 · 形态微调 · 保功能）：条目从松散段落 → 密清单卡（一行一条 ·
      // 底分隔线），与规则区/原型 web 的密行语言一致；信息与文案一个不少。
      if (planLines.isNotEmpty) ...[
        const SizedBox(height: 6),
        Container(
          width: double.infinity,
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          decoration: BoxDecoration(
            color: AppColors.darkSurface,
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.5)),
          ),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            for (var i = 0; i < planLines.length; i++)
              Container(
                padding: const EdgeInsets.symmetric(vertical: 5),
                decoration: i == planLines.length - 1
                    ? null
                    : BoxDecoration(
                        border: Border(
                            bottom: BorderSide(
                                color: AppColors.darkBorder.withValues(alpha: 0.45)))),
                child: Text(planLines[i].text,
                    style: TextStyle(fontSize: 12.5, color: planLines[i].color)),
              ),
          ]),
        ),
      ],
    ]);
  }

  // ══════════════════════════════════════════════════════════════════════════
  // 批 A（2026-10-08 · UI/UX 落地，**加法部分**）：状态条 + 阿呆说 + 最近的那条线
  //   口径：基础数据一个不少（资金 / 当日 / 市值都在场），**重点＝你自己的线**；
  //   只陈述 + 用你自己的规则对照，不出现「该买 / 该卖 / 建议」（B1 第一原则）。
  //   ⚠️ Tab 9→6 的 IA 重排不在这一批 —— 它撞了 28 个既有 widget 测试（视窗内的点击
  //      目标被挤到屏幕外），留作单独一批连着测试一起改，不混在这里。
  // ══════════════════════════════════════════════════════════════════════════

  /// 到线几只：现价已经到了（或破了）**你自己定的**线。
  /// 这是首屏最该被看见的那件事 —— 所以它不是列表里的一个细节，而是状态条上的一格。
  /// m5（2026-10-07）：口径与表格行标 [_onLine] 对齐——**破止损 ↓ / 到放飞 ↑ 都算**
  /// （设计稿「正向（放飞）与反向（止损）都提醒」；此前只算止损一个方向）。
  int _positionsOnLineCount() {
    var n = 0;
    for (final it in _positions) {
      if (_onLine(it)) n++;
    }
    return n;
  }

  /// 「最近的那条线」——只显示当前更近的一条（全给会变成一堵墙）。
  ({String text, Color color}) _nearestLine(PositionItem p) {
    final sl = p.effectiveStopLoss;
    final tp = p.targetPrice;
    if (sl != null && sl > 0 && p.currentPrice <= sl) {
      return (text: '止损 ${sl.toStringAsFixed(2)} ↓破', color: AppColors.darkOrange);
    }
    if (tp != null && tp > 0 && p.currentPrice >= tp) {
      return (text: '放飞 ${tp.toStringAsFixed(2)} ↑到', color: AppColors.darkOrange);
    }
    if (sl != null && sl > 0 && p.currentPrice > 0) {
      final gap = (p.currentPrice - sl) / p.currentPrice * 100;
      return (text: '离止损 ${gap.toStringAsFixed(1)}%', color: AppColors.darkGrey4);
    }
    return (text: '—', color: AppColors.darkGrey5);
  }

  /// m3（2026-10-07 · 原型 .wd-table tr.on）：这一行到线了吗——破止损 / 到放飞
  /// （与 _nearestLine 前两个分支同口径）。到线行＝整行浅橙 + 行首橙条。
  bool _onLine(PositionItem p) {
    final sl = p.effectiveStopLoss;
    if (sl != null && sl > 0 && p.currentPrice <= sl) return true;
    final tp = p.targetPrice;
    if (tp != null && tp > 0 && p.currentPrice >= tp) return true;
    return false;
  }

  /// m5（2026-10-07 · 原型 .wd-strip「主屏形态」）：**6 格一条**——总资产 · 当日 · 总盈亏 ·
  /// 持仓市值 · 到线 · 账实。由「上方三坨」收敛而来（账户卡 8 张 / 盈亏条 / 旧 3 格状态条）：
  ///   · 可用 / 可取 / 现金 → 账区（自证条 + 现金区，那儿才是资金的家）；
  ///   · 持仓浮盈 / 持仓数 → 持仓表（逐行盈亏 + 表头「持仓 N 只」——不重复占主位）；
  ///   · 到线的票 + 账句 → 右栏「阿呆说」卡（点名式：`600123 现价 26.10，破了你的 27.00`）。
  /// 诚实口径照旧：本金为 0 → 总盈亏「—」不给误导数值；账实拿不到 → 「—」不编「对上了」。
  Widget _buildStatusStrip() {
    final a = _account;
    final p = _portfolio;
    final hasAccount = a != null && a.assets > 0;
    final onLine = _positionsOnLineCount();
    final ir = _integrity;
    final clean = ir != null && !ir.hasIssue; // 拿不到对账（null）≠ 已经对上
    // 当日：金额 + 比例（比例与「当日盈亏」同源 pnl/periods；拿不到比例就不给——不编 0%）
    // m6：金额打码（比例属涨跌，不打——口径「数量与成本打码，现价与止损保留」）
    final todayPnl = hasAccount ? a.todayPnl : 0.0;
    final todayPct = _pnlPeriods?.today?.pct;
    final todayText = '¥${maskIf(_thousands(todayPnl), _amountsRevealed)}'
        '${todayPct == null ? '' : ' ${todayPct >= 0 ? '+' : ''}${todayPct.toStringAsFixed(2)}%'}';
    // 总盈亏 = 资产 − 本金（principal=0 → null → 「—」+ 引导语，不编数）
    final totalPnl = hasAccount ? a.totalPnl : (p?.totalPnl ?? 0);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.5)),
      ),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        _stripCell('总资产',
            '¥${maskIf(_thousands(hasAccount ? a.assets : (p?.totalValue ?? 0) + (p?.cashBalance ?? 0)), _amountsRevealed)}',
            valueKey: const Key('stripAssets')),
        _stripCell('当日', todayText,
            color: todayPnl >= 0 ? AppColors.darkRed : AppColors.darkGreen,
            valueKey: const Key('stripToday'),
            note: hasAccount && todayPnl != 0
                ? todayPnlSourceNote(a.todayPnlSource, a.snapshotDate)
                : null),
        _stripCell('总盈亏', totalPnl == null ? '—' : '¥${maskIf(_thousands(totalPnl), _amountsRevealed)}',
            color: totalPnl == null
                ? AppColors.darkGrey5
                : (totalPnl >= 0 ? AppColors.darkRed : AppColors.darkGreen),
            valueKey: const Key('stripTotalPnl'),
            note: hasAccount
                ? (a.principal > 0
                    ? '本金 ¥${maskIf(_thousands(a.principal), _amountsRevealed)}'
                    : '还没记过转入/转出')
                : null),
        _stripCell('持仓市值', '¥${maskIf(_thousands(hasAccount ? a.marketValue : (p?.totalValue ?? 0)), _amountsRevealed)}',
            valueKey: const Key('stripMarketValue')),
        _stripCell('到线', '$onLine 只',
            color: onLine > 0 ? AppColors.darkOrange : AppColors.darkGrey3,
            valueKey: const Key('stripOnline')),
        // 拿不到对账显示「—」（不编「对上了」）——与账区自证条同口径
        _stripCell('账实', ir == null ? '—' : (clean ? '✓ 对上了' : '⚠ 有差异'),
            color: ir == null
                ? AppColors.darkGrey4
                : (clean ? AppColors.darkGreen : AppColors.darkOrange),
            valueKey: const Key('stripIntegrity')),
      ]),
    );
  }

  /// m3b（2026-10-07 · 原型 .wd-strip 屏条）：label 上（11px 灰）/ 值下（15px w600）——与状态条格子同型。
  /// `valueKey` 供测试锚定「值」文本（标题文字不锚，防两处 strip 撞文案）。
  /// m5：加 `note`（第三行小字：口径来源 / 本金 / 引导——6 格状态条用；默认 null 不影响既有调用）。
  Widget _stripCell(String label, String value,
          {Color? color, Key? valueKey, String? note}) =>
      Expanded(
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(label, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
          const SizedBox(height: 3),
          Text(value,
              key: valueKey,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                  fontSize: 15, fontWeight: FontWeight.w600, color: color ?? AppColors.darkGrey1)),
          if (note != null) ...[
            const SizedBox(height: 2),
            Text(note,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
          ],
        ]),
      );

  /// m3b：买入日 → 「X季度」（原型清仓屏「二季度 61 天」里那个季度 = 在哪个季度买的）。
  /// 日期认不出 → 空串（只显示天数，绝不猜季度）。
  String _quarterLabel(String? date) {
    final d = date == null ? null : DateTime.tryParse(date);
    if (d == null) return '';
    const names = ['一', '二', '三', '四'];
    return '${names[(d.month - 1) ~/ 3]}季度';
  }

  // ══════════════════════════════════════════════════════════════════════════
  // 骨架重排（2026-10-08 · ② 彻底版）：**9 个平铺 Tab → 6 个分区**
  //   持仓（含 自选 / 清仓 筛选）· 账（资金 ‖ 历史成交）· 分析 · 规则 · 案例 · 计划
  //   设计口径见 .agents/workspace/trading-plugin/uiux-discovery-20261007.md §十一
  //   ⚠️ 这一批**连测试一起改** —— 围着旧 9 Tab 写的用例按新结构重写，不退回。
  // ══════════════════════════════════════════════════════════════════════════

  /// 持仓区筛选：0=全部 1=持仓 2=自选 3=清仓（原为三个并列 Tab）。
  /// m4（2026-10-07 · 原型主屏默认「全部」）：默认值改 0——「全部」= 持仓 + 自选混合表
  /// （原型持仓屏表格实锤：自选行轻量（数量/成本…填「—」+「自选」标），5+4=「全部 9」；
  /// 清仓表结构不同（了结日/拿了多久…）不进混合——清仓屏「全部 21」是 mock 自相矛盾，不采用）。
  int _positionFilter = 0;

  /// m4：分析区粒度（global/symbol/round）——提在父页，供左侧导航子项（这一笔/这只票/这一段）
  /// 与分析区内部粒度 chips 双向同步。
  String _analysisScope = 'global';

  /// 持仓区：四个持仓态收成一条筛选（m4：补「全部」第 4 项——原型 chips 就是四项）。
  Widget _buildPositionZone() {
    // m3（2026-10-07 · 原型 .wd-chips）：带计数 + Key 锚（测试不再依赖文案）。
    // m4：「全部」（原型主屏默认 on）= 持仓 + 自选混合表，见 _buildPositionTable(mixed:)。
    final labels = [
      '全部 ${_positions.length + _watchlist.length}',
      '持仓 ${_positions.length}',
      '自选 ${_watchlist.length}',
      '清仓 ${_sold.length}',
    ];
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Wrap(spacing: 8, runSpacing: 8, children: [
        for (var i = 0; i < labels.length; i++)
          ChoiceChip(
            key: Key('posFilter$i'),
            label: Text(labels[i]),
            selected: _positionFilter == i,
            onSelected: (_) => setState(() => _positionFilter = i),
            backgroundColor: AppColors.darkSurface,
            selectedColor: AppColors.darkGreen.withValues(alpha: 0.18),
            labelStyle: TextStyle(
                fontSize: 12,
                fontWeight: _positionFilter == i ? FontWeight.w600 : FontWeight.w400,
                color: _positionFilter == i ? AppColors.darkGrey1 : AppColors.darkGrey5),
            side: BorderSide(color: AppColors.darkBorder.withValues(alpha: 0.6)),
          ),
      ]),
      const SizedBox(height: 10),
      if (_positionFilter == 0) _buildPositionTable(mixed: true),
      if (_positionFilter == 1) _buildPositionTable(),
      if (_positionFilter == 2) _buildWatchlistSection(),
      if (_positionFilter == 3) _buildSoldSection(),
    ]);
  }

  /// 账区：**三合一**（设计口径 §十一）—— 顶部自证条（现金自证 · 本金从数据推 · 断点当场指出）
  /// + 资金在上、流水在下，对账不用来回跳 Tab。
  /// ⚠️ 合并后内容变高：承载它的视窗必须跟着变高（下面 `_workspaceHeight`），
  /// 否则下半个区块连人带测试都点不到 —— 前两次失败的正是这一点，不是布局方向。
  Widget _buildAccountZone() {
    // ⚠️ 两块都拿 **Expanded 分到的有界高度** —— `_HistorySection` 内部用了 Expanded，
    // 把它放进 SingleChildScrollView（高度无界）会当场抛
    // 「non-zero flex but incoming height constraints are unbounded」，每帧一条异常。
    // 高度按需分配：自证条定高在上，其余按 20:36 分给资金/流水（流水那半原本就要 380+ 才不溢出，
    // 2026-10-08 加自证条后压到 ~330 —— 跑测试实测无溢出，同时各块的「顶」都落在视窗内）。
    return Column(children: [
      _buildAccountProofStrip(),
      const SizedBox(height: 10),
      Expanded(flex: 20, child: SingleChildScrollView(child: _buildCashSection())),
      const SizedBox(height: 14),
      Expanded(
        flex: 36,
        child: _HistorySection(
          key: _historyKey,
          api: widget.api,
          // m6：浏览类明细金额/数量默认掩码，👁 状态由页头统一切换
          revealed: _amountsRevealed,
          onImportSnapshot: _openPositionsImport,
          onImported: () => unawaited(_loadIntegrity()),
        ),
      ),
    ]);
  }

  /// 账区顶部「自证条」（2026-10-08 · 账三合一）：**现金自证 · 本金从数据推 · 断点当场指出**。
  /// 与持仓状态条同型（label 上 / 值下 / 副行）：正常也给结论（「✓ 对上了」「✓ 券商 x 的余额」），
  /// 异常说人话（cashNote / principalNote 文案由后端给，前端只渲染）；断点可当场展开明细——
  /// 对账不必再回页面顶部找横幅。
  Widget _buildAccountProofStrip() {
    final a = _account;
    final ir = _integrity;
    final cashNote = a?.cashNote ?? '';
    final cashDate = a?.cashDate ?? '';
    final principal = a?.principal ?? 0;
    final totalPnl = a?.totalPnl;
    final hasIssue = ir?.hasIssue ?? false;
    final holdingsKnown = ir?.holdingsKnown ?? false;
    final driftCount = ir?.drift.length ?? 0;
    final gapCount = ir?.gaps.length ?? 0;
    final degradedWarnCount = ir?.degraded.where((d) => d.inferred).length ?? 0;

    Widget cell(String label, String value, {Color? color, required Widget sub}) => Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(label, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
            const SizedBox(height: 3),
            Text(value,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                    fontSize: 15, fontWeight: FontWeight.w600, color: color ?? AppColors.darkGrey1)),
            const SizedBox(height: 2),
            sub,
          ]),
        );

    // 断点摘要：当场说清是哪种（drift / gaps / 被推断锚定日的降级流水），点「看明细」展开逐行
    final breakParts = <String>[
      if (driftCount > 0) '$driftCount 只持仓不一致',
      if (gapCount > 0) '$gapCount 笔回放缺口',
      if (degradedWarnCount > 0) '$degradedWarnCount 笔没进持仓',
    ];
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.5)),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          // 现金自证：数是券商导进来的（cashDate），健康度人话由后端给（空 = 正常）
          cell('现金', a == null ? '—' : '¥${maskIf(_thousands(a.cash), _amountsRevealed)}',
              sub: cashNote.isNotEmpty
                  ? const Text('⚠ 有异常',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(fontSize: 11, color: AppColors.darkOrange))
                  : Text(cashDate.isNotEmpty ? '✓ 券商 $cashDate 的余额' : '券商来源未记',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                          fontSize: 11,
                          color: cashDate.isNotEmpty ? AppColors.darkGreen : AppColors.darkGrey5))),
          // m5：账户卡退役后「可用 / 可取」在账区安家（贴原型账屏状态条的语义——
          // 资金侧基础数据，旁边就是转入/转出操作；主屏 6 格不再重复）
          cell('可用', a == null ? '—' : '¥${maskIf(_thousands(a.available), _amountsRevealed)}',
              sub: Text(a == null ? '' : '可取 ¥${maskIf(_thousands(a.withdrawable), _amountsRevealed)}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5))),
          // 本金从数据推：= 转入 − 转出（后端含存量迁移调整）；总盈亏 = 资产 − 本金
          cell('本金（转入/转出自动算）', principal > 0 ? '¥${maskIf(_thousands(principal), _amountsRevealed)}' : '—',
              sub: principal > 0 && totalPnl != null
                  ? Text('总盈亏 ${totalPnl >= 0 ? '+' : '-'}¥${maskIf(_thousands(totalPnl.abs()), _amountsRevealed)}',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                          fontSize: 11,
                          color: totalPnl >= 0 ? AppColors.darkRed : AppColors.darkGreen))
                  : const Text('还没记过转入/转出',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(fontSize: 11, color: AppColors.darkGrey5))),
          // 断点当场指出：账实结论 + 摘要（有断点时可展开明细；无差异不打扰）
          cell('账实',
              ir == null ? '—' : (hasIssue ? '⚠ 有差异' : (holdingsKnown ? '✓ 对上了' : '—')),
              color: ir == null
                  ? AppColors.darkGrey4
                  : (hasIssue
                      ? AppColors.darkOrange
                      : (holdingsKnown ? AppColors.darkGreen : AppColors.darkGrey4)),
              sub: ir == null
                  ? const Text('对账还没取到',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(fontSize: 11, color: AppColors.darkGrey5))
                  : hasIssue
                      ? InkWell(
                          onTap: () =>
                              setState(() => _accountProofExpanded = !_accountProofExpanded),
                          child: Text(
                              '${breakParts.join(' · ')} · ${_accountProofExpanded ? '收起' : '看明细'}',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(fontSize: 11, color: AppColors.darkOrange)))
                      : Text(holdingsKnown ? '锚定日 ${ir.anchor?.anchorDate ?? '—'}' : '还没法判定 · 先导持仓快照',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5))),
        ]),
        // 现金人话（后端文案，前端只渲染；空 = 不打扰）
        if (cashNote.isNotEmpty) ...[
          const SizedBox(height: 8),
          Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Icon(Icons.warning_amber_rounded, size: 14, color: AppColors.darkOrange),
            const SizedBox(width: 6),
            Expanded(
                child: Text(cashNote,
                    style: const TextStyle(fontSize: 11, color: AppColors.darkOrange))),
          ]),
        ],
        // 本金置信度说明（后端文案；中性灰——是说明不是告警）
        if (a != null && a.principalNote.isNotEmpty) ...[
          const SizedBox(height: 4),
          Text(a.principalNote, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
        ],
        // 断点当场指出：展开逐行明细（与顶部横幅同一份渲染）
        if (_accountProofExpanded && ir != null && hasIssue) ...[
          const SizedBox(height: 6),
          ..._integrityDetailLines(ir),
        ],
      ]),
    );
  }

  /// 工作区视窗高度：合并后的「账」比原来任何单个 Tab 都高 → 跟着放大（原来的 380 只够一屏）。
  static const double _workspaceHeight = 620;

  /// m4（2026-10-07 · 原型 .wd 骨架）：顶部横 Tab → **左侧竖导航 + 右内容**。
  /// 切区由 `_buildSideNav` 驱 TabController（index 语义不变：0 持仓 1 账 2 分析 3 规则 4 案例 5 计划）。
  Widget _buildTabWorkspace() {
    return DefaultTabController(
      length: 6,
      child: _TabHistoryRefreshListener(
        onHistorySelected: () => _historyKey.currentState?.refreshSilently(),
        child: Builder(builder: (context) {
          final controller = DefaultTabController.of(context);
          return Container(
            decoration: BoxDecoration(
              color: AppColors.darkSurface,
              borderRadius: BorderRadius.circular(10),
              border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.5)),
            ),
            clipBehavior: Clip.antiAlias,
            child: SizedBox(
              height: _workspaceHeight,
              child: Row(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                _buildSideNav(controller),
                // 分隔线（原型 .wd-nav 的 border-right）
                Container(width: 1, color: AppColors.darkBorder.withValues(alpha: 0.6)),
                Expanded(
                  child: TabBarView(controller: controller, children: [
                    SingleChildScrollView(child: _buildPositionZone()),
                    // 账区自己管高度（内含两个 Expanded）→ 不能再套一层无界滚动
                    _buildAccountZone(),
                    // 2026-10-06（R-05）：三粒度「分析」——全局 / 单标的 / 单笔
                    // m4：粒度提父页（requestedScope），导航子项与区内部双向同步
                    SingleChildScrollView(
                        child: _AnalysisSection(
                      api: widget.api,
                      // m6：分析列表项里的金额（回合盈亏/分桶盈亏）默认掩码
                      revealed: _amountsRevealed,
                      requestedScope: _analysisScope,
                      onScopeChanged: (s) {
                        if (s != _analysisScope && mounted) setState(() => _analysisScope = s);
                      },
                    )),
                    SingleChildScrollView(child: _buildRuleSection()),
                    SingleChildScrollView(child: _buildCaseSection()),
                    SingleChildScrollView(child: _buildPlanSection()),
                  ]),
                ),
              ]),
            ),
          );
        }),
      ),
    );
  }

  /// m4（2026-10-07 · 原型 .wd-nav）：左侧竖导航——品牌 / 六个分区 /（激活区的）子项 / 左下脚注。
  /// 子项交互规则：**可点 ⇔ 该区有真实视图切换**（持仓=切筛选、分析=切粒度）；
  /// 账（资金/流水/对账）与案例（等你认/已收下）是目录型标注——对应内容屏内同屏，无切换目标。
  Widget _buildSideNav(TabController controller) {
    return AnimatedBuilder(
      animation: controller,
      builder: (context, _) {
        final idx = controller.index;
        return Container(
          width: 132,
          padding: const EdgeInsets.fromLTRB(10, 12, 10, 12),
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            // 品牌（原型 .wd-brand：交易 / AdaiOS 两行）
            const Padding(
              padding: EdgeInsets.fromLTRB(8, 0, 8, 10),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('交易',
                    style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
                Text('AdaiOS', style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
              ]),
            ),
            ..._navItem(controller, idx, 0, '持仓'),
            if (idx == 0) ..._positionNavSubs(),
            ..._navItem(controller, idx, 1, '账'),
            if (idx == 1) ..._accountNavSubs(),
            ..._navItem(controller, idx, 2, '分析'),
            if (idx == 2) ..._analysisNavSubs(),
            ..._navItem(controller, idx, 3, '规则'),
            ..._navItem(controller, idx, 4, '案例'),
            if (idx == 4) ..._caseNavSubs(),
            ..._navItem(controller, idx, 5, '计划'),
            const Spacer(),
            _navFoot(idx),
          ]),
        );
      },
    );
  }

  /// 导航项（原型 .wd-navitem）：12.5px；选中 = 淡绿底 + 亮字 w500。
  List<Widget> _navItem(TabController controller, int current, int index, String label) {
    final on = current == index;
    return [
      InkWell(
        key: Key('navItem$index'),
        onTap: on ? null : () => controller.animateTo(index),
        borderRadius: BorderRadius.circular(7),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 7),
          decoration: BoxDecoration(
            color: on ? AppColors.darkGreen.withValues(alpha: 0.13) : null,
            borderRadius: BorderRadius.circular(7),
          ),
          child: Text(label,
              style: TextStyle(
                  fontSize: 12.5,
                  fontWeight: on ? FontWeight.w500 : FontWeight.w400,
                  color: on ? AppColors.darkGrey1 : AppColors.darkGrey5)),
        ),
      ),
      const SizedBox(height: 1),
    ];
  }

  /// 子项（原型 .wd-sub）：11.5px、左缩进 20；on = 亮一档。无 onTap 即目录型（不可点、无高亮）。
  Widget _navSub({required Key key, required String label, required bool on, VoidCallback? onTap}) {
    return InkWell(
      key: key,
      onTap: onTap,
      borderRadius: BorderRadius.circular(6),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 4, 8, 4),
        child: Text(label,
            style: TextStyle(fontSize: 11.5, color: on ? AppColors.darkGrey2 : AppColors.darkGrey5)),
      ),
    );
  }

  /// 持仓子项（原型「全部 9 / 持仓 5 / 自选 4 / 清仓 12」）：可点——就是持仓区的筛选开关。
  List<Widget> _positionNavSubs() {
    final labels = [
      '全部 ${_positions.length + _watchlist.length}',
      '持仓 ${_positions.length}',
      '自选 ${_watchlist.length}',
      '清仓 ${_sold.length}',
    ];
    return [
      for (var i = 0; i < labels.length; i++)
        _navSub(
            key: Key('navSub_pos$i'),
            label: labels[i],
            on: _positionFilter == i,
            onTap: () => setState(() => _positionFilter = i)),
    ];
  }

  /// 账子项（原型「资金 / 流水 / 对账」）：目录型——账区三块同屏（资金/流水/对账），无切换目标。
  List<Widget> _accountNavSubs() => [
        _navSub(key: const Key('navSub_acc0'), label: '资金', on: false),
        _navSub(key: const Key('navSub_acc1'), label: '流水', on: false),
        _navSub(key: const Key('navSub_acc2'), label: '对账', on: false),
      ];

  /// 分析子项（原型「这一笔 / 这只票 / 这一段」）：可点——与分析区粒度双向同步。
  List<Widget> _analysisNavSubs() => [
        _navSub(
            key: const Key('navSub_ana0'),
            label: '这一笔',
            on: _analysisScope == 'round',
            onTap: () => setState(() => _analysisScope = 'round')),
        _navSub(
            key: const Key('navSub_ana1'),
            label: '这只票',
            on: _analysisScope == 'symbol',
            onTap: () => setState(() => _analysisScope = 'symbol')),
        _navSub(
            key: const Key('navSub_ana2'),
            label: '这一段',
            on: _analysisScope == 'global',
            onTap: () => setState(() => _analysisScope = 'global')),
      ];

  /// 案例子项（原型「等你认 3 / 已收下 27」）：目录型 + 实时计数（候选/已收下同屏）。
  List<Widget> _caseNavSubs() => [
        _navSub(key: const Key('navSub_case0'), label: '等你认 ${_caseCandidates.length}', on: false),
        _navSub(key: const Key('navSub_case1'), label: '已收下 ${_cases.length}', on: false),
      ];

  /// 左下脚注（原型 .wd-navfoot）：持仓/账给实况（最近导入日期 + 账实结论），
  /// 其余给屏相关文案（分析/案例句来自原型；规则/计划原型无屏——复用「看的是事实」这句全局精神）。
  Widget _navFoot(int tabIndex) {
    final parts = <Widget>[];
    Widget line(String text, {Color? color}) => Padding(
          padding: const EdgeInsets.only(bottom: 2),
          child: Text(text,
              style: TextStyle(fontSize: 11, height: 1.6, color: color ?? AppColors.darkGrey5)),
        );
    if (tabIndex == 0 || tabIndex == 1) {
      // 「最近导入」= 锚定日（有）→ 账户快照日（兜底）；只有日期粒度（原型带时分是 mock）
      final raw = _integrity?.anchor?.anchorDate ?? _account?.snapshotDate ?? '';
      if (raw.isNotEmpty) parts.add(line('最近导入 ${raw.length >= 10 ? raw.substring(5, 10) : raw}'));
      final ir = _integrity;
      if (ir == null) {
        // m4：避开与账区自证条 sub「对账还没取到」同文（脚注空间小 + 测试断言会双命中）
        parts.add(line('账实未知'));
      } else if (ir.hasIssue) {
        parts.add(line('账实有差异', color: AppColors.darkOrange));
      } else if (ir.holdingsKnown) {
        parts.add(line('账实对上了'));
      } else {
        parts.add(line('还没锚定 · 先导快照'));
      }
    } else if (tabIndex == 4) {
      parts.add(line('案例是规则的出口'));
      parts.add(line('它从你的记录里长'));
    } else {
      parts.add(line('看的是事实'));
      parts.add(line('不是预测'));
    }
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Container(height: 1, color: AppColors.darkBorder.withValues(alpha: 0.6)),
      const SizedBox(height: 8),
      ...parts,
    ]);
  }

  /// 买点信号列文案（P2-案例2，2026-09-03）：buyPoint="case" = 规则未命中但形态接近库中
  /// 完美买点（后端附 caseMatches，score=0 无意义）——改显「案例相似 + 最高相似度」，
  /// 不再渲染异常的「case 0%」。
  String _buyPointLabel(BuyPointDto b) {
    if (b.buyPoint == 'case') {
      double top = 0;
      for (final m in b.caseMatches) {
        if (m.similarityPercent > top) top = m.similarityPercent;
      }
      return top > 0 ? '案例相似 ${top.round()}%' : '案例相似';
    }
    return '${b.buyPoint} ${b.score.toStringAsFixed(0)}%';
  }

  Widget _buildWatchlistSection() {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        const Text('自选股', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
        const SizedBox(width: 8),
        Text('${_watchlist.length} 只 · 阿呆帮你盯买点', style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
        const Spacer(),
        OutlinedButton.icon(
          onPressed: () => _openImportDialog(
              '粘贴通达信自选导出（或选择文件）：代码/名称/细分行业/长期中期短期形态/近日指标提示',
              (c, _, _) async {
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
      const Text('买点信号：B1=回调缩量低吸 · B2=放量突破右侧 · 案例=形态接近历史完美买点（判定是提示不是指令）',
          style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
      const SizedBox(height: 8),
      if (_watchlist.isEmpty)
        const Text('暂无自选股——导入通达信自选导出，阿呆帮你盯买点',
            style: TextStyle(fontSize: 12, color: AppColors.darkGrey5))
      else
        _scrollableTable(
          // 2026-10-07 批 6 小尾巴：加「图」列（+40）——自选也能开 K 线（与持仓/清仓同一张通用图）
          table: DataTable(
            headingRowHeight: 30, dataRowMinHeight: 32, dataRowMaxHeight: 32,
            columns: const [
              // m3：名称与代码合并一列（原型口径，同持仓表）
              DataColumn(label: Text('代码 / 名称')), DataColumn(label: Text('行业')),
              DataColumn(label: Text('长/中/短')),
              DataColumn(label: Text('指标提示')), DataColumn(label: Text('买点信号')),
              // m3：近 20 日迷你走势（54×14；拿不到「—」）
              DataColumn(label: Text('近 20 日')),
              DataColumn(label: Text('图')), DataColumn(label: Text('')),
            ],
            rows: _watchlist.map((w) {
              // C2 买点信号：命中 B1/B2 显示红色徽标（判定是提示不是指令）
              final bp = _buyPoints.where((b) => b.symbol == w.symbol).toList();
              return DataRow(cells: [
                // m3：名称在前（小号灰代码在后）——与持仓/清仓同一口径
                DataCell(Row(mainAxisSize: MainAxisSize.min, children: [
                  Text(w.name, style: const TextStyle(fontSize: 12)),
                  const SizedBox(width: 6),
                  Text(w.symbol, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
                ])),
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
                        child: Text(bp.map(_buyPointLabel).join('、'),
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                                fontSize: 12,
                                fontWeight: FontWeight.w600,
                                // P2-案例2（2026-09-03）：case=形态相似弱参考，橙色区分于规则命中红
                                color: bp.first.buyPoint == 'case'
                                    ? AppColors.darkOrange
                                    : AppColors.darkRed)))),
                // m3：近 20 日迷你走势（54×14；拿不到「—」）——插在买点信号后、图前
                DataCell(_sparkCell(w.symbol)),
                // 2026-10-07（批 6 小尾巴）：自选行「图」入口——样式与清仓表同一款
                DataCell(TextButton(
                  onPressed: () => _openKline(w.symbol, w.name),
                  style: TextButton.styleFrom(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                    minimumSize: Size.zero,
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  ),
                  child: const Text('图', style: TextStyle(fontSize: 12, color: AppColors.darkOrange)),
                )),
                DataCell(IconButton(
                  icon: const Icon(Icons.close, size: 14, color: AppColors.darkGrey5),
                  onPressed: () => _removeWatchlist(w),
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
    // 2026-10-08 卖掉之后到现在（回答「我卖飞了没」）：涨=走早了（橙）/ 跌=走对了（绿）；
    // flat（没动）与拿不到（null）都不计——两数只在有数据且 >0 时显示，不编「0 只」
    final soldAfterUp = _soldAfter.where((a) => a.direction == 'up').length;
    final soldAfterDown = _soldAfter.where((a) => a.direction == 'down').length;
    // m3b（2026-10-07 · 原型清仓屏五格）：「合计」= 各笔持仓期涨幅直接相加（你口语里
    // 「这一串操作总共赚了几个点」的口径——不做资金加权）；「最长拿着」= 持仓天数最大的一笔
    // + 它的买入季度（原型「二季度 61 天」）。日期认不出只显示天数，不猜季度。
    final sumPct = _sold.fold<double>(0, (a, s) => a + s.holdPnlPct);
    SoldTradeDto? longest;
    for (final s in _sold) {
      if (longest == null || s.holdDays > longest.holdDays) longest = s;
    }
    final longestQuarter = longest == null ? '' : _quarterLabel(longest.buyDate);
    final longestText = longest == null
        ? '—'
        : '${longestQuarter.isEmpty ? '' : '$longestQuarter '}${longest.holdDays} 天';
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.5)),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        // m3b：五格屏条（原型 .wd-strip）——统计抢主位；下面小字保留规则细节（R66/R53/胜率…）
        Row(children: [
          _stripCell('清仓', '$total 只', valueKey: const Key('soldStatCount')),
          _stripCell('合计', '${sumPct > 0 ? '+' : ''}${sumPct.toStringAsFixed(2)}%',
              color: sumPct > 0
                  ? AppColors.darkRed
                  : (sumPct < 0 ? AppColors.darkGreen : AppColors.darkGrey3),
              valueKey: const Key('soldStatTotal')),
          _stripCell('卖掉之后又跌', soldAfterDown > 0 ? '$soldAfterDown 只 · 走对了' : '—',
              color: soldAfterDown > 0 ? AppColors.darkGreen : AppColors.darkGrey4,
              valueKey: const Key('soldStatAfterDown')),
          _stripCell('卖掉之后又涨', soldAfterUp > 0 ? '$soldAfterUp 只 · 走早了' : '—',
              color: soldAfterUp > 0 ? AppColors.darkOrange : AppColors.darkGrey4,
              valueKey: const Key('soldStatAfterUp')),
          _stripCell('最长拿着', longestText, valueKey: const Key('soldStatLongest')),
        ]),
        const SizedBox(height: 8),
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
          // （m3b：原「卖掉之后又跌/又涨」两段小字已上提为屏条格子，此处不再重复）
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

  /// R-04（2026-10-07）：通用 K 线 —— **一张图四处共用**（持仓 / 自选 / 清仓 / 案例）。
  /// 后端一次给齐「蜡烛 + 我的买卖点 + 你定的止损线 + 峰值浮盈线 + 上下文」；
  /// 副图（成交量 / MACD / KDJ）由 CaseKlineChart 从 OHLCV 重算 —— 与生产案例图同口径。
  /// 行情取不到时**只说取不到**，不画空图、不沿用旧价（承接需求「缺数据不编」）。
  Future<void> _openKline(String symbol, String name) async {
    TradingKlineDto? data;
    String? error;
    try {
      data = await widget.api.fetchTradingKline(symbol);
    } catch (e) {
      error = extractApiErrorMessage(e);
    }
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.darkSurface,
        title: Text('$name $symbol · K 线',
            style: const TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
        content: SizedBox(
          width: 760,
          child: data == null
              ? Text(error ?? '取不到这只票的行情',
                  style: const TextStyle(fontSize: 12, color: AppColors.darkGrey4))
              : (!data.hasData
                  ? Text(data.note ?? '暂时取不到这只票的行情',
                      style: const TextStyle(fontSize: 12, color: AppColors.darkGrey4))
                  : Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        SizedBox(
                          height: 430,
                          child: CaseKlineChart(
                            kline: data.candles,
                            marks: data.marks,
                            stopLine: data.stopLine,
                            peakLine: data.peakLine,
                          ),
                        ),
                        const SizedBox(height: 6),
                        Text(_klineSummary(data),
                            style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
                      ],
                    )),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('关掉')),
        ],
      ),
    );
  }

  /// 图下一句话交代：几个买卖点 / 你的那条线 / 现在还拿着吗、什么时候清的。
  String _klineSummary(TradingKlineDto d) {
    final parts = <String>['我的买卖点 ${d.marks.length} 个'];
    if (d.stopLine != null) parts.add('你定的止损 ${d.stopLine!.toStringAsFixed(2)}');
    if (d.peakLine != null) parts.add('峰值浮盈线 ${d.peakLine!.toStringAsFixed(2)}');
    if (d.held) {
      parts.add('现在还拿着');
    } else if (d.closedAt != null) {
      parts.add('${d.closedAt} 清的');
      if (d.holdPnlPct != null) {
        parts.add('这笔 ${d.holdPnlPct! >= 0 ? '+' : ''}${d.holdPnlPct!.toStringAsFixed(2)}%');
      }
    }
    return parts.join(' · ');
  }

  Widget _buildSoldSection() {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Row(children: [
        const Text('清仓股复盘', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
        const SizedBox(width: 8),
        Text('${_sold.length} 笔 · B/S 对照规则判对错', style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
        const Spacer(),
        OutlinedButton.icon(
          onPressed: () => _openImportDialog(
              '粘贴通达信清仓导出（或选择文件）：代码/名称/介入日期/清仓日期/持仓天数/买卖次数/持仓期涨幅%',
              (c, _, _) async {
                final r = await widget.api.importSold(c);
                await _loadAll();
                if (!mounted) return;
                // P2-交易83（2026-10-04）：后端如实回传没看懂的行——有丢行时不能只说「导入 N 笔」，
                // 必须说清「这几行对应的清仓股可能没进来」，并可逐条展开（复用历史成交的橙色警示块）。
                await _showImportReceipt(
                  receipt: '清仓股导入 ${r.imported} 笔',
                  unparsed: r.unparsed,
                  unparsedCount: r.unparsedCount,
                  header: (n) => '有 $n 行没能识别（你的清仓股可能少了几只）',
                );
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
      // 三官深审（2026-09-09）：来源徽标说明拆独立短行（避免与规则术语长句折行混排）
      const Text('名称旁「流水」徽标 = 该清仓记录由成交流水自动收录（非券商导出）',
          style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
      const SizedBox(height: 8),
      // RFC 20260909 批1 清仓双轨：pending 横幅放图例后、统计/空态/表格前——即使 _sold 为空也可见
      if (_pendingClearances.isNotEmpty) ...[
        _buildPendingClearanceBanner(),
        const SizedBox(height: 8),
      ],
      if (_sold.isNotEmpty) _buildSoldStats(),
      const SizedBox(height: 8),
      if (_sold.isEmpty)
        const Text('暂无清仓记录——导入通达信清仓导出，阿呆对照规则给你判对错',
            style: TextStyle(fontSize: 12, color: AppColors.darkGrey5))
      else
        _scrollableTable(
          table: DataTable(
            headingRowHeight: 30, dataRowMinHeight: 32, dataRowMaxHeight: 32,
            columns: const [
              // m3：名称与代码合并一列（原型口径，同持仓表）
              DataColumn(label: Text('代码 / 名称')),
              DataColumn(label: Text('介入→清仓')), DataColumn(label: Text('天数')),
              DataColumn(label: Text('持仓期涨幅')),
              // 2026-10-08：卖掉之后到现在（↑走早了·↓走对了）——清仓独有的一列
              DataColumn(label: Text('卖掉之后到现在')),
              DataColumn(label: Text('规则对照')),
              DataColumn(label: Text('买点分')), DataColumn(label: Text('执行分')), DataColumn(label: Text('总分')),
              DataColumn(label: Text('心理标注')),
              DataColumn(label: Text('图')),
            ],
            rows: _sold.asMap().entries.map((e) {
              // D3 三维打分：按列表顺序索引匹配（P1-交易8 修复，2026-08-17）
              // 后端 SoldScoreService.score 按 sold 列表顺序逐笔返回；同代码多笔时
              // 旧实现按 symbol .first 会把两笔的分数都挂到第一笔上（错挂）
              final s = e.value;
              final score = e.key < _soldScores.length ? _soldScores[e.key] : null;
              return DataRow(cells: [
                // m3：名称与代码合并（名在前、代码小号灰在后）+ 来源徽标仍挂在名称行
                DataCell(Row(mainAxisSize: MainAxisSize.min, children: [
                  Text(s.name, style: const TextStyle(fontSize: 12)),
                  const SizedBox(width: 6),
                  Text(s.symbol, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
                  // RFC 20260909 批 2 子项（2026-09-09 晚间批）：来源徽标——flow=由成交流水自动收录
                  if (s.provenance == 'flow') ...[
                    const SizedBox(width: 6),
                    // 三官深审（2026-09-09）：徽标用中性灰（原 darkBlue 与本表买点高分蓝撞色，V9-8）
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
                      decoration: BoxDecoration(
                        color: AppColors.darkGrey4.withValues(alpha: 0.16),
                        borderRadius: BorderRadius.circular(4),
                      ),
                      child: const Text('流水',
                          style: TextStyle(fontSize: 11, color: AppColors.darkGrey3)),
                    ),
                  ],
                ])),
                DataCell(Text('${s.buyDate ?? '?'}→${s.sellDate ?? '?'}',
                    style: const TextStyle(fontSize: 12))),
                DataCell(Text('${s.holdDays}天', style: const TextStyle(fontSize: 12))),
                DataCell(Text('${s.holdPnlPct.toStringAsFixed(2)}%', style: TextStyle(fontSize: 12,
                    color: s.holdPnlPct >= 0 ? AppColors.darkRed : AppColors.darkGreen))),
                DataCell(_soldAfterCell(e.key)),
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
                DataCell(TextButton(
                  onPressed: () => _openKline(s.symbol, s.name),
                  style: TextButton.styleFrom(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                    minimumSize: Size.zero,
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  ),
                  child: const Text('图', style: TextStyle(fontSize: 12, color: AppColors.darkOrange)),
                )),
              ]);
            }).toList(),
          ),
        ),
    ]);
  }

  /// 2026-10-08 清仓「卖掉之后到现在」单元格（回答「我卖飞了没」）：
  /// ↑ 走早了（橙）/ ↓ 走对了（绿）/ 没动（灰）；拿不到 → 「—」（tooltip 说原因，不编）。
  /// 文案由后端 direction 驱动（与判据同源）——不会出现「显示 0.0% 却标 ↑」。
  Widget _soldAfterCell(int index) {
    final a = index < _soldAfter.length ? _soldAfter[index] : null;
    if (a == null || a.pct == null) {
      final t = Text('—', style: const TextStyle(fontSize: 12, color: AppColors.darkGrey5));
      final note = a?.note;
      return note == null ? t : Tooltip(message: note, child: t);
    }
    final mag = a.pct!.abs().toStringAsFixed(1);
    final String text;
    final Color color;
    if (a.direction == 'up') {
      text = '+$mag% ↑ 走早了';
      color = AppColors.darkOrange; // 卖后涨 = 警示（与「这笔」的涨跌色语义不同）
    } else if (a.direction == 'down') {
      text = '-$mag% ↓ 走对了';
      color = AppColors.darkGreen;
    } else {
      text = '0.0% 没动';
      color = AppColors.darkGrey4;
    }
    return Text(text, style: TextStyle(fontSize: 12, color: color));
  }

  /// RFC 20260909 批1 清仓双轨：pending 横幅——流水已清仓但缺买入基线（条件 B 只提示不写脏），
  /// 展示前 3 只「名称(代码)」（多于 3 只加「等」），引导导入通达信「清仓股」导出补全复盘档案。
  Widget _buildPendingClearanceBanner() {
    final n = _pendingClearances.length;
    final shown = _pendingClearances
        .where((p) => p.symbol.isNotEmpty || p.name.isNotEmpty)
        .take(3)
        .map((p) => p.symbol.isNotEmpty
            ? '${p.name.isEmpty ? p.symbol : p.name}(${p.symbol})'
            : p.name)
        .join('、');
    final tail = n > 3 ? '等' : '';
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.8)),
      ),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Icon(Icons.info_outline, size: 16, color: AppColors.darkOrange),
        const SizedBox(width: 8),
        Expanded(
          child: Text.rich(
            TextSpan(
              style: const TextStyle(fontSize: 12, color: AppColors.darkGrey2, height: 1.4),
              children: [
                const TextSpan(text: '检测到 '),
                TextSpan(text: '$n',
                    style: const TextStyle(color: AppColors.darkOrange, fontWeight: FontWeight.w700)),
                const TextSpan(text: ' 只股票已清仓但缺复盘档案（流水缺买入基线）：'),
                TextSpan(text: '$shown$tail'),
                const TextSpan(text: '——导入通达信「清仓股」导出即可补全复盘档案'),
              ],
            ),
          ),
        ),
      ]),
    );
  }

  /// RFC 20260923 D 批：行情链路横幅（橙色，可展开）——**只有 ok=false 才由调用方渲染**。
  /// 标题给一句人话结论，正文直接用后端 note（它已经是人话，前端再翻译一遍只会产生第二套口径）；
  /// 取数链/时刻/次数收进展开区：用户第一眼要的是「阿呆现在拿不到行情」，复查细节是第二步。
  Widget _buildMarketHealthBanner(MarketDataHealthDto h) {
    final details = <String>[
      if (h.lastSuccessAt != null)
        '最近成功 ${h.lastSuccessAt}${h.lastSuccessSource != null ? ' · ${h.lastSuccessSource}' : ''}',
      if (h.lastFailureAt != null) '最近失败 ${h.lastFailureAt}',
      if (h.consecutiveFailures > 0) '连续失败 ${h.consecutiveFailures} 次',
      if (h.sources.isNotEmpty) '取数链 ${h.sources.join(' → ')}',
    ];
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: AppColors.darkOrange.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.darkOrange.withValues(alpha: 0.55)),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        InkWell(
          onTap: () => setState(() => _marketHealthExpanded = !_marketHealthExpanded),
          child: Row(children: [
            const Icon(Icons.cloud_off_rounded, size: 16, color: AppColors.darkOrange),
            const SizedBox(width: 8),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                const Text('阿呆最近拿不到行情',
                    style: TextStyle(
                        fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.darkOrange)),
                // note 可能为空（后端异常返回残缺 JSON）→ 只留标题，绝不显示空行
                if (h.note.isNotEmpty) ...[
                  const SizedBox(height: 2),
                  Text(h.note, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey3)),
                ],
              ]),
            ),
            const SizedBox(width: 8),
            Text(_marketHealthExpanded ? '收起' : '看明细',
                style: const TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
            Icon(_marketHealthExpanded ? Icons.expand_less : Icons.expand_more,
                size: 16, color: AppColors.darkGrey4),
          ]),
        ),
        if (_marketHealthExpanded && details.isNotEmpty) ...[
          const SizedBox(height: 6),
          for (final d in details)
            Padding(
              padding: const EdgeInsets.only(bottom: 3),
              child: Text(d, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey2)),
            ),
        ],
      ]),
    );
  }

  /// 对账断点明细（账区自证条展开时渲染，2026-10-08 账三合一）：drift 逐标的 /
  /// gaps 逐笔 / 被推断锚定日的降级流水 + 指路句。调用方负责「展开时才显示」。
  /// ⚠️ 降级流水只列 `inferred=true`（无据归一化，2026-10-03 用户反馈）——休市日导出归一化有据，
  /// 不在此列（快照即真相）；口径不因搬家（旧顶部横幅 → 账区自证条）而改变。
  List<Widget> _integrityDetailLines(IntegrityReportDto r) {
    final degradedWarn = r.degraded.where((d) => d.inferred).toList();
    return [
      for (final d in r.drift)
        Padding(
          padding: const EdgeInsets.only(bottom: 3),
          child: Text(
              '${d.symbol} ${d.name}：应有 ${d.derived} 股'
              '（快照基线 ${d.snapshotQty ?? 0} + 锚点后流水 ${d.ledgerDelta > 0 ? '+' : ''}${d.ledgerDelta}），'
              '落地 ${d.holdings ?? 0} 股，差 ${d.diff > 0 ? '+' : ''}${d.diff}',
              style: const TextStyle(fontSize: 11, color: AppColors.darkGrey2)),
        ),
      for (final g in r.gaps)
        Padding(
          padding: const EdgeInsets.only(bottom: 3),
          child: Text('回放缺口 · ${g.display}',
              style: const TextStyle(fontSize: 11, color: AppColors.darkGrey2)),
        ),
      // 锚定日归一化**没有依据**（文件日期是交易日却被归一化）→ 快照基准日可能不是这天，必须让人看见
      for (final d in degradedWarn)
        Padding(
          padding: const EdgeInsets.only(bottom: 3),
          child: Text(
              '只记了流水、没进持仓 · ${d.name}(${d.symbol}) '
              '${d.direction == 'BUY' ? '买' : '卖'} ${d.volume} 股'
              '${d.price != null ? ' @${d.price}' : ''}（${d.entryDate ?? '—'}）',
              style: const TextStyle(fontSize: 11, color: AppColors.darkOrange)),
        ),
      const SizedBox(height: 2),
      const Text('先导一次「持仓股」或「资金股份查询」快照，我就能重新对上了。',
          style: TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
    ];
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
      _toast('标注失败：${extractApiErrorMessage(e)}');
    }
  }

  Widget _buildCashSection() {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      // 2026-10-08 账三合一：操作行（转入/转出/导入资金）提到区块最顶部常驻可点——
      // 自证条占用账区顶部高度后，曲线卡若在上会把按钮挤出滚动视口（人点不到·测试 hit-test 也失败）；
      // 曲线卡无交互，随内容滚动即可。
      Row(children: [
        const Text('资金股份查询', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
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
          onPressed: () => _openImportDialog(
              '粘贴通达信「资金股份查询」导出（或选择文件）：更新现金余额 + 精确成本价（4 位）',
              _importCashSnapshot,
              withBasisDate: true),
          icon: const Icon(Icons.upload_file, size: 14),
          label: const Text('导入资金', style: TextStyle(fontSize: 12)),
          style: OutlinedButton.styleFrom(
              foregroundColor: AppColors.darkGrey1,
              side: const BorderSide(color: AppColors.darkGrey4),
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4)),
        ),
      ]),
      const SizedBox(height: 6),
      const Text('现金余额是 R81 仓位判定的分母（总资产=持仓+现金）——资金查询导入后占比判定更准',
          style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
      const SizedBox(height: 10),
      // 2026-09-04 资金曲线（决策方案 A）：净值 + 回撤迷你图（m6：总资产形态的「最新」金额打码）
      _EquityCurveCard(api: widget.api, revealed: _amountsRevealed),
      // 2026-10-08 账三合一：本金行（原「设置本金」入口——后端已退役为 410，撤）/ cashNote /
      // principalNote 全部收进账区顶部「自证条」，这里不再重复渲染。
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
      'constraintRuleMin': '纪律硬约束：规则号下限',
      'constraintRuleMax': '纪律硬约束：规则号上限',
    };
    // m2b（2026-10-07 · 形态微调 · 保功能）：参数从徽章墙 → 分组密清单——与原型 web 的
    // 密行语言一致（小标题分节 · 左标签右值 · 底分隔线）；未登记的 key 兜底进「其它」，
    // 后端今后加参数不静默吞。
    const groups = <String, List<String>>{
      '仓位与止损': ['positionLimitPercent', 'defaultStopLossRatio'],
      '浮盈回吐': ['givebackPeakPct', 'givebackRatioPct'],
      '短线与清仓': ['shortOverdueDays', 'soldStopLossPct', 'soldShortHoldDays'],
      '买点': [
        'buyPullbackPct',
        'buyShrinkRatio',
        'buyKdjLow',
        'buyVolumeSurge',
        'buyPriorHighDays'
      ],
      '打分与硬约束': [
        'scoreBuyWeight',
        'scoreExecWeight',
        'constraintRuleMin',
        'constraintRuleMax'
      ],
    };
    final known = <String>{for (final l in groups.values) ...l};
    final others = _ruleParams.keys.where((k) => !known.contains(k)).toList();

    Widget ruleLine(String key, {bool last = false}) => Container(
          padding: const EdgeInsets.symmetric(vertical: 5),
          decoration: last
              ? null
              : BoxDecoration(
                  border: Border(
                      bottom: BorderSide(color: AppColors.darkBorder.withValues(alpha: 0.45)))),
          child: Row(children: [
            Expanded(
                child: Text(labels[key] ?? key,
                    style: const TextStyle(fontSize: 12, color: AppColors.darkGrey3))),
            Text(_ruleParams[key] ?? '',
                style: const TextStyle(
                    fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
          ]),
        );
    Widget ruleGroupTitle(String text, {bool first = false}) => Padding(
          padding: EdgeInsets.only(top: first ? 2 : 12, bottom: 2),
          child: Text(text,
              style: const TextStyle(
                  fontSize: 10.5, color: AppColors.darkGrey5, letterSpacing: 0.4)),
        );

    final sections = <Widget>[];
    var firstSection = true;
    for (final g in groups.entries) {
      final keys = g.value.where(_ruleParams.containsKey).toList();
      if (keys.isEmpty) continue;
      sections.add(ruleGroupTitle(g.key, first: firstSection));
      firstSection = false;
      for (var i = 0; i < keys.length; i++) {
        sections.add(ruleLine(keys[i], last: i == keys.length - 1));
      }
    }
    if (others.isNotEmpty) {
      sections.add(ruleGroupTitle('其它', first: firstSection));
      for (var i = 0; i < others.length; i++) {
        sections.add(ruleLine(others[i], last: i == others.length - 1));
      }
    }

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
      const SizedBox(height: 10),
      // 密清单卡：组标题 + 左标签右值行（sections 已按组排好）
      Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        decoration: BoxDecoration(
          color: AppColors.darkSurface,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.5)),
        ),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: sections),
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

  // 批 ③（2026-10-08）案例候选：「从你的记录里长出来的 —— 我不替你定，你认了才算」。
  // pending 每次现算（数据变了候选跟着变）；accepted = 你收下的（决定落盘）。
  // 失败静默：候选拿不到就整区不显示——宁可不显示，也不弹错打扰（零噪音是默认）。
  List<Map<String, dynamic>> _caseCandidates = [];
  List<Map<String, dynamic>> _caseAccepted = [];
  bool _caseCandidatesLoaded = false;

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

  /// 加载案例候选（GET /trading/cases/candidates）。失败静默——候选是增强项，
  /// 拿不到就整区不显示，不打断案例版块（宁可不显示，也不弹错）。
  Future<void> _loadCaseCandidates() async {
    try {
      final r = await widget.api.getCaseCandidates();
      if (!mounted) return;
      setState(() {
        _caseCandidates = r['pending'] ?? const [];
        _caseAccepted = r['accepted'] ?? const [];
        _caseCandidatesLoaded = true;
      });
    } catch (_) {
      // 静默降级：候选拿不到 ≠ 案例坏了（零噪音是默认）
    }
  }

  /// m3b（2026-10-07 · 原型案例屏「本周新增」）：本周（周一起算）收下的候选数。
  /// 返回 null = 算不了（候选端点没拿到 / accepted 全都没有时间字段）——该格直接不显示，不编 0。
  int? _acceptedThisWeek() {
    if (!_caseCandidatesLoaded) return null;
    final now = DateTime.now();
    final monday = DateTime(now.year, now.month, now.day)
        .subtract(Duration(days: now.weekday - 1));
    var parsedAny = false;
    var count = 0;
    for (final c in _caseAccepted) {
      final d = DateTime.tryParse('${c['updatedAt'] ?? c['createdAt'] ?? ''}');
      if (d == null) continue;
      parsedAny = true;
      if (!d.isBefore(monday)) count++;
    }
    if (_caseAccepted.isNotEmpty && !parsedAny) return null;
    return count;
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
      // 批 ③：候选半区（从记录里长出来 + 已经收下的）在案例列表上方——先认新的，再看库
      ..._buildCaseCandidateSection(),
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
          onPressed: _openCaseImportDialog,
          icon: const Icon(Icons.playlist_add, size: 14, color: AppColors.darkGreen),
          label: const Text('批量导入', style: TextStyle(fontSize: 12)),
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
      // m3b：四格屏条（已收下 / 成功·失败 / 本周新增 / 等你认）——原型案例屏 .wd-strip
      ..._buildCaseStatStrip(),
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

  /// m3b（2026-10-07 · 原型案例屏四格 .wd-strip）：已收下 / 成功·失败 / 本周新增 / 等你认。
  /// 「本周新增」与「等你认」来自候选端点——没拿到就少这两格（不编 0）；
  /// 整库全空（没案例也没候选）→ 整条不显示（空态文案已够，不叠空壳）。
  List<Widget> _buildCaseStatStrip() {
    if (_cases.isEmpty && _caseCandidates.isEmpty && _caseAccepted.isEmpty) return const [];
    final failed = _cases.where((c) => '${c['buyType']}' == 'FAILED').length;
    final weekNew = _acceptedThisWeek();
    return [
      Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
        decoration: BoxDecoration(
          color: AppColors.darkSurface,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.5)),
        ),
        child: Row(children: [
          _stripCell('已收下', '${_cases.length}', valueKey: const Key('caseStatAccepted')),
          _stripCell('成功 / 失败', '${_cases.length - failed} / $failed',
              valueKey: const Key('caseStatSuccessFail')),
          if (weekNew != null)
            _stripCell('本周新增', '$weekNew',
                color: weekNew > 0 ? AppColors.darkRed : AppColors.darkGrey3,
                valueKey: const Key('caseStatWeekNew')),
          if (_caseCandidatesLoaded)
            _stripCell('等你认', '${_caseCandidates.length}',
                color: _caseCandidates.isNotEmpty ? AppColors.darkOrange : AppColors.darkGrey3,
                valueKey: const Key('caseStatPending')),
        ]),
      ),
      const SizedBox(height: 8),
    ];
  }

  /// 案例候选半区（批 ③）：候选卡（等你认）+「已经收下的」四列小表。
  /// 没候选且没收下过 → 整区不显示（沉默是默认）；加载失败同样不显示。
  List<Widget> _buildCaseCandidateSection() {
    if (!_caseCandidatesLoaded) return const [];
    if (_caseCandidates.isEmpty && _caseAccepted.isEmpty) return const [];
    return [
      if (_caseCandidates.isNotEmpty) ...[
        Row(children: [
          const Text('从你的记录里长出来的',
              style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
          const SizedBox(width: 8),
          Text('我不替你定，你认了才算 · 等你认 ${_caseCandidates.length} 条',
              style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
        ]),
        const SizedBox(height: 6),
        ..._caseCandidates.map((c) => _buildCandidateCard(c)),
      ],
      if (_caseAccepted.isNotEmpty) ...[
        if (_caseCandidates.isNotEmpty) const SizedBox(height: 10),
        Row(children: [
          const Text('已经收下的',
              style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
          const SizedBox(width: 8),
          Text('${_caseAccepted.length} 条 · 案例是规则的出口',
              style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
        ]),
        const SizedBox(height: 6),
        _buildAcceptedHeader(),
        ..._caseAccepted.map((c) => _buildAcceptedRow(c)),
      ],
      const SizedBox(height: 12),
    ];
  }

  /// 一条候选卡：结果签 + 标题 + 卡体文案（对照句 / 事实句）+ 三动作（收下 / 改一改 / 不要）。
  Widget _buildCandidateCard(Map<String, dynamic> c) {
    final id = '${c["id"]}';
    final outcome = '${c["outcome"] ?? ''}';
    final title = '${c["title"] ?? ''}';
    final notes = ((c['notes'] as List?) ?? const []).map((e) => '$e').toList();
    final outcomeColor = _candidateOutcomeColor(outcome);
    return Container(
      margin: const EdgeInsets.only(bottom: 6),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.5)),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Flexible(
            child: Text(title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
          ),
          const SizedBox(width: 6),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
            decoration: BoxDecoration(
              color: outcomeColor.withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(4),
              border: Border.all(color: outcomeColor.withValues(alpha: 0.45)),
            ),
            child: Text(_candidateOutcomeLabel(outcome),
                style: TextStyle(fontSize: 10.5, color: outcomeColor)),
          ),
        ]),
        for (final n in notes)
          Padding(
            padding: const EdgeInsets.only(top: 3),
            child: Text(n, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
          ),
        const SizedBox(height: 6),
        Row(children: [
          _candidateActionButton('收下', () => _acceptCaseCandidate(id), primary: true),
          const SizedBox(width: 8),
          _candidateActionButton('改一改', () => _openRenameCandidateDialog(id, title)),
          const SizedBox(width: 8),
          _candidateActionButton('不要', () => _dismissCaseCandidate(id)),
        ]),
      ]),
    );
  }

  /// 候选三动作小按钮（收下 = 绿色主按钮；改一改 / 不要 = 灰描边）。
  Widget _candidateActionButton(String label, VoidCallback onPressed, {bool primary = false}) {
    return OutlinedButton(
      onPressed: onPressed,
      style: OutlinedButton.styleFrom(
        foregroundColor: primary ? AppColors.darkGreen : AppColors.darkGrey3,
        side: BorderSide(color: primary ? AppColors.darkGreen : AppColors.darkGrey5),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 2),
        minimumSize: Size.zero,
        tapTargetSize: MaterialTapTargetSize.shrinkWrap,
      ),
      child: Text(label, style: const TextStyle(fontSize: 12)),
    );
  }

  /// 「已经收下的」小表表头（四列：案例 / 票·日期 / 类型 / 它支持或反对哪条规则）。
  Widget _buildAcceptedHeader() {
    return Padding(
      padding: const EdgeInsets.only(left: 10, right: 10, bottom: 4),
      child: Row(children: [
        const SizedBox(
            width: 260,
            child: Text('案例', style: TextStyle(fontSize: 10.5, color: AppColors.darkGrey5))),
        const SizedBox(
            width: 150,
            child: Text('票 / 日期', style: TextStyle(fontSize: 10.5, color: AppColors.darkGrey5))),
        const SizedBox(
            width: 100,
            child: Text('类型', style: TextStyle(fontSize: 10.5, color: AppColors.darkGrey5))),
        const Expanded(
            child: Text('它支持或反对哪条规则',
                style: TextStyle(fontSize: 10.5, color: AppColors.darkGrey5))),
      ]),
    );
  }

  /// 一条已收下的案例（四列：标题 / 票·日期 / 类型 / 对照规则）。
  Widget _buildAcceptedRow(Map<String, dynamic> c) {
    final title = '${c["title"] ?? ''}';
    final symbol = '${c["symbol"] ?? ''}';
    final name = '${c["name"] ?? ''}';
    final date = '${c["date"] ?? ''}';
    final outcome = '${c["outcome"] ?? ''}';
    final ruleRel = c['ruleRel'] as String?;
    final ruleText = '${c["ruleText"] ?? ''}';
    final relWord = ruleRel == 'SUPPORT' ? '支持' : '反对';
    final relText = (ruleRel == null || ruleText.isEmpty) ? '—' : '$relWord「$ruleText」';
    final outcomeColor = _candidateOutcomeColor(outcome);
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
          width: 260,
          child: Text(title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
        ),
        SizedBox(
          width: 150,
          child: Text(name.isNotEmpty ? '$name $symbol · $date' : '$symbol · $date',
              style: const TextStyle(fontSize: 12, color: AppColors.darkGrey2)),
        ),
        SizedBox(
          width: 100,
          child: Text(_candidateOutcomeLabel(outcome),
              style: TextStyle(fontSize: 11, color: outcomeColor)),
        ),
        Expanded(
          child: Text(relText,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
        ),
      ]),
    );
  }

  /// 候选结果 → 人话标签（买点：成功 / 失败；卖点：走早了 / 走对了——不出现枚举值）。
  String _candidateOutcomeLabel(String outcome) {
    switch (outcome) {
      case 'SUCCESS':
        return '买点 · 成功';
      case 'FAILED':
        return '买点 · 失败';
      case 'EARLY':
        return '卖点 · 走早了';
      case 'RIGHT':
        return '卖点 · 走对了';
      default:
        return outcome;
    }
  }

  /// 结果配色：成功 / 走对 = 绿，失败 = 红，走早 = 橙。
  Color _candidateOutcomeColor(String outcome) {
    switch (outcome) {
      case 'FAILED':
        return AppColors.darkRed;
      case 'EARLY':
        return AppColors.darkOrange;
      default:
        return AppColors.darkGreen;
    }
  }

  /// 收下一条候选（title 非空 = 「改一改」改名收下）。成功后刷新候选区 + 回执。
  Future<void> _acceptCaseCandidate(String id, {String? title}) async {
    try {
      await widget.api.acceptCaseCandidate(id, title: title);
      if (!mounted) return;
      _toast('收下了 —— 它成了你的一条案例');
      await _loadCaseCandidates();
    } catch (e) {
      if (!mounted) return;
      _toast('没放下：${extractApiErrorMessage(e)}');
    }
  }

  /// 不要一条候选（墓碑——下次刷新不复活；幂等）。
  Future<void> _dismissCaseCandidate(String id) async {
    try {
      await widget.api.dismissCaseCandidate(id);
      if (!mounted) return;
      _toast('好，这条不再出现');
      await _loadCaseCandidates();
    } catch (e) {
      if (!mounted) return;
      _toast('没放下：${extractApiErrorMessage(e)}');
    }
  }

  /// 「改一改」：收下前顺手改名（原型三按钮的中间那个）。清空后收下 = 保持原标题。
  Future<void> _openRenameCandidateDialog(String id, String currentTitle) async {
    final ctrl = TextEditingController(text: currentTitle);
    final result = await showDialog<String>(
      context: context,
      builder: (_) => AlertDialog(
        backgroundColor: AppColors.darkSurface2,
        title: const Text('改一改，再收下', style: TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
        content: TextField(
          controller: ctrl,
          autofocus: true,
          decoration: const InputDecoration(hintText: '起个你自己的名字（原来的是描述，你可以改成教训）'),
          style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('取消')),
          FilledButton(
            onPressed: () => Navigator.pop(context, ctrl.text.trim()),
            style: FilledButton.styleFrom(backgroundColor: AppColors.darkGreen),
            child: const Text('收下'),
          ),
        ],
      ),
    );
    // 不显式 dispose controller：pop 后对话框退场动画期间 TextField 仍会读它
    // （实测「A TextEditingController was used after being disposed」）；与 _markPsychology 同惯例。
    if (result != null && mounted) {
      await _acceptCaseCandidate(id, title: result.isEmpty ? null : result);
    }
  }

  Widget _buildCaseRow(Map<String, dynamic> c) {
    final id = '${c["id"]}';
    final name = '${c["name"] ?? ''}';
    final symbol = '${c["symbol"] ?? ''}';
    final buyDate = '${c["buyDate"] ?? ''}';
    final buyType = '${c["buyType"] ?? ''}';
    final isFailed = buyType == 'FAILED';
    final verify = (c['verify'] as Map<String, dynamic>?) ?? const {};
    final plus5 = verify['+5dReturnPct'];
    // P2-案例5：verify null 显示「—」而非 0.0%（后端 index 摘要 null 存 0.0 的历史占位）
    final plus5Text = (plus5 == null || (plus5 is num && plus5 == 0 && verify.isEmpty))
        ? '—'
        : '${(plus5 as num).toStringAsFixed(1)}%';
    final features = (c['features'] as Map<String, dynamic>?) ?? const {};
    final desc = '${c["description"] ?? ''}';
    return Container(
      margin: const EdgeInsets.only(bottom: 6),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      decoration: BoxDecoration(
        color: isFailed ? AppColors.darkRed.withValues(alpha: 0.06) : AppColors.darkSurface,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
            color: isFailed
                ? AppColors.darkRed.withValues(alpha: 0.5)
                : AppColors.darkBorder.withValues(alpha: 0.5)),
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
          child: Text(isFailed ? '失败' : (buyType.isNotEmpty ? buyType : '未知'),
              style: TextStyle(fontSize: 11,
                  color: isFailed ? AppColors.darkRed : AppColors.darkGreen)),
        ),
        SizedBox(
          width: 76,
          child: Text('+5d $plus5Text', style: const TextStyle(fontSize: 11, color: AppColors.darkGrey2)),
        ),
        Expanded(
          child: Text(
            desc.isNotEmpty ? desc : '回撤 ${features["drawdownFromHighPct"] ?? '—'}% · 量比 ${features["volumeShrinkRatio"] ?? '—'} · J ${features["kdjJ"] ?? '—'}',
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

  /// 共识判定卡（2026-08-30 核心价值）：案例库统计学习的「完美买点画像」逐维命中。
  Widget _buildConsensusCard(Map<String, dynamic> consensus) {    final hits = ((consensus['hits'] as List<dynamic>?) ?? const [])
        .cast<Map<String, dynamic>>();
    final hitCount = (consensus['hitCount'] as num?)?.toInt() ?? 0;
    final total = (consensus['total'] as num?)?.toInt() ?? 0;
    const labels = <String, String>{
      'drawdownFromHighPct': '回撤',
      'volumeShrinkRatio': '量比',
      'kdjJ': 'KDJ.J',
      'distToMa60Pct': '距60日线',
      'macdHist': 'MACD',
      'sidewaysDays': '盘整',
    };
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
            color: total > 0 && hitCount * 2 >= total
                ? AppColors.darkGreen.withValues(alpha: 0.6)
                : AppColors.darkBorder.withValues(alpha: 0.5)),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          const Text('共识命中（案例库完美买点画像）',
              style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
          const Spacer(),
          Text('$hitCount/$total 维',
              style: TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.bold,
                  color: total > 0 && hitCount * 2 >= total
                      ? AppColors.darkGreen
                      : AppColors.darkGrey2)),
        ]),
        const SizedBox(height: 6),
        Wrap(
          spacing: 6,
          runSpacing: 4,
          children: hits.map<Widget>((h) {
            final hit = h['hit'] == true;
            final feature = '${h["feature"] ?? ''}';
            final value = h['value'];
            final low = h['low'];
            final high = h['high'];
            String valueText = value == null ? '—'
                : (feature == 'volumeShrinkRatio' || feature == 'kdjJ' || feature == 'macdHist'
                    ? (value as num).toStringAsFixed(2)
                    : (value as num).toStringAsFixed(1));
            return Container(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
              decoration: BoxDecoration(
                color: hit ? AppColors.darkGreen.withValues(alpha: 0.12) : AppColors.darkSurface2,
                borderRadius: BorderRadius.circular(4),
                border: Border.all(
                    color: hit ? AppColors.darkGreen.withValues(alpha: 0.6) : AppColors.darkBorder),
              ),
              child: Text(
                '${hit ? '✓' : '✗'} ${labels[feature] ?? feature} $valueText'
                '（${(low as num).toStringAsFixed(1)}-${(high as num).toStringAsFixed(1)}）',
                style: TextStyle(
                    fontSize: 10, color: hit ? AppColors.darkGreen : AppColors.darkGrey4),
              ),
            );
          }).toList(),
        ),
      ]),
    );
  }

  /// 双轨判定卡（2026-08-31 方案第 1 层）：B1/B2 各自共识画像命中 + 类型判定。
  /// result.type 由后端判定（命中多的一轨）；b1/b2 为 {hits,total,similarity}。
  Widget _buildTrackCard(Map<String, dynamic> result) {
    final type = '${result["type"] ?? 'none'}';
    final b1 = result['b1'] as Map<String, dynamic>?;
    final b2 = result['b2'] as Map<String, dynamic>?;
    final failedSim = (result['failedSimilarity'] as num?)?.toDouble();
    Widget track(String label, Map<String, dynamic>? t, Color color) {
      if (t == null) {
        return Text('$label 画像：样本不足',
            style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5));
      }
      final hits = (t['hits'] as num?)?.toInt() ?? 0;
      final total = (t['total'] as num?)?.toInt() ?? 0;
      final sim = (t['similarity'] as num?)?.toDouble() ?? 0;
      return Text('$label 画像：命中 $hits/$total 维 · 最高相似 ${sim.toStringAsFixed(1)}%',
          style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: color));
    }

    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
            color: type == 'B1' || type == 'B2'
                ? AppColors.darkGreen.withValues(alpha: 0.6)
                : AppColors.darkBorder.withValues(alpha: 0.5)),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          const Text('双轨判定（B1/B2 各自画像）',
              style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
          const Spacer(),
          Text(
            type == 'B1' || type == 'B2' ? '判定：$type 型' : '判定：两不靠',
            style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.bold,
                color: type == 'B1' || type == 'B2' ? AppColors.darkGreen : AppColors.darkGrey2),
          ),
        ]),
        const SizedBox(height: 6),
        track('B1', b1, AppColors.darkGreen),
        const SizedBox(height: 3),
        track('B2', b2, AppColors.darkGreen),
        // 失败画像警示（2026-08-31 方案第 2 层：负样本参照系）
        if (failedSim != null) ...[
          const SizedBox(height: 6),
          Text(
            '⚠ 形态与历史失败案例相似 ${failedSim.toStringAsFixed(1)}%'
            '（${failedSim >= 70 ? '注意风险' : '参考'}）',
            style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w600,
                color: failedSim >= 70 ? AppColors.darkRed : AppColors.darkGrey4),
          ),
        ],
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
    var typedSymbol = ''; // P2-交易71：区分「打了代码没点下拉」与「完全没填」
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
                  child: _SymbolSearchField(
                    api: widget.api,
                    hint: '标的（代码/名称/拼音首字母）',
                    onSymbolSelected: (symbol, _) {
                      symbolCtrl.text = symbol;
                      if (error != null) setDlg(() => error = null);
                    },
                    onTextChanged: (text) {
                      typedSymbol = text;
                      if (error != null) setDlg(() => error = null);
                    },
                  ),
                ),
                const SizedBox(width: 8),
                SizedBox(
                  width: 150,
                  child: TextField(controller: dateCtrl,
                      decoration: _caseInput('日期 yyyy-MM-dd（可空）')),
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
                        Text('${mm["name"] ?? mm["symbol"]}（${mm["symbol"]}）',
                            style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
                        const SizedBox(width: 8),
                        Text('${mm["buyDate"] ?? ''} · ${mm["buyType"] ?? ''}',
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
                // 2026-08-31 双轨判定卡（核心价值）：B1/B2 各自画像命中 + 类型判定 + 失败警示
                const SizedBox(height: 10),
                _buildTrackCard(result!),
                // 2026-08-30 共识判定（核心价值）：案例库 ≥5 → 从案例统计学习「完美买点画像」
                // → 当前形态逐维命中（回撤/量比/KDJ/距60日线/MACD/盘整）
                if ((result!['consensus'] as Map<String, dynamic>?) != null) ...[
                  const SizedBox(height: 10),
                  _buildConsensusCard(result!['consensus'] as Map<String, dynamic>),
                ],
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
                      // P2-交易71：原先这里直接 return——点了「匹配」毫无反应；
                      // 文案还写着「输入任意 6 位代码」，与实际「必须点下拉候选」相矛盾。
                      if (symbol.isEmpty) {
                        setDlg(() => error = typedSymbol.trim().isEmpty
                            ? '请输入标的'
                            : '请从下拉列表里选择标的（只输入代码不算）');
                        return;
                      }
                      setDlg(() {
                        loading = true;
                        error = null;
                        result = null;
                      });
                      try {
                        final resp = await widget.api
                            .matchCases(symbol, date: dateCtrl.text.trim());
                        if (ctx.mounted) {
                          setDlg(() {
                            result = resp;
                            loading = false;
                          });
                        }
                      } catch (e) {
                        if (ctx.mounted) {
                          setDlg(() {
                            error = '匹配失败：${extractApiErrorMessage(e)}';
                            loading = false;
                          });
                        }
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

  /// 批量导入弹窗（2026-08-31）：粘贴完美案例笔记（B1/B2 格式）→ POST /cases/import → 结果列表。
  Future<void> _openCaseImportDialog() async {
    final ctrl = TextEditingController();
    List<Map<String, dynamic>>? results;
    String? error;
    var loading = false;
    await showDialog<void>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDlg) => AlertDialog(
          backgroundColor: AppColors.darkSurface2,
          title: const Text('批量导入完美案例', style: TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
          content: SizedBox(
            width: 560,
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('粘贴笔记（支持「名称【缩写】+日期行」或「名称[缩写_日期]」格式，自动识别名称/日期/买点）',
                  style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
              const SizedBox(height: 8),
              TextField(
                controller: ctrl,
                maxLines: 10,
                decoration: InputDecoration(
                  hintText: '## 华纳药厂【HNYC】\n- 2025-05-09\n## 昂立康[ALK_20250714]',
                  hintStyle: const TextStyle(fontSize: 11, color: AppColors.darkGrey4),
                  filled: true,
                  fillColor: AppColors.darkSurface,
                  border: OutlineInputBorder(borderRadius: BorderRadius.circular(6)),
                ),
              ),
              const SizedBox(height: 8),
              if (error != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: 6),
                  child: Text(error!, style: const TextStyle(fontSize: 11, color: AppColors.darkRed)),
                ),
              if (loading)
                const Center(child: Padding(
                  padding: EdgeInsets.all(8),
                  child: SizedBox(width: 16, height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.darkGreen)),
                ))
              else if (results != null)
                Flexible(
                  child: SizedBox(
                    height: 260,
                    child: ListView.builder(
                      shrinkWrap: true,
                      itemCount: results!.length,
                      itemBuilder: (ctx, i) {
                        final r = results![i];
                        final status = '${r["status"]}';
                        final ok = status == 'ok';
                        final skipped = status == 'skipped';
                        final icon = ok ? '✓' : (skipped ? '⏭' : '✗');
                        final color = ok ? AppColors.darkGreen : (skipped ? AppColors.darkGrey4 : AppColors.darkRed);
                        return Padding(
                          padding: const EdgeInsets.symmetric(vertical: 2),
                          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                            Text(icon, style: TextStyle(fontSize: 12, color: color)),
                            const SizedBox(width: 6),
                            Expanded(
                              child: Text(
                                '${r["name"]}${ok ? ' ${r["symbol"]} ${r["buyDate"]}' : ''}'
                                '${(r["error"] as String?) ?? ''}',
                                style: TextStyle(fontSize: 11, color: color, height: 1.4),
                              ),
                            ),
                          ]),
                        );
                      },
                    ),
                  ),
                ),
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
                      final text = ctrl.text.trim();
                      if (text.isEmpty) {
                        // 2026-10-01（P2-交易71 同族）：原先直接 return，点了「导入」毫无反应
                        setDlg(() => error = '先粘贴要导入的案例文本');
                        return;
                      }
                      setDlg(() {
                        loading = true;
                        error = null;
                        results = null;
                      });
                      try {
                        final resp = await widget.api.importCases(text);
                        if (!ctx.mounted) return;
                        setDlg(() {
                          results = resp.cast<Map<String, dynamic>>();
                          loading = false;
                        });
                        await _loadCases();
                      } catch (e) {
                        if (!ctx.mounted) return;
                        setDlg(() {
                          error = '导入失败：${extractApiErrorMessage(e)}';
                          loading = false;
                        });
                      }
                    },
              child: Text(loading ? '导入中…' : '导入',
                  style: const TextStyle(fontSize: 13, color: AppColors.darkGreen)),
            ),
          ],
        ),
      ),
    );
  }

  /// 标注弹窗：代码 + 日期 + 买点类型（含失败案例）+ 描述 → POST /trading/cases。
  Future<void> _openAnnotateCaseDialog() async {
    final symbolCtrl = TextEditingController();
    final dateCtrl = TextEditingController(text: '');
    final typeCtrl = TextEditingController(text: 'B1');
    final descCtrl = TextEditingController();
    var type = 'B1'; // 下拉选择（B1/B2/失败案例/其他）
    // 2026-10-01 P2-交易71：必填校验移进弹窗内（原先「标注」按钮无条件 pop，再由外层校验——
    // 用户只打了代码没点下拉时弹窗已关闭、填好的日期与理由全丢，只剩一句「代码和日期必填」，
    // 体感等同「已提交」（2026-09-23 23:34 用户实测以为标注成功了，生产侧无任何 POST）。
    String? formError;
    var typedSymbol = '';
    final saved = await showDialog<bool>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDlg) => AlertDialog(
          backgroundColor: AppColors.darkSurface2,
          title: const Text('标注买点案例', style: TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
          content: SizedBox(
            width: 380,
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('系统自动拉「前 60 + 后 30 交易日」日 K，还原 K 线画面、计算特征画像和后验窗口。',
                  style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
              const SizedBox(height: 12),
              _SymbolSearchField(
                api: widget.api,
                hint: '标的（代码/名称/拼音首字母，如 000831 / 中国稀土 / zgxt）',
                onSymbolSelected: (symbol, _) {
                  symbolCtrl.text = symbol;
                  if (formError != null) setDlg(() => formError = null);
                },
                onTextChanged: (text) {
                  typedSymbol = text;
                  // 用户重新输入即撤掉旧错误，不拿过期提示挡新动作
                  if (formError != null) setDlg(() => formError = null);
                },
              ),
              const SizedBox(height: 8),
              // P2-工程12④（2026-10-04）：改日期同样撤掉旧错误——与标的框一致，
              // 「不拿过期提示挡新动作」（原先只有标的框两个回调会清，日期框校验失败后
              // 改日期红字仍挂着，看着像「新输入也不对」）。
              // P3 修正（2026-10-04 前端审查）：但 `formError` 是跨字段单一变量，标的为空时
              // 它承载的是「请从下拉列表里选择标的」——敲日期不该把**仍成立**的错误抹掉，
              // 故只在标的已非空（残留错误只可能来自日期相关校验）时才清。
              // 彻底解需按字段分错（symbolError / dateError），属 P3 登记项。
              TextField(
                controller: dateCtrl,
                onChanged: (_) {
                  if (symbolCtrl.text.trim().isNotEmpty && formError != null) {
                    setDlg(() => formError = null);
                  }
                },
                decoration: _caseInput('买点日期（yyyy-MM-dd，如 2026-08-03）'),
              ),
              const SizedBox(height: 8),
              // 2026-08-31 双轨方案：类型下拉（B1/B2/失败案例/其他），失败案例负样本入库
              DropdownButtonFormField<String>(
                initialValue: type,
                decoration: _caseInput('案例类型'),
                dropdownColor: AppColors.darkSurface2,
                style: const TextStyle(fontSize: 12, color: AppColors.darkGrey1),
                items: const [
                  DropdownMenuItem(value: 'B1', child: Text('B1（回调缩量低吸）', style: TextStyle(fontSize: 12))),
                  DropdownMenuItem(value: 'B2', child: Text('B2（放量突破右侧）', style: TextStyle(fontSize: 12))),
                  DropdownMenuItem(value: 'FAILED', child: Text('失败案例（形态像买点但走坏）', style: TextStyle(fontSize: 12))),
                  DropdownMenuItem(value: '其他', child: Text('其他（B3/SB1/自定义）', style: TextStyle(fontSize: 12))),
                ],
                onChanged: (v) {
                  if (v == null) return;
                  setDlg(() {
                    type = v;
                    typeCtrl.text = v == '其他' ? '' : v;
                  });
                },
              ),
              const SizedBox(height: 8),
              TextField(controller: descCtrl,
                  decoration: _caseInput(type == 'FAILED'
                      ? '失败原因（可选，如：破位不收回 / 追高被套）'
                      : '为什么完美（可选，如：回踩 60 日线 + 地量）')),
              if (formError != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(formError!, style: const TextStyle(fontSize: 11, color: AppColors.darkRed)),
                ),
            ]),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消', style: TextStyle(fontSize: 13, color: AppColors.darkGrey5)),
            ),
            TextButton(
              onPressed: () {
                // 同步下拉值到 typeCtrl（非「其他」时 typeCtrl 已在 onChanged 设置）
                final symbol = symbolCtrl.text.trim();
                final date = dateCtrl.text.trim();
                // P2-交易71：校验不过就留在弹窗里报错——输入不丢、弹窗不关
                if (symbol.isEmpty) {
                  setDlg(() => formError = typedSymbol.trim().isEmpty
                      ? '代码和日期必填'
                      : '请从下拉列表里选择标的（只输入代码不算）');
                  return;
                }
                if (date.isEmpty) {
                  setDlg(() => formError = '买点日期必填（yyyy-MM-dd，如 2026-08-03）');
                  return;
                }
                Navigator.pop(ctx, true);
              },
              child: const Text('标注', style: TextStyle(fontSize: 13, color: AppColors.darkGreen)),
            ),
          ],
        ),
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
      final resp = await widget.api.annotateCase(
        symbol: symbol,
        buyDate: date,
        buyType: typeCtrl.text.trim(),
        description: descCtrl.text.trim(),
      );
      await _loadCases();
      if (mounted) _toast(typeCtrl.text.trim() == 'FAILED' ? '失败案例已入库' : '案例已标注，画面已还原');
      // 2026-08-30 建议 #4：共识偏离度校验——标注后若与库中完美买点画像偏离大，
      // 提示确认（防脏案例进库；不阻止——用户是权威）
      final check = (resp['consensusCheck'] as Map<String, dynamic>?) ?? const {};
      if (check.isNotEmpty && mounted) {
        final hitCount = (check['hitCount'] as num?)?.toInt() ?? 0;
        final total = (check['total'] as num?)?.toInt() ?? 1;
        if (hitCount * 2 < total) {
          await _showConsensusDeviationDialog(hitCount, total, check);
        }
      }
    } catch (e) {
      if (mounted) _toast('标注失败：${extractApiErrorMessage(e)}');
    }
  }

  /// 共识偏离提示（防脏案例进库——案例库 ≥5 后生效）。
  Future<void> _showConsensusDeviationDialog(int hitCount, int total, Map<String, dynamic> check) async {
    final misses = ((check['hits'] as List<dynamic>?) ?? const [])
        .cast<Map<String, dynamic>>()
        .where((h) => h['hit'] != true)
        .toList();
    const labels = <String, String>{
      'drawdownFromHighPct': '回撤', 'volumeShrinkRatio': '量比', 'kdjJ': 'KDJ.J',
      'distToMa60Pct': '距60日线', 'macdHist': 'MACD', 'sidewaysDays': '盘整',
    };
    final detail = misses.take(3).map((h) {
      final feature = '${h["feature"] ?? ''}';
      final value = h['value'];
      final low = h['low'];
      final high = h['high'];
      final valueText = value == null ? '—' : (value as num).toStringAsFixed(2);
      return '${labels[feature] ?? feature} $valueText（共识 ${(low as num).toStringAsFixed(1)}-${(high as num).toStringAsFixed(1)}）';
    }).join(' · ');
    await showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.darkSurface2,
        title: const Text('这个案例和你的完美买点画像有偏差',
            style: TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
        content: SizedBox(
          width: 420,
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('共识命中 $hitCount/$total 维（案例库 ≥5 后从你的历史完美买点统计）。偏离维度：',
                style: const TextStyle(fontSize: 12, color: AppColors.darkGrey3)),
            const SizedBox(height: 8),
            Text(detail.isEmpty ? '（各维接近共识）' : detail,
                style: const TextStyle(fontSize: 12, color: AppColors.darkGrey2, height: 1.5)),
            const SizedBox(height: 8),
            const Text('已入库。如果它确实是你认为的完美买点，保留即可；如果不是，可以删除。',
                style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
          ]),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('知道了', style: TextStyle(fontSize: 13, color: AppColors.darkGreen)),
          ),
        ],
      ),
    );
  }

  /// 详情弹窗：K 线图（三区）+ 特征 + 后验（GET /trading/cases/{id}?kline=true）。
  Future<void> _openCaseDetailDialog(Map<String, dynamic> c) async {
    showDialog<void>(
      context: context,
      builder: (ctx) => _CaseDetailDialog(api: widget.api, caseId: '${c["id"]}'),
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
      if (mounted) _toast('删除失败：${extractApiErrorMessage(e)}');
    }
  }


  /// 通用导入 Dialog：粘贴文本 或 选择文件（上传留存 + GBK 转码）→ 回调导入。
  /// 持仓快照导入（通达信「持仓股」导出，全量覆盖）——也是**建立/推进券商快照锚定**的入口
  /// （RFC 20260912：历史成交导入预检发现锚定缺失时，从这里「先导快照」把锚定日补上）。
  /// [snapshotDate] = 快照文件自身日期（从文件名解析），后端拿它当锚定日，优先于导入日。
  /// [basedOn]（2026-10-05，P2-交易84）= 用户在导入框里显式给出的**数据基准日**（可空）——
  /// 给了它就优先于「导入时刻」推断；为空则后端走既有归一化（回执标「无据」）。
  Future<void> _importPositionsSnapshot(String content, String? snapshotDate, String? basedOn) async {
    final parsed = parseTdxPositions(content);
    if (parsed.rows.isEmpty) {
      throw Exception('无法识别通达信持仓导出——请确认表头含「证券代码/股票余额/成本价」');
    }
    // 2026-09-13 负成本事故的正面修复：持仓导入是 replace=true **全量覆盖**——
    // 漏掉一行 = 那只持仓从持仓表里被静默删除（用户那次 3 只只进来 2 只，且毫不知情）。
    // 所以只要有一行没看懂，就**不覆盖**，并把每行的原因摆出来让人自己判断。
    // 这与交易账实一致性批确立的判据一致：状态不确定时 fail-closed，不猜。
    if (parsed.errors.isNotEmpty) {
      if (mounted) _showUnparsedRowsDialog(parsed);
      return;
    }
    final rows = parsed.rows.map((r) => r.toJson()).toList();
    // RFC 20261003 C4「持仓同理」（2026-10-03）：replace 是**全量覆盖**——先对账，把「文件 vs 系统」
    // 逐只差异摆出来（新增/移除/改数量），人看过再覆盖。**逐只相符时不打扰**；对账失败不挡路。
    try {
      final rec = await widget.api.reconcilePositions(rows);
      if (!mounted) return;
      final diffs = (rec['diffs'] as List?) ?? const [];
      if (diffs.isNotEmpty) {
        final ok = await showDialog<bool>(
          context: context,
          builder: (ctx) => AlertDialog(
            backgroundColor: AppColors.darkSurface,
            title: const Text('这次持仓覆盖会怎么改', style: TextStyle(fontSize: 15)),
            content: SizedBox(
              width: 520,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text('文件 ${rec["fileCount"]} 只 · 系统 ${rec["systemCount"]} 只',
                      style: const TextStyle(fontSize: 13)),
                  const SizedBox(height: 8),
                  for (final d in diffs)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 4),
                      child: Text(_qtyDiffLine(d),
                          style: const TextStyle(fontSize: 12.5, color: AppColors.darkRed)),
                    ),
                  const SizedBox(height: 8),
                  Text('${rec["note"]}', style: const TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
                ],
              ),
            ),
            actions: [
              TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('先不导')),
              FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('按文件覆盖')),
            ],
          ),
        );
        if (ok != true) return;
      }
    } catch (e) {
      // 同上（P1-7）：对账没拿到就如实说，不静默覆盖。
      if (!mounted) return;
      final ok = await _confirmWithoutReconcile(e, '持仓');
      if (ok != true) return;
    }
    final result = await widget.api.importPositions(
      rows,
      replace: true,
      snapshotDate: snapshotDate,
      // 2026-10-05（P2-交易84）：显式基准日（用户说清了才传；不传 = 既有行为）
      basedOn: basedOn,
      // 2026-09-13：券商「当日盈亏」列之和（含 0 股行）——账户卡的当日盈亏以券商口径为准。
      // null（文件没这列 / 有行取不到数）不传 → 后端保留账户旧值，绝不落零。
      todayPnl: parsed.todayPnl,
    );
    await _loadAll();
    if (mounted) {
      var msg = '持仓导入 ${result.imported} 只';
      if (result.missingStopLoss.isNotEmpty) {
        msg += ' · 未设止损 ${result.missingStopLoss.length} 只（${result.missingStopLoss.join('、')}）';
      }
      // 0 股残留行（已清空）如实告知：它不是错误，但用户有权知道「文件里有 4 行、进来 3 只」
      if (parsed.skipped.isNotEmpty) {
        msg += ' · 另有 ${parsed.skipped.length} 行已清空未计入';
      }
      // 2026-10-05（P2-交易84）：锚定日的**依据**如实带出（有据/无据）——「怎么定下来的」必须可见。
      // 后端新字段（旧后端没有 → null，不显示也不编造）。
      if (result.anchorNote != null) {
        msg += ' · ${result.anchorNote}';
      }
      _toast(msg);
    }
  }

  /// 对账没拿到时的确认（P1-7，2026-10-03 增量深审）：**不静默降级、不假装对账过**——
  /// 把原因摆出来，让用户自己决定要不要直接覆盖。
  Future<bool?> _confirmWithoutReconcile(Object e, String what) {
    return showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.darkSurface,
        title: const Text('这次没先对账', style: TextStyle(fontSize: 15)),
        content: Text('$what对账没拿到（$e）。\n仍要按文件直接覆盖吗？',
            style: const TextStyle(fontSize: 13, height: 1.5)),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('先不导')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('直接覆盖')),
        ],
      ),
    );
  }

  /// 「这份文件有 N 行我没看懂」——拒绝全量覆盖时把原因摆清楚（不猜、不静默）。
  void _showUnparsedRowsDialog(TdxParseResult parsed) {
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.darkSurface2,
        title: const Text('先不动你的持仓', style: TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
        content: SizedBox(
          width: 480,
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('这份文件里有 ${parsed.errors.length} 行我没看懂，'
                '导进去会按「以文件为准」覆盖持仓——那几只不在文件里的会被一起删掉，所以先停手了。',
                style: const TextStyle(fontSize: 12, color: AppColors.darkGrey3)),
            const SizedBox(height: 10),
            ...parsed.errors.map((e) => Padding(
                  padding: const EdgeInsets.only(bottom: 4),
                  child: Text('· $e', style: const TextStyle(fontSize: 12, color: AppColors.darkGrey2)),
                )),
            const SizedBox(height: 8),
            Text('看懂了的 ${parsed.rows.length} 行是：${parsed.rows.map((r) => r.symbol).join('、')}',
                style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
          ]),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('知道了')),
        ],
      ),
    );
    _toast('有 ${parsed.errors.length} 行没看懂，持仓未改动');
  }

  void _openPositionsImport() => _openImportDialog(
        '粘贴通达信持仓导出（或选择文件）：证券代码/股票余额/成本价 自动识别，全量覆盖，止损需导入后补设',
        _importPositionsSnapshot,
        // 2026-10-05（P2-交易84）：持仓快照建立**锚定日**——让用户能显式说清基准日，
        // 不再只能靠「导入时刻」推断（09:26 导出、09:28 导入会被退到上一交易日）。
        withBasisDate: true,
      );

  /// 资金股份查询导入（现金 + 精确成本）——同样建立锚定（cashImport，RFC 20260912）。
  ///
  /// RFC 20261003 C4（2026-10-03）：**先对账、再覆盖**——覆盖前把「券商现金 vs 系统推算」与差额摆出来，
  /// 人看过才动账（此前是静默覆盖：差额被抹掉、不留痕，于是只能反复导全量）。
  /// 对账失败**不挡路**（如实降级为直接导入，老后端没有 dryRun 时也走这条）。
  Future<void> _importCashSnapshot(String content, String? snapshotDate, String? basedOn) async {
    try {
      // 2026-10-05（P2-交易84）：对账口径与落盘锚定同判据——显式基准日优先（不再两套日期）
      final rec = await widget.api.reconcileCash(content, snapshotDate: snapshotDate, basedOn: basedOn);
      if (!mounted) return;
      final diff = (rec['diff'] as num?)?.toDouble() ?? 0;
      final same = diff.abs() < 0.005;
      final ledgerOnly = ((rec['ledgerOnlyCount'] as num?)?.toInt() ?? 0);
      // 差额为 0 且没有「只记账未动现金」的行 → **没有信息可看，不打扰**（直接按券商值覆盖）。
      // 只有在「有差额」或「有只记账行」时才让人确认——否则每天多一个无信息量的弹窗。
      if (!same || ledgerOnly > 0) {
        final ok = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          backgroundColor: AppColors.darkSurface,
          title: const Text('这笔资金导入会怎么改账', style: TextStyle(fontSize: 15)),
          content: SizedBox(
            width: 460,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text('券商现金 ${_fmtDailyMoney((rec["brokerCash"] as num?)?.toDouble())}'
                    '　系统推算 ${_fmtDailyMoney((rec["systemCash"] as num?)?.toDouble())}',
                    style: const TextStyle(fontSize: 13)),
                const SizedBox(height: 6),
                Text(
                    '差额 ${_fmtDailyMoney(diff)}${same ? '（一致，覆盖不改数字）' : '（覆盖后这个差就消失了）'}',
                    style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                        color: same ? AppColors.darkGrey4 : AppColors.darkRed)),
                if (((rec['ledgerOnlyCount'] as num?)?.toInt() ?? 0) > 0) ...[
                  const SizedBox(height: 6),
                  Text(
                      '另有 ${(rec["ledgerOnlyCount"] as num).toInt()} 笔只记账未动现金（合计 '
                      '${_fmtDailyMoney((rec["ledgerOnlyAmount"] as num?)?.toDouble())}）',
                      style: const TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
                ],
                const SizedBox(height: 10),
                Text('${rec["note"]}', style: const TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
              ],
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('先不导')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('按这个覆盖')),
          ],
        ),
      );
        if (ok != true) return;
      }
    } catch (e) {
      // ⚠️ P1（2026-10-03 增量深审）：原来是静默 `catch (_)` 直接按旧语义覆盖——用户以为「看过对账了」，
      // 实际是**没看就覆盖**。现在如实说「没拿到」并让人决定（不再假装对账过）。
      if (!mounted) return;
      final ok = await _confirmWithoutReconcile(e, '资金');
      if (ok != true) return;
    }
    final r = await widget.api.importCash(content, snapshotDate: snapshotDate, basedOn: basedOn);
    await _loadAll();
    if (mounted) {
      var msg = '资金已更新：现金 ¥${r.cash.toStringAsFixed(2)} · 成本更新 ${r.updatedCost} 只';
      // 2026-10-05（P2-交易84）：现金锚定日的**依据**如实带出（有据/无据）——与持仓侧同一口径。
      if (r.anchorNote != null) {
        msg += ' · ${r.anchorNote}';
      }
      // P2-交易83（2026-10-04）：有丢行明细 → 弹回执 + 逐条展开（「是哪只票的精确成本没更新」）；
      // 只有计数没有明细（旧后端只回 unparsedRows）→ 退回原来那句人话，不假装有明细。
      if (r.unparsed.isNotEmpty) {
        await _showImportReceipt(
          receipt: msg,
          unparsed: r.unparsed,
          unparsedCount: r.unparsedCount,
          header: (n) => '有 $n 行没能识别（这些票的精确成本这次没更新）',
        );
      } else {
        // P2-交易43（2026-09-14）：有明细行没认出来 → 该只精确成本本次没更新（丢数据必须可见）
        if (r.unparsedRows > 0) {
          msg += ' · 另有 ${r.unparsedRows} 行明细没认出来，这些持仓的精确成本本次没更新';
        }
        _toast(msg);
      }
    }
  }

  /// 打开导入抽屉（R-12；2026-10-07 全量对齐原型：右侧滑入，不跳页）。
  /// [hint] = 粘贴区的引导文案；[withBasisDate] = 显示「数据基准日」输入（快照类导入）。
  /// [onImport] 非空时：粘贴文本交回原调用链处理（与旧对话框行为逐字一致）。
  Future<void> _openImportDialog(String hint,
      Future<void> Function(String content, String? snapshotDate, String? basedOn) onImport,
      {bool withBasisDate = false}) {
    return _showImportDrawer(
        hint: hint, withBasisDate: withBasisDate, onPasteImport: onImport);
  }

  /// 统一导入抽屉（2026-10-07 原型 web-7「导入」）：右侧滑入 404px，不跳页；
  /// 一次交齐不计次序——文件交给后端逐份识别（选完自动先看计划 = dryRun 只报不动），
  /// 确认后一次入账；粘贴路径同框保留（各 Tab 进时交回原链，顶栏进时走统一 bundle 链）。
  Future<void> _showImportDrawer(
      {String hint = '',
      bool withBasisDate = false,
      Future<void> Function(String content, String? snapshotDate, String? basedOn)? onPasteImport}) {
    return showGeneralDialog<void>(
      context: context,
      barrierDismissible: true,
      barrierLabel: '导入',
      barrierColor: Colors.black45,
      transitionDuration: const Duration(milliseconds: 200),
      pageBuilder: (_, _, _) => Align(
        alignment: Alignment.centerRight,
        child: _ImportDrawer(
          api: widget.api,
          onImported: _loadAll,
          onToast: _toast,
          hint: hint,
          withBasisDate: withBasisDate,
          onPasteImport: onPasteImport,
        ),
      ),
      transitionBuilder: (_, anim, _, child) => SlideTransition(
        position: Tween<Offset>(begin: const Offset(1, 0), end: Offset.zero)
            .animate(CurvedAnimation(parent: anim, curve: Curves.easeOutCubic)),
        child: child,
      ),
    );
  }
}

/// m3（2026-10-07）：迷你走势画笔（54×14，原型持仓表「近 20 日」列）——
/// 只给形状不给刻度：按收盘 min/max 归一化画折线，上下各留 1px。
class _SparkPainter extends CustomPainter {
  const _SparkPainter({required this.closes, required this.color});

  final List<double> closes;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    if (closes.length < 2) return;
    var min = closes.first;
    var max = closes.first;
    for (final v in closes) {
      if (v < min) min = v;
      if (v > max) max = v;
    }
    final span = max - min;
    final paint = Paint()
      ..color = color
      ..strokeWidth = 1.3
      ..style = PaintingStyle.stroke
      ..strokeJoin = StrokeJoin.round;
    final path = Path();
    final step = (size.width - 3) / (closes.length - 1);
    for (var i = 0; i < closes.length; i++) {
      final x = 1.5 + i * step;
      final y = span == 0
          ? size.height / 2
          : 1 + (1 - (closes[i] - min) / span) * (size.height - 2);
      if (i == 0) {
        path.moveTo(x, y);
      } else {
        path.lineTo(x, y);
      }
    }
    canvas.drawPath(path, paint);
  }

  @override
  bool shouldRepaint(covariant _SparkPainter oldDelegate) =>
      oldDelegate.closes != closes || oldDelegate.color != color;
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
/// | 止损（可点「改」设/改本批止损，2026-09-04 按批次止损批）| 距止损% | 买点 | 角色 | 状态（初始底仓 / 持有中 / 已清仓-回合盈亏）。
/// reconcile 对账提示：note 含「≠」= 流水与持仓不一致 → 橙色警告行（以持仓快照为准）。
/// 批次加权平均成本（2026-09-16）：Σ(剩余×成本) ÷ Σ剩余。
double _lotWeightedAvgCost(List<LotItem> lots) {
  final total = lots.fold<int>(0, (a, l) => a + l.remaining);
  if (total <= 0) return 0;
  return lots.fold<double>(0, (a, l) => a + l.remaining * l.costPrice) / total;
}

class _LotsDialog extends StatefulWidget {
  final ApiService api;
  final String symbol;
  final String name;
  final List<LotItem> lots;
  final List<ReconcileLine> reconcile;
  /// 该标的累计手续费（买入/卖出/合计）；null = 旧后端/未取到（2026-09-16）
  final SymbolFee? fee;
  final String? error;
  /// m6：金额/数量打码状态（打开时继承页头 👁）。
  final bool revealed;

  const _LotsDialog({
    required this.api,
    required this.symbol,
    required this.name,
    required this.lots,
    required this.reconcile,
    this.fee,
    this.error,
    required this.revealed,
  });

  @override
  State<_LotsDialog> createState() => _LotsDialogState();
}

class _LotsDialogState extends State<_LotsDialog> {
  /// 本地可变副本（行内编辑止损后重拉刷新，不必关弹窗）。
  late List<LotItem> _lots = widget.lots;

  /// 止损价展示/回填：≤4 位小数去尾零（输入能力与后端校验一致）。
  static String _fmtStop(double v) {
    var s = v.toStringAsFixed(4);
    while (s.contains('.') && (s.endsWith('0') || s.endsWith('.'))) {
      s = s.substring(0, s.length - 1);
    }
    return s;
  }

  /// 编辑后重拉该股批次（止损位/距止损% 等随服务端覆盖即时生效）。
  Future<void> _reload() async {
    try {
      final resp = await widget.api.getLots(state: 'all', symbol: widget.symbol);
      if (!mounted) return;
      setState(() => _lots = resp.lots);
    } catch (e) {
      if (!mounted) return;
      final messenger = ScaffoldMessenger.of(context);
      messenger.showSnackBar(SnackBar(
        content: Text('刷新批次失败：${extractApiErrorMessage(e)}',
            style: const TextStyle(fontSize: 13)),
        backgroundColor: AppColors.darkSurface2,
      ));
    }
  }

  /// 设/改本批止损（PUT）或清空回退（DELETE）。空输入 = 清除覆盖。
  Future<void> _editStopLoss(LotItem lot) async {
    final controller = TextEditingController(
        text: lot.stopLossPrice != null && lot.stopLossPrice! > 0
            ? _fmtStop(lot.stopLossPrice!)
            : '');
    final action = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.darkSurface,
        title: Text('设置 ${lot.buyDate} 批次止损',
            style: const TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
        content: SizedBox(
          width: 320,
          child: TextField(
            controller: controller,
            autofocus: true,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            style: const TextStyle(fontSize: 13, color: AppColors.darkGrey1),
            decoration: const InputDecoration(
              labelText: '止损价（空 = 清除，回退流水/默认 −7%）',
              hintText: '如 8.50',
            ),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, 'clear'),
            child: const Text('清除止损', style: TextStyle(fontSize: 13, color: AppColors.darkGrey4)),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, 'cancel'),
            child: const Text('取消', style: TextStyle(fontSize: 13, color: AppColors.darkGrey4)),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, 'save'),
            style: FilledButton.styleFrom(backgroundColor: AppColors.darkRed),
            child: const Text('保存', style: TextStyle(fontSize: 13)),
          ),
        ],
      ),
    );
    if (!mounted || action == null || action == 'cancel') return;
    final messenger = ScaffoldMessenger.of(context);
    try {
      final raw = controller.text.trim();
      if (action == 'clear' || raw.isEmpty) {
        await widget.api.clearLotStopLoss(lot.lotId);
        messenger.showSnackBar(const SnackBar(
            content: Text('已清除该批止损（回退流水/默认 −7%）',
                style: TextStyle(fontSize: 13)),
            backgroundColor: AppColors.darkSurface2));
      } else {
        final v = double.tryParse(raw);
        if (v == null || v <= 0) {
          messenger.showSnackBar(const SnackBar(
              content: Text('止损价需为正数', style: TextStyle(fontSize: 13)),
              backgroundColor: AppColors.darkSurface2));
          return;
        }
        await widget.api.updateLotStopLoss(lot.lotId, v);
        messenger.showSnackBar(SnackBar(
            content: Text('该批止损已设为 ${_fmtStop(v)}',
                style: const TextStyle(fontSize: 13)),
            backgroundColor: AppColors.darkSurface2));
      }
      await _reload();
    } catch (e) {
      if (!mounted) return;
      messenger.showSnackBar(SnackBar(
          content: Text('设置止损失败：${extractApiErrorMessage(e)}',
              style: const TextStyle(fontSize: 13)),
          backgroundColor: AppColors.darkSurface2));
    }
  }

  /// 盈亏%：持有中/初始底仓用后端浮动 pnlPct；已清仓回合 = realizedPnl / (成本×买入量)（后端无回合百分比字段，前端算）。
  String _lotPnlPctText(LotItem l) {
    // 负/零成本 → 后端给 null（百分比语义翻转），显示「—」而不是 0.00%
    if (!l.closed) return l.pnlPct == null ? '—' : '${l.pnlPct!.toStringAsFixed(2)}%';
    final cost = l.costPrice * l.volume;
    if (cost <= 0) return '—';
    return '回合 ${(l.realizedPnl / cost * 100).toStringAsFixed(2)}%';
  }

  @override
  Widget build(BuildContext context) {
    // 防御：后端已按 symbol 过滤，前端再按 symbol 双保险（旧后端可能忽略参数返回全部）
    // 2026-09-16 用户拍板：只列**还持有着的**批次——已清仓回合不在这里出现
    //（7 月买过又清掉的那批不再显示；了结回合的完整档案在「清仓」Tab）
    final visible = _lots.where((l) => l.symbol == widget.symbol && l.remaining > 0).toList();
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
                Text('批次明细 · ${widget.symbol}${widget.name.isEmpty ? '' : ' ${widget.name}'}',
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
                      if (widget.error != null)
                        Text('批次明细加载失败：${widget.error}',
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
                                // m6：剩余/买入数量打码（现价/止损保留）
                                DataCell(Text(
                                    '${maskIf(_fmtThousandsInt(l.remaining), widget.revealed)} / ${maskIf(_fmtThousandsInt(l.volume), widget.revealed)}',
                                    style: const TextStyle(fontSize: 12, color: AppColors.darkGrey3))),
                                DataCell(Column(
                                    mainAxisAlignment: MainAxisAlignment.center,
                                    crossAxisAlignment: CrossAxisAlignment.start,
                                    children: [
                                  Text(maskIf(l.costPrice.toStringAsFixed(3), widget.revealed),
                                      style: const TextStyle(fontSize: 12, color: AppColors.darkGrey3)),
                                  if (l.buyFee > 0)
                                    Text('含手续费 ${l.buyFee.toStringAsFixed(2)}',
                                        style: const TextStyle(fontSize: 10, color: AppColors.darkGrey5)),
                                ])),
                                DataCell(Text(l.currentPrice.toStringAsFixed(3),
                                    style: const TextStyle(fontSize: 12, color: AppColors.darkGrey1))),
                                DataCell(Text(
                                    l.closed
                                        ? '回合 ${maskIf(_fmtThousands(l.realizedPnl), widget.revealed)}'
                                        : maskIf(_fmtThousands(l.pnl), widget.revealed),
                                    style: TextStyle(fontSize: 12, color: pnlColor, fontWeight: FontWeight.w600))),
                                DataCell(Text(_lotPnlPctText(l),
                                    style: TextStyle(fontSize: 12, color: pnlColor))),
                                DataCell(Row(mainAxisSize: MainAxisSize.min, children: [
                                  Text(stop != null && stop > 0 ? stop.toStringAsFixed(3) : '—',
                                      style: const TextStyle(fontSize: 12, color: AppColors.darkGrey3)),
                                  // 2026-09-04 按批次止损批：持有批次可点「改」设/改本批止损（已清仓回合止损无意义不给编辑）
                                  if (!l.closed) ...[
                                    const SizedBox(width: 6),
                                    InkWell(
                                      onTap: () => _editStopLoss(l),
                                      borderRadius: BorderRadius.circular(4),
                                      child: const Tooltip(
                                        message: '设/改本批止损',
                                        child: Icon(Icons.edit_outlined,
                                            size: 13, color: AppColors.darkGrey5),
                                      ),
                                    ),
                                  ],
                                ])),
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
                      // 2026-09-16：合计 + 该票累计手续费（卖出含印花税万 5，通常远大于买入）
                      if (visible.isNotEmpty) ...[
                        const SizedBox(height: 10),
                        // m6：合计行数量/成本/浮动打码（手续费行不打——非规模信息）
                        Text(
                          '合计 ${maskIf('${visible.fold<int>(0, (a, l) => a + l.remaining)}', widget.revealed)} 股 · '
                          '加权成本 ${maskIf(_lotWeightedAvgCost(visible).toStringAsFixed(3), widget.revealed)} · '
                          '浮动 ${maskIf(_fmtThousands(visible.fold<double>(0, (a, l) => a + l.pnl)), widget.revealed)}',
                          style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600,
                              color: AppColors.darkGrey2),
                        ),
                        if (widget.fee != null && widget.fee!.total > 0) ...[
                          const SizedBox(height: 3),
                          Text(
                            '这只票累计手续费  买入 ${widget.fee!.buy.toStringAsFixed(2)} · '
                            '卖出 ${widget.fee!.sell.toStringAsFixed(2)} · 合计 ${widget.fee!.total.toStringAsFixed(2)}',
                            style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5),
                          ),
                        ],
                      ],
                      if (widget.reconcile.any((r) => r.symbol == widget.symbol)) ...[
                        const SizedBox(height: 10),
                        const Text('对账提示（流水净增减 vs 当前持仓，以持仓快照为准）：',
                            style: TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
                        const SizedBox(height: 2),
                        for (final r in widget.reconcile.where((r) => r.symbol == widget.symbol))
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
/// 标的搜索输入（2026-08-30 验收反馈：记不住 6 位代码只记得名字——
/// 支持 代码/中文名/拼音首字母，通达信式候选下拉；防抖 300ms）。
class _SymbolSearchField extends StatefulWidget {
  const _SymbolSearchField({
    required this.api,
    required this.onSymbolSelected,
    this.onTextChanged,
    this.hint = '标的代码（如 000725）',
  });

  final ApiService api;
  final void Function(String symbol, String name) onSymbolSelected;

  /// 输入框文本变化（2026-10-01 P2-交易71）。
  /// 外层据此区分「打了代码但没点下拉」与「什么都没填」——前者才是用户真正踩的坑
  /// （只输入代码不会让外层拿到 symbol），提示要能照做，不能笼统说「代码和日期必填」。
  final void Function(String text)? onTextChanged;
  final String hint;

  @override
  State<_SymbolSearchField> createState() => _SymbolSearchFieldState();
}

class _SymbolSearchFieldState extends State<_SymbolSearchField> {
  final TextEditingController _ctrl = TextEditingController();
  List<Map<String, dynamic>> _candidates = const [];
  Timer? _debounce;
  bool _loading = false;
  /// 代际令牌（2026-08-30 审查 P2）：防快速输入时旧请求后返回覆盖新候选。
  int _seq = 0;

  @override
  void dispose() {
    _debounce?.cancel();
    _ctrl.dispose();
    super.dispose();
  }

  void _onChanged(String text) {
    widget.onTextChanged?.call(text); // P2-交易71：把原始输入告知外层校验
    _debounce?.cancel();
    _seq++;
    final mySeq = _seq;
    final q = text.trim();
    if (q.length < 2) {
      if (_candidates.isNotEmpty) setState(() => _candidates = const []);
      return;
    }
    _debounce = Timer(const Duration(milliseconds: 300), () async {
      final r = await widget.api.searchSymbols(q);
      if (!mounted || mySeq != _seq) return; // 竞态：新输入已发起 → 丢弃旧响应
      setState(() {
        _candidates = r;
        _loading = false;
      });
    });
    if (!_loading) setState(() => _loading = true);
  }

  void _select(Map<String, dynamic> c) {
    final symbol = '${c["symbol"]}';
    final name = '${c["name"] ?? ''}';
    _ctrl.text = symbol;
    _ctrl.selection = TextSelection.collapsed(offset: symbol.length);
    setState(() => _candidates = const []);
    widget.onTextChanged?.call(symbol); // P2-交易71：选中即视为已修正输入
    widget.onSymbolSelected(symbol, name);
  }

  @override
  Widget build(BuildContext context) {
    return Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
      TextField(
        controller: _ctrl,
        onChanged: _onChanged,
        decoration: InputDecoration(
          hintText: widget.hint,
          hintStyle: const TextStyle(fontSize: 12, color: AppColors.darkGrey4),
          suffixIcon: _loading
              ? const Padding(
                  padding: EdgeInsets.all(10),
                  child: SizedBox(
                    width: 12,
                    height: 12,
                    child: CircularProgressIndicator(strokeWidth: 1.5, color: AppColors.darkGreen),
                  ),
                )
              : null,
          isDense: true,
          contentPadding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
          enabledBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(6), borderSide: const BorderSide(color: AppColors.darkBorder)),
          focusedBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(6), borderSide: const BorderSide(color: AppColors.darkGreen)),
        ),
      ),
      if (_candidates.isNotEmpty)
        Container(
          margin: const EdgeInsets.only(top: 2),
          constraints: const BoxConstraints(maxHeight: 180),
          decoration: BoxDecoration(
            color: AppColors.darkSurface2,
            borderRadius: BorderRadius.circular(6),
            border: Border.all(color: AppColors.darkBorder),
          ),
          child: ListView.builder(
            shrinkWrap: true,
            itemCount: _candidates.length > 6 ? 6 : _candidates.length,
            itemBuilder: (ctx, i) {
              final c = _candidates[i];
              return InkWell(
                onTap: () => _select(c),
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
                  child: Row(children: [
                    Text('${c["symbol"]}',
                        style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
                    const SizedBox(width: 8),
                    Text('${c["name"] ?? ''}',
                        style: const TextStyle(fontSize: 12, color: AppColors.darkGrey3)),
                  ]),
                ),
              );
            },
          ),
        ),
    ]);
  }
}

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
  Map<String, dynamic>? _indicators;
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
      final detail =
          await widget.api.getCaseDetail(widget.caseId, kline: true, indicators: true);
      if (!mounted) return;
      setState(() {
        _record = (detail['caseRecord'] as Map<String, dynamic>?) ?? detail;
        _kline = ((detail['kline'] as List<dynamic>?) ?? const []).cast<Map<String, dynamic>>();
        _indicators = detail['indicators'] as Map<String, dynamic>?;
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
    final buyDate = '${record["buyDate"] ?? ''}';
    final insightSummary = '${insight["summary"] ?? ''}';
    final hasInsight = insightSummary.isNotEmpty;
    return AlertDialog(
      backgroundColor: AppColors.darkSurface2,
      title: Text('${record["name"] ?? record["symbol"]}（${record["symbol"]}）· ${record["buyType"] ?? ''} · $buyDate',
          style: const TextStyle(fontSize: 15, color: AppColors.darkGrey1)),
      content: SizedBox(
        width: 620,
        child: SingleChildScrollView(
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            CaseKlineChart(kline: _kline, buyDate: buyDate, indicators: _indicators),
            const SizedBox(height: 10),
            if ('${record["description"] ?? ''}'.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Text('「${record["description"]}」',
                    style: const TextStyle(fontSize: 12, color: AppColors.darkGrey2)),
              ),
            Wrap(
              spacing: 8,
              runSpacing: 6,
              children: [
                _chip('回撤 ${fmt(features["drawdownFromHighPct"], suffix: '%')}'),
                _chip('量比 ${fmt(features["volumeShrinkRatio"])}'),
                _chip('KDJ.J ${fmt(features["kdjJ"])}'),
                _chip('距60日线 ${fmt(features["distToMa60Pct"], suffix: '%')}'),
                _chip('黄白线 ${features["yellowLineState"] ?? '—'}'),
                _chip('盘整 ${fmt(features["sidewaysDays"], suffix: '天')}'),
                _chip('破前高 ${features["breakoutFromHigh"] == true ? '是' : '否'}'),
              ],
            ),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 6,
              children: [
                _chip('+5d ${fmt(verify["+5dReturnPct"], suffix: '%')}', highlight: true),
                _chip('+10d ${fmt(verify["+10dReturnPct"], suffix: '%')}', highlight: true),
                _chip('最大回撤 ${fmt(verify["maxDrawdownAfterBuyPct"], suffix: '%')}', highlight: true),
                _chip('破止损 ${verify["stopLossHit"] == true ? '是' : '否'}', highlight: true),
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
                    Text('置信度 ${fmt(insight["confidence"])}',
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
    // 骨架重排（2026-10-08）：Tab 9 → 6 后「账」（含历史成交）落到 index 1
    if (_controller?.index == 1) widget.onHistorySelected();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}

/// 历史成交 Tab 内容：日期范围查询 + 按日分组全字段流水列表 + 导入历史成交入口。
/// 2026-08-23：从页头 Dialog 升级为常驻第 5 Tab（RFC 20260823-trading-history-tab-backfill）；
/// 进 Tab 自动加载 + 手动刷新，不做定时轮询（保活页陈旧问题，切页刷新兜底）。
class _HistorySection extends StatefulWidget {
  final ApiService api;
  /// m6：金额/数量打码状态（父页 👁 统一切换）——浏览类明细默认掩码。
  final bool revealed;
  /// RFC 20260912：锚定缺失时的「先导快照」出路——关掉导入弹窗并打开持仓快照导入
  /// （「持仓股」导出即可建立锚定日）。可空：不传则只给引导文案。
  final VoidCallback? onImportSnapshot;
  /// RFC 20260912：确认落盘后回调（父页重算账实对账闸门——这次导入可能新增缺口）。
  final VoidCallback? onImported;

  const _HistorySection(
      {super.key, required this.api, required this.revealed, this.onImportSnapshot, this.onImported});

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
        onImportSnapshot: widget.onImportSnapshot,
        onImported: (result) {
          if (!mounted) return;
          setState(() => _importResult = result);
          _load();
          // RFC 20260912：这次导入可能新增账实缺口 → 让父页重算对账闸门横幅
          widget.onImported?.call();
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
                        child: LayoutBuilder(
                          builder: (ctx, cons) {
                            // 2026-09-07 用户反馈：宽屏下列宽固定（合计 742px）不撑开，
                            // 发生金额/成交编号截断——按可用宽度分配列宽，够宽时自然消失横向滚动
                            // 行内水平 padding 10×2：表宽需预留，否则 cells 和 = 视口宽导致每行 RenderFlex 溢出
                            const rowPad = 20.0;
                            final widths = _histWidths(cons.maxWidth - rowPad);
                            double total = rowPad;
                            for (final w in widths) {
                              total += w;
                            }
                            return SingleChildScrollView(
                              scrollDirection: Axis.horizontal,
                              child: SizedBox(
                                width: total,
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    _buildListHeader(widths),
                                    ..._grouped().entries
                                        .map((e) => _buildDateGroup(e.key, e.value, widths)),
                                  ],
                                ),
                              ),
                            );
                          },
                        ),
                      ),
      ),
    ]);
  }

  /// 历史成交列定义：(label, minWidth, grow 弹性权重, right 对齐)。
  /// 2026-09-07 用户反馈：固定列宽合计 742px，宽屏不撑开、发生金额/成交编号截断——
  /// 可用宽 > ΣminWidth 时按 grow 分配余量（长内容列权重大），不足时回落 minWidth 横向滚动。
  static const List<(String, double, double, bool)> _histCols = [
    ('方向', 44, 0, false),
    ('时间', 48, 0, false),
    ('代码', 72, 0.8, false),
    ('名称', 88, 2.5, false),
    ('数量', 60, 0, true),
    ('价格', 70, 1.2, true),
    ('成交金额', 90, 2, true),
    ('发生金额', 100, 3, true),
    ('成交编号', 110, 4.5, false),
    ('费用', 60, 1, true),
  ];

  /// 按可用宽度计算每列实际宽度。
  static List<double> _histWidths(double available) {
    double minTotal = 0;
    double growTotal = 0;
    for (final c in _histCols) {
      minTotal += c.$2;
      growTotal += c.$3;
    }
    final extra = available > minTotal ? available - minTotal : 0.0;
    return [
      for (final c in _histCols)
        c.$2 + (growTotal > 0 ? extra * c.$3 / growTotal : 0),
    ];
  }

  Widget _buildListHeader(List<double> widths) {
    Widget cell(String label, double width, {bool right = false}) => SizedBox(
          width: width,
          child: Text(label,
              textAlign: right ? TextAlign.right : TextAlign.left,
              style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
        );
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(children: [
        for (var i = 0; i < _histCols.length; i++)
          cell(_histCols[i].$1, widths[i], right: _histCols[i].$4),
      ]),
    );
  }

  Widget _buildDateGroup(String date, List<TradeRecordItem> trades, List<double> widths) {
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
            children: trades.map((t) => _buildTradeRow(t, widths)).toList(),
          ),
        ),
      ],
    );
  }

  /// 发生金额（源文件原生）：买入为负（扣款），卖出为正（到账）。
  /// 系统存储 fee = |发生金额 − 成交金额|，据此反推；fee 缺失（手动记录/旧数据）→ '—'。
  /// 股息类事件（P2-批次6）：amount 即发生金额绝对值，入账为正（现金流入）/ 税为负。
  /// m6：金额打码（原 static → 实例方法，取 widget.revealed；'—' 不受影响）。
  String _occurredAmount(TradeRecordItem t) {
    if (t.isDividendEvent) {
      return maskIf(_thousands(t.isBuy ? t.amount : -t.amount), widget.revealed);
    }
    if (t.fee == null) return '—';
    final occurred = t.isBuy ? -(t.amount + t.fee!) : (t.amount - t.fee!);
    return maskIf(_thousands(occurred), widget.revealed);
  }

  Widget _buildTradeRow(TradeRecordItem t, List<double> widths) {
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
          cell(t.dividendLabel, widths[0], color: dirColor),
          cell('—', widths[1], color: AppColors.darkGrey5),
          cell(t.symbol, widths[2], color: AppColors.darkGrey1),
          cell(t.name, widths[3]),
          cell('—', widths[4], right: true),
          cell('—', widths[5], right: true),
          cell('—', widths[6], right: true),
          cell(_occurredAmount(t), widths[7], right: true),
          cell('—', widths[8], color: AppColors.darkGrey5),
          cell('—', widths[9], right: true),
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
        cell(t.isBuy ? '买入' : '卖出', widths[0], color: dirColor),
        cell(timeStr, widths[1], color: AppColors.darkGrey5),
        cell(t.symbol, widths[2], color: AppColors.darkGrey1),
        cell(t.name, widths[3]),
        // m6：数量/金额打码（价格/编号/费用不打——费用非规模信息，原型无此列）
        cell(maskIf('${t.volume}', widget.revealed), widths[4], right: true),
        cell(t.price.toStringAsFixed(3), widths[5], right: true),
        cell(maskIf(_thousands(t.amount), widget.revealed), widths[6], right: true), // 成交金额（源文件）
        cell(_occurredAmount(t), widths[7], right: true), // 发生金额（源文件原生，推导自 fee；已含 m6 掩码）
        cell(t.orderId ?? '—', widths[8], color: AppColors.darkGrey5),
        cell(t.fee != null ? t.fee!.toStringAsFixed(2) : '—', widths[9], right: true), // 系统计算放最后
      ]),
    );
  }
}

// ─────────────────────────── 分析（R-05：三粒度 · 2026-10-06） ───────────────────────────

/// 三粒度分析（全局 / 单标的 / 单笔）——只陈述、不评价、不建议：
/// 描述块每个数字带 trace（哪几笔 / 哪几天）；对照块没规则就明说「判不了守没守」；
/// 缺数据 value=null → 显示「—」（**绝不渲染成 0**）。总结三段：事实 / 对照 / 留给你的问题。
class _AnalysisSection extends StatefulWidget {
  final ApiService api;

  /// m6：金额打码状态（父页 👁 统一切换）——列表项里的盈亏金额默认掩码。
  final bool revealed;

  /// m4：父页（左侧导航子项 这一笔/这只票/这一段）请求的粒度；null = 不受控（保持内部状态）。
  final String? requestedScope;

  /// m4：粒度变化的回调——让导航子项高亮跟随（与内部 chips 双向同步）。
  final ValueChanged<String>? onScopeChanged;

  const _AnalysisSection(
      {required this.api, required this.revealed, this.requestedScope, this.onScopeChanged});

  @override
  State<_AnalysisSection> createState() => _AnalysisSectionState();
}

class _AnalysisSectionState extends State<_AnalysisSection> {
  String _scope = 'global';
  final _symbolCtl = TextEditingController();
  final _roundCtl = TextEditingController();
  TradingAnalysisDto? _data;
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    // m4：粒度提父页后，切区重建时按父页记住的粒度恢复——与上次看到的一致
    _scope = widget.requestedScope ?? 'global';
    _load(); // 打开先看全局——零操作出内容
  }

  /// m4：导航子项改了粒度 → 这里跟着切（父子双向同步的「父→子」边）。
  /// 直接在 didUpdateWidget 改字段（本 widget 马上随之重建，无需 setState）；
  /// 不回调 onScopeChanged（父页自己就是发起方，幂等判断也能挡住重入）。
  @override
  void didUpdateWidget(covariant _AnalysisSection oldWidget) {
    super.didUpdateWidget(oldWidget);
    final req = widget.requestedScope;
    if (req != null && req != oldWidget.requestedScope && req != _scope) {
      _scope = req;
      _error = null;
      _data = null; // 换粒度旧结果不再挂着（避免看错对象）
      if (req == 'global') scheduleMicrotask(_load); // 本次 build 后再发请求
    }
  }

  @override
  void dispose() {
    _symbolCtl.dispose();
    _roundCtl.dispose();
    super.dispose();
  }

  void _selectScope(String s) {
    if (_scope == s) return;
    setState(() {
      _scope = s;
      _error = null;
      _data = null; // 换粒度旧结果不再挂着（避免看错对象）
    });
    widget.onScopeChanged?.call(s); // m4：内部 chips 点了 → 父页导航子项高亮跟上
    if (s == 'global') _load(); // 全局无需输入，切换即出
  }

  /// 取分析：空输入不发请求（先把要什么说清，不猜、不拿后端 400 当提示）。
  Future<void> _load() async {
    final symbol = _symbolCtl.text.trim();
    final roundId = _roundCtl.text.trim();
    if (_scope == 'symbol' && symbol.isEmpty) {
      setState(() => _error = '先填股票代码（如 600206），我再看这只票做了几笔');
      return;
    }
    if (_scope == 'round' && roundId.isEmpty) {
      setState(() => _error = '先填那一笔的编号（如 600206_2026-08-05，历史成交里能看到）');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final d = await widget.api.fetchTradingAnalysis(_scope,
          symbol: _scope == 'symbol' ? symbol : null,
          roundId: _scope == 'round' ? roundId : null);
      if (!mounted) return;
      setState(() {
        _data = d;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = extractApiErrorMessage(e);
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      // 粒度选择 + 目标输入 + 按钮（窄窗自动换行）
      Wrap(crossAxisAlignment: WrapCrossAlignment.center, spacing: 8, runSpacing: 6, children: [
        _scopeChip('global', '全局'),
        _scopeChip('symbol', '单标的'),
        _scopeChip('round', '单笔'),
        if (_scope == 'symbol')
          SizedBox(
            width: 130,
            child: TextField(
              key: const Key('analysisSymbol'),
              controller: _symbolCtl,
              style: const TextStyle(fontSize: 12, color: AppColors.darkGrey1),
              decoration: const InputDecoration(
                isDense: true,
                hintText: '600206',
                hintStyle: TextStyle(fontSize: 11, color: AppColors.darkGrey5),
              ),
              onSubmitted: (_) => _load(),
            ),
          ),
        if (_scope == 'round')
          SizedBox(
            width: 200,
            child: TextField(
              key: const Key('analysisRound'),
              controller: _roundCtl,
              style: const TextStyle(fontSize: 12, color: AppColors.darkGrey1),
              decoration: const InputDecoration(
                isDense: true,
                hintText: '600206_2026-08-05',
                hintStyle: TextStyle(fontSize: 11, color: AppColors.darkGrey5),
              ),
              onSubmitted: (_) => _load(),
            ),
          ),
        OutlinedButton(
          onPressed: _loading ? null : _load,
          style: OutlinedButton.styleFrom(
              foregroundColor: AppColors.darkGrey1,
              side: const BorderSide(color: AppColors.darkGrey4),
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4)),
          child: const Text('看分析', style: TextStyle(fontSize: 12)),
        ),
      ]),
      const SizedBox(height: 4),
      const Text('每个数字都能回到它来自哪几笔 / 哪几天；没数据就如实说「—」，不替你编一个 0',
          style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
      const SizedBox(height: 10),
      if (_loading)
        const Padding(
          padding: EdgeInsets.symmetric(vertical: 24),
          child: Center(
            child: SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.darkGreen)),
          ),
        )
      else if (_error != null)
        Container(
          width: double.infinity,
          padding: const EdgeInsets.all(10),
          decoration: BoxDecoration(
            color: AppColors.darkOrange.withValues(alpha: 0.12),
            borderRadius: BorderRadius.circular(8),
            border: Border.all(color: AppColors.darkOrange.withValues(alpha: 0.6)),
          ),
          child: Text(_error!, style: const TextStyle(fontSize: 12, color: AppColors.darkOrange, height: 1.4)),
        )
      else if (_data != null) ...[
        Text(_data!.label,
            style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
        const SizedBox(height: 8),
        if (_data!.description.isNotEmpty) _descriptionCard(_data!),
        if (_hasContrast(_data!)) ...[
          const SizedBox(height: 8),
          _contrastCard(_data!),
        ],
        if (_hasSummary(_data!)) ...[
          const SizedBox(height: 8),
          _summaryCard(_data!),
        ],
      ] else
        const Text('点「看分析」——先看全局，或挑一只票 / 一笔看细节',
            style: TextStyle(fontSize: 12, color: AppColors.darkGrey5)),
    ]);
  }

  Widget _scopeChip(String value, String label) {
    final selected = _scope == value;
    return InkWell(
      onTap: () => _selectScope(value),
      borderRadius: BorderRadius.circular(14),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
        decoration: BoxDecoration(
          color: selected ? AppColors.darkGreen.withValues(alpha: 0.16) : Colors.transparent,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: selected ? AppColors.darkGreen : AppColors.darkGrey4),
        ),
        child: Text(label,
            style: TextStyle(
                fontSize: 12,
                color: selected ? AppColors.darkGreen : AppColors.darkGrey4,
                fontWeight: selected ? FontWeight.w600 : FontWeight.w400)),
      ),
    );
  }

  bool _hasContrast(TradingAnalysisDto d) =>
      d.contrast.hasRules || (d.contrast.reason != null && d.contrast.reason!.isNotEmpty);

  bool _hasSummary(TradingAnalysisDto d) {
    final s = d.summary;
    return (s.fact != null && s.fact!.isNotEmpty) ||
        (s.contrast != null && s.contrast!.isNotEmpty) ||
        (s.question != null && s.question!.isNotEmpty);
  }

  Widget _card({required Widget child}) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.5)),
      ),
      child: child,
    );
  }

  /// 描述块：每行 label + value（+unit）；值下方淡色 trace（哪几笔 / 哪几天 / 说明）。
  Widget _descriptionCard(TradingAnalysisDto d) {
    return _card(
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        for (final f in d.description) _factRow(f),
      ]),
    );
  }

  Widget _factRow(AnalysisFactDto f) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          SizedBox(
              width: 150,
              child: Text(f.label, style: const TextStyle(fontSize: 12, color: AppColors.darkGrey3))),
          Expanded(child: _factValue(f)),
        ]),
        if (!f.trace.isEmpty)
          Padding(
            padding: const EdgeInsets.only(left: 150, top: 2),
            child: Text(_traceText(f.trace),
                style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5, height: 1.35)),
          ),
      ]),
    );
  }

  Widget _factValue(AnalysisFactDto f) {
    final v = f.value;
    // 缺数据：只显示「—」（设计红线：不如实为 0；说明文字在 trace.note 里如实给出）
    if (v == null) {
      return const Text('—', style: TextStyle(fontSize: 12, color: AppColors.darkGrey5));
    }
    if (v is num) {
      return Text('${_numText(v)}${f.unit ?? ''}',
          style: const TextStyle(fontSize: 12, color: AppColors.darkGrey1));
    }
    if (v is String) {
      return Text('$v${f.unit ?? ''}', style: const TextStyle(fontSize: 12, color: AppColors.darkGrey1));
    }
    if (v is List) {
      if (v.isEmpty) return const Text('—', style: TextStyle(fontSize: 12, color: AppColors.darkGrey5));
      return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        for (final item in v)
          Padding(
            padding: const EdgeInsets.only(bottom: 2),
            child: Text(_listItemText(item),
                style: const TextStyle(fontSize: 12, color: AppColors.darkGrey1, height: 1.35)),
          ),
      ]);
    }
    return Text(v.toString(), style: const TextStyle(fontSize: 12, color: AppColors.darkGrey1));
  }

  /// 列表项人话化：RoundBrief（一笔）/ Bucket / PeriodBucket / SizeBucket（分桶）。
  String _listItemText(dynamic item) {
    if (item is Map) {
      final m = Map<String, dynamic>.from(item);
      // RoundBrief：id 里已是「代码_起始日」，再补区间 / 盈亏 / 天数 / 未了结
      if (m.containsKey('id')) {
        final buf = StringBuffer('${m['id']}'.replaceFirst('_', ' '));
        if (m['end'] != null) buf.write(' 至 ${m['end']}');
        if (m['pnlPct'] is num) buf.write(' · ${_numText(m['pnlPct'])}%');
        // m6：盈亏金额掩码（% 与天数属涨跌/时长，不打）
        if (m['pnl'] is num) {
          buf.write(' · ¥${maskIf(_fmtThousands((m['pnl'] as num).toDouble()), widget.revealed)}');
        }
        if (m['holdDays'] is num) buf.write(' · 持 ${m['holdDays']} 天');
        if (m['unresolved'] == true) buf.write(' · 未了结');
        return buf.toString();
      }
      if (m.containsKey('period')) {
        final pnl = m['pnl'];
        return '${m['period']}：${m['count'] ?? 0} 笔'
            '${pnl is num && pnl != 0 ? ' · ¥${maskIf(_fmtThousands(pnl.toDouble()), widget.revealed)}' : ''}';
      }
      if (m.containsKey('avgPnlPct')) {
        return '${m['label'] ?? ''}：${m['count'] ?? 0} 笔 · 均 ${_numText(m['avgPnlPct'])}%';
      }
      if (m.containsKey('label') && m.containsKey('count')) {
        return '${m['label']}：${m['count']} 笔';
      }
      return m.values.map((e) => e?.toString() ?? '').where((s) => s.isNotEmpty).join(' · ');
    }
    return item.toString();
  }

  String _traceText(AnalysisTraceDto t) {
    final parts = <String>[];
    if (t.roundIds.isNotEmpty) {
      final head = t.roundIds.take(3).join('、');
      parts.add('来自 ${t.roundIds.length > 3 ? '$head 等 ${t.roundIds.length} 笔' : head}');
    }
    if (t.dates.isNotEmpty) {
      final head = t.dates.take(3).join('、');
      parts.add(t.dates.length > 3 ? '$head 等 ${t.dates.length} 天' : head);
    }
    if (t.note != null && t.note!.isNotEmpty) parts.add(t.note!);
    return parts.join(' · ');
  }

  Widget _contrastCard(TradingAnalysisDto d) {
    final c = d.contrast;
    return _card(
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        // 有话直说：hasRules=false 后端会给「判不了守没守」的 reason（fallback 兜底）
        Text(c.reason ?? (c.hasRules ? '对照你的规则' : '我还没有你的规则，判不了守没守'),
            style: const TextStyle(fontSize: 12, color: AppColors.darkGrey3, height: 1.4)),
        if (c.hasRules) ...[
          const SizedBox(height: 4),
          for (final h in c.ruleHits)
            Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('${h.rule} · 命中 ${h.count} 笔',
                    style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.darkOrange)),
                if (h.text.isNotEmpty)
                  Text(h.text, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey3, height: 1.35)),
                if (h.roundIds.isNotEmpty)
                  Text(
                      '哪几笔：${h.roundIds.take(3).join('、')}'
                      '${h.roundIds.length > 3 ? ' 等 ${h.roundIds.length} 笔' : ''}',
                      style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
              ]),
            ),
          if (c.ruleHits.isEmpty)
            const Text('这几笔没有命中你的规则', style: TextStyle(fontSize: 12, color: AppColors.darkGrey2)),
        ],
      ]),
    );
  }

  Widget _summaryCard(TradingAnalysisDto d) {
    final s = d.summary;
    return _card(
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        if (s.fact != null && s.fact!.isNotEmpty)
          _summaryLine(s.fact!, Icons.description_outlined, AppColors.darkGrey4),
        if (s.contrast != null && s.contrast!.isNotEmpty)
          _summaryLine(s.contrast!, Icons.compare_arrows, AppColors.darkOrange),
        if (s.question != null && s.question!.isNotEmpty)
          _summaryLine(s.question!, Icons.help_outline, AppColors.darkBlue),
      ]),
    );
  }

  Widget _summaryLine(String text, IconData icon, Color color) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 4),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Icon(icon, size: 13, color: color),
        const SizedBox(width: 6),
        Expanded(
            child: Text(text, style: const TextStyle(fontSize: 12, color: AppColors.darkGrey2, height: 1.4))),
      ]),
    );
  }

  /// 数字人话化：整数不带小数点，非整数去尾零（12.00→12，12.50→12.5）。
  static String _numText(dynamic v) {
    if (v is num) {
      if (v % 1 == 0) return v.toInt().toString();
      return v.toStringAsFixed(2).replaceFirst(RegExp(r'\.?0+$'), '');
    }
    return v?.toString() ?? '—';
  }
}

// ─────────────────────────── 历史成交导入结果补充（RFC 20260825：syncMode + 每日操作总结） ───────────────────────────

/// 导入结果补充展示（Dialog 内与历史成交 Tab inline 共用）：
/// syncMode=sync → 当日操作总结卡（标题带成交日期，如「8/22 操作」）+ 行为标注（亏损加仓/追高等醒目色）；
/// syncMode=append → 补录提示（只补流水，持仓未动）；summary 缺失（append）不报错。
/// RFC 20260912 扩展：rejected（无法归属持仓的成交逐条显眼展示）+ anchor 缺失/基线缺失提示。
class _ImportResultSummary extends StatelessWidget {
  final HistoricalTradeImportResult result;

  const _ImportResultSummary({required this.result});

  @override
  Widget build(BuildContext context) {
    final summary = result.summary;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      // P2-交易43（2026-09-14）：没看懂的行**根本没导入**——排在 rejected 之前（那批至少已记账）
      if (result.unparsed.isNotEmpty) _UnparsedBlock(
        lines: result.unparsed,
        declaredCount: result.unparsedCount,
      ),
      if (result.rejected.isNotEmpty) _rejectedBlock(),
      if (result.anchor != null && !result.anchor!.known) _anchorMissingBlock(),
      // 基线未记录（holdingsKnown=false）→ 对账无法判定（诚实说明，不误报差异）
      if (result.anchor != null && !result.anchor!.holdingsKnown) _baselineBlock(),
      if (result.syncMode == 'sync' && summary != null)
        Container(
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
        )
      else if (result.syncMode == 'append')
        const Padding(
          padding: EdgeInsets.only(top: 4),
          child: Text('已按历史补录处理（只补流水，持仓未动）',
              style: TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
        ),
    ]);
  }

  /// RFC 20260912 关键可见性：无法归属持仓的成交逐条列出（橙色警示卡）。
  /// 这些笔**已经记进流水**，但持仓/现金没动——旧实现把它们混在「跳过 N 笔」里，用户根本看不见。
  Widget _rejectedBlock() {
    final n = result.rejected.length;
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(top: 6),
      padding: const EdgeInsets.all(8),
      decoration: BoxDecoration(
        color: AppColors.darkOrange.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.darkOrange.withValues(alpha: 0.6)),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Icon(Icons.warning_amber_rounded, size: 14, color: AppColors.darkOrange),
          const SizedBox(width: 6),
          Expanded(
            child: Text('有 $n 笔成交没能并入持仓（已记账，未动持仓/现金）',
                style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: AppColors.darkOrange)),
          ),
        ]),
        const SizedBox(height: 4),
        for (final r in result.rejected)
          Padding(
            padding: const EdgeInsets.only(bottom: 3),
            child: Text(r.display, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey2, height: 1.35)),
          ),
        const Text('先导一次「持仓股」或「资金股份查询」快照，我就能把它们归位了。',
            style: TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
      ]),
    );
  }

  /// 锚定缺失（anchor.known=false）：说清原因 + 下一步（先导快照，或只想补流水就用 append）。
  Widget _anchorMissingBlock() {
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(top: 6),
      padding: const EdgeInsets.all(8),
      decoration: BoxDecoration(
        color: AppColors.darkOrange.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.darkOrange.withValues(alpha: 0.5)),
      ),
      child: const Text(
        '还没拿到券商快照的锚定日：哪些成交已经在券商口径里，我判断不了。'
        '先导一次「持仓股」或「资金股份查询」快照，我就能对上了；'
        '只想补逐笔流水（不动持仓/现金）就走「仅补流水」。',
        style: TextStyle(fontSize: 11, color: AppColors.darkGrey2, height: 1.35),
      ),
    );
  }

  /// 快照基线未记录（holdingsKnown=false）：诚实说明对账无法判定（不误报差异）。
  Widget _baselineBlock() {
    return const Padding(
      padding: EdgeInsets.only(top: 4),
      child: Text('快照基线未记录，账实对账暂时无法判定（导一次「持仓股」快照即可开始对账）',
          style: TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
    );
  }
}

/// 历史成交导入「没看懂的行」（P2-交易43，2026-09-14）：**这些行根本没导入**——
/// 旧实现静默 continue，用户在「跳过 N 笔」里根本看不出来自己丢了成交。
/// 沿用同页 rejected 警示卡配色（darkOrange 12%/60%），明细可收起（行多时不占满屏幕）。
/// 默认展开：丢数据必须第一眼可见。
class _UnparsedBlock extends StatefulWidget {
  final List<String> lines;
  // 后端计数（与明细条数恒等；跨结果合并去重后 lines 可能更少）→ 取两者较大值显示
  final int declaredCount;
  // 标题（含丢行条数 n）——不同导入的后果不一样，必须各说各的：
  // 历史成交是「这些成交没有导入」、清仓股是「这几位清仓档案没进库」、资金是「这些票的精确成本没更新」。
  // 不传 → 历史成交默认文案（既有调用点行为不变）。
  final String Function(int n)? header;

  const _UnparsedBlock({required this.lines, this.declaredCount = 0, this.header});

  @override
  State<_UnparsedBlock> createState() => _UnparsedBlockState();
}

/// 历史成交导入的默认标题（P2-交易43 原文案，不动）。
String _defaultUnparsedHeader(int n) => '有 $n 行没能识别（这些成交没有导入）';

class _UnparsedBlockState extends State<_UnparsedBlock> {
  bool _expanded = true;

  @override
  Widget build(BuildContext context) {
    final n = widget.declaredCount > widget.lines.length
        ? widget.declaredCount
        : widget.lines.length;
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(top: 6),
      padding: const EdgeInsets.all(8),
      decoration: BoxDecoration(
        color: AppColors.darkOrange.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.darkOrange.withValues(alpha: 0.6)),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        InkWell(
          onTap: () => setState(() => _expanded = !_expanded),
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Icon(Icons.warning_amber_rounded, size: 14, color: AppColors.darkOrange),
            const SizedBox(width: 6),
            Expanded(
              child: Text((widget.header ?? _defaultUnparsedHeader)(n),
                  style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: AppColors.darkOrange)),
            ),
            Text(_expanded ? '收起' : '看明细',
                style: const TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
            Icon(_expanded ? Icons.expand_less : Icons.expand_more,
                size: 16, color: AppColors.darkGrey4),
          ]),
        ),
        if (_expanded) ...[
          const SizedBox(height: 4),
          for (final line in widget.lines)
            Padding(
              padding: const EdgeInsets.only(bottom: 3),
              child: Text('· $line',
                  style: const TextStyle(fontSize: 11, color: AppColors.darkGrey2, height: 1.35)),
            ),
        ],
      ]),
    );
  }
}

// ─────────────────────────── 历史成交导入 Dialog（RFC 20260823，只认历史成交格式） ───────────────────────────

/// 历史成交导入（独立入口，2026-08-23）：只认通达信「历史成交查询」导出格式——
/// 粘贴或选文件 → isTdxHistoryExport 识别 → 预检（dryRun）→ 用户确认 → POST /trades/import。
/// RFC 20260912：从「点了就落盘」升级为**两段式**（改账与花钱同等待遇）——
/// 先 dryRun 预检拿计划（新增/合并/跳过/非交易/无法归属 + 锚定状态），确认后才真正写。
class _HistoryImportDialog extends StatefulWidget {
  final ApiService api;
  /// 导入成功（含 updated 回填）后回调：父 Tab 刷新列表并展示结果。**预检不触发**。
  final void Function(HistoricalTradeImportResult result) onImported;
  /// RFC 20260912：锚定缺失时「先导快照」的出路（关弹窗 → 打开持仓快照导入）。可空。
  final VoidCallback? onImportSnapshot;

  const _HistoryImportDialog({required this.api, required this.onImported, this.onImportSnapshot});

  @override
  State<_HistoryImportDialog> createState() => _HistoryImportDialogState();
}

/// 导入队列任务（P2-批次4/5，2026-08-29）：一份文件或一段粘贴文本 = 一个 job，
/// 逐份处理并实时展示状态（第 N/共 M、处理中、耗时、成功/跳过/失败）。
/// RFC 20260912：拆出 [plan]（预检结果，未落盘）与 [result]（确认后正式落盘结果）。
class _ImportJob {
  _ImportJob.file(this.name, this.bytes) : content = null;
  _ImportJob.text(this.name, this.content) : bytes = null;

  final String name;
  final List<int>? bytes; // 文件（未上传转码）；粘贴文本为 null
  String? content; // 上传转码后的内容（文件）或直接粘贴的文本
  bool processing = false;
  bool done = false; // 已正式落盘
  bool failed = false;
  String? error;
  HistoricalTradeImportResult? plan; // 预检计划（dryRun=true，未落盘）
  HistoricalTradeImportResult? result; // 确认后正式导入结果
  int elapsedMs = 0;

  bool get planned => plan != null;
  bool get finished => done || failed;
}

/// 多份历史成交导入结果聚合（P2-批次4，2026-08-29 多文件批量）：
/// 计数求和、对账行按 (symbol, netVolume, note) 去重、syncMode 任一 sync 即 sync、
/// summary 取首份非空（多份时以第一份 sync 的操作总结为代表）。
/// RFC 20260912：rejected 按 (symbol,direction,volume,price,entryDate,reason) 去重并合并；
/// anchor 取首份非空（同一次导入同一个锚定状态）。
/// P2-交易43（2026-09-14）：unparsed 明细按文本去重合并、unparsedCount 求和
/// （多份文件各有自己的没看懂行，计数必须累加，否则「N 行」和列出的明细对不上）。
HistoricalTradeImportResult aggregateImportResults(List<HistoricalTradeImportResult> results) {
  final lines = <ReconcileLine>[];
  final seen = <String>{};
  final rejected = <RejectedLineDto>[];
  final seenRejected = <String>{};
  final unparsed = <String>[];
  final seenUnparsed = <String>{};
  for (final r in results) {
    for (final l in r.lines) {
      final key = '${l.symbol}|${l.netVolume}|${l.note}';
      if (seen.add(key)) lines.add(l);
    }
    for (final x in r.rejected) {
      final key = '${x.symbol}|${x.direction}|${x.volume}|${x.price}|${x.entryDate}|${x.reason}';
      if (seenRejected.add(key)) rejected.add(x);
    }
    for (final u in r.unparsed) {
      if (seenUnparsed.add(u)) unparsed.add(u);
    }
  }
  TradeImportSummary? summary;
  for (final r in results) {
    if (r.summary != null) { summary = r.summary; break; }
  }
  AnchorStatusDto? anchor;
  for (final r in results) {
    if (r.anchor != null) { anchor = r.anchor; break; }
  }
  final unparsedCount = results.fold(0, (s, r) => s + r.unparsedCount);
  return HistoricalTradeImportResult(
    imported: results.fold(0, (s, r) => s + r.imported),
    updated: results.fold(0, (s, r) => s + r.updated),
    skipped: results.fold(0, (s, r) => s + r.skipped),
    nonTrades: results.fold(0, (s, r) => s + r.nonTrades),
    lines: lines,
    syncMode: results.any((r) => r.syncMode == 'sync') ? 'sync' : 'append',
    summary: summary,
    rejected: rejected,
    anchor: anchor,
    dryRun: results.any((r) => r.dryRun),
    unparsed: unparsed,
    // 求和后若小于去重后的明细条数（同一行在多份文件里重复出现）→ 取明细条数
    unparsedCount: unparsedCount > unparsed.length ? unparsedCount : unparsed.length,
  );
}

/// 多份预检计划汇总（RFC 20260912）：计数求和；wouldReject / anchorKnown 以聚合结果为准
/// （明细列表与计数必须一致，否则用户看到的「N 笔」和下面列出的行数对不上）。
ImportPlanDto aggregateImportPlans(HistoricalTradeImportResult agg) => ImportPlanDto(
      newCount: agg.imported,
      merged: agg.updated,
      skipped: agg.skipped,
      nonTrades: agg.nonTrades,
      wouldReject: agg.rejected.length,
      anchorKnown: agg.anchor?.known ?? false,
      syncMode: agg.syncMode,
    );

class _HistoryImportDialogState extends State<_HistoryImportDialog> {
  final _text = TextEditingController();
  bool _busy = false; // 预检或落盘在途（防连点并发）
  bool _appendOnly = false; // 用户选了「仅补流水」→ mode=append（不动持仓/现金）
  bool _confirmed = false; // 已确认落盘（预检阶段为 false）
  final List<_ImportJob> _jobs = [];
  HistoricalTradeImportResult? _preflight; // 预检聚合（未落盘）
  HistoricalTradeImportResult? _result; // 正式导入聚合结果
  String? _error;

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  /// 选择通达信历史成交导出（可多选）→ 每份入队 → 逐份预检（不落盘）。
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
        _preflight = null;
        _error = null;
        _confirmed = false;
        for (final f in result.files) {
          if (f.bytes == null) continue;
          _jobs.add(_ImportJob.file(f.name, f.bytes!));
        }
      });
      await _preflightQueue();
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = '文件读取失败，请重试');
    }
  }

  /// 粘贴文本：单 job 入队 → 预检。
  Future<void> _import() async {
    if (_text.text.trim().isEmpty) {
      setState(() => _error = '请粘贴通达信「历史成交查询」导出文本，或选择文件');
      return;
    }
    setState(() {
      _result = null;
      _preflight = null;
      _error = null;
      _confirmed = false;
      _jobs.clear();
      _jobs.add(_ImportJob.text('粘贴文本', _text.text));
    });
    await _preflightQueue();
  }

  /// 阶段一（RFC 20260912）：逐份 dryRun 预检——只算计划，**不写任何文件**。
  /// 多文件时逐份预检、汇总计划后再确认；失败（含锚定缺失 400）原样透出人话 error。
  Future<void> _preflightQueue() async {
    if (_busy) return;
    setState(() => _busy = true);
    final plans = <HistoricalTradeImportResult>[];
    String? firstError;
    for (final job in _jobs) {
      if (!mounted) { _busy = false; return; }
      setState(() {
        job.processing = true;
        job.failed = false;
        job.error = null;
        job.plan = null;
        job.done = false;
        job.result = null;
      });
      final sw = Stopwatch()..start();
      try {
        String content = job.content ?? '';
        if (job.bytes != null) {
          final saved = await widget.api.saveImportFile(job.name, job.bytes!);
          content = saved.content;
        }
        if (!mounted) { _busy = false; return; }
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
        final plan = await widget.api.importTradesHistory(content,
            mode: _appendOnly ? 'append' : 'auto', dryRun: true);
        if (!mounted) { _busy = false; return; }
        setState(() {
          job.processing = false;
          job.plan = plan;
          job.elapsedMs = sw.elapsedMilliseconds;
        });
        plans.add(plan);
      } catch (e) {
        if (!mounted) { _busy = false; return; }
        // 预检失败：原样透出（锚定缺失是 400 + 中文人话，必须逐字给用户看）
        final msg = extractApiErrorMessage(e);
        firstError ??= msg;
        setState(() {
          job.processing = false;
          job.failed = true;
          job.error = msg;
          job.elapsedMs = sw.elapsedMilliseconds;
        });
      }
    }
    if (!mounted) { _busy = false; return; }
    setState(() {
      _busy = false;
      if (plans.isNotEmpty) _preflight = aggregateImportResults(plans);
      if (firstError != null) _error = firstError;
    });
  }

  /// 阶段二：用户点「确认导入」→ 才真正落盘（dryRun=false）。
  Future<void> _confirmImport() async {
    if (_busy) return;
    final targets = _jobs.where((j) => j.planned).toList();
    if (targets.isEmpty) return;
    setState(() => _busy = true);
    final done = <HistoricalTradeImportResult>[];
    String? firstError;
    for (final job in targets) {
      if (!mounted) { _busy = false; return; }
      setState(() => job.processing = true);
      final sw = Stopwatch()..start();
      try {
        final result = await widget.api.importTradesHistory(job.content ?? '',
            mode: _appendOnly ? 'append' : 'auto');
        if (!mounted) { _busy = false; return; }
        setState(() {
          job.processing = false;
          job.done = true;
          job.result = result;
          job.elapsedMs = sw.elapsedMilliseconds;
        });
        done.add(result);
      } catch (e) {
        if (!mounted) { _busy = false; return; }
        final msg = extractApiErrorMessage(e);
        firstError ??= msg;
        setState(() {
          job.processing = false;
          job.failed = true;
          job.error = msg;
          job.elapsedMs = sw.elapsedMilliseconds;
        });
      }
    }
    if (!mounted) { _busy = false; return; }
    final agg = done.isNotEmpty ? aggregateImportResults(done) : null;
    setState(() {
      _busy = false;
      _confirmed = agg != null;
      _result = agg;
      if (firstError != null) _error = firstError;
    });
    if (agg != null) widget.onImported(agg);
  }

  /// 「仅补流水」：以 mode=append 重新预检（只补逐笔流水，不动持仓/现金）——锚定缺失时的安全路。
  Future<void> _retryAppendOnly() async {
    setState(() {
      _appendOnly = true;
      _error = null;
      _preflight = null;
      _confirmed = false;
      _result = null;
      for (final j in _jobs) {
        j.failed = false;
        j.error = null;
        j.plan = null;
        j.done = false;
        j.result = null;
      }
    });
    await _preflightQueue();
  }

  /// 「先导快照」：关掉本弹窗，去导一份「持仓股」/「资金股份查询」建立锚定。
  void _goImportSnapshot() {
    Navigator.pop(context);
    widget.onImportSnapshot?.call();
  }

  static String _fmtMs(int ms) {
    if (ms < 1000) return '${ms}ms';
    return '${(ms / 1000).toStringAsFixed(1)}s';
  }

  /// 预检计划卡（RFC 20260912）：这一次会改什么，落盘前先给用户看清楚。
  Widget _planCard(HistoricalTradeImportResult pre) {
    final plan = aggregateImportPlans(pre);
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(top: 8),
      padding: const EdgeInsets.all(8),
      decoration: BoxDecoration(
        color: AppColors.darkBlue.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.darkBlue.withValues(alpha: 0.45)),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('先看一眼会记什么（还没落盘）${_appendOnly ? ' · 仅补流水' : ''}',
            style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: AppColors.darkBlue)),
        const SizedBox(height: 4),
        Text('新增 ${plan.newCount} 笔 · 合并 ${plan.merged} 笔 · 跳过 ${plan.skipped} 笔'
            '${plan.nonTrades > 0 ? ' · 非交易 ${plan.nonTrades} 行' : ''}',
            style: const TextStyle(fontSize: 11, color: AppColors.darkGrey2)),
        Text(plan.syncMode == 'sync'
                ? '会按成交更新持仓与现金（${plan.anchorKnown ? '锚定日已对上' : '锚定日缺失'}）'
                : '只补逐笔流水，持仓与现金不动',
            style: const TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
        _ImportResultSummary(result: pre),
      ]),
    );
  }

  /// 失败卡（RFC 20260912）：原样透出人话 error + 两条路（先导快照 / 仅补流水）。
  Widget _errorCard(String msg) {
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(top: 8),
      padding: const EdgeInsets.all(8),
      decoration: BoxDecoration(
        color: AppColors.darkOrange.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.darkOrange.withValues(alpha: 0.6)),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(msg, style: const TextStyle(fontSize: 12, color: AppColors.darkGrey1, height: 1.4)),
        if (!_appendOnly) ...[
          const SizedBox(height: 6),
          Wrap(spacing: 8, children: [
            OutlinedButton(
              onPressed: _busy ? null : _goImportSnapshot,
              style: OutlinedButton.styleFrom(
                  foregroundColor: AppColors.darkGrey1,
                  side: const BorderSide(color: AppColors.darkGrey4)),
              child: const Text('先导快照', style: TextStyle(fontSize: 12)),
            ),
            OutlinedButton(
              onPressed: _busy ? null : _retryAppendOnly,
              style: OutlinedButton.styleFrom(
                  foregroundColor: AppColors.darkOrange,
                  side: const BorderSide(color: AppColors.darkOrange)),
              child: const Text('仅补流水', style: TextStyle(fontSize: 12)),
            ),
          ]),
        ],
      ]),
    );
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
                '补逐笔流水不重算持仓（成交编号幂等；缺成交时间自动回填）。'
                '选好后我先算一遍给你看，你点头才真正记进去。',
                style: TextStyle(fontSize: 12, color: AppColors.darkGrey4)),
            const SizedBox(height: 8),
            Row(children: [
              OutlinedButton.icon(
                onPressed: _busy ? null : _pickFile,
                icon: const Icon(Icons.upload_file, size: 16),
                label: Text(_busy ? '处理中…' : '选择文件（可多选，通达信导出 txt）',
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
                          Text(_confirmed ? '导入中…' : '预检中…',
                              style: const TextStyle(fontSize: 11, color: AppColors.darkGrey4))
                        else if (j.done && j.result != null)
                          Text('新增 ${j.result!.imported} · 跳过 ${j.result!.skipped}'
                              '${j.result!.updated > 0 ? ' · 回填 ${j.result!.updated}' : ''}'
                              '${j.result!.rejected.isNotEmpty ? ' · 未并入 ${j.result!.rejected.length}' : ''}'
                              ' · ${_fmtMs(j.elapsedMs)}',
                              style: const TextStyle(fontSize: 11, color: AppColors.darkGrey4))
                        else if (j.planned && j.plan != null)
                          Text('预检：新增 ${j.plan!.imported} · 跳过 ${j.plan!.skipped}'
                              '${j.plan!.rejected.isNotEmpty ? ' · 未并入 ${j.plan!.rejected.length}' : ''}',
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
            if (_confirmed && _result != null) ...[
              Text('导入完成：新增 ${_result!.imported} 笔'
                  '${_result!.updated > 0 ? ' · 回填成交时间 ${_result!.updated} 笔' : ''}'
                  ' · 跳过 ${_result!.skipped} 笔'
                  '${_result!.nonTrades > 0 ? ' · 非交易事件 ${_result!.nonTrades} 行' : ''}',
                  style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.darkGreen)),
              // RFC 20260825：syncMode + 每日操作总结（sync=总结卡+行为标注；append=补录提示）
              // RFC 20260912：rejected 明细 + 锚定缺失提示（同一组件内一并展示）
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
            ] else if (_preflight != null)
              _planCard(_preflight!),
            if (_error != null && !_confirmed) _errorCard(_error!),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('关闭', style: TextStyle(fontSize: 13, color: AppColors.darkGrey4)),
        ),
        if (_preflight != null && !_confirmed)
          FilledButton(
            onPressed: _busy ? null : _confirmImport,
            style: FilledButton.styleFrom(backgroundColor: AppColors.darkGreen, foregroundColor: AppColors.darkBg),
            child: const Text('确认导入', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
          ),
        // 有预检计划时只留「确认导入」（不给第二个会重新预检的入口，避免点错）
        if (_preflight == null && !_confirmed)
          FilledButton(
            onPressed: _busy ? null : _import,
            style: FilledButton.styleFrom(backgroundColor: AppColors.darkGreen, foregroundColor: AppColors.darkBg),
            child: Text(_busy ? '处理中…' : '导入', style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
          ),
      ],
    );
  }
}
// ─────────────── 统一导入抽屉（R-12 · 2026-10-07 起为右侧滑入抽屉，全量对齐原型） ───────────────

/// 统一导入抽屉（`R-12`「一次交齐就行 —— 不用记顺序、不用分次」）：右侧滑入 404px（原型 web-7），
/// 一次多选通达信导出文件（历史成交 / 资金股份 / 持仓股 / 清仓股 / 自选股）或粘贴文本，
/// 交给后端逐份识别。**选完自动先看计划**（dryRun，只报会做什么、不动任何数据），
/// 确认后一次入账；一份失败不影响其他份——逐份行如实展示成功/失败与人话原因。
///
/// 两条进入路径（[onPasteImport] 区分）：
/// - 各 Tab 导入按钮：粘贴文本交回原调用链（保留基准日与既有单份链路）；
/// - 顶栏「导入」：纯 bundle 链（含粘贴文本打包成文件一并逐份识别）。
class _ImportDrawer extends StatefulWidget {
  final ApiService api;
  final Future<void> Function()? onImported; // 正式导入成功后刷新页面数据
  final void Function(String msg) onToast; // 各 Tab 链路的失败反馈（粘贴交回时用）
  final String hint; // 粘贴区引导文案（各 Tab 专属提示）
  final bool withBasisDate; // 显示「数据基准日」输入（快照类导入）
  final Future<void> Function(String content, String? snapshotDate, String? basedOn)? onPasteImport;

  const _ImportDrawer({
    required this.api,
    required this.onToast,
    this.onImported,
    this.hint = '',
    this.withBasisDate = false,
    this.onPasteImport,
  });

  @override
  State<_ImportDrawer> createState() => _ImportDrawerState();
}

class _ImportDrawerState extends State<_ImportDrawer> {
  final List<BundleUploadFile> _files = [];
  final _pasteCtl = TextEditingController();
  final _basisCtl = TextEditingController();
  bool _busy = false;
  BundleImportReceipt? _receipt;
  bool _imported = false; // 已正式导入（按钮停用，防重复提交）
  String? _error;
  String? _pasteError; // 粘贴区校验错误（空内容 / 基准日非法）

  @override
  void dispose() {
    _pasteCtl.dispose();
    _basisCtl.dispose();
    super.dispose();
  }

  Future<void> _pick() async {
    final result = await FilePicker.platform.pickFiles(
      type: FileType.any,
      allowMultiple: true,
      withData: true,
    );
    if (result == null || result.files.isEmpty) return;
    setState(() {
      _receipt = null;
      _error = null;
      _files.clear();
      for (final f in result.files) {
        // 空文件不带进（后端也会逐份拒，但在选择这一步先如实说清）
        if (f.bytes == null || f.bytes!.isEmpty) continue;
        _files.add(BundleUploadFile(f.name, f.bytes!));
      }
      if (_files.isEmpty) {
        // 空文件：粘贴仍是可用路径 → 粘贴区的旧提示不撤（仍成立）
        _error = '选的文件都是空的——重新导出后再试';
      } else {
        // 已选到有效文件 → 「先粘贴内容…」不再成立，立刻撤掉（P2-工程12④ 同族）
        _pasteError = null;
      }
    });
    // 2026-10-07（原型「选完就看计划」）：选完自动预检，不再要求再点一次
    if (_files.isNotEmpty && !_busy) await _run(true);
  }

  Future<void> _run(bool dryRun) async {
    if (!mounted) return;
    if (_files.isEmpty) {
      setState(() => _error = '先选文件——点上面虚线框（可一次多选）');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
      _receipt = null;
    });
    try {
      final r = await widget.api.importTradingBundle(List.of(_files), dryRun: dryRun);
      if (!mounted) return;
      setState(() {
        _receipt = r;
        _busy = false;
        if (!dryRun && r.okCount > 0) _imported = true;
      });
      if (!dryRun && r.okCount > 0) await widget.onImported?.call();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = extractApiErrorMessage(e);
        _busy = false;
      });
    }
  }

  /// 「确认入账 N 条」的 N（原型底部主按钮的权威算法）：
  /// trades = 新增 + 并入；sold/watchlist = 会导入；positions = 文件只数；cash 不贡献。
  /// N = 0 时不编数字，退回「确认入账」。
  int _confirmCount() {
    final r = _receipt;
    if (r == null) return 0;
    var n = 0;
    for (final f in r.files) {
      if (!f.ok) continue;
      final d = f.detail;
      if (f.kind == 'trades') {
        final plan = d['plan'];
        if (plan is Map) {
          n += ((plan['new'] as num?)?.toInt() ?? 0) + ((plan['merged'] as num?)?.toInt() ?? 0);
        } else {
          n += ((d['imported'] as num?)?.toInt() ?? 0) + ((d['updated'] as num?)?.toInt() ?? 0);
        }
      } else if (f.kind == 'sold' || f.kind == 'watchlist') {
        final w = d['wouldImport'];
        if (w is num) n += w.toInt();
      } else if (f.kind == 'positions') {
        n += ((d['fileCount'] as num?)?.toInt() ?? 0);
      }
    }
    return n;
  }

  /// 「对完账才发现的事」：预检里 cash / positions 给的 note 直出（后端人话，前端不重写）。
  List<String> _insightNotes() {
    final r = _receipt;
    if (r == null || !r.dryRun) return const [];
    final notes = <String>[];
    for (final f in r.files) {
      if (!f.ok) continue;
      final note = f.detail['note'];
      if (note is String && note.trim().isNotEmpty) notes.add(note.trim());
    }
    return notes;
  }

  /// 粘贴路径提交：各 Tab 进 → 校验后交回原链（pop 后执行，与旧对话框逐字一致）；
  /// 顶栏进 → 打包成一份「粘贴文本.txt」走统一 bundle 链（同样自动预检）。
  Future<void> _submitPaste() async {
    final text = _pasteCtl.text;
    if (text.trim().isEmpty) {
      setState(() => _pasteError = '先粘贴内容，或选择通达信导出的文件');
      return;
    }
    String? basedOn;
    if (widget.withBasisDate && _basisCtl.text.trim().isNotEmpty) {
      basedOn = parseBasisDateInput(_basisCtl.text);
      if (basedOn == null) {
        setState(() => _pasteError = '基准日「${_basisCtl.text.trim()}」不是有效日期'
            '（写成 2026-09-18 这样），或留空让我按导入时间判断');
        return;
      }
    }
    setState(() => _pasteError = null);
    final handler = widget.onPasteImport;
    if (handler != null) {
      final toast = widget.onToast;
      if (!mounted) return;
      Navigator.pop(context);
      try {
        await handler(text, null, basedOn);
      } catch (e) {
        toast('导入失败：${extractApiErrorMessage(e)}');
      }
      return;
    }
    setState(() {
      _receipt = null;
      _error = null;
      _files
        ..clear()
        ..add(BundleUploadFile('粘贴文本.txt', utf8.encode(text)));
    });
    await _run(true);
  }

  @override
  Widget build(BuildContext context) {
    // 原型 web-7：右侧抽屉 404px——Material 铺底，左边框与页面分界；不遮整屏（barrier 由外层给）
    return Material(
      color: AppColors.darkSurface,
      child: Container(
        key: const Key('importDrawer'),
        width: 404,
        height: double.infinity,
        decoration: const BoxDecoration(
          border: Border(left: BorderSide(color: AppColors.darkBorder)),
        ),
        child: SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                const Text('导入',
                    style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
                const Spacer(),
                GestureDetector(
                  onTap: () => Navigator.pop(context),
                  child: const Icon(Icons.close, size: 18, color: AppColors.darkGrey5),
                ),
              ]),
              const SizedBox(height: 4),
              const Text('一次交齐就行 —— 不用记顺序、不用分次。',
                  style: TextStyle(fontSize: 11, color: AppColors.darkGrey5, height: 1.4)),
              const SizedBox(height: 12),
              Expanded(
                child: SingleChildScrollView(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    _pickZone(),
                    if (_files.isNotEmpty) ...[
                      const SizedBox(height: 10),
                      _fileList(),
                    ],
                    if (_busy) ...[
                      const SizedBox(height: 10),
                      const Row(children: [
                        SizedBox(width: 13, height: 13, child: CircularProgressIndicator(strokeWidth: 2)),
                        SizedBox(width: 8),
                        Text('我来逐份看看…', style: TextStyle(fontSize: 12, color: AppColors.darkGrey4)),
                      ]),
                    ],
                    if (_error != null) ...[
                      const SizedBox(height: 10),
                      _errorCard(),
                    ],
                    // 回执卡：预检「这次认出来的」→ 确认入账；正式导入后同卡换文案（结果留给人看）
                    if (_receipt != null) ...[
                      const SizedBox(height: 10),
                      _receiptCard(_receipt!),
                    ],
                    // 「对完账才发现的事」（原型 wd-say）：预检阶段才有
                    if (_receipt?.dryRun == true && _insightNotes().isNotEmpty) ...[
                      const SizedBox(height: 10),
                      _insightCard(),
                    ],
                    const SizedBox(height: 12),
                    _pasteSection(),
                  ]),
                ),
              ),
              const SizedBox(height: 12),
              _footer(),
            ]),
          ),
        ),
      ),
    );
  }

  /// 点选区（原型 wd-drop 虚线框）：整框可点，选完自动预检
  Widget _pickZone() {
    return GestureDetector(
      key: const Key('importPickZone'),
      onTap: _busy ? null : _pick,
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 26),
        decoration: BoxDecoration(
          color: AppColors.darkSurface2,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: AppColors.darkBorder),
        ),
        child: Column(children: [
          Icon(_busy ? Icons.hourglass_top : Icons.file_download_outlined,
              size: 22, color: _busy ? AppColors.darkGrey5 : AppColors.darkGrey3),
          const SizedBox(height: 8),
          const Text('把「资金流水 + 历史成交」选到这里',
              style: TextStyle(fontSize: 12, color: AppColors.darkGrey2)),
          const SizedBox(height: 4),
          Text(_files.isEmpty ? '或者点这里选文件（可多选，通达信导出）' : '已选 ${_files.length} 份——点这里重新选',
              style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
        ]),
      ),
    );
  }

  Widget _fileList() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2.withValues(alpha: 0.5),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        for (final f in _files)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 2),
            child: Row(children: [
              const Icon(Icons.insert_drive_file_outlined, size: 13, color: AppColors.darkGrey5),
              const SizedBox(width: 6),
              Expanded(
                  child: Text(f.name,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 12, color: AppColors.darkGrey2))),
            ]),
          ),
      ]),
    );
  }

  Widget _errorCard() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(8),
      decoration: BoxDecoration(
        color: AppColors.darkOrange.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.darkOrange.withValues(alpha: 0.6)),
      ),
      child: Text(_error!, style: const TextStyle(fontSize: 11, color: AppColors.darkOrange, height: 1.35)),
    );
  }

  /// 「这次认出来的」卡（原型 wd-card）：汇总一行 + 逐份行（成功绿勾 / 失败橙叹号 + 人话摘要）。
  /// 预检与正式导入共用一张卡，靠 r.dryRun 分流文案；滚动由外层 SingleChildScrollView 统一管。
  Widget _receiptCard(BundleImportReceipt r) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.8)),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Icon(
              r.failedCount == 0
                  ? (r.dryRun ? Icons.visibility_outlined : Icons.check_circle_outline)
                  : Icons.info_outline,
              size: 14,
              color: r.failedCount == 0 ? AppColors.darkGreen : AppColors.darkOrange),
          const SizedBox(width: 6),
          Expanded(
            child: Text(
              r.dryRun ? '这次认出来的' : '这次交齐的',
              style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.darkGrey1),
            ),
          ),
        ]),
        const SizedBox(height: 2),
        Text(
          r.dryRun
              ? '计划：${r.okCount} 份能处理${r.failedCount > 0 ? '，${r.failedCount} 份不行' : ''}（预检没动任何数据）'
              : '导入完成：成功 ${r.okCount} 份${r.failedCount > 0 ? '，失败 ${r.failedCount} 份' : ''}',
          style: const TextStyle(fontSize: 11, color: AppColors.darkGrey4),
        ),
        const SizedBox(height: 6),
        for (final f in r.files) _fileResultRow(f),
        if (!r.dryRun && r.okCount > 0)
          const Padding(
            padding: EdgeInsets.only(top: 6),
            child: Text('数据已更新——去「分析」标签看三粒度结果，或关掉后看各 Tab',
                style: TextStyle(fontSize: 11, color: AppColors.darkGrey4)),
          ),
      ]),
    );
  }

  /// 「对完账才发现的事」（原型 wd-say）：预检里 cash / positions 给的 note 直出。
  Widget _insightCard() {
    final notes = _insightNotes();
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2.withValues(alpha: 0.6),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: AppColors.darkBorder.withValues(alpha: 0.8)),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Text('对完账才发现的事',
            style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.darkGrey2)),
        const SizedBox(height: 4),
        for (final n in notes)
          Padding(
            padding: const EdgeInsets.only(bottom: 2),
            child: Text('· $n', style: const TextStyle(fontSize: 11, color: AppColors.darkGrey4, height: 1.4)),
          ),
      ]),
    );
  }

  /// 粘贴区（各 Tab 专属入口保留的旧链路）：有 onPasteImport 时点主按钮 = pop 后交回原链；
  /// 顶栏入口时粘贴文本打包成一份文件，走统一 bundle 链。
  Widget _pasteSection() {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      const Text('或直接粘贴文本', style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
      if (widget.hint.isNotEmpty) ...[
        const SizedBox(height: 4),
        Text(widget.hint, style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
      ],
      if (widget.withBasisDate) ...[
        const SizedBox(height: 8),
        Row(children: [
          const Text('数据基准日（可选）', style: TextStyle(fontSize: 12, color: AppColors.darkGrey3)),
          const SizedBox(width: 8),
          SizedBox(
            width: 130,
            child: TextField(
              key: const Key('tradeImportBasis'),
              controller: _basisCtl,
              style: const TextStyle(fontSize: 12, color: AppColors.darkGrey1),
              decoration: const InputDecoration(
                isDense: true,
                hintText: '2026-09-18',
                hintStyle: TextStyle(fontSize: 11, color: AppColors.darkGrey5),
              ),
            ),
          ),
        ]),
      ],
      const SizedBox(height: 6),
      TextField(
        key: const Key('tradeImportContent'),
        controller: _pasteCtl,
        maxLines: 5, minLines: 3,
        style: const TextStyle(fontSize: 12, color: AppColors.darkGrey1),
      ),
      if (_pasteError != null)
        Padding(
          padding: const EdgeInsets.only(top: 6),
          child: Text(_pasteError!, style: const TextStyle(fontSize: 11, color: AppColors.darkRed)),
        ),
    ]);
  }

  /// 底部（原型：主按钮 flex:1 + 「再看看」）：按状态分派——
  /// 已导入 → 「完成」；预检完有文件 → 「确认入账 N 条」（正式导入）；预检失败 → 「重试」；
  /// 无文件 → 「导入」（提交粘贴内容）。
  Widget _footer() {
    final r = _receipt;
    final needConfirm = _files.isNotEmpty && r != null && r.dryRun;
    final noFiles = _files.isEmpty;
    return Row(children: [
      Expanded(
        child: FilledButton(
          key: const Key('importConfirmBtn'),
          onPressed: _busy
              ? null
              : _imported
                  ? () => Navigator.pop(context)
                  : needConfirm
                      ? () => _run(false)
                      : noFiles
                          ? () => _submitPaste()
                          : () => _run(true),
          // 原型 wd-btn.main：亮底深字（= darkGrey1 底 / darkBg 字）——与顶栏「导入」同款
          style: FilledButton.styleFrom(
            backgroundColor: AppColors.darkGrey1,
            foregroundColor: AppColors.darkBg,
            minimumSize: const Size.fromHeight(36),
          ),
          child: Text(
            _busy
                ? '处理中…'
                : _imported
                    ? '完成'
                    : needConfirm
                        ? (_confirmCount() > 0 ? '确认入账 ${_confirmCount()} 条' : '确认入账')
                        : noFiles
                            ? '导入'
                            : '重试',
            style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
          ),
        ),
      ),
      const SizedBox(width: 8),
      if (!_imported)
        SizedBox(
          height: 36,
          child: OutlinedButton(
            onPressed: () => Navigator.pop(context),
            style: OutlinedButton.styleFrom(
              foregroundColor: AppColors.darkGrey3,
              side: const BorderSide(color: AppColors.darkBorder),
            ),
            child: const Text('再看看', style: TextStyle(fontSize: 13)),
          ),
        ),
    ]);
  }

  Widget _fileResultRow(BundleFileResultDto f) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Icon(f.ok ? Icons.check_circle_outline : Icons.error_outline,
            size: 14, color: f.ok ? AppColors.darkGreen : AppColors.darkOrange),
        const SizedBox(width: 6),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Flexible(
                child: Text(f.filename,
                    overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 12, color: AppColors.darkGrey1)),
              ),
              const SizedBox(width: 6),
              Text(f.kindLabel ?? '',
                  style: TextStyle(fontSize: 11, color: f.ok ? AppColors.darkGrey5 : AppColors.darkOrange)),
            ]),
            Text(
              f.ok ? _fileSummary(f) : (f.error ?? '这份没能处理（未做任何改动）'),
              style: TextStyle(
                  fontSize: 11, height: 1.35, color: f.ok ? AppColors.darkGrey3 : AppColors.darkOrange),
            ),
          ]),
        ),
      ]),
    );
  }

  /// 单份摘要（按 kind + detail 常见键拼人话；认不出的不硬猜，显示「完成」）。
  String _fileSummary(BundleFileResultDto f) {
    final d = f.detail;
    final parts = <String>[];
    if (f.kind == 'cash') {
      if (d['brokerCash'] != null) {
        parts.add('对账：券商 ¥${_money(d['brokerCash'])} vs 系统 ¥${_money(d['systemCash'])}');
        final diff = d['diff'];
        if (diff is num && diff != 0) parts.add('差 ¥${_money(diff)}');
      } else {
        if (d['assets'] != null) parts.add('总资产 ¥${_money(d['assets'])}');
        if (d['updatedCost'] != null) parts.add('更新成本 ${d['updatedCost']} 只');
      }
    } else if (f.kind == 'positions') {
      if (d['fileCount'] != null) {
        parts.add('对账：文件 ${d['fileCount']} 只 vs 系统 ${d['systemCount']} 只');
        final diffs = d['diffs'];
        if (diffs is List && diffs.isNotEmpty) parts.add('对不上的 ${diffs.length} 只');
      } else {
        parts.add('入账 ${d['imported'] ?? 0} 只');
      }
    } else if (f.kind == 'trades') {
      final plan = d['plan'];
      if (plan is Map) {
        parts.add('新增 ${plan['new'] ?? 0} · 并入 ${plan['merged'] ?? 0} · 跳过 ${plan['skipped'] ?? 0}');
        final wr = plan['wouldReject'];
        if (wr is num && wr > 0) parts.add('无法归属 $wr 笔');
      } else {
        parts.add('导入 ${d['imported'] ?? 0} 笔');
        final merged = d['updated'];
        if (merged is num && merged != 0) parts.add('并入 $merged 笔');
      }
    } else if (f.kind == 'sold') {
      parts.add(d['wouldImport'] != null ? '会导入 ${d['wouldImport']} 笔' : '导入 ${d['imported'] ?? 0} 笔');
    } else if (f.kind == 'watchlist') {
      parts.add(d['wouldImport'] != null ? '会导入 ${d['wouldImport']} 只' : '导入 ${d['imported'] ?? 0} 只');
    }
    final up = d['unparsedCount'];
    if (up is num && up > 0) parts.add('另有 $up 行没看懂（未导入）');
    final sk = d['skipped'];
    if (sk is List && sk.isNotEmpty) parts.add('跳过 ${sk.length} 行');
    final unsup = d['unsupported'];
    if (unsup is List && unsup.isNotEmpty) parts.add('非主板未入账 ${unsup.length} 只');
    final pres = d['preserved'];
    if (pres is List && pres.isNotEmpty) parts.add('保留存量 ${pres.length} 只');
    return parts.isEmpty ? '完成' : parts.join(' · ');
  }

  /// 金额人话化（detail 里的数字可能是 int/double）。
  static String _money(dynamic v) {
    if (v is num) return _fmtThousands(v.toDouble());
    return v?.toString() ?? '—';
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
  late final Map<String, bool> _settings = Map.of(widget.settings);

  /// D2（2026-09-13 首轮外部视角审查拍板 A）：网页端**收不到**推送。
  /// 浏览器通知本项目未接（Web Push 需要 Service Worker + VAPID），阿呆目前只能推到 iPhone。
  /// 此前这里没有门控：开关全能点、服务端也如实存下，然后一条通知都不来——
  /// 用户不会认为是「这个产品没做」，只会以为「我是不是设错了」。
  static const bool _canReceivePush = false;

  static const List<(String, String)> _items = [
    ('session', '时段节奏（早盘/午间/尾盘/收盘确认）'), // B11-3：注明含 15:15 收盘操作确认
    ('buy-point', '买点提醒'),
    ('close-summary', '收盘小结（当日成交+破止损+待确认）'), // P2-用户3 2026-08-29
    ('plan', '次日计划提醒（20:30 提醒写下个交易日的计划）'), // RFC 20261003 §三 2026-10-03
    ('learn-review', '学习复习提醒（每日复习到期卡片）'), // learn V2 批 4 2026-09-07
    ('todo-due', '待办到期提醒'), // RFC 20260917：待办到期日当天提醒（默认开、可关）
    ('stop-loss', '止损预警'),
    ('near-stop-loss', '接近止损'),
    ('loss', '单日大跌提醒'),
    ('gain', '放飞提示'),
    ('break-cost', '跌破成本线'),
    ('market', '大盘行情条'),
  ];

  /// 网页端的说明：不承诺收不到的东西（D2-A 明确告知）。
  Widget _notSupportedNotice() {
    return const Padding(
      padding: EdgeInsets.only(bottom: 10),
      child: Text(
        '网页里收不到通知——阿呆现在只能推到 iPhone。\n'
        '这些开关先留着，装了 iPhone 上的阿呆就按这个来。',
        style: TextStyle(fontSize: 12, color: AppColors.darkGrey4, height: 1.4),
      ),
    );
  }

  Future<void> _toggle(String type, bool on) async {
    // B5-6（2026-08-23，P2-推送5 半修残留）：成功才翻转 + 失败透出原因
    final err = await widget.onToggle(type, on);
    if (!mounted) return;
    if (err == null) {
      setState(() => _settings[type] = on);
    } else {
      widget.onToggleFailed?.call(err);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      // D2（2026-09-13）：加了「网页里收不到通知」说明条后内容变高，小屏会溢出 → 可滚动
      scrollable: true,
      backgroundColor: AppColors.darkSurface2,
      title: const Text('推送设置',
        style: TextStyle(fontSize: 16, color: AppColors.darkGrey1)),
      content: SizedBox(
        width: 320,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (!_canReceivePush) _notSupportedNotice(),
            for (final (type, label) in _items)
              SwitchListTile(
                dense: true,
                contentPadding: EdgeInsets.zero,
                title: Text(label,
                  style: const TextStyle(fontSize: 13, color: AppColors.darkGrey2)),
                value: _settings[type] ?? true,
                activeTrackColor: AppColors.darkGreen,
                onChanged: _canReceivePush ? (on) { _toggle(type, on); } : null,
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

// ─────────────────────────── 资金曲线卡（2026-09-04 决策方案 A） ───────────────────────────

/// 资金曲线迷你图：净值（或总资产，principal=0 时）折线 + 峰值参考线 + 最大回撤标注。
/// 数据源 GET /trading/equity-curve（后端流水+快照锚定聚合）；空/失败 → 人话空态不打断资金区。
class _EquityCurveCard extends StatefulWidget {
  const _EquityCurveCard({required this.api, required this.revealed});

  final ApiService api;

  /// m6：金额打码状态（父页 👁 统一切换）——总资产形态的「最新 ¥x」默认掩码（净值是比率，不打）。
  final bool revealed;

  @override
  State<_EquityCurveCard> createState() => _EquityCurveCardState();
}

class _EquityCurveCardState extends State<_EquityCurveCard> {
  EquityCurveResponse? _data;
  String? _error;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final resp = await widget.api.getEquityCurve();
      if (!mounted) return;
      setState(() {
        _data = resp;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = extractApiErrorMessage(e);
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const SizedBox(
        height: 150,
        child: Center(
          child: SizedBox(
              width: 18,
              height: 18,
              child: CircularProgressIndicator(
                  strokeWidth: 2, color: AppColors.darkGreen)),
        ),
      );
    }
    if (_error != null) {
      return Container(
        width: double.infinity,
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: AppColors.darkSurface2,
          borderRadius: BorderRadius.circular(10),
        ),
        child: Text('资金曲线加载失败：$_error · ',
            style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
      );
    }
    final pts = _data?.points ?? const <EquityCurvePoint>[];
    if (pts.length < 2) {
      return Container(
        width: double.infinity,
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: AppColors.darkSurface2,
          borderRadius: BorderRadius.circular(10),
        ),
        child: const Text('资金曲线：记录交易/导入资金后，这里会画出你的资产变化曲线',
            style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
      );
    }

    // 画净值；principal=0（netValue 全 null）→ 退画总资产
    final useNet = pts.every((p) => p.netValue != null) && (pts.last.netValue ?? 0) > 0;
    final values = useNet
        ? pts.map((p) => p.netValue!).toList()
        : pts.map((p) => p.totalAssets).toList();
    double minV = values.reduce((a, b) => a < b ? a : b);
    double maxV = values.reduce((a, b) => a > b ? a : b);
    if (maxV - minV < 1e-9) maxV = minV + 1;
    final last = pts.last;
    double maxDrawdown = 0;
    double runningPeak = double.negativeInfinity;
    for (final v in values) {
      if (v > runningPeak) runningPeak = v;
      if (runningPeak > 0) {
        final dd = (runningPeak - v) / runningPeak;
        if (dd > maxDrawdown) maxDrawdown = dd;
      }
    }

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(14, 10, 14, 8),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          const Text('资金曲线',
              style: TextStyle(
                  fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.darkGrey1)),
          const SizedBox(width: 8),
          Text(useNet ? '净值（总资产/净投入）' : '总资产',
              style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
          const Spacer(),
          if (useNet)
            Text('最新净值 ${last.netValue!.toStringAsFixed(3)}',
                style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: last.netValue! >= 1 ? AppColors.darkRed : AppColors.darkGreen)),
          if (!useNet)
            Text('最新 ${maskIf(_fmtThousands(last.totalAssets), widget.revealed)}',
                style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.darkGrey2)),
        ]),
        const SizedBox(height: 4),
        SizedBox(
          height: 120,
          width: double.infinity,
          child: CustomPaint(
            painter: _EquityLinePainter(
              values: values,
              minValue: minV,
              maxValue: maxV,
              color: useNet ? AppColors.darkRed : AppColors.darkBlue,
            ),
          ),
        ),
        const SizedBox(height: 4),
        Row(children: [
          Text('${pts.first.date} → ${pts.last.date}',
              style: const TextStyle(fontSize: 10, color: AppColors.darkGrey5)),
          const Spacer(),
          Text(
              useNet
                  ? (last.netValue! >= 1
                      ? '区间 ${((last.netValue! - 1) * 100).toStringAsFixed(1)}%'
                      : '区间 ${((last.netValue! - 1) * 100).toStringAsFixed(1)}%')
                  : '',
              style: TextStyle(fontSize: 10, color: last.netValue != null && last.netValue! >= 1 ? AppColors.darkRed : AppColors.darkGrey5)),
          if (maxDrawdown > 0.001) ...[
            const SizedBox(width: 12),
            Text('最大回撤 ${(maxDrawdown * 100).toStringAsFixed(1)}%',
                style: const TextStyle(fontSize: 10, color: AppColors.darkOrange)),
          ],
        ]),
      ]),
    );
  }
}

/// 折线 painter：值 → 归一折线 + 低透明面积 + 峰值参考虚线。
class _EquityLinePainter extends CustomPainter {
  final List<double> values;
  final double minValue;
  final double maxValue;
  final Color color;

  const _EquityLinePainter({
    required this.values,
    required this.minValue,
    required this.maxValue,
    required this.color,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final span = maxValue - minValue == 0 ? 1.0 : maxValue - minValue;
    final padV = 6.0;
    Offset point(int i) {
      final x = size.width * i / (values.length - 1);
      final y = padV + (size.height - padV * 2) * (1 - (values[i] - minValue) / span);
      return Offset(x, y.clamp(0, size.height).toDouble());
    }

    final line = Path()..moveTo(point(0).dx, point(0).dy);
    for (int i = 1; i < values.length; i++) {
      line.lineTo(point(i).dx, point(i).dy);
    }
    final fill = Path.from(line)
      ..lineTo(size.width, size.height)
      ..lineTo(0, size.height)
      ..close();
    canvas.drawPath(fill, Paint()..color = color.withValues(alpha: 0.08));
    canvas.drawPath(
        line, Paint()..color = color..strokeWidth = 1.6..style = PaintingStyle.stroke);

    // 峰值参考虚线
    double peak = double.negativeInfinity;
    int peakIdx = 0;
    for (int i = 0; i < values.length; i++) {
      if (values[i] > peak) {
        peak = values[i];
        peakIdx = i;
      }
    }
    final peakY = point(peakIdx).dy;
    canvas.drawLine(
      Offset(0, peakY),
      Offset(size.width, peakY),
      Paint()
        ..color = AppColors.darkGrey5.withValues(alpha: 0.5)
        ..strokeWidth = 1
        ..style = PaintingStyle.stroke,
    );
    canvas.drawCircle(point(peakIdx), 2.2, Paint()..color = color);
    canvas.drawCircle(point(values.length - 1), 2.2, Paint()..color = color);
  }

  @override
  bool shouldRepaint(_EquityLinePainter old) =>
      old.values != values || old.color != color;
}
