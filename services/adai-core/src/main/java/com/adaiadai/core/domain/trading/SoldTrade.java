package com.adaiadai.core.domain.trading;

import java.time.LocalDate;

/**
 * SoldTrade — 清仓股（已了结交易，复盘闭环，RFC 20260816）。
 * <p>
 * 通达信清仓导出自带：介入/清仓日期、持仓天数、买卖次数、持仓期涨幅%（交易盈亏）。
 * 阿呆补充：verdict（对照规则判对错）与 psychology（用户标注当时心理，复盘素材）。
 * <p>
 * RFC 20260909 批 1（双轨）加字段 provenance = 来源（flow | import）：
 * <ul>
 *   <li>{@code import}：券商清仓股导出导入（含老文件缺字段的兼容默认）——券商口径权威</li>
 *   <li>{@code flow}：流水自动推导收录（ClearanceDetector）——流水回合口径，只填空白不覆盖</li>
 * </ul>
 *
 * @param symbol      股票代码
 * @param name        股票名称
 * @param buyDate     介入日期
 * @param sellDate    清仓日期
 * @param holdDays    持仓天数
 * @param tradeCount  买卖次数（如 "5+1"；flow 行为流水总笔数字符串）
 * @param holdPnlPct  持仓期涨幅%（交易盈亏比例）
 * @param verdict     复盘结论（按规则判对错：R53 没涨=错 / R66 止损执行 / R120 卖点对应买点）
 * @param psychology  用户心理标注（追高/恐慌/贪婪等，可空）
 * @param provenance  来源（flow=流水推导 | import=券商导出；老文件缺省视为 import）
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
        String psychology,
        String provenance
) {
    public SoldTrade {
        if (symbol == null) symbol = "";
        if (name == null) name = "";
        if (tradeCount == null) tradeCount = "";
        if (verdict == null) verdict = "";
        if (psychology == null) psychology = "";
        if (provenance == null || provenance.isBlank()) provenance = "import";
    }

    /**
     * 9 参便捷构造（导入/旧调用兼容）：provenance 缺省为 import（券商导出/老行口径）。
     */
    public SoldTrade(String symbol, String name, LocalDate buyDate, LocalDate sellDate,
                     int holdDays, String tradeCount, double holdPnlPct,
                     String verdict, String psychology) {
        this(symbol, name, buyDate, sellDate, holdDays, tradeCount, holdPnlPct,
                verdict, psychology, "import");
    }
}
