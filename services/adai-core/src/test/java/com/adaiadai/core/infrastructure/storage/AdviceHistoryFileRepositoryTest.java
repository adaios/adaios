package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.AdviceEntry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AdviceHistoryFileRepository — 建议留痕按月持久化测试（RFC 20260905 B①）。
 * <p>
 * 覆盖：append/findByMonth roundtrip、跨月隔离、symbol 近 N 天回查（B② 卖出回查数据源）、
 * 损坏文件拒写回防覆盖历史（对齐 MarketPushRepository B5-5/B6-1）。
 */
class AdviceHistoryFileRepositoryTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final AdviceHistoryFileRepository repo = new AdviceHistoryFileRepository(storage);

    private AdviceEntry entry(String id, LocalDate date, String symbol, String suggestion) {
        return new AdviceEntry(id, date, symbol, "测试票", suggestion,
                "理由引用 R66", List.of("R66"), true,
                new BigDecimal("12.50"), "manual-advice",
                date != null ? date.atTime(15, 0) : LocalDateTime.now());
    }

    @Test
    void append_findByMonth_roundtrip() {
        LocalDate d = LocalDate.of(2026, 9, 5);
        repo.append("default", entry("adv_1", d, "600584", "clear"));
        repo.append("default", entry("adv_2", d, "000725", "hold"));

        List<AdviceEntry> list = repo.findByMonth("default", d);
        assertEquals(2, list.size());
        assertEquals("clear", list.get(0).suggestion());
        assertEquals("R66", list.get(0).rules().get(0));
        assertTrue(list.get(0).hardVerdict());
        assertEquals("600584", list.get(0).symbol());
    }

    @Test
    void findByMonth_noFile_returnsEmpty() {
        assertTrue(repo.findByMonth("default", LocalDate.of(2026, 9, 1)).isEmpty());
    }

    @Test
    void append_monthsIsolated() {
        LocalDate m1 = LocalDate.of(2026, 8, 15);
        LocalDate m2 = LocalDate.of(2026, 9, 2);
        repo.append("default", entry("adv_1", m1, "600584", "clear"));
        repo.append("default", entry("adv_2", m2, "600584", "reduce"));

        assertEquals(1, repo.findByMonth("default", m1).size());
        assertEquals(1, repo.findByMonth("default", m2).size());
        assertEquals("reduce", repo.findByMonth("default", m2).get(0).suggestion());
    }

    @Test
    void findBySymbolRecent_filtersBySymbolAndWindow() {
        LocalDate today = LocalDate.now();
        repo.append("default", entry("adv_1", today.minusDays(1), "600584", "clear"));
        repo.append("default", entry("adv_2", today.minusDays(1), "000725", "hold"));
        repo.append("default", entry("adv_3", today.minusDays(20), "600584", "reduce"));

        // 近 10 天窗口：只命中 adv_1（600584）；adv_3 超窗、adv_2 不同票
        List<AdviceEntry> recent = repo.findBySymbolRecent("default", "600584", 10, LocalDate.now());
        assertEquals(1, recent.size());
        assertEquals("adv_1", recent.get(0).id());
    }

    @Test
    void append_brokenFile_keepsOriginal() {
        LocalDate d = LocalDate.of(2026, 9, 5);
        storage.write("default", "trading/advice-history/2026-09.json", "{{{ 半写文件");
        repo.append("default", entry("adv_1", d, "600584", "clear"));
        assertEquals("{{{ 半写文件", storage.read("default", "trading/advice-history/2026-09.json"),
                "损坏文件必须保留，不得覆盖历史");
    }
    @Test
    void entryId_generatedFromCreatedAt_includesMillis() {
        // P1-5（2026-09-05 三官深审）：同秒多条建议 id 不得重复（含毫秒）
        var t1 = java.time.LocalDateTime.of(2026, 9, 5, 14, 50, 30, 123_000_000);
        var t2 = java.time.LocalDateTime.of(2026, 9, 5, 14, 50, 30, 456_000_000);
        var e1 = new com.adaiadai.core.domain.trading.AdviceEntry(null, t1.toLocalDate(), "600584", "长电科技",
                "clear", null, List.of(), false, null, "manual-advice", t1);
        var e2 = new com.adaiadai.core.domain.trading.AdviceEntry(null, t2.toLocalDate(), "600584", "长电科技",
                "clear", null, List.of(), false, null, "manual-advice", t2);
        assertTrue(e1.id().startsWith("adv_"), "id 应有 adv_ 前缀");
        assertTrue(e1.id().contains("_123"), "id 应含毫秒段（t1=123ms）");
        assertTrue(!e1.id().equals(e2.id()), "同秒不同毫秒 → id 不得相同");
    }

    // ── RFC 20260922 A 批 A3：basis（依据快照）/ outcome（结果回填）的落盘往返 ──

    /**
     * **回归**：实体加了字段、writeAll/toEntry 漏改 → 「内存里有、读回来没有」。
     * 2026-09-22 自查实锤过一次（basis 第一版就没落盘），所以这条必须钉住。
     */
    @Test
    void roundTrip_basisAndOutcome_survivePersistence() {
        LocalDate d = LocalDate.of(2026, 9, 5);
        repo.append("default", new AdviceEntry("adv_1", d, "600584", "长电科技", "clear",
                "理由", List.of("R66"), false, new BigDecimal("12.5"), "manual-advice",
                d.atTime(15, 0), "{\"price\":\"12.50\"}", "{\"afterDays\":5,\"pct\":3.2}"));

        AdviceEntry back = repo.findByMonth("default", d).get(0);
        assertEquals("{\"price\":\"12.50\"}", back.basis(), "basis 必须真的落盘（不是只在内存里）");
        assertEquals("{\"afterDays\":5,\"pct\":3.2}", back.outcome());
    }

    /** 缺省（老文件 / 尚未回填）→ 读作 **null**，不返回空串（「没有」与「空」要能区分）。 */
    @Test
    void roundTrip_missingBasisAndOutcome_readAsNull() {
        LocalDate d = LocalDate.of(2026, 9, 5);
        repo.append("default", entry("adv_1", d, "600584", "clear"));

        AdviceEntry back = repo.findByMonth("default", d).get(0);
        assertNull(back.basis());
        assertNull(back.outcome());
    }

    /** 回填：按 id 就地写（其余条目不动）+ **幂等**（已有 outcome 不覆盖——回填只写一次）。 */
    @Test
    void updateOutcome_writesOnce_andDoesNotOverwrite() {
        LocalDate d = LocalDate.of(2026, 9, 5);
        repo.append("default", entry("adv_1", d, "600584", "clear"));
        repo.append("default", entry("adv_2", d, "000725", "hold"));

        assertTrue(repo.updateOutcome("default", d, "adv_1", "{\"first\":true}"));
        List<AdviceEntry> list = repo.findByMonth("default", d);
        assertEquals("{\"first\":true}", list.get(0).outcome());
        assertNull(list.get(1).outcome(), "别的条目不受影响");
        assertEquals("hold", list.get(1).suggestion(), "原字段原样保留");

        assertTrue(repo.updateOutcome("default", d, "adv_1", "{\"second\":true}"));
        assertEquals("{\"first\":true}", repo.findByMonth("default", d).get(0).outcome(),
                "已有结果不得被二次回填覆盖");
    }

    /** 找不到该条目 → false（调用方按「下次再试」处理，**不静默当成功**）。 */
    @Test
    void updateOutcome_unknownId_returnsFalse() {
        LocalDate d = LocalDate.of(2026, 9, 5);
        repo.append("default", entry("adv_1", d, "600584", "clear"));
        assertFalse(repo.updateOutcome("default", d, "nope", "{}"));
    }
}
