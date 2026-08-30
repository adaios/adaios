package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.TradingException;
import com.adaiadai.core.domain.trading.cases.CaseFeatureExtractor;
import com.adaiadai.core.domain.trading.cases.CaseRecord;
import com.adaiadai.core.domain.trading.cases.CaseSimilarityEngine;
import com.adaiadai.core.domain.trading.cases.TradingCaseRepository;
import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.infrastructure.ai.interaction.AiTraceContext;
import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * TradingCaseAppService — 完美买点案例用例编排（2026-08-30 第四阶段，环 1-3）。
 * <p>
 * 环 1-2：标注（一句话 → 拉「前 60 + 后 30 交易日」日 K → 特征画像 + 后验 → 落盘 JSON）。
 * 环 3：LLM 案例理解（generateInsight）——读特征 + K 线统计 → 结构化「为什么这是完美买点」
 * （aiInsight 落盘，reviewed=false 待人工确认）。
 * <p>
 * 降级：拉 K 失败 → 业务异常（不落半成品，fail-visible）；buyDate 无交易数据 → 业务异常；
 * 后验窗口不足 → verify 字段 null（标注照常成功）；LLM 理解失败 → 业务异常（不落半成品）。
 */
@Service
public class TradingCaseAppService {

    private static final Logger log = LoggerFactory.getLogger(TradingCaseAppService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 前 60 交易日 ≈ 前 100 日历日（宽算，覆盖停牌缺口）。 */
    private static final int BEFORE_CAL_DAYS = 100;
    /** 后 30 交易日 ≈ 后 45 日历日。 */
    private static final int AFTER_CAL_DAYS = 45;
    /** 案例数据窗口（交易日语义，落盘标注）。 */
    private static final int BEFORE_TRADE_DAYS = 60;
    private static final int AFTER_TRADE_DAYS = 30;

    private static final String INSIGHT_SYSTEM_PROMPT = """
            你是交易纪律复盘助手。用户标注了一个「完美买点」案例，请基于特征画像、K 线统计与后验结果，
            输出该案例为什么是完美买点的结构化理解。

            只输出 JSON，不要任何其他文本或代码块标记：
            {"summary":"一句话（≤60 字）：形态+量能+指标+位置的核心逻辑","keyFeatures":["2-5 个短词，如 缩量回踩、黄线支撑、KDJ低位金叉"],"confidence":0到1的小数}

            判定依据优先级：形态（回撤/盘整/破前高）> 量能（量比）> 指标（KDJ/MACD/黄白线）> 位置（距 60 日线）。
            """;

    private final KlineService klineService;
    private final TradingCaseRepository caseRepository;
    private final TradingAppService tradingAppService;
    private final AiClient aiClient;

    public TradingCaseAppService(KlineService klineService,
                                 TradingCaseRepository caseRepository,
                                 TradingAppService tradingAppService,
                                 AiClient aiClient) {
        this.klineService = klineService;
        this.caseRepository = caseRepository;
        this.tradingAppService = tradingAppService;
        this.aiClient = aiClient;
    }

    /** 标注一个完美买点案例：拉窗口 → 特征 + 后验 → 落盘。 */
    public CaseRecord annotate(String userId, String symbol, LocalDate buyDate,
                               String buyType, String description, List<String> labels, String name) {
        if (buyDate.isAfter(LocalDate.now())) {
            throw new TradingException("买点日期不能是未来日期：" + buyDate);
        }
        String caseId = CaseRecord.idOf(symbol, buyDate);
        if (caseRepository.exists(userId, caseId)) {
            throw new TradingException("该案例已标注过（" + caseId + "），可查看或删除后重标");
        }
        List<Candle> candles = klineService.klineRange(symbol, buyDate.minusDays(BEFORE_CAL_DAYS), buyDate.plusDays(AFTER_CAL_DAYS));
        if (candles.isEmpty()) {
            throw new TradingException("无法获取 " + symbol + " 在 " + buyDate + " 前后的 K 线数据，请稍后重试或核对代码");
        }
        CaseRecord.CaseFeatures features = CaseFeatureExtractor.extract(candles, buyDate);
        if (features == null) {
            throw new TradingException("该日期无交易数据（可能停牌或非交易日）：" + buyDate);
        }
        CaseRecord.CaseVerify verify = CaseFeatureExtractor.verify(candles, buyDate);
        String resolvedName = name;
        if (resolvedName == null || resolvedName.isBlank()) {
            try {
                resolvedName = tradingAppService.lookupName(symbol);
            } catch (Exception e) {
                log.warn("案例名称查询失败（保持空）| symbol={} | {}", symbol, e.getMessage());
            }
        }
        CaseRecord record = new CaseRecord(
                caseId, symbol, resolvedName, buyDate,
                buyType == null || buyType.isBlank() ? "unknown" : buyType,
                description, labels == null ? List.of() : labels, LocalDateTime.now(),
                new CaseRecord.CaseWindow(BEFORE_TRADE_DAYS, AFTER_TRADE_DAYS),
                features, verify, CaseRecord.CaseAiInsight.empty());
        caseRepository.save(userId, record);
        log.info("完美买点案例已标注 | userId={} | caseId={} | buyType={}", userId, caseId, record.buyType());
        return record;
    }

    /** 案例列表（buyDate 倒序）。 */
    public List<CaseRecord> list(String userId) {
        return caseRepository.list(userId);
    }

    /** 案例详情；withKline=true 时附 90 根窗口日 K（前端画图重放，失败 → 空列表）。 */
    public CaseDetail detail(String userId, String caseId, boolean withKline) {
        CaseRecord record = caseRepository.findById(userId, caseId)
                .orElseThrow(() -> new TradingException("案例不存在：" + caseId));
        List<Candle> kline = List.of();
        if (withKline && record.buyDate() != null) {
            kline = klineService.klineRange(record.symbol(),
                    record.buyDate().minusDays(BEFORE_CAL_DAYS), record.buyDate().plusDays(AFTER_CAL_DAYS));
        }
        return new CaseDetail(record, kline);
    }

    /** 删除案例；不存在 → 业务异常（400 + 人话）。 */
    public void delete(String userId, String caseId) {
        if (!caseRepository.exists(userId, caseId)) {
            throw new TradingException("案例不存在：" + caseId);
        }
        caseRepository.delete(userId, caseId);
        log.info("完美买点案例已删除 | userId={} | caseId={}", userId, caseId);
    }

    /** 环 4：判定当下（POST /cases/match）——当前标的形态与案例库归一化相似度 Top N。 */
    public MatchResponse match(String userId, String symbol, LocalDate date) {
        List<CaseRecord> cases = caseRepository.list(userId);
        if (cases.isEmpty()) {
            return new MatchResponse(symbol, List.of());
        }
        LocalDate queryDate = date != null ? date : LocalDate.now();
        List<Candle> candles = klineService.klineRange(symbol,
                queryDate.minusDays(BEFORE_CAL_DAYS), queryDate.plusDays(AFTER_CAL_DAYS));
        if (candles.isEmpty()) {
            throw new TradingException("无法获取 " + symbol + " 的 K 线数据，请稍后重试");
        }
        LocalDate targetDate = queryDate;
        CaseRecord.CaseFeatures features = CaseFeatureExtractor.extract(candles, targetDate);
        if (features == null && !candles.isEmpty()) {
            // 指定日无数据（停牌/非交易日）→ 回落最近交易日
            targetDate = candles.get(candles.size() - 1).date();
            features = CaseFeatureExtractor.extract(candles, targetDate);
        }
        if (features == null) {
            throw new TradingException("无法计算 " + symbol + " 的形态特征");
        }
        List<CaseSimilarityEngine.MatchResult> top = CaseSimilarityEngine.topN(cases, features, 5);
        List<MatchItem> items = top.stream()
                .map(r -> new MatchItem(
                        r.caseRecord().id(), r.caseRecord().symbol(), r.caseRecord().name(),
                        r.caseRecord().buyDate(), r.caseRecord().buyType(), r.similarityPercent(),
                        r.caseRecord().verify() == null ? null : r.caseRecord().verify().plus5dReturnPct(),
                        insightSummary(r.caseRecord())))
                .toList();
        log.info("案例匹配完成 | userId={} | symbol={} | 基准日={} | 命中={} | 案例库={}",
                userId, symbol, targetDate, items.size(), cases.size());
        return new MatchResponse(symbol, items);
    }

    /** 匹配响应（核心价值输出：相似案例 + 相似度 + 后验参照）。 */
    public record MatchResponse(String symbol, List<MatchItem> matches) {}

    /** 匹配条目（轻量，不含全量特征——详情可再看）。 */
    public record MatchItem(String caseId, String symbol, String name, LocalDate buyDate,
                            String buyType, double similarityPercent, Double plus5dReturnPct,
                            String aiInsightSummary) {}

    private String insightSummary(CaseRecord c) {
        return c.aiInsight() == null || c.aiInsight().summary() == null
                ? null : c.aiInsight().summary();
    }

    /** 环 3：LLM 案例理解（POST /cases/{caseId}/insight）。
     * 读特征画像 + K 线统计 → 结构化「为什么这是完美买点」→ aiInsight 落盘。
     * LLM 失败 / 输出不可解析 → 业务异常（fail-visible，不落半成品）。
     */
    public CaseRecord generateInsight(String userId, String caseId) {
        CaseRecord record = caseRepository.findById(userId, caseId)
                .orElseThrow(() -> new TradingException("案例不存在：" + caseId));
        List<Candle> candles = klineService.klineRange(record.symbol(),
                record.buyDate().minusDays(BEFORE_CAL_DAYS), record.buyDate().plusDays(AFTER_CAL_DAYS));
        try {
            String prompt = buildInsightPrompt(record, summarizeKline(candles, record.buyDate()));
            ContextPackage ctx = ContextPackage.simple(
                    "trading", null, "完美买点案例理解", prompt,
                    List.of("trading", "案例"), prompt);
            AiTraceContext.set(userId, null, null, "trading_case_insight");
            String raw = aiClient.generate(ctx, INSIGHT_SYSTEM_PROMPT);
            CaseRecord.CaseAiInsight insight = parseInsight(raw);
            CaseRecord updated = new CaseRecord(
                    record.id(), record.symbol(), record.name(), record.buyDate(),
                    record.buyType(), record.description(), record.labels(), record.labeledAt(),
                    record.window(), record.features(), record.verify(), insight);
            caseRepository.save(userId, updated);
            log.info("案例 AI 理解已生成 | userId={} | caseId={} | confidence={}",
                    userId, caseId, insight.confidence());
            return updated;
        } catch (TradingException e) {
            throw e;
        } catch (Exception e) {
            log.warn("案例 AI 理解失败 | userId={} | caseId={} | {}", userId, caseId, e.getMessage());
            throw new TradingException("AI 理解生成失败，请稍后重试：" + e.getMessage());
        }
    }

    /** K 线统计摘要（token 友好，不喂全量 90 根）：前窗口高低点 + 买点位置 + 后验走势。 */
    private String summarizeKline(List<Candle> candles, LocalDate buyDate) {
        if (candles == null || candles.isEmpty()) return "（无 K 线数据）";
        int idx = -1;
        for (int i = 0; i < candles.size(); i++) {
            if (candles.get(i).date().equals(buyDate)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return "（买点日不在窗口内）";
        // 前窗口（buyDate 之前）：高低点 + 均价
        double preHigh = Double.MIN_VALUE, preLow = Double.MAX_VALUE, preSum = 0;
        int preCount = 0;
        for (int i = Math.max(0, idx - 60); i < idx; i++) {
            preHigh = Math.max(preHigh, candles.get(i).high());
            preLow = Math.min(preLow, candles.get(i).low());
            preSum += candles.get(i).close();
            preCount++;
        }
        // 后窗口（buyDate 之后）：最高/最低/最后收盘
        double postHigh = Double.MIN_VALUE, postLow = Double.MAX_VALUE;
        double postLast = candles.get(idx).close();
        for (int i = idx + 1; i < candles.size(); i++) {
            postHigh = Math.max(postHigh, candles.get(i).high());
            postLow = Math.min(postLow, candles.get(i).low());
            postLast = candles.get(i).close();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("买点日收盘=").append(String.format("%.2f", candles.get(idx).close())).append("；");
        if (preCount > 0) {
            sb.append("买点前 60 日：最高=").append(String.format("%.2f", preHigh))
                    .append("，最低=").append(String.format("%.2f", preLow))
                    .append("，均价=").append(String.format("%.2f", preSum / preCount)).append("；");
        }
        if (candles.size() - 1 > idx) {
            sb.append("买点后：最高=").append(String.format("%.2f", postHigh))
                    .append("，最低=").append(String.format("%.2f", postLow))
                    .append("，最新收盘=").append(String.format("%.2f", postLast)).append("；");
        } else {
            sb.append("买点后暂无后续 K 线（后验数据不足）；");
        }
        return sb.toString();
    }

    /** 组装 LLM 输入：案例 + 特征画像 + 后验 + K 线统计。 */
    private String buildInsightPrompt(CaseRecord record, String klineSummary) {
        CaseRecord.CaseFeatures f = record.features();
        CaseRecord.CaseVerify v = record.verify();
        StringBuilder sb = new StringBuilder();
        sb.append("案例：").append(record.symbol())
                .append(record.name() == null || record.name().isBlank() ? "" : " " + record.name())
                .append("，买点日 ").append(record.buyDate())
                .append("，用户标注类型 ").append(record.buyType())
                .append(record.description() == null || record.description().isBlank() ? "" : "，用户描述「" + record.description() + "」")
                .append("\n");
        sb.append("特征画像：回撤 ").append(String.format("%.1f", f.drawdownFromHighPct())).append("%，量比 ")
                .append(String.format("%.2f", f.volumeShrinkRatio()))
                .append(f.kdjJ() == null ? "" : String.format("，KDJ.J %.1f", f.kdjJ()))
                .append(f.kdjGoldenCross() ? "，KDJ 金叉" : "")
                .append(f.macdHist() == null ? "" : String.format("，MACD 柱 %.2f", f.macdHist()))
                .append(f.macdCrossUp() ? "，MACD 金叉" : "")
                .append("，均线关系 ").append(f.maRelation())
                .append(String.format("，距 60 日线 %.1f%%（黄线态 %s）", f.distToMa60Pct(), f.yellowLineState()))
                .append(f.whiteAboveYellow() ? "，白线在黄线之上（开门态）" : "，白线在黄线之下（关门态）")
                .append("，盘整 ").append(f.sidewaysDays()).append(" 天")
                .append(f.breakoutFromHigh() ? "，已破前高" : "，未破前高").append("\n");
        sb.append("后验：")
                .append(v.plus5dReturnPct() == null ? "+5d 数据不足" : String.format("+5d %.1f%%", v.plus5dReturnPct()))
                .append("，")
                .append(v.plus10dReturnPct() == null ? "+10d 数据不足" : String.format("+10d %.1f%%", v.plus10dReturnPct()))
                .append("，")
                .append(v.maxDrawdownAfterBuyPct() == null ? "最大回撤未知" : String.format("最大回撤 %.1f%%", v.maxDrawdownAfterBuyPct()))
                .append(v.stopLossHit() ? "，破止损" : "，未破止损").append("\n");
        sb.append("K 线统计：").append(klineSummary);
        return sb.toString();
    }

    /** 解析 LLM 结构化输出；非 JSON / summary 空 → 抛异常（调用方转业务异常）。 */
    private CaseRecord.CaseAiInsight parseInsight(String raw) throws Exception {
        String json = extractJson(raw);
        if (json == null) throw new IllegalStateException("AI 输出未包含 JSON");
        JsonNode node = MAPPER.readTree(json);
        String summary = node.path("summary").asText("").strip();
        if (summary.isBlank()) throw new IllegalStateException("summary 为空");
        List<String> keyFeatures = new ArrayList<>();
        JsonNode kf = node.path("keyFeatures");
        if (kf.isArray()) {
            for (JsonNode n : kf) {
                if (n.isTextual() && !n.asText().isBlank()) keyFeatures.add(n.asText().strip());
            }
        }
        double confidence = node.path("confidence").asDouble(0.0);
        if (confidence < 0 || confidence > 1) confidence = 0.0;
        return new CaseRecord.CaseAiInsight(summary, keyFeatures, confidence, false);
    }

    private String extractJson(String text) {
        if (text == null || text.isBlank()) return null;
        String trimmed = text.strip();
        if (trimmed.startsWith("{")) return trimmed;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        return null;
    }

    /** 案例详情响应（案例 + 可选 K 线窗口）。 */
    public record CaseDetail(CaseRecord caseRecord, List<Candle> kline) {}
}
