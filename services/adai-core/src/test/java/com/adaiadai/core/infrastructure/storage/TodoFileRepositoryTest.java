package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.kernel.todo.Todo;
import com.adaiadai.core.kernel.todo.TodoRepository;
import com.adaiadai.core.kernel.todo.TodoStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TodoFileRepository 单元测试（RFC 20260917：待办归 Kernel builtin，纯清单两态 + 可选到期日）。
 * 覆盖：多条目解析（历史正则 bug 回归）、多行 title 单行化、CRUD、统计、可选行（due/sourceRecordId）兼容、
 * 旧状态值兜底。
 * 使用 InMemoryFileStorage 替代真实文件系统。
 */
class TodoFileRepositoryTest {

    private InMemoryFileStorage fileStorage;
    private TodoFileRepository repository;

    @BeforeEach
    void setUp() {
        fileStorage = new InMemoryFileStorage();
        repository = new TodoFileRepository(fileStorage);
    }

    private Todo todo(String id, String title, TodoStatus status) {
        LocalDate now = LocalDate.now();
        return new Todo(id, title, status, null, now, now);
    }

    @Test
    void saveAndFindAll_parsesAllEntries() {
        // 回归：旧正则 DOTALL 下 .+ 贪婪跨行，save 多条后 findAll 只能解析出 1 条
        repository.save("default", todo("todo_1", "待办一", TodoStatus.OPEN));
        repository.save("default", todo("todo_2", "待办二", TodoStatus.DONE));
        repository.save("default", todo("todo_3", "待办三", TodoStatus.OPEN));

        List<Todo> all = repository.findAll("default");
        assertEquals(3, all.size());
    }

    @Test
    void saveAndFindAll_preservesFields() {
        LocalDate now = LocalDate.now();
        repository.save("default", new Todo(
                "todo_f1", "给妈打个电话", TodoStatus.OPEN,
                LocalDate.of(2026, 9, 20), "rec_20260917_000000001", now, now));

        Todo loaded = repository.findById("default", "todo_f1").orElseThrow();
        assertEquals("给妈打个电话", loaded.title());
        assertEquals(TodoStatus.OPEN, loaded.status());
        assertEquals(LocalDate.of(2026, 9, 20), loaded.due());
        assertEquals("rec_20260917_000000001", loaded.sourceRecordId());

        String content = fileStorage.read("default", todoPath(now));
        assertTrue(content.contains("due: 2026-09-20"), "应含 due 行: " + content);
        assertTrue(content.contains("sourceRecordId: rec_20260917_000000001"), "应含 sourceRecordId 行");
        assertTrue(content.startsWith("# 待办 - "), "文件头应为「# 待办 - yyyy/MM」");
    }

    @Test
    void save_withoutDue_writesEmptyOptionalLine() {
        repository.save("default", todo("todo_nodue", "没有期限", TodoStatus.OPEN));

        Todo loaded = repository.findById("default", "todo_nodue").orElseThrow();
        assertNull(loaded.due(), "未设到期日应回读为 null（不是空串/报错）");
        assertNull(loaded.sourceRecordId());
    }

    @Test
    void save_multilineTitle_isFlattenedToSingleLine() {
        // 多行 title 曾写坏月文件（6146 行重复堆积）——必须被单行化
        repository.save("default", todo("todo_ml", "标题第一行\n标题第二行\nstatus: 污染", TodoStatus.OPEN));

        Optional<Todo> found = repository.findById("default", "todo_ml");
        assertTrue(found.isPresent());
        assertFalse(found.get().title().contains("\n"), "title 不应含换行");
        assertTrue(found.get().title().startsWith("标题第一行 标题第二行"));

        String content = fileStorage.read("default", todoPath(LocalDate.now()));
        assertNotNull(content);
        assertTrue(content.lines().anyMatch(l -> l.startsWith("title: 标题第一行 标题第二行")),
                "文件中 title 应为单行: " + content);
    }

    @Test
    void save_update_replacesEntryNotDuplicates() {
        repository.save("default", todo("todo_u", "原始", TodoStatus.OPEN));
        repository.save("default", new Todo("todo_u", "更新后", TodoStatus.DONE, null,
                LocalDate.now(), LocalDate.now()));

        List<Todo> all = repository.findAll("default");
        assertEquals(1, all.size(), "更新不应产生重复条目");
        Todo loaded = repository.findById("default", "todo_u").orElseThrow();
        assertEquals("更新后", loaded.title());
        assertEquals(TodoStatus.DONE, loaded.status());
    }

    @Test
    void findById_notFound_returnsEmpty() {
        assertFalse(repository.findById("default", "todo_none").isPresent());
    }

    @Test
    void delete_removesEntry() {
        repository.save("default", todo("todo_d", "待删", TodoStatus.OPEN));
        repository.delete("default", "todo_d");
        assertTrue(repository.findAll("default").isEmpty());
    }

    @Test
    void delete_nonexistent_doesNothing() {
        repository.save("default", todo("todo_k", "保留", TodoStatus.OPEN));
        repository.delete("default", "todo_none");
        assertEquals(1, repository.findAll("default").size());
    }

    @Test
    void saveAndDelete_preservesManualComments() {
        // 用户手写注释（标题与条目之间）在 save 更新 / delete 重建时不应被丢弃（REVIEW #21）
        repository.save("default", todo("todo_c1", "待办一", TodoStatus.OPEN));

        String path = todoPath(LocalDate.now());
        String content = fileStorage.read("default", path);
        String withComment = content.replace(
                "# 待办 - " + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM")),
                "# 待办 - " + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"))
                        + "\n\n手动说明：这周先把搬家的事办了。");
        fileStorage.write("default", path, withComment);

        // save 更新同一条 → 重建文件应保留注释
        repository.save("default", new Todo("todo_c1", "待办一更新", TodoStatus.DONE, null,
                LocalDate.now(), LocalDate.now()));
        String afterSave = fileStorage.read("default", path);
        assertTrue(afterSave.contains("手动说明：这周先把搬家的事办了。"), "save 后手写注释应保留");
        assertEquals(1, repository.findAll("default").size());

        // delete 另一条 → 重建文件也应保留注释
        repository.save("default", todo("todo_c2", "待办二", TodoStatus.OPEN));
        repository.delete("default", "todo_c2");
        String afterDelete = fileStorage.read("default", path);
        assertTrue(afterDelete.contains("手动说明：这周先把搬家的事办了。"), "delete 后手写注释应保留");
    }

    @Test
    void stats_countsByTwoStates() {
        repository.save("default", todo("todo_s1", "a", TodoStatus.OPEN));
        repository.save("default", todo("todo_s2", "b", TodoStatus.OPEN));
        repository.save("default", todo("todo_s3", "c", TodoStatus.DONE));

        TodoRepository.TodoStats stats = repository.stats("default");
        assertEquals(3, stats.total());
        assertEquals(2, stats.open());
        assertEquals(1, stats.done());
    }

    @Test
    void findAll_filterByStatus() {
        repository.save("default", todo("todo_f1", "未完成", TodoStatus.OPEN));
        repository.save("default", todo("todo_f2", "已完成", TodoStatus.DONE));

        List<Todo> open = repository.findAll(TodoStatus.OPEN, "default");
        assertEquals(1, open.size());
        assertEquals("todo_f1", open.get(0).id());

        assertEquals(2, repository.findAll((TodoStatus) null, "default").size(), "status=null 返回全部");
    }

    @Test
    void parse_legacyEntryWithoutOptionalLines_returnsNulls() {
        // 向后兼容：手写条目可能没有 due / sourceRecordId 行——解析应为 null 而非抛错/字段错位
        LocalDate now = LocalDate.now();
        String legacy = """
                # 待办 - %s

                ---
                id: todo_legacy
                title: 手写的待办
                status: OPEN
                createdAt: %s
                updatedAt: %s
                ---
                手写的待办
                """.formatted(now.format(DateTimeFormatter.ofPattern("yyyy/MM")), now, now);
        fileStorage.write("default", todoPath(now), legacy);

        Todo loaded = repository.findById("default", "todo_legacy").orElseThrow();
        assertNull(loaded.due(), "无 due 行应解析 null");
        assertNull(loaded.sourceRecordId(), "无 sourceRecordId 行应解析 null");
        assertEquals("手写的待办", loaded.title());
        assertEquals(TodoStatus.OPEN, loaded.status());
    }

    @Test
    void parse_legacyStatus_readingAsOpen() {
        // 旧看板遗留状态（DOING / CANCELLED）读作 OPEN——待办只有两态，老数据不迁移也不炸
        LocalDate now = LocalDate.now();
        String legacy = """
                # 待办 - %s

                ---
                id: todo_doing
                title: 进行中的老待办
                status: DOING
                createdAt: %s
                updatedAt: %s
                ---
                进行中的老待办
                """.formatted(now.format(DateTimeFormatter.ofPattern("yyyy/MM")), now, now);
        fileStorage.write("default", todoPath(now), legacy);

        Todo loaded = repository.findById("default", "todo_doing").orElseThrow();
        assertEquals(TodoStatus.OPEN, loaded.status(), "非两态旧值读作 OPEN");
    }

    @Test
    void save_multiMonthFiles_allListed() {
        // 待办可能跨月：扫描全部月份文件（比对本机当月路径不同即触发多文件读取）
        LocalDate now = LocalDate.now();
        LocalDate lastMonth = now.minusMonths(1);
        repository.save("default", new Todo("todo_prev", "上个月的事", TodoStatus.OPEN, null,
                lastMonth, lastMonth));
        repository.save("default", new Todo("todo_now", "这个月的事", TodoStatus.OPEN, null,
                now, now));

        assertEquals(2, repository.findAll("default").size(), "跨月文件应都被读到");
    }

    private String todoPath(LocalDate date) {
        return "todos/" + date.format(DateTimeFormatter.ofPattern("yyyy/MM")) + ".md";
    }
}
