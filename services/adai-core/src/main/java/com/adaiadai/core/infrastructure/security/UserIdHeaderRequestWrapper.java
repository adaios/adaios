package com.adaiadai.core.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

/**
 * UserIdHeaderRequestWrapper — 把 {@code X-User-Id} 头强制改写为会话 userId。
 * <p>
 * RFC 20260901-auth-login 核心机制：19 个 Controller × 92 处
 * {@code @RequestHeader("X-User-Id")} 一行不改——客户端传什么 userId 都被本
 * wrapper 覆盖为 {@link AuthInterceptor} 从会话解析出的真实 userId，伪造 header
 * 无效（根治 REVIEW #179）。
 */
public class UserIdHeaderRequestWrapper extends HttpServletRequestWrapper {

    /**
     * 外部令牌调用时注入的令牌标识 header（2026-09-17 B4 批，S-凭据1 付费动作频控要用它）。
     * <p>
     * 只有 {@code handleExternalToken} 走新构造器时才会设；会话调用不设（客户端即使自己传，
     * 也不被采信——见 {@link #getHeader}），所以「这次是令牌调用」这件事不可伪造。
     */
    public static final String TOKEN_ID_HEADER = "X-Adai-Token-Id";

    private final String sessionUserId;
    private final String tokenId;

    public UserIdHeaderRequestWrapper(HttpServletRequest request, String sessionUserId) {
        this(request, sessionUserId, null);
    }

    public UserIdHeaderRequestWrapper(HttpServletRequest request, String sessionUserId, String tokenId) {
        super(request);
        this.sessionUserId = sessionUserId;
        this.tokenId = tokenId;
    }

    @Override
    public String getHeader(String name) {
        if ("X-User-Id".equalsIgnoreCase(name)) {
            return sessionUserId;
        }
        if (tokenId != null && TOKEN_ID_HEADER.equalsIgnoreCase(name)) {
            return tokenId;
        }
        return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        if ("X-User-Id".equalsIgnoreCase(name)) {
            return Collections.enumeration(Collections.singletonList(sessionUserId));
        }
        if (tokenId != null && TOKEN_ID_HEADER.equalsIgnoreCase(name)) {
            return Collections.enumeration(Collections.singletonList(tokenId));
        }
        return super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        Set<String> names = new HashSet<>();
        Enumeration<String> original = super.getHeaderNames();
        if (original != null) {
            while (original.hasMoreElements()) {
                names.add(original.nextElement());
            }
        }
        // 保证 X-User-Id 一定存在（即使客户端没传，Controller 也能读到会话 userId）
        names.add("X-User-Id");
        if (tokenId != null) {
            names.add(TOKEN_ID_HEADER);
        }
        return Collections.enumeration(names);
    }
}
