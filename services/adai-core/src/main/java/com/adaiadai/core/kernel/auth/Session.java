package com.adaiadai.core.kernel.auth;

import java.time.Instant;

/**
 * Session — 登录会话（系统级，RFC 20260901-auth-login）。
 * <p>
 * 会话由 {@code POST /api/v1/auth/login} 签发：token 为 32 字节随机 hex（只在响应中
 * 出现一次），落盘 {@code data/accounts/sessions.json} 仅存 SHA-256 哈希——
 * 文件泄露也无法直接冒用（需原像攻击）。
 * <p>
 * 有效期：30 天滑动续期（每次请求刷新 {@code expiresAt}，活跃即不过期）。
 *
 * @param tokenHash  token 的 SHA-256 十六进制哈希（唯一键）
 * @param userId     会话所属账号
 * @param createdAt  签发时间
 * @param lastSeenAt 最近一次请求时间（续期依据）
 * @param expiresAt  过期时间（滑动续期后刷新）
 */
public record Session(String tokenHash, String userId, Instant createdAt,
                      Instant lastSeenAt, Instant expiresAt) {

    /** 会话默认有效期（滑动续期，活跃不过期）。 */
    public static final long DEFAULT_TTL_SECONDS = 30L * 24 * 3600;

    /** 是否已过期（当前时刻晚于 expiresAt）。 */
    public boolean isExpired(Instant now) {
        return expiresAt == null || !expiresAt.isAfter(now);
    }

    /** 滑动续期：刷新 lastSeenAt 与 expiresAt（相对 now 重新计算 TTL）。 */
    public Session touch(Instant now) {
        return new Session(tokenHash, userId, createdAt, now, now.plusSeconds(DEFAULT_TTL_SECONDS));
    }
}
