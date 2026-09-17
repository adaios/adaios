package com.adaiadai.core.application;

import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.todo.Todo;
import com.adaiadai.core.kernel.todo.TodoException;
import com.adaiadai.core.kernel.todo.TodoRepository;
import com.adaiadai.core.kernel.todo.TodoStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * TodoAppService — 待办应用服务（RFC 20260917：待办归 Kernel builtin）。
 * <p>
 * 纯清单 CRUD 编排：一句话 + 可选到期日 + 两态（OPEN / DONE）。
 * File First：数据经 {@link TodoRepository} 读写 {@code data/{userId}/todos/YYYY/MM.md}。
 * <p>
 * 记忆联动（单向，RFC 20260917 §4.3）：待办是「你会看会点的那份」，记忆是「AI 用的那份」。
 * 完成待办 → 同步 {@code markDone(记忆)}；删除待办 → 同步清记忆行动标记（不留僵尸）；
 * **建待办不动记忆**（记忆继续供 AI 使用，问答时它该知道你手头有事）。
 * 联动是 best-effort：记忆侧失败只记日志，不影响待办本身的状态变更。
 */
@Service
public class TodoAppService {

    private static final Logger log = LoggerFactory.getLogger(TodoAppService.class);

    private final TodoRepository todoRepository;
    private final MemoryService memoryService;

    public TodoAppService(TodoRepository todoRepository, MemoryService memoryService) {
        this.todoRepository = todoRepository;
        this.memoryService = memoryService;
    }

    /** 待办列表（status 为 null 表示全部；新创建的在前）。 */
    public List<Todo> listTodos(String userId, TodoStatus status) {
        List<Todo> todos = todoRepository.findAll(status, userId);
        todos.sort(java.util.Comparator
                .comparing(Todo::createdAt, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                .thenComparing(Todo::id)
                .reversed());
        return todos;
    }

    /**
     * 新建待办。
     *
     * @param sourceRecordId 源记录 ID（记录自动转待办时关联，手动添加传 null）
     */
    public Todo createTodo(String userId, String title, LocalDate due, String sourceRecordId) {
        if (title == null || title.isBlank()) {
            throw new TodoException("待办内容不能为空");
        }
        LocalDate now = LocalDate.now();
        Todo todo = new Todo(
                Todo.generateId(),
                title.strip(),
                TodoStatus.OPEN,
                due,
                sourceRecordId,
                now,
                now
        );
        todoRepository.save(userId, todo);
        log.info("待办已创建 | id={} | title=\"{}\" | due={} | source={}", todo.id(), todo.title(),
                due != null ? due : "-", sourceRecordId != null ? sourceRecordId : "-");
        return todo;
    }

    /**
     * 更新待办。
     *
     * @param replaceDue true 表示按 {@code due} 覆盖（传 null 即清除到期日）；false 表示保持原值
     */
    public Todo updateTodo(String userId, String id, String title, TodoStatus status,
                           LocalDate due, boolean replaceDue) {
        Todo existing = todoRepository.findById(userId, id)
                .orElseThrow(() -> new TodoException("没找到这条待办"));

        Todo updated = new Todo(
                existing.id(),
                title != null && !title.isBlank() ? title.strip() : existing.title(),
                status != null ? status : existing.status(),
                replaceDue ? due : existing.due(),
                existing.sourceRecordId(),
                existing.createdAt(),
                LocalDate.now()
        );
        todoRepository.save(userId, updated);

        // 记忆联动：刚被标记完成 → 记忆里的行动标记同步为已完成（AI 不再追问一件已经做完的事）
        if (updated.status() == TodoStatus.DONE && existing.status() != TodoStatus.DONE) {
            markMemoryDone(userId, existing.sourceRecordId());
        }
        log.info("待办已更新 | id={} | status={} | due={}", id, updated.status(),
                updated.due() != null ? updated.due() : "-");
        return updated;
    }

    /** 删除待办（取消即删除）：同步清记忆行动标记，不留「AI 还在追、清单里没有」的僵尸。 */
    public void deleteTodo(String userId, String id) {
        Todo existing = todoRepository.findById(userId, id).orElse(null);
        if (existing == null) {
            throw new TodoException("没找到这条待办");
        }
        todoRepository.delete(userId, id);
        if (existing.sourceRecordId() != null) {
            try {
                memoryService.clearActionable(userId, existing.sourceRecordId());
            } catch (Exception e) {
                log.warn("待办删除后清记忆标记失败（不影响删除） | id={} | recordId={} | {}",
                        id, existing.sourceRecordId(), e.getMessage());
            }
        }
    }

    /** 待办统计（两态口径）。 */
    public TodoRepository.TodoStats getStats(String userId) {
        return todoRepository.stats(userId);
    }

    /** 记忆侧标记完成（按源记录关联；无源记录 / 找不到记忆则跳过，属正常——手动加的待办没有记忆）。 */
    private void markMemoryDone(String userId, String sourceRecordId) {
        if (sourceRecordId == null || sourceRecordId.isBlank()) return;
        try {
            memoryService.findByRecordId(userId, sourceRecordId)
                    .map(Memory::id)
                    .ifPresent(memoryId -> memoryService.markDone(userId, memoryId));
        } catch (Exception e) {
            log.warn("待办完成后同步记忆失败（不影响待办） | recordId={} | {}", sourceRecordId, e.getMessage());
        }
    }
}
