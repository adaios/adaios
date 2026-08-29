package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.AccountSnapshot;
import com.adaiadai.core.domain.trading.AccountSnapshotRepository;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.WatchlistItem;
import com.adaiadai.core.domain.trading.WatchlistRepository;
import com.adaiadai.core.domain.trading.engine.PositionVerdict;
import com.adaiadai.core.domain.trading.engine.StopLossVerdict;
import com.adaiadai.core.domain.trading.engine.TradingRuleEngine;
import com.adaiadai.core.domain.trading.market.MarketData;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import com.adaiadai.core.infrastructure.ai.interaction.AiTraceContext;
import com.adaiadai.core.infrastructure.storage.PushSettingsRepository;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.adaiadai.core.kernel.push.PushChannel;
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
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * TradingSessionPushService — 交易时段节奏推送（RFC 20260816：早盘计划/午间跟踪/尾盘建议）。
 * <p>
 * 三个交易节点主动服务（工作日）：
 * <ul>
 *   <li><b>早盘计划 9:15</b>：持仓总览 + 各票止损位/买点 + 今日关注（择时状态）</li>
 *   <li><b>午间跟踪 12:00</b>：上午表现 + 是否触发止损 + 计划更新</li>
 *   <li><b>尾盘建议 14:50</b>：逐票建议（R66/R81 判定）+ 明日关注 + 复盘提醒</li>
 * </ul>
 * 内容两阶段：阶段二 LLM 自然语言生成（阿呆口吻，无第三视角），失败降级模板（阶段一）。
 * 推送走 {@link PushChannel} 渠道插件化（Feed + 微信）。轮询频率 = 用户节奏（早盘 + 快收盘 + 最多中午一个）。
 */
@Service
public class TradingSessionPushService {

    private static final Logger log = LoggerFactory.getLogger(TradingSessionPushService.class);

    /** 早盘计划 / 午间跟踪 / 尾盘建议 cron（工作日）。 */
    static final String CRON_MORNING = "0 15 9 * * MON-FRI";
    static final String CRON_MIDDAY = "0 0 12 * * MON-FRI";
    static final String CRON_CLOSE = "0 50 14 * * MON-FRI";
    /** 收盘小结 cron（P2-用户3，2026-08-29）：15:30——15:15 交易日志确认之后。 */
    static final String CRON_CLOSE_SUMMARY = "0 30 15 * * MON-FRI";

    private static final String SESSION_SYSTEM_PROMPT = """
            你是阿呆，用户的个人 AI 助手，用自然、亲切、简洁的中文（无系统标签，像朋友聊天）。
            基于给出的持仓、行情与规则判定数据，生成{节点}消息。要求：
            1. 第一行是「总结：」一句话概括当前状态
            2. 每只持仓单独一行（· 名称 现价 涨跌 → 建议），建议引用规则用 R 编号（如 R66/R81）
            3. 建议是参考不是指令，语气是"我建议"而非"你必须"
            4. 简洁，不堆砌寒暄
            """.strip();

    private final PositionRepository positionRepository;
    private final MarketDataSource marketDataSource;
    private final AccountRepository accountRepository;
    private final PluginService pluginService;
    private final TradingRuleEngine ruleEngine;
    private final AiClient aiClient;
    private final List<PushChannel> pushChannels;
    private final AccountSnapshotRepository accountSnapshotRepository;
    private final WatchlistBuyPointService buyPointService;
    private final WatchlistRepository watchlistRepository;
    /** RFC 20260817：推送开关（用户可关闭各类型推送）。 */
    private final PushSettingsRepository pushSettingsRepository;
    /** RFC 20260817：交易日志自动归集（收盘确认推送）。 */
    private final TradeLogCollectService tradeLogCollectService;
    /** P2-用户3（2026-08-29）：收盘小结统计今日成交（getTradeHistory 过滤股息流水）。 */
    private final TradingAppService tradingAppService;
    /** 择时状态来源：knowledge/context/current.md（G-4 后路径，配置驱动——生产 /opt/adaios/os/... 由 .env 注入）。 */
    private final Path currentMd;

    public TradingSessionPushService(PositionRepository positionRepository,
                                     MarketDataSource marketDataSource,
                                     AccountRepository accountRepository,
                                     PluginService pluginService,
                                     TradingRuleEngine ruleEngine,
                                     AiClient aiClient,
                                     List<PushChannel> pushChannels,
                                     AccountSnapshotRepository accountSnapshotRepository,
                                     WatchlistBuyPointService buyPointService,
                                     WatchlistRepository watchlistRepository,
                                     PushSettingsRepository pushSettingsRepository,
                                     TradeLogCollectService tradeLogCollectService,
                                     TradingAppService tradingAppService,
                                     @Value("${adai.knowledge.trading-engine-path:../../os/trading-engine/knowledge/context}") String knowledgeDir) {
        this.positionRepository = positionRepository;
        this.marketDataSource = marketDataSource;
        this.accountRepository = accountRepository;
        this.pluginService = pluginService;
        this.ruleEngine = ruleEngine;
        this.aiClient = aiClient;
        this.pushChannels = pushChannels;
        this.accountSnapshotRepository = accountSnapshotRepository;
        this.buyPointService = buyPointService;
        this.watchlistRepository = watchlistRepository;
        this.pushSettingsRepository = pushSettingsRepository;
        this.tradeLogCollectService = tradeLogCollectService;
        this.tradingAppService = tradingAppService;
        this.currentMd = Paths.get(knowledgeDir, "current.md").toAbsolutePath().normalize();
        log.info("时段推送：择时状态来源 current.md = {}", currentMd);
    }

    // ── 三节点 cron ──

    /**
     * P3（2026-08-17）+ B5-1（2026-08-23）：A 股法定节假日休市日——节假日不推送/不改账。
     * cron 已排除周末（MON-FRI），此处只登记**落在工作日**的休市日（周末无需登记）。
     * 2026 按沪深交易所官方通知（2025-12-22 发布）；2027 为预测（官方通常年底发布），
     * 临时调休/追加休市不追（以官方最终通知为准）。
     */
    static final java.util.Set<java.time.LocalDate> HOLIDAYS = java.util.Set.of(
            // ── 2026（官方，沪深交易所 2025-12-22 通知）──
            // 元旦 1/1(四)~1/3(六) → 工作日：1/1、1/2
            java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 1, 2),
            // 春节 2/15(日)~2/23(一) → 工作日：2/16(一)~2/20(五)、2/23(一)
            java.time.LocalDate.of(2026, 2, 16), java.time.LocalDate.of(2026, 2, 17),
            java.time.LocalDate.of(2026, 2, 18), java.time.LocalDate.of(2026, 2, 19),
            java.time.LocalDate.of(2026, 2, 20), java.time.LocalDate.of(2026, 2, 23),
            // 清明 4/4(六)~4/6(一) → 工作日：4/6(一)
            java.time.LocalDate.of(2026, 4, 6),
            // 劳动节 5/1(五)~5/5(二) → 工作日：5/1(五)、5/4(一)、5/5(二)
            java.time.LocalDate.of(2026, 5, 1), java.time.LocalDate.of(2026, 5, 4),
            java.time.LocalDate.of(2026, 5, 5),
            // 端午 6/19(五)~6/21(日) → 工作日：6/19(五)
            java.time.LocalDate.of(2026, 6, 19),
            // 中秋 9/25(五)~9/27(日) → 工作日：9/25(五)
            java.time.LocalDate.of(2026, 9, 25),
            // 国庆 10/1(四)~10/7(三) → 工作日：10/1(四)、10/2(五)、10/5(一)~10/7(三)（10/8 开市，旧表误记）
            java.time.LocalDate.of(2026, 10, 1), java.time.LocalDate.of(2026, 10, 2),
            java.time.LocalDate.of(2026, 10, 5), java.time.LocalDate.of(2026, 10, 6),
            java.time.LocalDate.of(2026, 10, 7),
            // ── 2027（预测，官方通常年底发布）──
            // 元旦 1/1(五)
            java.time.LocalDate.of(2027, 1, 1),
            // 春节 2/3(三,除夕)~2/9(二) → 工作日：2/3(三)~2/5(五)、2/8(一)、2/9(二)
            java.time.LocalDate.of(2027, 2, 3), java.time.LocalDate.of(2027, 2, 4),
            java.time.LocalDate.of(2027, 2, 5), java.time.LocalDate.of(2027, 2, 8),
            java.time.LocalDate.of(2027, 2, 9),
            // 清明 4/4(日)~4/6(二) → 工作日：4/5(一)、4/6(二)
            java.time.LocalDate.of(2027, 4, 5), java.time.LocalDate.of(2027, 4, 6),
            // 劳动节 5/1(六)~5/5(三) → 工作日：5/3(一)~5/5(三)
            java.time.LocalDate.of(2027, 5, 3), java.time.LocalDate.of(2027, 5, 4),
            java.time.LocalDate.of(2027, 5, 5),
            // 端午 6/9(三)（预测单日）
            java.time.LocalDate.of(2027, 6, 9),
            // 中秋 9/15(三)（2027 农历八月十五，预测单日——C2 修正：不在国庆，独立 9 月）
            java.time.LocalDate.of(2027, 9, 15),
            // 国庆 10/1(五)~10/7(四) → 工作日：10/1(五)、10/4(一)~10/7(四)（10/8 开市——C2 修正：2027 中秋不在国庆，无 8 天长假）
            java.time.LocalDate.of(2027, 10, 1), java.time.LocalDate.of(2027, 10, 4),
            java.time.LocalDate.of(2027, 10, 5), java.time.LocalDate.of(2027, 10, 6),
            java.time.LocalDate.of(2027, 10, 7)
    );

    /** 是否 A 股交易日（周末由 cron 排除；此处补法定节假日）。 */
    static boolean isTradingDay(java.time.LocalDate date) {
        return !HOLIDAYS.contains(date);
    }

    /** 早盘计划（9:15）：持仓 + 止损/买点 + 今日关注。 */
    @Scheduled(cron = "${adai.trading.session.morning-cron:" + CRON_MORNING + "}")
    public void morningPlan() {
        if (!isTradingDay(java.time.LocalDate.now())) return;
        forEachTradingUser(userId -> {
            String content = generateContent(userId, "早盘计划", "morning-plan", this::buildMorningTemplate);
            pushToAll(userId, "早盘计划", content, "session", null, null);
        });
    }

    /** 午间跟踪（12:00）：上午表现 + 是否触发止损 + 计划更新。 */
    @Scheduled(cron = "${adai.trading.session.midday-cron:" + CRON_MIDDAY + "}")
    public void middayTracking() {
        if (!isTradingDay(java.time.LocalDate.now())) return;
        forEachTradingUser(userId -> {
            String content = generateContent(userId, "午间跟踪", "midday-tracking", this::buildMiddayTemplate);
            pushToAll(userId, "午间跟踪", content, "session", null, null);
        });
    }

    /** 尾盘建议（14:50）：逐票建议（R66/R81）+ 明日关注 + 复盘提醒。 */
    @Scheduled(cron = "${adai.trading.session.close-cron:" + CRON_CLOSE + "}")
    public void closeAdvice() {
        // P2-1（2026-08-17 走查）：同文件 4/5 个定时任务都有 isTradingDay，唯独它漏——节假日照常推尾盘建议
        if (!isTradingDay(java.time.LocalDate.now())) return;
        forEachTradingUser(userId -> {
            String content = generateContent(userId, "尾盘建议", "close-advice", this::buildCloseTemplate);
            pushToAll(userId, "尾盘建议", content, "session", null, null);
        });
    }

    /**
     * 收盘小结（15:30，P2-用户3 2026-08-29）：当日成交 + 破止损持仓 + 待确认候选 + 一句话收尾。
     * <p>
     * 用户原话「交易帮不到忙、没有感觉」——收盘后把「今天发生了什么 + 明天该注意什么」送到手机
     * （Bark 已接，iOS 原生推送）。模板聚合客观数字，**不耗 AI**（秒出、稳定）；类型 close-summary
     * 受推送开关门控（与其它类型一致，前端可单独关）。
     */
    @Scheduled(cron = "${adai.trading.session.close-summary-cron:" + CRON_CLOSE_SUMMARY + "}")
    public void closeSummaryPush() {
        if (!isTradingDay(java.time.LocalDate.now())) return;
        java.time.LocalDate today = java.time.LocalDate.now();
        forEachTradingUser(userId -> {
            SessionData data = loadData(userId);
            // 今日真实成交（过滤股息流水 volume=0——股息入账/红利税不计入买卖笔数）
            int buy = 0, sell = 0;
            try {
                for (com.adaiadai.core.domain.trading.TradeRecord tr :
                        tradingAppService.getTradeHistory(userId, today, today)) {
                    if (tr.volume() <= 0) continue;
                    if (tr.direction() == com.adaiadai.core.domain.trading.TradeDirection.BUY) buy++; else sell++;
                }
            } catch (RuntimeException e) {
                log.warn("收盘小结：今日成交统计失败 | userId={} | {}", userId, e.getMessage());
            }
            int pending = tradeLogCollectService.todayCandidates(userId).size();
            String content = buildCloseSummaryTemplate(data, userId, buy, sell, pending);
            pushToAll(userId, "收盘小结", content, "close-summary", null, null);
        });
    }

    /** 收盘 15:05 账户自动更新（B1，2026-08-16）：行情可得部分自动——参考市值/当日盈亏/持仓浮盈；
     *  现金/可用/本金保持券商导入值与转账推导。
     *  P2-交易24（2026-08-29 注释如实化）：positions/行情在 account.json update() 锁**外**读取——
     *  与 recordTrade 并发时存在跨文件残余窗口（快照=新现金+旧市值）；收盘 15:05 与手动记录并发概率极低、
     *  次日收盘自愈，无原子跨文件手段（详见 trading-features §8 跨文件窗口注意点）。 */
    @Scheduled(cron = "${adai.trading.session.close-update-cron:0 5 15 * * MON-FRI}")
    public void closeAccountUpdate() {        if (!isTradingDay(java.time.LocalDate.now())) return;
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
                            "market", null, null);
                } catch (RuntimeException e) {
                    log.warn("收盘缺行情通知推送失败 | userId={} | {}", userId, e.getMessage());
                }
                return;
            }
            final java.math.BigDecimal fMarket = marketValue;
            final java.math.BigDecimal fToday = todayPnl;
            final java.math.BigDecimal fFloat = floatPnl;
            // P0-2（2026-08-23）：account.json 写统一走 update（per-user 锁原子 RMW）——
            // 原 findLatest+save 无锁，与 recordTrade/转账/资金导入并发整文件互相覆盖
            // B6-4（2026-08-23，P1-交易11）：写失败明确告警（外层 forEachTradingUser 兜底不中断整批）
            try {
                accountSnapshotRepository.update(userId, cur -> cur.map(c -> {
                    AccountSnapshot next = new AccountSnapshot(
                            fMarket.add(c.cash()), c.cash(), c.available(), c.withdrawable(),
                            fMarket, fFloat, fToday, c.principal(), java.time.LocalDate.now());
                    log.info("收盘账户更新 | userId={} | 市值={} 当日盈亏={} 浮盈={}",
                            userId, fMarket, fToday, fFloat);
                    return next;
                }).orElse(null)); // 无快照（未导入资金）不初始化
            } catch (RuntimeException e) {
                log.error("收盘账户更新写失败——账目未落盘 | userId={} | {}", userId, e.getMessage());
            }
        });
    }

    /** RFC 20260817 收盘交易日志确认（15:15）：当日有归集候选 → 推送「今日操作汇总，是否完整」。
     *  用户确认后由交易模块落库；无候选静默跳过。 */
    @Scheduled(cron = "${adai.trading.session.trade-log-confirm-cron:0 15 15 * * MON-FRI}")
    public void tradeLogConfirm() {
        if (!isTradingDay(java.time.LocalDate.now())) return;
        forEachTradingUser(userId -> {
            var candidates = tradeLogCollectService.todayCandidates(userId);
            if (candidates.isEmpty()) return;
            String content = tradeLogCollectService.summarize(candidates);
            pushToAll(userId, "今日操作确认", content, "session", null, null);
        });
    }

    /** 收盘 15:10 自选股买点扫描推送（C2）：命中 B1/B2 → 「到买点了」。 */
    @Scheduled(cron = "${adai.trading.session.buy-point-cron:0 10 15 * * MON-FRI}")
    public void buyPointScan() {
        if (!isTradingDay(java.time.LocalDate.now())) return;
        forEachTradingUser(userId -> {
            List<WatchlistItem> watchlist = watchlistRepository.findAll(userId);
            if (watchlist.isEmpty()) return;
            List<WatchlistBuyPointService.WatchBuyPoint> hits =
                    buyPointService.scanWatchlist(watchlist, userId);
            for (WatchlistBuyPointService.WatchBuyPoint h : hits) {
                // P2-交易7（2026-08-17）：B1?（部分满足候选）不推送——「不硬推」声明；
                // 只有正式 B1/B2 才推「到买点了」，B1? 留给 web 信号列灰显
                if (h.buyPoint().endsWith("?")) {
                    log.info("自选买点候选 B1? 不推送 | symbol={}", h.symbol());
                    continue;
                }
                String content = h.buyPoint().startsWith("B1")
                        ? "📌 " + h.name() + "（" + h.symbol() + "）到 B1 买点区了：" + String.join("、", h.signals())
                        + "——按纪律设好止损再进（R68）"
                        : "🚀 " + h.name() + "（" + h.symbol() + "）放量突破，B2 右侧信号：" + String.join("、", h.signals())
                        + "——按纪律设好止损再进（R68）";
                pushToAll(userId, "买点提醒", content, "buy-point", h.symbol(), h.name());
            }
            log.info("自选买点推送 | userId={} | {} 命中", userId, hits.size());
        });
    }

    // ── 数据组装 ──

    /** 持仓 + 行情 + 引擎判定 + 择时状态 + 现金的完整数据（模板与 LLM 共用）。 */
    private SessionData loadData(String userId) {
        List<Position> positions = positionRepository.findAll(userId);
        Map<String, MarketData> quotes = Map.of();
        if (!positions.isEmpty()) {
            try {
                quotes = marketDataSource.quote(positions.stream().map(Position::symbol).toList());
            } catch (Exception e) {
                log.warn("时段推送：行情查询失败 | {}", e.getMessage());
            }
        }
        // P1-交易4（2026-08-17）：现金唯一真源 = account.json AccountSnapshot.cash（S5）
        BigDecimal cash = accountSnapshotRepository.findLatest(userId)
                .map(AccountSnapshot::cash)
                .orElse(BigDecimal.ZERO);
        return new SessionData(positions, quotes, readMarketStage(), cash);
    }

    private String readMarketStage() {
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

    // ── 内容生成（阶段二 LLM 优先，降级模板）──

    private String generateContent(String userId, String node, String scene, TemplateBuilder fallback) {
        SessionData data = loadData(userId);
        if (data.positions().isEmpty()) {
            return switch (scene) {
                case "morning-plan" -> "早上好！今天还没有持仓，空仓也是一种策略——等待好的买点，不着急。";
                case "midday-tracking" -> "午间跟踪：目前空仓，没有需要盯的标的。";
                default -> "尾盘建议：今天空仓收盘。明天继续等信号，保持耐心。";
            };
        }
        String dataText = buildDataText(data, userId);
        try {
            AiTraceContext.set(userId, null, null, "trading_session_" + scene);
            ContextPackage ctx = ContextPackage.simple(
                    "trading", null, node, dataText, List.of("trading", "推送"), dataText);
            String raw = aiClient.generate(ctx, SESSION_SYSTEM_PROMPT.replace("{节点}", node));
            if (raw != null && !raw.isBlank()) return raw.strip();
        } catch (Exception e) {
            log.warn("时段推送 LLM 生成失败，降级模板 | node={} | {}", node, e.getMessage());
        }
        return fallback.build(data, userId);
    }

    /** 数据文本（注入 LLM 的上下文）。 */
    private String buildDataText(SessionData data, String userId) {
        StringBuilder sb = new StringBuilder();
        sb.append("【当前持仓】\n");
        for (Position p : data.positions()) {
            MarketData md = data.quotes().get(p.symbol());
            sb.append("- ").append(p.name()).append("(").append(p.symbol()).append(")")
                    .append(" 数量").append(p.quantity())
                    .append(" 成本").append(fmt(p.avgCost()));
            if (md != null) {
                sb.append(" 现价").append(fmt(md.price()));
                // B6-3：changePercent 可 null（行情字段残缺）→ 显示 "-" 不 NPE
                if (md.changePercent() != null) {
                    sb.append(" 涨跌").append(fmt(md.changePercent())).append("%");
                }
            }
            sb.append(" 止损位").append(p.stopLossPrice() != null ? fmt(p.stopLossPrice()) : "未设置")
                    .append(" 买点").append(p.buyPoint() != null ? p.buyPoint() : "未知");
            var sl = ruleEngine.evaluateStopLoss(userId, md != null ? md.price() : null, p.stopLossPrice());
            if (sl.verdict() == StopLossVerdict.BREACHED) {
                sb.append(" ⚠️已跌破止损位（R66）");
            }
            sb.append("\n");
        }
        sb.append("【择时状态】").append(data.marketStage()).append("\n");
        return sb.toString();
    }

    // ── 模板（阶段一 / LLM 降级）──

    private String buildMorningTemplate(SessionData data, String userId) {
        // RFC 20260817：结构化——总结 + 每持仓一行（名称/数量/止损/买点）+ 纪律提醒
        StringBuilder sb = new StringBuilder("📋 早盘计划\n");
        sb.append("总结：").append(data.positions().size()).append(" 只持仓，今日按纪律执行。\n");
        for (Position p : data.positions()) {
            sb.append("· ").append(p.name())
                    .append("（数量 ").append(p.quantity()).append("）")
                    .append(" 止损 ").append(fmt(p.stopLossPrice()))
                    .append(" 买点 ").append(p.buyPoint() != null ? p.buyPoint() : "未知").append("\n");
        }
        sb.append("择时：").append(data.marketStage()).append("。\n");
        sb.append("建议：不追高不抄底，破止损按 R66 处理。");
        return sb.toString();
    }

    private String buildMiddayTemplate(SessionData data, String userId) {
        // RFC 20260817：结构化——总结 + 每持仓现价/涨跌/状态
        StringBuilder sb = new StringBuilder("📊 午间跟踪\n");
        sb.append("总结：上午表现如下，下午重点盯止损位。\n");
        for (Position p : data.positions()) {
            MarketData md = data.quotes().get(p.symbol());
            // B6-3：changePercent 可 null → 显示 "-" 不 NPE
            BigDecimal changePct = md != null ? md.changePercent() : null;
            String change = changePct != null
                    ? (changePct.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "") + fmt(changePct) + "%"
                    : "-";
            var sl = ruleEngine.evaluateStopLoss(userId, md != null ? md.price() : null, p.stopLossPrice());
            String status = sl.verdict() == StopLossVerdict.BREACHED
                    ? "⚠️已破止损（R66）" : "未触发止损";
            sb.append("· ").append(p.name()).append(" 现价 ")
                    .append(fmt(md != null ? md.price() : null))
                    .append("（").append(change).append("） ").append(status).append("\n");
        }
        sb.append("建议：计划暂不大改，破止损的按纪律处理。");
        return sb.toString();
    }

    private String buildCloseTemplate(SessionData data, String userId) {
        // RFC 20260817：结构化——总结 + 每持仓一行（现价/涨跌/建议）
        StringBuilder sb = new StringBuilder("📉 尾盘建议\n");
        sb.append("总结：").append(data.positions().size()).append(" 只持仓，逐票建议如下。\n");
        for (Position p : data.positions()) {
            MarketData md = data.quotes().get(p.symbol());
            // B6-3（2026-08-23，P1-交易13）：md 非 null 但 price/changePercent 可 null——
            // 原 `md.changePercent().compareTo()` 直接 NPE 崩整个尾盘推送（单用户异常中断整批）
            BigDecimal price = md != null && md.price() != null ? md.price() : p.currentPrice();
            BigDecimal changePct = md != null ? md.changePercent() : null;
            String change = changePct != null
                    ? (changePct.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "") + fmt(changePct) + "%"
                    : "-";
            var sl = ruleEngine.evaluateStopLoss(userId, price, p.stopLossPrice());
            BigDecimal percent = positionPercent(p, data.positions(), data.quotes(), data.cash());
            var pv = ruleEngine.evaluatePosition(userId, percent);
            String advice;
            if (sl.verdict() == StopLossVerdict.BREACHED) {
                advice = "清仓（R66）";
            } else if (pv.verdict() == PositionVerdict.OVER_WEIGHT && r81Applicable(data)) {
                // B3-2（2026-08-23，P2-交易21 半修残留）：R81 减仓判定须过「总资产 <100 万」前提——
                // 与 TradingAdviceAppService 输出侧同口径（超 100 万按 R82-R95 配置评估，不强制减仓）
                advice = "减仓（占比 " + fmt(percent) + "% 超 R81）";
            } else {
                advice = "持有";
            }
            sb.append("· ").append(p.name()).append(" 现价 ").append(fmt(price))
                    .append("（").append(change).append("） → ").append(advice).append("\n");
        }
        sb.append("明日关注：").append(data.marketStage()).append("。收盘后要不要做今日复盘？");
        return sb.toString();
    }

    /** 收盘小结模板（P2-用户3，2026-08-29）：客观数字聚合，不耗 AI；阿呆口吻（B1 无系统标签）。 */
    private String buildCloseSummaryTemplate(SessionData data, String userId, int buyCount, int sellCount, int pending) {
        StringBuilder sb = new StringBuilder("📋 收盘小结\n");
        int total = buyCount + sellCount;
        if (total > 0) {
            sb.append("今日成交：买 ").append(buyCount).append(" 笔 · 卖 ").append(sellCount).append(" 笔");
            if (pending > 0) sb.append(" · 还有 ").append(pending).append(" 笔没确认（15:15 那条记得点）");
            sb.append("\n");
        } else if (pending > 0) {
            sb.append("今天有 ").append(pending).append(" 笔操作还没确认——点一下「确认并入账」就记上了\n");
        }
        if (!data.positions().isEmpty()) {
            sb.append("持仓 ").append(data.positions().size()).append(" 只：\n");
        }
        int breached = 0;
        for (Position p : data.positions()) {
            MarketData md = data.quotes().get(p.symbol());
            BigDecimal price = md != null && md.price() != null ? md.price() : p.currentPrice();
            var sl = ruleEngine.evaluateStopLoss(userId, price, p.stopLossPrice());
            boolean isBreached = sl.verdict() == StopLossVerdict.BREACHED;
            if (isBreached) breached++;
            sb.append("· ").append(p.name()).append(" 现价 ").append(fmt(price))
                    .append(isBreached ? " ⚠️ 破止损" : "").append("\n");
        }
        // 一句话收尾（按状态给建议，参考不是指令）
        if (breached > 0) {
            sb.append("有 ").append(breached).append(" 只破了止损没走——明早开盘按纪律处理（R66）。");
        } else if (total > 0) {
            sb.append("今天有操作——收盘后做个复盘，看看执行得怎么样？");
        } else {
            sb.append("今天没有操作，持仓按计划拿着就行。");
        }
        return sb.toString();
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
        // RFC 20260817：推送开关——用户关闭的类型不推送（session=早/午/尾盘，buy-point=买点）
        if (!pushSettingsRepository.findByUser(userId).isEnabled(type)) {
            log.info("时段推送跳过（用户关闭）| userId={} | type={}", userId, type);
            return;
        }
        PushChannel.PushMessage message = new PushChannel.PushMessage(
                title, content, type, symbol, name, LocalTime.now());
        for (PushChannel channel : pushChannels) {
            if (channel.enabled()) {
                channel.push(userId, message);
            }
        }
        log.info("时段推送完成 | userId={} | title={}", userId, title);
    }

    private String fmt(BigDecimal v) {
        return v != null ? v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() : "-";
    }

    /** 时段推送数据载体。 */
    record SessionData(List<Position> positions, Map<String, MarketData> quotes, String marketStage,
                      BigDecimal cash) {}

    @FunctionalInterface
    private interface TemplateBuilder {
        String build(SessionData data, String userId);
    }
}
