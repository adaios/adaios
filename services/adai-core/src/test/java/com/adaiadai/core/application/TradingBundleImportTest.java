package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.AccountSnapshotRepository;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.TradingException;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.TransferRepository;
import com.adaiadai.core.domain.trading.WatchlistRepository;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * importBundle — 统一入口（R-12「一次把导出的文件交给它就行」· 2026-10-06 ingest 批）编排测试。
 * <p>
 * 钉住四条编排契约：① 内部排序 = <b>快照在前、流水在后</b>（用户 2026-10-05 拍板，偏离设计 §3① 字面，
 * 依据见 {@code ImportKind} javadoc）；② 一份失败不影响其他份；③ 认不出的文件先留存、后如实拒绝；
 * ④ dryRun 只预检、不落盘不留存；⑤ 持仓品种门（账只接主板）+ 非主板存量保留。
 */
class TradingBundleImportTest {

    /** 统一入口测试的 service（11 参构造：1970 已知锚定 → 不触发防重误伤；对账调整仓储缺省 = null）。 */
    private TradingAppService service(PositionRepository repo, TradingHistoryRepository history,
                                      WatchlistRepository watchlist, AccountSnapshotRepository accounts,
                                      TransferRepository transfers) {
        return new TradingAppService(repo, mock(RecordRepository.class), history,
                watchlist, mock(com.adaiadai.core.domain.trading.SoldTradeRepository.class), accounts, transfers,
                mock(MarketDataSource.class), mock(TradingLotService.class),
                TradingAppServiceTest.defaultRuleRepo(), TradingAppServiceTest.knownAnchorRepo());
    }

    private static TradingAppService.BundleFileInput file(String name, String content) {
        return new TradingAppService.BundleFileInput(name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static final String WATCHLIST_CONTENT = "代码\t名称\t细分行业\t一二级行业\t长期形态\t中期形态\t短期形态\t近日指标提示\n"
            + "000725\t京东方Ａ\t元器件\t信息产业-元器件\t6\t8\t1\tKDJ死叉\n"
            + "600487\t亨通光电\t通信设备\t信息产业-通信设备\t8\t10\t13\tKDJ金叉\n";

    @Test
    void importBundle_sortsSnapshotsBeforeTrades() {
        // 输入顺序故意反着给（成交 → 持仓 → 资金）：处理顺序必须按 kind.order 排成 资金(10) → 持仓(20) → 成交(30)。
        // 若顺序反了（先补流水、后导快照），流水会被 auto 模式当「需回放」→ 与快照双计（routine.md 三次事故形态）。
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        TradingHistoryRepository history = mock(TradingHistoryRepository.class);
        when(history.findAll(any())).thenReturn(List.of());
        TransferRepository transfers = mock(TransferRepository.class);
        when(transfers.findAll(any())).thenReturn(List.of());
        AccountSnapshotRepository accounts = mock(AccountSnapshotRepository.class);
        when(accounts.findLatest(any())).thenReturn(Optional.empty());
        TradingAppService service = service(repo, history, mock(WatchlistRepository.class), accounts, transfers);

        String trades = "成交日期\t证券代码\t买卖标志\t成交数量\t成交价格\t成交编号\n"
                + "20261006\t600206\t买入\t100\t50.00\t1\n";
        String positions = "证券代码\t证券名称\t证券数量\t成本价\t当日盈亏\n"
                + "600206\t有研新材\t900\t46.012\t100.00\n";
        String cash = "人民币: 余额:292.88  可用:292.88  可取:292.88  参考市值:110212.00  资产:110504.88  盈亏:15235.55\n"
                + "编号 证券代码 证券名称 证券数量 成本价 当前价 浮动盈亏\n"
                + "1 600206 有研新材 900 46.012 50.78 100.0\n";

        TradingAppService.BundleImportResult result = service.importBundle("default", List.of(
                        file("20261006000001_历史成交20261006.txt", trades),
                        file("20261006000002_持仓股20261006.txt", positions),
                        file("20261006000003_资金股份20261006.txt", cash)),
                TradingAppService.ImportMode.AUTO, true);

        assertEquals(List.of("cash", "positions", "trades"),
                result.files().stream().map(TradingAppService.BundleFileResult::kind).toList(),
                "处理顺序必须快照在前、流水在后——与用户选择顺序无关");
        assertEquals(3, result.okCount());
        assertEquals(0, result.failedCount());
        assertTrue(result.dryRun());
    }

    @Test
    void importBundle_oneBadFileDoesNotStopOthers_andUnknownIsKept() {
        // 一份认不出的文件（乱文）与一份好文件混在一起：好文件照常导入，坏文件如实拒绝，
        // 且坏文件也**先留存**（U6 原始文件长期留存——不丢原始文件，但也绝不当成任何一类静默入库）。
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        WatchlistRepository watchlist = mock(WatchlistRepository.class);
        when(watchlist.findAll(any())).thenReturn(new ArrayList<>());
        TradingAppService service = service(repo, mock(TradingHistoryRepository.class), watchlist,
                mock(AccountSnapshotRepository.class), mock(TransferRepository.class));

        String garbage = "交易日期 摘要 发生额 余额\n20261006 转账 500.00 3000.00\n";
        TradingAppService.BundleImportResult result = service.importBundle("default", List.of(
                        file("银行流水.txt", garbage),
                        file("20261006000000_自选股20261006.txt", WATCHLIST_CONTENT)),
                TradingAppService.ImportMode.AUTO, false);

        assertEquals(2, result.files().size(), "两份都要出现在回执里——谁也不静默消失");
        assertEquals(1, result.okCount());
        assertEquals(1, result.failedCount());
        // 排序：自选(50) 在 未知(90) 之前
        assertEquals("watchlist", result.files().get(0).kind());
        assertTrue(result.files().get(0).ok());
        assertEquals("unknown", result.files().get(1).kind());
        assertFalse(result.files().get(1).ok());
        assertTrue(result.files().get(1).error().contains("没认出"),
                "认不出必须如实拒绝并说清支持哪几类：" + result.files().get(1).error());
        // 两份都被留存（留存发生在识别之前——认不出也要留原件）
        verify(repo, times(2)).saveImportFile(any(), any(), any());
        verify(watchlist).saveAll(any(), any());
    }

    @Test
    void importBundle_emptyBytes_failsVisibleNotSilently() {
        // 空文件 / 读取失败的字节（controller 读失败以 null bytes 进来）：必须出现在逐份回执里，
        // 不能让「这份没处理」静默消失（无痕导入）。空字节不产生留存文件（没有字节可留）。
        PositionRepository repo = mock(PositionRepository.class);
        TradingAppService service = service(repo, mock(TradingHistoryRepository.class),
                mock(WatchlistRepository.class), mock(AccountSnapshotRepository.class),
                mock(TransferRepository.class));

        TradingAppService.BundleImportResult result = service.importBundle("default", List.of(
                        new TradingAppService.BundleFileInput("空文件.txt", new byte[0])),
                TradingAppService.ImportMode.AUTO, false);

        assertEquals(1, result.failedCount());
        assertEquals("unknown", result.files().get(0).kind());
        assertTrue(result.files().get(0).error().contains("空的"),
                "要如实说清为什么跳过：" + result.files().get(0).error());
        verify(repo, never()).saveImportFile(any(), any(), any());
    }

    @Test
    void importBundle_dryRun_persistsNothing() {
        // 预检语义（改账与花钱同等待遇）：dryRun 只识别 + 只报「会做什么」——不落盘、不留存。
        PositionRepository repo = mock(PositionRepository.class);
        WatchlistRepository watchlist = mock(WatchlistRepository.class);
        TradingAppService service = service(repo, mock(TradingHistoryRepository.class), watchlist,
                mock(AccountSnapshotRepository.class), mock(TransferRepository.class));

        TradingAppService.BundleImportResult result = service.importBundle("default", List.of(
                        file("20261006000000_自选股20261006.txt", WATCHLIST_CONTENT)),
                TradingAppService.ImportMode.AUTO, true);

        assertEquals(1, result.okCount());
        assertTrue(result.dryRun());
        TradingAppService.BundleFileResult f = result.files().get(0);
        assertEquals("watchlist", f.kind());
        assertEquals(Boolean.TRUE, f.detail().get("dryRun"));
        assertEquals(2, f.detail().get("wouldImport"), "预检要报「会导入几只」");
        verify(repo, never()).saveImportFile(any(), any(), any());
        verify(watchlist, never()).saveAll(any(), any());
    }

    @Test
    void importBundle_positionsGateOnlyMainboard_andKeepsExistingNonMainboard() {
        // 设计 §11.2「限制只在账」+ 非主板存量保留（本导入是 replace 全量覆盖）：
        // 文件里 600206 入账；688981（科创，系统已持有 300 股）按系统现值保留——账不收新，但也不能被 replace 删掉；
        // 512690（ETF，系统没有）如实报「未入账」；830799（系统持有但文件里没有）按「以文件为准」移除。
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of(
                new Position("688981", "中芯国际", 300, new BigDecimal("50.0"), new BigDecimal("60.0"),
                        LocalDateTime.now(), LocalDate.of(2026, 1, 1),
                        new BigDecimal("45.0"), "支撑位", "核心"),
                new Position("830799", "北交所示例", 500, new BigDecimal("10.0"), new BigDecimal("11.0"),
                        LocalDateTime.now())));
        AccountSnapshotRepository accounts = mock(AccountSnapshotRepository.class);
        when(accounts.findLatest(any())).thenReturn(Optional.empty());
        TradingAppService service = service(repo, mock(TradingHistoryRepository.class),
                mock(WatchlistRepository.class), accounts, mock(TransferRepository.class));

        String content = "证券代码\t证券名称\t证券数量\t成本价\t当日盈亏\n"
                + "600206\t有研新材\t900\t46.012\t100.00\n"
                + "688981\t中芯国际\t200\t55.0\t0.00\n"
                + "512690\t酒ETF\t1000\t1.5\t0.00\n";
        TradingAppService.BundleImportResult result = service.importBundle("default", List.of(
                        file("20261006000000_持仓股20261006.txt", content)),
                TradingAppService.ImportMode.AUTO, false);

        TradingAppService.BundleFileResult f = result.files().get(0);
        assertTrue(f.ok(), "含非主板行只影响该行，不整份拒绝：" + f.error());
        assertEquals("positions", f.kind());
        assertEquals(2, f.detail().get("imported"), "入账 = 600206 + 系统已有的 688981（按现值保留）");
        String unsupported = String.valueOf(f.detail().get("unsupported"));
        assertTrue(unsupported.contains("512690"), "系统没有的 ETF 要如实报「未入账」：" + unsupported);
        assertFalse(unsupported.contains("688981"), "已持有的并进 preserved，不重复说");
        assertTrue(String.valueOf(f.detail().get("preserved")).contains("688981"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Position>> cap = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(any(), cap.capture());
        List<Position> saved = cap.getValue();
        assertEquals(2, saved.size());
        Position kept = saved.stream().filter(p -> p.symbol().equals("688981")).findFirst().orElseThrow();
        assertEquals(300, kept.quantity(), "688981 用系统现值保留——不是文件里的 200 股");
        assertTrue(saved.stream().noneMatch(p -> p.symbol().equals("830799")),
                "文件里没有的非主板持仓仍按「以文件为准」移除");
        assertTrue(saved.stream().noneMatch(p -> p.symbol().equals("512690")),
                "账只接主板——ETF 不入账");
    }

    @Test
    void importBundle_noFiles_throwsHumanMessage() {
        TradingAppService service = service(mock(PositionRepository.class), mock(TradingHistoryRepository.class),
                mock(WatchlistRepository.class), mock(AccountSnapshotRepository.class),
                mock(TransferRepository.class));
        TradingException ex = assertThrows(TradingException.class,
                () -> service.importBundle("default", List.of(), TradingAppService.ImportMode.AUTO, false));
        assertTrue(ex.getMessage().contains("没有收到文件"), ex.getMessage());
    }
}
