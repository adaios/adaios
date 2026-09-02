package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.AuthService;
import com.adaiadai.core.infrastructure.security.AuthFilter;
import com.adaiadai.core.kernel.auth.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AuthFilter 安全矩阵（RFC 20260901-auth-login 核心验证）。
 * <p>
 * 验证四条红线：
 * 1. 无 token → 401（fail-closed，不再信任裸 X-User-Id）
 * 2. 伪造 X-User-Id + 无 token → 401
 * 3. 有效 token → 放行，且 X-User-Id 被覆盖为会话 userId（伪造 header 无效）
 * 4. login/setup 免鉴权；admin 路径由 X-Admin-Token 体系接管；OPTIONS 放行
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
        mvc = MockMvcBuilders.standaloneSetup(new EchoUserIdController())
                .addFilter(new AuthFilter(authService))
                .build();
    }

    private Session validSession(String userId) {
        Instant now = Instant.now();
        return new Session("tok_1", userId, now, now, now.plusSeconds(Session.DEFAULT_TTL_SECONDS));
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

    // ── 红线 3：有效 token → 放行 + X-User-Id 覆盖 ──

    @Test
    void validToken_passesThrough() throws Exception {
        when(authService.validateAndTouch("tok_1")).thenReturn(Optional.of(validSession("adai")));

        mvc.perform(get("/api/v1/test/echo").header("Authorization", "Bearer tok_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedUserId").value("adai"));
    }

    @Test
    void validToken_overridesForgedUserIdHeader() throws Exception {
        // 客户端同时带有效 token 和伪造 userId → 覆盖为会话 userId（根治 #179 的关键断言）
        when(authService.validateAndTouch("tok_1")).thenReturn(Optional.of(validSession("adai")));

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
        // filter 不拦截 login：请求到达 AuthController（mock login 返回结果 → 200）
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

    @Test
    void accountsAvailable_requiresLogin() throws Exception {
        // 决策 4（RFC 20260901）：available 不再免鉴权——封 userId 枚举面
        when(authService.validateAndTouch(anyString())).thenReturn(Optional.empty());
        when(authService.validateAndTouch("tok_1")).thenReturn(Optional.of(validSession("adai")));

        // 无 token → 401（走 AuthFilter 鉴权）
        mvc.perform(get("/api/v1/accounts/available"))
                .andExpect(status().isUnauthorized());
        // 带有效 token → filter 放行（standaloneSetup 无 AccountController → 404 而非 401，证明未被拦）
        mvc.perform(get("/api/v1/accounts/available").header("Authorization", "Bearer tok_1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminEndpoints_notHandledByAuthFilter() throws Exception {
        // admin 端点由 AdminAuthInterceptor（X-Admin-Token）接管，AuthFilter 不拦
        when(authService.validateAndTouch(anyString())).thenReturn(Optional.empty());
        // standaloneSetup 无 admin controller → 404（证明未走 AuthFilter 的 401 语义）
        mvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isNotFound());
    }
}
