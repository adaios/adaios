package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.AccountSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-交易69（2026-09-23）：现金健康度提示 + 账户视图字段完整性。
 * <p>
 * 背景（生产实据）：现金在两次「资金股份查询」导入之间会漂——09-11 导入真值 1,381.93 之后，
 * 系统 09-15 漂成 **−6,093.97**（负数）、09-23 漂到 **24,101.01**，而券商真值只有 **414.86**，
 * 虚高 23,686.15 → 总盈亏少报 2.37 万，而用户侧**看不到任何提示**（REVIEW P2-交易64/69）。
 */
class CashHealthNoteTest {

    private static AccountSnapshot snap(BigDecimal cash) {
        return new AccountSnapshot(new BigDecimal("106180.86"), cash, cash, cash,
                new BigDecimal("105766.00"), new BigDecimal("21074.85"), new BigDecimal("278.00"),
                new BigDecimal("150000"), LocalDate.of(2026, 9, 23), AccountSnapshot.SOURCE_CALC);
    }

    /** 负现金是「自证失败」的硬信号（生产 09-15 出现过 −6,093.97）→ 必须明说这个数不对。 */
    @Test
    void negativeCash_isCalledOutAsWrong() {
        String note = TradingAppService.cashHealthNote(new BigDecimal("-6093.97"),
                LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 23));

        assertNotNull(note, "负现金必须提示");
        assertTrue(note.contains("负数"), note);
        assertTrue(note.contains("资金股份查询"), "要给下一步动作，实际: " + note);
    }

    /** 从没导过资金查询（无券商来源）→ 说明这个数没有出处。 */
    @Test
    void noCashDate_saysNoBrokerSource() {
        String note = TradingAppService.cashHealthNote(new BigDecimal("100"), null, LocalDate.of(2026, 9, 23));

        assertNotNull(note);
        assertTrue(note.contains("券商来源"), note);
    }

    /** 超过阈值（7 天）→ 说清这是哪天的余额、距今多少天。 */
    @Test
    void staleCash_saysWhichDayAndHowOld() {
        String note = TradingAppService.cashHealthNote(new BigDecimal("414.86"),
                LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 23));

        assertNotNull(note);
        assertTrue(note.contains("2026-09-11"), note);
        assertTrue(note.contains("12 天前"), note);
    }

    /** 阈值内 + 非负 → 不打扰（每天弹「去导数据」会变成新的噪音）。 */
    @Test
    void freshCash_noNote() {
        assertNull(TradingAppService.cashHealthNote(new BigDecimal("414.86"),
                LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 23)), "当天导入不该提示");
        assertNull(TradingAppService.cashHealthNote(new BigDecimal("414.86"),
                LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 23)), "6 天 < 阈值 7 天，不该提示");
    }

    /** 契约：账户视图必须带全快照的每个字段 + cashDate/cashNote（防手工拼 Map 漏字段）。 */
    @Test
    void accountViewOf_carriesEverySnapshotFieldPlusCashFields() {
        Map<String, Object> m = TradingAppService.accountViewOf(
                snap(new BigDecimal("414.86")), LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 23));

        for (String k : new String[]{"assets", "cash", "available", "withdrawable", "marketValue",
                "pnl", "todayPnl", "principal", "snapshotDate", "todayPnlSource"}) {
            assertTrue(m.containsKey(k), "账户视图缺字段: " + k);
        }
        assertTrue(m.containsKey("cashDate"), m.keySet().toString());
        assertTrue(m.containsKey("cashNote"), m.keySet().toString());
        assertEquals("2026-09-23", m.get("cashDate"));
        assertNull(m.get("cashNote"), "当天导入的现金不该提示");
    }
}
