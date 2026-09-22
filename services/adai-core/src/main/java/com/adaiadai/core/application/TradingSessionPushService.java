package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.AccountSnapshot;
import com.adaiadai.core.domain.trading.AccountSnapshotRepository;
import com.adaiadai.core.domain.trading.AdviceEntry;
import com.adaiadai.core.domain.trading.AdviceHistoryRepository;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeRecord;
import com.adaiadai.core.domain.trading.TradingMarketStage;
import com.adaiadai.core.domain.trading.TradingSyncState;
import com.adaiadai.core.domain.trading.WatchlistItem;
import com.adaiadai.core.domain.trading.WatchlistRepository;
import com.adaiadai.core.domain.trading.engine.PositionVerdict;
import com.adaiadai.core.domain.trading.engine.StopLossVerdict;
import com.adaiadai.core.domain.trading.engine.TradingRuleEngine;
import com.adaiadai.core.domain.trading.market.MarketData;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import com.adaiadai.core.infrastructure.storage.PushSettingsRepository;
import com.adaiadai.core.infrastructure.storage.TradingMarketStageRepository;
import com.adaiadai.core.infrastructure.storage.TradingSyncStateRepository;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.adaiadai.core.kernel.push.PushChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * TradingSessionPushService — 交易时段的**决策时点对话式提醒**（RFC `20260922-trading-decision-copilot` B 批）。
 *
 * <p>用户 2026-09-21 亲述：「我一般习惯**早盘买，尾盘卖**，不太会在中间时间放飞和止损，
 * 所以需要阿呆**提醒我，给我意见，尤其给我铁证，通过我之前的操作**」。B 批把原来四节点等频的
 * 「报表式推送」改造成他要的形态：
 *
 * <table border="1">
 *   <tr><th>时点</th><th>回答</th><th>性质</th><th>本类入口</th></tr>
 *   <tr><td>早盘 09:15</td><td>买点什么（含自选买点扫描）</td><td><b>提醒（决策）</b>——四要素铁证</td>
 *       <td>{@link #morningPlan()}</td></tr>
 *   <tr><td>午间 12:00</td><td>现在是什么状态</td><td><b>知会</b>——只报事实与位置，不催操作；无异常不发</td>
 *       <td>{@link #middayTracking()}</td></tr>
 *   <tr><td>尾盘 14:50</td><td>要不要卖</td><td><b>提醒（决策）</b>——四要素铁证 + 账日期标注</td>
 *       <td>{@link #closeAdvice()}</td></tr>
 *   <tr><td>收盘后</td><td>今天做完了什么</td><td><b>复盘</b>——<b>数据同步完成才出</b>（未同步如实说）</td>
 *       <td>{@link #closeSummaryPush()} / {@link #afterDataSync(String)}</td></tr>
 * </table>
 *
 * <p><b>正文一律确定性渲染，不再走 LLM</b>：四要素必须「逐条能指到」（RFC §八 1），
 * 而 LLM 既无法保证数字与原文逐字，也无法保证「缺证据就不说」——这两条恰是用户要的「铁证」的全部意义。
 * 于是本类只做编排与判定，文字由 {@link TradingDecisionNarrator} 按要素拼装。
 *
 * <p><b>三条硬约束</b>（RFC §五「关键推论」）：
 * <ol>
 *   <li><b>盘中不读「今天的账」</b>——它还不存在（用户收盘后才导）。所有涉及账的文案都标**账的日期**
 *       （「按你 09-19 收盘的账」）；拿旧账当新账是最危险的谎。</li>
 *   <li><b>行情失败必须显式</b>（P1-交易60，本批一并收口）：双源都失败时不发带空洞数字的推送，
 *       改为「今天行情没取到，这条我暂时给不了」——<b>不知道 ≠ 没问题</b>。</li>
 *   <li><b>复盘不是到点就发</b>：它是「数据同步完成后」的产物（用户拍板 D4，接受晚于 15:30）。</li>
 * </ol>
 *
 * <p><b>不制造噪音</b>：早盘无买点信号只发持仓概览；尾盘没有触发卖出条件的票就不发；
 * 午间无异常不发；统计样本不足时直说「样本还不够」（RFC §八 5）。
 */
@Service
public class TradingSessionPushService {

    private static final Logger log = LoggerFactory.getLogger(TradingSessionPushService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 早盘计划 / 午间知会 / 尾盘卖点 cron（工作日）。 */
    static final String CRON_MORNING = "0 15 9 * * MON-FRI";
    static final String CRON_MIDDAY = "0 0 12 * * MON-FRI";
    static final String CRON_CLOSE = "0 50 14 * * MON-FRI";
    /** 收盘复盘兜底 cron（P2-用户3，2026-08-29）：15:30——B3 之后它只负责「到点检查」：
     *  已同步 → 出复盘；未同步 → 如实说「还没看到你的账」（用户拍板 D4）。 */
    static final String CRON_CLOSE_SUMMARY = "0 30 15 * * MON-FRI";

    /** 收盘后「同步即出复盘」的起始时刻（B3）：15:00 之后的导入立刻出复盘；
     *  之前的导入只记状态，留给 15:30 兜底（那时账最新，避免「先导持仓就发一条、成交随后才导」的残缺复盘）。 */
    static final LocalTime REVIEW_TRIGGER_FROM = LocalTime.of(15, 0);

    private final PositionRepository positionRepository;
    private final MarketDataSource marketDataSource;
    private final AccountRepository accountRepository;
    private final PluginService pluginService;
    private final TradingRuleEngine ruleEngine;
    private final List<PushChannel> pushChannels;
    private final AccountSnapshotRepository accountSnapshotRepository;
    private final WatchlistBuyPointService buyPointService;
    private final WatchlistRepository watchlistRepository;
    /** RFC 20260817：推送开关（用户可关闭各类型推送）。 */
    private final PushSettingsRepository pushSettingsRepository;
    /** RFC 20260817：交易日志自动归集（收盘确认推送）。 */
    private final TradeLogCollectService tradeLogCollectService;
    /** P2-用户3（2026-08-29）：收盘复盘统计今日成交（getTradeHistory 过滤股息流水）。 */
    private final TradingAppService tradingAppService;
    /** v3.41（2026-09-04）：活跃市值区间（用户手动判定）——择时状态三级读取第 1 级，权威于 current.md。 */
    private final TradingMarketStageRepository marketStageRepository;
    /** RFC 20260905 B①：建议留痕——时段推送的确定性逐票建议也落 AdviceEntry（💥1 对抗审 2026-09-05：
     *  尾盘建议不留痕 → 遵守率分母空、复盘对照段永不出现）。 */
    private final AdviceHistoryRepository adviceHistoryRepository;
    /** RFC 20260922 B 批：四要素铁证渲染（① 历史统计 / ② 数字 / ③ 规则原文 / ④ 位置）。 */
    private final TradingDecisionNarrator narrator;
    /** RFC 20260922 B 批 B3：账同步状态与「复盘已发」标记（复盘改由同步触发，不再到点硬发）。 */
    private final TradingSyncStateRepository syncStateRepository;
    /** RFC 20260922 B 批 B3：复盘要回看用户今日了结的回合（sold.json）。 */
    private final SoldTradeRepository soldTradeRepository;
    /** RFC 20260922 B 批 B1：①「该形态的历史统计」取数（与 narrator 同一口径）。 */
    private final TradingEvidenceService evidenceService;
    /** 择时状态来源：knowledge/context/current.md（G-4 后路径，配置驱动——生产 /opt/adaios/os/... 由 .env 注入）。 */
    private final Path currentMd;

    public TradingSessionPushService(PositionRepository positionRepository,
                                     MarketDataSource marketDataSource,
                                     AccountRepository accountRepository,
                                     PluginService pluginService,
                                     TradingRuleEngine ruleEngine,
                                     List<PushChannel> pushChannels,
                                     AccountSnapshotRepository accountSnapshotRepository,
                                     WatchlistBuyPointService buyPointService,
                                     WatchlistRepository watchlistRepository,
                                     PushSettingsRepository pushSettingsRepository,
                                     TradeLogCollectService tradeLogCollectService,
                                     TradingAppService tradingAppService,
                                     TradingMarketStageRepository marketStageRepository,
                                     AdviceHistoryRepository adviceHistoryRepository,
                                     TradingDecisionNarrator narrator,
                                     TradingSyncStateRepository syncStateRepository,
                                     SoldTradeRepository soldTradeRepository,
                                     TradingEvidenceService evidenceService,
                                     @Value("${adai.knowledge.trading-engine-path:../../os/trading-engine/knowledge/context}") String knowledgeDir) {
        this.positionRepository = positionRepository;
        this.marketDataSource = marketDataSource;
        this.accountRepository = accountRepository;
        this.pluginService = pluginService;
        this.ruleEngine = ruleEngine;
        this.pushChannels = pushChannels;
        this.accountSnapshotRepository = accountSnapshotRepository;
        this.buyPointService = buyPointService;
        this.watchlistRepository = watchlistRepository;
        this.pushSettingsRepository = pushSettingsRepository;
        this.tradeLogCollectService = tradeLogCollectService;
        this.tradingAppService = tradingAppService;
        this.marketStageRepository = marketStageRepository;
        this.adviceHistoryRepository = adviceHistoryRepository;
        this.narrator = narrator;
        this.syncStateRepository = syncStateRepository;
        this.soldTradeRepository = soldTradeRepository;
        this.evidenceService = evidenceService;
        this.currentMd = Paths.get(knowledgeDir, "current.md").toAbsolutePath().normalize();
        log.info("时段推送：择时状态来源 current.md = {}", currentMd);
    }

    // ── 交易日 ──

    /**
     * P3（2026-08-17）+ B5-1（2026-08-23）：A 股法定节假日休市日——节假日不推送/不改账。
     * cron 已排除周末（MON-FRI），此处只登记**落在工作日**的休市日（周末无需登记）。
     * 2026 按沪深交易所官方通知（2025-12-22 发布）；2027 为预测（官方通常年底发布），
     * 临时调休/追加休市不追（以官方最终通知为准）。
     */
    static final java.util.Set<LocalDate> HOLIDAYS = java.util.Set.of(
            // ── 2026（官方，沪深交易所 2025-12-22 通知）──
            // 元旦 1/1(四)~1/3(六) → 工作日：1/1、1/2
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2),
            // 春节 2/15(日)~2/23(一) → 工作日：2/16(一)~2/20(五)、2/23(一)
            LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 17),
            LocalDate.of(2026, 2, 18), LocalDate.of(2026, 2, 19),
            LocalDate.of(2026, 2, 20), LocalDate.of(2026, 2, 23),
            // 清明 4/4(六)~4/6(一) → 工作日：4/6(一)
            LocalDate.of(2026, 4, 6),
            // 劳动节 5/1(五)~5/5(二) → 工作日：5/1(五)、5/4(一)、5/5(二)
            LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 4),
            LocalDate.of(2026, 5, 5),
            // 端午 6/19(五)~6/21(日) → 工作日：6/19(五)
            LocalDate.of(2026, 6, 19),
            // 中秋 9/25(五)~9/27(日) → 工作日：9/25(五)
            LocalDate.of(2026, 9, 25),
            // 国庆 10/1(四)~10/7(三) → 工作日：10/1(四)、10/2(五)、10/5(一)~10/7(三)（10/8 开市，旧表误记）
            LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2),
            LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6),
            LocalDate.of(2026, 10, 7),
            // ── 2027（预测，官方通常年底发布）──
            // 元旦 1/1(五)
            LocalDate.of(2027, 1, 1),
            // 春节 2/3(三,除夕)~2/9(二) → 工作日：2/3(三)~2/5(五)、2/8(一)、2/9(二)
            LocalDate.of(2027, 2, 3), LocalDate.of(2027, 2, 4),
            LocalDate.of(2027, 2, 5), LocalDate.of(2027, 2, 8),
            LocalDate.of(2027, 2, 9),
            // 清明 4/4(日)~4/6(二) → 工作日：4/5(一)、4/6(二)
            LocalDate.of(2027, 4, 5), LocalDate.of(2027, 4, 6),
            // 劳动节 5/1(六)~5/5(三) → 工作日：5/3(一)~5/5(三)
            LocalDate.of(2027, 5, 3), LocalDate.of(2027, 5, 4),
            LocalDate.of(2027, 5, 5),
            // 端午 6/9(三)（预测单日）
            LocalDate.of(2027, 6, 9),
            // 中秋 9/15(三)（2027 农历八月十五，预测单日——C2 修正：不在国庆，独立 9 月）
            LocalDate.of(2027, 9, 15),
            // 国庆 10/1(五)~10/7(四) → 工作日：10/1(五)、10/4(一)~10/7(四)（10/8 开市——C2 修正：2027 中秋不在国庆，无 8 天长假）
            LocalDate.of(2027, 10, 1), LocalDate.of(2027, 10, 4),
            LocalDate.of(2027, 10, 5), LocalDate.of(2027, 10, 6),
            LocalDate.of(2027, 10, 7)
    );

    /**
     * 是否交易日 —— <b>仅查法定节假日表，不判周末</b>。
     * <p>
     * ⚠️ <b>调用前提（务必先读）</b>：它的调用方全是 `@Scheduled(cron = …MON-FRI)` 的定时任务，
     * <b>周末由 cron 表达式排除</b>，所以本方法不需要（也刻意没有）判周末。
     * <b>非 cron 路径（HTTP 触发、测试直调）禁止直接用</b>——否则周六会被判成交易日。
     * 2026-09-13 生产实测事故正是这么来的：`refreshTodayPnl` 在<b>周六</b>被历史成交导入触发，
     * 用「周末行情接口给的最后两个交易日收盘」算出「当日浮动」，把周五的涨跌（且当时持仓还被双计污染）
     * 写成 −2837.00 当作「当日盈亏」挂了整整两天（真值 −1759.00）。
     * 这类路径请改用 {@link #isTradingDayStrict(LocalDate)}。
     */
    static boolean isTradingDay(LocalDate date) {
        return !HOLIDAYS.contains(date);
    }

    /**
     * 是否交易日 —— <b>自证版：周末 + 法定节假日都不算</b>。
     * <p>
     * 与 {@link #isTradingDay} 的区别只有一点：**不依赖「调用方已按工作日调度」这个前提**，
     * 因此可以被任何路径（含 HTTP 触发的重算）安全复用。两者对工作日的判断完全一致。
     */
    static boolean isTradingDayStrict(LocalDate date) {
        java.time.DayOfWeek dow = date.getDayOfWeek();
        if (dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY) return false;
        return !HOLIDAYS.contains(date);
    }

    /** 上一交易日（早盘买点的新鲜度基准：09:15 时最近一根已收盘 K 线就该是它）。 */
    static LocalDate previousTradingDay(LocalDate today) {
        LocalDate d = today.minusDays(1);
        while (!isTradingDayStrict(d)) d = d.minusDays(1);
        return d;
    }

    // ── B1 · 早盘（买点什么）────────────────────────────────────────────

    /**
     * 早盘 09:15：**持仓概览 + 今天进了你条件的买点**，一条推送。
     * <p>
     * 用户明确选择「买点提醒并入早盘」（RFC §3.1）：原来 15:10 那条独立的「买点提醒」不再单独推——
     * 他是早盘买的人，昨天收盘触发的信号该在他要动手的时刻出现，而不是在他已经收工之后。
     * 买点逐条带四要素铁证；凑不齐证据的那一只**不出现**（宁可不给，也不给假的）。
     */
    @Scheduled(cron = "${adai.trading.session.morning-cron:" + CRON_MORNING + "}")
    public void morningPlan() {
        if (!isTradingDay(LocalDate.now())) return;
        forEachTradingUser(userId -> {
            SessionData data = loadData(userId);
            // B4（P1-交易60）：持仓行情整体取不到 → 正文里的数字就没一个是可信的，整条显式降级
            if (!data.positions().isEmpty() && !quotesUsable(data)) {
                pushToAll(userId, "早盘计划", MARKET_UNAVAILABLE_BODY, "session", null, null,
                        MARKET_UNAVAILABLE_LOCK);
                return;
            }
            String content = buildMorningContent(userId, data);
            // P0-1（2026-09-14 增量深审）：正文含持仓名/现价/止损 → 锁屏只留「有几件事」
            pushToAll(userId, "早盘计划", content, "session", null, null,
                    "早盘计划备好了。有几只今天要留意，打开阿呆看看。");
        });
    }

    private String buildMorningContent(String userId, SessionData data) {
        StringBuilder sb = new StringBuilder("📋 早盘计划\n");
        if (data.positions().isEmpty()) {
            sb.append("今天还没有持仓，空仓也是一种策略——等待好的买点，不着急。\n");
        } else {
            sb.append("按你 ").append(accountDayLabel(data)).append(" 的账（持仓 ")
                    .append(data.positions().size()).append(" 只）：\n");
            for (Position p : data.positions()) {
                MarketData md = data.quotes().get(p.symbol());
                if (md == null || md.price() == null) {
                    // B4：这一只没取到 → 说「这只我说不了」，绝不回落到存储旧价冒充「昨收」
                    sb.append("· ").append(p.name()).append("：这只的行情我没取到，今天给不了数字\n");
                    continue;
                }
                sb.append("· ").append(p.name()).append(" 昨收 ").append(fmt(md.price()))
                        .append("（").append(signed(md.changePercent())).append("%）")
                        .append(" · 数量 ").append(p.quantity())
                        .append(" · 成本 ").append(fmt(p.avgCost()))
                        .append(" · 止损 ").append(p.effectiveStopLoss() != null
                                ? fmt(p.effectiveStopLoss()) : "未设置");
                var sl = ruleEngine.evaluateStopLoss(userId, md.price(), p.effectiveStopLoss());
                if (sl.verdict() == StopLossVerdict.BREACHED) sb.append(" ⚠️ 已在止损位下方（R66）");
                sb.append("\n");
            }
            sb.append("择时：").append(data.marketStage()).append("\n");
        }
        String buySection = buildBuyPointSection(userId);
        if (buySection != null) sb.append("\n").append(buySection);
        return sb.toString().strip();
    }

    /**
     * 买点段（RFC §3.1）：自选扫描 → 逐条四要素。返回 null = 这一段不该出现（不制造噪音）。
     * <p>
     * 新鲜度口径（早盘版）：判定所用的最后一根 K 线必须是**最近一个已收盘交易日**（或今天，盘后重跑）。
     * 原 15:10 那条要求「dataDate == 今天」，挪到 09:15 后那个判据必然全拦——早盘的「最近一根」
     * 本来就该是昨天。真正的红灯是**比上一交易日还旧**（tdx 数据包滞后那类，REVIEW 有前科）。
     */
    private String buildBuyPointSection(String userId) {
        // 尊重用户关掉的「买点提醒」开关：合并进早盘不等于强迫他收买点
        if (!pushSettingsRepository.findByUser(userId).isEnabled("buy-point")) return null;
        List<WatchlistItem> watchlist = watchlistRepository.findAll(userId);
        if (watchlist.isEmpty()) return null;
        WatchlistBuyPointService.ScanResult scan = buyPointService.scanWatchlistDetailed(watchlist, userId);
        LocalDate expected = previousTradingDay(LocalDate.now());
        List<WatchlistBuyPointService.WatchBuyPoint> hits = scan.hits().stream()
                .filter(h -> !"case".equals(h.buyPoint()))       // 案例相似度是参考，不是规则命中（不推）
                .filter(h -> !h.buyPoint().endsWith("?"))        // B1? 候选不推（P2-交易7 的「不硬推」）
                .filter(h -> freshEnough(h.dataDate(), expected))
                .toList();
        List<WatchlistBuyPointService.Unavailable> unavailable = scan.unavailable();
        // 行情整体失败（半数以上取不到）就算没命中也要说一声；零星的失败只在有命中时附带说明
        boolean marketWideFailure = !unavailable.isEmpty()
                && unavailable.size() >= Math.max(1, watchlist.size() / 2);
        if (hits.isEmpty() && !marketWideFailure) return null;

        StringBuilder sb = new StringBuilder();
        if (!hits.isEmpty()) {
            Map<String, MarketData> quotes = quoteOf(hits.stream()
                    .map(WatchlistBuyPointService.WatchBuyPoint::symbol).toList());
            sb.append("今天有 ").append(hits.size()).append(" 只进了你的条件——\n");
            int unrenderable = 0;
            for (WatchlistBuyPointService.WatchBuyPoint hit : hits) {
                MarketData md = quotes.get(hit.symbol());
                Optional<TradingDecisionNarrator.Block> block =
                        narrator.buyPointBlock(userId, hit, md);
                if (block.isPresent()) {
                    sb.append(block.get().text()).append("\n");
                } else if (md == null || md.price() == null) {
                    // B4：这一只的行情没取到 → 点名说清（比笼统的「拿不出依据」有用）
                    sb.append("· ").append(hit.name()).append("（").append(hit.symbol())
                            .append("）：这只的行情我没取到，先不给你数字\n");
                } else {
                    unrenderable++;
                }
            }
            if (unrenderable > 0) {
                // 有信号但拿不出四要素（行情没取到 / 该形态在规则库没有可逐字引用的原文）——如实说，不硬编
                sb.append("（另有 ").append(unrenderable).append(" 只我这次拿不出完整依据，先不给你数字）\n");
            }
        }
        if (!unavailable.isEmpty()) {
            sb.append("有 ").append(unavailable.size()).append(" 只自选我没取到行情（")
                    .append(unavailable.stream().map(WatchlistBuyPointService.Unavailable::name)
                            .limit(3).collect(Collectors.joining("、")))
                    .append(unavailable.size() > 3 ? " 等" : "")
                    .append("），这次没算进来。\n");
        }
        return sb.toString().strip();
    }

    /** 信号新鲜度（早盘口径）：数据末端不早于「最近一个已收盘交易日」。 */
    static boolean freshEnough(String dataDate, LocalDate expected) {
        if (dataDate == null || dataDate.isBlank()) return false;
        try {
            return !LocalDate.parse(dataDate).isBefore(expected);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ── B5 · 午间（知会，不是提醒）────────────────────────────────────────

    /**
     * 午间 12:00：**知会**（用户拍板 D1「不砍」，但重新定位）。
     * <p>
     * 他的节奏是「盘中不放飞、不止损」，所以这条**不能写成提醒**——否则每天中午都在对他说
     * 「现在该动手」，与他的用法直接相悖。这里只陈述事实与位置（现价 / 涨跌 / 有没有到他设的止损位），
     * **不出现任何催促**（该不该卖归尾盘那条）。
     * <p>
     * <b>无异常（没有触及止损、也没有持仓）→ 不发</b>：保留 ≠ 天天发（RFC §3.5）。
     * 行情取不到时也**不发**（知会性质，没有可信位置就没什么可知会的；不发即不误导）。
     */
    @Scheduled(cron = "${adai.trading.session.midday-cron:" + CRON_MIDDAY + "}")
    public void middayTracking() {
        if (!isTradingDay(LocalDate.now())) return;
        forEachTradingUser(userId -> {
            SessionData data = loadData(userId);
            if (data.positions().isEmpty()) return;
            if (!quotesUsable(data)) {
                log.info("午间知会跳过：行情没取到（不发比发旧值好）| userId={}", userId);
                return;
            }
            List<Position> breached = data.positions().stream()
                    .filter(p -> {
                        MarketData md = data.quotes().get(p.symbol());
                        return md != null && md.price() != null
                                && ruleEngine.evaluateStopLoss(userId, md.price(), p.effectiveStopLoss())
                                        .verdict() == StopLossVerdict.BREACHED;
                    })
                    .toList();
            if (breached.isEmpty()) return; // 无异常 → 不打扰
            String content = buildMiddayContent(userId, data, breached);
            // P0-1：正文含逐票表现 → 锁屏精简
            pushToAll(userId, "午间知会", content, "session", null, null,
                    "午间看过一眼。有 " + breached.size() + " 只到了你设的止损位，打开阿呆看看。");
        });
    }

    /** 午间正文：只报事实与位置。**措辞上刻意不出现「快卖/建议减仓」这类催促**。 */
    private String buildMiddayContent(String userId, SessionData data, List<Position> breached) {
        StringBuilder sb = new StringBuilder("📊 午间知会\n");
        sb.append("按你 ").append(accountDayLabel(data)).append(" 的账，现在的位置：\n");
        for (Position p : data.positions()) {
            MarketData md = data.quotes().get(p.symbol());
            if (md == null || md.price() == null) {
                sb.append("· ").append(p.name()).append("：行情没取到，这只我说不了\n");
                continue;
            }
            boolean hit = ruleEngine.evaluateStopLoss(userId, md.price(), p.effectiveStopLoss())
                    .verdict() == StopLossVerdict.BREACHED;
            sb.append("· ").append(p.name()).append(" 现价 ").append(fmt(md.price()))
                    .append("（今日 ").append(signed(md.changePercent())).append("%）");
            if (p.effectiveStopLoss() != null) {
                sb.append(hit
                        ? " ——已在你设的 " + fmt(p.effectiveStopLoss()) + " 下方（只是告诉你位置）"
                        : " ——未到你设的 " + fmt(p.effectiveStopLoss()));
            }
            sb.append("\n");
        }
        sb.append("（午间只报状态，要不要动留到尾盘那条说。）");
        return sb.toString().strip();
    }

    // ── B2 · 尾盘（要不要卖）────────────────────────────────────────────

    /**
     * 尾盘 14:50：**逐票四要素卖点**（RFC §3.2），正文标账日期。
     * <p>
     * 只列**触发了卖出条件**的持仓（破止损 R66 / 超仓位 R81）——他的习惯是「不太会在中间放飞」，
     * 没有触发就不该有这条推送（无信号不发）。行情整体取不到 → 显式降级（B4）。
     */
    @Scheduled(cron = "${adai.trading.session.close-cron:" + CRON_CLOSE + "}")
    public void closeAdvice() {
        // P2-1（2026-08-17 走查）：同文件多个定时任务都有 isTradingDay，唯独它漏——节假日照常推尾盘
        if (!isTradingDay(LocalDate.now())) return;
        forEachTradingUser(userId -> {
            SessionData data = loadData(userId);
            if (data.positions().isEmpty()) return; // 空仓 → 没有「要不要卖」的问题
            if (!quotesUsable(data)) {
                pushToAll(userId, "尾盘卖点", MARKET_UNAVAILABLE_BODY, "session", null, null,
                        MARKET_UNAVAILABLE_LOCK);
                return;
            }
            String content = buildCloseContent(userId, data);
            if (content == null) return; // 没有触发条件的票 → 不发
            // P0-1：尾盘正文含逐票建议 → 锁屏精简
            pushToAll(userId, "尾盘卖点", content, "session", null, null,
                    "尾盘看过了。有几只要留意的，打开阿呆看看。");
        });
    }

    /** 尾盘正文；返回 null = 今天没有任何一只触发卖出条件（不制造噪音）。 */
    private String buildCloseContent(String userId, SessionData data) {
        List<String> blocks = new ArrayList<>();
        int held = 0;
        for (Position p : data.positions()) {
            MarketData md = data.quotes().get(p.symbol());
            if (md == null || md.price() == null) continue; // quotesUsable 已保证不是全缺
            BigDecimal price = md.price();
            BigDecimal percent = positionPercent(p, data.positions(), data.quotes(), data.cash());
            var sl = ruleEngine.evaluateStopLoss(userId, price, p.effectiveStopLoss());
            var pv = ruleEngine.evaluatePosition(userId, percent);
            String action;
            String suggestionKey;
            String reason;
            List<String> refs;
            if (sl.verdict() == StopLossVerdict.BREACHED) {
                action = "清仓参考（R66）";
                suggestionKey = "clear";
                reason = sl.message();
                refs = List.of("R66");
            } else if (pv.verdict() == PositionVerdict.OVER_WEIGHT && r81Applicable(data)) {
                // B3-2（2026-08-23）：R81 减仓判定须过「总资产 <100 万」前提，与建议服务输出侧同口径
                action = "减仓参考（占比 " + fmt(percent) + "% 超 R81）";
                suggestionKey = "reduce";
                reason = pv.message();
                refs = List.of("R81");
            } else {
                held++;
                continue;
            }
            held++;
            // RFC 20260905 B①：时段建议留痕（附 A3 依据快照）——建议遵守率的真实数据源
            recordSessionAdvice(userId, p, md, suggestionKey, reason, percent);
            Optional<TradingDecisionNarrator.Block> block =
                    narrator.sellPointBlock(userId, p, md, percent, action, refs);
            if (block.isPresent()) {
                blocks.add(block.get().text());
            } else {
                // 四要素凑不齐（规则原文取不到 / 价格口径不全）→ 这一只不发（RFC §八 1 的「缺证据不发」）
                log.info("尾盘卖点：{} 触发 {} 但四要素凑不齐，本条不发 | userId={}",
                        p.symbol(), suggestionKey, userId);
            }
        }
        if (blocks.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("📉 尾盘卖点\n");
        sb.append("按你 ").append(accountDayLabel(data)).append(" 的账（持仓 ")
                .append(data.positions().size()).append(" 只，")
                .append(blocks.size()).append(" 只要看一眼）：\n");
        sb.append(String.join("\n", blocks)).append("\n");
        if (held > blocks.size()) {
            sb.append("另外 ").append(held - blocks.size()).append(" 只没有触发你的卖出条件，按计划拿着。");
        }
        return sb.toString().strip();
    }

    // ── B3 · 收盘复盘（数据同步之后）──────────────────────────────────────

    /**
     * 收盘复盘兜底 cron（15:30）：只做**检查**——账同步了就出复盘，没同步就如实说（用户拍板 D4）。
     * <p>
     * 真正的触发点在数据同步完成时（{@link #afterDataSync(String)}），因为「复盘随数据更新」
     * （用户原话）；15:30 只是那天的兜底时刻。
     */
    @Scheduled(cron = "${adai.trading.session.close-summary-cron:" + CRON_CLOSE_SUMMARY + "}")
    public void closeSummaryPush() {
        if (!isTradingDay(LocalDate.now())) return;
        LocalDate today = LocalDate.now();
        forEachTradingUser(userId -> pushDailyReview(userId, today));
    }

    /**
     * 数据同步完成（导入持仓 / 资金 / 历史成交成功）后由接口层调用（B3）。
     * <p>
     * 两条规则：
     * <ol>
     *   <li><b>先记状态</b>——不管什么时刻导的，「今天账动过」这件事本身要留下，15:30 兜底据此决定出不出复盘。</li>
     *   <li><b>收盘后（≥15:00）导入的立刻出复盘</b>；收盘前导入的不立刻发——那时账还没导全，
     *       先发一条残缺复盘不如等 15:30 用最新的账算一次。</li>
     * </ol>
     */
    public void afterDataSync(String userId) {
        LocalDate today = LocalDate.now();
        syncStateRepository.recordSync(userId, today, LocalDateTime.now());
        if (!isTradingDayStrict(today)) return; // 非交易日导入（补昨天/上周的账）→ 只记状态
        if (nowTime().isBefore(REVIEW_TRIGGER_FROM)) {
            log.info("数据已同步（收盘前），复盘留给 15:30 兜底 | userId={}", userId);
            return;
        }
        pushDailyReview(userId, today);
    }

    /** 当前时刻（包私有：测试可覆盖，用来钉住「收盘前只记状态 / 收盘后立刻出复盘」两条分支）。 */
    LocalTime nowTime() {
        return LocalTime.now();
    }

    /**
     * 出复盘（或如实说还没看到账）。**每天至多一条**：
     * <ul>
     *   <li>已推过 → 跳过（防「15:30 兜底 + 随后补导」双发）；</li>
     *   <li>今天没同步过 → 不硬生成，一句人话告诉他导一下；<b>不落「已推」标记</b>——
     *       他补导之后仍要能拿到那条真正的复盘（这正是「可晚于 15:30」的用法）；</li>
     *   <li>已同步 → 出复盘并落标记。</li>
     * </ul>
     */
    private void pushDailyReview(String userId, LocalDate today) {
        TradingSyncState state = syncStateRepository.find(userId);
        if (state.reviewPushedOn(today)) {
            log.info("收盘复盘今天已发过，跳过 | userId={}", userId);
            return;
        }
        if (!state.syncedOn(today)) {
            pushToAll(userId, "收盘复盘",
                    "今天的持仓/成交快照我还没看到，导一下我再给你复盘。",
                    "close-summary", null, null,
                    "今天的账我还没看到。打开阿呆看看怎么导。");
            log.info("收盘复盘：今天还没同步，如实说 | userId={}", userId);
            return;
        }
        String content = buildDailyReview(userId, today);
        pushToAll(userId, "收盘复盘", content, "close-summary", null, null, lockDailyReview(userId, today));
        syncStateRepository.markDailyReview(userId, today);
    }

    /**
     * 收盘复盘正文（RFC §3.3）：记账 / 账实 / 只记流水的 / 复盘 / 明天。
     * <p>
     * 每一段要么是账上的事实，要么是**如实说没有**——不出现「今天表现不错」这类没有出处的话。
     */
    private String buildDailyReview(String userId, LocalDate today) {
        StringBuilder sb = new StringBuilder("📋 收盘复盘\n");
        List<TradeRecord> trades = todayTrades(userId, today);
        if (trades.isEmpty()) {
            sb.append("今天没有成交记录。\n");
        } else {
            sb.append("今天你做了 ").append(trades.size()).append(" 笔，我按你的账核过一遍——\n");
            sb.append("记账：").append(trades.stream().limit(8)
                            .map(tr -> (tr.direction() == TradeDirection.BUY ? "买 " : "卖 ")
                                    + tr.name() + " " + tr.volume() + " 股 @" + fmt(tr.price()))
                            .collect(Collectors.joining(" · ")))
                    .append(trades.size() > 8 ? " 等" : "").append("\n");
        }
        sb.append("账实：").append(integrityLine(userId)).append("\n");
        sb.append("只记流水的：").append(degradedLine(userId)).append("\n");
        String review = reviewLine(userId, today);
        if (review != null) sb.append("复盘：").append(review).append("\n");
        String tomorrow = tomorrowLine(userId);
        if (tomorrow != null) sb.append("明天：").append(tomorrow);
        return sb.toString().strip();
    }

    /** 锁屏版复盘：只报「几件事」，不带任何标的名称与金额。 */
    private String lockDailyReview(String userId, LocalDate today) {
        List<TradeRecord> trades = todayTrades(userId, today);
        StringBuilder sb = new StringBuilder();
        sb.append(trades.isEmpty() ? "今天没有操作" : "今天成交 " + trades.size() + " 笔");
        try {
            TradingAppService.IntegrityReport report = tradingAppService.integrity(userId);
            int issues = (report.drift() == null ? 0 : report.drift().size())
                    + (report.gaps() == null ? 0 : report.gaps().size());
            if (issues > 0) sb.append(" · 账实有出入");
            if (report.degraded() != null && !report.degraded().isEmpty()) sb.append(" · 有只记流水的");
        } catch (RuntimeException e) {
            log.warn("锁屏复盘：账实自检失败（省略该句）| userId={} | {}", userId, e.getMessage());
        }
        sb.append("\n打开阿呆看详情");
        return sb.toString();
    }

    private List<TradeRecord> todayTrades(String userId, LocalDate today) {
        try {
            return tradingAppService.getTradeHistory(userId, today, today).stream()
                    .filter(tr -> tr.volume() > 0) // 股息入账/红利税（volume=0）不计入买卖笔数
                    .toList();
        } catch (RuntimeException e) {
            log.warn("收盘复盘：今日成交统计失败 | userId={} | {}", userId, e.getMessage());
            return List.of();
        }
    }

    /** 账实一句话（唯一口径：{@code TradingAppService.integrity}）——判不了就直说判不了。 */
    private String integrityLine(String userId) {
        try {
            TradingAppService.IntegrityReport report = tradingAppService.integrity(userId);
            int drift = report.drift() == null ? 0 : report.drift().size();
            int gaps = report.gaps() == null ? 0 : report.gaps().size();
            if (!report.holdingsKnown()) {
                // 不知道 ≠ 没问题：基线/锚定缺失时绝不报「一致」（P1-交易61 的教训）
                return "还判不了（" + (report.note() != null && !report.note().isBlank()
                        ? report.note() : "持仓基线或锚定缺失") + "）";
            }
            if (drift == 0 && gaps == 0) {
                return "一致（派生持仓与落地持仓相符）";
            }
            StringBuilder sb = new StringBuilder("对不上——");
            if (drift > 0) sb.append(drift).append(" 只标的的持仓和流水对不上");
            if (drift > 0 && gaps > 0) sb.append("、");
            if (gaps > 0) sb.append(gaps).append(" 笔成交没能并进持仓");
            sb.append("（明细在交易页）");
            return sb.toString();
        } catch (RuntimeException e) {
            log.warn("收盘复盘：账实自检失败 | userId={} | {}", userId, e.getMessage());
            return "这次我没核出来（自检失败）";
        }
    }

    /** 「只记流水的」：P1-交易61 的 degraded 流水——不让「只记了流水、没进持仓」静默。 */
    private String degradedLine(String userId) {
        try {
            TradingAppService.IntegrityReport report = tradingAppService.integrity(userId);
            List<TradingAppService.DegradedLine> degraded = report.degraded();
            if (degraded == null || degraded.isEmpty()) return "无";
            String detail = degraded.stream().limit(3)
                    .map(d -> d.name() + (d.direction() == TradeDirection.BUY ? " 买 " : " 卖 ")
                            + d.volume() + " 股")
                    .collect(Collectors.joining(" · "));
            boolean inferred = degraded.stream().anyMatch(TradingAppService.DegradedLine::inferred);
            return degraded.size() + " 笔（" + detail + (degraded.size() > 3 ? " 等" : "") + "）"
                    + (inferred ? "——锚定日是推断出来的，这几笔可能没进持仓" : "——已在快照里，仅说明");
        } catch (RuntimeException e) {
            log.warn("收盘复盘：降级流水读取失败 | userId={} | {}", userId, e.getMessage());
            return "这次我没核出来";
        }
    }

    /**
     * 复盘段（RFC §3.3 的「000831 这笔持了 4 天，+6.2%」）：今日了结的回合 + 与他自己同区间历史的对照。
     * 没有今日了结 / 样本不足 → 如实说（返回 null 表示这段不出现）。
     */
    private String reviewLine(String userId, LocalDate today) {
        try {
            List<SoldTrade> closed = soldTradeRepository.findAll(userId).stream()
                    .filter(t -> today.equals(t.sellDate()))
                    .toList();
            if (closed.isEmpty()) return null;
            SoldTrade t = closed.get(0);
            StringBuilder sb = new StringBuilder();
            sb.append(t.name()).append(" 这笔持了 ").append(t.holdDays()).append(" 天、")
                    .append(t.holdPnlPct() >= 0 ? "+" : "")
                    .append(String.format("%.1f%%", t.holdPnlPct()));
            String label = TradingEvidenceService.pnlBucket(t.holdPnlPct());
            TradingEvidenceService.HistoryStats stats =
                    evidenceService.historyStats(userId, TradingEvidenceService.Dimension.PNL_BUCKET);
            Optional<TradingEvidenceService.HistoryBucket> mine = stats.buckets().stream()
                    .filter(b -> label.equals(b.label())).findFirst();
            if (mine.isPresent() && mine.get().sufficient()) {
                TradingEvidenceService.HistoryBucket b = mine.get();
                sb.append("；你过去 ").append(b.count()).append(" 次在「").append(label)
                        .append("」了结，平均 ").append(String.format("%+.1f%%", b.avgPnlPct()))
                        .append("、平均持 ").append(Math.round(b.avgHoldDays())).append(" 天");
            } else {
                sb.append("；这个区间你的样本还不够，先不给你对比");
            }
            if (closed.size() > 1) sb.append("（今天还有 ").append(closed.size() - 1).append(" 笔了结）");
            return sb.toString();
        } catch (RuntimeException e) {
            log.warn("收盘复盘：清仓回合读取失败 | userId={} | {}", userId, e.getMessage());
            return null;
        }
    }

    /** 明天段：最接近止损位的那只持仓（他真正的风险点），没有就返回 null。 */
    private String tomorrowLine(String userId) {
        List<Position> positions = positionRepository.findAll(userId);
        if (positions.isEmpty()) return null;
        Map<String, MarketData> quotes = quoteOf(positions.stream().map(Position::symbol).toList());
        Position nearest = null;
        BigDecimal nearestGap = null;
        for (Position p : positions) {
            MarketData md = quotes.get(p.symbol());
            if (md == null || md.price() == null || p.effectiveStopLoss() == null
                    || p.effectiveStopLoss().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal gap = md.price().subtract(p.effectiveStopLoss())
                    .divide(p.effectiveStopLoss(), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            if (nearestGap == null || gap.compareTo(nearestGap) < 0) {
                nearestGap = gap;
                nearest = p;
            }
        }
        if (nearest == null) return null;
        return nearest.name() + " 现价距你设的止损位 " + fmt(nearest.effectiveStopLoss())
                + (nearestGap.compareTo(BigDecimal.ZERO) >= 0
                        ? " 还有 " + signed(nearestGap) + "%"
                        : " 已在下方 " + signed(nearestGap) + "%");
    }

    /** B4 的显式降级文案（P1-交易60）：不知道 ≠ 没问题。 */
    static final String MARKET_UNAVAILABLE_BODY =
            "今天行情我没取到，这条我暂时给不了——不是没问题，是我现在看不到。"
                    + "稍后我再试，或打开阿呆看别的。";
    static final String MARKET_UNAVAILABLE_LOCK = "今天行情没取到，这条我暂时给不了。";

    // ── 收盘 15:05 账户自动更新（不属 B 批，原样保留）────────────────────

    /** 收盘 15:05 账户自动更新（B1，2026-08-16）：行情可得部分自动——参考市值/当日盈亏/持仓浮盈；
     *  现金/可用/本金保持券商导入值与转账推导。
     *  P2-交易24（2026-08-29 注释如实化）：positions/行情在 account.json update() 锁**外**读取——
     *  与 recordTrade 并发时存在跨文件残余窗口（快照=新现金+旧市值）；收盘 15:05 与手动记录并发概率极低、
     *  次日收盘自愈，无原子跨文件手段（详见 trading-features §8 跨文件窗口注意点）。 */
    @Scheduled(cron = "${adai.trading.session.close-update-cron:0 5 15 * * MON-FRI}")
    public void closeAccountUpdate() {
        if (!isTradingDay(LocalDate.now())) return;
        forEachTradingUser(userId -> {
            List<Position> positions = positionRepository.findAll(userId);
            if (positions.isEmpty()) return;
            Map<String, MarketData> quotes;
            try {
                quotes = marketDataSource.quote(positions.stream().map(Position::symbol).toList());
            } catch (Exception e) {
                log.warn("收盘账户更新：行情失败 | {}", e.getMessage());
                return;
            }
            if (quotes.isEmpty()) return;
            java.math.BigDecimal marketValue = java.math.BigDecimal.ZERO;
            java.math.BigDecimal todayPnl = java.math.BigDecimal.ZERO;
            java.math.BigDecimal floatPnl = java.math.BigDecimal.ZERO;
            int missingQuotes = 0;
            for (Position p : positions) {
                MarketData md = quotes.get(p.symbol());
                if (md == null || md.price() == null) {
                    // P1-交易3（2026-08-17）：行情缺失的持仓不计入 → 下方若存在缺失则跳过保存，
                    // 避免用残缺市值覆盖总资产（旧值不可恢复）
                    missingQuotes++;
                    continue;
                }
                java.math.BigDecimal value = md.price().multiply(java.math.BigDecimal.valueOf(p.quantity()));
                marketValue = marketValue.add(value);
                // B3-3（2026-08-23，P1-交易3 半修残留）：yesterdayClose 缺失时 todayPnl 残缺
                // 会覆盖旧快照的当日盈亏（旧值不可恢复）——与缺 price 同等待遇：整体跳过
                if (md.yesterdayClose() == null) {
                    missingQuotes++;
                    continue;
                }
                todayPnl = todayPnl.add(md.price().subtract(md.yesterdayClose())
                        .multiply(java.math.BigDecimal.valueOf(p.quantity())));
                floatPnl = floatPnl.add(md.price().subtract(p.avgCost())
                        .multiply(java.math.BigDecimal.valueOf(p.quantity())));
            }
            // P1-交易3 + B3-3：任一持仓缺行情（price 或 yesterdayClose）→ 本次不覆盖（保留旧快照）
            if (missingQuotes > 0) {
                String missing = positions.stream()
                        .filter(p -> {
                            MarketData md = quotes.get(p.symbol());
                            return md == null || md.price() == null || md.yesterdayClose() == null;
                        })
                        .map(Position::symbol).toList()
                        .toString();
                log.warn("收盘账户更新：{} 只缺行情（价格或昨收），跳过保存保留旧快照 | userId={} | 缺失={}",
                        missingQuotes, userId, missing);
                // P2-交易33（2026-08-29，B3-3 残留）：跳过长期无感——新股/停牌无昨收时账户卡陈旧，
                // 用户看不到任何提示。补一条行情提醒推送（受推送开关门控，尊重用户设置）。
                try {
                    pushToAll(userId, "账户今日未自动更新",
                            "收盘自动更新跳过：有 " + missingQuotes + " 只持仓缺行情（新股/停牌可能无昨收）"
                                    + "，账户市值维持上次快照。明日正常收盘会自愈，或手动点「点击更新」。",
                            "market", null, null,
                            // P2-推送1（2026-09-14 晚间批）：本条正文只有「几只缺行情」的计数，
                            // 无标的/金额——按 fail-closed 口径**显式声明**它是中性文案（而不是靠漏传回落）
                            "收盘自动更新跳过了：有 " + missingQuotes + " 只持仓缺行情。打开阿呆看看。",
                            "账户今日未自动更新");
                } catch (RuntimeException e) {
                    log.warn("收盘缺行情通知推送失败 | userId={} | {}", userId, e.getMessage());
                }
                return;
            }
            final java.math.BigDecimal fMarket = marketValue;
            // P2-交易37（2026-09-09，用户拍板口径①）：当日盈亏改「精确计算」——
            // 当日已实现（卖出净额−卖出成本） + 持仓日浮动 + 当日股息/红利税
            //（详见 TradingAppService.computeDailyPnl；行情已确认齐备，notes 记日志）
            final java.math.BigDecimal fToday = resolveTodayPnl(userId, todayPnl);
            final java.math.BigDecimal fFloat = floatPnl;
            // P0-2（2026-08-23）：account.json 写统一走 update（per-user 锁原子 RMW）——
            // 原 findLatest+save 无锁，与 recordTrade/转账/资金导入并发整文件互相覆盖
            // B6-4（2026-08-23，P1-交易11）：写失败明确告警（外层 forEachTradingUser 兜底不中断整批）
            try {
                accountSnapshotRepository.update(userId, cur -> cur.map(c -> {
                    AccountSnapshot next = new AccountSnapshot(
                            fMarket.add(c.cash()), c.cash(), c.available(), c.withdrawable(),
                            fMarket, fFloat, fToday, c.principal(), LocalDate.now(),
                            // P2-交易48：收盘写入的当日盈亏 = 系统精确计算（口径①）
                            AccountSnapshot.SOURCE_CALC);
                    log.info("收盘账户更新 | userId={} | 市值={} 当日盈亏={} 浮盈={}",
                            userId, fMarket, fToday, fFloat);
                    return next;
                }).orElse(null)); // 无快照（未导入资金）不初始化
            } catch (RuntimeException e) {
                log.error("收盘账户更新写失败——账目未落盘 | userId={} | {}", userId, e.getMessage());
            }
        });
    }

    /** P2-交易37：收盘当日盈亏 = 精确计算（已实现+浮动+股息/红利税）；失败回落旧持仓浮动估算。
     *  存在「未计入附注」（缺昨收/无成本基线/今日无成交等）→ 推一条说明通知，不让「少算了」静默。 */
    private java.math.BigDecimal resolveTodayPnl(String userId, java.math.BigDecimal fallback) {
        try {
            com.adaiadai.core.application.TradingAppService.DailyPnlResult r =
                    tradingAppService.computeDailyPnl(userId, LocalDate.now());
            if (!r.notes().isEmpty()) {
                String joined = String.join("；", r.notes());
                log.info("当日盈亏计算附注 | userId={} | {}", userId, joined);
                // 只推送「实质未计入」（缺昨收/无成本基线…）；「今日无成交记录」是纯持有日的常规提示，不打扰
                List<String> actionable = r.notes().stream()
                        .filter(n -> !n.startsWith("今日无成交记录"))
                        .toList();
                if (!actionable.isEmpty()) {
                    try {
                        // 复用行情类通知（受推送开关门控，尊重用户设置；best-effort 不打断收盘更新）
                        pushToAll(userId, "当日盈亏已更新",
                                "今日当日盈亏已计算，但部分未计入：\n· " + String.join("\n· ", actionable)
                                        + "\n如需最精确口径：当天成交请走「历史成交导入/手动记录」入流水；"
                                        + "缺昨收/成本基线的部分会在券商文件或明日收盘后自愈。",
                                "market", null, null,
                                // P0-1：附注明细逐条点名标的/金额 → 锁屏只说「有说明」
                                "今日盈亏算好了，但有几句要跟你说明。打开阿呆看看。");
                    } catch (RuntimeException e) {
                        log.warn("当日盈亏附注通知推送失败 | userId={} | {}", userId, e.getMessage());
                    }
                }
            }
            return r.todayPnl();
        } catch (RuntimeException e) {
            log.warn("收盘账户更新：当日盈亏精确计算失败，回落持仓浮动估算 | userId={} | {}", userId, e.getMessage());
            return fallback;
        }
    }

    /** RFC 20260817 收盘交易日志确认（15:15）：当日有归集候选 → 推送「今日操作汇总，是否完整」。
     *  用户确认后由交易模块落库；无候选静默跳过。 */
    @Scheduled(cron = "${adai.trading.session.trade-log-confirm-cron:0 15 15 * * MON-FRI}")
    public void tradeLogConfirm() {
        if (!isTradingDay(LocalDate.now())) return;
        forEachTradingUser(userId -> {
            var candidates = tradeLogCollectService.todayCandidates(userId);
            if (candidates.isEmpty()) return;
            String content = tradeLogCollectService.summarize(candidates);
            // P0-1：今日操作汇总含标的/数量/金额 → 锁屏只说笔数
            pushToAll(userId, "今日操作确认", content, "session", null, null,
                    "今天有 " + candidates.size() + " 笔操作等你确认。打开阿呆看看对不对。");
        });
    }

    // ── 数据组装 ──

    /** 持仓 + 行情 + 引擎判定 + 择时状态 + 现金 + **账日期**的完整数据（模板与渲染共用）。 */
    private SessionData loadData(String userId) {
        List<Position> positions = positionRepository.findAll(userId);
        Map<String, MarketData> quotes = Map.of();
        if (!positions.isEmpty()) {
            quotes = quoteOf(positions.stream().map(Position::symbol).toList());
        }
        // P1-交易4（2026-08-17）：现金唯一真源 = account.json AccountSnapshot.cash（S5）
        // RFC 20260922 B 批：顺带取**账的日期**——所有涉及账的文案都要标它（§五 关键推论 1）
        AccountSnapshot snapshot = accountSnapshotRepository.findLatest(userId).orElse(null);
        BigDecimal cash = snapshot != null ? snapshot.cash() : BigDecimal.ZERO;
        LocalDate accountDate = snapshot != null ? snapshot.snapshotDate() : null;
        return new SessionData(positions, quotes, readMarketStage(userId), cash, accountDate);
    }

    /** 行情取数（安全约定见 MarketDataSource：异常返回空 Map；这里再兜一层，F 类失败不炸推送）。 */
    private Map<String, MarketData> quoteOf(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) return Map.of();
        try {
            Map<String, MarketData> quotes = marketDataSource.quote(symbols);
            return quotes != null ? quotes : Map.of();
        } catch (Exception e) {
            log.warn("时段推送：行情查询失败 | {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 行情是否可用（B4/P1-交易60）：持仓非空时，只要**一只**拿到有效现价就算可用——
     * 逐只缺（新股/停牌无昨收）是业务事实，该在正文里点名；
     * **一只都拿不到**才是取数失败，那时整条推送必须显式降级。
     */
    static boolean quotesUsable(SessionData data) {
        if (data.positions() == null || data.positions().isEmpty()) return true;
        return data.positions().stream().anyMatch(p -> {
            MarketData md = data.quotes().get(p.symbol());
            return md != null && md.price() != null;
        });
    }

    /** 账日期标注（RFC §五 关键推论 1）：盘中不读「今天的账」——它还不存在，所以每条都要标账的日期。 */
    static String accountDayLabel(SessionData data) {
        if (data.accountDate() == null) return "上次导入";
        return data.accountDate().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd"));
    }

    /**
     * 择时状态三级读取（v3.41，2026-09-04）：
     * ① market-stage.json 用户手动判定（bull/bear，权威——用户设了即以此为准，
     *    不再被 current.md 的 OAMV 规则推断覆盖）→ ② current.md「当前判断」行 → ③ "择时状态未知"。
     */
    private String readMarketStage(String userId) {
        try {
            TradingMarketStage manual = marketStageRepository.findByUser(userId);
            if (manual != null) {
                return "当前判断：" + TradingMarketStage.label(manual.stage())
                        + "（用户手动判定 " + manual.updatedAt().substring(0, 10) + "）";
            }
        } catch (Exception e) {
            log.warn("时段推送：用户活跃市值区间读取失败 | userId={} | {}", userId, e.getMessage());
        }
        try {
            if (Files.isReadable(currentMd)) {
                for (String line : Files.readAllLines(currentMd, StandardCharsets.UTF_8)) {
                    if (line.contains("当前判断")) {
                        return line.replace("**", "").strip();
                    }
                }
            } else {
                log.warn("时段推送：current.md 不可读 | {}", currentMd);
            }
        } catch (Exception e) {
            log.warn("时段推送：current.md 读取失败 | {} | {}", currentMd, e.getMessage());
        }
        return "择时状态未知";
    }

    /**
     * RFC 20260905 B①（💥1 对抗审 2026-09-05）：时段推送确定性逐票建议留痕。
     * 规则引擎判定的 clear/reduce/hold 落 AdviceEntry（source=session-push）——
     * 否则建议遵守率分母长期空、复盘对照段永不出现（推送是每日建议的真实主源）。
     * RFC 20260922 A3：同时落**依据快照**（当时的价 / 占比 / 止损位 / 形态）——铁证④的实体。
     * 落盘失败仅 error 日志（推送主链路不受阻，与 MarketPush 同口径）。
     */
    private void recordSessionAdvice(String userId, Position p, MarketData md, String suggestion,
                                     String reason, BigDecimal percent) {
        try {
            adviceHistoryRepository.append(userId, new AdviceEntry(
                    null, LocalDate.now(), p.symbol(), p.name(), suggestion,
                    reason, List.of(), false, percent, "session-push",
                    LocalDateTime.now(), basisOf(p, md, suggestion, percent)));
        } catch (Exception e) {
            log.error("时段建议留痕失败（不影响推送）| userId={} | symbol={} | {}", userId, p.symbol(), e.getMessage());
        }
    }

    /** 依据快照（A3 口径）：只记事实（价 / 占比 / 止损 / 形态 / 动作），不含对错判断；序列化失败 → null。 */
    private static String basisOf(Position p, MarketData md, String suggestion, BigDecimal percent) {
        try {
            var node = MAPPER.createObjectNode();
            putNum(node, "price", md != null ? md.price() : null);
            putNum(node, "positionPercent", percent);
            putNum(node, "stopLoss", p.effectiveStopLoss());
            node.put("buyPoint", p.buyPoint());
            node.put("suggestion", suggestion);
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("依据快照序列化失败（该条留痕无 basis）| symbol={} | {}", p.symbol(), e.getMessage());
            return null;
        }
    }

    /** JSON 里的数字：缺就是 null（"—" 是给人看的文案，不是数据）。 */
    private static void putNum(com.fasterxml.jackson.databind.node.ObjectNode node, String key, BigDecimal v) {
        if (v == null) node.putNull(key); else node.put(key, v);
    }

    /** 单票占比（总资产口径，P1-交易4 2026-08-17：分母 = 持仓市值 + 现金；现金不可用按 0）。 */
    private BigDecimal positionPercent(Position target, List<Position> positions, Map<String, MarketData> quotes,
                                       BigDecimal cash) {
        BigDecimal total = cash == null ? BigDecimal.ZERO : cash;
        BigDecimal mine = BigDecimal.ZERO;
        for (Position p : positions) {
            MarketData md = quotes.get(p.symbol());
            BigDecimal price = md != null && md.price() != null ? md.price() : p.currentPrice();
            BigDecimal value = price.multiply(BigDecimal.valueOf(p.quantity()));
            total = total.add(value);
            if (p.symbol().equals(target.symbol())) mine = value;
        }
        if (total.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        return mine.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    }

    /**
     * R81 是否适用（B3-2，2026-08-23）：总资产（持仓市值 + 现金）&lt; 100 万——
     * 与 TradingAdviceAppService 输出侧同口径（超 100 万按 R82-R95 配置评估，不强制 25% 上限）。
     */
    private boolean r81Applicable(SessionData data) {
        BigDecimal total = data.cash() == null ? BigDecimal.ZERO : data.cash();
        for (Position p : data.positions()) {
            MarketData md = data.quotes().get(p.symbol());
            BigDecimal price = md != null && md.price() != null ? md.price() : p.currentPrice();
            total = total.add(price.multiply(BigDecimal.valueOf(p.quantity())));
        }
        return total.compareTo(new BigDecimal("1000000")) < 0;
    }

    // ── 推送 ──

    private void forEachTradingUser(java.util.function.Consumer<String> action) {
        accountRepository.findAll().stream()
                .filter(Account::enabled)
                .filter(a -> a.userId() != null
                        && pluginService.hasPlugin(a.userId(), PluginRegistry.PLUGIN_TRADING))
                .map(Account::userId)
                .forEach(userId -> {
                    try {
                        action.accept(userId);
                    } catch (Exception e) {
                        log.warn("时段推送失败 | userId={} | {}", userId, e.getMessage());
                    }
                });
    }

    private void pushToAll(String userId, String title, String content, String type,
                           String symbol, String name) {
        pushToAll(userId, title, content, type, symbol, name, null, null);
    }

    /**
     * 带**锁屏精简正文**的重载（D1，2026-09-13 外部视角审查拍板 A）。
     * <p>
     * 外部通知渠道（APNs / Bark / 微信）渲染 {@code lockScreenContent}，站内 Feed 仍渲染完整
     * {@code content}——锁屏是「放在桌上旁人能看见」的，Feed 是「自己打开才看到」的，两者不该同一份字。
     * <p>
     * <b>传 null（例如 6 参重载路径）= 外部渠道发中性兜底文案，不回落完整正文</b>
     * （P2-推送1，2026-09-14 晚间批：回退方向 fail-closed）。中性推送请显式传自己的文案 +
     * {@code lockScreenTitle}，把「这条不敏感」写进代码。
     */
    private void pushToAll(String userId, String title, String content, String type,
                           String symbol, String name, String lockScreenContent,
                           String lockScreenTitle) {
        pushToAllInternal(userId, title, content, type, symbol, name, lockScreenContent, lockScreenTitle);
    }

    private void pushToAll(String userId, String title, String content, String type,
                           String symbol, String name, String lockScreenContent) {
        pushToAllInternal(userId, title, content, type, symbol, name, lockScreenContent, null);
    }

    private void pushToAllInternal(String userId, String title, String content, String type,
                                   String symbol, String name, String lockScreenContent,
                                   String lockScreenTitle) {
        // RFC 20260817：推送开关——用户关闭的类型不推送（session=早/午/尾盘，close-summary=复盘）
        if (!pushSettingsRepository.findByUser(userId).isEnabled(type)) {
            log.info("时段推送跳过（用户关闭）| userId={} | type={}", userId, type);
            return;
        }
        PushChannel.PushMessage message = new PushChannel.PushMessage(
                title, content, type, symbol, name, LocalTime.now(), lockScreenContent, lockScreenTitle);
        for (PushChannel channel : pushChannels) {
            if (channel.enabled()) {
                channel.push(userId, message);
            }
        }
        log.info("时段推送完成 | userId={} | title={}", userId, title);
    }

    private static String fmt(BigDecimal v) {
        return v != null ? v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() : "—";
    }

    private static String signed(BigDecimal v) {
        if (v == null) return "—";
        String s = fmt(v);
        return v.compareTo(BigDecimal.ZERO) > 0 ? "+" + s : s;
    }

    /** 时段推送数据载体（{@code accountDate} = 账的日期，B3/§五：所有账相关文案都要标它）。 */
    record SessionData(List<Position> positions, Map<String, MarketData> quotes, String marketStage,
                      BigDecimal cash, LocalDate accountDate) {}
}
