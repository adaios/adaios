package com.adaiadai.core.domain.trading;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * TradeRecord — 单笔交易记录（逐笔流水真相源，RFC 20260816 §2.1）。
 * <p>
 * 每笔 BUY/SELL 交易在 {@code recordTrade} 成功后落盘为一条流水，
 * 存储于 {@code data/{userId}/trading/trades/{yyyy-MM}.json}（每月一个 JSON 数组）。
 * 与 positions.md（聚合快照）互补：本模型保留每笔交易的完整细节，可回溯首买日/止损/买点/原因。
 * <p>
 * 数据分层（RFC 20260816 §1）：
 * <ul>
 *   <li>系统字段：id / amount（price×volume 派生）/ entryDate（默认当天，可补录）/ tradeTime（成交时刻，可空）/ timestamp / sourceRecordId</li>
 *   <li>用户提供：symbol / direction / price / volume / stopLossPrice / buyPoint / targetPrice / reason / fee</li>
 *   <li>行情补全：name（缺省 symbol）</li>
 * </ul>
 *
 * @param id            流水 ID（{@code trade_yyyyMMdd_HHmmssSSS}，IdGenerator 生成）
 * @param symbol        股票/资产代码（如 "600000"）
 * @param name          资产名称（行情补全，缺省 symbol）
 * @param direction     交易方向
 * @param price         成交单价
 * @param volume        成交数量
 * @param amount        成交金额（price × volume，派生）
 * @param entryDate     交易日期（用户可改/可补录，缺省当天）
 * @param tradeTime     成交时刻（可空，RFC 20260822——通达信成交时间列/当日记录缺省落盘时刻时分；旧数据 null）
 * @param stopLossPrice 止损位（BUY 必填；SELL 可空）
 * @param buyPoint      买点类型（B1/B2/B3/SB1/暴力特噗/深水炸弹/单针/其他；BUY 必填；SELL 可空）
 * @param targetPrice   目标价（可空，盈亏比 R38 复盘锚点）
 * @param reason        交易原因/预期（可空，复盘锚点）
 * @param fee           手续费（可空，P2 可全局费率；历史成交导入时 = 券商实际发生金额与成交金额之差）
 * @param timestamp     落盘时间
 * @param sourceRecordId 关联的时间线 Record ID（可空）
 * @param orderId       券商成交编号（可空；历史成交导入幂等键——同编号不重复导入）
 */
public record TradeRecord(
        String id,
        String symbol,
        String name,
        TradeDirection direction,
        BigDecimal price,
        int volume,
        BigDecimal amount,
        LocalDate entryDate,
        LocalTime tradeTime,
        BigDecimal stopLossPrice,
        String buyPoint,
        BigDecimal targetPrice,
        String reason,
        BigDecimal fee,
        LocalDateTime timestamp,
        String sourceRecordId,
        String orderId
) {

    /**
     * 构造逐笔流水：amount 由 price × volume 派生。
     */
    public static TradeRecord of(String id, String symbol, String name, TradeDirection direction,
                                 BigDecimal price, int volume, LocalDate entryDate,
                                 LocalTime tradeTime,
                                 BigDecimal stopLossPrice, String buyPoint,
                                 BigDecimal targetPrice, String reason, BigDecimal fee,
                                 LocalDateTime timestamp, String sourceRecordId, String orderId) {
        return new TradeRecord(
                id, symbol, name, direction, price, volume,
                price.multiply(BigDecimal.valueOf(volume)),
                entryDate, tradeTime, stopLossPrice, buyPoint, targetPrice, reason, fee, timestamp, sourceRecordId, orderId);
    }
}
