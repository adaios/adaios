package com.adaiadai.core.domain.trading;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * TradeLogCandidate — 当日交易日志候选（RFC 20260817 交易日志自动归集）。
 * <p>
 * 用户发截图/说「清仓了XX」→ 识别出的当日成交动作，**未落库**（待用户确认）。
 * 同 (symbol, direction, volume 近似, 当日) 去重。
 *
 * @param symbol    股票代码
 * @param name      股票名称
 * @param direction BUY / SELL
 * @param price     成交价（截图识别可得；文字无价格 → null = 不完整）
 * @param volume    数量（文字「清仓了XX」无数量 → null = 不完整）
 * @param tradeDate 成交日期（2026-08-27：截图表格「日期」列提取；当日成交单无日期列 → null = 归集当天）
 * @param source    来源（text=文字 / image=截图）
 * @param complete  是否完整（symbol+direction+price+volume 全有）
 * @param orderId   券商成交编号（P2-交易36 治本，2026-09-09：截图入账/手动确认可携带；
 *                  可空——本期截图 OCR 不抽取，由用户确认前补填或确认落库后对流水补填）
 * @param fee       手续费（同上，可空；确认落库透传 recordTradeWithOrderId）
 * @param id        候选**行标识**（P1-交易54，2026-09-17）：候选此前没有 id，删除/补日期/补元信息
 *                  只能按「代码 + 方向」粗粒度匹配——同标的同方向的多笔（生产实据：当日三笔
 *                  亨通光电买入各 100 股）**一删就是全部**，用户「识别错误的行删不掉」的第二层。
 *                  旧数据/旧调用点可为 null，由 {@code TradeLogRepository.append} 在落盘前补发。
 */
public record TradeLogCandidate(
        String symbol,
        String name,
        String direction,
        BigDecimal price,
        Integer volume,
        LocalDate tradeDate,
        String source,
        boolean complete,
        String orderId,
        BigDecimal fee,
        String id
) {
    /** canonical 构造兜底（与 TradeLogRepository.normalizeNull 同口径）：
     *  orderId 空白/字面 "null" → null（防脏值污染落库与去重判定）；fee 无空值概念保持原样；
     *  id 同 orderId 口径（空串/字面 "null" 归 null = 尚未发号）。 */
    public TradeLogCandidate {
        orderId = (orderId == null || orderId.isBlank() || "null".equals(orderId)) ? null : orderId;
        id = (id == null || id.isBlank() || "null".equals(id)) ? null : id;
    }

    /** 10 参委派构造（P1-交易54 兼容）：不带 id —— 既有调用点零改动，id 在落盘前补发。 */
    public TradeLogCandidate(String symbol, String name, String direction, BigDecimal price,
                             Integer volume, LocalDate tradeDate, String source, boolean complete,
                             String orderId, BigDecimal fee) {
        this(symbol, name, direction, price, volume, tradeDate, source, complete, orderId, fee, null);
    }

    /** 换 id 的副本（落盘前发号 / 读取时给旧数据补号用；其余字段逐字保留）。 */
    public TradeLogCandidate withId(String newId) {
        return new TradeLogCandidate(symbol, name, direction, price, volume, tradeDate,
                source, complete, orderId, fee, newId);
    }

    /** 8 参委派构造（历史调用点兼容）：orderId/fee 缺省 null——截图 OCR 抽取上线前候选不携带。 */
    public TradeLogCandidate(String symbol, String name, String direction, BigDecimal price,
                             Integer volume, LocalDate tradeDate, String source, boolean complete) {
        this(symbol, name, direction, price, volume, tradeDate, source, complete, null, null);
    }
    /** 去重键：同 symbol + 方向（volume 维度由 {@link #sameTrade} 按 ±10% 区间判定）。 */
    public String dedupeKey() {
        // P1-1（2026-08-18 生产）：symbol 缺失（宽松解析未识别代码）时用 name 兜底，
        // 避免所有无代码候选共用 "unknown" 键互相吞并（生产 09:01-09:02 三次归集只剩 2 笔）。
        String key = (symbol != null && !symbol.isBlank()) ? symbol
                : (name != null && !name.isBlank()) ? name
                : "?";
        return key + ":" + direction;
    }

    /**
     * 是否与另一候选视为同一笔（B6-2，2026-08-23，P1-交易12）：
     * 同 symbol + 方向，且 volume 差 ≤ ±10%（相对大者）——`volume/10*10` 固定 10 股桶
     * 过宽吞笔（10 vs 19 同桶）/过窄漏去重（100 vs 110 分开 → confirm 双落库）双缺陷；
     * 任一方 volume 缺失（不完整候选）按 symbol+direction 同笔（去重键语义不变）。
     *
     * <p>2026-09-17（P0-交易53）**补价格维度**：原判定只看 symbol+方向+数量，而「同标的、
     * 同方向、各 100 股」的多笔成交极其常见——生产实测一张截图 3 笔亨通光电买入各 100 股
     * （价格 68.27 / 67.73 / 67.92）会被吞成 1 笔，解析修好也白修。价格不同 → 判为不同笔；
     * 同一张图重复上传时价格一致，去重语义不变。
     */
    public boolean sameTrade(TradeLogCandidate other) {
        if (other == null) return false;
        if (!dedupeKey().equals(other.dedupeKey())) return false;
        // 价格维度：双方都有有效价格且不相等 → 不是同一笔。
        // 取舍：宁可多留一笔让用户手动丢，也不能静默吞掉真实成交（吞掉是数据丢失，删多只是多点一下）。
        if (price != null && other.price != null
                && price.signum() > 0 && other.price.signum() > 0
                && price.compareTo(other.price) != 0) {
            return false;
        }
        if (volume == null || other.volume == null || volume <= 0 || other.volume <= 0) return true;
        int max = Math.max(volume, other.volume);
        long diff = Math.abs((long) volume - other.volume);
        return diff * 10L <= (long) max; // diff/max ≤ 0.10 → ±10% 内同笔
    }
}
