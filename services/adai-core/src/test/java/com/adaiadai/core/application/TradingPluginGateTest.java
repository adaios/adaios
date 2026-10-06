package com.adaiadai.core.application;

import com.adaiadai.core.domain.trading.PushSettings;
import com.adaiadai.core.domain.trading.market.MarketDataSource;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.MarketPushRepository;
import com.adaiadai.core.infrastructure.storage.PushSettingsRepository;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.infrastructure.storage.TagIndexService;
import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.context.engine.ContextEngine;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.kernel.context.policy.ContextAssemblyPolicy;
import com.adaiadai.core.kernel.identity.IdentityRepository;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.rhythm.RhythmRepository;
import com.adaiadai.core.kernel.search.SearchService;
import com.adaiadai.core.kernel.timeline.TimelineProjection;
import com.adaiadai.core.kernel.todo.TodoRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §11.3 六面闸门 + 可逆断言（设计稿 final 2026-10-06 第 11.3 节）——跨面终检：
 * <ul>
 *   <li><b>六面</b>：① Feed 记录折叠 ② 时间线 ③ 搜索 ④ 领域活动度 ⑤ 简报记忆段 ⑥ AI 上下文相关记录——
 *       关插件后逐面断言不再含交易条目；</li>
 *   <li><b>可逆断言</b>：{@code 关 → 开} 后六面全部恢复，且 {@code data/{userId}/trading/} 文件数
 *       在「关前 / 关后 / 重开后」<b>三次相等</b>（证明「保留不删」不是「顺手清掉」）。</li>
 * </ul>
 * 判据方式是读侧过滤（T18）：数据文件一个都不动，只是关插件期间读不出来。
 */
class TradingPluginGateTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final RecordFileRepository records = new RecordFileRepository(storage);
    private final AtomicBoolean tradingOn = new AtomicBoolean(true);

    /** 插件开关 mock：hasPlugin / enabledPlugins 同步跟随 tradingOn（六面共用同一真相源）。 */
    private PluginService plugins() {
        PluginService plugins = mock(PluginService.class);
        when(plugins.hasPlugin(any(), any())).thenAnswer(inv -> tradingOn.get());
        when(plugins.enabledPlugins(any())).thenAnswer(inv ->
                tradingOn.get() ? Set.of(PluginRegistry.PLUGIN_TRADING) : Set.<String>of());
        return plugins;
    }

    /** data/{userId}/trading/ 下的文件数（可逆断言的取数口径）。 */
    private int tradingFileCount() {
        return storage.listFiles("default", "trading/").size();
    }

    /** 简报 prompt 捕获（面5）：每次构建新实例——绕开 5 分钟缓存；AI 抛异常走降级（prompt 已捕获）。 */
    private String briefPrompt(PluginService plugins) {
        AtomicReference<String> captured = new AtomicReference<>();
        AiClient ai = mock(AiClient.class);
        when(ai.understand(any())).thenAnswer(inv -> {
            captured.set(((ContextPackage) inv.getArgument(0)).prompt());
            throw new RuntimeException("stop-after-capture");
        });
        IdentityRepository identity = mock(IdentityRepository.class);
        when(identity.load(any())).thenReturn(Optional.empty());
        TodoRepository todos = mock(TodoRepository.class);
        when(todos.findAll(any(), any())).thenReturn(List.of());
        RhythmRepository rhythms = mock(RhythmRepository.class);
        when(rhythms.findAll(any(), any())).thenReturn(List.of());
        TradingReviewAppService review = mock(TradingReviewAppService.class);
        when(review.positionSummaryLines(any())).thenReturn(List.of());
        MemoryService memories = new MemoryService(storage);
        BriefAppService brief = new BriefAppService(identity, records, memories, ai, review,
                new DomainActivityService(records, plugins),
                new TagRecommendationService(new TagIndexService(storage)),
                todos, rhythms, plugins,
                new ActionReviewService(todos, new TodoAppService(todos, memories)));
        brief.generateBrief("default");
        return captured.get();
    }

    @Test
    void pluginToggle_sixFacesHideAndRecover_tradingFilesUntouched() {
        // ── 种子数据：1 条交易记录 + 1 条生活记录 + 3 个 trading 数据文件 ──
        LocalDateTime now = LocalDateTime.now();
        ContentRecord trade = new ContentRecord("rec_trade_gate", "record", "user_input", "买入 京东方A",
                "买入 京东方A 1000 股 @5.20", List.of(), now, "log", null, "trading");
        ContentRecord life = new ContentRecord("rec_life_gate", "note", "user_input", "散步",
                "今天去公园散步了", List.of(), now, "log", null, "life");
        records.save("default", trade);
        records.save("default", life);
        storage.write("default", "trading/trades/2026-10.md", "# 成交\n");
        storage.write("default", "trading/positions/2026-10.md", "# 持仓\n");
        storage.write("default", "trading/account/account.md", "# 账户\n");

        PluginService plugins = plugins();

        // ── 六面组装（共享同一 recordRepository 与插件开关）──
        SearchService search = new SearchService(records, plugins);
        CardFileRepository cards = mock(CardFileRepository.class);
        when(cards.findAll(any())).thenReturn(List.of());
        when(cards.findTodayCards(any(), any())).thenReturn(List.of());
        TimelineProjection timeline = new TimelineProjection(records, cards, plugins);
        DomainActivityService domainActivity = new DomainActivityService(records, plugins);
        MarketDataSource market = mock(MarketDataSource.class);
        when(market.indices()).thenReturn(Map.of());
        MarketPushRepository push = mock(MarketPushRepository.class);
        when(push.findByDate(any(), any())).thenReturn(List.of());
        PushSettingsRepository pushSettings = mock(PushSettingsRepository.class);
        when(pushSettings.findByUser(any())).thenReturn(PushSettings.defaults());
        FeedAppService feed = new FeedAppService(records, new MemoryService(storage), cards, market, push,
                plugins, pushSettings, Clock.systemDefaultZone());
        // 面6：真实 SearchService + mock TagIndex/Memory（相关记录只由 records 驱动）
        IdentityRepository identity = mock(IdentityRepository.class);
        when(identity.load(any())).thenReturn(Optional.empty());
        TagIndexService tagIndex = mock(TagIndexService.class);
        when(tagIndex.findRelatedIds(any(), any(), anyInt())).thenReturn(List.of());
        MemoryService engineMemory = mock(MemoryService.class);
        ContextEngine engine = new ContextEngine(identity, records, tagIndex, engineMemory, cards,
                List.of(), List.of(), search, plugins, ContextAssemblyPolicy.legacy());

        // ── 关前（基线：六面都看得见）──
        int filesBefore = tradingFileCount();
        assertTrue(filesBefore > 0, "种子 trading 数据文件应存在");
        assertFalse(search.search("default", "京东方").isEmpty(), "关前：搜索可见交易记录");
        assertTrue(timeline.fullTimeline("default").stream()
                .anyMatch(e -> "rec_trade_gate".equals(e.id())), "关前：时间线含交易条目");
        assertTrue(domainActivity.getActivity("default").domains().stream()
                .anyMatch(d -> "trading".equals(d.domain())), "关前：领域活动度含交易域");
        assertTrue(feed.getFeed("default", LocalDate.now(), 0, 20).entries().stream()
                .anyMatch(e -> "trading".equals(e.domain())), "关前：Feed 含交易条目");
        assertTrue(briefPrompt(plugins).contains("买入 京东方A"), "关前：简报记录段含交易记录");
        assertTrue(engine.compose("default", "note", life, null).prompt().contains("京东方"),
                "关前：AI 上下文相关记录含交易记录");

        // ── 关插件（T18：读侧过滤，数据保留不删）──
        tradingOn.set(false);
        assertTrue(search.search("default", "京东方").isEmpty(), "面3：搜索不含交易记录");
        assertTrue(timeline.fullTimeline("default").stream()
                .noneMatch(e -> "rec_trade_gate".equals(e.id())), "面2：时间线不含交易条目");
        assertTrue(domainActivity.getActivity("default").domains().stream()
                .noneMatch(d -> "trading".equals(d.domain())), "面4：领域活动度不含交易域");
        assertTrue(feed.getFeed("default", LocalDate.now(), 0, 20).entries().stream()
                .noneMatch(e -> "trading".equals(e.domain())), "面1：Feed 不含任何 domain=trading 条目");
        String offBrief = briefPrompt(plugins);
        assertNotNull(offBrief);
        assertFalse(offBrief.contains("京东方"), "面5：简报记忆段/记录段不含交易内容");
        assertFalse(engine.compose("default", "note", life, null).prompt().contains("京东方"),
                "面6：AI 上下文的相关历史记录不含交易记录");

        int filesClosed = tradingFileCount();
        assertEquals(filesBefore, filesClosed, "关后：trading 文件数不变（保留不删，不是顺手清掉）");

        // ── 重开 → 六面全部恢复（可逆）──
        tradingOn.set(true);
        assertEquals(1, search.search("default", "京东方").size(), "重开：搜索恢复");
        assertTrue(timeline.fullTimeline("default").stream()
                .anyMatch(e -> "rec_trade_gate".equals(e.id())), "重开：时间线恢复");
        assertTrue(domainActivity.getActivity("default").domains().stream()
                .anyMatch(d -> "trading".equals(d.domain())), "重开：领域活动度恢复");
        assertTrue(feed.getFeed("default", LocalDate.now(), 0, 20).entries().stream()
                .anyMatch(e -> "trading".equals(e.domain())), "重开：Feed 恢复");
        assertTrue(briefPrompt(plugins).contains("买入 京东方A"), "重开：简报记录段恢复");
        assertTrue(engine.compose("default", "note", life, null).prompt().contains("京东方"),
                "重开：AI 上下文相关记录恢复");

        int filesReopened = tradingFileCount();
        assertEquals(filesBefore, filesReopened, "重开后：trading 文件数仍不变（三次相等）");
    }
}
