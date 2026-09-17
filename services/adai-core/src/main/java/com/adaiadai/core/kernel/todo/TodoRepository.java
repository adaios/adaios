package com.adaiadai.core.kernel.todo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * TodoRepository — 待办存储接口（端口定义，RFC 20260917）。
 * <p>
 * 定义在 kernel/todo（与 memory 同级的基础能力），实现由 infrastructure/storage 提供。
 * File First：待办以 {@code data/{userId}/todos/YYYY/MM.md} 文件存储。
 */
public interface TodoRepository {

    /** 查找该用户待办（可按状态筛选，status 为 null 表示全部）。 */
    List<Todo> findAll(TodoStatus status, String userId);

    /** 查找该用户全部待办。 */
    List<Todo> findAll(String userId);

    /** 按 ID 查找待办。 */
    Optional<Todo> findById(String userId, String id);

    /** 保存待办（新增或更新）。 */
    void save(String userId, Todo todo);

    /** 删除待办。 */
    void delete(String userId, String id);

    /** 待办统计。 */
    TodoStats stats(String userId);

    /** TodoStats — 待办统计（两态口径）。 */
    record TodoStats(
            int total,
            int open,
            int done
    ) {}
}
