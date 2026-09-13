package com.adaiadai.core.kernel.push;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PushDevice — 设备标识校验与环境归一化测试（RFC 20260913 APNs 批）。
 * <p>
 * 重点：token 会被拼进 APNs 出站 URL 路径，必须严格只收十六进制；
 * 环境归一化的未知值回落方向（sandbox）是显式取舍，需有测试锁住，防将来被「顺手改成 production」。
 */
class PushDeviceTest {

    private static final String VALID = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";

    @Test
    void validHexToken_accepted() {
        assertTrue(PushDevice.isValidToken(VALID));
        assertTrue(PushDevice.isValidToken(VALID.toUpperCase()), "大写十六进制也应接受（APNs 两种写法都可能）");
        assertTrue(PushDevice.isValidToken("  " + VALID + "  "), "两侧空白应容忍");
    }

    @Test
    void tooShortOrTooLong_rejected() {
        assertFalse(PushDevice.isValidToken("a1b2c3"), "过短拒绝");
        assertFalse(PushDevice.isValidToken("a".repeat(31)), "短于 32 拒绝");
        assertFalse(PushDevice.isValidToken("a".repeat(201)), "长于 200 拒绝");
    }

    @Test
    void nonHexOrEmpty_rejected() {
        assertFalse(PushDevice.isValidToken(null));
        assertFalse(PushDevice.isValidToken(""));
        assertFalse(PushDevice.isValidToken("../etc/passwd".repeat(4)), "路径穿越字符必须拒绝（token 进 URL 路径）");
        assertFalse(PushDevice.isValidToken("g".repeat(64)), "非十六进制字符拒绝");
        assertFalse(PushDevice.isValidToken("a1b2-".repeat(16)), "连字符拒绝");
    }

    @Test
    void environment_normalization() {
        assertEquals(PushDevice.ENV_PRODUCTION, PushDevice.normalizeEnvironment("production"));
        assertEquals(PushDevice.ENV_PRODUCTION, PushDevice.normalizeEnvironment("PROD"));
        assertEquals(PushDevice.ENV_SANDBOX, PushDevice.normalizeEnvironment("development"));
        assertEquals(PushDevice.ENV_SANDBOX, PushDevice.normalizeEnvironment("dev"));
        assertEquals(PushDevice.ENV_SANDBOX, PushDevice.normalizeEnvironment("sandbox"));
    }

    @Test
    void unknownEnvironment_fallsBackToSandbox() {
        // 显式取舍：装机路径是 development profile 侧载 → 真实环境就是 sandbox；
        // 送错时 APNs 回 BadDeviceToken 且被日志显式记下（fail-visible），不静默消失
        assertEquals(PushDevice.ENV_SANDBOX, PushDevice.normalizeEnvironment(null));
        assertEquals(PushDevice.ENV_SANDBOX, PushDevice.normalizeEnvironment(""));
        assertEquals(PushDevice.ENV_SANDBOX, PushDevice.normalizeEnvironment("不知道"));
    }
}
