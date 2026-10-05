package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.TradingPlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TradingPlanFileRepositoryTest — 操作计划文件仓储（RFC 20261003-trading-plan-and-review-loop §二）。
 *
 * <p>重点钉住**旧文件兼容**：{@code dayStatus} 是 P2-交易72（2026-10-05）才加的字段，
 * 此前落盘的 JSON 没有它。缺字段只能读作「没填」（{@link TradingPlan#DAY_STATUS_NONE}），
 * **不许猜**（猜成「今天没动」会替用户说一句他没说过的话，还会污染收盘对账）。
 */
class TradingPlanFileRepositoryTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final TradingPlanFileRepository repository = new TradingPlanFileRepository(storage);

    /**
     * 手写一份 **2026-10-05 之前格式**的计划 JSON（无 {@code dayStatus} 字段），读回来必须是「没填」。
     * 变异「缺字段默认猜成 NO_TRADE」在加本用例前全仓 24/24 全绿——旧格式在本仓没有任何样本。
     */
    @Test
    void find_legacyFileWithoutDayStatus_readsAsNotFilled_notGuessed() {
        storage.write("adai", "trading/plans/2026-09-20.json", """
                {
                  "date": "2026-09-20",
                  "note": "只做计划内的票",
                  "createdAt": "2026-09-20T21:00:00",
                  "items": [
                    {"id": "plan_1", "action": "SELL", "symbol": "600206", "name": "有研新材",
                     "condition": "跌破 45.5", "condOp": "LT", "condPrice": 45.5,
                     "quantity": 800, "text": "600206 跌破 45.5 清仓", "done": false}
                  ]
                }
                """);

        TradingPlan plan = repository.find("adai", LocalDate.of(2026, 9, 20)).orElseThrow();

        assertEquals(TradingPlan.DAY_STATUS_NONE, plan.dayStatus(),
                "旧文件缺 dayStatus = 没填，不得猜成「今天没动」（那会替用户说一句他没说过的话）");
        assertNotEquals(TradingPlan.DAY_STATUS_NO_TRADE, plan.dayStatus());
        assertEquals("只做计划内的票", plan.note());
        assertEquals(1, plan.items().size());
        assertEquals("600206", plan.items().get(0).symbol());
        assertEquals(0, plan.items().get(0).condPrice().compareTo(new BigDecimal("45.5")));
        assertEquals(LocalDate.of(2026, 9, 20), plan.date());
    }

    /** 新格式（含 dayStatus）写-读往返：存储层显式写出状态，不靠字段缺失表达「没填」。 */
    @Test
    void save_thenFind_roundTripsDayStatus() {
        LocalDate d = LocalDate.of(2026, 10, 12);
        repository.save("adai", new TradingPlan(d, List.of(), "空仓也是仓位",
                TradingPlan.DAY_STATUS_NO_TRADE, null));

        TradingPlan plan = repository.find("adai", d).orElseThrow();

        assertEquals(TradingPlan.DAY_STATUS_NO_TRADE, plan.dayStatus());
        assertTrue(storage.read("adai", "trading/plans/2026-10-12.json").contains("\"dayStatus\""),
                "状态必须显式落字段，别让「没填」与「没写」在下游混为一谈");
    }
}
