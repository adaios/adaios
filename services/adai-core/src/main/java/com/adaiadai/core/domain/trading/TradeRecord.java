package com.adaiadai.core.domain.trading;

import com.adaiadai.core.kernel.record.ContentRecord;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * TradeRecord — 单笔交易记录。
 * <p>
 * 从 ContentRecord 中解析出的交易结构化数据。
 * 交易记录源文件为 {@code data/records/}，此模型为内存中的结构化表示。
 *
 * @param symbol    股票/资产代码（如 "600123"）
 * @param name      资产名称（如 "立昂微"）
 * @param direction 交易方向
 * @param price     成交单价
 * @param volume    成交数量
 * @param amount    成交金额（price × volume）
 * @param timestamp   交易时间
 * @param sourceRecordId 关联的原始 Record ID
 */
public record TradeRecord(
        String symbol,
        String name,
        TradeDirection direction,
        BigDecimal price,
        int volume,
        BigDecimal amount,
        LocalDateTime timestamp,
        String sourceRecordId
) {

    /**
     * 从 ContentRecord 解析出 TradeRecord 的简化工厂。
     * <p>
     * 实际场景需配合交易数据完整解析，此处提供基础能力。
     */
    public static TradeRecord fromContent(String symbol, String name,
                                          TradeDirection direction,
                                          BigDecimal price, int volume,
                                          ContentRecord source) {
        return new TradeRecord(
                symbol, name, direction, price, volume,
                price.multiply(BigDecimal.valueOf(volume)),
                source.createdAt(), source.id()
        );
    }
}
