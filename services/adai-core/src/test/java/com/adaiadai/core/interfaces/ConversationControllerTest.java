package com.adaiadai.core.interfaces;

import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.ai.AiUnderstanding;
import com.adaiadai.core.infrastructure.ai.llm.TestAiClient;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.infrastructure.storage.TagIndexService;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.ContentRecord;
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
        memoryService = new MemoryService(fileStorage);
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
    void endConversation_persistsOriginalTextNotSummary() throws Exception {
        // E-A 写侧保真（memory-fidelity.md，2026-09-15 P4①）：正文必须是对话原文，
        // AI 转述降入 summary；source 由 ai_summary 改为 user_input 作「正文=原话」标记。
        InMemoryFileStorage storage = new InMemoryFileStorage();
        TagIndexService tis = new TagIndexService(storage);
        RecordFileRepository repo = new RecordFileRepository(storage);
        repo.setTagIndexService(tis);
        ConversationController ctrl = new ConversationController(
                new TestAiClient(), repo, new CardFileRepository(storage), new MemoryService(storage));
        MockMvc localMvc = MockMvcBuilders.standaloneSetup(ctrl).build();

        String body = mapper.writeValueAsString(Map.of(
                "turns", List.of("我最近睡不好", "是不是想太多了")));

        localMvc.perform(post("/api/v1/conversations/end")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        List<ContentRecord> saved = repo.findAll("default");
        assertEquals(1, saved.size(), "应落一条 conversation 记录");
        ContentRecord r = saved.get(0);
        assertEquals("user_input", r.source(), "source 应为 user_input（正文=原话的新旧可分标记）");
        assertTrue(r.content().contains("我：我最近睡不好"), "正文必须是用户原话，实际：" + r.content());
        assertTrue(r.content().contains("你：是不是想太多了"), "正文应含阿呆原话");
        assertNotNull(r.summary(), "AI 转述应保留在 summary 字段");
        assertFalse(r.summary().contains("我："), "summary 应是 AI 转述而非对话原文");
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
            public String generate(ContextPackage contextPackage, String systemPrompt) {
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

    // ── REVIEW P1-对话1：同卡幂等（超时重发 / 双端并发只落一条 conversation）──

    @Test
    void endConversation_sameCardIdTwice_returnsSameRecordAndKeepsOneRecord() throws Exception {
        saveActiveCard("card_idem_1");
        String body = mapper.writeValueAsString(Map.of(
                "turns", List.of("第一句", "回一句"),
                "cardId", "card_idem_1"
        ));

        String first = mockMvc.perform(post("/api/v1/conversations/end")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/v1/conversations/end")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String firstId = mapper.readTree(first).get("recordId").asText();
        String secondId = mapper.readTree(second).get("recordId").asText();
        assertEquals(firstId, secondId, "同卡重复 end 必须返回同一条记录（幂等），而不是新落一条");
        assertEquals(1, recordRepository.findAll("default").size(), "同卡只允许一条 conversation 记录");
        assertEquals(firstId, cardRepository.findById("default", "card_idem_1").orElseThrow().conversationRecordId(),
                "卡片须记下幂等键（跨请求/重启后仍幂等）");
    }

    @Test
    void endConversation_differentCards_eachPersisted() throws Exception {
        saveActiveCard("card_a_1");
        saveActiveCard("card_b_1");

        mockMvc.perform(post("/api/v1/conversations/end")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("turns", List.of("甲"), "cardId", "card_a_1"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/conversations/end")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("turns", List.of("乙"), "cardId", "card_b_1"))))
                .andExpect(status().isOk());

        assertEquals(2, recordRepository.findAll("default").size(), "不同卡各自落一条（幂等不得误伤）");
    }

    @Test
    void endConversation_noCardId_stillPersistsEveryCall() throws Exception {
        String body = mapper.writeValueAsString(Map.of("turns", List.of("没有卡片上下文的记录")));
        mockMvc.perform(post("/api/v1/conversations/end")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/conversations/end")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());

        assertEquals(2, recordRepository.findAll("default").size(), "无 cardId 时保持原语义（每次都落）");
    }

    @Test
    void endConversation_concurrentSameCard_persistsOnceAndCallsAiOnce() throws Exception {
        saveActiveCard("card_race_1");

        java.util.concurrent.atomic.AtomicInteger aiCalls = new java.util.concurrent.atomic.AtomicInteger();
        TestAiClient delegate = new TestAiClient();
        AiClient countingClient = new AiClient() {
            @Override
            public AiUnderstanding understand(ContextPackage contextPackage) {
                aiCalls.incrementAndGet();
                return delegate.understand(contextPackage);
            }

            @Override
            public String generate(ContextPackage contextPackage, String systemPrompt) {
                return delegate.generate(contextPackage, systemPrompt);
            }

            @Override
            public String recognizeIntent(String content) {
                return delegate.recognizeIntent(content);
            }
        };
        ConversationController controller = new ConversationController(
                countingClient, recordRepository, cardRepository, memoryService);

        var request = new ConversationController.EndConversationRequest(List.of("甲", "乙"), "card_race_1");
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        Runnable task = () -> {
            try {
                start.await();
                controller.endConversation("default", request);
            } catch (Exception ignored) {
                // 断言在 join 之后统一做
            }
        };
        Thread t1 = new Thread(task);
        Thread t2 = new Thread(task);
        t1.start();
        t2.start();
        start.countDown();
        t1.join();
        t2.join();

        assertEquals(1, recordRepository.findAll("default").size(), "并发同卡只落一条记录");
        assertEquals(1, aiCalls.get(), "并发同卡只调用一次 AI（不重复花钱）");
    }

    private void saveActiveCard(String cardId) {
        cardRepository.save("default", new CardRecord(
                cardId, "conversation", "active",
                List.of(), List.of(), null,
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now()
        ));
    }
}
