package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.*;
import com.adaiadai.core.domain.trading.market.MarketData;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.infrastructure.storage.TradingRuleSettingsRepository;
import com.adaiadai.core.kernel.IdGenerator;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TradingAppService — 交易领域应用服务。
 * <p>
 * 编排交易记录的完整流程：结构化交易输入 → Record → 更新持仓。
 * 独立的交易业务编排，不同于 RecordFlowAppService 的通用 MVP 流程。
 */
@Service
public class TradingAppService {

    private static final Logger log = LoggerFactory.getLogger(TradingAppService.class);

    /** 交易记录去重窗口：同一标题的记录在窗口内视为重试，不重复写入（防重试重复进时间线/复盘提醒）。 */
    private static final Duration RECORD_DEDUP_WINDOW = Duration.ofMinutes(5);

    /** P2-交易37（2026-09-09 晚间自主批）：每用户读写锁固定 16 条带——
     *  原 ConcurrentHashMap computeIfAbsent 按 userId 无界增长（同族 P3：userTradeLocks 无界累积），
     *  收敛为固定条带（个人系统并发度低，条带串行可接受；同 TradeLogRepository/P2-交易28 模式）。 */
    private static final Object[] USER_TRADE_LOCKS = new Object[16];

    static {
        for (int i = 0; i < USER_TRADE_LOCKS.length; i++) USER_TRADE_LOCKS[i] = new Object();
    }

    private final PositionRepository positionRepository;
    private final RecordRepository recordRepository;
    private final TradingHistoryRepository tradingHistoryRepository;
    private final WatchlistRepository watchlistRepository;
    private final SoldTradeRepository soldTradeRepository;
    private final AccountSnapshotRepository accountSnapshotRepository;
    private final TransferRepository transferRepository;
    /** P2-交易34 治本（2026-09-09）：券商快照锚定（持仓 replace / 资金股份导入日），增量推导防重复入账。 */
    private final TradingAnchorRepository anchorRepository;
    private final MarketDataSource marketDataSource;
    /** RFC 20260825：批次推导与行为标注（当日成交同步模式 / 每日操作总结依赖）。 */
    private final TradingLotService tradingLotService;
    /** 第三阶段：用户规则参数配置（清仓 verdict 阈值按用户隔离）。 */
    private final TradingRuleSettingsRepository tradingRuleSettingsRepository;
    /** RFC 20260909 批 1：清仓股流水自动收录（可空=未接线，触发/查询判空跳过）。 */
    private final ClearanceDetector clearanceDetector;

    /** Spring 主构造（含锚定仓储——P2-交易34 防重；清仓推导——RFC 20260909 批 1）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public TradingAppService(PositionRepository positionRepository,
                             RecordRepository recordRepository,
                             TradingHistoryRepository tradingHistoryRepository,
                             WatchlistRepository watchlistRepository,
                             SoldTradeRepository soldTradeRepository,
                             AccountSnapshotRepository accountSnapshotRepository,
                             TransferRepository transferRepository,
                             MarketDataSource marketDataSource,
                             TradingLotService tradingLotService,
                             TradingRuleSettingsRepository tradingRuleSettingsRepository,
                             TradingAnchorRepository anchorRepository,
                             ClearanceDetector clearanceDetector) {
        this.positionRepository = positionRepository;
        this.recordRepository = recordRepository;
        this.tradingHistoryRepository = tradingHistoryRepository;
        this.watchlistRepository = watchlistRepository;
        this.soldTradeRepository = soldTradeRepository;
        this.accountSnapshotRepository = accountSnapshotRepository;
        this.transferRepository = transferRepository;
        this.anchorRepository = anchorRepository;
        this.marketDataSource = marketDataSource;
        this.tradingLotService = tradingLotService;
        this.tradingRuleSettingsRepository = tradingRuleSettingsRepository;
        this.clearanceDetector = clearanceDetector;
    }

    /** 兼容构造（11 参，无清仓推导——测试/旧调用兼容，行为等同历史版本）。 */
    public TradingAppService(PositionRepository positionRepository,
                             RecordRepository recordRepository,
                             TradingHistoryRepository tradingHistoryRepository,
                             WatchlistRepository watchlistRepository,
                             SoldTradeRepository soldTradeRepository,
                             AccountSnapshotRepository accountSnapshotRepository,
                             TransferRepository transferRepository,
                             MarketDataSource marketDataSource,
                             TradingLotService tradingLotService,
                             TradingRuleSettingsRepository tradingRuleSettingsRepository,
                             TradingAnchorRepository anchorRepository) {
        this(positionRepository, recordRepository, tradingHistoryRepository, watchlistRepository,
                soldTradeRepository, accountSnapshotRepository, transferRepository, marketDataSource,
                tradingLotService, tradingRuleSettingsRepository, anchorRepository, null);
    }

    /** 无锚定仓储/清仓推导构造（测试/旧调用兼容：不做 P2-交易34 防重，行为等同历史版本）。 */
    public TradingAppService(PositionRepository positionRepository,
                             RecordRepository recordRepository,
                             TradingHistoryRepository tradingHistoryRepository,
                             WatchlistRepository watchlistRepository,
                             SoldTradeRepository soldTradeRepository,
                             AccountSnapshotRepository accountSnapshotRepository,
                             TransferRepository transferRepository,
                             MarketDataSource marketDataSource,
                             TradingLotService tradingLotService,
                             TradingRuleSettingsRepository tradingRuleSettingsRepository) {
        this(positionRepository, recordRepository, tradingHistoryRepository, watchlistRepository,
                soldTradeRepository, accountSnapshotRepository, transferRepository, marketDataSource,
                tradingLotService, tradingRuleSettingsRepository, noopAnchorRepository(), null);
    }

    private static TradingAnchorRepository noopAnchorRepository() {
        return new TradingAnchorRepository() {
            @Override
            public SnapshotAnchor find(String userId) {
                return SnapshotAnchor.empty();
            }

            @Override
            public void updatePositionsReplace(String userId, java.time.LocalDate date) {
            }

            @Override
            public void updateCashImport(String userId, java.time.LocalDate date) {
            }

            @Override
            public void recordHoldings(String userId, List<SnapshotHolding> holdings) {
            }

            @Override
            public List<SnapshotHolding> holdings(String userId) {
                return List.of();
            }

            @Override
            public boolean holdingsRecorded(String userId) {
                return false;
            }
        };
    }

    private Object tradeLock(String userId) {
        // 同一 userId 的持仓读-改-写全串行，防并发交易互相覆盖（REVIEW #147）
        int h = (userId != null ? userId : "default").hashCode();
        return USER_TRADE_LOCKS[(h ^ (h >>> 16)) & (USER_TRADE_LOCKS.length - 1)];
    }

    // ── P2-交易34 治本（2026-09-09）：券商快照锚定防重 ──

    /**
     * 最近一次「全量锚定」日 = 持仓 replace 与资金股份导入的较晚者。
     * <p>锚定语义：replace/资金导入落地的是券商**当下**真实状态（已含此前全部成交/转账结果），
     * 因此 entryDate ≤ 锚定日的增量（成交回放/手动补录/转账补记）会双计持仓与现金
     * （2026-09-07 实测 −3.19 万、2026-09-09 同源复发 −1.27 万），必须防重。
     */
    private LocalDate brokerAnchorDate(String userId) {
        SnapshotAnchor a = anchorRepository.find(userId);
        LocalDate latest = a.cashImport();
        if (a.positionsReplace() != null && (latest == null || a.positionsReplace().isAfter(latest))) {
            latest = a.positionsReplace();
        }
        return latest;
    }

    /** 该日期是否已被券商快照锚定覆盖（防重复入账）。 */
    private boolean coveredByAnchor(String userId, LocalDate entryDate) {
        LocalDate anchor = brokerAnchorDate(userId);
        return anchor != null && entryDate != null && !entryDate.isAfter(anchor);
    }

    /** 记录一次持仓全量 replace 锚定（best-effort：失败告警不阻断已成功的导入，防重暂时失效可被发现）。
     *  2026-09-12：锚定日取**快照自身日期**（文件名日期，前端可传 snapshotDate）——补导几天前的快照文件时，
     *  不能把锚定日写成「今天」，否则锚定日之后、快照之前的真实成交会被误判为「已含在快照内」而丢掉持仓/现金增量。 */
    private void recordPositionsReplaceAnchor(String userId, LocalDate snapshotDate) {
        try {
            anchorRepository.updatePositionsReplace(userId,
                    snapshotDate != null ? snapshotDate : LocalDate.now());
        } catch (RuntimeException e) {
            log.error("持仓 replace 已落库但快照锚定写入失败（P2-34 防重暂时失效）| userId={} | {}", userId, e.getMessage());
        }
    }

    /** 记录一次资金股份导入锚定（best-effort，同上；锚定日取快照自身日期，见 recordPositionsReplaceAnchor）。 */
    private void recordCashImportAnchor(String userId, LocalDate snapshotDate) {
        try {
            anchorRepository.updateCashImport(userId,
                    snapshotDate != null ? snapshotDate : LocalDate.now());
        } catch (RuntimeException e) {
            log.error("资金股份导入已落库但快照锚定写入失败（P2-34 防重暂时失效）| userId={} | {}", userId, e.getMessage());
        }
    }

    // ── 账实一致性闸门（2026-09-12）：把「口径塌了」变成当天可见 ──

    /**
     * 账实一致性报告（GET /trading/integrity）。
     * <p>
     * 应有持仓 = 券商快照基线（锚定日，positions replace 时记录的 holdings）+ 锚定日之后逐笔流水
     * 净增减；与当前落地持仓比对，差异即「账实不符」。同时从基线开始按时序重放流水，
     * 报出「卖超/未持有」的缺口（对应导入时 rejected 的同一件事，可重复核算，不依赖当时返回）。
     * <p>
     * 降级（诚实、不误报）：锚定未知 或 基线未记录 → note 说明「无法判定」，drift 为空。
     */
    public IntegrityReport integrity(String userId) {
        SnapshotAnchor anchor = anchorRepository.find(userId);
        if (anchor == null) anchor = SnapshotAnchor.empty();
        List<SnapshotHolding> base = anchorRepository.holdings(userId);
        boolean holdingsKnown = anchorRepository.holdingsRecorded(userId);
        AnchorStatus status = AnchorStatus.of(anchor, holdingsKnown);
        if (!anchor.known()) {
            return new IntegrityReport(status, holdingsKnown, List.of(), List.of(),
                    "券商快照锚定缺失（还没有导过「持仓股」或「资金股份查询」快照）——"
                            + "此时无法判断哪些成交已包含在券商口径内，导入近日成交会被拒绝重放（可改用「仅补流水」）");
        }
        if (!holdingsKnown) {
            return new IntegrityReport(status, false, List.of(), List.of(),
                    "快照持仓基线未记录（锚定日 " + status.anchorDate() + " 的 replace 导入未带基线，"
                            + "或基线文件缺失）——对账无法判定；重导一次「持仓股」快照即可建立基线");
        }
        LocalDate anchorDate = status.anchorDate();
        Map<String, Integer> baseQty = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (SnapshotHolding h : base) {
            baseQty.merge(h.symbol(), h.quantity(), Integer::sum);
            names.put(h.symbol(), h.name());
        }
        // 锚定日之后的流水（含被拒绝入账但已落流水的那些）：按 symbol 聚合净增减 + 逐笔缺口
        List<TradeRecord> after = tradingHistoryRepository.findAll(userId).stream()
                .filter(t -> t.volume() > 0 && t.entryDate() != null && t.entryDate().isAfter(anchorDate))
                .sorted(java.util.Comparator.comparing(TradeRecord::entryDate)
                        .thenComparing(t -> t.tradeTime() != null ? t.tradeTime() : LocalTime.MIN))
                .toList();
        Map<String, Integer> delta = new LinkedHashMap<>();
        Map<String, Integer> running = new LinkedHashMap<>(baseQty);
        List<RejectedLine> gaps = new ArrayList<>();
        for (TradeRecord t : after) {
            String s = t.symbol();
            names.putIfAbsent(s, t.name());
            int signed = t.direction() == TradeDirection.BUY ? t.volume() : -t.volume();
            int held = running.getOrDefault(s, 0);
            if (t.direction() == TradeDirection.SELL && t.volume() > held) {
                // 缺口行**既不计入派生持仓、也不计入流水净增减**（否则派生值本身就是错的：
                // 会得出「应有 −800 股」这种荒谬结论）；它只以 gaps 报出，等人工核对/重导快照
                gaps.add(new RejectedLine(s, t.name(), TradeDirection.SELL, t.volume(), t.price(),
                        t.entryDate(), "重放时持仓不足（持有 " + held + " 股）——快照基线缺口或漏导买入；"
                        + "该笔未计入派生持仓"));
                continue;
            }
            delta.merge(s, signed, Integer::sum);
            running.put(s, held + signed);
        }
        Map<String, Integer> holdings = currentQuantities(userId);
        Set<String> symbols = new java.util.LinkedHashSet<>();
        symbols.addAll(baseQty.keySet());
        symbols.addAll(delta.keySet());
        symbols.addAll(holdings.keySet());
        List<DriftLine> drift = new ArrayList<>();
        for (String s : symbols) {
            Integer baseQ = baseQty.get(s);
            int d = delta.getOrDefault(s, 0);
            int derived = (baseQ != null ? baseQ : 0) + d;
            Integer have = holdings.get(s);
            int haveQ = have != null ? have : 0;
            int diff = haveQ - derived;
            if (diff == 0) continue;
            drift.add(new DriftLine(s, names.getOrDefault(s, s), baseQ, d, derived, have, diff,
                    "应有 " + derived + " 股（快照基线 " + (baseQ != null ? baseQ : 0) + " + 锚点后流水 "
                            + signed(d) + "），落地 " + haveQ + " 股，差 " + signed(diff)
                            + " 股——账实不符，请核对流水/重导券商快照"));
        }
        String note = drift.isEmpty() && gaps.isEmpty()
                ? "账实一致：派生持仓与落地持仓逐标的相符（锚定日 " + anchorDate + "）"
                : String.format("账实不符：%d 只标的持仓不一致、%d 笔回放缺口（锚定日 %s）——"
                        + "先核对逐笔流水，再决定是否重导券商快照重建口径",
                        drift.size(), gaps.size(), anchorDate);
        if (!drift.isEmpty() || !gaps.isEmpty()) {
            log.error("账实一致性自检发现不符 | userId={} | 锚定={} | 差异 {} 只 | 缺口 {} 笔 | 明细={}",
                    userId, anchorDate, drift.size(), gaps.size(), drift.stream().limit(5).toList());
        }
        return new IntegrityReport(status, true, drift, gaps, note);
    }

    /**
     * 锚点回填（PUT /trading/anchor，2026-09-12）：给「升级前已导过快照、但没有锚定文件」的存量环境
     * 一次显式自愈手段（防 fail-closed 把用户卡死）。语义是元信息修正，不改持仓/现金/流水；
     * 传 holdings 时可一并补建对账基线；日期只前进不后退。
     */
    public AnchorStatus backfillAnchor(String userId, LocalDate positionsReplace, LocalDate cashImport,
                                       List<SnapshotHolding> holdings) {
        if (positionsReplace == null && cashImport == null && (holdings == null || holdings.isEmpty())) {
            throw new TradingException("锚点回填至少要给一个日期（positionsReplace / cashImport）或持仓基线");
        }
        if (positionsReplace != null) anchorRepository.updatePositionsReplace(userId, positionsReplace);
        if (cashImport != null) anchorRepository.updateCashImport(userId, cashImport);
        if (holdings != null && !holdings.isEmpty()) anchorRepository.recordHoldings(userId, holdings);
        SnapshotAnchor after = anchorRepository.find(userId);
        if (after == null) after = SnapshotAnchor.empty();
        log.info("券商快照锚点回填 | userId={} | positionsReplace={} | cashImport={} | 基线 {} 只",
                userId, after.positionsReplace(), after.cashImport(),
                holdings != null ? holdings.size() : 0);
        return AnchorStatus.of(after, anchorRepository.holdingsRecorded(userId));
    }

    /** 锚定状态查询（GET /trading/anchor）：前端/部署自检用，不触发任何写入。 */
    public AnchorStatus anchorStatus(String userId) {
        SnapshotAnchor a = anchorRepository.find(userId);
        if (a == null) a = SnapshotAnchor.empty();
        return AnchorStatus.of(a, anchorRepository.holdingsRecorded(userId));
    }

    // ── RFC 20260909 批 1：清仓股流水自动收录（触发接线）──

    /**
     * 清仓推导触发（best-effort：清仓收录/提示失败只告警，不阻断交易主流程；
     * detector 未接线（测试/旧构造）→ 跳过）。
     */
    private void runClearanceSync(String userId, java.util.Collection<String> symbols) {
        if (clearanceDetector == null || symbols == null || symbols.isEmpty()) return;
        try {
            clearanceDetector.sync(userId, symbols);
        } catch (RuntimeException e) {
            log.error("清仓推导失败（不影响交易主流程）| userId={} | {}", userId, e.getMessage());
        }
    }

    /** 待补清仓档案提示（GET /trading/sold 响应 pendingClearances，RFC 20260909 批 1）。 */
    public List<PendingClearance> soldPendingClearances(String userId) {
        if (clearanceDetector == null) return List.of();
        try {
            return clearanceDetector.detectPending(userId);
        } catch (RuntimeException e) {
            log.warn("清仓待补档案检测失败 | userId={} | {}", userId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 记录一笔交易并更新持仓（RFC 20260816：逐笔流水 + 持仓新字段）。
     *
     * @param symbol        股票代码
     * @param name          股票名称
     * @param direction     交易方向
     * @param price         成交单价
     * @param volume        成交数量
     * @param entryDate     交易日期（可空，缺省今天；首买日持久化，加仓不覆盖）
     * @param stopLossPrice 止损位（BUY 必填；SELL 可空）
     * @param buyPoint      买点类型（BUY 必填；SELL 可空）
     * @param targetPrice   目标价（可空）
     * @param reason        交易原因/预期（可空）
     * @return 更新后的持仓列表
     */
    public List<Position> recordTrade(String userId, String symbol, String name,
                                      TradeDirection direction,
                                      BigDecimal price, int volume,
                                      LocalDate entryDate, LocalTime tradeTime,
                                      BigDecimal stopLossPrice, String buyPoint,
                                      BigDecimal targetPrice, String reason) {
        return recordTradeInternal(userId, symbol, name, direction, price, volume,
                entryDate, tradeTime, stopLossPrice, buyPoint, targetPrice, reason, null, null);
    }

    /**
     * 带券商成交编号与实扣费用的交易记录（RFC 20260825 §5 当日成交同步专用）：
     * orderId 透传流水落盘（导入幂等键）、fee 透传券商实扣（后端审查 P1-2——不丢实际手续费，
     * 与 append 补录模式同口径）。其余语义与 {@link #recordTrade} 完全一致。
     */
    public List<Position> recordTradeWithOrderId(String userId, String symbol, String name,
                                                 TradeDirection direction,
                                                 BigDecimal price, int volume,
                                                 LocalDate entryDate, LocalTime tradeTime,
                                                 BigDecimal stopLossPrice, String buyPoint,
                                                 BigDecimal targetPrice, String reason,
                                                 String orderId, BigDecimal fee) {
        return recordTradeInternal(userId, symbol, name, direction, price, volume,
                entryDate, tradeTime, stopLossPrice, buyPoint, targetPrice, reason, orderId, fee);
    }

    private List<Position> recordTradeInternal(String userId, String symbol, String name,
                                               TradeDirection direction,
                                               BigDecimal price, int volume,
                                               LocalDate entryDate, LocalTime tradeTime,
                                               BigDecimal stopLossPrice, String buyPoint,
                                               BigDecimal targetPrice, String reason,
                                               String orderId, BigDecimal fee) {
        // #147：读-改-写加每用户锁，防并发交易互相覆盖丢持仓
        synchronized (tradeLock(userId)) {
            // RFC 20260815：name 可空（web 标注"可选"），缺名时以 symbol 兜底（简单方案：symbol 即名）
            String effectiveName = (name == null || name.isBlank()) ? symbol : name;
            // RFC 20260816：入场日期缺省今天（用户可补录）
            LocalDate effectiveEntryDate = entryDate != null ? entryDate : LocalDate.now();
            // RFC 20260822：成交时刻缺省 = 落盘时刻时分（客观真实，前端可不传）
            LocalTime effectiveTradeTime = tradeTime != null
                    ? tradeTime
                    : LocalDateTime.now().toLocalTime();

            // P2-交易34 治本（2026-09-09）：成交日期 ≤ 券商快照锚定日 = 该笔已包含在快照内
            // （持仓数量/成本与现金均已是券商口径），手动/确认再录入会双计持仓与现金 → 拒绝 + 指路。
            // 正确姿势：白天先记/先导成交，收盘后最后做 replace+资金股份锚定；或锚定后导历史成交（只补流水）。
            if (coveredByAnchor(userId, effectiveEntryDate)) {
                throw new TradingException(String.format(
                        "成交日期 %s 已包含在 %s 的券商快照中（持仓/现金已按快照校准）——重复录入会双计账目；"
                                + "如需补流水请用「历史成交导入」（只记账不改账），确需修正持仓/现金请重导通达信持仓或资金股份快照",
                        effectiveEntryDate, brokerAnchorDate(userId)));
            }

            // RFC 20260909 批 1：本笔成交后卖光的 symbol 收集（供锁内清仓推导触发）
            List<String> clearedSymbols = new java.util.ArrayList<>();
            List<Position> currentPositions = new ArrayList<>(positionRepository.findAll(userId));
            boolean found = false;

            for (int i = 0; i < currentPositions.size(); i++) {
                Position p = currentPositions.get(i);
                if (p.symbol().equals(symbol)) {
                    // #147：卖出数量超过持仓 → 明确报错，防静默清仓失真
                    if (direction == TradeDirection.SELL && volume > p.quantity()) {
                        throw new TradingException(
                                "卖出数量超过持仓: " + symbol + "（持有 " + p.quantity() + " 股）");
                    }
                    if (direction == TradeDirection.SELL && volume >= p.quantity()) {
                        // 本次卖出即清仓（剩余 ≤ 0）→ 交清仓推导自动收录（RFC 20260909 批 1）
                        clearedSymbols.add(symbol);
                    }
                    Position updated = updatePosition(p.symbol(), p, direction, price, volume,
                            effectiveEntryDate, stopLossPrice, buyPoint);
                    currentPositions.set(i, updated);
                    found = true;
                    break;
                }
            }

            // #147：SELL 未持有 symbol 不再是静默 no-op，明确报错防数据静默丢失
            if (!found && direction == TradeDirection.SELL) {
                throw new TradingException("未持有 " + symbol + "，无法卖出");
            }

            if (!found && direction == TradeDirection.BUY) {
                // 首次买入：新建持仓（2026-08-16 手续费：avgCost = 摊薄成本价含佣金/过户费）
                BigDecimal unit = CommissionCalculator.unitCost(symbol, price, volume);
                Position newPos = new Position(symbol, effectiveName, volume, unit, price, LocalDateTime.now(),
                        effectiveEntryDate, stopLossPrice, buyPoint, null);
                currentPositions.add(newPos);
            }

            // 清仓后的 0 持仓行不落盘（findAll 读取时本就过滤，保持文件干净）
            currentPositions.removeIf(p -> p.quantity() <= 0);

            positionRepository.saveAll(userId, currentPositions);

            // P1-交易2（2026-08-17）：买卖本质是现金↔市值转移，总资产只变手续费。
            // 旧实现只动现金不动市值 → BUY 少计成交额、SELL 多计成交额（账户卡 15:05 前账目错误）。
            // 修：现金 ± 成交额（含费），市值 ∓ 价×量，总资产 = 现金 + 市值（不变式）。
            BigDecimal tradeCashDelta = direction == TradeDirection.BUY
                    ? CommissionCalculator.buyCost(symbol, price, volume).negate()
                    : CommissionCalculator.sellProceeds(symbol, price, volume);
            BigDecimal tradeValueDelta = direction == TradeDirection.BUY
                    ? price.multiply(BigDecimal.valueOf(volume))
                    : price.multiply(BigDecimal.valueOf(volume)).negate();
            try {
                accountSnapshotRepository.update(userId, current -> current.map(c -> {
                    BigDecimal newCash = c.cash().add(tradeCashDelta);
                    BigDecimal newMarketValue = c.marketValue().add(tradeValueDelta);
                    return new AccountSnapshot(
                            newCash.add(newMarketValue), // 总资产 = 现金 + 市值（只差手续费）
                            newCash,
                            c.available().add(tradeCashDelta),
                            c.withdrawable().add(tradeCashDelta),
                            newMarketValue, c.pnl(), c.todayPnl(),
                            c.principal(), c.snapshotDate());
                }).orElse(null)); // P0-2：无快照（首次交易未导入资金）不初始化，保持既有语义
            } catch (RuntimeException e) {
                // B6-4（2026-08-23，P1-交易11）：账目快照写失败——持仓/流水已落库（跨文件无原子回滚），
                // 明确告警不静默（用户看到的账目可能滞后于持仓），交易本身不中断
                log.error("交易已落库但账户快照更新失败——账目未落盘 | userId={} | {} {} {}股@{} | {}",
                        userId, direction, symbol, volume, price, e.getMessage());
            }

            // RFC 20260815 §6：当日成交（entryDate=今天）同步写一条 domain=trading 记录
            // （时间线事实 + Feed 事件卡 + 复盘当日上下文）。2026-09-07 用户拍板口径：
            // 非当日成交（历史成交导入/补录 sync 回放等批量回填）不写记录——一次导几十笔历史
            // 若逐笔进 Feed/时间线会刷屏，且复盘卡点已改「当日真实成交」口径（2026-08-26），
            // 不再依赖记录关键词触发复盘。位置在 saveAll 成功之后：recordTrade 失败路径不会
            // 留下记录；窗口内同标题（重试）不重复写（幂等）。返回时间线 Record ID 作为流水 sourceRecordId。
            String recordId = effectiveEntryDate.isEqual(LocalDate.now())
                    ? writeTradingRecord(userId, direction, effectiveName, symbol, price, volume)
                    : null;

            // RFC 20260816 §2.1：逐笔流水真相源（BUY/SELL 都写）。best-effort：
            // 持仓已落库，流水写入失败不阻塞交易本身（与 writeTradingRecord 同口径），只告警。
            appendTradeRecord(userId, symbol, effectiveName, direction, price, volume,
                    effectiveEntryDate, effectiveTradeTime, stopLossPrice, buyPoint, targetPrice, reason,
                    recordId, orderId, fee);

            log.info("交易已记录 | {} {} {}股@{}元 | 持仓数={} | entryDate={} | 止损={}",
                    direction, symbol, volume, price, currentPositions.size(),
                    effectiveEntryDate, stopLossPrice);

            // RFC 20260909 批 1：卖光 symbol → 清仓推导（flow 自动收录 / pending 提示），best-effort
            runClearanceSync(userId, clearedSymbols);

            return currentPositions;
        }
    }

    /**
     * 当日成交成功后写 domain=trading 记录（标题如「买入 京东方A 1000股@5.20」）。
     * <p>
     * 目的：当日成交进 Feed 事件卡/时间线/记忆（用户可见「今天买卖了啥」）；
     * 复盘当日上下文（generateReview 汇总当日记录）。2026-09-07 起只对「当日」成交调用——
     * 历史成交导入/补录等非当日批量回填不写记录，防聊天流被逐笔刷屏。
     * 附加动作 best-effort：记录写入失败不阻塞交易本身（持仓已落库），只告警。
     * 幂等：5 分钟窗口内存在同标题记录（重试）→ 跳过，防重复进时间线。
     *
     * @return 时间线 Record ID（写入成功）；重复/失败返回 null
     */
    private String writeTradingRecord(String userId, TradeDirection direction,
                                      String name, String symbol,
                                      BigDecimal price, int volume) {
        try {
            String directionLabel = direction == TradeDirection.BUY ? "买入" : "卖出";
            String title = "%s %s %d股@%s".formatted(directionLabel, name, volume, price.toPlainString());

            LocalDateTime cutoff = LocalDateTime.now().minus(RECORD_DEDUP_WINDOW);
            List<ContentRecord> existing = recordRepository.findAll(userId);
            boolean duplicated = existing != null && existing.stream()
                    .anyMatch(r -> title.equals(r.title())
                            && r.createdAt() != null && r.createdAt().isAfter(cutoff));
            if (duplicated) {
                log.debug("交易记录已存在（窗口内重试），跳过写记录 | title={}", title);
                return null;
            }

            String content = "%s %s（%s）%d股@%s，成交金额 %s 元".formatted(
                    directionLabel, name, symbol, volume, price.toPlainString(),
                    price.multiply(BigDecimal.valueOf(volume)).setScale(2).toPlainString());
            ContentRecord record = new ContentRecord(
                    RecordFileRepository.generateId(), "trade", "auto_collect",
                    title, content, List.of("trading", "交易"), LocalDateTime.now(),
                    null, null, "trading");
            recordRepository.save(userId, record);
            log.info("交易记录已写入时间线 | id={} | title={}", record.id(), title);
            return record.id();
        } catch (Exception e) {
            log.warn("交易记录写入失败（不影响交易落库）| symbol={} | {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * 逐笔流水落盘（RFC 20260816 §2.1）：BUY/SELL 都写 {@code data/{userId}/trading/trades/{yyyy-MM}.json}。
     * <p>
     * best-effort：流水写入失败不阻塞交易本身（持仓已落库），只告警——与 writeTradingRecord 同口径。
     */
    private void appendTradeRecord(String userId, String symbol, String name, TradeDirection direction,
                                   BigDecimal price, int volume, LocalDate entryDate, LocalTime tradeTime,
                                   BigDecimal stopLossPrice, String buyPoint,
                                   BigDecimal targetPrice, String reason, String sourceRecordId,
                                   String orderId, BigDecimal fee) {
        try {
            TradeRecord trade = TradeRecord.of(
                    IdGenerator.monotonic("trade_"),
                    symbol, name, direction, price, volume,
                    entryDate, tradeTime, stopLossPrice, buyPoint, targetPrice, reason,
                    fee, LocalDateTime.now(), sourceRecordId, orderId);
            tradingHistoryRepository.append(userId, trade);
        } catch (Exception e) {
            log.warn("交易流水写入失败（不影响交易落库）| symbol={} | {}", symbol, e.getMessage());
        }
    }

    /**
     * 获取当前投资组合快照。
     */
    public PortfolioSnapshot getPortfolioSnapshot(String userId) {
        // 2026-08-16 修复：组合快照用行情注入后的持仓（getPositions），否则 currentPrice=存储价
        // （=成本），盈亏/市值全错（此前 totalPnl 恒 ≈0，用户"资金导入不起作用"实为此因）
        List<Position> injected = getPositions(userId);
        // S5（2026-08-17）：现金唯一真源 = account.json 的 AccountSnapshot.cash（不再读 positions.md cashBalance）
        java.math.BigDecimal cash = accountSnapshotRepository.findLatest(userId)
                .map(AccountSnapshot::cash)
                .orElse(java.math.BigDecimal.ZERO);
        return PortfolioSnapshot.of(injected, cash);
    }

    /**
     * 获取所有持仓（注入实时行情：currentPrice=现价 → 盈亏/盈亏% 展示正确；
     * 同时注入系统计算止损位 computedStopLossPrice——风险预算公式，不落盘）。
     * <p>
     * 行情拉取失败/单票无行情 → 用存储价（=成本价，盈亏 0），降级不报错；
     * 行情整体失败仍返回计算止损（computed 与行情无关）。
     */
    public List<Position> getPositions(String userId) {
        List<Position> stored = positionRepository.findAll(userId);
        if (stored.isEmpty()) return stored;
        BigDecimal principal = accountSnapshotRepository.findLatest(userId)
                .map(AccountSnapshot::principal).orElse(null);
        Map<String, MarketData> quotes = Map.of();
        try {
            quotes = marketDataSource.quote(stored.stream().map(Position::symbol).toList());
        } catch (Exception e) {
            log.warn("持仓行情注入失败，使用存储价 | userId={} | {}", userId, e.getMessage());
        }
        if (quotes.isEmpty()) {
            return stored.stream().map(p -> withComputedStopLoss(p, principal)).toList();
        }
        List<Position> result = new ArrayList<>();
        for (Position p : stored) {
            MarketData md = quotes.get(p.symbol());
            if (md != null && md.price() != null && md.price().compareTo(BigDecimal.ZERO) > 0) {
                Position withQuote = new Position(p.symbol(), p.name(), p.quantity(), p.avgCost(), md.price(),
                        p.lastUpdated(), p.entryDate(), p.stopLossPrice(), p.buyPoint(), p.role());
                result.add(withComputedStopLoss(withQuote, principal));
            } else {
                result.add(withComputedStopLoss(p, principal));
            }
        }
        return result;
    }

    /** 风险预算单笔风险比例（本金 × 1%，docs/reference/trading-risk-plan.md 校准参数表）。 */
    private static final BigDecimal RISK_BUDGET_PCT = new BigDecimal("0.01");
    /** 止损距离上限（min(R÷单仓市值, 5%)——5% 与 R66 清仓阈值闭合）。 */
    private static final BigDecimal MAX_STOP_DISTANCE_PCT = new BigDecimal("0.05");

    /**
     * 系统计算止损位（风险预算公式，动态算、不落盘）：
     * R = 本金 × 1%；止损距离 = min(R ÷ 单仓市值, 5%)；止损价 = 成本 × (1 − 距离)，两位小数。
     * 本金缺失/数量成本异常 → null（无据可算，判定走人工止损或跳过）。
     */
    private Position withComputedStopLoss(Position p, BigDecimal principal) {
        BigDecimal computed = null;
        if (principal != null && principal.signum() > 0
                && p.quantity() > 0 && p.avgCost() != null && p.avgCost().signum() > 0) {
            BigDecimal marketValue = p.avgCost().multiply(BigDecimal.valueOf(p.quantity()));
            BigDecimal distance = principal.multiply(RISK_BUDGET_PCT)
                    .divide(marketValue, 4, java.math.RoundingMode.HALF_UP);
            if (distance.compareTo(MAX_STOP_DISTANCE_PCT) > 0) {
                distance = MAX_STOP_DISTANCE_PCT;
            }
            computed = p.avgCost().multiply(BigDecimal.ONE.subtract(distance))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
        }
        return new Position(p.symbol(), p.name(), p.quantity(), p.avgCost(), p.currentPrice(),
                p.lastUpdated(), p.entryDate(), p.stopLossPrice(), p.buyPoint(), p.role(), computed);
    }

    /**
     * 一键按流水重建持仓（2026-08-25 用户场景：导入历史成交后持仓快照过期，
     * 中电电机已清仓但快照残留 1000 股被当初始底仓）。
     * <p>
     * 语义：以流水为准（结合 INIT 兜底）重放每个 symbol 的开放批次 → 覆盖 positions：
     * <ul>
     *   <li>有开放批次 → 持仓 = 批次 Σ（数量/加权成本），保留快照元信息（entryDate/止损/买点/角色）</li>
     *   <li>无开放批次（流水已全部卖出）→ 快照里该 symbol 移除（removed 报告）</li>
     *   <li>保留 INIT 底仓的 symbol → keptInitial 报告（快照早于流水的真底仓）</li>
     * </ul>
     * 与「每日导当天成交 sync 模式」互补：sync 处理增量，本端点一次性对齐存量账本。
     */
    public SyncResult syncPositionsFromFlow(String userId) {
        Map<String, List<TradingLot>> bySymbol = tradingLotService.derive(userId);
        Map<String, Position> oldHoldings = positionRepository.findAll(userId).stream()
                .collect(java.util.stream.Collectors.toMap(Position::symbol, p -> p, (a, b) -> a));
        List<Position> newPositions = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> keptInitial = new ArrayList<>();
        synchronized (tradeLock(userId)) {
            for (Map.Entry<String, List<TradingLot>> e : bySymbol.entrySet()) {
                String symbol = e.getKey();
                List<TradingLot> open = e.getValue().stream().filter(l -> !l.closed()).toList();
                if (open.isEmpty()) {
                    if (oldHoldings.containsKey(symbol)) removed.add(symbol); // 流水已清仓 → 快照残留移除
                    continue;
                }
                int qty = open.stream().mapToInt(TradingLot::remaining).sum();
                BigDecimal totalCost = BigDecimal.ZERO;
                for (TradingLot l : open) {
                    totalCost = totalCost.add(l.costPrice().multiply(BigDecimal.valueOf(l.remaining())));
                }
                BigDecimal cost = totalCost.divide(BigDecimal.valueOf(qty), 4, java.math.RoundingMode.HALF_UP);
                Position old = oldHoldings.get(symbol);
                String name = (open.get(0).name() != null && !open.get(0).name().isBlank())
                        ? open.get(0).name() : (old != null ? old.name() : symbol);
                BigDecimal stop = old != null ? old.stopLossPrice() : null;
                String bp = old != null ? old.buyPoint() : null;
                String role = old != null ? old.role() : null;
                LocalDate entryDate = old != null ? old.entryDate() : null;
                if (open.stream().anyMatch(TradingLot::initial)) keptInitial.add(symbol);
                newPositions.add(new Position(symbol, name, qty, cost, cost, LocalDateTime.now(),
                        entryDate, stop, bp, role));
            }
            positionRepository.saveAll(userId, newPositions);
        }
        // RFC 20260909 批 1：一键重建移除的流水已清仓残留 → 清仓推导自动收录，best-effort
        runClearanceSync(userId, removed);
        log.info("一键同步持仓 | userId={} | 持仓 {} 只 | 移除已清仓残留 {} | 保留底仓 {}",
                userId, newPositions.size(), removed, keptInitial);
        return new SyncResult(newPositions.size(), removed, keptInitial);
    }

    /**
     * 获取交易逐笔流水（RFC 20260816：web 交易历史）。
     * 可按日期范围过滤（from/to 均为 null 时返回全部）。
     */
    public List<TradeRecord> getTradeHistory(String userId, java.time.LocalDate from, java.time.LocalDate to) {
        List<TradeRecord> all = tradingHistoryRepository.findAll(userId);
        if ((from == null) && (to == null)) return all;
        return all.stream()
                .filter(tr -> {
                    java.time.LocalDate d = tr.entryDate() != null ? tr.entryDate()
                            : (tr.timestamp() != null ? tr.timestamp().toLocalDate() : null);
                    if (d == null) return false;
                    if (from != null && d.isBefore(from)) return false;
                    if (to != null && d.isAfter(to)) return false;
                    return true;
                })
                .toList();
    }

    /**
     * 补写单笔流水成交编号/手续费（P2-交易36 治本，2026-09-09）：截图入账/手动确认成交落库后
     * 缺 orderId/fee → 按 tradeId 对已落库流水补填（PUT /trades/{tradeId}/meta）。
     * <p>简单委托 history repo（含 per-user 锁——与 recordTrade/import 等流水写路径同锁，
     * 防止补填读-改-写与并发 append 互相覆盖）；只覆盖非空新值。
     *
     * @return 实际更新笔数（0 = 找不到该 tradeId 或无可写新值）
     */
    public int updateTradeMeta(String userId, String tradeId, String orderId, BigDecimal fee) {
        synchronized (tradeLock(userId)) { // #147：与流水写路径同 per-user 锁
            int updated = tradingHistoryRepository.updateTradeMeta(userId, tradeId, orderId, fee);
            log.info("交易流水补成交元信息 | userId={} | tradeId={} | orderId={} fee={} | {}",
                    userId, tradeId,
                    orderId != null && !orderId.isBlank() ? orderId : "（不改）",
                    fee != null ? fee : "（不改）",
                    updated > 0 ? "已更新" : "未命中");
            return updated;
        }
    }

    /**
     * 当日交易复盘聚合（RFC 20260822，纯客观数据）：指定日期成交的时段分桶/买卖分布/节奏。
     * <p>
     * 时段口径（2026-08-22 用户确认）：早盘 09:30-11:30 / 午盘 13:00-14:30 / 尾盘 14:30-15:00。
     * tradeTime 为 null 的历史流水：计入 count/金额，不计入 sessions（无时间不误判时段）。
     */
    public DailyTradeSummary getDailyTradeSummary(String userId, java.time.LocalDate date) {
        List<TradeRecord> dayTrades = tradingHistoryRepository.findAll(userId).stream()
                .filter(tr -> {
                    java.time.LocalDate d = tr.entryDate() != null ? tr.entryDate()
                            : (tr.timestamp() != null ? tr.timestamp().toLocalDate() : null);
                    return date.equals(d);
                })
                .toList();
        int buyCount = 0, sellCount = 0;
        double buyAmount = 0, sellAmount = 0;
        java.time.LocalTime first = null, last = null;
        List<DailySession> sessions = new ArrayList<>();
        // 三个时段桶（早盘/午盘/尾盘）
        List<int[]> buckets = List.of(
                new int[]{9, 30, 11, 30},
                new int[]{13, 0, 14, 30},
                new int[]{14, 30, 15, 0});
        String[] names = {"早盘", "午盘", "尾盘"};
        int[] counts = new int[3];
        for (TradeRecord tr : dayTrades) {
            boolean buy = tr.direction() == TradeDirection.BUY;
            double amt = tr.amount() != null ? tr.amount().doubleValue() : 0;
            if (buy) { buyCount++; buyAmount += amt; } else { sellCount++; sellAmount += amt; }
            java.time.LocalTime t = tr.tradeTime();
            if (t != null) {
                if (first == null || t.isBefore(first)) first = t;
                if (last == null || t.isAfter(last)) last = t;
                for (int i = 0; i < buckets.size(); i++) {
                    int[] b = buckets.get(i);
                    java.time.LocalTime start = java.time.LocalTime.of(b[0], b[1]);
                    java.time.LocalTime end = java.time.LocalTime.of(b[2], b[3]);
                    if (!t.isBefore(start) && t.isBefore(end)) counts[i]++;
                }
            }
        }
        for (int i = 0; i < names.length; i++) {
            sessions.add(new DailySession(names[i],
                    "%02d:%02d-%02d:%02d".formatted(buckets.get(i)[0], buckets.get(i)[1],
                            buckets.get(i)[2], buckets.get(i)[3]),
                    counts[i]));
        }
        return new DailyTradeSummary(date.toString(), dayTrades.size(), buyCount, sellCount,
                round2(buyAmount), round2(sellAmount), sessions, first, last);
    }

    private static double round2(double v) {
        return java.math.BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    /** 当日复盘聚合结果（RFC 20260822，纯客观数字）。 */
    public record DailyTradeSummary(
            String date,
            int count,
            int buyCount,
            int sellCount,
            double buyAmount,
            double sellAmount,
            List<DailySession> sessions,
            java.time.LocalTime firstTradeTime,
            java.time.LocalTime lastTradeTime
    ) {}

    /** 时段桶：名称 / 时间范围文案 / 笔数。 */
    public record DailySession(String name, String range, int count) {}

    /**
     * 按股票代码查询名称（GET /trading/lookup，代码输入带出名称 + 二次确认）。
     * <p>
     * 走行情数据源（腾讯）单码查询；失败/无结果返回 null（前端让用户手填或留空）。
     */
    public String lookupName(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        try {
            Map<String, MarketData> quotes = marketDataSource.quote(List.of(symbol));
            MarketData md = quotes.get(symbol);
            if (md != null && md.name() != null && !md.name().isBlank()) {
                return md.name();
            }
        } catch (Exception e) {
            log.warn("代码查名失败 | symbol={} | {}", symbol, e.getMessage());
        }
        return null;
    }

    /**
     * 持仓初始化导入（通达信导出 → 持仓快照，RFC 20260816 用户需求）。
     * <p>
     * 按 symbol upsert（已存在更新数量/成本，不存在新增）；name 缺失时用行情补全；
     * 返回导入统计（导入数 + 未设止损列表——R68 提示补设，建议引擎/推送才按纪律工作）。
     * <p>
     * 全量覆盖（2026-08-18 确认批次）：{@code replace=true} 时「以文件为准」——
     * 导入后移除文件里不存在的持仓（含 0 股残留），解决 upsert 无删除语义导致的漂移；
     * 通达信持仓导出是当日券商口径快照，与资金股份查询配套使用。
     *
     * @param items   导入项（代码/名称/数量/成本 + 可选止损/买点/角色/入场日期）
     * @param replace true = 全量覆盖（文件为准，缺失删除）；false = upsert（默认）
     */
    public PositionImportResult importPositions(String userId, List<PositionImportItem> items, boolean replace) {
        return importPositions(userId, items, replace, null);
    }

    /**
     * 持仓全量/增量导入（2026-09-12 账实一致性批加 {@code snapshotDate}）：
     * {@code replace=true} 时按 {@code snapshotDate}（通达信「持仓股」文件名里的日期）记锚定日 +
     * 快照持仓基线；为 null 时退回导入日（旧行为，兼容既有前端与测试）。
     */
    public PositionImportResult importPositions(String userId, List<PositionImportItem> items, boolean replace,
                                                LocalDate snapshotDate) {
        if (items == null || items.isEmpty()) {
            return new PositionImportResult(0, List.of());
        }
        synchronized (tradeLock(userId)) {
            List<Position> current = new ArrayList<>(positionRepository.findAll(userId));
            List<String> missingStopLoss = new ArrayList<>();
            int imported = 0;
            Set<String> importedSymbols = new java.util.HashSet<>();

            for (PositionImportItem item : items) {
                String symbol = item.symbol();
                if (symbol == null || symbol.isBlank()) continue;
                // P2-交易22（2026-08-17）：avgCost/quantity 校验——缺失/非法会让下游 NPE 500
                if (item.avgCost() == null || item.avgCost().signum() <= 0) {
                    throw new TradingException("持仓导入：股票 " + symbol + " 的成本价缺失或非法（需 > 0）");
                }
                if (item.quantity() <= 0) {
                    throw new TradingException("持仓导入：股票 " + symbol + " 的数量需 > 0");
                }
                // name 缺失 → 行情补全（P3：lookupName 只调一次，避免双网络请求）
                String name = item.name();
                if (name == null || name.isBlank()) {
                    String looked = lookupName(symbol);
                    name = looked != null ? looked : symbol;
                }

                boolean found = false;
                boolean effectiveStopLoss = item.stopLossPrice() != null; // 导入项带止损
                for (int i = 0; i < current.size(); i++) {
                    if (current.get(i).symbol().equals(symbol)) {
                        Position p = current.get(i);
                        effectiveStopLoss = item.stopLossPrice() != null || p.stopLossPrice() != null;
                        current.set(i, new Position(symbol, name, item.quantity(), item.avgCost(), item.avgCost(),
                                LocalDateTime.now(),
                                item.entryDate() != null ? item.entryDate() : p.entryDate(),
                                item.stopLossPrice() != null ? item.stopLossPrice() : p.stopLossPrice(),
                                item.buyPoint() != null ? item.buyPoint() : p.buyPoint(),
                                item.role() != null ? item.role() : p.role()));
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    current.add(new Position(symbol, name, item.quantity(), item.avgCost(), item.avgCost(),
                            LocalDateTime.now(),
                            item.entryDate() != null ? item.entryDate() : LocalDate.now(),
                            item.stopLossPrice(), item.buyPoint(), item.role()));
                }
                // P3（2026-08-17）：已存在持仓且保留旧止损 → 不进 missingStopLoss（提示失真）
                if (!effectiveStopLoss) {
                    missingStopLoss.add(symbol + " " + name);
                }
                importedSymbols.add(symbol);
                imported++;
            }

            // 全量覆盖：以文件为准——文件里没有的持仓 = 已清仓/不在券商口径，移除（含 0 股残留）
            if (replace) {
                current.removeIf(p -> !importedSymbols.contains(p.symbol()));
            }
            current.removeIf(p -> p.quantity() <= 0);
            positionRepository.saveAll(userId, current);
            // P2-交易34 治本：replace=true 是「以券商文件为准」的全量锚定——记锚定日供增量防重
            // （此后 entryDate ≤ 本日的成交 sync 回放/手动补录将转补录或拒绝，防 replace+回放双计）。
            if (replace) {
                recordPositionsReplaceAnchor(userId, snapshotDate);
                // 2026-09-12 账实一致性批：同文件记「快照当日持仓基线」——
                // 对账闸门（integrity）用基线 + 锚点之后流水净增减推应有持仓，与落地持仓比对，
                // 让「账实不符」当天可见（本次生产事故：口径塌了三天没人报警）。
                try {
                    anchorRepository.recordHoldings(userId, current.stream()
                            .map(p -> new SnapshotHolding(p.symbol(), p.name(), p.quantity()))
                            .toList());
                } catch (RuntimeException e) {
                    log.error("持仓 replace 已落库但快照持仓基线写入失败（对账降级为无法判定）| userId={} | {}",
                            userId, e.getMessage());
                }
            }
            log.info("持仓初始化导入 | userId={} | 导入 {} 只 | 未设止损 {} 只 | replace={} | 落盘 {} 只",
                    userId, imported, missingStopLoss.size(), replace, current.size());
            return new PositionImportResult(imported, missingStopLoss);
        }
    }

    /** 持仓导入项（通达信/批量，symbol 必填；name 缺失行情补全；止损/买点可选——缺失提示补设）。 */
    public record PositionImportItem(
            String symbol,
            String name,
            int quantity,
            BigDecimal avgCost,
            BigDecimal stopLossPrice,
            String buyPoint,
            String role,
            LocalDate entryDate
    ) {}

    /** 导入结果：导入数量 + 未设止损列表（R68 提示）。 */
    public record PositionImportResult(int imported, List<String> missingStopLoss) {}

    /**
     * 更新持仓元信息（web 持仓编辑，2026-08-17 补端点：role/止损位，只更新非空字段）。
     * <p>
     * 之前前端与测试都在调 PUT /positions/{symbol} 但后端从未实现（持仓编辑一直 404）。
     * targetPrice 后端 Position 无字段落盘（前端编辑目标价是既有无效功能，另记 P3）。
     *
     * @return 更新后的持仓；symbol 不存在返回 null
     */
    public Position updatePositionMeta(String userId, String symbol,
                                       String role, BigDecimal stopLossPrice) {
        // P2-6（2026-08-17 走查）：与同文件其余 RMW 一致进 tradeLock——此前裸跑与并发交易互覆持仓
        synchronized (tradeLock(userId)) {
        List<Position> current = new ArrayList<>(positionRepository.findAll(userId));
        for (int i = 0; i < current.size(); i++) {
            Position p = current.get(i);
            if (!p.symbol().equals(symbol)) continue;
            Position updated = new Position(p.symbol(), p.name(), p.quantity(), p.avgCost(),
                    p.currentPrice(), p.lastUpdated(), p.entryDate(),
                    stopLossPrice != null ? stopLossPrice : p.stopLossPrice(),
                    p.buyPoint(),
                    role != null ? role : p.role());
            current.set(i, updated);
            positionRepository.saveAll(userId, current);
            log.info("持仓元信息更新 | userId={} | {} | 止损 {} | 角色 {}",
                    userId, symbol, updated.stopLossPrice(), updated.role());
            return updated;
        }
        return null;
        }
    }

    /**
     * 保存导入文件（上传留存 + 编码转码，2026-08-16）。
     * <p>
     * 留存：原始文件存 {@code data/{userId}/trading/imports/{yyyy-MM}/{ts}_{filename}}（UTF-8 转码后可追溯）。
     * 编码：通达信导出为 GBK——UTF-8 严格解码失败则按 GBK 转码，前端/解析器拿到 UTF-8 文本。
     *
     * @return {path, content}——content 为转码后的 UTF-8 文本（前端填充解析）
     */
    public ImportFileResult saveImportFile(String userId, String filename, byte[] bytes) {
        String safeName = filename != null ? filename.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_") : "import.txt";
        String content = decodeText(bytes);
        String monthDir = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        String ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String path = "trading/imports/" + monthDir + "/" + ts + "_" + safeName;
        positionRepository.saveImportFile(userId, path, content);
        log.info("导入文件已留存 | userId={} | path={} | {} 字节", userId, path, bytes.length);
        return new ImportFileResult(path, content);
    }

    /** 编码识别 + 转码：UTF-8 严格解码优先，失败按 GBK（通达信导出默认编码）。 */
    private String decodeText(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return new String(bytes, java.nio.charset.Charset.forName("GBK"));
        }
    }


    /** 导入文件留存结果。 */
    public record ImportFileResult(String path, String content) {}

    // ── 自选股（RFC 20260816：盯盘买点原料）──

    /** 读取自选股列表。 */
    public List<WatchlistItem> watchlistList(String userId) {
        return watchlistRepository.findAll(userId);
    }

    /** 导入自选股（通达信导出文本；以文件为准全量替换）。
     *  <p>2026-08-27 策略变更（用户拍板，覆盖+归档）：原「按 symbol 合并 upsert」→「覆盖」——
     *  自选列表 = 最后一次导入的镜像，通达信里删除的自选随之消失；导入前旧列表自动归档
     *  （{@code watchlist.json.bak-<ts>}）供回滚（当日清仓股文件误导入 → 170 只污染事故的直接诱因）。
     *  同名条目保留原 addedAt（首次加入日），仅真正新增记今天。</p>
     *  <p>格式校验：缺形态列（非自选导出，如清仓股文件）→ 解析为空 → 抛业务异常 400 + 人话提示，
     *  不再静默 no-op（REVIEW #147 风格；前端导入对话框 toast 透出）。</p>
     */
    public WatchlistImportResult watchlistImport(String userId, String content) {
        List<WatchlistItem> parsed = TradingImportParser.parseWatchlist(content);
        if (parsed.isEmpty()) {
            throw new TradingException("无法识别为自选股导出：缺少形态列（长期/中期/短期形态）——是否选错了文件（如清仓股/资金股份/历史成交导出）？");
        }
        synchronized (tradeLock(userId)) {
            List<WatchlistItem> current = new ArrayList<>(watchlistRepository.findAll(userId));
            // 覆盖前归档旧列表（撤销保险；失败不阻塞导入）
            try {
                watchlistRepository.archive(userId, LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            } catch (Exception e) {
                log.warn("自选归档失败（不阻塞导入）| userId={} | {}", userId, e.getMessage());
            }
            Map<String, LocalDate> existingAddedAt = new HashMap<>();
            for (WatchlistItem it : current) existingAddedAt.put(it.symbol(), it.addedAt());
            List<WatchlistItem> next = new ArrayList<>(parsed.size());
            for (WatchlistItem item : parsed) {
                LocalDate addedAt = existingAddedAt.getOrDefault(item.symbol(), LocalDate.now());
                next.add(new WatchlistItem(item.symbol(), item.name(), item.industry(), item.industry2(),
                        item.longForm(), item.midForm(), item.shortForm(), item.signal(), addedAt));
            }
            watchlistRepository.saveAll(userId, next);
        }
        log.info("自选股导入（覆盖）| userId={} | {} 只", userId, parsed.size());
        return new WatchlistImportResult(parsed.size());
    }

    /** 删除自选股。 */
    public boolean watchlistRemove(String userId, String symbol) {
        synchronized (tradeLock(userId)) {
            List<WatchlistItem> current = new ArrayList<>(watchlistRepository.findAll(userId));
            boolean removed = current.removeIf(it -> it.symbol().equals(symbol));
            if (removed) watchlistRepository.saveAll(userId, current);
            return removed;
        }
    }

    // ── 清仓股（RFC 20260816：复盘闭环）──

    /** 读取清仓股列表。 */
    public List<SoldTrade> soldList(String userId) {
        return soldTradeRepository.findAll(userId);
    }

    /** 导入清仓股（通达信导出文本；按 symbol upsert，保留已有 verdict/psychology）。 */
    public SoldImportResult soldImport(String userId, String content) {
        List<SoldTrade> parsed = TradingImportParser.parseSold(content);
        if (parsed.isEmpty()) return new SoldImportResult(0);
        synchronized (tradeLock(userId)) {
            List<SoldTrade> current = new ArrayList<>(soldTradeRepository.findAll(userId));
            for (SoldTrade t : parsed) {
                boolean found = false;
                for (int i = 0; i < current.size(); i++) {
                    if (current.get(i).symbol().equals(t.symbol())) {
                        SoldTrade old = current.get(i);
                        // 保留已有心理标注，刷新日期/涨幅（P3 2026-08-17：verdict 下方统一重算，
                        // 此处不再声称「保留 verdict」——确定性覆盖，避免注释误导）
                        current.set(i, new SoldTrade(t.symbol(), t.name(), t.buyDate(), t.sellDate(),
                                t.holdDays(), t.tradeCount(), t.holdPnlPct(),
                                old.verdict(), old.psychology()));
                        found = true;
                        break;
                    }
                }
                if (!found) current.add(t);
            }
            // D1（2026-08-16）：规则对照生成 verdict（R53/R66），保留已有心理
            // 第三阶段：阈值按用户规则配置（rules.yaml params；无规则 → 默认 -5%/5 天，R66/R53 语义）
            TradingRuleSettings ruleSettings = tradingRuleSettingsRepository.findByUser(userId);
            double stopLossPct = ruleSettings.soldStopLossPct();
            int shortHoldDays = ruleSettings.soldShortHoldDays();
            for (int i = 0; i < current.size(); i++) {
                SoldTrade t = current.get(i);
                String verdict = SoldTradeVerdict.compute(t.holdPnlPct(), t.holdDays(),
                        stopLossPct, shortHoldDays, "R66", "R53");
                current.set(i, new SoldTrade(t.symbol(), t.name(), t.buyDate(), t.sellDate(),
                        t.holdDays(), t.tradeCount(), t.holdPnlPct(), verdict, t.psychology()));
            }
            soldTradeRepository.saveAll(userId, current);
        }
        log.info("清仓股导入 | userId={} | {} 笔（含规则对照 verdict）", userId, parsed.size());
        return new SoldImportResult(parsed.size());
    }

    /** 补/改心理标注（用户复盘素材）。 */
    public boolean soldUpdatePsychology(String userId, String symbol, String psychology) {
        synchronized (tradeLock(userId)) {
            List<SoldTrade> current = new ArrayList<>(soldTradeRepository.findAll(userId));
            for (int i = 0; i < current.size(); i++) {
                SoldTrade t = current.get(i);
                if (t.symbol().equals(symbol)) {
                    current.set(i, new SoldTrade(t.symbol(), t.name(), t.buyDate(), t.sellDate(),
                            t.holdDays(), t.tradeCount(), t.holdPnlPct(), t.verdict(), psychology));
                    soldTradeRepository.saveAll(userId, current);
                    return true;
                }
            }
            return false;
        }
    }

    // ── 资金股份查询（cashBalance + 精确成本）──

    /** 导入资金股份查询：存账户快照（资产/可用/可取/市值/盈亏/当日盈亏）+ 更新 cashBalance + 精确成本。 */
    public CashImportResult importCashQuery(String userId, String content) {
        return importCashQuery(userId, content, null);
    }

    /**
     * 资金股份查询导入（2026-09-12 加 {@code snapshotDate}）：账户快照日期与现金锚定日都用
     * 快照自身日期（文件名日期），为 null 时退回今天（旧行为）。
     */
    public CashImportResult importCashQuery(String userId, String content, LocalDate snapshotDate) {
        LocalDate effectiveDate = snapshotDate != null ? snapshotDate : LocalDate.now();
        TradingImportParser.CashQuery q = TradingImportParser.parseCash(content);
        // 2026-08-17（P1-交易5 修复）：解析失败（首行「余额/可用/可取/参考市值/资产/盈亏」未命中）
        // 禁止落零覆盖——此前会把 account.json 资产/现金清零、cashBalance 置零且无提示（B51 检查点）
        if (!q.headerMatched()) {
            throw new TradingException("无法识别资金股份查询格式——请确认首行是「余额:… 可用:… 可取:… 参考市值:… 资产:… 盈亏:…」，且是通达信资金股份导出");
        }
        // 账户总体快照（券商口径，顶层账户卡数据源）——当日盈亏 = 明细「当日盈亏」列和。
        // P2-交易37（2026-09-09）：明细**缺「当日盈亏」列**或**明细为空（当日清仓后导出无行，
        // 三官深审 P1-2）**时不得静默清零/覆盖——保留 account 既有 todayPnl（收盘任务/随流水重算
        // 的精确值不能被「文件没给该列/文件没有该股」抹掉；文件带列且有行才以券商真源覆盖）。
        double todayPnl = q.positions().stream().mapToDouble(TradingImportParser.CashPosition::todayPnl).sum();
        boolean todayPnlFromFile = q.todayPnlColumn() && !q.positions().isEmpty();
        // P0-2（2026-08-23）：account.json 写统一走 update（per-user 锁内原子 RMW），
        // 原 save 在 tradeLock 外 → 与 recordTrade/转账/收盘更新并发互相覆盖
        // B6-4（2026-08-23，P1-交易11）：写失败上抛（不再静默）——资金导入是用户主动修正账目的动作，
        // 必须让用户知道没生效（controller → 400 人话）
        accountSnapshotRepository.update(userId, cur -> new AccountSnapshot(
                q.assets(), q.cash(), q.available(), q.withdrawable(),
                q.marketValue(), q.pnl(),
                todayPnlFromFile ? BigDecimal.valueOf(todayPnl)
                        : cur.map(AccountSnapshot::todayPnl).orElse(BigDecimal.ZERO),
                cur.map(AccountSnapshot::principal).orElse(BigDecimal.ZERO), effectiveDate));
        synchronized (tradeLock(userId)) {
            // 1. cashBalance 更新
            java.math.BigDecimal cash = q.cash();
            List<Position> positions = new ArrayList<>(positionRepository.findAll(userId));
            int updated = 0;
            // 2. 精确成本价更新（资金查询 4 位 > 持仓导出 2-3 位）
            for (Position p : positions) {
                for (TradingImportParser.CashPosition cp : q.positions()) {
                    if (cp.symbol().equals(p.symbol()) && cp.costPrice() > 0) {
                        java.math.BigDecimal precise = java.math.BigDecimal.valueOf(cp.costPrice());
                        if (precise.compareTo(p.avgCost()) != 0) {
                            positions.set(positions.indexOf(p),
                                    new Position(p.symbol(), p.name(), p.quantity(), precise, p.currentPrice(),
                                            p.lastUpdated(), p.entryDate(), p.stopLossPrice(), p.buyPoint(), p.role()));
                            updated++;
                        }
                        break;
                    }
                }
            }
            if (!positions.isEmpty()) positionRepository.saveAll(userId, positions);
            // S5（2026-08-17）：现金唯一真源 = account.json（上方已保存）——不再写 positions.md cashBalance
            // P2-交易34 治本：资金股份导入 = 现金/资产锚定日（此后 ≤ 本日的转账补记/成交回放需防重）。
            recordCashImportAnchor(userId, snapshotDate);
            log.info("资金查询导入 | userId={} | 现金={} 资产={} | 成本更新 {} 只 | 当日盈亏列={}",
                    userId, cash, q.assets(), updated, todayPnlFromFile);
            return new CashImportResult(cash, q.assets(), updated);
        }
    }

    // ── 当日盈亏精确计算（口径①，2026-09-09 用户拍板）──

    /**
     * 当日盈亏精确计算（口径①：当日已实现 + 持仓日浮动 + 当日股息/红利税）。
     * <p>
     * 口径（用户拍板，2026-09-09）：
     * <pre>当日盈亏 = Σ已实现（当日卖出净额 − 卖出对应成本 − 卖出费用）
     *         + Σ持仓日浮动（(现价 − 昨收) × 数量；当日新买入部分按 (现价 − 当日含费买入成本)）
     *         + Σ当日股息入账（+）/红利税（−）现金事件</pre>
     * <ul>
     *   <li><b>已实现</b>：卖出量先冲抵当日买入（成本 = 当日含费买入均价），超出部分按旧仓成本
     *       （持仓 avgCost；当日清仓导致持仓无行 → 回退用流水中历史买入的含费加权均价）；
     *       卖出净额 = Σ(price×volume) − Σ卖出 fee</li>
     *   <li><b>持仓日浮动</b>：当前持仓 (现价−昨收)×数量；其中「当日买入且仍持有」的数量
     *       按成本计浮动（新买入没有昨收基差）</li>
     *   <li><b>诚实降级</b>：单票缺昨收/缺成本基线 → 该票不计入并在 {@code notes} 说明
     *       （不硬给错数，沿用 B3-3 原则）；今日无任何当日成交流水 → 已实现按 0 且 notes 提示</li>
     *   <li>前提：当日成交需经系统流水（历史成交导入/手动记录/截图确认）——只有持仓表推不回当日买卖</li>
     * </ul>
     *
     * @param date 查询日（通常 = 今日）
     */
    public DailyPnlResult computeDailyPnl(String userId, LocalDate date) {
        BigDecimal pnl = BigDecimal.ZERO;
        List<String> notes = new ArrayList<>();
        List<TradeRecord> dayTrades = tradingHistoryRepository.findAll(userId).stream()
                .filter(t -> date.equals(t.entryDate()))
                .toList();
        boolean hasActivity = dayTrades.stream().anyMatch(t -> t.volume() > 0);
        if (!hasActivity) {
            notes.add("今日无成交记录——当日盈亏 = 持仓日浮动（若当天有成交请先导历史成交或手动记录后重算）");
        }
        // 1. 当日已实现：逐 symbol 聚合 + 时序判定（三官深审 2026-09-09：A 股 T+1 当日卖出必来自盘前旧仓，
        // 不得一律冲抵当日买入——卖光后买回/加减仓日会符号翻转与重叠）
        Map<String, SoldAgg> realized = new LinkedHashMap<>();
        for (TradeRecord t : dayTrades) {
            if (t.volume() <= 0) continue;
            SoldAgg a = realized.computeIfAbsent(t.symbol(), s -> new SoldAgg(t.name()));
            if (t.direction() == TradeDirection.BUY) {
                a.buyQty += t.volume();
                a.buyCost = a.buyCost.add(buyCostOf(t));
                if (t.tradeTime() != null
                        && (a.firstBuyTime == null || t.tradeTime().isBefore(a.firstBuyTime))) {
                    a.firstBuyTime = t.tradeTime();
                }
            } else {
                a.sellQty += t.volume();
                a.sellNet = a.sellNet.add(sellNetOf(t));
                if (t.tradeTime() != null && a.firstBuyTime != null
                        && t.tradeTime().isAfter(a.firstBuyTime)) {
                    // T+0 边界判定：卖出晚于当日最早买入（股票 T+1 不可能；可转债/异常数据才见）
                    a.sellAfterFirstBuy = true;
                }
            }
        }
        List<Position> positions = positionRepository.findAll(userId);
        Map<String, Position> held = positions.stream()
                .collect(java.util.stream.Collectors.toMap(Position::symbol, p -> p, (a, b) -> a));
        for (Map.Entry<String, SoldAgg> e : realized.entrySet()) {
            SoldAgg a = e.getValue();
            if (a.sellQty <= 0) continue;
            // 卖出成本：T+1 语义 → 卖出量属盘前旧仓，成本 = 盘前成本
            //（今日无买入 → 持仓 avgCost 即盘前，精确；有买入/已清仓 → 回退「当日之前」流水历史买入
            //  含费加权；仍无基线 → 持仓成本兜底并诚实附注）。仅 T+0 边界才对冲抵部分按当日买入均价
            // 并附注（A 股 T+1 账户不会出现）。
            long matchedToday = a.sellAfterFirstBuy ? Math.min(a.sellQty, a.buyQty) : 0;
            BigDecimal sellCost = BigDecimal.ZERO;
            if (matchedToday > 0 && a.buyQty > 0) {
                BigDecimal buyAvg = a.buyCost.divide(BigDecimal.valueOf(a.buyQty), 6,
                        java.math.RoundingMode.HALF_UP);
                sellCost = sellCost.add(buyAvg.multiply(BigDecimal.valueOf(matchedToday)));
                notes.add(e.getKey() + " " + a.name
                        + "：含当日先买后卖（T+0 边界），已实现成本按匹配口径近似——A 股 T+1 账户不会出现");
            }
            long fromOld = a.sellQty - matchedToday;
            if (fromOld > 0) {
                Position p = held.get(e.getKey());
                BigDecimal oldUnit = BigDecimal.ZERO;
                if (a.buyQty == 0 && p != null && p.avgCost() != null && p.avgCost().signum() > 0) {
                    oldUnit = p.avgCost(); // 今日无买入摊薄 → 当前 avgCost = 盘前成本（精确）
                } else {
                    oldUnit = historicalBuyAvgCost(userId, e.getKey(), date);
                    if (oldUnit.signum() <= 0 && p != null && p.avgCost() != null
                            && p.avgCost().signum() > 0) {
                        oldUnit = p.avgCost(); // 无盘前历史基线 → 持仓成本兜底（近似）
                        notes.add(e.getKey() + " " + a.name
                                + "：卖出旧仓缺盘前成本基线，按当前持仓成本近似（精度受当日摊薄影响）");
                    } else if (oldUnit.signum() <= 0) {
                        notes.add(e.getKey() + " " + a.name
                                + "：已清仓且无成本基线，卖出已实现暂按净额计（偏高）——请导历史成交或资金股份校准");
                    }
                }
                sellCost = sellCost.add(oldUnit.multiply(BigDecimal.valueOf(fromOld)));
            }
            pnl = pnl.add(a.sellNet.subtract(sellCost));
        }
        // 2. 持仓日浮动
        Map<String, MarketData> quotes = Map.of();
        if (!positions.isEmpty()) {
            try {
                quotes = marketDataSource.quote(positions.stream().map(Position::symbol).toList());
            } catch (Exception ex) {
                log.warn("当日盈亏：行情拉取失败 | {}", ex.getMessage());
            }
        }
        for (Position p : positions) {
            if (p.quantity() <= 0) continue;
            MarketData md = quotes.get(p.symbol());
            if (md == null || md.price() == null) {
                notes.add(p.symbol() + " " + p.name() + "：缺行情现价，当日浮动未计入");
                continue;
            }
            int todayBuy = 0;
            SoldAgg a = realized.get(p.symbol());
            if (a != null) {
                // 当日买入且收盘仍持有 = 当日买入量 − 已被卖出冲抵的当日买量（T+0 边界；T+1 下即当日买入量）
                long t0Sold = a.sellAfterFirstBuy ? Math.min(a.sellQty, a.buyQty) : 0;
                todayBuy = (int) Math.max(0, Math.min(a.buyQty, p.quantity()) - t0Sold);
            }
            BigDecimal floatPnl = BigDecimal.ZERO;
            if (todayBuy > 0 && a.buyQty > 0) {
                // 当日新买入且仍持有的部分：按当日含费买入成本计浮动（无昨收基差）
                BigDecimal buyAvg = a.buyCost.divide(BigDecimal.valueOf(a.buyQty), 6, java.math.RoundingMode.HALF_UP);
                floatPnl = floatPnl.add(md.price().subtract(buyAvg).multiply(BigDecimal.valueOf(todayBuy)));
            }
            int rest = p.quantity() - todayBuy;
            if (rest > 0) {
                if (md.yesterdayClose() == null) {
                    notes.add(p.symbol() + " " + p.name() + "：缺昨收，旧仓 " + rest + " 股日浮动未计入");
                } else {
                    floatPnl = floatPnl.add(md.price().subtract(md.yesterdayClose())
                            .multiply(BigDecimal.valueOf(rest)));
                }
            }
            pnl = pnl.add(floatPnl);
        }
        // 3. 当日股息入账（+）/红利税（−）：volume=0 的资金事件（amount 存绝对值，方向编码）
        for (TradeRecord t : dayTrades) {
            if (t.volume() != 0 || t.amount() == null) continue;
            if (t.direction() == TradeDirection.BUY) {
                pnl = pnl.add(t.amount());
            } else {
                pnl = pnl.subtract(t.amount());
            }
        }
        return new DailyPnlResult(pnl.setScale(2, java.math.RoundingMode.HALF_UP), List.copyOf(notes));
    }

    /**
     * 当日买入含费成本（三官深审 backend P2 修复，2026-09-09）：历史导入带券商实扣 fee → amount+fee；
     * 手动记录 fee=null → 用系统费率估算总成本（CommissionCalculator.buyCost，与持仓 avgCost 摊薄同口径，
     * 避免「买入成本少计佣金、卖出净额未扣费」的数元级口径不一致）。
     */
    private static BigDecimal buyCostOf(TradeRecord t) {
        if (t.fee() != null) return amountOf(t, true);
        if (t.price() == null || t.volume() <= 0) return amountOf(t, true);
        return CommissionCalculator.buyCost(t.symbol(), t.price(), t.volume());
    }

    /** 当日卖出净额（已扣费；fee=null 手动记录 → 按系统费率估算卖出回款，见 {@link #buyCostOf}）。 */
    private static BigDecimal sellNetOf(TradeRecord t) {
        if (t.fee() != null) return amountOf(t, false);
        if (t.price() == null || t.volume() <= 0) return amountOf(t, false);
        return CommissionCalculator.sellProceeds(t.symbol(), t.price(), t.volume());
    }

    /** 卖出净额 / 买入成本聚合（amount = price×volume；买入 +fee、卖出 −fee）。 */
    private static BigDecimal amountOf(TradeRecord t, boolean buy) {
        BigDecimal fee = t.fee() != null ? t.fee() : BigDecimal.ZERO;
        BigDecimal base = t.amount() != null ? t.amount() : BigDecimal.ZERO;
        return buy ? base.add(fee) : base.subtract(fee);
    }

    /** 清仓后无持仓行时，回退「当日之前」流水历史买入的含费加权均价作为卖出成本基线。 */
    private BigDecimal historicalBuyAvgCost(String userId, String symbol, LocalDate until) {
        BigDecimal costSum = BigDecimal.ZERO;
        long qty = 0;
        for (TradeRecord t : tradingHistoryRepository.findAll(userId)) {
            if (!symbol.equals(t.symbol()) || t.direction() != TradeDirection.BUY || t.volume() <= 0) continue;
            if (t.entryDate() == null || !t.entryDate().isBefore(until)) continue;
            costSum = costSum.add(buyCostOf(t));
            qty += t.volume();
        }
        return qty > 0
                ? costSum.divide(BigDecimal.valueOf(qty), 6, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
    }

    /** 当日盈亏计算结果（notes：未计入部分的人话说明，非空即非全精确）。 */
    public record DailyPnlResult(BigDecimal todayPnl, List<String> notes) {}

    /**
     * 三官深审 P1-1（2026-09-09）：当日成交流水在 15:05 后落库（截图确认/单笔记录/历史成交导入）
     * → 触发当日盈亏重算写入 account.todayPnl（只改 todayPnl，不动现金/资产/市值/快照日期）。
     * 仅当 account 快照存在才写；best-effort 失败仅告警。
     */
    public void refreshTodayPnl(String userId) {
        if (accountSnapshotRepository.findLatest(userId).isEmpty()) return;
        try {
            DailyPnlResult r = computeDailyPnl(userId, LocalDate.now());
            accountSnapshotRepository.update(userId, cur -> cur.map(c -> new AccountSnapshot(
                    c.assets(), c.cash(), c.available(), c.withdrawable(),
                    c.marketValue(), c.pnl(), r.todayPnl(), c.principal(), c.snapshotDate()))
                    .orElse(null));
            log.info("当日盈亏随成交流水重算 | userId={} | todayPnl={} | notes={}", userId, r.todayPnl(),
                    r.notes().isEmpty() ? "无" : String.join("；", r.notes()));
        } catch (RuntimeException e) {
            log.warn("当日盈亏随成交流水重算失败 | userId={} | {}", userId, e.getMessage());
        }
    }

    /** 当日单 symbol 卖出/买入聚合（可变；含 T+0 边界时序判定，三官深审 2026-09-09 修复）。 */
    private static final class SoldAgg {
        final String name;
        long buyQty;
        long sellQty;
        BigDecimal buyCost = BigDecimal.ZERO;   // 当日买入含费成本合计
        BigDecimal sellNet = BigDecimal.ZERO;   // 当日卖出净额（已扣费）
        java.time.LocalTime firstBuyTime;       // 当日最早买入成交时刻（T+0 判定用）
        boolean sellAfterFirstBuy;              // 存在卖出晚于当日最早买入（T+0 边界）

        SoldAgg(String name) {
            this.name = name;
        }
    }

    /**
     * 银证转账（2026-08-16 净投入跟踪）：转入/转出 → 更新本金（净投入）+ 现金 + 资产，
     * 追加流水。总盈亏 = 资产 - 本金：转账本身不变盈亏（转钱不算赚亏），后续行情/买卖推导。
     */
    public TransferRecord recordTransfer(String userId, String type, BigDecimal amount,
                                         LocalDate date, String note) {
        // P2-交易34 治本（2026-09-09）：转账日期 ≤ 最近资金股份快照锚定日 → 该笔现金变动已包含在
        // 快照内（2026-09-09 实测：21:23 锚定余额 2278.16 后又补记当天提现 15000 → 现金被双扣成 −12721.84），
        // 补记会重复扣现金——拒绝并指路：纯净投入修正走「设置本金」，现金以券商快照为准。
        LocalDate transferDate = date != null ? date : LocalDate.now();
        LocalDate cashAnchor = anchorRepository.find(userId).cashImport();
        if (cashAnchor != null && !transferDate.isAfter(cashAnchor)) {
            throw new TradingException(String.format(
                    "转账日期 %s 已包含在 %s 的资金股份快照中（快照余额已含这笔现金变动）——补记会重复扣现金；"
                            + "如仅需修正净投入本金，请用「设置本金」；现金请以券商资金快照为准（转账应在快照导入前记录）",
                    transferDate, cashAnchor));
        }
        TransferRecord record = new TransferRecord(IdGenerator.monotonic("transfer_"),
                type, amount, transferDate, note);
        synchronized (tradeLock(userId)) {
            // P0-2（2026-08-23）：account.json 写统一走 update（per-user 锁原子 RMW）
            AccountSnapshot updated = accountSnapshotRepository.update(userId, cur -> {
                AccountSnapshot current = cur.orElse(new AccountSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, LocalDate.now()));
                BigDecimal delta = record.isIn() ? amount : amount.negate();
                return new AccountSnapshot(
                        current.assets().add(delta),
                        current.cash().add(delta),
                        current.available().add(delta),
                        current.withdrawable().add(delta),
                        current.marketValue(),
                        current.pnl(),
                        current.todayPnl(),
                        // 净投入 += 转入 - 转出（用户确认：本金 = 净投入累计）
                        current.principal().add(delta),
                        LocalDate.now());
            });
            transferRepository.append(userId, record);
            log.info("银证转账 | userId={} | {} {} | 本金净投入 → {}",
                    userId, record.isIn() ? "转入" : "转出", amount,
                    updated != null ? updated.principal() : amount);
            return record;
        }
    }

    /** 转账流水（web 展示用）。 */
    public List<TransferRecord> transferList(String userId) {
        return transferRepository.findAll(userId);
    }

    /** 读取最近账户快照（顶层账户卡数据源）。 */
    public AccountSnapshot accountSnapshot(String userId) {
        return accountSnapshotRepository.findLatest(userId)
                .orElse(new AccountSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, null));
    }

    /**
     * 设置本金（累计净投入，2026-08-18 确认批次）。
     * <p>
     * 背景：总盈亏 = 资产 − 本金；资金股份查询导入/转账推导都不覆盖本金，新建账号 principal=0
     * → 总盈亏失真。本金是「累计净投入」的历史事实，不是当前资金变动——
     * <b>只改 principal 字段，不动现金/资产/市值</b>（转账会动现金，不能用来初始化本金）。
     */
    public AccountSnapshot setPrincipal(String userId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new TradingException("本金必须是大于 0 的金额");
        }
        synchronized (tradeLock(userId)) {
            // P0-2（2026-08-23）：account.json 写统一走 update（per-user 锁原子 RMW）
            AccountSnapshot updated = accountSnapshotRepository.update(userId, cur -> {
                AccountSnapshot current = cur.orElse(new AccountSnapshot(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, LocalDate.now()));
                return new AccountSnapshot(
                        current.assets(), current.cash(), current.available(), current.withdrawable(),
                        current.marketValue(), current.pnl(), current.todayPnl(),
                        amount, current.snapshotDate());
            });
            log.info("本金设置 | userId={} | principal → {}（总盈亏 = 资产 {} - 本金 = {}）",
                    userId, amount, updated != null ? updated.assets() : BigDecimal.ZERO,
                    updated != null ? updated.assets().subtract(amount) : BigDecimal.ZERO);
            return updated;
        }
    }

    // ── 历史成交导入（第五份文件：通达信「历史成交查询」导出，2026-08-18）──

    /**
     * 导入历史成交日志（增量补录 + 缺失字段回填）。
     * <p>
     * 把券商成交逐笔补进 {@code trades/} 流水（entryDate=成交日、fee=券商实扣、orderId=成交编号幂等），
     * 供交易历史/复盘/对账使用。设计原则（2026-08-18 确认批次 + 2026-08-23 回填批次）：
     * <ul>
     *   <li><b>不重算持仓/现金</b>——历史成交往往缺窗口前基线（本次 8/3 起、8/3 前已有持仓），
     *       回放重建算不出券商口径（摊薄成本 vs 系统加权平均实测差 3.4 倍）；
     *       持仓/成本/现金以「全量覆盖」导入（positions/import replace + imports/cash）为准</li>
     *   <li><b>幂等 + 回填</b>——按成交编号 orderId 去重，重复导入同一文件不落重复流水；
     *       已存在 orderId 且旧记录成交时间缺失时，用新文件值回填（2026-08-23，用户实测重传不更新）
     *       计入 updated；无编号按 (symbol, direction, entryDate, price, volume) 指纹去重</li>
     *   <li><b>非交易事件跳过</b>——数量 0 行（如股息红利税资金下账）不落流水，计入 nonTrades</li>
     *   <li><b>对账提示</b>——每标的返回流水净增减 vs 当前持仓，指出窗口前基线或未导入成交</li>
     * </ul>
     */
    public HistoricalTradeImportResult importHistoricalTrades(String userId, String content) {
        return importHistoricalTrades(userId, content, ImportMode.AUTO, false);
    }

    /**
     * 历史成交导入（2026-09-12 账实一致性批重写：fail-closed 锚定 + 统一幂等 + 卖超不丢数据 + 预检）。
     * <p>
     * 与旧实现的差别（本次生产事故根因，见 RFC 20260912-trading-ledger-integrity）：
     * <ol>
     *   <li><b>锚定 fail-closed</b>：锚定未知（snapshot-anchor.json 缺失/损坏/两日期皆空）而系统
     *       已有持仓或账户快照 → 存在需要改账的回放行时**拒绝导入**并指路（先导持仓/资金快照建立锚定，
     *       或显式 {@code mode=append} 只补流水）。旧实现遇到空锚定直接当「不做防重」继续重放 →
     *       把已含在券商快照内的成交重算一遍（2026-09-12 实测现金被推到 −26666.85）。</li>
     *   <li><b>幂等统一</b>：append 与 replay 用同一套判定（含「有成交编号的行也查指纹」）——
     *       跨来源同笔（截图/记录归集无编号 ↔ 券商导出有编号）合并回填，不再双落
     *       （2026-09-12 实测 4 笔重复流水，600206 一度被卖成 −600 股）。</li>
     *   <li><b>卖超不丢数据</b>：回放时 SELL 超出持仓/未持有的真实成交**仍落流水**，进
     *       {@code rejected} 行级明细（持仓/现金不动，由对账闸门提示缺口）——
     *       旧实现 try/catch 后计入「去重跳过」，3 笔真实卖出永久消失（09-03 600487 400 股、
     *       09-04 000776 600 股、09-08 000831 800 股）。</li>
     *   <li><b>预检（dryRun）</b>：只算计划不落盘（新增/合并/跳过/非交易/无法归属 + 锚定状态），
     *       供前端「先看计划再确认」——改账与花钱同等待遇。</li>
     * </ol>
     *
     * @param mode   {@link ImportMode#AUTO} 按锚定分派补录/回放；{@link ImportMode#APPEND} 全部只补流水
     * @param dryRun true = 只返回计划，不写任何文件
     */
    public HistoricalTradeImportResult importHistoricalTrades(String userId, String content,
                                                              ImportMode mode, boolean dryRun) {
        ImportMode effectiveMode = mode != null ? mode : ImportMode.AUTO;
        List<TradingImportParser.HistoricalTradeRow> rows = TradingImportParser.parseHistoricalTrades(content);
        if (rows.isEmpty()) {
            throw new TradingException("无法识别历史成交导出——请确认表头含「成交日期/证券代码/买卖标志」且为通达信历史成交查询导出");
        }
        // 2026-08-25 用户反馈：明显非股票代码（通达信占位段 79/80/81/82，如 799999「登记指定」）一律不落库，
        // 计入 nonTrades（与股息红利税同口径，前端「非交易 N」可见）
        int nonTradable = (int) rows.stream()
                .filter(r -> TradingImportParser.isNonTradableCode(r.symbol())).count();
        if (nonTradable > 0) {
            log.warn("历史成交导入跳过非交易占位代码 {} 条（非股票，不入库）| userId={} | 示例: {}",
                    nonTradable, userId,
                    rows.stream().filter(r -> TradingImportParser.isNonTradableCode(r.symbol()))
                            .map(r -> r.symbol() + " " + r.name()).distinct().toList());
            rows = rows.stream().filter(r -> !TradingImportParser.isNonTradableCode(r.symbol())).toList();
        }
        // RFC 20260825 §5：窗口内成交 → 同步（更新持仓/现金/流水 + 每日操作总结）；
        // 窗口外历史 → 补录（只补流水 + 对账提示）。对抗审查 P1-1：混合窗口拆组，不整批降级。
        LocalDate windowStart = LocalDate.now().minusDays(10);
        List<TradingImportParser.HistoricalTradeRow> recent = rows.stream()
                .filter(r -> r.entryDate() != null && !r.entryDate().isBefore(windowStart))
                .toList();
        List<TradingImportParser.HistoricalTradeRow> old = rows.stream()
                .filter(r -> r.entryDate() == null || r.entryDate().isBefore(windowStart))
                .toList();
        // P2-交易34 治本 + 2026-09-12 fail-closed：近 10 日窗口内成交按「券商快照锚定」拆两层——
        // entryDate ≤ 锚定日 → 已含在券商口径，只补流水；晚于锚定日 → 回放（快照未覆盖的增量）。
        SnapshotAnchor anchor = anchorRepository.find(userId);
        if (anchor == null) anchor = SnapshotAnchor.empty(); // mock/异常实现兜底：null 视为未知锚定（fail-closed）
        AnchorStatus anchorStatus = AnchorStatus.of(anchor, anchorRepository.holdingsRecorded(userId));
        List<TradingImportParser.HistoricalTradeRow> anchoredRecent = new ArrayList<>();
        List<TradingImportParser.HistoricalTradeRow> replayRecent = new ArrayList<>();
        for (TradingImportParser.HistoricalTradeRow r : recent) {
            (anchorStatus.known() && r.entryDate() != null && !r.entryDate().isAfter(anchorStatus.anchorDate())
                    ? anchoredRecent : replayRecent).add(r);
        }
        List<TradingImportParser.HistoricalTradeRow> appendRows = new ArrayList<>(old);
        appendRows.addAll(anchoredRecent);
        // APPEND 模式：全部按补录处理（只补流水），replay 组清空
        if (effectiveMode == ImportMode.APPEND && !replayRecent.isEmpty()) {
            appendRows.addAll(replayRecent);
            replayRecent = List.of();
        }
        // fail-closed：锚定未知 + 系统已有账目状态 + 存在需要改账的回放行 → 拒绝（不静默重放）。
        // 预检（dryRun）同样拒绝：让用户在「确认导入」前就知道这次不能导、以及两条逃生路径。
        if (!anchorStatus.known() && !replayRecent.isEmpty() && hasExistingAccountState(userId)) {
            throw new TradingException(String.format(
                    "券商快照锚定缺失（%s）：本次有 %d 笔近日成交需要回放持仓/现金，但没有锚定日就无法判断"
                            + "哪些成交已包含在券商口径内——照旧回放会把它们重复计算一遍。请先导入「持仓股」或"
                            + "「资金股份查询」快照建立锚定；若只想补逐笔流水（不动持仓/现金），用「仅补流水」模式重试",
                    "trading/snapshot-anchor.json 缺失或损坏", replayRecent.size()));
        }
        if (dryRun) {
            return dryRunPlan(userId, rows, appendRows, replayRecent, anchorStatus, effectiveMode, nonTradable);
        }
        HistoricalTradeImportResult appendResult = null;
        if (!appendRows.isEmpty()) {
            appendResult = importAppend(userId, appendRows);
        }
        if (replayRecent.isEmpty()) {
            HistoricalTradeImportResult base = appendResult != null ? appendResult
                    : new HistoricalTradeImportResult(0, 0, 0, 0, List.of(), "append", null, List.of(), anchorStatus);
            refreshTodayPnl(userId);
            return withExtras(base, base.nonTrades() + nonTradable, base.rejected(), anchorStatus);
        }
        HistoricalTradeImportResult syncResult = importSync(userId, replayRecent);
        HistoricalTradeImportResult base = appendResult != null ? appendResult
                : new HistoricalTradeImportResult(0, 0, 0, 0, syncResult.lines(), "sync", null, List.of(), anchorStatus);
        refreshTodayPnl(userId);
        List<RejectedLine> rejected = new ArrayList<>(base.rejected());
        rejected.addAll(syncResult.rejected());
        return new HistoricalTradeImportResult(
                base.imported() + syncResult.imported(),
                base.updated() + syncResult.updated(),
                base.skipped() + syncResult.skipped(),
                base.nonTrades() + syncResult.nonTrades() + nonTradable,
                syncResult.lines(), "sync", syncResult.summary(), rejected, anchorStatus);
    }

    /** 导入模式（2026-09-12）：AUTO = 按券商快照锚定分派补录/回放；APPEND = 全部只补流水（锚定缺失时的安全模式）。 */
    public enum ImportMode { AUTO, APPEND }

    /**
     * 预检计划（dryRun，2026-09-12）：不落盘地给出「这次导入会做什么」——
     * 前端先展示再让用户确认（改账与花钱同等待遇；旧实现点了就落盘，出问题才知道）。
     */
    private HistoricalTradeImportResult dryRunPlan(String userId,
                                                   List<TradingImportParser.HistoricalTradeRow> rows,
                                                   List<TradingImportParser.HistoricalTradeRow> appendRows,
                                                   List<TradingImportParser.HistoricalTradeRow> replayRows,
                                                   AnchorStatus anchorStatus, ImportMode mode, int nonTradable) {
        List<TradeRecord> existing = tradingHistoryRepository.findAll(userId);
        IntakeIndex index = buildIndex(existing);
        int fresh = 0, merged = 0, skipped = 0, nonTrades = 0;
        List<RejectedLine> wouldReject = new ArrayList<>();
        // 回放行的可归属性预演：从当前持仓出发按时间顺序模拟（与真实回放同一规则）
        Map<String, Integer> simQty = new java.util.LinkedHashMap<>();
        for (Position p : positionRepository.findAll(userId)) simQty.put(p.symbol(), p.quantity());
        List<TradingImportParser.HistoricalTradeRow> orderedReplay = new ArrayList<>(replayRows);
        orderedReplay.sort(rowOrder());
        for (TradingImportParser.HistoricalTradeRow r : appendRows) {
            if (r.volume() <= 0) { nonTrades++; continue; }
            String action = classify(index, r);
            if ("NEW".equals(action)) { index.addRecord(previewRecord(r)); fresh++; }
            else if ("MERGE".equals(action)) merged++;
            else skipped++;
        }
        for (TradingImportParser.HistoricalTradeRow r : orderedReplay) {
            if (r.volume() <= 0) { nonTrades++; continue; }
            String action = classify(index, r);
            if ("SKIP".equals(action)) { skipped++; continue; }
            if ("MERGE".equals(action)) merged++;
            String reason = replayBlockReason(simQty, r);
            if (reason != null) {
                wouldReject.add(new RejectedLine(r.symbol(), r.name(), r.direction(), r.volume(),
                        r.price(), r.entryDate(), reason));
            } else {
                simQty.merge(r.symbol(), r.direction() == TradeDirection.BUY ? r.volume() : -r.volume(),
                        Integer::sum);
                fresh++;
            }
            if (!"MERGE".equals(action)) index.addRecord(previewRecord(r));
        }
        HistoricalTradeImportResult plan = new HistoricalTradeImportResult(
                fresh, merged, skipped, nonTrades + nonTradable, List.of(),
                mode == ImportMode.APPEND ? "append" : (replayRows.isEmpty() ? "append" : "sync"),
                null, wouldReject, anchorStatus);
        log.info("历史成交导入预检（未落盘）| userId={} | 新增 {} 合并 {} 跳过 {} 非交易 {} 无法归属 {} | 锚定={} | mode={}",
                userId, fresh, merged, skipped, nonTrades + nonTradable, wouldReject.size(),
                anchorStatus.known() ? anchorStatus.anchorDate() : "缺失", mode);
        return plan;
    }

    /** 预检用的占位流水（只参与后续幂等判定，不落盘）。 */
    private TradeRecord previewRecord(TradingImportParser.HistoricalTradeRow r) {
        return TradeRecord.of("plan_" + Math.abs(r.hashCode()), r.symbol(), r.name(), r.direction(),
                r.price(), r.volume(), r.entryDate(), r.tradeTime(), null, null, null, null, r.fee(),
                LocalDateTime.now(), null, r.orderId());
    }

    /** 系统是否已有账目状态（持仓非空或账户快照存在）——fail-closed 判定用：
     *  全新用户（无持仓/无账户）从零回放不会双计，允许；已有状态才禁止盲回放。 */
    private boolean hasExistingAccountState(String userId) {
        if (!positionRepository.findAll(userId).isEmpty()) return true;
        return accountSnapshotRepository.findLatest(userId).isPresent();
    }

    /** 回放行能否归属到持仓：返回 null = 可回放（并在模拟态上扣减）；非 null = 无法归属的原因。 */
    private String replayBlockReason(Map<String, Integer> simQty, TradingImportParser.HistoricalTradeRow r) {
        if (r.direction() != TradeDirection.SELL) return null;
        int held = simQty.getOrDefault(r.symbol(), 0);
        if (held <= 0) {
            return "未持有 " + r.symbol() + "（快照基线/流水缺该标的的买入）——已落流水，未动持仓与现金";
        }
        if (r.volume() > held) {
            return "卖出 " + r.volume() + " 股超过可归属持仓 " + held + " 股"
                    + "（快照基线缺口或漏导买入）——已落流水，未动持仓与现金";
        }
        return null;
    }

    /** 复制导入结果并替换 nonTrades / rejected / anchor（2026-09-12 扩展）。 */
    private HistoricalTradeImportResult withExtras(HistoricalTradeImportResult r, int nonTrades,
                                                   List<RejectedLine> rejected, AnchorStatus anchor) {
        return new HistoricalTradeImportResult(r.imported(), r.updated(), r.skipped(),
                nonTrades, r.lines(), r.syncMode(), r.summary(), rejected, anchor);
    }

    /**
     * 回放模式（锚定日之后的增量成交）：幂等过滤 → 按成交时间排序 → 逐笔走 recordTrade 全链路
     * （持仓增减 + 现金 + 手续费 + 逐笔流水），返回对账 + 每日操作总结（含行为标注）。
     * <p>
     * 2026-09-12 账实一致性批：幂等判定与补录统一（{@link #classify}）；
     * <b>无法归属到持仓的真实卖出不再丢弃</b>——仍落流水 + 进 rejected 明细（原因人话）+
     * ERROR 日志 + 由对账闸门（GET /trading/integrity）报缺口，持仓/现金不动。
     */
    private HistoricalTradeImportResult importSync(String userId, List<TradingImportParser.HistoricalTradeRow> rows) {
        long t0 = System.nanoTime(); // 2026-08-25：导入耗时定位（锁等待/幂等/落盘分段）
        // 导入前批次快照：diff 出新增/扣减批次（每日操作总结）
        Map<String, List<TradingLot>> before = tradingLotService.derive(userId);
        int imported = 0, skipped = 0, updated = 0, nonTrades = 0;
        List<RejectedLine> rejected = new ArrayList<>();
        synchronized (tradeLock(userId)) {
            IntakeIndex index = buildIndex(tradingHistoryRepository.findAll(userId));
            List<TradingImportParser.HistoricalTradeRow> toSync = new ArrayList<>();
            for (TradingImportParser.HistoricalTradeRow r : rows) {
                if (r.volume() <= 0) {
                    // 2026-08-25 方案 A：股息类资金事件记账（入账 +现金 / 红利税 −现金，不进持仓/批次）；
                    // 其余数量 0 行（如纯股息红利税无备注识别）计入 nonTrades
                    if (TradingImportParser.isDividendEvent(r)) {
                        if (coveredByAnchor(userId, r.entryDate())) {
                            // P2-交易34 治本：股息/红利税日期 ≤ 券商快照锚定日 → 该现金变动已含在快照内，跳过防双计
                            skipped++;
                        } else {
                            applyDividendCash(userId, r);
                        }
                    } else {
                        nonTrades++;
                    }
                    continue;
                }
                String action = classify(index, r);
                if ("SKIP".equals(action)) { skipped++; continue; }
                if ("MERGE".equals(action)) {
                    updated += mergeInto(userId, index, r);
                    continue;
                }
                // 2026-09-12：把「待回放」的行也登记进索引——同文件内重复行（同编号/同指纹）必须被识别，
                // 否则会回放两次（旧实现靠 orderIds 累加防住，重写时若遗漏即退化）
                index.addRecord(previewRecord(r));
                toSync.add(r);
            }
            // LIFO 依赖时间序：按成交日期 + 成交时刻排序后逐笔处理（A 股 T+1，顺序确定）
            toSync.sort(rowOrder());
            for (TradingImportParser.HistoricalTradeRow r : toSync) {
                // 2026-09-12：回放前先判「能否归属到持仓」——不能归属的真实成交也要留痕，不再静默丢弃
                String block = replayBlockReason(currentQuantities(userId), r);
                if (block != null) {
                    ledgerOnly(userId, r);
                    rejected.add(new RejectedLine(r.symbol(), r.name(), r.direction(), r.volume(),
                            r.price(), r.entryDate(), block));
                    log.error("当日成交回放无法归属持仓（已落流水、未动持仓与现金）| userId={} | {} {} {}股@{} | {}",
                            userId, r.direction(), r.symbol(), r.volume(), r.price(), block);
                    continue;
                }
                try {
                    // 通达信成交无止损/买点列 → null；批次止损由推导层按默认 −7% 兜底（RFC 20260825）。
                    // orderId 透传流水落盘 = 幂等键；fee 透传券商实扣（后端审查 P1-2，与 append 模式同口径）。
                    recordTradeWithOrderId(userId, r.symbol(), r.name(), r.direction(), r.price(), r.volume(),
                            r.entryDate(), r.tradeTime(), null, null, null, null, r.orderId(), r.fee());
                    imported++;
                } catch (TradingException e) {
                    // 逐条失败不整批回滚（与 /trades/batch 同语义）：真实成交落流水 + 明确可见，不阻塞其余
                    ledgerOnly(userId, r);
                    rejected.add(new RejectedLine(r.symbol(), r.name(), r.direction(), r.volume(),
                            r.price(), r.entryDate(), e.getMessage() + "——已落流水，未动持仓与现金"));
                    log.error("当日成交回放单笔失败（已落流水、未动持仓与现金）| userId={} | {} {} {}股@{} | {}",
                            userId, r.direction(), r.symbol(), r.volume(), r.price(), e.getMessage());
                }
            }
        }
        List<ReconcileLine> lines = tradingLotService.reconcile(userId);
        DailyOperationSummary summary = buildDailySummary(userId, rows, before);
        log.info("当日成交回放导入 | userId={} | 回放 {} 笔 | 合并回填 {} 笔 | 去重跳过 {} | 无法归属 {} | 非交易 {} | 对账 {} 行 | 买 {} 卖 {} 新增批次 {} 扣减 {} 行为 {} | 耗时 {}ms",
                userId, imported, updated, skipped, rejected.size(), nonTrades, lines.size(),
                summary.buyCount(), summary.sellCount(), summary.newLots(), summary.deductedLots(),
                summary.behaviors().size(), (System.nanoTime() - t0) / 1_000_000);
        return new HistoricalTradeImportResult(imported, updated, skipped, nonTrades, lines, "sync", summary,
                rejected, null);
    }

    /** 当前持仓数量表（回放可归属性判定用）。 */
    private Map<String, Integer> currentQuantities(String userId) {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        for (Position p : positionRepository.findAll(userId)) m.put(p.symbol(), p.quantity());
        return m;
    }

    /** 只落流水、不动持仓与现金（2026-09-12：真实成交永不因系统状态不准而消失）。 */
    private void ledgerOnly(String userId, TradingImportParser.HistoricalTradeRow r) {
        try {
            appendTradeRecord(userId, r.symbol(), r.name(), r.direction(), r.price(), r.volume(),
                    r.entryDate(), r.tradeTime(), null, null, null, null, null, r.orderId(), r.fee());
        } catch (RuntimeException e) {
            log.error("回放流水兜底写入失败（该笔未能留痕）| userId={} | {} {} {}股 | {}",
                    userId, r.direction(), r.symbol(), r.volume(), e.getMessage());
        }
    }

    /** 补录模式（历史成交导入）：只补流水不重算持仓/现金，返回对账提示。
     *  2026-09-12：幂等判定与 sync 统一（{@link #classify}）——有成交编号的行也查指纹，
     *  跨来源同笔合并回填而不是双落（旧实现只在「无编号」分支查指纹 → 记录/截图归集过的同一笔
     *  再导就多出一行，600206 曾被重复卖出 600 股打成 −600）。 */
    private HistoricalTradeImportResult importAppend(String userId, List<TradingImportParser.HistoricalTradeRow> rows) {
        long t0 = System.nanoTime(); // 2026-08-25：导入耗时定位（含锁等待）
        int imported = 0, skipped = 0, updated = 0, nonTrades = 0;
        List<TradeRecord> toAdd = new ArrayList<>();
        synchronized (tradeLock(userId)) {
            IntakeIndex index = buildIndex(tradingHistoryRepository.findAll(userId));
            for (TradingImportParser.HistoricalTradeRow r : rows) {
                if (r.volume() <= 0) {
                    // 2026-08-25 方案 A：股息类资金事件记账（入账 +现金 / 红利税 −现金，不进持仓/批次）
                    if (TradingImportParser.isDividendEvent(r)) {
                        applyDividendCash(userId, r);
                    } else {
                        nonTrades++;
                    }
                    continue;
                }
                String action = classify(index, r);
                if ("SKIP".equals(action)) { skipped++; continue; }
                if ("MERGE".equals(action)) {
                    updated += mergeInto(userId, index, r);
                    continue;
                }
                TradeRecord trade = TradeRecord.of(
                        IdGenerator.monotonic("trade_"),
                        r.symbol(), r.name(), r.direction(), r.price(), r.volume(),
                        r.entryDate(), r.tradeTime(), null, null, null, null, r.fee(),
                        LocalDateTime.now(), null, r.orderId());
                toAdd.add(trade);
                index.addRecord(trade);
                imported++;
            }
            for (TradeRecord t : toAdd) tradingHistoryRepository.append(userId, t);
        }
        List<ReconcileLine> lines = reconcileHistorical(userId, rows);
        log.info("历史成交补录导入 | userId={} | 导入 {} 笔 | 合并回填 {} 笔 | 去重跳过 {} | 非交易 {} | 对账 {} 行 | 耗时 {}ms",
                userId, imported, updated, skipped, nonTrades, lines.size(), (System.nanoTime() - t0) / 1_000_000);
        return new HistoricalTradeImportResult(imported, updated, skipped, nonTrades, lines, "append", null, List.of(), null);
    }

    // ── 统一幂等索引（2026-09-12 账实一致性批）──

    /** 幂等索引：orderId 精确命中 + 指纹命中（含「旧行无编号」与「旧行有编号」两种情况）。 */
    private static final class IntakeIndex {
        final Map<String, TradeRecord> byOrderId = new HashMap<>();
        final Map<String, TradeRecord> byFingerprint = new HashMap<>();

        /** 同一文件内已判定的行也要进索引（同文件重复行同样要合并/跳过）。 */
        void addRecord(TradeRecord t) {
            if (t.orderId() != null && !t.orderId().isBlank()) byOrderId.putIfAbsent(t.orderId(), t);
            if (t.entryDate() != null && t.price() != null) {
                byFingerprint.putIfAbsent(key(t.symbol(), t.direction(), t.entryDate(), t.price(), t.volume()), t);
            }
        }

        static String key(String symbol, TradeDirection direction, LocalDate entryDate,
                          BigDecimal price, int volume) {
            return symbol + "|" + direction + "|" + entryDate + "|"
                    + (price != null ? price.stripTrailingZeros().toPlainString() : "") + "|" + volume;
        }
    }

    private IntakeIndex buildIndex(List<TradeRecord> all) {
        IntakeIndex idx = new IntakeIndex();
        for (TradeRecord t : all) idx.addRecord(t);
        return idx;
    }

    /**
     * 单行归类：{@code NEW}（新增流水）/ {@code MERGE}（跨来源同笔，合并回填）/ {@code SKIP}（完全重复）。
     * <p>
     * 判定顺序：orderId 命中 → 缺元信息则 MERGE，否则 SKIP；指纹命中 → 时间兼容则 MERGE（补齐编号/费用/
     * 成交时间），时间明显不同（同价同量同日的两笔真实成交）→ NEW。时间兼容规则：
     * 任一侧缺失、或旧值带纳秒（历史遗留「落盘时刻」被写进成交时间）、或相差 ≤ 1 分钟。
     */
    private String classify(IntakeIndex index, TradingImportParser.HistoricalTradeRow r) {
        if (r.volume() <= 0) return "SKIP";
        String oid = r.orderId();
        if (oid != null && !oid.isBlank()) {
            TradeRecord hit = index.byOrderId.get(oid);
            if (hit != null) {
                return needsBackfill(hit, r) ? "MERGE" : "SKIP";
            }
        }
        if (r.entryDate() == null || r.price() == null) return "NEW";
        TradeRecord fp = index.byFingerprint.get(
                IntakeIndex.key(r.symbol(), r.direction(), r.entryDate(), r.price(), r.volume()));
        if (fp == null) return "NEW";
        return timeCompatible(fp.tradeTime(), r.tradeTime()) ? "MERGE" : "NEW";
    }

    /** 旧流水是否缺「新文件能补上」的元信息（成交编号/手续费/成交时间）。 */
    private boolean needsBackfill(TradeRecord existing, TradingImportParser.HistoricalTradeRow r) {
        if (existing.orderId() == null || existing.orderId().isBlank()) {
            if (r.orderId() != null && !r.orderId().isBlank()) return true;
        }
        if (existing.fee() == null && r.fee() != null) return true;
        return existing.tradeTime() == null && r.tradeTime() != null;
    }

    /** 成交时间是否可作为同一笔（见 {@link #classify} 注释）。 */
    private static boolean timeCompatible(LocalTime existing, LocalTime incoming) {
        if (existing == null || incoming == null) return true;
        if (existing.getNano() != 0) return true; // 历史遗留：落盘时刻被写进成交时间，不可当判据
        if (existing.equals(incoming)) return true;
        return Math.abs(java.time.Duration.between(existing, incoming).getSeconds()) <= 60;
    }

    /** 合并回填：把新文件里的成交编号/手续费/成交时间补进既有流水（只补缺，不覆盖已有值）。 */
    private int mergeInto(String userId, IntakeIndex index, TradingImportParser.HistoricalTradeRow r) {
        TradeRecord existing = null;
        if (r.orderId() != null && !r.orderId().isBlank()) {
            existing = index.byOrderId.get(r.orderId());
        }
        if (existing == null && r.entryDate() != null && r.price() != null) {
            existing = index.byFingerprint.get(
                    IntakeIndex.key(r.symbol(), r.direction(), r.entryDate(), r.price(), r.volume()));
        }
        if (existing == null) return 0;
        // 同编号且已有费用 → 只可能缺成交时间：走既有回填（语义与历史行为一致）
        if (existing.orderId() != null && !existing.orderId().isBlank() && existing.fee() != null) {
            return (r.tradeTime() != null && existing.tradeTime() == null)
                    ? tradingHistoryRepository.backfillTradeTime(userId, existing.id(),
                            existing.entryDate(), r.tradeTime())
                    : 0;
        }
        int n = tradingHistoryRepository.mergeFromImport(userId, existing.id(), existing.entryDate(),
                r.orderId(), r.fee(), r.tradeTime());
        if (n > 0) {
            log.info("历史成交跨来源同笔合并回填 | userId={} | tradeId={} | {} {} {}股@{} | orderId={}",
                    userId, existing.id(), r.direction(), r.symbol(), r.volume(), r.price(),
                    r.orderId() != null ? r.orderId() : "（无）");
        }
        return n;
    }

    /** 按「成交日期 + 成交时刻」排序（回放/LIFO 依赖时间序；A 股 T+1 顺序确定）。 */
    private static java.util.Comparator<TradingImportParser.HistoricalTradeRow> rowOrder() {
        return java.util.Comparator
                .comparing((TradingImportParser.HistoricalTradeRow r) -> r.entryDate() != null ? r.entryDate() : LocalDate.MIN)
                .thenComparing(r -> r.tradeTime() != null ? r.tradeTime() : LocalTime.MIN);
    }

    /**
     * 股息类资金事件记账（2026-08-25 用户拍板方案 A）：
     * 股息入账（发生金额为正）→ 现金 +N；股息红利税（发生金额为负）→ 现金 −N。
     * 不动持仓、不进批次；落一条 volume=0 的流水（amount=发生金额，reason=源文件备注）可回溯。
     * 幂等：股息行按（symbol, entryDate, 发生金额）指纹去重，重复导入不重复记账。
     */
    private void applyDividendCash(String userId, TradingImportParser.HistoricalTradeRow r) {
        long t0 = System.nanoTime();
        BigDecimal occurred = r.occurred();
        if (occurred == null || occurred.signum() == 0) {
            log.warn("股息类事件无发生金额，跳过记账 | userId={} | {} {}", userId, r.symbol(), r.remark());
            return;
        }
        // 幂等指纹（股息行无 orderId）：symbol|entryDate|发生金额绝对值
        // （红利税为负值，流水存绝对值——统一用 abs 防 -7.5 vs 7.5 不匹配导致重复记账）
        String fp = "DIV:" + r.symbol() + "|" + r.entryDate() + "|" + occurred.abs().stripTrailingZeros();
        try {
            synchronized (tradeLock(userId)) {
                List<TradeRecord> all = tradingHistoryRepository.findAll(userId);
                if (all.stream().anyMatch(t -> fp.equals("DIV:" + t.symbol() + "|" + t.entryDate()
                        + "|" + (t.amount() != null ? t.amount() : BigDecimal.ZERO).stripTrailingZeros()))) {
                    log.debug("股息类事件已记账（幂等）| userId={} | {}", userId, fp);
                    return;
                }
                // 现金 ± 发生金额（只动现金/资产，不动本金/持仓）
                accountSnapshotRepository.update(userId, cur -> cur.map(c -> new AccountSnapshot(
                        c.assets().add(occurred),
                        c.cash().add(occurred),
                        c.available().add(occurred),
                        c.withdrawable().add(occurred),
                        c.marketValue(), c.pnl(), c.todayPnl(), c.principal(), c.snapshotDate()))
                        .orElse(null)); // 无账户快照（未导入资金）不初始化，保持既有语义
                // 落流水可回溯：direction = 入账 BUY / 税 SELL，volume 0，amount = 发生金额绝对值，reason = 源文件备注
                TradeDirection dir = occurred.signum() > 0 ? TradeDirection.BUY : TradeDirection.SELL;
                TradeRecord tr = new TradeRecord(
                        IdGenerator.monotonic("trade_"), r.symbol(), r.name(), dir,
                        BigDecimal.ZERO, 0, occurred.abs(), r.entryDate(), r.tradeTime(),
                        null, null, null, r.remark(), null, LocalDateTime.now(), null, null);
                tradingHistoryRepository.append(userId, tr);
            }
        } catch (RuntimeException e) {
            log.warn("股息类事件记账失败 | userId={} | {} | {}", userId, r.symbol(), e.getMessage());
        } finally {
            log.info("股息记账 | userId={} | {} {} 元 | 耗时 {}ms", userId, r.symbol(), r.occurred(),
                    (System.nanoTime() - t0) / 1_000_000);
        }
    }

    /** 每日操作总结（RFC 20260825 §6）：客观聚合 + 批次 diff + 行为标注——不耗 AI 秒出。 */    private DailyOperationSummary buildDailySummary(String userId, List<TradingImportParser.HistoricalTradeRow> rows,
                                                    Map<String, List<TradingLot>> before) {
        LocalDate date = rows.stream().map(TradingImportParser.HistoricalTradeRow::entryDate)
                .filter(java.util.Objects::nonNull).max(LocalDate::compareTo).orElse(LocalDate.now());
        int buyCount = 0, sellCount = 0;
        double buyAmount = 0, sellAmount = 0;
        for (TradingImportParser.HistoricalTradeRow r : rows) {
            if (r.volume() <= 0) continue;
            double amt = r.price().multiply(BigDecimal.valueOf(r.volume())).doubleValue();
            if (r.direction() == TradeDirection.BUY) { buyCount++; buyAmount += amt; }
            else { sellCount++; sellAmount += amt; }
        }
        // 批次 diff：导入后新增批次 / 被扣减批次
        Map<String, List<TradingLot>> after = tradingLotService.derive(userId);
        Set<String> beforeIds = before.values().stream().flatMap(List::stream)
                .map(TradingLot::lotId).collect(java.util.stream.Collectors.toSet());
        Set<String> afterIds = after.values().stream().flatMap(List::stream)
                .map(TradingLot::lotId).collect(java.util.stream.Collectors.toSet());
        int newLots = (int) afterIds.stream().filter(id -> !beforeIds.contains(id)).count();
        Map<String, TradingLot> beforeById = before.values().stream().flatMap(List::stream)
                .collect(java.util.stream.Collectors.toMap(TradingLot::lotId, l -> l, (a, b) -> a));
        int deductedLots = 0;
        for (List<TradingLot> lots : after.values()) {
            for (TradingLot l : lots) {
                TradingLot b = beforeById.get(l.lotId());
                if (b != null && b.remaining() > l.remaining()) deductedLots++;
            }
        }
        // P2-批次2（审查归口）：多日导入 → 每个交易日各做一次行为标注再合并（同 标的+类型+日期 去重）——
        // 原来只分析最大日期，前几天的亏损加仓/追高被漏标（10 天窗口内一次导多天成交的场景）
        List<TradingLotService.BehaviorNote> behaviors = new ArrayList<>();
        Set<String> behaviorKeys = new HashSet<>();
        for (LocalDate d : rows.stream().map(TradingImportParser.HistoricalTradeRow::entryDate)
                .filter(java.util.Objects::nonNull).distinct().sorted().toList()) {
            for (TradingLotService.BehaviorNote b : tradingLotService.analyzeBehaviors(userId, d)) {
                if (behaviorKeys.add(b.type() + "|" + b.symbol() + "|" + b.date())) {
                    behaviors.add(b);
                }
            }
        }
        return new DailyOperationSummary(date.toString(), buyCount, sellCount,
                round2(buyAmount), round2(sellAmount), newLots, deductedLots, behaviors);
    }

    /** 幂等指纹（无成交编号时）：symbol|direction|entryDate|price|volume。
     *  价格 stripTrailingZeros 归一化（坑：BigDecimal.equals 区分 scale——手动记录 12.0 vs 导入 12.00000000 必须视为同价）。 */
    private String fingerprint(String symbol, TradeDirection direction, LocalDate entryDate,
                               BigDecimal price, int volume) {
        return symbol + "|" + direction + "|" + entryDate + "|"
                + (price != null ? price.stripTrailingZeros().toPlainString() : "") + "|" + volume;
    }

    /** 对账：每标的 流水净增减 vs 当前持仓数量 → 基线缺口提示（只报告，不改数据）。 */
    private List<ReconcileLine> reconcileHistorical(String userId, List<TradingImportParser.HistoricalTradeRow> rows) {
        Map<String, ReconcileAcc> acc = new LinkedHashMap<>();
        for (TradingImportParser.HistoricalTradeRow r : rows) {
            if (r.volume() <= 0) continue;
            ReconcileAcc a = acc.computeIfAbsent(r.symbol(), k -> new ReconcileAcc(r.name()));
            a.count++;
            a.netVolume += r.direction() == TradeDirection.BUY ? r.volume() : -r.volume();
        }
        Map<String, Position> holdings = positionRepository.findAll(userId).stream()
                .collect(java.util.stream.Collectors.toMap(Position::symbol, p -> p, (a, b) -> a));
        List<ReconcileLine> lines = new ArrayList<>();
        for (Map.Entry<String, ReconcileAcc> e : acc.entrySet()) {
            ReconcileAcc a = e.getValue();
            Position h = holdings.get(e.getKey());
            String note;
            if (h == null) {
                note = "当前无持仓——已清仓或快照未含（流水净 " + signed(a.netVolume) + " 股）";
            } else if (h.quantity() == a.netVolume) {
                note = "流水净增减与持仓一致（窗口内成交完整）";
            } else {
                note = "当前持仓 " + h.quantity() + " ≠ 流水净 " + signed(a.netVolume)
                        + "——存在窗口前基线或未导入成交（以持仓快照为准）";
            }
            lines.add(new ReconcileLine(e.getKey(), a.name, a.count, a.netVolume,
                    h != null ? h.quantity() : null, note));
        }
        return lines;
    }

    private static String signed(int v) {
        return v > 0 ? "+" + v : String.valueOf(v);
    }

    /** 对账聚合（可变计数器）。 */
    private static final class ReconcileAcc {
        final String name;
        int count;
        int netVolume;

        ReconcileAcc(String name) {
            this.name = name;
        }
    }

    /** 对账行：每标的 导入笔数 / 流水净增减 / 当前持仓 / 人话提示。 */
    public record ReconcileLine(String symbol, String name, int count, int netVolume,
                                Integer holdings, String note) {}

    /**
     * 历史成交导入结果：导入笔数 / 合并回填笔数 / 去重跳过 / 非交易事件 / 对账行 /
     * 模式（sync 回放 | append 补录）/ 每日操作总结 / **无法归属明细（rejected）** / **锚定状态（anchor）**。
     * <p>
     * 2026-09-12 账实一致性批：新增 {@code rejected} 与 {@code anchor}——真实成交因系统状态不准
     * 而无法入账时必须**可见**（旧实现只写 WARN 日志、计入「去重跳过」，3 笔真实卖出就此消失）；
     * 锚定状态让「为什么这次重放/为什么不重放/为什么被拒绝」在前端有据可查。
     */
    public record HistoricalTradeImportResult(int imported, int updated, int skipped, int nonTrades,
                                              List<ReconcileLine> lines, String syncMode,
                                              DailyOperationSummary summary,
                                              List<RejectedLine> rejected, AnchorStatus anchor) {
        /** 兼容旧 7 参构造（rejected/anchor 缺省的内部中间结果）。 */
        public HistoricalTradeImportResult(int imported, int updated, int skipped, int nonTrades,
                                           List<ReconcileLine> lines, String syncMode,
                                           DailyOperationSummary summary) {
            this(imported, updated, skipped, nonTrades, lines, syncMode, summary, List.of(), null);
        }

        /** 兼容旧 5 参构造（补录模式无总结）。 */
        public HistoricalTradeImportResult(int imported, int updated, int skipped, int nonTrades,
                                           List<ReconcileLine> lines) {
            this(imported, updated, skipped, nonTrades, lines, null, null, List.of(), null);
        }
    }

    /**
     * 无法归属的成交（2026-09-12）：该笔**已落逐笔流水**，但持仓/现金未变——系统状态不足以
     * 判定它归属哪个批次（快照基线缺口 / 漏导买入 / 重复流水污染）。
     * 前端必须显示（橙色），不得再让它消失在「跳过 N 笔」里。
     */
    public record RejectedLine(String symbol, String name, TradeDirection direction, int volume,
                               BigDecimal price, LocalDate entryDate, String reason) {}

    /** 券商快照锚定状态（导入结果与对账闸门共用）：known=false → 无法判断哪些成交已含在快照内。 */
    public record AnchorStatus(LocalDate positionsReplace, LocalDate cashImport,
                               boolean known, boolean holdingsKnown) {
        static AnchorStatus of(SnapshotAnchor a, boolean holdingsKnown) {
            return new AnchorStatus(a.positionsReplace(), a.cashImport(), a.known(), holdingsKnown);
        }

        /** 生效锚定日（较晚者；未知 → null）。显式 @JsonProperty：record 默认只序列化组件，
         *  前端（与部署自检）需要一个字段名，避免各自拼 positionsReplace/cashImport 取较晚者。 */
        @com.fasterxml.jackson.annotation.JsonProperty("anchorDate")
        public LocalDate anchorDate() {
            if (positionsReplace == null) return cashImport;
            if (cashImport == null) return positionsReplace;
            return positionsReplace.isAfter(cashImport) ? positionsReplace : cashImport;
        }
    }

    /** 账实对账差异行（2026-09-12）：应有持仓（快照基线 + 锚点之后流水净增减）≠ 落地持仓。 */
    public record DriftLine(String symbol, String name, Integer snapshotQty, int ledgerDelta,
                            int derived, Integer holdings, int diff, String note) {}

    /** 账实一致性报告（GET /trading/integrity）：锚定状态 + 差异 + 重放缺口。 */
    public record IntegrityReport(AnchorStatus anchor, boolean holdingsKnown, List<DriftLine> drift,
                                  List<RejectedLine> gaps, String note) {}

    /** 每日操作总结（RFC 20260825 §6，导入/归集后秒出，不耗 AI）：
     *  买卖聚合 + 批次 diff（新增/扣减）+ 行为标注。 */
    public record DailyOperationSummary(
            String date,
            int buyCount,
            int sellCount,
            double buyAmount,
            double sellAmount,
            int newLots,
            int deductedLots,
            List<TradingLotService.BehaviorNote> behaviors
    ) {}

    /** 一键同步持仓结果（2026-08-25）：positionCount 同步后持仓数 / removed 流水已清仓的快照残留 /
     *  keptInitial 保留的初始底仓（快照早于流水的真底仓）。 */
    public record SyncResult(int positionCount, List<String> removed, List<String> keptInitial) {}

    /** 自选导入结果。 */
    public record WatchlistImportResult(int imported) {}

    /** 清仓导入结果。 */
    public record SoldImportResult(int imported) {}

    /** 资金导入结果。 */
    public record CashImportResult(java.math.BigDecimal cash, java.math.BigDecimal assets, int updatedCost) {}


    // ── 内部方法 ──

    /**
     * 更新持仓（RFC 20260816 §2.2）：
     * <ul>
     *   <li>BUY（加仓）：摊平成本；entryDate 保留首买日（不覆盖）；stopLossPrice/buyPoint 更新为最近一次 BUY</li>
     *   <li>SELL：保留 entryDate/stopLossPrice/buyPoint/role</li>
     * </ul>
     */
    private Position updatePosition(String symbol, Position current, TradeDirection direction,
                                    BigDecimal price, int volume,
                                    LocalDate entryDate, BigDecimal stopLossPrice, String buyPoint) {
        switch (direction) {
            case BUY -> {
                // 摊平成本（2026-08-16 含手续费：加买入总成本 = 价×量 + 佣金 + 过户费）
                int newQty = current.quantity() + volume;
                BigDecimal newCost = current.costValue()
                        .add(CommissionCalculator.buyCost(symbol, price, volume))
                        .divide(BigDecimal.valueOf(newQty), 4, java.math.RoundingMode.HALF_UP);
                // 加仓不覆盖首买日：已有 entryDate 保留，缺失时以本次入场日期落盘
                LocalDate effectiveEntryDate = current.entryDate() != null ? current.entryDate() : entryDate;
                return new Position(current.symbol(), current.name(), newQty, newCost, price, LocalDateTime.now(),
                        effectiveEntryDate, stopLossPrice, buyPoint, current.role());
            }
            case SELL -> {
                int newQty = current.quantity() - volume;
                if (newQty <= 0) {
                    // 清仓：返回数量为 0 的持仓，上层应该过滤（止损/买点/入场保留在流水里可回溯）
                    return new Position(current.symbol(), current.name(), 0, BigDecimal.ZERO, price, LocalDateTime.now(),
                            current.entryDate(), current.stopLossPrice(), current.buyPoint(), current.role());
                }
                return new Position(current.symbol(), current.name(), newQty, current.avgCost(), price, LocalDateTime.now(),
                        current.entryDate(), current.stopLossPrice(), current.buyPoint(), current.role());
            }
            default -> throw new IllegalArgumentException("未知交易方向: " + direction);
        }
    }
}
