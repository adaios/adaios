package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.identity.IdentityProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IdentityFileRepository 测试。
 * 验证 profile.md 的读写、默认降级、解析逻辑。
 */
class IdentityFileRepositoryTest {

    private InMemoryFileStorage fileStorage;
    private IdentityFileRepository repository;

    @BeforeEach
    void setUp() {
        fileStorage = new InMemoryFileStorage();
        repository = new IdentityFileRepository(fileStorage);
    }

    @Test
    void load_whenFileMissing_returnsDefault() {
        // 文件不存在时返回默认档案，不抛异常
        Optional<IdentityProfile> result = repository.load();
        assertTrue(result.isPresent());
        assertEquals("阿呆", result.get().name());
    }

    @Test
    void saveAndLoad() {
        IdentityProfile profile = new IdentityProfile(
                "测试用户",
                Map.of("语言", "中文", "风格", "简洁"),
                Map.of("确认", "交易需确认"),
                List.of("投资", "科技")
        );

        repository.save(profile);
        Optional<IdentityProfile> loaded = repository.load();

        assertTrue(loaded.isPresent());
        assertEquals("测试用户", loaded.get().name());
        assertEquals("中文", loaded.get().preferences().get("语言"));
        assertEquals("交易需确认", loaded.get().rules().get("确认"));
        assertTrue(loaded.get().tags().contains("投资"));
        assertTrue(loaded.get().tags().contains("科技"));
    }

    @Test
    void saveOverwrite() {
        IdentityProfile p1 = new IdentityProfile("用户A", Map.of(), Map.of(), List.of());
        repository.save(p1);

        IdentityProfile p2 = new IdentityProfile("用户B", Map.of(), Map.of(), List.of("标签"));
        repository.save(p2);

        Optional<IdentityProfile> loaded = repository.load();
        assertTrue(loaded.isPresent());
        assertEquals("用户B", loaded.get().name());
        assertEquals(1, loaded.get().tags().size());
    }

    @Test
    void load_malformedFrontmatter_returnsDefault() {
        // 写入无效内容
        fileStorage.write("identity/profile.md", "这不是有效的 frontmatter 格式");
        Optional<IdentityProfile> result = repository.load();
        assertTrue(result.isPresent());
        assertEquals("阿呆", result.get().name());
    }
}
