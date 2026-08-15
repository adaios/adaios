package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.ProjectStatusAppService;
import com.adaiadai.core.application.ProjectTaskAppService;
import com.adaiadai.core.domain.project.Task;
import com.adaiadai.core.domain.project.TaskRepository;
import com.adaiadai.core.domain.project.TaskStatus;
import org.springframework.http.ResponseEntity;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * ProjectStatusController — 项目状态查询 + 任务管理 API。
 * <p>
 * 项目状态：返回 AdaiOS 项目的元信息（Kernel 组件状态、Domain OS 进度等）。
 * 任务管理：CRUD 操作使用轻量任务系统，File First 存储。
 */
@RestController
@RequestMapping("/api/v1/project")
public class ProjectStatusController {

    private final ProjectStatusAppService statusService;
    private final ProjectTaskAppService taskService;
    private final PluginService pluginService;

    public ProjectStatusController(ProjectStatusAppService statusService,
                                   ProjectTaskAppService taskService,
                                   PluginService pluginService) {
        this.statusService = statusService;
        this.taskService = taskService;
        this.pluginService = pluginService;
    }

    /** REVIEW P1-W13（B40）：project 插件门控——无 project 插件的用户不得写任务（与 trading 侧对称）。 */
    private ResponseEntity<?> requireProjectPlugin(String userId) {
        if (!pluginService.hasPlugin(userId, PluginRegistry.PLUGIN_PROJECT)) {
            return ResponseEntity.status(403).body(Map.of("error", "project 插件未启用，无法管理任务"));
        }
        return null;
    }

    /**
     * 获取项目状态摘要。
     */
    @GetMapping("/status")
    public ResponseEntity<ProjectStatusAppService.StatusResult> getStatus() {
        return ResponseEntity.ok(statusService.getStatus());
    }

    // ── 任务管理 API ──

    /**
     * 获取任务列表。
     */
    @GetMapping("/tasks")
    public ResponseEntity<List<Task>> listTasks(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) String tag) {
        return ResponseEntity.ok(taskService.listTasks(userId, status, tag));
    }

    /**
     * 创建任务。
     */
    @PostMapping("/tasks")
    public ResponseEntity<?> createTask(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @RequestBody TaskRequest request) {
        ResponseEntity<?> denied = requireProjectPlugin(userId);
        if (denied != null) return denied;
        Task task = taskService.createTask(
                userId, request.title(), request.description(),
                request.priority(), request.tags(), request.rfcRef(),
                null // R2：前端手动建任务无源记录
        );
        return ResponseEntity.ok(task);
    }

    /**
     * 更新任务。
     */
    @PutMapping("/tasks/{id}")
    public ResponseEntity<?> updateTask(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String id,
            @RequestBody TaskRequest request) {
        ResponseEntity<?> denied = requireProjectPlugin(userId);
        if (denied != null) return denied;
        Task task = taskService.updateTask(
                userId, id, request.title(), request.description(),
                request.status(), request.priority(),
                request.tags(), request.rfcRef()
        );
        return ResponseEntity.ok(task);
    }

    /**
     * 删除任务。
     */
    @DeleteMapping("/tasks/{id}")
    public ResponseEntity<Void> deleteTask(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId,
            @PathVariable String id) {
        taskService.deleteTask(userId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 获取任务统计。
     */
    @GetMapping("/tasks/stats")
    public ResponseEntity<TaskRepository.TaskStats> getTaskStats(
            @RequestHeader(value = "X-User-Id", defaultValue = "default") String userId) {
        return ResponseEntity.ok(taskService.getStats(userId));
    }

    // ── DTO ──

    public record TaskRequest(
            String title,
            String description,
            String priority,
            List<String> tags,
            String rfcRef,
            TaskStatus status
    ) {}
}
