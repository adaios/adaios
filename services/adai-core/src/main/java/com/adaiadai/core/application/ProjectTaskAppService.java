package com.adaiadai.core.application;

import com.adaiadai.core.domain.project.Task;
import com.adaiadai.core.domain.project.TaskRepository;
import com.adaiadai.core.domain.project.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * ProjectTaskAppService — 任务管理应用服务。
 * <p>
 * 编排任务的 CRUD 操作和业务逻辑。
 * 采用 File First：所有数据通过 TaskRepository 读写文件。
 */
@Service
public class ProjectTaskAppService {

    private static final Logger log = LoggerFactory.getLogger(ProjectTaskAppService.class);

    private final TaskRepository taskRepository;

    public ProjectTaskAppService(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    /**
     * 获取任务列表（可筛选）。
     */
    public List<Task> listTasks(String userId, TaskStatus status, String tag) {
        return taskRepository.findAll(status, tag, userId);
    }

    /**
     * 创建新任务。
     *
     * @param sourceRecordId 源记录 ID（R2：domain=project 记录自动转任务时关联，前端手动建任务传 null）
     */
    public Task createTask(String userId, String title, String description,
                           String priority, List<String> tags,
                           String rfcRef, String sourceRecordId) {
        LocalDate now = LocalDate.now();
        Task task = new Task(
                Task.generateId(),
                title,
                description != null ? description : "",
                TaskStatus.TODO,
                priority != null ? priority : "P2",
                tags != null ? tags : List.of(),
                rfcRef,
                sourceRecordId,
                now,
                now
        );
        taskRepository.save(userId, task);
        log.info("任务已创建 | id={} | title={} | source={}", task.id(), task.title(),
                sourceRecordId != null ? sourceRecordId : "-");
        return task;
    }

    /**
     * 更新任务。
     */
    public Task updateTask(String userId, String id, String title, String description,
                           TaskStatus status, String priority,
                           List<String> tags, String rfcRef) {
        Task existing = taskRepository.findById(userId, id)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + id));

        Task updated = new Task(
                existing.id(),
                title != null ? title : existing.title(),
                description != null ? description : existing.description(),
                status != null ? status : existing.status(),
                priority != null ? priority : existing.priority(),
                tags != null ? tags : existing.tags(),
                rfcRef != null ? rfcRef : existing.rfcRef(),
                existing.sourceRecordId(), // R2：更新保留源记录关联
                existing.createdAt(),
                LocalDate.now()
        );
        taskRepository.save(userId, updated);
        log.info("任务已更新 | id={} | status={}", id, updated.status());
        return updated;
    }

    /**
     * 删除任务。
     */
    public void deleteTask(String userId, String id) {
        taskRepository.delete(userId, id);
    }

    /**
     * 获取任务统计。
     */
    public TaskRepository.TaskStats getStats(String userId) {
        return taskRepository.stats(userId);
    }
}
