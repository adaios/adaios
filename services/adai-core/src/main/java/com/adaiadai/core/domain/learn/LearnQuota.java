package com.adaiadai.core.domain.learn;

import java.time.YearMonth;

/**
 * LearnQuota — 云端转写月度配额视图（RFC 20260912 §3.8 费用可控条 4）。
 * <p>
 * 「费用可控」防的不是「贵」而是「不可预测」——按正常使用强度年成本在 0~几十元，
 * 但必须有**月度硬闸 + 记账**，接近上限提示而非默默花钱。
 *
 * @param month          账期（yyyy-MM），跨月自动归零（月初重置）
 * @param usedSeconds    本月已用转写时长（秒）
 * @param usedYuan       本月已产生费用（元）
 * @param quotaSeconds   本月配额上限（秒，默认对齐阿里云免费额度 10 小时）
 */
public record LearnQuota(String month, int usedSeconds, double usedYuan, int quotaSeconds) {

    /** 本月剩余额度（秒；可为 0，不为负）。 */
    public int remainSeconds() {
        return Math.max(0, quotaSeconds - usedSeconds);
    }

    /** 配额是否已用尽（再转写即超限 → 拒绝并人话提示）。 */
    public boolean exceeded() {
        return usedSeconds >= quotaSeconds;
    }

    /** 空账期视图（该月无记账记录 = 全新，usedSeconds/usedYuan 为 0）。 */
    public static LearnQuota empty(YearMonth month, int quotaSeconds) {
        return new LearnQuota(month.toString(), 0, 0.0d, quotaSeconds);
    }
}
