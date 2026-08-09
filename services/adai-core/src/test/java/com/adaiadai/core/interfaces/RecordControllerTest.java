package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.QuestionAppService;
import com.adaiadai.core.application.RecordRetryService;
import com.adaiadai.core.application.RecordUnderstandingService;
import com.adaiadai.core.infrastructure.ai.llm.AiClient;
import com.adaiadai.core.infrastructure.ai.llm.AiUnderstanding;
import com.adaiadai.core.infrastructure.ai.llm.TestAiClient;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.IdentityFileRepository;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.infrastructure.storage.TagIndexService;
import com.adaiadai.core.kernel.context.IntentRecognizer;
import com.adaiadai.core.kernel.context.engine.ContextEngine;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.RecordRepository;
import com.adaiadai.core.kernel.search.SearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * RecordController integration tests.
 * Uses MockMvc + real dependencies (InMemoryFileStorage + TestAiClient).
 * No Spring context loading.
 */
class RecordControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper mapper;
    private RecordFileRepository recordRepository;
    private CardFileRepository cardRepository;
    private MemoryService memoryService;

    /**
     * 构造 MockMvc（可注入任意 AiClient，用于模拟 AI 失败降级路径）。
     */
    private MockMvc buildMockMvc(AiClient aiClient) {
        InMemoryFileStorage fileStorage = new InMemoryFileStorage();
        TagIndexService tagIndexService = new TagIndexService(fileStorage);
        recordRepository = new RecordFileRepository(fileStorage);
        recordRepository.setTagIndexService(tagIndexService);
        cardRepository = new CardFileRepository(fileStorage);
        IntentRecognizer intentRecognizer = new IntentRecognizer(new TestAiClient());

        QuestionAppService questionAppService = mock(QuestionAppService.class);
        when(questionAppService.answer(any(), any()))
                .thenReturn(new QuestionAppService.AnswerResult(
                        "rec_test", "mock answer", List.of("test"), "raw", "life"
                ));
        when(questionAppService.answer(any(), any(), any()))
                .thenReturn(new QuestionAppService.AnswerResult(
                        "rec_dec", "decision analysis", List.of("trading"), "raw", "life"
                ));

        // ContextEngine with real dependencies
        IdentityFileRepository identityRepository = new IdentityFileRepository(fileStorage);
        memoryService = new MemoryService(fileStorage);
        SearchService searchService = new SearchService(recordRepository);
        ContextEngine contextEngine = new ContextEngine(
                identityRepository, recordRepository, tagIndexService,
                memoryService, cardRepository, List.of(), List.of(), searchService
        );
        RecordUnderstandingService understandingService = new RecordUnderstandingService(contextEngine, aiClient);

        RecordRetryService retryService = mock(RecordRetryService.class);
        RecordController controller = new RecordController(
                intentRecognizer,
                questionAppService,
                understandingService,
                recordRepository,
                cardRepository,
                memoryService,
                retryService
        );

        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mockMvc = buildMockMvc(new TestAiClient());
    }

    @Test
    void createRecord_statement() throws Exception {
        String body = mapper.writeValueAsString(Map.of("content", "buy stock today"));
        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("log"))
                .andExpect(jsonPath("$.recordId").isString())
                .andExpect(jsonPath("$.summary").isString())
                .andExpect(jsonPath("$.tags").isArray())
                .andExpect(jsonPath("$.domain").isString());
    }

    @Test
    void createRecord_question() throws Exception {
        String body = mapper.writeValueAsString(Map.of("content", "天气如何"));
        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("question"));
    }

    @Test
    void createRecord_emptyContent_returns400() throws Exception {
        String body = mapper.writeValueAsString(Map.of("content", ""));
        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRecord_blankContent_returns400() throws Exception {
        String body = mapper.writeValueAsString(Map.of("content", "   "));
        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRecord_nullContent_returns400() throws Exception {
        String body = mapper.writeValueAsString(Map.of());
        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRecord_missingContentField_returns400() throws Exception {
        String body = mapper.writeValueAsString(Map.of("type", "note"));
        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRecord_withTypeAndTags() throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "content", "test with tags",
                "type", "note",
                "tags", java.util.List.of("test", "dev")
        ));
        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("log"))
                .andExpect(jsonPath("$.recordId").isString());
    }

    @Test
    void createRecord_longContent() throws Exception {
        String longContent = "A".repeat(5000);
        String body = mapper.writeValueAsString(Map.of("content", longContent));
        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void createRecord_exceedsMaxLength_returns400() throws Exception {
        String tooLong = "A".repeat(10001);
        String body = mapper.writeValueAsString(Map.of("content", tooLong));
        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRecord_wrongContentType_returns415() throws Exception {
        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("content=hello"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void createRecord_unicodeContent() throws Exception {
        String body = mapper.writeValueAsString(Map.of("content", "hello world test"));
        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void createRecord_decisionByContent() throws Exception {
        // "该不该" is no longer a DECISION intent; TestAiClient returns "log" for this
        String body = mapper.writeValueAsString(Map.of("content", "该不该加仓立昂微"));
        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("log"));
    }

    @Test
    void createRecord_decisionByManualIntent() throws Exception {
        // manual "decision" now maps to log since only question/log exist
        String body = mapper.writeValueAsString(Map.of(
                "content", "我该不该卖掉京东方",
                "intent", "decision"
        ));
        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("log"));
    }

    @Test
    void deleteRecord_existing_returns204() throws Exception {
        // First create a record
        String createBody = mapper.writeValueAsString(Map.of("content", "delete me"));
        String createResp = mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String recordId = mapper.readTree(createResp).get("recordId").asText();

        // Then delete it — should return 204 AND actually remove the record
        mockMvc.perform(delete("/api/v1/records/" + recordId))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteRecord_removesFromAllRepos() throws Exception {
        // Create a record
        String createBody = mapper.writeValueAsString(Map.of("content", "买股票赚了钱"));
        String createResp = mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String recordId = mapper.readTree(createResp).get("recordId").asText();

        // Delete it
        mockMvc.perform(delete("/api/v1/records/" + recordId))
                .andExpect(status().isNoContent());

        // Verify it's gone from RecordRepository
        org.junit.jupiter.api.Assertions.assertFalse(recordRepository.findById("default",recordId).isPresent());
        // Verify card file also cleaned (no card files match the recordId)
        org.junit.jupiter.api.Assertions.assertFalse(cardRepository.findById("default",recordId).isPresent());
    }

    @Test
    void deleteRecord_nonexistent_returns204() throws Exception {
        // Deleting non-existent record should not throw
        mockMvc.perform(delete("/api/v1/records/nonexistent_id"))
                .andExpect(status().isNoContent());
    }

    @Test
    void createRecord_aiFailure_degradedMemoryPersisted() throws Exception {
        // 模拟 DeepSeek 不可用：understand 抛异常
        AiClient failingAi = new AiClient() {
            @Override
            public AiUnderstanding understand(ContextPackage contextPackage) {
                throw new IllegalStateException("AI 服务不可用");
            }

            @Override
            public String generate(ContextPackage contextPackage, String systemPrompt) {
                throw new IllegalStateException("AI 服务不可用");
            }

            @Override
            public String recognizeIntent(String content) {
                return "log";
            }
        };
        MockMvc failingMvc = buildMockMvc(failingAi);

        String body = mapper.writeValueAsString(Map.of("content", "今天加仓了立昂微"));
        String resp = failingMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("log"))
                .andExpect(jsonPath("$.summary").value("recorded"))
                .andReturn().getResponse().getContentAsString();

        String recordId = mapper.readTree(resp).get("recordId").asText();

        // AI 失败 → 记录不丢 + 降级记忆已沉淀（标 DEGRADED，供重补升级）
        assertTrue(recordRepository.findById("default",recordId).isPresent(), "记录不应因 AI 失败丢失");
        Optional<Memory> degraded = memoryService.findByRecordId("default",recordId);
        assertTrue(degraded.isPresent(), "AI 失败也应降级沉淀记忆");
        assertTrue(Memory.isDegraded(degraded.get()), "降级记忆应标 DEGRADED");
        assertEquals("今天加仓了立昂微", degraded.get().summary());
    }

    // ── domain 切换 + retry（adai-admin 系统操作台依赖，纯 mock 独立构造）──

    private MockMvc mockRecordMvc(RecordRepository repo, MemoryService mem, RecordRetryService retry) {
        RecordController controller = new RecordController(
                mock(IntentRecognizer.class),
                mock(QuestionAppService.class),
                mock(RecordUnderstandingService.class),
                repo,
                mock(CardFileRepository.class),
                mem,
                retry);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void updateDomain_valid_returns204() throws Exception {
        RecordRepository repo = mock(RecordRepository.class);
        MockMvc mvc = mockRecordMvc(repo, mock(MemoryService.class), mock(RecordRetryService.class));

        mvc.perform(patch("/api/v1/records/rec_1/domain")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"trading\"}"))
                .andExpect(status().isNoContent());
        verify(repo).updateDomain("default", "rec_1", "trading");
    }

    @Test
    void updateDomain_invalid_returns400() throws Exception {
        RecordRepository repo = mock(RecordRepository.class);
        MockMvc mvc = mockRecordMvc(repo, mock(MemoryService.class), mock(RecordRetryService.class));

        mvc.perform(patch("/api/v1/records/rec_1/domain")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"unknown\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void triggerRetry_returnsCountDelta() throws Exception {
        RecordRepository repo = mock(RecordRepository.class);
        MemoryService mem = mock(MemoryService.class);
        when(mem.count(any())).thenReturn(2L, 5L);
        RecordRetryService retry = mock(RecordRetryService.class);
        MockMvc mvc = mockRecordMvc(repo, mem, retry);

        mvc.perform(post("/api/v1/records/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.memoriesBefore").value(2))
                .andExpect(jsonPath("$.memoriesAfter").value(5))
                .andExpect(jsonPath("$.newMemories").value(3));
        verify(retry).retryUnprocessed("default");
    }
}
