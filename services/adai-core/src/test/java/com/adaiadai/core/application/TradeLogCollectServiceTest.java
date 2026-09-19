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
import static org.mockito.Mockito.never;
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
        // P2-交易44（2026-09-14）：Mockito 对自定义 record 返回类型默认给 null（只有 List 才默认空表），
        // 而真实实现「表格未命中 → 空结果 + 回退单笔宽松解析」。这里显式兜底，避免未 stub 的用例 NPE；
        // 各用例自己的 stub 写在测试方法里，晚于此处置，按 Mockito 语义覆盖本行。
        when(parse.parseLooseBatchDetailed(any(), any()))
                .thenReturn(new TradingParseAppService.LooseBatchParse(java.util.List.of(), java.util.List.of()));
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
        // 2026-09-19（P1-4，三官深审）：confirm 现在把「判重 → 锚定分派 → 落账」整段放进
        // per-user 锁（`candidateLock`）。mock 默认返回 null → `synchronized(null)` 会 NPE，
        // 这里给一把真锁（单线程用例下等价于无竞争）。
        when(trading.candidateLock(any())).thenReturn(new Object());
        service = new TradeLogCollectService(parse, repository, trading, mock(NameToSymbolResolver.class));
        // 2026-09-15 防重复入账：Mockito 对 Optional 返回类型默认给 null（不是 empty），
        // 未显式 stub 的用例会 NPE——此处统一兜底为「没有记过」，各用例可自行覆盖。
        when(trading.findRecordedTrade(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(java.util.Optional.empty());
    }

    @Test
    void confirm_sameTradeAlreadyRecorded_skippedNotDuplicated() {
        // 2026-09-15 生产事故：同一张截图反复确认 → 同一笔被记多次。confirm 现在按
        // 「成交编号优先 / 指纹兜底」判重，命中则跳过并如实回报，不再重复落库。
        service.collect("default", "我清仓了京东方", "text");
        assertEquals(1, service.todayCandidates("default").size());
        when(trading.findRecordedTrade(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(java.util.Optional.of(new com.adaiadai.core.domain.trading.TradeRecord(
                        "trade_x", "000725", "京东方", TradeDirection.SELL, new BigDecimal("6.10"),
                        5000, new BigDecimal("30500.00"), java.time.LocalDate.now(),
                        java.time.LocalTime.of(10, 0), null, null, null, null, null,
                        java.time.LocalDateTime.now(), null, null)));

        TradeLogCollectService.ConfirmResult r = service.confirm("default");

        assertEquals(0, r.confirmed(), "同笔已记过 → 不重复落库");
        assertEquals(1, r.duplicated(), "应计入 duplicated");
        assertEquals(1, r.duplicates().size(), "应给出人话明细");
        assertTrue(r.duplicates().get(0).contains("已经记过"), "提示应为「已经记过」：" + r.duplicates());
        assertTrue(service.todayCandidates("default").isEmpty(), "已入账的候选不再保留");
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
        // 2026-09-19（P1-4）：confirm 的「判重 → 分派 → 落账」现在整段在 per-user 锁内；
        // 局部新建的 mock 必须同样给出真锁，否则 synchronized(null) → NPE。
        when(trading.candidateLock(any())).thenReturn(new Object());
        doThrow(new TradingException("卖出数量超过持仓: 000725（持有 100 股）"))
                .when(trading).recordTradeWithOrderId(any(), any(), any(), any(), any(), anyInt(),
                any(), any(), any(), any(), any(), any(), any(), any());
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
        // 2026-09-19（P1-4）：confirm 的「判重 → 分派 → 落账」现在整段在 per-user 锁内；
        // 局部新建的 mock 必须同样给出真锁，否则 synchronized(null) → NPE。
        when(trading.candidateLock(any())).thenReturn(new Object());
        // 京东方（000725/SELL）成功；贵州茅台（600519/SELL）抛错
        doThrow(new TradingException("未持有 600519，无法卖出"))
                .when(trading).recordTradeWithOrderId(eq("default"), eq("600519"), any(), any(), any(), anyInt(),
                any(), any(), any(), any(), any(), any(), any(), any());
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
        verify(trading).recordTradeWithOrderId(eq("default"), eq("000831"), any(), eq(TradeDirection.BUY),
                eq(new BigDecimal("56.04")), eq(100), eq(tradeDate),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void confirm_candidateWithoutTradeDate_fallsBackToToday() {
        // 文字归集（「清仓了XX」当日口语）无日期 → entryDate 回退确认当天（2026-08-27 二修后仍成立：
        // 强制日期只针对截图归集——文字没有日期列概念，当日动作回退当天合理）
        repository.append("default", java.time.LocalDate.now(),
                new TradeLogCandidate("000831", "中国稀土", "BUY",
                        new BigDecimal("56.04"), 100, null, "text", true));

        TradeLogCollectService.ConfirmResult r = service.confirm("default");

        assertEquals(1, r.confirmed());
        verify(trading).recordTradeWithOrderId(eq("default"), eq("000831"), any(), eq(TradeDirection.BUY),
                eq(new BigDecimal("56.04")), eq(100), eq(java.time.LocalDate.now()),
                any(), any(), any(), any(), any(), any(), any());
    }

    // ── 2026-08-27 二修（用户拍板「截图缺日期禁止落库，补充日期后再确认」）──

    @Test
    void confirm_screenshotCandidateWithoutTradeDate_skippedAndKept() {
        // 截图归集候选无日期列（tradeDate=null）→ 禁止落库：skipped + 候选保留 + 人话提示
        repository.append("default", java.time.LocalDate.now(),
                new TradeLogCandidate("600206", "有研新材", "SELL",
                        new BigDecimal("50.33"), 600, null, "image", true));

        TradeLogCollectService.ConfirmResult r = service.confirm("default");

        assertEquals(0, r.confirmed(), "截图缺日期不得落库");
        assertEquals(1, r.skipped(), "缺日期应计入跳过");
        assertEquals(1, r.failures().size(), "应返回人话提示");
        assertTrue(r.failures().get(0).contains("缺少成交日期"), "提示应含缺日期原因: " + r.failures());
        assertEquals(1, service.todayCandidates("default").size(), "候选应保留待补日期");
        verify(trading, never()).recordTradeWithOrderId(any(), any(), any(), any(), any(), anyInt(),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void confirm_screenshotCandidateWithoutTradeDate_mixedWithText_fallsBackOnlyText() {
        // 混合场景：截图缺日期跳过保留；文字无日期正常回退当天落库——互不干扰
        repository.append("default", java.time.LocalDate.now(),
                new TradeLogCandidate("600206", "有研新材", "SELL",
                        new BigDecimal("50.33"), 600, null, "image", true));
        repository.append("default", java.time.LocalDate.now(),
                new TradeLogCandidate("000831", "中国稀土", "BUY",
                        new BigDecimal("56.04"), 100, null, "text", true));

        TradeLogCollectService.ConfirmResult r = service.confirm("default");

        assertEquals(1, r.confirmed(), "文字候选正常落库");
        assertEquals(1, r.skipped(), "截图缺日期跳过");
        assertEquals(1, service.todayCandidates("default").size(), "仅截图候选保留");
    }

    @Test
    void setTradeDate_updatesCandidateTradeDate() {
        repository.append("default", java.time.LocalDate.now(),
                new TradeLogCandidate("600206", "有研新材", "SELL",
                        new BigDecimal("50.33"), 600, null, "image", true));

        boolean updated = service.setTradeDate("default", "600206", "SELL",
                java.time.LocalDate.of(2026, 8, 26));

        assertTrue(updated, "应更新成功");
        List<TradeLogCandidate> candidates = service.todayCandidates("default");
        assertEquals(1, candidates.size());
        assertEquals(java.time.LocalDate.of(2026, 8, 26), candidates.get(0).tradeDate(),
                "候选 tradeDate 应被补写");

        // 补日期后可正常确认落库（entryDate=补写的日期，不再回退当天）
        TradeLogCollectService.ConfirmResult r = service.confirm("default");
        assertEquals(1, r.confirmed());
        verify(trading).recordTradeWithOrderId(eq("default"), eq("600206"), any(), eq(TradeDirection.SELL),
                eq(new BigDecimal("50.33")), eq(600), eq(java.time.LocalDate.of(2026, 8, 26)),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void setTradeDate_unknownCandidate_returnsFalse() {
        assertFalse(service.setTradeDate("default", "999999", "SELL",
                java.time.LocalDate.of(2026, 8, 26)), "无此候选应返回 false");
        assertTrue(service.todayCandidates("default").isEmpty(), "未命中不得产生候选");
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
        // 2026-09-19（P1-4）：confirm 的「判重 → 分派 → 落账」现在整段在 per-user 锁内；
        // 局部新建的 mock 必须同样给出真锁，否则 synchronized(null) → NPE。
        when(trading.candidateLock(any())).thenReturn(new Object());
        AtomicInteger calls = new AtomicInteger(0);
        try {
            when(trading.recordTradeWithOrderId(any(), any(), any(), any(), any(), anyInt(),
                    any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
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
        when(parse.parseLooseBatchDetailed(any(), any())).thenReturn(new TradingParseAppService.LooseBatchParse(java.util.List.of(
                new TradingParseAppService.ParseResult(true, "000776", "广发证券", "BUY", new BigDecimal("21.170"), 200, null, null, null, null, null),
                new TradingParseAppService.ParseResult(true, "600487", "亨通光电", "BUY", new BigDecimal("64.840"), 300, null, null, null, null, null),
                new TradingParseAppService.ParseResult(true, "000831", "中国稀土", "BUY", new BigDecimal("56.040"), 100, null, null, null, null, null),
                new TradingParseAppService.ParseResult(true, "600206", "有研新材", "SELL", new BigDecimal("50.330"), 600, null, null, null, null, null)),
                java.util.List.of()));

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
        when(parse.parseLooseBatchDetailed(any(), any())).thenReturn(new TradingParseAppService.LooseBatchParse(java.util.List.of(
                new TradingParseAppService.ParseResult(true, "600487", "亨通光电", "BUY", new BigDecimal("64.840"), 300, null, null, null, null, null)),
                java.util.List.of()));

        service.collect("default", "第一张截图", "image");
        service.collect("default", "第二张截图（重复同一笔）", "image");

        List<TradeLogCandidate> candidates = service.todayCandidates("default");
        assertEquals(1, candidates.size(), "跨截图同 symbol+方向+volume±10% 应去重");
        assertEquals("600487", candidates.get(0).symbol());
    }

    @Test
    void collect_plainScreenshotTable_noBatchNoSingle_ignored() {
        // 截图表格但 parseLooseBatch 空（如全部已报/申购）→ 回退 parseLoose 仍 unmatched → 不归集
        // P2-交易44（2026-09-14）：同时验证「被丢掉的行」随归集结果带出去（原来只 log.debug 静默吞）
        when(parse.parseLooseBatchDetailed(any(), any())).thenReturn(new TradingParseAppService.LooseBatchParse(
                java.util.List.of(),
                java.util.List.of(new TradingImportParser.UnparsedLine(1,
                        "云南锗业 002428 93.480 卖出 100 已报",
                        "状态「已报」不是已成/部成（未成交的单子没有记）"))));
        // parseLoose 对表格文字命中不了 mock 关键词分支 → 返回 null（NPE 风险：宽松解析不得返回 null）。
        // 此测试同时回归「parseLoose 返回 null 时 collect 不得崩」——按真实实现 parseLoose 永不返回 null
        // （末尾 return unmatched()），mock 这里显式给 unmatched 模拟真实行为。
        when(parse.parseLoose(any(), any())).thenReturn(TradingParseAppService.ParseResult.unmatched());

        TradeLogCollectService.CollectResult r = service.collectDetailed("default",
                "云南锗业 002428 93.480 卖出 100 已报 撤 天博申购 732448 买入 4000 已确认", "image");

        assertTrue(r.candidates().isEmpty(), "全非成交截图不应产生候选");
        assertEquals(1, r.dropped().size(), "没记的行必须随结果带出去（用户才知道截图里有行没进候选）");
        assertTrue(r.dropped().get(0).reason().contains("已报"));
    }

    @Test
    void collect_batchResultWithUnknownSymbol_skipped() {
        // 批量解析结果含无 symbol 无 name 的脏行 → 跳过不落 unknown（与单笔 P1-1 同口径）
        when(parse.parseLooseBatchDetailed(any(), any())).thenReturn(new TradingParseAppService.LooseBatchParse(java.util.List.of(
                new TradingParseAppService.ParseResult(true, null, null, "SELL", null, null, null, null, null, null, null),
                new TradingParseAppService.ParseResult(true, "000776", "广发证券", "BUY", new BigDecimal("21.170"), 200, null, null, null, null, null)),
                java.util.List.of()));

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

    // ── P2-交易36 治本（2026-09-09）：成交编号/手续费 候选补填 + 确认透传 ──

    @Test
    void confirm_candidateWithOrderIdAndFee_passesToRecordTradeWithOrderId() {
        repository.append("default", java.time.LocalDate.now(),
                new TradeLogCandidate("000831", "中国稀土", "BUY",
                        new BigDecimal("56.04"), 100, java.time.LocalDate.of(2026, 9, 9),
                        "image", true, "order-12345", new BigDecimal("5.6")));

        TradeLogCollectService.ConfirmResult r = service.confirm("default");

        assertEquals(1, r.confirmed());
        verify(trading).recordTradeWithOrderId(eq("default"), eq("000831"), any(), eq(TradeDirection.BUY),
                eq(new BigDecimal("56.04")), eq(100), eq(java.time.LocalDate.of(2026, 9, 9)),
                any(), any(), any(), any(), any(),
                eq("order-12345"), eq(new BigDecimal("5.6")));
    }

    @Test
    void updateMeta_setsOrderIdAndFee_onTodayCandidate() {
        repository.append("default", java.time.LocalDate.now(),
                new TradeLogCandidate("600206", "有研新材", "SELL",
                        new BigDecimal("50.33"), 600, java.time.LocalDate.of(2026, 9, 9),
                        "image", true));

        assertTrue(service.updateMeta("default", "600206", "SELL", "委托号88", new BigDecimal("3.20")),
                "应更新成功");
        TradeLogCandidate c = service.todayCandidates("default").get(0);
        assertEquals("委托号88", c.orderId());
        assertEquals(0, new BigDecimal("3.20").compareTo(c.fee()), "手续费应写回候选");

        // 只覆盖非空：再补 null orderId（保留旧值）+ 新 fee
        assertTrue(service.updateMeta("default", "600206", "SELL", null, new BigDecimal("4.00")));
        c = service.todayCandidates("default").get(0);
        assertEquals("委托号88", c.orderId(), "null 不得清空已有 orderId");
        assertEquals(0, new BigDecimal("4.00").compareTo(c.fee()));
    }

    @Test
    void updateMeta_unknownCandidateOrEmptyValues_returnsFalse() {
        repository.append("default", java.time.LocalDate.now(),
                new TradeLogCandidate("600206", "有研新材", "SELL",
                        new BigDecimal("50.33"), 600, null, "image", true));
        assertFalse(service.updateMeta("default", "999999", "SELL", "x", null), "无此候选返回 false");
        assertFalse(service.updateMeta("default", "600206", "SELL", null, null), "无可写值返回 false");
        assertEquals(1, service.todayCandidates("default").size());
    }

    // ── P0-交易53（2026-09-17 生产实测）端到端：竖排截图 → 解析 → 归集 → 去重 ──

    /** 真实链路组合（真解析器 + 真仓储 + 真归集），用于锁死「解析修好但被去重吞掉」这类叠加缺陷。 */
    private TradeLogCollectService realPipeline() {
        return new TradeLogCollectService(
                new TradingParseAppService(mock(com.adaiadai.core.kernel.ai.AiClient.class),
                        new com.fasterxml.jackson.databind.ObjectMapper()),
                repository, trading, mock(NameToSymbolResolver.class));
    }

    @Test
    void collectDetailed_verticalTable_keepsAllThreeSameSymbolTrades() {
        // 用户 2026-09-17 真实截图：亨通光电 3 笔买入、各 100 股、价格 68.27/67.73/67.92。
        // 两个缺陷叠加才会只落 1 笔：① 竖排版式下横排正则 0 命中 → 降级单笔解析只出一笔；
        // ② 去重键不含价格 → 同标的/同方向/同数量的三笔互相吞并。本用例锁死「三笔都要留下」。
        String text = "识别交易动作：当日成交\n"
                + "开源证券 (****0888)\n"
                + "名称/代码\n成交价/买卖\n成交量/额\n成交时间\n"
                + "亨通光电\n600487\n68.270\n买入\n100\n6827.000\n13:08:59\n"
                + "亨通光电\n600487\n67.730\n买入\n100\n6773.000\n10:13:10\n"
                + "亨通光电\n600487\n67.920\n买入\n100\n6792.000\n10:11:44\n";

        TradeLogCollectService.CollectResult result =
                realPipeline().collectDetailed("default", text, "image");

        assertEquals(3, result.candidates().size(),
                "3 笔必须都留下（原实现：竖排 0 命中 + 去重吞并 → 只剩 1 笔）：" + result.candidates());
        assertEquals(3, result.candidates().stream().map(TradeLogCandidate::price).distinct().count(),
                "三笔价格各不相同，必须是三条候选");
    }

    @Test
    void collectDetailed_sameScreenshotTwice_deduplicates() {
        // 反向保护：同一张图重复上传（用户重传是常态）必须去重，否则候选每次翻倍。
        TradeLogCollectService svc = realPipeline();
        String text = "亨通光电\n600487\n68.270\n买入\n100\n6827.000\n13:08:59\n";

        svc.collectDetailed("default", text, "image");
        TradeLogCollectService.CollectResult second = svc.collectDetailed("default", text, "image");

        assertEquals(1, second.candidates().size(), "同图重传仍是一笔（价格一致 → 同笔）");
    }

    // ── 2026-09-18（P0-交易59）：同价同量分单被静默吞并 ──────────────────────────

    @Test
    void collectDetailed_samePriceSameVolumeSplitOrders_keepsAllSix() {
        // 用户 2026-09-18 生产截图（开源证券「当日成交」6 笔）——原文照抄自生产记录
        // records/2026/09/rec_20260918_210957579.md 的【图片文字】段（一词未改）：
        // 修复前：批内 sameTrade（同代码+同方向+同价+量差 ≤10%）把 000831 的第二笔吞掉
        // → 5 条候选、实际卖 400 股只记 200 股（少记 10660 元）。
        String text = "【图片文字】当日成交\n"
                + "开源证券 (****0888)\n"
                + "名称/代码 成交价/买卖 成交量/额 成交时间\n"
                + "广发证券 000776 20.410 买入 100 2041.000 10:10:00\n"
                + "风华高科 000636 56.670 买入 100 5667.000 10:08:20\n"
                + "风华高科 000636 56.360 买入 100 5636.000 10:04:25\n"
                + "中国稀土 000831 53.300 卖出 200 10660.000 10:04:09\n"
                + "风华高科 000636 56.270 买入 200 11254.000 10:03:55\n"
                + "中国稀土 000831 53.300 卖出 200 10660.000 10:03:44\n"
                + "【备注】今日成交\n";

        TradeLogCollectService.CollectResult result =
                realPipeline().collectDetailed("default", text, "image");

        assertEquals(6, result.candidates().size(),
                "6 笔成交必须全部成候选（原实现被吞成 5 条）：" + result.candidates());
        assertEquals(2, result.candidates().stream().filter(c -> "000831".equals(c.symbol())).count(),
                "000831 两笔卖出都要在（400 股不能只记 200 股）");
        assertEquals(3, result.candidates().stream().filter(c -> "000636".equals(c.symbol())).count(),
                "风华高科三笔价格各不同，必须三条");
        assertEquals(java.time.LocalTime.of(10, 3, 44),
                result.candidates().stream().filter(c -> java.time.LocalTime.of(10, 3, 44).equals(c.tradeTime()))
                        .findFirst().orElseThrow().tradeTime(),
                "成交时间要带进候选（10:03:44 与 10:04:09 才能分得开）");
    }

    @Test
    void collectDetailed_samePriceSameVolumeScreenshotTwice_deduplicates() {
        // 反向保护：同价同量分单保留之后，**同一张图重传**仍不能翻倍（批间按成交时间判同笔）。
        TradeLogCollectService svc = realPipeline();
        String text = "中国稀土 000831 53.300 卖出 200 10660.000 10:04:09\n"
                + "中国稀土 000831 53.300 卖出 200 10660.000 10:03:44\n";

        assertEquals(2, svc.collectDetailed("default", text, "image").candidates().size(), "图内两笔都要在");

        TradeLogCollectService.CollectResult second = svc.collectDetailed("default", text, "image");
        assertEquals(2, second.candidates().size(), "同图重传仍是两笔（成交时间一致 → 各自判重）");
    }

    @Test
    void confirm_coveredByAnchor_ledgerOnlyInsteadOfReject() {
        // 2026-09-18（P0-交易59）：命中券商快照锚定**不再硬拒**（原实现抛「成交日期已包含在券商
        // 快照中」，用户当天 6 笔 confirm 连点六次全拒，且锚定日是「≤」判定、只会更晚 → 永远补不回来）。
        // 现在：只落流水、不动持仓与现金（与历史成交导入 ledgerOnly 同语义）。
        repository.append("default", java.time.LocalDate.now(), new TradeLogCandidate(
                "000636", "风华高科", "BUY", new BigDecimal("56.27"), 200,
                java.time.LocalDate.now(), java.time.LocalTime.of(10, 3, 55), "image", true));
        assertEquals(1, service.todayCandidates("default").size());
        when(trading.isCoveredByAnchor(any(), any())).thenReturn(true);
        when(trading.ledgerOnlyTrade(any(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(true);

        TradeLogCollectService.ConfirmResult r = service.confirm("default");

        assertEquals(1, r.ledgerOnly(), "命中锚定 → 记进流水（只记账不改账）");
        assertEquals(0, r.failed(), "不再硬拒（失败 0 笔）");
        assertEquals(0, r.confirmed(), "持仓/现金没动，不计入已入账");
        assertTrue(service.todayCandidates("default").isEmpty(), "流水已留痕 → 候选不残留");
        verify(trading).ledgerOnlyTrade(eq("default"), eq("000636"), eq("风华高科"),
                eq(TradeDirection.BUY), eq(new BigDecimal("56.27")), eq(200),
                eq(java.time.LocalDate.now()), eq(java.time.LocalTime.of(10, 3, 55)), any(), any());
    }

    @Test
    void confirm_coveredByAnchor_ledgerWriteFails_keepsCandidate() {
        // 兜底写入失败 → 保留候选 + 如实报错（绝不静默吞）
        repository.append("default", java.time.LocalDate.now(), new TradeLogCandidate(
                "000636", "风华高科", "BUY", new BigDecimal("56.27"), 200,
                java.time.LocalDate.now(), java.time.LocalTime.of(10, 3, 55), "image", true));
        when(trading.isCoveredByAnchor(any(), any())).thenReturn(true);
        when(trading.ledgerOnlyTrade(any(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(false);

        TradeLogCollectService.ConfirmResult r = service.confirm("default");

        assertEquals(1, r.failed(), "写不进去要如实报错");
        assertEquals(1, service.todayCandidates("default").size(), "候选必须保留，不许静默吞");
    }

    // ── 2026-09-18（RFC 20260918 A1-4）：候选就地编辑 + 补日期不丢成交时间 ──

    @Test
    void updateFieldsById_editsPriceVolumeDirectionAndKeepsTradeTime() {
        // 截图识别错了只能丢弃重录 → 现在可就地改价格/数量/方向（A1-4）
        List<TradeLogCandidate> saved = repository.append("default", java.time.LocalDate.now(),
                new TradeLogCandidate("000831", "中国稀土", "BUY", new BigDecimal("53.30"), 200,
                        null, java.time.LocalTime.of(10, 3, 44), "image", true));
        String id = saved.get(0).id();

        boolean ok = service.updateFieldsById("default", id,
                new BigDecimal("53.30"), 300, "SELL", null, null);

        assertTrue(ok, "就地编辑应命中该候选");
        TradeLogCandidate c = service.todayCandidates("default").get(0);
        assertEquals(300, c.volume());
        assertEquals("SELL", c.direction());
        assertTrue(c.complete(), "改完仍是完整候选");
        assertEquals(java.time.LocalTime.of(10, 3, 44), c.tradeTime(),
                "改字段**不得抹掉成交时间**（同价同量分单靠它区分）");
    }

    @Test
    void updateFieldsById_incompleteToComplete_recomputesFlag() {
        // 缺数量的候选（complete=false，确认时会被跳过）→ 补上数量后应可确认
        List<TradeLogCandidate> saved = repository.append("default", java.time.LocalDate.now(),
                new TradeLogCandidate("000636", "风华高科", "BUY", new BigDecimal("56.27"), null,
                        java.time.LocalDate.now(), null, "image", false));
        String id = saved.get(0).id();
        assertFalse(service.todayCandidates("default").get(0).complete());

        assertTrue(service.updateFieldsById("default", id, null, 200, null, null, null));

        assertTrue(service.todayCandidates("default").get(0).complete(),
                "补全后 complete 必须重算，否则确认时仍被跳过");
    }

    @Test
    void updateTradeDateById_keepsTradeTime() {
        // 回归：2026-09-18 上午引入 tradeTime 时，四处重建候选的 update* 漏传 → 补日期会静默清空成交时间
        List<TradeLogCandidate> saved = repository.append("default", java.time.LocalDate.now(),
                new TradeLogCandidate("000831", "中国稀土", "SELL", new BigDecimal("53.30"), 200,
                        null, java.time.LocalTime.of(10, 4, 9), "image", true));
        String id = saved.get(0).id();

        assertTrue(repository.updateTradeDateById("default", java.time.LocalDate.now(), id,
                java.time.LocalDate.of(2026, 9, 18)));

        TradeLogCandidate c = service.todayCandidates("default").get(0);
        assertEquals(java.time.LocalDate.of(2026, 9, 18), c.tradeDate());
        assertEquals(java.time.LocalTime.of(10, 4, 9), c.tradeTime(), "补日期不得清掉成交时间");
    }
}
