package com.adaiadai.core.infrastructure.ai.interaction;

import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AiInteractionLogger — JSONL 落盘 + 读回单元测试。
 */
class AiInteractionLoggerTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final AiInteractionLogger logger = new AiInteractionLogger(storage, 30);

    private AiInteractionLog entry(String traceId, String kind) {
        return new AiInteractionLog(
                traceId, "2026-08-12T10:00:00", 100L, "adai",
                kind, "note", "rec_" + traceId, null, "record", "deepseek",
                "prompt-" + traceId, null, 50, "ok", null, 200, "summary=" + traceId);
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
                "完整 prompt 文本", "复盘模板指令", 123, "ok", null, 456, "summary=买入 | tags=[trading]");
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

    // ── #210 retention 过期清理 ──

    @Test
    void cleanupExpired_removesOldFiles_keepsRecent() {
        AiInteractionLogger logger2 = new AiInteractionLogger(storage, 2);
        storage.append("adai", "ai-logs/2026/01/ai-log-2026-01-01.jsonl", "old\n");
        storage.append("adai", "ai-logs/2026/01/ai-log-2026-01-31.jsonl", "recent\n");

        logger2.cleanupExpired("adai", LocalDate.of(2026, 2, 2)); // cutoff = 2026-01-31

        List<String> remaining = storage.listFiles("adai", "ai-logs");
        assertFalse(remaining.stream().anyMatch(p -> p.contains("2026-01-01")), "过期日志应被清理");
        assertTrue(remaining.stream().anyMatch(p -> p.contains("2026-01-31")), "保留期内日志应保留");
    }

    @Test
    void log_cleansExpiredFilesOnFirstWrite() {
        AiInteractionLogger logger2 = new AiInteractionLogger(storage, 2);
        storage.append("adai", "ai-logs/2020/01/ai-log-2020-01-01.jsonl", "very-old\n");

        logger2.log("adai", entry("t-new", "understand"));

        List<String> remaining = storage.listFiles("adai", "ai-logs");
        assertFalse(remaining.stream().anyMatch(p -> p.contains("2020-01-01")), "首次写入应触发过期清理");
        assertTrue(remaining.stream().anyMatch(p -> p.contains("ai-log-" + LocalDate.now())), "今日日志保留");
    }

    @Test
    void retentionDays_nonPositive_disablesCleanup() {
        AiInteractionLogger logger2 = new AiInteractionLogger(storage, 0);
        storage.append("adai", "ai-logs/2020/01/ai-log-2020-01-01.jsonl", "old\n");

        logger2.cleanupExpired("adai", LocalDate.now());

        assertTrue(storage.listFiles("adai", "ai-logs").stream()
                        .anyMatch(p -> p.contains("2020-01-01")),
                "retentionDays<=0 应保留全部日志");
    }

    // ── #210 分页读取 ──

    @Test
    void readDay_pagination_slicesByOffsetAndLimit() {
        logger.log("adai", entry("t1", "understand"));
        logger.log("adai", entry("t2", "understand"));
        logger.log("adai", entry("t3", "understand"));

        List<AiInteractionLog> page1 = logger.readDay("adai", LocalDate.now(), 0, 2);
        List<AiInteractionLog> page2 = logger.readDay("adai", LocalDate.now(), 2, 2);

        assertEquals(2, page1.size());
        assertEquals("t1", page1.get(0).traceId());
        assertEquals("t2", page1.get(1).traceId());
        assertEquals(1, page2.size());
        assertEquals("t3", page2.get(0).traceId());
        assertEquals(3, logger.countDay("adai", LocalDate.now()));
    }
}
