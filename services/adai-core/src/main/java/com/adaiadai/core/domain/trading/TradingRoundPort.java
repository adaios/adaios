package com.adaiadai.core.domain.trading;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * TradingRoundPort — 逐笔回合数据源端口（RFC 20260905 P2-认知2，2026-10-05）。
 * <p>
 * <b>为什么有它</b>：画像统计曾建在 {@code sold.json}（清仓表）上——清仓表是**标的级总账**
 * （首买 → 末卖，纸上区间收益），同一只票做过两轮各 +24% 会被读成「+224%」。画像要的是
 * **逐笔回合**（一批买入 → 卖清，一次了结），而回合推导在
 * {@code application.TradingLotService}（流水投影，不落盘）。内核域不得依赖 application，
 * 故此处立端口：domain 只认「已了结回合」这一概念，实现（批次重放）由 application 提供。
 * <p>
 * 口径与 {@link TradingLot} 一致：同标的同日买入合并为一批，卖出按 LIFO 扣减，剩余归零 = 回合关闭。
 *
 * @see TradingLot
 */
public interface TradingRoundPort {

    /**
     * 该用户的全部已了结回合（买入批次 → 卖清），按回合关闭日倒序（最新在前）。
     * <p>
     * 约定：数据缺失/损坏 → 返回空表，不抛错（画像降级为空）；成本不可测的回合
     * （买入量为 0 / 成本价 ≤ 0，负成本批等）**不返回**——它们的收益率没有意义，
     * 不参与统计（宁缺毋滥，不编造 0）。
     */
    List<ClosedRound> closedRounds(String userId);

    /**
     * 一个已了结回合（批次口径，非标的级总账）。
     *
     * @param lotId      批次稳定 ID（{@code {symbol}_{buyDate}_B}；初始批次 {@code {symbol}_INIT}）
     * @param symbol     标的代码
     * @param name       标的名称（缺省 symbol）
     * @param buyDate    回合买入日（批次建立日；初始批次取持仓 entryDate，可空）
     * @param closeDate  回合关闭日（卖清该批的成交日；可空）
     * @param costValue  回合买入成本（volume × costPrice，含买入费用）
     * @param realizedPnl 回合已实现盈亏（毛利 − 买入费用 − 卖出费用）
     * @param pnlPct     回合收益率%（realizedPnl / costValue × 100）
     * @param holdDays   持仓自然日（buyDate → closeDate；buyDate/closeDate 不可得 → 0）
     */
    record ClosedRound(
            String lotId,
            String symbol,
            String name,
            LocalDate buyDate,
            LocalDate closeDate,
            BigDecimal costValue,
            BigDecimal realizedPnl,
            double pnlPct,
            int holdDays
    ) {}
}
