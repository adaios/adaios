package com.adaiadai.core.domain.trading.market;

import java.math.BigDecimal;

/**
 * MarketData — 个股/指数行情数据。
 *
 * @param code           股票代码（6位，如 600519）
 * @param name           股票名称
 * @param price          最新价
 * @param yesterdayClose 昨收价
 * @param open           今开价
 * @param high           最高价
 * @param low            最低价
 * @param changePercent  涨跌幅（百分比，如 3.09 表示涨 3.09%）
 * @param volume         成交量（手）
 */
public record MarketData(
        String code,
        String name,
        BigDecimal price,
        BigDecimal yesterdayClose,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal changePercent,
        long volume
) {
}
