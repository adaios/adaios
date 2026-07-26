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
    public List<Task> listTasks(TaskStatus status, String tag) {
        return taskRepository.findAll(status, tag);
    }

    /**
     * 创建新任务。
     */
    public Task createTask(String title, String description,
                           String priority, List<String> tags,
                           String rfcRef) {
        LocalDate now = LocalDate.now();
        Task task = new Task(
                Task.generateId(),
                title,
                description != null ? description : "",
                TaskStatus.TODO,
                priority != null ? priority : "P2",
                tags != null ? tags : List.of(),
                rfcRef,
                now,
                now
        );
        taskRepository.save(task);
        log.info("任务已创建 | id={} | title={}", task.id(), task.title());
        return task;
    }

    /**
     * 更新任务。
     */
    public Task updateTask(String id, String title, String description,
                           TaskStatus status, String priority,
                           List<String> tags, String rfcRef) {
        Task existing = taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + id));

        Task updated = new Task(
                existing.id(),
                title != null ? title : existing.title(),
                description != null ? description : existing.description(),
                status != null ? status : existing.status(),
                priority != null ? priority : existing.priority(),
                tags != null ? tags : existing.tags(),
                rfcRef != null ? rfcRef : existing.rfcRef(),
                existing.createdAt(),
                LocalDate.now()
        );
        taskRepository.save(updated);
        log.info("任务已更新 | id={} | status={}", id, updated.status());
        return updated;
    }

    /**
     * 删除任务。
     */
    public void deleteTask(String id) {
        taskRepository.delete(id);
    }

    /**
     * 获取任务统计。
     */
    public TaskRepository.TaskStats getStats() {
        return taskRepository.stats();
    }
}
