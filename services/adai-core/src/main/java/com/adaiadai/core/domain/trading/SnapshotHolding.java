package com.adaiadai.core.domain.trading;

/**
 * SnapshotHolding — 券商快照（持仓 replace 导入）当日的逐标的持仓基线（2026-09-12 账实一致性批）。
 * <p>
 * 与 {@link SnapshotAnchor} 同存于 {@code data/{userId}/trading/snapshot-anchor.json}。
 * 作用：把「券商口径基线」从隐式变成显式——对账闸门（GET /trading/integrity）用
 * {@code 基线数量 + 锚点之后流水净增减 = 派生持仓} 推出应有持仓，与落地持仓比对，
 * 差异即「账实不符」（2026-09-12 生产事故：锚点缺失 + 重放双计，持仓与现金都错，
 * 却没有任何一处会报警）。
 *
 * @param symbol   证券代码
 * @param name     证券名称（可空）
 * @param quantity 快照当日持仓数量
 */
public record SnapshotHolding(String symbol, String name, int quantity) {}
