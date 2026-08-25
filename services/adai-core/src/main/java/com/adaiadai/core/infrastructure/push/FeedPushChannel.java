package com.adaiadai.core.infrastructure.push;

import com.adaiadai.core.domain.trading.MarketPushEvent;
import com.adaiadai.core.infrastructure.storage.MarketPushRepository;
import com.adaiadai.core.kernel.IdGenerator;
import com.adaiadai.core.kernel.push.PushChannel;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * FeedPushChannel — 默认推送渠道：落盘 → App 内 Feed（type=push）。
 * <p>
 * RFC 20260816：Feed 是渠道插件化的默认实现。写 {@code data/{userId}/trading/pushes/{date}.json}，
 * FeedAppService 按日读取展示——"你不问，App 也告诉你今天需要知道的"。
 * RFC 20260825 §7：落盘时按类型计算过期时间（expiresAt）——行情类当天收盘消失、汇总类次日 23:59 消失，
 * 读取侧（FeedAppService）过滤过期，用户无需手动删时效推送。
 */
@Component
public class FeedPushChannel implements PushChannel {

    private final MarketPushRepository pushRepository;

    public FeedPushChannel(MarketPushRepository pushRepository) {
        this.pushRepository = pushRepository;
    }

    @Override
    public String name() {
        return "feed";
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void push(String userId, PushMessage message) {
        String time = message.time() != null
                ? message.time().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                : java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        pushRepository.append(userId, LocalDate.now(), new MarketPushEvent(
                IdGenerator.monotonic("push_"),
                message.symbol(),
                message.name(),
                message.content(),
                message.type(),
                time,
                message.title(), // B9-1（2026-08-23，P1-推送1）：透传原标题——此前落库丢标题，
                // 前端按标题 switch 全落空（徽章配色 + 「确认并入账」按钮判定）
                expiresAt(message.type()))); // RFC 20260825 §7：定时消失
    }

    /**
     * 推送保留期（RFC 20260825 §7）：行情类 = **次日 09:30** 消失——用户主场景是
     * 「收盘后导入 + 晚上看 App」，15:30 即消失会误以为漏推（对抗审查 P1-5 修正）；
     * 次日开盘前自动清掉，新一天的行情提醒无缝接管。汇总类（每日操作总结/复盘）= 次日 23:59 消失。
     */
    private String expiresAt(String type) {
        LocalDate today = LocalDate.now();
        if (type != null && MarketPushRepository.SESSION_TYPES.contains(type)) {
            return today.plusDays(1).atTime(9, 30).toString();
        }
        return today.plusDays(1).atTime(23, 59).toString();
    }
}
