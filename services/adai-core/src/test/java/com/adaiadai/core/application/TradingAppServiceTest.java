package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.Position;
import com.adaiadai.core.domain.trading.PositionRepository;
import com.adaiadai.core.domain.trading.TradeDirection;
import com.adaiadai.core.domain.trading.TradingException;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TradingAppService — 交易业务规则测试（REVIEW #147 + RFC 20260815 闭环）。
 */
class TradingAppServiceTest {

    private Position pos(String symbol, int qty) {
        return new Position(symbol, symbol + "名", qty, new BigDecimal("10.0"), new BigDecimal("10.5"),
                LocalDateTime.now());
    }

    private TradingAppService service(PositionRepository repo, RecordRepository records) {
        return new TradingAppService(repo, records);
    }

    @Test
    void recordTrade_sellUnheld_throwsNotSilent() {
        // #147：SELL 未持有 symbol 必须报错，不再静默 no-op
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        TradingAppService service = service(repo, mock(RecordRepository.class));

        assertThrows(TradingException.class, () ->
                service.recordTrade("default", "600000", "浦发银行", TradeDirection.SELL,
                        new BigDecimal("10.5"), 100));
    }

    @Test
    void recordTrade_sellOverHeld_throws() {
        // #147：卖出数量超过持仓 → 报错，防静默清仓失真
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of(pos("600000", 100)));
        TradingAppService service = service(repo, mock(RecordRepository.class));

        assertThrows(TradingException.class, () ->
                service.recordTrade("default", "600000", "浦发银行", TradeDirection.SELL,
                        new BigDecimal("10.5"), 200));
    }

    @Test
    void recordTrade_fullSell_clearsPosition() {
        // 恰好全部卖出 → 清仓，0 持仓行不落盘
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of(pos("600000", 100)));
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(List.of());
        TradingAppService service = service(repo, records);

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
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(List.of());
        TradingAppService service = service(repo, records);

        List<Position> result = service.recordTrade("default", "600000", "浦发银行", TradeDirection.BUY,
                new BigDecimal("10.5"), 100);

        assertTrue(result.size() == 1 && result.get(0).symbol().equals("600000"),
                "首次买入应新建持仓");
    }

    // ── RFC 20260815：name 可空兜底 ──

    @Test
    void recordTrade_buyWithoutName_usesSymbolAsName() {
        // name 可空：缺名时以 symbol 兜底（写入持仓与交易记录都使用兜底名）
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(List.of());
        TradingAppService service = service(repo, records);

        List<Position> result = service.recordTrade("default", "600000", null, TradeDirection.BUY,
                new BigDecimal("10.5"), 100);

        assertEquals("600000", result.get(0).name(), "缺名时以 symbol 兜底为名称");
        ArgumentCaptor<List<Position>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(any(), captor.capture());
        assertEquals("600000", captor.getValue().get(0).name(), "落盘持仓也应为兜底名");
    }

    @Test
    void recordTrade_blankName_usesSymbolAsName() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(List.of());
        TradingAppService service = service(repo, records);

        List<Position> result = service.recordTrade("default", "600000", "  ", TradeDirection.BUY,
                new BigDecimal("10.5"), 100);

        assertEquals("600000", result.get(0).name(), "空白名也以 symbol 兜底");
    }

    // ── RFC 20260815：recordTrade 成功写 domain=trading 记录（复盘提醒闭环） ──

    @Test
    void recordTrade_success_writesTradingRecord() {
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(List.of());
        TradingAppService service = service(repo, records);

        service.recordTrade("default", "600000", "浦发银行", TradeDirection.BUY,
                new BigDecimal("10.5"), 100);

        ArgumentCaptor<ContentRecord> captor = ArgumentCaptor.forClass(ContentRecord.class);
        verify(records).save(any(), captor.capture());
        ContentRecord r = captor.getValue();
        assertEquals("trading", r.domain(), "记录 domain 应为 trading（复盘关键词检测 + 时间线归类）");
        assertEquals("trade", r.type());
        assertEquals("auto_collect", r.source());
        assertTrue(r.title().contains("买入"), "标题应含方向");
        assertTrue(r.title().contains("浦发银行") && r.title().contains("100股") && r.title().contains("@10.5"),
                "标题应符合「买入 名称 数量股@价格」格式，实际: " + r.title());
        assertTrue(r.tags().contains("trading"), "记录应带 trading 标签");
    }

    @Test
    void recordTrade_failure_doesNotWriteRecord() {
        // recordTrade 失败（SELL 未持有 → TradingException）时不应留下交易记录
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        RecordRepository records = mock(RecordRepository.class);
        TradingAppService service = service(repo, records);

        assertThrows(TradingException.class, () ->
                service.recordTrade("default", "600000", "浦发银行", TradeDirection.SELL,
                        new BigDecimal("10.5"), 100));

        verify(records, never()).save(any(), any());
    }

    @Test
    void recordTrade_retryWithinWindow_doesNotDuplicateRecord() {
        // 幂等：窗口内已存在同标题记录（重试）→ 不重复写，防重复进时间线/复盘提醒
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        RecordRepository records = mock(RecordRepository.class);
        ContentRecord existing = new ContentRecord(
                "rec_old", "trade", "auto_collect",
                "买入 浦发银行 100股@10.5",
                "买入 浦发银行（600000）100股@10.5，成交金额 1050.00 元",
                List.of("trading", "交易"), LocalDateTime.now(), null, null, "trading");
        when(records.findAll(any())).thenReturn(List.of(existing));
        TradingAppService service = service(repo, records);

        service.recordTrade("default", "600000", "浦发银行", TradeDirection.BUY,
                new BigDecimal("10.5"), 100);

        verify(records, never()).save(any(), any());
    }

    @Test
    void recordTrade_recordWriteFailure_doesNotFailTrade() {
        // 记录写入是附加动作：即使失败也不阻塞交易本身（持仓已落库）
        PositionRepository repo = mock(PositionRepository.class);
        when(repo.findAll(any())).thenReturn(List.of());
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenThrow(new RuntimeException("存储不可用"));
        TradingAppService service = service(repo, records);

        List<Position> result = service.recordTrade("default", "600000", "浦发银行", TradeDirection.BUY,
                new BigDecimal("10.5"), 100);

        assertTrue(result.size() == 1, "记录写入失败不应影响交易结果");
    }
}
