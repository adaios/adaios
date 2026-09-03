package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.storage.FileStorage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LotStopLossOverrideRepository 单元测试（2026-09-04 按批次止损批）。
 * 用真 InMemoryFileStorage 验证 set/remove/损坏降级/精度语义。
 */
class LotStopLossOverrideRepositoryTest {

    private static final String USER = "u";

    private LotStopLossOverrideRepository repo(FileStorage storage) {
        return new LotStopLossOverrideRepository(storage);
    }

    @Test
    void noFile_returnsEmpty() {
        LotStopLossOverrideRepository r = repo(new InMemoryFileStorage());
        assertTrue(r.findByUser(USER).isEmpty());
    }

    @Test
    void setThenFind_roundTrips() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        LotStopLossOverrideRepository r = repo(storage);
        r.setStopLoss(USER, "600519_20260901_B", new BigDecimal("1500.00"));
        r.setStopLoss(USER, "600519_INIT", new BigDecimal("1350.5"));

        Map<String, BigDecimal> all = r.findByUser(USER);
        assertEquals(2, all.size());
        assertEquals(0, new BigDecimal("1500").compareTo(all.get("600519_20260901_B")));
        assertEquals(0, new BigDecimal("1350.5").compareTo(all.get("600519_INIT")));
        // 文件可读（File First）
        assertTrue(storage.read(USER, "trading/lot-stoploss.json").contains("1500"));
    }

    @Test
    void setOverwrite_existingValueReplaced() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        LotStopLossOverrideRepository r = repo(storage);
        r.setStopLoss(USER, "600519_20260901_B", new BigDecimal("9.0"));
        r.setStopLoss(USER, "600519_20260901_B", new BigDecimal("8.55"));

        assertEquals(0, new BigDecimal("8.55").compareTo(r.findByUser(USER).get("600519_20260901_B")));
    }

    @Test
    void remove_deletesOnlyThatLot() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        LotStopLossOverrideRepository r = repo(storage);
        r.setStopLoss(USER, "A", new BigDecimal("1.0"));
        r.setStopLoss(USER, "B", new BigDecimal("2.0"));

        r.removeStopLoss(USER, "A");

        Map<String, BigDecimal> all = r.findByUser(USER);
        assertFalse(all.containsKey("A"));
        assertTrue(all.containsKey("B"));
        // 幂等：再删一次不炸
        r.removeStopLoss(USER, "A");
        assertEquals(1, r.findByUser(USER).size());
    }

    @Test
    void corruptedFile_returnsEmpty_failSafe() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        storage.write(USER, "trading/lot-stoploss.json", "{not-json");
        LotStopLossOverrideRepository r = repo(storage);
        assertTrue(r.findByUser(USER).isEmpty(), "损坏文件视为无覆盖（降级不坏）");
    }

    @Test
    void precision_storedAsPlainString_noScientificNotation() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        LotStopLossOverrideRepository r = repo(storage);
        r.setStopLoss(USER, "600000_20260803_B", new BigDecimal("1.2300E+2"));
        // 1.2300E+2 = 123 → 存 "123" 而非科学计数
        String raw = storage.read(USER, "trading/lot-stoploss.json");
        assertFalse(raw.contains("E"));
        assertEquals(0, new BigDecimal("123").compareTo(r.findByUser(USER).get("600000_20260803_B")));
    }
}
