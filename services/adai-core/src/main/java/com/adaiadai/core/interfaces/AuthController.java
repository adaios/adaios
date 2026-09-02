package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.AuthService;
import com.adaiadai.core.kernel.account.Account;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * AuthController — 用户认证端点（RFC 20260901-auth-login，根治 REVIEW #179）。
 * <p>
 * POST /api/v1/auth/login     → 登录（免鉴权，限流防爆破）
 * POST /api/v1/auth/logout    → 登出（会话）
 * GET  /api/v1/auth/me        → 当前会话信息（会话；前端启动校验 token）
 * POST /api/v1/auth/setup     → 首访一次性设密码（免鉴权，全系统无密码时可用）
 * POST /api/v1/auth/password  → 改密（会话；踢除其他会话）
 * <p>
 * 免鉴权路径在 {@code WebConfig} 从 {@link AuthInterceptor} 拦截范围 exclude；
 * 其余 /api/** 一律要求 Authorization: Bearer &lt;token&gt;（fail-closed）。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 登录（免鉴权）。失败 401 + 人话；连续失败触发限流。 */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request,
                                   HttpServletRequest servletRequest) {
        AuthService.LoginResult result = authService.login(
                request.account(), request.password(), clientIp(servletRequest));
        return ResponseEntity.ok(Map.of(
                "token", result.token(),
                "userId", result.userId(),
                "role", result.role(),
                "plugins", result.plugins(),
                "expiresAt", result.expiresAt().toString()));
    }

    /** 登出（会话）。幂等：无 token 也返回成功。 */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpServletRequest request) {
        authService.logout(bearerToken(request));
        return ResponseEntity.ok(Map.of("message", "已退出登录"));
    }

    /** 当前会话信息（会话）：前端启动校验 token 有效性 + 拿 userId/role/plugins。 */
    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        Optional<Account> account = authService.currentAccount(bearerToken(request));
        if (account.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "会话已失效，请重新登录"));
        }
        Account a = account.get();
        return ResponseEntity.ok(Map.of(
                "userId", a.userId(),
                "role", a.role(),
                "enabled", a.enabled(),
                "plugins", a.plugins()));
    }

    /**
     * 首访一次性设密码（免鉴权）：仅当全系统无任何账号设过密码时可用。
     * 已有密码 → 404（前端识别后引导去登录）；账号不存在 → 401。
     */
    @PostMapping("/setup")
    public ResponseEntity<?> setup(@RequestBody(required = false) SetupRequest request) {
        String account = request == null ? null : request.account();
        String password = request == null ? null : request.password();
        boolean ok = authService.setupInitialPassword(account, password);
        if (!ok) {
            return ResponseEntity.status(404).body(Map.of("error", "系统已完成初始化，请直接登录"));
        }
        return ResponseEntity.ok(Map.of("message", "密码设置成功，请登录"));
    }

    /** 改密（会话）：校验旧密码 → 更新 → 踢除其他会话（保留当前）。 */
    @PostMapping("/password")
    public ResponseEntity<?> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                            HttpServletRequest servletRequest) {
        int kicked = authService.changePassword(
                bearerToken(servletRequest), request.oldPassword(), request.newPassword());
        return ResponseEntity.ok(Map.of("message", "密码已更新", "kickedSessions", kicked));
    }

    /** 取 Authorization: Bearer 后的 token（无则空串）。 */
    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return "";
        }
        return header.substring("Bearer ".length()).trim();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // 取最左（原始客户端）；Caddy 反代会追加
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    // ── Request DTOs ──

    public record LoginRequest(@NotBlank(message = "账号不能为空") String account,
                               @NotBlank(message = "密码不能为空") String password) {}

    public record SetupRequest(String account,
                               @Size(min = 8, message = "密码长度至少 8 位") String password) {}

    public record ChangePasswordRequest(@NotBlank(message = "原密码不能为空") String oldPassword,
                                        @NotBlank(message = "新密码不能为空")
                                        @Size(min = 8, message = "新密码长度至少 8 位") String newPassword) {}
}
