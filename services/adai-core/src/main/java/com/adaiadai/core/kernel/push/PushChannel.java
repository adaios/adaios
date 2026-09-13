package com.adaiadai.core.kernel.push;

import java.time.LocalTime;

/**
 * PushChannel — 推送渠道抽象（RFC 20260816-trading-session-push：渠道 = 插件）。
 * <p>
 * 默认实现 FeedPushChannel（App 内 Feed）；外部渠道按需实现（WeChatPushChannel 等）。
 * 推送方（MarketAlertService / TradingSessionPushService）注入 {@code List<PushChannel>}，
 * 遍历所有 {@link #enabled()} 的渠道推送——渠道插件化，新增渠道不动主流程。
 */
public interface PushChannel {

    /** 渠道名（feed / wechat / ...）。 */
    String name();

    /** 渠道是否可用（如微信未配置 SendKey → false，静默跳过）。 */
    boolean enabled();

    /**
     * 推送一条消息。
     *
     * @param userId 目标用户
     * @param message 推送内容（title 短标题 / content 正文 / type 类型 / symbol/name 标的）
     */
    void push(String userId, PushMessage message);

    /**
     * 渠道自述状态（RFC 20260913 APNs 批）：给 {@code GET /api/v1/push/status} 用。
     * <p>
     * 默认只报「我是谁、通不通」，具体渠道可覆写补充诊断信息（如 APNs 的 keyId / 灰度白名单）。
     * 之所以加在端口上而不是让 Controller 判断实现类型：application 层应当只依赖 kernel 端口，
     * 对基础设施实现做 instanceof 会破坏分层依赖（C7）。
     */
    default java.util.Map<String, Object> status() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", name());
        m.put("enabled", enabled());
        return m;
    }

    /** 推送消息载体。 */
    record PushMessage(
            String title,
            String content,
            String type,
            String symbol,
            String name,
            LocalTime time
    ) {}
}
