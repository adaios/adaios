package com.adaiadai.core.domain.trading;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * TradingHistoryRepository — 交易逐笔流水存储接口（端口定义，RFC 20260816 §2.1）。
 * <p>
 * 定义在 domain/trading 层，实现由 infrastructure/storage 提供（TradingHistoryFileRepository）。
 * 采用 File First：流水以 {@code data/{userId}/trading/trades/{yyyy-MM}.json}（每月一个 JSON 数组）存储。
 * 与 PositionRepository（聚合快照）互补：本端口提供逐笔流水的写入与查询。
 */
public interface TradingHistoryRepository {

    /**
     * 追加一笔交易流水（按月文件追加；同月多次 append 累积为数组）。
     *
     * @param userId 用户 ID
     * @param trade  逐笔交易记录（BUY/SELL 都写）
     */
    void append(String userId, TradeRecord trade);

    /**
     * 获取该用户全部交易流水（跨月合并，按 timestamp 倒序，最新在前）。
     */
    List<TradeRecord> findAll(String userId);

    /**
     * 获取该用户指定日期的交易流水（按 timestamp 倒序）。
     */
    List<TradeRecord> findByDate(String userId, LocalDate date);

    /**
     * 回填单笔流水的成交时间（历史成交重传：幂等命中的记录补缺失字段）。
     * <p>
     * 读-改-写该笔所在月份文件：按 {@code tradeId} 定位，仅当旧记录 {@code tradeTime} 为空时回填；
     * 找不到该 id 静默（文件漂移/并发删除兜底，不抛错）。
     *
     * @return 实际回填笔数（0 或 1）
     */
    int backfillTradeTime(String userId, String tradeId, LocalDate entryDate, LocalTime tradeTime);

    /**
     * 补写单笔流水的成交编号/手续费（P2-交易36 治本，2026-09-09：截图入账/手动确认成交
     * 落库时缺 orderId/fee——对已落库流水按 tradeId 补填）。
     * <p>
     * 读-改-写该笔所在月份文件（跨月：优先按 tradeId 内时间戳定位月份文件，找不到再全扫兜底）：
     * 只覆盖非空新值——orderId 非 null 且非 blank 才替换、fee 非 null 才替换；
     * 不改其它字段与时间戳；找不到该 id 返回 0（不抛错）；写失败抛 StorageException
     * （与 {@link #backfillTradeTime} 同口径）。
     *
     * @param userId  用户 ID
     * @param tradeId 流水 ID（定位键）
     * @param orderId 券商成交编号（可空：null/blank 不覆盖）
     * @param fee     手续费（可空：null 不覆盖）
     * @return 实际更新笔数（0 或 1）；无可写新值（orderId 空且 fee null）返回 0
     */
    int updateTradeMeta(String userId, String tradeId, String orderId, BigDecimal fee);
}
