package com.adaiadai.core.application;

import com.adaiadai.core.kernel.auth.ApiToken;
import com.adaiadai.core.kernel.auth.ApiTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ApiTokenServiceTest — 外部工具令牌的签发 / 校验 / 撤销（2026-09-13 外部入口批）。
 * <p>
 * 覆盖：明文只出现一次且落盘只有哈希、无权限不发、未知 scope 不放权、备注归一、
 * **lastUsedAt 节流写盘**（校验发生在每个请求上，不能每次都回写文件）、
 * 以及撤销的账号隔离。
 * <p>
 * 用内存替身而不是 mock：这里要验的是「签发 → 校验」整条链的行为，
 * mock 会把「存了什么」变成逐字段断言，反而看不出链路是否真的通。
 */
class ApiTokenServiceTest {

    private InMemoryTokenRepo repository;
    private MutableClock clock;
    private ApiTokenService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTokenRepo();
        clock = new MutableClock(Instant.parse("2026-09-13T10:00:00Z"));
        service = new ApiTokenService(repository, clock);
    }

    // ── 签发 ──

    @Test
    void issue_returnsPlainOnce_andStoresOnlyHash() {
        ApiTokenService.IssuedToken issued = service.issue("adai", "快捷指令", List.of("learn:digest"));

        assertTrue(issued.plainToken().startsWith(ApiToken.PLAIN_PREFIX));
        assertEquals(ApiToken.PLAIN_PREFIX.length() + 64, issued.plainToken().length(),
                "32 字节随机 → 64 个十六进制字符");
        assertEquals(ApiToken.PLAIN_PREFIX.length() + 8, issued.token().tokenPrefix().length(),
                "前缀 = adai_ + 8 位（展示与撤销定位用）");

        ApiToken stored = repository.byHash.values().iterator().next();
        assertEquals(AuthService.sha256Hex(issued.plainToken()), stored.tokenHash());
        assertFalse(stored.tokenHash().contains(issued.plainToken()),
                "落盘内容不得包含明文");
        assertEquals("adai", stored.userId());
        assertEquals("快捷指令", stored.label());
        assertEquals(java.util.Set.of("learn:digest"), stored.scopes());
        assertNull(stored.lastUsedAt(), "刚签发时还没用过");
        assertNotNull(stored.createdAt());
    }

    @Test
    void issue_twoTokens_haveDistinctPlainAndPrefix() {
        var a = service.issue("adai", "a", List.of("learn:digest"));
        var b = service.issue("adai", "b", List.of("learn:digest"));

        assertFalse(a.plainToken().equals(b.plainToken()), "每次签发必须是新的随机值");
        assertFalse(a.token().tokenPrefix().equals(b.token().tokenPrefix()));
    }

    @Test
    void issue_noScopes_throwsHumanMessage() {
        AuthService.AuthException e = assertThrows(AuthService.AuthException.class,
                () -> service.issue("adai", "x", List.of()));
        assertTrue(e.getMessage().contains("权限"), "要说清楚「至少要给一项权限」");
        assertEquals(0, repository.saveCalls, "没通过校验就不该落盘");
    }

    @Test
    void issue_allUnknownScopes_throws_notSilentlyGrantingEverything() {
        assertThrows(AuthService.AuthException.class,
                () -> service.issue("adai", "x", List.of("learn:everything", "root")));
        assertEquals(0, repository.saveCalls);
    }

    @Test
    void issue_unknownScopeAmongKnown_keepsOnlyKnown() {
        var issued = service.issue("adai", "x", List.of("learn:digest", "root", "admin:*"));
        assertEquals(java.util.Set.of("learn:digest"), issued.token().scopes(),
                "未知 scope 丢弃（不报错、也绝不放权）");
    }

    @Test
    void issue_labelNormalized() {
        assertEquals("未命名", service.issue("adai", null, List.of("learn:digest")).token().label());
        assertEquals("未命名", service.issue("adai", "   ", List.of("learn:digest")).token().label());
        String longLabel = "长".repeat(100);
        assertEquals(40, service.issue("adai", longLabel, List.of("learn:digest")).token().label().length());
    }

    @Test
    void issue_blankUserId_throws() {
        assertThrows(AuthService.AuthException.class,
                () -> service.issue("  ", "x", List.of("learn:digest")));
    }

    // ── 校验 ──

    @Test
    void validate_matchingPlainToken_returnsToken() {
        var issued = service.issue("adai", "快捷指令", List.of("learn:digest"));

        Optional<ApiToken> found = service.validate(issued.plainToken());

        assertTrue(found.isPresent());
        assertEquals("adai", found.get().userId());
        assertTrue(found.get().allows("POST", "/api/v1/learn/digest"));
    }

    @Test
    void validate_wrongPlainToken_returnsEmpty() {
        service.issue("adai", "快捷指令", List.of("learn:digest"));
        assertTrue(service.validate("adai_" + "0".repeat(64)).isEmpty());
    }

    @Test
    void validate_withoutPrefix_returnsEmpty_withoutTouchingRepository() {
        // 前缀筛选：任意无效 token 不该触发令牌文件读取（既是无谓 IO，也让「猜 token」不成为探针）
        assertTrue(service.validate("some-session-token").isEmpty());
        assertTrue(service.validate("adaiext123").isEmpty());
        assertTrue(service.validate("").isEmpty());
        assertTrue(service.validate(null).isEmpty());
        assertEquals(0, repository.findCalls, "不带前缀的直接返回，不该查存储");
    }

    @Test
    void validate_stampsLastUsedAt_onFirstUse() {
        var issued = service.issue("adai", "x", List.of("learn:digest"));
        service.validate(issued.plainToken());

        ApiToken stored = repository.byHash.values().iterator().next();
        assertEquals(clock.instant(), stored.lastUsedAt());
    }

    /** 节流：校验在每个请求上发生，若每次回写文件，一次整理就会引发多轮全局锁 RMW。 */
    @Test
    void validate_throttlesLastUsedWrites() {
        var issued = service.issue("adai", "x", List.of("learn:digest"));
        int afterIssue = repository.saveCalls;      // 签发时 save 过一次

        service.validate(issued.plainToken());       // 首次使用 → 记录
        assertEquals(afterIssue + 1, repository.saveCalls);

        clock.advance(Duration.ofSeconds(30));
        service.validate(issued.plainToken());       // 30 秒后再用 → 节流内，不写
        assertEquals(afterIssue + 1, repository.saveCalls, "节流窗口内的重复使用不该写盘");

        clock.advance(ApiTokenService.LAST_USED_THROTTLE.plusSeconds(1));
        service.validate(issued.plainToken());       // 超过窗口 → 该记录一次
        assertEquals(afterIssue + 2, repository.saveCalls);
    }

    @Test
    void validate_doesNotFailRequest_whenStampWriteFails() {
        var issued = service.issue("adai", "x", List.of("learn:digest"));
        repository.failOnSave = true;

        // 记不上「最近使用」只影响可观测性，不该让这次请求失败
        assertTrue(service.validate(issued.plainToken()).isPresent());
    }

    // ── 列表与撤销 ──

    @Test
    void list_returnsOnlyOwnTokens_newestFirst() {
        var a = service.issue("adai", "第一把", List.of("learn:digest"));
        clock.advance(Duration.ofMinutes(1));
        var b = service.issue("adai", "第二把", List.of("learn:digest"));
        service.issue("someone-else", "别人的", List.of("learn:digest"));

        List<ApiToken> mine = service.list("adai");

        assertEquals(2, mine.size());
        assertEquals(b.token().tokenPrefix(), mine.get(0).tokenPrefix(), "按签发时间倒序");
        assertEquals(a.token().tokenPrefix(), mine.get(1).tokenPrefix());
    }

    @Test
    void revoke_deletesByPrefix_withinOwnerOnly() {
        var mine = service.issue("adai", "我的", List.of("learn:digest"));
        var other = service.issue("someone-else", "别人的", List.of("learn:digest"));

        assertTrue(service.revoke("adai", mine.token().tokenPrefix()));
        assertTrue(service.validate(mine.plainToken()).isEmpty(), "撤销后立刻失效");
        assertTrue(service.validate(other.plainToken()).isPresent(), "不该碰到别人的令牌");

        assertFalse(service.revoke("adai", other.token().tokenPrefix()),
                "跨账号撤销必须失败（前缀限定 owner）");
        assertTrue(service.validate(other.plainToken()).isPresent());
    }

    @Test
    void revokeAll_clearsEveryTokenOfAccount() {
        service.issue("adai", "a", List.of("learn:digest"));
        service.issue("adai", "b", List.of("learn:digest"));
        var other = service.issue("someone-else", "c", List.of("learn:digest"));

        assertEquals(2, service.revokeAll("adai"));
        assertEquals(0, service.list("adai").size());
        assertTrue(service.validate(other.plainToken()).isPresent());
    }

    // ── 替身 ──

    static class InMemoryTokenRepo implements ApiTokenRepository {
        final Map<String, ApiToken> byHash = new LinkedHashMap<>();
        int saveCalls = 0;
        int findCalls = 0;
        boolean failOnSave = false;

        @Override
        public List<ApiToken> findAll() {
            return new ArrayList<>(byHash.values());
        }

        @Override
        public List<ApiToken> findByUserId(String userId) {
            return byHash.values().stream()
                    .filter(t -> Objects.equals(t.userId(), userId))
                    .toList();
        }

        @Override
        public Optional<ApiToken> findByTokenHash(String tokenHash) {
            findCalls++;
            return Optional.ofNullable(byHash.get(tokenHash));
        }

        @Override
        public ApiToken save(ApiToken token) {
            if (failOnSave) throw new IllegalStateException("磁盘故障（测试模拟）");
            saveCalls++;
            byHash.put(token.tokenHash(), token);
            return token;
        }

        @Override
        public boolean deleteByTokenHash(String tokenHash) {
            return byHash.remove(tokenHash) != null;
        }

        @Override
        public boolean deleteByPrefix(String userId, String tokenPrefix) {
            return byHash.values().removeIf(t -> Objects.equals(t.userId(), userId)
                    && Objects.equals(t.tokenPrefix(), tokenPrefix));
        }

        @Override
        public int deleteByUserId(String userId) {
            int before = byHash.size();
            byHash.values().removeIf(t -> Objects.equals(t.userId(), userId));
            return before - byHash.size();
        }
    }

    /** 可推进的时钟（验证节流窗口需要控制时间）。 */
    static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
