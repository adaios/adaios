package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.QuestionAppService;
import com.adaiadai.core.infrastructure.ai.llm.AiClient;
import com.adaiadai.core.infrastructure.ai.llm.MockAiClient;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.kernel.context.IntentRecognizer;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * RecordController integration tests.
 * Uses MockMvc + real dependencies (InMemoryFileStorage + MockAiClient).
 * No Spring context loading.
 */
class RecordControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        InMemoryFileStorage fileStorage = new InMemoryFileStorage();
        RecordFileRepository recordRepository = new RecordFileRepository(fileStorage);
        IntentRecognizer intentRecognizer = new IntentRecognizer(new MockAiClient());

        QuestionAppService questionAppService = mock(QuestionAppService.class);
        when(questionAppService.answer(any()))
                .thenReturn(new QuestionAppService.AnswerResult(
                        "rec_test", "mock answer", List.of("test"), "raw"
                ));

        AiClient aiClient = new MockAiClient();
        RecordController controller = new RecordController(
                intentRecognizer,
                questionAppService,
                recordRepository,
                aiClient
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
                .andExpect(jsonPath("$.recordId").isString());
    }

    @Test
    void createRecord_question() throws Exception {
        String body = mapper.writeValueAsString(Map.of("content", "what about weather?"));
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
}
