package com.adaiadai.core.infrastructure.ai.interaction;

import com.adaiadai.core.kernel.ai.AiUnderstanding;
import com.adaiadai.core.infrastructure.ai.llm.DeepSeekAiClient;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LoggingAiClient — 装饰器打点单元测试（R1 AI 交互日志）。
 */
class LoggingAiClientTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final AiInteractionLogger logger = new AiInteractionLogger(storage, 30);
    private final DeepSeekAiClient delegate = mock(DeepSeekAiClient.class);
    private final LoggingAiClient client = new LoggingAiClient(delegate, logger);

    @AfterEach
    void tearDown() {
        AiTraceContext.restore(null);
    }

    private ContextPackage ctx() {
        return ContextPackage.simple("trading", "身份摘要", "标题", "内容", List.of("a", "b"),
                "完整组装 prompt：处理一条新记录。");
    }

    private AiUnderstanding understanding() {
        return new AiUnderstanding("买入记录", "insight 内容", null, null,
                List.of("trading"), "neutral", "trading", false, null, "原始回复全文");
    }

    @Test
    void understand_forwardsAndLogs() {
        when(delegate.understand(any())).thenReturn(understanding());
        AiTraceContext.set("adai", "rec_7", "card_3", "question");

        AiUnderstanding result = client.understand(ctx());

        // 转发结果
        assertEquals("买入记录", result.summary());

        // 落一条日志：kind/scene/prompt 全文/关联锚点/status
        AiInteractionLog log = logger.readDay("adai", LocalDate.now()).get(0);
        assertEquals("understand", log.kind());
        assertEquals("trading", log.scene());
        assertTrue(log.prompt().contains("完整组装 prompt"));
        assertEquals("rec_7", log.recordId());
        assertEquals("card_3", log.cardId());
        assertEquals("question", log.source());
        assertEquals("deepseek", log.model());
        assertEquals("ok", log.status());
        assertNotNull(log.traceId());
        assertNotNull(log.durationMs());
    }

    @Test
    void understand_returnsTraceToPreviousSnapshot() {
        when(delegate.understand(any())).thenReturn(understanding());
        AiTraceContext.set("adai", "rec_1", null, "log");

        client.understand(ctx());

        // 调用后恢复进入前快照（调用点可继续复用同一锚点）
        assertEquals("rec_1", AiTraceContext.get().recordId());
    }

    @Test
    void generate_logsContent() {
        when(delegate.generate(any(), any())).thenReturn("生成的复盘正文");
        AiTraceContext.set("adai", "rec_8", null, "trading_review");

        String out = client.generate(ctx(), "复盘模板");

        assertEquals("生成的复盘正文", out);
        AiInteractionLog log = logger.readDay("adai", LocalDate.now()).get(0);
        assertEquals("generate", log.kind());
        assertEquals("trading_review", log.source());
        assertTrue(log.responseSummary().contains("生成的复盘正文"));
        // #231：generate 记录自定义 system 指令（复盘模板），understand/intent 为 null
        assertEquals("复盘模板", log.systemPrompt());
        assertNotNull(log.systemPrompt());
    }

    @Test
    void recognizeIntent_logsContent() {
        when(delegate.recognizeIntent("我想记录一下")).thenReturn("log");
        AiTraceContext.set("adai", null, null, "intent");

        String out = client.recognizeIntent("我想记录一下");

        assertEquals("log", out);
        AiInteractionLog log = logger.readDay("adai", LocalDate.now()).get(0);
        assertEquals("recognizeIntent", log.kind());
        assertEquals("intent", log.scene());
        assertEquals("我想记录一下", log.prompt());
    }

    @Test
    void understand_error_logsErrorAndRethrows() {
        when(delegate.understand(any())).thenThrow(new RuntimeException("API 超时"));
        AiTraceContext.set("adai", "rec_9", null, "record");

        assertThrows(RuntimeException.class, () -> client.understand(ctx()));

        AiInteractionLog log = logger.readDay("adai", LocalDate.now()).get(0);
        assertEquals("error", log.status());
        assertTrue(log.error().contains("API 超时"));
    }

    @Test
    void understand_noTrace_usesDefaultUser() {
        when(delegate.understand(any())).thenReturn(understanding());
        client.understand(ctx());

        AiInteractionLog log = logger.readDay("default", LocalDate.now()).get(0);
        assertEquals("default", log.userId());
        assertNull(log.recordId());
    }
}
