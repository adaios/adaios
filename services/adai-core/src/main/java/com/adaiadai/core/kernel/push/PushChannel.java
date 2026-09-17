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
     * {@code lockScreenContent} / {@code lockScreenTitle} 是**锁屏精简版**（D1，2026-09-13 外部视角
     * 审查拍板 A；2026-09-14 增量深审 P0-1 补全）：外部通知渠道（APNs / Bark / 微信）渲染它们，
     * 站内 Feed 仍渲染完整 {@code title/content}。
     * 起因：收盘小结的完整正文会逐只列出持仓名称与现价，而它是 alert 推送——**锁屏直接可见**，
     * 手机放在桌上就等于把持仓摊开给旁边的人。
     * <p>
     * <b>凡 title 或 content 含标的/金额/成本/止损价的推送，都必须给锁屏版</b>——包括把股票名写进
     * title 的行情提醒（P0-1：此前只改了收盘小结一条路径，行情/止损/早中尾盘照样把持仓明细怼上锁屏）。
     * 锁屏只留「有几件事」，细节留给打开 App 的人。
     * <p>
     * null / 空白 = **不回落完整正文**（2026-09-14 晚间批 P2-推送1：回退方向改为 fail-closed）——
     * 外部渠道改发中性兜底文案 {@link PushMessage#NEUTRAL_LOCK_SCREEN}。
     * 理由：回落完整正文是 fail-open，**新**调用点一旦漏传就静默泄露（P0-1 的机制根因）；
     * 「少说一句」比「把持仓摊在锁屏上」便宜得多。中性推送（内容本身不含标的/金额）
     * 请显式把自己的文案作为锁屏版传入，把「这条不敏感」变成代码里的显式声明。
     */
    record PushMessage(
            String title,
            String content,
            String type,
            String symbol,
            String name,
            LocalTime time,
            String lockScreenContent,
            String lockScreenTitle
    ) {
        /** 漏传锁屏正文时的中性兜底：不含任何标的/金额/上下文，只说明「有事，打开看」。 */
        public static final String NEUTRAL_LOCK_SCREEN = "阿呆有新的提示，打开看看。";

        /** 兼容构造（不区分锁屏版）：外部通知渠道渲染中性兜底文案（不再回落完整内容）。 */
        public PushMessage(String title, String content, String type,
                           String symbol, String name, LocalTime time) {
            this(title, content, type, symbol, name, time, null, null);
        }

        /** 兼容构造（只有锁屏正文、标题不脱敏）。 */
        public PushMessage(String title, String content, String type,
                           String symbol, String name, LocalTime time, String lockScreenContent) {
            this(title, content, type, symbol, name, time, lockScreenContent, null);
        }

        /**
         * 外部通知渠道应当渲染的正文：**有锁屏版用锁屏版，没有就用中性兜底**（fail-closed）。
         * 见上方类型注释——这里是 P0-1 的机制修复点，不要改回「回落 content」。
         */
        public String notificationContent() {
            return (lockScreenContent == null || lockScreenContent.isBlank())
                    ? NEUTRAL_LOCK_SCREEN : lockScreenContent;
        }

        /**
         * 外部通知渠道应当渲染的标题：有锁屏标题用锁屏标题，否则回落完整标题。
         * <p>
         * 标题**刻意保留回落**：推送标题在设计上是短标签（「早盘计划」「收盘小结」「买点提醒」），
         * 带标的名的只有行情/批次止损两条路径，它们已显式传锁屏标题；
         * 且有专门的用例把「行情类标题不得含股票名」钉住。若将来新增带标的的标题，必须同时传锁屏标题。
         */
        public String notificationTitle() {
            return (lockScreenTitle == null || lockScreenTitle.isBlank())
                    ? title : lockScreenTitle;
        }

        /**
         * 点开通知后要定位到哪儿（REVIEW P2-APNs1，2026-09-17）。
         * <p>
         * 由**已有字段推导**，而不是新增字段——这样所有推送构造点零改动，也不会出现
         * 「某个调用点忘了传」的漏网（漏传的表现正好是修前的样子：点通知回到 Feed 顶部，得自己找）。
         * <ul>
         *   <li>带标的（{@code symbol} 非空）→ {@code trading:<symbol>}：客户端据此找那条持仓/行情卡</li>
         *   <li>学习复习提醒 → {@code learn:review}</li>
         *   <li>其余（早中尾盘 / 收盘小结 / 交易日志归集确认）→ {@code trading:today}</li>
         * </ul>
         * 返回 {@code null} = 只回 Feed 不做定位。
         */
        public String deepLink() {
            if (symbol != null && !symbol.isBlank()) return "trading:" + symbol;
            if (type == null || type.isBlank()) return null;
            return switch (type) {
                case "learn-review" -> "learn:review";
                case "todo-due" -> "todo:today";
                default -> "trading:today";
            };
        }
    }
}
