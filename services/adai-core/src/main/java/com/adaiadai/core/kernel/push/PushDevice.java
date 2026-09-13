package com.adaiadai.core.kernel.push;

import java.util.Locale;

/**
 * PushDevice — 推送目标设备（RFC 20260913 APNs 批）。
 * <p>
 * 与 {@link PushChannel} 的关系：渠道负责「怎么送」，PushDevice 负责「送给谁」。
 * 付费开发者账号落地后，阿呆 app 可以自己持 {@code aps-environment} 能力直连 APNs，
 * 不再借第三方 App（Bark）转达——但前提是后端知道这台设备的 token 和它属于哪个环境。
 * <p>
 * <b>environment 为什么必须存</b>：APNs 有两套互不相通的网关（sandbox / production），
 * token 只在自己那套网关有效，送错网关的结果是 {@code BadDeviceToken} 静默丢弃。
 * 侧载（development profile 签名）拿到的永远是 sandbox token；将来走 TestFlight/App Store
 * 才是 production token。所以环境跟着 token 走，不跟后端部署环境走。
 *
 * @param token         APNs device token（十六进制，客户端上报）
 * @param platform      平台（当前仅 ios）
 * @param environment   sandbox | production
 * @param bundleId      App bundle id（APNs 的 apns-topic）
 * @param label         设备备注（可空，如「iPhone」）
 * @param registeredAt  注册时间（ISO-8601）
 * @param lastSeenAt    最近一次上报时间（ISO-8601，同 token 重复上报只刷新此字段）
 */
public record PushDevice(
        String token,
        String platform,
        String environment,
        String bundleId,
        String label,
        String registeredAt,
        String lastSeenAt
) {

    public static final String PLATFORM_IOS = "ios";

    /** APNs 沙箱网关（development 签名 → 沙箱 token）。 */
    public static final String ENV_SANDBOX = "sandbox";

    /** APNs 生产网关（Ad Hoc / TestFlight / App Store 签名 → 生产 token）。 */
    public static final String ENV_PRODUCTION = "production";

    /** token 最短长度（旧设备 32 字节 = 64 字符；留余量容忍未来格式变化）。 */
    private static final int TOKEN_MIN = 32;

    /** token 最长长度（防有人把任意长字符串塞进存储）。 */
    private static final int TOKEN_MAX = 200;

    /**
     * token 合法性：十六进制字符串（大小写均可）。
     * <p>
     * 严格校验的理由：token 会被拼进 APNs 的 URL 路径（{@code /3/device/{token}}），
     * 放开等于把用户输入拼进出站请求路径——本项目已有「出站请求必须收敛」的先例
     * （learn 抓取的 SSRF 修复）。非十六进制一律拒收。
     */
    public static boolean isValidToken(String token) {
        if (token == null) return false;
        String t = token.trim();
        if (t.length() < TOKEN_MIN || t.length() > TOKEN_MAX) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    /**
     * 环境归一化：客户端可能报 {@code development} / {@code dev} / {@code sandbox} / {@code production} / {@code prod}。
     * <p>
     * <b>未知一律回落 sandbox</b>（fail-safe 方向的选择依据）：当前装机路径是 development profile 侧载，
     * 真实环境就是 sandbox；若客户端读不出 {@code aps-environment}，回落 sandbox 才是「大概率对」的那一边。
     * 送错时 APNs 返回 BadDeviceToken 并被推送日志显式记下（fail-visible），不会静默消失。
     */
    public static String normalizeEnvironment(String raw) {
        if (raw == null) return ENV_SANDBOX;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "production", "prod" -> ENV_PRODUCTION;
            default -> ENV_SANDBOX;
        };
    }
}
