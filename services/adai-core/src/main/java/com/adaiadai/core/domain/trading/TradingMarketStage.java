package com.adaiadai.core.domain.trading;

/**
 * TradingMarketStage — 活跃市值区间（用户手动判定，v3.41 2026-09-04）。
 * <p>
 * 用户亲手判定当前活跃市值处于哪个区间（指南针活跃市值口径——「一切的前提」，
 * 决定仓位松紧与操作手法），存储于 {@code data/{userId}/trading/market-stage.json}：
 * <pre>{"stage":"bear","updatedAt":"2026-09-04T09:00:00"}</pre>
 * 两档：bull=多头区间 / bear=空头区间（红涨绿亏：多头红、空头绿）。
 * 一旦用户设置，时段推送的【择时状态】以用户判定为准，不再被 current.md 的
 * OAMV 规则推断覆盖（2026-09-03 对话：current.md 靠 6/26 一条旧规则永久锁死空头）。
 *
 * @param stage     bull（多头区间）| bear（空头区间）
 * @param updatedAt 最近手动判定时间（ISO 本地时间 yyyy-MM-dd'T'HH:mm:ss）
 */
public record TradingMarketStage(String stage, String updatedAt) {

    public static final String BULL = "bull";
    public static final String BEAR = "bear";

    public TradingMarketStage {
        if (!isValid(stage)) {
            throw new IllegalArgumentException("stage 必须是 bull（多头）或 bear（空头）：" + stage);
        }
        if (updatedAt == null || updatedAt.isBlank()) {
            throw new IllegalArgumentException("updatedAt 不能为空");
        }
    }

    /** 是否为合法区间值。 */
    public static boolean isValid(String s) {
        return BULL.equals(s) || BEAR.equals(s);
    }

    /** 区间中文标签（推送/前端展示用）。 */
    public static String label(String stage) {
        return BULL.equals(stage) ? "多头区间" : "空头区间";
    }
}
