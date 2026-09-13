package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.ApiTokenService;
import com.adaiadai.core.application.AuthService;
import com.adaiadai.core.kernel.account.Account;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
    private final ApiTokenService apiTokenService;

    public AuthController(AuthService authService, ApiTokenService apiTokenService) {
        this.authService = authService;
        this.apiTokenService = apiTokenService;
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

    // ── 外部工具令牌（2026-09-13 外部入口批）──
    //
    // 为什么另立一类凭据：快捷指令的 token 是**明文写在 plist 里**的，而 .shortcut 文件
    // 本身会被分享出去。把登录会话交给它，等于把「能改密码、能读全部数据」的主钥匙
    // 放进一个会被转发的文件里。这三条端点让用户自己发一把**限权、可撤销、可辨认**的钥匙。
    //
    // ⚠️ 这三条端点**只能由会话 token 访问**：外部令牌的 scope 白名单（TokenScope）里
    // 没有任何 /api/v1/auth/** 路径，所以外部令牌调到这里会被 AuthFilter 判 403
    // ——它无法给自己签发一把权限更大的钥匙。

    /**
     * 签发外部令牌（会话）。**明文只在这次响应里出现一次**，之后连服务端也还原不出来。
     */
    @PostMapping("/tokens")
    public ResponseEntity<?> issueToken(@Valid @RequestBody IssueTokenRequest request,
                                        HttpServletRequest servletRequest) {
        Optional<Account> account = authService.currentAccount(bearerToken(servletRequest));
        if (account.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "会话已失效，请重新登录"));
        }
        ApiTokenService.IssuedToken issued = apiTokenService.issue(
                account.get().userId(), request.label(), request.scopes());
        return ResponseEntity.ok(Map.of(
                "token", issued.plainToken(),
                "prefix", issued.token().tokenPrefix(),
                "label", issued.token().label(),
                "scopes", issued.token().scopes(),
                "createdAt", issued.token().createdAt().toString(),
                "notice", "这串令牌只会显示这一次，请现在就复制走；丢了就撤销重发一把。"));
    }

    /** 列出本账号已签发的外部令牌 + 可选权限清单（供界面展示「这把钥匙能做什么」）。 */
    @GetMapping("/tokens")
    public ResponseEntity<?> listTokens(HttpServletRequest servletRequest) {
        Optional<Account> account = authService.currentAccount(bearerToken(servletRequest));
        if (account.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "会话已失效，请重新登录"));
        }
        return ResponseEntity.ok(Map.of(
                "tokens", apiTokenService.list(account.get().userId()).stream()
                        .map(ApiTokenService.TokenView::of).toList(),
                "availableScopes", ApiTokenService.availableScopes()));
    }

    /** 撤销一把外部令牌（会话）：立即失效，不影响登录会话与其它设备。 */
    @DeleteMapping("/tokens/{prefix}")
    public ResponseEntity<?> revokeToken(@PathVariable String prefix, HttpServletRequest servletRequest) {
        Optional<Account> account = authService.currentAccount(bearerToken(servletRequest));
        if (account.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "会话已失效，请重新登录"));
        }
        boolean removed = apiTokenService.revoke(account.get().userId(), prefix);
        if (!removed) {
            return ResponseEntity.status(404).body(Map.of("error", "没找到这把令牌，可能已经撤销过了"));
        }
        return ResponseEntity.ok(Map.of("message", "已撤销，这把令牌立刻失效"));
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

    /** 签发外部令牌：{@code label} 是用途备注（如「快捷指令」），{@code scopes} 见 TokenScope。 */
    public record IssueTokenRequest(String label, List<String> scopes) {}

    public record SetupRequest(String account,
                               @Size(min = 8, message = "密码长度至少 8 位") String password) {}

    public record ChangePasswordRequest(@NotBlank(message = "原密码不能为空") String oldPassword,
                                        @NotBlank(message = "新密码不能为空")
                                        @Size(min = 8, message = "新密码长度至少 8 位") String newPassword) {}
}
