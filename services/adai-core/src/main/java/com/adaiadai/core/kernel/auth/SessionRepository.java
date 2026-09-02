package com.adaiadai.core.kernel.auth;

import java.util.List;
import java.util.Optional;

/**
 * SessionRepository — 会话存储接口（系统级，File First 于 {@code data/accounts/sessions.json}）。
 * <p>
 * 会话属于全系统共享层（不按 {@code data/{userId}/} 分层）：token 哈希为键，
 * 支持多设备多会话同时在线（RFC 20260901-auth-login）。
 */
public interface SessionRepository {

    /** 按 token 哈希查询会话（含已过期；调用方负责 isExpired 判定）。 */
    Optional<Session> findByTokenHash(String tokenHash);

    /** 查询某账号的全部会话（改密踢会话用）。 */
    List<Session> findByUserId(String userId);

    /** 新增会话。 */
    Session save(Session session);

    /** 删除会话（登出 / 改密踢除）。 */
    boolean deleteByTokenHash(String tokenHash);

    /** 删除某账号的全部会话（改密踢全部 / 禁用账号）。 */
    int deleteByUserId(String userId);

    /**
     * 清理并返回截止时间前已过期会话数（G2 守卫：storage 不取 now()，
     * 时间由调用方（application 层）传入）。
     */
    int purgeExpiredBefore(java.time.Instant cutoff);
}
