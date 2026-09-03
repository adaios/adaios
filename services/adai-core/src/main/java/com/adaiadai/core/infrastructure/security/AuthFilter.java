package com.adaiadai.core.infrastructure.security;

import com.adaiadai.core.application.AuthService;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.auth.Session;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

/**
 * AuthFilter — 全量会话鉴权（RFC 20260901-auth-login 根治 #179；REVIEW #178 收编 admin 口）。
 * <p>
 * 为什么用 Filter 而非 HandlerInterceptor：Controller 的 92 处
 * {@code @RequestHeader("X-User-Id")} 由 Spring MVC 参数解析器在 DispatcherServlet
 * 内读取原始 request——拦截器无法替换 request 对象，只有 Servlet Filter 能在
 * DispatcherServlet 之前包装 request。因此本 Filter：
 * <ol>
 *   <li>解析 {@code Authorization: Bearer &lt;token&gt;}（AuthService 校验 + 滑动续期）</li>
 *   <li>无有效会话 → <b>401 fail-closed</b>（不再信任裸 X-User-Id）</li>
 *   <li>有效会话：<b>admin 范围（/api/v1/admin/**、/api/v1/accounts/**，available 例外）要求
 *       session 账号 role=admin，否则 403</b>（REVIEW #178：X-Admin-Token 烧录前端 = 管理口
 *       弱鉴权 → 管理端点并入统一登录，AdminAuthInterceptor 退役）</li>
 *   <li>X-User-Id 覆盖：user 会话一律覆盖为会话 userId（伪造 header 无效）；
 *       admin 会话保留客户端传入的 X-User-Id（adai-admin 用户切换器跨账号治理浏览），
 *       缺省回落会话 userId</li>
 * </ol>
 * <p>
 * 顺序（2026-09-04 线上事故根因修复）：本 Filter 注册为 {@link Ordered#HIGHEST_PRECEDENCE}
 * 之后的低优先级（{@code HIGHEST_PRECEDENCE + 10}），确保跑在 WebConfig 的
 * {@code CorsFilter}（HIGHEST_PRECEDENCE）<b>之后</b>——401/403 响应因此带 CORS 头，
 * 浏览器读到的是鉴权失败而非误报 CORS policy 错误。
 * <p>
 * ⚠️ 历史坑（勿复犯）：原 {@code @Order(Ordered.LOWEST_PRECEDENCE + 1)} 意图「最后执行」，
 * 但 {@code LOWEST_PRECEDENCE = Integer.MAX_VALUE}，{@code +1} <b>溢出为
 * Integer.MIN_VALUE</b> 反成最高优先级 → 跑在 CorsFilter 之前 → 401/403 无 CORS 头
 * → 前端把未登录/无权限误报为 CORS 错误（2026-09-04 admin 打开即 CORS 报错刷屏根因）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    private final AuthService authService;

    public AuthFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                         FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        if (shouldNotFilter(request)) {
            chain.doFilter(request, response);
            return;
        }

        String token = bearerToken(request);
        Optional<Session> session = authService.validateAndTouch(token);
        // 防御纵深：AuthService 已删过期会话，这里再查一次 isExpired（即使 AuthService 实现有缺陷也挡住）
        if (session.isEmpty() || session.get().isExpired(java.time.Instant.now())) {
            // 2026-09-02：401 记 WARN（含客户端来源 IP 与路径）——此前静默拒绝导致
            // 前端漏带 token 类问题（multipart 未带 Bearer）在生产日志完全不可见，排查靠猜。
            log.warn("AuthFilter 拒绝: {} {} from {} (token={})", request.getMethod(),
                    request.getRequestURI(), request.getRemoteAddr(),
                    token.isEmpty() ? "缺失" : "无效");
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或会话已失效，请先登录");
            return;
        }

        // ── REVIEW #178：admin 范围 role=admin 门禁 + admin 会话 X-User-Id 透传判定 ──
        String uri = request.getRequestURI();
        boolean adminScope = isAdminScope(uri);
        String clientUserId = request.getHeader("X-User-Id");
        boolean clientDiffers = clientUserId != null && !clientUserId.isBlank()
                && !clientUserId.equals(session.get().userId());
        // 需要知道会话 role 的两种情况：① admin 范围门禁；② 客户端带了与会话不一致的 X-User-Id
        // （决定「透传」还是「覆盖」）。命中才读账号文件——普通 user 会话请求不额外开销。
        boolean needRole = adminScope || clientDiffers;
        boolean isAdmin = false;
        if (needRole) {
            Optional<Account> account = authService.findAccount(session.get().userId());
            isAdmin = account.isPresent() && Account.ROLE_ADMIN.equals(account.get().role());
            if (adminScope && !isAdmin) {
                log.warn("AuthFilter 403: {} {} from {} (userId={} 非 admin 访问管理端点)",
                        request.getMethod(), uri, request.getRemoteAddr(), session.get().userId());
                writeJson(response, HttpServletResponse.SC_FORBIDDEN, "仅管理员账号可访问，请使用 admin 账号登录");
                return;
            }
        }
        // 覆盖 X-User-Id：user 会话一律会话 userId；admin 会话保留客户端指定（跨账号治理浏览）
        String effectiveUserId = session.get().userId();
        if (isAdmin && clientDiffers) {
            effectiveUserId = clientUserId;
        }
        chain.doFilter(new UserIdHeaderRequestWrapper(request, effectiveUserId), response);
    }

    /** 免鉴权路径。除登录/首访初始化/CORS 预检外一律鉴权（admin 范围在门禁内，不再单独豁免）。 */
    private boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith("/api/")) {
            return true;
        }
        // CORS 预检不带鉴权头
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // 登录 / 首访初始化：免鉴权（RFC 20260901-auth-login 决策）
        return uri.startsWith("/api/v1/auth/login") || uri.startsWith("/api/v1/auth/setup");
    }

    /**
     * admin 范围判定（REVIEW #178）：/api/v1/admin/** 与 /api/v1/accounts/** 需 role=admin；
     * 例外 /api/v1/accounts/available（产品端遗留选号端点，登录即可——仅返回 enabled userId 最小集）。
     */
    private boolean isAdminScope(String uri) {
        if (isUnder(uri, "/api/v1/admin")) {
            return true;
        }
        return isUnder(uri, "/api/v1/accounts") && !uri.endsWith("/available");
    }

    /** 精确路径或子路径判定（同时覆盖带/不带尾斜杠的精确命中）。 */
    private boolean isUnder(String uri, String base) {
        return uri.equals(base) || uri.startsWith(base + "/");
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return "";
        }
        return header.substring("Bearer ".length()).trim();
    }

    private void writeJson(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
