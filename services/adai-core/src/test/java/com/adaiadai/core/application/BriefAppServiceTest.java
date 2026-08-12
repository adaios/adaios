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

/**
 * BriefAppService 单元测试。
 * 验证简报生成的基本行为（Mock AI 模式下返回固定格式）。
 */
class BriefAppServiceTest {

    private InMemoryFileStorage fileStorage;
    private RecordFileRepository recordRepository;
    private IdentityFileRepository identityRepository;
    private BriefAppService briefAppService;

    @BeforeEach
    void setUp() {
        fileStorage = new InMemoryFileStorage();
        TagIndexService tagIndexService = new TagIndexService(fileStorage);
        recordRepository = new RecordFileRepository(fileStorage);
        recordRepository.setTagIndexService(tagIndexService);
        identityRepository = new IdentityFileRepository(fileStorage);
        MemoryService memoryService = new MemoryService(fileStorage);
        TradingReviewFileRepository reviewRepo = new TradingReviewFileRepository(fileStorage);
        briefAppService = new BriefAppService(
                identityRepository, recordRepository, memoryService,
                new TestAiClient(), new TradingReviewAppService(
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
    void greetingForHour_boundaries() {
        // 凌晨 0-5 → 深夜好（#14：之前误归「早上好/morning」）
        assertEquals("深夜好", BriefAppService.greetingForHour(0));
        assertEquals("深夜好", BriefAppService.greetingForHour(5));
        assertEquals("late night", BriefAppService.greetingEnForHour(0));
        assertEquals("late night", BriefAppService.greetingEnForHour(5));
        // 早上 6-11
        assertEquals("早上好", BriefAppService.greetingForHour(6));
        assertEquals("早上好", BriefAppService.greetingForHour(11));
        assertEquals("morning", BriefAppService.greetingEnForHour(6));
        assertEquals("morning", BriefAppService.greetingEnForHour(11));
        // 下午 12-17
        assertEquals("下午好", BriefAppService.greetingForHour(12));
        assertEquals("下午好", BriefAppService.greetingForHour(17));
        assertEquals("afternoon", BriefAppService.greetingEnForHour(12));
        assertEquals("afternoon", BriefAppService.greetingEnForHour(17));
        // 晚上 18-23
        assertEquals("晚上好", BriefAppService.greetingForHour(18));
        assertEquals("晚上好", BriefAppService.greetingForHour(23));
        assertEquals("evening", BriefAppService.greetingEnForHour(18));
        assertEquals("evening", BriefAppService.greetingEnForHour(23));
    }

    @Test
    void emojiForHour_matchesGreetingPeriods() {
        // #221：降级问候 emoji 按时段——凌晨不再配 ☀️（语义矛盾）
        assertEquals("🌙", BriefAppService.emojiForHour(0));
        assertEquals("🌙", BriefAppService.emojiForHour(5));
        assertEquals("☀️", BriefAppService.emojiForHour(6));
        assertEquals("☀️", BriefAppService.emojiForHour(11));
        assertEquals("🌤️", BriefAppService.emojiForHour(12));
        assertEquals("🌤️", BriefAppService.emojiForHour(17));
        assertEquals("✨", BriefAppService.emojiForHour(18));
        assertEquals("✨", BriefAppService.emojiForHour(23));
    }
}
