package com.adaiadai.core.infrastructure.ai.interaction;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * AiTraceCleanupInterceptor — 请求级 AI 追踪上下文清理（REVIEW #213）。
 * <p>
 * {@link AiTraceContext} 的 ThreadLocal 由调用点 set 后，装饰器 {@link LoggingAiClient}
 * 快照-恢复回原值（非销毁），Tomcat 线程池复用下会残留到下一个请求——任何
 * "漏 set trace 就调 AI"的路径都会把日志落进上一个请求的用户目录（跨用户隐私泄漏，
 * 历史已发生两次：brief/intent 漏挂，见 {@code e0a1461}）。
 * <p>
 * 本拦截器在每个 HTTP 请求 {@code afterCompletion} 时无条件清空，从根上消灭跨请求残留。
 * 请求内 trace 仍由调用点 set（业务锚点语义不变）；定时任务（@Scheduled）不走 HTTP，
 * 各自在调用点 set，不受本拦截器影响。
 *
 * @see AiTraceContext
 * @see LoggingAiClient
 */
public class AiTraceCleanupInterceptor implements HandlerInterceptor {

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AiTraceContext.restore(null);
    }
}
