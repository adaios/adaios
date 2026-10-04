package com.adaiadai.core.domain.trading.market;

import java.time.LocalDate;
import java.util.List;

/**
 * KlineSource — K 线数据源接口（2026-08-16：盯盘买点/完美图匹配的原料）。
 * <p>
 * 链路（RFC 20260928 批 2）：tdx 本地（{@code TdxFileKlineSource}）→ 主源腾讯
 * （{@code TencentMarketDataSource.kline}，域名列表按序尝试）→ 兜底新浪（{@code SinaKlineDataSource}）。
 * 东财 K 线源已于 2026-09-28 移出链路（长期不可达）。
 * 安全约定：网络异常返回空列表而非抛异常。
 */
public interface KlineSource {

    /** 探测取几根（够判断「有没有数据」即可，越小越轻）。 */
    int PROBE_LIMIT = 10;

    /**
     * 查询日 K 线。
     *
     * @param symbol 6 位股票代码
     * @param limit  最近 N 根（上限 320）
     * @return 日 K 序列（旧→新）
     */
    List<Candle> kline(String symbol, int limit);

    /**
     * 轻量健康探测（2026-10-04，REVIEW P2-交易58 收口）。
     * <p>
     * <b>为什么在接口上</b>：兜底源（新浪）平时零调用，只在主源熔断时才被批量打过去——而那时
     * 恰恰是它最可能也挂的时刻，事后才知道「兜底形同虚设」太晚。{@code KlineService} 用它做
     * 低频主动体检（交易时段每 30 分钟一次，结果经 {@code GET /trading/market-data/health} 可见）。
     * <p>
     * <b>默认实现</b>：复用 {@link #kline(String, int)} 取 {@link #PROBE_LIMIT} 根，非空即算活。
     * 带按日缓存、或需要比 kline 更短超时的源**应当覆写**（如 {@code SinaKlineDataSource}：
     * 真实网络请求、5s 超时、**绕过缓存**——缓存命中只证明「今天成功过」，证不了「此刻还活着」）。
     * <p>
     * <b>约定</b>：与 {@link #kline} 同——异常一律吞掉返回 {@code false}，绝不抛给调用方
     * （探测跑在调度线程上，抛出去就是定时任务报错）。
     *
     * @param symbol 6 位股票代码（探测用固定标的）
     * @return true = 这一次真的拿到了数据
     */
    default boolean probe(String symbol) {
        try {
            List<Candle> c = kline(symbol, PROBE_LIMIT);
            return c != null && !c.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 按日期范围查询日 K 线（2026-08-30：完美买点案例库——标注历史日期案例需取
     * 「前 60 + 后 30」窗口，最近 N 根语义覆盖不了任意历史日期）。
     * <p>
     * 默认实现（新浪走这条）：拉最近 320 根（约 1.3 年）后按日期过滤截取——覆盖距今 ≤320 交易日的
     * 案例；腾讯覆写为数据源日期参数直查（更早历史也可取，且在本地按 [from,to] 裁剪）。
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
