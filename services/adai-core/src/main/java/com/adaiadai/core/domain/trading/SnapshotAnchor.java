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
 * @param positionsFileDate 持仓快照**文件里的原始日期**（导出日，未归一化；未记录 → null）。
 *                         2026-09-21（P1-交易61）：与 {@code positionsReplace} 不等 = 锚定日经过归一化**推断**——
 *                         归一化只在「快照日期 == 今天」时生效（盘前/非交易日导出退到上一交易日），
 *                         此时「锚定日当天」的成交可能并不在快照里，必须让对账闸门能识别并报出。
 * @param cashFileDate      资金股份快照的原始文件日期（语义同 positionsFileDate；未记录 → null）
 * @param positionsBasis    持仓锚定日的**依据**（2026-10-05，P2-交易84）：显式基准日 / 文件日期 /
 *                          休市日归一化 = 有据；时钟推断 = 无据。老落盘文件无此字段 → null（不可判定，
 *                          沿用「文件日期 ≠ 锚定日 = 推断」的既有语义，不诬告）。
 * @param cashBasis         资金股份锚定日的依据（语义同 positionsBasis；未记录 → null）
 */
public record SnapshotAnchor(LocalDate positionsReplace, LocalDate cashImport,
                             LocalDate positionsFileDate, LocalDate cashFileDate,
                             AnchorBasis positionsBasis, AnchorBasis cashBasis) {

    /** 兼容构造（旧调用 / 老落盘文件没有文件日期信息 = 不可判定是否推断）。 */
    public SnapshotAnchor(LocalDate positionsReplace, LocalDate cashImport) {
        this(positionsReplace, cashImport, null, null, null, null);
    }

    /** 兼容构造（有文件日期、无依据信息——2026-09-21 批的落盘格式）。 */
    public SnapshotAnchor(LocalDate positionsReplace, LocalDate cashImport,
                          LocalDate positionsFileDate, LocalDate cashFileDate) {
        this(positionsReplace, cashImport, positionsFileDate, cashFileDate, null, null);
    }

    /** 空锚定（从未做过全量导入）。 */
    public static SnapshotAnchor empty() {
        return new SnapshotAnchor(null, null);
    }

    /**
     * 持仓锚定日是否为「推断值」（2026-09-21，P1-交易61）：文件写的日期 ≠ 最终锚定日
     * → 归一化动过它 → 锚定日当天的成交**可能**不在快照里（不能假定已被覆盖）。
     * <p>
     * 文件日期未记录（老落盘文件 / 历史调用）→ false（**不诬告**）：拿不到证据就不报警；
     * 这一信息由 {@code positionsFileDate == null} 在对账 note 里如实说明。
     */
    public boolean positionsDateInferred() {
        return positionsFileDate != null && positionsReplace != null
                && !positionsFileDate.equals(positionsReplace);
    }

    /** 资金股份锚定日是否为推断值（语义同 {@link #positionsDateInferred()}）。 */
    public boolean cashDateInferred() {
        return cashFileDate != null && cashImport != null && !cashFileDate.equals(cashImport);
    }

    /**
     * 持仓锚定日是否有据（2026-10-05，P2-交易84）：依据被记录过且不是时钟推断。
     * <p>老落盘文件（依据为 null）→ false：**不报案**也**不诬告**，由既有
     * {@link #positionsDateInferred()} 与文件日期是否记录来判定。
     */
    public boolean positionsBasisWithEvidence() {
        return positionsBasis != null && positionsBasis.withEvidence();
    }

    /** 资金股份锚定日是否有据（语义同 {@link #positionsBasisWithEvidence()}）。 */
    public boolean cashBasisWithEvidence() {
        return cashBasis != null && cashBasis.withEvidence();
    }

    /**
     * 锚定是否已知（至少做过一次全量导入）。
     * <p>
     * 2026-09-12 账实一致性批：空锚定**不再**等于「不做防重」——未知锚定 + 需要重放的增量
     * = fail-closed（拒绝重放 + 指路先导快照），防 P2-交易34 式静默双计在生产再现
     * （2026-09-12 实测：生产 snapshot-anchor.json 根本不存在 → 防重整条失效 → 一次导入
     * 把已含在 09-09 快照内的成交重放一遍，持仓与现金双计，现金被推到 −26666.85）。
     */
    public boolean known() {
        return positionsReplace != null || cashImport != null;
    }

    /** 合并后的锚定日（较晚者；两者皆空 → null）。 */
    public LocalDate latest() {
        if (positionsReplace == null) return cashImport;
        if (cashImport == null) return positionsReplace;
        return positionsReplace.isAfter(cashImport) ? positionsReplace : cashImport;
    }
}
