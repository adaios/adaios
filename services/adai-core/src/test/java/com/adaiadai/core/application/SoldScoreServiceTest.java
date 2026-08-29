package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.market.Candle;
import com.adaiadai.core.infrastructure.storage.TradingRuleSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * SoldScoreServiceTest — 清仓复盘三维打分（D3）。
 */
@ExtendWith(MockitoExtension.class)
class SoldScoreServiceTest {

    @Mock
    private KlineService klineService;

    @Mock
    private TradingRuleSettingsRepository settingsRepository;

    private SoldScoreService service() {
        when(settingsRepository.findByUser(anyString())).thenReturn(TradingRuleSettings.defaults());
        return new SoldScoreService(klineService, settingsRepository);
    }

    private static final LocalDate BUY = LocalDate.of(2026, 8, 1);

    /** 构造一段 K 线：前高 10 → 回调到 5（50%+）→ 缩量 → 末根=买入日。 */
    private List<Candle> b1Candles() {
        // 26 根（≥25 detector 要求）：前 6 根放量冲到 high=10，后 19 根缩量回调到 5
        java.util.List<Candle> cs = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            cs.add(new Candle(BUY.minusDays(25 - i), 9, 10, 10, 9, 100_000));
        }
        // 回调前半段量 30_000；末 5 根量递减 6k/4k/2k/1k/1k → avg3 < avg5×0.7 成立
        for (int i = 0; i < 14; i++) {
            double close = 9.2 - i * 0.24; // 9.2 → 5.84
            cs.add(new Candle(BUY.minusDays(19 - i), close, close + 0.1, close + 0.2, close - 0.1, 30_000));
        }
        double[] v = {6000, 4000, 2000, 1000};
        for (int i = 0; i < 4; i++) {
            double close = 5.84 - i * 0.2; // 5.84 → 5.24
            cs.add(new Candle(BUY.minusDays(5 - i), close, close + 0.1, close + 0.2, close - 0.1, v[i]));
        }
        // 末根 = 买入日：缩量 + 低位
        cs.add(new Candle(BUY, 4.8, 5, 5.1, 4.7, 1_000));
        return cs;
    }

    @Test
    void 盈利了结_买入B1_执行好_高分() {
        when(klineService.kline(anyString(), eq(250))).thenReturn(b1Candles());
        SoldTrade t = new SoldTrade("600519", "贵州茅台", BUY, BUY.plusDays(10), 10,
                "1+1", 5.0, "盈利了结", "");
        SoldScoreService.SoldScore s = service().score(List.of(t), "default").get(0);
        assertNotNull(s.buyPointScore());
        assertTrue(s.buyPointScore() >= 70, "B1 买点应≥70，实际 " + s.buyPointScore());
        assertEquals(90, s.executionScore());
        assertNotNull(s.totalScore());
        assertTrue(s.totalScore() >= 70, "总分应≥70，实际 " + s.totalScore());
    }

    @Test
    void 扛单超10_R66_执行分低() {
        when(klineService.kline(anyString(), eq(250))).thenReturn(b1Candles());
        SoldTrade t = new SoldTrade("600519", "贵州茅台", BUY, BUY.plusDays(30), 30,
                "1+1", -15.0, "扛单超 10%——按 R66 只输一根K线，止损位早该执行", "");
        SoldScoreService.SoldScore s = service().score(List.of(t), "default").get(0);
        assertEquals(15, s.executionScore());
        assertTrue(s.executionExplain().contains("R66"));
    }

    @Test
    void 短持仓亏损_R53_执行分中等() {
        when(klineService.kline(anyString(), eq(250))).thenReturn(b1Candles());
        SoldTrade t = new SoldTrade("000725", "京东方A", BUY, BUY.plusDays(3), 3,
                "1+1", -6.0, "短持仓亏损——按 R53 没涨=错，该涨不涨该拍掉", "");
        SoldScoreService.SoldScore s = service().score(List.of(t), "default").get(0);
        assertEquals(45, s.executionScore());
    }

    @Test
    void 无K线_买点分null_执行分仍给() {
        when(klineService.kline(anyString(), eq(250))).thenReturn(List.of());
        SoldTrade t = new SoldTrade("600519", "贵州茅台", BUY, BUY.plusDays(10), 10,
                "1+1", 5.0, "盈利了结", "");
        SoldScoreService.SoldScore s = service().score(List.of(t), "default").get(0);
        assertNull(s.buyPointScore());
        assertNull(s.buyPointSignal());
        assertEquals(90, s.executionScore());
        assertNull(s.totalScore()); // 买点缺失 → 总分不糊弄
    }

    @Test
    void 无买点形态_追高买入_买点分低() {
        // 放量高位追入：末根在顶部放量（无回调无缩量）→ NONE
        java.util.List<Candle> cs = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            double close = 8 + i * 0.2; // 一路上涨到 12.8
            cs.add(new Candle(BUY.minusDays(25 - i), close, close + 0.1, close + 0.3, close - 0.1, 80_000));
        }
        cs.add(new Candle(BUY, 12.8, 13, 13.2, 12.7, 90_000)); // 买入日高位放量
        when(klineService.kline(anyString(), eq(250))).thenReturn(cs);
        SoldTrade t = new SoldTrade("601066", "中信建投", BUY, BUY.plusDays(5), 5,
                "1+1", -8.0, "短持仓亏损——按 R53 没涨=错，该涨不涨该拍掉", "");
        SoldScoreService.SoldScore s = service().score(List.of(t), "default").get(0);
        assertNotNull(s.buyPointScore());
        assertEquals(25, s.buyPointScore(), "追高无形态买点分应=25，实际 " + s.buyPointScore());
    }

    /** 第三阶段：买点参数按用户规则（更严 KDJ 阈值 → 信号判定不同）。 */
    @Test
    void 用户规则KDJ阈值_影响买点判定() {
        // 默认 KDJ.J < 13 才判 B1；用户规则放宽到 J < 50 → 更多命中
        java.util.List<Candle> cs = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            cs.add(new Candle(BUY.minusDays(25 - i), 9, 10, 10, 9, 100_000));
        }
        for (int i = 6; i < 25; i++) {
            cs.add(new Candle(BUY.minusDays(25 - i), 6, 6.5, 6.5, 6, 30_000));
        }
        cs.add(new Candle(BUY, 5.5, 5.8, 5.8, 5.5, 5_000)); // 缩量回调
        when(klineService.kline(anyString(), eq(250))).thenReturn(cs);

        SoldTrade t = new SoldTrade("600000", "浦发银行", BUY, BUY.plusDays(5), 5,
                "1+1", -4.0, "盈利了结", "");
        // 用户规则：KDJ 阈值放宽到 50（比默认 13 宽松）→ 应判出买点信号
        when(settingsRepository.findByUser("alice")).thenReturn(new TradingRuleSettings(
                new BigDecimal("25"), new BigDecimal("0.93"), new BigDecimal("20"),
                new BigDecimal("50"), 5, 5.0, 5, 0.5, 0.7, 50, 1.5, 20, 0.5, 0.5, 66, 95));
        SoldScoreService.SoldScore s = new SoldScoreService(klineService, settingsRepository)
                .score(List.of(t), "alice").get(0);
        assertNotNull(s.buyPointScore(), "用户规则 KDJ<50 → 应判出买点信号");
    }
}
