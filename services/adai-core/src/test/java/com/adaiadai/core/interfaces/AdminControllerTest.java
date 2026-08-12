package com.adaiadai.core.interfaces;

import com.adaiadai.core.infrastructure.ai.interaction.AiInteractionLog;
import com.adaiadai.core.infrastructure.ai.interaction.AiInteractionLogger;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AdminController 单元测试（data/ 文件树 + os/ 知识浏览）。
 */
class AdminControllerTest {

    @TempDir
    Path dataDir;

    @TempDir
    Path osDir;

    private MockMvc mvc;

    private InMemoryFileStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        // data/ 结构：records/、identity/profile.md、notes.md
        Files.createDirectories(dataDir.resolve("records"));
        Files.createDirectories(dataDir.resolve("default").resolve("memory"));
        Files.createDirectories(dataDir.resolve("default").resolve("identity"));
        Files.writeString(dataDir.resolve("notes.md"), "测试文件内容");
        Files.writeString(dataDir.resolve("default").resolve("identity").resolve("profile.md"), "name: 阿呆");
        // os/ 结构：trading-os/11-context/rules.md
        Files.createDirectories(osDir.resolve("trading-os").resolve("11-context"));
        Files.writeString(osDir.resolve("trading-os").resolve("11-context").resolve("rules.md"), "R1 空仓也是策略");

        storage = new InMemoryFileStorage();
        AiInteractionLogger aiLogger = new AiInteractionLogger(storage, 30);
        mvc = MockMvcBuilders.standaloneSetup(
                new AdminController(dataDir.toString(), osDir.toString(), aiLogger)).build();
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
                        .param("domain", "trading-os")
                        .param("path", "trading-os"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("11-context")));
    }

    @Test
    void listKnowledge_domainOnly_listsRoot() throws Exception {
        // 不传 path 时列出 os/ 根（含各 domain 目录）
        mvc.perform(get("/api/v1/admin/knowledge").param("domain", "trading-os"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("trading-os")));
    }

    @Test
    void getKnowledgeContent_returnsContent() throws Exception {
        mvc.perform(get("/api/v1/admin/knowledge/content").param("path", "trading-os/11-context/rules.md"))
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
                "处理一条新记录。", 50, "ok", null, 200, "summary=测试"));
        logger.log("adai", new AiInteractionLog(
                "trace-2", "2026-08-12T10:01:00", 80L, "adai",
                "generate", "trading", "rec_2", null, "trading_review", "deepseek",
                "复盘模板...", 100, "ok", null, 300, "复盘正文..."));

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
                    "prompt-" + i, 50, "ok", null, 200, "summary=" + i));
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
}
