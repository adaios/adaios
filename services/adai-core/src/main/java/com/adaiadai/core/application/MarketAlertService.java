package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.engine.StopLossVerdict;
import com.adaiadai.core.domain.trading.engine.TradingRuleEngine;
import com.adaiadai.core.infrastructure.storage.MarketSnapshotRepository;
import com.adaiadai.core.infrastructure.storage.PushSettingsRepository;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.domain.trading.market.MarketData;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MarketAlertService — 行情异动主动推送（Layer 2/5 主动推送，Phase 2）。
 * <p>
 * 交易时段（工作日 9:30-11:30 / 13:00-15:00，cron 可配）每 30 分钟轮询持仓行情，
 * 检测四类异动并写入当日推送事件（{@code data/{userId}/trading/pushes/{date}.json}）：
 * <ul>
 *   <li><b>stop-loss</b>：现价跌破用户预设止损位（R66 硬判定，G-3 引擎口径）→ 真止损预警（2026-08-16 新增）</li>
 *   <li><b>loss</b>：单日跌幅 ≥ {@code loss-threshold}（默认 3%）→ 止损预警</li>
 *   <li><b>gain</b>：单日涨幅 ≥ {@code gain-threshold}（默认 5%）→ 放飞提示</li>
 *   <li><b>break-cost</b>：现价跌破成本线（默认开启）→ 风控提醒</li>
 * </ul>
 * 同一持仓同类异动当日只推一次（{@link MarketSnapshotRepository} 签名去重），
 * 避免每轮询刷屏。RFC 20260816：推送走 {@link PushChannel} 渠道插件化——
 * Feed 默认（App 内）+ 微信（Server酱，不打开也收到），外部渠道随账号配置。
 * 推送载体 = Feed 内展示 + 外部渠道，"你不问，App 也告诉你今天需要知道的"。
 */
@Service
public class MarketAlertService {

    private static final Logger log = LoggerFactory.getLogger(MarketAlertService.class);

    private final MarketDataSource marketDataSource;
    private final PositionRepository positionRepository;
    private final AccountRepository accountRepository;
    private final MarketSnapshotRepository snapshotRepository;
    private final List<PushChannel> pushChannels;
    private final PluginService pluginService;
    /** G-3 规则引擎：stop-loss 判定口径与建议引擎一致（R66，现价口径）。 */
    private final TradingRuleEngine ruleEngine;
    /** RFC 20260817：推送开关（用户可关闭各类型推送）。 */
    private final PushSettingsRepository pushSettingsRepository;

    private final BigDecimal lossThreshold;
    private final BigDecimal gainThreshold;
    private final boolean breakCostEnabled;
    /** C3 接近止损阈值（距止损百分比，默认 2%）。 */
    private final BigDecimal nearStopLossPct;

    public MarketAlertService(MarketDataSource marketDataSource,
                              PositionRepository positionRepository,
                              AccountRepository accountRepository,
                              MarketSnapshotRepository snapshotRepository,
                              List<PushChannel> pushChannels,
                              PluginService pluginService,
                              TradingRuleEngine ruleEngine,
                              PushSettingsRepository pushSettingsRepository,
                              @Value("${adai.market.alert.loss-threshold:3.0}") double lossThreshold,
                              @Value("${adai.market.alert.gain-threshold:5.0}") double gainThreshold,
                              @Value("${adai.market.alert.break-cost-enabled:true}") boolean breakCostEnabled,
                              @Value("${adai.market.alert.near-stop-loss-pct:2.0}") double nearStopLossPct) {
        this.marketDataSource = marketDataSource;
        this.positionRepository = positionRepository;
        this.accountRepository = accountRepository;
        this.snapshotRepository = snapshotRepository;
        this.pushChannels = pushChannels;
        this.pluginService = pluginService;
        this.ruleEngine = ruleEngine;
        this.pushSettingsRepository = pushSettingsRepository;
        this.lossThreshold = BigDecimal.valueOf(lossThreshold);
        this.gainThreshold = BigDecimal.valueOf(gainThreshold);
        this.breakCostEnabled = breakCostEnabled;
        this.nearStopLossPct = BigDecimal.valueOf(nearStopLossPct);
    }

    /**
     * 定时轮询：遍历启用账号中**启用了 trading 插件**的用户逐用户检测（REVIEW S-4：写侧与 Feed 读侧
     * 门控对称——无插件用户磁盘不累积看不见的 push 残留、不做无谓行情轮询）。
     * 交易时段 cron 可通过 {@code adai.market.alert.poll-cron} 配置（默认工作日 9-11/13-15 点每 30 分钟）。
     */
    @Scheduled(cron = "${adai.market.alert.poll-cron:0 */30 9-11,13-15 * * MON-FRI}")
    public void poll() {
        Set<String> userIds = new LinkedHashSet<>();
        accountRepository.findAll().stream()
                .filter(Account::enabled)
                // S-4：行情推送是 trading 插件能力，只轮询启用该插件的账号（与 Feed 门控口径一致）；
                // P2-B2：userId null 防护（Account 构造器已拒绝，双保险防脏文件历史残留）
                .filter(a -> a.userId() != null
                        && pluginService.hasPlugin(a.userId(), PluginRegistry.PLUGIN_TRADING))
                .map(Account::userId)
                .forEach(userIds::add);
        if (userIds.isEmpty()) {
            log.warn("行情轮询：无启用账号，跳过（预期外，账号表应至少含 seed adai）");
            return;
        }
        for (String userId : userIds) {
            try {
                poll(userId);
            } catch (Exception e) {
                log.warn("行情轮询失败 | userId={} | {}", userId, e.getMessage());
            }
        }
    }

    /** 检测单个用户持仓异动；无持仓 / 行情拉取失败时静默跳过。 */
    public void poll(String userId) {
        List<Position> positions = positionRepository.findAll(userId);
        if (positions.isEmpty()) return;

        LocalDate today = LocalDate.now();
        Set<String> existing = snapshotRepository.alertedSignatures(userId, today);
        Set<String> newSignatures = new HashSet<>(existing);
        List<PushChannel.PushMessage> alerts = new ArrayList<>();

        Map<String, MarketData> quotes = marketDataSource.quote(positions.stream().map(Position::symbol).toList());
        if (quotes.isEmpty()) return; // 网络/接口失败：保留快照，不误推

        for (Position p : positions) {
            MarketData md = quotes.get(p.symbol());
            // B5-2（2026-08-23）：行情缺失整只跳过；changePercent 缺失只跳过涨跌类判定——
            // 原「md.changePercent()==null 即 continue」连带漏掉只依赖 price 的 R66 止损/接近止损/破成本
            if (md == null || md.price() == null) continue;

            BigDecimal change = md.changePercent(); // 可 null（涨跌类判定据此跳过）

            // 真止损预警（2026-08-16）：现价跌破用户预设止损位 → R66 硬判定（引擎口径，与建议引擎一致）
            // 止损位未设置（旧数据）不判——R68 入场即设止损，买入时已强制填写
            if (p.stopLossPrice() != null
                    && ruleEngine.evaluateStopLoss(md.price(), p.stopLossPrice()).verdict()
                    == StopLossVerdict.BREACHED) {
                addIfNew(userId, p, md, change, "stop-loss", existing, newSignatures, alerts);
            }
            // C3 接近止损预警（2026-08-16）：未跌破但距止损 ≤ nearStopLossPct（默认 2%，可配）
            if (p.stopLossPrice() != null && md.price().compareTo(p.stopLossPrice()) > 0) {
                BigDecimal gapPct = md.price().subtract(p.stopLossPrice())
                        .multiply(BigDecimal.valueOf(100)).divide(md.price(), 2, RoundingMode.HALF_UP);
                if (gapPct.compareTo(nearStopLossPct) <= 0) {
                    addIfNew(userId, p, md, change, "near-stop-loss", existing, newSignatures, alerts);
                }
            }
            // 止损预警：单日跌幅 ≥ 阈值（依赖 changePercent，缺失跳过）
            if (change != null && change.compareTo(lossThreshold.negate()) <= 0) {
                addIfNew(userId, p, md, change, "loss", existing, newSignatures, alerts);
            }
            // 放飞提示：单日涨幅 ≥ 阈值（依赖 changePercent，缺失跳过）
            if (change != null && change.compareTo(gainThreshold) >= 0) {
                addIfNew(userId, p, md, change, "gain", existing, newSignatures, alerts);
            }
            // 跌破成本线风控提醒（只依赖 price）
            if (breakCostEnabled && p.avgCost() != null
                    && md.price().compareTo(p.avgCost()) < 0) {
                addIfNew(userId, p, md, change, "break-cost", existing, newSignatures, alerts);
            }
        }

        if (!alerts.isEmpty()) {
            // 2026-08-20 生产「微信双份」：同股票同轮命中多个异动类型（如 loss + break-cost）
            // 各生成一条 → 微信收到同股票两条内容重叠的消息。合并为一条（保留最严重类型 + 内容拼接）。
            List<PushChannel.PushMessage> merged = mergeBySymbol(alerts);
            // RFC 20260816：推送走渠道插件化——Feed（默认落盘）+ 微信（外部）等所有 enabled 渠道
            for (PushChannel.PushMessage m : merged) {
                for (PushChannel channel : pushChannels) {
                    if (channel.enabled()) {
                        channel.push(userId, m);
                    }
                }
            }
            snapshotRepository.saveSignatures(userId, today, newSignatures);
            log.info("行情异动推送 | userId={} | {} 条（合并前 {}）| {}", userId, merged.size(), alerts.size(),
                    merged.stream().map(PushChannel.PushMessage::type).toList());
        }
    }

    /** 异动类型严重度（合并时保留最严重者）：真止损 > 单日大跌 > 跌破成本 > 接近止损 > 放飞。 */
    private int severity(String type) {
        return switch (type) {
            case "stop-loss" -> 5;
            case "loss" -> 4;
            case "break-cost" -> 3;
            case "near-stop-loss" -> 2;
            case "gain" -> 1;
            default -> 0;
        };
    }

    /** 同股票同轮多类型命中 → 合并为一条：类型取最严重，内容按严重度降序拼接（避免微信双份刷屏）。 */
    private List<PushChannel.PushMessage> mergeBySymbol(List<PushChannel.PushMessage> alerts) {
        Map<String, PushChannel.PushMessage> bySymbol = new java.util.LinkedHashMap<>();
        Map<String, java.util.TreeMap<Integer, String>> contentBySymbol = new java.util.LinkedHashMap<>();
        for (PushChannel.PushMessage m : alerts) {
            String key = m.symbol() != null && !m.symbol().isBlank() ? m.symbol() : m.name();
            if (key == null) key = m.title();
            PushChannel.PushMessage existing = bySymbol.get(key);
            if (existing == null || severity(m.type()) > severity(existing.type())) {
                bySymbol.put(key, m);
            }
            contentBySymbol.computeIfAbsent(key, k -> new java.util.TreeMap<>(
                    java.util.Collections.reverseOrder())).put(severity(m.type()), m.content());
        }
        List<PushChannel.PushMessage> merged = new ArrayList<>();
        for (Map.Entry<String, PushChannel.PushMessage> e : bySymbol.entrySet()) {
            PushChannel.PushMessage main = e.getValue();
            String combined = String.join("\n", contentBySymbol.get(e.getKey()).values());
            merged.add(new PushChannel.PushMessage(
                    main.title(), combined, main.type(), main.symbol(), main.name(), main.time()));
        }
        return merged;
    }

    // ── 内部方法 ──

    private void addIfNew(String userId, Position p, MarketData md, BigDecimal change, String type,
                          Set<String> existing, Set<String> newSignatures, List<PushChannel.PushMessage> alerts) {
        // RFC 20260817：推送开关——用户关闭的类型不生成（不落盘、不推送）
        if (!pushSettingsRepository.findByUser(userId).isEnabled(type)) return;
        String sig = signature(p.symbol(), LocalDate.now(), type);
        if (existing.contains(sig)) return;
        newSignatures.add(sig);
        alerts.add(new PushChannel.PushMessage(
                p.name() + " 行情提醒", message(p, md, change, type), type,
                p.symbol(), p.name(), LocalTime.now()));
    }

    private String signature(String symbol, LocalDate date, String type) {
        return symbol + ":" + date + ":" + type;
    }

    private String message(Position p, MarketData md, BigDecimal change, String type) {
        return switch (type) {
            case "stop-loss" -> "📉 " + p.name() + "(" + p.symbol() + ") 现价 " + fmt(md.price())
                    + " 已跌破你的止损位 " + fmt(p.stopLossPrice())
                    + "——按纪律（R66）该清仓了，要我给出建议吗？";
            case "near-stop-loss" -> "⚠️ " + p.name() + "(" + p.symbol() + ") 现价 " + fmt(md.price())
                    + " 距止损位 " + fmt(p.stopLossPrice()) + " 不到 "
                    + nearStopLossPct.stripTrailingZeros().toPlainString()
                    + "%了——提前想好怎么走，别等插针（R66）";
            case "loss" -> "📉 " + p.name() + "(" + p.symbol() + ") 今日跌 " + fmt(change) + "%，现价 "
                    + fmt(md.price())
                    + (p.stopLossPrice() != null
                        ? "——单日大跌，盯紧止损位 " + fmt(p.stopLossPrice()) + "（R66）"
                        : "——单日大跌，留意风险（你还没设止损位，想好怎么走）");
            case "gain" -> "📈 " + p.name() + "(" + p.symbol() + ") 今日涨 " + fmt(change) + "%，现价 "
                    + fmt(md.price()) + "，关注放飞条件";
            default -> "⚠️ " + p.name() + "(" + p.symbol() + ") 现价 " + fmt(md.price())
                    + " 已跌破成本线 " + fmt(p.avgCost()) + "，注意持仓风险";
        };
    }

    private String fmt(BigDecimal v) {
        return v != null ? v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() : "-";
    }
}
