package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.CashAdjustment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CashAdjustmentFileRepository — 对账调整落盘（RFC 20261003 C4 + D3「独立文件」，2026-10-03）。
 *
 * <p>它让「系统现金 = 上次快照值 + Σ已计入流水 + Σ调整」这条恒等式**成立且可查**——
 * 原实现静默覆盖，差额被抹掉、不留痕。
 */
class CashAdjustmentFileRepositoryTest {

    private InMemoryFileStorage fs;
    private CashAdjustmentFileRepository repo;

    @BeforeEach
    void setUp() {
        fs = new InMemoryFileStorage();
        repo = new CashAdjustmentFileRepository(fs);
    }

    private CashAdjustment adj(String id, String date, String amount, String reason) {
        return new CashAdjustment(id, LocalDate.parse(date), new BigDecimal(amount), reason,
                "覆盖前系统 500 → 券商 900", LocalDateTime.of(2026, 10, 8, 16, 0));
    }

    @Test
    void appendAndFindAll_roundTrip() {
        repo.append("default", adj("adj_1", "2026-10-08", "400.00", "未记录股息"));
        repo.append("default", adj("adj_2", "2026-10-09", "-25.50", "费用口径差"));

        List<CashAdjustment> all = repo.findAll("default");

        assertEquals(2, all.size());
        // 倒序（最新在前）
        assertEquals("2026-10-09", all.get(0).date().toString());
        assertEquals(0, all.get(0).amount().compareTo(new BigDecimal("-25.50")));
        assertEquals("费用口径差", all.get(0).reason());
        assertEquals("覆盖前系统 500 → 券商 900", all.get(0).note());
    }

    @Test
    void total_sumsAllAdjustments() {
        repo.append("default", adj("adj_1", "2026-10-08", "400.00", "x"));
        repo.append("default", adj("adj_2", "2026-10-09", "-25.50", "y"));

        assertEquals(0, repo.total("default").compareTo(new BigDecimal("374.50")));
    }

    @Test
    void emptyFile_totalIsZero() {
        assertEquals(0, repo.total("default").compareTo(BigDecimal.ZERO));
        assertTrue(repo.findAll("default").isEmpty());
    }

    @Test
    void corruptedFile_degradesToEmpty_notThrow() {
        fs.write("default", "trading/cash-adjustments.json", "{not json");

        assertTrue(repo.findAll("default").isEmpty(), "读坏不炸、按无调整处理");
    }

    @Test
    void perUserIsolation() {
        repo.append("adai", adj("adj_1", "2026-10-08", "400.00", "x"));
        assertTrue(repo.findAll("other").isEmpty(), "调整按用户隔离");
    }
}
