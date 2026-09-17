package com.adaiadai.core.application;

import com.adaiadai.core.kernel.todo.Todo;
import com.adaiadai.core.kernel.todo.TodoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * RecordToTodoLinker — R2：记录自动转待办联动（RFC 20260917 重定位，通用化不限 domain）。
 * <p>
 * {@code RecordController.handleStatem} 保存记录后 best-effort 调用（失败不阻塞记录保存，
 * 同 memory persist 降级原则）。触发条件：
 * <pre>
 *   记录转待办 ⇔ intent=log AND actionable=true AND 非空摘要
 * </pre>
 * <p>
 * <b>RFC 20260917 变化</b>：
 * <ul>
 *   <li>旧实现「转任务后 {@code clearActionable}」已删除——记忆是 AI 用的那份，待办是你会看会点的那份，
 *       建待办不再动记忆（§4.3 单向同步：状态只由待办流向记忆）；</li>
 *   <li>{@code #备忘 / #想法} 排除判断**前移到记忆写入侧**（RecordController persist 前把 actionable
 *       置 false）——排除意图在记忆里就生效，Feed / 上下文 / 待办三处口径一致，不再各判一次。</li>
 * </ul>
 * 幂等：同 {@code sourceRecordId} 已有待办则跳过（防重补/重复输入刷屏清单）。
 */
@Service
public class RecordToTodoLinker {

    private static final Logger log = LoggerFactory.getLogger(RecordToTodoLinker.class);

    private final TodoRepository todoRepository;
    private final TodoAppService todoService;

    public RecordToTodoLinker(TodoRepository todoRepository, TodoAppService todoService) {
        this.todoRepository = todoRepository;
        this.todoService = todoService;
    }

    /**
     * 尝试把记录转为待办（任何 domain 的可执行记录都转）。
     * <p>
     * 无需在此判断排除标签：记忆写入侧已按 {@code #备忘/#想法} 把 actionable 置 false，
     * 这里只看 actionable（单一判据，避免两处口径漂移）。
     *
     * @return 生成的 todoId；不满足触发条件或已存在（幂等）时返回 null
     */
    public String link(String userId, String recordId, String intent, String title, boolean actionable) {
        try {
            if (!"log".equals(intent)) return null;
            if (!actionable) {
                log.info("R2 跳过：非 actionable（陈述/备忘） | recordId={}", recordId);
                return null;
            }
            // 误转保护：AI 未产出有效摘要（title 空）→ 不转，防垃圾待办
            if (title == null || title.isBlank()) {
                log.info("R2 跳过：记录无有效摘要（title 空） | recordId={}", recordId);
                return null;
            }
            // 幂等：同源记录已转待办则跳过
            boolean exists = todoRepository.findAll(userId).stream()
                    .anyMatch(t -> recordId.equals(t.sourceRecordId()));
            if (exists) {
                log.info("R2 跳过：记录已转待办 | recordId={}", recordId);
                return null;
            }
            Todo todo = todoService.createTodo(userId, title, null, recordId);
            log.info("R2 记录自动转待办 | recordId={} → todoId={} | title=\"{}\"", recordId, todo.id(), title);
            return todo.id();
        } catch (Exception e) {
            // best-effort：联动失败不阻塞记录保存
            log.warn("R2 记录转待办失败（不阻塞记录） | recordId={} | {}", recordId, e.getMessage());
            return null;
        }
    }
}
