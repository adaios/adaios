package com.adaiadai.core.application;

import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.auth.ApiToken;
import com.adaiadai.core.kernel.auth.ApiTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    /** 账号状态复核（2026-09-14 增量深审 P1-2）用的可控账号表。 */
    private final Map<String, Account> accounts = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        repository = new InMemoryTokenRepo();
        clock = new MutableClock(Instant.parse("2026-09-13T10:00:00Z"));
        accounts.clear();
        accounts.put("adai", new Account("adai", "admin", true, LocalDate.of(2026, 8, 2), List.of("learn")));
        accounts.put("someone-else", new Account("someone-else", "user", true,
                LocalDate.of(2026, 8, 2), List.of("learn")));
        AccountRepository accountRepository = new AccountRepository() {
            @Override
            public List<Account> findAll() {
                return new ArrayList<>(accounts.values());
            }

            @Override
            public Optional<Account> findById(String userId) {
                return Optional.ofNullable(accounts.get(userId));
            }

            @Override
            public Account save(Account account) {
                accounts.put(account.userId(), account);
                return account;
            }

            @Override
            public boolean delete(String userId) {
                return accounts.remove(userId) != null;
            }

            @Override
            public Account mergePlugins(String userId, List<String> add, List<String> remove) {
                throw new UnsupportedOperationException("本测试不涉及插件合并");
            }
        };
        service = new ApiTokenService(repository, accountRepository, clock);
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
    void validate_disabledAccount_returnsEmpty() {
        // P1-2（2026-09-14 增量深审）：禁用账号的旧令牌不得继续可用——
        // 会话路径是 fail-closed 的，令牌路径必须同口径（否则「禁用了他，快捷指令还能用」）
        var issued = service.issue("adai", "快捷指令", List.of("learn:digest"));
        accounts.put("adai", new Account("adai", "admin", false, LocalDate.of(2026, 8, 2), List.of("learn")));

        assertTrue(service.validate(issued.plainToken()).isEmpty());
    }

    @Test
    void validate_deletedAccount_returnsEmpty() {
        var issued = service.issue("adai", "快捷指令", List.of("learn:digest"));
        accounts.remove("adai");

        assertTrue(service.validate(issued.plainToken()).isEmpty());
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

    // ── P1-令牌1 / S-凭据1（2026-09-14 晚间批）：到期与按 id 撤销 ──

    @Test
    void issue_setsDefaultExpiry() {
        var issued = service.issue("adai", "快捷指令", List.of("learn:digest"));
        assertNotNull(issued.token().expiresAt(), "新令牌必须有有效期（钥匙会被转发出去）");
        assertEquals(Instant.parse("2026-09-13T10:00:00Z")
                        .plus(Duration.ofDays(ApiToken.DEFAULT_TTL_DAYS)),
                issued.token().expiresAt());
    }

    @Test
    void validate_expiredToken_returnsEmpty() {
        var issued = service.issue("adai", "快捷指令", List.of("learn:digest"));
        assertTrue(service.validate(issued.plainToken()).isPresent(), "到期前可用");
        clock.advance(Duration.ofDays(ApiToken.DEFAULT_TTL_DAYS));   // 恰好到期（now == expiresAt）
        assertTrue(service.validate(issued.plainToken()).isEmpty(), "到期即失效");
    }

    @Test
    void validate_legacyTokenWithoutExpiry_stillWorks() {
        // 存量老令牌（文件里没有 expiresAt）→ 不过期，行为不变，由用户手动撤销
        ApiToken legacy = new ApiToken(AuthService.sha256Hex("adai_" + "a".repeat(64)),
                "adai_aaaaaaaa", "adai", "老钥匙", java.util.Set.of("learn:digest"),
                Instant.parse("2026-01-01T00:00:00Z"), null);
        repository.save(legacy);
        assertTrue(service.validate("adai_" + "a".repeat(64)).isPresent());
    }

    @Test
    void revoke_byFullHash_works() {
        var issued = service.issue("adai", "快捷指令", List.of("learn:digest"));
        assertTrue(service.revoke("adai", issued.token().tokenHash()), "按 id 撤销");
        assertTrue(service.validate(issued.plainToken()).isEmpty());
    }

    @Test
    void revoke_prefixCollision_isRefusedInsteadOfDeletingBoth() {
        // 构造两把前缀相同的令牌（真实概率极低，但旧实现会一次删两把）
        String prefix = "adai_deadbeef";
        ApiToken a = new ApiToken(AuthService.sha256Hex("tok-a"), prefix, "adai", "a",
                java.util.Set.of("learn:digest"), clock.instant(), null);
        ApiToken b = new ApiToken(AuthService.sha256Hex("tok-b"), prefix, "adai", "b",
                java.util.Set.of("learn:digest"), clock.instant(), null);
        repository.save(a);
        repository.save(b);

        assertFalse(service.revoke("adai", prefix), "前缀非唯一命中必须拒绝撤销");
        assertEquals(2, service.list("adai").size(), "两把都还在（不静默误撤）");
        assertTrue(service.revoke("adai", a.tokenHash()), "改用完整 id 可精确撤销");
        assertEquals(1, service.list("adai").size());
    }

    @Test
    void revoke_otherUsersTokenById_isRefused() {
        var mine = service.issue("adai", "我的", List.of("learn:digest"));
        assertFalse(service.revoke("someone-else", mine.token().tokenHash()), "跨账号按 id 撤销必须失败");
        assertTrue(service.list("adai").size() == 1);
    }

    @Test
    void label_truncatesByCodePoint_neverSplitsEmoji() {
        // P2-令牌4：40 个 emoji（每个 2 个 char）——按 char 截断会切出孤立代理字符
        String label = "\uD83D\uDE00".repeat(60);
        var issued = service.issue("adai", label, List.of("learn:digest"));
        String stored = issued.token().label();
        assertEquals(40, stored.codePointCount(0, stored.length()), "按 codePoint 截到 40");
        assertFalse(stored.endsWith("\uD83D"), "不得以孤立高位代理结尾（pitfalls emoji 代理对）");
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

    // ── 轮换（REVIEW S-凭据1 剩余项，2026-09-17）──

    @Test
    void rotate_issuesNew_andRevokesOld_immediately() {
        var old = service.issue("adai", "快捷指令", List.of("learn:digest"));

        var fresh = service.rotate("adai", old.token().tokenHash()).orElseThrow();

        assertNotEquals(old.plainToken(), fresh.plainToken(), "必须换一把新的");
        assertTrue(service.validate(old.plainToken()).isEmpty(), "旧钥匙立刻失效（不留两把同时有效）");
        assertTrue(service.validate(fresh.plainToken()).isPresent(), "新钥匙可用");
        assertEquals("快捷指令", fresh.token().label(), "label 继承旧钥匙，用户能对上号");
        assertEquals(old.token().scopes(), fresh.token().scopes(), "权限不变");
        assertEquals(1, service.list("adai").size(), "账号下只剩一把（旧的没留残影）");
    }

    @Test
    void rotate_unknownOrAnotherAccount_isEmpty() {
        assertTrue(service.rotate("adai", "deadbeefdeadbeef").isEmpty(), "找不到 → 空（Controller 转 404）");
        assertTrue(service.rotate("adai", "  ").isEmpty(), "空参不炸");

        var mine = service.issue("adai", "我的", List.of("learn:digest"));
        assertTrue(service.rotate("someone-else", mine.token().tokenHash()).isEmpty(),
                "跨账号轮换必须失败（限定 owner）");
        assertTrue(service.validate(mine.plainToken()).isPresent(), "失败的轮换不得动到原钥匙");
    }
}
