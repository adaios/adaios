package com.adaiadai.core.application;

import com.adaiadai.core.domain.learn.LearnCard;
import com.adaiadai.core.domain.learn.LearnCardRepository;
import com.adaiadai.core.domain.trading.PushSettings;
import com.adaiadai.core.infrastructure.storage.PushSettingsRepository;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.adaiadai.core.kernel.push.PushChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LearnReviewPushServiceTest — 学习复习提醒推送（RFC 20260829 V2 批 4 + S-learn1 修复 2026-09-07）。
 * <p>
 * 验证：review **进入队列（review_at）** 满 7 天未 done 的卡被聚合推送；created 旧但刚进 review
 * 不误推（S-learn1 计时口径）；同卡 7 天节流（reminded_at，防每晚 nag）；new/done/未满期不推；
 * 仅 learn 插件用户；开关 learn-review 关闭不推（含 learn 侧端点读写）；逐用户隔离。
 */
class LearnReviewPushServiceTest {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final PluginService pluginService = mock(PluginService.class);
    private final LearnCardRepository learnCardRepository = mock(LearnCardRepository.class);
    private final PushSettingsRepository pushSettingsRepository = mock(PushSettingsRepository.class);
    private final List<PushChannel> channels = new ArrayList<>();
    private final RecordingChannel channel = new RecordingChannel();
    private LearnReviewPushService service;

    static class RecordingChannel implements PushChannel {
        final List<PushMessage> pushed = new ArrayList<>();
        @Override public String name() { return "test"; }
        @Override public boolean enabled() { return true; }
        @Override public void push(String userId, PushMessage message) { pushed.add(message); }
    }

    @BeforeEach
    void setUp() {
        channels.add(channel);
        service = new LearnReviewPushService(accountRepository, pluginService,
                learnCardRepository, pushSettingsRepository, channels);
        when(pushSettingsRepository.findByUser(anyString())).thenReturn(PushSettings.defaults());
    }

    private void withUsers(String... userIds) {
        List<Account> accounts = new ArrayList<>();
        for (String u : userIds) {
            accounts.add(new Account(u, Account.ROLE_USER, true, LocalDate.of(2026, 8, 2), List.of()));
        }
        when(accountRepository.findAll()).thenReturn(accounts);
    }

    private void withLearnPlugin(String userId) {
        when(pluginService.hasPlugin(userId, PluginRegistry.PLUGIN_LEARN)).thenReturn(true);
    }

    /** review 卡：进入队列日 = reviewAt（S-learn1 计时起点，非 created）。 */
    private LearnCard reviewCard(String type, String title, LocalDate reviewAt) {
        return new LearnCard(type, title, null, null, null, null, reviewAt, LearnCard.STATUS_REVIEW,
                false, null, List.of(), "观点", List.of(), List.of(), "", reviewAt, null);
    }

    /** review 卡（已提醒过，remindedAt 非空）：验证节流。 */
    private LearnCard reviewCardReminded(String type, String title, LocalDate reviewAt, LocalDate remindedAt) {
        return new LearnCard(type, title, null, null, null, null, reviewAt, LearnCard.STATUS_REVIEW,
                false, null, List.of(), "观点", List.of(), List.of(), "", reviewAt, remindedAt);
    }

    /** 非 review 卡。 */
    private LearnCard otherCard(String type, String title, LocalDate created, String status) {
        return new LearnCard(type, title, null, null, null, null, created, status,
                false, null, List.of(), "观点", List.of(), List.of(), "");
    }

    /** 今天（服务用 LocalDate.now()）。 */
    private static LocalDate daysAgo(int n) {
        return LocalDate.now().minusDays(n);
    }

    @Test
    void reviewStale_reviewAtOver7days_pushed() {
        withUsers("adai");
        withLearnPlugin("adai");
        when(learnCardRepository.list("adai", LearnCard.TYPE_AI)).thenReturn(List.of(
                reviewCard(LearnCard.TYPE_AI, "超期卡", daysAgo(10))));
        when(learnCardRepository.list("adai", LearnCard.TYPE_TRADING)).thenReturn(List.of());
        when(learnCardRepository.list("adai", LearnCard.TYPE_OTHER)).thenReturn(List.of());

        service.reviewReminder();

        assertEquals(1, channel.pushed.size());
        PushChannel.PushMessage msg = channel.pushed.get(0);
        assertEquals("学习复习提醒", msg.title());
        assertEquals("learn-review", msg.type());
        assertTrue(msg.content().contains("超期卡"));
        assertTrue(msg.content().contains("1 张卡片"));
        // 推送后 markReminded 写提醒日（节流）
        verify(learnCardRepository).markReminded("adai", LearnCard.TYPE_AI, "超期卡", LocalDate.now());
    }

    @Test
    void oldCreated_justReviewed_notPushed() {
        // S-learn1 核心回归：消化日旧但 review_at=今天 → 不误推「满 7 天」
        withUsers("adai");
        withLearnPlugin("adai");
        when(learnCardRepository.list("adai", LearnCard.TYPE_AI)).thenReturn(List.of(
                reviewCard(LearnCard.TYPE_AI, "老素材刚复习", LocalDate.now())));
        when(learnCardRepository.list("adai", LearnCard.TYPE_TRADING)).thenReturn(List.of());
        when(learnCardRepository.list("adai", LearnCard.TYPE_OTHER)).thenReturn(List.of());

        service.reviewReminder();

        assertTrue(channel.pushed.isEmpty(), "刚进入 review 不满 7 天不推（无论 created 多旧）");
    }

    @Test
    void newOrDoneOrNotStale_notPushed() {
        withUsers("adai");
        withLearnPlugin("adai");
        when(learnCardRepository.list("adai", LearnCard.TYPE_AI)).thenReturn(List.of(
                otherCard(LearnCard.TYPE_AI, "新卡", daysAgo(1), LearnCard.STATUS_NEW),
                otherCard(LearnCard.TYPE_AI, "已完成", daysAgo(20), LearnCard.STATUS_DONE),
                reviewCard(LearnCard.TYPE_AI, "未满7天", daysAgo(5))));
        when(learnCardRepository.list("adai", LearnCard.TYPE_TRADING)).thenReturn(List.of());
        when(learnCardRepository.list("adai", LearnCard.TYPE_OTHER)).thenReturn(List.of());

        service.reviewReminder();

        assertTrue(channel.pushed.isEmpty(), "无满 7 天 review 卡不应推送");
    }

    @Test
    void reviewStale_onDay7Included() {
        withUsers("adai");
        withLearnPlugin("adai");
        when(learnCardRepository.list("adai", LearnCard.TYPE_AI)).thenReturn(List.of(
                reviewCard(LearnCard.TYPE_AI, "刚好满7天", daysAgo(7))));
        when(learnCardRepository.list("adai", LearnCard.TYPE_TRADING)).thenReturn(List.of());
        when(learnCardRepository.list("adai", LearnCard.TYPE_OTHER)).thenReturn(List.of());

        service.reviewReminder();

        assertEquals(1, channel.pushed.size());
        assertTrue(channel.pushed.get(0).content().contains("刚好满7天"));
    }

    @Test
    void remindedWithinCooldown_notPushedAgain() {
        // S-learn1 节流：昨天刚提醒过 → 今天不再推（防每晚 nag）
        withUsers("adai");
        withLearnPlugin("adai");
        when(learnCardRepository.list("adai", LearnCard.TYPE_AI)).thenReturn(List.of(
                reviewCardReminded(LearnCard.TYPE_AI, "昨天提醒过", daysAgo(10), LocalDate.now().minusDays(1))));
        when(learnCardRepository.list("adai", LearnCard.TYPE_TRADING)).thenReturn(List.of());
        when(learnCardRepository.list("adai", LearnCard.TYPE_OTHER)).thenReturn(List.of());

        service.reviewReminder();

        assertTrue(channel.pushed.isEmpty(), "冷却期（7 天内）不重复推");
    }

    @Test
    void remindedCooldownExpired_pushedAgain() {
        withUsers("adai");
        withLearnPlugin("adai");
        when(learnCardRepository.list("adai", LearnCard.TYPE_AI)).thenReturn(List.of(
                reviewCardReminded(LearnCard.TYPE_AI, "7天前提醒过", daysAgo(20), daysAgo(7))));
        when(learnCardRepository.list("adai", LearnCard.TYPE_TRADING)).thenReturn(List.of());
        when(learnCardRepository.list("adai", LearnCard.TYPE_OTHER)).thenReturn(List.of());

        service.reviewReminder();

        assertEquals(1, channel.pushed.size(), "冷却期满 → 再提醒一次");
    }

    @Test
    void learnPluginDisabled_skipped() {
        withUsers("bob");
        // bob 未启用 learn 插件
        when(learnCardRepository.list(anyString(), anyString())).thenReturn(List.of(
                reviewCard(LearnCard.TYPE_AI, "卡", daysAgo(10))));

        service.reviewReminder();

        assertTrue(channel.pushed.isEmpty());
        verify(learnCardRepository, never()).list("bob", LearnCard.TYPE_AI);
    }

    @Test
    void switchOff_learnReview_notPushed() {
        withUsers("adai");
        withLearnPlugin("adai");
        when(learnCardRepository.list("adai", LearnCard.TYPE_AI)).thenReturn(List.of(
                reviewCard(LearnCard.TYPE_AI, "超期卡", daysAgo(10))));
        when(learnCardRepository.list("adai", LearnCard.TYPE_TRADING)).thenReturn(List.of());
        when(learnCardRepository.list("adai", LearnCard.TYPE_OTHER)).thenReturn(List.of());
        PushSettings off = PushSettings.defaults().with("learn-review", false);
        when(pushSettingsRepository.findByUser("adai")).thenReturn(off);

        service.reviewReminder();

        assertTrue(channel.pushed.isEmpty(), "learn-review 开关关闭不推");
    }

    @Test
    void reviewEnabled_defaultOn_andSetterPersists() {
        when(pushSettingsRepository.findByUser("adai")).thenReturn(PushSettings.defaults());
        assertTrue(service.reviewEnabled("adai"), "缺失默认开");

        service.setReviewEnabled("adai", false);
        verify(pushSettingsRepository).save("adai",
                PushSettings.defaults().with("learn-review", false));
    }

    @Test
    void multiUser_isolated_onlyStaleUserPushed() {
        withUsers("adai", "bob");
        withLearnPlugin("adai");
        withLearnPlugin("bob");
        when(learnCardRepository.list("adai", LearnCard.TYPE_AI)).thenReturn(List.of(
                reviewCard(LearnCard.TYPE_AI, "adai超期", daysAgo(10))));
        when(learnCardRepository.list("adai", LearnCard.TYPE_TRADING)).thenReturn(List.of());
        when(learnCardRepository.list("adai", LearnCard.TYPE_OTHER)).thenReturn(List.of());
        when(learnCardRepository.list("bob", LearnCard.TYPE_AI)).thenReturn(List.of(
                otherCard(LearnCard.TYPE_AI, "bob新卡", daysAgo(1), LearnCard.STATUS_NEW)));
        when(learnCardRepository.list("bob", LearnCard.TYPE_TRADING)).thenReturn(List.of());
        when(learnCardRepository.list("bob", LearnCard.TYPE_OTHER)).thenReturn(List.of());

        service.reviewReminder();

        assertEquals(1, channel.pushed.size());
        assertTrue(channel.pushed.get(0).content().contains("adai超期"));
    }

    @Test
    void staleReviewCards_sortedByReviewAt() {
        when(learnCardRepository.list("u", LearnCard.TYPE_AI)).thenReturn(List.of(
                reviewCard(LearnCard.TYPE_AI, "较晚", daysAgo(9))));
        when(learnCardRepository.list("u", LearnCard.TYPE_TRADING)).thenReturn(List.of(
                reviewCard(LearnCard.TYPE_TRADING, "较早", daysAgo(15))));
        when(learnCardRepository.list("u", LearnCard.TYPE_OTHER)).thenReturn(List.of());

        List<LearnCard> stale = service.staleReviewCards("u", LocalDate.now());
        assertEquals(2, stale.size());
        assertEquals("较早", stale.get(0).title(), "reviewAt 升序");
        assertEquals("较晚", stale.get(1).title());
    }

    @Test
    void reviewCardWithoutReviewAt_notPushed() {
        // 老数据 status=review 但无 review_at → 宁可不提醒，不按 created 误判（S-learn1）
        withUsers("adai");
        withLearnPlugin("adai");
        LearnCard legacy = new LearnCard(LearnCard.TYPE_AI, "老卡无reviewAt", null, null, null, null,
                daysAgo(30), LearnCard.STATUS_REVIEW, false, null, List.of(),
                "观点", List.of(), List.of(), "");
        when(learnCardRepository.list("adai", LearnCard.TYPE_AI)).thenReturn(List.of(legacy));
        when(learnCardRepository.list("adai", LearnCard.TYPE_TRADING)).thenReturn(List.of());
        when(learnCardRepository.list("adai", LearnCard.TYPE_OTHER)).thenReturn(List.of());

        service.reviewReminder();

        assertTrue(channel.pushed.isEmpty(), "缺 review_at 的 review 卡不误推");
    }
}
