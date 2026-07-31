package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.project.Task;
import com.adaiadai.core.domain.project.TaskRepository;
import com.adaiadai.core.domain.project.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProjectFileRepository 单元测试。
 * <p>
 * 验证任务文件读写：save 不重复、update 覆盖、delete 移除。
 * 使用 InMemoryFileStorage。
 */
class ProjectFileRepositoryTest {

    private InMemoryFileStorage fileStorage;
    private ProjectFileRepository repository;

    @BeforeEach
    void setUp() {
        fileStorage = new InMemoryFileStorage();
        repository = new ProjectFileRepository(fileStorage);
    }

    private Task task(String id, String title, TaskStatus status) {
        return new Task(id, title, "desc", status, "P1", List.of("tag"),
                null, LocalDate.now(), LocalDate.now());
    }

    @Test
    void save_thenFindById() {
        repository.save(task("t1", "任务一", TaskStatus.TODO));
        assertTrue(repository.findById("t1").isPresent());
        assertEquals("任务一", repository.findById("t1").orElseThrow().title());
    }

    @Test
    void save_sameTaskTwice_doesNotDuplicate() {
        // 核心回归：文件损坏的根源是 save() 重复追加。
        // 同一任务连续 save 两次，文件里必须只有一条。
        repository.save(task("t1", "任务一", TaskStatus.TODO));
        repository.save(task("t1", "任务一", TaskStatus.TODO));

        List<Task> all = repository.findAll();
        assertEquals(1, all.size(), "重复 save 不应产生重复条目");
        assertEquals("t1", all.get(0).id());
    }

    @Test
    void save_multipleDifferentTasks() {
        repository.save(task("t1", "任务一", TaskStatus.TODO));
        repository.save(task("t2", "任务二", TaskStatus.DOING));
        repository.save(task("t3", "任务三", TaskStatus.DONE));

        List<Task> all = repository.findAll();
        assertEquals(3, all.size());
    }

    @Test
    void save_updateOverridesStatus() {
        repository.save(task("t1", "任务一", TaskStatus.TODO));
        repository.save(task("t1", "任务一", TaskStatus.DONE));

        List<Task> all = repository.findAll();
        assertEquals(1, all.size(), "更新不应新增条目");
        assertEquals(TaskStatus.DONE, all.get(0).status());
    }

    @Test
    void delete_removesEntry() {
        repository.save(task("t1", "任务一", TaskStatus.TODO));
        repository.save(task("t2", "任务二", TaskStatus.TODO));
        repository.delete("t1");

        List<Task> all = repository.findAll();
        assertEquals(1, all.size());
        assertEquals("t2", all.get(0).id());
    }

    @Test
    void delete_nonexistent_noThrow() {
        repository.delete("nope"); // 不应抛异常
        assertEquals(0, repository.findAll().size());
    }

    @Test
    void stats_countsStatuses() {
        repository.save(task("t1", "任务一", TaskStatus.TODO));
        repository.save(task("t2", "任务二", TaskStatus.DOING));
        repository.save(task("t3", "任务三", TaskStatus.DONE));
        repository.save(task("t4", "任务四", TaskStatus.DONE));

        TaskRepository.TaskStats stats = repository.stats();
        assertEquals(4, stats.total());
        assertEquals(1, stats.todo());
        assertEquals(1, stats.doing());
        assertEquals(2, stats.done());
    }

    @Test
    void findAll_filterByStatus() {
        repository.save(task("t1", "任务一", TaskStatus.TODO));
        repository.save(task("t2", "任务二", TaskStatus.DONE));

        List<Task> todos = repository.findAll(TaskStatus.TODO, null);
        assertEquals(1, todos.size());
        assertEquals("t1", todos.get(0).id());
    }

    @Test
    void save_readsBackCleanly() {
        // 写入多个任务后，文件内容应能被完整解析，无残留垃圾
        repository.save(task("t1", "任务一", TaskStatus.TODO));
        repository.save(task("t2", "任务二", TaskStatus.DOING));

        String content = fileStorage.read("project/tasks/" + LocalDate.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy/MM")) + ".md");
        assertNotNull(content);
        // 文件应包含 header + 2 个完整条目
        assertTrue(content.startsWith("# 任务 - "));
        int idCount = content.split("id: ").length - 1;
        assertEquals(2, idCount, "文件里应只有 2 个 id");
    }
}
