package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.TodoFileRepository;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.todo.Todo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * RecordToTodoLinker 单元测试（R2 记录→待办关联，RFC 20260917 重定位）。
 * <p>
 * 覆盖触发规则：intent=log + actionable + 非空摘要 + 幂等；并钉住两条 RFC 变化：
 * <ul>
 *   <li>排除标签（#备忘/#想法）判断已**前移到记忆写入侧**——本类不再自判（上游置 actionable=false 即跳过）；</li>
 *   <li>建待办**不再清记忆**（旧 {@code clearActionable} 搬运已删除，记忆继续供 AI 使用）。</li>
 * </ul>
 */
class RecordToTodoLinkerTest {

    private TodoFileRepository todoRepo;
    private TodoAppService todoService;
    private MemoryService memoryService;
    private RecordToTodoLinker linker;

    @BeforeEach
    void setUp() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        todoRepo = new TodoFileRepository(storage);
        memoryService = mock(MemoryService.class);
        todoService = new TodoAppService(todoRepo, memoryService);
        linker = new RecordToTodoLinker(todoRepo, todoService);
    }

    private String link(String intent, String title, boolean actionable) {
        return linker.link("default", "rec_1", intent, title, actionable);
    }

    @Test
    void link_logActionable_createsTodoWithSource() {
        String todoId = link("log", "凌晨问候语显示 morning，需要修复", true);
        assertNotNull(todoId, "满足触发应创建待办");
        Todo todo = todoRepo.findById("default", todoId).orElseThrow();
        assertEquals("rec_1", todo.sourceRecordId(), "待办应关联源记录");
        assertEquals("凌晨问候语显示 morning，需要修复", todo.title());
        assertEquals(com.adaiadai.core.kernel.todo.TodoStatus.OPEN, todo.status());
        assertNull(todo.due(), "记录转待办默认没有到期日");
    }

    @Test
    void link_universal_anyDomainConverts() {
        // 通用化：不限 domain，life/trading/任何记录同样可转待办
        assertNotNull(linker.link("default", "rec_life", "log", "生活记录转待办", true));
        assertNotNull(linker.link("default", "rec_trade", "log", "交易记录转待办", true));
        assertEquals(2, todoRepo.findAll("default").size());
    }

    @Test
    void link_nonLogIntent_skips() {
        assertNull(link("question", "对话内容", true));
    }

    @Test
    void link_notActionable_skips() {
        // 排除标签（#备忘/#想法）在记忆写入侧就把 actionable 置 false → 这里自然跳过
        assertNull(link("log", "项目进展不错", false));
        assertTrue(todoRepo.findAll("default").isEmpty());
    }

    @Test
    void link_blankTitle_skips() {
        // 误转保护：AI 未产出有效摘要（title 空）→ 不转，防垃圾待办
        assertNull(link("log", "", true));
        assertNull(link("log", null, true));
        assertTrue(todoRepo.findAll("default").isEmpty());
    }

    @Test
    void link_idempotent_sameSourceSkips() {
        assertNotNull(link("log", "内容", true));
        // 同 recordId 重复触发（如重补/重复输入）→ 不重复建待办
        assertNull(link("log", "内容", true));
        assertEquals(1, todoRepo.findAll("default").size());
    }

    @Test
    void link_doesNotTouchMemory() {
        // RFC 20260917 §4.3：建待办不动记忆（不再 clearActionable）——记忆继续供 AI 使用
        assertNotNull(link("log", "需要修复", true));
        verify(memoryService, never()).clearActionable(any(), any());
        verify(memoryService, never()).markDone(any(), any());
    }

    @Test
    void link_skipped_doesNotTouchMemory() {
        assertNull(link("log", "项目进展", false));
        assertNull(link("log", "", true));
        verify(memoryService, never()).clearActionable(any(), any());
        verify(memoryService, never()).markDone(any(), any());
    }
}
