package com.adaiadai.core.infrastructure.ai.interaction;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * AiTraceCleanupInterceptor — 请求级 AI 追踪上下文清理（REVIEW #213）。
 * 覆盖：请求完成后无条件清空 ThreadLocal（防跨请求残留 → 跨用户错属）。
 */
class AiTraceCleanupInterceptorTest {

    @AfterEach
    void tearDown() {
        AiTraceContext.restore(null); // 清理，防跨测试污染
    }

    @Test
    void afterCompletion_clearsThreadLocalTrace() {
        // 模拟请求内调用点 set 后线程残留（装饰器 restore 回原值而非销毁）
        AiTraceContext.set("adai", "rec_1", null, "question");
        interceptor().afterCompletion(
                mock(HttpServletRequest.class), mock(HttpServletResponse.class), null, null);
        assertNull(AiTraceContext.get(), "请求结束后 ThreadLocal 必须清空");
    }

    @Test
    void afterCompletion_whenAlreadyClean_staysClean() {
        interceptor().afterCompletion(
                mock(HttpServletRequest.class), mock(HttpServletResponse.class), null, null);
        assertNull(AiTraceContext.get());
    }

    private AiTraceCleanupInterceptor interceptor() {
        return new AiTraceCleanupInterceptor();
    }
}
