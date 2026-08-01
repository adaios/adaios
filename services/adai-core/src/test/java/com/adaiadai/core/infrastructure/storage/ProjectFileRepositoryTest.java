package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.project.Task;
import com.adaiadai.core.domain.project.TaskRepository;
import com.adaiadai.core.domain.project.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProjectFileRepository 单元测试。
 * 覆盖：多条目解析（历史正则 bug 回归）、多行 title 单行化、CRUD、统计。
 * 使用 InMemoryFileStorage 替代真实文件系统。
 */
class ProjectFileRepositoryTest {

    private InMemoryFileStorage fileStorage;
    private ProjectFileRepository repository;

    @BeforeEach
    void setUp() {
        fileStorage = new InMemoryFileStorage();
        repository = new ProjectFileRepository(fileStorage);
    }

    private Task task(String id, String title, String desc, TaskStatus status) {
        LocalDate now = LocalDate.now();
        return new Task(id, title, desc, status, "P1", List.of("测试"), null, now, now);
    }

    @Test
    void saveAndFindAll_parsesAllEntries() {
        // 回归：旧正则 DOTALL 下 .+ 贪婪跨行，save 多条后 findAll 只能解析出 1 条
        repository.save(task("task_1", "任务一", "描述一", TaskStatus.TODO));
        repository.save(task("task_2", "任务二", "描述二", TaskStatus.DONE));
        repository.save(task("task_3", "任务三", "描述三", TaskStatus.TODO));

        List<Task> all = repository.findAll();
        assertEquals(3, all.size());
    }

    @Test
    void saveAndFindAll_preservesFields() {
        repository.save(new Task(
                "task_f1", "行情接入", "腾讯行情数据源", TaskStatus.DOING,
                "P0", List.of("后端", "交易"), "20260730-market-data-and-push",
                LocalDate.now(), LocalDate.now()));

        Task loaded = repository.findById("task_f1").orElseThrow();
        assertEquals("行情接入", loaded.title());
        assertEquals("腾讯行情数据源", loaded.description());
        assertEquals(TaskStatus.DOING, loaded.status());
        assertEquals("P0", loaded.priority());
        assertEquals(List.of("后端", "交易"), loaded.tags());
        assertEquals("20260730-market-data-and-push", loaded.rfcRef());
    }

    @Test
    void save_multilineTitle_isFlattenedToSingleLine() {
        // 多行 title 曾写坏 07.md（6146 行重复堆积）——必须被单行化
        repository.save(task("task_ml", "标题第一行\n标题第二行\nstatus: 污染", "正常描述", TaskStatus.TODO));

        Optional<Task> found = repository.findById("task_ml");
        assertTrue(found.isPresent());
        assertFalse(found.get().title().contains("\n"), "title 不应含换行");
        assertTrue(found.get().title().startsWith("标题第一行 标题第二行"));

        // 文件落盘时 title 行必须是单行
        String path = taskPath(LocalDate.now());
        String content = fileStorage.read(path);
        assertNotNull(content);
        assertTrue(content.lines().anyMatch(l -> l.startsWith("title: 标题第一行 标题第二行")),
                "文件中 title 应为单行: " + content);
    }

    @Test
    void save_update_replacesEntryNotDuplicates() {
        repository.save(task("task_u", "原始", "原始描述", TaskStatus.TODO));
        repository.save(task("task_u", "更新后", "更新描述", TaskStatus.DOING));

        List<Task> all = repository.findAll();
        assertEquals(1, all.size(), "更新不应产生重复条目");
        Task loaded = repository.findById("task_u").orElseThrow();
        assertEquals("更新后", loaded.title());
        assertEquals(TaskStatus.DOING, loaded.status());
    }

    @Test
    void findById_notFound_returnsEmpty() {
        assertFalse(repository.findById("task_none").isPresent());
    }

    @Test
    void delete_removesEntry() {
        repository.save(task("task_d", "待删", "描述", TaskStatus.TODO));
        repository.delete("task_d");
        assertTrue(repository.findAll().isEmpty());
    }

    @Test
    void delete_nonexistent_doesNothing() {
        repository.save(task("task_k", "保留", "描述", TaskStatus.TODO));
        repository.delete("task_none");
        assertEquals(1, repository.findAll().size());
    }

    @Test
    void saveAndDelete_preservesManualComments() {
        // 用户手写注释（标题与条目之间）在 save 更新 / delete 重建时不应被丢弃（REVIEW #21）
        repository.save(task("task_c1", "任务一", "描述一", TaskStatus.TODO));

        String path = taskPath(LocalDate.now());
        String content = fileStorage.read(path);
        String withComment = content.replace(
                "# 任务 - " + LocalDate.now(),
                "# 任务 - " + LocalDate.now() + "\n\n手动说明：本周重点做 A 方向。");
        fileStorage.write(path, withComment);

        // save 更新同一任务 → 重建文件应保留注释
        repository.save(task("task_c1", "任务一更新", "描述一", TaskStatus.DOING));
        String afterSave = fileStorage.read(path);
        assertTrue(afterSave.contains("手动说明：本周重点做 A 方向。"), "save 后手写注释应保留");
        assertEquals(1, repository.findAll().size());
        assertEquals("任务一更新", repository.findById("task_c1").orElseThrow().title());

        // delete 另一任务 → 重建文件也应保留注释
        repository.save(task("task_c2", "任务二", "描述二", TaskStatus.TODO));
        repository.delete("task_c2");
        String afterDelete = fileStorage.read(path);
        assertTrue(afterDelete.contains("手动说明：本周重点做 A 方向。"), "delete 后手写注释应保留");
    }

    @Test
    void stats_countsByStatus() {
        repository.save(task("task_s1", "a", "1", TaskStatus.TODO));
        repository.save(task("task_s2", "b", "2", TaskStatus.DOING));
        repository.save(task("task_s3", "c", "3", TaskStatus.DONE));
        repository.save(task("task_s4", "d", "4", TaskStatus.CANCELLED));

        TaskRepository.TaskStats stats = repository.stats();
        assertEquals(4, stats.total());
        assertEquals(1, stats.todo());
        assertEquals(1, stats.doing());
        assertEquals(1, stats.done());
        assertEquals(1, stats.cancelled());
    }

    @Test
    void findAll_filterByStatusAndTag() {
        repository.save(task("task_f1", "后端任务", "1", TaskStatus.TODO));
        repository.save(new Task("task_f2", "前端任务", "2", TaskStatus.DONE,
                "P1", List.of("前端"), null, LocalDate.now(), LocalDate.now()));

        List<Task> byStatus = repository.findAll(TaskStatus.DONE, null);
        assertEquals(1, byStatus.size());
        assertEquals("task_f2", byStatus.get(0).id());

        List<Task> byTag = repository.findAll(null, "前端");
        assertEquals(1, byTag.size());
        assertEquals("task_f2", byTag.get(0).id());
    }

    private String taskPath(LocalDate date) {
        String ym = date.format(DateTimeFormatter.ofPattern("yyyy/MM"));
        return "project/tasks/" + ym + ".md";
    }
}
