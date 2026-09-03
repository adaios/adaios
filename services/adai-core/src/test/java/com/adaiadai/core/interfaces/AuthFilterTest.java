package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.AuthService;
import com.adaiadai.core.infrastructure.WebConfig;
import com.adaiadai.core.infrastructure.security.AuthFilter;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.auth.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AuthFilter 安全矩阵（RFC 20260901-auth-login 核心验证 + REVIEW #178 admin 收编）。
 * <p>
 * 验证红线：
 * 1. 无 token → 401（fail-closed，不再信任裸 X-User-Id）
 * 2. 伪造 X-User-Id + 无 token → 401
 * 3. 有效 token → 放行；user 会话 X-User-Id 覆盖为会话 userId（伪造 header 无效）
 * 4. login/setup 免鉴权；OPTIONS 放行
 * 5.（#178）admin 范围（/admin/**、/accounts/**，available 例外）：无 token 401 / user 会话 403 /
 *    admin 会话放行——X-Admin-Token 退役，管理口并入统一登录
 * 6.（#178）admin 会话 X-User-Id 透传（adai-admin 用户切换器跨账号治理浏览）；user 会话一律覆盖
 */
class AuthFilterTest {

    private AuthService authService;
    private MockMvc mvc;

    /** 测试用控制器：回显收到的 X-User-Id（验证 filter 覆盖生效）。 */
    @RestController
    @RequestMapping("/api/v1/test")
    static class EchoUserIdController {
        @GetMapping("/echo")
        public Map<String, String> echo(@RequestHeader(value = "X-User-Id", defaultValue = "none") String userId) {
            return Map.of("receivedUserId", userId);
        }
    }

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        when(authService.findAccount(anyString())).thenReturn(Optional.empty());
        // 2026-09-04：链前加 CorsFilter（对应生产 FilterRegistrationBean 最高优先级注册）——
        // 回归红线：AuthFilter 的 401/403 响应必须带 CORS 头，浏览器才不把鉴权失败误报为 CORS 错误。
        mvc = MockMvcBuilders.standaloneSetup(new EchoUserIdController())
                .addFilter(new WebConfig().corsFilter().getFilter())
                .addFilter(new AuthFilter(authService))
                .build();
    }

    private Session validSession(String userId) {
        Instant now = Instant.now();
        return new Session("tok_1", userId, now, now, now.plusSeconds(Session.DEFAULT_TTL_SECONDS));
    }

    private Account account(String userId, String role) {
        return new Account(userId, role, true, LocalDate.of(2026, 8, 2));
    }

    private void stubSession(String token, String userId) {
        when(authService.validateAndTouch(token)).thenReturn(Optional.of(validSession(userId)));
    }

    // ── 红线 1：无 token → 401 ──

    @Test
    void noToken_returns401() throws Exception {
        when(authService.validateAndTouch(anyString())).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/test/echo"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或会话已失效，请先登录"));
    }

    // ── 红线 2：伪造 X-User-Id 无 token → 401 ──

    @Test
    void forgedUserIdWithoutToken_returns401() throws Exception {
        when(authService.validateAndTouch(anyString())).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/test/echo").header("X-User-Id", "victim"))
                .andExpect(status().isUnauthorized());
    }

    // ── 红线 3：有效 token → 放行 + X-User-Id 覆盖（user 会话）──

    @Test
    void validToken_passesThrough() throws Exception {
        stubSession("tok_1", "adai");

        mvc.perform(get("/api/v1/test/echo").header("Authorization", "Bearer tok_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedUserId").value("adai"));
    }

    @Test
    void validToken_userSession_overridesForgedUserIdHeader() throws Exception {
        // user 会话带有效 token + 伪造 userId → 覆盖为会话 userId（根治 #179 的关键断言）
        stubSession("tok_1", "adai");
        when(authService.findAccount("adai")).thenReturn(Optional.of(account("adai", Account.ROLE_USER)));

        mvc.perform(get("/api/v1/test/echo")
                        .header("Authorization", "Bearer tok_1")
                        .header("X-User-Id", "victim"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedUserId").value("adai"));
    }

    @Test
    void expiredToken_returns401() throws Exception {
        Instant past = Instant.now().minusSeconds(100);
        Session expired = new Session("tok_old", "adai", past, past, past);
        when(authService.validateAndTouch("tok_old")).thenReturn(Optional.of(expired));

        mvc.perform(get("/api/v1/test/echo").header("Authorization", "Bearer tok_old"))
                .andExpect(status().isUnauthorized());
    }

    // ── 红线 4：白名单 ──

    @Test
    void loginEndpoint_isExempt() throws Exception {
        when(authService.login(anyString(), anyString(), any()))
                .thenReturn(new AuthService.LoginResult("tok", "adai", "admin",
                        java.util.List.of(), Instant.now().plusSeconds(3600)));
        MockMvc loginMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilter(new AuthFilter(authService))
                .build();

        loginMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"adai\",\"password\":\"x\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void setupEndpoint_isExempt() throws Exception {
        when(authService.validateAndTouch(anyString())).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/auth/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound()); // 到达 AuthController（mock setup=false → 404）
    }

    @Test
    void optionsPreflight_isExempt() throws Exception {
        when(authService.validateAndTouch(anyString())).thenReturn(Optional.empty());

        mvc.perform(options("/api/v1/test/echo"))
                .andExpect(status().isOk()); // 未被 filter 拦截（controller 对 OPTIONS 默认放行）
    }

    @Test
    void nonApiPath_isExempt() throws Exception {
        when(authService.validateAndTouch(anyString())).thenReturn(Optional.empty());

        mvc.perform(get("/favicon.ico"))
                .andExpect(status().isNotFound()); // 非 /api/**，filter 不拦
    }

    // ── 红线 5（#178）：admin 范围并入统一登录（X-Admin-Token 退役）──
    // standaloneSetup 未注册 Admin/Account controller → 放行后为 404；401/403 证明被 filter 拦下。

    @Test
    void adminEndpoint_withoutToken_returns401() throws Exception {
        when(authService.validateAndTouch(anyString())).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminEndpoint_userRole_returns403() throws Exception {
        // 登录了但是普通用户 → 管理端点 403（#178 role=admin 门禁）
        stubSession("tok_1", "alice");
        when(authService.findAccount("alice")).thenReturn(Optional.of(account("alice", Account.ROLE_USER)));

        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer tok_1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("仅管理员账号可访问，请使用 admin 账号登录"));
    }

    @Test
    void adminEndpoint_adminRole_passes() throws Exception {
        stubSession("tok_1", "adai");
        when(authService.findAccount("adai")).thenReturn(Optional.of(account("adai", Account.ROLE_ADMIN)));

        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer tok_1"))
                .andExpect(status().isNotFound()); // 未注册 AdminController → 404，证明未被 filter 拦
    }

    @Test
    void accountsListEndpoint_userRole_returns403() throws Exception {
        stubSession("tok_1", "alice");
        when(authService.findAccount("alice")).thenReturn(Optional.of(account("alice", Account.ROLE_USER)));

        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer tok_1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void accountsListEndpoint_adminRole_passes() throws Exception {
        stubSession("tok_1", "adai");
        when(authService.findAccount("adai")).thenReturn(Optional.of(account("adai", Account.ROLE_ADMIN)));

        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer tok_1"))
                .andExpect(status().isNotFound()); // 未注册 AccountController → 404，证明未被拦
    }

    @Test
    void adminBasePath_withoutSlash_adminRole_passes() throws Exception {
        // 对称回归（2026-09-02 根因）：无尾斜杠精确路径也走统一门禁
        stubSession("tok_1", "adai");
        when(authService.findAccount("adai")).thenReturn(Optional.of(account("adai", Account.ROLE_ADMIN)));

        mvc.perform(get("/api/v1/admin").header("Authorization", "Bearer tok_1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void accountsAvailable_anyLogin_passes() throws Exception {
        // available 是 admin 范围例外（产品端遗留选号）：登录即可，普通用户不被 403
        stubSession("tok_1", "alice");
        when(authService.findAccount("alice")).thenReturn(Optional.of(account("alice", Account.ROLE_USER)));

        mvc.perform(get("/api/v1/accounts/available").header("Authorization", "Bearer tok_1"))
                .andExpect(status().isNotFound()); // 未注册 AccountController → 404，证明未被拦

        // 无 token → 401（仍需登录，RFC 决策 4：封 userId 枚举面）
        when(authService.validateAndTouch(anyString())).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/accounts/available"))
                .andExpect(status().isUnauthorized());
    }

    // ── 红线 6（#178）：admin 会话 X-User-Id 透传（adai-admin 用户切换器）──

    @Test
    void adminSession_passesThroughClientUserId() throws Exception {
        // admin 登录控制台，浏览其他用户数据 → X-User-Id 保留客户端指定（治理只读）
        stubSession("tok_1", "adai");
        when(authService.findAccount("adai")).thenReturn(Optional.of(account("adai", Account.ROLE_ADMIN)));

        mvc.perform(get("/api/v1/test/echo")
                        .header("Authorization", "Bearer tok_1")
                        .header("X-User-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedUserId").value("alice"));
    }

    @Test
    void adminSession_withoutClientUserId_usesSessionId() throws Exception {
        stubSession("tok_1", "adai");
        when(authService.findAccount("adai")).thenReturn(Optional.of(account("adai", Account.ROLE_ADMIN)));

        mvc.perform(get("/api/v1/test/echo").header("Authorization", "Bearer tok_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedUserId").value("adai"));
    }

    @Test
    void userSession_alwaysOverriddenEvenOnAdminScope() throws Exception {
        // user 会话即使带了目标 userId 头也不能越权浏览他人数据（覆盖为会话自身）
        stubSession("tok_1", "alice");
        when(authService.findAccount("alice")).thenReturn(Optional.of(account("alice", Account.ROLE_USER)));

        mvc.perform(get("/api/v1/test/echo")
                        .header("Authorization", "Bearer tok_1")
                        .header("X-User-Id", "victim"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedUserId").value("alice"));
    }

    // ── 红线 7（2026-09-04 线上事故根因）：401/403 响应必须带 CORS 头 ──
    // 原 AuthFilter @Order(LOWEST_PRECEDENCE + 1) 整数溢出成 MIN_VALUE → 反跑在
    // CorsFilter 之前 → 401/403 无 Access-Control-Allow-Origin → 浏览器把未登录/无权限
    // 误报为 CORS policy 错误（admin 打开即报错刷屏）。修复后 CorsFilter 最高优先级先写头。

    @Test
    void noToken401_carriesCorsHeader() throws Exception {
        when(authService.validateAndTouch(anyString())).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/test/echo").header("Origin", "http://localhost:8082"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8082"));
    }

    @Test
    void userRole403_carriesCorsHeader() throws Exception {
        stubSession("tok_1", "alice");
        when(authService.findAccount("alice")).thenReturn(Optional.of(account("alice", Account.ROLE_USER)));

        mvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer tok_1")
                        .header("Origin", "http://localhost:8082"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8082"));
    }

    @Test
    void authFilterOrder_mustStayAfterCorsFilter() throws Exception {
        // 顺序契约（防历史坑复犯）：AuthFilter 的 order 必须高于（晚于）CorsFilter 注册的
        // HIGHEST_PRECEDENCE；LOWEST_PRECEDENCE + 1 会溢出为 MIN_VALUE（=HIGHEST_PRECEDENCE）反超。
        int authOrder = AuthFilter.class.getAnnotation(Order.class).value();
        assertTrue(authOrder > Ordered.HIGHEST_PRECEDENCE,
                "AuthFilter order 必须晚于 CorsFilter（HIGHEST_PRECEDENCE），否则 401/403 丢失 CORS 头");
        assertNotEquals(Ordered.LOWEST_PRECEDENCE + 1, authOrder,
                "LOWEST_PRECEDENCE + 1 整数溢出（Integer.MAX_VALUE+1 → MIN_VALUE）为历史坑，勿复犯");
    }
}
