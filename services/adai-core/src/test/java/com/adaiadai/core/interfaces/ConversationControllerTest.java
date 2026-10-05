package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.ActionReviewService;
import com.adaiadai.core.application.TodoAppService;
import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.ai.AiUnderstanding;
import com.adaiadai.core.infrastructure.ai.llm.TestAiClient;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.infrastructure.storage.TagIndexService;
import com.adaiadai.core.infrastructure.storage.TodoFileRepository;
import com.adaiadai.core.kernel.memory.Memory;
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
    private InMemoryFileStorage fileStorage;

    /**
     * REVIEW P2-交易73：动作搬运器（真实落盘，供「对话里给的动作 → 既有待办」端到端断言）。
     */
    private static ActionReviewService actionReviewService(InMemoryFileStorage storage) {
        TodoFileRepository todoRepo = new TodoFileRepository(storage);
        return new ActionReviewService(todoRepo, new TodoAppService(todoRepo, new MemoryService(storage)));
    }

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        fileStorage = new InMemoryFileStorage();
        TagIndexService tagIndexService = new TagIndexService(fileStorage);
        recordRepository = new RecordFileRepository(fileStorage);
        recordRepository.setTagIndexService(tagIndexService);
        cardRepository = new CardFileRepository(fileStorage);
        memoryService = new MemoryService(fileStorage);
        ConversationController controller = new ConversationController(
                new TestAiClient(), recordRepository, cardRepository, memoryService,
                actionReviewService(fileStorage)
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
        ConversationController ctrl = new ConversationController(new TestAiClient(), repo, new CardFileRepository(storage), new MemoryService(storage),
                actionReviewService(storage));
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
                new TestAiClient(), repo, new CardFileRepository(storage), new MemoryService(storage),
                actionReviewService(storage));
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
        ConversationController ctrl = new ConversationController(failingClient, repo, cardRepo, memoryService, actionReviewService(storage));
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
        assertEquals("ended", card.get().status(), "actual=" + card.get());
    }

    // ── REVIEW P1-对话1：同卡幂等（超时重发 / 双端并发只落一条 conversation）──

    @Test
    void endConversation_sameCardIdTwice_returnsSameRecordAndKeepsOneRecord() throws Exception {
        saveActiveCard("card_idem_1", List.of("第一句", "回一句"));
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

    // ── REVIEW P2-工程14：幂等键悬空（记录文件被删/损坏/迁移丢失）不得返回幽灵 recordId ──

    /**
     * 「卡片带幂等键 + 记录文件已删」→ 不得再把那个查不到的 id 当成功返回。
     * <p>
     * 幂等键在 card_*.md 上、记录在 rec_*.md 上，分属两个文件且无跨文件事务：记录被删 / 损坏 /
     * 迁移丢失后键会悬空。修复前：命中键直接回旧 id，前端拿到一个**打不开**的 recordId（假成功）。
     * 修复后：失效该键 → 走 doEnd 新落一条，返回**真实存在、可打开**的 id，且只落一条。
     */
    @Test
    void endConversation_idempotentHitButRecordDeleted_returnsLiveRecordNotGhostId() throws Exception {
        saveActiveCard("card_ghost_1", List.of("第一句", "回一句"));
        String body = mapper.writeValueAsString(Map.of(
                "turns", List.of("第一句", "回一句"),
                "cardId", "card_ghost_1"
        ));

        String first = mockMvc.perform(post("/api/v1/conversations/end")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String firstId = mapper.readTree(first).get("recordId").asText();
        assertTrue(recordRepository.findById("default", firstId).isPresent(), "首次落盘后记录应可查");

        // 模拟记录文件被删/迁移丢失（卡片上的幂等键仍在——这正是「键与卡片分属两个文件」的裂缝）
        recordRepository.deleteById("default", firstId);
        assertTrue(recordRepository.findById("default", firstId).isEmpty(), "前置：记录文件确已不在");
        assertEquals(firstId,
                cardRepository.findById("default", "card_ghost_1").orElseThrow().conversationRecordId(),
                "前置：卡片仍带着悬空的幂等键");

        String second = mockMvc.perform(post("/api/v1/conversations/end")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String secondId = mapper.readTree(second).get("recordId").asText();

        assertNotEquals(firstId, secondId, "不得再把已删记录的 id 当成功返回（幽灵 id）");
        assertTrue(recordRepository.findById("default", secondId).isPresent(),
                "返回的 recordId 必须真实存在、可打开，实际：" + secondId);
        assertEquals(1, recordRepository.findAll("default").size(), "只落一条，不得重复落盘");
        assertEquals(secondId,
                cardRepository.findById("default", "card_ghost_1").orElseThrow().conversationRecordId(),
                "悬空的幂等键要被新 id 覆盖（下次同内容重试才复用）");
    }

    /**
     * 正常幂等命中（记录仍在）→ 行为不变：返回同一条 id、不重复落盘、**不重复调 AI**（不重复花钱）。
     */
    @Test
    void endConversation_idempotentHitWithLiveRecord_reusesWithoutCallingAiAgain() throws Exception {
        saveActiveCard("card_live_1", List.of("甲", "乙"));

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
        MockMvc localMvc = MockMvcBuilders.standaloneSetup(new ConversationController(
                countingClient, recordRepository, cardRepository, memoryService,
                actionReviewService(fileStorage))).build();

        String body = mapper.writeValueAsString(Map.of(
                "turns", List.of("甲", "乙"), "cardId", "card_live_1"));

        String first = localMvc.perform(post("/api/v1/conversations/end")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = localMvc.perform(post("/api/v1/conversations/end")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertEquals(mapper.readTree(first).get("recordId").asText(),
                mapper.readTree(second).get("recordId").asText(), "记录仍在 → 幂等命中，复用同一条");
        assertEquals(1, aiCalls.get(), "幂等命中不得重复调 AI（不重复花钱）");
        assertEquals(1, recordRepository.findAll("default").size(), "幂等命中不得重复落盘");
    }

    /**
     * 对抗审查 P1-A（2026-09-26）：**内容变了就不是重试**——用户在已结束的卡上继续聊几轮再点
     * 结束，必须新落一条（含新 summary 与记忆），不得拿回上一次的结论。
     */
    @Test
    void endConversation_sameCardWithNewTurns_persistsFreshRecord() throws Exception {
        saveActiveCard("card_evolve_1", List.of("第一句", "回一句"));
        String first = mockMvc.perform(post("/api/v1/conversations/end")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "turns", List.of("第一句", "回一句"), "cardId", "card_evolve_1"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String firstId = mapper.readTree(first).get("recordId").asText();

        String second = mockMvc.perform(post("/api/v1/conversations/end")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "turns", List.of("第一句", "回一句", "又聊两句", "再回一句"),
                                "cardId", "card_evolve_1"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String secondId = mapper.readTree(second).get("recordId").asText();

        assertNotEquals(firstId, secondId, "同卡但对话内容变了 → 必须新落一条（改前会复用旧结论）");
        assertEquals(2, recordRepository.findAll("default").size(), "两段对话各留一条记录（新轮次也要进记忆/时间线）");
        assertEquals(secondId,
                cardRepository.findById("default", "card_evolve_1").orElseThrow().conversationRecordId(),
                "幂等键要指向**最新**那条，后续同内容重试才复用");
    }

    @Test
    void endConversation_differentCards_eachPersisted() throws Exception {
        saveActiveCard("card_a_1", List.of("甲"));
        saveActiveCard("card_b_1", List.of("乙"));

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
        saveActiveCard("card_race_1", List.of("甲", "乙"));

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
                countingClient, recordRepository, cardRepository, memoryService,
                actionReviewService(fileStorage));

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

    /**
     * 预建一张 active 卡；[turns] 写入轮次——**缺指纹时的等价判据（sameTurnsText）要拿它比对**，
     * 所以必须带上与用例请求一致的 turns（空 turns 的卡会被判「不比」→ 走新落一条）。
     */
    private void saveActiveCard(String cardId, List<String> turns) {
        List<CardRecord.Turn> turnList = new java.util.ArrayList<>();
        for (int i = 0; i < turns.size(); i++) {
            turnList.add(new CardRecord.Turn(i % 2 == 0, turns.get(i), "14:00"));
        }
        cardRepository.save("default", new CardRecord(
                cardId, "conversation", "active",
                List.of(), turnList, null,
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now()
        ));
    }

    // ── REVIEW P2-交易73：对话里给出的动作 → 既有待办 + 记忆的待行动事项 ──

    /**
     * 模拟「阿呆在对话里给了动作」：rawResponse 是带 {@code actions} 数组的回执 JSON
     * ——正是 {@code LlmResponseParser.parseActions} 的输入形态。
     */
    private static AiClient actionsClient(String... actions) {
        String json = "{\"summary\":\"收盘后三件事\",\"tags\":[\"交易\"],\"sentiment\":\"neutral\","
                + "\"actionable\":false,\"actionSuggestion\":null,\"actions\":["
                + java.util.Arrays.stream(actions)
                        .map(a -> "\"" + a + "\"")
                        .collect(java.util.stream.Collectors.joining(","))
                + "]}";
        return new AiClient() {
            @Override
            public AiUnderstanding understand(ContextPackage contextPackage) {
                return new AiUnderstanding("收盘后三件事", "收盘后要做的事", null, null,
                        List.of("交易"), "neutral", "life", false, null, json);
            }
            @Override
            public String generate(ContextPackage contextPackage, String systemPrompt) { return "[Test]"; }
            @Override
            public String recognizeIntent(String content) { return "log"; }
        };
    }

    @Test
    void endConversation_capturesActionsIntoExistingTodos_andMemory() throws Exception {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        TagIndexService tis = new TagIndexService(storage);
        RecordFileRepository repo = new RecordFileRepository(storage);
        repo.setTagIndexService(tis);
        MemoryService memory = new MemoryService(storage);
        ConversationController ctrl = new ConversationController(
                actionsClient("把云南锗业的白线调出来看看", "数一下亨通光电持有几天"),
                repo, new CardFileRepository(storage), memory, actionReviewService(storage));
        MockMvc localMvc = MockMvcBuilders.standaloneSetup(ctrl).build();

        String body = mapper.writeValueAsString(Map.of(
                "turns", List.of("今天收盘后做点什么", "三件事，我说给你听")));
        String response = localMvc.perform(post("/api/v1/conversations/end")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String recordId = mapper.readTree(response).get("recordId").asText();

        // ① 落点 = 既有待办（data/{userId}/todos/），带来源锚点 + 次日到期（次日早盘捞回）
        List<com.adaiadai.core.kernel.todo.Todo> todos =
                new TodoFileRepository(storage).findAll("default");
        assertEquals(2, todos.size(), "对话里给的两条动作都应落进既有待办");
        assertTrue(todos.stream().allMatch(t -> recordId.equals(t.sourceRecordId())), "待办带来源锚点");
        assertTrue(todos.stream().allMatch(t -> java.time.LocalDate.now().plusDays(1).equals(t.due())),
                "到期日 = 次日");
        assertTrue(todos.stream().anyMatch(t -> t.title().contains("云南锗业")));

        // ② 记忆的「待行动事项」——ContextEngine 下次对话据此捞回
        Memory persisted = memory.findByRecordId("default", recordId).orElseThrow();
        assertTrue(persisted.actionable(), "动作要落成 actionable 记忆（下次对话的待行动事项）");
        assertTrue(persisted.suggestion().contains("云南锗业"), "行动建议 = 动作原话");
    }

    @Test
    void endConversation_withoutActions_landsNoTodo() throws Exception {
        // TestAiClient 的 rawResponse 不是 JSON 回执 → 解析不出 actions → 沉默（一个待办都不该有）
        InMemoryFileStorage storage = new InMemoryFileStorage();
        TagIndexService tis = new TagIndexService(storage);
        RecordFileRepository repo = new RecordFileRepository(storage);
        repo.setTagIndexService(tis);
        ConversationController ctrl = new ConversationController(
                new TestAiClient(), repo, new CardFileRepository(storage), new MemoryService(storage),
                actionReviewService(storage));
        MockMvc localMvc = MockMvcBuilders.standaloneSetup(ctrl).build();

        localMvc.perform(post("/api/v1/conversations/end")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("turns", List.of("闲聊", "嗯")))))
                .andExpect(status().isOk());

        assertTrue(new TodoFileRepository(storage).findAll("default").isEmpty(),
                "没有动作就一个待办都不该有——沉默是默认项");
    }

    @Test
    void endConversation_sameCardTwice_doesNotDuplicateTodos() throws Exception {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        TagIndexService tis = new TagIndexService(storage);
        RecordFileRepository repo = new RecordFileRepository(storage);
        repo.setTagIndexService(tis);
        CardFileRepository cards = new CardFileRepository(storage);
        ConversationController ctrl = new ConversationController(
                actionsClient("把白线调出来"), repo, cards, new MemoryService(storage),
                actionReviewService(storage));
        MockMvc localMvc = MockMvcBuilders.standaloneSetup(ctrl).build();

        String cardId = "card_actions_1";
        // 卡片必须落在**这份** storage 里（controller 读的是同一份，幂等键才认得出）
        cards.save("default", new CardRecord(
                cardId, "conversation", "active", List.of(),
                List.of(new CardRecord.Turn(true, "收盘后做什么", "14:00"),
                        new CardRecord.Turn(false, "把白线调出来", "14:00")),
                null, java.time.LocalDateTime.now(), java.time.LocalDateTime.now()));
        String body = mapper.writeValueAsString(Map.of(
                "turns", List.of("收盘后做什么", "把白线调出来"), "cardId", cardId));

        localMvc.perform(post("/api/v1/conversations/end")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        localMvc.perform(post("/api/v1/conversations/end")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        assertEquals(1, new TodoFileRepository(storage).findAll("default").size(),
                "同卡重复 end（幂等命中）不得重复落待办");
    }
}
