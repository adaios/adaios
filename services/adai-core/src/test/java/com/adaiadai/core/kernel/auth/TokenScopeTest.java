package com.adaiadai.core.kernel.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TokenScopeTest — 外部令牌权限白名单（2026-09-13 外部入口批）。
 * <p>
 * 这个测试类比它看起来更重要：{@code TokenScope} 是「限权」这件事的**唯一实现**。
 * 它写错的方式很安静——多放行一条路径不会有任何报错，只会让一把交给快捷指令的钥匙
 * 多出一点权限。所以这里既测「该放行的放行」，也测**「不该放行的一条都不放行」**。
 */
class TokenScopeTest {

    @Test
    void learnDigest_allowsExactlyTheListedRequests() {
        TokenScope scope = TokenScope.LEARN_DIGEST;

        assertTrue(scope.allows("POST", "/api/v1/learn/digest"));
        assertTrue(scope.allows("POST", "/api/v1/learn/digest/confirm"));
        assertTrue(scope.allows("GET", "/api/v1/learn/digest/status"));
        assertTrue(scope.allows("GET", "/api/v1/learn/digest/quota"));
    }

    @Test
    void learnDigest_methodSensitive() {
        // 同一个路径、不同方法，权限不同——「能提交整理」不等于「能删能改」
        assertFalse(TokenScope.LEARN_DIGEST.allows("GET", "/api/v1/learn/digest"));
        assertFalse(TokenScope.LEARN_DIGEST.allows("DELETE", "/api/v1/learn/digest"));
        assertFalse(TokenScope.LEARN_DIGEST.allows("PUT", "/api/v1/learn/digest"));
    }

    /**
     * 本套设计最关键的一条：外部令牌若能被允许调签发端点，它就能给自己造一把权限更大的钥匙，
     * 「限权」当场失效。这条红了说明边界破了，不是小问题。
     */
    @Test
    void noScope_canReachAuthEndpoints_soTokenCannotMintAStrongerToken() {
        for (TokenScope scope : TokenScope.values()) {
            assertFalse(scope.allows("POST", "/api/v1/auth/tokens"), scope.id());
            assertFalse(scope.allows("GET", "/api/v1/auth/tokens"), scope.id());
            assertFalse(scope.allows("POST", "/api/v1/auth/password"), scope.id());
            assertFalse(scope.allows("POST", "/api/v1/auth/setup"), scope.id());
            assertFalse(scope.allows("POST", "/api/v1/auth/logout"), scope.id());
            assertFalse(scope.allows("DELETE", "/api/v1/auth/tokens/adai_abcd1234"), scope.id());
        }
    }

    /** 兜底不变量：任何 scope 都不该触及鉴权、管理、账号、记录、交易、记忆等端点。 */
    @Test
    void noScope_grantsAdminOrPersonalDataEndpoints() {
        List<String> forbidden = List.of(
                "/api/v1/auth/me", "/api/v1/auth/tokens", "/api/v1/auth/password",
                "/api/v1/admin/users", "/api/v1/admin/data", "/api/v1/accounts",
                "/api/v1/records", "/api/v1/feed", "/api/v1/brief", "/api/v1/timeline",
                "/api/v1/memory", "/api/v1/identity", "/api/v1/search",
                "/api/v1/trading/positions", "/api/v1/trading/advice", "/api/v1/push/devices");
        List<String> methods = List.of("GET", "POST", "PUT", "PATCH", "DELETE");

        for (TokenScope scope : TokenScope.values()) {
            for (String uri : forbidden) {
                for (String method : methods) {
                    assertFalse(scope.allows(method, uri),
                            scope.id() + " 不该放行 " + method + " " + uri);
                }
            }
        }
    }

    /**
     * 精确匹配，不做前缀匹配：前缀匹配会让**将来新增的子端点自动获得权限**——
     * 那是一次不需要改代码就发生的权限扩张，也是本设计刻意避开的东西。
     */
    @Test
    void learnDigest_exactMatchOnly_noPrefixGrant() {
        assertFalse(TokenScope.LEARN_DIGEST.allows("POST", "/api/v1/learn/digest/something-new"));
        assertFalse(TokenScope.LEARN_DIGEST.allows("GET", "/api/v1/learn/digest/status/extra"));
        assertFalse(TokenScope.LEARN_DIGEST.allows("POST", "/api/v1/learn/digest/confirm/extra"));
        assertFalse(TokenScope.LEARN_DIGEST.allows("POST", "/api/v1/learn/digest/"));
    }

    @Test
    void of_unknownId_returnsNull_whichMeansNoPermission() {
        // 未知 id 返回 null（而不是「默认全权限」）——降级方向必须是「更严」而不是「更松」
        assertNull(TokenScope.of("learn:everything"));
        assertNull(TokenScope.of(""));
        assertNull(TokenScope.of(null));
        assertEquals(TokenScope.LEARN_DIGEST, TokenScope.of("learn:digest"));
    }

    @Test
    void allows_rejectsNullInputs_andIgnoresMethodCase() {
        assertFalse(TokenScope.LEARN_DIGEST.allows(null, "/api/v1/learn/digest"));
        assertFalse(TokenScope.LEARN_DIGEST.allows("POST", null));
        assertTrue(TokenScope.LEARN_DIGEST.allows("post", "/api/v1/learn/digest"));
    }

    // ── ApiToken 实体的权限判定 ──

    @Test
    void apiToken_allows_unionOfItsScopes() {
        ApiToken token = new ApiToken("h", "adai_abcd1234", "adai", "快捷指令",
                java.util.Set.of("learn:digest"), java.time.Instant.now(), null);

        assertTrue(token.allows("POST", "/api/v1/learn/digest"));
        assertFalse(token.allows("GET", "/api/v1/feed"));
    }

    @Test
    void apiToken_noScopesOrUnknownScopes_grantsNothing() {
        ApiToken none = new ApiToken("h", "adai_x", "adai", "l",
                java.util.Set.of(), java.time.Instant.now(), null);
        assertFalse(none.allows("POST", "/api/v1/learn/digest"));

        ApiToken unknown = new ApiToken("h", "adai_x", "adai", "l",
                java.util.Set.of("learn:everything"), java.time.Instant.now(), null);
        assertFalse(unknown.allows("POST", "/api/v1/learn/digest"),
                "未知 scope 必须当作「无权限」，不能当作通配");
    }
}
