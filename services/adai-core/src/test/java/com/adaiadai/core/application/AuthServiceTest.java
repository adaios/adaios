package com.adaiadai.core.application;

import com.adaiadai.core.application.AuthService.AuthException;
import com.adaiadai.core.application.AuthService.LoginResult;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.auth.Session;
import com.adaiadai.core.kernel.auth.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * AuthService unit tests（RFC 20260901-auth-login）。
 * 覆盖：登录成功/未设密码/限流、setup 一次性、改密踢会话、token 校验滑动续期。
 */
class AuthServiceTest {

    private AccountRepository accountRepository;
    private SessionRepository sessionRepository;
    private AuthService authService;

    private static final Account ACCOUNT_WITH_PASSWORD = new Account("adai", "admin", true,
            LocalDate.of(2026, 8, 2), List.of("trading"), null);

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        sessionRepository = mock(SessionRepository.class);
        authService = new AuthService(accountRepository, sessionRepository);
    }

    // ── 登录 ──

    @Test
    void login_success_issuesTokenAndSavesSession() {
        Account acct = withPassword(ACCOUNT_WITH_PASSWORD, "secret123");
        when(accountRepository.findById("adai")).thenReturn(Optional.of(acct));
        when(sessionRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        LoginResult result = authService.login("adai", "secret123", "1.2.3.4");

        assertEquals("adai", result.userId());
        assertEquals("admin", result.role());
        assertEquals(64, result.token().length()); // 32 字节 hex
        verify(sessionRepository).save(any(Session.class));
    }

    @Test
    void login_unknownAccount_throws401() {
        when(accountRepository.findById("nobody")).thenReturn(Optional.empty());
        assertThrows(AuthException.class, () -> authService.login("nobody", "x", "ip"));
    }

    @Test
    void login_disabledAccount_throws401() {
        Account disabled = new Account("u", "user", false, LocalDate.now(), List.of(), "hash");
        when(accountRepository.findById("u")).thenReturn(Optional.of(disabled));
        assertThrows(AuthException.class, () -> authService.login("u", "x", "ip"));
    }

    @Test
    void login_noPasswordSet_failsClosed() {
        when(accountRepository.findById("adai")).thenReturn(Optional.of(ACCOUNT_WITH_PASSWORD));
        AuthException e = assertThrows(AuthException.class,
                () -> authService.login("adai", "anything", "ip"));
        assertTrue(e.getMessage().contains("尚未设置密码"));
    }

    @Test
    void login_wrongPassword_throws401() {
        Account acct = withPassword(ACCOUNT_WITH_PASSWORD, "secret123");
        when(accountRepository.findById("adai")).thenReturn(Optional.of(acct));
        assertThrows(AuthException.class, () -> authService.login("adai", "wrong", "ip"));
    }

    @Test
    void login_fiveFailures_locksRateLimit() {
        Account acct = withPassword(ACCOUNT_WITH_PASSWORD, "secret123");
        when(accountRepository.findById("adai")).thenReturn(Optional.of(acct));
        for (int i = 0; i < 5; i++) {
            assertThrows(AuthException.class, () -> authService.login("adai", "wrong", "ip"));
        }
        AuthException e = assertThrows(AuthException.class,
                () -> authService.login("adai", "secret123", "ip"));
        assertTrue(e.getMessage().contains("尝试次数过多"));
        // 锁定期间正确密码也拒绝
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void login_rateLimitIsPerIpAndAccount() {
        Account acct = withPassword(ACCOUNT_WITH_PASSWORD, "secret123");
        when(accountRepository.findById("adai")).thenReturn(Optional.of(acct));
        for (int i = 0; i < 5; i++) {
            assertThrows(AuthException.class, () -> authService.login("adai", "wrong", "ipA"));
        }
        // 不同 IP 不受影响
        assertDoesNotThrow(() -> authService.login("adai", "secret123", "ipB"));
    }

    // ── 会话校验 ──

    @Test
    void validateAndTouch_invalidToken_returnsEmpty() {
        when(sessionRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());
        assertTrue(authService.validateAndTouch("bad-token").isEmpty());
    }

    @Test
    void validateAndTouch_expiredSession_deletesAndReturnsEmpty() {
        Instant past = Instant.now().minusSeconds(1000);
        Session expired = new Session(AuthService.sha256Hex("tok"), "adai", past, past, past);
        when(sessionRepository.findByTokenHash(AuthService.sha256Hex("tok"))).thenReturn(Optional.of(expired));
        assertTrue(authService.validateAndTouch("tok").isEmpty());
        verify(sessionRepository).deleteByTokenHash(AuthService.sha256Hex("tok"));
    }

    @Test
    void validateAndTouch_validSession_refreshesWhenNearExpiry() {
        Instant now = Instant.now();
        // 1 分钟后过期 → 进入续期窗口（expiresAt < now + TTL/2）→ 写盘续期
        Session nearExpiry = new Session(AuthService.sha256Hex("tok"), "adai", now,
                now, now.plusSeconds(60));
        when(sessionRepository.findByTokenHash(AuthService.sha256Hex("tok"))).thenReturn(Optional.of(nearExpiry));
        assertTrue(authService.validateAndTouch("tok").isPresent());
        verify(sessionRepository).save(any(Session.class)); // 触发续期写盘
    }

    @Test
    void validateAndTouch_freshSession_doesNotRewrite() {
        Instant now = Instant.now();
        Session fresh = new Session(AuthService.sha256Hex("tok"), "adai", now, now,
                now.plusSeconds(Session.DEFAULT_TTL_SECONDS));
        when(sessionRepository.findByTokenHash(AuthService.sha256Hex("tok"))).thenReturn(Optional.of(fresh));
        assertTrue(authService.validateAndTouch("tok").isPresent());
        verify(sessionRepository, never()).save(any());
    }

    // ── setup ──

    @Test
    void setup_firstTime_setsPassword() {
        when(accountRepository.findAll()).thenReturn(List.of(ACCOUNT_WITH_PASSWORD));
        when(accountRepository.findById("adai")).thenReturn(Optional.of(ACCOUNT_WITH_PASSWORD));
        assertTrue(authService.setupInitialPassword("adai", "secret123"));
        verify(accountRepository).save(argThat(a ->
                a.passwordHash() != null && a.passwordHash().startsWith("$2a$")));
    }

    @Test
    void setup_alreadyInitialized_returnsFalse() {
        Account withPwd = withPassword(ACCOUNT_WITH_PASSWORD, "secret123");
        when(accountRepository.findAll()).thenReturn(List.of(withPwd));
        assertFalse(authService.setupInitialPassword("adai", "another123"));
        verify(accountRepository, never()).save(any());
    }

    @Test
    void setup_shortPassword_throws401() {
        when(accountRepository.findAll()).thenReturn(List.of(ACCOUNT_WITH_PASSWORD));
        when(accountRepository.findById("adai")).thenReturn(Optional.of(ACCOUNT_WITH_PASSWORD));
        assertThrows(AuthException.class, () -> authService.setupInitialPassword("adai", "short"));
    }

    @Test
    void setup_unknownAccount_throws401() {
        when(accountRepository.findAll()).thenReturn(List.of(ACCOUNT_WITH_PASSWORD));
        when(accountRepository.findById("ghost")).thenReturn(Optional.empty());
        assertThrows(AuthException.class, () -> authService.setupInitialPassword("ghost", "secret123"));
    }

    // ── 改密 ──

    @Test
    void changePassword_success_kicksOtherSessionsKeepsCurrent() {
        Account acct = withPassword(ACCOUNT_WITH_PASSWORD, "old1234");
        Instant now = Instant.now();
        Session current = new Session("h_current", "adai", now, now, now.plusSeconds(3600));
        Session other = new Session("h_other", "adai", now, now, now.plusSeconds(3600));
        when(sessionRepository.findByTokenHash(AuthService.sha256Hex("tok_current")))
                .thenReturn(Optional.of(current));
        when(accountRepository.findById("adai")).thenReturn(Optional.of(acct));
        when(sessionRepository.findByUserId("adai")).thenReturn(List.of(current, other));

        int kicked = authService.changePassword("tok_current", "old1234", "new12345");

        assertEquals(1, kicked);
        verify(sessionRepository).deleteByTokenHash("h_other");
        verify(sessionRepository, never()).deleteByTokenHash("h_current");
    }

    @Test
    void changePassword_wrongOldPassword_throws401() {
        Account acct = withPassword(ACCOUNT_WITH_PASSWORD, "old1234");
        Instant now = Instant.now();
        Session current = new Session("h", "adai", now, now, now.plusSeconds(3600));
        when(sessionRepository.findByTokenHash(AuthService.sha256Hex("tok")))
                .thenReturn(Optional.of(current));
        when(accountRepository.findById("adai")).thenReturn(Optional.of(acct));

        assertThrows(AuthException.class,
                () -> authService.changePassword("tok", "wrong", "new12345"));
        verify(accountRepository, never()).save(any());
    }

    // ── token 哈希 ──

    @Test
    void sha256Hex_isStableAndDifferentForInputs() {
        assertEquals(AuthService.sha256Hex("abc"), AuthService.sha256Hex("abc"));
        assertNotEquals(AuthService.sha256Hex("abc"), AuthService.sha256Hex("abd"));
        assertEquals(64, AuthService.sha256Hex("abc").length());
    }

    // ── helpers ──

    private Account withPassword(Account base, String rawPassword) {
        AuthService svc = new AuthService(accountRepository, sessionRepository);
        return new Account(base.userId(), base.role(), base.enabled(), base.createdAt(),
                base.plugins(), svc.encodePassword(rawPassword));
    }
}
