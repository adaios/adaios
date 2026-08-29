package com.adaiadai.core.application;

import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.ai.AiUnderstanding;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.kernel.context.engine.ContextEngine;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QuestionAppService — 问答写卡测试。
 * <p>
 * REVIEW #13/#11：AI 原始回复（自然语言 + JSON）写 card 与返回前端前必须剥离 JSON，
 * 保证实时显示与刷新后 parseTurns 一致，card 文件不混入游离 JSON 块。
 */
class QuestionAppServiceTest {

    private ContextEngine contextEngine;
    private CardFileRepository cardRepository;
    private RecordRepository recordRepository;
    private MemoryService memoryService;
    private AiClient aiClient;
    private com.adaiadai.core.kernel.ai.StreamingAiClient streamingAiClient;
    private QuestionAppService service;

    private static final String CARD_ID = "card_1";

    @BeforeEach
    void setUp() {
        contextEngine = mock(ContextEngine.class);
        cardRepository = mock(CardFileRepository.class);
        recordRepository = mock(RecordRepository.class);
        memoryService = mock(MemoryService.class);
        aiClient = mock(AiClient.class);
        streamingAiClient = mock(com.adaiadai.core.kernel.ai.StreamingAiClient.class);
        PluginService pluginService = mock(PluginService.class);
        // D5 gateDomain 透传原值（本测试不覆盖 domain 收敛）
        when(pluginService.gateDomain(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        service = new QuestionAppService(contextEngine, cardRepository, recordRepository, memoryService,
                aiClient, streamingAiClient, pluginService);

        when(contextEngine.compose(any(), any(), any(), any())).thenReturn(mock(ContextPackage.class));
        CardRecord existing = new CardRecord(
                CARD_ID, "conversation", "active",
                List.of(), List.of(new CardRecord.Turn(true, "用户提问", "09:00")), null,
                LocalDateTime.now(), LocalDateTime.now());
        when(cardRepository.findById(any(), any())).thenReturn(Optional.of(existing));
    }

    private ContentRecord record(String id, String content) {
        return new ContentRecord(
                id, "note", "user_input", "标题", content, List.of(),
                LocalDateTime.now(), "question", null, "life");
    }

    @Test
    void answer_cardTurn_stripsJsonFromRawResponse() {
        String raw = "自然语言回复内容\n\n{\"summary\":\"摘要\",\"tags\":[\"日常\"],\"sentiment\":\"neutral\"}";
        when(aiClient.understand(any())).thenReturn(new AiUnderstanding(
                "摘要", null, null, null, List.of("日常"), "neutral", "life", false, null, raw));

        service.answer("default", record("rec_1", "用户提问"), CARD_ID);

        ArgumentCaptor<CardRecord> captor = ArgumentCaptor.forClass(CardRecord.class);
        verify(cardRepository).save(any(), captor.capture());
        CardRecord saved = captor.getValue();
        assertEquals(2, saved.turns().size());
        assertEquals("自然语言回复内容", saved.turns().get(1).text(),
                "card AI turn 应剥离 JSON，只写自然语言（REVIEW #13/#11）");
    }

    @Test
    void answer_returnRawResponse_stripsJson() {
        String raw = "自然语言回复内容\n\n{\"summary\":\"摘要\",\"tags\":[\"日常\"],\"sentiment\":\"neutral\"}";
        when(aiClient.understand(any())).thenReturn(new AiUnderstanding(
                "摘要", null, null, null, List.of("日常"), "neutral", "life", false, null, raw));

        QuestionAppService.AnswerResult result = service.answer("default", record("rec_1", "用户提问"), CARD_ID);

        assertEquals("自然语言回复内容", result.rawResponse(),
                "返回前端实时显示的回复应剥离 JSON，与刷新后一致");
    }

    @Test
    void answer_pureJsonResponse_fallsBackToSummary() {
        // 整段都是 JSON（无自然语言）时 card turn 回退 summary，前端 bubble 不空白
        when(aiClient.understand(any())).thenReturn(new AiUnderstanding(
                "摘要兜底", null, null, null, List.of("日常"), "neutral", "life", false, null,
                "{\"summary\":\"摘要兜底\",\"tags\":[\"日常\"],\"sentiment\":\"neutral\"}"));

        service.answer("default", record("rec_1", "用户提问"), CARD_ID);

        ArgumentCaptor<CardRecord> captor = ArgumentCaptor.forClass(CardRecord.class);
        verify(cardRepository).save(any(), captor.capture());
        assertEquals("摘要兜底", captor.getValue().turns().get(1).text(),
                "纯 JSON 回复（无自然语言）时 card turn 回退 summary");
    }

    // ── 2026-08-30 流式问答（ai-calling-governance 批 2，REVIEW P2-用户2）──

    private QuestionAppService.AnswerStreamHandler handler(StringBuilder deltas,
                                                           List<QuestionAppService.StreamResult> results,
                                                           List<String> errors) {
        return new QuestionAppService.AnswerStreamHandler() {
            @Override public void onDelta(String chunk) { deltas.append(chunk); }
            @Override public void onComplete(QuestionAppService.StreamResult result) { results.add(result); }
            @Override public void onError(String message) { errors.add(message); }
        };
    }

    @Test
    @SuppressWarnings("unchecked")
    void answerStream_filtersJsonTail_writesCardTurn() {
        String raw = "你好，世界\n{\"summary\":\"摘要\",\"tags\":[\"日常\"],\"sentiment\":\"neutral\"}";
        when(streamingAiClient.streamGenerate(any(), any(), any())).thenAnswer(inv -> {
            java.util.function.Consumer<String> onDelta = inv.getArgument(2);
            onDelta.accept("你好，");
            onDelta.accept("世界\n");
            onDelta.accept("{\"summary\":\"摘要\",\"tags\":[\"日常\"],\"sentiment\":\"neutral\"}");
            return raw;
        });
        StringBuilder deltas = new StringBuilder();
        List<QuestionAppService.StreamResult> results = new java.util.ArrayList<>();
        List<String> errors = new java.util.ArrayList<>();

        service.answerStream("default", record("rec_1", "用户提问"), CARD_ID, handler(deltas, results, errors));

        assertEquals("你好，世界", deltas.toString(),
                "流式增量应剥离 JSON 回执尾巴（\\n{ 之后扣留）");
        assertEquals(1, results.size());
        assertEquals("你好，世界", results.get(0).text(), "meta 定稿正文与草稿一致");
        assertEquals("摘要", results.get(0).summary());
        ArgumentCaptor<CardRecord> captor = ArgumentCaptor.forClass(CardRecord.class);
        verify(cardRepository).save(any(), captor.capture());
        assertEquals("你好，世界", captor.getValue().turns().get(1).text(),
                "card AI turn 与同步路径同口径剥离 JSON");
        assertTrue(errors.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void answerStream_streamFailsBeforeFirstDelta_fallsBackToSync() {
        when(streamingAiClient.streamGenerate(any(), any(), any()))
                .thenThrow(new RuntimeException("AI 流式调用失败 status=500"));
        String raw = "降级后的完整回答\n{\"summary\":\"摘要\",\"tags\":[\"日常\"],\"sentiment\":\"neutral\"}";
        when(aiClient.understand(any())).thenReturn(new AiUnderstanding(
                "摘要", null, null, null, List.of("日常"), "neutral", "life", false, null, raw));
        StringBuilder deltas = new StringBuilder();
        List<QuestionAppService.StreamResult> results = new java.util.ArrayList<>();
        List<String> errors = new java.util.ArrayList<>();

        service.answerStream("default", record("rec_1", "用户提问"), CARD_ID, handler(deltas, results, errors));

        assertEquals("降级后的完整回答", deltas.toString(),
                "未发出增量即失败 → 降级非流式，全文一次性补发");
        assertEquals(1, results.size(), "降级成功仍正常 onComplete");
        assertTrue(errors.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void answerStream_streamFailsAfterDelta_onError_noAiTurnWritten() {
        when(streamingAiClient.streamGenerate(any(), any(), any())).thenAnswer(inv -> {
            java.util.function.Consumer<String> onDelta = inv.getArgument(2);
            onDelta.accept("说到一半");
            throw new RuntimeException("中途断流");
        });
        StringBuilder deltas = new StringBuilder();
        List<QuestionAppService.StreamResult> results = new java.util.ArrayList<>();
        List<String> errors = new java.util.ArrayList<>();

        service.answerStream("default", record("rec_1", "用户提问"), CARD_ID, handler(deltas, results, errors));

        assertEquals(1, errors.size(), "已发出增量后的失败 → onError（前端保留草稿可重试）");
        assertTrue(results.isEmpty(), "失败不 onComplete");
        verify(cardRepository, never()).save(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void answerStream_pureText_flushesHeldTail() {
        String raw = "你好\n世界";
        when(streamingAiClient.streamGenerate(any(), any(), any())).thenAnswer(inv -> {
            java.util.function.Consumer<String> onDelta = inv.getArgument(2);
            onDelta.accept("你好\n世");   // 尾字符 "世" 进缓冲（防 "\n{" 跨块切开）
            onDelta.accept("界");
            return raw;
        });
        StringBuilder deltas = new StringBuilder();
        List<QuestionAppService.StreamResult> results = new java.util.ArrayList<>();
        List<String> errors = new java.util.ArrayList<>();

        service.answerStream("default", record("rec_1", "用户提问"), CARD_ID, handler(deltas, results, errors));

        assertEquals("你好\n世界", deltas.toString(),
                "全文无 JSON → 缓冲尾字符补发，草稿拼接 = 完整正文");
        assertEquals("你好\n世界", results.get(0).text());
    }

    @Test
    void findDuplicateResend_hitsWithinWindow() {
        // 卡片最近一轮用户问句与本次相同 + updatedAt 在 5 分钟窗口内 → 重发命中
        CardRecord card = new CardRecord(
                CARD_ID, "conversation", "active",
                List.of("日常"), List.of(
                        new CardRecord.Turn(true, "同一句问话", "09:00"),
                        new CardRecord.Turn(false, "上次的回答", "09:01")),
                "旧摘要", LocalDateTime.now().minusMinutes(1), LocalDateTime.now().minusMinutes(1));
        when(cardRepository.findById(any(), any())).thenReturn(Optional.of(card));

        var dup = service.findDuplicateResend("default", CARD_ID, "  同一句问话 ");

        assertTrue(dup.isPresent(), "同问句 + 窗口内 → 判定超时重发");
        assertEquals("上次的回答", dup.get().rawResponse());
        assertEquals("旧摘要", dup.get().summary());
    }

    @Test
    void findDuplicateResend_missWhenQuestionDiffers() {
        CardRecord card = new CardRecord(
                CARD_ID, "conversation", "active",
                List.of(), List.of(new CardRecord.Turn(true, "上一句", "09:00")),
                null, LocalDateTime.now(), LocalDateTime.now());
        when(cardRepository.findById(any(), any())).thenReturn(Optional.of(card));

        assertTrue(service.findDuplicateResend("default", CARD_ID, "新的一句").isEmpty(),
                "用户追问内容必然不同 → 不判重发");
    }
}
