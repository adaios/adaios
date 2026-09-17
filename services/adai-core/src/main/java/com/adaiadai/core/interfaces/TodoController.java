package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.TodoAppService;
import com.adaiadai.core.kernel.todo.Todo;
import com.adaiadai.core.kernel.todo.TodoException;
import com.adaiadai.core.kernel.todo.TodoRepository;
import com.adaiadai.core.kernel.todo.TodoStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * TodoController — 待办清单 API（RFC 20260917：待办归 Kernel builtin）。
 * <p>
 * 纯清单两态（OPEN / DONE）+ 可选到期日；**无插件门控**——待办是人人有的基础能力
 * （旧 {@code /api/v1/project/tasks} 的三处 403 已随 project 插件一起退役，旧路径不做兼容别名）。
 * <p>
 * 端点：
 * <pre>
 *   GET    /api/v1/todos?status=OPEN|DONE   列表（status 可选）
 *   POST   /api/v1/todos                    新建 {title, due?}
 *   PUT    /api/v1/todos/{id}               更新 {title?, status?, due?}（due 传空串 = 清除到期日）
 *   DELETE /api/v1/todos/{id}               删除（取消即删除）
 *   GET    /api/v1/todos/stats              统计（total / open / done）
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/todos")
public class TodoController {

    private final TodoAppService todoService;

    public TodoController(TodoAppService todoService) {
        this.todoService = todoService;
    }

    /** 待办列表（status 可选；新创建的在前）。 */
    @GetMapping
    public ResponseEntity<List<Todo>> listTodos(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(todoService.listTodos(userId, parseStatus(status)));
    }

    /** 新建待办。 */
    @PostMapping
    public ResponseEntity<Todo> createTodo(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody TodoRequest request) {
        return ResponseEntity.ok(todoService.createTodo(
                userId, request.title(), parseDue(request.due()), null));
    }

    /** 更新待办（null 字段保持原值；due 传空串清除到期日）。 */
    @PutMapping("/{id}")
    public ResponseEntity<Todo> updateTodo(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String id,
            @RequestBody TodoRequest request) {
        boolean replaceDue = request.due() != null;
        return ResponseEntity.ok(todoService.updateTodo(
                userId, id, request.title(), parseStatus(request.status()),
                replaceDue ? parseDue(request.due()) : null, replaceDue));
    }

    /** 删除待办。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTodo(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String id) {
        todoService.deleteTodo(userId, id);
        return ResponseEntity.noContent().build();
    }

    /** 待办统计。 */
    @GetMapping("/stats")
    public ResponseEntity<TodoRepository.TodoStats> getStats(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        return ResponseEntity.ok(todoService.getStats(userId));
    }

    // ── 解析（非法输入 → 400 人话，不 500 裸奔）──

    private TodoStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return TodoStatus.valueOf(raw.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new TodoException("待办状态只有「未完成 / 已完成」两种");
        }
    }

    private LocalDate parseDue(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.strip());
        } catch (DateTimeParseException e) {
            throw new TodoException("到期日要写成 2026-09-20 这样");
        }
    }

    // ── DTO ──

    /** 新建/更新请求。{@code due} 为 null 表示不改到期日，空串表示清除。 */
    public record TodoRequest(
            String title,
            String status,
            String due
    ) {}
}
