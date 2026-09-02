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

    private final String sessionUserId;

    public UserIdHeaderRequestWrapper(HttpServletRequest request, String sessionUserId) {
        super(request);
        this.sessionUserId = sessionUserId;
    }

    @Override
    public String getHeader(String name) {
        if ("X-User-Id".equalsIgnoreCase(name)) {
            return sessionUserId;
        }
        return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        if ("X-User-Id".equalsIgnoreCase(name)) {
            return Collections.enumeration(Collections.singletonList(sessionUserId));
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
        return Collections.enumeration(names);
    }
}
