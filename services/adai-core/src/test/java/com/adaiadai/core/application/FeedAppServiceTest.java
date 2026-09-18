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
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

    /**
     * 固定时钟：2026-09-04（周五）10:00——A 股交易时段内。
     * 行情条窗口（2026-09-05 起）测试前提：默认 helper 落在交易时段，注入行情条。
     */
    private static final Clock TRADING_CLOCK = Clock.fixed(
            LocalDateTime.of(2026, 9, 4, 10, 0).atZone(ZoneId.systemDefault()).toInstant(),
            ZoneId.systemDefault());

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
        return serviceWith(userId, market, push, TRADING_CLOCK, plugins);
    }

    private FeedAppService serviceWith(String userId, MarketDataSource market, MarketPushRepository push,
                                       Clock clock, String... plugins) {
        RecordRepository recordRepository = mock(RecordRepository.class);
        when(recordRepository.findAll(any())).thenReturn(List.of());
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.findByDate(any(), any())).thenReturn(List.of());
        CardFileRepository cardRepository = mock(CardFileRepository.class);
        // RFC 20260817：推送开关默认全开（findByUser 未 stub 返回 null → NPE）
        PushSettingsRepository pushSettings = mock(PushSettingsRepository.class);
        when(pushSettings.findByUser(any())).thenReturn(com.adaiadai.core.domain.trading.PushSettings.defaults());
        when(cardRepository.findTodayCards(any(), any())).thenReturn(List.of());
        return new FeedAppService(recordRepository, memoryService, cardRepository, market, push,
                pluginService(userId, plugins), pushSettings, clock);
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

    // ── 2026-09-05 行情条窗口：只在 A 股交易时段注入（用户反馈「周末还在给我推行情」）──

    @Test
    void getFeed_noMarketEntry_onWeekend() {
        assertMarketHidden(2026, 9, 5, 10, 0, "周六打开首页不应出现行情条");
    }

    @Test
    void getFeed_noMarketEntry_onHoliday() {
        assertMarketHidden(2026, 10, 1, 10, 0, "国庆节（法定休市）不应出现行情条");
    }

    @Test
    void getFeed_noMarketEntry_beforeOpen() {
        assertMarketHidden(2026, 9, 4, 9, 0, "盘前 9:00 不应出现行情条");
    }

    @Test
    void getFeed_noMarketEntry_lunchBreak() {
        assertMarketHidden(2026, 9, 4, 12, 0, "午休不应出现行情条");
    }

    @Test
    void getFeed_noMarketEntry_afterClose() {
        assertMarketHidden(2026, 9, 4, 15, 30, "收盘后（15:30）不应出现行情条");
    }

    /** 行情存在但处于非交易时段 → Feed 不注入 market 行情条。 */
    private void assertMarketHidden(int year, int month, int day, int hour, int minute, String reason) {
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of(
                "000001", new MarketData("000001", "上证指数",
                        new BigDecimal("3200.12"), new BigDecimal("3200.00"),
                        new BigDecimal("3190.00"), new BigDecimal("3210.00"), new BigDecimal("3180.00"),
                        new BigDecimal("0.85"), 1000000L)
        ));
        Clock clock = Clock.fixed(LocalDateTime.of(year, month, day, hour, minute)
                .atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        FeedAppService service = serviceWith("default", market, emptyPush(), clock, "trading");
        FeedAppService.FeedResponse resp = service.getFeed("default", LocalDate.of(year, month, day), 0, 10);
        assertTrue(resp.entries().stream().noneMatch(e -> "market".equals(e.type())), reason);
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
                        "loss", "14:05", null, "2999-01-01T00:00:00")
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
                        "loss", "14:05", null, "2999-01-01T00:00:00")
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
    void getFeed_pageZero_mergesAttachEntriesIntoSameTimeline_notAppendingTail() {
        // P1-前端2（2026-09-16 生产实测）：核心（record/card）与附加（ai_note/action/行情/推送）
        // 原先**分两段拼接**——附加条目整体堆在核心之后，于是 10:03 的 ai_note 会排到 21:30 的
        // record 后面（生产真实返回就是这样，用户看到的就是「主页卡片乱序」）。
        // 本用例锁住「附加条目并入同一条时间轴再输出」。
        ContentRecord evening = new ContentRecord(
                "rec_evening", "note", "user_input",
                "晚上的记录", "晚上的记录", List.of(),
                LocalDateTime.of(2026, 8, 6, 21, 30), "log", "晚上的记录", "life");
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(List.of(evening));

        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());
        MarketPushRepository push = mock(MarketPushRepository.class);
        when(push.findByDate(any(), any())).thenReturn(List.of(
                new MarketPushEvent("push_1", "600519", "贵州茅台", "推送内容", "loss", "14:05", null,
                        "2999-01-01T00:00:00")));

        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.findByDate(any(), any())).thenReturn(List.of());
        CardFileRepository cardRepository = mock(CardFileRepository.class);
        when(cardRepository.findTodayCards(any(), any())).thenReturn(List.of());
        PushSettingsRepository pushSettings = mock(PushSettingsRepository.class);
        when(pushSettings.findByUser(any()))
                .thenReturn(com.adaiadai.core.domain.trading.PushSettings.defaults());

        FeedAppService service = new FeedAppService(records, memoryService, cardRepository, market, push,
                pluginService("default", "trading"), pushSettings, TRADING_CLOCK);

        FeedAppService.FeedResponse resp = service.getFeed("default", LocalDate.of(2026, 8, 6), 0, 10);
        List<String> types = resp.entries().stream().map(FeedAppService.FeedEntry::type).toList();

        assertEquals(List.of("push", "record"), types,
                "14:05 的附加条目必须按时间排到 21:30 的核心之前，而不是被追加到列表末尾");
    }

    @Test
    void getFeed_mergesSameMinuteSameDirectionTrades_andKeepsAllOriginalIds() {
        // P2-UI12（2026-09-16）：一次「确认入账 N 笔」→ 写侧 N 条记录 → Feed 里 N 张几乎一样的卡。
        // 展示层按 (date,time,方向) 折叠成一条，并带上全部原始 id（前端据此逐条删，否则「删了又回来」）。
        // 账目真相源（trades/account/positions）与写侧记录都不动。
        ContentRecord buy1 = trade("rec_buy1", "买入 京东方A 1000股@4.10", 14, 5);
        ContentRecord buy2 = trade("rec_buy2", "买入 京东方A 2000股@4.11", 14, 5);
        ContentRecord sell = trade("rec_sell", "卖出 京东方A 500股@4.20", 14, 5);
        ContentRecord note = new ContentRecord(
                "rec_note", "note", "user_input", "普通记录", "普通记录", List.of(),
                LocalDateTime.of(2026, 8, 6, 14, 5), "log", "普通记录", "life");

        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(List.of(buy1, buy2, sell, note));

        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.findByDate(any(), any())).thenReturn(List.of());
        CardFileRepository cardRepository = mock(CardFileRepository.class);
        when(cardRepository.findTodayCards(any(), any())).thenReturn(List.of());
        PushSettingsRepository pushSettings = mock(PushSettingsRepository.class);
        when(pushSettings.findByUser(any()))
                .thenReturn(com.adaiadai.core.domain.trading.PushSettings.defaults());

        FeedAppService service = new FeedAppService(records, memoryService, cardRepository, market, emptyPush(),
                pluginService("default", "trading"), pushSettings, TRADING_CLOCK);

        List<FeedAppService.FeedEntry> entries = service
                .getFeed("default", LocalDate.of(2026, 8, 6), 0, 10).entries();
        List<String> titles = entries.stream()
                .filter(e -> "record".equals(e.type()))
                .map(FeedAppService.FeedEntry::title)
                .toList();

        assertTrue(titles.contains("买入 2 笔"), "同分钟同向两笔买入要折叠成一条：" + titles);
        assertTrue(titles.contains("卖出 京东方A 500股@4.20"), "同分钟的反向成交不合并：" + titles);
        assertTrue(titles.contains("普通记录"), "非成交记录一律不参与折叠：" + titles);
        assertEquals(1, titles.stream().filter(t -> t.startsWith("买入")).count(), "买入只剩一条：" + titles);

        FeedAppService.FeedEntry merged = entries.stream()
                .filter(e -> "买入 2 笔".equals(e.title())).findFirst().orElseThrow();
        assertEquals(List.of("rec_buy1", "rec_buy2"), merged.mergedIds(),
                "折叠卡必须带全部原始 id（前端删除要删全，否则「删了又回来」）");
        assertTrue(merged.content().contains("1000股") && merged.content().contains("2000股"),
                "被折叠的每一笔内容都要保留：" + merged.content());
    }

    /** 交易成交记录的落库形态（对齐 TradingAppService.writeTradingRecord）。 */
    private ContentRecord trade(String id, String title, int hour, int minute) {
        return new ContentRecord(id, "trade", "auto_collect", title, title, List.of(),
                LocalDateTime.of(2026, 8, 6, hour, minute), "log", title, "trading");
    }

    @Test
    void getFeed_pushTitle_passthroughOriginalTitle() {
        // B9-1/B9-2（2026-08-23，P1-推送1 根因）：落库透传原标题 → Feed 标题=原标题
        // （前端按标题 switch 的徽章配色 + 「确认并入账」按钮判定依赖它）
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());
        MarketPushRepository push = mock(MarketPushRepository.class);
        when(push.findByDate(any(), any())).thenReturn(List.of(
                new MarketPushEvent("push_1", "600519", "贵州茅台",
                        "📋 今日操作汇总\n· 京东方A 卖出 5300 股 @6.10\n是否完整？",
                        "session", "15:15", "今日操作确认", "2999-01-01T00:00:00") // title 透传
        ));

        FeedAppService service = serviceWith(market, push);
        FeedAppService.FeedResponse resp = service.getFeed("default", LocalDate.of(2026, 8, 6), 0, 10);

        FeedAppService.FeedEntry pushEntry = resp.entries().stream()
                .filter(e -> "push".equals(e.type())).findFirst().orElseThrow();
        assertEquals("今日操作确认", pushEntry.title(), "原标题必须透传（不再按 type 重映射）");
    }

    @Test
    void getFeed_pushTitle_nullTitle_fallbackByType() {
        // B9-2：旧数据（2026-08-23 前落库无 title 字段）→ 按 type 兜底映射（渐进兼容）
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());
        MarketPushRepository push = mock(MarketPushRepository.class);
        when(push.findByDate(any(), any())).thenReturn(List.of(
                new MarketPushEvent("push_1", "600519", "贵州茅台",
                        "📉 贵州茅台(600519) 今日跌 -3.20%", "loss", "14:05", null, "2999-01-01T00:00:00") // 旧 6 参构造 → title=null
        ));

        FeedAppService service = serviceWith(market, push);
        FeedAppService.FeedResponse resp = service.getFeed("default", LocalDate.of(2026, 8, 6), 0, 10);

        FeedAppService.FeedEntry pushEntry = resp.entries().stream()
                .filter(e -> "push".equals(e.type())).findFirst().orElseThrow();
        assertEquals("单日大跌提醒", pushEntry.title(), "旧数据无 title 应按 type 兜底");
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
        CardFileRepository cardRepository = mock(CardFileRepository.class);
        when(cardRepository.findTodayCards(any(), any())).thenReturn(List.of());
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());

        FeedAppService service = new FeedAppService(recordRepository, memoryService, cardRepository, market, emptyPush(), pluginService("default", "trading"), defaultPushSettings(), Clock.systemDefaultZone());
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
        CardFileRepository cardRepository = mock(CardFileRepository.class);
        when(cardRepository.findTodayCards(any(), any())).thenReturn(List.of());
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());

        FeedAppService service = new FeedAppService(recordRepository, memoryService, cardRepository, market, emptyPush(), pluginService("default", "trading"), defaultPushSettings(), Clock.systemDefaultZone());
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
        CardFileRepository cardRepository = mock(CardFileRepository.class);
        when(cardRepository.findTodayCards(any(), any())).thenReturn(List.of());
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());

        FeedAppService service = new FeedAppService(recordRepository, memoryService, cardRepository, market, emptyPush(), pluginService("default", "trading"), defaultPushSettings(), Clock.systemDefaultZone());
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
        CardFileRepository cardRepository = mock(CardFileRepository.class);
        when(cardRepository.findTodayCards(any(), any())).thenReturn(List.of());
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());

        FeedAppService service = new FeedAppService(recordRepository, memoryService, cardRepository, market, emptyPush(), pluginService("default", "trading"), defaultPushSettings(), Clock.systemDefaultZone());
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
        CardFileRepository cardRepository = mock(CardFileRepository.class);
        when(cardRepository.findTodayCards(any(), any())).thenReturn(List.of(card));
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());

        FeedAppService service = new FeedAppService(recordRepository, memoryService, cardRepository, market, emptyPush(), pluginService("default", "trading"), defaultPushSettings(), Clock.systemDefaultZone());
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
        CardFileRepository cardRepository = mock(CardFileRepository.class);
        when(cardRepository.findTodayCards(any(), any())).thenReturn(List.of());
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());

        FeedAppService service = new FeedAppService(recordRepository, memoryService, cardRepository, market, emptyPush(), pluginService("default", "trading"), defaultPushSettings(), Clock.systemDefaultZone());

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
        CardFileRepository cardRepository = mock(CardFileRepository.class);
        when(cardRepository.findTodayCards(any(), any())).thenReturn(List.of());
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());

        FeedAppService service = new FeedAppService(recordRepository, memoryService, cardRepository,
                market, emptyPush(), pluginService("default", "trading"), defaultPushSettings(), Clock.systemDefaultZone());

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
        CardFileRepository cardRepository = mock(CardFileRepository.class);
        when(cardRepository.findTodayCards(any(), any())).thenReturn(List.of());
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());

        FeedAppService service = new FeedAppService(recordRepository, memoryService, cardRepository,
                market, emptyPush(), pluginService("default", "trading"), defaultPushSettings(), Clock.systemDefaultZone());

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

    // ── learn V2 批 4：learn-review 复习提醒对纯 learn 用户可见（Feed 注入门控放宽 trading || learn）──

    @Test
    void getFeed_learnOnlyUser_seesLearnReviewPush() {
        // learn 用户（无 trading 插件）应能看到 learn-review push 条目——复习提醒不依赖行情插件
        MarketDataSource market = mock(MarketDataSource.class);
        MarketPushRepository push = mock(MarketPushRepository.class);
        when(push.findByDate(any(), any())).thenReturn(List.of(
                new MarketPushEvent("push_l1", null, null,
                        "有 1 张卡片进入复习队列已满 7 天，该回看了：\n· 回调一半的判定（2026-08-29）",
                        "learn-review", "20:00", "学习复习提醒", "2099-01-01T09:00")));
        FeedAppService service = serviceWith("alice", market, push, "learn");

        FeedAppService.FeedResponse resp = service.getFeed("alice", LocalDate.of(2026, 9, 6), 0, 10);

        FeedAppService.FeedEntry pushEntry = resp.entries().stream()
                .filter(e -> "push".equals(e.type())).findFirst().orElse(null);
        assertTrue(pushEntry != null, "纯 learn 用户 Feed 应注入 learn-review push 条目");
        assertEquals("学习复习提醒", pushEntry.title());
        assertTrue(pushEntry.content().contains("回调一半的判定"));
    }

    @Test
    void getFeed_noPlugins_userSeesNoPushEntries() {
        // 无 learn/trading 插件的用户：push 条目不注入（复习提醒/行情推送都不可见）
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());
        MarketPushRepository push = mock(MarketPushRepository.class);
        when(push.findByDate(any(), any())).thenReturn(List.of(
                new MarketPushEvent("push_1", "600519", "贵州茅台",
                        "学习复习提醒内容", "learn-review", "20:00", "学习复习提醒", null)));
        FeedAppService service = serviceWith("stranger", market, push);  // 无插件

        FeedAppService.FeedResponse resp = service.getFeed("stranger", LocalDate.of(2026, 9, 6), 0, 10);

        assertTrue(resp.entries().stream().noneMatch(e -> "push".equals(e.type())),
                "无 learn/trading 插件用户不应看到 push 条目");
    }

    // ── RFC 20260917：Feed 去掉待办卡（用户拍板「在 Feed 里不舒服」，待办有自己的地方）──

    @Test
    void getFeed_pendingActionMemory_doesNotProduceActionEntry() {
        // 记忆里仍有 actionable 待办（供问答上下文使用），但 Feed 不再产出 type=action 条目
        Memory pending = new Memory("mem_1", "rec_1", "insight", "该给妈打个电话了",
                List.of(), List.of(), List.of("生活"), "neutral", true, "给妈打个电话",
                LocalDateTime.of(2026, 8, 6, 9, 0), null, false, null, null, null);

        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(any())).thenReturn(List.of());
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.findByDate(any(), any())).thenReturn(List.of());
        when(memoryService.findPendingActions(any())).thenReturn(List.of(pending));
        CardFileRepository cardRepository = mock(CardFileRepository.class);
        when(cardRepository.findTodayCards(any(), any())).thenReturn(List.of());
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());
        PushSettingsRepository pushSettings = defaultPushSettings();

        FeedAppService service = new FeedAppService(records, memoryService, cardRepository, market,
                emptyPush(), pluginService("default", "trading"), pushSettings, TRADING_CLOCK);
        FeedAppService.FeedResponse resp = service.getFeed("default", LocalDate.of(2026, 8, 6), 0, 10);

        assertTrue(resp.entries().stream().noneMatch(e -> "action".equals(e.type())),
                "Feed 不应再出现待办卡");
        assertTrue(resp.entries().stream().noneMatch(e -> "给妈打个电话".equals(e.content())),
                "待办内容不得以任何条目形式进 Feed");
    }

    // ── 2026-09-18（REVIEW P1-UI14 复发修复）：hasHistory = 空 Feed 分流的老用户判据 ──

    /** 只注入记录仓库的 FeedAppService（其余协作者全空）——hasHistory 由 findAll 派生，别的无关。 */
    private FeedAppService serviceWithRecords(String userId, RecordRepository recordRepository) {
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());
        MemoryService memoryService = mock(MemoryService.class);
        when(memoryService.findByDate(any(), any())).thenReturn(List.of());
        CardFileRepository cardRepository = mock(CardFileRepository.class);
        when(cardRepository.findTodayCards(any(), any())).thenReturn(List.of());
        return new FeedAppService(recordRepository, memoryService, cardRepository, market,
                emptyPush(), pluginService(userId), defaultPushSettings(), TRADING_CLOCK);
    }

    private static ContentRecord noteAt(String id, LocalDate date) {
        return new ContentRecord(id, "note", "user_input", "标题", "内容", List.of(), date.atTime(10, 0));
    }

    @Test
    void hasHistory_true_whenOnlyEarlierDaysHaveRecords() {
        // 老用户的真实处境：今天（09-04）还没有记录，但 09-01 记过 → Feed 空 ≠ 新用户
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll("default")).thenReturn(List.of(noteAt("rec_1", LocalDate.of(2026, 9, 1))));

        FeedAppService.FeedResponse resp = serviceWithRecords("default", records)
                .getFeed("default", LocalDate.of(2026, 9, 4), 0, 10);

        assertEquals(0, resp.totalToday(), "今天没有核心记录 → 前端走空态");
        assertTrue(resp.hasHistory(), "但历史上有记录 → 老用户，空态不得播新用户引导");
    }

    @Test
    void hasHistory_false_whenUserHasNoRecordAtAll() {
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll("default")).thenReturn(List.of());

        FeedAppService.FeedResponse resp = serviceWithRecords("default", records)
                .getFeed("default", LocalDate.of(2026, 9, 4), 0, 10);

        assertFalse(resp.hasHistory(), "零记录的新账号 → 空态播能力引导三问");
    }

    @Test
    void hasHistory_true_whenTodayHasRecords() {
        // 今天有记录时根本走不到空态；此用例钉住判据不与「当天切分」耦合
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll("default")).thenReturn(List.of(noteAt("rec_1", LocalDate.of(2026, 9, 4))));

        FeedAppService.FeedResponse resp = serviceWithRecords("default", records)
                .getFeed("default", LocalDate.of(2026, 9, 4), 0, 10);

        assertEquals(1, resp.totalToday());
        assertTrue(resp.hasHistory());
    }
}
