package com.adaiadai.core.interfaces;

import com.adaiadai.core.infrastructure.ai.llm.MockAiClient;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
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
        RecordFileRepository recordRepository = new RecordFileRepository(fileStorage);
        CardFileRepository cardRepository = new CardFileRepository(fileStorage);
        ConversationController controller = new ConversationController(
                new MockAiClient(), recordRepository, cardRepository
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
        RecordFileRepository repo = new RecordFileRepository(storage);
        ConversationController ctrl = new ConversationController(new MockAiClient(), repo, new CardFileRepository(storage));
        MockMvc localMvc = MockMvcBuilders.standaloneSetup(ctrl).build();

        String body = mapper.writeValueAsString(Map.of(
                "turns", List.of("你好", "你好有什么可以帮助")
        ));

        localMvc.perform(post("/api/v1/conversations/end")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Verify a record was saved
        assertFalse(storage.listFiles("records").isEmpty());
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
}
