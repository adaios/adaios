package com.adaiadai.core.application;

import com.adaiadai.core.domain.project.Task;
import com.adaiadai.core.domain.project.TaskRepository;
import com.adaiadai.core.kernel.memory.MemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RecordToTaskLinker — R2：记录自动转待办联动（通用化，不限 domain）。
 * <p>
 * {@code RecordController.handleStatem} 保存记录后 best-effort 调用（失败不阻塞记录保存，
 * 同 memory persist 降级原则）。触发条件（方案 B 通用化，RFC 20260814 D1）：
 * <pre>
 *   记录转待办 ⇔ intent=log AND actionable=true AND 非空摘要 AND 无排除标签(#备忘/#想法)
 * </pre>
 * 幂等：同 {@code sourceRecordId} 已有任务则跳过（防重补/重复输入刷屏看板）。
 */
@Service
public class RecordToTaskLinker {

    private static final Logger log = LoggerFactory.getLogger(RecordToTaskLinker.class);

    /** 排除标签（手动挡）：含任一即不转任务（阿呆想纯备忘、不跟踪的）。 */
    private static final List<String> EXCLUDE_TAGS = List.of("备忘", "想法");

    private final TaskRepository taskRepository;
    private final ProjectTaskAppService taskService;
    private final MemoryService memoryService;

    public RecordToTaskLinker(TaskRepository taskRepository, ProjectTaskAppService taskService,
                              MemoryService memoryService) {
        this.taskRepository = taskRepository;
        this.taskService = taskService;
        this.memoryService = memoryService;
    }

    /**
     * 尝试把记录转为待办（通用化：任何 domain 的可执行记录都转）。
     *
     * @return 生成的 taskId；不满足触发条件或已存在（幂等）时返回 null
     */
    public String link(String userId, String recordId, String intent,
                       List<String> tags, String title, String content, boolean actionable) {
        try {
            if (!"log".equals(intent)) return null;
            if (!actionable) {
                log.info("R2 跳过：非 actionable（陈述/备忘） | recordId={}", recordId);
                return null;
            }
            // 误转保护（RFC D1）：AI 未产出有效摘要（title 空）→ 不转，防垃圾待办
            if (title == null || title.isBlank()) {
                log.info("R2 跳过：记录无有效摘要（title 空） | recordId={}", recordId);
                return null;
            }
            // 手动挡：排除标签（tags 可能带/不带 #，content 原文兜底）
            if (hasExcludeTag(tags, content)) {
                log.info("R2 跳过：记录含排除标签(#备忘/#想法) | recordId={} | tags={}", recordId, tags);
                return null;
            }
            // 幂等：同源记录已转任务则跳过
            boolean exists = taskRepository.findAll(userId).stream()
                    .anyMatch(t -> recordId.equals(t.sourceRecordId()));
            if (exists) {
                log.info("R2 跳过：记录已转任务 | recordId={}", recordId);
                return null;
            }
            Task task = taskService.createTask(userId, title, content, "P2", tags, null, recordId);
            // 方案 A：转任务后清记忆待办（任务即跟踪载体，记忆只留回顾），避免双份跟踪
            memoryService.clearActionable(userId, recordId);
            log.info("R2 记录自动转任务 | recordId={} → taskId={} | title=\"{}\"", recordId, task.id(), title);
            return task.id();
        } catch (Exception e) {
            // best-effort：联动失败不阻塞记录保存
            log.warn("R2 记录转任务失败（不阻塞记录） | recordId={} | {}", recordId, e.getMessage());
            return null;
        }
    }

    private boolean hasExcludeTag(List<String> tags, String content) {
        if (tags != null) {
            for (String tag : tags) {
                String t = tag == null ? "" : tag.strip();
                if (EXCLUDE_TAGS.contains(t) || t.startsWith("#") && EXCLUDE_TAGS.contains(t.substring(1))) {
                    return true;
                }
            }
        }
        if (content != null && (content.contains("#备忘") || content.contains("#想法"))) {
            return true;
        }
        return false;
    }
}
