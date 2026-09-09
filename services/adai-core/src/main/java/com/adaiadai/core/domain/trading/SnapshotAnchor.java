package com.adaiadai.core.domain.trading;

import java.time.LocalDate;

/**
 * SnapshotAnchor — 券商快照锚定记录（P2-交易34 治本，2026-09-09）。
 * <p>
 * 记录最近一次「全量锚定」的日期：
 * <ul>
 *   <li>{@code positionsReplace}：通达信「持仓股」replace=true 全量覆盖导入日——此后持仓数量/成本以券商文件为准；</li>
 *   <li>{@code cashImport}：通达信「资金股份查询」导入日——此后现金/资产/可用以券商快照为准（S5 真源锚定）。</li>
 * </ul>
 * 锚定日及更早的成交/转账已包含在券商口径内（快照即当日收盘后的结果），
 * 系统层面的「增量推导」（当日成交 sync 回放、手动成交补录、转账补记）不得再对该日期重复入账——
 * 否则现金/持仓双计（2026-09-07 P2-交易34 实测 −3.7 万、2026-09-09 同源复发 −1.27 万）。
 *
 * @param positionsReplace 最近一次持仓全量 replace 导入日期（从未 replace → null）
 * @param cashImport       最近一次资金股份查询导入日期（从未导入 → null）
 */
public record SnapshotAnchor(LocalDate positionsReplace, LocalDate cashImport) {

    /** 空锚定（从未做过全量导入）。 */
    public static SnapshotAnchor empty() {
        return new SnapshotAnchor(null, null);
    }
}
