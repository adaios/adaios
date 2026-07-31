package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.QuestionAppService;
import com.adaiadai.core.application.RecordRetryService;
import com.adaiadai.core.infrastructure.ai.llm.AiClient;
import com.adaiadai.core.infrastructure.ai.llm.TestAiClient;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.IdentityFileRepository;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.infrastructure.storage.TagIndexService;
import com.adaiadai.core.kernel.context.IntentRecognizer;
import com.adaiadai.core.kernel.context.engine.ContextEngine;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.search.SearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
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

        AiClient aiClient = new TestAiClient();
        RecordRetryService retryService = mock(RecordRetryService.class);
        RecordController controller = new RecordController(
                intentRecognizer,
                questionAppService,
                contextEngine,
                recordRepository,
                cardRepository,
                aiClient,
                memoryService,
                retryService
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
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
        org.junit.jupiter.api.Assertions.assertFalse(recordRepository.findById(recordId).isPresent());
        // Verify card file also cleaned (no card files match the recordId)
        org.junit.jupiter.api.Assertions.assertFalse(cardRepository.findById(recordId).isPresent());
    }

    @Test
    void deleteRecord_nonexistent_returns204() throws Exception {
        // Deleting non-existent record should not throw
        mockMvc.perform(delete("/api/v1/records/nonexistent_id"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteRecord_cleansMemory() throws Exception {
        // Create a statement record → memory persisted with recordId
        String createBody = mapper.writeValueAsString(Map.of("content", "今天健身了一小时"));
        String createResp = mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String recordId = mapper.readTree(createResp).get("recordId").asText();

        // Statement → AI understanding → memory persisted
        org.junit.jupiter.api.Assertions.assertTrue(
                memoryService.findByRecordId(recordId).isPresent(),
                "删除前 memory 应存在");

        // Delete it → memory entry with this recordId must also be gone
        mockMvc.perform(delete("/api/v1/records/" + recordId))
                .andExpect(status().isNoContent());

        // Record gone
        org.junit.jupiter.api.Assertions.assertFalse(recordRepository.findById(recordId).isPresent());
        // Card gone
        org.junit.jupiter.api.Assertions.assertFalse(cardRepository.findById(recordId).isPresent());
        // Memory gone（联动清理）
        org.junit.jupiter.api.Assertions.assertFalse(
                memoryService.findByRecordId(recordId).isPresent(),
                "删除记录后关联 Memory 应一并清理");
    }
}
