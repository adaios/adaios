package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.PushSettings;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.PushSettingsRepository;
import com.adaiadai.core.infrastructure.storage.TodoFileRepository;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.push.PushChannel;
import com.adaiadai.core.kernel.todo.Todo;
import com.adaiadai.core.kernel.todo.TodoStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TodoReminderService — 待办到期提醒（RFC 20260917 §4.1）。
 * <p>
 * 验证：到期当天早上 / 傍晚各推一次；只推**今天到期且未完成**的；无到期日/已完成/别的日期不推；
 * 开关 todo-due 可关；锁屏版只报件数、不含待办标题（P0-1 脱敏）；不按插件门控（人人都有）。
 */
class TodoReminderServiceTest {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final PushSettingsRepository pushSettingsRepository = mock(PushSettingsRepository.class);
    private final List<PushChannel> channels = new ArrayList<>();
    private final RecordingChannel channel = new RecordingChannel();
    private TodoFileRepository todoRepository;
    private TodoReminderService service;

    static class RecordingChannel implements PushChannel {
        final List<PushMessage> pushed = new ArrayList<>();
        @Override public String name() { return "test"; }
        @Override public boolean enabled() { return true; }
        @Override public void push(String userId, PushMessage message) { pushed.add(message); }
    }

    @BeforeEach
    void setUp() {
        channels.add(channel);
        todoRepository = new TodoFileRepository(new InMemoryFileStorage());
        service = new TodoReminderService(accountRepository, todoRepository,
                pushSettingsRepository, channels);
        when(pushSettingsRepository.findByUser(anyString())).thenReturn(PushSettings.defaults());
    }

    private void withUsers(String... userIds) {
        List<Account> accounts = new ArrayList<>();
        for (String u : userIds) {
            accounts.add(new Account(u, Account.ROLE_USER, true, LocalDate.of(2026, 8, 2), List.of()));
        }
        when(accountRepository.findAll()).thenReturn(accounts);
    }

    private Todo add(String userId, String title, TodoStatus status, LocalDate due) {
        Todo todo = new Todo(Todo.generateId(), title, status, due, null, LocalDate.now(), LocalDate.now());
        todoRepository.save(userId, todo);
        return todo;
    }

    @Test
    void morning_pushesOnlyTodosDueToday() {
        withUsers("adai");
        LocalDate today = LocalDate.now();
        add("adai", "今天到期的事", TodoStatus.OPEN, today);
        add("adai", "明天的事", TodoStatus.OPEN, today.plusDays(1));
        add("adai", "没有期限的事", TodoStatus.OPEN, null);

        service.morningReminder();

        assertEquals(1, channel.pushed.size(), "只推今天到期的那一条");
        PushChannel.PushMessage msg = channel.pushed.get(0);
        assertEquals("todo-due", msg.type());
        assertEquals("待办到期提醒", msg.title());
        assertTrue(msg.content().contains("今天到期的事"), "正文逐条列标题");
        assertFalse(msg.notificationContent().contains("今天到期的事"), "锁屏不含待办标题（P0-1）");
        assertTrue(msg.notificationContent().contains("1"), "锁屏只报件数");
        assertEquals("todo:today", msg.deepLink(), "点通知回待办清单（RFC 20260917）");
    }

    @Test
    void evening_pushesOnlyStillOpen() {
        withUsers("adai");
        LocalDate today = LocalDate.now();
        add("adai", "还没做的", TodoStatus.OPEN, today);
        add("adai", "已经做完的", TodoStatus.DONE, today);

        service.eveningReminder();

        assertEquals(1, channel.pushed.size());
        assertTrue(channel.pushed.get(0).content().contains("还没做的"));
        assertFalse(channel.pushed.get(0).content().contains("已经做完的"));
    }

    @Test
    void noDueToday_doesNotPush() {
        withUsers("adai");
        add("adai", "明天的事", TodoStatus.OPEN, LocalDate.now().plusDays(1));
        add("adai", "没有期限的事", TodoStatus.OPEN, null);

        service.morningReminder();
        service.eveningReminder();

        assertTrue(channel.pushed.isEmpty(), "没有今天到期的待办 → 不打扰");
    }

    @Test
    void disabledSetting_doesNotPush() {
        withUsers("adai");
        add("adai", "今天到期的事", TodoStatus.OPEN, LocalDate.now());
        when(pushSettingsRepository.findByUser("adai"))
                .thenReturn(PushSettings.defaults().with("todo-due", false));

        service.morningReminder();

        assertTrue(channel.pushed.isEmpty(), "用户关掉 todo-due → 不推");
    }

    @Test
    void disabledAccount_isSkipped() {
        List<Account> accounts = List.of(
                new Account("adai", Account.ROLE_USER, false, LocalDate.of(2026, 8, 2), List.of()));
        when(accountRepository.findAll()).thenReturn(accounts);
        add("adai", "今天到期的事", TodoStatus.OPEN, LocalDate.now());

        service.morningReminder();

        assertTrue(channel.pushed.isEmpty(), "停用账号不推");
    }

    @Test
    void dueToday_filtersByOpenAndDate() {
        LocalDate today = LocalDate.now();
        add("adai", "今天未完成", TodoStatus.OPEN, today);
        add("adai", "今天已完成", TodoStatus.DONE, today);
        add("adai", "昨天未完成（过期不在此口径）", TodoStatus.OPEN, today.minusDays(1));
        add("other", "别人的待办", TodoStatus.OPEN, today);

        List<Todo> due = service.dueToday("adai", today);

        assertEquals(1, due.size());
        assertEquals("今天未完成", due.get(0).title());
    }

    @Test
    void noAccount_isNoOp() {
        when(accountRepository.findAll()).thenReturn(List.of());
        service.morningReminder();
        assertTrue(channel.pushed.isEmpty());
    }
}
