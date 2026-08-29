package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.QuestionAppService;
import com.adaiadai.core.application.RecordToTaskLinker;
import com.adaiadai.core.application.RecordUnderstandingService;
import com.adaiadai.core.application.TradeLogCollectService;
import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.ai.AiUnderstanding;
import com.adaiadai.core.infrastructure.ai.llm.TestAiClient;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.IdentityFileRepository;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.infrastructure.storage.TagIndexService;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.context.IntentRecognizer;
import com.adaiadai.core.kernel.context.engine.ContextEngine;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import com.adaiadai.core.kernel.search.SearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.never;
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
    private RecordToTaskLinker recordToTaskLinker;
    private TradeLogCollectService tradeLogCollectService;

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

        // ContextEngine with real dependencies
        IdentityFileRepository identityRepository = new IdentityFileRepository(fileStorage);
        memoryService = new MemoryService(fileStorage);
        SearchService searchService = new SearchService(recordRepository);
        // 插件服务：默认授予全插件（保持既有行为；D5 domain 收敛在 gateDomain 单测覆盖）
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findById(any())).thenReturn(Optional.of(
                new Account("default", Account.ROLE_USER, true, LocalDate.of(2026, 8, 2),
                        List.of(PluginRegistry.PLUGIN_TRADING, PluginRegistry.PLUGIN_PROJECT))));
        PluginService pluginService = new PluginService(accounts, new PluginRegistry());
        ContextEngine contextEngine = new ContextEngine(
                identityRepository, recordRepository, tagIndexService,
                memoryService, cardRepository, List.of(), List.of(), searchService, pluginService
        );
        RecordUnderstandingService understandingService = new RecordUnderstandingService(contextEngine, aiClient);

        // 2026-08-30 流式批：去重/建卡逻辑已迁移 QuestionAppService——改用 spy 真实例，
        // 只 stub answer（保持原 mock 行为），findDuplicateResend / ensureCardWithUserTurn 走真实逻辑
        QuestionAppService questionAppService = spy(new QuestionAppService(
                contextEngine, cardRepository, recordRepository, memoryService,
                aiClient, mock(com.adaiadai.core.kernel.ai.StreamingAiClient.class), pluginService));
        doReturn(new QuestionAppService.AnswerResult(
                "rec_test", "mock answer", List.of("test"), "raw", "life"
        )).when(questionAppService).answer(any(), any());
        doReturn(new QuestionAppService.AnswerResult(
                "rec_dec", "decision analysis", List.of("trading"), "raw", "life"
        )).when(questionAppService).answer(any(), any(), any());

        recordToTaskLinker = mock(RecordToTaskLinker.class);
        tradeLogCollectService = mock(TradeLogCollectService.class);
        RecordController controller = new RecordController(
                intentRecognizer,
                questionAppService,
                understandingService,
                recordRepository,
                cardRepository,
                memoryService,
                recordToTaskLinker,
                pluginService,
                tradeLogCollectService
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

    /**
     * 2026-08-20 生产问题 1：用户说「清仓了云南锗业」→ R2 误转 TODO 任务（生产 5 条脏任务
     * 「云南锗业清仓止盈/汾酒利欧清仓」，概览持续提醒已清仓股）。修复：交易表述先归集、
     * 命中则跳过 R2 任务转换（交易归集管线是唯一跟踪载体）。
     */
    @Test
    void createRecord_tradeStatement_skipsTaskLink() throws Exception {
        // 交易归集命中（宽松解析出 云南锗业 + SELL）
        when(tradeLogCollectService.isTradeStatement("今天清仓了云南锗业，全部卖出")).thenReturn(true);
        when(tradeLogCollectService.todayCandidates(anyString())).thenReturn(List.of());

        String body = mapper.writeValueAsString(Map.of("content", "今天清仓了云南锗业，全部卖出"));
        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("log"));

        verify(tradeLogCollectService).collect(anyString(), eq("今天清仓了云南锗业，全部卖出"), eq("text"));
        // 交易表述不得转任务
        verify(recordToTaskLinker, never()).link(anyString(), anyString(), anyString(), any(), anyString(), anyString(), anyBoolean());
    }

    /**
     * 2026-08-20 生产问题 1 对称回归：非交易表述（普通记录）仍走 R2 转任务。
     */
    @Test
    void createRecord_nonTradeStatement_stillLinksTask() throws Exception {
        when(tradeLogCollectService.isTradeStatement(anyString())).thenReturn(false);
        when(tradeLogCollectService.todayCandidates(anyString())).thenReturn(List.of());

        String body = mapper.writeValueAsString(Map.of("content", "记得给花浇水"));
        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("log"));

        verify(recordToTaskLinker).link(anyString(), anyString(), anyString(), any(), anyString(), anyString(), anyBoolean());
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
    void createRecord_firstQuestionWithNewCardId_persistsIntentQuestion() throws Exception {
        // REVIEW #181：前端新聊天首问带新 cardId（card 文件不存在）→
        // record 已落盘后走 QUESTION 分支，须补写 intent=question，
        // rebuild 借此排除 question 记录，避免当 log 重跑烧 AI。
        String cardId = "card_test_181";
        String body = mapper.writeValueAsString(Map.of(
                "content", "今天适合买入吗",
                "cardId", cardId
        ));
        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // 该 record 应以 intent=question 落盘
        var records = recordRepository.findAll("default");
        assertFalse(records.isEmpty());
        boolean markedQuestion = records.stream()
                .anyMatch(r -> cardId != null && r.intent() != null && "question".equals(r.intent()));
        assertTrue(markedQuestion, "首问带新 cardId 的 record 应落盘 intent=question，rebuild 才能跳过");
    }

    /**
     * 2026-08-26 生产事故回归（REVIEW S-9 关闭项）：
     * 前端 AI 超时断开 → 后端仍跑完 → 用户/前端重发相同问句 → 同一卡片 3 条相同 turns。
     * 同 cardId 短窗口内相同 content 应去重：不 append、不烧 AI，直接返回已有回答。
     */
    @Test
    void createRecord_sameContentResendWithinWindow_deduped() throws Exception {
        String cardId = "card_dup_resend";
        String content = "交易市场如何玩铜呢？";  // 含「？」→ TestAiClient 识别为 question
        String body = mapper.writeValueAsString(Map.of(
                "content", content,
                "cardId", cardId
        ));

        // 第一次：正常提问（card 不存在 → 创建 + answer）
        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("question"));

        // 模拟 answer 已完成：生产上回答结束后卡片最后 turn 是 AI（QuestionAppService 追加），
        // 重发判定必须能穿透 AI turn 找到最近用户问句（2026-08-26 生产验证发现）。
        Optional<CardRecord> afterFirst = cardRepository.findById("default", cardId);
        assertTrue(afterFirst.isPresent(), "首问后卡片应存在");
        CardRecord withAiTurn = afterFirst.get().withTurn(false, "阿呆，回答完毕", "17:36");
        cardRepository.save("default", withAiTurn);

        // 第二次：完全相同内容 + 同 cardId（模拟超时重发）→ 命中去重，不重复 append
        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        Optional<CardRecord> card =
                cardRepository.findById("default", cardId);
        assertTrue(card.isPresent(), "卡片应存在");
        long userTurns = card.get().turns().stream()
                .filter(CardRecord.Turn::isUser)
                .count();
        assertEquals(1, userTurns, "重发不得重复 append 用户问句（生产 08-26 卡片 3 条相同问句）");
        // AI turn 也应保持 1（Answer completed 的那一条），重发不再触发 answer
        long aiTurns = card.get().turns().stream()
                .filter(t -> !t.isUser())
                .count();
        assertEquals(1, aiTurns, "重发不得追加新的 AI 回答");
    }

    /**
     * 去重对称回归：用户追问内容不同（真正的下一轮对话）不得被误杀。
     */
    @Test
    void createRecord_differentContent_appendNormally() throws Exception {
        String cardId = "card_dup_normal";
        String body1 = mapper.writeValueAsString(Map.of(
                "content", "交易市场如何玩铜呢？",
                "cardId", cardId
        ));
        String body2 = mapper.writeValueAsString(Map.of(
                "content", "那纽约铜呢？",
                "cardId", cardId
        ));

        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body1))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body2))
                .andExpect(status().isOk());

        Optional<CardRecord> card =
                cardRepository.findById("default", cardId);
        assertTrue(card.isPresent(), "卡片应存在");
        long userTurns = card.get().turns().stream()
                .filter(CardRecord.Turn::isUser)
                .count();
        assertEquals(2, userTurns, "不同内容的追问应正常 append");
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

    /**
     * #207：AI 成功但摘要 >50 字 → 落盘截断后的真实摘要，不得用 "recorded" 哨兵——
     * 否则 RecordRetryService.alreadyProcessed（判 !"recorded".equals）把它当未处理，
     * 每 15 分钟无限重补烧 AI。修复后"recorded"仅表示真正失败降级。
     */
    @Test
    void createRecord_aiLongSummary_truncatedNotRecordedSentinel() throws Exception {
        // 自定义 AiClient：摘要固定超长（>50 字，模拟 AI 返回长洞察）
        AiClient longSummaryAi = new AiClient() {
            @Override
            public AiUnderstanding understand(ContextPackage contextPackage) {
                return new AiUnderstanding(
                        "这是一条超过五十个字符的长摘要用于验证记录不会因为摘要过长而回退为recorded哨兵导致无限重补循环造成算力浪费",
                        "洞察", null, null,
                        List.of("测试"), "neutral", "life", false, null, "raw");
            }

            @Override
            public String generate(ContextPackage contextPackage, String systemPrompt) {
                return "raw";
            }

            @Override
            public String recognizeIntent(String content) {
                return "log";
            }
        };
        MockMvc mvc = buildMockMvc(longSummaryAi);

        String body = mapper.writeValueAsString(Map.of("content", "测试长摘要"));
        String resp = mvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("log"))
                .andReturn().getResponse().getContentAsString();

        String recordId = mapper.readTree(resp).get("recordId").asText();
        Optional<ContentRecord> saved = recordRepository.findById("default", recordId);
        assertTrue(saved.isPresent(), "记录应保存");
        String persistedSummary = saved.get().summary();
        assertNotNull(persistedSummary, "长摘要不应为 null");
        assertNotEquals("recorded", persistedSummary,
                "AI 成功但长摘要不得落 recorded 哨兵（否则 RetryService 无限重补）");
        assertTrue(persistedSummary.length() <= 50,
                "长摘要应截断到 50 字，实际 " + persistedSummary.length() + " 字");
        assertTrue(persistedSummary.startsWith("这是一条超过五十个字符的长摘要"),
                "截断后应保留真实摘要前缀而非哨兵");
    }

    // ── domain 切换（adai-admin 系统操作台依赖，纯 mock 独立构造）──

    private MockMvc mockRecordMvc(RecordRepository repo, MemoryService mem) {
        // P1-W13：gateDomain 透传（测试默认有插件，domain 原样保留）
        PluginService pluginService = mock(PluginService.class);
        when(pluginService.gateDomain(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        RecordController controller = new RecordController(
                mock(IntentRecognizer.class),
                mock(QuestionAppService.class),
                mock(RecordUnderstandingService.class),
                repo,
                mock(CardFileRepository.class),
                mem,
                mock(RecordToTaskLinker.class),
                pluginService,
                mock(TradeLogCollectService.class));
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void updateDomain_valid_returns204() throws Exception {
        RecordRepository repo = mock(RecordRepository.class);
        MockMvc mvc = mockRecordMvc(repo, mock(MemoryService.class));

        mvc.perform(patch("/api/v1/records/rec_1/domain")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"trading\"}"))
                .andExpect(status().isNoContent());
        verify(repo).updateDomain("default", "rec_1", "trading");
    }

    @Test
    void updateDomain_invalid_returns400() throws Exception {
        RecordRepository repo = mock(RecordRepository.class);
        MockMvc mvc = mockRecordMvc(repo, mock(MemoryService.class));

        mvc.perform(patch("/api/v1/records/rec_1/domain")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"unknown\"}"))
                .andExpect(status().isBadRequest());
    }
}
