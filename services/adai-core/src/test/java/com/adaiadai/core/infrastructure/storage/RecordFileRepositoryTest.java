package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.record.ContentRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * RecordFileRepository 单元测试。
 * 验证 Markdown 文件的读写、解析、ID 生成逻辑。
 * 使用 InMemoryFileStorage 替代真实文件系统。
 */
class RecordFileRepositoryTest {

    private InMemoryFileStorage fileStorage;
    private RecordFileRepository repository;
    private TagIndexService tagIndexService;

    @BeforeEach
    void setUp() {
        fileStorage = new InMemoryFileStorage();
        tagIndexService = new TagIndexService(fileStorage);
        repository = new RecordFileRepository(fileStorage);
        repository.setTagIndexService(tagIndexService);
    }

    @Test
    void saveAndFindById() {
        ContentRecord record = new ContentRecord(
                "rec_20260718_120000",
                "note", "user_input", "测试记录", "今天买了立昂微",
                List.of("投资", "半导体"),
                LocalDateTime.of(2026, 7, 18, 12, 0)
        );
        repository.save("default",record);

        Optional<ContentRecord> found = repository.findById("default","rec_20260718_120000");
        assertTrue(found.isPresent());
        assertEquals("今天买了立昂微", found.get().content());
        assertEquals(List.of("投资", "半导体"), found.get().tags());
    }

    @Test
    void findById_notFound_returnsEmpty() {
        Optional<ContentRecord> found = repository.findById("default","rec_nonexistent");
        assertFalse(found.isPresent());
    }

    @Test
    void findById_standardIdMissingFile_returnsEmptyWithoutWarn() {
        // 2026-08-27：image_qa 引用悬空（被引用的图片记录已不存在）→ 标准格式 id 直读路径
        // 文件不存在应静默返回 empty，不经过 parseFromFile 的 WARN（REVIEW #248 只针对存在但损坏）。
        InMemoryFileStorage spyStorage = Mockito.spy(new InMemoryFileStorage());
        RecordFileRepository repo = new RecordFileRepository(spyStorage);
        Optional<ContentRecord> found = repo.findById("default", "rec_20260811_232734755");
        assertFalse(found.isPresent());
        // 关键：exists 短路后不触发 read（否则 parseFromFile 会打「文件为空或不可读」WARN）
        verify(spyStorage, never()).read(Mockito.anyString(), Mockito.anyString());
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
        repository.save("default",r1);
        repository.save("default",r2);

        List<ContentRecord> all = repository.findAll("default");
        assertEquals(2, all.size());
        // 默认按时间倒序
        assertEquals("rec_20260718_110000", all.get(0).id());
        assertEquals("rec_20260718_100000", all.get(1).id());
    }

    @Test
    void findAll_empty() {
        assertTrue(repository.findAll("default").isEmpty());
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
        repository.save("default",record);

        // 验证文件路径和内容格式
        String path = "records/2026/07/rec_20260718_153000.md";
        assertTrue(fileStorage.exists("default",path));

        String content = fileStorage.read("default",path);
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
        repository.save("default",record);

        ContentRecord loaded = repository.findById("default","rec_20260718_140000").orElseThrow();
        // 标题从第一行非空内容提取
        assertNotNull(loaded.title());
    }

    @Test
    void parseConversationTitle_stripsSpeakerPrefix() {
        // E-A：conversation 正文为「我：/你：」对话原文 → 标题剥角色前缀保持干净
        ContentRecord record = new ContentRecord(
                "rec_20260915_100000",
                "conversation", "user_input", "标题",
                "我：我最近睡不好\n你：是不是想太多了",
                List.of(),
                LocalDateTime.of(2026, 9, 15, 10, 0)
        );
        repository.save("default", record);

        ContentRecord loaded = repository.findById("default", "rec_20260915_100000").orElseThrow();
        assertEquals("我最近睡不好", loaded.title(), "对话记录标题应剥掉「我：」前缀");
    }

    @Test
    void parseNoteTitle_keepsLeadingSpeakerLikeText() {
        // 反向锁：剥离只对 conversation 生效——note 正文真以「我：」开头时不得改动
        ContentRecord record = new ContentRecord(
                "rec_20260915_100001",
                "note", "user_input", "标题",
                "我：这是我的口头禅开场",
                List.of(),
                LocalDateTime.of(2026, 9, 15, 10, 1)
        );
        repository.save("default", record);

        ContentRecord loaded = repository.findById("default", "rec_20260915_100001").orElseThrow();
        assertEquals("我：这是我的口头禅开场", loaded.title(), "note 记录标题不得被剥前缀");
    }

    @Test
    void generateId_format() {
        String id = RecordFileRepository.generateId();
        assertTrue(id.matches("rec_\\d{8}_\\d{9}"), "ID 应含毫秒精度: " + id);
    }

    @Test
    void recordWithTags() {
        ContentRecord record = new ContentRecord(
                "rec_20260718_160000",
                "research", "user_input", "研究笔记", "分析了半导体行业趋势",
                List.of("研究", "半导体", "行业分析"),
                LocalDateTime.of(2026, 7, 18, 16, 0)
        );
        repository.save("default",record);

        ContentRecord loaded = repository.findById("default","rec_20260718_160000").orElseThrow();
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
        repository.save("default",r1);
        repository.save("default",r2);

        Optional<ContentRecord> found = repository.findById("default","rec_20260718_170000");
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
        repository.save("default",record);

        ContentRecord loaded = repository.findById("default","rec_20260718_180000").orElseThrow();
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
        repository.save("default",record);

        String path = "records/2026/01/rec_20260115_100000.md";
        assertTrue(fileStorage.exists("default",path));
    }

    @Test
    void deleteById_removesFile() {
        ContentRecord record = new ContentRecord(
                "rec_20260718_120000",
                "note", "user_input", "待删", "这条会被删除",
                List.of("测试"),
                LocalDateTime.of(2026, 7, 18, 12, 0)
        );
        repository.save("default",record);

        // 确认保存成功
        assertTrue(repository.findById("default","rec_20260718_120000").isPresent());

        // 删除
        repository.deleteById("default","rec_20260718_120000");

        // 验证文件已删除
        assertFalse(repository.findById("default","rec_20260718_120000").isPresent());
        String path = "records/2026/07/rec_20260718_120000.md";
        assertFalse(fileStorage.exists("default",path));
    }

    @Test
    void deleteById_wrongFormat_doesNothing() {
        // card_ 前缀不应被 RecordFileRepository 处理
        repository.deleteById("default","card_12345");
        // 没有异常就是成功
    }

    /**
     * 图文一体（RFC 20260815-media-event-unification step-1）：mediaIds 读写兼容——
     * 有附件则落行并往返一致；无附件不落行（旧格式字节零变化）；旧文件缺该字段解析为空列表。
     */
    @Test
    void mediaIds_roundTrip_andLegacyFileWithoutFieldParsesEmpty() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 22, 20, 0);
        ContentRecord withMedia = new ContentRecord("rec_20260922_200000001", "image", "user_input",
                "图文记录", "正文", List.of("群聊"), now, "log", "综合总结", "life",
                List.of("rec_attach_a", "rec_attach_b"));
        repository.save("default", withMedia);

        ContentRecord loaded = repository.findById("default", withMedia.id()).orElseThrow();
        assertEquals(List.of("rec_attach_a", "rec_attach_b"), loaded.mediaIds(), "mediaIds 往返一致");
        assertTrue(loaded.hasMedia());

        // 无附件：不落 mediaIds 行（旧格式零变化）
        ContentRecord plain = new ContentRecord("rec_20260922_200000002", "note", "user_input",
                "笔记", "正文", List.of(), now);
        repository.save("default", plain);
        String raw = fileStorage.read("default", "records/2026/09/" + plain.id() + ".md");
        assertFalse(raw.contains("mediaIds"), "无附件不落 mediaIds 行");
        assertTrue(repository.findById("default", plain.id()).orElseThrow().mediaIds().isEmpty());

        // 旧文件（手写、无该字段）→ 空列表，不报错、不丢记录
        fileStorage.write("default", "records/2026/09/rec_20260922_200000003.md", """
                ---
                id: rec_20260922_200000003
                type: image
                source: user_input
                tags: []
                createdAt: 2026-09-22T20:00:00
                summary: 旧图
                domain: life
                intent: log
                ---
                旧正文
                """);
        ContentRecord legacy = repository.findById("default", "rec_20260922_200000003").orElseThrow();
        assertEquals(List.of(), legacy.mediaIds(), "旧文件缺 mediaIds → 空列表（向后兼容）");
        assertFalse(legacy.hasMedia());
    }
}
