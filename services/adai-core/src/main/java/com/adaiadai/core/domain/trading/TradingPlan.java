package com.adaiadai.core.domain.trading;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * TradingPlan — 次日操作计划（RFC 20261003-trading-plan-and-review-loop §二，2026-10-03）。
 *
 * <p><b>定位（用户原话）</b>：「我可以整理第二天操作规则，买和卖，到时候结合起来提醒我；
 * 必须像开公司一样认真对待每一笔投资，像打仗规划好子弹，必须抬高自身态度」。
 * 系统在这里**只做三件事**：记用户的话 · 到点提醒 · 收盘对账——
 * **绝不生成计划、不下判断、不预测**（否则就成了投资建议）。
 *
 * <p>存储：{@code data/{userId}/trading/plans/YYYY-MM-DD.json}（与 transfers.json / sold.json 同惯例）。
 * 文件日期 = 该计划**管的那一天**（前晚写、次日执行）。
 *
 * <p><b>P2-交易72（2026-10-05）：当天事后的「今日状态」也落这一份记录。</b>
 * 事前写的是「明天不动」，事后回填的是「今天没动」——**同一份记录、同一语义、时间方向相反**。
 * 为什么不另建存储：这份记录本就是「这一天我怎么打算 / 我动没动」的载体，且
 * {@code TradingPlanService.review} 已把它与当天真实成交对账（含计划外成交）；
 * 另立一处才是孤岛。为什么**不塞进流水**（{@code TradeRecord}）：那是**成交**，
 * 「没动」写成 volume=0 的假流水会污染持仓重建 / 现金 / 盈亏 / 复盘的所有下游。
 *
 * @param date      计划管的那一天
 * @param items     计划条目（标的三要素：标的 · 条件 · 动作）
 * @param note      用户的自我约束（如「只做计划内的票，不追高」）
 * @param dayStatus 当天事后的状态回填（P2-交易72）：{@code ""} 没填 /
 *                  {@link #DAY_STATUS_NO_TRADE} 今天没动 / {@link #DAY_STATUS_WANTED_NOT_ACTED} 想动但没动。
 *                  **与 items 互不覆盖**：回填状态不碰用户已写的计划条目。
 * @param createdAt 落盘时间
 */
public record TradingPlan(LocalDate date, List<PlanItem> items, String note, String dayStatus,
                          LocalDateTime createdAt) {

    /** 没填（缺省）。 */
    public static final String DAY_STATUS_NONE = "";
    /** 今天没动（P2-交易72 用户原话三选一之一）。 */
    public static final String DAY_STATUS_NO_TRADE = "NO_TRADE";
    /** 想动但没动（同上；连续出现可作「手痒」的对照，R119「零仓位也是交易」）。 */
    public static final String DAY_STATUS_WANTED_NOT_ACTED = "WANTED_NOT_ACTED";

    /** 是否为可落盘的状态值（**未填不算**——清空状态走 items/note 之外的显式语义，不在这里放行）。 */
    public static boolean isKnownDayStatus(String s) {
        return DAY_STATUS_NO_TRADE.equals(s) || DAY_STATUS_WANTED_NOT_ACTED.equals(s);
    }

    public TradingPlan {
        if (items == null) items = List.of();
        if (note == null) note = "";
        if (dayStatus == null) dayStatus = DAY_STATUS_NONE;
    }

    /** 兼容构造（P2-交易72 之前写入/构造的调用点）：dayStatus 缺省为空，旧调用零改动。 */
    public TradingPlan(LocalDate date, List<PlanItem> items, String note, LocalDateTime createdAt) {
        this(date, items, note, DAY_STATUS_NONE, createdAt);
    }

    /**
     * 一条计划。{@code action} 取值：{@code BUY} 买入 / {@code SELL} 卖出 / {@code HOLD} 明确不动。
     *
     * @param condition 触发条件的人话原文（可为空 = 无条件，开盘即做），如「跌破 45.50」
     * @param condOp    机器可判定的条件算子：{@code LT}（触及下方价）/ {@code GE}（触及上方价）/ 空 = 无条件。
     *                  为什么两存：{@code condition} 是**用户的原话**（提醒时必须引用、不得改写），
     *                  {@code condOp/condPrice} 只服务对账判定，两者分开避免「为了能算而改用户的话」。
     * @param condPrice 条件价格（配合 {@code condOp}）
     * @param quantity  数量（可空 = 未指定/全仓语义由 text 承载）
     * @param text      用户当时写的原话（提醒时**必须引用它**，不得改写成系统的判断句）
     */
    public record PlanItem(String id, String action, String symbol, String name,
                           String condition, String condOp, java.math.BigDecimal condPrice,
                           Integer quantity, String text, boolean done) {

        public PlanItem {
            if (action == null || action.isBlank()) action = "HOLD";
            if (condition == null) condition = "";
            if (condOp == null) condOp = "";
            if (text == null) text = "";
            if (name == null) name = "";
        }

        /** 是否买卖类（HOLD = 明确不动，不参与「执行/未执行」判定）。 */
        public boolean tradable() {
            return "BUY".equals(action) || "SELL".equals(action);
        }
    }
}
