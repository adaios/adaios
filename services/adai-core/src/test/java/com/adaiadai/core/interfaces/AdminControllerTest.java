package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.RecordFlowAppService;
import com.adaiadai.core.application.RecordRetryService;
import com.adaiadai.core.application.TradingAppService;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.infrastructure.ai.interaction.AiInteractionLog;
import com.adaiadai.core.infrastructure.ai.interaction.AiInteractionLogger;
import com.adaiadai.core.infrastructure.storage.CardMigrationService;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AdminController 单元测试（data/ 文件树 + os/ 知识浏览 + 维护操作端点）。
 * <p>
 * REVIEW P-be-01：维护类端点（records/retry、memory/rebuild、memory/{id} PATCH、
 * cards/cleanup、trading/knowledge/conflicts）已从 per-user 路径迁入 /api/v1/admin/**，
 * 本测试覆盖其业务逻辑（鉴权由 AdminAuthInterceptorTest 覆盖）。
 */
class AdminControllerTest {

    @TempDir
    Path dataDir;

    @TempDir
    Path osDir;

    private MockMvc mvc;

    private InMemoryFileStorage storage;

    /** 维护端点默认 mock（避免业务测试因 mock 默认值 NPE）。 */
    private AdminController adminController(MemoryService memoryService,
                                            RecordRepository recordRepository,
                                            RecordFlowAppService flow,
                                            RecordRetryService retry,
                                            CardMigrationService migration,
                                            TradingAppService trading) {
        AiInteractionLogger aiLogger = new AiInteractionLogger(storage, 30);
        return new AdminController(dataDir.toString(), osDir.toString(), aiLogger,
                memoryService, recordRepository, flow, retry, migration, trading);
    }

    private AdminController adminController() {
        return adminController(mock(MemoryService.class), mock(RecordRepository.class),
                mock(RecordFlowAppService.class), mock(RecordRetryService.class),
                mock(CardMigrationService.class), mock(TradingAppService.class));
    }

    @BeforeEach
    void setUp() throws Exception {
        // data/ 结构：records/、identity/profile.md、notes.md
        Files.createDirectories(dataDir.resolve("records"));
        Files.createDirectories(dataDir.resolve("default").resolve("memory"));
        Files.createDirectories(dataDir.resolve("default").resolve("identity"));
        Files.writeString(dataDir.resolve("notes.md"), "测试文件内容");
        Files.writeString(dataDir.resolve("default").resolve("identity").resolve("profile.md"), "name: 阿呆");
        // os/ 结构：trading-engine/11-context/rules.md
        Files.createDirectories(osDir.resolve("trading-engine").resolve("11-context"));
        Files.writeString(osDir.resolve("trading-engine").resolve("11-context").resolve("rules.md"), "R1 空仓也是策略");

        storage = new InMemoryFileStorage();
        mvc = MockMvcBuilders.standaloneSetup(adminController()).build();
    }

    @Test
    void listFiles_returnsDirEntries() throws Exception {
        mvc.perform(get("/api/v1/admin/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].name", hasItem("notes.md")))
                .andExpect(jsonPath("$[*].name", hasItem("records")))
                .andExpect(jsonPath("$[*].name", hasItem("default")));
    }

    @Test
    void listFiles_subdirPath() throws Exception {
        mvc.perform(get("/api/v1/admin/files").param("path", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("memory")));
    }

    @Test
    void listFiles_missingDir_404() throws Exception {
        mvc.perform(get("/api/v1/admin/files").param("path", "no_such_dir"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listFiles_pathTraversal_400() throws Exception {
        mvc.perform(get("/api/v1/admin/files").param("path", "../"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getFileContent_returnsContent() throws Exception {
        mvc.perform(get("/api/v1/admin/files/content").param("path", "notes.md"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("测试文件内容"))
                .andExpect(jsonPath("$.size").isNumber());
    }

    @Test
    void getFileContent_missing_404() throws Exception {
        mvc.perform(get("/api/v1/admin/files/content").param("path", "ghost.md"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listKnowledge_invalidDomain_400() throws Exception {
        mvc.perform(get("/api/v1/admin/knowledge").param("domain", "hack-os"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listKnowledge_validDomain_returnsEntries() throws Exception {
        mvc.perform(get("/api/v1/admin/knowledge")
                        .param("domain", "trading-engine")
                        .param("path", "trading-engine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("11-context")));
    }

    @Test
    void listKnowledge_domainOnly_listsRoot() throws Exception {
        // 不传 path 时列出 os/ 根（含各 domain 目录）
        mvc.perform(get("/api/v1/admin/knowledge").param("domain", "trading-engine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("trading-engine")));
    }

    @Test
    void getKnowledgeContent_returnsContent() throws Exception {
        mvc.perform(get("/api/v1/admin/knowledge/content").param("path", "trading-engine/11-context/rules.md"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("R1 空仓也是策略"));
    }

    @Test
    void getKnowledgeContent_traversal_400() throws Exception {
        mvc.perform(get("/api/v1/admin/knowledge/content").param("path", "../secret.txt"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aiLogs_readsLoggedEntries() throws Exception {
        AiInteractionLogger logger = new AiInteractionLogger(storage, 30);
        logger.log("adai", new AiInteractionLog(
                "trace-1", "2026-08-12T10:00:00", 120L, "adai",
                "understand", "note", "rec_1", null, "record", "deepseek",
                "处理一条新记录。", null, 50, "ok", null, 200, "summary=测试"));
        logger.log("adai", new AiInteractionLog(
                "trace-2", "2026-08-12T10:01:00", 80L, "adai",
                "generate", "trading", "rec_2", null, "trading_review", "deepseek",
                "复盘模板...", "复盘 system 指令", 100, "ok", null, 300, "复盘正文..."));

        mvc.perform(get("/api/v1/admin/ai-logs")
                        .param("userId", "adai")
                        .param("date", LocalDate.now().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.logs[0].traceId").value("trace-1"))
                .andExpect(jsonPath("$.logs[0].recordId").value("rec_1"))
                .andExpect(jsonPath("$.logs[0].prompt").value("处理一条新记录。"))
                .andExpect(jsonPath("$.logs[1].kind").value("generate"));
    }

    @Test
    void aiLogs_invalidDate_400() throws Exception {
        mvc.perform(get("/api/v1/admin/ai-logs").param("date", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aiLogs_noLogs_returnsEmpty() throws Exception {
        mvc.perform(get("/api/v1/admin/ai-logs").param("userId", "adai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    // ── #210 读取治理：日期上界 + 分页 + size 上限 ──

    @Test
    void aiLogs_expiredDate_400() throws Exception {
        // 早于保留期（30 天）的日志已被清理，拒绝查询（防扫任意历史）
        mvc.perform(get("/api/v1/admin/ai-logs")
                        .param("userId", "adai")
                        .param("date", LocalDate.now().minusDays(31).toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aiLogs_pagination_returnsPageAndTotal() throws Exception {
        AiInteractionLogger logger = new AiInteractionLogger(storage, 30);
        for (int i = 1; i <= 3; i++) {
            logger.log("adai", new AiInteractionLog(
                    "trace-" + i, "2026-08-12T10:00:00", 100L, "adai",
                    "understand", "note", "rec_" + i, null, "record", "deepseek",
                    "prompt-" + i, null, 50, "ok", null, 200, "summary=" + i));
        }

        mvc.perform(get("/api/v1/admin/ai-logs")
                        .param("userId", "adai")
                        .param("date", LocalDate.now().toString())
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.logs.length()").value(2));

        mvc.perform(get("/api/v1/admin/ai-logs")
                        .param("userId", "adai")
                        .param("date", LocalDate.now().toString())
                        .param("page", "2")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.logs[0].traceId").value("trace-3"));
    }

    @Test
    void aiLogs_sizeAboveMax_clampedTo500() throws Exception {
        mvc.perform(get("/api/v1/admin/ai-logs")
                        .param("userId", "adai")
                        .param("date", LocalDate.now().toString())
                        .param("size", "9999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(500));
    }

    // ── 维护操作端点（REVIEW P-be-01：自 per-user 路径迁入 /api/v1/admin/**）──

    @Test
    void triggerRetry_returnsCountDelta() throws Exception {
        MemoryService mem = mock(MemoryService.class);
        when(mem.count(any())).thenReturn(2L, 5L);
        RecordRetryService retry = mock(RecordRetryService.class);
        MockMvc adminMvc = MockMvcBuilders.standaloneSetup(
                adminController(mem, mock(RecordRepository.class), mock(RecordFlowAppService.class),
                        retry, mock(CardMigrationService.class), mock(TradingAppService.class)))
                .build();

        adminMvc.perform(post("/api/v1/admin/records/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.memoriesBefore").value(2))
                .andExpect(jsonPath("$.memoriesAfter").value(5))
                .andExpect(jsonPath("$.newMemories").value(3));
        verify(retry).retryUnprocessed("default");
    }

    @Test
    void triggerRetry_forwardsUserIdParam() throws Exception {
        MemoryService mem = mock(MemoryService.class);
        when(mem.count(any())).thenReturn(0L, 0L);
        RecordRetryService retry = mock(RecordRetryService.class);
        MockMvc adminMvc = MockMvcBuilders.standaloneSetup(
                adminController(mem, mock(RecordRepository.class), mock(RecordFlowAppService.class),
                        retry, mock(CardMigrationService.class), mock(TradingAppService.class)))
                .build();

        adminMvc.perform(post("/api/v1/admin/records/retry").param("userId", "alice"))
                .andExpect(status().isOk());
        verify(retry).retryUnprocessed("alice");
    }

    @Test
    void rebuild_returnsOk() throws Exception {
        RecordRepository recordRepository = mock(RecordRepository.class);
        when(recordRepository.findAll(any())).thenReturn(List.of());
        MockMvc adminMvc = MockMvcBuilders.standaloneSetup(
                adminController(mock(MemoryService.class), recordRepository,
                        mock(RecordFlowAppService.class), mock(RecordRetryService.class),
                        mock(CardMigrationService.class), mock(TradingAppService.class)))
                .build();

        adminMvc.perform(post("/api/v1/admin/memory/rebuild"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").isNumber());
    }

    @Test
    void rebuild_skipsProcessedFactOnly_degradedAndBlankReprocessed() throws Exception {
        // #144 幂等：已处理（summary 非空白）且无降级记忆 → 不重跑；
        // 降级记忆 → 重跑升级；未处理（summary 空白）→ 重跑；question → 排除
        ContentRecord processed = new ContentRecord("rec_proc", "note", "user_input", "t", "已处理内容",
                List.of(), LocalDateTime.of(2026, 8, 1, 9, 0), "log", "已处理", "life");
        ContentRecord degraded = new ContentRecord("rec_degraded", "note", "user_input", "t", "降级内容",
                List.of(), LocalDateTime.of(2026, 8, 1, 9, 1), "log", "recorded", "life");
        ContentRecord unprocessed = new ContentRecord("rec_new", "note", "user_input", "t", "未处理内容",
                List.of(), LocalDateTime.of(2026, 8, 1, 9, 2), null, null, "life");
        ContentRecord question = new ContentRecord("rec_q", "note", "user_input", "t", "问句",
                List.of(), LocalDateTime.of(2026, 8, 1, 9, 3), "question", null, "life");

        RecordRepository recordRepository = mock(RecordRepository.class);
        when(recordRepository.findAll(any())).thenReturn(List.of(processed, degraded, unprocessed, question));
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.hasDegradedMemory(any(), eq("rec_degraded"))).thenReturn(true);
        RecordFlowAppService flow = mock(RecordFlowAppService.class);
        when(flow.process(any(), any())).thenAnswer(inv -> {
            ContentRecord r = inv.getArgument(1);
            return new RecordFlowAppService.FlowResult(r.id(), "mem_" + r.id(), null, 0);
        });

        MockMvc adminMvc = MockMvcBuilders.standaloneSetup(
                adminController(memoryService, recordRepository, flow,
                        mock(RecordRetryService.class), mock(CardMigrationService.class),
                        mock(TradingAppService.class)))
                .build();
        adminMvc.perform(post("/api/v1/admin/memory/rebuild"))
                .andExpect(status().isOk());

        ArgumentCaptor<ContentRecord> captor = ArgumentCaptor.forClass(ContentRecord.class);
        verify(flow, times(2)).process(any(), captor.capture());
        List<String> processedIds = captor.getAllValues().stream().map(ContentRecord::id).toList();
        assertTrue(processedIds.contains("rec_degraded"), "降级记忆应重跑以升级");
        assertTrue(processedIds.contains("rec_new"), "未处理记录应重建");
        assertFalse(processedIds.contains("rec_proc"), "已处理 fact-only 记录不应重跑烧 AI");
        assertFalse(processedIds.contains("rec_q"), "question 记录不应进入 rebuild");
    }

    @Test
    void updateMemory_returnsOk() throws Exception {
        MemoryService mem = mock(MemoryService.class);
        when(mem.update(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        MockMvc adminMvc = MockMvcBuilders.standaloneSetup(
                adminController(mem, mock(RecordRepository.class), mock(RecordFlowAppService.class),
                        mock(RecordRetryService.class), mock(CardMigrationService.class),
                        mock(TradingAppService.class)))
                .build();

        adminMvc.perform(patch("/api/v1/admin/memory/m1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"fact\",\"summary\":\"新摘要\",\"tags\":[\"a\"],\"actionable\":true,\"suggestion\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void updateMemory_notFound_returns404() throws Exception {
        MemoryService mem = mock(MemoryService.class);
        when(mem.update(any(), any(), any(), any(), any(), any(), any())).thenReturn(false);
        MockMvc adminMvc = MockMvcBuilders.standaloneSetup(
                adminController(mem, mock(RecordRepository.class), mock(RecordFlowAppService.class),
                        mock(RecordRetryService.class), mock(CardMigrationService.class),
                        mock(TradingAppService.class)))
                .build();

        adminMvc.perform(patch("/api/v1/admin/memory/ghost")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cleanupRecords_returnsDeleted() throws Exception {
        CardMigrationService migration = mock(CardMigrationService.class);
        when(migration.cleanupDuplicateRecords(any()))
                .thenReturn(new CardMigrationService.CleanupResult(3, List.of("card_1"), List.of("card_2")));
        MockMvc adminMvc = MockMvcBuilders.standaloneSetup(
                adminController(mock(MemoryService.class), mock(RecordRepository.class),
                        mock(RecordFlowAppService.class), mock(RecordRetryService.class),
                        migration, mock(TradingAppService.class)))
                .build();

        adminMvc.perform(post("/api/v1/admin/cards/cleanup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(3))
                .andExpect(jsonPath("$.deletedFiles[0]").value("card_1"))
                .andExpect(jsonPath("$.skippedFiles[0]").value("card_2"));
    }

    // ── 规则冲突检测（自 TradingController 迁入，依赖真实 rules.md，同原覆盖）──

    @Test
    void detectConflicts_noPositions_citesRealRule() throws Exception {
        TradingAppService trading = mock(TradingAppService.class);
        when(trading.getPositions(any())).thenReturn(List.of());
        MockMvc adminMvc = MockMvcBuilders.standaloneSetup(
                adminController(mock(MemoryService.class), mock(RecordRepository.class),
                        mock(RecordFlowAppService.class), mock(RecordRetryService.class),
                        mock(CardMigrationService.class), trading))
                .build();

        adminMvc.perform(get("/api/v1/admin/trading/knowledge/conflicts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflicts.length()").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.conflicts[0].rule").value(containsString("R")))
                .andExpect(jsonPath("$.conflicts[0].description").value(containsString("空仓")));
    }

    @Test
    void detectConflicts_singlePosition_citesR96() throws Exception {
        Position single = new Position("600000", "浦发银行", 1000,
                new BigDecimal("10.00"), new BigDecimal("10.50"), LocalDateTime.now());
        TradingAppService trading = mock(TradingAppService.class);
        when(trading.getPositions(any())).thenReturn(List.of(single));
        MockMvc adminMvc = MockMvcBuilders.standaloneSetup(
                adminController(mock(MemoryService.class), mock(RecordRepository.class),
                        mock(RecordFlowAppService.class), mock(RecordRetryService.class),
                        mock(CardMigrationService.class), trading))
                .build();

        adminMvc.perform(get("/api/v1/admin/trading/knowledge/conflicts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflicts.length()").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.conflicts[0].rule").value(containsString("R96")));
    }
}
