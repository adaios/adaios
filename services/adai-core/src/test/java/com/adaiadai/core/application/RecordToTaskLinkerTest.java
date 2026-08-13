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
 * RecordToTaskLinker 单元测试（R2 记录↔任务关联）。
 * 覆盖触发规则（方案 B）：domain=project + intent=log + actionable + 无排除标签 + 幂等 + 清记忆待办。
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

    private String link(String domain, String intent, List<String> tags, String content, boolean actionable) {
        return linker.link("default", "rec_1", domain, intent, tags, "标题", content, actionable);
    }

    @Test
    void link_projectActionable_createsTaskWithSource() {
        String taskId = link("project", "log", List.of("bug"), "凌晨问候语显示 morning，需要修复", true);
        assertNotNull(taskId, "满足触发应创建任务");
        Task task = taskRepo.findById("default", taskId).orElseThrow();
        assertEquals("rec_1", task.sourceRecordId(), "任务应关联源记录");
        assertEquals("标题", task.title());
        assertEquals("凌晨问候语显示 morning，需要修复", task.description());
    }

    @Test
    void link_nonProjectDomain_skips() {
        assertNull(link("life", "log", List.of(), "内容", true));
        assertNull(link("trading", "log", List.of(), "内容", true));
        assertTrue(taskRepo.findAll("default").isEmpty());
    }

    @Test
    void link_nonLogIntent_skips() {
        assertNull(link("project", "question", List.of(), "对话内容", true));
    }

    @Test
    void link_notActionable_skips() {
        assertNull(link("project", "log", List.of(), "项目进展不错", false));
    }

    @Test
    void link_excludeTag_skips() {
        assertNull(link("project", "log", List.of("备忘"), "纯备忘", true));
        assertNull(link("project", "log", List.of("#想法"), "纯想法", true));
        // content 原文兜底（AI 可能未把 #备忘 提为 tag）
        assertNull(link("project", "log", List.of(), "这条 #备忘 不转任务", true));
        assertTrue(taskRepo.findAll("default").isEmpty());
    }

    @Test
    void link_idempotent_sameSourceSkips() {
        assertNotNull(link("project", "log", List.of(), "内容", true));
        // 同 recordId 重复触发（如重补/重复输入）→ 不重复建任务
        assertNull(link("project", "log", List.of(), "内容", true));
        assertEquals(1, taskRepo.findAll("default").size());
    }

    @Test
    void link_clearsMemoryActionable() {
        // 方案 A：转任务后清记忆待办（任务即跟踪载体，记忆只留回顾），避免双份跟踪
        MemoryService memoryService = mock(MemoryService.class);
        linker = new RecordToTaskLinker(taskRepo, taskService, memoryService);

        String taskId = link("project", "log", List.of(), "需要修复", true);
        assertNotNull(taskId);
        verify(memoryService).clearActionable("default", "rec_1");
    }

    @Test
    void link_skipped_doesNotClearMemory() {
        // 不满足触发条件（不转任务）→ 不清记忆待办
        MemoryService memoryService = mock(MemoryService.class);
        linker = new RecordToTaskLinker(taskRepo, taskService, memoryService);

        assertNull(link("life", "log", List.of(), "生活记录", true));
        assertNull(link("project", "log", List.of(), "项目进展", false));
        verify(memoryService, never()).clearActionable(any(), any());
    }
}
