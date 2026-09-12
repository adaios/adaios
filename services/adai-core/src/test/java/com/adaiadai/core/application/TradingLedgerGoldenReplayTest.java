package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.AccountSnapshot;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.SnapshotAnchor;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.domain.trading.TradingAnchorRepository;
import com.adaiadai.core.domain.trading.TradingException;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.TransferRepository;
import com.adaiadai.core.domain.trading.WatchlistRepository;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import com.adaiadai.core.infrastructure.storage.AccountSnapshotFileRepository;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.PositionFileRepository;
import com.adaiadai.core.infrastructure.storage.TradingAnchorFileRepository;
import com.adaiadai.core.infrastructure.storage.TradingHistoryFileRepository;
import com.adaiadai.core.infrastructure.storage.TradingRuleSettingsRepository;
import com.adaiadai.core.kernel.record.RecordRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TradingLedgerGoldenReplayTest — 2026-09-12 生产事故的**端到端回放**（RFC 20260912 §九 验收用例）。
 * <p>
 * 用真实文件仓储（InMemoryFileStorage + PositionFileRepository / AccountSnapshotFileRepository /
 * TradingHistoryFileRepository / TradingAnchorFileRepository）复现「09-09 券商快照 + 之后的历史成交导出」
 * 这一真实流程，逐条断言验收标准：
 * <ol>
 *   <li>≤ 锚定日的成交只补流水（不重复改持仓/现金）→ 不再出现「重放已含在快照里的成交」双计；</li>
 *   <li>锚定日之后的成交正常回放 → 持仓/现金增量正确；</li>
 *   <li>无法归属的真实卖出仍落流水 + rejected 可见（不消失）；</li>
 *   <li>重传同一文件幂等（不新增流水、现金不变）；</li>
 *   <li><b>删掉锚定文件再导 → 必须拒绝（fail-closed），而不是静默重放</b>（本次事故根因的回归防线）。</li>
 * </ol>
 * 夹具全部为**合成数据**（B3 隐私红线：真实成交导出不进 git），结构与通达信导出一致。
 */
class TradingLedgerGoldenReplayTest {

    private static final String USER = "default";
    /** 锚定日 = 09-09（相对今天 3 天前，保证落在导入的 10 日窗口内）。 */
    private static final LocalDate ANCHOR = LocalDate.now().minusDays(3);

    private InMemoryFileStorage storage;
    private PositionRepository positions;
    private AccountSnapshotFileRepository accounts;
    private TradingHistoryRepository history;
    private TradingAnchorRepository anchors;
    private TradingAppService service;

    @BeforeEach
    void setUp() {
        storage = new InMemoryFileStorage();
        positions = new PositionFileRepository(storage);
        accounts = new AccountSnapshotFileRepository(storage);
        history = new TradingHistoryFileRepository(storage);
        anchors = new TradingAnchorFileRepository(storage);
        TradingRuleSettingsRepository ruleRepo = mock(TradingRuleSettingsRepository.class);
        when(ruleRepo.findByUser(anyString())).thenReturn(TradingRuleSettings.defaults());
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.quote(any())).thenReturn(Map.of());
        service = new TradingAppService(positions, mock(RecordRepository.class), history,
                mock(WatchlistRepository.class), mock(SoldTradeRepository.class), accounts,
                mock(TransferRepository.class), market, mock(TradingLotService.class), ruleRepo, anchors);
    }

    /** 券商持仓快照（replace=true）：三只标的 + 现金快照（锚定日 = 文件自身日期 ANCHOR，不是导入日）。 */
    private void importBrokerSnapshot() {
        service.importPositions(USER, List.of(
                new TradingAppService.PositionImportItem("600206", "有研新材", 600, new BigDecimal("46.8091"),
                        null, null, null, null),
                new TradingAppService.PositionImportItem("603113", "金能科技", 1900, new BigDecimal("5.5739"),
                        null, null, null, null),
                new TradingAppService.PositionImportItem("002428", "云南锗业", 300, new BigDecimal("42.9111"),
                        null, null, null, null)), true, ANCHOR);
        service.importCashQuery(USER, """
                人民币: 余额:2278.16  可用:2278.16  可取:2278.16  参考市值:79079.00  资产:81357.16  盈亏:16423.25
                -------------------------------------------------------------------------------------------------------
                编号        证券代码        证券名称        证券数量        可卖数量        成本价          当前价          最新市值        今买数量        今卖数量        浮动盈亏        盈亏比例(%)        股东代码
                1           600206          有研新材        600.00          600.00          46.8091         46.4200         27852.00        0.00            0.00            -233.33         -0.831             A000000000
                """, ANCHOR);
    }

    private int qty(String symbol) {
        return positions.findAll(USER).stream().filter(p -> p.symbol().equals(symbol))
                .mapToInt(Position::quantity).findFirst().orElse(0);
    }

    private BigDecimal cash() {
        return accounts.findLatest(USER).map(AccountSnapshot::cash).orElse(BigDecimal.ZERO);
    }

    /** 通达信「历史成交查询」导出（空格对齐；列序与真实导出一致）。 */
    private static String tdx(LocalDate date, String time, String symbol, String name, String flag,
                              int volume, String price, String orderId) {
        String d = date.toString().replace("-", "");
        String amount = new BigDecimal(price).multiply(BigDecimal.valueOf(volume)).setScale(2).toPlainString();
        String occurred = flag.equals("买入") ? "-" + amount : amount;
        return "%s        %s         %s          %s        %s            %s.00          %s        %s         %s           %s                %s          A000000000        证券买入"
                .formatted(d, time, symbol, name, flag, volume, price, amount, "1" + volume, orderId, occurred);
    }

    private static String tdxFile(String... rows) {
        String header = "成交日期        成交时间        证券代码        证券名称        买卖标志        成交数量        成交价格        成交金额        委托编号        成交编号                发生金额          股东代码          备注";
        return header + "\n" + String.join("\n", rows) + "\n";
    }

    /** 与生产 20260911 导出同形的一批成交：锚定日及之前（已含在快照内）+ 锚定日之后（真增量）+ 一笔无法归属的卖出。 */
    private String prodShapedExport() {
        return tdxFile(
                // ≤ 锚定日：快照已含 → 只补流水（旧实现会把它们重放一遍 → 持仓/现金双计）
                tdx(ANCHOR.minusDays(4), "09:39:48", "002428", "云南锗业", "买入", 100, "92.31000000", "0102000011306054"),
                tdx(ANCHOR, "14:53:04", "603113", "金能科技", "买入", 1500, "5.57000000", "64052365"),
                tdx(ANCHOR, "14:53:50", "603113", "金能科技", "买入", 400, "5.57000000", "64328201"),
                tdx(ANCHOR, "14:53:21", "002428", "云南锗业", "卖出", 100, "91.41000000", "0105000058551565"),
                // > 锚定日：真增量 → 回放持仓/现金
                tdx(ANCHOR.plusDays(1), "09:42:24", "603113", "金能科技", "买入", 200, "5.59000000", "11656976"),
                tdx(ANCHOR.plusDays(1), "09:43:28", "603113", "金能科技", "买入", 100, "5.55000000", "12208429"),
                tdx(ANCHOR.plusDays(1), "09:51:00", "603113", "金能科技", "买入", 100, "5.53000000", "15847917"),
                tdx(ANCHOR.plusDays(2), "10:00:00", "600206", "有研新材", "买入", 100, "44.74000000", "21307464"),
                tdx(ANCHOR.plusDays(2), "10:28:50", "603113", "金能科技", "卖出", 2300, "5.08000000", "19561910"),
                // 无法归属：未持有的标的卖出（真实成交，必须留痕可见）
                tdx(ANCHOR.plusDays(2), "14:53:12", "000831", "中国稀土", "卖出", 800, "54.83000000", "0104000068320388"));
    }

    @Test
    void replay_withAnchor_appliesOnlyPostAnchorRowsAndKeepsUnattributableVisible() {
        importBrokerSnapshot();
        BigDecimal cashBefore = cash();

        TradingAppService.HistoricalTradeImportResult r =
                service.importHistoricalTrades(USER, prodShapedExport(), null, false);

        // ① ≤ 锚定日的成交只补流水：持仓数量只受「锚定日之后」影响
        //    603113：快照 1900 + 09-10 买 400 − 09-11 卖 2300 = 0（真实：09-11 全卖清仓）
        assertEquals(0, qty("603113"), "金能科技应已清仓（锚定后卖出 2300 股）");
        //    600206：快照 600 + 09-11 买 100 = 700
        assertEquals(700, qty("600206"), "有研新材应为 700 股（快照 600 + 锚定后买入 100）");
        //    002428：快照 300（锚定日那笔卖出已含在快照内，不得再扣）= 300
        assertEquals(300, qty("002428"), "云南锗业应保持 300 股（≤锚定日的卖出不得重复扣减）");

        // ② 现金只被锚定日之后的现金流影响（≤ 锚定日的成交不再双扣）
        BigDecimal expectedCash = cashBefore
                .subtract(com.adaiadai.core.domain.trading.CommissionCalculator.buyCost(
                        "603113", new BigDecimal("5.59"), 200))
                .subtract(com.adaiadai.core.domain.trading.CommissionCalculator.buyCost(
                        "603113", new BigDecimal("5.55"), 100))
                .subtract(com.adaiadai.core.domain.trading.CommissionCalculator.buyCost(
                        "603113", new BigDecimal("5.53"), 100))
                .add(com.adaiadai.core.domain.trading.CommissionCalculator.sellProceeds(
                        "603113", new BigDecimal("5.08"), 2300))
                .subtract(com.adaiadai.core.domain.trading.CommissionCalculator.buyCost(
                        "600206", new BigDecimal("44.74"), 100));
        assertEquals(0, cash().compareTo(expectedCash.setScale(2, java.math.RoundingMode.HALF_UP)),
                "现金应为「快照现金 + 锚定后现金流」，实际 " + cash() + " 期望 " + expectedCash);

        // ③ 无法归属的真实卖出：落流水 + rejected 可见
        assertEquals(1, r.rejected().size(), "未持有标的的卖出必须进 rejected");
        assertTrue(r.rejected().get(0).reason().contains("未持有"), r.rejected().get(0).reason());
        assertTrue(history.findAll(USER).stream()
                        .anyMatch(t -> t.symbol().equals("000831") && t.volume() == 800),
                "该笔必须已落逐笔流水（不再消失）");
        assertEquals("sync", r.syncMode());
    }

    @Test
    void replay_sameFileTwice_isIdempotent() {
        importBrokerSnapshot();
        service.importHistoricalTrades(USER, prodShapedExport(), null, false);
        int rowsAfterFirst = history.findAll(USER).size();
        BigDecimal cashAfterFirst = cash();

        TradingAppService.HistoricalTradeImportResult second =
                service.importHistoricalTrades(USER, prodShapedExport(), null, false);

        assertEquals(rowsAfterFirst, history.findAll(USER).size(), "重传同一文件不得新增流水");
        assertEquals(0, cash().compareTo(cashAfterFirst), "重传不得再动现金");
        assertEquals(0, second.imported(), "全部命中幂等");
    }

    /**
     * 2026-09-12 生产场景**逐数复刻**（合成数据、结构与股数一一对应）：09-09 快照 + 09-04~09-11 导出，
     * 验收期望 = 002428 400 / 600206 900 / 600601 100 / 603113 0 / 其余 0。
     * <p>
     * 与上一测试的区别：这里把 09-03/09-04/09-08 那三笔**曾被静默丢弃的真实卖出**（600487 400 股、
     * 000776 600 股、000831 800 股）一并放进文件——在锚定正确的前提下它们属于「≤ 锚定日」的补录，
     * 应当**正常入库且不参与持仓/现金运算**（不再是 rejected）；rejected 只用于锚点之后无法归属的成交。
     */
    @Test
    void prodScenarioMirror_positionsMatchBrokerAndNoRealFillIsLost() {
        importBrokerSnapshot();
        // 生产 09-09 快照另有 600601 900 股（本测试的券商快照先补齐它，再导成交）
        service.importPositions(USER, List.of(
                new TradingAppService.PositionImportItem("600206", "有研新材", 600, new BigDecimal("46.8091"),
                        null, null, null, null),
                new TradingAppService.PositionImportItem("600601", "方正科技", 900, new BigDecimal("12.3300"),
                        null, null, null, null),
                new TradingAppService.PositionImportItem("603113", "金能科技", 1900, new BigDecimal("5.5739"),
                        null, null, null, null),
                new TradingAppService.PositionImportItem("002428", "云南锗业", 300, new BigDecimal("42.9111"),
                        null, null, null, null)), true, ANCHOR);

        String file = tdxFile(
                // ── ≤ 锚定日（快照已含）：只补流水；其中 000487/000776/000831 三笔正是曾被丢掉的真实卖出 ──
                tdx(ANCHOR.minusDays(6), "14:55:24", "600487", "亨通光电", "卖出", 400, "65.31000000", "62621142"),
                tdx(ANCHOR.minusDays(5), "14:55:49", "000776", "广发证券", "卖出", 600, "22.19000000", "0104000075537936"),
                tdx(ANCHOR.minusDays(5), "14:56:00", "000831", "中国稀土", "买入", 100, "56.17000000", "0102000080344938"),
                tdx(ANCHOR.minusDays(1), "14:53:12", "000831", "中国稀土", "卖出", 800, "54.83000000", "0104000068320388"),
                tdx(ANCHOR.minusDays(1), "14:53:46", "600206", "有研新材", "买入", 400, "46.85000000", "65872510"),
                tdx(ANCHOR, "09:39:17", "600601", "方正科技", "买入", 100, "14.37000000", "8930160"),
                tdx(ANCHOR, "14:53:04", "603113", "金能科技", "买入", 1500, "5.57000000", "64052365"),
                tdx(ANCHOR, "14:53:21", "002428", "云南锗业", "卖出", 100, "91.41000000", "0105000058551565"),
                tdx(ANCHOR, "14:53:50", "603113", "金能科技", "买入", 400, "5.57000000", "64328201"),
                // ── > 锚定日：真增量 → 回放（09-10 与 09-11 的真实成交结构）──
                tdx(ANCHOR.plusDays(1), "09:42:24", "603113", "金能科技", "买入", 200, "5.59000000", "11656976"),
                tdx(ANCHOR.plusDays(1), "09:43:28", "603113", "金能科技", "买入", 100, "5.55000000", "12208429"),
                tdx(ANCHOR.plusDays(1), "09:51:00", "603113", "金能科技", "买入", 100, "5.53000000", "15847917"),
                tdx(ANCHOR.plusDays(2), "09:53:00", "603113", "金能科技", "卖出", 2300, "5.08000000", "19561910"),
                tdx(ANCHOR.plusDays(2), "09:56:50", "600206", "有研新材", "买入", 100, "44.74000000", "21307464"),
                tdx(ANCHOR.plusDays(2), "10:01:26", "600206", "有研新材", "买入", 100, "44.47000000", "23273206"),
                tdx(ANCHOR.plusDays(2), "10:06:17", "600601", "方正科技", "买入", 100, "14.71000000", "25376885"),
                tdx(ANCHOR.plusDays(2), "10:28:50", "600601", "方正科技", "卖出", 900, "14.53000000", "32872197"),
                tdx(ANCHOR.plusDays(2), "10:31:48", "002428", "云南锗业", "买入", 100, "86.27000000", "0102000027068612"),
                tdx(ANCHOR.plusDays(2), "10:36:04", "600206", "有研新材", "买入", 100, "43.95000000", "35131106"));

        TradingAppService.HistoricalTradeImportResult r =
                service.importHistoricalTrades(USER, file, null, false);

        // ① 持仓 = 券商真实（002428 400 / 600206 900 / 600601 100 / 603113 0）
        assertEquals(400, qty("002428"), "云南锗业应为 400 股（快照 300 + 09-11 买 100）");
        assertEquals(900, qty("600206"), "有研新材应为 900 股（快照 600 + 09-11 买 300）");
        assertEquals(100, qty("600601"), "方正科技应为 100 股（快照 900 + 买 100 − 卖 900）");
        assertEquals(0, qty("603113"), "金能科技 09-11 全卖 2300 股 → 应清仓");
        assertEquals(3, positions.findAll(USER).size(), "只应剩 3 只（金能科技已清仓、无幽灵持仓）");
        assertTrue(positions.findAll(USER).stream().map(Position::symbol).sorted().toList()
                        .equals(List.of("002428", "600206", "600601")),
                "持仓标的应为 002428/600206/600601，实际 " + positions.findAll(USER).stream()
                        .map(Position::symbol).toList());

        // ② 三笔曾被丢弃的真实卖出全部入库（补录，不动持仓/现金）
        for (String[] expect : List.of(new String[]{"600487", "400"}, new String[]{"000776", "600"},
                new String[]{"000831", "800"})) {
            assertTrue(history.findAll(USER).stream().anyMatch(t -> t.symbol().equals(expect[0])
                            && t.volume() == Integer.parseInt(expect[1]) && t.direction() != null
                            && t.entryDate() != null),
                    "真实成交必须留痕：" + expect[0] + " " + expect[1] + " 股");
        }
        assertEquals(19, history.findAll(USER).size(), "文件 19 行成交应逐笔入库（一笔不多一笔不少）");

        // ③ 没有无法归属的成交（锚定正确时补录行不参与持仓判定）
        assertTrue(r.rejected().isEmpty(), "锚定正确时不应有 rejected：" + r.rejected());

        // ④ 账实一致（派生 = 快照基线 + 锚点后流水）
        TradingAppService.IntegrityReport report = service.integrity(USER);
        assertTrue(report.drift().isEmpty(), "账实应一致：" + report.drift());
        assertTrue(report.gaps().isEmpty(), "不应有回放缺口：" + report.gaps());

        // ⑤ 现金 = 快照现金 + 锚定日之后现金流（补录行不得再动现金）
        BigDecimal flows = com.adaiadai.core.domain.trading.CommissionCalculator.buyCost(
                        "603113", new BigDecimal("5.59"), 200)
                .add(com.adaiadai.core.domain.trading.CommissionCalculator.buyCost(
                        "603113", new BigDecimal("5.55"), 100))
                .add(com.adaiadai.core.domain.trading.CommissionCalculator.buyCost(
                        "603113", new BigDecimal("5.53"), 100))
                .subtract(com.adaiadai.core.domain.trading.CommissionCalculator.sellProceeds(
                        "603113", new BigDecimal("5.08"), 2300))
                .add(com.adaiadai.core.domain.trading.CommissionCalculator.buyCost(
                        "600206", new BigDecimal("44.74"), 100))
                .add(com.adaiadai.core.domain.trading.CommissionCalculator.buyCost(
                        "600206", new BigDecimal("44.47"), 100))
                .add(com.adaiadai.core.domain.trading.CommissionCalculator.buyCost(
                        "600601", new BigDecimal("14.71"), 100))
                .subtract(com.adaiadai.core.domain.trading.CommissionCalculator.sellProceeds(
                        "600601", new BigDecimal("14.53"), 900))
                .add(com.adaiadai.core.domain.trading.CommissionCalculator.buyCost(
                        "002428", new BigDecimal("86.27"), 100))
                .add(com.adaiadai.core.domain.trading.CommissionCalculator.buyCost(
                        "600206", new BigDecimal("43.95"), 100));
        BigDecimal expected = new BigDecimal("2278.16").subtract(flows);
        assertEquals(0, cash().compareTo(expected.setScale(2, java.math.RoundingMode.HALF_UP)),
                "现金 = 快照现金 − 锚定后现金流；实际 " + cash() + " 期望 " + expected);

        // ⑥ 重传幂等：不新增流水、现金不变
        int rows = history.findAll(USER).size();
        service.importHistoricalTrades(USER, file, null, false);
        assertEquals(rows, history.findAll(USER).size(), "重传不得新增流水（含跨来源合并）");
        assertEquals(0, cash().compareTo(expected.setScale(2, java.math.RoundingMode.HALF_UP)), "重传不得动现金");
    }

    @Test
    void replay_withoutAnchorFile_isRejectedInsteadOfDoubleCounting() {
        importBrokerSnapshot();
        // 模拟生产事故：锚定文件缺失（升级前导过快照、没有锚定元信息）
        storage.write(USER, "trading/snapshot-anchor.json", "");
        assertEquals(SnapshotAnchor.empty(), anchors.find(USER));
        int rowsBefore = history.findAll(USER).size();

        TradingException ex = assertThrows(TradingException.class, () ->
                service.importHistoricalTrades(USER, prodShapedExport(), null, false));
        assertTrue(ex.getMessage().contains("锚定"), ex.getMessage());
        assertEquals(rowsBefore, history.findAll(USER).size(), "fail-closed：拒绝时不得落任何流水");
        assertEquals(600, qty("600206"), "持仓不得被静默重放改动");
    }

    @Test
    void replay_withoutAnchorFile_appendModeStillWorksWithoutTouchingPositions() {
        importBrokerSnapshot();
        storage.write(USER, "trading/snapshot-anchor.json", "");
        int qtyBefore = qty("600206");
        BigDecimal cashBefore = cash();

        TradingAppService.HistoricalTradeImportResult r = service.importHistoricalTrades(USER,
                prodShapedExport(), TradingAppService.ImportMode.APPEND, false);

        assertEquals("append", r.syncMode());
        assertTrue(r.imported() > 0);
        assertEquals(qtyBefore, qty("600206"), "仅补流水模式不得改持仓");
        assertEquals(0, cash().compareTo(cashBefore), "仅补流水模式不得改现金");
        assertTrue(history.findAll(USER).stream().anyMatch(t -> t.symbol().equals("603113")),
                "流水应已补齐（可回溯）");
    }

    @Test
    void dryRun_thenRealImport_planMatchesOutcome() {
        importBrokerSnapshot();
        String file = prodShapedExport();

        TradingAppService.HistoricalTradeImportResult plan =
                service.importHistoricalTrades(USER, file, null, true);
        assertEquals(0, history.findAll(USER).size(), "预检不得落任何流水");
        int plannedNew = plan.imported();
        int plannedReject = plan.rejected().size();
        assertTrue(plannedNew > 0);

        TradingAppService.HistoricalTradeImportResult real =
                service.importHistoricalTrades(USER, file, null, false);
        assertEquals(plannedNew, real.imported(), "实际新增应与预检一致");
        assertEquals(plannedReject, real.rejected().size(), "实际无法归属数与预检一致");
    }

    /**
     * 顺序不变式（RFC §9.2 R1）：**先成交后快照**（错序）不再静默错账 —— 快照以券商为准覆盖持仓，
     * 而流水里晚于锚定日的成交没被体现在持仓里 → 对账闸门必须报 drift（把顺序问题变成可见的「账实不符」）。
     * 正确顺序（先快照后成交）见 prodScenarioMirror，两者都在测试里锁住。
     */
    @Test
    void reverseOrder_tradesThenSnapshot_isCaughtByDriftGate() {
        // ① 先导成交（此时账户是空的 → 允许从零回放）
        service.importHistoricalTrades(USER, prodShapedExport(), null, false);
        // ② 再导券商快照（以券商为准全量覆盖；锚定日 = 快照自身日期）
        importBrokerSnapshot();

        TradingAppService.IntegrityReport report = service.integrity(USER);
        assertFalse(report.drift().isEmpty(),
                "错序导入后派生持仓必然与快照基线对不上 → 必须报「账实不符」，而不是安静地留着错账");
        assertTrue(report.note().contains("账实不符"), report.note());
    }

    @Test
    void integrity_afterCleanImport_isConsistent() {
        importBrokerSnapshot();
        service.importHistoricalTrades(USER, prodShapedExport(), null, false);

        TradingAppService.IntegrityReport report = service.integrity(USER);
        assertTrue(report.anchor().known());
        assertTrue(report.holdingsKnown(), "replace 导入应记录快照基线");
        assertTrue(report.drift().isEmpty(), "正常流程后账实应一致，实际差异：" + report.drift());
        assertEquals(1, report.gaps().size(), "唯一缺口 = 未持有标的的卖出（快照基线里本就没有该股）");
        assertTrue(report.gaps().get(0).symbol().equals("000831"));
        assertFalse(report.note().isBlank());
    }
}
