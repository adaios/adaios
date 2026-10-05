package com.adaiadai.core.application;

import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.TodoFileRepository;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.todo.Todo;
import com.adaiadai.core.kernel.todo.TodoStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * ActionReviewService 单元测试 —— REVIEW P2-交易73「对话里给的动作要有待回看的载体」。
 *
 * <p>三条判据（对应登记的验收）：
 * <ul>
 *   <li><b>落盘</b>：动作进**既有待办**（不新造存储）、带来源锚点、到期日 = 次日（次日早盘捞回）；</li>
 *   <li><b>幂等 / 不冲突</b>：同源不重复落、同标题未完成不重复落、既有待办一字不动；</li>
 *   <li><b>捞回</b>：有未完成才返回（有界），没有就是空（沉默是默认项），完成后不再返回。</li>
 * </ul>
 */
class ActionReviewServiceTest {

    private TodoFileRepository todoRepo;
    private ActionReviewService service;
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        InMemoryFileStorage storage = new InMemoryFileStorage();
        todoRepo = new TodoFileRepository(storage);
        TodoAppService todoService = new TodoAppService(todoRepo, mock(MemoryService.class));
        service = new ActionReviewService(todoRepo, todoService);
    }

    // ── 落盘 ──

    @Test
    void capture_landsActionsIntoExistingTodos_withSourceAndNextDayDue() {
        List<Todo> created = service.captureFromConversation("default", "rec_conv_1",
                List.of("把云南锗业的白线调出来看看", "数一下亨通光电持有几天"));

        assertEquals(2, created.size(), "两条动作都应落进既有待办");
        List<Todo> all = todoRepo.findAll("default");
        assertEquals(2, all.size());
        Todo first = all.stream().filter(t -> t.title().contains("云南锗业")).findFirst().orElseThrow();
        assertEquals("rec_conv_1", first.sourceRecordId(), "待办必须带来源锚点（待办↔记忆靠它对账）");
        assertEquals(TodoStatus.OPEN, first.status());
        assertEquals(today.plusDays(1), first.due(), "到期日 = 次日：次日早盘由既有待办提醒捞回");
    }

    @Test
    void capture_emptyOrBlankActions_silentNoTodo() {
        assertTrue(service.captureFromConversation("default", "rec_x", null).isEmpty());
        assertTrue(service.captureFromConversation("default", "rec_x", List.of()).isEmpty());
        assertTrue(service.captureFromConversation("default", "rec_x", List.of("", "   ", "\n")).isEmpty());
        assertTrue(todoRepo.findAll("default").isEmpty(), "没有动作就一个待办都不该有（沉默是默认项）");
    }

    @Test
    void capture_cleansListPrefixAndMultiline_andCapsAtThree() {
        List<Todo> created = service.captureFromConversation("default", "rec_many", List.of(
                "- 第一条动作",
                "1. 第二条动作",
                "多行\n动作要单行化",
                "第四条动作（超出上限，不该落）"));

        assertEquals(ActionReviewService.MAX_ACTIONS_PER_CONVERSATION, created.size(), "每段对话最多 3 条（有界）");
        List<String> titles = todoRepo.findAll("default").stream().map(Todo::title).toList();
        assertTrue(titles.contains("第一条动作"), "列表前缀应被剥掉");
        assertTrue(titles.contains("第二条动作"));
        assertTrue(titles.contains("多行 动作要单行化"), "多行必须单行化，否则写坏待办条目");
        assertFalse(titles.stream().anyMatch(t -> t.contains("第四条")), "超出上限的动作不落");
    }

    @Test
    void capture_truncatesOverlongTitle() {
        String longAction = "把".repeat(200);
        service.captureFromConversation("default", "rec_long", List.of(longAction));

        String title = todoRepo.findAll("default").get(0).title();
        assertEquals(ActionReviewService.MAX_TITLE_CHARS + 1, title.length(), "超长截断并带省略号");
        assertTrue(title.endsWith("…"));
    }

    // ── 幂等 / 与既有待办不冲突 ──

    @Test
    void capture_idempotent_sameRecordDoesNotLandTwice() {
        service.captureFromConversation("default", "rec_conv_1", List.of("把白线调出来"));
        List<Todo> second = service.captureFromConversation("default", "rec_conv_1", List.of("把白线调出来"));

        assertTrue(second.isEmpty(), "同一段对话重复触发不再落");
        assertEquals(1, todoRepo.findAll("default").size());
    }

    /**
     * 同源幂等**独立**成立（2026-10-05 独立审查实测补钉）：把标题改掉后，同标题去重挡不住重放，
     * 只有 {@code alreadyLinked}（键 = sourceRecordId）能挡。
     *
     * <p>为什么这条必须单独钉：{@code sourceRecordId} 同时是「待办 ↔ 记忆」反向对账的钥匙
     * （{@code TodoAppService.markMemoryDone} 按它查记忆）；若幂等判据被悄悄删掉，
     * 重放会再落一条**无记忆锚点的新待办**，用户划掉它时清不掉原来那条记忆行动标记
     * （AI 会一直追问一件已经做完的事）。
     */
    @Test
    void capture_sameRecordIdAfterTitleEdited_doesNotLandAgain() {
        List<Todo> first = service.captureFromConversation("default", "rec_conv_edit", List.of("甲"));
        assertEquals(1, first.size());
        Todo original = first.get(0);

        // 模拟用户编辑这条待办的标题：同标题去重从此挡不住重放
        new TodoAppService(todoRepo, mock(MemoryService.class))
                .updateTodo("default", original.id(), "甲（我改过的说法）", null, null, false);

        List<Todo> second = service.captureFromConversation("default", "rec_conv_edit", List.of("甲"));

        assertTrue(second.isEmpty(),
                "同一条对话记录重放不得再追加（幂等键 = sourceRecordId，不是标题）");
        assertEquals(1, todoRepo.findAll("default").size(), "清单里仍只有用户编辑过的那一条");
        assertEquals("rec_conv_edit", todoRepo.findAll("default").get(0).sourceRecordId(),
                "原锚点必须原样保留：完成/删除待办要靠它反向清记忆");
    }

    @Test
    void capture_skipsTitleAlreadyOpen_doesNotTouchExistingTodo() {
        // 用户自己加的（无来源）同标题未完成待办
        Todo manual = new TodoAppService(todoRepo, mock(MemoryService.class))
                .createTodo("default", "把白线调出来", null, null);

        List<Todo> created = service.captureFromConversation("default", "rec_conv_2",
                List.of("把白线调出来", "另一件新动作"));

        assertEquals(1, created.size(), "同标题未完成已存在 → 跳过，不重复落");
        List<Todo> all = todoRepo.findAll("default");
        assertEquals(2, all.size());
        Todo untouched = all.stream().filter(t -> t.id().equals(manual.id())).findFirst().orElseThrow();
        assertNull(untouched.sourceRecordId(), "既有待办一字不动（只增不改不删）");
        assertEquals(today, untouched.createdAt());
    }

    @Test
    void capture_sameTitleDoneTodo_isNotAConflict() {
        TodoAppService todoService = new TodoAppService(todoRepo, mock(MemoryService.class));
        Todo done = todoService.createTodo("default", "把白线调出来", null, null);
        todoService.updateTodo("default", done.id(), null, TodoStatus.DONE, null, false);

        List<Todo> created = service.captureFromConversation("default", "rec_conv_3", List.of("把白线调出来"));

        assertEquals(1, created.size(), "已完成的同标题不算冲突（这次是新一轮的回看）");
        assertEquals(2, todoRepo.findAll("default").size());
    }

    // ── 捞回判据 ──

    @Test
    void pendingReviews_returnsOpenActions_mostRecentFirst() {
        service.captureFromConversation("default", "rec_a", List.of("旧动作"));
        service.captureFromConversation("default", "rec_b", List.of("新动作"));

        List<Todo> pending = service.pendingReviews("default", 3);

        assertEquals(2, pending.size());
        assertTrue(pending.stream().allMatch(t -> t.status() == TodoStatus.OPEN));
        assertTrue(pending.get(0).createdAt().compareTo(pending.get(1).createdAt()) >= 0, "最近优先");
    }

    @Test
    void pendingReviews_noPending_isSilent() {
        assertTrue(service.pendingReviews("default", 3).isEmpty(), "没有未完成动作 → 空（调用方一个字都不加）");
    }

    @Test
    void pendingReviews_doneActionNotRecalled() {
        service.captureFromConversation("default", "rec_a", List.of("要回看的动作"));
        Todo todo = todoRepo.findAll("default").get(0);

        new TodoAppService(todoRepo, mock(MemoryService.class))
                .updateTodo("default", todo.id(), null, TodoStatus.DONE, null, false);

        assertTrue(service.pendingReviews("default", 3).isEmpty(), "完成后不再捞回");
    }

    @Test
    void pendingReviews_ignoresManualTodosWithoutSource() {
        new TodoAppService(todoRepo, mock(MemoryService.class))
                .createTodo("default", "我自己加的事", null, null);

        assertTrue(service.pendingReviews("default", 3).isEmpty(),
                "用户手加的待办不是「对话给的待回看动作」，走既有的 Open todos 注入，不占这一段的位");
    }

    @Test
    void pendingReviews_boundedByLimit_andZeroMeansSilent() {
        service.captureFromConversation("default", "rec_a", List.of("一", "二", "三"));

        assertEquals(2, service.pendingReviews("default", 2).size(), "上限生效（有界）");
        assertTrue(service.pendingReviews("default", 0).isEmpty(), "limit<=0 视为不问");
    }

    // ── 记忆侧文本 ──

    @Test
    void joinForMemory_joinsOrNull() {
        assertNull(ActionReviewService.joinForMemory(List.of()), "无动作 → null（记忆保持普通洞察）");
        assertNull(ActionReviewService.joinForMemory(null));
        assertEquals("把白线调出来", ActionReviewService.joinForMemory(List.of("把白线调出来")));
        assertEquals("甲；乙", ActionReviewService.joinForMemory(List.of("甲", "乙")));
    }
}
