package com.adaiadai.core.infrastructure.storage;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REVIEW P2-工程13：共享 per-card 锁池的**上限**与**稳定性**（防无界增长 / 同键同锁 / 命名空间隔离）。
 */
class CardLockRegistryTest {

    @Test
    void stripes_areFixed_neverGrowWithKeys() {
        CardLockRegistry registry = new CardLockRegistry();
        assertEquals(registry.stripeCount(), CardLockRegistry.STRIPES);
        int baseline = registry.lockCount();
        assertTrue(baseline > 0 && baseline <= 64, "锁对象总数应为常量（2×条带），实际=" + baseline);

        // REVIEW P2-工程13 发现5：只断言 lockCount()（**自报**数字）会被「锁池其实无界、但自报 64」的
        // 实现骗过——变异把 cardLock 改成 ConcurrentHashMap.computeIfAbsent(new Object()) 动态建锁后，
        // 本类 4/4 仍全绿，而反射实测 5000 个不同 key 后真实活锁对象 5000 个。
        // 因此这里断言**可观察不变量**：把返回的 monitor 按**身份**收进集合，数真实对象。
        Set<Object> monitors = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < 5_000; i++) {
            monitors.add(registry.cardLock("user-" + i, "card_" + i));
            monitors.add(registry.endLock("user-" + i, "card_" + i));
        }
        assertTrue(monitors.size() <= 2 * CardLockRegistry.STRIPES,
                "真实活锁对象数不得超过 2×STRIPES（无界 map 是 P2-交易37 同族问题）| 实测=" + monitors.size());
        assertEquals(baseline, registry.lockCount(),
                "锁池不得随 (userId,cardId) 数量增长（lockCount 自报；真实对象数见上一条断言）");
    }

    @Test
    void sameKey_sameLock_acrossCalls() {
        CardLockRegistry registry = new CardLockRegistry();
        String cardId = "card_stable_1";

        assertSame(registry.cardLock("u1", cardId), registry.cardLock("u1", cardId), "同键必须恒同锁");
        assertSame(registry.endLock("u1", cardId), registry.endLock("u1", cardId), "同键必须恒同锁");
        assertNotSame(registry.cardLock("u1", cardId), registry.endLock("u1", cardId),
                "cardLock 与 endLock 是两个命名空间，不可混为一谈（混用会让 append 等 AI）");
    }

    @Test
    void legacyNumericCardId_mapsToSameLockAsCanonical() {
        // CardFileRepository.findById 兼容旧版数字 id（"178..." 命中的是 "card_178..."）——
        // 锁键必须同样归一，否则同一个卡用两种写法传入会落进两把锁，锁形同虚设。
        CardLockRegistry registry = new CardLockRegistry();
        assertSame(registry.cardLock("u1", "1784872873886"), registry.cardLock("u1", "card_1784872873886"));
        assertSame(registry.endLock("u1", "1784872873886"), registry.endLock("u1", "card_1784872873886"));
    }

    @Test
    void nullOrBlankUserId_fallsBackToDefault() {
        CardLockRegistry registry = new CardLockRegistry();
        assertSame(registry.cardLock(null, "card_x"), registry.cardLock("default", "card_x"));
        assertSame(registry.cardLock("  ", "card_x"), registry.cardLock("default", "card_x"));
    }
}
