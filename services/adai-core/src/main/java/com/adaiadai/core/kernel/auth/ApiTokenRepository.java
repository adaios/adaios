package com.adaiadai.core.kernel.auth;

import java.util.List;
import java.util.Optional;

/**
 * ApiTokenRepository — 外部工具令牌存储端口（2026-09-13 外部入口批）。
 * <p>
 * 实现归 {@code infrastructure/storage}（依赖倒置，与会话同一约定）。
 * <p>
 * 注意这里是**系统级**数据（与会话同理）：鉴权时只知道 tokenHash、还不知道 userId，
 * 所以按 hash 的查找必须是全局的。
 */
public interface ApiTokenRepository {

    /** 全部令牌（鉴权路径用；文件损坏必须 fail-fast，不能降级成「没有令牌」）。 */
    List<ApiToken> findAll();

    /** 某账号的令牌（列表/撤销用，避免跨账号可见）。 */
    List<ApiToken> findByUserId(String userId);

    Optional<ApiToken> findByTokenHash(String tokenHash);

    ApiToken save(ApiToken token);

    boolean deleteByTokenHash(String tokenHash);

    /** 按「账号 + 前缀」撤销（前缀是展示用标识；限定 userId 防跨账号删除）。 */
    boolean deleteByPrefix(String userId, String tokenPrefix);

    /** 踢某账号全部令牌（改密/账号禁用时联动）。 */
    int deleteByUserId(String userId);
}
