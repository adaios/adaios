package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.SnapshotAnchor;
import com.adaiadai.core.domain.trading.SnapshotHolding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TradingAnchorFileRepositoryTest — 券商快照锚定文件（trading/snapshot-anchor.json，2026-09-12 账实一致性批）。
 * 覆盖：锚定日/基线的读写回环、**锚定日只前进不后退**、更新锚定日不得抹掉持仓基线、
 * 损坏文件 → 空锚定（调用方据此 fail-closed）。
 */
class TradingAnchorFileRepositoryTest {

    private static final String USER = "u1";

    private InMemoryFileStorage storage;
    private TradingAnchorFileRepository repo;

    @BeforeEach
    void setUp() {
        storage = new InMemoryFileStorage();
        repo = new TradingAnchorFileRepository(storage);
    }

    @Test
    void emptyWhenFileMissing() {
        assertEquals(SnapshotAnchor.empty(), repo.find(USER));
        assertFalse(repo.find(USER).known());
        assertTrue(repo.holdings(USER).isEmpty());
    }

    @Test
    void roundTripDatesAndHoldings() {
        LocalDate d = LocalDate.of(2026, 9, 9);
        repo.updatePositionsReplace(USER, d);
        repo.updateCashImport(USER, d);
        repo.recordHoldings(USER, List.of(
                new SnapshotHolding("600206", "有研新材", 600),
                new SnapshotHolding("603113", "金能科技", 1900)));

        SnapshotAnchor a = repo.find(USER);
        assertTrue(a.known());
        assertEquals(d, a.positionsReplace());
        assertEquals(d, a.cashImport());
        assertEquals(d, a.latest());
        List<SnapshotHolding> h = repo.holdings(USER);
        assertEquals(2, h.size());
        assertEquals(600, h.get(0).quantity());
        assertEquals("有研新材", h.get(0).name());
    }

    /** 补导几天前的旧快照文件：锚定日只前进不后退（否则锚定日之后、快照之前的成交会被误判为已含在快照内）。 */
    @Test
    void anchorDate_neverMovesBackwards() {
        repo.updatePositionsReplace(USER, LocalDate.of(2026, 9, 9));
        repo.updatePositionsReplace(USER, LocalDate.of(2026, 9, 4));
        assertEquals(LocalDate.of(2026, 9, 9), repo.find(USER).positionsReplace());

        repo.updateCashImport(USER, LocalDate.of(2026, 9, 10));
        repo.updateCashImport(USER, LocalDate.of(2026, 8, 1));
        assertEquals(LocalDate.of(2026, 9, 10), repo.find(USER).cashImport());
        assertEquals(LocalDate.of(2026, 9, 10), repo.find(USER).latest());
    }

    /** 回归：资金股份导入更新锚定日时不得抹掉持仓基线（旧实现传 null → 对账永远「无法判定」）。 */
    @Test
    void updateCashImport_keepsExistingHoldings() {
        repo.updatePositionsReplace(USER, LocalDate.of(2026, 9, 9));
        repo.recordHoldings(USER, List.of(new SnapshotHolding("600206", "有研新材", 600)));

        repo.updateCashImport(USER, LocalDate.of(2026, 9, 9));

        assertEquals(1, repo.holdings(USER).size(), "持仓基线必须保留");
        assertEquals(LocalDate.of(2026, 9, 9), repo.find(USER).cashImport());
    }

    @Test
    void corruptedFile_degradesToEmptyAnchor() {
        storage.write(USER, "trading/snapshot-anchor.json", "{ this is not json");
        assertEquals(SnapshotAnchor.empty(), repo.find(USER));
        assertFalse(repo.find(USER).known(), "损坏 → 未知锚定 → 调用方 fail-closed");
        assertTrue(repo.holdings(USER).isEmpty());
    }

    /** 「记录了但为空」（全现金账户）与「从未记录」必须可区分，否则对账被误判为无法判定。 */
    @Test
    void holdingsRecorded_distinguishesEmptyBaselineFromNeverRecorded() {
        repo.updatePositionsReplace(USER, LocalDate.of(2026, 9, 9));
        assertFalse(repo.holdingsRecorded(USER), "只写锚定日不算记录过基线");

        repo.recordHoldings(USER, List.of());
        assertTrue(repo.holdingsRecorded(USER), "记录过空基线 = 全现金账户，对账应可判定");
        assertTrue(repo.holdings(USER).isEmpty());
    }

    @Test
    void holdingsOnlyWriteKeepsDates() {
        repo.updatePositionsReplace(USER, LocalDate.of(2026, 9, 9));
        repo.recordHoldings(USER, List.of());

        assertEquals(LocalDate.of(2026, 9, 9), repo.find(USER).positionsReplace());
        assertTrue(repo.holdings(USER).isEmpty(), "空列表 = 快照当日无持仓（清仓态），会覆盖旧基线");
    }
}
