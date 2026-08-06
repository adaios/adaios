package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.MarketPushEvent;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.MarketPushRepository;
import com.adaiadai.core.kernel.market.MarketData;
import com.adaiadai.core.kernel.market.MarketDataSource;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FeedAppService — v0.2.0 L5 行情嵌入测试。
 */
class FeedAppServiceTest {

    private FeedAppService serviceWith(MarketDataSource market, MarketPushRepository push) {
        RecordRepository recordRepository = mock(RecordRepository.class);
        when(recordRepository.findAll(any())).thenReturn(List.of());
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.findByDate(any(), any())).thenReturn(List.of());
        when(memoryService.findPendingActions(any())).thenReturn(List.of());
        CardFileRepository cardRepository = mock(CardFileRepository.class);
        when(cardRepository.findTodayCards(any(), any())).thenReturn(List.of());
        return new FeedAppService(recordRepository, memoryService, null, cardRepository, market, push);
    }

    private MarketPushRepository emptyPush() {
        MarketPushRepository push = mock(MarketPushRepository.class);
        when(push.findByDate(any(), any())).thenReturn(List.of());
        return push;
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

        FeedAppService service = serviceWith(market, emptyPush());
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

        FeedAppService service = serviceWith(market, emptyPush());
        FeedAppService.FeedResponse resp = service.getFeed("default", LocalDate.now(), 0, 10);

        assertTrue(resp.entries().stream().noneMatch(e -> "market".equals(e.type())),
                "行情为空（网络失败）时不输出 market 条目");
    }

    @Test
    void getFeed_includesPushEntry_whenPushExists() {
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());
        MarketPushRepository push = mock(MarketPushRepository.class);
        when(push.findByDate(any(), any())).thenReturn(List.of(
                new MarketPushEvent("push_1", "600519", "贵州茅台",
                        "📉 贵州茅台(600519) 今日跌 -3.20%，现价 1321，触发止损预警",
                        "loss", "14:05")
        ));

        FeedAppService service = serviceWith(market, push);
        FeedAppService.FeedResponse resp = service.getFeed("default", LocalDate.of(2026, 8, 6), 0, 10);

        FeedAppService.FeedEntry pushEntry = resp.entries().stream()
                .filter(e -> "push".equals(e.type())).findFirst().orElseThrow();
        assertEquals("push_1", pushEntry.id());
        assertEquals("trading", pushEntry.domain());
        assertEquals("08-06", pushEntry.date());
        assertTrue(pushEntry.content().contains("止损预警"));
    }

    @Test
    void getFeed_imageRecord_carriesDateAndMediaPath() {
        ContentRecord img = new ContentRecord(
                "rec_img1", "image", "user_input",
                "图片摘要", "【图片文字】hello", List.of("photo"),
                LocalDateTime.of(2026, 8, 3, 9, 15),
                "log", "图片摘要", "life");
        RecordRepository recordRepository = mock(RecordRepository.class);
        when(recordRepository.findAll(any())).thenReturn(List.of(img));
        when(recordRepository.findMediaPath(any(), any()))
                .thenReturn(Optional.of("records/2026/08/media/rec_img1.jpg"));
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.findByDate(any(), any())).thenReturn(List.of());
        when(memoryService.findPendingActions(any())).thenReturn(List.of());
        CardFileRepository cardRepository = mock(CardFileRepository.class);
        when(cardRepository.findTodayCards(any(), any())).thenReturn(List.of());
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());

        FeedAppService service = new FeedAppService(recordRepository, memoryService, null, cardRepository, market, emptyPush());
        FeedAppService.FeedResponse resp = service.getFeed("default", LocalDate.of(2026, 8, 3), 0, 10);

        FeedAppService.FeedEntry imgEntry = resp.entries().stream()
                .filter(e -> "rec_img1".equals(e.id())).findFirst().orElseThrow();
        assertEquals("08-03", imgEntry.date(), "图片记录应带 MM-dd 日期");
        assertEquals("records/2026/08/media/rec_img1.jpg", imgEntry.mediaPath(), "图片记录应带 mediaPath（原图访问）");
    }

    @Test
    void getFeed_textRecord_noMediaPath() {
        ContentRecord text = new ContentRecord(
                "rec_txt1", "note", "user_input",
                "标题", "正文", List.of("tag"),
                LocalDateTime.of(2026, 8, 3, 10, 0),
                "log", null, "life");
        RecordRepository recordRepository = mock(RecordRepository.class);
        when(recordRepository.findAll(any())).thenReturn(List.of(text));
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.findByDate(any(), any())).thenReturn(List.of());
        when(memoryService.findPendingActions(any())).thenReturn(List.of());
        CardFileRepository cardRepository = mock(CardFileRepository.class);
        when(cardRepository.findTodayCards(any(), any())).thenReturn(List.of());
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());

        FeedAppService service = new FeedAppService(recordRepository, memoryService, null, cardRepository, market, emptyPush());
        FeedAppService.FeedResponse resp = service.getFeed("default", LocalDate.of(2026, 8, 3), 0, 10);

        FeedAppService.FeedEntry textEntry = resp.entries().stream()
                .filter(e -> "rec_txt1".equals(e.id())).findFirst().orElseThrow();
        assertNull(textEntry.mediaPath(), "文本记录不应带 mediaPath");
        assertEquals("08-03", textEntry.date());
    }
}
