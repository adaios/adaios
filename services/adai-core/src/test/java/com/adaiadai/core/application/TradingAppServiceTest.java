package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradingException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TradingAppService — 交易业务规则测试（REVIEW #147）。
 */
class TradingAppServiceTest {

    private Position pos(String symbol, int qty) {
        return new Position(symbol, symbol + "名", qty, new BigDecimal("10.0"), new BigDecimal("10.5"),
                LocalDateTime.now());
    }

    @Test
    void recordTrade_sellUnheld_throwsNotSilent() {
        // #147：SELL 未持有 symbol 必须报错，不再静默 no-op
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        TradingAppService service = new TradingAppService(repo);

        assertThrows(TradingException.class, () ->
                service.recordTrade("default", "600000", "浦发银行", TradeDirection.SELL,
                        new BigDecimal("10.5"), 100));
    }

    @Test
    void recordTrade_sellOverHeld_throws() {
        // #147：卖出数量超过持仓 → 报错，防静默清仓失真
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of(pos("600000", 100)));
        TradingAppService service = new TradingAppService(repo);

        assertThrows(TradingException.class, () ->
                service.recordTrade("default", "600000", "浦发银行", TradeDirection.SELL,
                        new BigDecimal("10.5"), 200));
    }

    @Test
    void recordTrade_fullSell_clearsPosition() {
        // 恰好全部卖出 → 清仓，0 持仓行不落盘
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of(pos("600000", 100)));
        TradingAppService service = new TradingAppService(repo);

        service.recordTrade("default", "600000", "浦发银行", TradeDirection.SELL,
                new BigDecimal("10.5"), 100);

        ArgumentCaptor<List<Position>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(any(), captor.capture());
        assertTrue(captor.getValue().isEmpty(), "清仓后不应残留 0 持仓行");
    }

    @Test
    void recordTrade_buyNew_createsPosition() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        TradingAppService service = new TradingAppService(repo);

        List<Position> result = service.recordTrade("default", "600000", "浦发银行", TradeDirection.BUY,
                new BigDecimal("10.5"), 100);

        assertTrue(result.size() == 1 && result.get(0).symbol().equals("600000"),
                "首次买入应新建持仓");
    }
}
