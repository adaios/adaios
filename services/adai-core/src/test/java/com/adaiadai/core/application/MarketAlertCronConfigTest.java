package com.adaiadai.core.application;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

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
        String yml = new String(new ClassPathResource("application.yml").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertFalse(yml.contains("poll-cron"),
                "application.yml 里不得出现 poll-cron——唯一真相源是 MarketAlertService.CRON_POLL。"
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
