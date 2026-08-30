package com.adaiadai.core.domain.trading.market;

import java.time.LocalDate;
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

    /**
     * 按日期范围查询日 K 线（2026-08-30：完美买点案例库——标注历史日期案例需取
     * 「前 60 + 后 30」窗口，最近 N 根语义覆盖不了任意历史日期）。
     * <p>
     * 默认实现：拉最近 320 根（约 1.3 年）后按日期过滤截取——覆盖距今 ≤320 交易日的
     * 案例；腾讯/东财实现覆写为数据源日期参数直查（更早历史也可取）。
     * 安全约定：异常返回空列表。
     *
     * @param symbol 6 位股票代码
     * @param from   起始日期（含）
     * @param to     截止日期（含）
     * @return 窗口内日 K 序列（旧→新），可能含停牌缺口
     */
    default List<Candle> klineRange(String symbol, LocalDate from, LocalDate to) {
        if (symbol == null || symbol.isBlank() || from == null || to == null || from.isAfter(to)) {
            return List.of();
        }
        List<Candle> all = kline(symbol, 320);
        return all.stream()
                .filter(c -> !c.date().isBefore(from) && !c.date().isAfter(to))
                .toList();
    }
}
