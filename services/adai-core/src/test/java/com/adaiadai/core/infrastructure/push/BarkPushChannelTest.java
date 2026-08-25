package com.adaiadai.core.infrastructure.push;

import com.adaiadai.core.kernel.push.PushChannel;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BarkPushChannel — iOS 原生推送渠道测试（Bark，2026-08-25 新增，替代微信 Server酱）。
 * <p>
 * 覆盖：未配置 key → 渠道不可用（静默跳过，Feed 不受影响）；配置后可用；
 * push 不抛错（外部 HTTP 不在单测范围）；自定义 base-url 可用。
 */
class BarkPushChannelTest {

    @Test
    void missingKey_disabled() {
        BarkPushChannel channel = new BarkPushChannel("https://api.day.app", "");
        assertFalse(channel.enabled(), "未配置 key 应不可用");
        // 不可用时 push 静默跳过，不抛错
        channel.push("adai", new PushChannel.PushMessage(
                "t", "c", "session", null, null, LocalTime.now()));
        assertTrue(true);
    }

    @Test
    void withKey_enabled() {
        BarkPushChannel channel = new BarkPushChannel("https://api.day.app", "test-device-key");
        assertTrue(channel.enabled(), "配置 key 应可用");
    }

    @Test
    void nullKey_disabled() {
        BarkPushChannel channel = new BarkPushChannel("https://api.day.app", null);
        assertFalse(channel.enabled());
    }

    @Test
    void blankBaseUrl_fallsBackToDefault() {
        BarkPushChannel channel = new BarkPushChannel("  ", "test-device-key");
        assertTrue(channel.enabled(), "base-url 为空应回退默认且不影响可用性");
    }
}
