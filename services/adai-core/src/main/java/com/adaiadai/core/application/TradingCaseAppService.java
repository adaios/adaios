package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.TradingException;
import com.adaiadai.core.domain.trading.cases.CaseFeatureExtractor;
import com.adaiadai.core.domain.trading.cases.CaseRecord;
import com.adaiadai.core.domain.trading.cases.TradingCaseRepository;
import com.adaiadai.core.domain.trading.market.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * TradingCaseAppService — 完美买点案例用例编排（2026-08-30 第四阶段，环 1-2 最小闭环）。
 * <p>
 * 标注流程：一句话（symbol+buyDate）→ 拉「前 60 + 后 30 交易日」日 K（日历日宽算：前 100/后 45）
 * → 特征画像 + 后验 → 落盘案例（JSON，File First）。K 线本体不落盘——详情按需重放。
 * <p>
 * 降级：拉 K 失败 → 业务异常（不落半成品，fail-visible）；buyDate 无交易数据 → 业务异常；
 * 后验窗口不足 → verify 字段 null（标注照常成功）。
 */
@Service
public class TradingCaseAppService {

    private static final Logger log = LoggerFactory.getLogger(TradingCaseAppService.class);

    /** 前 60 交易日 ≈ 前 100 日历日（宽算，覆盖停牌缺口）。 */
    private static final int BEFORE_CAL_DAYS = 100;
    /** 后 30 交易日 ≈ 后 45 日历日。 */
    private static final int AFTER_CAL_DAYS = 45;
    /** 案例数据窗口（交易日语义，落盘标注）。 */
    private static final int BEFORE_TRADE_DAYS = 60;
    private static final int AFTER_TRADE_DAYS = 30;

    private final KlineService klineService;
    private final TradingCaseRepository caseRepository;
    private final TradingAppService tradingAppService;

    public TradingCaseAppService(KlineService klineService,
                                 TradingCaseRepository caseRepository,
                                 TradingAppService tradingAppService) {
        this.klineService = klineService;
        this.caseRepository = caseRepository;
        this.tradingAppService = tradingAppService;
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

    /** 案例详情响应（案例 + 可选 K 线窗口）。 */
    public record CaseDetail(CaseRecord caseRecord, List<Candle> kline) {}
}
