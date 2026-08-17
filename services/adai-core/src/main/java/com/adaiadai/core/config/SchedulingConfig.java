package com.adaiadai.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * SchedulingConfig — 定时任务调度器配置（P3，2026-08-17）。
 * <p>
 * 默认单线程调度器：多个 @Scheduled 共享 1 线程，LLM 60s 超时/网络慢会顺延后续任务
 * （15:05 收盘更新可能拖到 15:10 买点扫描后）。改为 4 线程，互不阻塞。
 */
@Configuration
public class SchedulingConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("scheduled-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
