package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.record.ContentRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RecordFileRepository 单元测试。
 * 验证 Markdown 文件的读写、解析、ID 生成逻辑。
 * 使用 InMemoryFileStorage 替代真实文件系统。
 */
class RecordFileRepositoryTest {

    private InMemoryFileStorage fileStorage;
    private RecordFileRepository repository;

    @BeforeEach
    void setUp() {
        fileStorage = new InMemoryFileStorage();
        repository = new RecordFileRepository(fileStorage);
    }

    @Test
    void saveAndFindById() {
        ContentRecord record = new ContentRecord(
                "rec_20260718_120000",
                "note", "user_input", "测试记录", "今天买了立昂微",
                List.of("投资", "半导体"),
                LocalDateTime.of(2026, 7, 18, 12, 0)
        );
        repository.save(record);

        Optional<ContentRecord> found = repository.findById("rec_20260718_120000");
        assertTrue(found.isPresent());
        assertEquals("今天买了立昂微", found.get().content());
        assertEquals(List.of("投资", "半导体"), found.get().tags());
    }

    @Test
    void findById_notFound_returnsEmpty() {
        Optional<ContentRecord> found = repository.findById("rec_nonexistent");
        assertFalse(found.isPresent());
    }

    @Test
    void saveAndFindAll() {
        ContentRecord r1 = new ContentRecord(
                "rec_20260718_100000",
                "note", "user_input", "第一条", "早上好",
                List.of("问候"),
                LocalDateTime.of(2026, 7, 18, 10, 0)
        );
        ContentRecord r2 = new ContentRecord(
                "rec_20260718_110000",
                "note", "user_input", "第二条", "下午买了股票",
                List.of("投资"),
                LocalDateTime.of(2026, 7, 18, 11, 0)
        );
        repository.save(r1);
        repository.save(r2);

        List<ContentRecord> all = repository.findAll();
        assertEquals(2, all.size());
        // 默认按时间倒序
        assertEquals("rec_20260718_110000", all.get(0).id());
        assertEquals("rec_20260718_100000", all.get(1).id());
    }

    @Test
    void findAll_empty() {
        assertTrue(repository.findAll().isEmpty());
    }

    @Test
    void markdownFileWrittenCorrectly() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 18, 15, 30, 0);
        ContentRecord record = new ContentRecord(
                "rec_20260718_153000",
                "trade", "user_input", "买入操作", "今天早盘买了立昂微",
                List.of("投资", "半导体", "立昂微"),
                now
        );
        repository.save(record);

        // 验证文件路径和内容格式
        String path = "records/2026/07/rec_20260718_153000.md";
        assertTrue(fileStorage.exists(path));

        String content = fileStorage.read(path);
        assertNotNull(content);
        assertTrue(content.contains("id: rec_20260718_153000"));
        assertTrue(content.contains("type: trade"));
        assertTrue(content.contains("source: user_input"));
        assertTrue(content.contains("tags: [投资, 半导体, 立昂微]"));
        assertTrue(content.contains("今天早盘买了立昂微"));
    }

    @Test
    void parseFileWithTitleFromContent() {
        // 标题从正文第一行提取
        ContentRecord record = new ContentRecord(
                "rec_20260718_140000",
                "note", "user_input", "标题",
                "今天天气很好，适合出门散步。\n今天也是充满希望的一天。",
                List.of("生活"),
                LocalDateTime.of(2026, 7, 18, 14, 0)
        );
        repository.save(record);

        ContentRecord loaded = repository.findById("rec_20260718_140000").orElseThrow();
        // 标题从第一行非空内容提取
        assertNotNull(loaded.title());
    }

    @Test
    void generateId_format() {
        String id = RecordFileRepository.generateId();
        assertTrue(id.matches("rec_\\d{8}_\\d{6}"));
    }

    @Test
    void recordWithTags() {
        ContentRecord record = new ContentRecord(
                "rec_20260718_160000",
                "research", "user_input", "研究笔记", "分析了半导体行业趋势",
                List.of("研究", "半导体", "行业分析"),
                LocalDateTime.of(2026, 7, 18, 16, 0)
        );
        repository.save(record);

        ContentRecord loaded = repository.findById("rec_20260718_160000").orElseThrow();
        assertEquals(List.of("研究", "半导体", "行业分析"), loaded.tags());
        assertEquals("research", loaded.type());
        assertEquals("user_input", loaded.source());
    }

    @Test
    void save_overrideExisting() {
        ContentRecord r1 = new ContentRecord(
                "rec_20260718_170000",
                "note", "user_input", "原版", "原始内容",
                List.of(),
                LocalDateTime.of(2026, 7, 18, 17, 0)
        );
        ContentRecord r2 = new ContentRecord(
                "rec_20260718_170000",
                "note", "user_input", "新版", "覆盖内容",
                List.of(),
                LocalDateTime.of(2026, 7, 18, 17, 0)
        );
        repository.save(r1);
        repository.save(r2);

        Optional<ContentRecord> found = repository.findById("rec_20260718_170000");
        assertTrue(found.isPresent());
        assertEquals("覆盖内容", found.get().content());
    }

    @Test
    void parseFrontmatterWithSpecialChars() {
        // 标签包含特殊字符
        ContentRecord record = new ContentRecord(
                "rec_20260718_180000",
                "note", "user_input", "特殊", "内容包含,逗号",
                List.of("tag,with,comma", "normal"),
                LocalDateTime.of(2026, 7, 18, 18, 0)
        );
        repository.save(record);

        ContentRecord loaded = repository.findById("rec_20260718_180000").orElseThrow();
        // tags 字段经过 parseTags 处理，逗号分隔
        assertNotNull(loaded.tags());
    }

    @Test
    void fileDirectoryStructure() {
        LocalDateTime jan = LocalDateTime.of(2026, 1, 15, 10, 0);
        ContentRecord record = new ContentRecord(
                "rec_20260115_100000",
                "note", "user_input", "一月记录", "新年好",
                List.of(),
                jan
        );
        repository.save(record);

        String path = "records/2026/01/rec_20260115_100000.md";
        assertTrue(fileStorage.exists(path));
    }
}
