package com.adaiadai.core.kernel.account;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Account 实体测试（REVIEW P2-3：脏 JSON 的 "plugins":[null] 不 NPE、过滤 null 元素）。
 */
class AccountTest {

    @Test
    void compactConstructor_filtersNullPluginElements() {
        // 脏 accounts.json 出现 "plugins":[null] 时，反序列化经紧凑构造器应过滤而非 NPE
        Account account = new Account("alice", "user", true, LocalDate.of(2026, 8, 2),
                Arrays.asList("trading", null, "project"));

        assertEquals(List.of("trading", "project"), account.plugins(),
                "null 插件元素应被过滤，不 NPE 不落脏数据");
    }

    @Test
    void compactConstructor_normalizesNullList() {
        Account account = new Account("alice", "user", true, LocalDate.of(2026, 8, 2), null);
        assertTrue(account.plugins().isEmpty(), "null plugins 列表应归一为空列表");
    }
}
