package com.adaiadai.core.domain.trading.market;

import java.util.List;

/**
 * KlineSource — K 线数据源接口（2026-08-16：盯盘买点/完美图匹配的原料）。
 * <p>
 * 主源东方财富（EastMoneyKlineDataSource），兜底腾讯（TencentMarketDataSource.kline）。
 * 安全约定：网络异常返回空列表而非抛异常。
 */
public interface KlineSource {

    /**
     * 查询日 K 线。
     *
     * @param symbol 6 位股票代码
     * @param limit  最近 N 根（上限 320）
     * @return 日 K 序列（旧→新）
     */
    List<Candle> kline(String symbol, int limit);
}
