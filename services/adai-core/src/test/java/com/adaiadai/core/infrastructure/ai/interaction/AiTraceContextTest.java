package com.adaiadai.core.infrastructure.ai.interaction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * AiTraceContext — ThreadLocal 追踪上下文单元测试。
 */
class AiTraceContextTest {

    @AfterEach
    void tearDown() {
        AiTraceContext.restore(null); // 清理，防跨测试污染
    }

    @Test
    void unset_returnsNull() {
        assertNull(AiTraceContext.get());
    }

    @Test
    void set_thenGet_roundTrips() {
        AiTraceContext.set("adai", "rec_1", "card_2", "question");
        AiTraceContext.Trace t = AiTraceContext.get();
        assertEquals("adai", t.userId());
        assertEquals("rec_1", t.recordId());
        assertEquals("card_2", t.cardId());
        assertEquals("question", t.source());
    }

    @Test
    void restore_null_clears() {
        AiTraceContext.set("adai", "rec_1", null, "log");
        AiTraceContext.restore(null);
        assertNull(AiTraceContext.get());
    }

    @Test
    void restore_snapshot_revertsToPrevious() {
        // 装饰器快照-恢复语义：调用点 set → 装饰器读到 → finally 恢复进入前快照（非销毁）
        AiTraceContext.set("adai", "rec_1", null, "log");
        AiTraceContext.Trace snapshot = AiTraceContext.get();

        // 模拟装饰器中途覆盖（理论上不覆盖，但恢复语义必须成立）
        AiTraceContext.set("adai", "rec_2", "card_9", "other");
        AiTraceContext.restore(snapshot);

        AiTraceContext.Trace t = AiTraceContext.get();
        assertEquals("rec_1", t.recordId());
        assertEquals("log", t.source());
    }
}
