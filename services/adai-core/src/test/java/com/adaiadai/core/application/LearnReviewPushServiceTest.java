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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LearnReviewPushServiceTest — 学习复习提醒推送（RFC 20260829 V2 批 4）。
 * <p>
 * 验证：review 超 7 天未 done 的卡片被聚合推送；new/done/未超期不推；仅 learn 插件用户；
 * 推送开关 learn-review 关闭不推；损坏/无卡片不推；逐用户隔离。
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

    private LearnCard card(String type, String title, LocalDate created, String status) {
        return new LearnCard(type, title, null, null, null, null, created, status,
                false, null, List.of(), "观点", List.of(), List.of(), "");
    }

    /** 今天（服务用 LocalDate.now()；deadline = today-7，created ≤ today-7 才算超期）。 */
    private static LocalDate daysAgo(int n) {
        return LocalDate.now().minusDays(n);
    }

    @Test
    void reviewStale_over7days_pushedOnce() {
        withUsers("adai");
        withLearnPlugin("adai");
        when(learnCardRepository.list("adai", LearnCard.TYPE_AI)).thenReturn(List.of(
                card(LearnCard.TYPE_AI, "超期卡", daysAgo(10), LearnCard.STATUS_REVIEW)));
        when(learnCardRepository.list("adai", LearnCard.TYPE_TRADING)).thenReturn(List.of());
        when(learnCardRepository.list("adai", LearnCard.TYPE_OTHER)).thenReturn(List.of());

        service.reviewReminder();

        assertEquals(1, channel.pushed.size());
        PushChannel.PushMessage msg = channel.pushed.get(0);
        assertEquals("学习复习提醒", msg.title());
        assertEquals("learn-review", msg.type());
        assertTrue(msg.content().contains("超期卡"));
        assertTrue(msg.content().contains("1 张卡片"));
    }

    @Test
    void newOrDoneOrNotStale_notPushed() {
        withUsers("adai");
        withLearnPlugin("adai");
        when(learnCardRepository.list("adai", LearnCard.TYPE_AI)).thenReturn(List.of(
                card(LearnCard.TYPE_AI, "新卡", daysAgo(1), LearnCard.STATUS_NEW),
                card(LearnCard.TYPE_AI, "已完成", daysAgo(20), LearnCard.STATUS_DONE),
                card(LearnCard.TYPE_AI, "未满7天", daysAgo(5), LearnCard.STATUS_REVIEW)));
        when(learnCardRepository.list("adai", LearnCard.TYPE_TRADING)).thenReturn(List.of());
        when(learnCardRepository.list("adai", LearnCard.TYPE_OTHER)).thenReturn(List.of());

        service.reviewReminder();

        assertTrue(channel.pushed.isEmpty(), "无超期 review 卡不应推送");
    }

    @Test
    void reviewStale_onDay7Included() {
        withUsers("adai");
        withLearnPlugin("adai");
        // created = today-7（满 7 天）→ 应提醒
        when(learnCardRepository.list("adai", LearnCard.TYPE_AI)).thenReturn(List.of(
                card(LearnCard.TYPE_AI, "刚好满7天", daysAgo(7), LearnCard.STATUS_REVIEW)));
        when(learnCardRepository.list("adai", LearnCard.TYPE_TRADING)).thenReturn(List.of());
        when(learnCardRepository.list("adai", LearnCard.TYPE_OTHER)).thenReturn(List.of());

        service.reviewReminder();

        assertEquals(1, channel.pushed.size());
        assertTrue(channel.pushed.get(0).content().contains("刚好满7天"));
    }

    @Test
    void learnPluginDisabled_skipped() {
        withUsers("bob");
        // bob 未启用 learn 插件
        when(learnCardRepository.list(anyString(), anyString())).thenReturn(List.of(
                card(LearnCard.TYPE_AI, "卡", daysAgo(10), LearnCard.STATUS_REVIEW)));

        service.reviewReminder();

        assertTrue(channel.pushed.isEmpty());
        verify(learnCardRepository, never()).list("bob", LearnCard.TYPE_AI);
    }

    @Test
    void switchOff_learnReview_notPushed() {
        withUsers("adai");
        withLearnPlugin("adai");
        when(learnCardRepository.list("adai", LearnCard.TYPE_AI)).thenReturn(List.of(
                card(LearnCard.TYPE_AI, "超期卡", daysAgo(10), LearnCard.STATUS_REVIEW)));
        when(learnCardRepository.list("adai", LearnCard.TYPE_TRADING)).thenReturn(List.of());
        when(learnCardRepository.list("adai", LearnCard.TYPE_OTHER)).thenReturn(List.of());
        PushSettings off = PushSettings.defaults().with("learn-review", false);
        when(pushSettingsRepository.findByUser("adai")).thenReturn(off);

        service.reviewReminder();

        assertTrue(channel.pushed.isEmpty(), "learn-review 开关关闭不推");
    }

    @Test
    void multiUser_isolated_onlyStaleUserPushed() {
        withUsers("adai", "bob");
        withLearnPlugin("adai");
        withLearnPlugin("bob");
        // adai 有超期卡，bob 只有新卡
        when(learnCardRepository.list("adai", LearnCard.TYPE_AI)).thenReturn(List.of(
                card(LearnCard.TYPE_AI, "adai超期", daysAgo(10), LearnCard.STATUS_REVIEW)));
        when(learnCardRepository.list("adai", LearnCard.TYPE_TRADING)).thenReturn(List.of());
        when(learnCardRepository.list("adai", LearnCard.TYPE_OTHER)).thenReturn(List.of());
        when(learnCardRepository.list("bob", LearnCard.TYPE_AI)).thenReturn(List.of(
                card(LearnCard.TYPE_AI, "bob新卡", daysAgo(1), LearnCard.STATUS_NEW)));
        when(learnCardRepository.list("bob", LearnCard.TYPE_TRADING)).thenReturn(List.of());
        when(learnCardRepository.list("bob", LearnCard.TYPE_OTHER)).thenReturn(List.of());

        service.reviewReminder();

        assertEquals(1, channel.pushed.size());
        assertTrue(channel.pushed.get(0).content().contains("adai超期"));
    }

    @Test
    void staleReviewCards_sortedByCreated() {
        // 直接测内部聚合排序（跨 type 合并 + created 升序）
        when(learnCardRepository.list("u", LearnCard.TYPE_AI)).thenReturn(List.of(
                card(LearnCard.TYPE_AI, "较晚", daysAgo(9), LearnCard.STATUS_REVIEW)));
        when(learnCardRepository.list("u", LearnCard.TYPE_TRADING)).thenReturn(List.of(
                card(LearnCard.TYPE_TRADING, "较早", daysAgo(15), LearnCard.STATUS_REVIEW)));
        when(learnCardRepository.list("u", LearnCard.TYPE_OTHER)).thenReturn(List.of());

        List<LearnCard> stale = service.staleReviewCards("u", LocalDate.now().minusDays(7));
        assertEquals(2, stale.size());
        assertEquals("较早", stale.get(0).title(), "created 升序");
        assertEquals("较晚", stale.get(1).title());
    }
}
