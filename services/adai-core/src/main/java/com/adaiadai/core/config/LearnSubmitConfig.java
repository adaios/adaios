package com.adaiadai.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * LearnSubmitConfig — learn 消化专用执行器（2026-09-10 learn 喂入入口批）。
 * <p>
 * learn 卡片化同走 LLM（同步 digest 实测风险同复盘：AI 生成几十秒级，远超 app 15s /
 * web 客户端超时）。POST /learn/cards 改为提交即返回（{@code status=running}），消化放后台
 * 线程池执行，前端轮询 {@code GET /learn/digest/status} 直到 done/failed。独立小池与复盘
 * 池互不挤占：2 线程 + 8 队列（单用户产品绰绰有余），队列满走 AbortPolicy →
 * LearnDigestAppService 捕获后抛 LearnException 400（fail-visible）。
 */
@Configuration
public class LearnSubmitConfig {

    @Bean(name = "learnSubmitExecutor")
    public Executor learnSubmitExecutor() {
        return new ThreadPoolExecutor(
                2, 2, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(8),
                r -> {
                    Thread t = new Thread(r, "learn-submit");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }
}
