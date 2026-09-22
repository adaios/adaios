package com.adaiadai.core.domain.trading;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * TradingSyncState — 「账同步到哪一天 / 复盘发没发」的记录（RFC `20260922-trading-decision-copilot` B 批 B3）。
 *
 * <p><b>为什么需要它</b>：用户 2026-09-22 拍板 D4——收盘复盘**不是到点就发**，而是
 * 「数据同步完成才出」。这就要求系统能回答两个问题：
 * <ol>
 *   <li>今天用户到底导没导账？（导了才出复盘；没导就如实说「我还没看到」——不硬编一份基于旧账的复盘）</li>
 *   <li>今天的复盘已经发过了吗？（避免「15:30 兜底发一条 + 用户随后补导又发一条」重复打扰）</li>
 * </ol>
 *
 * <p>两者都是**时间点事实**，不是账本内容——因此单独落一个极小的状态文件，
 * 不去污染 anchor（锚定日回答的是「券商快照覆盖到哪天」，与「用户今天做过同步动作」不是一回事：
 * 用户完全可能今天导一份**上周**的快照，那天账变了、锚定日却是上周）。
 *
 * @param lastSyncDate     最近一次成功导入（持仓/资金/历史成交任一）的**发生日**
 * @param lastSyncAt       同一动作的发生时刻（可选，诊断用）
 * @param dailyReviewDate  最近一次已推送「收盘复盘」的日期
 */
public record TradingSyncState(LocalDate lastSyncDate, LocalDateTime lastSyncAt, LocalDate dailyReviewDate) {

    /** 空状态（从未同步、从未推过复盘）。 */
    public static TradingSyncState empty() {
        return new TradingSyncState(null, null, null);
    }

    /** 今天是否发生过同步（复盘「有账可依」的判据）。 */
    public boolean syncedOn(LocalDate date) {
        return date != null && date.equals(lastSyncDate);
    }

    /** 这一天的复盘是否已经推过（每天至多一条，防兜底与同步触发双发）。 */
    public boolean reviewPushedOn(LocalDate date) {
        return date != null && date.equals(dailyReviewDate);
    }

    /** 记一次同步动作（保留既有的复盘标记）。 */
    public TradingSyncState withSync(LocalDate date, LocalDateTime at) {
        return new TradingSyncState(date, at, dailyReviewDate);
    }

    /** 记一次复盘推送（保留既有的同步标记）。 */
    public TradingSyncState withReview(LocalDate date) {
        return new TradingSyncState(lastSyncDate, lastSyncAt, date);
    }
}
