package com.adaiadai.core.domain.trading.engine;

/** 止损判定结论（R66 只输一根K线）。 */
public enum StopLossVerdict {
    /** 未跌破止损位 / 无据可判。 */
    OK,
    /** 现价已跌破止损位 → suggestion 必须 clear（R66）。 */
    BREACHED
}
