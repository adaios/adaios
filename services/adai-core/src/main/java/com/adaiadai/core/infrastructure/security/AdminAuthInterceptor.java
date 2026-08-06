package com.adaiadai.core.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * AdminAuthInterceptor — 管理端点鉴权（REVIEW #127 最小封闭）。
 *
 * <p>保护系统级管理端点 {@code /api/v1/admin/**} 与 {@code /api/v1/accounts/**}，
 * 要求请求携带 {@code X-Admin-Token} 且与配置令牌一致（常量时间比较防时序侧信道）。
 * 令牌由 {@code adai.security.admin-token}（env {@code ADAI_ADMIN_TOKEN}）注入，
 * 在 {@link com.adaiadai.core.infrastructure.WebConfig} 中注册。</p>
 *
 * <p><b>安全默认（fail-closed）</b>：未配置令牌时管理端点直接返回 503——
 * 防止生产环境误部署导致管理口在公网裸奔。令牌比较用
 * {@link MessageDigest#isEqual(byte[], byte[])} 避免时序攻击。</p>
 */
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final String adminToken;

    public AdminAuthInterceptor(String adminToken) {
        this.adminToken = adminToken == null ? "" : adminToken.trim();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (adminToken.isEmpty()) {
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "管理令牌未配置（ADAI_ADMIN_TOKEN），管理端点已禁用");
            return false;
        }
        String provided = request.getHeader("X-Admin-Token");
        if (provided == null
                || !MessageDigest.isEqual(
                        adminToken.getBytes(StandardCharsets.UTF_8),
                        provided.getBytes(StandardCharsets.UTF_8))) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "未授权：X-Admin-Token 缺失或不匹配");
            return false;
        }
        return true;
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
