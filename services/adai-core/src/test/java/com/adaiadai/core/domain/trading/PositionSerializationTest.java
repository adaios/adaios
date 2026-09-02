package com.adaiadai.core.domain.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Position JSON 序列化测试。
 * 回归：计算字段（marketValue/pnl/pnlPercent）此前不序列化，
 * 前端 fromJson 读不到恒为 0，导致盈亏明细全 0。
 */
class PositionSerializationTest {

    @Test
    void serializesComputedFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Position p = new Position("600123", "立昂微", 200,
                new BigDecimal("25.30"), new BigDecimal("26.60"), LocalDateTime.now());

        String json = mapper.writeValueAsString(p);

        assertTrue(json.contains("\"marketValue\":5320.0"), "应序列化市值: " + json);
        assertTrue(json.contains("\"pnl\":260.0"), "应序列化盈亏金额: " + json);
        assertTrue(json.contains("\"pnlPercent\":5.14"), "应序列化盈亏百分比: " + json);
    }

    @Test
    void serializesComputedStopLossFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Position p = new Position("600123", "立昂微", 200,
                new BigDecimal("25.30"), new BigDecimal("26.60"), LocalDateTime.now(),
                java.time.LocalDate.of(2026, 8, 1), new BigDecimal("23.00"), "B1", null,
                new BigDecimal("24.00"));

        String json = mapper.writeValueAsString(p);

        assertTrue(json.contains("\"stopLossPrice\":23.0"), "人工止损应序列化: " + json);
        assertTrue(json.contains("\"computedStopLossPrice\":24.0"), "计算止损应序列化: " + json);
        assertTrue(json.contains("\"effectiveStopLoss\":24.0"), "生效止损=max(人工,计算) 应序列化: " + json);
    }

    @Test
    void effectiveStopLoss_takesStricterOfManualAndComputed() {
        LocalDateTime now = LocalDateTime.now();
        // 人工更严（更高价）→ 生效 = 人工
        Position manualStricter = new Position("a", "A", 100, new BigDecimal("10"), new BigDecimal("10"), now,
                null, new BigDecimal("9.50"), null, null, new BigDecimal("9.00"));
        assertEquals(0, manualStricter.effectiveStopLoss().compareTo(new BigDecimal("9.50")));
        // 计算更严 → 生效 = 计算
        Position computedStricter = new Position("b", "B", 100, new BigDecimal("10"), new BigDecimal("10"), now,
                null, new BigDecimal("8.50"), null, null, new BigDecimal("9.00"));
        assertEquals(0, computedStricter.effectiveStopLoss().compareTo(new BigDecimal("9.00")));
        // 只有人工 → 生效 = 人工
        Position manualOnly = new Position("c", "C", 100, new BigDecimal("10"), new BigDecimal("10"), now,
                null, new BigDecimal("9.50"), null, null, null);
        assertEquals(0, manualOnly.effectiveStopLoss().compareTo(new BigDecimal("9.50")));
        // 只有计算 → 生效 = 计算
        Position computedOnly = new Position("d", "D", 100, new BigDecimal("10"), new BigDecimal("10"), now,
                null, null, null, null, new BigDecimal("9.00"));
        assertEquals(0, computedOnly.effectiveStopLoss().compareTo(new BigDecimal("9.00")));
        // 两者皆空 → null
        Position none = new Position("e", "E", 100, new BigDecimal("10"), new BigDecimal("10"), now,
                null, null, null, null, null);
        assertNull(none.effectiveStopLoss());
    }
}
