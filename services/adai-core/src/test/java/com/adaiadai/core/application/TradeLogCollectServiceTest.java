package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.TradeLogCandidate;
import com.adaiadai.core.domain.trading.TradingException;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.TradeLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
    private TradingParseAppService parse;
    private TradingAppService trading;

    @BeforeEach
    void setUp() {
        fileStorage = new InMemoryFileStorage();
        repository = new TradeLogRepository(fileStorage);
        parse = mock(TradingParseAppService.class);
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
        trading = mock(TradingAppService.class);
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
        // mock TradingAppService.recordTrade 无副作用；确认后完整候选落库清空
        TradeLogCollectService.ConfirmResult r = service.confirm("default");
        assertEquals(1, r.confirmed(), "完整候选确认应落库");
        assertTrue(service.todayCandidates("default").isEmpty(), "确认后当日候选应清空");
    }

    @Test
    void confirm_incompleteCandidate_skippedNotCounted() {
        // 「我清仓了（股票名）」→ 600519/SELL/无数量 → complete=false → 确认跳过不落库
        service.collect("default", "我清仓了（股票名）", "text");
        assertEquals(1, service.todayCandidates("default").size());
        assertFalse(service.todayCandidates("default").get(0).complete(), "无数量价格应标不完整");

        TradeLogCollectService.ConfirmResult r = service.confirm("default");
        assertEquals(0, r.confirmed(), "不完整候选确认应跳过（不落库）");
        assertEquals(1, r.skipped(), "不完整候选计入跳过");
        // P0-1（2026-08-23）：不完整候选保留（前端引导补全后再确认），不静默清空
        assertEquals(1, service.todayCandidates("default").size(), "不完整候选应保留");
    }

    // ── P0-1 回归（2026-08-23：确认失败候选不丢失）──

    @Test
    void confirm_recordTradeThrows_candidateKeptAndFailureReported() {
        // recordTrade 抛错（如 SELL 超持仓）→ 该候选保留 + 失败明细返回，不静默清空
        TradingAppService trading = mock(TradingAppService.class);
        doThrow(new TradingException("卖出数量超过持仓: 000725（持有 100 股）"))
                .when(trading).recordTrade(any(), any(), any(), any(), any(), anyInt(),
                any(), any(), any(), any(), any(), any());
        service = new TradeLogCollectService(parse, repository, trading);

        service.collect("default", "我清仓了京东方", "text");
        assertEquals(1, service.todayCandidates("default").size());

        TradeLogCollectService.ConfirmResult r = service.confirm("default");
        assertEquals(0, r.confirmed(), "全部失败不计成功");
        assertEquals(1, r.failed(), "失败笔数应报告");
        assertEquals(1, r.failures().size(), "失败明细应返回");
        assertTrue(r.failures().get(0).contains("卖出数量超过持仓"), "失败明细含人话原因");
        assertEquals(1, service.todayCandidates("default").size(), "失败候选必须保留（不丢失）");
    }

    @Test
    void confirm_mixedResult_successClearedFailureKept() {
        // 混合场景：一笔成功落库清空 + 一笔失败保留
        TradingAppService trading = mock(TradingAppService.class);
        // 京东方（000725/SELL）成功；贵州茅台（600519/SELL）抛错
        doThrow(new TradingException("未持有 600519，无法卖出"))
                .when(trading).recordTrade(eq("default"), eq("600519"), any(), any(), any(), anyInt(),
                any(), any(), any(), any(), any(), any());
        service = new TradeLogCollectService(parse, repository, trading);

        service.collect("default", "我清仓了京东方", "text"); // 000725 complete（mock 京东方分支带数量价格）
        // 茅台完整候选直接 append（mock「清仓」分支无数量价格 → 不完整，会走 dedupe 去重干扰）
        repository.append("default", java.time.LocalDate.now(),
                new TradeLogCandidate("600519", "贵州茅台", "SELL", new BigDecimal("1500"), 500, "text", true));

        TradeLogCollectService.ConfirmResult r = service.confirm("default");
        assertEquals(1, r.confirmed(), "京东方成功落库");
        assertEquals(1, r.failed(), "茅台失败计入失败");
        assertEquals(1, service.todayCandidates("default").size(), "失败的茅台候选保留");
        assertEquals("600519", service.todayCandidates("default").get(0).symbol());
    }

    // ── B6-5（2026-08-23，P1-交易18）：丢弃保留候选（钉子户）──

    @Test
    void discard_removesCandidate() {
        service.collect("default", "清仓了贵州茅台", "text"); // 600519/SELL/无数量 → 保留待补全
        assertEquals(1, service.todayCandidates("default").size());

        assertTrue(service.discard("default", "600519", "SELL"), "应丢弃成功");
        assertTrue(service.todayCandidates("default").isEmpty(), "丢弃后候选清空");
    }

    @Test
    void discard_unknownCandidate_returnsFalse() {
        service.collect("default", "清仓了贵州茅台", "text");
        assertFalse(service.discard("default", "999999", "SELL"), "无此候选应返回 false");
        assertEquals(1, service.todayCandidates("default").size(), "未命中不得误删");
    }

    // ── C1（2026-08-23，隔离审查 P2-2）：confirm 处理期间新归集候选不丢 ──

    @Test
    void confirm_newCandidateAppendedDuringProcessing_isKept() {
        // 模拟：confirm 读取候选后、处理过程中，新候选被 collect append（真实并发窗口）——
        // 用 mock recordTrade 在首次调用时动态 append，验证 save 前合并逻辑保留新候选
        TradingAppService trading = mock(TradingAppService.class);
        AtomicInteger calls = new AtomicInteger(0);
        try {
            when(trading.recordTrade(any(), any(), any(), any(), any(), anyInt(),
                    any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
                if (calls.incrementAndGet() == 1) {
                    // 首次处理（京东方）进行中，新候选茅台到达（模拟 collect 并发）
                    repository.append("default", java.time.LocalDate.now(),
                            new TradeLogCandidate("600519", "贵州茅台", "SELL",
                                    new BigDecimal("1500"), 500, "text", true));
                }
                return java.util.List.of();
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        service = new TradeLogCollectService(parse, repository, trading);

        service.collect("default", "我清仓了京东方", "text"); // 000725 complete
        assertEquals(1, service.todayCandidates("default").size());

        TradeLogCollectService.ConfirmResult r = service.confirm("default");
        assertEquals(1, r.confirmed(), "京东方确认落库");
        List<TradeLogCandidate> after = service.todayCandidates("default");
        assertEquals(1, after.size(), "处理期间到达的新候选必须保留（不得被 confirm 清空）");
        assertEquals("600519", after.get(0).symbol(), "保留的是处理期间到达的茅台");
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
