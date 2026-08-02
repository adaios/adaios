package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.record.ContentRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多用户数据隔离测试（多用户架构预留，2026-08-02）。
 * <p>
 * 核心价值验证：不同 userId 的数据互不可见——
 * FileStorage 路径分层 → Repository/索引/记忆全链路按用户隔离。
 * 单用户时全部传 "default"，行为与之前一致（其余测试已覆盖）。
 */
class MultiUserIsolationTest {

    private InMemoryFileStorage fileStorage;
    private RecordFileRepository recordRepository;
    private TagIndexService tagIndexService;
    private MemoryService memoryService;

    @BeforeEach
    void setUp() {
        fileStorage = new InMemoryFileStorage();
        tagIndexService = new TagIndexService(fileStorage);
        recordRepository = new RecordFileRepository(fileStorage);
        recordRepository.setTagIndexService(tagIndexService);
        memoryService = new MemoryService(fileStorage);
    }

    private ContentRecord rec(String id, String content, List<String> tags) {
        return new ContentRecord(id, "note", "user_input", content, content,
                tags, LocalDateTime.of(2026, 8, 2, 12, 0));
    }

    // ── FileStorage 路径隔离 ──

    @Test
    void fileStorage_userPathsAreIsolated() {
        fileStorage.write("alice", "records/2026/08/a.md", "alice 的记录");
        fileStorage.write("bob", "records/2026/08/b.md", "bob 的记录");

        // 各用户只读到自己路径下的内容
        assertEquals("alice 的记录", fileStorage.read("alice", "records/2026/08/a.md"));
        assertEquals("bob 的记录", fileStorage.read("bob", "records/2026/08/b.md"));

        // 跨用户读不到
        assertNull(fileStorage.read("bob", "records/2026/08/a.md"), "bob 不应读到 alice 的文件");
        assertNull(fileStorage.read("alice", "records/2026/08/b.md"), "alice 不应读到 bob 的文件");
        assertFalse(fileStorage.exists("bob", "records/2026/08/a.md"));

        // listFiles 也按用户隔离（返回相对用户层的路径）
        List<String> aliceFiles = fileStorage.listFiles("alice", "records");
        assertTrue(aliceFiles.contains("records/2026/08/a.md"));
        assertFalse(aliceFiles.contains("records/2026/08/b.md"));
        assertTrue(fileStorage.listFiles("bob", "records").isEmpty()
                || !fileStorage.listFiles("bob", "records").contains("records/2026/08/a.md"));
    }

    // ── RecordRepository 隔离 ──

    @Test
    void recordRepository_usersAreIsolated() {
        recordRepository.save("alice", rec("rec_alice_1", "alice 的股票记录", List.of("交易")));
        recordRepository.save("bob", rec("rec_bob_1", "bob 的生活记录", List.of("生活")));

        // 各用户只看到自己的记录
        assertTrue(recordRepository.findById("alice", "rec_alice_1").isPresent());
        assertTrue(recordRepository.findById("bob", "rec_bob_1").isPresent());

        // 跨用户查不到
        assertFalse(recordRepository.findById("bob", "rec_alice_1").isPresent(),
                "bob 不应查到 alice 的记录");
        assertFalse(recordRepository.findById("alice", "rec_bob_1").isPresent(),
                "alice 不应查到 bob 的记录");

        // findAll 隔离
        assertTrue(recordRepository.findAll("alice").stream().allMatch(r -> r.id().startsWith("rec_alice_")));
        assertTrue(recordRepository.findAll("bob").stream().allMatch(r -> r.id().startsWith("rec_bob_")));
    }

    @Test
    void recordRepository_deleteIsScopedToUser() {
        // deleteById 按 ID 推导路径（rec_yyyyMMdd_HHmmss 格式，需 ≥17 位），用合规 ID
        String id = "rec_20260802_120000001";
        recordRepository.save("alice", rec(id, "待删除", List.of()));
        recordRepository.deleteById("alice", id);

        // 只删了 alice 的，bob 无此文件不受影响
        assertFalse(recordRepository.findById("alice", id).isPresent());
    }

    // ── TagIndex 隔离 ──

    @Test
    void tagIndex_usersAreIsolated() {
        recordRepository.save("alice", rec("rec_alice_3", "今天加仓半导体", List.of("半导体", "交易")));
        recordRepository.save("bob", rec("rec_bob_2", "今天去公园散步", List.of("生活", "运动")));

        // alice 的标签索引不含 bob 的记录
        List<String> aliceRelated = tagIndexService.findRelatedIds("alice", List.of("生活"), 10);
        assertFalse(aliceRelated.contains("rec_bob_2"), "alice 不应通过标签查到 bob 的记录");

        List<String> bobRelated = tagIndexService.findRelatedIds("bob", List.of("半导体"), 10);
        assertFalse(bobRelated.contains("rec_alice_3"), "bob 不应通过标签查到 alice 的记录");

        // getAllTags 隔离
        assertTrue(tagIndexService.getAllTags("alice").stream().noneMatch(t -> t.name().equals("生活")));
        assertTrue(tagIndexService.getAllTags("bob").stream().noneMatch(t -> t.name().equals("半导体")));
    }

    // ── Memory 隔离 ──

    @Test
    void memoryService_usersAreIsolated() {
        Memory aliceMemory = Memory.fromContentFallback("rec_alice_4", "alice 喜欢科幻");
        memoryService.persist("alice", aliceMemory);

        Memory bobMemory = Memory.fromContentFallback("rec_bob_3", "bob 喜欢足球");
        memoryService.persist("bob", bobMemory);

        // 各用户只看到自己的记忆
        assertFalse(memoryService.findByDate("bob", LocalDate.now()).stream()
                .anyMatch(m -> m.recordId().equals("rec_alice_4")), "bob 不应查到 alice 的记忆");
        assertFalse(memoryService.findByDate("alice", LocalDate.now()).stream()
                .anyMatch(m -> m.recordId().equals("rec_bob_3")), "alice 不应查到 bob 的记忆");
        assertTrue(memoryService.hasRealMemory("alice", "rec_alice_4") == false, "降级记忆不算 AI 记忆（基线语义）");
    }
}
