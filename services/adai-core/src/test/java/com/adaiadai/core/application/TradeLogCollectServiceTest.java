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
        when(parse.parseLoose(any(), any())).thenAnswer(i -> {
            String text = i.getArgument(1);
            if (text.contains("未知股")) {
                // P1-1：LLM 幻觉——有 direction 无 symbol 无 name（think 泄漏文本次生）
                return new TradingParseAppService.ParseResult(true, null, null,
                        "SELL", null, null, null, null, null, null);
            }
            if (text.contains("只有名字")) {
                // P1-1：宽松解析「清仓了XX」——有 name 无 symbol（合法待补充场景）
                return new TradingParseAppService.ParseResult(true, null, "山西汾酒",
                        "SELL", null, null, null, null, null, null);
            }
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

    @Test
    void confirm_incompleteCandidate_skippedNotCounted() {
        // 「我清仓了（股票名）」→ 600519/SELL/无数量 → complete=false → 确认跳过不落库
        service.collect("default", "我清仓了（股票名）", "text");
        assertEquals(1, service.todayCandidates("default").size());
        assertFalse(service.todayCandidates("default").get(0).complete(), "无数量价格应标不完整");

        int done = service.confirm("default");
        assertEquals(0, done, "不完整候选确认应跳过（不落库）");
        assertTrue(service.todayCandidates("default").isEmpty(), "确认后候选清空（跳过的也清，前端引导补全）");
    }

    // ── P1-1 回归（2026-08-18 生产：SELL unknown 污染）──

    @Test
    void collect_symbolAndNameMissing_ignoredNoUnknown() {
        // LLM 幻觉：有 direction 无 symbol 无 name → 拒绝归集，不落 "unknown" 占位
        service.collect("default", "识别出未知股卖出动作", "text");
        assertTrue(service.todayCandidates("default").isEmpty(), "无 symbol 无 name 不得归集");
    }

    @Test
    void collect_nameOnly_symbolNull_keptAsIncomplete() {
        // 宽松解析「清仓了XX」：有 name 无 symbol → 归集为待补充（complete=false），不落 unknown
        service.collect("default", "清仓了只有名字的股票", "text");
        List<TradeLogCandidate> candidates = service.todayCandidates("default");
        assertEquals(1, candidates.size());
        assertFalse(candidates.get(0).complete(), "缺 symbol/数量/价格应标不完整");
        assertFalse("unknown".equals(candidates.get(0).symbol()), "不得用 unknown 占位");
        assertEquals("山西汾酒", candidates.get(0).name());
    }

    @Test
    void collect_nameOnly_differentNames_notDeduplicated() {
        // P1-1：dedupeKey 用 name 兜底——两只不同股票（均无代码）不得互相吞并
        service.collect("default", "清仓了只有名字的股票A", "text");
        service.collect("default", "清仓了只有名字的股票B", "text");
        assertEquals(2, service.todayCandidates("default").size(), "无代码但名称不同应各自归集");
    }

    @Test
    void summarize_nameOnly_showsNameNotUnknown() {
        service.collect("default", "清仓了只有名字的股票", "text");
        String text = service.summarize(service.todayCandidates("default"));
        assertTrue(text.contains("山西汾酒"), "汇总应显示股票名");
        assertFalse(text.contains("unknown"), "汇总不得显示 unknown 占位");
    }
}
