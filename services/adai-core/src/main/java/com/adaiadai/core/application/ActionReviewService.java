package com.adaiadai.core.application;

import com.adaiadai.core.kernel.todo.Todo;
import com.adaiadai.core.kernel.todo.TodoRepository;
import com.adaiadai.core.kernel.todo.TodoStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ActionReviewService — 「对话里给出的动作」的落点与回捞（REVIEW P2-交易73）。
 *
 * <p><b>缺口</b>：阿呆在对话里给的 4 组具体动作（三行记录 / 三类篮子 / 收盘后三件事 / 节日清单），
 * 一周后在落盘数据里一项痕迹都没有——动作产生在对话里，**没有「待回看」的载体**，
 * 也没有任何机制在下次对话时把它捞回来。共因与 P2-交易72 · P2-认知3 · S-12 同源：
 * 「对话产物没有下游」。
 *
 * <p><b>本类的定位：只做「动作 → 既有载体」的搬运与回捞判据，不新造第三套存储。</b>
 * <ul>
 *   <li><b>落点</b>：既有待办（{@code data/{userId}/todos/YYYY/MM.md}，{@link TodoAppService}
 *       / {@link TodoRepository}）——用户会看、会点、会划掉的那份清单；</li>
 *   <li><b>回捞</b>：① 落盘时给「次日」到期日 → 既有 {@code TodoReminderService} 次日早盘
 *       （08:00）按既有 {@code todo-due} 口播一次（可关）；② {@link #pendingReviews} 供既有
 *       {@code BriefAppService} 在概览卡里优先提及；③ 对话侧由记忆的「## 待行动事项」承担
 *       （{@code ContextEngine} 既有链路，记忆由调用方按 {@link #joinForMemory} 落成 actionable）。</li>
 * </ul>
 *
 * <p><b>三条硬约束</b>（对齐 RFC 20260923「沉默是默认项」）：
 * <ol>
 *   <li><b>有据</b>：只搬 AI 在对话里明确让用户做的事；没有就是空清单——
 *       这里不生成、不推测、不补全；</li>
 *   <li><b>有界</b>：每段对话最多 {@value #MAX_ACTIONS_PER_CONVERSATION} 条、
 *       回捞最多 {@code limit} 条、标题最多 {@value #MAX_TITLE_CHARS} 字；</li>
 *   <li><b>不冲突</b>：只新增，绝不覆盖/改写/删除既有待办；同源（同 recordId）幂等；
 *       同标题的未完成待办已存在则跳过（用户手抄过的那条不再重复落）。</li>
 * </ol>
 */
@Service
public class ActionReviewService {

    private static final Logger log = LoggerFactory.getLogger(ActionReviewService.class);

    /** 每段对话最多落几条（防「对话里给一屏动作」把清单刷满）。 */
    static final int MAX_ACTIONS_PER_CONVERSATION = 3;

    /** 单条动作的字数上限（超长截断，不丢动作；与待办清单一行可读的体量一致）。 */
    static final int MAX_TITLE_CHARS = 60;

    /** 回捞窗口：落盘时给的到期日 = 次日——次日早盘由既有待办提醒把它捞回来。 */
    static final int DEFAULT_DUE_DAYS = 1;

    private final TodoRepository todoRepository;
    private final TodoAppService todoAppService;

    public ActionReviewService(TodoRepository todoRepository, TodoAppService todoAppService) {
        this.todoRepository = todoRepository;
        this.todoAppService = todoAppService;
    }

    /**
     * 把「这段对话里阿呆让用户做的事」落进既有待办（best-effort，失败不阻塞对话结束）。
     *
     * @param recordId 本次对话落盘的记录 id（作为动作的来源锚点：待办↔记忆靠它双向对账）
     * @param actions  AI 从对话里抽出的动作清单（可空 / 可含脏项）
     * @return 本次真正落下的待办（被幂等 / 去重 / 上限挡下的不计）
     */
    public List<Todo> captureFromConversation(String userId, String recordId, List<String> actions) {
        List<String> cleaned = normalize(actions);
        if (cleaned.isEmpty()) {
            return List.of();
        }
        try {
            // 幂等：同一条对话记录已经落过动作 → 不重复落（重补 / 重复结束同一段对话）
            if (recordId != null && !recordId.isBlank() && alreadyLinked(userId, recordId)) {
                log.info("动作落盘跳过：该对话已落过 | userId={} | recordId={}", userId, recordId);
                return List.of();
            }
            // 与既有待办不冲突：同标题的未完成待办已存在（用户自己加过 / 上一段对话落过）→ 跳过
            Set<String> openTitles = todoRepository.findAll(TodoStatus.OPEN, userId).stream()
                    .map(t -> normalizeOne(t.title()))
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());

            LocalDate due = LocalDate.now().plusDays(DEFAULT_DUE_DAYS);
            List<Todo> created = new ArrayList<>();
            for (String action : cleaned) {
                if (openTitles.contains(action)) {
                    log.info("动作落盘跳过：同标题未完成待办已存在 | userId={} | title=\"{}\"", userId, action);
                    continue;
                }
                created.add(todoAppService.createTodo(userId, action, due, recordId));
                openTitles.add(action);
            }
            if (!created.isEmpty()) {
                log.info("对话动作已落待办 | userId={} | recordId={} | 落 {} 条（候选 {} 条） | due={}",
                        userId, recordId, created.size(), cleaned.size(), due);
            }
            return created;
        } catch (Exception e) {
            // best-effort：落盘失败不阻塞对话结束（与 RecordToTodoLinker 同原则）
            log.warn("对话动作落待办失败（不阻塞对话结束） | userId={} | recordId={} | {}",
                    userId, recordId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 回捞判据：**来自对话/记录、仍未完成**的动作（最近优先、有界）。
     * <p>
     * 「有未完成才提、无则沉默」由返回值承担：空清单 ⇒ 调用方（概览卡注入）一个字都不加。
     * 「完成后不再捞回」由 {@link TodoStatus#OPEN} 过滤承担——用户在清单里划掉即消失
     * （同时经 {@code TodoAppService} 反向 markDone 记忆，对话侧也不再注入）。
     *
     * @param limit 条数上限（≤0 → 空，调用方不该问就不提）
     */
    public List<Todo> pendingReviews(String userId, int limit) {
        if (limit <= 0) return List.of();
        try {
            return todoRepository.findAll(TodoStatus.OPEN, userId).stream()
                    // 「有据」：有来源锚点 = 从记录/对话里搬过来的动作；用户手动加的空 sourceRecordId 不在此列
                    .filter(t -> t.sourceRecordId() != null && !t.sourceRecordId().isBlank())
                    .sorted(Comparator
                            .comparing(Todo::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(Todo::id)
                            .reversed())
                    .limit(limit)
                    .toList();
        } catch (Exception e) {
            log.warn("待回看动作取数失败（按沉默处理） | userId={} | {}", userId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 动作清单 → 记忆的「行动建议」单行文本（供 {@code ContextEngine} 的「## 待行动事项」注入）。
     *
     * @return 「；」连接的单行文本；无有效动作时 null（记忆保持普通洞察，不进待行动事项）
     */
    public static String joinForMemory(List<String> actions) {
        List<String> cleaned = normalize(actions);
        if (cleaned.isEmpty()) return null;
        return String.join("；", cleaned);
    }

    /** 该对话记录是否已落过动作（幂等键 = 源记录 id，与 RecordToTodoLinker 同口径）。 */
    private boolean alreadyLinked(String userId, String recordId) {
        return todoRepository.findAll(userId).stream()
                .anyMatch(t -> recordId.equals(t.sourceRecordId()));
    }

    /**
     * 清洗动作清单：单行化 → 去列表前缀 → 去空/去重 → 截断 → 上限。
     * <p>
     * 顺序敏感：先单行化（多行会破坏待办条目格式，历史踩过「7-30 多行 title 写坏文件」），
     * 再去前缀（LLM 常回「- xxx」「1. xxx」），最后才去重与截断。
     */
    static List<String> normalize(List<String> actions) {
        if (actions == null || actions.isEmpty()) return List.of();
        Map<String, String> unique = new LinkedHashMap<>(); // 保序去重
        for (String raw : actions) {
            String one = normalizeOne(raw);
            if (one.isEmpty()) continue;
            unique.putIfAbsent(one, one);
            if (unique.size() >= MAX_ACTIONS_PER_CONVERSATION) break;
        }
        return List.copyOf(unique.values());
    }

    /** 单条清洗：单行化 + 去常见列表前缀 + 截断（与 TodoFileRepository 的 singleLine 同口径）。 */
    static String normalizeOne(String raw) {
        if (raw == null) return "";
        String s = raw.replace('\r', ' ').replace('\n', ' ').replaceAll(" +", " ").strip();
        // LLM 常把清单带前缀回来（- / * / • / 1. / 1、）；去掉后才是「他自己会说的一句话」
        s = s.replaceFirst("^[-*•·]+\\s*", "").replaceFirst("^\\d+[.、)]\\s*", "").strip();
        if (s.isEmpty()) return "";
        if (s.length() > MAX_TITLE_CHARS) {
            s = s.substring(0, MAX_TITLE_CHARS).strip() + "…";
        }
        return s;
    }
}
