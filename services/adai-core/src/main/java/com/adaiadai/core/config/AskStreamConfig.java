package com.adaiadai.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * AskStreamExecutor — 流式问答专用线程池（ai-calling-governance 批 2）。
 * <p>
 * SSE 业务在请求线程外执行（SseEmitter 模式）；独立小池而非 ForkJoinPool.commonPool——
 * 流式读取是长阻塞 IO，占满 commonPool 会拖垮其他并行流。单用户产品 2~4 线程 + 32 队列
 * 绰绰有余；队列满走 AbortPolicy，AskStreamController 捕获后返回 503（fail-visible，不静默）。
 */
@Configuration
public class AskStreamConfig {

    @Bean(name = "askStreamExecutor")
    public Executor askStreamExecutor() {
        return new ThreadPoolExecutor(
                2, 4, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(32),
                r -> {
                    Thread t = new Thread(r, "ask-stream");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }
}
