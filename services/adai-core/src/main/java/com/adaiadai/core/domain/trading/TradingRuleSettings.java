package com.adaiadai.core.domain.trading;

import java.math.BigDecimal;

/**
 * TradingRuleSettings — 交易规则参数化配置（第三阶段：规则层按用户隔离）。
 * <p>
 * 从 {@code DefaultTradingRuleEngine} / {@code TradingLotService} 等硬编码参数抽出的
 * 「用户自己的交易参数」——止损幅度、仓位上限、行为标注阈值等。按用户存储于
 * {@code data/{userId}/trading/rules.yaml}（File First，可导入导出）。
 * <p>
 * 默认值 = 原硬编码值（adai 规则体系经用户确认的默认），无规则文件/损坏 → 默认值兜底
 * （P1-5 2026-08-30 审查定稿：降级语义 = **默认值兜底**——无规则用户行为与 adai 现状一致，
 * 非「纯客观降级」；D6 决策「无规则用户给通用建议」已在建议引擎层实现）。
 * <p>
 * 设计（RFC 20260825 批次 + 第三阶段架构蓝图 trading-plugin-architecture.md §六）：
 * <ul>
 *   <li>参数化阈值层（本类）：单变量硬约束，表单可编辑，启动即校验（fail-closed）</li>
 *   <li>条件规则层（rules.yaml rules/signals/behaviors）：进阶层，后置</li>
 * </ul>
 *
 * @param positionLimitPercent  单票仓位上限 %（默认 25，R81：100万以下分4-5仓）
 * @param defaultStopLossRatio  默认止损比例（买入价 × 该值 = 止损位；默认 0.93 = −7%）
 * @param givebackPeakPct       浮盈回吐：峰值浮盈阈值 %（默认 20）
 * @param givebackRatioPct      浮盈回吐：从峰值回吐比例 %（默认 50）
 * @param shortOverdueDays      短线超期：持有超过 N 个交易日（默认 5）
 * @param soldStopLossPct       清仓复盘：止损阈值 %（亏损超过该值 = 扛单违反止损，默认 5）
 * @param soldShortHoldDays     清仓复盘：短持仓天数（短持仓却亏 = 该涨不涨，默认 5）
 * @param buyPullbackPct        买点信号：回调幅度（B1 低吸回调 ≥ 该比例，默认 0.5）
 * @param buyShrinkRatio        买点信号：缩量阈值（3日均量 &lt; 5日均量 × 该值，默认 0.7）
 * @param buyKdjLow             买点信号：KDJ.J 低位阈值（默认 13）
 * @param buyVolumeSurge        买点信号：放量倍数（B2 放量突破，默认 1.5）
 * @param buyPriorHighDays      买点信号：前高窗口天数（默认 20）
 * @param scoreBuyWeight        清仓打分：买点维度权重（默认 0.5）
 * @param scoreExecWeight       清仓打分：执行维度权重（默认 0.5）
 * @param constraintRuleMin     建议引擎：硬约束规则号下限（默认 66）
 * @param constraintRuleMax     建议引擎：硬约束规则号上限（默认 95）
 */
public record TradingRuleSettings(
        BigDecimal positionLimitPercent,
        BigDecimal defaultStopLossRatio,
        BigDecimal givebackPeakPct,
        BigDecimal givebackRatioPct,
        int shortOverdueDays,
        double soldStopLossPct,
        int soldShortHoldDays,
        double buyPullbackPct,
        double buyShrinkRatio,
        double buyKdjLow,
        double buyVolumeSurge,
        int buyPriorHighDays,
        double scoreBuyWeight,
        double scoreExecWeight,
        int constraintRuleMin,
        int constraintRuleMax) {

    /** 默认配置 = 原硬编码参数（adai 规则体系经确认的默认值，2026-08-17 P2-6 / RFC 20260825）。 */
    public static TradingRuleSettings defaults() {
        return new TradingRuleSettings(
                new BigDecimal("25"),
                new BigDecimal("0.93"),
                new BigDecimal("20"),
                new BigDecimal("50"),
                5,
                5.0,
                5,
                0.5,
                0.7,
                13,
                1.5,
                20,
                0.5,
                0.5,
                66,
                95);
    }

    public TradingRuleSettings {
        // fail-closed 校验（启动/加载即拒绝非法值，回落默认）
        if (positionLimitPercent == null || positionLimitPercent.compareTo(BigDecimal.ZERO) <= 0
                || positionLimitPercent.compareTo(new BigDecimal("100")) > 0) {
            positionLimitPercent = new BigDecimal("25");
        }
        if (defaultStopLossRatio == null || defaultStopLossRatio.compareTo(BigDecimal.ZERO) <= 0
                || defaultStopLossRatio.compareTo(BigDecimal.ONE) > 0) {
            defaultStopLossRatio = new BigDecimal("0.93");
        }
        if (givebackPeakPct == null || givebackPeakPct.compareTo(BigDecimal.ZERO) <= 0) {
            givebackPeakPct = new BigDecimal("20");
        }
        if (givebackRatioPct == null || givebackRatioPct.compareTo(BigDecimal.ZERO) <= 0
                || givebackRatioPct.compareTo(new BigDecimal("100")) > 0) {
            givebackRatioPct = new BigDecimal("50");
        }
        if (shortOverdueDays <= 0) {
            shortOverdueDays = 5;
        }
        // P2-2（2026-08-30 审查）：NaN/Infinity 穿透所有 <=/> 比较（NaN <= 0 恒 false）——
        // double 参数先 finite 校验再回落默认
        if (!Double.isFinite(soldStopLossPct) || soldStopLossPct <= 0 || soldStopLossPct > 100) {
            soldStopLossPct = 5.0;
        }
        if (soldShortHoldDays <= 0) {
            soldShortHoldDays = 5;
        }
        if (!Double.isFinite(buyPullbackPct) || buyPullbackPct <= 0 || buyPullbackPct >= 1) {
            buyPullbackPct = 0.5;
        }
        // P2-6（2026-08-30 审查）：买点参数业务下限（防阈值调太松 → 15:10 天天推「到买点」轰炸）——
        // 回调至少 10%、缩量至少 0.3、KDJ 低位 ≤50、放量至少 1.1x；超下限回落默认
        if (buyPullbackPct < 0.1) buyPullbackPct = 0.5;
        if (!Double.isFinite(buyShrinkRatio) || buyShrinkRatio <= 0 || buyShrinkRatio >= 1) {
            buyShrinkRatio = 0.7;
        }
        if (buyShrinkRatio < 0.3) buyShrinkRatio = 0.7;
        if (!Double.isFinite(buyKdjLow) || buyKdjLow <= 0 || buyKdjLow > 100) {
            buyKdjLow = 13;
        }
        if (buyKdjLow > 50) buyKdjLow = 13;
        if (!Double.isFinite(buyVolumeSurge) || buyVolumeSurge <= 1) {
            buyVolumeSurge = 1.5;
        }
        if (buyVolumeSurge < 1.1) buyVolumeSurge = 1.5;
        if (buyPriorHighDays <= 0) {
            buyPriorHighDays = 20;
        }
        if (!Double.isFinite(scoreBuyWeight) || scoreBuyWeight < 0 || scoreBuyWeight > 1) {
            scoreBuyWeight = 0.5;
        }
        if (!Double.isFinite(scoreExecWeight) || scoreExecWeight < 0 || scoreExecWeight > 1) {
            scoreExecWeight = 0.5;
        }
        // P0-2（2026-08-30 审查）：硬约束区间**只能在 R66-R95 内收缩**，且恒满足 min ≤ max——
        // 原实现 min>max 时只重置 max 留 min（如 min=200 → (200,95) 恒空 → 建议引擎失去全部硬约束 fail-open）。
        // 任一越界/倒挂 → 一起回落默认（66,95），绝不产生空区间。
        final int DEFAULT_CONSTRAINT_MIN = 66;
        final int DEFAULT_CONSTRAINT_MAX = 95;
        boolean constraintInvalid = constraintRuleMin < DEFAULT_CONSTRAINT_MIN
                || constraintRuleMax > DEFAULT_CONSTRAINT_MAX
                || constraintRuleMin > constraintRuleMax;
        if (constraintInvalid) {
            constraintRuleMin = DEFAULT_CONSTRAINT_MIN;
            constraintRuleMax = DEFAULT_CONSTRAINT_MAX;
        }
    }

    /** 默认止损位 = 买入价 × defaultStopLossRatio（−7%）。 */
    public BigDecimal defaultStopLoss(BigDecimal buyPrice) {
        if (buyPrice == null) return null;
        return buyPrice.multiply(defaultStopLossRatio);
    }
}
