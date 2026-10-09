package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.market.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * SoldAfterCloseService — 清仓「卖掉之后到现在」（2026-10-08，交易插件 UI/UX 重做批）。
 *
 * <p><b>回答什么问题</b>：『我卖飞了没』——卖掉之后这票又走了多少。涨 = <b>走早了</b>（↑），
 * 跌 = <b>走对了</b>（↓）。这是清仓独有的一列（设计稿 uiux-discovery §十一「清仓持续整理」）。
 *
 * <p><b>口径（本类唯一权威，勿在别处重算）</b>：
 * <ul>
 *   <li>基准价 = <b>卖出日（或其后第一根）K 线收盘</b>——不用流水里的成交价：清仓表是
 *       import/flow 双轨数据，import 行（生产 172 行里绝大多数）根本没有对应流水，
 *       只有 K 线是**每一行都拿得到**的统一基准。卖出当天的实际成交价与收盘价的
 *       细小偏差在此口径下如实存在——UI 上这一列讲的是「卖掉那天收盘起，到现在」。</li>
 *   <li>最新价 = 区间最后一根 K 线收盘（停牌票 = 停牌前最后一根，如实）。</li>
 *   <li>{@code pct} = (最新收盘 − 基准收盘) / 基准收盘 × 100，保留 2 位。</li>
 *   <li>{@code direction}：按 <b>四舍五入到 1 位</b>后的 pct 判——<b>up</b>（走早了，涨）/
 *       <b>down</b>（走对了，跌）/ <b>flat</b>（没动）。1 位判据与前端显示（1 位小数）自洽：
 *       不会出现「显示 0.0% 却标 ↑」。</li>
 * </ul>
 *
 * <p><b>诚实口径（缺数据不编——与全项目同一条线）</b>：
 * <ul>
 *   <li>清仓日期缺失 / K 线取不到 / 行情覆盖不到卖出日（第一根距卖出日 &gt;
 *       {@value #BASE_TOLERANCE_DAYS} 天——多为一年以上的老清仓，K 线源只回溯约一年）
 *       → {@code pct=null} + 一句人话 {@code note}，前端显示「—」，<b>绝不用邻近价格冒充基准</b>。</li>
 *   <li>单笔超时/异常不炸整批：该笔如实 null，其余照常。</li>
 * </ul>
 *
 * <p><b>并发</b>：拉 K 线是网络 IO（生产清仓表 172 行），逐笔串行会拖到分钟级；16 并发线程池
 * 与 {@link SoldScoreService} 同法（K 线源有按日缓存，两处同时拉同标的不会重复打网络）。
 */
@Service
public class SoldAfterCloseService {

    private static final Logger log = LoggerFactory.getLogger(SoldAfterCloseService.class);

    /**
     * 卖出日与基准 K 线的最大容差（自然日）。
     * <p>正常情况基准就是卖出日当天那一根（容差 0 天即命中）；留 10 天只为覆盖周末/短停牌等
     * 「卖出日当天恰好没有那根」的边缘。超过说明 K 线源覆盖不到卖出日（数据源约回溯一年），
     * 此时拿到的第一根价格与卖出日毫无关系——必须判不可用，而不是拿它当基准把涨跌幅算成另一段。
     */
    static final int BASE_TOLERANCE_DAYS = 10;

    private final KlineService klineService;
    private final ExecutorService klinePool = Executors.newFixedThreadPool(16);

    public SoldAfterCloseService(KlineService klineService) {
        this.klineService = klineService;
    }

    /** 应用关闭时优雅关闭线程池（同 SoldScoreService 的 B53 检查点）。 */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        klinePool.shutdown();
        try {
            if (!klinePool.awaitTermination(5, TimeUnit.SECONDS)) {
                klinePool.shutdownNow();
            }
        } catch (InterruptedException e) {
            klinePool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("SoldAfterCloseService K 线线程池已关闭");
    }

    /**
     * 单笔清仓的「卖掉之后到现在」。
     *
     * @param pct    涨跌幅 %（保留 2 位）；null = 没算出来（note 说明原因，前端显示「—」）
     * @param direction up=走早了（涨）/ down=走对了（跌）/ flat=没动；null = 没算出来
     */
    public record AfterClose(String symbol, String name, String sellDate,
                             String baseDate, Double baseClose,
                             String latestDate, Double latestClose,
                             Double pct, String direction, String note) {}

    /** 批量计算（按清仓列表顺序返回——前端按索引匹配，同 /sold/score 模式）。 */
    public List<AfterClose> compute(List<SoldTrade> trades) {
        List<AfterClose> result = new ArrayList<>();
        if (trades == null || trades.isEmpty()) return result;
        LocalDate today = LocalDate.now();
        List<Future<AfterClose>> futures = new ArrayList<>();
        for (SoldTrade t : trades) {
            futures.add(klinePool.submit(() -> one(t, today)));
        }
        for (int i = 0; i < futures.size(); i++) {
            SoldTrade t = trades.get(i);
            try {
                result.add(futures.get(i).get(30, TimeUnit.SECONDS));
            } catch (Exception e) {
                log.warn("卖后涨跌单笔超时/失败 | symbol={} | {}", t.symbol(), e.getMessage());
                result.add(unavailable(t, "行情取不到"));
            }
        }
        return result;
    }

    private AfterClose one(SoldTrade t, LocalDate today) {
        if (t.sellDate() == null) return unavailable(t, "没记清仓日期");
        List<Candle> candles = klineService.klineRange(t.symbol(), t.sellDate(), today);
        if (candles == null || candles.isEmpty()) return unavailable(t, "行情取不到");
        Candle base = candles.get(0);
        // 基准必须是「卖掉那天或之后第一根」——差太多 = 行情覆盖不到卖出日（见 BASE_TOLERANCE_DAYS）
        if (ChronoUnit.DAYS.between(t.sellDate(), base.date()) > BASE_TOLERANCE_DAYS) {
            return unavailable(t, "行情覆盖不到卖掉那天");
        }
        Candle latest = candles.get(candles.size() - 1);
        double pct = (latest.close() - base.close()) / base.close() * 100.0;
        // direction 按四舍五入到 1 位后的值判——与前端显示（1 位小数）自洽
        double pct1 = Math.round(pct * 10.0) / 10.0;
        String direction = pct1 > 0 ? "up" : pct1 < 0 ? "down" : "flat";
        return new AfterClose(t.symbol(), t.name(), t.sellDate().toString(),
                base.date().toString(), round2(base.close()),
                latest.date().toString(), round2(latest.close()),
                Math.round(pct * 100.0) / 100.0, direction, null);
    }

    private AfterClose unavailable(SoldTrade t, String note) {
        return new AfterClose(t.symbol(), t.name(),
                t.sellDate() == null ? null : t.sellDate().toString(),
                null, null, null, null, null, null, note);
    }

    private static Double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
