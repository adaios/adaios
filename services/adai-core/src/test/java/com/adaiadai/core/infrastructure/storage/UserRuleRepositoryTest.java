package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.trading.UserRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UserRuleRepositoryTest — 规则集持久化（rules 批，2026-10-06）。
 * 用真 InMemoryFileStorage 验证：三态/参数/依据 round-trip · 同 id 原位替换 · 删除幂等 ·
 * 损坏降级 · 缺 id/text / 未知状态跳过。
 */
class UserRuleRepositoryTest {

    private static final String USER = "u";

    private UserRuleRepository repo(InMemoryFileStorage storage) {
        return new UserRuleRepository(storage);
    }

    private static UserRule cand(String id, String text) {
        return UserRule.candidate(id, text, Map.of("pct", -3.0),
                new UserRule.Evidence("亏损分布 75% 分位", List.of("亏损 6 笔"), List.of("600206_2026-08-05"),
                        List.of("2026-08-05")),
                "2026-10-06T20:00:00");
    }

    @Test
    void noFile_returnsEmpty() {
        assertTrue(repo(new InMemoryFileStorage()).findByUser(USER).isEmpty());
    }

    @Test
    void upsertThenFind_roundTrips() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        UserRuleRepository r = repo(storage);
        r.upsert(USER, cand("cand-stoploss", "止损：亏到 -3% 就走"));
        r.upsert(USER, UserRule.custom("usr-abc12345", "不追新股", Map.of(), "2026-10-06T21:00:00"));

        List<UserRule> all = r.findByUser(USER);
        assertEquals(2, all.size());
        UserRule c = all.get(0);
        assertEquals("cand-stoploss", c.id());
        assertEquals(UserRule.UserRuleState.CANDIDATE, c.state());
        assertEquals(UserRule.RuleSource.DATA, c.source());
        assertEquals(-3.0, ((Number) c.params().get("pct")).doubleValue());
        assertNotNull(c.evidence());
        assertEquals("亏损分布 75% 分位", c.evidence().how());
        assertEquals(List.of("600206_2026-08-05"), c.evidence().roundIds());
        UserRule u = all.get(1);
        assertEquals(UserRule.UserRuleState.CUSTOM, u.state());
        assertEquals(UserRule.RuleSource.USER, u.source());
        assertNull(u.evidence(), "自定义没有「带据」");
        // File First：文件可读
        assertTrue(storage.read(USER, "trading/user-rules.json").contains("不追新股"));
    }

    @Test
    void upsert_sameId_replacesInPlace() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        UserRuleRepository r = repo(storage);
        r.upsert(USER, cand("cand-stoploss", "旧文本"));
        r.upsert(USER, cand("cand-short-overdue", "第二条"));
        r.upsert(USER, cand("cand-stoploss", "新文本"));   // 同 id → 原位替换

        List<UserRule> all = r.findByUser(USER);
        assertEquals(2, all.size(), "同 id 不新增");
        assertEquals("cand-stoploss", all.get(0).id(), "原位——候选刷新不跳位");
        assertEquals("新文本", all.get(0).text());
        assertEquals("cand-short-overdue", all.get(1).id());
    }

    @Test
    void remove_deletesOnlyThatId_idempotent() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        UserRuleRepository r = repo(storage);
        r.upsert(USER, cand("cand-stoploss", "A"));
        r.upsert(USER, cand("cand-giveback", "B"));

        r.remove(USER, "cand-stoploss");
        List<UserRule> all = r.findByUser(USER);
        assertEquals(1, all.size());
        assertEquals("cand-giveback", all.get(0).id());

        r.remove(USER, "cand-stoploss");   // 幂等：再删不炸
        assertEquals(1, r.findByUser(USER).size());
    }

    @Test
    void corruptedFile_backedUpBeforeDegrade() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        storage.write(USER, "trading/user-rules.json", "{not-json");
        assertTrue(repo(storage).findByUser(USER).isEmpty(), "损坏视为空规则集（降级不坏）");
        assertEquals("{not-json", storage.read(USER, "trading/user-rules.json.bak-corrupt"),
                "P2-交易95：原文先备份再降级（防写侧全量重写把残余内容盖掉）");
    }

    @Test
    void corruptedFile_writeDoesNotLoseOriginal_canRecoverFromBak() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        storage.write(USER, "trading/user-rules.json", "{not-json");
        UserRuleRepository r = repo(storage);
        r.upsert(USER, cand("cand-stoploss", "新写的"));   // 写侧全量重写（损坏时第一次写会盖掉原文）
        assertEquals(1, r.findByUser(USER).size(), "写侧照常工作（损坏后重建）");
        assertEquals("{not-json", storage.read(USER, "trading/user-rules.json.bak-corrupt"),
                "被覆盖的原文仍在 .bak-corrupt（可人工恢复）——P2-交易95");
    }

    @Test
    void missingIdOrText_skipped() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        storage.write(USER, "trading/user-rules.json",
                "{\"rules\":[{\"state\":\"CANDIDATE\",\"text\":\"缺 id\"},"
                        + "{\"id\":\"x\",\"state\":\"CANDIDATE\"},"
                        + "{\"id\":\"ok\",\"state\":\"ACCEPTED\",\"text\":\"有效\"}]}");
        List<UserRule> all = repo(storage).findByUser(USER);
        assertEquals(1, all.size(), "缺 id / 缺 text 的行跳过");
        assertEquals("ok", all.get(0).id());
        assertEquals(UserRule.UserRuleState.ACCEPTED, all.get(0).state());
    }

    @Test
    void unknownState_skipped() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        storage.write(USER, "trading/user-rules.json",
                "{\"rules\":[{\"id\":\"a\",\"state\":\"WAT\",\"text\":\"状态不认识\"},"
                        + "{\"id\":\"b\",\"state\":\"CUSTOM\",\"text\":\"有效\"}]}");
        List<UserRule> all = repo(storage).findByUser(USER);
        assertEquals(1, all.size());
        assertEquals("b", all.get(0).id());
    }
}
