package com.adaiadai.core.interfaces;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

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

        mvc = MockMvcBuilders.standaloneSetup(
                new AdminController(dataDir.toString(), osDir.toString())).build();
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
}
