package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.ai.llm.AiClient;
import com.adaiadai.core.infrastructure.ai.llm.AiUnderstanding;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.kernel.context.engine.ContextEngine;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.kernel.memory.MemoryService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
    private QuestionAppService service;

    private static final String CARD_ID = "card_1";

    @BeforeEach
    void setUp() {
        contextEngine = mock(ContextEngine.class);
        cardRepository = mock(CardFileRepository.class);
        recordRepository = mock(RecordRepository.class);
        memoryService = mock(MemoryService.class);
        aiClient = mock(AiClient.class);
        service = new QuestionAppService(contextEngine, cardRepository, recordRepository, memoryService, aiClient);

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
}
