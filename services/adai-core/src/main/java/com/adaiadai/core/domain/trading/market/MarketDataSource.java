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
}
