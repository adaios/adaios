package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.RecordFlowAppService;
import com.adaiadai.core.application.RecordRetryService;
import com.adaiadai.core.application.TradingAppService;
import com.adaiadai.core.infrastructure.ai.interaction.AiInteractionLogger;
import com.adaiadai.core.infrastructure.security.AdminAuthInterceptor;
import com.adaiadai.core.infrastructure.storage.CardMigrationService;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.RecordRepository;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REVIEW #127 管理端点鉴权测试（AdminAuthInterceptor）。
 * <p>
 * 现有 Admin/Account 控制器测试用 standaloneSetup（不挂拦截器）直测业务逻辑；
 * 本测试专门验证拦截器本身：缺 token / 错 token / 未配置 fail-closed / 正确 token。
 * 路径模式（仅 /api/v1/admin/** 与 /api/v1/accounts/**）在 {@code WebConfig} 注册。
 * <p>
 * REVIEW P-be-01：维护类端点（records/retry、memory/rebuild、memory/{id} PATCH、
 * cards/cleanup、trading/knowledge/conflicts）已迁入 /api/v1/admin/**——同样受拦截器保护，
 * 缺 token 401 / 带正确 token 200（见下方 adminMaintenanceEndpoints_* 测试）。
 */
class AdminAuthInterceptorTest {

    @TempDir
    Path dataDir;

    /** 构造带默认 mock 的 AdminController（维护端点不因 mock 默认值抛异常）。 */
    private AdminController adminController() {
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.update(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        RecordRepository recordRepository = mock(RecordRepository.class);
        when(recordRepository.findAll(any())).thenReturn(List.of());
        CardMigrationService migration = mock(CardMigrationService.class);
        when(migration.cleanupDuplicateRecords(any()))
                .thenReturn(new CardMigrationService.CleanupResult(0, List.of(), List.of()));
        TradingAppService trading = mock(TradingAppService.class);
        when(trading.getPositions(any())).thenReturn(List.of());
        return new AdminController(dataDir.toString(), dataDir.toString(),
                new AiInteractionLogger(new InMemoryFileStorage(), 30),
                memoryService, recordRepository, mock(RecordFlowAppService.class),
                mock(RecordRetryService.class), migration, trading);
    }

    private MockMvc adminMvc(String token) throws Exception {
        Files.createDirectories(dataDir.resolve("default"));
        return MockMvcBuilders
                .standaloneSetup(adminController())
                .addInterceptors(new AdminAuthInterceptor(token))
                .build();
    }

    @Test
    void admin_withoutToken_returns401() throws Exception {
        MockMvc mvc = adminMvc("secret");
        mvc.perform(get("/api/v1/admin/files"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());

        // accounts 端点同样被拦截（独立 MockMvc 映射 AccountController）
        MockMvc accountMvc = MockMvcBuilders
                .standaloneSetup(new AccountController(mock(AccountRepository.class), new PluginRegistry(), mock(PluginService.class)))
                .addInterceptors(new AdminAuthInterceptor("secret"))
                .build();
        accountMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void admin_withWrongToken_returns401() throws Exception {
        MockMvc mvc = adminMvc("secret");
        mvc.perform(get("/api/v1/admin/files").header("X-Admin-Token", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void admin_optionsPreflight_passesWithoutToken() throws Exception {
        // 生产 CORS 事故回归（2026-08-11）：8083 调 /api/v1/accounts 预检 OPTIONS 无
        // X-Admin-Token → 拦截器 401 → CORS 报 "does not have HTTP ok status"。
        // 预检必须放行（浏览器不发鉴权头到预检），由 CORS 处理器响应。
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor("secret");
        var request = mock(jakarta.servlet.http.HttpServletRequest.class);
        when(request.getMethod()).thenReturn("OPTIONS");
        var response = mock(jakarta.servlet.http.HttpServletResponse.class);
        assertTrue(interceptor.preHandle(request, response, null),
                "OPTIONS 预检应放行，不校验 X-Admin-Token");
        // 未放行的话会写 401 —— 断言响应从未被写入错误
        org.mockito.Mockito.verify(response, org.mockito.Mockito.never())
                .setStatus(org.mockito.Mockito.anyInt());
    }

    @Test
    void admin_withCorrectToken_passes() throws Exception {
        MockMvc mvc = adminMvc("secret");
        mvc.perform(get("/api/v1/admin/files").header("X-Admin-Token", "secret"))
                .andExpect(status().isOk());
    }

    @Test
    void admin_tokenNotConfigured_failsClosed() throws Exception {
        MockMvc mvc = adminMvc("");
        // 即使带了 token，服务端未配置时也拒绝（防生产误部署裸奔）
        mvc.perform(get("/api/v1/admin/files").header("X-Admin-Token", "secret"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());
    }

    // ── REVIEW P-be-01：维护端点迁入 /admin/** 后的鉴权 ──

    @Test
    void adminMaintenanceEndpoints_withoutToken_401() throws Exception {
        MockMvc mvc = adminMvc("secret");

        mvc.perform(post("/api/v1/admin/records/retry"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/memory/rebuild"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/admin/memory/m1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/cards/cleanup"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/trading/knowledge/conflicts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminMaintenanceEndpoints_withCorrectToken_200() throws Exception {
        MockMvc mvc = adminMvc("secret");

        mvc.perform(post("/api/v1/admin/records/retry").header("X-Admin-Token", "secret"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/memory/rebuild").header("X-Admin-Token", "secret"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/admin/memory/m1")
                        .header("X-Admin-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/cards/cleanup").header("X-Admin-Token", "secret"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/trading/knowledge/conflicts").header("X-Admin-Token", "secret"))
                .andExpect(status().isOk());
    }

    @Test
    void adminMaintenanceEndpoints_tokenNotConfigured_failsClosed() throws Exception {
        MockMvc mvc = adminMvc("");
        mvc.perform(post("/api/v1/admin/records/retry").header("X-Admin-Token", "secret"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/v1/admin/trading/knowledge/conflicts").header("X-Admin-Token", "secret"))
                .andExpect(status().isServiceUnavailable());
    }
}
