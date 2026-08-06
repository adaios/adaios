package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.MarketPushEvent;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.infrastructure.storage.MarketPushRepository;
import com.adaiadai.core.infrastructure.storage.MarketSnapshotRepository;
import com.adaiadai.core.kernel.IdGenerator;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.market.MarketData;
import com.adaiadai.core.kernel.market.MarketDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
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
 * 检测三类异动并写入当日推送事件（{@code data/{userId}/trading/pushes/{date}.json}）：
 * <ul>
 *   <li><b>loss</b>：单日跌幅 ≥ {@code loss-threshold}（默认 3%）→ 止损预警</li>
 *   <li><b>gain</b>：单日涨幅 ≥ {@code gain-threshold}（默认 5%）→ 放飞提示</li>
 *   <li><b>break-cost</b>：现价跌破成本线（默认开启）→ 风控提醒</li>
 * </ul>
 * 同一持仓同类异动当日只推一次（{@link MarketSnapshotRepository} 签名去重），
 * 避免每轮询刷屏。FeedAppService 按日读取推送事件注入 {@code type=push} 条目。
 * 推送载体 = Feed 内展示（无系统通知渠道），"你不问，App 也告诉你今天需要知道的"。
 */
@Service
public class MarketAlertService {

    private static final Logger log = LoggerFactory.getLogger(MarketAlertService.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final MarketDataSource marketDataSource;
    private final PositionRepository positionRepository;
    private final AccountRepository accountRepository;
    private final MarketSnapshotRepository snapshotRepository;
    private final MarketPushRepository pushRepository;

    private final BigDecimal lossThreshold;
    private final BigDecimal gainThreshold;
    private final boolean breakCostEnabled;

    public MarketAlertService(MarketDataSource marketDataSource,
                              PositionRepository positionRepository,
                              AccountRepository accountRepository,
                              MarketSnapshotRepository snapshotRepository,
                              MarketPushRepository pushRepository,
                              @Value("${adai.market.alert.loss-threshold:3.0}") double lossThreshold,
                              @Value("${adai.market.alert.gain-threshold:5.0}") double gainThreshold,
                              @Value("${adai.market.alert.break-cost-enabled:true}") boolean breakCostEnabled) {
        this.marketDataSource = marketDataSource;
        this.positionRepository = positionRepository;
        this.accountRepository = accountRepository;
        this.snapshotRepository = snapshotRepository;
        this.pushRepository = pushRepository;
        this.lossThreshold = BigDecimal.valueOf(lossThreshold);
        this.gainThreshold = BigDecimal.valueOf(gainThreshold);
        this.breakCostEnabled = breakCostEnabled;
    }

    /**
     * 定时轮询：遍历 {@code default} + 全部启用账号逐用户检测。
     * 当前单用户数据在 {@code data/default/}（多账号架构预留后仍是 single-user 默认层），
     * 必须显式包含 default，否则轮询漏掉真实用户数据（accounts.json 的 adai/alice 是功能层账号）。
     * 交易时段 cron 可通过 {@code adai.market.alert.poll-cron} 配置（默认工作日 9-11/13-15 点每 30 分钟）。
     */
    @Scheduled(cron = "${adai.market.alert.poll-cron:0 */30 9-11,13-15 * * MON-FRI}")
    public void poll() {
        Set<String> userIds = new LinkedHashSet<>(List.of("default"));
        accountRepository.findAll().stream()
                .filter(Account::enabled)
                .map(Account::userId)
                .forEach(userIds::add);
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
        List<MarketPushEvent> alerts = new ArrayList<>();

        Map<String, MarketData> quotes = marketDataSource.quote(positions.stream().map(Position::symbol).toList());
        if (quotes.isEmpty()) return; // 网络/接口失败：保留快照，不误推

        for (Position p : positions) {
            MarketData md = quotes.get(p.symbol());
            if (md == null || md.changePercent() == null) continue;

            BigDecimal change = md.changePercent();

            // 止损预警：单日跌幅 ≥ 阈值
            if (change.compareTo(lossThreshold.negate()) <= 0) {
                addIfNew(p, md, change, "loss", existing, newSignatures, alerts);
            }
            // 放飞提示：单日涨幅 ≥ 阈值
            if (change.compareTo(gainThreshold) >= 0) {
                addIfNew(p, md, change, "gain", existing, newSignatures, alerts);
            }
            // 跌破成本线风控提醒
            if (breakCostEnabled && md.price() != null && p.avgCost() != null
                    && md.price().compareTo(p.avgCost()) < 0) {
                addIfNew(p, md, change, "break-cost", existing, newSignatures, alerts);
            }
        }

        if (!alerts.isEmpty()) {
            for (MarketPushEvent e : alerts) {
                pushRepository.append(userId, today, e);
            }
            snapshotRepository.saveSignatures(userId, today, newSignatures);
            log.info("行情异动推送 | userId={} | {} 条 | {}", userId, alerts.size(), alerts.stream().map(MarketPushEvent::type).toList());
        }
    }

    // ── 内部方法 ──

    private void addIfNew(Position p, MarketData md, BigDecimal change, String type,
                          Set<String> existing, Set<String> newSignatures, List<MarketPushEvent> alerts) {
        String sig = signature(p.symbol(), LocalDate.now(), type);
        if (existing.contains(sig)) return;
        newSignatures.add(sig);
        alerts.add(new MarketPushEvent(
                IdGenerator.monotonic("push_"),
                p.symbol(), p.name(), message(p, md, change, type), type,
                LocalTime.now().format(TIME_FMT)));
    }

    private String signature(String symbol, LocalDate date, String type) {
        return symbol + ":" + date + ":" + type;
    }

    private String message(Position p, MarketData md, BigDecimal change, String type) {
        return switch (type) {
            case "loss" -> "📉 " + p.name() + "(" + p.symbol() + ") 今日跌 " + fmt(change) + "%，现价 "
                    + fmt(md.price()) + "，触发止损预警，注意风控";
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
