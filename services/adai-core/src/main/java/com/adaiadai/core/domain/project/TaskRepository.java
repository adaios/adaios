package com.adaiadai.core.domain.project;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * TaskRepository — 任务存储接口（端口定义）。
 * <p>
 * 定义在 domain/project 层，实现由 infrastructure/storage 提供。
 * 采用 File First：任务数据以 {@code data/project/tasks/YYYY/MM.md} 文件存储。
 */
public interface TaskRepository {

    /**
     * 查找该用户所有任务（可选的按状态和标签筛选）。
     */
    List<Task> findAll(TaskStatus status, String tag, String userId);

    /**
     * 查找该用户所有任务（无筛选）。
     */
    List<Task> findAll(String userId);

    /**
     * 按 ID 查找任务。
     */
    Optional<Task> findById(String userId, String id);

    /**
     * 保存任务（新增或更新）。
     */
    void save(String userId, Task task);

    /**
     * 删除任务。
     */
    void delete(String userId, String id);

    /**
     * 获取任务统计。
     */
    TaskStats stats(String userId);

    /**
     * TaskStats — 任务统计。
     */
    record TaskStats(
            int total,
            int todo,
            int doing,
            int done,
            int cancelled
    ) {}
}
