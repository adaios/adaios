package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.TradingException;
import com.adaiadai.core.domain.trading.cases.CaseFeatureExtractor;
import com.adaiadai.core.domain.trading.cases.CaseRecord;
import com.adaiadai.core.domain.trading.cases.CaseConsensus;
import com.adaiadai.core.domain.trading.cases.CaseImportParser;
import com.adaiadai.core.infrastructure.market.NameToSymbolResolver;
import com.adaiadai.core.domain.trading.cases.CaseSimilarityEngine;
import com.adaiadai.core.domain.trading.cases.TradingCaseRepository;
import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.domain.trading.market.IndicatorSeriesCalculator;
import static com.adaiadai.core.domain.trading.market.IndicatorSeriesCalculator.IndicatorSeries;
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
    private final NameToSymbolResolver nameToSymbolResolver;
    /** 黄白线近似均线周期（批 5 前置，配置化：adai.trading.case.yellow-ma / white-ma，默认 60/10）。 */
    private final int yellowMaPeriod;
    private final int whiteMaPeriod;

    public TradingCaseAppService(KlineService klineService,
                                 TradingCaseRepository caseRepository,
                                 TradingAppService tradingAppService,
                                 AiClient aiClient,
                                 NameToSymbolResolver nameToSymbolResolver,
                                 @org.springframework.beans.factory.annotation.Value("${adai.trading.case.yellow-ma:60}") int yellowMaPeriod,
                                 @org.springframework.beans.factory.annotation.Value("${adai.trading.case.white-ma:10}") int whiteMaPeriod) {
        this.klineService = klineService;
        this.caseRepository = caseRepository;
        this.tradingAppService = tradingAppService;
        this.aiClient = aiClient;
        this.nameToSymbolResolver = nameToSymbolResolver;
        this.yellowMaPeriod = yellowMaPeriod;
        this.whiteMaPeriod = whiteMaPeriod;
    }

    /** 标注一个完美买点案例：拉窗口 → 特征 + 后验 → 落盘（返回案例，兼容既有调用）。 */
    public CaseRecord annotate(String userId, String symbol, LocalDate buyDate,
                               String buyType, String description, List<String> labels, String name) {
        return annotateWithCheck(userId, symbol, buyDate, buyType, description, labels, name).record();
    }

    /**
     * 标注 + 共识偏离度校验（2026-08-30 建议 #4：防脏案例进库）。
     * 落盘后基于**既有案例库**（不含新案例）共识画像评估新案例——偏离大仅提示不阻止
     * （用户是权威）；案例库 <5 → consensusCheck null。
     */
    public AnnotateResult annotateWithCheck(String userId, String symbol, LocalDate buyDate,
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
        CaseRecord.CaseFeatures features = CaseFeatureExtractor.extract(candles, buyDate, yellowMaPeriod, whiteMaPeriod);
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
        List<CaseRecord> existingBeforeSave = caseRepository.list(userId);
        CaseRecord record = new CaseRecord(
                caseId, symbol, resolvedName, buyDate,
                buyType == null || buyType.isBlank() ? "unknown" : buyType,
                description, labels == null ? List.of() : labels, LocalDateTime.now(),
                new CaseRecord.CaseWindow(BEFORE_TRADE_DAYS, AFTER_TRADE_DAYS),
                features, verify, CaseRecord.CaseAiInsight.empty());
        caseRepository.save(userId, record);
        log.info("完美买点案例已标注 | userId={} | caseId={} | buyType={}", userId, caseId, record.buyType());
        // 共识偏离度校验：用既有案例库画像评估新案例（save 后库含新案例——用 save 前 list）
        CaseConsensus.ConsensusResult check = null;
        List<CaseConsensus.Range> profile = CaseConsensus.buildProfile(existingBeforeSave);
        if (profile != null) {
            check = CaseConsensus.evaluate(record.features(), profile);
        }
        return new AnnotateResult(record, check);
    }

    /** 标注结果（案例 + 共识偏离度校验，check 可为 null）。 */
    public record AnnotateResult(CaseRecord record, CaseConsensus.ConsensusResult consensusCheck) {}

    /** 案例列表（buyDate 倒序）。 */
    public List<CaseRecord> list(String userId) {
        return caseRepository.list(userId);
    }

    /** 批量导入（2026-08-31：用户完美案例笔记 B1/B2）——解析 → 名称转代码 → 逐条标注。 */
    public List<CaseImportResult> importCases(String userId, String text) {
        List<CaseImportParser.ImportItem> items = CaseImportParser.parse(text);
        List<CaseImportResult> results = new java.util.ArrayList<>();
        for (CaseImportParser.ImportItem item : items) {
            // 缺日期 → 跳过报告
            if (item.buyDate() == null) {
                results.add(new CaseImportResult(item.name(), null, null, "skipped", "笔记缺日期（跳过）", null));
                continue;
            }
            // 名称 → 代码（东财 suggest；查不到 → 失败报告）
            String symbol = null;
            try {
                // 2026-08-31：本地名称表精确匹配优先（suggest 对部分名称空）→ suggest 兜底
                symbol = nameToSymbolResolver.resolveExact(item.name());
                if (symbol == null) {
                    List<NameToSymbolResolver.Candidate> candidates =
                            nameToSymbolResolver.search(item.name());
                    if (!candidates.isEmpty()) symbol = candidates.get(0).code();
                }
            } catch (Exception e) {
                log.warn("批量导入名称查代码失败 | name={} | {}", item.name(), e.getMessage());
            }
            if (symbol == null) {
                results.add(new CaseImportResult(item.name(), null, item.buyDate(),
                        "failed", "名称未匹配到代码（请核对名称或改标注）", null));
                continue;
            }
            // 重复 → 跳过
            String caseId = CaseRecord.idOf(symbol, item.buyDate());
            if (caseRepository.exists(userId, caseId)) {
                results.add(new CaseImportResult(item.name(), symbol, item.buyDate(),
                        "skipped", "已存在（" + caseId + "）", null));
                continue;
            }
            // 北交所（92 开头）本地行情无数据 → 明确提示（2026-08-31）
            if (symbol.startsWith("92")) {
                results.add(new CaseImportResult(item.name(), symbol, item.buyDate(),
                        "failed", "北交所暂不支持（本地行情仅沪深）", null));
                continue;
            }
            // 标注（含共识校验）
            try {
                AnnotateResult result = annotateWithCheck(userId, symbol, item.buyDate(),
                        null, null, null, null);
                results.add(new CaseImportResult(item.name(), symbol, item.buyDate(),
                        "ok", null, result.consensusCheck()));
            } catch (TradingException e) {
                results.add(new CaseImportResult(item.name(), symbol, item.buyDate(),
                        "failed", e.getMessage(), null));
            }
        }
        log.info("完美案例批量导入 | userId={} | 解析 {} 条 → ok {} / skipped {} / failed {}",
                userId, results.size(),
                results.stream().filter(r -> "ok".equals(r.status())).count(),
                results.stream().filter(r -> "skipped".equals(r.status())).count(),
                results.stream().filter(r -> "failed".equals(r.status())).count());
        return results;
    }

    /** 批量导入单条结果。 */
    public record CaseImportResult(String name, String symbol, LocalDate buyDate,
                                   String status, String error,
                                   CaseConsensus.ConsensusResult consensusCheck) {}

    /** 案例详情；withKline=true 附 90 根窗口日 K（前端画图重放，失败 → 空列表）；
     * withIndicators=true 附指标全序列（2026-08-30 前后端一致：前端图不重算，hover 值 = 特征同源）。 */
    public CaseDetail detail(String userId, String caseId, boolean withKline, boolean withIndicators) {
        CaseRecord record = caseRepository.findById(userId, caseId)
                .orElseThrow(() -> new TradingException("案例不存在：" + caseId));
        List<Candle> kline = List.of();
        if (withKline && record.buyDate() != null) {
            kline = klineService.klineRange(record.symbol(),
                    record.buyDate().minusDays(BEFORE_CAL_DAYS), record.buyDate().plusDays(AFTER_CAL_DAYS));
        }
        IndicatorSeries series = withIndicators && !kline.isEmpty()
                ? IndicatorSeriesCalculator.series(kline) : null;
        return new CaseDetail(record, kline, series);
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
            return new MatchResponse(symbol, List.of(), null);
        }
        LocalDate queryDate = date != null ? date : LocalDate.now();
        List<Candle> candles = klineService.klineRange(symbol,
                queryDate.minusDays(BEFORE_CAL_DAYS), queryDate.plusDays(AFTER_CAL_DAYS));
        if (candles.isEmpty()) {
            throw new TradingException("无法获取 " + symbol + " 的 K 线数据，请稍后重试");
        }
        LocalDate targetDate = queryDate;
        CaseRecord.CaseFeatures features = CaseFeatureExtractor.extract(candles, targetDate, yellowMaPeriod, whiteMaPeriod);
        if (features == null && !candles.isEmpty()) {
            // 指定日无数据（停牌/非交易日）→ 回落最近交易日
            targetDate = candles.get(candles.size() - 1).date();
            features = CaseFeatureExtractor.extract(candles, targetDate, yellowMaPeriod, whiteMaPeriod);
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
        // 2026-08-30 共识判定（核心价值）：案例库 ≥5 → 从案例统计学习「完美买点画像」
        //（各特征 25-75 分位区间）→ 当前形态逐维命中 → 「共识命中 N/M 维」。
        CaseConsensus.ConsensusResult consensus = null;
        List<CaseConsensus.Range> profile = CaseConsensus.buildProfile(cases);
        if (profile != null) {
            consensus = CaseConsensus.evaluate(features, profile);
        }
        log.info("案例匹配完成 | userId={} | symbol={} | 基准日={} | 命中={} | 案例库={} | 共识={}",
                userId, symbol, targetDate, items.size(), cases.size(),
                consensus == null ? "不可用(<5案例)" : consensus.hitCount() + "/" + consensus.total());
        return new MatchResponse(symbol, items, consensus);
    }

    /** 匹配响应（核心价值输出：相似案例 + 相似度 + 后验参照 + 共识命中）。 */
    public record MatchResponse(String symbol, List<MatchItem> matches,
                                CaseConsensus.ConsensusResult consensus) {}

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

    /** 案例详情响应（案例 + 可选 K 线窗口 + 可选指标序列）。 */
    public record CaseDetail(CaseRecord caseRecord, List<Candle> kline,
                             IndicatorSeries indicators) {}
}
