package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.AccountSnapshotRepository;
import com.adaiadai.core.domain.trading.AnchorBasis;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.SnapshotAnchor;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.domain.trading.TradingAnchorRepository;
import com.adaiadai.core.domain.trading.TradingHistoryRepository;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.TransferRepository;
import com.adaiadai.core.domain.trading.WatchlistRepository;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import com.adaiadai.core.infrastructure.storage.TradingRuleSettingsRepository;
import com.adaiadai.core.kernel.record.RecordRepository;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TradingAnchorBasisTest — 锚定日**依据显式化**（2026-10-05，P2-交易84）。
 *
 * <p><b>病根</b>：锚定日归一化用「导入时刻」猜「数据基准日」——09:26 导出、09:28 导入会退到
 * 上一交易日（{@code LocalTime.now() < 09:30}），而与快照双计；未来日期已于 09-21 按「无文件日期」
 * 处理，但**基准仍靠时钟推断**、且「据」没有随锚定落盘。
 *
 * <p><b>修法</b>：导入可带**显式数据基准日**（{@code basedOn}）——有据就用据（不再被时钟改写）；
 * 时钟推断只作**最后兜底**，且兜底必须标「无据」（{@link AnchorBasis#CLOCK}），
 * 依据随锚定落盘并进入既有对账回执（{@code normalizedWithEvidence} 链路）。
 *
 * <p><b>不放松的既有保护</b>：① 未来日期仍不可信（显式基准日在未来 → 忽略 + 如实说明）；
 * ② 补导历史快照仍按文件日期；③ 休市日归一化仍是「有据」（b90f56a2 口径，本类只做回归）。
 */
class TradingAnchorBasisTest {

    private static final String USER = "default";

    private static final LocalDate FRIDAY = LocalDate.of(2026, 9, 18);          // 交易日（周五）
    private static final LocalDate SATURDAY = LocalDate.of(2026, 9, 19);        // 非交易日
    private static final LocalDate PREV_TRADING_DAY = LocalDate.of(2026, 9, 17);
    /** 09:26 导出、09:28 导入——旧实现正是被这个时刻退到上一交易日。 */
    private static final LocalTime EXPORT_THEN_IMPORT = LocalTime.of(9, 28);

    // ── ① 核心：有据（显式基准日）时，导入时刻不再改写基准日 ──

    /**
     * **本条病根的核心断言**：09:26 导出、09:28 导入，用户/前端说清「这份快照就是 09-18 的」
     * → 锚定日必须是 09-18，**不得**因为「导入时刻 &lt; 09:30」被退到上一交易日 09-17。
     */
    @Test
    void explicitBasis_beforeOpenOnTradingDay_doesNotFallBackToPreviousTradingDay() {
        TradingAppService.AnchorDecision d =
                TradingAppService.decideAnchor(FRIDAY, FRIDAY, EXPORT_THEN_IMPORT, FRIDAY);

        assertEquals(FRIDAY, d.anchorDate(),
                "有据（显式基准日）时不得用导入时刻把锚定日退到上一交易日");
        assertEquals(AnchorBasis.EXPLICIT, d.basis());
        assertTrue(d.withEvidence(), "显式基准日 = 有据");
        assertFalse(d.explicitRejected());
    }

    /** 显式基准日优先于文件日期（用户说得比文件名准）：「导出日是 09-18，但数据是 09-17 的」。 */
    @Test
    void explicitBasis_winsOverFileDate() {
        TradingAppService.AnchorDecision d =
                TradingAppService.decideAnchor(FRIDAY, FRIDAY, LocalTime.of(16, 0), PREV_TRADING_DAY);

        assertEquals(PREV_TRADING_DAY, d.anchorDate());
        assertEquals(FRIDAY, d.fileDate(), "文件日期仍如实保留（可追溯）");
        assertEquals(AnchorBasis.EXPLICIT, d.basis());
        assertTrue(d.withEvidence());
    }

    // ── ② 无据时仍按既有兜底，且必须标「无据」 ──

    /** 没有显式基准日 → 既有归一化（盘前退上一交易日）**不回归**，但依据必须如实标「无据」。 */
    @Test
    void noExplicitBasis_beforeOpen_stillFallsBackAndIsMarkedNoEvidence() {
        TradingAppService.AnchorDecision d =
                TradingAppService.decideAnchor(FRIDAY, FRIDAY, EXPORT_THEN_IMPORT, null);

        assertEquals(PREV_TRADING_DAY, d.anchorDate(), "无据时既有兜底不变（防重不能失效）");
        assertEquals(AnchorBasis.CLOCK, d.basis());
        assertFalse(d.withEvidence(), "时钟推断 = 无据，不许冒充确定");
        assertTrue(d.describe().contains("无据"), d.describe());
        assertTrue(d.describe().contains("按导入时间推断"), d.describe());
    }

    /** 粘贴导入（没有文件日期）且没有显式基准日 → 同一套时钟兜底，同样标「无据」。 */
    @Test
    void nullFileDate_withoutExplicitBasis_isClockNoEvidence() {
        TradingAppService.AnchorDecision d =
                TradingAppService.decideAnchor(null, FRIDAY, LocalTime.of(0, 24), null);

        assertEquals(PREV_TRADING_DAY, d.anchorDate());
        assertEquals(AnchorBasis.CLOCK, d.basis());
        assertFalse(d.withEvidence());
    }

    /** 盘后导入 + 文件日期就是今天 → 文件本身就是证据（有据，不必归因给时钟）。 */
    @Test
    void fileDateEqualsAnchor_isMarkedAsFileDateEvidence() {
        TradingAppService.AnchorDecision d =
                TradingAppService.decideAnchor(FRIDAY, FRIDAY, LocalTime.of(16, 0), null);

        assertEquals(FRIDAY, d.anchorDate());
        assertEquals(AnchorBasis.FILE_DATE, d.basis());
        assertTrue(d.withEvidence());
    }

    /** 休市日导出 → 基准日取上一交易日是确定的（b90f56a2 口径不回归：仍算有据、不报警）。 */
    @Test
    void holidayFileDate_normalization_isMarkedWithEvidence() {
        TradingAppService.AnchorDecision d =
                TradingAppService.decideAnchor(SATURDAY, SATURDAY, LocalTime.of(10, 0), null);

        assertEquals(FRIDAY, d.anchorDate());
        assertEquals(AnchorBasis.CLOSED_DAY, d.basis());
        assertTrue(d.withEvidence());
    }

    // ── ③ 未来日期仍不可信（P2-10 保护不放松） ──

    /** 显式基准日在未来（文件名解析错 / 手改错）→ 忽略，落回既有兜底，且标「无据」+ 如实说明被忽略。 */
    @Test
    void explicitBasis_inFuture_isUntrusted() {
        LocalDate future = FRIDAY.plusDays(30);
        TradingAppService.AnchorDecision d =
                TradingAppService.decideAnchor(FRIDAY, FRIDAY, LocalTime.of(0, 24), future);

        assertEquals(PREV_TRADING_DAY, d.anchorDate(), "未来日期绝不能被写成锚定日");
        assertEquals(AnchorBasis.CLOCK, d.basis());
        assertFalse(d.withEvidence(), "被忽略的显式基准日不得算作「有据」");
        assertTrue(d.explicitRejected());
        assertTrue(d.describe().contains("在未来，不可信"), d.describe());
    }

    /** 未来日期 + 盘后 → 兜底到「今天」→ 退回**文件日期**这条有据的路径（文件本身就是证据），
     *  但「显式基准日在未来」必须如实标出被忽略，绝不静默采用。 */
    @Test
    void explicitBasis_inFuture_afterOpen_fallsBackToToday() {
        TradingAppService.AnchorDecision d =
                TradingAppService.decideAnchor(FRIDAY, FRIDAY, LocalTime.of(16, 0), FRIDAY.plusDays(30));

        assertEquals(FRIDAY, d.anchorDate(), "绝不能把锚定日写进未来");
        assertTrue(d.explicitRejected(), "被忽略的显式基准日必须如实说明");
        assertEquals(AnchorBasis.FILE_DATE, d.basis(), "退回文件日期（本身就是证据，不是猜）");
        assertTrue(d.describe().contains("在未来，不可信"), d.describe());
    }

    // ── ④ 服务层：依据随锚定落盘、并进入回执 ──

    private TradingAppService service(PositionRepository repo, TradingAnchorRepository anchor) {
        TradingRuleSettingsRepository ruleRepo = mock(TradingRuleSettingsRepository.class);
        when(ruleRepo.findByUser(anyString())).thenReturn(TradingRuleSettings.defaults());
        return new TradingAppService(repo, mock(RecordRepository.class),
                mock(TradingHistoryRepository.class), mock(WatchlistRepository.class),
                mock(SoldTradeRepository.class), mock(AccountSnapshotRepository.class),
                mock(TransferRepository.class), mock(MarketDataSource.class),
                mock(TradingLotService.class), ruleRepo, anchor);
    }

    private TradingAppService.PositionImportItem item() {
        return new TradingAppService.PositionImportItem("600206", "有研新材", 100,
                new BigDecimal("46.0"), null, null, null, null);
    }

    /** 显式基准日随锚定一起落盘（repository 收到 EXPLICIT），且导入回执带出「有据」的依据。 */
    @Test
    void importPositions_landsExplicitBasisAndReturnsReceipt() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of());
        TradingAnchorRepository anchor = mock(TradingAnchorRepository.class);
        when(anchor.find(anyString())).thenReturn(SnapshotAnchor.empty());
        TradingAppService service = service(repo, anchor);

        LocalDate today = LocalDate.now();
        TradingAppService.PositionImportResult r =
                service.importPositions(USER, List.of(item()), true, today, null, today);

        verify(anchor).updatePositionsReplace(eq(USER), eq(today), eq(today), eq(AnchorBasis.EXPLICIT));
        assertNotNull(r.anchor(), "replace=true 的导入必须回执锚定依据");
        assertEquals(AnchorBasis.EXPLICIT, r.anchor().basis());
        assertTrue(r.anchor().withEvidence());
        assertTrue(r.anchor().describe().contains("有据"), r.anchor().describe());
    }

    /** 无显式基准日 + 补导历史快照 → 按文件日期（有据），回执如实说「按快照文件里的日期」。 */
    @Test
    void importPositions_backdatedSnapshot_landsFileDateBasis() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of());
        TradingAnchorRepository anchor = mock(TradingAnchorRepository.class);
        when(anchor.find(anyString())).thenReturn(SnapshotAnchor.empty());
        TradingAppService service = service(repo, anchor);

        LocalDate oldFileDate = LocalDate.now().minusDays(5);
        TradingAppService.PositionImportResult r =
                service.importPositions(USER, List.of(item()), true, oldFileDate, null, null);

        verify(anchor).updatePositionsReplace(eq(USER), eq(oldFileDate), eq(oldFileDate),
                eq(AnchorBasis.FILE_DATE));
        assertNotNull(r.anchor());
        assertEquals(AnchorBasis.FILE_DATE, r.anchor().basis());
    }

    /** 非 replace 导入不动锚定 → 不编造依据（回执 anchor 为 null，字段如实缺省）。 */
    @Test
    void importPositions_withoutReplace_hasNoAnchorReceipt() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(anyString())).thenReturn(List.of());
        TradingAnchorRepository anchor = mock(TradingAnchorRepository.class);
        when(anchor.find(anyString())).thenReturn(SnapshotAnchor.empty());
        TradingAppService service = service(repo, anchor);

        TradingAppService.PositionImportResult r =
                service.importPositions(USER, List.of(item()), false, LocalDate.now(), null, null);

        assertNull(r.anchor(), "非全量导入不建立锚定，不得凭空给依据");
    }
}
