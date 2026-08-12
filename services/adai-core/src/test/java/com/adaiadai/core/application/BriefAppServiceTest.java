package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.ai.llm.TestAiClient;
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
    private com.adaiadai.core.infrastructure.ai.llm.AiClient aiClient;

    @BeforeEach
    void setUp() {
        fileStorage = new InMemoryFileStorage();
        TagIndexService tagIndexService = new TagIndexService(fileStorage);
        recordRepository = new RecordFileRepository(fileStorage);
        recordRepository.setTagIndexService(tagIndexService);
        identityRepository = new IdentityFileRepository(fileStorage);
        MemoryService memoryService = new MemoryService(fileStorage);
        TradingReviewFileRepository reviewRepo = new TradingReviewFileRepository(fileStorage);
        aiClient = new TestAiClient();
        briefAppService = buildService(tagIndexService);
    }

    private BriefAppService buildService(TagIndexService tagIndexService) {
        MemoryService memoryService = new MemoryService(fileStorage);
        TradingReviewFileRepository reviewRepo = new TradingReviewFileRepository(fileStorage);
        return new BriefAppService(
                identityRepository, recordRepository, memoryService,
                aiClient, new TradingReviewAppService(
                        recordRepository, null, null, null, reviewRepo),
                new DomainActivityService(recordRepository),
                new TagRecommendationService(tagIndexService)
        );
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
        aiClient = mock(com.adaiadai.core.infrastructure.ai.llm.AiClient.class);
        when(aiClient.understand(any())).thenThrow(new RuntimeException("mock down"));
        TagIndexService tagIndexService = new TagIndexService(fileStorage);
        briefAppService = buildService(tagIndexService);

        String brief = briefAppService.generateBrief("default");
        assertNotNull(brief);
        assertFalse(brief.contains("• "), "降级 brief 不应含绿点前缀");
        assertTrue(brief.contains("💬"), "降级 brief 第二行应带 💬 emoji");
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
}
