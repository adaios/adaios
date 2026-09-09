package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.AccountSnapshotRepository;
import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.SoldTrade;
import com.adaiadai.core.domain.trading.SoldTradeRepository;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradingRuleSettings;
import com.adaiadai.core.domain.trading.WatchlistRepository;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.PositionFileRepository;
import com.adaiadai.core.infrastructure.storage.SoldTradeFileRepository;
import com.adaiadai.core.infrastructure.storage.TradingAnchorFileRepository;
import com.adaiadai.core.infrastructure.storage.TradingHistoryFileRepository;
import com.adaiadai.core.infrastructure.storage.TradingRuleSettingsRepository;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RFC 20260909 批 1 触发接线集成测试（真实文件仓储 + 真实 ClearanceDetector）：
 * 手动/确认成交把某股卖到 0 → recordTradeInternal 尾部触发清仓推导 → sold.json 自动收录 flow 行。
 * 覆盖「只填空白 + 幂等」链路在真实仓储下的行为（与 ClearanceDetectorTest 的单测互补）。
 */
class TradingClearanceTriggerTest {

    private static final String USER = "default";

    /** 真实文件仓储装配（锚定 + 清仓推导均启用，等价生产 Spring 接线）。 */
    private TradingAppService realService(InMemoryFileStorage fs) {
        PositionFileRepository positions = new PositionFileRepository(fs);
        TradingHistoryFileRepository history = new TradingHistoryFileRepository(fs);
        SoldTradeRepository sold = new SoldTradeFileRepository(fs);
        TradingRuleSettingsRepository rules = mock(TradingRuleSettingsRepository.class);
        when(rules.findByUser(anyString())).thenReturn(TradingRuleSettings.defaults());
        ClearanceDetector detector = new ClearanceDetector(history, positions, sold, rules);
        return new TradingAppService(positions, mock(RecordRepository.class), history,
                mock(WatchlistRepository.class), sold,
                mock(AccountSnapshotRepository.class), mock(com.adaiadai.core.domain.trading.TransferRepository.class),
                mock(com.adaiadai.core.domain.trading.market.MarketDataSource.class),
                mock(TradingLotService.class), rules,
                new TradingAnchorFileRepository(fs), detector);
    }

    @Test
    void recordTrade_sellToZero_recordsFlowSoldRow() {
        InMemoryFileStorage fs = new InMemoryFileStorage();
        TradingAppService service = realService(fs);
        PositionRepository positions = new PositionFileRepository(fs);
        SoldTradeRepository sold = new SoldTradeFileRepository(fs);

        // 建仓（流水留 BUY）→ 卖光（触发清仓推导）——买入/卖出均在流水内 = 完整可推导
        service.recordTrade(USER, "600000", "浦发银行", TradeDirection.BUY,
                new BigDecimal("10.00"), 100, LocalDate.of(2026, 9, 1), null,
                null, null, null, null);
        service.recordTrade(USER, "600000", "浦发银行", TradeDirection.SELL,
                new BigDecimal("12.00"), 100, LocalDate.of(2026, 9, 3), null,
                null, null, null, null);

        assertEquals(0, positions.findAll(USER).size(), "卖光后持仓清空");
        List<SoldTrade> soldList = sold.findAll(USER);
        assertEquals(1, soldList.size(), "清仓自动收录进 sold.json");
        SoldTrade s = soldList.get(0);
        assertEquals("600000", s.symbol());
        assertEquals("flow", s.provenance(), "自动收录来源标记 flow");
        assertEquals(LocalDate.of(2026, 9, 1), s.buyDate());
        assertEquals(LocalDate.of(2026, 9, 3), s.sellDate());
        assertEquals(2, Integer.parseInt(s.tradeCount()));

        // 幂等：再卖一次空仓会报错不触发；重复对已收录 symbol 的推导不翻倍（仓储只填空白）
        // 直接验证 detector 幂等（前序 ClearanceDetectorTest 已覆盖），此处补查真实仓储 upsert 语义：
        sold.upsertFromFlow(USER, new SoldTrade("600000", "浦发银行",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3), 3, "2", 20.0, "", "", "flow"));
        assertEquals(1, sold.findAll(USER).size(), "upsertFromFlow 已存在不重复追加");
    }
}
