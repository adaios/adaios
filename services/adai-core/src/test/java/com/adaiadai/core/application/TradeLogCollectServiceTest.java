package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.TradeLogCandidate;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.TradeLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TradeLogCollectService — RFC 20260817 交易日志自动归集。
 * 覆盖：归集去重（同 symbol+方向当日只记一笔）/ 确认落库清空候选 / summarize 文案。
 */
class TradeLogCollectServiceTest {

    private InMemoryFileStorage fileStorage;
    private TradeLogRepository repository;
    private TradeLogCollectService service;

    @BeforeEach
    void setUp() {
        fileStorage = new InMemoryFileStorage();
        repository = new TradeLogRepository(fileStorage);
        TradingParseAppService parse = mock(TradingParseAppService.class);
        when(parse.parse(any(), any())).thenAnswer(i -> {
            String text = i.getArgument(1);
            if (text.contains("京东方")) {
                return new TradingParseAppService.ParseResult(true, "000725", "京东方A",
                        "SELL", new BigDecimal("6.10"), 5300, null, null, null, null);
            }
            if (text.contains("清仓")) {
                return new TradingParseAppService.ParseResult(true, "600519", "贵州茅台",
                        "SELL", null, null, null, null, null, null);
            }
            return TradingParseAppService.ParseResult.unmatched();
        });
        TradingAppService trading = mock(TradingAppService.class);
        service = new TradeLogCollectService(parse, repository, trading);
    }

    @Test
    void collect_deduplicatesSameSymbolDirection() {
        service.collect("default", "我清仓了京东方", "text");
        service.collect("default", "已清仓京东方5000股@6.1", "text"); // 同 symbol+方向 → 去重
        System.out.println("DEBUG candidates=" + service.todayCandidates("default"));

        List<TradeLogCandidate> candidates = service.todayCandidates("default");
        assertEquals(1, candidates.size(), "同股票同方向当日只记一笔");
        assertEquals("000725", candidates.get(0).symbol());
    }

    @Test
    void collect_unmatched_text_ignored() {
        service.collect("default", "今天天气不错", "text");
        assertTrue(service.todayCandidates("default").isEmpty(), "非交易表述不归集");
    }

    @Test
    void collect_incomplete_text_marksCompleteFalse() {
        service.collect("default", "我清仓了（股票名）", "text"); // 无数量价格 → complete=false
        // mock 里「清仓」无数量 → SELL 茅台 complete=false
        List<TradeLogCandidate> candidates = service.todayCandidates("default");
        assertEquals(1, candidates.size());
        assertFalse(candidates.get(0).complete(), "无数量价格应标不完整");
    }

    @Test
    void summarize_containsCandidateAndPrompt() {
        service.collect("default", "我清仓了京东方", "text");
        String text = service.summarize(service.todayCandidates("default"));
        assertTrue(text.contains("京东方"), "汇总应含股票名");
        assertTrue(text.contains("卖出"), "汇总应含方向");
        assertTrue(text.contains("是否完整"), "汇总应提示确认");
    }

    @Test
    void confirm_clearsCandidates() {
        service.collect("default", "我清仓了京东方", "text");
        assertEquals(1, service.todayCandidates("default").size());
        // mock TradingAppService.recordTrade 无副作用；确认后候选清空
        int done = service.confirm("default");
        assertTrue(service.todayCandidates("default").isEmpty(), "确认后当日候选应清空");
    }
}
