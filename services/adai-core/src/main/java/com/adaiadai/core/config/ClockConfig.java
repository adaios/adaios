package com.adaiadai.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * ClockConfig — 系统时钟 bean。
 * <p>
 * Spring Boot 默认不注册 {@link Clock}，而行情条窗口等时间判定需要可注入时钟
 * （2026-09-05：FeedAppService 只在 A 股交易时段注入大盘行情条，测试传固定 Clock 验证
 * 周末/节假日/收盘后不显示）。统一在此提供系统默认时区时钟，业务类构造注入。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemDefaultZone();
    }
}
