package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.cases.CaseFeatureExtractor;
import com.adaiadai.core.domain.trading.cases.CaseRecord;
import com.adaiadai.core.domain.trading.cases.TradingCaseRepository;
import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * CaseVerifyBackfillScheduler — 案例后验回填（2026-08-31 方案第 3 层：让数据「好坏」说话）。
 * <p>
 * 问题：verify（+5d/+10d/最大回撤/止损）仅在标注当天算一次——标注日近窗口起点时
 * +5d/+10d 为 null，之后永远 null（中国稀土 8-26 标注即此情况，index 存 0.0 占位，P2-案例5）。
 * <p>
 * 方案：每日收盘后（15:35，与 buy-points 15:10 错峰）遍历案例库，对 verify 缺失的案例
 * 重拉 K 线（buyDate 后 45 日历日窗口）→ 重算 → 非 null 回填落盘；顺带重建清单
 * （修复「文件类型已补标/verify 已回填但 _index.json 摘要过期」的不一致）。
 * 降级：单案例 K 线失败 → 跳过（次日再试，不中断整批）；账号级异常隔离。
 */
@Component
public class CaseVerifyBackfillScheduler {

    private static final Logger log = LoggerFactory.getLogger(CaseVerifyBackfillScheduler.class);

    /** 后验窗口（与标注同口径）：buyDate 后 45 日历日（≈ 30 交易日）。 */
    private static final int AFTER_CAL_DAYS = 45;

    private final TradingCaseRepository caseRepository;
    private final KlineService klineService;
    private final AccountRepository accountRepository;
    private final PluginService pluginService;

    public CaseVerifyBackfillScheduler(TradingCaseRepository caseRepository,
                                       KlineService klineService,
                                       AccountRepository accountRepository,
                                       PluginService pluginService) {
        this.caseRepository = caseRepository;
        this.klineService = klineService;
        this.accountRepository = accountRepository;
        this.pluginService = pluginService;
    }

    /** 每日 15:35（收盘后，错峰 buy-points 15:10）回填。 */
    @Scheduled(cron = "${adai.trading.case.backfill-cron:0 35 15 * * MON-FRI}")
    public void backfill() {
        for (Account account : accountRepository.findAll()) {
            if (!account.enabled() || account.userId() == null) continue;
            if (!pluginService.hasPlugin(account.userId(), PluginRegistry.PLUGIN_TRADING)) continue;
            try {
                backfillForUser(account.userId());
            } catch (Exception e) {
                log.warn("案例后验回填失败 | userId={} | {}", account.userId(), e.getMessage());
            }
        }
    }

    /** 单用户回填：verify +5d/+10d 缺失 → 重算回填；顺带重建清单。 */
    void backfillForUser(String userId) {
        List<CaseRecord> cases = caseRepository.list(userId);
        int checked = 0, filled = 0;
        for (CaseRecord c : cases) {
            if (c.buyDate() == null || c.features() == null) continue;
            CaseRecord.CaseVerify v = c.verify();
            boolean need5 = v == null || v.plus5dReturnPct() == null;
            boolean need10 = v == null || v.plus10dReturnPct() == null;
            if (!need5 && !need10) continue; // 后验已完整，跳过
            checked++;
            try {
                List<Candle> candles = klineService.klineRange(c.symbol(),
                        c.buyDate().minusDays(5), c.buyDate().plusDays(AFTER_CAL_DAYS));
                CaseRecord.CaseVerify fresh = CaseFeatureExtractor.verify(candles, c.buyDate());
                if (fresh == null) continue; // 窗口仍不足 → 下次再试
                boolean changed = !sameVerify(v, fresh);
                if (changed) {
                    CaseRecord updated = new CaseRecord(
                            c.id(), c.symbol(), c.name(), c.buyDate(), c.buyType(),
                            c.description(), c.labels(), c.labeledAt(), c.window(),
                            c.features(), fresh, c.aiInsight());
                    caseRepository.save(userId, updated);
                    filled++;
                    log.info("案例后验回填 | userId={} | caseId={} | +5d={} | +10d={}",
                            userId, c.id(),
                            fresh.plus5dReturnPct() == null ? "-" : fresh.plus5dReturnPct(),
                            fresh.plus10dReturnPct() == null ? "-" : fresh.plus10dReturnPct());
                }
            } catch (Exception e) {
                log.warn("案例后验回填单条失败（跳过，次日重试）| userId={} | caseId={} | {}",
                        userId, c.id(), e.getMessage());
            }
        }
        // 顺带修复清单与文件不一致（类型补标/verify 回填后摘要过期）
        caseRepository.rebuildIndex(userId);
        log.info("案例后验回填完成 | userId={} | 待回填 {} | 实际回填 {} | 案例库 {}",
                userId, checked, filled, cases.size());
    }

    /** 两 verify 是否一致（null 安全；stopLossHit 不参与——回填口径以收益字段为准）。 */
    private boolean sameVerify(CaseRecord.CaseVerify a, CaseRecord.CaseVerify b) {
        if (a == null || b == null) return false;
        return eq(a.plus5dReturnPct(), b.plus5dReturnPct())
                && eq(a.plus10dReturnPct(), b.plus10dReturnPct())
                && eq(a.maxDrawdownAfterBuyPct(), b.maxDrawdownAfterBuyPct())
                && a.stopLossHit() == b.stopLossHit();
    }

    private boolean eq(Double x, Double y) {
        if (x == null || y == null) return x == y;
        return Math.abs(x - y) < 0.01;
    }
}
