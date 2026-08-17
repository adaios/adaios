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

    private static final String SESSION_SYSTEM_PROMPT = """
            你是阿呆，用户的个人 AI 助手，用自然、亲切、简洁的中文（无系统标签，像朋友聊天）。
            基于给出的持仓、行情与规则判定数据，生成{节点}消息。要求：
            1. 第一句直接进入主题，不要寒暄废话
            2. 逐票提到时引用其止损位/买点/占比
            3. 引用规则时用 R 编号（如 R66/R81）
            4. 建议是参考不是指令，语气是"我建议"而非"你必须"
            5. 30-80 字以内
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
        this.currentMd = Paths.get(knowledgeDir, "current.md").toAbsolutePath().normalize();
        log.info("时段推送：择时状态来源 current.md = {}", currentMd);
    }

    // ── 三节点 cron ──

    /**
     * P3（2026-08-17）：A 股法定节假日（2026-2027 主要休市日）——节假日不推送/不改账。
     * cron 已排除周末（MON-FRI），此处补法定假日。仅维护主要节假日，临时调休不追。
     */
    static final java.util.Set<java.time.LocalDate> HOLIDAYS = java.util.Set.of(
            // 2026 国庆（10-01 ~ 10-08）
            java.time.LocalDate.of(2026, 10, 1), java.time.LocalDate.of(2026, 10, 2),
            java.time.LocalDate.of(2026, 10, 5), java.time.LocalDate.of(2026, 10, 6),
            java.time.LocalDate.of(2026, 10, 7), java.time.LocalDate.of(2026, 10, 8),
            // 2027 元旦（1-01 ~ 1-03）+ 春节（约 2 月初）
            java.time.LocalDate.of(2027, 1, 1), java.time.LocalDate.of(2027, 1, 4),
            java.time.LocalDate.of(2027, 2, 15), java.time.LocalDate.of(2027, 2, 16),
            java.time.LocalDate.of(2027, 2, 17), java.time.LocalDate.of(2027, 2, 18),
            java.time.LocalDate.of(2027, 2, 19), java.time.LocalDate.of(2027, 2, 22)
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
        forEachTradingUser(userId -> {
            String content = generateContent(userId, "尾盘建议", "close-advice", this::buildCloseTemplate);
            pushToAll(userId, "尾盘建议", content, "session", null, null);
        });
    }

    /** 收盘 15:05 账户自动更新（B1，2026-08-16）：行情可得部分自动——参考市值/当日盈亏/持仓浮盈；
     *  现金/可用/本金保持券商导入值与转账推导。 */
    @Scheduled(cron = "${adai.trading.session.close-update-cron:0 5 15 * * MON-FRI}")
    public void closeAccountUpdate() {
        if (!isTradingDay(java.time.LocalDate.now())) return;
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
                if (md.yesterdayClose() != null) {
                    todayPnl = todayPnl.add(md.price().subtract(md.yesterdayClose())
                            .multiply(java.math.BigDecimal.valueOf(p.quantity())));
                }
                floatPnl = floatPnl.add(md.price().subtract(p.avgCost())
                        .multiply(java.math.BigDecimal.valueOf(p.quantity())));
            }
            // P1-交易3：任一持仓缺行情 → 本次不覆盖（保留旧快照，等行情恢复）
            if (missingQuotes > 0) {
                log.warn("收盘账户更新：{} 只缺行情，跳过保存保留旧快照 | userId={} | 缺失={}",
                        missingQuotes, userId, positions.stream()
                                .filter(p -> quotes.get(p.symbol()) == null || quotes.get(p.symbol()).price() == null)
                                .map(Position::symbol).toList());
                return;
            }
            final java.math.BigDecimal fMarket = marketValue;
            final java.math.BigDecimal fToday = todayPnl;
            final java.math.BigDecimal fFloat = floatPnl;
            accountSnapshotRepository.findLatest(userId).ifPresent(cur -> {
                accountSnapshotRepository.save(userId, new AccountSnapshot(
                        fMarket.add(cur.cash()), cur.cash(), cur.available(), cur.withdrawable(),
                        fMarket, fFloat, fToday, cur.principal(), java.time.LocalDate.now()));
                log.info("收盘账户更新 | userId={} | 市值={} 当日盈亏={} 浮盈={}",
                        userId, fMarket, fToday, fFloat);
            });
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
                    buyPointService.scanWatchlist(watchlist);
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

    /** 持仓 + 行情 + 引擎判定 + 择时状态的完整数据（模板与 LLM 共用）。 */
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
        return new SessionData(positions, quotes, readMarketStage());
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
        String dataText = buildDataText(data);
        try {
            AiTraceContext.set(userId, null, null, "trading_session_" + scene);
            ContextPackage ctx = ContextPackage.simple(
                    "trading", null, node, dataText, List.of("trading", "推送"), dataText);
            String raw = aiClient.generate(ctx, SESSION_SYSTEM_PROMPT.replace("{节点}", node));
            if (raw != null && !raw.isBlank()) return raw.strip();
        } catch (Exception e) {
            log.warn("时段推送 LLM 生成失败，降级模板 | node={} | {}", node, e.getMessage());
        }
        return fallback.build(data);
    }

    /** 数据文本（注入 LLM 的上下文）。 */
    private String buildDataText(SessionData data) {
        StringBuilder sb = new StringBuilder();
        sb.append("【当前持仓】\n");
        for (Position p : data.positions()) {
            MarketData md = data.quotes().get(p.symbol());
            sb.append("- ").append(p.name()).append("(").append(p.symbol()).append(")")
                    .append(" 数量").append(p.quantity())
                    .append(" 成本").append(fmt(p.avgCost()));
            if (md != null) sb.append(" 现价").append(fmt(md.price()))
                    .append(" 涨跌").append(fmt(md.changePercent())).append("%");
            sb.append(" 止损位").append(p.stopLossPrice() != null ? fmt(p.stopLossPrice()) : "未设置")
                    .append(" 买点").append(p.buyPoint() != null ? p.buyPoint() : "未知");
            var sl = ruleEngine.evaluateStopLoss(md != null ? md.price() : null, p.stopLossPrice());
            if (sl.verdict() == StopLossVerdict.BREACHED) {
                sb.append(" ⚠️已跌破止损位（R66）");
            }
            sb.append("\n");
        }
        sb.append("【择时状态】").append(data.marketStage()).append("\n");
        return sb.toString();
    }

    // ── 模板（阶段一 / LLM 降级）──

    private String buildMorningTemplate(SessionData data) {
        StringBuilder sb = new StringBuilder("早上好！今天的交易计划：");
        sb.append("当前 ").append(data.positions().size()).append(" 只持仓。");
        for (Position p : data.positions()) {
            sb.append(p.name()).append("：止损 ").append(fmt(p.stopLossPrice()))
                    .append("，买点 ").append(p.buyPoint() != null ? p.buyPoint() : "未知").append("；");
        }
        sb.append("择时：").append(data.marketStage()).append("。今日按纪律执行，不追高不抄底。");
        return sb.toString();
    }

    private String buildMiddayTemplate(SessionData data) {
        StringBuilder sb = new StringBuilder("午间跟踪：");
        for (Position p : data.positions()) {
            MarketData md = data.quotes().get(p.symbol());
            String change = md != null ? (md.changePercent().compareTo(BigDecimal.ZERO) >= 0 ? "+" : "")
                    + fmt(md.changePercent()) + "%" : "-";
            var sl = ruleEngine.evaluateStopLoss(md != null ? md.price() : null, p.stopLossPrice());
            String status = sl.verdict() == StopLossVerdict.BREACHED
                    ? "⚠️已跌破止损位（R66），建议减仓/清仓" : "未触发止损";
            sb.append(p.name()).append(" 现价 ").append(fmt(md != null ? md.price() : null))
                    .append("（").append(change).append("），").append(status).append("；");
        }
        sb.append("计划暂时不用大改，下午重点盯止损位。");
        return sb.toString();
    }

    private String buildCloseTemplate(SessionData data) {
        StringBuilder sb = new StringBuilder("尾盘建议：");
        for (Position p : data.positions()) {
            MarketData md = data.quotes().get(p.symbol());
            BigDecimal price = md != null ? md.price() : p.currentPrice();
            var sl = ruleEngine.evaluateStopLoss(price, p.stopLossPrice());
            BigDecimal percent = positionPercent(p, data.positions(), data.quotes());
            var pv = ruleEngine.evaluatePosition(percent);
            String advice;
            if (sl.verdict() == StopLossVerdict.BREACHED) {
                advice = "已跌破止损位 → 建议清仓（R66）";
            } else if (pv.verdict() == PositionVerdict.OVER_WEIGHT) {
                advice = "占比 " + fmt(percent) + "% 超 R81 上限 → 建议减仓";
            } else {
                advice = "未破止损、仓位合规 → 持有";
            }
            sb.append(p.name()).append("：").append(advice).append("；");
        }
        sb.append("明日关注：").append(data.marketStage()).append("。收盘后要不要做今日复盘？");
        return sb.toString();
    }

    /** 单票占比（总资产口径，FP-P2：市值 + 现金，现金不可用按 0）。 */
    private BigDecimal positionPercent(Position target, List<Position> positions, Map<String, MarketData> quotes) {
        BigDecimal total = BigDecimal.ZERO;
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
    record SessionData(List<Position> positions, Map<String, MarketData> quotes, String marketStage) {}

    @FunctionalInterface
    private interface TemplateBuilder {
        String build(SessionData data);
    }
}
