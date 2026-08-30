package com.adaiadai.core.domain.trading.cases;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * CaseRecord — 买点案例（2026-08-30 第四阶段：案例沉淀 → 判定当下）。
 * <p>
 * 真相源文件 {@code data/{userId}/trading/cases/{buyDate}_{symbol}.json}（File First）。
 * 设计：特征全为标准化相对值（跨标的可比）；verify 为后验窗口（完美与否的客观证据）；
 * aiInsight 为环 3 LLM 理解产物（P2 填充）。K 线本体不落盘——按 symbol+buyDate 可重放。
 * <p>
 * 2026-08-31 双轨方案：buyType 支持 B1/B2 正样本（完美买点）与 {@link #TYPE_FAILED} 负样本
 * （失败案例——形态看似买点但走坏）。负样本不参与正样本画像/匹配，单独成「失败画像」供风险警示。
 */
public record CaseRecord(
        String id,
        String symbol,
        String name,
        LocalDate buyDate,
        String buyType,
        String description,
        List<String> labels,
        LocalDateTime labeledAt,
        CaseWindow window,
        CaseFeatures features,
        CaseVerify verify,
        CaseAiInsight aiInsight) {

    /** 负样本类型（失败案例）——2026-08-31 双轨方案：不参与正样本画像/匹配。 */
    public static final String TYPE_FAILED = "FAILED";

    /** 案例 id：{buyDate}_{symbol}（#211 风格：日期+标的，天然幂等键）。 */
    public static String idOf(String symbol, LocalDate buyDate) {
        return buyDate + "_" + symbol;
    }

    /** 数据窗口（交易日语义由提取器按 K 线序列截取；日历日仅用于拉取范围）。 */
    public record CaseWindow(int beforeDays, int afterDays) {}

    /** 买点日特征画像（全部标准化相对值）。 */
    public record CaseFeatures(
            double drawdownFromHighPct,
            double volumeShrinkRatio,
            Double kdjJ,
            boolean kdjGoldenCross,
            Double macdHist,
            boolean macdCrossUp,
            String maRelation,
            double distToMa60Pct,
            String yellowLineState,
            boolean whiteAboveYellow,
            int sidewaysDays,
            boolean breakoutFromHigh) {}

    /** 后验窗口（缺数据 → null，标注照常成功，页面显示「数据不足」）。 */
    public record CaseVerify(
            @JsonProperty("+5dReturnPct") Double plus5dReturnPct,
            @JsonProperty("+10dReturnPct") Double plus10dReturnPct,
            @JsonProperty("maxDrawdownAfterBuyPct") Double maxDrawdownAfterBuyPct,
            @JsonProperty("stopLossHit") boolean stopLossHit) {}

    /** 环 3 LLM 理解产物（P2 填充；空 = 未理解）。 */
    public record CaseAiInsight(String summary, List<String> keyFeatures, double confidence, boolean reviewed) {
        public static CaseAiInsight empty() {
            return new CaseAiInsight("", List.of(), 0.0, false);
        }
    }
}
