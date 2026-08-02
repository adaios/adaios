package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.kernel.market.MarketData;
import com.adaiadai.core.kernel.market.MarketDataSource;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FeedAppService — v0.2.0 L5 行情嵌入测试。
 */
class FeedAppServiceTest {

    private FeedAppService serviceWith(MarketDataSource market) {
        RecordRepository recordRepository = mock(RecordRepository.class);
        when(recordRepository.findAll(any())).thenReturn(List.of());
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.findByDate(any(), any())).thenReturn(List.of());
        when(memoryService.findPendingActions(any())).thenReturn(List.of());
        CardFileRepository cardRepository = mock(CardFileRepository.class);
        when(cardRepository.findTodayCards(any(), any())).thenReturn(List.of());
        return new FeedAppService(recordRepository, memoryService, null, cardRepository, market);
    }

    @Test
    void getFeed_includesMarketEntry_whenIndicesAvailable() {
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of(
                "000001", new MarketData("000001", "上证指数",
                        new BigDecimal("3200.12"), new BigDecimal("3200.00"),
                        new BigDecimal("3190.00"), new BigDecimal("3210.00"), new BigDecimal("3180.00"),
                        new BigDecimal("0.85"), 1000000L)
        ));

        FeedAppService service = serviceWith(market);
        FeedAppService.FeedResponse resp = service.getFeed("default", LocalDate.now(), 0, 10);

        assertTrue(resp.entries().stream().anyMatch(e -> "market".equals(e.type())),
                "有行情时应输出 type=market 条目");
        assertTrue(resp.entries().stream().anyMatch(e -> e.content().contains("上证指数")),
                "market 条目内容应含指数名称");
    }

    @Test
    void getFeed_noMarketEntry_whenIndicesEmpty() {
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());

        FeedAppService service = serviceWith(market);
        FeedAppService.FeedResponse resp = service.getFeed("default", LocalDate.now(), 0, 10);

        assertTrue(resp.entries().stream().noneMatch(e -> "market".equals(e.type())),
                "行情为空（网络失败）时不输出 market 条目");
    }
}
