package com.adaiadai.core.domain.trading;

import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

/**
 * PushSettings — 用户推送开关（RFC 20260817 交易推送体验）。
 * <p>
 * 按用户独立配置推送类型开/关，存储于 {@code data/{userId}/trading/push-settings.json}：
 * <pre>{"session":true,"buy-point":true,"stop-loss":true,"near-stop-loss":true,
 *   "loss":true,"gain":true,"market":true}</pre>
 * 关闭的类型不再生成/注入 Feed（定时任务与 Feed 读侧双侧门控）。
 *
 * @param enabled 类型 → 是否开启（缺失 = 默认开启）
 */
public record PushSettings(Map<String, Boolean> enabled) {

    /** 全部推送类型（RFC 20260817：session=时段节奏 / buy-point=买点 / 止损/接近止损/大跌/放飞/破成本=异动 / market=行情条）。 */
    public static final Set<String> ALL_TYPES = Set.of(
            "session", "buy-point", "stop-loss", "near-stop-loss", "loss", "gain", "break-cost", "market");

    public PushSettings {
        if (enabled == null) enabled = defaultEnabled();
    }

    public static PushSettings defaults() {
        return new PushSettings(defaultEnabled());
    }

    private static Map<String, Boolean> defaultEnabled() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (String t : ALL_TYPES) m.put(t, true);
        return m;
    }

    /** 某类型是否开启（未配置的类型默认开）。 */
    public boolean isEnabled(String type) {
        if (type == null) return true;
        Boolean v = enabled.get(type);
        return v == null || v;
    }

    /** 设置某类型开关（返回新实例，不可变）。 */
    public PushSettings with(String type, boolean on) {
        Map<String, Boolean> m = new LinkedHashMap<>(enabled);
        m.put(type, on);
        return new PushSettings(m);
    }

    /** 关闭的类型列表（用于调试/展示）。 */
    public Set<String> disabledTypes() {
        return enabled.entrySet().stream()
                .filter(e -> !e.getValue())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }
}
