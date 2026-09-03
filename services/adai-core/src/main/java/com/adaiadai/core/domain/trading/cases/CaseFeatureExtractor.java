package com.adaiadai.core.domain.trading.cases;

import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.domain.trading.market.KdjIndicator;
import com.adaiadai.core.domain.trading.market.MacdIndicator;

import java.time.LocalDate;
import java.util.List;

/**
 * CaseFeatureExtractor — 案例特征提取（2026-08-30：完美买点案例库环 2）。
 * <p>
 * 全部特征从 OHLCV 重算（无外部依赖），口径对齐设计文档 §四：
 * <ul>
 *   <li>回撤/前高窗口同 B2（前 20 日最高收盘）</li>
 *   <li>KDJ 复用 {@link KdjIndicator}（9,3,3，J&lt;13 低位锚点）</li>
 *   <li>MACD 新增 {@link MacdIndicator}（EMA12/26 + DEA9 + 柱）</li>
 *   <li>黄白线近似：黄线 ≈ 长均线（默认 60 日，第一版常量），白线 ≈ MA10；
 *       语义依据 os/trading-engine/01-raw/2025-10-04_国庆课程.md（黄线=主力成本线，接近 60 日线）</li>
 * </ul>
 */
public final class CaseFeatureExtractor {

    /** 前窗口交易日数（案例数据模型）。 */
    public static final int BEFORE_DAYS = 60;
    /** 后窗口交易日数（后验）。 */
    public static final int AFTER_DAYS = 30;
    /** 前高窗口（B2 口径）。 */
    private static final int PRIOR_HIGH_DAYS = 20;
    /** 黄线近似均线周期（可配后置）。 */
    private static final int YELLOW_MA_PERIOD = 60;
    /** 白线近似均线周期。 */
    private static final int WHITE_MA_PERIOD = 10;
    /** 盘整判定振幅阈值（(high-low)/close &lt; 3%）。 */
    private static final double SIDEWAYS_AMP = 0.03;
    /** 黄线「贴近」阈值（%）。 */
    private static final double YELLOW_NEAR_PCT = 2.0;
    /** 黄线「触及」阈值（%）。 */
    private static final double YELLOW_TOUCH_PCT = 0.5;

    private CaseFeatureExtractor() {}

    /**
     * 提取买点日特征画像（默认黄白线周期：黄线=60 日、白线=10 日）。
     *
     * @param candles 包含 buyDate 的日 K 窗口（旧→新，含前 60/后 30 的可用部分）
     * @param buyDate 买点日期（必须落在 candles 内，否则返回 null）
     * <p>
     * 已知限制（P2-案例1，REVIEW 2026-08-30）：停牌/新股/标注日近窗口起点时前 60 根不足，
     * MA20/MA60 与黄白线态用可用根数近似（{@link #ma}）——特征可算但不精确；形态/位置类
     * 特征（maRelation/yellowLineState/whiteAboveYellow/distToMa60Pct）随窗口缩短失真增大。
     * 属已知取舍（设计文档 trading-case-library-design.md §4.1），标注照常成功、不阻塞；
     * windowComplete 显式标记列为后续项。
     */
    public static CaseRecord.CaseFeatures extract(List<Candle> candles, LocalDate buyDate) {
        return extract(candles, buyDate, YELLOW_MA_PERIOD, WHITE_MA_PERIOD);
    }

    /**
     * 提取买点日特征画像（黄白线周期参数化——批 5 前置：用户自定义指标语义近似，
     * 黄线/白线均线周期可配 {@code adai.trading.case.yellow-ma} / {@code .white-ma}）。
     */
    public static CaseRecord.CaseFeatures extract(List<Candle> candles, LocalDate buyDate,
                                                  int yellowMaPeriod, int whiteMaPeriod) {
        if (candles == null || candles.isEmpty() || buyDate == null) return null;
        int idx = indexOf(candles, buyDate);
        if (idx < 0) return null;

        List<Candle> upToBuy = candles.subList(0, idx + 1);
        double close = candles.get(idx).close();

        // 前 20 根最高收盘（不含当日；不足则用可用根）
        int from = Math.max(0, idx - PRIOR_HIGH_DAYS);
        double peak = Double.MIN_VALUE;
        for (int i = from; i < idx; i++) peak = Math.max(peak, candles.get(i).close());
        double drawdown = peak == Double.MIN_VALUE ? 0.0
                : (peak - close) / peak * 100.0;

        // 量比：3 日均量 / 5 日均量
        double vol3 = avgVolume(candles, idx - 2, idx);
        double vol5 = avgVolume(candles, idx - 4, idx);
        double volRatio = vol5 <= 0 ? 1.0 : vol3 / vol5;

        // KDJ：截至买点日的序列（前一日 vs 当日判金叉）
        KdjIndicator.Kdj kdjPrev = upToBuy.size() > 1
                ? KdjIndicator.latest(candles.subList(0, idx)) : null;
        KdjIndicator.Kdj kdjCur = KdjIndicator.latest(upToBuy);
        Double kdjJ = kdjCur == null ? null : kdjCur.j();
        boolean kdjGoldenCross = kdjPrev != null && kdjCur != null
                && kdjPrev.k() <= kdjPrev.d() && kdjCur.k() > kdjCur.d();

        // MACD：序列最后两根判金叉
        List<MacdIndicator.Macd> macdSeries = MacdIndicator.series(upToBuy);
        Double macdHist = macdSeries.isEmpty() ? null : macdSeries.get(macdSeries.size() - 1).hist();
        boolean macdCrossUp = MacdIndicator.crossUp(upToBuy);

        // MA10/MA20/MA60（不足用可用根数近似；黄/白线周期参数化）
        double ma10 = ma(candles, idx, whiteMaPeriod);
        double ma20 = ma(candles, idx, 20);
        double ma60 = ma(candles, idx, yellowMaPeriod);

        String maRelation;
        if (close > ma20 && close > ma60) maRelation = "above_all";
        else if (close > ma20) maRelation = "close_above_ma20_below_ma60";
        else if (close > ma60) maRelation = "close_below_ma20_above_ma60";
        else maRelation = "below_all";

        double distToMa60 = ma60 <= 0 ? 0.0 : Math.abs(close - ma60) / ma60 * 100.0;
        String yellowState;
        if (distToMa60 < YELLOW_TOUCH_PCT) yellowState = "touch";
        else if (distToMa60 < YELLOW_NEAR_PCT) yellowState = "near";
        else yellowState = close > ma60 ? "above" : "below";
        boolean whiteAboveYellow = ma10 > ma60;

        // 盘整天数：近 10 根（含当日）振幅 < 3%
        int sideways = 0;
        for (int i = Math.max(0, idx - 9); i <= idx; i++) {
            Candle c = candles.get(i);
            if (c.close() > 0 && (c.high() - c.low()) / c.close() < SIDEWAYS_AMP) sideways++;
        }

        // 破前高：收盘 > 前 20 根最高收盘
        boolean breakout = peak != Double.MIN_VALUE && close > peak;

        return new CaseRecord.CaseFeatures(drawdown, round(volRatio), kdjJ, kdjGoldenCross,
                macdHist == null ? null : round(macdHist), macdCrossUp, maRelation,
                round(distToMa60), yellowState, whiteAboveYellow, sideways, breakout);
    }

    /**
     * 后验窗口：+5/+10 日收益、买入后最大回撤、是否破默认止损（−7%）。
     * 缺数据 → 对应字段 null（标注照常成功）。
     */
    public static CaseRecord.CaseVerify verify(List<Candle> candles, LocalDate buyDate) {
        if (candles == null || candles.isEmpty() || buyDate == null) return null;
        int idx = indexOf(candles, buyDate);
        if (idx < 0) return null;
        double close = candles.get(idx).close();
        Double plus5 = returnAt(candles, idx, 5, close);
        Double plus10 = returnAt(candles, idx, 10, close);
        Double maxDrawdown = null;
        double lowest = close;
        for (int i = idx + 1; i < candles.size(); i++) lowest = Math.min(lowest, candles.get(i).low());
        if (candles.size() - 1 > idx) maxDrawdown = (lowest / close - 1.0) * 100.0;
        boolean stopLossHit = lowest <= close * 0.93;
        return new CaseRecord.CaseVerify(plus5 == null ? null : round(plus5 * 100.0),
                plus10 == null ? null : round(plus10 * 100.0),
                maxDrawdown == null ? null : round(maxDrawdown), stopLossHit);
    }

    private static int indexOf(List<Candle> candles, LocalDate date) {
        for (int i = 0; i < candles.size(); i++) {
            if (candles.get(i).date().equals(date)) return i;
        }
        return -1;
    }

    private static double avgVolume(List<Candle> candles, int from, int to) {
        int f = Math.max(0, from);
        int t = Math.min(candles.size() - 1, to);
        if (t < f) return 0;
        double sum = 0;
        for (int i = f; i <= t; i++) sum += candles.get(i).volume();
        return sum / (t - f + 1);
    }

    /** 均线：截至 idx 的最近 n 根收盘均值（不足 n 根用可用根数）。 */
    private static double ma(List<Candle> candles, int idx, int n) {
        int from = Math.max(0, idx - n + 1);
        double sum = 0;
        int count = 0;
        for (int i = from; i <= idx; i++) {
            sum += candles.get(i).close();
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }

    private static Double returnAt(List<Candle> candles, int idx, int offset, double base) {
        int target = idx + offset;
        if (target >= candles.size() || base <= 0) return null;
        return (candles.get(target).close() / base - 1.0);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
