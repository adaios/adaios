package com.adaiadai.core.application;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TradingAppService#normalizeAnchorDate} — 快照锚定日归一化（2026-09-18，P0-交易59）。
 *
 * <p>背景：通达信「持仓股 / 资金股份查询」文件名里的日期是**导出日**，内容是最近一个**已收盘
 * 交易日**的状态。用户凌晨导出时，导出日 = 今天、数据基准 = 上一交易日——直接拿导出日当锚定日
 * 等于把「今天」提前锚定：当天盘中成交全被 {@code coveredByAnchor} 判成「已含在快照内」而拒绝
 * （生产实据：2026-09-18 凌晨 00:24 导入持仓快照 → 当天 6 笔成交 confirm 连点六次全拒；
 * 而该判定是「≤ 锚定日」且锚定日只增不减 → 这笔成交**永远补不回来**）。
 *
 * <p>只动「快照日期 == 今天」这一种；补导历史快照一律按文件日期（2026-09-12 的语义不回归）。
 */
class TradingAnchorDateNormalizeTest {

    private static final LocalDate FRIDAY = LocalDate.of(2026, 9, 18);      // 交易日（周五）
    private static final LocalDate SATURDAY = LocalDate.of(2026, 9, 19);    // 非交易日
    private static final LocalDate PREV_TRADING_DAY = LocalDate.of(2026, 9, 17);

    /** 交易日盘前（09:30 前）导出 → 数据基准取上一交易日（本次修复点）。 */
    @Test
    void beforeOpen_onTradingDay_usesPreviousTradingDay() {
        assertEquals(PREV_TRADING_DAY,
                TradingAppService.normalizeAnchorDate(FRIDAY, FRIDAY, LocalTime.of(0, 24)),
                "凌晨导入的持仓快照，数据基准是上一交易日，不能把今天提前锚定");
    }

    /** 交易日盘中/盘后导出 → 数据已含当日成交，锚定日保持今天。 */
    @Test
    void afterOpen_onTradingDay_keepsToday() {
        assertEquals(FRIDAY,
                TradingAppService.normalizeAnchorDate(FRIDAY, FRIDAY, LocalTime.of(16, 0)));
        assertEquals(FRIDAY,
                TradingAppService.normalizeAnchorDate(FRIDAY, FRIDAY, LocalTime.of(10, 0)),
                "盘中导入的是实时状态，锚定今天是对的");
    }

    /** 非交易日（周六）白天导出 → 数据基准是上一交易日（周五）。 */
    @Test
    void weekend_usesPreviousTradingDay() {
        assertEquals(FRIDAY,
                TradingAppService.normalizeAnchorDate(SATURDAY, SATURDAY, LocalTime.of(10, 0)));
    }

    /** 补导历史快照（文件日期 ≠ 今天）→ 一律按文件日期，不做归一化。 */
    @Test
    void historicalSnapshot_keepsFileDate() {
        LocalDate fileDate = LocalDate.of(2026, 9, 11);
        assertEquals(fileDate,
                TradingAppService.normalizeAnchorDate(fileDate, FRIDAY, LocalTime.of(0, 24)),
                "补导几天前的快照：锚定日按文件日期，不能被改写成今天");
    }

    /**
     * 未传快照日期（粘贴导入／前端没给日期）→ **也要走同一套归一化**（P1-2，2026-09-19 后端审查）：
     * 凌晨粘贴导入的锚定日必须退到上一交易日，否则当天成交全被降级为「只记流水」、持仓整日不动。
     */
    @Test
    void nullSnapshotDate_alsoNormalizedBeforeOpen() {
        assertEquals(PREV_TRADING_DAY,
                TradingAppService.normalizeAnchorDate(null, FRIDAY, LocalTime.of(0, 24)),
                "粘贴导入没有文件日期，也不能把「今天」提前锚定");
        assertEquals(FRIDAY,
                TradingAppService.normalizeAnchorDate(null, FRIDAY, LocalTime.of(16, 0)),
                "盘后导入的数据已含当日成交，锚今天是对的");
    }
}
