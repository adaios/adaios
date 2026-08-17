package com.adaiadai.core.domain.trading;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PushSettings — RFC 20260817 推送开关。
 * 覆盖：默认全开 / 关闭后 isEnabled=false / with 返回新实例不改原。
 */
class PushSettingsTest {

    @Test
    void defaults_allEnabled() {
        PushSettings s = PushSettings.defaults();
        for (String t : PushSettings.ALL_TYPES) {
            assertTrue(s.isEnabled(t), t + " 默认应开启");
        }
    }

    @Test
    void disabledType_isFalse() {
        PushSettings s = PushSettings.defaults().with("session", false);
        assertFalse(s.isEnabled("session"), "关闭后 session 应 false");
        assertTrue(s.isEnabled("buy-point"), "其他类型不受影响");
    }

    @Test
    void with_returnsNewInstance_keepsOriginal() {
        PushSettings original = PushSettings.defaults();
        PushSettings updated = original.with("market", false);
        assertTrue(original.isEnabled("market"), "原实例不变");
        assertFalse(updated.isEnabled("market"), "新实例关闭");
    }

    @Test
    void unknownType_defaultsEnabled() {
        PushSettings s = PushSettings.defaults().with("session", false);
        assertTrue(s.isEnabled("nonexistent"), "未知类型默认开（不阻断）");
        assertTrue(s.isEnabled(null), "null 类型默认开");
    }
}
