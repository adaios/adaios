package com.adaiadai.core.domain.trading;

import java.time.LocalDate;

/**
 * SoldTrade — 清仓股（已了结交易，复盘闭环，RFC 20260816）。
 * <p>
 * 通达信清仓导出自带：介入/清仓日期、持仓天数、买卖次数、持仓期涨幅%（交易盈亏）。
 * 阿呆补充：verdict（对照规则判对错）与 psychology（用户标注当时心理，复盘素材）。
 *
 * @param symbol      股票代码
 * @param name        股票名称
 * @param buyDate     介入日期
 * @param sellDate    清仓日期
 * @param holdDays    持仓天数
 * @param tradeCount  买卖次数（如 "5+1"）
 * @param holdPnlPct  持仓期涨幅%（交易盈亏比例）
 * @param verdict     复盘结论（按规则判对错：R53 没涨=错 / R66 止损执行 / R120 卖点对应买点）
 * @param psychology  用户心理标注（追高/恐慌/贪婪等，可空）
 */
public record SoldTrade(
        String symbol,
        String name,
        LocalDate buyDate,
        LocalDate sellDate,
        int holdDays,
        String tradeCount,
        double holdPnlPct,
        String verdict,
        String psychology
) {
    public SoldTrade {
        if (symbol == null) symbol = "";
        if (name == null) name = "";
        if (tradeCount == null) tradeCount = "";
        if (verdict == null) verdict = "";
        if (psychology == null) psychology = "";
    }
}
