package com.adaiadai.core.domain.trading.market;

import java.time.LocalDate;

/**
 * Candle — K 线（蜡烛图）数据（RFC 20260816 交易数据智能：盯盘买点/完美图匹配的原料）。
 *
 * @param date   日期
 * @param open   开盘
 * @param high   最高
 * @param low    最低
 * @param close  收盘
 * @param volume 成交量（手）
 */
public record Candle(
        LocalDate date,
        double open,
        double high,
        double low,
        double close,
        double volume
) {}
