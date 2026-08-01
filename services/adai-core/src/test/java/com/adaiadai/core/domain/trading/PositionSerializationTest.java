package com.adaiadai.core.domain.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
}
