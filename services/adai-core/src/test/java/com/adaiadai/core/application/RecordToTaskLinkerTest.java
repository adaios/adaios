package com.adaiadai.core.application;

import com.adaiadai.core.domain.project.Task;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.ProjectFileRepository;
import com.adaiadai.core.kernel.memory.MemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RecordToTaskLinker 单元测试（R2 记录↔待办关联）。
 * 覆盖触发规则（通用化，RFC 20260814 D1）：intent=log + actionable + 非空摘要 + 无排除标签 + 幂等 + 清记忆待办。
 */
class RecordToTaskLinkerTest {

    private ProjectFileRepository taskRepo;
    private ProjectTaskAppService taskService;
    private RecordToTaskLinker linker;

    @BeforeEach
    void setUp() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        taskRepo = new ProjectFileRepository(storage);
        taskService = new ProjectTaskAppService(taskRepo);
        linker = new RecordToTaskLinker(taskRepo, taskService, mock(MemoryService.class));
    }

    private String link(String intent, List<String> tags, String content, boolean actionable) {
        return linker.link("default", "rec_1", intent, tags, "标题", content, actionable);
    }

    /** 空摘要变体（T1.2 误转保护）。 */
    private String linkBlankTitle(String intent, List<String> tags, String content, boolean actionable) {
        return linker.link("default", "rec_1", intent, tags, "", content, actionable);
    }

    @Test
    void link_logActionable_createsTaskWithSource() {
        String taskId = link("log", List.of("bug"), "凌晨问候语显示 morning，需要修复", true);
        assertNotNull(taskId, "满足触发应创建任务");
        Task task = taskRepo.findById("default", taskId).orElseThrow();
        assertEquals("rec_1", task.sourceRecordId(), "任务应关联源记录");
        assertEquals("标题", task.title());
        assertEquals("凌晨问候语显示 morning，需要修复", task.description());
    }

    @Test
    void link_universal_anyDomainConverts() {
        // RFC D1 通用化：不再限 domain=project，life/trading 记录同样可转待办
        assertNotNull(linker.link("default", "rec_life", "log", List.of(), "标题", "生活记录转待办", true));
        assertNotNull(linker.link("default", "rec_trade", "log", List.of(), "标题", "交易记录转待办", true));
        assertEquals(2, taskRepo.findAll("default").size());
    }

    @Test
    void link_nonLogIntent_skips() {
        assertNull(link("question", List.of(), "对话内容", true));
    }

    @Test
    void link_notActionable_skips() {
        assertNull(link("log", List.of(), "项目进展不错", false));
    }

    @Test
    void link_blankTitle_skips() {
        // T1.2 误转保护：AI 未产出有效摘要（title 空）→ 不转，防垃圾待办
        assertNull(linkBlankTitle("log", List.of(), "无摘要内容", true));
        assertTrue(taskRepo.findAll("default").isEmpty());
    }

    @Test
    void link_excludeTag_skips() {
        assertNull(link("log", List.of("备忘"), "纯备忘", true));
        assertNull(link("log", List.of("#想法"), "纯想法", true));
        // content 原文兜底（AI 可能未把 #备忘 提为 tag）
        assertNull(link("log", List.of(), "这条 #备忘 不转任务", true));
        assertTrue(taskRepo.findAll("default").isEmpty());
    }

    @Test
    void link_idempotent_sameSourceSkips() {
        assertNotNull(link("log", List.of(), "内容", true));
        // 同 recordId 重复触发（如重补/重复输入）→ 不重复建任务
        assertNull(link("log", List.of(), "内容", true));
        assertEquals(1, taskRepo.findAll("default").size());
    }

    @Test
    void link_clearsMemoryActionable() {
        // 方案 A：转任务后清记忆待办（任务即跟踪载体，记忆只留回顾），避免双份跟踪
        MemoryService memoryService = mock(MemoryService.class);
        linker = new RecordToTaskLinker(taskRepo, taskService, memoryService);

        String taskId = link("log", List.of(), "需要修复", true);
        assertNotNull(taskId);
        verify(memoryService).clearActionable("default", "rec_1");
    }

    @Test
    void link_skipped_doesNotClearMemory() {
        // 不满足触发条件（不转待办）→ 不清记忆待办
        MemoryService memoryService = mock(MemoryService.class);
        linker = new RecordToTaskLinker(taskRepo, taskService, memoryService);

        assertNull(link("log", List.of(), "项目进展", false));          // 非 actionable 不转
        assertNull(link("log", List.of("备忘"), "纯备忘", true));        // 排除标签不转
        assertNull(linkBlankTitle("log", List.of(), "内容", true));      // 空摘要不转（T1.2）
        verify(memoryService, never()).clearActionable(any(), any());
    }
}
