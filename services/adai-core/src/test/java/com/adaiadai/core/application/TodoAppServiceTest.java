package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.TodoFileRepository;
import com.adaiadai.core.kernel.memory.Memory;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.todo.Todo;
import com.adaiadai.core.kernel.todo.TodoException;
import com.adaiadai.core.kernel.todo.TodoStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TodoAppService 单元测试（RFC 20260917）。
 * <p>
 * 覆盖：纯清单 CRUD（两态 + 可选到期日）、到期日覆盖/清除语义，以及 §4.3 **单向记忆联动**：
 * 建待办不动记忆 / 完成 → markDone / 删除 → 清 actionable（best-effort，记忆失败不影响待办）。
 */
class TodoAppServiceTest {

    private TodoFileRepository todoRepo;
    private MemoryService memoryService;
    private TodoAppService service;

    @BeforeEach
    void setUp() {
        todoRepo = new TodoFileRepository(new InMemoryFileStorage());
        memoryService = mock(MemoryService.class);
        service = new TodoAppService(todoRepo, memoryService);
    }

    private Memory memory(String id, String recordId, boolean actionable) {
        return new Memory(id, recordId, "insight", "给妈打个电话",
                List.of(), List.of(), List.of("生活"), "neutral",
                actionable, "记得打电话", LocalDateTime.now(),
                null, false, null, null, null);
    }

    @Test
    void createTodo_opensWithOptionalDue_andDoesNotTouchMemory() {
        Todo todo = service.createTodo("default", "  买菜  ", LocalDate.of(2026, 9, 20), null);
        assertEquals("买菜", todo.title(), "标题两端空白应被去除");
        assertEquals(TodoStatus.OPEN, todo.status());
        assertEquals(LocalDate.of(2026, 9, 20), todo.due());
        assertNull(todo.sourceRecordId());

        // RFC 20260917 §4.3：建待办不动记忆
        verify(memoryService, never()).clearActionable(any(), any());
        verify(memoryService, never()).markDone(any(), any());
    }

    @Test
    void createTodo_blankTitle_throwsHumanReadable() {
        TodoException e = assertThrows(TodoException.class,
                () -> service.createTodo("default", "   ", null, null));
        assertEquals("待办内容不能为空", e.getMessage());
        assertTrue(todoRepo.findAll("default").isEmpty());
    }

    @Test
    void completeTodo_marksSourceMemoryDone() {
        Todo todo = service.createTodo("default", "给妈打个电话", null, "rec_1");
        when(memoryService.findByRecordId("default", "rec_1"))
                .thenReturn(Optional.of(memory("mem_1", "rec_1", true)));

        Todo done = service.updateTodo("default", todo.id(), null, TodoStatus.DONE, null, false);

        assertEquals(TodoStatus.DONE, done.status());
        verify(memoryService).markDone("default", "mem_1");
    }

    @Test
    void completeTodo_withoutSourceRecord_orMemory_doesNotFail() {
        // 手动加的待办没有源记录 → 不动记忆，也不报错
        Todo manual = service.createTodo("default", "手动加的", null, null);
        assertEquals(TodoStatus.DONE,
                service.updateTodo("default", manual.id(), null, TodoStatus.DONE, null, false).status());
        verify(memoryService, never()).markDone(any(), any());

        // 有源记录但记忆侧查不到（如记忆已过期清理）→ 同样不动
        Todo withSource = service.createTodo("default", "有源记录", null, "rec_missing");
        when(memoryService.findByRecordId("default", "rec_missing")).thenReturn(Optional.empty());
        service.updateTodo("default", withSource.id(), null, TodoStatus.DONE, null, false);
        verify(memoryService, never()).markDone(any(), any());
    }

    @Test
    void reopenTodo_doesNotReverseMemory() {
        // 已完成再改回未完成：待办是你会点的那份，不做记忆反向操作（记忆保持已完成的痕迹）
        Todo todo = service.createTodo("default", "来回改", null, "rec_2");
        when(memoryService.findByRecordId("default", "rec_2"))
                .thenReturn(Optional.of(memory("mem_2", "rec_2", true)));
        service.updateTodo("default", todo.id(), null, TodoStatus.DONE, null, false);
        service.updateTodo("default", todo.id(), null, TodoStatus.OPEN, null, false);

        verify(memoryService, times(1)).markDone("default", "mem_2");
    }

    @Test
    void deleteTodo_clearsSourceMemoryActionable() {
        Todo todo = service.createTodo("default", "取消这件事", null, "rec_3");

        service.deleteTodo("default", todo.id());

        assertTrue(todoRepo.findAll("default").isEmpty());
        verify(memoryService).clearActionable("default", "rec_3");
    }

    @Test
    void deleteTodo_withoutSourceRecord_doesNotTouchMemory() {
        Todo todo = service.createTodo("default", "手动加的", null, null);
        service.deleteTodo("default", todo.id());
        verify(memoryService, never()).clearActionable(any(), any());
    }

    @Test
    void deleteTodo_notFound_throwsHumanReadable() {
        TodoException e = assertThrows(TodoException.class, () -> service.deleteTodo("default", "todo_none"));
        assertEquals("没找到这条待办", e.getMessage());
    }

    @Test
    void updateTodo_dueSemantics_keepReplaceClear() {
        Todo todo = service.createTodo("default", "交房租", LocalDate.of(2026, 9, 25), null);

        // replaceDue=false → 保持原到期日
        Todo kept = service.updateTodo("default", todo.id(), "交房租（改标题）", null, null, false);
        assertEquals(LocalDate.of(2026, 9, 25), kept.due());
        assertEquals("交房租（改标题）", kept.title());

        // replaceDue=true + 新日期 → 覆盖
        Todo changed = service.updateTodo("default", todo.id(), null, null, LocalDate.of(2026, 10, 1), true);
        assertEquals(LocalDate.of(2026, 10, 1), changed.due());

        // replaceDue=true + null → 清除到期日
        Todo cleared = service.updateTodo("default", todo.id(), null, null, null, true);
        assertNull(cleared.due());
    }

    @Test
    void updateTodo_notFound_throwsHumanReadable() {
        TodoException e = assertThrows(TodoException.class,
                () -> service.updateTodo("default", "todo_none", "x", null, null, false));
        assertEquals("没找到这条待办", e.getMessage());
    }

    @Test
    void listTodos_newestFirst_andStats() {
        Todo older = service.createTodo("default", "先加的", null, null);
        Todo newer = service.createTodo("default", "后加的", null, null);
        service.updateTodo("default", older.id(), null, TodoStatus.DONE, null, false);

        List<Todo> all = service.listTodos("default", null);
        assertEquals(2, all.size());
        assertEquals(newer.id(), all.get(0).id(), "新加的排前面");

        assertEquals(1, service.listTodos("default", TodoStatus.OPEN).size());
        assertEquals(1, service.listTodos("default", TodoStatus.DONE).size());

        var stats = service.getStats("default");
        assertEquals(2, stats.total());
        assertEquals(1, stats.open());
        assertEquals(1, stats.done());
    }
}
