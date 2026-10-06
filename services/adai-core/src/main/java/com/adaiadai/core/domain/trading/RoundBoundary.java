package com.adaiadai.core.domain.trading;

/**
 * RoundBoundary — 「笔的边界」一等对象（design-final §2.3 / §7 · input D3，2026-10-06）。
 *
 * <p><b>为什么是一等对象</b>：边界不是「覆盖计算结果」，而是可追溯 / 可撤销 / 自动让位的实体——
 * 用户标了就以用户为准（<b>人工 &gt; 自动</b>），自动切分让位；每条边界可带备注。
 *
 * <p><b>结构</b>（设计稿 §7 术语表）：{@code {symbol, 锚定日, 锚定那笔买入, source: auto|manual, 备注}}。
 * 本 record 是<b>人工侧</b>的持久化形态（落 {@code data/{userId}/trading/round-boundaries.json}）；
 * 自动边界不落盘——每次从流水实时算，改完即重算（验收 3「改完后续分析与统计跟着重算」）。
 *
 * <p><b>mode 语义</b>：
 * <ul>
 *   <li>{@link #MODE_CUT}：从锚定那笔买入开始算新的一笔（切开）</li>
 *   <li>{@link #MODE_MERGE}：锚定买入所在的笔与上一笔合并</li>
 *   <li>{@code null}：只加备注，不动切分</li>
 * </ul>
 *
 * @param symbol     标的代码
 * @param anchorBuyId 锚定那笔买入的流水 id（TradeRecord.id）
 * @param anchorDate 锚定日（锚定买入的成交日，冗余存储便于人读与校验）
 * @param mode       切分动作（cut / merge；null = 仅备注）
 * @param note       备注（可空）
 * @param updatedAt  最后改动时间（ISO-8601，可追溯）
 */
public record RoundBoundary(
        String symbol,
        String anchorBuyId,
        String anchorDate,
        String mode,
        String note,
        String updatedAt) {

    /** 从锚定那笔买入开始算新的一笔。 */
    public static final String MODE_CUT = "cut";
    /** 锚定买入所在的笔与上一笔合并。 */
    public static final String MODE_MERGE = "merge";

    /** 是否携带切分动作（cut / merge）——false = 仅备注，不动切分。 */
    public boolean hasSplit() {
        return MODE_CUT.equals(mode) || MODE_MERGE.equals(mode);
    }
}
