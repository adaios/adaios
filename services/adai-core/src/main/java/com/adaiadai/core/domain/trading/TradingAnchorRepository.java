package com.adaiadai.core.domain.trading;

import java.time.LocalDate;
import java.util.List;

/**
 * TradingAnchorRepository — 券商快照锚定存储端口（File First：
 * data/{userId}/trading/snapshot-anchor.json）。
 * <p>
 * 锚定是「全量覆盖导入」（持仓 replace / 资金股份查询）落地的元信息，供增量
 * 推导（sync 回放/手动成交/转账补记）判定「该笔是否已包含在券商快照内」，
 * 防 P2-交易34 类重复入账。
 */
public interface TradingAnchorRepository {

    /**
     * 读取锚定记录；文件缺失/损坏 → 空锚定。
     * <p>
     * ⚠️ 2026-09-12 账实一致性批：空锚定**不等于**「不做防重」——调用方（成交导入重放、
     * 手动成交、转账补记）必须先判 {@link SnapshotAnchor#known()}：未知锚定 + 需要改账的增量
     * 一律 fail-closed（拒绝 + 指路先导快照），不得静默按旧行为重放。
     */
    SnapshotAnchor find(String userId);

    /** 记录一次持仓全量 replace 导入（date 通常 = 导入日；只前进不后退）。 */
    void updatePositionsReplace(String userId, LocalDate date);

    /** 记录一次资金股份查询导入（date 通常 = 导入日；只前进不后退）。 */
    void updateCashImport(String userId, LocalDate date);

    /**
     * 记录一次持仓全量 replace 导入，并保留**快照文件里的原始日期**（2026-09-21，P1-交易61）。
     * <p>
     * {@code date} 是归一化后的**数据基准日**（盘前/非交易日导出会被退到上一交易日），
     * {@code fileDate} 是文件名里的导出日。两者不等 = 锚定日是推断出来的 → 对账闸门据此判断
     * 「锚定日当天的成交是否可能并不在快照内」，把「基线自洽的假绿」变成可见。
     * <p>
     * default 实现忽略 fileDate（旧实现 / 测试替身零改动）。
     */
    default void updatePositionsReplace(String userId, LocalDate date, LocalDate fileDate) {
        updatePositionsReplace(userId, date);
    }

    /** 记录一次资金股份查询导入 + 原始文件日期（语义同 {@link #updatePositionsReplace(String, LocalDate, LocalDate)}）。 */
    default void updateCashImport(String userId, LocalDate date, LocalDate fileDate) {
        updateCashImport(userId, date);
    }

    /**
     * 记录快照当日的逐标的持仓基线（与锚定同文件，2026-09-12 账实一致性批）：
     * 对账闸门用它 + 锚点之后流水净增减推出应有持仓，与落地持仓比对，
     * 差异即「账实不符」（防「口径塌了却没人报警」）。空列表 = 本次快照无持仓（会覆盖旧基线）。
     */
    void recordHoldings(String userId, List<SnapshotHolding> holdings);

    /** 读取快照当日持仓基线；从未记录/文件损坏 → 空列表（对账降级为「无法判定」，不误报）。 */
    List<SnapshotHolding> holdings(String userId);

    /**
     * 基线是否**被记录过**（2026-09-12：与「基线为空」区分）。
     * <p>
     * 全现金账户的快照 holdings 合法为空，若用「空列表」当未记录，对账会被误判成「无法判定」——
     * 与 fail-closed 精神一致：把「没记录」和「记录了但为空」分开。
     */
    boolean holdingsRecorded(String userId);
}
