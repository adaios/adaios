package com.adaiadai.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * ReviewSubmitConfig — 复盘生成专用执行器（2026-09-07 复盘超时修复批）。
 * <p>
 * 复盘 AI 生成实测 77~176s（deepseek-v4-pro + 4 万字上下文），远超 HTTP 客户端超时。
 * POST /trading/review 改为提交即返回（{@code status=pending/running}），生成放后台线程池执行，
 * 前端轮询 GET /trading/review 直到文件就绪。独立小池：2 线程 + 8 队列（单用户产品绰绰有余），
 * 队列满走 AbortPolicy → TradingReviewAppService 捕获后抛 TradingException 400（fail-visible）。
 */
@Configuration
public class ReviewSubmitConfig {

    @Bean(name = "reviewSubmitExecutor")
    public Executor reviewSubmitExecutor() {
        return new ThreadPoolExecutor(
                2, 2, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(8),
                r -> {
                    Thread t = new Thread(r, "review-submit");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }
}
