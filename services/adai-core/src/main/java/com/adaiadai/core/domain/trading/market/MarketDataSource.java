package com.adaiadai.core.domain.trading.market;

import java.util.List;
import java.util.Map;

/**
 * MarketDataSource — 行情数据源接口。
 * <p>
 * Kernel 组件，负责从外部获取 A 股行情数据。
 * 当前实现：{@link TencentMarketDataSource}（腾讯行情 API）。
 * <p>
 * 所有方法均安全：网络异常时返回空 Map 而非抛异常。
 */
public interface MarketDataSource {

    /**
     * 批量查询个股行情。
     *
     * @param codes 6位股票代码列表，如 ["600519", "600123"]
     * @return code → MarketData 映射，查询失败的代码不在 Map 中
     */
    Map<String, MarketData> quote(List<String> codes);

    /**
     * 查询大盘指数行情。
     * <p>
     * 固定查询：上证(sh000001)、深证(sz399001)、创业板(sz399006)。
     *
     * @return code → MarketData 映射
     */
    Map<String, MarketData> indices();

    /**
     * 查询日 K 线（2026-08-16，RFC 交易数据智能）。
     * <p>
     * 安全约定同 quote：网络异常返回空列表而非抛异常。主源东方财富，失败降级腾讯。
     *
     * @param symbol 6 位股票代码
     * @param limit  最近 N 根（320 上限）
     * @return 日 K 序列（新→旧或旧→新由实现定，统一旧→新）
     */
    default List<Candle> kline(String symbol, int limit) {
        return List.of();
    }
}
