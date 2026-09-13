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

    /**
     * 推送消息载体。
     * <p>
     * {@code lockScreenContent} 是**锁屏精简正文**（D1，2026-09-13 外部视角审查拍板 A）：
     * 外部通知渠道（APNs / Bark / 微信）渲染它，站内 Feed 仍渲染完整 {@code content}。
     * 起因：收盘小结的完整正文会逐只列出持仓名称与现价，而它是 alert 推送——**锁屏直接可见**，
     * 手机放在桌上就等于把持仓摊开给旁边的人。凡 content 含标的/金额细节的推送都应给精简版：
     * 锁屏只留「有几件事」，细节留给打开 App 的人。
     * <p>
     * null / 空白 = 沿用 {@code content}（未脱敏）——老构造点行为不变，可渐进补齐。
     */
    record PushMessage(
            String title,
            String content,
            String type,
            String symbol,
            String name,
            LocalTime time,
            String lockScreenContent
    ) {
        /** 兼容构造（不区分锁屏正文）：外部通知渠道也渲染完整 content。 */
        public PushMessage(String title, String content, String type,
                           String symbol, String name, LocalTime time) {
            this(title, content, type, symbol, name, time, null);
        }

        /** 外部通知渠道应当渲染的正文：有精简版用精简版，否则回落完整版。 */
        public String notificationContent() {
            return (lockScreenContent == null || lockScreenContent.isBlank())
                    ? content : lockScreenContent;
        }
    }
}
