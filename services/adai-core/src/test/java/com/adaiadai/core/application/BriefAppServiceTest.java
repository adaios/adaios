package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.ai.llm.MockAiClient;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.infrastructure.storage.IdentityFileRepository;
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
        recordRepository = new RecordFileRepository(fileStorage);
        identityRepository = new IdentityFileRepository(fileStorage);
        MemoryService memoryService = new MemoryService(fileStorage);
        briefAppService = new BriefAppService(
                identityRepository, recordRepository, memoryService, new MockAiClient()
        );
    }

    @Test
    void generateBrief_withIdentity() {
        // 先保存用户身份
        fileStorage.write("identity/profile.md", """
                ---
                name: 张三
                preferences:
                  greeting: 随意点
                rules:
                  response_style: 简洁
                ---
                """);

        String brief = briefAppService.generateBrief();
        assertNotNull(brief);
        // Mock AI 模式下应该包含"记录: 今日简报"
        assertTrue(brief.contains("记录:"));
    }

    @Test
    void generateBrief_withRecentRecords() {
        recordRepository.save(new ContentRecord(
                "rec_20260718_100000",
                "note", "user_input", "测试", "今天买了立昂微",
                List.of("投资"),
                LocalDateTime.now().minusDays(1)
        ));

        String brief = briefAppService.generateBrief();
        assertNotNull(brief);
    }

    @Test
    void generateBrief_emptyIdentity() {
        // 不设身份
        String brief = briefAppService.generateBrief();
        assertNotNull(brief);
    }

    @Test
    void generateBrief_neverFails() {
        // 极端情况：空的存储
        String brief = briefAppService.generateBrief();
        assertNotNull(brief);
        assertFalse(brief.isBlank());
    }
}
