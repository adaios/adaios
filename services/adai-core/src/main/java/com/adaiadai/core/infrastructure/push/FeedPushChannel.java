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
                message.title())); // B9-1（2026-08-23，P1-推送1）：透传原标题——此前落库丢标题，
        // 前端按标题 switch 全落空（徽章配色 + 「确认并入账」按钮判定）
    }
}
