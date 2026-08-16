package com.adaiadai.core.infrastructure.push;

import com.adaiadai.core.kernel.push.PushChannel;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WeChatPushChannel — 微信推送渠道测试（Server酱，RFC 20260816）。
 * <p>
 * 覆盖：未配置 SendKey → 渠道不可用（静默跳过，Feed 不受影响）；配置后可用；
 * push 不抛错（外部 HTTP 不在单测范围）。
 */
class WeChatPushChannelTest {

    @Test
    void missingSendKey_disabled() {
        WeChatPushChannel channel = new WeChatPushChannel("");
        assertFalse(channel.enabled(), "未配置 SendKey 应不可用");
        // 不可用时 push 静默跳过，不抛错
        channel.push("adai", new PushChannel.PushMessage(
                "t", "c", "session", null, null, LocalTime.now()));
        assertTrue(true);
    }

    @Test
    void withSendKey_enabled() {
        WeChatPushChannel channel = new WeChatPushChannel("SCT-test-key");
        assertTrue(channel.enabled(), "配置 SendKey 应可用");
    }

    @Test
    void nullSendKey_disabled() {
        WeChatPushChannel channel = new WeChatPushChannel(null);
        assertFalse(channel.enabled());
    }
}
