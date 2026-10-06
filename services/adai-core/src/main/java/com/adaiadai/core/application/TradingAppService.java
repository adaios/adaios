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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

    /** 幂等窗口（RFC 20261003 C6，2026-10-03）：同一 {@code Idempotency-Key} 在窗口内只落一笔。
     *  10 分钟足够覆盖「网络重试 / 连点提交 / 客户端超时重发」；**进程内**（重启即清）——
     *  持久化幂等属「对账自证」批（C5），本轮刻意不引入新落盘文件。 */
    private static final Duration IDEMPOTENCY_WINDOW = Duration.ofMinutes(10);

    /** 幂等登记表上限（防无界增长；超限时按窗口机会式清理）。 */
    private static final int IDEMPOTENCY_MAX_ENTRIES = 512;

    /** 幂等登记：{@code userId|idempotencyKey → 首次落库时间}。只增不持久化（见 {@link #IDEMPOTENCY_WINDOW}）。 */
    private final java.util.Map<String, LocalDateTime> idempotencyLog = new java.util.concurrent.ConcurrentHashMap<>();

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
    /** RFC 20261003 C4（2026-10-03）：对账调整落账（可空=未接线，落账判空跳过）。 */
    private final CashAdjustmentRepository cashAdjustmentRepository;
    /** 设计 §4.3（2026-10-06）：系统侧**不可见修改日志**（纠错/重导快照/迁移留痕，fail-visible）——
     *  可空=未接线（兼容构造），判空跳过；只记系统侧，不进任何用户可见面。 */
    private final TradingAuditRepository auditRepository;

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
                             ClearanceDetector clearanceDetector,
                             CashAdjustmentRepository cashAdjustmentRepository,
                             TradingAuditRepository auditRepository) {
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
        this.cashAdjustmentRepository = cashAdjustmentRepository;
        this.auditRepository = auditRepository;
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
                tradingLotService, tradingRuleSettingsRepository, anchorRepository, null, null);
    }

    /**
     * 无对账调整仓储构造（测试/旧调用兼容，2026-10-03）：差额**不落账**（判空跳过），
     * 其余行为与主构造一致。加它是为了让既有 12 参调用点零改动。
     */
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
        this(positionRepository, recordRepository, tradingHistoryRepository, watchlistRepository,
                soldTradeRepository, accountSnapshotRepository, transferRepository, marketDataSource,
                tradingLotService, tradingRuleSettingsRepository, anchorRepository, clearanceDetector, null);
    }

    /**
     * 无修改日志仓储构造（测试/旧调用兼容，2026-10-06）：纠错照常执行（审计判空跳过），
     * 加它是为了让既有 13 参调用点零改动。
     */
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
                             ClearanceDetector clearanceDetector,
                             CashAdjustmentRepository cashAdjustmentRepository) {
        this(positionRepository, recordRepository, tradingHistoryRepository, watchlistRepository,
                soldTradeRepository, accountSnapshotRepository, transferRepository, marketDataSource,
                tradingLotService, tradingRuleSettingsRepository, anchorRepository, clearanceDetector,
                cashAdjustmentRepository, null);
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
                tradingLotService, tradingRuleSettingsRepository, noopAnchorRepository(), null, null);
    }

    /**
     * 兼容构造用的锚定仓储（测试与旧调用，**仅此二者**——生产走主构造注入的真实仓储）。
     *
     * <p>它返回一个**很久以前的锚定日**（1970-01-01）而不是「空锚定」，这样：
     * ① C7 的 fail-closed 闸门放行（兼容构造的语义是「不做锚定防重」，不该被 C7 拦死）；
     * ② `coveredByAnchor` 恒为 false（所有成交都晚于 1970）——防重仍然**不生效**，与历史行为一致。
     *
     * <p>真实环境的「锚定读不到」由 {@code TradingAnchorFileRepository} 返回 empty 表达，C7 照常拦。
     */
    private static TradingAnchorRepository noopAnchorRepository() {
        return new TradingAnchorRepository() {
            @Override
            public SnapshotAnchor find(String userId) {
                return new SnapshotAnchor(java.time.LocalDate.of(1970, 1, 1), java.time.LocalDate.of(1970, 1, 1));
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
     *  不能把锚定日写成「今天」，否则锚定日之后、快照之前的真实成交会被误判为「已含在快照内」而丢掉持仓/现金增量。
     *  2026-09-18（P0-交易59）：再对「盘前导出的当日快照」做一次归一化，见 {@link #normalizeAnchorDate}。
     *  2026-10-05（P2-交易84）：归一化的**依据**随锚定一起落盘（{@link #decideAnchor}）——
     *  显式基准日（{@code basedOn}）在先，时钟只在无据时兜底，且兜底必标无据。 */
    private AnchorDecision recordPositionsReplaceAnchor(String userId, LocalDate snapshotDate, LocalDate basedOn) {
        // 2026-09-21（P1-交易61）：同时留**文件原始日期**——归一化后与它不等 = 锚定日是推断出来的，
        // 对账闸门据此报出「锚定日当天的成交可能不在快照里」（此前只能结构自洽地报「账实一致」= 假绿）。
        AnchorDecision decision = decideAnchor(snapshotDate, LocalDate.now(), LocalTime.now(), basedOn);
        try {
            anchorRepository.updatePositionsReplace(userId, decision.anchorDate(), decision.fileDate(),
                    decision.basis());
        } catch (RuntimeException e) {
            log.error("持仓 replace 已落库但快照锚定写入失败（P2-34 防重暂时失效）| userId={} | {}", userId, e.getMessage());
        }
        return decision;
    }

    /** 记录一次资金股份导入锚定（best-effort，同上；锚定日取快照自身日期，见 recordPositionsReplaceAnchor）。 */
    private AnchorDecision recordCashImportAnchor(String userId, LocalDate snapshotDate, LocalDate basedOn) {
        AnchorDecision decision = decideAnchor(snapshotDate, LocalDate.now(), LocalTime.now(), basedOn);
        try {
            anchorRepository.updateCashImport(userId, decision.anchorDate(), decision.fileDate(),
                    decision.basis());
        } catch (RuntimeException e) {
            log.error("资金股份导入已落库但快照锚定写入失败（P2-34 防重暂时失效）| userId={} | {}", userId, e.getMessage());
        }
        return decision;
    }

    /**
     * 锚定日归一化（2026-09-18，P0-交易59）：**盘前导出的「当日」快照，数据基准是上一交易日**。
     *
     * <p>通达信「持仓股 / 资金股份查询」文件名里的日期是**导出日**，文件内容却是最近一个
     * **已收盘交易日**的状态。用户凌晨（或开盘前）导出时，导出日 = 今天、数据基准 = 上一交易日——
     * 直接拿导出日当锚定日，等于把「今天」提前锚定：当天盘中的真实成交全被
     * {@link #coveredByAnchor} 判成「已包含在快照内」而拒绝（生产实据：2026-09-18 凌晨 00:24
     * 导入持仓快照 → 锚定日 09-18 → 当天 6 笔成交 confirm 六次全拒；且 coveredByAnchor 是
     * 「≤ 锚定日」，之后每天再导快照锚定日只会更晚，这笔成交**永远补不回来**）。
     *
     * <p>规则（只动「快照日期 == 今天」这一种情况，补导历史快照一律按文件日期）：
     * <ul>
     *   <li>今天开盘（09:30）前导入 → 数据基准 = 上一交易日</li>
     *   <li>今天不是交易日（周末/节假日白天导入）→ 数据基准 = 上一交易日</li>
     *   <li>交易日盘中/盘后导入 → 数据已含当日成交，锚定日保持今天</li>
     * </ul>
     */
    private static LocalDate normalizeAnchorDate(LocalDate snapshotDate) {
        return normalizeAnchorDate(snapshotDate, LocalDate.now(), LocalTime.now());
    }

    /**
     * 锚定决策（2026-10-05，P2-交易84）：锚定日 + 文件日期 + 显式基准日 + **依据**（有据/无据）。
     *
     * <p>「有据」= 显式基准日 / 文件日期直接采用 / 休市日归一化；「无据」= 时钟推断兜底。
     * 回执与对账闸门都据它说话——**任何归一化都不许静默**。
     */
    public record AnchorDecision(LocalDate anchorDate, LocalDate fileDate, LocalDate explicitDate,
                                 AnchorBasis basis) {

        /** 是否为有据的锚定日。 */
        public boolean withEvidence() {
            return basis != null && basis.withEvidence();
        }

        /** 给了显式基准日却被判不可信（未来日期）→ 回执必须如实说明，不许静默忽略。 */
        public boolean explicitRejected() {
            return explicitDate != null && (anchorDate == null || !explicitDate.equals(anchorDate));
        }

        /** 回执人话（导入回执 / 日志直显；第一人称、无系统视角标签）。 */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            if (explicitRejected()) {
                sb.append("你指定的基准日 ").append(explicitDate)
                        .append(" 在未来，不可信，已按「没有基准日」处理——");
            }
            sb.append("锚定日 ").append(anchorDate).append("：")
                    .append(basis != null ? basis.label() : "依据未记录")
                    .append(withEvidence() ? "（有据）" : "（无据）");
            if (anchorDate != null && fileDate != null && !anchorDate.equals(fileDate)) {
                sb.append("；文件里的日期是 ").append(fileDate);
            }
            return sb.toString();
        }
    }

    /**
     * 锚定决策：把「数据基准日从哪来」显式化（2026-10-05，P2-交易84 治本）。
     *
     * <p>① <b>显式基准日在先</b>：导入方（用户/前端）说了「这份数据是哪天的」→ 直接采用，
     * 不再用导入时刻去猜（09:26 导出、09:28 导入不再被退到上一交易日——本条病根）。
     * <p>② <b>未来日期仍不可信</b>（P2-10 保护不放松）：显式基准日在今天之后 → 忽略，
     * 落回时钟兜底，且依据标 {@link AnchorBasis#CLOCK}（无据），回执说明「已忽略」。
     * <p>③ 没有显式依据 → 既有归一化（盘前/非交易日退上一交易日）+ 依据判据：
     * 文件日期当天休市 = 有据（{@link AnchorBasis#CLOSED_DAY}，b90f56a2 口径不变）；
     * 其余归一化 = 时钟推断（{@link AnchorBasis#CLOCK}，保持报警）。
     */
    static AnchorDecision decideAnchor(LocalDate fileDate, LocalDate today, LocalTime now,
                                       LocalDate explicitBasis) {
        if (explicitBasis != null && !explicitBasis.isAfter(today)) {
            return new AnchorDecision(explicitBasis, fileDate, explicitBasis, AnchorBasis.EXPLICIT);
        }
        LocalDate anchor = normalizeAnchorDate(fileDate, today, now);
        AnchorBasis basis;
        if (fileDate != null && fileDate.equals(anchor)) {
            basis = AnchorBasis.FILE_DATE;           // 没动过：文件本身就是证据
        } else if (fileDate != null && !TradingSessionPushService.isTradingDayStrict(fileDate)) {
            basis = AnchorBasis.CLOSED_DAY;          // 休市日导出 → 基准日是上一交易日，确定
        } else {
            basis = AnchorBasis.CLOCK;               // 兜底：导入时刻推断
        }
        return new AnchorDecision(anchor, fileDate, explicitBasis, basis);
    }

    /** 可测版本（包级可见）：把「今天/现在」参数化，便于单测覆盖盘前与盘后两种分支。 */
    static LocalDate normalizeAnchorDate(LocalDate snapshotDate, LocalDate today, LocalTime now) {
        // P1-2（2026-09-19 后端审查）：**null 也要走同一套归一化**——粘贴导入（web 粘贴文本，
        // 前端拿不到文件名日期）时 snapshotDate 恒为 null，原来这里直接返回 today，于是凌晨/开盘前
        // 粘贴导入的锚定日 = 今天 → 当天真实成交全部命中覆盖 → 降级为只落流水（持仓/现金不动）
        // → 持仓整日停在快照状态。正是本批要修的那起生产事故，只在"没有文件名的路径"上原样保留。
        LocalDate effective = snapshotDate != null ? snapshotDate : today;
        // 2026-09-21（P2-10）：**未来日期不可信**（文件名解析错 / 手改错）——按「没有文件日期」处理。
        // 否则锚定日被写进未来 → 之后每一笔成交都 ≤ 锚定日 → 全被判「已含在快照内」降级为只落流水
        // （静默失效；而锚定日只前进不后退，这笔状态再也退不回来）。
        if (effective.isAfter(today)) effective = today;
        if (!effective.equals(today)) return effective; // 补导历史快照：按文件日期，不调整
        boolean beforeOpen = now != null && now.isBefore(LocalTime.of(9, 30));
        if (beforeOpen || !TradingSessionPushService.isTradingDayStrict(today)) {
            LocalDate prev = previousTradingDay(today);
            if (prev != null) return prev;
        }
        return effective;
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
        List<TradeRecord> allTrades = tradingHistoryRepository.findAll(userId);
        List<TradeRecord> after = allTrades.stream()
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
        // ── 降级流水 / 基线新鲜度（2026-09-21，P1-交易61）────────────────────────────
        // 成交日 **等于锚定日** 的流水被 coveredByAnchor 判成「已含在券商快照内」→ 只落流水、不动持仓。
        // 锚定日是文件日期时这样处理是对的（快照=当日收盘后的结果）；但锚定日若是**推断**出来的
        // （文件日期 X 被归一化成 Y：盘前/非交易日导出、或粘贴导入没有文件日期），这些成交就**可能
        // 其实不在快照里**——而派生持仓与落地持仓**同源于那份快照**，结构上必然相等 →
        // 老实现对账只会报「账实一致」（**假绿**，生产实据：2026-09-18 锚定日应为 09-17 却写成 09-18，
        // 当天 6 笔成交全部降级，持仓少 2 只/多算 400 股，09-18~09-20 该端点一直报「账实一致」）。
        // 这里把它们单独列出来：不替用户断定对错，只把「基线可能是旧的」这条事实摆到台面上。
        List<DegradedLine> degraded = new ArrayList<>();
        // 归一化「**有据**」判据（2026-10-03，用户反馈「不应该提示」）：
        //   文件日期当天**休市**（周末 / 法定节假日）→ 数据基准日取「上一交易日」是**确定的**（不是猜的）。
        // 生产实据：10-01 国庆休市日导出 → 归一化到 09-30 → 当天 3 笔成交被提示「没进持仓、请重导」，
        // 而那份快照的 600206 = 800 股正好含当天买的 200 股——**数据本来就是对的**，提示纯属噪音
        // （用户原话：「我导入就是为了修正数据；我没导入，你不知道我是否操作了」）。
        // 反之若文件日期**是交易日**却被归一化（2026-09-18 真事故：应为 09-17 却写成 09-18，当天 6 笔
        // 全降级、持仓少 2 只）→ 归一化**没有依据** → 必须继续报警（drift 在该场景会假绿，这条是唯一旁路）。
        LocalDate evidenceDate = anchor.positionsFileDate() != null
                ? anchor.positionsFileDate() : anchor.cashFileDate();
        // 2026-10-05（P2-交易84）：**依据随锚定落盘**（AnchorBasis）后，判据多一条——
        // 导入时明确给出「数据基准日」的（EXPLICIT）同样是有据的，不该报警；
        // 时钟推断（CLOCK）照旧算「无据的归一化」→ 保持 ⚠️ 旁路（2026-09-18 真事故同型仍可见）。
        // 休市日判据（b90f56a2 口径）一字不改，只做 OR 扩展。
        boolean basisSaysEvidence = anchor.positionsBasisWithEvidence() || anchor.cashBasisWithEvidence();
        boolean normalizedWithEvidence = basisSaysEvidence
                || (evidenceDate != null && anchorDate != null
                    && !TradingSessionPushService.isTradingDayStrict(evidenceDate));
        // 只有「无据的归一化」才算可疑推断 → 前端据此决定要不要出横幅
        boolean anchorInferred = (anchor.positionsDateInferred() || anchor.cashDateInferred())
                && !normalizedWithEvidence;
        String fileDateText = anchor.positionsFileDate() != null ? anchor.positionsFileDate().toString()
                : (anchor.cashFileDate() != null ? anchor.cashFileDate().toString() : "未记录");
        for (TradeRecord t : allTrades) {
            if (t.volume() <= 0 || t.entryDate() == null || !t.entryDate().equals(anchorDate)) continue;
            degraded.add(new DegradedLine(t.symbol(), t.name(), t.direction(), t.volume(), t.price(),
                    t.entryDate(), anchorInferred,
                    anchorInferred
                            ? "成交日 = 锚定日 " + anchorDate + "，被按「已含在券商快照内」处理（只记流水、未进持仓）；"
                                    + "但锚定日是从文件日期 " + fileDateText + " 推断（归一化）来的，而那天本身是交易日"
                                    + " —— 若这份快照的实际基准日不是 " + anchorDate + "，本笔就不会在持仓里，"
                                    + "请核对后重导「持仓股」快照"
                            : "成交日 = 锚定日 " + anchorDate + "，已含在券商快照内（只记流水、未重复计入持仓）"));
        }

        // P2（独立审查 2026-10-03 修复）：现金侧检查必须在「账实一致」措辞定稿**之前**算出来——
        // 原实现先写「账实一致」再追加 ⚠️ 现金为负，同一句话自相矛盾。
        String cashAlert = cashAlertOf(accountSnapshot(userId));
        String note = drift.isEmpty() && gaps.isEmpty() && cashAlert == null
                ? "账实一致：派生持仓与落地持仓逐标的相符（锚定日 " + anchorDate + "）"
                : drift.isEmpty() && gaps.isEmpty()
                        ? "持仓账实一致（锚定日 " + anchorDate + "），但现金侧有异常——见下"
                        : String.format("账实不符：%d 只标的持仓不一致、%d 笔回放缺口（锚定日 %s）——"
                                + "先核对逐笔流水，再决定是否重导券商快照重建口径",
                                drift.size(), gaps.size(), anchorDate);
        // P2-交易62（2026-09-23 修）：文件日期未记录时 `positionsDateInferred()` 刻意返回 false（不诬告），
        // 但那**不等于**「确定没被推断过」——原实现只在 degraded 为空时才说这句「无法判断」，一旦有降级
        // 流水就落到 `inferred=false` 的**确定语气**（「已含在券商快照内」）＝拿不到证据却断言确定。
        // 现在把「能判定」与「不可判定」分开，两种情形各有各的话。
        // 2026-10-03（用户反馈「不应该提示」）：**锚定日内的成交不进持仓是正常语义，不提示**。
        //
        // 用户导入快照的目的就是**修正数据**——快照即真相（File First）；成交明细只是过程记录，
        // 在锚定日内不重复计入持仓是**设计使然**。「锚定日是按文件日期归一化的」是系统的实现细节，
        // 不该被翻译成「请核对后重导一次」推给用户。
        // 生产实据（2026-10-03）：10-01 国庆休市日导出 → 锚定日归一化为 09-30 → 当天 3 笔成交被判降级，
        // 横幅提示「3 笔成交没进持仓，请先导一次快照」，而那份快照的 600206 = **800 股**正好含当天买的
        // 200 股——数据本来就是对的，提示纯属噪音（用户原话：「我导入就是为了修正数据，我没导入，
        // 你不知道我是否操作了」）。
        //
        // 现在：**有据的归一化完全静默**（快照即真相，不打扰用户）；**无据的归一化照旧报警**（那可能是推错）。
        if (!degraded.isEmpty() && anchorInferred) {
            note += String.format("；⚠️ 另有 %d 笔成交只记了流水、没进持仓（成交日 = 锚定日 %s，而锚定日是按文件日期"
                    + " %s 归一化来的，但那一天本身是交易日）——这份快照若实际不是 %s 的收盘状态，"
                    + "这些成交就不会体现在持仓里，请核对后重导一次「持仓股」快照",
                    degraded.size(), anchorDate, fileDateText, anchorDate);
        }
        if (!drift.isEmpty() || !gaps.isEmpty()) {
            log.error("账实一致性自检发现不符 | userId={} | 锚定={} | 差异 {} 只 | 缺口 {} 笔 | 明细={}",
                    userId, anchorDate, drift.size(), gaps.size(), drift.stream().limit(5).toList());
        }
        if (!degraded.isEmpty()) {
            // 正常语义，不是异常 → debug（原先 warn，会在生产日志里制造假警报）
            log.debug("账实自检：{} 笔成交日 = 锚定日 {}（文件日期 {}）→ 只落流水、未重复计入持仓 | userId={}",
                    degraded.size(), anchorDate, fileDateText, userId);
        }
        // RFC 20261003 C2 + P2（独立审查 2026-10-03）：现金侧对账——旧版 integrity **只管持仓不管现金**
        // （生产实据：09-22 本项报「账实一致」的同时，现金实际错了 23,686.15，见存量盘点清单 E）。
        // 按用户拍板 D1：**警告不拦**，但必须看得见。
        if (cashAlert != null) note += "；" + cashAlert;
        return new IntegrityReport(status, true, drift, gaps, degraded, note);
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
                entryDate, tradeTime, stopLossPrice, buyPoint, targetPrice, reason, null, null, null);
    }

    /**
     * 带幂等键的交易记录（RFC 20261003 C6，2026-10-03）：{@code POST /trading/trades} 支持
     * {@code Idempotency-Key} 请求头——同一 key 在 {@link #IDEMPOTENCY_WINDOW} 内只落一笔。
     * <p>
     * <b>覆盖范围（独立审查 2026-10-03 纠正）</b>：只覆盖「手动 / 一句话记录」路径；截图确认
     * （{@code /trade-log/confirm}，防重由 2026-09-15 的指纹判定承担）与批量（{@code /trades/batch}，
     * 尚无幂等键）**不走本方法**。幂等命中返回当前持仓（调用方语义与「已记录」一致，不报错）——
     * 且**只在成功落库之后才登记**（被拒绝的请求不占窗口，见 {@code recordTradeInternal}）。
     */
    public List<Position> recordTradeIdempotent(String userId, String symbol, String name,
                                                TradeDirection direction,
                                                BigDecimal price, int volume,
                                                LocalDate entryDate, LocalTime tradeTime,
                                                BigDecimal stopLossPrice, String buyPoint,
                                                BigDecimal targetPrice, String reason,
                                                String idempotencyKey) {
        return recordTradeInternal(userId, symbol, name, direction, price, volume,
                entryDate, tradeTime, stopLossPrice, buyPoint, targetPrice, reason, null, null,
                idempotencyKey);
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
                entryDate, tradeTime, stopLossPrice, buyPoint, targetPrice, reason, orderId, fee, null);
    }

    private List<Position> recordTradeInternal(String userId, String symbol, String name,
                                               TradeDirection direction,
                                               BigDecimal price, int volume,
                                               LocalDate entryDate, LocalTime tradeTime,
                                               BigDecimal stopLossPrice, String buyPoint,
                                               BigDecimal targetPrice, String reason,
                                               String orderId, BigDecimal fee, String idempotencyKey) {
        // #147：读-改-写加每用户锁，防并发交易互相覆盖丢持仓
        synchronized (tradeLock(userId)) {
            // RFC 20261003 C6（2026-10-03）：幂等键——同一 Idempotency-Key 在窗口内只落一笔。
            // 覆盖「网络重试 / 连点提交」两类重复（生产 09-15 事故原型：同一张截图反复提交 →
            // 600536 记成 1000 股、现金被扣成 −6,093.97）。窗口内重复 → 直接返回当前持仓、不报错。
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                String ikey = userId + "|" + idempotencyKey;
                LocalDateTime seen = idempotencyLog.get(ikey);
                if (seen != null && seen.isAfter(LocalDateTime.now().minus(IDEMPOTENCY_WINDOW))) {
                    log.info("幂等命中——同一 Idempotency-Key 已在窗口内落库，跳过重复记录 | userId={} | key={}",
                            userId, idempotencyKey);
                    return positionRepository.findAll(userId);
                }
                // P1（独立审查 2026-10-03 修复）：**登记移到成功落库之后**（见方法末尾）——
                // 原实现在业务校验（锚定覆盖 / 卖超 / 未持有）**之前**就占用了窗口，
                // 被拒绝的请求会让同 key 重试在 10 分钟内被静默吞掉（返回持仓却不报错、账也没记）。
            }
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
            // C7（2026-10-03）：锚定读不到就先拦住（全新账号放行，见闸门注释）
            requireAnchorKnownForLedgerChange(userId, "记录成交");
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
            // RFC 20261003 C3（D2 拍板 A 档，2026-10-03）：T+1 可用/可取分离——
            // 卖出回款**当日计入「可用」、不计入「可取」**（A 股 T+1：当天卖出所得可继续买，但须次一交易日才能转出）；
            // 买入时资金被占用，可用与可取同时减少。
            // 现实对照：券商「资金股份查询」首行的「可用 / 可取」本就是两个数，本处让两次快照导入之间的
            // 中间态也符合 T+1。A 档**不做每日自动结转**，故「可取」会停在最近一次快照值，由每次转出校验兜住。
            BigDecimal availableDelta = tradeCashDelta;
            BigDecimal withdrawableDelta = direction == TradeDirection.BUY
                    ? tradeCashDelta : BigDecimal.ZERO;
            try {
                accountSnapshotRepository.update(userId, current -> current.map(c -> {
                    BigDecimal newCash = c.cash().add(tradeCashDelta);
                    BigDecimal newMarketValue = c.marketValue().add(tradeValueDelta);
                    return new AccountSnapshot(
                            newCash.add(newMarketValue), // 总资产 = 现金 + 市值（只差手续费）
                            newCash,
                            c.available().add(availableDelta),
                            c.withdrawable().add(withdrawableDelta),
                            newMarketValue, c.pnl(), c.todayPnl(),
                            c.principal(), c.snapshotDate(), c.todayPnlSource());
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

            // P1（独立审查 2026-10-03 修复）：幂等键在**业务校验全部通过、账已落库之后**才登记——
            // 保证被拒绝的请求（锚定覆盖 / 卖超 / 未持有）不占用窗口，同 key 可重试。
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                LocalDateTime now = LocalDateTime.now();
                idempotencyLog.put(userId + "|" + idempotencyKey, now);
                if (idempotencyLog.size() > IDEMPOTENCY_MAX_ENTRIES) {
                    idempotencyLog.entrySet().removeIf(e -> e.getValue().isBefore(now.minus(IDEMPOTENCY_WINDOW)));
                }
            }

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
    /**
     * 追加一条流水。**返回是否真的写进去了**（2026-09-19，对抗审查 P0-2）：
     * 原实现把异常全 catch、只 `log.warn`、**从不抛**——调用方无从知道失败，于是新写的
     * `ledgerOnlyTrade` 恒返回 true（写失败也报「已记进流水」并把候选清掉，注释里
     * 「绝不静默吞」是假的）。交易主链路（持仓已落库）仍 best-effort 忽略返回值；
     * **降级路径必须消费它**。
     */
    private boolean appendTradeRecord(String userId, String symbol, String name, TradeDirection direction,
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
            return true;
        } catch (Exception e) {
            log.warn("交易流水写入失败（不影响交易落库）| symbol={} | {}", symbol, e.getMessage());
            return false;
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

    /** 单股当日口径（持仓列表现用）：当日盈亏 / 昨收 / 今日涨跌幅 / 仓位比例。 */
    public record StockDaily(
            BigDecimal todayPnl,        // 当日盈亏（券商口径；null = 未计入，见外层 notes）
            BigDecimal yesterdayClose,  // 昨收（null = 缺）
            BigDecimal dayChangePct,    // 今日涨跌幅 %（null = 缺昨收）
            BigDecimal positionRatio    // 占总资产 %（null = 总资产为 0）
    ) {}

    /** 持仓列表视图：原持仓 + 逐股当日口径 + 总仓位/现金比例 + 未计入说明。 */
    public record PositionsDailyView(
            List<Position> positions,
            Map<String, StockDaily> daily,
            BigDecimal totalAssets,          // 总资产 = 持仓市值 + 现金
            BigDecimal totalMarketValue,     // 持仓总市值
            BigDecimal cashBalance,
            BigDecimal totalPositionRatio,   // 总仓位 %（持仓市值 / 总资产）
            BigDecimal cashRatio,            // 现金比例 %
            List<String> notes               // 未计入项（非空 → 当日盈亏偏小）
    ) {}

    /**
     * 持仓列表视图（2026-09-14 用户拍板）：给每只票补「当日盈亏 + 今日涨跌幅 + 仓位比例」，
     * 外加顶部「总仓位 / 现金比例」。
     * <p>
     * 设计取舍：**不改** {@link #getPositions} 的返回形状——app/web/admin 三处都在消费
     * `List<Position>`，改成对象属破坏性变更；因此新增独立端点，逐股当日口径放**平行 map**
     * （key=symbol），前端原有解析原样可用，只多读一个 map。
     * <p>
     * 数据一致性：当日盈亏取 {@link #dailyPnlDetail}（与账户卡、收盘、重算同一份实现），
     * 不在这里另算——否则「卡片一个数、列表另一个数」就是下一个漂移。
     */
    public PositionsDailyView getPositionsDailyView(String userId) {
        PortfolioSnapshot snap = getPortfolioSnapshot(userId);
        DailyPnlDetail detail = dailyPnlDetail(userId, LocalDate.now());
        Map<String, MarketData> quotes = Map.of();
        List<String> symbols = snap.positions().stream().map(Position::symbol).toList();
        if (!symbols.isEmpty()) {
            try {
                quotes = marketDataSource.quote(symbols);
            } catch (Exception e) {
                log.warn("持仓视图：昨收拉取失败 | userId={} | {}", userId, e.getMessage());
            }
        }
        BigDecimal totalAssets = snap.totalValue().add(snap.cashBalance());
        Map<String, StockDaily> daily = new LinkedHashMap<>();
        for (Position p : snap.positions()) {
            MarketData md = quotes.get(p.symbol());
            BigDecimal yc = md != null ? md.yesterdayClose() : null;
            BigDecimal changePct = null;
            if (yc != null && yc.signum() > 0 && p.currentPrice() != null) {
                changePct = p.currentPrice().subtract(yc)
                        .divide(yc, 6, java.math.RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, java.math.RoundingMode.HALF_UP);
            }
            daily.put(p.symbol(), new StockDaily(
                    detail.bySymbol().get(p.symbol()), yc, changePct, pct(p.marketValue(), totalAssets)));
        }
        return new PositionsDailyView(snap.positions(), Map.copyOf(daily), totalAssets,
                snap.totalValue(), snap.cashBalance(),
                pct(snap.totalValue(), totalAssets), pct(snap.cashBalance(), totalAssets),
                detail.notes());
    }

    /** a / b → 百分数（两位小数）；b ≤ 0 或无意义 → null（不编造 0%）。 */
    private static BigDecimal pct(BigDecimal a, BigDecimal b) {
        if (a == null || b == null || b.signum() <= 0) return null;
        return a.divide(b, 6, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, java.math.RoundingMode.HALF_UP);
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
     * 已落库流水里是否已有这一笔（2026-09-15「截图反复确认 → 重复入账」治本）。
     * <p>
     * 生产事故：同一张成交截图反复提交，确认一次就落一笔 → 同一天同一笔被记多次
     * （2026-09-15 实测 600536 记成 1000 股、真实 800 股，现金被扣成 −6093.97）。
     * 判定分两条：
     * <ul>
     *   <li>{@code orderId} 非空（券商成交编号）→ <b>只认编号精确命中</b>：编号唯一，
     *       同价同量的两笔真实分笔成交不会被误判；</li>
     *   <li>{@code orderId} 为空（截图 OCR 常见）→ <b>指纹命中</b>：同标的 + 同方向 + 同价 +
     *       同量 + 同成交日。</li>
     * </ul>
     * 取向：宁可提示「这笔像已经记过了」让用户确认，也不重复入账——重复入账污染的是持仓与
     * 现金（要人工修数据），少记一笔用户立刻看得到提示、可手动补。
     *
     * @return 命中的既有流水（无 → empty）
     */
    /** 旧签名（无成交时间）：语义不变——双方都无时间概念时按原指纹判同笔。 */
    public Optional<TradeRecord> findRecordedTrade(String userId, String symbol,
                                                   TradeDirection direction,
                                                   BigDecimal price, Integer volume,
                                                   java.time.LocalDate entryDate, String orderId) {
        return findRecordedTrade(userId, symbol, direction, price, volume, entryDate, null, orderId);
    }

    /**
     * 带**成交时间**的判重（2026-09-19，对抗审查 P0-1）。
     *
     * <p>本批把成交时间加进候选去重（`sameTrade`）后，**confirm 的判重键却没有它**：
     * 「同代码 + 同方向 + 同价 + 同量 + 同一天」的分单（生产实据：000831 两笔各 200 股 @53.300，
     * 10:03:44 / 10:04:09）第 2 笔会命中第 1 笔刚落的流水 → 被判「这笔之前已经记过了」跳过
     * → **持仓仍然只记 200 股**——症状与修复前完全一致，而用户看到的是一句真话的假象。
     *
     * <p>规则：双方都有成交时间且不相等 → **不是同一笔**；任一方无时间 → 退回原指纹。
     */
    public Optional<TradeRecord> findRecordedTrade(String userId, String symbol,
                                                   TradeDirection direction,
                                                   BigDecimal price, Integer volume,
                                                   java.time.LocalDate entryDate, LocalTime tradeTime,
                                                   String orderId) {
        if (symbol == null || symbol.isBlank() || price == null || volume == null) {
            return Optional.empty();
        }
        boolean byOrderId = orderId != null && !orderId.isBlank();
        for (TradeRecord t : tradingHistoryRepository.findAll(userId)) {
            if (!symbol.equals(t.symbol())) continue;
            if (byOrderId) {
                if (orderId.equals(t.orderId())) return Optional.of(t);
                continue;
            }
            if (t.direction() != direction) continue;
            if (t.price() == null || t.price().compareTo(price) != 0) continue;
            if (t.volume() != volume) continue;
            java.time.LocalDate d = t.entryDate() != null ? t.entryDate()
                    : (t.timestamp() != null ? t.timestamp().toLocalDate() : null);
            if (entryDate != null && d != null && !entryDate.equals(d)) continue;
            // 成交时间维度（P0-1）：同价同量分单的唯一区分维度——不同时刻就是两笔
            if (tradeTime != null && t.tradeTime() != null && !tradeTime.equals(t.tradeTime())) continue;
            return Optional.of(t);
        }
        return Optional.empty();
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
            // §4.3 审计（2026-10-06）：补填也是账面改动——先留痕（fail-visible，失败则整个补填中止）、后改账
            if (auditRepository != null && tradeId != null && !tradeId.isBlank()) {
                TradeRecord old = tradingHistoryRepository.findAll(userId).stream()
                        .filter(t -> tradeId.equals(t.id())).findFirst().orElse(null);
                if (old != null) {
                    // P2-交易96：多条留痕收集后一次 appendAll（原逐条 append 各自读改写，中途失败留半截）
                    List<TradingAuditRepository.AuditEntry> entries = new ArrayList<>();
                    collectAudit(entries, tradeId, "orderId", old.orderId(),
                            orderId != null && !orderId.isBlank() ? orderId : old.orderId(), "补成交元信息");
                    if (fee != null) {
                        collectAudit(entries, tradeId, "fee", old.fee(), fee, "补成交元信息");
                    }
                    if (!entries.isEmpty()) auditRepository.appendAll(userId, entries);
                }
            }
            int updated = tradingHistoryRepository.updateTradeMeta(userId, tradeId, orderId, fee);
            log.info("交易流水补成交元信息 | userId={} | tradeId={} | orderId={} fee={} | {}",
                    userId, tradeId,
                    orderId != null && !orderId.isBlank() ? orderId : "（不改）",
                    fee != null ? fee : "（不改）",
                    updated > 0 ? "已更新" : "未命中");
            return updated;
        }
    }

    // ── 纠错链（R-08 · 设计 §3④ §4.3 §9#24，2026-10-06）：流水就地改/删 + 派生重算 + 系统侧留痕 ──

    /**
     * 就地改一笔流水（纠错 · R-08）：**用户面不留痕**（无版本），系统侧写 §4.3 不可见修改日志
     * （字段级前后值，fail-visible），持仓/现金等派生自动重算。
     * <p>
     * 派生重算口径（与 recordTrade 同一加权平均）：先**撤销旧影响**、再**应用新影响**；
     * entryDate ≤ 券商快照锚定日的版本其效果已在快照里，不参与派生（只改流水）；只记账未动现金
     * （{@code cashApplied=false}）的流水同样不动派生。撤销买入需持仓足量、撤销卖出需成本底账仍在
     * ——否则拒绝并指路「重导持仓/资金快照」（快照即真相，重导后派生自动对齐）。
     *
     * @param patch 要改的字段（price / volume / direction / entryDate / tradeTime / fee / orderId /
     *              stopLossPrice / buyPoint / targetPrice / reason）；未给的字段原样保留；
     *              不改 symbol（改标的 = 删了重记）。
     */
    public Map<String, Object> editTradeRecord(String userId, String tradeId, Map<String, Object> patch) {
        if (patch == null || patch.isEmpty()) {
            throw new TradingException("没有要改的内容——请给出要修改的字段");
        }
        Map<String, Object> result;
        synchronized (tradeLock(userId)) {
            requireAnchorKnownForLedgerChange(userId, "纠正流水");
            TradeRecord old = tradingHistoryRepository.findAll(userId).stream()
                    .filter(t -> tradeId != null && tradeId.equals(t.id())).findFirst().orElse(null);
            if (old == null) {
                throw new TradingException("没找到这笔流水（可能已被删除）——请刷新后重试");
            }
            TradeRecord updated = applyTradePatch(old, patch);
            if (updated.equals(old)) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("updated", false);
                r.put("tradeId", tradeId);
                r.put("note", "没有变化");
                return r;
            }
            // 先把「能不能撤销/应用」算清——任何一项不满足都在动笔之前拒绝（不写一半）
            CorrectionPlan plan = planCorrection(userId, old, updated);
            // §4.3 审计（fail-visible）：先留痕、后改账——审计写不进则整个纠错中止
            appendCorrectionAudit(userId, old, updated);
            int replaced = tradingHistoryRepository.replaceTrade(userId, tradeId, updated);
            if (replaced <= 0) {
                throw new TradingException("这笔流水没改成（文件里没找到）——请刷新后重试");
            }
            applyCorrectionPlan(userId, old.symbol(), plan);
            runClearanceSync(userId, List.of(old.symbol()));   // 清仓/取消清仓场景 best-effort 重扫
            log.info("流水已纠正 | userId={} | id={} | {} {} {}股@{} → {} {} {}股@{} | 派生{}",
                    userId, tradeId, old.direction(), old.symbol(), old.volume(), old.price(),
                    updated.direction(), updated.symbol(), updated.volume(), updated.price(),
                    plan.touchPosition() ? "已重算" : "未动（快照覆盖区/只记账）");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("updated", true);
            r.put("tradeId", tradeId);
            r.put("record", updated);
            r.put("derivationRecomputed", plan.touchPosition());
            result = r;
        }
        refreshTodayPnl(userId);   // 当日盈亏 best-effort 重算（锁外：避免持锁拉行情）
        return result;
    }

    /**
     * 就地删一笔流水（纠错 · R-08）：只撤销旧影响（不应用新影响），其余语义与
     * {@link #editTradeRecord} 完全一致（审计 fail-visible / 派生重算 / 不可精确撤销则拒绝）。
     */
    public Map<String, Object> deleteTradeRecord(String userId, String tradeId) {
        Map<String, Object> result;
        synchronized (tradeLock(userId)) {
            requireAnchorKnownForLedgerChange(userId, "删除流水");
            TradeRecord old = tradingHistoryRepository.findAll(userId).stream()
                    .filter(t -> tradeId != null && tradeId.equals(t.id())).findFirst().orElse(null);
            if (old == null) {
                throw new TradingException("没找到这笔流水（可能已被删除）——请刷新后重试");
            }
            CorrectionPlan plan = planCorrection(userId, old, null);
            // §4.3 审计（fail-visible）：删除也留痕（记录原文摘要）
            if (auditRepository != null) {
                auditRepository.append(userId, TradingAuditRepository.AuditEntry.of(
                        tradeId, "record", describeTrade(old), "（已删除）", "纠错·就地删"));
            }
            int deleted = tradingHistoryRepository.deleteTrade(userId, tradeId);
            if (deleted <= 0) {
                throw new TradingException("这笔流水没删掉（文件里没找到）——请刷新后重试");
            }
            applyCorrectionPlan(userId, old.symbol(), plan);
            runClearanceSync(userId, List.of(old.symbol()));
            log.info("流水已删除 | userId={} | id={} | {} {} {}股@{} | 派生{}",
                    userId, tradeId, old.direction(), old.symbol(), old.volume(), old.price(),
                    plan.touchPosition() ? "已重算" : "未动（快照覆盖区/只记账）");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("deleted", true);
            r.put("tradeId", tradeId);
            r.put("derivationRecomputed", plan.touchPosition());
            result = r;
        }
        refreshTodayPnl(userId);   // 当日盈亏 best-effort 重算（锁外：避免持锁拉行情）
        return result;
    }

    /** 纠错重算计划：在写任何文件之前算清全部增量；任何不可精确撤销的场景直接拒绝（不动笔）。 */
    private record CorrectionPlan(boolean touchPosition, Position upsert, boolean removePosition,
                                  BigDecimal cashDelta, BigDecimal mvDelta,
                                  BigDecimal availableDelta, BigDecimal withdrawableDelta) {
        static final CorrectionPlan NONE = new CorrectionPlan(false, null, false,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * 纠错派生重算计划（先校验 + 全部增量在手，纯内存不落盘）。
     *
     * @param updated null = 删除（只撤销、不应用）
     */
    private CorrectionPlan planCorrection(String userId, TradeRecord old, TradeRecord updated) {
        // 覆盖区：entryDate ≤ 锚定日的版本，其效果已在券商快照里 → 不参与派生
        //（cashApplied=null 与 reconcileCash 同口径：只有显式 false 才算「只记账未动现金」）
        boolean oldApplied = !Boolean.FALSE.equals(old.cashApplied()) && !coveredByAnchor(userId, old.entryDate());
        boolean newApplied = updated != null && !Boolean.FALSE.equals(updated.cashApplied())
                && !coveredByAnchor(userId, updated.entryDate());
        if (!oldApplied && !newApplied) {
            return CorrectionPlan.NONE;
        }
        Position cur = positionRepository.findAll(userId).stream()
                .filter(p -> p.symbol().equals(old.symbol())).findFirst().orElse(null);
        int qty = cur != null ? cur.quantity() : 0;
        BigDecimal cost = cur != null ? cur.avgCost() : BigDecimal.ZERO;
        // ① 撤销旧影响
        if (oldApplied) {
            if (old.direction() == TradeDirection.BUY) {
                if (cur == null || qty < old.volume()) {
                    throw new TradingException(String.format(
                            "这笔买入（%s %d股）之后的持仓已被卖出或调整过——撤销它会把持仓算成负数，"
                                    + "无法精确回推成本。请重导「持仓股」或「资金股份查询」快照，按券商口径重新对齐后再纠错",
                            old.symbol(), old.volume()));
                }
                int next = qty - old.volume();
                if (next == 0) {
                    qty = 0;
                    cost = BigDecimal.ZERO;
                } else {
                    BigDecimal costValue = cost.multiply(BigDecimal.valueOf(qty))
                            .subtract(CommissionCalculator.buyCost(old.symbol(), old.price(), old.volume()));
                    qty = next;
                    cost = costValue.divide(BigDecimal.valueOf(next), 4, java.math.RoundingMode.HALF_UP);
                }
            } else {
                if (cur == null) {
                    throw new TradingException(old.symbol() + " 的这笔卖出曾把它清仓（成本底账已不在），"
                            + "撤销后无法知道持仓成本——请重导「持仓股」或「资金股份查询」快照后再纠错");
                }
                qty = qty + old.volume();   // 撤销卖出：数量加回，成本不变（与 recordTrade SELL 口径对称）
            }
        }
        // ② 应用新影响
        if (newApplied) {
            if (updated.direction() == TradeDirection.BUY) {
                BigDecimal costValue = cost.multiply(BigDecimal.valueOf(qty))
                        .add(CommissionCalculator.buyCost(updated.symbol(), updated.price(), updated.volume()));
                qty = qty + updated.volume();
                cost = costValue.divide(BigDecimal.valueOf(qty), 4, java.math.RoundingMode.HALF_UP);
            } else {
                if (qty < updated.volume()) {
                    throw new TradingException(String.format(
                            "改成卖出 %s %d股后，彼时起的持仓不够卖（重算后仅 %d 股）——"
                                    + "如确要修正真实账目，请重导「持仓股」或「资金股份查询」快照对齐",
                            updated.symbol(), updated.volume(), qty));
                }
                qty = qty - updated.volume();
            }
        }
        // ③ 账户增量（现金/市值/可用/可取——与 recordTrade 落账方向完全对称）
        BigDecimal cashDelta = BigDecimal.ZERO;
        BigDecimal mvDelta = BigDecimal.ZERO;
        BigDecimal availableDelta = BigDecimal.ZERO;
        BigDecimal withdrawableDelta = BigDecimal.ZERO;
        if (oldApplied) {
            BigDecimal impact = cashImpact(old);   // recordTrade 时落账的现金增量
            cashDelta = cashDelta.subtract(impact);
            mvDelta = mvDelta.subtract(old.price().multiply(BigDecimal.valueOf(old.volume())));
            availableDelta = availableDelta.subtract(impact);
            if (old.direction() == TradeDirection.BUY) {
                withdrawableDelta = withdrawableDelta.subtract(impact);
            }
        }
        if (newApplied) {
            BigDecimal impact = cashImpact(updated);
            cashDelta = cashDelta.add(impact);
            mvDelta = mvDelta.add(updated.price().multiply(BigDecimal.valueOf(updated.volume())));
            availableDelta = availableDelta.add(impact);
            if (updated.direction() == TradeDirection.BUY) {
                withdrawableDelta = withdrawableDelta.add(impact);
            }
        }
        // ④ 结果持仓（touch 时才动；qty≤0 → 删行；元信息沿用现有行/新流水兜底）
        boolean touch = oldApplied || newApplied;
        Position upsert = null;
        if (touch && qty > 0) {
            upsert = cur != null
                    ? new Position(cur.symbol(), cur.name(), qty, cost, cur.currentPrice(),
                            LocalDateTime.now(), cur.entryDate(), cur.stopLossPrice(), cur.buyPoint(), cur.role())
                    : new Position(old.symbol(), old.name() != null ? old.name() : old.symbol(), qty, cost,
                            updated != null ? updated.price() : old.price(), LocalDateTime.now(),
                            updated != null ? updated.entryDate() : old.entryDate(),
                            updated != null ? updated.stopLossPrice() : old.stopLossPrice(),
                            updated != null ? updated.buyPoint() : old.buyPoint(), null);
        }
        return new CorrectionPlan(touch, upsert, touch && qty <= 0,
                cashDelta, mvDelta, availableDelta, withdrawableDelta);
    }

    /** 执行纠错重算计划（持仓 + 账户；账户写失败与 recordTrade 同口径：告警不中断）。 */
    private void applyCorrectionPlan(String userId, String symbol, CorrectionPlan plan) {
        if (plan.touchPosition()) {
            List<Position> positions = new ArrayList<>(positionRepository.findAll(userId));
            if (plan.removePosition()) {
                positions.removeIf(p -> p.symbol().equals(symbol));
            } else if (plan.upsert() != null) {
                boolean found = false;
                for (int i = 0; i < positions.size(); i++) {
                    if (positions.get(i).symbol().equals(symbol)) {
                        positions.set(i, plan.upsert());
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    positions.add(plan.upsert());
                }
            }
            positionRepository.saveAll(userId, positions);
        }
        if (plan.cashDelta().signum() != 0 || plan.mvDelta().signum() != 0
                || plan.availableDelta().signum() != 0 || plan.withdrawableDelta().signum() != 0) {
            try {
                accountSnapshotRepository.update(userId, cur -> cur.map(c -> {
                    BigDecimal newCash = c.cash().add(plan.cashDelta());
                    BigDecimal newMv = c.marketValue().add(plan.mvDelta());
                    return new AccountSnapshot(
                            newCash.add(newMv), newCash,
                            c.available().add(plan.availableDelta()),
                            c.withdrawable().add(plan.withdrawableDelta()),
                            newMv, c.pnl(), c.todayPnl(), c.principal(), c.snapshotDate(), c.todayPnlSource());
                }).orElse(null));
            } catch (RuntimeException e) {
                // 与 recordTrade 同口径（B6-4）：流水已改、账户没跟上 → ERROR 可见，下次对账/重导校准
                log.error("纠错已落库但账户快照更新失败——账目未落盘 | userId={} | {} | {}",
                        userId, symbol, e.getMessage());
            }
        }
    }

    /** 一笔流水对现金的落账方向（与 recordTrade 完全一致）：BUY = −含费总成本；SELL = +扣费回款。 */
    private static BigDecimal cashImpact(TradeRecord t) {
        return t.direction() == TradeDirection.BUY
                ? CommissionCalculator.buyCost(t.symbol(), t.price(), t.volume()).negate()
                : CommissionCalculator.sellProceeds(t.symbol(), t.price(), t.volume());
    }

    /** §4.3：把「旧 → 新」的字段级差异逐条留痕（fail-visible：仓储写失败直接抛，业务中止）。
     *  <p>P2-交易96（2026-10-06）：先收集完再**一次 appendAll**——原逐条 append 各自读改写，
     *  多字段纠错中途失败会留下「部分字段有留痕、账未改」的半截孤儿。 */
    private void appendCorrectionAudit(String userId, TradeRecord old, TradeRecord updated) {
        if (auditRepository == null) return;
        List<TradingAuditRepository.AuditEntry> entries = new ArrayList<>();
        collectAudit(entries, old.id(), "direction", old.direction(), updated.direction(), "纠错·就地改");
        collectAudit(entries, old.id(), "price", old.price(), updated.price(), "纠错·就地改");
        collectAudit(entries, old.id(), "volume", old.volume(), updated.volume(), "纠错·就地改");
        collectAudit(entries, old.id(), "entryDate", old.entryDate(), updated.entryDate(), "纠错·就地改");
        collectAudit(entries, old.id(), "tradeTime", old.tradeTime(), updated.tradeTime(), "纠错·就地改");
        collectAudit(entries, old.id(), "fee", old.fee(), updated.fee(), "纠错·就地改");
        collectAudit(entries, old.id(), "orderId", old.orderId(), updated.orderId(), "纠错·就地改");
        collectAudit(entries, old.id(), "stopLossPrice", old.stopLossPrice(), updated.stopLossPrice(), "纠错·就地改");
        collectAudit(entries, old.id(), "buyPoint", old.buyPoint(), updated.buyPoint(), "纠错·就地改");
        collectAudit(entries, old.id(), "targetPrice", old.targetPrice(), updated.targetPrice(), "纠错·就地改");
        collectAudit(entries, old.id(), "reason", old.reason(), updated.reason(), "纠错·就地改");
        if (!entries.isEmpty()) auditRepository.appendAll(userId, entries);
    }

    /** 字段有差异才收集一条（无差异不记——审计只记「修改」）；收集完由调用方一次 appendAll（P2-交易96）。 */
    private static void collectAudit(List<TradingAuditRepository.AuditEntry> out, String recordId,
                                     String field, Object before, Object after, String source) {
        if (java.util.Objects.equals(before, after)) return;
        if (before instanceof BigDecimal b && after instanceof BigDecimal a && b.compareTo(a) == 0) {
            return;   // scale 不同但数值相等（5.20 vs 5.2）不算修改
        }
        out.add(TradingAuditRepository.AuditEntry.of(recordId, field, str(before), str(after), source));
    }

    /** 值的可读化：BigDecimal 去尾零（避免「5.20 → 5.200」的伪差异）。 */
    private static String str(Object v) {
        if (v == null) return "";
        if (v instanceof BigDecimal bd) return bd.stripTrailingZeros().toPlainString();
        return String.valueOf(v);
    }

    /** 流水的一句话摘要（审计用：删除留痕/日志）。 */
    private static String describeTrade(TradeRecord t) {
        return "%s %s %s %d股@%s".formatted(
                t.direction() == TradeDirection.BUY ? "买入" : "卖出", t.symbol(),
                t.name() != null ? t.name() : "", t.volume(),
                t.price() != null ? t.price().stripTrailingZeros().toPlainString() : "?");
    }

    /**
     * 把 patch 字段套到旧流水上（未给的字段原样保留）；格式不合法抛 {@link TradingException}
     * （400 人话）。不接受改 symbol——「改标的」不是纠错，应删了重记。
     */
    private static TradeRecord applyTradePatch(TradeRecord old, Map<String, Object> patch) {
        if (patch.containsKey("symbol") && patch.get("symbol") != null
                && !String.valueOf(patch.get("symbol")).equals(old.symbol())) {
            throw new TradingException("不支持改股票代码（改标的 = 删了重记）——请用删除 + 新记录");
        }
        TradeDirection direction = old.direction();
        if (patch.containsKey("direction") && patch.get("direction") != null) {
            String d = String.valueOf(patch.get("direction")).trim().toUpperCase(Locale.ROOT);
            direction = switch (d) {
                case "BUY", "买入" -> TradeDirection.BUY;
                case "SELL", "卖出" -> TradeDirection.SELL;
                default -> throw new TradingException("direction 只能是 BUY 或 SELL");
            };
        }
        BigDecimal price = parseDecimalField(patch, "price", old.price());
        if (price == null || price.signum() <= 0) {
            throw new TradingException("price 必须是正数");
        }
        int volume = old.volume();
        if (patch.containsKey("volume") && patch.get("volume") != null) {
            volume = parseIntField(patch, "volume");
            if (volume <= 0) {
                throw new TradingException("volume 必须是正整数");
            }
        }
        LocalDate entryDate = parseDateField(patch, "entryDate", old.entryDate());
        LocalTime tradeTime = parseTimeField(patch, "tradeTime", old.tradeTime());
        BigDecimal fee = parseDecimalField(patch, "fee", old.fee());
        BigDecimal stopLossPrice = parseDecimalField(patch, "stopLossPrice", old.stopLossPrice());
        BigDecimal targetPrice = parseDecimalField(patch, "targetPrice", old.targetPrice());
        String orderId = parseStrField(patch, "orderId", old.orderId());
        String buyPoint = parseStrField(patch, "buyPoint", old.buyPoint());
        String reason = parseStrField(patch, "reason", old.reason());
        BigDecimal amount = price.multiply(BigDecimal.valueOf(volume));
        return new TradeRecord(old.id(), old.symbol(), old.name(), direction, price, volume, amount,
                entryDate, tradeTime, stopLossPrice, buyPoint, targetPrice, reason, fee,
                old.timestamp(), old.sourceRecordId(), orderId, old.cashApplied(), old.ledgerOnlyReason());
    }

    // patch 字段解析（格式错误 → 400 人话；未给 → 旧值）

    private static BigDecimal parseDecimalField(Map<String, Object> patch, String key, BigDecimal fallback) {
        if (!patch.containsKey(key)) return fallback;
        Object v = patch.get(key);
        if (v == null || String.valueOf(v).isBlank()) return null;
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new TradingException(key + " 不是有效数字（收到「" + v + "」）");
        }
    }

    private static int parseIntField(Map<String, Object> patch, String key) {
        try {
            return new BigDecimal(String.valueOf(patch.get(key)).trim()).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new TradingException(key + " 不是整数（收到「" + patch.get(key) + "」）");
        }
    }

    private static LocalDate parseDateField(Map<String, Object> patch, String key, LocalDate fallback) {
        if (!patch.containsKey(key)) return fallback;
        Object v = patch.get(key);
        if (v == null || String.valueOf(v).isBlank()) return null;
        try {
            String s = String.valueOf(v).trim().replace("-", "");
            return LocalDate.parse(s, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        } catch (Exception e) {
            throw new TradingException(key + " 不是日期（需 yyyy-MM-dd 或 yyyyMMdd，收到「" + v + "」）");
        }
    }

    private static LocalTime parseTimeField(Map<String, Object> patch, String key, LocalTime fallback) {
        if (!patch.containsKey(key)) return fallback;
        Object v = patch.get(key);
        if (v == null || String.valueOf(v).isBlank()) return null;
        try {
            String s = String.valueOf(v).trim();
            return s.length() == 5 ? LocalTime.parse(s + ":00") : LocalTime.parse(s);
        } catch (Exception e) {
            throw new TradingException(key + " 不是时间（需 HH:mm 或 HH:mm:ss，收到「" + v + "」）");
        }
    }

    private static String parseStrField(Map<String, Object> patch, String key, String fallback) {
        if (!patch.containsKey(key)) return fallback;
        Object v = patch.get(key);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
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
        return importPositions(userId, items, replace, snapshotDate, null);
    }

    /**
     * 持仓导入（2026-09-13 加 {@code brokerTodayPnl}）。
     * <p>
     * <b>为什么加这个参数</b>：用户实测发现「通达信『持仓股』导出**有『当日盈亏』列**（实测
     * 600206 −1116.00 / 002428 −644.00 / 600601 +1.00，Σ = −1759.00，与逐股复算一字不差），
     * 而系统从来没读它」——前端只解析 代码/名称/数量/成本 四列，后端入参也没有这个字段，
     * 于是账户卡的当日盈亏永远退回「系统自算」。而自算值可能错（实测：周六重算 + 双计污染持仓
     * → 把 −1759 写成 −2837 并挂了两天）。
     * <p>
     * 语义（与资金股份导入的「缺列不覆盖」同一约定，P2-交易37）：
     * <ul>
     *   <li>{@code brokerTodayPnl == null}（文件没这列 / 有行取不到数）→ <b>什么都不做</b>，
     *       保留账户旧值（绝不静默落零）；</li>
     *   <li>非 null 时仅当<b>文件日期与账户快照日相同</b>才写入——不同日期意味着
     *       「当日」不是同一天，写进去就是混日期（那正是本批要根除的病）；</li>
     *   <li>写入前若账户已有同日值且不同 → WARN 记录两个口径与差值（<b>口径差异可见化</b>）。</li>
     * </ul>
     *
     * @param brokerTodayPnl 券商「持仓股」导出「当日盈亏」列之和（含 0 股行）。可 null。
     */
    /**
     * 持仓导入的「现价」取舍（P2-交易65，2026-09-23）。
     * <p>
     * 优先券商导出自带的现价（{@code item.currentPrice()}）；文件没有这一列（旧导出/旧前端）时
     * <b>保留原有存储价</b>；只有「新持仓且无任何现价来源」才回退成本价。
     * <p>
     * <b>为什么不能一律写 avgCost</b>：运行时 {@code getPositions} 会用行情注入覆盖存储价，
     * 所以平时看不出来；但行情源不可用时（P1-交易62 那种整段失效）代码回退存储价，
     * 于是持仓页把成本价当真实市价 → 「0 盈亏 + 市值退回成本」的**假象**（2026-08-16 修过的同型病）。
     *
     * @param item     导入项（currentPrice 可空）
     * @param existing 该标的已有存储价（新持仓传 null；可空/可为脏值）
     */
    private static BigDecimal resolveCurrentPrice(PositionImportItem item, BigDecimal existing) {
        if (item.currentPrice() != null && item.currentPrice().signum() > 0) {
            return item.currentPrice();
        }
        if (existing != null && existing.signum() > 0) {
            return existing;
        }
        return item.avgCost();
    }

    public PositionImportResult importPositions(String userId, List<PositionImportItem> items, boolean replace,
                                                LocalDate snapshotDate, BigDecimal brokerTodayPnl) {
        return importPositions(userId, items, replace, snapshotDate, brokerTodayPnl, null);
    }

    /**
     * 持仓导入（2026-10-05，P2-交易84 加 {@code basedOn}）。
     *
     * @param basedOn **显式数据基准日**（可选；导入方说清「这份快照是哪天的」）——
     *                给了它就优先于时钟推断，不再因为「导入时刻 &lt; 09:30」被退到上一交易日；
     *                在未来 → 不可信，忽略并如实说明（见 {@link #decideAnchor}）。
     *                为 null → 既有归一化（时钟推断兜底，回执标「无据」）。
     */
    public PositionImportResult importPositions(String userId, List<PositionImportItem> items, boolean replace,
                                                LocalDate snapshotDate, BigDecimal brokerTodayPnl,
                                                LocalDate basedOn) {
        if (items == null || items.isEmpty()) {
            return new PositionImportResult(0, List.of());
        }
        synchronized (tradeLock(userId)) {
            List<Position> current = new ArrayList<>(positionRepository.findAll(userId));
            List<String> missingStopLoss = new ArrayList<>();
            int imported = 0;
            int rowNo = 0; // 入参行号（1 起）——fail-closed 报错定位用（2026-09-13）
            Set<String> importedSymbols = new java.util.HashSet<>();

            for (PositionImportItem item : items) {
                rowNo++;
                String symbol = item.symbol();
                // 2026-09-13（P2-交易41 同源残留封堵）：原实现 `continue` 静默丢行——而 replace=true
                // 是**全量覆盖**，丢一行 = 静默删一只持仓（正是「3 只只导入 2 只」的成因之一），
                // 且响应只有 {imported, missingStopLoss}、没有「被丢行」出口，调用方完全看不见。
                // 前端已 fail-closed，但后端是公共 API（curl / 未来 app 端导入）必须自证：
                // 缺代码即拒绝，且**在写任何东西之前**抛（items 只读，不落盘）。
                if (symbol == null || symbol.isBlank()) {
                    throw new TradingException("持仓导入：第 " + rowNo + " 行没有股票代码——"
                            + "为避免覆盖时丢掉持仓，本次导入已取消（未改动任何持仓）");
                }
                // P2-交易22（2026-08-17）：avgCost/quantity 校验——缺失/非法会让下游 NPE 500
                // 2026-09-13 负成本批：**负数成本合法**（反复做 T / 分红把成本摊到 0 以下是真实存在的，
                // 通达信持仓导出就是这么记的——实测 600601 方正科技 成本 −5.078 / 100 股）。
                // 原实现 signum() <= 0 一律抛异常 → 前端一旦放行负成本就会「整批 400、一只都进不去」，
                // 比静默少一只更糟。此处只拒「缺失」；0 与负数都放行（0 的百分比语义由 Position.pnlPercent 兜）。
                if (item.avgCost() == null) {
                    throw new TradingException("持仓导入：股票 " + symbol + " 的成本价缺失");
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
                        current.set(i, new Position(symbol, name, item.quantity(), item.avgCost(),
                                resolveCurrentPrice(item, p.currentPrice()),
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
                    current.add(new Position(symbol, name, item.quantity(), item.avgCost(),
                            resolveCurrentPrice(item, null),
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
            AnchorDecision anchorDecision = null;
            if (replace) {
                // 2026-10-05（P2-交易84）：锚定日 + **依据**（显式基准日 / 文件日期 / 休市归一化 / 时钟推断）
                anchorDecision = recordPositionsReplaceAnchor(userId, snapshotDate, basedOn);
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
            // 2026-09-13：券商「持仓股」导出的「当日盈亏」列（权威口径）落到账户卡——
            // 此前这一列从未被读（前端不解析、后端无入参），账户卡的当日盈亏只能靠系统自算。
            applyBrokerTodayPnl(userId, brokerTodayPnl, snapshotDate);
            // RFC 20261003 C1（2026-10-03，单侧动作显式化）：持仓快照是**只改持仓侧**的动作，
            // **不动现金**——显式声明，让「单侧」可审计（现金侧由「资金股份查询」导入负责）。
            log.info("持仓初始化导入 | side=POSITIONS_ONLY（只改持仓，不动现金）| userId={} | 导入 {} 只 | 未设止损 {} 只 | replace={} | 落盘 {} 只",
                    userId, imported, missingStopLoss.size(), replace, current.size());
            return new PositionImportResult(imported, missingStopLoss, anchorDecision);
        }
    }

    /**
     * 券商口径当日盈亏入账（2026-09-13）。
     * <p>
     * 严格三闸，任一不满足就<b>不写</b>（保留账户旧值 + INFO/WARN 说明），绝不猜：
     * ① 值为 null（文件没这一列 / 有行取不到数）→ 不动；
     * ② 无账户快照（未导过资金股份）→ 不动（不凭空初始化账户）；
     * ③ <b>文件日期 ≠ 账户快照日期</b> → 不动——「当日」必须是同一天，
     *    否则就是把 A 日的当日盈亏贴到 B 日的快照上（本批要根除的正是这类混日期）。
     */
    private void applyBrokerTodayPnl(String userId, BigDecimal brokerTodayPnl, LocalDate fileDate) {
        if (brokerTodayPnl == null) {
            return; // ① 缺列/不可靠：保留旧值（P2-交易37 约定）
        }
        java.util.Optional<AccountSnapshot> found = accountSnapshotRepository.findLatest(userId);
        if (found.isEmpty()) {
            log.info("券商当日盈亏未写入（尚无账户快照）| userId={} | 券商值={}", userId, brokerTodayPnl);
            return; // ②
        }
        AccountSnapshot snap = found.get();
        if (fileDate == null || snap.snapshotDate() == null || !fileDate.equals(snap.snapshotDate())) {
            log.info("券商当日盈亏未写入（文件日期与账户快照日不同，避免混日期）| userId={} | 文件日={} 快照日={} 券商值={}",
                    userId, fileDate, snap.snapshotDate(), brokerTodayPnl);
            return; // ③
        }
        BigDecimal prev = snap.todayPnl();
        if (prev != null && prev.compareTo(brokerTodayPnl) == 0) {
            log.info("券商当日盈亏与账户一致（无需改写）| userId={} | {}", userId, brokerTodayPnl);
            return;
        }
        if (prev != null) {
            // 口径差异可见化：不静默覆盖——把「系统自算」与「券商权威」两个数都留在日志里
            log.warn("当日盈亏口径差异（以券商为准覆盖）| userId={} | 券商={} 系统={} 差={}",
                    userId, brokerTodayPnl, prev, brokerTodayPnl.subtract(prev));
        }
        try {
            accountSnapshotRepository.update(userId, cur -> cur.map(c -> new AccountSnapshot(
                    c.assets(), c.cash(), c.available(), c.withdrawable(),
                    c.marketValue(), c.pnl(), brokerTodayPnl, c.principal(), c.snapshotDate(),
                    AccountSnapshot.SOURCE_BROKER))
                    .orElse(null));
            log.info("券商当日盈亏入库 | userId={} | todayPnl={}（持仓股导出「当日盈亏」列求和的权威值）",
                    userId, brokerTodayPnl);
        } catch (RuntimeException e) {
            // 持仓已落库、只有账户这一个字段没写：不抛错回滚（导入本身成功了），但必须 ERROR 可见
            log.error("券商当日盈亏写失败——账户卡当日盈亏仍是旧值 | userId={} | {}", userId, e.getMessage());
        }
    }

    /** 持仓导入项（通达信/批量，symbol 必填；name 缺失行情补全；止损/买点可选——缺失提示补设）。 */
    /**
     * 持仓导入项。
     * <p>
     * 2026-09-23（P2-交易65 修复）：新增 {@code currentPrice}——券商「持仓股」导出带「现价」列
     * （实测 002428 现价 93.50 / 成本 41.58），而此前入参根本没有这个字段，落库只能拿
     * {@code avgCost} 顶替 → 存储层的「现价」永远是成本价，行情源一挂就显示成 0 盈亏。
     * 可空（旧前端 / 无该列的导出）：为 null 时导入侧**保留原有存储价**，绝不写回成本价。
     */
    public record PositionImportItem(
            String symbol,
            String name,
            int quantity,
            BigDecimal avgCost,
            BigDecimal stopLossPrice,
            String buyPoint,
            String role,
            LocalDate entryDate,
            BigDecimal currentPrice
    ) {
        /** 兼容构造（2026-09-23 之前的 8 参调用 / 旧前端）：不带券商现价。 */
        public PositionImportItem(String symbol, String name, int quantity, BigDecimal avgCost,
                                  BigDecimal stopLossPrice, String buyPoint, String role,
                                  LocalDate entryDate) {
            this(symbol, name, quantity, avgCost, stopLossPrice, buyPoint, role, entryDate, null);
        }
    }

    /** 导入结果：导入数量 + 未设止损列表（R68 提示）。
     *  <p>2026-10-05（P2-交易84）：{@code anchor} = 本次落地的锚定日与**依据**（replace=true 才有；
     *  非全量导入 / 空导入 → null）。回执据此说清「这一天是怎么定下来的」，不再静默推断。 */
    public record PositionImportResult(int imported, List<String> missingStopLoss, AnchorDecision anchor) {

        /** 兼容构造（无锚定动作：非 replace / 空导入 / 既有测试零改动）。 */
        public PositionImportResult(int imported, List<String> missingStopLoss) {
            this(imported, missingStopLoss, null);
        }
    }

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
        // U6 脱敏（设计 §4.1#4，2026-10-06）：备注列银行账号**入库前**抹除——留存文件、返回给前端的
        // 解析文本都取脱敏后的内容（单点收口，同源同存）；存量文件原样保留、不回溯改写。
        String content = desensitizeImportText(decodeText(bytes));
        String monthDir = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        String ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String path = "trading/imports/" + monthDir + "/" + ts + "_" + safeName;
        positionRepository.saveImportFile(userId, path, content);
        log.info("导入文件已留存 | userId={} | path={} | {} 字节", userId, path, bytes.length);
        return new ImportFileResult(path, content);
    }

    /**
     * 备注列银行账号脱敏（U6，设计 §3①「备注列银行账号入库前抹除」）：16–19 位连续数字视为银行账号
     * → 整段抹除为占位符。只匹配纯数字长串（价格带小数点、股东代码带字母、委托/成交编号 ≤ 10 位均不受影响）。
     */
    static String desensitizeImportText(String content) {
        return content == null ? null : content.replaceAll("\\d{16,19}", "【银行账号已脱敏】");
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

    // ── 统一入口（R-12「一次把导出的文件交给它就行」· 2026-10-06 ingest 批）──

    /** 统一入口的一份输入文件（filename = 原始文件名，bytes = 原始字节——转码在识别前完成）。 */
    public record BundleFileInput(String filename, byte[] bytes) {}

    /** 统一入口的单份回执：ok=false 时 error 为人话原因；detail = 该份的具体结果（字段与既有端点回执同口径）。 */
    public record BundleFileResult(String filename, String savedPath, String kind, String kindLabel,
                                   boolean ok, String error, Map<String, Object> detail) {
        public BundleFileResult {
            if (detail == null) detail = Map.of();
        }
    }

    /** 统一入口总回执：逐份结果 + 成功/失败计数（一份失败不影响其他份）。 */
    public record BundleImportResult(List<BundleFileResult> files, int okCount, int failedCount,
                                     boolean dryRun) {}

    /** 预处理后的待处理文件（kind == null = 转码/留存阶段失败，仅用于如实报告）。 */
    private record PreparedBundleFile(String filename, String savedPath, String content,
                                      TradingImportParser.ImportKind kind) {}

    /**
     * 统一入口（R-12「一次把导出的文件交给它就行」· 设计 §3① §5）：一次收到多份导出文件，
     * 逐份识别（表头 fail-closed）→ 内部排序 → 逐份处理 → 逐份回执，**一份失败不影响其他份**。
     * <p>
     * <b>排序 = 快照在前、流水在后</b>（{@link TradingImportParser.ImportKind#order()}，依据与偏离
     * 设计稿 §3① 字面的原因见该 enum javadoc）：资金(10) → 持仓(20) → 成交(30) → 清仓(40) →
     * 自选(50) → 未知(90)。快照先落有硬依赖：持仓导入的「当日盈亏」写入要求账户快照已存在；
     * 且锚定日推进后本批流水 ≤ 锚定日一律走补录（不重放、批内不双计）。
     * <p>
     * {@code dryRun=true}：只识别 + 只报「会做什么」（各链对账/预检），**不落盘、不留存**。
     * <p>
     * 认不出的文件（{@link TradingImportParser.ImportKind#UNKNOWN}）**先留存、后如实拒绝**——
     * 原始文件不丢（U6 长期留存），但绝不当成任何一类静默入库。
     * <p><b>已知边界（登记）</b>：TRADES 的 dryRun 用**导入前**的锚定拆分补录/回放；正式导入时
     * 快照（本批若含）已先落、锚定日已推进——正式结果只会比预检**更温和**（少回放、多补录），
     * 偏差方向安全（不会出现「预检说不动账、正式却动账」），故 dryRun 不模拟排序后的锚定状态。
     */
    public BundleImportResult importBundle(String userId, List<BundleFileInput> files,
                                           ImportMode mode, boolean dryRun) {
        List<BundleFileInput> inputs = files != null
                ? files.stream().filter(f -> f != null).toList()
                : List.of();
        if (inputs.isEmpty()) {
            throw new TradingException("没有收到文件——请把通达信导出的文件选进来（可一次多选）");
        }
        // ① 逐份转码 +（非 dryRun）留存 + 识别——单份失败（转码/写入异常）不拖累其他份
        List<PreparedBundleFile> prepared = new ArrayList<>(inputs.size());
        for (BundleFileInput f : inputs) {
            String filename = f.filename() != null && !f.filename().isBlank() ? f.filename() : "未命名文件";
            // 空文件 / 读取失败的字节（controller 读失败会以 null bytes 进来）→ 如实失败，不静默消失
            if (f.bytes() == null || f.bytes().length == 0) {
                prepared.add(new PreparedBundleFile(filename, null, null, null));
                continue;
            }
            String content;
            String savedPath = null;
            try {
                // U6 脱敏：解析链与留存同源（saveImportFile 内部再解一次并脱敏，两处共用同一静态规则）
                content = desensitizeImportText(decodeText(f.bytes()));
                if (!dryRun) savedPath = saveImportFile(userId, filename, f.bytes()).path();
            } catch (RuntimeException e) {
                log.error("统一入口：文件转码/留存失败 | userId={} | {} | {}", userId, filename, e.getMessage());
                prepared.add(new PreparedBundleFile(filename, null, null, null));
                continue;
            }
            prepared.add(new PreparedBundleFile(filename, savedPath, content,
                    TradingImportParser.detectKind(content)));
        }
        // ② 内部排序：快照在前、流水在后（同 kind 保持用户选择顺序——稳定排序）
        List<PreparedBundleFile> ordered = new ArrayList<>(prepared);
        ordered.sort(java.util.Comparator.comparingInt(
                (PreparedBundleFile p) -> p.kind() != null ? p.kind().order() : Integer.MAX_VALUE));
        // ③ 逐份处理
        List<BundleFileResult> results = new ArrayList<>(ordered.size());
        for (PreparedBundleFile p : ordered) {
            results.add(processBundleFile(userId, p, mode, dryRun));
        }
        int okCount = (int) results.stream().filter(BundleFileResult::ok).count();
        log.info("统一入口{} | userId={} | {} 份：成功 {} / 失败 {} | 处理顺序 {}",
                dryRun ? "预检（不落盘）" : "导入", userId, results.size(), okCount,
                results.size() - okCount,
                ordered.stream().map(p -> p.filename() + ":"
                        + (p.kind() != null ? p.kind().name() : "PREP_FAIL")).toList());
        return new BundleImportResult(results, okCount, results.size() - okCount, dryRun);
    }

    /** 处理一份已识别文件：按 kind 分派；业务异常（TradingException）人话直出，未预期异常记日志 + 人话兜底。 */
    private BundleFileResult processBundleFile(String userId, PreparedBundleFile p,
                                               ImportMode mode, boolean dryRun) {
        if (p.kind() == null) {
            return fail(p, "这份文件是空的（或没能读取/留存）——为避免无痕导入，本份已跳过");
        }
        try {
            return switch (p.kind()) {
                case CASH -> cashBundleFile(userId, p, dryRun);
                case POSITIONS -> positionsBundleFile(userId, p, dryRun);
                case TRADES -> ok(p, historicalImportReceipt(
                        importHistoricalTrades(userId, p.content(), mode, dryRun), dryRun));
                case SOLD -> soldBundleFile(userId, p, dryRun);
                case WATCHLIST -> watchlistBundleFile(userId, p, dryRun);
                case UNKNOWN -> fail(p, "没认出这份文件是哪类导出——支持：历史成交 / 资金股份 / 持仓股 / 清仓股 / 自选股"
                        + "（「资金流水」银行流水暂无解析器，已登记待样本）。本份未做任何改动"
                        + (dryRun ? "" : "，原始文件已留存"));
            };
        } catch (TradingException e) {
            return fail(p, e.getMessage() != null ? e.getMessage() : "这份文件没能通过校验（未做任何改动）");
        } catch (RuntimeException e) {
            log.error("统一入口：处理失败 | userId={} | {} | {}", userId, p.filename(), e.toString());
            return fail(p, "处理失败：" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /** 资金股份（快照类）：dryRun → 只对账（券商 vs 系统）；否则落到现金/成本 + 锚定。 */
    private BundleFileResult cashBundleFile(String userId, PreparedBundleFile p, boolean dryRun) {
        LocalDate fileDate = TradingImportParser.parseDateFromFilename(p.filename());
        if (dryRun) {
            return ok(p, cashReconcileReceipt(reconcileCash(userId, p.content(), fileDate, null)));
        }
        return ok(p, cashImportReceipt(importCashQuery(userId, p.content(), fileDate, null)));
    }

    /**
     * 持仓股（快照类 · 全量覆盖）：表头 / 丢行 fail-closed → 品种门（账只接主板）→
     * 非主板存量保留 → dryRun 对账 / 正式以文件为准 replace。
     * <p>
     * <b>为什么非主板存量要「保留」</b>：本导入是 replace=true 全量覆盖——文件里出现的非主板行
     * （科创/创业/北交所/ETF/可转债/港美股）按 §11.2 品种门不入账，但若系统已持有同标的，
     * 直接用系统现值加回 items，避免「账不收新，把已有存量也静默删掉」。
     * 文件里没有的非主板系统持仓仍按「以文件为准」移除（与主板同一语义）。
     */
    private BundleFileResult positionsBundleFile(String userId, PreparedBundleFile p, boolean dryRun) {
        LocalDate fileDate = TradingImportParser.parseDateFromFilename(p.filename());
        TradingImportParser.PositionParse parsed = TradingImportParser.parsePositions(p.content());
        if (!parsed.headerMatched()) {
            throw new TradingException("持仓股文件表头认不出（需要「代码 / 证券数量 / 成本价」列）——是否选错了文件？");
        }
        if (!parsed.unparsedRows().isEmpty()) {
            throw new TradingException("持仓股文件有 " + parsed.unparsedRows().size()
                    + " 行没能识别，为避免覆盖时丢掉持仓，本份已取消：" + previewDropped(parsed.unparsedRows())
                    + "——请确认文件完整（导出未截断）后重试");
        }
        List<PositionImportItem> rows = new ArrayList<>();
        for (TradingImportParser.PositionRow r : parsed.rows()) {
            rows.add(new PositionImportItem(r.symbol(), r.name(), r.quantity(), r.avgCost(),
                    null, null, null, null, r.currentPrice()));
        }
        // P1-交易90（2026-10-06）：品种门收口到 gatePositions——与老端点 POST /positions/import
        // 共用同一份判据（原为两处手写，老端点漏了这道门）
        MainboardGate gate = gatePositions(userId, rows);
        Map<String, Object> extra = new LinkedHashMap<>();
        if (!parsed.skipped().isEmpty()) extra.put("skipped", parsed.skipped());
        extra.putAll(mainboardGateExtra(gate));
        if (gate.mainboardItems().isEmpty()) {
            extra.put("imported", 0);
            extra.put("note", "这份持仓文件里没有可入账的主板持仓——持仓不会有任何改动");
            return ok(p, extra);
        }
        if (dryRun) {
            return ok(p, mergeExtra(positionsReconcileReceipt(
                    reconcilePositions(userId, gate.mainboardItems())), extra));
        }
        return ok(p, mergeExtra(positionImportReceipt(
                importPositions(userId, gate.mainboardItems(), true, fileDate, parsed.todayPnl(), null)), extra));
    }

    /** 清仓股：dryRun 跑与正式导入**完全相同**的校验（清仓链无对账端点），只报到「会导入几笔」。 */
    private BundleFileResult soldBundleFile(String userId, PreparedBundleFile p, boolean dryRun) {
        if (dryRun) {
            TradingImportParser.SoldParse parsed = validateSoldImport(p.content());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("dryRun", true);
            detail.put("wouldImport", parsed.trades().size());
            if (!parsed.unparsedRows().isEmpty()) {
                detail.put("unparsed", parsed.unparsedRows());
                detail.put("unparsedCount", parsed.unparsedRows().size());
            }
            return ok(p, detail);
        }
        SoldImportResult r = soldImport(userId, p.content());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("imported", r.imported());
        if (!r.unparsedRows().isEmpty()) {
            detail.put("unparsed", r.unparsedRows());
            detail.put("unparsedCount", r.unparsedRows().size());
        }
        return ok(p, detail);
    }

    /** 自选股：dryRun 跑与正式导入**完全相同**的校验（自选链无对账端点），只报到「会导入几只」。 */
    private BundleFileResult watchlistBundleFile(String userId, PreparedBundleFile p, boolean dryRun) {
        if (dryRun) {
            TradingImportParser.WatchlistParse parsed = validateWatchlistImport(p.content());
            return ok(p, Map.of("dryRun", true, "wouldImport", parsed.items().size()));
        }
        return ok(p, Map.of("imported", watchlistImport(userId, p.content()).imported()));
    }

    /** 自选股文件的校验（统一入口 dryRun 与正式导入共用——保证「预检过的 = 正式会发生的」）。
     *  <p>2026-09-13（P2-交易41 同型风险封堵）：本导入是**全量覆盖**（saveAll 以文件为准）——
     *  有一行没看懂就拒绝，绝不「丢一行 = 静默删一只自选」。与持仓导入前端 fail-closed 同一判据
     *  （一行没解析成功就拒绝覆盖），把「静默丢行」变成用户可见的拒绝。 */
    private static TradingImportParser.WatchlistParse validateWatchlistImport(String content) {
        TradingImportParser.WatchlistParse parsed = TradingImportParser.parseWatchlistDetailed(content);
        if (parsed.items().isEmpty()) {
            throw new TradingException("无法识别为自选股导出：缺少形态列（长期/中期/短期形态）——是否选错了文件（如清仓股/资金股份/历史成交导出）？");
        }
        if (!parsed.unparsed().isEmpty()) {
            throw new TradingException("自选股文件有 " + parsed.unparsed().size()
                    + " 行没能识别，为避免覆盖时丢掉自选，本次导入已取消：" + previewRows(parsed.unparsed())
                    + "——请确认文件完整（如粘贴时被截断）或删掉这些行后重试");
        }
        return parsed;
    }

    /** 清仓股文件的校验（统一入口 dryRun 与正式导入共用）。表头未识别（选错文件 / 空文件）→ fail-closed 人话。
     *  <p>2026-10-04 对抗审查 P1（追加 A）：选错文件（表头核心列「代码/介入日期/清仓日期」一个没命中）
     *  原来把每行 continue 掉 → 回 {"imported":0}、连 WARN 都没有，用户以为「导了 0 笔」。
     *  与资金链 headerMatched 同一口径（含**空文件**：资金链也是这一条判据直接拒绝，不另做静默 no-op）：
     *  fail-closed 人话，未识别就不落笔（参数行都没有，也不会写任何档案）。 */
    private static TradingImportParser.SoldParse validateSoldImport(String content) {
        TradingImportParser.SoldParse parsed = TradingImportParser.parseSoldWithReport(content);
        if (!parsed.headerMatched()) {
            throw new TradingException("无法识别清仓股导出格式——请确认表头含「代码、介入日期、清仓日期」，"
                    + "且是通达信清仓股（已了结交易）导出——是否选错了文件（如自选股/资金股份/历史成交导出）？");
        }
        return parsed;
    }

    /** 成功回执（kind 小写名 + 人话标签；savedPath 仅非 dryRun 有值）。 */
    private static BundleFileResult ok(PreparedBundleFile p, Map<String, Object> detail) {
        return new BundleFileResult(p.filename(), p.savedPath(),
                p.kind() != null ? p.kind().name().toLowerCase(Locale.ROOT) : "unknown",
                p.kind() != null ? p.kind().label() : "无法识别", true, null, detail);
    }

    /** 失败回执（人话原因；「未做任何改动」也要如实说）。 */
    private static BundleFileResult fail(PreparedBundleFile p, String error) {
        return new BundleFileResult(p.filename(), p.savedPath(),
                p.kind() != null ? p.kind().name().toLowerCase(Locale.ROOT) : "unknown",
                p.kind() != null ? p.kind().label() : "无法识别", false, error, Map.of());
    }

    /** 回执合并：base（该链固有字段）+ extra（品种门/保留等补充字段）。 */
    public static Map<String, Object> mergeExtra(Map<String, Object> base, Map<String, Object> extra) {
        if (extra.isEmpty()) return base;
        Map<String, Object> out = new LinkedHashMap<>(base);
        out.putAll(extra);
        return out;
    }

    // ── 品种门（§11.2 账只接主板；2026-10-06 P1-交易90 收口到一处）──

    /** 品种门结果：可入账主板行 + 未入账（系统没持有的非主板）+ 按系统现值保留的（非主板存量）。 */
    public record MainboardGate(List<PositionImportItem> mainboardItems,
                                List<String> unsupported, List<String> preserved) {}

    /**
     * 品种门（§11.2 账只接主板：600/601/603/605/000/001/002/003）：统一入口与老端点
     * POST /positions/import 共用同一份判据。
     * <p>
     * 非主板（科创/创业/北交所/ETF/可转债/港美股）不入账；文件里出现的非主板品种若系统已持有
     * →**按系统现值加回**（replace 全量覆盖时不删存量、也不新增）；系统没有的 → 进 unsupported 如实拒绝。
     * null/blank symbol 放行（交给 importPositions 原 fail-closed 校验报 400 人话，不在此处越俎代庖）。
     */
    public MainboardGate gatePositions(String userId, List<PositionImportItem> items) {
        List<PositionImportItem> mainboard = new ArrayList<>();
        Set<String> unsupportedSymbols = new LinkedHashSet<>();
        for (PositionImportItem it : items) {
            String symbol = it.symbol();
            if (symbol == null || symbol.isBlank() || TradingImportParser.isMainboardCode(symbol)) {
                mainboard.add(it);
            } else {
                unsupportedSymbols.add(symbol);
            }
        }
        // 非主板存量保留（文件里出现的那些）：用系统现值加回——replace 不删、也不新增
        List<String> preserved = new ArrayList<>();
        Set<String> preservedSymbols = new HashSet<>();
        for (Position sys : positionRepository.findAll(userId)) {
            if (sys.symbol() != null && !TradingImportParser.isMainboardCode(sys.symbol())
                    && unsupportedSymbols.contains(sys.symbol())) {
                mainboard.add(new PositionImportItem(sys.symbol(), sys.name(), sys.quantity(), sys.avgCost(),
                        sys.stopLossPrice(), sys.buyPoint(), sys.role(), sys.entryDate(), sys.currentPrice()));
                preservedSymbols.add(sys.symbol());
                preserved.add(sys.symbol() + " " + sys.name() + "（" + sys.quantity() + " 股，按系统现值保留）");
            }
        }
        // 「未入账」只报系统没持有的那些（已持有的并进 preserved，不重复说）
        List<String> unsupported = new ArrayList<>();
        for (PositionImportItem it : items) {
            String symbol = it.symbol();
            if (symbol != null && unsupportedSymbols.contains(symbol) && !preservedSymbols.contains(symbol)) {
                unsupported.add(symbol + " " + it.name() + "（" + it.quantity() + " 股）");
            }
        }
        return new MainboardGate(mainboard, unsupported, preserved);
    }

    /** 品种门回执字段（unsupported + preserved，含口径 note；与统一入口逐字同款）。 */
    public static Map<String, Object> mainboardGateExtra(MainboardGate gate) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (!gate.unsupported().isEmpty()) {
            extra.put("unsupported", gate.unsupported());
            extra.put("unsupportedNote", "账只接主板（600/601/603/605/000/001/002/003）——"
                    + "这些品种本份里没有入账（如实拒绝、不静默）");
        }
        if (!gate.preserved().isEmpty()) {
            extra.put("preserved", gate.preserved());
            extra.put("preservedNote", "系统里已有的非主板持仓按现值保留（账不收新，但不会因这次的 replace 被删掉）");
        }
        return extra;
    }

    // ── 回执组装（统一入口与既有端点共用一份字段口径；2026-10-06 自 controller 迁入）──

    /**
     * 锚定决策 → 回执（2026-10-05，P2-交易84）。字段 additive：旧客户端忽略即可。
     * <p>{@code basis} = EXPLICIT/FILE_DATE/CLOSED_DAY（有据）或 CLOCK（无据）；
     * {@code note} = 人话说明（含「你指定的基准日在未来，已忽略」这类如实交代）。
     */
    public static Map<String, Object> anchorReceipt(AnchorDecision d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("anchorDate", d.anchorDate() != null ? d.anchorDate().toString() : null);
        m.put("fileDate", d.fileDate() != null ? d.fileDate().toString() : null);
        m.put("basis", d.basis() != null ? d.basis().name() : null);
        m.put("withEvidence", d.withEvidence());
        if (d.explicitRejected()) {
            m.put("explicitDate", d.explicitDate() != null ? d.explicitDate().toString() : null);
            m.put("explicitRejected", true);
        }
        m.put("note", d.describe());
        return m;
    }

    /** 持仓导入回执 {imported, missingStopLoss, anchor?}（字段与 POST /positions/import 逐字一致）。 */
    public static Map<String, Object> positionImportReceipt(PositionImportResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("imported", result.imported());
        body.put("missingStopLoss", result.missingStopLoss());
        if (result.anchor() != null) body.put("anchor", anchorReceipt(result.anchor()));
        return body;
    }

    /** 持仓对账回执（dryRun）{dryRun, fileCount, systemCount, diffs[], note}（与 POST /positions/import?dryRun=true 逐字一致）。 */
    public static Map<String, Object> positionsReconcileReceipt(PositionsReconcile rec) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dryRun", true);
        out.put("fileCount", rec.fileCount());
        out.put("systemCount", rec.systemCount());
        out.put("diffs", rec.diffs().stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", d.symbol());
            m.put("name", d.name());
            m.put("fileQty", d.fileQty());
            m.put("systemQty", d.systemQty());
            m.put("diff", d.diff());
            m.put("why", d.why());
            return m;
        }).toList());
        out.put("note", rec.note());
        return out;
    }

    /** 资金对账回执（dryRun）（与 POST /imports/cash dryRun 逐字一致；cashAnchorDate 空值 = ""）。 */
    public static Map<String, Object> cashReconcileReceipt(CashReconcile rec) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dryRun", true);
        out.put("brokerCash", rec.brokerCash());
        out.put("systemCash", rec.systemCash());
        out.put("diff", rec.diff());
        out.put("cashAnchorDate", rec.cashAnchorDate() != null ? rec.cashAnchorDate().toString() : "");
        out.put("ledgerOnlyCount", rec.ledgerOnlyCount());
        out.put("ledgerOnlyAmount", rec.ledgerOnlyAmount());
        out.put("adjustmentTotal", rec.adjustmentTotal());
        out.put("adjustmentCount", rec.adjustmentCount());
        out.put("since", rec.since().stream().map(k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", k.kind());
            m.put("count", k.count());
            m.put("amount", k.amount());
            return m;
        }).toList());
        out.put("note", rec.note());
        return out;
    }

    /** 资金导入回执 {cash, assets, updatedCost, unparsedRows(int), unparsed+unparsedCount(非空), anchor?}
     *  （与 POST /imports/cash 逐字一致）。 */
    public static Map<String, Object> cashImportReceipt(CashImportResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cash", r.cash());
        out.put("assets", r.assets());
        out.put("updatedCost", r.updatedCost());
        // P2-交易45：保持 int 类型不变——旧客户端按数字解析，改类型会把导入直接打挂
        out.put("unparsedRows", r.unparsedRows());
        if (!r.unparsed().isEmpty()) {
            out.put("unparsed", r.unparsed());
            out.put("unparsedCount", r.unparsed().size());
        }
        if (r.anchor() != null) out.put("anchor", anchorReceipt(r.anchor()));
        return out;
    }

    /** 历史成交导入回执（与 POST /trades/import 逐字一致；anchor 为**裸 AnchorStatus**，dryRun 时附 plan）。 */
    public static Map<String, Object> historicalImportReceipt(HistoricalTradeImportResult result, boolean dryRun) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("imported", result.imported());
        resp.put("updated", result.updated());
        resp.put("skipped", result.skipped());
        resp.put("nonTrades", result.nonTrades());
        resp.put("lines", result.lines());
        resp.put("syncMode", result.syncMode() != null ? result.syncMode() : "append");
        if (result.summary() != null) resp.put("summary", result.summary());
        resp.put("rejected", result.rejected() != null ? result.rejected() : List.of());
        if (result.unparsed() != null && !result.unparsed().isEmpty()) {
            resp.put("unparsed", result.unparsed().stream()
                    .map(TradingImportParser.UnparsedLine::describe).toList());
            resp.put("unparsedCount", result.unparsed().size());
        }
        if (result.anchor() != null) resp.put("anchor", result.anchor());
        resp.put("dryRun", dryRun);
        if (dryRun) {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("new", result.imported());
            plan.put("merged", result.updated());
            plan.put("skipped", result.skipped());
            plan.put("nonTrades", result.nonTrades());
            plan.put("wouldReject", result.rejected() != null ? result.rejected().size() : 0);
            plan.put("anchorKnown", result.anchor() != null && result.anchor().known());
            plan.put("syncMode", result.syncMode() != null ? result.syncMode() : "append");
            resp.put("plan", plan);
        }
        return resp;
    }

    /** 统一入口总回执 {dryRun, okCount, failedCount, files[]}（逐份 filename/kind/ok/error/detail）。 */
    public static Map<String, Object> bundleReceipt(BundleImportResult bundle) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dryRun", bundle.dryRun());
        out.put("okCount", bundle.okCount());
        out.put("failedCount", bundle.failedCount());
        out.put("files", bundle.files().stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("filename", f.filename());
            if (f.savedPath() != null) m.put("savedPath", f.savedPath());
            m.put("kind", f.kind());
            m.put("kindLabel", f.kindLabel());
            m.put("ok", f.ok());
            if (!f.ok()) m.put("error", f.error());
            if (!f.detail().isEmpty()) m.put("detail", f.detail());
            return m;
        }).toList());
        return out;
    }

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
        // 校验（表头/丢行 fail-closed）与统一入口 dryRun 共用 validateWatchlistImport——预检过的 = 正式会发生的
        TradingImportParser.WatchlistParse parsed = validateWatchlistImport(content);
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
            List<WatchlistItem> next = new ArrayList<>(parsed.items().size());
            for (WatchlistItem item : parsed.items()) {
                LocalDate addedAt = existingAddedAt.getOrDefault(item.symbol(), LocalDate.now());
                next.add(new WatchlistItem(item.symbol(), item.name(), item.industry(), item.industry2(),
                        item.longForm(), item.midForm(), item.shortForm(), item.signal(), addedAt));
            }
            watchlistRepository.saveAll(userId, next);
        }
        log.info("自选股导入（覆盖）| userId={} | {} 只", userId, parsed.items().size());
        return new WatchlistImportResult(parsed.items().size());
    }

    /** 人话预览被丢弃/未识别的行（最多 5 行，超出报总数）——fail-closed 报错文案用（2026-09-13）。 */
    private static String previewRows(List<String> rows) {
        List<String> head = rows.stream().limit(5)
                .map(r -> r.length() > 60 ? r.substring(0, 60) + "…" : r)
                .toList();
        String joined = String.join(" ／ ", head);
        return rows.size() > head.size() ? joined + " 等 " + rows.size() + " 行" : joined;
    }

    /** 丢行明细预览（P2-交易83）：条目已是「第 N 行「原文」：原因」人话，**不再二次截断**
     *  （否则原因会被切掉，正是本批要修掉的「看不见」）。最多 5 条，超出报总数。 */
    private static String previewDropped(List<String> described) {
        List<String> head = described.stream().limit(5).toList();
        String joined = String.join(" ／ ", head);
        return described.size() > head.size() ? joined + " 等 " + described.size() + " 行" : joined;
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

    /** 导入清仓股（通达信导出文本；按 symbol upsert，保留已有 verdict/psychology）。
     *  <p>P2-交易83（2026-10-04）：解析层没看懂的行带<b>行号 + 原文 + 原因</b>回执
     *  （对齐 P2-交易43 历史成交口径）。本导入是 upsert（不删档案），故丢行不 fail-closed，
     *  但必须可见——原来 `imported` 一个计数把丢的行全盖住了。</p>
     *  <p>对抗审查 P1（2026-10-04 追加 A）：**表头未识别**（选错文件 / 空文件）是另一回事——
     *  与自选/资金/历史成交三条链同口径 fail-closed 400 + 人话，不再静默回 {@code imported=0}。</p>
     */
    public SoldImportResult soldImport(String userId, String content) {
        // 校验（表头 fail-closed）与统一入口 dryRun 共用 validateSoldImport——预检过的 = 正式会发生的
        TradingImportParser.SoldParse parsed = validateSoldImport(content);
        List<SoldTrade> trades = parsed.trades();
        if (trades.isEmpty()) {
            // 一行都没解析出来时同样带回丢行明细（原来直接 `new SoldImportResult(0)`，连丢的行都没出口）
            if (!parsed.unparsedRows().isEmpty()) {
                log.warn("清仓股导入：{} 行没看懂，本次没有任何可导入的清仓记录 | userId={} | {}",
                        parsed.unparsedRows().size(), userId, previewDropped(parsed.unparsedRows()));
            }
            return new SoldImportResult(0, parsed.unparsedRows());
        }
        synchronized (tradeLock(userId)) {
            List<SoldTrade> current = new ArrayList<>(soldTradeRepository.findAll(userId));
            for (SoldTrade t : trades) {
                boolean found = false;
                for (int i = 0; i < current.size(); i++) {
                    if (current.get(i).symbol().equals(t.symbol())) {
                        SoldTrade old = current.get(i);
                        // 2026-09-13（P2-交易41 同型「字段级静默落零」封堵）：解析器取不到数时
                        // 落 null/0（parseDateSafe→null、parseIntSafe→0、parseDoubleSafe→0.0），
                        // 而此处是**整体覆盖**既有行 → 介入/清仓日期被清空（连带
                        // /sold/{symbol}/psychology-questions 走 404）、持仓天数与持仓期涨幅归零
                        // （再连带下方 verdict 用被置 0 的 holdPnlPct 重算出错误结论）。
                        // 判据：A 股 T+1，真实清仓行的介入/清仓日期与持仓天数（≥1）必然存在——
                        // 缺失或 0 只可能是「这一列没解析出来」→ 该字段保留旧值，不让解析失败覆盖真数据。
                        boolean rowNumericOk = t.holdDays() > 0;
                        current.set(i, new SoldTrade(
                                t.symbol(),
                                t.name().isBlank() ? old.name() : t.name(),
                                t.buyDate() != null ? t.buyDate() : old.buyDate(),
                                t.sellDate() != null ? t.sellDate() : old.sellDate(),
                                rowNumericOk ? t.holdDays() : old.holdDays(),
                                t.tradeCount().isBlank() ? old.tradeCount() : t.tradeCount(),
                                rowNumericOk ? t.holdPnlPct() : old.holdPnlPct(),
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
        log.info("清仓股导入 | userId={} | {} 笔（含规则对照 verdict）| 没看懂的行 {}",
                userId, trades.size(), parsed.unparsedRows().size());
        if (!parsed.unparsedRows().isEmpty()) {
            // P2-交易83：这些行的清仓档案本次没进库/没更新——用户必须能对上文件里的哪一行
            log.warn("清仓股导入有 {} 行没看懂（这些清仓记录本次没进档案）| userId={} | {}",
                    parsed.unparsedRows().size(), userId, previewDropped(parsed.unparsedRows()));
        }
        return new SoldImportResult(trades.size(), parsed.unparsedRows());
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
     * <p>2026-10-05（P2-交易84）：{@code basedOn} = 导入方显式给出的**数据基准日**（可选）——
     * 优先于时钟推断，见 {@link #decideAnchor}。
     */
    public CashImportResult importCashQuery(String userId, String content, LocalDate snapshotDate) {
        return importCashQuery(userId, content, snapshotDate, null);
    }

    /** 资金股份查询导入 + 显式数据基准日（2026-10-05，P2-交易84）。 */
    public CashImportResult importCashQuery(String userId, String content, LocalDate snapshotDate,
                                            LocalDate basedOn) {
        // 2026-10-05（P2-交易84）：显式基准日（且不在未来）优先——账户快照日与现金锚定日随之对齐，
        // 避免「对账看到的是 A 日、落盘锚定成 B 日」的两套口径。
        LocalDate today = LocalDate.now();
        LocalDate explicit = (basedOn != null && !basedOn.isAfter(today)) ? basedOn : null;
        LocalDate effectiveDate = explicit != null ? explicit
                : (snapshotDate != null ? snapshotDate : today);
        TradingImportParser.CashQuery q = TradingImportParser.parseCash(content);
        // 2026-08-17（P1-交易5 修复）：解析失败（首行「余额/可用/可取/参考市值/资产/盈亏」未命中）
        // 禁止落零覆盖——此前会把 account.json 资产/现金清零、cashBalance 置零且无提示（B51 检查点）
        if (!q.headerMatched()) {
            throw new TradingException("无法识别资金股份查询格式——请确认首行是「余额:… 可用:… 可取:… 参考市值:… 资产:… 盈亏:…」，且是通达信资金股份导出");
        }
        // P2-交易45（2026-09-14）：表头命中了正则 ≠ 6 个数值都解析成功——parseNum 失败返回 null，
        // 原来 null 会一路写进 AccountSnapshot（资产/现金变空，账户卡显示异常且无提示）。
        // 缺任何一项直接拒绝导入（fail-closed），绝不用半份数据覆盖账户。
        if (!q.headerUnparsed().isEmpty()) {
            throw new TradingException("资金文件首行的「" + String.join("、", q.headerUnparsed())
                    + "」没能读成数字，为避免把账户资金写成空值，本次导入已取消——"
                    + "请确认导出完整（数字列之间是正常空格、文件没被截断）后重试");
        }
        if (!q.unparsedRows().isEmpty()) {
            // 明细丢一行 = 该只持仓的「精确成本」不更新（不覆盖既有数据，故不 fail-closed，但必须可见）
            // P2-交易83（2026-10-04）：日志与回执都带上**行号 + 原文 + 原因**（原来只有原文，对不上文件）
            log.warn("资金明细有 {} 行没看懂（这些持仓的精确成本本次不更新）| userId={} | {}",
                    q.unparsedRows().size(), userId, previewDropped(q.unparsedRows()));
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
        // RFC 20261003 C4 + D3（2026-10-03）：**对账差额落账**——覆盖之前先记下旧现金，
        // 覆盖之后把「券商 − 系统」这条差额写成一条调整，使
        //   系统现金 = 上次快照值 + Σ(已计入的流水与转账) + Σ(调整)
        // 恒成立且可查（原实现静默覆盖：差额被抹掉、不留痕 → 只能反复导全量）。
        synchronized (tradeLock(userId)) {
            // P2-9 归正（2026-10-06 设计 §4.3 §9#24）：差额基准与审计前值必须在**覆盖之前**读取，
            // 而覆盖/落账/审计三段同锁串行——原实现先覆盖后读（读到的是新值：差额恒 0、
            // 「重导快照」审计的前值也会失真）。两端同时导同一份文件时各读各的真实旧值，不再虚增调整。
            AccountSnapshot beforeImport = accountSnapshot(userId);
            BigDecimal cashBeforeImport = beforeImport.cash();
            // §4.3 审计（fail-visible）：重导快照=覆盖式改账——先留痕、后覆盖（失败则整个导入中止）
            // P2-交易96：cash/assets 两条收集后一次 appendAll（原各自独立读改写）
            if (auditRepository != null) {
                List<TradingAuditRepository.AuditEntry> entries = new ArrayList<>();
                if (cashBeforeImport != null && q.cash() != null
                        && cashBeforeImport.compareTo(q.cash()) != 0) {
                    entries.add(TradingAuditRepository.AuditEntry.of(
                            "account:cash", "cash", str(cashBeforeImport), str(q.cash()), "重导快照·资金股份"));
                }
                if (beforeImport.assets() != null && q.assets() != null
                        && beforeImport.assets().compareTo(q.assets()) != 0) {
                    entries.add(TradingAuditRepository.AuditEntry.of(
                            "account:cash", "assets", str(beforeImport.assets()), str(q.assets()), "重导快照·资金股份"));
                }
                if (!entries.isEmpty()) auditRepository.appendAll(userId, entries);
            }
            accountSnapshotRepository.update(userId, cur -> new AccountSnapshot(
                    q.assets(), q.cash(), q.available(), q.withdrawable(),
                    q.marketValue(), q.pnl(),
                    todayPnlFromFile ? BigDecimal.valueOf(todayPnl)
                            : cur.map(AccountSnapshot::todayPnl).orElse(BigDecimal.ZERO),
                    cur.map(AccountSnapshot::principal).orElse(BigDecimal.ZERO), effectiveDate,
                    // P2-交易48：来源随值一起落盘——文件带「当日盈亏」列且明细非空 → broker（券商权威）；
                    // 否则该字段没被本次导入改动，来源原样继承（不得把券商来源错记成系统计算）
                    todayPnlFromFile ? AccountSnapshot.SOURCE_BROKER
                            : cur.map(AccountSnapshot::todayPnlSource).orElse(null)));
            // 1. cashBalance 更新
            java.math.BigDecimal cash = q.cash();
            List<Position> positions = new ArrayList<>(positionRepository.findAll(userId));
            int updated = 0;
            // 2. 精确成本价更新（资金查询 4 位 > 持仓导出 2-3 位）
            for (Position p : positions) {
                for (TradingImportParser.CashPosition cp : q.positions()) {
                    // 2026-09-13（P2-交易41 同型封堵）：原条件 `costPrice() > 0` 把**负成本**
                    // （合法：反复做 T / 分红把成本摊到 0 以下，实测 600601 方正科技 −5.078）
                    // 与 0 一并静默跳过 → 「精确成本」永远不更新，用户看到的仍是粗糙的 2-3 位成本。
                    // 改为只把 0 当「该列没取到数」的哨兵（parseDoubleSafe 失败返回 0）——0 成本本身
                    // 也已由持仓链路兜住；负成本则正常写入（`!= 0`）。
                    if (cp.symbol().equals(p.symbol()) && cp.costPrice() != 0) {
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
            // 2026-10-05（P2-交易84）：锚定日 + 依据一起落盘，回执如实带上。
            AnchorDecision anchorDecision = recordCashImportAnchor(userId, snapshotDate, basedOn);
            // C4：差额落账（|差| ≤ 0.005 视为一致，不记——避免每天一条 0 元调整刷屏）
            if (cashAdjustmentRepository != null && cashBeforeImport != null && q.cash() != null) {
                BigDecimal adj = q.cash().subtract(cashBeforeImport);
                if (adj.abs().compareTo(new BigDecimal("0.005")) > 0) {
                    try {
                        cashAdjustmentRepository.append(userId, new CashAdjustment(
                                IdGenerator.monotonic("adj_"), effectiveDate, adj,
                                "资金快照对账差额（系统解释不了的部分：未记录股息/利息、费用口径差或未知）",
                                "覆盖前系统现金 " + cashBeforeImport.stripTrailingZeros().toPlainString()
                                        + " → 券商 " + q.cash().stripTrailingZeros().toPlainString(),
                                LocalDateTime.now()));
                        log.info("对账差额已落账 | userId={} | {} | 系统 {} → 券商 {}", userId,
                                adj.stripTrailingZeros().toPlainString(),
                                cashBeforeImport.stripTrailingZeros().toPlainString(),
                                q.cash().stripTrailingZeros().toPlainString());
                    } catch (RuntimeException e) {
                        // 落账失败不阻断导入（钱已经按券商值对齐了）——但必须告警，不得静默
                        log.error("对账差额落账失败（账已按券商值覆盖，但差额无痕迹）| userId={} | {}",
                                userId, e.getMessage());
                    }
                }
            }
            // RFC 20261003 C1（2026-10-03，单侧动作显式化）：资金快照是**只改现金侧**的动作
            // （外加持仓成本价），**不动持仓数量**——在日志里显式声明，让「单侧」成为可审计的事实，
            // 而不是靠读代码才知道。持仓侧由「持仓股」导入负责（见 importPositions 的 POSITIONS_ONLY）。
            log.info("资金查询导入 | side=CASH_ONLY（只改现金侧与成本，不动持仓数量）| userId={} | 现金={} 资产={} | 成本更新 {} 只 | 当日盈亏列={} | 锚定={}",
                    userId, cash, q.assets(), updated, todayPnlFromFile, anchorDecision.describe());
            return new CashImportResult(cash, q.assets(), updated, q.unparsedRows(), anchorDecision);
        }
    }

    // ── C4 对账式导入（RFC 20261003-trading-cash-position-linkage §三 C4，2026-10-03）──

    /**
     * 资金快照**对账**（只读，不落盘）：把「券商现金」与「系统推算现金」摆在一起、把差额与期间事件摊开，
     * 让人**先看见差在哪、再决定要不要覆盖**（用户 2026-10-03「减少全量导入」的正面解法）。
     *
     * <p><b>为什么需要</b>：原 {@link #importCashQuery} 是**静默覆盖**——差额被抹掉、不留痕，于是只能反复
     * 导全量。生产实据（存量盘点清单 E）：两次导入之间系统现金漂到 **−37,226.29 / +24,101.01**，
     * 而券商真值全程 ≤ 2,278.16。
     *
     * <p><b>口径</b>：系统现金 = 当前 `account.json` 的 cash（它本身 = 上次快照值 + 此后已计入的流水与转账）；
     * 差额 = 券商现金 − 系统现金 = **系统解释不了的那部分**（未记录的股息/利息/费用差/未知）。
     * 期间明细按类型汇总，其中「只记账未动现金」的行（{@code cashApplied=false}）**单列**——它们本就不在
     * 系统现金里，也不该在，但必须让人看得见（这正是 C5 流水自证带来的能力）。
     */
    public CashReconcile reconcileCash(String userId, String content, LocalDate snapshotDate) {
        return reconcileCash(userId, content, snapshotDate, null);
    }

    /**
     * 资金快照对账 + **显式数据基准日**（2026-10-05，P2-交易84）。
     *
     * <p>原实现 {@code snapshotDate != null ? snapshotDate : LocalDate.now()}：不给文件日期就用
     * 「导入时刻」当天当对账截止日——与落盘锚定的口径可能分叉（dryRun 按 A 日算、落盘锚成 B 日）。
     * 现在显式基准日（且不在未来）优先，与 {@link #decideAnchor} 同一判据；**时钟只作最后兜底**。
     */
    public CashReconcile reconcileCash(String userId, String content, LocalDate snapshotDate,
                                      LocalDate basedOn) {
        TradingImportParser.CashQuery q = TradingImportParser.parseCash(content);
        if (!q.headerMatched()) {
            throw new TradingException("无法识别资金股份查询格式——请确认首行是「余额:… 可用:… 可取:…"
                    + " 参考市值:… 资产:… 盈亏:…」，且是通达信资金股份导出");
        }
        if (!q.headerUnparsed().isEmpty()) {
            throw new TradingException("资金文件首行的「" + String.join("、", q.headerUnparsed())
                    + "」没能读成数字——为避免拿半份数据说话，本次对账已取消");
        }
        AccountSnapshot cur = accountSnapshot(userId);
        LocalDate anchor = anchorRepository.find(userId).cashImport();
        // 2026-10-05（P2-交易84）：显式基准日（非未来）优先于时钟——对账口径与落盘锚定同一判据
        LocalDate today = LocalDate.now();
        LocalDate explicit = (basedOn != null && !basedOn.isAfter(today)) ? basedOn : null;
        LocalDate effectiveDate = explicit != null ? explicit
                : (snapshotDate != null ? snapshotDate : today);

        java.util.Map<String, BigDecimal> sums = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        int ledgerOnlyCount = 0;
        BigDecimal ledgerOnlyAmount = BigDecimal.ZERO;
        for (TradeRecord t : tradingHistoryRepository.findAll(userId)) {
            if (t.entryDate() == null) continue;
            // 锚定日**当天及之前**的成交已含在上次快照里 → 不再重复计入推算
            if (anchor != null && !t.entryDate().isAfter(anchor)) continue;
            if (t.entryDate().isAfter(effectiveDate)) continue;
            if (Boolean.FALSE.equals(t.cashApplied())) {
                // 只落流水、不动现金（回放兜底 / 锚定降级 / append 补录）——单列，不参与现金推算
                ledgerOnlyCount++;
                ledgerOnlyAmount = ledgerOnlyAmount.add(t.amount() != null ? t.amount() : BigDecimal.ZERO);
                continue;
            }
            BigDecimal amount = t.amount() != null ? t.amount() : BigDecimal.ZERO;
            BigDecimal fee = t.fee() != null ? t.fee() : BigDecimal.ZERO;
            String kind;
            BigDecimal delta;
            if (t.volume() == 0) {
                kind = "股息/红利税";
                delta = t.direction() == TradeDirection.BUY ? amount : amount.negate();
            } else if (t.direction() == TradeDirection.BUY) {
                kind = "买入";
                delta = amount.add(fee).negate();
            } else {
                kind = "卖出";
                delta = amount.subtract(fee);
            }
            sums.merge(kind, delta, BigDecimal::add);
            counts.merge(kind, 1, Integer::sum);
        }
        for (TransferRecord tr : transferRepository.findAll(userId)) {
            if (tr.date() == null) continue;
            if (anchor != null && !tr.date().isAfter(anchor)) continue;
            if (tr.date().isAfter(effectiveDate)) continue;
            String kind = tr.isIn() ? "转入" : "转出";
            sums.merge(kind, tr.isIn() ? tr.amount() : tr.amount().negate(), BigDecimal::add);
            counts.merge(kind, 1, Integer::sum);
        }
        List<KindSum> since = new ArrayList<>();
        for (java.util.Map.Entry<String, BigDecimal> e : sums.entrySet()) {
            since.add(new KindSum(e.getKey(), counts.getOrDefault(e.getKey(), 0), e.getValue()));
        }

        BigDecimal brokerCash = q.cash() != null ? q.cash() : BigDecimal.ZERO;
        BigDecimal systemCash = cur.cash() != null ? cur.cash() : BigDecimal.ZERO;
        BigDecimal diff = brokerCash.subtract(systemCash);
        BigDecimal adjTotal = BigDecimal.ZERO;
        int adjCount = 0;
        if (cashAdjustmentRepository != null) {
            try {
                var all = cashAdjustmentRepository.findAll(userId);
                for (CashAdjustment a : all) {
                    // §9#4（2026-10-06）：存量本金迁移事件不是现金差额（那笔钱早已含在快照里）——
                    // 单列统计会让「累计 N 次」混入非现金事件，这里排除。
                    if (a.id() != null && a.id().startsWith(PRINCIPAL_MIGRATION_PREFIX)) continue;
                    adjCount++;
                    if (a.amount() != null) adjTotal = adjTotal.add(a.amount());
                }
            } catch (RuntimeException e) {
                log.warn("读对账调整失败（报告里按 0 处理）| userId={} | {}", userId, e.getMessage());
            }
        }
        StringBuilder note = new StringBuilder();
        if (diff.signum() == 0) {
            note.append("券商现金与系统推算一致（差额 0）——这次导入不会改变现金。");
        } else {
            note.append("券商现金 ").append(brokerCash.stripTrailingZeros().toPlainString())
                    .append("、系统推算 ").append(systemCash.stripTrailingZeros().toPlainString())
                    .append("，差 ").append(diff.stripTrailingZeros().toPlainString())
                    .append("——这部分我解释不了（未记录的股息/利息、费用口径差或未知）；覆盖后它就消失了。");
        }
        if (adjCount > 0) {
            note.append(" 历史上已落账 ").append(adjCount).append(" 次对账调整，累计 ")
                    .append(adjTotal.stripTrailingZeros().toPlainString()).append("。");
        }
        if (ledgerOnlyCount > 0) {
            note.append(" 另有 ").append(ledgerOnlyCount).append(" 笔只记账未动现金（合计 ")
                    .append(ledgerOnlyAmount.stripTrailingZeros().toPlainString())
                    .append("），它们本就不在系统现金里。");
        }
        return new CashReconcile(brokerCash, systemCash, diff, anchor,
                ledgerOnlyCount, ledgerOnlyAmount, adjTotal, adjCount, since, note.toString());
    }

    // ── C4 延伸：持仓快照对账（RFC C4「持仓同理」，2026-10-03）──

    /**
     * 持仓快照**对账**（只读，不落盘）：对比「本次文件里的持仓」与「系统当前落地持仓」，
     * 把差异**逐只**摆出来——先看见「哪只、差多少、replace 会怎么改」，再决定要不要覆盖。
     *
     * <p>与资金侧的差别：持仓的「系统值」就是 `positions.md`（持仓的唯一真源），
     * 所以这里不做流水重放（那是 {@code GET /trading/integrity} 的事），只做**文件 vs 落地**的直比。
     */
    public PositionsReconcile reconcilePositions(String userId, List<PositionImportItem> items) {
        java.util.Map<String, Integer> fileQty = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
        if (items != null) {
            for (PositionImportItem it : items) {
                if (it.symbol() == null || it.symbol().isBlank()) continue;
                fileQty.merge(it.symbol(), it.quantity(), Integer::sum);
                if (it.name() != null && !it.name().isBlank()) names.put(it.symbol(), it.name());
            }
        }
        java.util.Map<String, Integer> sysQty = currentQuantities(userId);
        java.util.Set<String> all = new java.util.LinkedHashSet<>();
        all.addAll(fileQty.keySet());
        all.addAll(sysQty.keySet());
        List<QtyDiff> diffs = new ArrayList<>();
        for (String sym : all) {
            int f = fileQty.getOrDefault(sym, 0);
            int c = sysQty.getOrDefault(sym, 0);
            if (f == c) continue;
            String why = f == 0 ? "文件里没有这只（replace 会移除）"
                    : c == 0 ? "系统里没有这只（replace 会新增）"
                    : "数量不一致（replace 会以文件为准）";
            diffs.add(new QtyDiff(sym, names.getOrDefault(sym, sym), f, c, f - c, why));
        }
        String note = diffs.isEmpty()
                ? "文件与系统持仓逐只相符（文件 " + fileQty.size() + " 只）——这次 replace 不会改变持仓。"
                : "有 " + diffs.size() + " 只不一致：replace 会以文件为准改掉它们（差额见 diff）。";
        return new PositionsReconcile(fileQty.size(), sysQty.size(), diffs, note);
    }

    /** 持仓快照对账报告（**只读**）。 */
    public record PositionsReconcile(int fileCount, int systemCount, List<QtyDiff> diffs, String note) {}

    /** 单只数量差异（文件 − 系统）。 */
    public record QtyDiff(String symbol, String name, int fileQty, int systemQty, int diff, String why) {}

    /**
     * 是否已有账户快照（P1-6，2026-10-03 增量深审）：**没有快照时 `accountSnapshot` 的「可取」是占位 0**，
     * 拿它做「可取够不够」的判断会给出纯误导的告警——调用方先用本方法确认有没有真值。
     */
    public boolean hasAccountSnapshot(String userId) {
        return accountSnapshotRepository.findLatest(userId).isPresent();
    }

    /** 资金快照对账报告（**只读**，不改任何账）。 */
    public record CashReconcile(
            BigDecimal brokerCash, BigDecimal systemCash, BigDecimal diff,
            LocalDate cashAnchorDate, int ledgerOnlyCount, BigDecimal ledgerOnlyAmount,
            // RFC 20261003 C4：历史对账调整累计（让「差额去哪了」可回看）
            BigDecimal adjustmentTotal, int adjustmentCount,
            List<KindSum> since, String note) {}

    /** 自上次现金锚定日以来的事件汇总（类型 · 笔数 · 净额）。 */
    public record KindSum(String kind, int count, BigDecimal amount) {}

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
        DailyPnlDetail d = dailyPnlDetail(userId, date);
        return new DailyPnlResult(d.todayPnl(), d.notes(), d.effectiveDate());
    }

    /**
     * 当日盈亏明细（含逐股拆分）。账户卡的持仓列表（{@link #getPortfolioView}）与收盘/重算路径
     * 共用这一份实现——否则「卡片一个数、日志另一个数」就是下一个同型漂移。
     */
    public record DailyPnlDetail(BigDecimal todayPnl, List<String> notes,
                                 Map<String, BigDecimal> bySymbol, LocalDate effectiveDate) {
        /**
         * 兼容构造（不关心口径日期的调用方）。
         * <p>
         * <b>P1-2（2026-09-17 深审）</b>：{@code effectiveDate} 是「这个数算的是哪一天」的**显式**答案。
         * 盘前 / 非交易日时它**不等于**查询日期（是上一交易日）——调用方必须先判它再决定
         * 覆盖 today / 回填 week、month，否则就是把上一交易日的盈亏当今天（周月还会双计）。
         */
        public DailyPnlDetail(BigDecimal todayPnl, List<String> notes, Map<String, BigDecimal> bySymbol) {
            this(todayPnl, notes, bySymbol, null);
        }
    }

    public DailyPnlDetail dailyPnlDetail(String userId, LocalDate date) {
        BigDecimal pnl = BigDecimal.ZERO;
        List<String> notes = new ArrayList<>();
        Map<String, BigDecimal> bySymbol = new LinkedHashMap<>();
        // P2-交易51（2026-09-16）：盘前 / 非交易日时，行情接口给的「现价」仍是**上一交易日收盘**，
        // 「昨收」是再前一交易日收盘——照 date 直接算，等于把「上一交易日的当日盈亏」当成「今天」
        // （用户实测：同一持仓两天里给出两个数，09-15 真实 −503.90 被显示成 −180）。
        // 修法：以**行情数据所在的那个交易日**为「当日」（盘前/非交易日 → 上一交易日），并如实标注。
        LocalDate resolvedDate = date;
        if (date != null && quoteIsFromPreviousDay(date)) {
            LocalDate prev = previousTradingDay(date);
            if (prev != null) resolvedDate = prev;
        }
        final LocalDate effectiveDate = resolvedDate;
        if (!effectiveDate.equals(date)) {
            notes.add("今天还没开盘（或今天不是交易日），下面是 " + effectiveDate
                    + " 的当日盈亏——开盘后它会自动换成今天的数");
        }
        List<TradeRecord> dayTrades = tradingHistoryRepository.findAll(userId).stream()
                .filter(t -> effectiveDate.equals(t.entryDate()))
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
        // 行情符号 = 当前持仓 ∪ 今日有成交的票。必须含后者：今天卖光的票已不在持仓里，
        // 但它的昨收是「券商口径卖出当日盈亏」的唯一基准（2026-09-14 A 方案）。
        java.util.LinkedHashSet<String> quoteSymbols = new java.util.LinkedHashSet<>();
        positions.forEach(p -> quoteSymbols.add(p.symbol()));
        realized.keySet().forEach(quoteSymbols::add);
        Map<String, MarketData> quotes = Map.of();
        if (!quoteSymbols.isEmpty()) {
            try {
                quotes = marketDataSource.quote(new ArrayList<>(quoteSymbols));
            } catch (Exception ex) {
                log.warn("当日盈亏：行情拉取失败 | {}", ex.getMessage());
            }
        }
        for (Map.Entry<String, SoldAgg> e : realized.entrySet()) {
            SoldAgg a = e.getValue();
            if (a.sellQty <= 0) continue;
            // ── 卖出部分的「当日」基差（2026-09-14 用户拍板 A：改为券商口径）──
            // 原口径拿**建仓成本**当基准 → 算的是「这笔交易从建仓到现在赚了多少」，把过去累积的
            // 浮盈记进了「当日」（实测：云南锗业卖 100 股虚增 3466 元，系统 5826 vs 券商 2245）。
            // 券商口径 = **昨收**：卖出部分的当日盈亏 = 卖出净额 − 昨收 × 卖量。
            // 唯一例外是 T+0 边界（当日先买后卖，A 股 T+1 不会出现）→ 该部分按当日买入均价。
            long matchedToday = a.sellAfterFirstBuy ? Math.min(a.sellQty, a.buyQty) : 0;
            BigDecimal basis = BigDecimal.ZERO;
            if (matchedToday > 0 && a.buyQty > 0) {
                BigDecimal buyAvg = a.buyCost.divide(BigDecimal.valueOf(a.buyQty), 6,
                        java.math.RoundingMode.HALF_UP);
                basis = basis.add(buyAvg.multiply(BigDecimal.valueOf(matchedToday)));
                notes.add(e.getKey() + " " + a.name
                        + "：含当日先买后卖（T+0 边界），该部分按当日买入均价计——A 股 T+1 账户不会出现");
            }
            long fromOld = a.sellQty - matchedToday;
            if (fromOld > 0) {
                MarketData md = quotes.get(e.getKey());
                BigDecimal yc = md != null ? md.yesterdayClose() : null;
                if (yc == null) {
                    // 缺昨收 → 这笔的当日盈亏算不出来。静默按 0 计会让总数偏小且看不出原因，
                    // 故如实附注；refreshTodayPnl 见「实质未计入」会拒绝写回（P2-交易46 的闸）。
                    notes.add(e.getKey() + " " + a.name + "：缺昨收，这笔卖出（" + fromOld
                            + " 股）的当日盈亏未计入——当日盈亏偏小");
                } else {
                    basis = basis.add(yc.multiply(BigDecimal.valueOf(fromOld)));
                }
            }
            BigDecimal delta = a.sellNet.subtract(basis);
            pnl = pnl.add(delta);
            bySymbol.merge(e.getKey(), delta, BigDecimal::add);
        }
        // 2. 持仓日浮动（quotes 已在上方按「持仓 ∪ 今日成交」拉好）
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
            bySymbol.merge(p.symbol(), floatPnl, BigDecimal::add);
        }
        // 3. 当日股息入账（+）/红利税（−）：volume=0 的资金事件（amount 存绝对值，方向编码）
        for (TradeRecord t : dayTrades) {
            if (t.volume() != 0 || t.amount() == null) continue;
            BigDecimal ev = t.direction() == TradeDirection.BUY ? t.amount() : t.amount().negate();
            pnl = pnl.add(ev);
            bySymbol.merge(t.symbol(), ev, BigDecimal::add);
        }
        bySymbol.remove(null);
        return new DailyPnlDetail(pnl.setScale(2, java.math.RoundingMode.HALF_UP),
                List.copyOf(notes), java.util.Collections.unmodifiableMap(new LinkedHashMap<>(bySymbol)),
                effectiveDate);
    }

    /**
     * 行情是否还停在**上一个交易日**（P2-交易51）。
     * <p>
     * 只在「问的就是今天」时有意义：非交易日，或交易日但还没到 9:30 开盘——此时行情接口返回的
     * 「现价 / 昨收」都是上一交易日的口径，按今天算出来的数不是今天的。
     * <p>
     * 可测版本：{@link #quoteIsFromPreviousDay(LocalDate, LocalDate, java.time.LocalTime)}。
     */
    static boolean quoteIsFromPreviousDay(LocalDate date) {
        return quoteIsFromPreviousDay(date, LocalDate.now(), java.time.LocalTime.now());
    }

    static boolean quoteIsFromPreviousDay(LocalDate date, LocalDate today, java.time.LocalTime now) {
        if (date == null || today == null || !date.equals(today)) return false;
        if (!TradingSessionPushService.isTradingDayStrict(today)) return true;
        return now != null && now.isBefore(java.time.LocalTime.of(9, 30));
    }

    /** 上一个交易日（最多回退 15 天；找不到返回 null）。 */
    static LocalDate previousTradingDay(LocalDate date) {
        if (date == null) return null;
        LocalDate d = date.minusDays(1);
        for (int i = 0; i < 15; i++) {
            if (TradingSessionPushService.isTradingDayStrict(d)) return d;
            d = d.minusDays(1);
        }
        return null;
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

    /** 当日盈亏计算结果（notes：未计入部分的人话说明，非空即非全精确）。 */
    public record DailyPnlResult(BigDecimal todayPnl, List<String> notes, LocalDate effectiveDate) {
        /** 兼容构造。 */
        public DailyPnlResult(BigDecimal todayPnl, List<String> notes) {
            this(todayPnl, notes, null);
        }
    }

    /**
     * 三官深审 P1-1（2026-09-09）：当日成交流水在 15:05 后落库（截图确认/单笔记录/历史成交导入）
     * → 触发当日盈亏重算写入 account.todayPnl（只改 todayPnl，不动现金/资产/市值/快照日期）。
     * 仅当 account 快照存在才写；best-effort 失败仅告警。
     */
    public void refreshTodayPnl(String userId) {
        refreshTodayPnl(userId, LocalDate.now());
    }

    /**
     * 指定日期版本（生产走 {@link #refreshTodayPnl(String)}；日期参数化是为了能测非交易日）。
     * <p>
     * 2026-09-13 加两道闸——起因是用户实测「屏幕上的当日盈亏 −2837 是错的」：
     * 09-12（<b>周六</b>）导入 09-11 历史成交时触发了本方法，于是
     * <pre>周六的日期 + 周末行情接口给的「最后两个交易日收盘」+ 双计污染后的持仓 = −2837</pre>
     * 顶着「当日盈亏」的名义在账户卡上挂了整整两天（真值 −1759.00，券商「持仓股」导出的
     * 「当日盈亏」列求和也是 −1759.00）。
     * <ul>
     *   <li><b>闸 1 非交易日不重算</b>：非交易日「当日」不存在，算出来的既不是今天的、也不是
     *       上一交易日真值的数（它是用两日收盘差冒充的）。</li>
     *   <li><b>闸 2 有实质未计入则不覆盖</b>（P2-交易46）：缺昨收/无成本基线时算出的值是<b>偏小的</b>，
     *       写回去比保留旧值更糟——它看起来像真的。「今日无成交记录」不算实质缺失
     *       （纯持有日只算浮动是可信的）。</li>
     * </ul>
     */
    public void refreshTodayPnl(String userId, LocalDate today) {
        if (!TradingSessionPushService.isTradingDayStrict(today)) {
            log.info("非交易日不重算当日盈亏 | userId={} | {}", userId, today);
            return;
        }
        if (accountSnapshotRepository.findLatest(userId).isEmpty()) return;
        try {
            DailyPnlResult r = computeDailyPnl(userId, today);
            // P1-2（2026-09-17 深审修复）：**显式**判「算的是不是今天」。原实现靠闸 2 的 notes 文案前缀
            // 间接挡住（我的新提示恰好不在豁免名单里），属**巧合**——文案一改就会把上一交易日的
            // 盈亏写进今天的快照。这里用 effectiveDate 直说。
            if (r.effectiveDate() != null && !today.equals(r.effectiveDate())) {
                log.info("当日盈亏未写回（算的是 {} 的当日，不是今天的）| userId={} | today={}",
                        r.effectiveDate(), userId, today);
                return;
            }
            List<String> actionable = r.notes().stream()
                    .filter(n -> !n.startsWith("今日无成交记录"))
                    .toList();
            if (!actionable.isEmpty()) {
                log.warn("当日盈亏未写回（有实质未计入，保留原值）| userId={} | 算出={} | {}",
                        userId, r.todayPnl(), String.join("；", actionable));
                return;
            }
            accountSnapshotRepository.update(userId, cur -> cur.map(c -> new AccountSnapshot(
                    c.assets(), c.cash(), c.available(), c.withdrawable(),
                    c.marketValue(), c.pnl(), r.todayPnl(), c.principal(), c.snapshotDate(),
                    AccountSnapshot.SOURCE_CALC))
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
        TransferRecord record = new TransferRecord(IdGenerator.monotonic("transfer_"),
                type, amount, transferDate, note);
        synchronized (tradeLock(userId)) {
            // ⚠️ P2-13（2026-10-03 增量深审）：C7 闸门与锚定日校验原先在**锁外**——检查通过后、写入前
            // 若有快照导入推进了锚定日，这笔转账仍会落账 → 与快照双计（窄窗口，与 recordTradeInternal 同型）。
            // 现在与写账动作同锁：检查-再动作之间不再有窗口。
            requireAnchorKnownForLedgerChange(userId, "记录转账");
            SnapshotAnchor curAnchor = anchorRepository.find(userId);
            LocalDate cashAnchor = curAnchor != null ? curAnchor.cashImport() : null;
            if (cashAnchor != null && !transferDate.isAfter(cashAnchor)) {
                throw new TradingException(String.format(
                        "转账日期 %s 已包含在 %s 的资金股份快照中（快照余额已含这笔现金变动）——补记会重复扣现金；"
                                + "如仅需修正净投入本金，请用「设置本金」；现金请以券商资金快照为准（转账应在快照导入前记录）",
                        transferDate, cashAnchor));
            }
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
                        LocalDate.now(), current.todayPnlSource());
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

    /** 现金新鲜度阈值（天）：距上次「资金股份查询」导入超过它 → 账户卡提示对账（P2-交易69）。 */
    static final long CASH_STALE_DAYS = 7;

    /**
     * 账户视图（P2-交易69，2026-09-23）：在 {@link AccountSnapshot} 的字段之外，补两个**读侧拼装**的字段：
     * <ul>
     *   <li>{@code cashDate}——现金这个数对应的**券商快照日期**（{@link SnapshotAnchor#cashImport()}）。
     *       它与 {@code snapshotDate}（收盘更新的日期、每个交易日都会被刷新）**不是一回事**：现金只在导入
     *       「资金股份查询」时才更新，两者混用一个日期会让人误以为手上这个现金数是今天的。</li>
     *   <li>{@code cashNote}——现金健康度的一句人话（负现金 / 无券商来源 / 过期），null = 不必提示。</li>
     * </ul>
     * 起因是生产实据：09-11 导入真值 1,381.93 之后，系统在两次导入之间把现金漂成 **−6,093.97**
     * （09-15，负数）与 **24,101.01**（09-23），而券商真值只有 **414.86**——虚高 23,686.15、
     * 总盈亏少报 2.37 万，而用户侧**看不到任何提示**（REVIEW P2-交易64/69）。
     */
    public Map<String, Object> accountView(String userId) {
        // §9#4（2026-10-06）：读账户时懒触发一次存量本金迁移（幂等；失败不阻塞读，见该方法 javadoc）
        try {
            migrateLegacyPrincipal(userId);
        } catch (RuntimeException e) {
            log.warn("账户视图：本金迁移触发失败（按未迁移处理）| userId={} | {}", userId, e.getMessage());
        }
        AccountSnapshot s = accountSnapshot(userId);
        LocalDate cashDate = null;
        try {
            cashDate = anchorRepository.find(userId).cashImport();
        } catch (RuntimeException e) {
            log.warn("账户视图：读锚定失败，现金日期按未知处理 | userId={} | {}", userId, e.getMessage());
        }
        // P2-交易66（2026-09-23）：本金置信度——`principal` 是「手填值 + 已记录转账」推出来的，
        // 而 transfers.json 里只有 2026-09 三笔（2025-04 建仓以来的出入金零记录）→ 所以
        // 「总盈亏 = 资产 − 本金」不是账本能自证的数，如实标一句并给出补记路径。
        LocalDate earliestTransfer = null;
        try {
            earliestTransfer = transferRepository.findAll(userId).stream()
                    .map(TransferRecord::date).filter(java.util.Objects::nonNull)
                    .min(LocalDate::compareTo).orElse(null);
        } catch (RuntimeException e) {
            log.warn("账户视图：读转账失败，本金说明按最保守处理 | userId={} | {}", userId, e.getMessage());
        }
        LocalDate today = LocalDate.now();
        return accountViewOf(s, cashDate, today,
                principalNote(s.principal(), earliestTransfer, today));
    }

    /**
     * 纯函数版账户视图（可单测）：把快照 + 现金日期拼成对外的账户视图。
     * <p>
     * 手工列字段是为了**不动 AccountSnapshot 的 schema**（它有 19+ 处构造点），代价是可能漏字段
     * —— 故本方法由 `CashHealthNoteTest` 断言「快照的每个字段都在」+「两个新字段都在」。
     */
    /** 兼容重载（调用方不想给本金说明时用；principalNote 落 null = 不提示）。 */
    static Map<String, Object> accountViewOf(AccountSnapshot s, LocalDate cashDate, LocalDate today) {
        return accountViewOf(s, cashDate, today, null);
    }

    static Map<String, Object> accountViewOf(AccountSnapshot s, LocalDate cashDate, LocalDate today,
                                             String principalNote) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("assets", s.assets());
        m.put("cash", s.cash());
        m.put("available", s.available());
        m.put("withdrawable", s.withdrawable());
        m.put("marketValue", s.marketValue());
        m.put("pnl", s.pnl());
        m.put("todayPnl", s.todayPnl());
        m.put("principal", s.principal());
        m.put("snapshotDate", s.snapshotDate());
        m.put("todayPnlSource", s.todayPnlSource());
        m.put("cashDate", cashDate != null ? cashDate.toString() : "");
        m.put("cashNote", cashHealthNote(s.cash(), s.withdrawable(), cashDate, today));
        m.put("principalNote", principalNote);
        return m;
    }

    /** 现金健康度人话（null = 不用提示）。优先级：负现金 > 无券商来源 > 过期（P2-交易69）。 */
    static String cashHealthNote(BigDecimal cash, LocalDate cashDate, LocalDate today) {
        return cashHealthNote(cash, null, cashDate, today);
    }

    /** 现金侧异常人话（null = 无异常）。P2（独立审查 2026-10-03）：抽出来供 integrity 的 note 在**定稿前**使用。 */
    static String cashAlertOf(AccountSnapshot acct) {
        if (acct == null) return null;
        StringBuilder sb = new StringBuilder();
        if (acct.cash() != null && acct.cash().signum() < 0) {
            sb.append("⚠️ 现金为负（").append(acct.cash().stripTrailingZeros().toPlainString())
                    .append("）——现实中券商不可能出现，导一次「资金股份查询」对齐");
        }
        if (acct.withdrawable() != null && acct.withdrawable().signum() < 0) {
            if (sb.length() > 0) sb.append("；");
            sb.append("⚠️ 可取为负（").append(acct.withdrawable().stripTrailingZeros().toPlainString())
                    .append("）——已转出的多于可取（当日卖出所得要次一交易日才能取）");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 现金健康度人话（4 参版，RFC 20261003 C3，2026-10-03）：在「可用」之外补「可取」维度。
     * <p>
     * 为什么需要：A 股 T+1——**当日卖出所得可继续买（可用）但要次一交易日才能转出（可取）**。
     * 旧版只看 cash，于是「可取已经不够/为负」时账户卡不提示（用户 2026-10-03 提的症结：
     * 「只有卖出股票后，才有现金，才能转出」）。优先级：可用为负 > 可取为负 > 无券商来源 > 过期。
     */
    static String cashHealthNote(BigDecimal cash, BigDecimal withdrawable, LocalDate cashDate, LocalDate today) {
        if (cash != null && cash.signum() < 0) {
            // 负现金是「自证失败」的硬信号：真实账户不可能有负的可用资金（生产 09-15 出现过 −6,093.97）
            return "可用资金是负数（" + cash.stripTrailingZeros().toPlainString()
                    + "）——这个数不对，导一次「资金股份查询」就能对齐。";
        }
        if (withdrawable != null && withdrawable.signum() < 0) {
            return "可取资金是负数（" + withdrawable.stripTrailingZeros().toPlainString()
                    + "）——已经转出的多于可取（当日卖出的钱要次一交易日才能取）；"
                    + "导一次「资金股份查询」就能对齐。";
        }
        if (cashDate == null) {
            return "这个现金数还没有券商来源，导一次「资金股份查询」就能对齐。";
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(cashDate, today);
        if (days > CASH_STALE_DAYS) {
            return "现金还是 " + cashDate + " 的券商余额（" + days + " 天前），导一次「资金股份查询」对一下账。";
        }
        return null;
    }

    /**
     * 本金覆盖阈值（天）：转账记录最早一条若晚于「今天 − 180 天」，说明更早的出入金基本没记
     * （P2-交易66）——那就该如实说明「总盈亏是按你报的本金算出来的」。
     */
    static final long PRINCIPAL_TRAILING_DAYS = 180;

    /**
     * 本金置信度说明（P2-交易66，2026-09-23）。
     * <p>
     * 起因：用户导入资金快照后算出真实总盈亏 **−43,819.14**（106,180.86 − 150,000），但 `principal`
     * 是他**手填**的、`transfers.json` 只有 2026-09 三笔——2025-04 建仓以来的出入金**零记录**。
     * 也就是说这个 −4.38 万不是账本推出来的，而是「按你报的 15 万算出来的」：若历史另有转入/转出，
     * 真值同步变化。本条如实说清，并给出补记路径（资金页的转入/转出）。
     * <p>
     * null = 不必提示：未设本金（已有「设本金」引导）或转账记录覆盖得够久。
     */
    static String principalNote(BigDecimal principal, LocalDate earliestTransfer, LocalDate today) {
        if (principal == null || principal.signum() <= 0) return null;
        if (earliestTransfer != null
                && earliestTransfer.isBefore(today.minusDays(PRINCIPAL_TRAILING_DAYS))) return null;
        return "本金是按你填的数 + 已记录的转入转出算的；更早的出入金如果没记，总盈亏的基准会跟着偏"
                + "——可以在「资金」里补记。";
    }

    /**
     * 存量本金迁移的幂等标记前缀（2026-10-06，设计 §9#4 U2）：本金改由事件推出前，
     * 手填值里「转账净额解释不了」的差额一次性补记为出入金调整事件（cash-adjustments.json）。
     */
    static final String PRINCIPAL_MIGRATION_PREFIX = "adjmig_";

    /**
     * 存量本金迁移（2026-10-06，设计 §9#4）：写侧「设置本金」退役后，本金 = 转账净额 + 一次性迁移调整。
     * <p>
     * 口径：{@code residual = principal − Σ(转入 − 转出)}——历史手填值里转账事件解释不了的部分。
     * <ul>
     *   <li>{@code residual == 0} → 不变量已成立（本金 = 转账净额），无需迁移；</li>
     *   <li>{@code residual ≠ 0} 且尚无 {@code adjmig_} 前缀记录 → 补记一条调整事件（<b>不动账户快照</b>:
     *       那笔钱早含在券商快照里，这里只是把「基准来自何处」变成可审计的事实）；</li>
     *   <li>已有迁移记录 → 幂等跳过（永不复迁）。</li>
     * </ul>
     * <p>
     * <b>失败不阻塞读</b>：迁移失败只告警，账户读路径照常返回（下次读再试）；
     * 仓储未注入（兼容构造）时整体 no-op。触发点：{@link #accountView}（懒迁移，读时自查一次）。
     */
    void migrateLegacyPrincipal(String userId) {
        if (cashAdjustmentRepository == null) return;
        AccountSnapshot s = accountSnapshot(userId);
        BigDecimal principal = s.principal() != null ? s.principal() : BigDecimal.ZERO;
        synchronized (tradeLock(userId)) {
            BigDecimal transferNet = BigDecimal.ZERO;
            for (TransferRecord tr : transferRepository.findAll(userId)) {
                if (tr.amount() == null) continue;
                transferNet = transferNet.add(tr.isIn() ? tr.amount() : tr.amount().negate());
            }
            BigDecimal residual = principal.subtract(transferNet);
            if (residual.signum() == 0) return;
            try {
                boolean migrated = cashAdjustmentRepository.findAll(userId).stream()
                        .anyMatch(a -> a.id() != null && a.id().startsWith(PRINCIPAL_MIGRATION_PREFIX));
                if (migrated) return;
                String migrationId = IdGenerator.monotonic(PRINCIPAL_MIGRATION_PREFIX);
                // §4.3 审计（fail-visible）：先留痕、后落账——迁移是账面事实的永久证据
                if (auditRepository != null) {
                    auditRepository.append(userId, TradingAuditRepository.AuditEntry.of(
                            migrationId, "principal.migrate",
                            principal.stripTrailingZeros().toPlainString(),
                            transferNet.stripTrailingZeros().toPlainString(),
                            "存量本金迁移"));
                }
                cashAdjustmentRepository.append(userId, new CashAdjustment(
                        migrationId, LocalDate.now(), residual,
                        "存量本金迁移（历史出入金未逐笔记账）——本金改由转入/转出推出前的一次性差额补记，不计入现金差额",
                        "迁移时本金 " + principal.stripTrailingZeros().toPlainString()
                                + "，已记转账净额 " + transferNet.stripTrailingZeros().toPlainString(),
                        LocalDateTime.now()));
                log.info("存量本金迁移已落账 | userId={} | 差额 {} = 本金 {} − 转账净额 {}", userId,
                        residual.stripTrailingZeros().toPlainString(),
                        principal.stripTrailingZeros().toPlainString(),
                        transferNet.stripTrailingZeros().toPlainString());
            } catch (RuntimeException e) {
                log.warn("存量本金迁移失败（不阻塞读，下次读账户时会重试）| userId={} | {}", userId, e.getMessage());
            }
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
        // P2-交易43（2026-09-14）：解析层丢弃的行带行号/原文/原因上报（原来静默 continue，
        // 用户只看到「识别出 N 笔」而不知道同文件里还有行被丢了）
        TradingImportParser.HistoricalTradeParse parsed = TradingImportParser.parseHistoricalTradesDetailed(content);
        List<TradingImportParser.HistoricalTradeRow> rows = new ArrayList<>(parsed.rows());
        List<TradingImportParser.UnparsedLine> unparsedLines = parsed.unparsed();
        if (rows.isEmpty()) {
            String extra = unparsedLines.isEmpty() ? ""
                    : "（另有 " + unparsedLines.size() + " 行没能识别，首条：" + unparsedLines.get(0).describe() + "）";
            throw new TradingException("无法识别历史成交导出——请确认表头含「成交日期/证券代码/买卖标志」"
                    + "且为通达信历史成交查询导出" + extra);
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
            return withUnparsed(dryRunPlan(userId, rows, appendRows, replayRecent, anchorStatus,
                    effectiveMode, nonTradable), unparsedLines);
        }
        HistoricalTradeImportResult appendResult = null;
        if (!appendRows.isEmpty()) {
            appendResult = importAppend(userId, appendRows);
        }
        if (replayRecent.isEmpty()) {
            HistoricalTradeImportResult base = appendResult != null ? appendResult
                    : new HistoricalTradeImportResult(0, 0, 0, 0, List.of(), "append", null, List.of(), anchorStatus);
            refreshTodayPnl(userId);
            return withUnparsed(withExtras(base, base.nonTrades() + nonTradable, base.rejected(), anchorStatus),
                    unparsedLines);
        }
        HistoricalTradeImportResult syncResult = importSync(userId, replayRecent);
        HistoricalTradeImportResult base = appendResult != null ? appendResult
                : new HistoricalTradeImportResult(0, 0, 0, 0, syncResult.lines(), "sync", null, List.of(), anchorStatus);
        refreshTodayPnl(userId);
        List<RejectedLine> rejected = new ArrayList<>(base.rejected());
        rejected.addAll(syncResult.rejected());
        return withUnparsed(new HistoricalTradeImportResult(
                base.imported() + syncResult.imported(),
                base.updated() + syncResult.updated(),
                base.skipped() + syncResult.skipped(),
                base.nonTrades() + syncResult.nonTrades() + nonTradable,
                syncResult.lines(), "sync", syncResult.summary(), rejected, anchorStatus), unparsedLines);
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
    /**
     * C7 闸门（RFC 20261003 §三 C7，2026-10-03）：**锚定 fail-closed**。
     *
     * <p>锚定读不到（`snapshot-anchor.json` 缺失/损坏）时，系统**无法判断**哪些成交已包含在券商口径内——
     * 此时改账会静默双计（与 2026-09-12 事故同型：锚定文件不在 → 近日成交全走 replay → 现金被算成
     * −26,666.85）。原实现只在「历史成交回放」这一条路径 fail-closed，手动记录/批量/转账/截图确认
     * 仍然是 fail-open（照常改账）。
     *
     * <p>判据：**锚定未知 + 账上已有持仓或资金快照** → 拒绝并指路；**全新账号**（还没导过快照）放行，
     * 否则新用户无法从零开始记录。
     */
    private void requireAnchorKnownForLedgerChange(String userId, String action) {
        SnapshotAnchor anchor = anchorRepository.find(userId);
        if (AnchorStatus.of(anchor, anchorRepository.holdingsRecorded(userId)).known()) return;
        if (!hasExistingAccountState(userId)) return;   // 全新账号：还没有账目可比对，允许从零记
        throw new TradingException(action + "会改动持仓与现金，但券商快照锚定读不到"
                + "（trading/snapshot-anchor.json 缺失或损坏）——此时没法判断哪些成交已经包含在券商口径里，"
                + "继续记账可能把同一笔算两次。请先导一次「持仓股」或「资金股份查询」建立锚定；"
                + "如果只是想补逐笔流水（不动账），用「历史成交导入 · 仅补流水」");
    }

    private boolean hasExistingAccountState(String userId) {
        if (!positionRepository.findAll(userId).isEmpty()) return true;
        if (accountSnapshotRepository.findLatest(userId).isPresent()) return true;
        // ⚠️ P2-8（2026-10-03 增量深审）：只看「持仓 + 账户快照」会把**清仓后且从未导过资金**的老账号
        // 判成「全新」——锚定一旦读不到就放行改账，静默双计。有流水或有转账 = 账上已有账目，同样不算全新。
        if (!tradingHistoryRepository.findAll(userId).isEmpty()) return true;
        return !transferRepository.findAll(userId).isEmpty();
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
                nonTrades, r.lines(), r.syncMode(), r.summary(), rejected, anchor, r.unparsed());
    }

    /** P2-交易43：把解析层「没看懂的行」附到结果上（各分支统一出口，避免遗漏）。 */
    private HistoricalTradeImportResult withUnparsed(HistoricalTradeImportResult r,
                                                     List<TradingImportParser.UnparsedLine> unparsed) {
        if (unparsed == null || unparsed.isEmpty()) return r;
        return new HistoricalTradeImportResult(r.imported(), r.updated(), r.skipped(), r.nonTrades(),
                r.lines(), r.syncMode(), r.summary(), r.rejected(), r.anchor(), unparsed);
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
            appendLedgerOnlyRecord(userId, r.symbol(), r.name(), r.direction(), r.price(), r.volume(),
                    r.entryDate(), r.tradeTime(), r.orderId(), r.fee(),
                    "历史成交回放无法归属持仓：只记账不改账");
        } catch (RuntimeException e) {
            log.error("回放流水兜底写入失败（该笔未能留痕）| userId={} | {} {} {}股 | {}",
                    userId, r.direction(), r.symbol(), r.volume(), e.getMessage());
        }
    }

    /**
     * 只落流水、不动持仓与现金（2026-09-18，P0-交易59）：截图入账候选命中「券商快照锚定日」时的降级路径。
     *
     * <p>原实现是**硬拒**——{@code recordTradeInternal} 抛 TradingException「成交日期已包含在券商快照中」，
     * 用户白天的真实成交被整批挡回（生产实据：2026-09-18 六笔全拒、连点六次确认全拒），而
     * {@link #coveredByAnchor} 判的是「≤ 锚定日」、锚定日只增不减 → 这笔成交**永远补不回来**。
     *
     * <p>降级后：流水照落（只记账不改账）、持仓/现金以券商快照为准——成交永不丢失，也不会与快照双计
     * （因为不动持仓与现金）。与历史成交导入的 {@link #ledgerOnly} 完全同语义。
     *
     * @return true=已落流水；false=写入失败（调用方须保留候选，不得静默吞）
     */
    public boolean ledgerOnlyTrade(String userId, String symbol, String name, TradeDirection direction,
                                   BigDecimal price, int volume, LocalDate entryDate, LocalTime tradeTime,
                                   String orderId, BigDecimal fee) {
        // P1-3（2026-09-19 对抗审查）：**必须在 per-user 流水锁内写**——历史导入的 ledgerOnly 在
        // tradeLock 里；本方法原先直调 appendTradeRecord（流水仓储是 read→add→write、无锁），
        // app 与 web 同时确认会互相覆盖、静默少一笔流水。
        // P0-2（同批）：消费 appendTradeRecord 的**真实结果**（它现在返回 boolean），
        // 不再恒返回 true（原来写失败也报「已记进流水」并把候选清掉）。
        synchronized (tradeLock(userId)) {
            boolean ok = appendLedgerOnlyRecord(userId, symbol, name, direction, price, volume, entryDate,
                    tradeTime, orderId, fee, "截图候选命中券商快照锚定日：只记账不改账");
            if (!ok) {
                log.error("锚定降级：流水兜底写入失败（该笔未能留痕，候选将保留）| userId={} | {} {} {}股",
                        userId, direction, symbol, volume);
            }
            return ok;
        }
    }

    /**
     * **只落流水、不动现金**的降级写入（RFC 20261003 C5，2026-10-03）：标记 {@code cashApplied=false}
     * 与原因，「这笔动过钱没有」从此可查询（此前降级行与正常流水在文件里完全无法区分）。
     */
    private boolean appendLedgerOnlyRecord(String userId, String symbol, String name, TradeDirection direction,
                                           BigDecimal price, int volume, LocalDate entryDate, LocalTime tradeTime,
                                           String orderId, BigDecimal fee, String ledgerOnlyReason) {
        try {
            TradeRecord trade = TradeRecord.ledgerOnly(
                    IdGenerator.monotonic("trade_"), symbol, name, direction, price, volume,
                    entryDate, tradeTime, null, fee, null, orderId, ledgerOnlyReason);
            tradingHistoryRepository.append(userId, trade);
            return true;
        } catch (Exception e) {
            log.warn("降级流水写入失败（不影响主流程）| symbol={} | {}", symbol, e.getMessage());
            return false;
        }
    }

    /** 该日期是否已被券商快照锚定覆盖（防重复入账）；2026-09-18（P0-交易59）起 confirm 命中它走降级而非硬拒。 */
    public boolean isCoveredByAnchor(String userId, LocalDate entryDate) {
        return coveredByAnchor(userId, entryDate);
    }

    /**
     * 候选确认用的 per-user 锁（2026-09-19，对抗审查 P1-4）。
     *
     * <p>`confirm` 的「判重 → 锚定分派 → 落账」必须**整体串行**：原实现在锁外先
     * `findRecordedTrade`（并发时两方都拿到 empty），再调 `recordTradeWithOrderId`（后者才进锁）
     * → 两个客户端同时点「确认入账」时同一笔能通过两次检查、落两次账（持仓/现金双计，
     * 正是 pitfalls 里的「检查-再动作竞态」）。
     *
     * <p>暴露锁对象而不是包一层方法，是为了保持 `confirm` 既有的分派结构与各分支可见性。
     * `recordTradeWithOrderId` 内部用的是**同一把**锁 → 可重入，不会自锁。
     */
    public Object candidateLock(String userId) {
        return tradeLock(userId);
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
                    // ⚠️ P1（2026-10-03 增量深审，**既有缺陷**）：append 补录路径原先**无条件**加现金，
                    // 而 importSync 路径有 coveredByAnchor 保护 → 锚定日内的股息会被二次计入。
                    // 现在两条路径同口径：**锚定覆盖到的日期只落流水、不动现金**（股息也照此）。
                    if (TradingImportParser.isDividendEvent(r)) {
                        if (r.entryDate() != null && coveredByAnchor(userId, r.entryDate())) {
                            log.info("股息行落在锚定日内，只落流水不改现金 | userId={} | {} | {}",
                                    userId, r.symbol(), r.entryDate());
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
                // RFC 20261003 C5（2026-10-03）：补录行**只记账不改账**（不动持仓与现金）→ 必须带
                // cashApplied=false 与原因，否则事后无法区分（存量盘点清单 A 的根因）。
                TradeRecord trade = TradeRecord.ledgerOnly(
                        IdGenerator.monotonic("trade_"),
                        r.symbol(), r.name(), r.direction(), r.price(), r.volume(),
                        r.entryDate(), r.tradeTime(), null, r.fee(), null, r.orderId(),
                        "历史成交补录（append 模式）：只记账不改账");
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
                // RFC 20261003 C3（A 档）：股息/红利税属非交易性资金变动，与「卖出回款」同规则——
                // 先只进「可用」池，「可取」待券商快照刷新（A 股 T+1 语义）。
                accountSnapshotRepository.update(userId, cur -> cur.map(c -> new AccountSnapshot(
                        c.assets().add(occurred),
                        c.cash().add(occurred),
                        c.available().add(occurred),
                        c.withdrawable(),
                        c.marketValue(), c.pnl(), c.todayPnl(), c.principal(), c.snapshotDate(),
                        c.todayPnlSource()))
                        .orElse(null)); // 无账户快照（未导入资金）不初始化，保持既有语义
                // 落流水可回溯：direction = 入账 BUY / 税 SELL，volume 0，amount = 发生金额绝对值，reason = 源文件备注
                TradeDirection dir = occurred.signum() > 0 ? TradeDirection.BUY : TradeDirection.SELL;
                // RFC 20261003 C5：股息/红利税**确实动了现金** → 显式标记 true（原来是兼容构造的 null =
                // 未标记；对账重放时会把「真的动过钱」当成「不知道」，让现金对账无法自洽）。
                TradeRecord tr = new TradeRecord(
                        IdGenerator.monotonic("trade_"), r.symbol(), r.name(), dir,
                        BigDecimal.ZERO, 0, occurred.abs(), r.entryDate(), r.tradeTime(),
                        null, null, null, r.remark(), null, LocalDateTime.now(), null, null,
                        true, null);
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
                                              List<RejectedLine> rejected, AnchorStatus anchor,
                                              List<TradingImportParser.UnparsedLine> unparsed) {
        public HistoricalTradeImportResult {
            if (unparsed == null) unparsed = List.of();
        }

        /** 兼容旧 9 参构造（无「没看懂的行」上报）。 */
        public HistoricalTradeImportResult(int imported, int updated, int skipped, int nonTrades,
                                           List<ReconcileLine> lines, String syncMode,
                                           DailyOperationSummary summary,
                                           List<RejectedLine> rejected, AnchorStatus anchor) {
            this(imported, updated, skipped, nonTrades, lines, syncMode, summary, rejected, anchor,
                    List.of());
        }

        /** 兼容旧 7 参构造（rejected/anchor 缺省的内部中间结果）。 */
        public HistoricalTradeImportResult(int imported, int updated, int skipped, int nonTrades,
                                           List<ReconcileLine> lines, String syncMode,
                                           DailyOperationSummary summary) {
            this(imported, updated, skipped, nonTrades, lines, syncMode, summary, List.of(), null,
                    List.of());
        }

        /** 兼容旧 5 参构造（补录模式无总结）。 */
        public HistoricalTradeImportResult(int imported, int updated, int skipped, int nonTrades,
                                           List<ReconcileLine> lines) {
            this(imported, updated, skipped, nonTrades, lines, null, null, List.of(), null, List.of());
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
                               boolean known, boolean holdingsKnown,
                               // 2026-10-05（P2-交易84）：锚定日的**依据**（有据/无据）+ 人话说明——
                               // 老数据（依据未记录）→ null，前端不得据此冒充确定。
                               String positionsBasis, String cashBasis, String basisNote) {

        /** 兼容构造（依据未记录 —— 老落盘文件 / 既有测试与旧调用零改动）。 */
        public AnchorStatus(LocalDate positionsReplace, LocalDate cashImport,
                            boolean known, boolean holdingsKnown) {
            this(positionsReplace, cashImport, known, holdingsKnown, null, null, null);
        }

        static AnchorStatus of(SnapshotAnchor a, boolean holdingsKnown) {
            AnchorBasis pb = a.positionsBasis();
            AnchorBasis cb = a.cashBasis();
            LocalDate latest = a.latest();
            // 取「生效锚定日」那一边的依据；都记了但只有一边生效时以生效边为准
            AnchorBasis effective = latest != null && latest.equals(a.positionsReplace()) ? pb : cb;
            if (effective == null) effective = pb != null ? pb : cb;
            String note = effective == null || latest == null ? null
                    : latest + "：" + effective.label() + (effective.withEvidence() ? "（有据）" : "（无据）");
            return new AnchorStatus(a.positionsReplace(), a.cashImport(), a.known(), holdingsKnown,
                    pb != null ? pb.name() : null, cb != null ? cb.name() : null, note);
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

    /** 「只记了流水、没进持仓」的降级成交（2026-09-21，P1-交易61）：把「锚定日被推断」导致的假绿变成可见。
     *  {@code inferred=true} 表示锚定日是推断出来的 → 前端按**警告**呈现；false = 已含在快照内的事实说明。 */
    public record DegradedLine(String symbol, String name, TradeDirection direction, int volume,
                               BigDecimal price, LocalDate entryDate, boolean inferred, String reason) {}

    /** 账实一致性报告（GET /trading/integrity）：锚定状态 + 差异 + 重放缺口 + 降级流水。 */
    public record IntegrityReport(AnchorStatus anchor, boolean holdingsKnown, List<DriftLine> drift,
                                  List<RejectedLine> gaps, List<DegradedLine> degraded, String note) {

        /** 兼容构造（无降级流水）：旧调用与既有测试零改动。 */
        public IntegrityReport(AnchorStatus anchor, boolean holdingsKnown, List<DriftLine> drift,
                               List<RejectedLine> gaps, String note) {
            this(anchor, holdingsKnown, drift, gaps, List.of(), note);
        }
    }

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

    /** 清仓导入结果。
     *  <p>P2-交易83（2026-10-04）：{@code unparsedRows} = 解析层没看懂的行（每条「第 N 行「原文」：原因」，
     *  行号 = 原文件行号，1 起算）——对齐 P2-交易43 历史成交回执口径；丢一行 = 该只清仓档案本次没进库。</p>
     */
    public record SoldImportResult(int imported, List<String> unparsedRows) {
        public SoldImportResult {
            if (unparsedRows == null) unparsedRows = List.of();
        }

        /** 兼容旧 1 参构造（无丢行明细）。
         *  @deprecated 生产路径**必须**带明细（{@code SoldImportResult(int, List)}）——只给条数等于把
         *              「哪几行被丢了」藏起来（P2-交易83 的根因）。此构造仅供既有测试/外部兼容调用，
         *              新代码不要再走。 */
        @Deprecated
        public SoldImportResult(int imported) {
            this(imported, List.of());
        }
    }

    /** 资金导入结果。
     *  <p>P2-交易83（2026-10-04）：{@code unparsed} = 明细里没看懂的行（每条「第 N 行「原文」：原因」，
     *  行号 = 原文件行号，1 起算）——丢一行 = 该只持仓的精确成本本次不更新。
     *  旧接口只给 int 计数，用户不知道是哪只。</p>
     */
    public record CashImportResult(java.math.BigDecimal cash, java.math.BigDecimal assets,
                                   int updatedCost, List<String> unparsed, AnchorDecision anchor) {
        public CashImportResult {
            if (unparsed == null) unparsed = List.of();
        }

        /** 兼容构造（无锚定信息：既有测试 / 外部调用零改动）。 */
        public CashImportResult(java.math.BigDecimal cash, java.math.BigDecimal assets,
                                int updatedCost, List<String> unparsed) {
            this(cash, assets, updatedCost, unparsed, null);
        }

        /** 丢行**条数**——沿用 P2-交易45 的 int 语义（旧响应字段 `unparsedRows` 与既有调用点/测试不变）。 */
        public int unparsedRows() {
            return unparsed.size();
        }

        /** 兼容旧 3 参构造（无丢行明细）。
         *  @deprecated 生产路径**必须**带明细（{@code CashImportResult(BigDecimal, BigDecimal, int, List)}）
         *              ——只给计数，用户不知道是哪只的精确成本没更新（P2-交易83）。此构造仅供既有测试/
         *              外部兼容调用，新代码不要再走。 */
        @Deprecated
        public CashImportResult(java.math.BigDecimal cash, java.math.BigDecimal assets, int updatedCost) {
            this(cash, assets, updatedCost, List.of());
        }
    }


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
