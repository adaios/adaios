package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradeLogCandidate;
import com.adaiadai.core.domain.trading.TradingException;
import com.adaiadai.core.infrastructure.market.NameToSymbolResolver;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.TradeLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
            if (text == null) {
                // Mockito when() 占位触发：any() 匹配器注册时传 null 命中本 answer——按真实
                // parseLoose 语义（null → unmatched）兜底，不 NPE（2026-08-26 截图批量归集测试引入）
                return TradingParseAppService.ParseResult.unmatched();
            }
            if (text.contains("未知股")) {
                // P1-1：LLM 幻觉——有 direction 无 symbol 无 name（think 泄漏文本次生）
                return new TradingParseAppService.ParseResult(true, null, null, "SELL", null, null, null, null, null, null, null);
            }
            if (text.contains("只有名字")) {
                // P1-1：宽松解析「清仓了XX」——有 name 无 symbol（合法待补充场景）
                // 2026-08-27：A/B 两个不同名——验证 dedupeKey name 兜底不互吞（原 mock 同名
                // 靠旧 bug「symbol 读成 null 字符串致 key 不同」才过，仓储归一化后须真不同名）。
                if (text.contains("股票B")) {
                    return new TradingParseAppService.ParseResult(true, null, "泸州老窖", "SELL", null, null, null, null, null, null, null);
                }
                return new TradingParseAppService.ParseResult(true, null, "山西汾酒", "SELL", null, null, null, null, null, null, null);
            }
            if (text.contains("京东方")) {
                return new TradingParseAppService.ParseResult(true, "000725", "京东方A", "SELL", new BigDecimal("6.10"), 5300, null, null, null, null, null);
            }
            if (text.contains("清仓")) {
                return new TradingParseAppService.ParseResult(true, "600519", "贵州茅台", "SELL", null, null, null, null, null, null, null);
            }
            return TradingParseAppService.ParseResult.unmatched();
        });
        trading = mock(TradingAppService.class);
        service = new TradeLogCollectService(parse, repository, trading, mock(NameToSymbolResolver.class));
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
        service = new TradeLogCollectService(parse, repository, trading, mock(NameToSymbolResolver.class));

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
        service = new TradeLogCollectService(parse, repository, trading, mock(NameToSymbolResolver.class));

        service.collect("default", "我清仓了京东方", "text"); // 000725 complete（mock 京东方分支带数量价格）
        // 茅台完整候选直接 append（mock「清仓」分支无数量价格 → 不完整，会走 dedupe 去重干扰）
        repository.append("default", java.time.LocalDate.now(),
                new TradeLogCandidate("600519", "贵州茅台", "SELL", new BigDecimal("1500"), 500, null, "text", true));

        TradeLogCollectService.ConfirmResult r = service.confirm("default");
        assertEquals(1, r.confirmed(), "京东方成功落库");
        assertEquals(1, r.failed(), "茅台失败计入失败");
        assertEquals(1, service.todayCandidates("default").size(), "失败的茅台候选保留");
        assertEquals("600519", service.todayCandidates("default").get(0).symbol());
    }

    // ── 2026-08-27（用户反馈「今日 4 笔其实是昨天」）：确认落库日期归属 ──
    // 候选携带截图「日期」列提取的成交日期 → entryDate = 候选日期；无日期才回退确认当天。

    @Test
    void confirm_candidateWithTradeDate_usesTradeDateAsEntryDate() {
        java.time.LocalDate tradeDate = java.time.LocalDate.of(2026, 8, 26);
        repository.append("default", java.time.LocalDate.now(),
                new TradeLogCandidate("000831", "中国稀土", "BUY",
                        new BigDecimal("56.04"), 100, tradeDate, "image", true));

        TradeLogCollectService.ConfirmResult r = service.confirm("default");

        assertEquals(1, r.confirmed());
        verify(trading).recordTrade(eq("default"), eq("000831"), any(), eq(TradeDirection.BUY),
                eq(new BigDecimal("56.04")), eq(100), eq(tradeDate),
                any(), any(), any(), any(), any());
    }

    @Test
    void confirm_candidateWithoutTradeDate_fallsBackToToday() {
        // 文字归集/当日委托截图无日期列 → tradeDate=null → entryDate 回退确认当天
        repository.append("default", java.time.LocalDate.now(),
                new TradeLogCandidate("000831", "中国稀土", "BUY",
                        new BigDecimal("56.04"), 100, null, "text", true));

        TradeLogCollectService.ConfirmResult r = service.confirm("default");

        assertEquals(1, r.confirmed());
        verify(trading).recordTrade(eq("default"), eq("000831"), any(), eq(TradeDirection.BUY),
                eq(new BigDecimal("56.04")), eq(100), eq(java.time.LocalDate.now()),
                any(), any(), any(), any(), any());
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
                                    new BigDecimal("1500"), 500, null, "text", true));
                }
                return java.util.List.of();
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        service = new TradeLogCollectService(parse, repository, trading, mock(NameToSymbolResolver.class));

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

    // ── 截图表格批量归集（2026-08-26 截图归集缺口修复）──

    @Test
    void collect_screenshotTable_collectsAllFilledTrades() {
        // 截图表格文字 → parseLooseBatch 命中多笔 → 逐笔归集候选
        when(parse.parseLooseBatch(any(), any())).thenReturn(java.util.List.of(
                new TradingParseAppService.ParseResult(true, "000776", "广发证券", "BUY", new BigDecimal("21.170"), 200, null, null, null, null, null),
                new TradingParseAppService.ParseResult(true, "600487", "亨通光电", "BUY", new BigDecimal("64.840"), 300, null, null, null, null, null),
                new TradingParseAppService.ParseResult(true, "000831", "中国稀土", "BUY", new BigDecimal("56.040"), 100, null, null, null, null, null),
                new TradingParseAppService.ParseResult(true, "600206", "有研新材", "SELL", new BigDecimal("50.330"), 600, null, null, null, null, null)));

        service.collect("default", "当日委托 表格文字（模拟截图识别）", "image");

        List<TradeLogCandidate> candidates = service.todayCandidates("default");
        assertEquals(4, candidates.size(), "表格 4 笔已成应全部归集");
        assertTrue(candidates.stream().allMatch(TradeLogCandidate::complete), "表格行应完整（代码/价格/数量全有）");
        assertTrue(candidates.stream().anyMatch(c -> "600487".equals(c.symbol())), "应含亨通光电");
        assertTrue(candidates.stream().anyMatch(c -> "600206".equals(c.symbol())), "应含有研新材");
    }

    @Test
    void collect_screenshotAcrossTwoImages_deduplicates() {
        // 用户「可能给多张截图且重复」：两张截图同一笔（亨通买入 300）→ 去重只留一笔
        when(parse.parseLooseBatch(any(), any())).thenReturn(java.util.List.of(
                new TradingParseAppService.ParseResult(true, "600487", "亨通光电", "BUY", new BigDecimal("64.840"), 300, null, null, null, null, null)));

        service.collect("default", "第一张截图", "image");
        service.collect("default", "第二张截图（重复同一笔）", "image");

        List<TradeLogCandidate> candidates = service.todayCandidates("default");
        assertEquals(1, candidates.size(), "跨截图同 symbol+方向+volume±10% 应去重");
        assertEquals("600487", candidates.get(0).symbol());
    }

    @Test
    void collect_plainScreenshotTable_noBatchNoSingle_ignored() {
        // 截图表格但 parseLooseBatch 空（如全部已报/申购）→ 回退 parseLoose 仍 unmatched → 不归集
        when(parse.parseLooseBatch(any(), any())).thenReturn(java.util.List.of());
        // parseLoose 对表格文字命中不了 mock 关键词分支 → 返回 null（NPE 风险：宽松解析不得返回 null）。
        // 此测试同时回归「parseLoose 返回 null 时 collect 不得崩」——按真实实现 parseLoose 永不返回 null
        // （末尾 return unmatched()），mock 这里显式给 unmatched 模拟真实行为。
        when(parse.parseLoose(any(), any())).thenReturn(TradingParseAppService.ParseResult.unmatched());

        service.collect("default", "云南锗业 002428 93.480 卖出 100 已报 撤 天博申购 732448 买入 4000 已确认", "image");

        assertTrue(service.todayCandidates("default").isEmpty(), "全非成交截图不应产生候选");
    }

    @Test
    void collect_batchResultWithUnknownSymbol_skipped() {
        // 批量解析结果含无 symbol 无 name 的脏行 → 跳过不落 unknown（与单笔 P1-1 同口径）
        when(parse.parseLooseBatch(any(), any())).thenReturn(java.util.List.of(
                new TradingParseAppService.ParseResult(true, null, null, "SELL", null, null, null, null, null, null, null),
                new TradingParseAppService.ParseResult(true, "000776", "广发证券", "BUY", new BigDecimal("21.170"), 200, null, null, null, null, null)));

        service.collect("default", "表格（含幻觉脏行）", "image");

        List<TradeLogCandidate> candidates = service.todayCandidates("default");
        assertEquals(1, candidates.size(), "脏行跳过，正常行归集");
        assertEquals("000776", candidates.get(0).symbol());
    }

    // ── 2026-08-27：VLM OCR 漏代码列 → 按名称查代码补 symbol（NameToSymbolResolver）──

    @Test
    void collectBatch_missingSymbol_resolvesByName() {
        // 截图 OCR 无代码列（名称 价格 买卖 数量 金额）→ 归集时按名称查代码 → complete=true 可确认
        NameToSymbolResolver resolver = mock(NameToSymbolResolver.class);
        when(resolver.resolve("有研新材")).thenReturn("600206");
        service = new TradeLogCollectService(parse, repository, trading, resolver);

        service.collectBatch("default", java.util.List.of(
                new TradingParseAppService.ParseResult(true, null, "有研新材", "SELL", new BigDecimal("50.330"), 600, null, null, null, null, null)), "image");

        List<TradeLogCandidate> candidates = service.todayCandidates("default");
        assertEquals(1, candidates.size());
        assertEquals("600206", candidates.get(0).symbol(), "按名称查到代码应补 symbol");
        assertEquals("有研新材", candidates.get(0).name());
        assertTrue(candidates.get(0).complete(), "代码补齐后应 complete=true 可确认入账");
        verify(resolver).resolve("有研新材");
    }

    @Test
    void collectBatch_missingSymbol_unresolved_completeFalse() {
        // 名称查不到代码（东财无结果/失败）→ 保持按名称归集待补充（complete=false，确认时补）
        NameToSymbolResolver resolver = mock(NameToSymbolResolver.class);
        when(resolver.resolve(any())).thenReturn(null);
        service = new TradeLogCollectService(parse, repository, trading, resolver);

        service.collectBatch("default", java.util.List.of(
                new TradingParseAppService.ParseResult(true, null, "某某新股", "BUY", new BigDecimal("10.000"), 100, null, null, null, null, null)), "image");

        List<TradeLogCandidate> candidates = service.todayCandidates("default");
        assertEquals(1, candidates.size());
        assertNull(candidates.get(0).symbol());
        assertFalse(candidates.get(0).complete(), "查不到代码应保持待补充");
    }
}
