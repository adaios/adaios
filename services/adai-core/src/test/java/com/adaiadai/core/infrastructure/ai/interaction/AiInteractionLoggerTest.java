package com.adaiadai.core.infrastructure.ai.interaction;

import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AiInteractionLogger — JSONL 落盘 + 读回单元测试。
 */
class AiInteractionLoggerTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final AiInteractionLogger logger = new AiInteractionLogger(storage);

    private AiInteractionLog entry(String traceId, String kind) {
        return new AiInteractionLog(
                traceId, "2026-08-12T10:00:00", 100L, "adai",
                kind, "note", "rec_" + traceId, null, "record", "deepseek",
                "prompt-" + traceId, 50, "ok", null, 200, "summary=" + traceId);
    }

    @Test
    void log_appendsMultipleLines_inOrder() {
        logger.log("adai", entry("t1", "understand"));
        logger.log("adai", entry("t2", "generate"));

        List<AiInteractionLog> logs = logger.readDay("adai", LocalDate.now());
        assertEquals(2, logs.size());
        assertEquals("t1", logs.get(0).traceId());
        assertEquals("generate", logs.get(1).kind());
        assertEquals("prompt-t2", logs.get(1).prompt());
    }

    @Test
    void readDay_noFile_returnsEmpty() {
        assertTrue(logger.readDay("adai", LocalDate.now()).isEmpty());
    }

    @Test
    void log_userIdSegregated() {
        logger.log("adai", entry("a1", "understand"));
        logger.log("other", entry("b1", "understand"));

        assertEquals(1, logger.readDay("adai", LocalDate.now()).size());
        assertEquals(1, logger.readDay("other", LocalDate.now()).size());
        assertEquals("b1", logger.readDay("other", LocalDate.now()).get(0).traceId());
    }

    @Test
    void log_nullEntry_skipped() {
        logger.log("adai", null);
        assertTrue(logger.readDay("adai", LocalDate.now()).isEmpty());
    }

    @Test
    void log_roundTripsFullRecord() {
        AiInteractionLog original = new AiInteractionLog(
                "tx", "2026-08-12T09:30:00", 500L, "adai",
                "understand", "trading", "rec_9", "card_1", "question", "deepseek",
                "完整 prompt 文本", 123, "ok", null, 456, "summary=买入 | tags=[trading]");
        logger.log("adai", original);

        AiInteractionLog read = logger.readDay("adai", LocalDate.now()).get(0);
        assertEquals("tx", read.traceId());
        assertEquals(500L, read.durationMs());
        assertEquals("trading", read.scene());
        assertEquals("rec_9", read.recordId());
        assertEquals("card_1", read.cardId());
        assertEquals("question", read.source());
        assertEquals(123, read.estimatedTokens());
        assertEquals("完整 prompt 文本", read.prompt());
        assertNotNull(read.ts());
    }
}
