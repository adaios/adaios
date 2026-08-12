package com.adaiadai.core.infrastructure.ai.interaction;

import com.adaiadai.core.infrastructure.ai.vision.GlmVisualAiClient;
import com.adaiadai.core.infrastructure.ai.vision.ImageRequest;
import com.adaiadai.core.infrastructure.ai.vision.ImageUnderstanding;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LoggingVisualAiClient — 图片理解/追问打点单元测试（R1 AI 交互日志）。
 */
class LoggingVisualAiClientTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final AiInteractionLogger logger = new AiInteractionLogger(storage);
    private final GlmVisualAiClient delegate = mock(GlmVisualAiClient.class);
    private final LoggingVisualAiClient client = new LoggingVisualAiClient(delegate, logger);

    @AfterEach
    void tearDown() {
        AiTraceContext.restore(null);
    }

    @Test
    void understand_logsCaptionAndRecord() {
        when(delegate.understand(any())).thenReturn(
                new ImageUnderstanding("持仓截图", "trading", "浦发银行", List.of("持仓")));
        AiTraceContext.set("adai", "rec_img_1", null, "media");

        ImageUnderstanding r = client.understand(new ImageRequest("b64", "image/png", "今天加仓了"));

        assertEquals("持仓截图", r.summary());
        AiInteractionLog log = logger.readDay("adai", LocalDate.now()).get(0);
        assertEquals("visual.understand", log.kind());
        assertEquals("glm", log.model());
        assertEquals("media", log.scene());
        assertEquals("今天加仓了", log.prompt());
        assertEquals("rec_img_1", log.recordId());
        assertEquals("media", log.source());
        assertEquals("ok", log.status());
        assertEquals("summary=持仓截图 | category=trading | tags=[持仓]", log.responseSummary());
    }

    @Test
    void ask_logsQuestionAndAnswer() {
        when(delegate.ask(any(), eq("这张图显示什么"))).thenReturn("图中是浦发银行持仓");
        AiTraceContext.set("adai", "rec_img_2", null, "media");

        String answer = client.ask(new ImageRequest("b64", "image/png", null), "这张图显示什么");

        assertEquals("图中是浦发银行持仓", answer);
        AiInteractionLog log = logger.readDay("adai", LocalDate.now()).get(0);
        assertEquals("visual.ask", log.kind());
        assertEquals("这张图显示什么", log.prompt());
        assertEquals("rec_img_2", log.recordId());
    }
}
