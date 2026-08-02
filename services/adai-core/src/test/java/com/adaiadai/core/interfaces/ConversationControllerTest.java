package com.adaiadai.core.interfaces;

import com.adaiadai.core.infrastructure.ai.llm.AiClient;
import com.adaiadai.core.infrastructure.ai.llm.AiUnderstanding;
import com.adaiadai.core.infrastructure.ai.llm.TestAiClient;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.infrastructure.storage.TagIndexService;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.CardRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ConversationController unit tests.
 */
class ConversationControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        InMemoryFileStorage fileStorage = new InMemoryFileStorage();
        TagIndexService tagIndexService = new TagIndexService(fileStorage);
        RecordFileRepository recordRepository = new RecordFileRepository(fileStorage);
        recordRepository.setTagIndexService(tagIndexService);
        CardFileRepository cardRepository = new CardFileRepository(fileStorage);
        MemoryService memoryService = new MemoryService(fileStorage);
        ConversationController controller = new ConversationController(
                new TestAiClient(), recordRepository, cardRepository, memoryService
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void endConversation_withTurns() throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "turns", List.of("今天天气如何", "今天多云转晴", "那明天呢", "明天预计有雨")
        ));

        mockMvc.perform(post("/api/v1/conversations/end")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").isString())
                .andExpect(jsonPath("$.tags").isArray())
                .andExpect(jsonPath("$.recordId").isString());
    }

    @Test
    void endConversation_singleTurn() throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "turns", List.of("只是一个记录")
        ));

        mockMvc.perform(post("/api/v1/conversations/end")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").isString());
    }

    @Test
    void endConversation_emptyTurns() throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "turns", List.of()
        ));

        mockMvc.perform(post("/api/v1/conversations/end")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").isString());
    }

    @Test
    void endConversation_persistsRecord() throws Exception {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        TagIndexService tis = new TagIndexService(storage);
        RecordFileRepository repo = new RecordFileRepository(storage);
        repo.setTagIndexService(tis);
        ConversationController ctrl = new ConversationController(new TestAiClient(), repo, new CardFileRepository(storage), new MemoryService(storage));
        MockMvc localMvc = MockMvcBuilders.standaloneSetup(ctrl).build();

        String body = mapper.writeValueAsString(Map.of(
                "turns", List.of("你好", "你好有什么可以帮助")
        ));

        localMvc.perform(post("/api/v1/conversations/end")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Verify a record was saved
        assertFalse(storage.listFiles("default", "records").isEmpty());
    }

    @Test
    void endConversation_withCardId() throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "turns", List.of("今天天气如何", "今天多云转晴"),
                "cardId", "card_test_123"
        ));

        mockMvc.perform(post("/api/v1/conversations/end")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").isString())
                .andExpect(jsonPath("$.tags").isArray())
                .andExpect(jsonPath("$.recordId").isString())
                .andExpect(jsonPath("$.recordId").isNotEmpty());
    }

    @Test
    void endConversation_wrongMethod_returns405() throws Exception {
        var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/conversations/end");
        mockMvc.perform(req)
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void endConversation_malformedBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/conversations/end")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void endConversation_aiFailure_degradesToOriginalText() throws Exception {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        TagIndexService tis = new TagIndexService(storage);
        RecordFileRepository repo = new RecordFileRepository(storage);
        repo.setTagIndexService(tis);
        CardFileRepository cardRepo = new CardFileRepository(storage);
        MemoryService memoryService = new MemoryService(storage);

        // AI 失败：understand 抛异常（模拟 DeepSeek 返回空内容）
        AiClient failingClient = new AiClient() {
            @Override
            public AiUnderstanding understand(ContextPackage contextPackage) {
                throw new RuntimeException("DeepSeek API 返回空内容");
            }
            @Override
            public String recognizeIntent(String content) { return "log"; }
        };
        ConversationController ctrl = new ConversationController(failingClient, repo, cardRepo, memoryService);
        MockMvc localMvc = MockMvcBuilders.standaloneSetup(ctrl).build();

        // 预建 card（controller 只更新已存在的 card）
        cardRepo.save("default", new CardRecord(
                "card_test_ai_fail", "conversation", "active",
                List.of(), List.of(), null,
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now()
        ));

        String body = mapper.writeValueAsString(Map.of(
                "turns", List.of("今天天气如何", "今天多云转晴"),
                "cardId", "card_test_ai_fail"
        ));

        // 不再 500：返回 200 + 原文兜底 summary
        localMvc.perform(post("/api/v1/conversations/end")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.containsString("今天天气如何")));

        // card 仍标记为 ended（即使 AI 失败，用户点了结束就该结束）
        var card = cardRepo.findById("default", "card_test_ai_fail");
        assertTrue(card.isPresent());
        assertEquals("ended", card.get().status());
    }
}
