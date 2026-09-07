package com.adaiadai.core.application;

import com.adaiadai.core.domain.learn.LearnCard;
import com.adaiadai.core.domain.learn.LearnCardRepository;
import com.adaiadai.core.infrastructure.storage.PushSettingsRepository;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.adaiadai.core.kernel.push.PushChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * LearnReviewPushService — 学习复习提醒推送（RFC 20260829 learn V2 消化闭环批 4）。
 * <p>
 * 每晚 20:00（用户拍板 2026-09-07）：遍历启用 learn 插件的用户，聚合「进入复习状态（review）
 * 已满 7 天仍未完成（done）」的卡片，推一条汇总到 Feed（type=learn-review，PushChannel 渠道化；
 * 开关走 PushSettings——缺失默认开，可关）。
 * <p>
 * 间隔复习语义（RFC 3.2「一周/一月回看卡片」的最简落地）：卡片 new → 用户复习 → review
 * （卡片在复习队列）；review 满 7 天未 done → 每晚提醒「这几张该回看了」。done = 你确认消化完毕。
 * <p>
 * 仅聚合提醒，不自动改状态（done 与否由你判断）。推送失败不阻断（逐用户 catch，日志告警）。
 */
@Service
public class LearnReviewPushService {

    private static final Logger log = LoggerFactory.getLogger(LearnReviewPushService.class);

    /** 每晚 20:00（非交易日限定——学习不受行情日程约束）。 */
    static final String CRON_EVENING = "0 0 20 * * *";

    /** review 进入 7 天仍未 done 即提醒（用户拍板口径 2026-09-07）。 */
    static final int REVIEW_STALE_DAYS = 7;

    /** 推送标题（Feed 卡 + Bark 均透传；前端按标题分类/配色需同步）。 */
    static final String TITLE = "学习复习提醒";

    private final AccountRepository accountRepository;
    private final PluginService pluginService;
    private final LearnCardRepository learnCardRepository;
    private final PushSettingsRepository pushSettingsRepository;
    private final List<PushChannel> pushChannels;

    public LearnReviewPushService(AccountRepository accountRepository,
                                  PluginService pluginService,
                                  LearnCardRepository learnCardRepository,
                                  PushSettingsRepository pushSettingsRepository,
                                  List<PushChannel> pushChannels) {
        this.accountRepository = accountRepository;
        this.pluginService = pluginService;
        this.learnCardRepository = learnCardRepository;
        this.pushSettingsRepository = pushSettingsRepository;
        this.pushChannels = pushChannels;
    }

    @Scheduled(cron = "${adai.learn.review-cron:" + CRON_EVENING + "}")
    public void reviewReminder() {
        LocalDate today = LocalDate.now();
        LocalDate deadline = today.minusDays(REVIEW_STALE_DAYS);
        for (Account account : accountRepository.findAll()) {
            String userId = account.userId();
            if (userId == null || !account.enabled()) continue;
            if (!pluginService.hasPlugin(userId, PluginRegistry.PLUGIN_LEARN)) continue;
            try {
                remindIfStale(userId, deadline);
            } catch (Exception e) {
                log.warn("学习复习提醒失败 | userId={} | {}", userId, e.getMessage());
            }
        }
    }

    /** 单用户：聚合 review 超 7 天未 done 的卡片，有则推送。 */
    private void remindIfStale(String userId, LocalDate deadline) {
        List<LearnCard> stale = staleReviewCards(userId, deadline);
        if (stale.isEmpty()) {
            return;
        }
        // RFC 20260817：推送开关——learn-review 关闭则不推（读侧也按类型门控）
        if (!pushSettingsRepository.findByUser(userId).isEnabled("learn-review")) {
            log.info("学习复习提醒跳过（用户关闭 learn-review）| userId={}", userId);
            return;
        }
        String content = buildContent(stale);
        PushChannel.PushMessage message = new PushChannel.PushMessage(
                TITLE, content, "learn-review", null, null, java.time.LocalTime.now());
        for (PushChannel channel : pushChannels) {
            if (channel.enabled()) {
                channel.push(userId, message);
            }
        }
        log.info("学习复习提醒已推送 | userId={} | 待复习 {} 张", userId, stale.size());
    }

    /** 跨 ai/trading/other 全扫，筛 status=review 且 created ≤ deadline 的卡片。 */
    List<LearnCard> staleReviewCards(String userId, LocalDate deadline) {
        List<LearnCard> stale = new ArrayList<>();
        for (String type : List.of(LearnCard.TYPE_AI, LearnCard.TYPE_TRADING, LearnCard.TYPE_OTHER)) {
            for (LearnCard card : learnCardRepository.list(userId, type)) {
                if (LearnCard.STATUS_REVIEW.equals(card.status())
                        && card.created() != null && !card.created().isAfter(deadline)) {
                    stale.add(card);
                }
            }
        }
        stale.sort(java.util.Comparator.comparing(LearnCard::created));
        return stale;
    }

    private String buildContent(List<LearnCard> stale) {
        StringBuilder sb = new StringBuilder();
        sb.append("有 ").append(stale.size()).append(" 张卡片进入复习队列已满 7 天，该回看了：\n");
        for (LearnCard card : stale) {
            sb.append("· ").append(card.title()).append("（").append(card.created()).append("）\n");
        }
        sb.append("看完记得把状态改成 done 哦。");
        return sb.toString();
    }
}
