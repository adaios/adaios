package com.adaiadai.core.infrastructure.security;

import com.adaiadai.core.application.AuthService;
import com.adaiadai.core.kernel.auth.Session;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

/**
 * AuthFilter — 产品端点会话鉴权（RFC 20260901-auth-login，根治 REVIEW #179）。
 * <p>
 * 为什么用 Filter 而非 HandlerInterceptor：Controller 的 92 处
 * {@code @RequestHeader("X-User-Id")} 由 Spring MVC 参数解析器在 DispatcherServlet
 * 内读取原始 request——拦截器无法替换 request 对象，只有 Servlet Filter 能在
 * DispatcherServlet 之前包装 request。因此本 Filter：
 * <ol>
 *   <li>解析 {@code Authorization: Bearer &lt;token&gt;}（AuthService 校验 + 滑动续期）</li>
 *   <li>无有效会话 → <b>401 fail-closed</b>（不再信任裸 X-User-Id）</li>
 *   <li>有效 → {@link UserIdHeaderRequestWrapper} 覆盖 X-User-Id 为会话 userId → 放行</li>
 * </ol>
 * <p>
 * 顺序：{@link Order} = LOWEST_PRECEDENCE + 1，在 Spring Boot 自动注册的
 * {@code CorsFilter}（LOWEST_PRECEDENCE）之后执行——401 响应也带 CORS 头，
 * 浏览器能读到鉴权失败而不是误报 CORS policy 错误。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE + 1)
public class AuthFilter implements Filter {

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
            writeUnauthorized(response);
            return;
        }
        // 覆盖 X-User-Id 为会话 userId——客户端伪造 header 无效（根治 #179 的关键）
        chain.doFilter(new UserIdHeaderRequestWrapper(request, session.get().userId()), response);
    }

    /** 免鉴权路径（与 WebConfig 的 AdminAuthInterceptor 范围互斥）。 */
    private boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith("/api/")) {
            return true;
        }
        // CORS 预检不带鉴权头（AdminAuthInterceptor 同款处理）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // 登录 / 首访初始化：免鉴权
        if (uri.startsWith("/api/v1/auth/login") || uri.startsWith("/api/v1/auth/setup")) {
            return true;
        }
        // admin 体系：X-Admin-Token 由 AdminAuthInterceptor 管（fail-closed）。
        // 例外：/accounts/available（旧免鉴权选号端点）不再免鉴权——决策 4（RFC 20260901）
        // 封掉 userId 枚举面，登录页手输账号名；故 available 走本 Filter 需登录。
        if (uri.startsWith("/api/v1/admin/")) {
            return true;
        }
        if (uri.startsWith("/api/v1/accounts/") && !uri.endsWith("/available")) {
            return true;
        }
        return false;
    }

    private String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return "";
        }
        return header.substring("Bearer ".length()).trim();
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"未登录或会话已失效，请先登录\"}");
    }
}
