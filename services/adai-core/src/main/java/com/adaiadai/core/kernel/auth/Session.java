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
 * <p>
 * {@code device}（2026-09-14 登录体验方案 RFC 20260914 L2）：让用户看得见「哪些设备登录着」
 * 并能单独撤销一台。**客户端上报，仅供展示与辨认，不做任何安全判定**（可伪造）——
 * 安全判定一律基于 {@code tokenHash} 与账号状态。
 *
 * @param tokenHash  token 的 SHA-256 十六进制哈希（唯一键）
 * @param userId     会话所属账号
 * @param createdAt  签发时间
 * @param lastSeenAt 最近一次请求时间（续期依据）
 * @param expiresAt  过期时间（滑动续期后刷新）
 * @param device     签发该会话的设备信息（可空：老会话文件 / 客户端未上报）
 */
public record Session(String tokenHash, String userId, Instant createdAt,
                      Instant lastSeenAt, Instant expiresAt, DeviceInfo device) {

    /** 会话默认有效期（滑动续期，活跃不过期）。 */
    public static final long DEFAULT_TTL_SECONDS = 30L * 24 * 3600;

    /** 无设备信息的兼容构造（老会话文件读入、内部重建时用）。 */
    public Session(String tokenHash, String userId, Instant createdAt,
                   Instant lastSeenAt, Instant expiresAt) {
        this(tokenHash, userId, createdAt, lastSeenAt, expiresAt, null);
    }

    /**
     * 设备信息（客户端上报，仅供展示/辨认，不参与安全判定）。
     *
     * @param name       设备名（如「iPhone 15」；由客户端上报）
     * @param platform   平台（如 ios / android / web）
     * @param appVersion 应用版本（如 3.66.0）
     */
    public record DeviceInfo(String name, String platform, String appVersion) {}

    /** 会话在界面上的短标识：token 哈希前 8 位（哈希不可逆，前缀无泄露风险）。 */
    public String viewId() {
        if (tokenHash == null) {
            return null;
        }
        return tokenHash.length() <= 8 ? tokenHash : tokenHash.substring(0, 8);
    }

    /** 是否已过期（当前时刻晚于 expiresAt）。 */
    public boolean isExpired(Instant now) {
        return expiresAt == null || !expiresAt.isAfter(now);
    }

    /** 滑动续期：刷新 lastSeenAt 与 expiresAt（相对 now 重新计算 TTL），设备信息保留。 */
    public Session touch(Instant now) {
        return new Session(tokenHash, userId, createdAt, now,
                now.plusSeconds(DEFAULT_TTL_SECONDS), device);
    }
}
