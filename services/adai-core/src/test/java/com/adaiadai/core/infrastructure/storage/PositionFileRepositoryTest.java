package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PositionFileRepository — 持仓文件存储（RFC 20260816 §2.2）单元测试。
 * 覆盖：新列 entryDate/stopLoss/buyPoint/role 写入与读回、旧文件无新列兜底 null 不报错、cashBalance 保留。
 */
class PositionFileRepositoryTest {

    private InMemoryFileStorage fileStorage;
    private PositionFileRepository repository;

    @BeforeEach
    void setUp() {
        fileStorage = new InMemoryFileStorage();
        repository = new PositionFileRepository(fileStorage);
    }

    private Position pos(String symbol, String entryDate, String stopLoss, String buyPoint, String role) {
        return new Position(symbol, "名称" + symbol, 100, new BigDecimal("10.00"), new BigDecimal("10.50"),
                LocalDateTime.now(),
                entryDate != null ? LocalDate.parse(entryDate) : null,
                stopLoss != null ? new BigDecimal(stopLoss) : null,
                buyPoint, role);
    }

    @Test
    void saveAllAndFindAll_withNewColumns_roundTrip() {
        // 预置旧文件（带手工 cashBalance），验证 saveAll 后：新列写入、cashBalance 保留（#138）
        fileStorage.write("default", "trading/positions.md", """
                # 当前持仓

                | symbol | name | quantity | avgCost | currentPrice |
                |--------|------|----------|---------|--------------|
                | 600000 | 浦发银行 | 100 | 10.00 | 10.50 |

                cashBalance: 50000
                lastUpdated: 2026-07-12T11:30:00
                """);
        Position p = pos("600000", "2026-08-01", "9.50", "B1", "防守");

        repository.saveAll("default", List.of(p));

        List<Position> loaded = repository.findAll("default");
        assertEquals(1, loaded.size());
        Position back = loaded.get(0);
        assertEquals("600000", back.symbol());
        assertEquals(LocalDate.of(2026, 8, 1), back.entryDate(), "entryDate 应读回");
        assertEquals(0, new BigDecimal("9.5").compareTo(back.stopLossPrice()),
                "stopLoss 应读回（toMarkdown 剥尾零 → 9.5）");
        assertEquals("B1", back.buyPoint(), "buyPoint 应读回");
        assertEquals("防守", back.role(), "role 应读回");
        // cashBalance 手工值保留（#138 回归）
        assertEquals(0, new BigDecimal("50000").compareTo(repository.cashBalance("default")));
    }

    @Test
    void saveAll_withNullPlanFields_writesEmptyColumns() {
        Position p = new Position("600000", "浦发银行", 100, new BigDecimal("10.0"), new BigDecimal("10.5"),
                LocalDateTime.now());

        repository.saveAll("default", List.of(p));

        // 缺省列写空（不是 null 字面量），文件保持人类可读表格
        String content = fileStorage.read("default", "trading/positions.md");
        assertNotNull(content);
        assertTrue(content.contains("entryDate"), "表头应含 entryDate 列");
        assertTrue(content.contains("stopLoss"), "表头应含 stopLoss 列");
        assertTrue(content.contains("buyPoint"), "表头应含 buyPoint 列");
        assertTrue(content.contains("role"), "表头应含 role 列");
        Position loaded = repository.findAll("default").get(0);
        assertNull(loaded.entryDate(), "空列兜底 null");
        assertNull(loaded.stopLossPrice());
        assertNull(loaded.buyPoint());
        assertNull(loaded.role());
    }

    @Test
    void oldFileWithoutNewColumns_fallsBackToNull() {
        // freeze MINOR：旧 5 列 positions.md 无新列 → 解析兜底 null 不报错
        fileStorage.write("default", "trading/positions.md", """
                # 当前持仓

                | symbol | name | quantity | avgCost | currentPrice |
                |--------|------|----------|---------|--------------|
                | 600000 | 浦发银行 | 100 | 10.00 | 10.50 |

                cashBalance: 50000
                lastUpdated: 2026-07-12T11:30:00
                """);

        List<Position> loaded = repository.findAll("default");

        assertEquals(1, loaded.size(), "旧文件应正常解析");
        Position p = loaded.get(0);
        assertEquals("600000", p.symbol());
        assertEquals(100, p.quantity());
        assertNull(p.entryDate(), "旧文件无 entryDate 列 → null");
        assertNull(p.stopLossPrice(), "旧文件无 stopLoss 列 → null");
        assertNull(p.buyPoint(), "旧文件无 buyPoint 列 → null");
        assertNull(p.role(), "旧文件无 role 列 → null");
        assertEquals(0, new BigDecimal("50000").compareTo(repository.cashBalance("default")), "cashBalance 保留");
    }

    @Test
    void oldFile_partialNewColumns_parseIndependently() {
        // 部分新列（只有 entryDate/stopLoss，无 buyPoint/role）→ 有则解析，缺则 null
        fileStorage.write("default", "trading/positions.md", """
                # 当前持仓

                | symbol | name | quantity | avgCost | currentPrice | entryDate | stopLoss |
                |--------|------|----------|---------|--------------|-----------|----------|
                | 600000 | 浦发银行 | 100 | 10.00 | 10.50 | 2026-08-01 | 9.50 |

                cashBalance: 1000
                lastUpdated: 2026-08-01T09:30:00
                """);

        Position p = repository.findAll("default").get(0);

        assertEquals(LocalDate.of(2026, 8, 1), p.entryDate(), "有列则解析");
        assertEquals(new BigDecimal("9.50"), p.stopLossPrice(), "有列则解析");
        assertNull(p.buyPoint(), "缺列 → null");
        assertNull(p.role(), "缺列 → null");
    }

    @Test
    void malformedNewColumn_fallsBackToNull() {
        // 新列值坏（entryDate 非日期）→ 该列兜底 null，整行不丢弃
        fileStorage.write("default", "trading/positions.md", """
                # 当前持仓

                | symbol | name | quantity | avgCost | currentPrice | entryDate | stopLoss | buyPoint | role |
                |--------|------|----------|---------|--------------|-----------|----------|----------|------|
                | 600000 | 浦发银行 | 100 | 10.00 | 10.50 | 不是日期 | 9.50 | B1 | 防守 |

                cashBalance: 1000
                lastUpdated: 2026-08-01T09:30:00
                """);

        Position p = repository.findAll("default").get(0);

        assertEquals("600000", p.symbol(), "坏列不丢行");
        assertNull(p.entryDate(), "坏日期列 → null 兜底");
        assertEquals(new BigDecimal("9.50"), p.stopLossPrice(), "其余列正常解析");
        assertEquals("B1", p.buyPoint());
    }
}
