package com.adaiadai.core.domain.trading;

import java.time.LocalDate;

/**
 * WatchlistItem — 自选股条目（RFC 20260816 交易数据智能：盯盘买点原料）。
 * <p>
 * 通达信自选导出自带形态/指标字段——长期/中期/短期形态（1-15 级）与近日指标提示
 * （KDJ死叉/金叉/阶段放量等），是 B1/B2/B3 买点判定的现成原料。
 *
 * @param symbol     股票代码
 * @param name       股票名称
 * @param industry   细分行业
 * @param industry2  一二级行业
 * @param longForm   长期形态（1-15）
 * @param midForm    中期形态
 * @param shortForm  短期形态
 * @param signal     近日指标提示（KDJ死叉/金叉/MACD死叉/阶段放量等）
 * @param addedAt    加入日期
 */
public record WatchlistItem(
        String symbol,
        String name,
        String industry,
        String industry2,
        int longForm,
        int midForm,
        int shortForm,
        String signal,
        LocalDate addedAt
) {
    public WatchlistItem {
        if (symbol == null) symbol = "";
        if (name == null) name = "";
        if (industry == null) industry = "";
        if (industry2 == null) industry2 = "";
        if (signal == null) signal = "";
        if (addedAt == null) addedAt = LocalDate.now();
    }
}
