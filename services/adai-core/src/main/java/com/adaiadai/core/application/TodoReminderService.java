package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.storage.PushSettingsRepository;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.push.PushChannel;
import com.adaiadai.core.kernel.todo.Todo;
import com.adaiadai.core.kernel.todo.TodoRepository;
import com.adaiadai.core.kernel.todo.TodoStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

/**
 * TodoReminderService — 待办到期提醒（RFC 20260917 §4.1）。
 * <p>
 * 待办只有日期没有时刻，所以「到期日」当天提醒两次（用户拍板：早上一条 + 到点再一条）：
 * <ul>
 *   <li><b>08:00</b> 汇总今天到期的未完成待办（「今天到期」）；</li>
 *   <li><b>18:00</b> 再看一眼：当天到期仍未完成的，再提醒一次（完成的不再打扰）。</li>
 * </ul>
 * 推送类型 {@code todo-due}，走 {@link PushSettingsRepository} 开关（缺失默认开、用户可关），
 * 与 {@code learn-review} 同机制；渠道走 {@link PushChannel} 扇出（Feed / APNs / Bark…），
 * 新增渠道不动本服务。待办是 Kernel builtin，**不按插件门控**（人人都有）。
 * <p>
 * 锁屏脱敏（P0-1，2026-09-14）：待办标题是私人事务——正文逐条列标题（站内 Feed 可见），
 * 锁屏只报「有几件」，标题不进锁屏（显式传 lockScreenContent / lockScreenTitle，不回落到完整正文）。
 * 逐用户 catch，推送失败不阻断其他用户。
 */
@Service
public class TodoReminderService {

    private static final Logger log = LoggerFactory.getLogger(TodoReminderService.class);

    /** 早上汇总：当天到期。 */
    static final String CRON_MORNING = "0 0 8 * * *";
    /** 傍晚再提一次：当天到期仍未完成。 */
    static final String CRON_EVENING = "0 0 18 * * *";

    /** 推送类型（进 PushSettings.ALL_TYPES，默认开、可关）。 */
    static final String TYPE = "todo-due";
    /** 推送标题（Feed 卡 + 外部通知共用；不含待办内容）。 */
    static final String TITLE = "待办到期提醒";
    /** 锁屏标题（显式声明不敏感，避免回落完整标题）。 */
    static final String LOCK_SCREEN_TITLE = "阿呆提醒";

    private final AccountRepository accountRepository;
    private final TodoRepository todoRepository;
    private final PushSettingsRepository pushSettingsRepository;
    private final List<PushChannel> pushChannels;

    public TodoReminderService(AccountRepository accountRepository,
                               TodoRepository todoRepository,
                               PushSettingsRepository pushSettingsRepository,
                               List<PushChannel> pushChannels) {
        this.accountRepository = accountRepository;
        this.todoRepository = todoRepository;
        this.pushSettingsRepository = pushSettingsRepository;
        this.pushChannels = pushChannels;
    }

    /** 早上 08:00：今天到期的待办。 */
    @Scheduled(cron = "${adai.todo.due-morning-cron:" + CRON_MORNING + "}")
    public void morningReminder() {
        remind(true);
    }

    /** 傍晚 18:00：今天到期、还没完成的待办再提一次。 */
    @Scheduled(cron = "${adai.todo.due-evening-cron:" + CRON_EVENING + "}")
    public void eveningReminder() {
        remind(false);
    }

    /** 遍历启用账号逐个提醒（单用户失败不影响其他用户）。 */
    private void remind(boolean morning) {
        LocalDate today = LocalDate.now();
        for (Account account : accountRepository.findAll()) {
            String userId = account.userId();
            if (userId == null || !account.enabled()) continue;
            try {
                remindUser(userId, today, morning);
            } catch (Exception e) {
                log.warn("待办到期提醒失败 | userId={} | {}", userId, e.getMessage());
            }
        }
    }

    /** 单用户：有今天到期的未完成待办且开关打开 → 推一条。 */
    private void remindUser(String userId, LocalDate today, boolean morning) {
        List<Todo> due = dueToday(userId, today);
        if (due.isEmpty()) return;
        if (!pushSettingsRepository.findByUser(userId).isEnabled(TYPE)) {
            log.info("待办到期提醒跳过（用户关闭 {}）| userId={}", TYPE, userId);
            return;
        }
        String content = buildContent(due, morning);
        PushChannel.PushMessage message = new PushChannel.PushMessage(
                TITLE, content, TYPE, null, null, LocalTime.now(),
                "今天有 " + due.size() + " 件待办到期，打开看看。", LOCK_SCREEN_TITLE);
        for (PushChannel channel : pushChannels) {
            if (!channel.enabled()) continue;
            try {
                channel.push(userId, message);
            } catch (Exception e) {
                log.warn("待办到期提醒渠道失败 | userId={} | channel={} | {}", userId, channel.name(), e.getMessage());
            }
        }
        log.info("待办到期提醒已推送 | userId={} | {} | 到期 {} 件", userId, morning ? "早上" : "傍晚", due.size());
    }

    /** 今天到期的未完成待办（无到期日、已完成、别的日期都不在内）。 */
    List<Todo> dueToday(String userId, LocalDate today) {
        return todoRepository.findAll(TodoStatus.OPEN, userId).stream()
                .filter(t -> today.equals(t.due()))
                .sorted(Comparator.comparing(Todo::id))
                .toList();
    }

    /** 正文：逐条列标题（站内 Feed 可见；锁屏版在调用处单独给，不含标题）。 */
    private String buildContent(List<Todo> due, boolean morning) {
        StringBuilder sb = new StringBuilder();
        sb.append(morning
                ? "今天有 " + due.size() + " 件待办到期：\n"
                : "今天到期的这几件还没完成：\n");
        for (Todo t : due) {
            sb.append("· ").append(t.title()).append("\n");
        }
        sb.append(morning ? "做完跟我说一声就行。" : "来不及也没关系，明天我还在。");
        return sb.toString();
    }
}
