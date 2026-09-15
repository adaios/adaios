package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.ApiTokenService;
import com.adaiadai.core.application.AuthService;
import com.adaiadai.core.application.AuthService.AuthException;
import com.adaiadai.core.application.AuthService.LoginResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AuthController unit tests（RFC 20260901-auth-login）。
 * 覆盖：登录成功/失败/未设密码/限流、setup 一次性、改密、me、logout。
 */
class AuthControllerTest {

    private AuthService authService;
    private ApiTokenService apiTokenService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        apiTokenService = mock(ApiTokenService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(authService, apiTokenService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ── login ──

    @Test
    void login_success_returnsTokenAndAccount() throws Exception {
        when(authService.login(eq("adai"), eq("secret123"), any(), any()))
                .thenReturn(new LoginResult("tok_abc", "adai", "admin",
                        List.of("trading", "project"), Instant.parse("2026-10-01T00:00:00Z"), "abc12345"));

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"adai\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("tok_abc"))
                .andExpect(jsonPath("$.userId").value("adai"))
                .andExpect(jsonPath("$.role").value("admin"))
                .andExpect(jsonPath("$.plugins[0]").value("trading"))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-01T00:00:00Z"));
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        when(authService.login(eq("adai"), eq("wrong"), any(), any()))
                .thenThrow(new AuthException("账号或密码错误"));

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"adai\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("账号或密码错误"));
    }

    @Test
    void login_accountWithoutPassword_returns401WithSetupHint() throws Exception {
        when(authService.login(anyString(), anyString(), any(), any()))
                .thenThrow(new AuthException("该账号尚未设置密码，请先完成初始化"));

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"adai\",\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("该账号尚未设置密码，请先完成初始化"));
    }

    @Test
    void login_rateLimited_returns401WithRetryHint() throws Exception {
        when(authService.login(anyString(), anyString(), any(), any()))
                .thenThrow(new AuthException("尝试次数过多，请 15 分钟后再试"));

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"adai\",\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("尝试次数过多，请 15 分钟后再试"));
    }

    @Test
    void login_missingFields_returns400() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── setup ──

    @Test
    void setup_firstTime_returnsOk() throws Exception {
        when(authService.setupInitialPassword(eq("adai"), eq("secret123"))).thenReturn(true);

        mvc.perform(post("/api/v1/auth/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"adai\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("密码设置成功，请登录"));
    }

    @Test
    void setup_alreadyInitialized_returns404() throws Exception {
        when(authService.setupInitialPassword(any(), any())).thenReturn(false);

        mvc.perform(post("/api/v1/auth/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"account\":\"adai\",\"password\":\"secret123\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("系统已完成初始化，请直接登录"));
    }

    // ── change password ──

    @Test
    void changePassword_success_kicksOtherSessions() throws Exception {
        when(authService.changePassword(eq("tok_1"), eq("old1234"), eq("new12345"))).thenReturn(2);

        mvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer tok_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"old1234\",\"newPassword\":\"new12345\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kickedSessions").value(2));
    }

    @Test
    void changePassword_wrongOld_returns401() throws Exception {
        when(authService.changePassword(anyString(), anyString(), anyString()))
                .thenThrow(new AuthException("原密码错误"));

        mvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer tok_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"bad\",\"newPassword\":\"new12345\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("原密码错误"));
    }

    // ── me ──

    @Test
    void me_withValidToken_returnsAccount() throws Exception {
        var account = new com.adaiadai.core.kernel.account.Account("adai", "admin", true,
                java.time.LocalDate.of(2026, 8, 2), List.of("trading"), "hash");
        when(authService.currentAccount(eq("tok_1"))).thenReturn(java.util.Optional.of(account));

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer tok_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("adai"))
                .andExpect(jsonPath("$.role").value("admin"))
                .andExpect(jsonPath("$.plugins[0]").value("trading"));
    }

    @Test
    void me_withInvalidToken_returns401() throws Exception {
        when(authService.currentAccount(anyString())).thenReturn(java.util.Optional.empty());

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer bad"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("会话已失效，请重新登录"));
    }

    // ── logout ──

    @Test
    void logout_deletesSession() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer tok_1"))
                .andExpect(status().isOk());
        verify(authService).logout("tok_1");
    }

    @Test
    void logout_withoutToken_isIdempotent() throws Exception {
        mvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isOk());
        verify(authService).logout("");
    }

    // ── 登录设备（RFC 20260914 L2） ──

    @Test
    void listSessions_returnsDevicesWithCurrentFlag() throws Exception {
        Instant now = Instant.parse("2026-09-14T12:00:00Z");
        when(authService.listSessions("tok_1")).thenReturn(java.util.Optional.of(List.of(
                new AuthService.SessionView("currhash",
                        new com.adaiadai.core.kernel.auth.Session.DeviceInfo("iPhone 15", "ios", "3.66.0"),
                        now, now, now.plusSeconds(3600), true),
                new AuthService.SessionView("oldhash", null, now, now, now.plusSeconds(3600), false))));

        mvc.perform(get("/api/v1/auth/sessions").header("Authorization", "Bearer tok_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].id").value("currhash"))
                .andExpect(jsonPath("$.sessions[0].current").value(true))
                .andExpect(jsonPath("$.sessions[0].device.name").value("iPhone 15"))
                .andExpect(jsonPath("$.sessions[1].current").value(false));
    }

    @Test
    void listSessions_invalidToken_returns401() throws Exception {
        when(authService.listSessions(anyString())).thenReturn(java.util.Optional.empty());

        mvc.perform(get("/api/v1/auth/sessions").header("Authorization", "Bearer bad"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokeSession_success_returnsMessage() throws Exception {
        when(authService.revokeSession("tok_1", "oldhash"))
                .thenReturn(new AuthService.RevokeOutcome(true, null));

        mvc.perform(delete("/api/v1/auth/sessions/oldhash").header("Authorization", "Bearer tok_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("已撤销，这台设备需要重新登录"));
    }

    @Test
    void revokeSession_notFound_returns404() throws Exception {
        when(authService.revokeSession("tok_1", "zzz"))
                .thenReturn(new AuthService.RevokeOutcome(false, "没找到这台设备，可能已经退出过了"));

        mvc.perform(delete("/api/v1/auth/sessions/zzz").header("Authorization", "Bearer tok_1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("没找到这台设备，可能已经退出过了"));
    }

    @Test
    void revokeSession_currentDevice_returns400() throws Exception {
        when(authService.revokeSession("tok_1", "curr"))
                .thenReturn(new AuthService.RevokeOutcome(false, "这台就是当前设备；要退出它请用「退出登录」"));

        mvc.perform(delete("/api/v1/auth/sessions/curr").header("Authorization", "Bearer tok_1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("这台就是当前设备；要退出它请用「退出登录」"));
    }
}
