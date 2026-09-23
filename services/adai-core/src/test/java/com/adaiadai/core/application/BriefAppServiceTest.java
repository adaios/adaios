package com.adaiadai.core.application;

import com.adaiadai.core.kernel.todo.Todo;
import com.adaiadai.core.kernel.todo.TodoRepository;
import com.adaiadai.core.kernel.todo.TodoStatus;
import com.adaiadai.core.domain.trading.AccountSnapshotRepository;
import com.adaiadai.core.infrastructure.ai.llm.TestAiClient;
import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.ai.AiUnderstanding;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.infrastructure.storage.IdentityFileRepository;
import com.adaiadai.core.infrastructure.storage.TagIndexService;
import com.adaiadai.core.infrastructure.storage.TradingReviewFileRepository;
import com.adaiadai.core.kernel.identity.IdentityProfile;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.ContentRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BriefAppService 单元测试。
 * 验证简报生成的基本行为（Mock AI 模式下返回固定格式）。
 */
class BriefAppServiceTest {

    private InMemoryFileStorage fileStorage;
    private RecordFileRepository recordRepository;
    private IdentityFileRepository identityRepository;
    private BriefAppService briefAppService;
    private AiClient aiClient;
    private TodoRepository todoRepository;
    /** RFC 20260923：记录最后一次交给 AI 的 prompt，用于断言注入闸门。 */
    private RecordingAiClient recordingAi;

    @BeforeEach
    void setUp() {
        fileStorage = new InMemoryFileStorage();
        TagIndexService tagIndexService = new TagIndexService(fileStorage);
        recordRepository = new RecordFileRepository(fileStorage);
        recordRepository.setTagIndexService(tagIndexService);
        identityRepository = new IdentityFileRepository(fileStorage);
        MemoryService memoryService = new MemoryService(fileStorage);
        TradingReviewFileRepository reviewRepo = new TradingReviewFileRepository(fileStorage);
        // RFC 20260923 A 批：待办注入闸门测试需要可控的 OPEN 待办；默认空清单（不影响既有用例）
        todoRepository = mock(TodoRepository.class);
        when(todoRepository.findAll(any(), any())).thenReturn(List.of());
        recordingAi = new RecordingAiClient(new TestAiClient());
        aiClient = recordingAi;
        briefAppService = buildService(tagIndexService);
    }

    private BriefAppService buildService(TagIndexService tagIndexService) {
        MemoryService memoryService = new MemoryService(fileStorage);
        TradingReviewFileRepository reviewRepo = new TradingReviewFileRepository(fileStorage);
        // 2026-08-26 复盘卡点：hasTradingActivity 依赖 TradingAppService.getDailyTradeSummary——
        // mock 打桩当日无成交（简报测试不关心成交，防 NPE）
        TradingAppService trading = mock(TradingAppService.class);
        when(trading.getDailyTradeSummary(any(), any())).thenReturn(
                new TradingAppService.DailyTradeSummary("2026-08-02", 0, 0, 0,
                        0, 0, java.util.List.of(), null, null));
        return new BriefAppService(
                identityRepository, recordRepository, memoryService,
                aiClient, new TradingReviewAppService(
                        recordRepository, null, mock(AccountSnapshotRepository.class), null, null, reviewRepo, mock(TradingLotService.class),
                        trading, mock(com.adaiadai.core.domain.trading.AdviceHistoryRepository.class)),
                new DomainActivityService(recordRepository),
                new TagRecommendationService(tagIndexService),
                todoRepository,
                // G-2：PluginService（trading 插件开启——简报交易活动信号测试用）
                pluginService("trading")
        );
    }

    /** 简化 PluginService mock：指定用户的插件集（G-2 门控测试）。 */
    private com.adaiadai.core.kernel.plugin.PluginService pluginService(String... plugins) {
        com.adaiadai.core.kernel.account.AccountRepository accounts =
                mock(com.adaiadai.core.kernel.account.AccountRepository.class);
        when(accounts.findById(any())).thenReturn(java.util.Optional.of(
                new com.adaiadai.core.kernel.account.Account(
                        "default", com.adaiadai.core.kernel.account.Account.ROLE_USER, true,
                        java.time.LocalDate.of(2026, 8, 2), java.util.List.of(plugins))));
        return new com.adaiadai.core.kernel.plugin.PluginService(accounts, new com.adaiadai.core.kernel.plugin.PluginRegistry());
    }

    @Test
    void generateBrief_withIdentity() {
        // 先保存用户身份
        fileStorage.write("default", "identity/profile.md", """
                ---
                name: 张三
                preferences:
                  greeting: 随意点
                rules:
                  response_style: 简洁
                ---
                """);

        String brief = briefAppService.generateBrief("default");
        assertNotNull(brief);
        // Mock AI 模式下应该包含"记录: 今日简报"
        assertTrue(brief.contains("记录:"));
    }

    @Test
    void generateBrief_withRecentRecords() {
        recordRepository.save("default",new ContentRecord(
                "rec_20260718_100000",
                "note", "user_input", "测试", "今天买了立昂微",
                List.of("投资"),
                LocalDateTime.now().minusDays(1)
        ));

        String brief = briefAppService.generateBrief("default");
        assertNotNull(brief);
    }

    @Test
    void generateBrief_emptyIdentity() {
        // 不设身份
        String brief = briefAppService.generateBrief("default");
        assertNotNull(brief);
    }

    @Test
    void generateBrief_neverFails() {
        // 极端情况：空的存储
        String brief = briefAppService.generateBrief("default");
        assertNotNull(brief);
        assertFalse(brief.isBlank());
    }

    @Test
    void generateBrief_degradesToEmojiPrefixedLines_noBullet() {
        // 降级路径：AI 调用失败时产出 emoji 前缀行，不再用绿点「• 」（顶部摘要前缀冲突修复）
        aiClient = mock(AiClient.class);
        when(aiClient.understand(any())).thenThrow(new RuntimeException("mock down"));
        TagIndexService tagIndexService = new TagIndexService(fileStorage);
        briefAppService = buildService(tagIndexService);

        String brief = briefAppService.generateBrief("default");
        assertNotNull(brief);
        assertFalse(brief.contains("• "), "降级 brief 不应含绿点前缀");
        assertTrue(brief.contains("📋"), "降级 brief 应含记录统计行（📋，08-14 降级增强）");
        assertTrue(brief.contains("☕"), "降级 brief 应含收尾行（☕）");
    }

    @Test
    void greetingForHour_boundaries() {
        // 凌晨 0-5 → 深夜好（#14：之前误归「早上好/morning」）
        assertEquals("深夜好", BriefAppService.greetingForHour(0));
        assertEquals("深夜好", BriefAppService.greetingForHour(5));
        assertEquals("late night", BriefAppService.greetingEnForHour(0));
        assertEquals("late night", BriefAppService.greetingEnForHour(5));
        // 早上 6-10（#222：原 6-11，11 归中午段）
        assertEquals("早上好", BriefAppService.greetingForHour(6));
        assertEquals("早上好", BriefAppService.greetingForHour(10));
        assertEquals("morning", BriefAppService.greetingEnForHour(6));
        assertEquals("morning", BriefAppService.greetingEnForHour(10));
        // 中午 11-13（#222：12 点不再机械归「下午好」）
        assertEquals("中午好", BriefAppService.greetingForHour(11));
        assertEquals("中午好", BriefAppService.greetingForHour(13));
        assertEquals("midday", BriefAppService.greetingEnForHour(11));
        assertEquals("midday", BriefAppService.greetingEnForHour(13));
        // 下午 14-17（#222：原 12-17）
        assertEquals("下午好", BriefAppService.greetingForHour(14));
        assertEquals("下午好", BriefAppService.greetingForHour(17));
        assertEquals("afternoon", BriefAppService.greetingEnForHour(14));
        assertEquals("afternoon", BriefAppService.greetingEnForHour(17));
        // 晚上 18-23
        assertEquals("晚上好", BriefAppService.greetingForHour(18));
        assertEquals("晚上好", BriefAppService.greetingForHour(23));
        assertEquals("evening", BriefAppService.greetingEnForHour(18));
        assertEquals("evening", BriefAppService.greetingEnForHour(23));
    }

    @Test
    void emojiForHour_matchesGreetingPeriods() {
        // #221/#222：降级问候 emoji 按时段——凌晨不再配 ☀️（语义矛盾），中午/下午独立 emoji
        assertEquals("🌙", BriefAppService.emojiForHour(0));
        assertEquals("🌙", BriefAppService.emojiForHour(5));
        assertEquals("☀️", BriefAppService.emojiForHour(6));
        assertEquals("☀️", BriefAppService.emojiForHour(10));
        assertEquals("🌤️", BriefAppService.emojiForHour(11));
        assertEquals("🌤️", BriefAppService.emojiForHour(13));
        assertEquals("🌇", BriefAppService.emojiForHour(14));
        assertEquals("🌇", BriefAppService.emojiForHour(17));
        assertEquals("✨", BriefAppService.emojiForHour(18));
        assertEquals("✨", BriefAppService.emojiForHour(23));
    }

    // ── RFC 20260923 A 批：周期习惯不是待办（概览卡天天提醒的止血闸门）──

    @Test
    void isRhythmLike_distinguishesRhythmFromOneOffTasks() {
        // 周期习惯 → 命中（不该被当待办催）
        assertTrue(BriefAppService.isRhythmLike("周四固定发版加班"), "生产实据原句必须命中");
        assertTrue(BriefAppService.isRhythmLike("每周四发版"));
        assertTrue(BriefAppService.isRhythmLike("每周给妈打个电话"));
        assertTrue(BriefAppService.isRhythmLike("每天跑步半小时"));
        assertTrue(BriefAppService.isRhythmLike("每月 1 号交房租"));
        assertTrue(BriefAppService.isRhythmLike("周三固定例会"));
        // 一次性任务 → 不得命中（否则真待办会被静默吞掉）
        assertFalse(BriefAppService.isRhythmLike("周四要交周报"), "含「周四」但非周期，不得误伤");
        assertFalse(BriefAppService.isRhythmLike("给妈打个电话"));
        assertFalse(BriefAppService.isRhythmLike("整理上周复盘"));
        assertFalse(BriefAppService.isRhythmLike("准备周会材料"));
        assertFalse(BriefAppService.isRhythmLike(null));
    }

    @Test
    void buildBriefPrompt_rhythmTodoHeldBack_fromRemindSection() {
        when(todoRepository.findAll(TodoStatus.OPEN, "default")).thenReturn(List.of(
                new Todo("todo_rhythm", "周四固定发版加班", TodoStatus.OPEN, null,
                        java.time.LocalDate.now(), java.time.LocalDate.now()),
                new Todo("todo_task", "周四要交周报", TodoStatus.OPEN, null,
                        java.time.LocalDate.now(), java.time.LocalDate.now())));

        briefAppService.generateBrief("default");

        String prompt = recordingAi.lastPrompt;
        assertNotNull(prompt, "应捕获到简报 prompt");
        assertFalse(prompt.contains("周四固定发版加班"),
                "周期性习惯不得进入提醒段（闸 2）");
        assertTrue(prompt.contains("周四要交周报"),
                "同批的一次性任务仍应正常注入——闸门只挡节律，不吞真待办");
        // 正面成因（原「发现习惯就自然提及」指令）已删除，且新增禁止编造提醒的规则 8
        assertFalse(prompt.contains("mention it naturally"), "习惯注入指令应已删除");
        assertTrue(prompt.contains("Never invent reminders"), "规则 8 应存在");
    }

    @Test
    void buildBriefPrompt_allRhythmTodos_sectionAbsent() {
        when(todoRepository.findAll(TodoStatus.OPEN, "default")).thenReturn(List.of(
                new Todo("todo_rhythm", "每天跑步半小时", TodoStatus.OPEN, null,
                        java.time.LocalDate.now(), java.time.LocalDate.now())));

        briefAppService.generateBrief("default");

        assertFalse(recordingAi.lastPrompt.contains("Open todos (not done"),
                "全是节律时提醒段整体缺席——沉默是默认项（宁可少说）");
    }

    /** 记录最后一次 prompt 的 AiClient 装饰器（其余行为委托 TestAiClient）。 */
    private static final class RecordingAiClient implements AiClient {
        private final AiClient delegate;
        String lastPrompt;

        RecordingAiClient(AiClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public AiUnderstanding understand(ContextPackage contextPackage) {
            this.lastPrompt = contextPackage.prompt();
            return delegate.understand(contextPackage);
        }

        @Override
        public String generate(ContextPackage contextPackage, String systemPrompt) {
            return delegate.generate(contextPackage, systemPrompt);
        }

        @Override
        public String recognizeIntent(String content) {
            return delegate.recognizeIntent(content);
        }
    }
}
