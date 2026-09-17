package com.adaiadai.core.application;

import com.adaiadai.core.domain.learn.LearnException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ApiTokenGuardTest — 外部令牌付费动作闸门（REVIEW S-凭据1 剩余项，2026-09-17 B4 批）。
 *
 * <p>覆盖：令牌级频控按分钟窗口生效、会话调用不受限、跨 IP 只告警不阻断、不同令牌互不影响。
 */
class ApiTokenGuardTest {

    private final ApiTokenGuard guard = new ApiTokenGuard();

    @Test
    void sessionCallIsNeverThrottled() {
        // 会话调用不带 tokenId：限的是「钥匙被谁拿到」，不是限号主本人。
        for (int i = 0; i < 50; i++) {
            assertDoesNotThrow(() -> guard.checkPayAction("adai", null, "1.1.1.1"));
            assertDoesNotThrow(() -> guard.checkPayAction("adai", "  ", "1.1.1.1"));
        }
    }

    @Test
    void tokenIsThrottledAfterFiveCallsInOneMinute() {
        // D5 拍板：5 次/分钟。第 6 次必须被拦（付费动作，一把被转发的钥匙不能无限刷）。
        for (int i = 0; i < ApiTokenGuard.PAY_ACTION_PER_MINUTE; i++) {
            assertDoesNotThrow(() -> guard.checkPayAction("adai", "adai_abc12345", "1.1.1.1"),
                    "第 " + (i + 1) + " 次应当放行");
        }

        LearnException e = assertThrows(LearnException.class,
                () -> guard.checkPayAction("adai", "adai_abc12345", "1.1.1.1"));

        assertTrue(e.getMessage().contains("一分钟内"), "要人话说清原因：" + e.getMessage());
        assertTrue(e.getMessage().contains("学习"), "要指路到「学习」页（可以收回它）：" + e.getMessage());
    }

    @Test
    void differentTokensHaveIndependentWindows() {
        for (int i = 0; i < ApiTokenGuard.PAY_ACTION_PER_MINUTE; i++) {
            guard.checkPayAction("adai", "adai_token1", "1.1.1.1");
        }
        // 另一把钥匙不受牵连（频控是 per-token，不是 per-user）
        assertDoesNotThrow(() -> guard.checkPayAction("adai", "adai_token2", "1.1.1.1"));
    }

    @Test
    void ipChangeOnlyWarnsNeverBlocks() {
        // 手机切 Wi-Fi/蜂窝会换 IP —— 硬拦会误伤正常使用；只留痕（WARN 进生产日志，日报会捞）。
        assertDoesNotThrow(() -> guard.checkPayAction("adai", "adai_abc12345", "1.1.1.1"));
        assertDoesNotThrow(() -> guard.checkPayAction("adai", "adai_abc12345", "2.2.2.2"));
        assertDoesNotThrow(() -> guard.checkPayAction("adai", "adai_abc12345", null));
    }
}
