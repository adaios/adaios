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
 * @param date      计划管的那一天
 * @param items     计划条目（标的三要素：标的 · 条件 · 动作）
 * @param note      用户的自我约束（如「只做计划内的票，不追高」）
 * @param createdAt 落盘时间
 */
public record TradingPlan(LocalDate date, List<PlanItem> items, String note, LocalDateTime createdAt) {

    public TradingPlan {
        if (items == null) items = List.of();
        if (note == null) note = "";
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
