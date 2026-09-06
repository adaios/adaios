package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TradePsychologyService — 清仓情绪采集测试（RFC 20260905 P2 主观层）。
 * <p>
 * 覆盖：按交易结构生成提问（深亏/短持/长拿/多次买入）、回答回填 sold.psychology（追加式）、
 * 沉淀 profile.md 主观层、无画像文件不自动建。
 */
class TradePsychologyServiceTest {

    private SoldTrade sold(String symbol, double pnl, int holdDays, String tradeCount, String verdict) {
        return new SoldTrade(symbol, "测试票", LocalDate.now().minusDays(holdDays),
                LocalDate.now(), holdDays, tradeCount, pnl, verdict, "");
    }

    private TradePsychologyService service(InMemoryFileStorage storage, List<SoldTrade> sold) {
        SoldTradeRepository repo = mock(SoldTradeRepository.class);
        when(repo.findAll(anyString())).thenReturn(sold);
        return new TradePsychologyService(repo, storage);
    }

    @Test
    void questionsFor_deepLoss_asksTriggerAndStopMissing() {
        TradePsychologyService svc = service(new InMemoryFileStorage(), List.of());
        SoldTrade deep = sold("600584", -29.22, 12, "5+1", "扛单超 5%——按 R66 只输一根K线");
        List<TradePsychologyService.PsychologyQuestion> qs = svc.questionsFor(deep);

        assertFalse(qs.isEmpty());
        assertTrue(qs.size() <= 5, "最多 5 问");
        assertTrue(qs.stream().anyMatch(q -> q.key().equals("deep_loss_trigger")), "深亏问触发原因");
        assertTrue(qs.stream().anyMatch(q -> q.key().equals("stop_missing")), "深亏问止损缺失");
        assertTrue(qs.stream().anyMatch(q -> q.question().contains("29.22")), "问题应含实际亏损%");
    }

    @Test
    void questionsFor_shortLoss_asksWhyQuickExit() {
        TradePsychologyService svc = service(new InMemoryFileStorage(), List.of());
        SoldTrade shortLoss = sold("002131", -5.07, 5, "9+1", "短持仓亏损——按 R53 没涨=错");
        List<TradePsychologyService.PsychologyQuestion> qs = svc.questionsFor(shortLoss);

        assertTrue(qs.stream().anyMatch(q -> q.key().equals("exit_why_short")), "短亏问为什么快走");
        assertTrue(qs.stream().anyMatch(q -> q.key().equals("panic_or_plan")), "问恐慌还是计划");
        assertTrue(qs.stream().anyMatch(q -> q.key().equals("multi_buy")), "9 次买入问分批还是摊平");
    }

    @Test
    void questionsFor_longProfit_asksWhatHeldIt() {
        TradePsychologyService svc = service(new InMemoryFileStorage(), List.of());
        SoldTrade win = sold("000547", 224.14, 161, "11+9", "盈利了结");
        List<TradePsychologyService.PsychologyQuestion> qs = svc.questionsFor(win);

        assertTrue(qs.stream().anyMatch(q -> q.key().equals("profit_hold")), "长赢问拿住原因");
        assertTrue(qs.stream().anyMatch(q -> q.key().equals("profit_exit")), "长赢问卖出理由");
    }

    @Test
    void questionsFor_generalTrade_hasFallback() {
        TradePsychologyService svc = service(new InMemoryFileStorage(), List.of());
        // 不命中任何模板 → 兜底一问
        SoldTrade plain = sold("600000", 0.5, 10, "1+1", "盈利了结");
        List<TradePsychologyService.PsychologyQuestion> qs = svc.questionsFor(plain);
        assertFalse(qs.isEmpty());
        assertTrue(qs.get(0).key().equals("general"));
    }

    @Test
    void submitAnswer_updatesPsychologyAppendAndProfile() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        storage.write("default", "trading/profile.md", "# 画像\n\nS1 追高手大于抄底手。\n");
        // 真实 repo 行为：findAll 返回可变列表，saveAll 捕获写入
        SoldTradeRepository repo = mock(SoldTradeRepository.class);
        List<SoldTrade> current = new java.util.ArrayList<>(List.of(
                sold("600584", -29.22, 12, "5+1", "扛单超 5%")));
        when(repo.findAll(anyString())).thenReturn(current);
        org.mockito.ArgumentCaptor<List<SoldTrade>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        TradePsychologyService svc = new TradePsychologyService(repo, storage);

        boolean ok = svc.submitAnswer("default", "600584", "扛到受不了才割的，一直觉得会反弹");

        assertTrue(ok);
        org.mockito.Mockito.verify(repo).saveAll(anyString(), captor.capture());
        List<SoldTrade> updated = captor.getValue();
        SoldTrade t = updated.get(0);
        assertTrue(t.psychology().contains("扛到受不了才割"), "sold.psychology 应更新");
        // profile.md 沉淀
        String profile = storage.read("default", "trading/profile.md");
        assertTrue(profile.contains("600584"), "情绪应沉淀进画像");
        assertTrue(profile.contains("扛到受不了才割"), "画像应含回答内容");
    }

    @Test
    void submitAnswer_noProfileFile_doesNotCreate() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        TradePsychologyService svc = service(storage, List.of(
                sold("600584", -29.22, 12, "5+1", "扛单超 5%")));

        boolean ok = svc.submitAnswer("default", "600584", "测试回答");

        assertTrue(ok);
        assertEquals(null, storage.read("default", "trading/profile.md"),
                "无画像文件时不应自动创建（尊重用户是否启用画像）");
    }

    @Test
    void submitAnswer_unknownSymbol_returnsFalse() {
        TradePsychologyService svc = service(new InMemoryFileStorage(), List.of());
        assertFalse(svc.submitAnswer("default", "999999", "回答"));
    }

    @Test
    void submitAnswer_blank_returnsFalse() {
        TradePsychologyService svc = service(new InMemoryFileStorage(), List.of(
                sold("600584", -29.22, 12, "5+1", "扛单超 5%")));
        assertFalse(svc.submitAnswer("default", "600584", "  "));
    }

    @Test
    void submitAnswer_duplicateContent_noDoubleAppend() {
        // P2（2026-09-05 三官审）：重复 POST 同一内容 → 幂等，sold.psychology 不无限追加
        InMemoryFileStorage storage = new InMemoryFileStorage();
        SoldTradeRepository repo = mock(SoldTradeRepository.class);
        List<SoldTrade> current = new java.util.ArrayList<>(List.of(
                new SoldTrade("600584", "长电科技", java.time.LocalDate.now().minusDays(12),
                        java.time.LocalDate.now(), 12, "5+1", -29.22, "扛单超 5%", "")));
        when(repo.findAll(anyString())).thenReturn(current);
        org.mockito.ArgumentCaptor<List<SoldTrade>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        TradePsychologyService svc = new TradePsychologyService(repo, storage);

        svc.submitAnswer("default", "600584", "扛到受不了才割");
        svc.submitAnswer("default", "600584", "扛到受不了才割"); // 重复提交

        org.mockito.Mockito.verify(repo, org.mockito.Mockito.times(2)).saveAll(anyString(), captor.capture());
        List<SoldTrade> saved = captor.getValue();
        String psy = saved.get(0).psychology();
        long occurrences = psy.split("扛到受不了才割", -1).length - 1;
        assertEquals(1, occurrences, "重复内容不应重复追加，实际: " + psy);
    }

}
