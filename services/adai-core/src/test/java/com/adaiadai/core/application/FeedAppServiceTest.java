package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.MarketPushEvent;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.MarketPushRepository;
import com.adaiadai.core.infrastructure.storage.PushSettingsRepository;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.domain.trading.market.MarketData;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FeedAppService — v0.2.0 L5 行情嵌入测试。
 */
class FeedAppServiceTest {

    /** 插件服务：默认给 trading 插件（行情卡门控的前提），可单独构建无插件用户。 */
    private PluginService pluginService(String userId, String... plugins) {
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findById(userId)).thenReturn(Optional.of(
                new Account(userId, Account.ROLE_USER, true, LocalDate.of(2026, 8, 2), List.of(plugins))));
        return new PluginService(accounts, new PluginRegistry());
    }

    private FeedAppService serviceWith(MarketDataSource market, MarketPushRepository push) {
        return serviceWith("default", market, push, "trading");
    }

    /** RFC 20260817：推送开关默认全开（findByUser 未 stub 返回 null → NPE）。 */
    private PushSettingsRepository defaultPushSettings() {
        PushSettingsRepository ps = mock(PushSettingsRepository.class);
        when(ps.findByUser(any())).thenReturn(com.adaiadai.core.domain.trading.PushSettings.defaults());
        return ps;
    }

    private FeedAppService serviceWith(String userId, MarketDataSource market, MarketPushRepository push, String... plugins) {
        RecordRepository recordRepository = mock(RecordRepository.class);
        when(recordRepository.findAll(any())).thenReturn(List.of());
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.findByDate(any(), any())).thenReturn(List.of());
        when(memoryService.findPendingActions(any())).thenReturn(List.of());
        CardFileRepository cardRepository = mock(CardFileRepository.class);
        // RFC 20260817：推送开关默认全开（findByUser 未 stub 返回 null → NPE）
        PushSettingsRepository pushSettings = mock(PushSettingsRepository.class);
        when(pushSettings.findByUser(any())).thenReturn(com.adaiadai.core.domain.trading.PushSettings.defaults());
        when(cardRepository.findTodayCards(any(), any())).thenReturn(List.of());
        return new FeedAppService(recordRepository, memoryService, cardRepository, market, push,
                pluginService(userId, plugins), pushSettings);
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
    void getFeed_noMarketOrPushEntry_whenNoTradingPlugin() {
        // T2.6（RFC 20260814）：无 trading 插件用户 Feed 不出现行情卡/异动推送，即使数据存在
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of(
                "000001", new MarketData("000001", "上证指数",
                        new BigDecimal("3200.12"), new BigDecimal("3200.00"),
                        new BigDecimal("3190.00"), new BigDecimal("3210.00"), new BigDecimal("3180.00"),
                        new BigDecimal("0.85"), 1000000L)
        ));
        MarketPushRepository push = mock(MarketPushRepository.class);
        when(push.findByDate(any(), any())).thenReturn(List.of(
                new MarketPushEvent("push_1", "600519", "贵州茅台",
                        "📉 贵州茅台(600519) 今日跌 -3.20%，现价 1321——单日大跌，留意风险（你还没设止损位，想好怎么走）",
                        "loss", "14:05")
        ));

        FeedAppService service = serviceWith("alice", market, push); // 无插件
        FeedAppService.FeedResponse resp = service.getFeed("alice", LocalDate.of(2026, 8, 6), 0, 10);

        assertTrue(resp.entries().stream().noneMatch(e -> "market".equals(e.type())),
                "无 trading 插件用户不应出现 market 行情条");
        assertTrue(resp.entries().stream().noneMatch(e -> "push".equals(e.type())),
                "无 trading 插件用户不应出现 push 异动推送");
    }

    @Test
    void getFeed_includesPushEntry_whenPushExists() {
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());
        MarketPushRepository push = mock(MarketPushRepository.class);
        when(push.findByDate(any(), any())).thenReturn(List.of(
                new MarketPushEvent("push_1", "600519", "贵州茅台",
                        "📉 贵州茅台(600519) 今日跌 -3.20%，现价 1321——单日大跌，留意风险（你还没设止损位，想好怎么走）",
                        "loss", "14:05")
        ));

        FeedAppService service = serviceWith(market, push);
        FeedAppService.FeedResponse resp = service.getFeed("default", LocalDate.of(2026, 8, 6), 0, 10);

        FeedAppService.FeedEntry pushEntry = resp.entries().stream()
                .filter(e -> "push".equals(e.type())).findFirst().orElseThrow();
        assertEquals("push_1", pushEntry.id());
        assertEquals("trading", pushEntry.domain());
        assertEquals("08-06", pushEntry.date());
        assertTrue(pushEntry.content().contains("单日大跌"));
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

        FeedAppService service = new FeedAppService(recordRepository, memoryService, cardRepository, market, emptyPush(), pluginService("default", "trading"), defaultPushSettings());
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

        FeedAppService service = new FeedAppService(recordRepository, memoryService, cardRepository, market, emptyPush(), pluginService("default", "trading"), defaultPushSettings());
        FeedAppService.FeedResponse resp = service.getFeed("default", LocalDate.of(2026, 8, 3), 0, 10);

        FeedAppService.FeedEntry textEntry = resp.entries().stream()
                .filter(e -> "rec_txt1".equals(e.id())).findFirst().orElseThrow();
        assertNull(textEntry.mediaPath(), "文本记录不应带 mediaPath");
        assertEquals("08-03", textEntry.date());
    }

    @Test
    void getFeed_crossDayMemory_aiNoteBelongsToRecordDate() {
        // REVIEW #148：记录在 8-03，记忆因重补/升级沉淀在 8-06（Memory.createdAt=处理当天）。
        // 同日 findByDate 查不到 → findByRecordIds 补齐，ai_note 归属记录日期而非沉淀日期。
        ContentRecord rec = new ContentRecord(
                "rec_cross1", "note", "user_input",
                "标题", "正文", List.of("日常"),
                LocalDateTime.of(2026, 8, 3, 9, 30),
                "log", null, "life");
        Memory crossDay = new Memory(
                "mem_cross1", "rec_cross1", Memory.KIND_INSIGHT, "跨日重补的洞察",
                List.of(), List.of(), List.of("日常"), "neutral", false, null,
                LocalDateTime.of(2026, 8, 6, 14, 0),  // 沉淀在 8-06（处理当天）
                null, false, null, null, null);

        RecordRepository recordRepository = mock(RecordRepository.class);
        when(recordRepository.findAll(any())).thenReturn(List.of(rec));
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.findByDate(any(), any())).thenReturn(List.of()); // 同日无记忆
        when(memoryService.findByRecordIds(any(), any())).thenReturn(Map.of("rec_cross1", crossDay));
        when(memoryService.findPendingActions(any())).thenReturn(List.of());
        CardFileRepository cardRepository = mock(CardFileRepository.class);
        when(cardRepository.findTodayCards(any(), any())).thenReturn(List.of());
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());

        FeedAppService service = new FeedAppService(recordRepository, memoryService, cardRepository, market, emptyPush(), pluginService("default", "trading"), defaultPushSettings());
        FeedAppService.FeedResponse resp = service.getFeed("default", LocalDate.of(2026, 8, 3), 0, 10);

        FeedAppService.FeedEntry aiNote = resp.entries().stream()
                .filter(e -> "ai_note".equals(e.type())).findFirst().orElseThrow();
        assertEquals("跨日重补的洞察", aiNote.content());
        assertEquals("09:30", aiNote.time(), "ai_note 归属记录时间，而非记忆沉淀 14:00");
        assertEquals("08-03", aiNote.date(), "ai_note 归属记录日期，而非记忆沉淀 08-06");
    }

    @Test
    void getFeed_noCrossDayMemory_noAiNote() {
        // 记录无任何记忆（含跨日）时不渲染 ai_note，不报错
        ContentRecord rec = new ContentRecord(
                "rec_nomem", "note", "user_input",
                "标题", "正文", List.of("日常"),
                LocalDateTime.of(2026, 8, 3, 9, 30),
                "log", null, "life");

        RecordRepository recordRepository = mock(RecordRepository.class);
        when(recordRepository.findAll(any())).thenReturn(List.of(rec));
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.findByDate(any(), any())).thenReturn(List.of());
        when(memoryService.findByRecordIds(any(), any())).thenReturn(Map.of());
        when(memoryService.findPendingActions(any())).thenReturn(List.of());
        CardFileRepository cardRepository = mock(CardFileRepository.class);
        when(cardRepository.findTodayCards(any(), any())).thenReturn(List.of());
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());

        FeedAppService service = new FeedAppService(recordRepository, memoryService, cardRepository, market, emptyPush(), pluginService("default", "trading"), defaultPushSettings());
        FeedAppService.FeedResponse resp = service.getFeed("default", LocalDate.of(2026, 8, 3), 0, 10);

        assertTrue(resp.entries().stream().noneMatch(e -> "ai_note".equals(e.type())),
                "无记忆的记录不渲染 ai_note");
    }

    @Test
    void getFeed_card_timeAndDate_useUpdatedAt_lastActiveDay() {
        // REVIEW updatedAt 时间基准：卡片 8-07 创建、8-09 最后活跃（跨日续接）→
        // 8-09 Feed 应含该卡，时间/日期按 updatedAt 而非 createdAt/首条消息。
        CardRecord card = new CardRecord(
                "card_x", "conversation", "active", List.of("对话"),
                List.of(new CardRecord.Turn(true, "跨日续接的问题", "02:06")),
                null,
                LocalDateTime.of(2026, 8, 7, 22, 0),
                LocalDateTime.of(2026, 8, 9, 2, 14));

        RecordRepository recordRepository = mock(RecordRepository.class);
        when(recordRepository.findAll(any())).thenReturn(List.of());
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.findByDate(any(), any())).thenReturn(List.of());
        when(memoryService.findPendingActions(any())).thenReturn(List.of());
        CardFileRepository cardRepository = mock(CardFileRepository.class);
        when(cardRepository.findTodayCards(any(), any())).thenReturn(List.of(card));
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());

        FeedAppService service = new FeedAppService(recordRepository, memoryService, cardRepository, market, emptyPush(), pluginService("default", "trading"), defaultPushSettings());
        FeedAppService.FeedResponse resp = service.getFeed("default", LocalDate.of(2026, 8, 9), 0, 10);

        FeedAppService.FeedEntry cardEntry = resp.entries().stream()
                .filter(e -> "card".equals(e.type())).findFirst().orElseThrow();
        assertEquals("02:14", cardEntry.time(), "卡片时间按 updatedAt（最后活跃），而非首条消息 02:06");
        assertEquals("08-09", cardEntry.date(), "卡片归最后活跃日 8-09，而非创建日 8-07");
    }

    @Test
    void getFeed_page0_returnsFullSizeNewestCore_remainderOnLastPage() {
        // REVIEW #175：9 条核心（8-03 当天）size=5 → page 0 = 最新完整 5 条，page 1 = 最早 4 条余数
        List<ContentRecord> records = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            records.add(new ContentRecord(
                    "rec_" + i, "note", "user_input",
                    "标题" + i, "正文" + i, List.of("日常"),
                    LocalDateTime.of(2026, 8, 3, 9, i + 1),
                    "log", null, "life"));
        }
        RecordRepository recordRepository = mock(RecordRepository.class);
        when(recordRepository.findAll(any())).thenReturn(records);
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.findByDate(any(), any())).thenReturn(List.of());
        when(memoryService.findByRecordIds(any(), any())).thenReturn(Map.of());
        when(memoryService.findPendingActions(any())).thenReturn(List.of());
        CardFileRepository cardRepository = mock(CardFileRepository.class);
        when(cardRepository.findTodayCards(any(), any())).thenReturn(List.of());
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());

        FeedAppService service = new FeedAppService(recordRepository, memoryService, cardRepository, market, emptyPush(), pluginService("default", "trading"), defaultPushSettings());

        FeedAppService.FeedResponse page0 = service.getFeed("default", LocalDate.of(2026, 8, 3), 0, 5);
        List<FeedAppService.FeedEntry> core0 = page0.entries().stream()
                .filter(e -> "record".equals(e.type())).toList();
        assertEquals(5, core0.size(), "page 0 应返回完整 size 核心（最新 5 条）");
        assertEquals("rec_4", core0.get(0).id(), "page 0 最早一条 = 最新 5 条的最早（rec_4）");
        assertEquals("rec_8", core0.get(4).id(), "page 0 最新一条 = rec_8");
        assertEquals(9, page0.totalToday(), "totalToday = 核心总数，不随页收缩");

        FeedAppService.FeedResponse page1 = service.getFeed("default", LocalDate.of(2026, 8, 3), 1, 5);
        List<FeedAppService.FeedEntry> core1 = page1.entries().stream()
                .filter(e -> "record".equals(e.type())).toList();
        assertEquals(4, core1.size(), "余数 4 条放末页");
        assertEquals("rec_0", core1.get(0).id(), "page 1 最早一条 = rec_0");
        assertEquals("rec_3", core1.get(3).id(), "page 1 最新一条 = rec_3");

        FeedAppService.FeedResponse page2 = service.getFeed("default", LocalDate.of(2026, 8, 3), 2, 5);
        assertTrue(page2.entries().isEmpty(), "超范围页返回空");
    }

    @Test
    void getFeed_aggregatesImageQa_referencedImagesNotSeparate() {
        // S-2 图文一体：3 图 + 1 条 image_qa（引用 3 图）→ Feed 只 1 条图文事件，图不单独成条
        LocalDateTime t0 = LocalDateTime.of(2026, 8, 15, 10, 0);
        List<ContentRecord> all = List.of(
                record("img1", "image", "图1", "【图片文字】K线", "log", t0),
                record("img2", "image", "图2", "【图片文字】成交量", "log", t0),
                record("img3", "image", "图3", "【图片文字】MACD", "log", t0),
                record("qa1", "image_qa", "顶背离判断",
                        "【多图问答】\n图片记录：img1, img2, img3\n问：看看是不是顶背离\n答：是",
                        "question", t0.plusSeconds(30)));

        RecordRepository recordRepository = mock(RecordRepository.class);
        when(recordRepository.findAll(any())).thenReturn(all);
        when(recordRepository.findMediaPath(eq("default"), eq("img1")))
                .thenReturn(Optional.of("records/2026/08/media/img1.png"));
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.findByDate(any(), any())).thenReturn(List.of());
        when(memoryService.findByRecordIds(any(), any())).thenReturn(Map.of());
        when(memoryService.findPendingActions(any())).thenReturn(List.of());
        CardFileRepository cardRepository = mock(CardFileRepository.class);
        when(cardRepository.findTodayCards(any(), any())).thenReturn(List.of());
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());

        FeedAppService service = new FeedAppService(recordRepository, memoryService, cardRepository,
                market, emptyPush(), pluginService("default", "trading"), defaultPushSettings());

        FeedAppService.FeedResponse resp = service.getFeed("default", LocalDate.of(2026, 8, 15), 0, 10);

        List<FeedAppService.FeedEntry> recordEntries = resp.entries().stream()
                .filter(e -> "record".equals(e.type())).toList();
        assertEquals(1, recordEntries.size(), "3 图 + 1 问答 → 聚合为 1 条图文事件");
        assertEquals("qa1", recordEntries.get(0).id(), "保留 image_qa 记录");
        assertEquals("records/2026/08/media/img1.png", recordEntries.get(0).mediaPath(),
                "图文事件缩略图取引用首图");
        // 第一原则（无第三视角）：标题=用户问句、正文自然对话（无 问：/答：/图片记录：/【多图问答】标签）
        assertEquals("看看是不是顶背离", recordEntries.get(0).title(),
                "image_qa 标题应为用户问句（自然语言）");
        assertEquals("看看是不是顶背离\n是", recordEntries.get(0).content(),
                "image_qa 正文应为 问/答 两行（去标签）");
        assertFalse(recordEntries.get(0).content().contains("图片记录"),
                "正文不得出现内部图片引用（第三视角）");
        assertFalse(recordEntries.get(0).content().contains("问："),
                "正文不得出现「问：」标签");

        // S-2 聚合卡对话历史：image_qa 条目附带 turns（问句 + 回答，从 content 解析）——
        // 刷新后聚合卡以"图文对话卡"形态呈现，前端进对话态可显示历史
        assertNotNull(recordEntries.get(0).turns(), "image_qa 聚合条目应附对话 turns");
        assertEquals(2, recordEntries.get(0).turns().size(), "turns = 问句 + 回答 两条");
        assertTrue(recordEntries.get(0).turns().get(0).isUser());
        assertEquals("看看是不是顶背离", recordEntries.get(0).turns().get(0).text());
        assertEquals("10:00", recordEntries.get(0).turns().get(0).time(),
                "turns 时间取 image_qa 记录 createdAt 的 HH:mm");
        assertFalse(recordEntries.get(0).turns().get(1).isUser());
        assertEquals("是", recordEntries.get(0).turns().get(1).text());
    }

    @Test
    void getFeed_imageQa_singleImageFormat_carriesTurns() {
        // 单图追问（【图片问答】格式）聚合条目同样附带 turns
        LocalDateTime t0 = LocalDateTime.of(2026, 8, 15, 9, 30);
        List<ContentRecord> all = List.of(
                record("img1", "image", "图1", "【图片文字】K线", "log", t0),
                record("qa1", "image_qa", "这是什么股票",
                        "【图片问答】\n图片记录：img1\n问：这是什么股票\n答：浦发银行", "question", t0.plusSeconds(30)));

        RecordRepository recordRepository = mock(RecordRepository.class);
        when(recordRepository.findAll(any())).thenReturn(all);
        when(recordRepository.findMediaPath(eq("default"), eq("img1")))
                .thenReturn(Optional.of("records/2026/08/media/img1.png"));
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.findByDate(any(), any())).thenReturn(List.of());
        when(memoryService.findByRecordIds(any(), any())).thenReturn(Map.of());
        when(memoryService.findPendingActions(any())).thenReturn(List.of());
        CardFileRepository cardRepository = mock(CardFileRepository.class);
        when(cardRepository.findTodayCards(any(), any())).thenReturn(List.of());
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());

        FeedAppService service = new FeedAppService(recordRepository, memoryService, cardRepository,
                market, emptyPush(), pluginService("default", "trading"), defaultPushSettings());

        FeedAppService.FeedResponse resp = service.getFeed("default", LocalDate.of(2026, 8, 15), 0, 10);

        FeedAppService.FeedEntry qaEntry = resp.entries().stream()
                .filter(e -> "record".equals(e.type())).findFirst().orElseThrow();
        assertEquals(2, qaEntry.turns().size());
        assertEquals("这是什么股票", qaEntry.turns().get(0).text());
        assertEquals("浦发银行", qaEntry.turns().get(1).text());
        assertEquals("09:30", qaEntry.turns().get(0).time());
    }

    private static ContentRecord record(String id, String type, String title, String content, String intent,
                                        LocalDateTime time) {
        return new ContentRecord(id, type, "user_input", title, content, List.of(), time, intent, title, "life");
    }
}
