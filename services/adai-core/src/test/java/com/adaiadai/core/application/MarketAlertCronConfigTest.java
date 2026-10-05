package com.adaiadai.core.application;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-交易63（2026-09-23）守卫：`adai.market.alert.poll-cron` 的**唯一真相源**是
 * {@link MarketAlertService#CRON_POLL}，不得在 `application.yml` 里再配一份。
 * <p>
 * 事故回顾：2026-08-30（提交 `212eba7`）把轮询首轮从 09:00 改到 10:00 时只改了代码默认值，
 * yml 里仍留着旧值 `9-11,13-15`——而 `@Scheduled(cron = "${adai.market.alert.poll-cron:…}")`
 * 的**配置值优先于默认值**，于是那次修复空转近一个月：09:00/09:30 继续拿上一交易日收盘价判定异动、
 * 并把它当「现价」推给用户（生产实据 2026-09-23 09:00「有研新材 现价 49.95」= 前一日收盘价，
 * 而当日实际开盘 49.36）。
 * <p>
 * 这两个断言就是那次的机械守卫：谁把该键加回 yml，或把首轮改回 9 点，这里立刻红。
 */
class MarketAlertCronConfigTest {

    @Test
    void applicationYmlMustNotConfigurePollCron() throws Exception {
        // 2026-10-05 修两处（本守护原先形同虚设 + 会被注释误伤）：
        //  ① 原实现读 ClassPathResource("application.yml")，而 src/test/resources/application.yml
        //     在 classpath 上**优先命中**——于是它一直在检查那份**测试副本**，真有人把该键写回
        //     生产 yml 它也不会红（守护失效）。改为**显式读生产 yml**（gradle test / 直跑均以
        //     services/adai-core 为工作目录）。
        //  ② 原判据 `yml.contains("poll-cron")` 是**整串匹配**，注释里提及该键（刻意的反面教材留痕）
        //     也会误报。改为**只看非注释行**——注释不是配置。
        String yml = Files.readString(Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);

        boolean configured = yml.lines()
                .map(String::trim)
                .filter(line -> !line.startsWith("#"))
                .anyMatch(line -> line.contains("poll-cron"));

        assertFalse(configured,
                "生产 application.yml 里不得**配置** poll-cron——唯一真相源是 MarketAlertService.CRON_POLL。"
                        + "配置值会覆盖代码默认值（2026-08-30 的修复正是这样空转的）；"
                        + "临时调整请用环境变量 ADAI_MARKET_ALERT_POLL_CRON");
    }

    @Test
    void codeDefaultKeepsFirstRoundAtTen() {
        assertTrue(MarketAlertService.CRON_POLL.startsWith("0 */30 10-11"),
                "首轮必须是 10:00——09:00/09:30 的行情仍属上一交易日，会推错「现价」并烧掉当日去重名额。实际: "
                        + MarketAlertService.CRON_POLL);
    }
}
