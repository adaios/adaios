import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:image_picker/image_picker.dart';
import '../theme/app_colors.dart';
import '../services/api_service.dart';
import '../widgets/input_bar.dart' show PickedImage;

/// TradingPage — 建议引擎，不是记账工具（RFC 20260815）。
///
/// 记录真实交易是手段（喂数据），建议是目的：阿呆结合 trading domain 规则
/// 给买/卖/持仓建议。本页没有「平仓/减仓」执行按钮——建议是输出，不是操作。
///
/// 记录区两条通道共用同一确认/写入链路：
/// - 通道 A：一句话（NL）→ POST /trading/trades/parse → 确认卡回显（AI 错误在此拦截）
/// - 通道 B：精确填写折叠表单（标的 | 价格 | 数量 + 隐藏式止损/买点 + 底部 [买入][卖出] 双按钮）
/// 2026-08-22：止损/买点非必填隐藏式（需要时展开，止损默认 −7% 可改可清，买点可为空）；
///            自选股/清仓复盘区块移除（管理归 web，手机专注记录 + 阿呆建议）。
class TradingPage extends StatefulWidget {
  final ApiService api;

  /// 测试钩子：注入选图结果（等价 input_bar 的 debugInjectImages 模式，widget 测试不真调相册）。
  @visibleForTesting
  final Future<List<PickedImage>> Function()? debugPickImages;

  const TradingPage({super.key, required this.api, this.debugPickImages});

  @override
  State<TradingPage> createState() => _TradingPageState();
}

class _TradingPageState extends State<TradingPage> {
  List<PositionItem> _positions = [];
  PortfolioSnapshotResponse? _snapshot;
  bool _loading = true;
  String? _error;

  // ── 2026-08-17 对齐 web：账户快照（异步加载不阻塞主数据）──
  AccountSnapshotDto? _account;
  bool _auxLoading = false; // 次级数据加载中（账户快照，不转圈整页）
  int _auxGen = 0; // 代际令牌：_loadAux 防乱序覆盖

  // ── RFC 20260825：逐笔批次（GET /trading/lots，异步加载失败静默）──
  List<LotItem> _lots = [];
  bool _lotsLoading = false;
  int _lotsGen = 0; // 代际令牌：_loadLots 防乱序覆盖（与 _auxGen 同款守卫）

  // ── RFC 20260822：当日交易复盘（纯客观：今日 N 笔 · 时段分布）──
  DailyTradeSummaryDto? _dailySummary;
  bool _dailyLoading = false;

  // ── 通道 A：NL 输入条 ──
  final _nlCtrl = TextEditingController();
  bool _parsing = false;

  // ── 确认卡片（NL 解析结果草稿，可编辑回显）──
  ParseTradeResponse? _draft;
  final _confirmVolumeCtrl = TextEditingController();
  final _confirmPriceCtrl = TextEditingController();
  String _confirmDirection = 'BUY';
  bool _confirming = false;

  // ── 通道 B：精确表单（标的/价格/数量 + 双按钮，2026-08-22：隐藏式止损/买点，需要时展开）──
  bool _showForm = false;
  final _symbolCtrl = TextEditingController();
  final _priceCtrl = TextEditingController();
  final _volumeCtrl = TextEditingController();
  bool _showPlan = false; // 隐藏式「止损/买点」展开开关（默认收起，需要时点开）
  final _stopLossCtrl = TextEditingController();
  String _buyPoint = ''; // 买点类型（空 = 不填，BUY 可选）
  bool _stopLossTouched = false; // 用户手动改过止损 → 价格变化不再覆盖默认值
  bool _submitting = false;

  // ── 复盘横幅（P1：has-activity 检测 + 生成）──
  bool _hasActivity = false;
  bool _bannerDismissed = false;
  bool _reviewGenerated = false;
  bool _reviewing = false;
  ReviewResponse? _lastReview;

  // ── 2026-08-26 截图入账（交易闭环第一环）：当日候选 + 确认/丢弃 ──
  List<TradeLogCandidateDto> _candidates = [];
  bool _shotsUploading = false;       // 截图上传 + VLM 归集中
  bool _candidatesConfirming = false; // 全部确认入账中

  Timer? _autoRefresh; // 30 分钟自动刷新（对齐 web B3，2026-08-17）

  @override
  void initState() {
    super.initState();
    _loadAll();
    // 跟随交易节奏：每 30 分钟自动刷新盈亏/行情（手机上看不到旧数据）
    _autoRefresh = Timer.periodic(const Duration(minutes: 30), (_) {
      if (mounted) _refresh();
    });
  }

  @override
  void dispose() {
    _autoRefresh?.cancel();
    _nlCtrl.dispose();
    _confirmVolumeCtrl.dispose();
    _confirmPriceCtrl.dispose();
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
      _loadAux();       // 账户快照（异步，不阻塞主数据）
      _loadDaily();     // RFC 20260822：当日交易复盘（异步，失败静默）
      _loadLots();      // RFC 20260825：逐笔批次简版（异步，失败静默）
      _loadCandidates(); // 2026-08-26 截图入账：当日候选（异步，失败静默）
    } catch (e) {
      if (!mounted) return;
      setState(() { _error = _extractApiError(e); _loading = false; }); // #113 人话
    }
  }

  /// 2026-08-26 截图入账：当日交易日志候选（GET /trading/trade-log，异步失败静默）。
  Future<void> _loadCandidates() async {
    try {
      final list = await widget.api.getTradeLogCandidates();
      if (!mounted) return;
      setState(() => _candidates = list);
    } catch (_) {
      // 静默：候选是增强项，失败不影响持仓主数据（确认后失败候选保留由 _confirmCandidates 兜底）
    }
  }

  /// 次级数据（账户快照）：独立拉取，失败静默不影响主数据。
  /// 代际令牌（2026-08-17）：响应乱序时旧代不覆盖新代（与 web P2-10 同款守卫）。
  /// 2026-08-22：自选/买点/清仓/打分区块移除（管理归 web，能力不删），只保留账户快照。
  Future<void> _loadAux() async {
    // P2-UX1（2026-08-17 走查）：先判锁再递增——早退分支不得先递增代际，
    // 否则在途请求 finally 判定 gen != _auxGen 不复位锁 → 次级数据本会话永久不刷新
    if (_auxLoading) return;
    _auxLoading = true;
    final gen = ++_auxGen;
    try {
      final acct = await widget.api.getAccount();
      if (!mounted || gen != _auxGen) return; // 旧代丢弃
      setState(() {
        _account = acct;
      });
    } catch (_) {
      // 次级数据失败静默（账户卡退回组合快照口径）
    } finally {
      _auxLoading = false; // 无条件复位（锁只被本请求持有，串行安全）
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

  /// RFC 20260822：当日交易复盘聚合（今日 N 笔 · 早/午/尾盘分布）——纯客观数字，
  /// 失败静默（今日无成交/后端旧版本 → 不显示该行，不阻塞页面）。
  Future<void> _loadDaily() async {
    if (_dailyLoading) return;
    _dailyLoading = true;
    try {
      final resp = await widget.api.getDailyTrades();
      if (!mounted) return;
      setState(() {
        _dailySummary = resp.daily.count > 0 ? resp.daily : null; // 无成交不显示
      });
    } catch (_) {
      // 静默：后端未升级 / 无数据时页面不受影响
    } finally {
      _dailyLoading = false;
    }
  }

  /// RFC 20260825：逐笔批次（简版）——一次拉全量，持仓卡按 symbol 过滤。
  /// 失败静默降级（保留原持仓卡，不整页报错）：后端旧版本/网络失败 → 批次行不出现。
  /// 代际令牌：与 _loadAux 同款守卫——旧代响应不覆盖新代。
  Future<void> _loadLots() async {
    if (_lotsLoading) return;
    _lotsLoading = true;
    final gen = ++_lotsGen;
    try {
      final resp = await widget.api.getLots(); // 默认 state=all（含已清仓回合，前端过滤开放批）
      if (!mounted || gen != _lotsGen) return; // 旧代丢弃
      setState(() => _lots = resp.lots);
    } catch (_) {
      // 静默：批次信息是增强项，拉取失败不影响持仓卡原有展示
    } finally {
      _lotsLoading = false; // 无条件复位（锁只被本请求持有，串行安全）
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
  // 2026-08-26 截图入账（交易闭环第一环）：选图 → VLM 归集 → 候选确认
  // ────────────────────────────────────────────────────────────

  /// 选图（相册多选，最多 3 张）→ 上传归集。测试注入 debugPickImages 跳过相册。
  Future<void> _pickScreenshots() async {
    if (_shotsUploading) return;
    List<PickedImage> picked;
    try {
      if (widget.debugPickImages != null) {
        picked = await widget.debugPickImages!();
      } else {
        final files = await ImagePicker().pickMultiImage(
          maxWidth: 1920, // 限制长边，避免超大字节压栈（与 input_bar 同参）
          imageQuality: 85,
          limit: 3,
        );
        picked = [];
        for (final f in files) {
          final bytes = await f.readAsBytes();
          picked.add(PickedImage(bytes, f.name,
              f.name.contains('.') ? f.name.split('.').last : 'jpg'));
        }
      }
    } catch (e) {
      _showSnack('图片选择失败: $e', AppColors.darkOrange);
      return;
    }
    if (picked.isEmpty) return;
    if (picked.length > 3) {
      _showSnack('一次最多 3 张截图', AppColors.darkOrange);
      picked = picked.take(3).toList();
    }
    await _uploadScreenshots(picked);
  }

  /// 上传截图 → 后端 VLM 识别归集为当日候选（不建记录不落原图）。
  Future<void> _uploadScreenshots(List<PickedImage> images) async {
    setState(() => _shotsUploading = true);
    try {
      final result = await widget.api.uploadTradingScreenshots(
        bytesList: images.map((i) => i.bytes).toList(),
        filenames: images.map((i) => i.name).toList(),
        mimeTypes: images.map((i) => _mimeOf(i.extension)).toList(),
      );
      if (!mounted) return;
      setState(() {
        _shotsUploading = false;
        _candidates = result.candidates;
      });
      if (result.errors.isNotEmpty) {
        _showSnack('${result.errors.length} 张识别失败：${result.errors.join('；')}', AppColors.darkOrange);
      } else if (result.candidates.isEmpty) {
        _showSnack('没认出成交，试试截清楚一点（只认「已成」）', AppColors.darkGrey4);
      } else {
        _showSnack('识别出 ${result.candidates.length} 笔成交候选，确认后入账', AppColors.darkGreen);
      }
      _checkActivity(); // 截图归集后今日可能有成交 → 复盘横幅重新检测
    } catch (e) {
      if (!mounted) return;
      setState(() => _shotsUploading = false);
      _showSnack('截图入账失败: ${_extractApiError(e)}', AppColors.darkOrange);
    }
  }

  /// 全部确认入账：逐笔走 recordTrade 落库 → 清空候选 → 持仓即时刷新 + 复盘横幅触发。
  /// 2026-08-27 二修：截图候选缺成交日期会被后端拒（skipped）——确认前先拦截提示补日期。
  Future<void> _confirmCandidates() async {
    if (_candidates.isEmpty || _candidatesConfirming) return;
    final missingDateCount = _candidates.where(
        (c) => c.tradeDate == null || c.tradeDate!.isEmpty).length;
    if (missingDateCount > 0) {
      _showSnack('有 $missingDateCount 笔截图候选缺成交日期——点「补日期」选好日期后才能入账',
          AppColors.darkOrange);
      return;
    }
    setState(() => _candidatesConfirming = true);
    try {
      final result = await widget.api.confirmTradeLog();
      if (!mounted) return;
      setState(() {
        _candidatesConfirming = false;
        _candidates = [];
      });
      if (result.confirmed > 0) {
        _showSnack('已确认 ${result.confirmed} 笔并入账', AppColors.darkGreen);
      } else if (result.failed > 0) {
        _showSnack('确认失败：${result.failures.isNotEmpty ? result.failures.first : '未知原因'}', AppColors.darkOrange);
      } else {
        _showSnack('没有可确认的候选', AppColors.darkGrey4);
      }
      _refresh();       // 持仓即时刷新
      _checkActivity(); // 成交入账 → 复盘横幅可生成（真实成交口径）
      _loadCandidates(); // 失败候选保留（钉子户）→ 重新拉取
    } catch (e) {
      if (!mounted) return;
      setState(() => _candidatesConfirming = false);
      _showSnack('确认失败: ${_extractApiError(e)}', AppColors.darkOrange);
    }
  }

  /// 丢弃一条候选（识别错误/重复，DELETE /trading/trade-log?symbol=&direction=）。
  Future<void> _discardCandidate(TradeLogCandidateDto c) async {
    try {
      await widget.api.discardTradeLogCandidate(
        symbol: c.symbol.isEmpty ? null : c.symbol,
        direction: c.direction,
      );
      if (!mounted) return;
      setState(() => _candidates = _candidates
          .where((x) => !(x.symbol == c.symbol && x.direction == c.direction))
          .toList());
    } catch (e) {
      if (mounted) _showSnack('丢弃失败: ${_extractApiError(e)}', AppColors.darkOrange);
    }
  }

  /// 扩展名 → MIME（与 main_page._mimeTypeOf 同口径；HEIC 等真实类型防误标）。
  String _mimeOf(String? ext) {
    switch (ext?.toLowerCase()) {
      case 'jpg': case 'jpeg': return 'image/jpeg';
      case 'webp': return 'image/webp';
      case 'gif': return 'image/gif';
      case 'heic': return 'image/heic';
      case 'heif': return 'image/heif';
      default: return 'image/png';
    }
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
        // 2026-08-18 简化：app 只记录买卖（标的/价格/数量/方向），止损/买点由 NL 带回也不再回填——归 web 端
        setState(() {
          _draft = parsed;
          _confirmDirection = parsed.direction == 'SELL' ? 'SELL' : 'BUY';
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
    // 2026-08-18 简化：app 不填止损/买点（归 web 端设置）
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
  /// 2026-08-22：BUY 可选带隐藏式止损/买点（非必填，止损有默认值 −7%，买点可为空）。
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
    // SELL 预检：未持有/超持仓拦截（与通道 A 一致）
    if (direction == 'SELL') {
      final sellErr = _validateSell(symbol, volume!);
      if (sellErr != null) { _showSnack(sellErr, AppColors.darkOrange); return; }
    }
    // BUY 可选止损/买点：非必填；止损填了须 > 0（清空 = 不设止损）
    double? stopLoss;
    if (direction == 'BUY' && _stopLossCtrl.text.trim().isNotEmpty) {
      stopLoss = double.tryParse(_stopLossCtrl.text.trim());
      if (stopLoss == null || stopLoss <= 0) {
        _showSnack('止损位须为大于 0 的数字（不设就清空）', AppColors.darkOrange);
        return;
      }
    }
    final buyPoint = direction == 'BUY' ? _buyPoint.trim() : '';
    setState(() => _submitting = true);
    try {
      await widget.api.recordTrade(
        symbol: symbol,
        direction: direction,
        price: price!,
        volume: volume!,
        stopLossPrice: stopLoss,
        buyPoint: buyPoint.isEmpty ? null : buyPoint,
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
    // D9（P2-UI3）：成功 toast 用中性/方向色（买=红卖=绿），不再恒绿混用成功色
    _showSnack('已$dirLabel $label $volume 股 @${_fmtPrice(price)}',
        direction == 'BUY' ? AppColors.darkRed : AppColors.darkGreen);
    setState(() {
      _draft = null;
      _confirmVolumeCtrl.clear();
      _confirmPriceCtrl.clear();
      _nlCtrl.clear();
      _showForm = false;
      _showPlan = false; // 2026-08-22：收起隐藏式止损/买点并清空
      _stopLossCtrl.clear();
      _buyPoint = '';
      _stopLossTouched = false;
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
          // B11-1（2026-08-23，P1-推送3）：推送设置入口——app 首页入口仅右滑 push 卡，
          // 空仓日/8 类全关后卡不可达 → self-lock；交易页常驻铃铛（对齐 web）
          IconButton(
            icon: Icon(Icons.notifications_outlined, size: 18, color: AppColors.darkGrey5),
            onPressed: _openPushSettings,
            tooltip: '推送设置',
          ),
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
                if (_dailySummary != null) ...[
                  const SizedBox(height: 10),
                  _buildDailySummary(),
                ],
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
                // 2026-08-26 截图入账：当日候选（快记区下方，确认即入账，与复盘横幅成闭环）
                if (_candidates.isNotEmpty) ...[
                  const SizedBox(height: 10),
                  _buildCandidatesCard(),
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
                // 2026-08-22：自选股/清仓复盘区块移除——管理归 web（通达信导入/打分/心理标注），
                // 手机端专注日常记录 + 阿呆建议；买点提醒由 15:10 推送覆盖。
              ],
            ),
    );
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
      Row(children: [
        // 2026-08-26 截图入账：用户核心工作流「发截图」的一等入口（拍照/相册 → VLM → 候选）
        GestureDetector(
          onTap: _shotsUploading ? null : _pickScreenshots,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
            child: Row(children: [
              _shotsUploading
                  ? const SizedBox(width: 11, height: 11,
                      child: CircularProgressIndicator(strokeWidth: 1.6, color: AppColors.darkGreen))
                  : Icon(Icons.camera_alt_outlined, size: 13, color: AppColors.darkGreen),
              const SizedBox(width: 4),
              Text('截图入账', style: TextStyle(fontSize: 11, color: AppColors.darkGreen,
                  decoration: TextDecoration.underline, decorationColor: AppColors.darkGreen)),
            ]),
          ),
        ),
        const SizedBox(width: 16),
        GestureDetector(
          onTap: () => setState(() => _showForm = !_showForm),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
            child: Text(_showForm ? '收起精确填写' : '精确填写',
                style: TextStyle(fontSize: 11, color: AppColors.darkGrey5, decoration: TextDecoration.underline)),
          ),
        ),
      ]),
    ]);
  }

  // ── 2026-08-26 截图入账：当日候选卡（逐笔可丢弃 + 全部确认入账）──

  Widget _buildCandidatesCard() {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: AppColors.darkGreen.withValues(alpha: 0.25), width: 0.5),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Icon(Icons.camera_alt_outlined, size: 14, color: AppColors.darkGreen),
          const SizedBox(width: 6),
          Expanded(
            child: Text('今日截图候选 ${_candidates.length} 笔 · 确认后入账',
                style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: AppColors.darkGrey2)),
          ),
        ]),
        const SizedBox(height: 8),
        ..._candidates.map(_candidateRow),
        const SizedBox(height: 10),
        SizedBox(
          width: double.infinity,
          height: 36,
          child: ElevatedButton(
            onPressed: _candidatesConfirming ? null : _confirmCandidates,
            style: ElevatedButton.styleFrom(
              backgroundColor: AppColors.darkGreen.withValues(alpha: 0.15),
              foregroundColor: AppColors.darkGreen,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
            ),
            child: _candidatesConfirming
                ? const SizedBox(width: 14, height: 14,
                    child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.darkGreen))
                : const Text('全部确认入账', style: TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600)),
          ),
        ),
      ]),
    );
  }

  Widget _candidateRow(TradeLogCandidateDto c) {
    final isBuy = c.direction == 'BUY';
    final dirColor = isBuy ? AppColors.darkRed : AppColors.darkGreen;
    final missingDate = c.tradeDate == null || c.tradeDate!.isEmpty;
    return Container(
      margin: const EdgeInsets.only(bottom: 6),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      decoration: BoxDecoration(
        color: AppColors.darkSurface,
        borderRadius: BorderRadius.circular(8),
        border: missingDate
            ? Border.all(color: AppColors.darkOrange.withValues(alpha: 0.5), width: 0.8)
            : null,
      ),
      child: Row(children: [
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
          decoration: BoxDecoration(
            color: dirColor.withValues(alpha: 0.14),
            borderRadius: BorderRadius.circular(4),
          ),
          child: Text(isBuy ? '买入' : '卖出',
              style: TextStyle(fontSize: 10, fontWeight: FontWeight.w600, color: dirColor)),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('${c.name.isEmpty ? c.symbol : c.name} (${c.symbol})',
                style: const TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600, color: AppColors.darkGrey2),
                overflow: TextOverflow.ellipsis),
            if (missingDate)
              // 2026-08-27 二修：截图缺成交日期禁止落库——需补日期才能确认入账
              Text('⚠ 缺成交日期，需补充后才能入账',
                  style: TextStyle(fontSize: 10, color: AppColors.darkOrange)),
          ]),
        ),
        if (c.tradeDate != null && c.tradeDate!.isNotEmpty)
          Text(c.tradeDate!, // 2026-08-27：截图「日期」列提取的成交日期（确认入账按此日期）
              style: const TextStyle(fontSize: 10.5, color: AppColors.darkGrey5)),
        if (c.price != null && c.volume != null)
          Text('${c.volume}股 @${_fmtPrice(c.price!)}',
              style: const TextStyle(fontSize: 11.5, color: AppColors.darkGrey4)),
        const SizedBox(width: 6),
        if (missingDate)
          GestureDetector(
            onTap: () => _pickCandidateDate(c),
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 3),
              decoration: BoxDecoration(
                color: AppColors.darkOrange.withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(5),
              ),
              child: const Text('补日期', style: TextStyle(fontSize: 10.5, fontWeight: FontWeight.w600, color: AppColors.darkOrange)),
            ),
          )
        else
          GestureDetector(
            onTap: () => _discardCandidate(c),
            child: const Icon(Icons.close, size: 14, color: AppColors.darkGrey5),
          ),
      ]),
    );
  }

  /// 2026-08-27 二修：截图候选缺成交日期 → 弹日期选择补写（PUT /trade-log/date）→ 刷新候选。
  Future<void> _pickCandidateDate(TradeLogCandidateDto c) async {
    if (_candidatesConfirming) return;
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      initialDate: DateTime(now.year, now.month, now.day),
      firstDate: DateTime(now.year - 1, now.month, now.day),
      lastDate: DateTime(now.year, now.month, now.day),
      helpText: '这笔成交发生在哪一天？',
      cancelText: '取消',
      confirmText: '确定',
    );
    if (picked == null || !mounted) return;
    final dateStr = '${picked.year.toString().padLeft(4, '0')}-'
        '${picked.month.toString().padLeft(2, '0')}-'
        '${picked.day.toString().padLeft(2, '0')}';
    try {
      final updated = await widget.api.setTradeLogDate(
        symbol: c.symbol,
        direction: c.direction,
        tradeDate: dateStr,
      );
      if (!mounted) return;
      if (updated) {
        _showSnack('已补日期 $dateStr，可确认入账', AppColors.darkGreen);
      } else {
        _showSnack('未找到该候选，可能已处理', AppColors.darkOrange);
      }
      _loadCandidates();
    } catch (e) {
      if (!mounted) return;
      _showSnack('补日期失败: ${_extractApiError(e)}', AppColors.darkOrange);
    }
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
          // 2026-08-18：价格键盘支持小数点（A 股成本价 4 位精度，需 5 位小数输入能力）
          Expanded(child: _formField('价格', _confirmPriceCtrl,
              keyboardType: const TextInputType.numberWithOptions(decimal: true), hintText: '成交单价')),
          const SizedBox(width: 10),
          _dirChip('BUY', '买'),
          const SizedBox(width: 4),
          _dirChip('SELL', '卖'),
        ]),
        const SizedBox(height: 12),
        Row(children: [
          Expanded(
            child: SizedBox(
              height: 36,
              child: ElevatedButton(
                onPressed: _confirming ? null : _confirmTrade,
                // D9（P2-UI3）：确认按钮按方向着色（买=红、卖=绿），成功色不再混用
                style: ElevatedButton.styleFrom(
                  backgroundColor: (_confirmDirection == 'BUY' ? AppColors.darkRed : AppColors.darkGreen)
                      .withValues(alpha: 0.2),
                  foregroundColor: _confirmDirection == 'BUY' ? AppColors.darkRed : AppColors.darkGreen,
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

  // ── 精确表单：标的 | 价格 | 数量 + 隐藏式止损/买点 + 底部 [买入][卖出]
  // 2026-08-22：止损/买点非必填，默认收起，需要时展开（止损有默认值 −7%，买点可为空）──

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
          // 2026-08-18：价格键盘支持小数点（A 股成本价 4 位精度，需 5 位小数输入能力）
          Expanded(child: _formField('价格', _priceCtrl,
              keyboardType: const TextInputType.numberWithOptions(decimal: true), hintText: '成交单价',
              onChanged: _onPriceChanged)),
          const SizedBox(width: 10),
          Expanded(child: _formField('数量', _volumeCtrl, keyboardType: TextInputType.number, hintText: '股数')),
        ]),
        // 隐藏式止损/买点：默认收起，点开按需填（止损自动带默认 −7%，可改可清；买点可空）
        const SizedBox(height: 4),
        GestureDetector(
          onTap: () => setState(() => _showPlan = !_showPlan),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
            child: Text(_showPlan ? '收起止损/买点' : '止损/买点（可选）',
                style: TextStyle(fontSize: 11, color: AppColors.darkGrey5, decoration: TextDecoration.underline)),
          ),
        ),
        if (_showPlan) ...[
          const SizedBox(height: 6),
          Row(children: [
            Expanded(child: _formField('止损', _stopLossCtrl,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                hintText: '跌破就清仓（默认 −7%）',
                onChanged: (_) => _stopLossTouched = true)),
            const SizedBox(width: 10),
            Expanded(child: _buyPointField()),
          ]),
          const SizedBox(height: 4),
          Text('止损/买点只对买入生效；清空止损 = 不设（建议引擎按 R68 降级判定）',
              style: TextStyle(fontSize: 10, color: AppColors.darkGrey5)),
        ],
        const SizedBox(height: 12),
        Row(children: [
          Expanded(child: _tradeButton('买入', 'BUY')),
          const SizedBox(width: 10),
          Expanded(child: _tradeButton('卖出', 'SELL')),
        ]),
      ]),
    );
  }

  /// 价格输入 → 自动带默认止损 = 价格 × 0.93（−7%，2026-08-17 设定，与 web 一致）。
  /// 2026-08-22：只在展开隐藏区时预填（收起 = 不设置止损，隐藏式语义）；
  /// 手动改过止损（_stopLossTouched）或当前非空则不覆盖。
  void _onPriceChanged(String v) {
    if (!_showPlan) return;
    if (_stopLossTouched) return;
    if (_stopLossCtrl.text.trim().isNotEmpty) return;
    final price = double.tryParse(v.trim());
    if (price != null && price > 0) {
      _stopLossCtrl.text = (price * 0.93).toStringAsFixed(2);
    } else {
      _stopLossCtrl.clear();
    }
  }

  /// 买点类型下拉（可空选，白名单与 web 一致）。
  Widget _buyPointField() {
    const options = ['B1', 'B2', 'B3', 'SB1', '暴力特噗', '深水炸弹', '单针', '其他'];
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text('买点', style: TextStyle(fontSize: 11, color: AppColors.darkGrey5)),
      const SizedBox(height: 4),
      Container(
        height: 38,
        padding: const EdgeInsets.symmetric(horizontal: 10),
        decoration: BoxDecoration(
          color: AppColors.darkBg,
          borderRadius: BorderRadius.circular(6),
          border: Border.all(color: AppColors.darkBorder, width: 0.5),
        ),
        child: DropdownButtonHideUnderline(
          child: DropdownButton<String>(
            value: _buyPoint.isEmpty ? null : _buyPoint,
            hint: const Text('不填', style: TextStyle(fontSize: 12, color: AppColors.darkGrey6)),
            isExpanded: true,
            dropdownColor: AppColors.darkSurface,
            style: const TextStyle(fontSize: 12, color: AppColors.darkGrey2),
            items: [
              ...options.map((o) => DropdownMenuItem(value: o, child: Text(o, style: const TextStyle(fontSize: 12, color: AppColors.darkGrey2)))),
            ],
            onChanged: (v) => setState(() => _buyPoint = v ?? ''),
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
    // D9（2026-08-23 app 体感，P2-UI3）：方向徽标按 A 股红涨绿跌——买=红、卖=绿（原恒绿）
    final dirColor = value == 'BUY' ? AppColors.darkRed : AppColors.darkGreen;
    return GestureDetector(
      onTap: () => setState(() => _confirmDirection = value),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        decoration: BoxDecoration(
          color: selected ? dirColor.withValues(alpha: 0.15) : AppColors.darkBg,
          borderRadius: BorderRadius.circular(6),
          border: Border.all(
            color: selected ? dirColor.withValues(alpha: 0.3) : AppColors.darkBorder,
            width: 0.5,
          ),
        ),
        child: Text(label, style: TextStyle(fontSize: 12, fontWeight: FontWeight.w500,
            color: selected ? dirColor : AppColors.darkGrey5)),
      ),
    );
  }

  // ── RFC 20260822：当日交易复盘（纯客观数字）──

  Widget _buildDailySummary() {
    final d = _dailySummary!;
    final sessionText = d.sessions
        .where((s) => s.count > 0)
        .map((s) => '${s.name} ${s.count}')
        .join(' · ');
    final timeText = (d.firstTradeTime != null && d.lastTradeTime != null)
        ? '${d.firstTradeTime!.substring(0, 5)}-${d.lastTradeTime!.substring(0, 5)}'
        : '';
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: AppColors.darkSurface2,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.darkGreen.withValues(alpha: 0.2), width: 0.5),
      ),
      child: Row(children: [
        Icon(Icons.schedule, size: 14, color: AppColors.darkGreen),
        const SizedBox(width: 6),
        Expanded(
          child: Text(
            '今日 ${d.count} 笔 · 买 ${d.buyCount} 卖 ${d.sellCount}'
            '${sessionText.isEmpty ? '' : ' · $sessionText'}'
            '${timeText.isEmpty ? '' : ' · $timeText'}',
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 11, color: AppColors.darkGrey2),
          ),
        ),
      ]),
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
          // D9（2026-08-23 app 体感，P2-UI2）：无账户快照时显示「—」——原显示伪 0 冒充真实值
          _snapshotItem('可用', hasAccount ? _fmtMoney(a.available) : '—'),
          _snapshotItem('可取', hasAccount ? _fmtMoney(a.withdrawable) : '—'),
          _snapshotItem('市值', hasAccount ? _fmtMoney(a.marketValue) : (s?.totalValue != null ? _fmtMoney(s!.totalValue) : '—')),
          _snapshotItem('当日盈亏', hasAccount
              ? '${a.todayPnl >= 0 ? '+' : ''}${_fmtMoney(a.todayPnl)}'
              : '—',
              hasAccount ? (a.todayPnl >= 0 ? AppColors.darkRed : AppColors.darkGreen) : AppColors.darkGrey5),
          if (hasAccount && a.principal > 0)
            _snapshotItem('本金', _fmtMoney(a.principal)),
        ]),
        // D9（2026-08-23 app 体感，P2-UX3）：快照时间戳——收盘 15:05 后无陈旧感知
        if (a?.snapshotDate != null && a!.snapshotDate.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text('账户快照 ${a.snapshotDate} · 每 30 分钟自动刷新',
                style: const TextStyle(fontSize: 9, color: AppColors.darkGrey5)),
          ),
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
          // RFC 20260825：批次简版（一眼可见，管理归 web；无批次数据时不出现该行）
          if (_lotSummaryFor(p.symbol) case final lot?) ...[
            const SizedBox(height: 6),
            _buildLotRow(lot),
          ],
        ]),
      ),
    );
  }

  /// 持仓卡的批次简版摘要：开放批次（未清仓且剩余 > 0）计数 + 最近买入日期 + 含底仓 + 破止损警示。
  _LotSummary? _lotSummaryFor(String symbol) {
    if (_lots.isEmpty) return null;
    final open = _lots
        .where((l) => l.symbol == symbol && !l.closed && l.remaining > 0)
        .toList();
    if (open.isEmpty) return null;
    final latestBuy = open
        .map((l) => l.buyDate)
        .reduce((a, b) => a.compareTo(b) >= 0 ? a : b);
    return _LotSummary(
      count: open.length,
      latestBuyDate: latestBuy,
      hasInitial: open.any((l) => l.initial),
      breachStopLoss: open.any((l) => (l.stopLossDistancePct ?? 0) < 0),
    );
  }

  /// 批次简版行：批次数 + 最近买入 + 含底仓徽标 + 「有批次破止损」警示点。
  Widget _buildLotRow(_LotSummary s) {
    return Row(children: [
      Icon(Icons.account_tree_outlined, size: 12, color: AppColors.darkGrey5),
      const SizedBox(width: 5),
      Flexible(
        child: Text(
          '${s.count} 个批次 · 最近买入 ${_fmtShortDate(s.latestBuyDate)}',
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(fontSize: 11, color: AppColors.darkGrey5),
        ),
      ),
      if (s.hasInitial) ...[
        const SizedBox(width: 6),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
          decoration: BoxDecoration(
            color: AppColors.darkGreen.withValues(alpha: 0.12),
            borderRadius: BorderRadius.circular(4),
          ),
          child: const Text('含底仓',
              style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppColors.darkGreen)),
        ),
      ],
      if (s.breachStopLoss) ...[
        const SizedBox(width: 6),
        Row(mainAxisSize: MainAxisSize.min, children: [
          Icon(Icons.warning_amber_rounded, size: 11, color: AppColors.darkOrange),
          const SizedBox(width: 3),
          const Text('有批次破止损',
              style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: AppColors.darkOrange)),
        ]),
      ],
    ]);
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
    // D7（2026-08-23 app 体感）：优先提取后端人话 error（原只回状态码）
    final str = e.toString();
    if (str.contains('API 错误') || str.contains('API 请求失败')) {
      try {
        final jsonStr = str.split(': ').skip(1).join(': ');
        final decoded = jsonDecode(jsonStr);
        if (decoded is Map && decoded['error'] != null) return '${decoded['error']}';
      } catch (_) {}
      final codeMatch = RegExp(r'(\d{3})').firstMatch(str);
      return '请求失败 (${codeMatch?.group(1) ?? '?'})';
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

  /// B11-1（2026-08-23，P1-推送3）：推送设置（交易页常驻铃铛入口——app 首页右滑入口
  /// 空仓日/全关后不可达 self-lock）。开关失败透出原因（B11-2 同口径）。
  Future<void> _openPushSettings() async {
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
            return null;
          } catch (e) {
            return _extractApiError(e);
          }
        },
        onToggleFailed: (msg) {
          messenger.showSnackBar(SnackBar(
            content: Text('推送设置失败：$msg', style: const TextStyle(fontSize: 13)),
            backgroundColor: AppColors.darkSurface2,
          ));
        },
      ),
    );
  }

  /// 生成今日复盘 → 弹窗展示（交易系统反哺可达）。
  /// 2026-08-26 复盘卡点（用户拍板）：无当日真实成交 → 引导先截图入账，不空转 AI。
  Future<void> _showReview() async {
    if (!_hasActivity) {
      _showSnack('今天还没有导入成交，先「截图入账」吧', AppColors.darkGrey4);
      return;
    }
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

/// RFC 20260825：批次简版摘要（持仓卡副行用，克制展示）。
class _LotSummary {
  final int count; // 开放批次数
  final String latestBuyDate; // 最近买入日期 yyyy-MM-dd
  final bool hasInitial; // 含初始底仓（_INIT 批次）
  final bool breachStopLoss; // 有批次破止损未走（距止损 < 0 且仍有剩余）

  const _LotSummary({
    required this.count,
    required this.latestBuyDate,
    required this.hasInitial,
    required this.breachStopLoss,
  });
}

/// yyyy-MM-dd → M/d（如 2026-08-03 → 8/03），批次行日期克制显示。
String _fmtShortDate(String yyyyMMdd) {
  if (yyyyMMdd.length < 10) return yyyyMMdd;
  final m = int.tryParse(yyyyMMdd.substring(5, 7));
  final d = yyyyMMdd.substring(8, 10);
  return m == null ? yyyyMMdd : '$m/$d';
}

// ── B11-1（2026-08-23，P1-推送3）：推送设置对话框（交易页常驻铃铛入口）──

/// 推送设置逐项开关；onToggle 返回 null=成功 / 字符串=失败原因（B11-2 同口径）。
class _PushSettingsDialog extends StatefulWidget {
  final Map<String, bool> settings;
  final Future<String?> Function(String type, bool on) onToggle;
  final void Function(String message)? onToggleFailed;

  const _PushSettingsDialog({required this.settings, required this.onToggle, this.onToggleFailed});

  @override
  State<_PushSettingsDialog> createState() => _PushSettingsDialogState();
}

class _PushSettingsDialogState extends State<_PushSettingsDialog> {
  late Map<String, bool> _settings = Map.of(widget.settings);

  static const List<(String, String)> _items = [
    ('session', '时段节奏（早盘/午间/尾盘/收盘确认）'),
    ('buy-point', '买点提醒'),
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
        width: 300,
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
