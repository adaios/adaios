package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.ActionReviewService;
import com.adaiadai.core.application.MediaRecordAppService;
import com.adaiadai.core.application.QuestionAppService;
import com.adaiadai.core.application.RecordRetryService;
import com.adaiadai.core.application.RecordUnderstandingService;
import com.adaiadai.core.application.TodoAppService;
import com.adaiadai.core.application.TradeLogCollectService;
import com.adaiadai.core.infrastructure.ai.llm.TestAiClient;
import com.adaiadai.core.infrastructure.ai.vision.VisualAiClient;
import com.adaiadai.core.infrastructure.storage.CardFileRepository;
import com.adaiadai.core.infrastructure.storage.CardLockRegistry;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.RecordFileRepository;
import com.adaiadai.core.infrastructure.storage.TagIndexService;
import com.adaiadai.core.infrastructure.storage.TodoFileRepository;
import com.adaiadai.core.kernel.account.Account;
import com.adaiadai.core.kernel.account.AccountRepository;
import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.ai.AiUnderstanding;
import com.adaiadai.core.kernel.context.engine.ContextEngine;
import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.kernel.memory.MemoryService;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.adaiadai.core.kernel.record.CardRecord;
import com.adaiadai.core.kernel.record.ContentRecord;
import com.adaiadai.core.kernel.record.RecordRepository;
import com.adaiadai.core.kernel.storage.FileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REVIEW P2-工程13 —— 卡片 RMW 竞态：append 侧（QuestionAppService 的两处 append /
 * MediaRecordAppService）、end 侧（ConversationController）与重补写回（RecordRetryService）
 * 必须在**同一把 per-card 锁**下读写卡片文件。
 *
 * <p>本类用「竞态注入仓储」把原来只有微秒级的窗口**确定性放大**：写回线程（end / 重补）在
 * {@link CardFileRepository#beforeWriteback} 钩子里——即「已读到旧快照、尚未写回」的瞬间——挂起；
 * 与此同时 append 线程走真实业务路径尝试写入。
 *
 * <p>同步判据（P2-工程13 发现11 整改）：写回线程等待的是 append 线程给出的**真实事件**之一——
 * ① append 已落盘（= 共锁未生效，变异）或 ② append 线程 BLOCKED 在 cardLock 上（= 共锁生效）。
 * 不再用固定超时当绿灯路径。
 *
 * <p>共锁修复前 → append 落盘后被旧快照覆盖（测试红）；修复后 → append 被同一把锁挡住，
 * 写回完成后才基于最新卡 append，轮次完整保留（测试绿）。
 */
@Timeout(20)
class CardRmwConcurrencyTest {

    private static final String USER = "default";

    /**
     * 竞态注入：在写回线程的 {@code beforeWriteback} 钩子处放大「re-read → save」窗口；
     * append 线程走原速。
     */
    private static final class RacingCardFileRepository extends CardFileRepository {

        /** 只武装「写回线程」（end / 重补），append 线程不受影响，才不会把窗口挪到别处。 */
        private final ThreadLocal<Boolean> armed = ThreadLocal.withInitial(() -> false);
        /** 写回线程已到达钩子（= 已持旧快照）。append 线程据此确定性地进入窗口。 */
        private final CountDownLatch writebackReached = new CountDownLatch(1);
        /** append 线程已完成一次卡片 save。 */
        private final CountDownLatch appendSaved = new CountDownLatch(1);
        /** append 线程引用（判定它是否被 cardLock 挡住）。 */
        private volatile Thread appendThread;
        /** 只有「append 阶段」之后的写才算 append 落盘（用例准备阶段的建卡 save 不得触发）。 */
        private final AtomicBoolean appendPhase = new AtomicBoolean(false);

        RacingCardFileRepository(FileStorage fileStorage) {
            super(fileStorage);
        }

        /** 只武装写回线程。 */
        void armWritebackThread() {
            armed.set(true);
        }

        /** 登记 append 线程（写回线程用它判定 BLOCKED）。 */
        void setAppendThread(Thread thread) {
            this.appendThread = thread;
        }

        /** append 线程进入业务前调用——此后该线程的 save 视为「append 已落盘」。 */
        void beginAppendPhase() {
            appendPhase.set(true);
        }

        /**
         * 显式写回前钩子（P2-工程13 发现11）：生产路径是空操作，用例在此确定性挂起，
         * 不再绑定「end 线程第 N 次 findById」这类隐式调用次数。
         */
        @Override
        public void beforeWriteback(String userId, String cardId) {
            if (!Boolean.TRUE.equals(armed.get())) return;
            writebackReached.countDown();
            try {
                awaitAppendSettled(appendThread, appendSaved);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void save(String userId, CardRecord card) {
            super.save(userId, card);
            if (appendPhase.get() && !Boolean.TRUE.equals(armed.get())) {
                appendSaved.countDown(); // append 线程已落盘（写回线程自己的 save 不触发）
            }
        }

        /** append 线程用：等写回线程进入钩子（已持旧快照）。 */
        boolean awaitWritebackReached() throws InterruptedException {
            return writebackReached.await(15, TimeUnit.SECONDS);
        }
    }

    // ── 用例 1：end 写回 与 文本 append 交错 ──

    @Test
    void appendDuringEnd_isNotLost() throws Exception {
        InMemoryFileStorage fs = new InMemoryFileStorage();
        RacingCardFileRepository cardRepo = new RacingCardFileRepository(fs);
        CardLockRegistry registry = new CardLockRegistry();

        ConversationController controller = new ConversationController(
                new TestAiClient(), recordRepo(fs), cardRepo, new MemoryService(fs), registry,
                actionReviewService(fs));
        QuestionAppService questionService = questionService(cardRepo, registry, null);

        String cardId = "card_race_text_1";
        cardRepo.save(USER, activeCard(cardId, List.of("第一句")));

        ConversationController.EndConversationRequest endRequest =
                new ConversationController.EndConversationRequest(List.of("第一句", "阿呆回一句"), cardId);

        AtomicReference<Throwable> endError = new AtomicReference<>();
        AtomicReference<Throwable> appendError = new AtomicReference<>();
        List<String> appended = new ArrayList<>();

        Thread endThread = new Thread(() -> {
            cardRepo.armWritebackThread();
            try {
                controller.endConversation(USER, endRequest);
            } catch (Throwable t) {
                endError.set(t);
            }
        }, "end-thread");

        Thread appendThread = new Thread(() -> {
            try {
                assertTrue(cardRepo.awaitWritebackReached(), "end 未在 15s 内到达写回前钩子");
                cardRepo.beginAppendPhase();
                questionService.ensureCardWithUserTurn(USER, cardId, "第二句", LocalDateTime.now());
                appended.add("第二句");
            } catch (Throwable t) {
                appendError.set(t);
            }
        }, "append-thread");
        cardRepo.setAppendThread(appendThread);

        endThread.start();
        appendThread.start();
        endThread.join(15_000);
        appendThread.join(15_000);

        assertFalse(endThread.isAlive(), "end 线程未在 15s 内结束（用例无效）");
        assertFalse(appendThread.isAlive(), "append 线程未在 15s 内结束（用例无效）");
        assertNull(endError.get(), "end 线程异常: " + endError.get());
        assertNull(appendError.get(), "append 线程异常: " + appendError.get());
        assertEquals(List.of("第二句"), appended, "append 线程应已完成（否则用例无效）");
        List<String> texts = turnsOf(cardRepo, cardId);
        assertTrue(texts.contains("第二句"),
                "end 写回覆盖了并发 append 的轮次（P2-工程13：append 与 end 未共用同一把 per-card 锁）| turns=" + texts);
        assertEquals(2, texts.size(), "turns 应为 [第一句, 第二句] | turns=" + texts);
        assertEquals("ended", cardRepo.findById(USER, cardId).orElseThrow().status(), "end 仍须把卡置为 ended");
    }

    // ── 用例 2：end 写回 与 图片卡 Q/A append 交错（第三个 append 站点）──

    @Test
    void mediaAppendDuringEnd_isNotLost() throws Exception {
        InMemoryFileStorage fs = new InMemoryFileStorage();
        RacingCardFileRepository cardRepo = new RacingCardFileRepository(fs);
        CardLockRegistry registry = new CardLockRegistry();
        RecordFileRepository recordRepository = recordRepo(fs);

        ConversationController controller = new ConversationController(
                new TestAiClient(), recordRepository, cardRepo, new MemoryService(fs), registry,
                actionReviewService(fs));

        VisualAiClient glm = mock(VisualAiClient.class);
        when(glm.ask(any(), anyString())).thenReturn("看图回答");
        MediaRecordAppService mediaService = new MediaRecordAppService(
                glm, recordRepository, new MemoryService(fs), fs, cardRepo,
                mock(PluginService.class), mock(TradeLogCollectService.class), registry);

        String imageId = RecordFileRepository.generateId();
        LocalDateTime now = LocalDateTime.now();
        recordRepository.save(USER, new ContentRecord(
                imageId, "image", "user_input", "图片", "图片记录", List.of(), now, null, "图片附件", "life"));
        recordRepository.saveMedia(USER, imageId, new byte[]{1, 2, 3}, "png", now);
        cardRepo.save(USER, activeCard(imageId, List.of("第一张图")));

        ConversationController.EndConversationRequest endRequest =
                new ConversationController.EndConversationRequest(List.of("第一张图", "看看"), imageId);

        AtomicReference<Throwable> endError = new AtomicReference<>();
        AtomicReference<Throwable> appendError = new AtomicReference<>();
        List<String> appended = new ArrayList<>();

        Thread endThread = new Thread(() -> {
            cardRepo.armWritebackThread();
            try {
                controller.endConversation(USER, endRequest);
            } catch (Throwable t) {
                endError.set(t);
            }
        }, "end-thread-media");

        Thread appendThread = new Thread(() -> {
            try {
                assertTrue(cardRepo.awaitWritebackReached(), "end 未在 15s 内到达写回前钩子");
                cardRepo.beginAppendPhase();
                mediaService.askImage(USER, imageId, "这是什么");
                appended.add("这是什么");
            } catch (Throwable t) {
                appendError.set(t);
            }
        }, "media-append-thread");
        cardRepo.setAppendThread(appendThread);

        endThread.start();
        appendThread.start();
        endThread.join(15_000);
        appendThread.join(15_000);

        assertFalse(endThread.isAlive(), "end 线程未在 15s 内结束（用例无效）");
        assertFalse(appendThread.isAlive(), "图片追问线程未在 15s 内结束（用例无效）");
        assertNull(endError.get(), "end 线程异常: " + endError.get());
        assertNull(appendError.get(), "图片追问线程异常: " + appendError.get());
        assertEquals(List.of("这是什么"), appended, "图片追问线程应已完成（否则用例无效）");
        List<String> texts = turnsOf(cardRepo, imageId);
        assertTrue(texts.contains("这是什么") && texts.contains("看图回答"),
                "end 写回覆盖了并发图片追问的 Q/A（P2-工程13）| turns=" + texts);
        assertEquals(3, texts.size(), "turns 应为 [第一张图, 这是什么, 看图回答] | turns=" + texts);
    }

    // ── 用例 3：并发上限压测（1 个 end + N 个 append 同时打同一张卡）──

    @Test
    void concurrentAppendsAndEnd_noTurnLost() throws Exception {
        InMemoryFileStorage fs = new InMemoryFileStorage();
        CardFileRepository cardRepo = new CardFileRepository(fs);
        CardLockRegistry registry = new CardLockRegistry();

        ConversationController controller = new ConversationController(
                new TestAiClient(), recordRepo(fs), cardRepo, new MemoryService(fs), registry,
                actionReviewService(fs));
        QuestionAppService questionService = questionService(cardRepo, registry, null);

        String cardId = "card_race_stress_1";
        cardRepo.save(USER, activeCard(cardId, List.of("起点")));

        int appends = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        threads.add(new Thread(() -> {
            awaitQuietly(start);
            controller.endConversation(USER,
                    new ConversationController.EndConversationRequest(List.of("起点"), cardId));
        }, "end-stress"));
        for (int i = 0; i < appends; i++) {
            String text = "并发轮次-" + i;
            threads.add(new Thread(() -> {
                awaitQuietly(start);
                questionService.ensureCardWithUserTurn(USER, cardId, text, LocalDateTime.now());
            }, "append-stress-" + i));
        }

        threads.forEach(Thread::start);
        start.countDown();
        for (Thread t : threads) {
            t.join(15_000);
            assertFalse(t.isAlive(), "线程未在 15s 内结束（用例无效）| thread=" + t.getName());
        }

        List<String> texts = turnsOf(cardRepo, cardId);
        for (int i = 0; i < appends; i++) {
            assertTrue(texts.contains("并发轮次-" + i),
                    "并发 append 的轮次丢失（P2-工程13）| 缺=" + ("并发轮次-" + i) + " | turns=" + texts);
        }
        assertEquals(appends + 1, texts.size(), "turns 应 = 起点 + N 个并发轮次 | turns=" + texts);
    }

    // ── 用例 4：重补写回 与 文本 append 交错（P2-工程13 发现1，2026-10-05 独立并发审查）──

    @Test
    void retryWritebackDuringAppend_isNotLost() throws Exception {
        InMemoryFileStorage fs = new InMemoryFileStorage();
        RacingCardFileRepository cardRepo = new RacingCardFileRepository(fs);
        CardLockRegistry registry = new CardLockRegistry();

        QuestionAppService questionService = questionService(cardRepo, registry, null);

        String cardId = "card_race_retry_1";
        cardRepo.save(USER, activeCard(cardId, List.of("旧轮次"))); // summary 空 → 重补候选

        // 重补服务：AI 返回 summary/tags；无待补记录
        RecordRepository records = mock(RecordRepository.class);
        when(records.findAll(USER)).thenReturn(List.of());
        AiClient ai = mock(AiClient.class);
        when(ai.understand(any())).thenReturn(new AiUnderstanding(
                "重补总结", null, null, null, List.of("重补"), "neutral", "life", false, null, "[Test]"));
        AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findAll()).thenReturn(List.of(new Account(USER, "user", true, null)));
        RecordRetryService retryService = new RecordRetryService(
                records, mock(RecordUnderstandingService.class), ai,
                new MemoryService(fs), cardRepo, accounts, mock(PluginService.class), registry);

        AtomicReference<Throwable> retryError = new AtomicReference<>();
        AtomicReference<Throwable> appendError = new AtomicReference<>();
        List<String> appended = new ArrayList<>();

        Thread retryThread = new Thread(() -> {
            cardRepo.armWritebackThread();
            try {
                retryService.retryUnprocessed(USER);
            } catch (Throwable t) {
                retryError.set(t);
            }
        }, "retry-thread");

        Thread appendThread = new Thread(() -> {
            try {
                assertTrue(cardRepo.awaitWritebackReached(), "重补未在 15s 内到达写回前钩子");
                cardRepo.beginAppendPhase();
                questionService.ensureCardWithUserTurn(USER, cardId, "窗口内新轮次", LocalDateTime.now());
                appended.add("窗口内新轮次");
            } catch (Throwable t) {
                appendError.set(t);
            }
        }, "append-thread-retry");
        cardRepo.setAppendThread(appendThread);

        retryThread.start();
        appendThread.start();
        retryThread.join(15_000);
        appendThread.join(15_000);

        assertFalse(retryThread.isAlive(), "重补线程未在 15s 内结束（用例无效）");
        assertFalse(appendThread.isAlive(), "append 线程未在 15s 内结束（用例无效）");
        assertNull(retryError.get(), "重补线程异常: " + retryError.get());
        assertNull(appendError.get(), "append 线程异常: " + appendError.get());
        assertEquals(List.of("窗口内新轮次"), appended, "append 线程应已完成（否则用例无效）");

        List<String> texts = turnsOf(cardRepo, cardId);
        assertTrue(texts.contains("窗口内新轮次"),
                "重补用旧快照写回，覆盖了窗口内 append 的轮次（P2-工程13 发现1：processCard 未持 cardLock）"
                        + " | turns=" + texts);
        assertEquals(2, texts.size(), "turns 应为 [旧轮次, 窗口内新轮次] | turns=" + texts);
        assertEquals("重补总结", cardRepo.findById(USER, cardId).orElseThrow().summary(),
                "重补仍须写入自己负责的 summary 字段");
    }

    // ── 用例 5：end 写回 与 finishAnswer 的 AI 轮 append 交错（P2-工程13 发现4）──

    @Test
    void finishAnswerAiTurnDuringEnd_isNotLost() throws Exception {
        InMemoryFileStorage fs = new InMemoryFileStorage();
        RacingCardFileRepository cardRepo = new RacingCardFileRepository(fs);
        CardLockRegistry registry = new CardLockRegistry();

        ConversationController controller = new ConversationController(
                new TestAiClient(), recordRepo(fs), cardRepo, new MemoryService(fs), registry,
                actionReviewService(fs));

        ContextEngine contextEngine = mock(ContextEngine.class);
        when(contextEngine.compose(anyString(), anyString(), any(), anyString()))
                .thenReturn(ContextPackage.simple("question", "", "问答", "问答正文", List.of(), "prompt"));
        QuestionAppService questionService = questionService(cardRepo, registry, contextEngine);

        String cardId = "card_race_finish_1";
        cardRepo.save(USER, activeCard(cardId, List.of("第一句")));

        ContentRecord questionRecord = new ContentRecord(
                "rec_race_finish", "question", "user_input", "第一句", "第一句",
                List.of(), LocalDateTime.now(), "question", null, "life");

        ConversationController.EndConversationRequest endRequest =
                new ConversationController.EndConversationRequest(List.of("第一句", "阿呆回一句"), cardId);

        AtomicReference<Throwable> endError = new AtomicReference<>();
        AtomicReference<Throwable> appendError = new AtomicReference<>();
        List<String> appended = new ArrayList<>();

        Thread endThread = new Thread(() -> {
            cardRepo.armWritebackThread();
            try {
                controller.endConversation(USER, endRequest);
            } catch (Throwable t) {
                endError.set(t);
            }
        }, "end-thread-finish");

        Thread appendThread = new Thread(() -> {
            try {
                assertTrue(cardRepo.awaitWritebackReached(), "end 未在 15s 内到达写回前钩子");
                cardRepo.beginAppendPhase();
                questionService.answer(USER, questionRecord, cardId); // 走 finishAnswer → AI 轮 append
                appended.add("AI轮");
            } catch (Throwable t) {
                appendError.set(t);
            }
        }, "finish-answer-thread");
        cardRepo.setAppendThread(appendThread);

        endThread.start();
        appendThread.start();
        endThread.join(15_000);
        appendThread.join(15_000);

        assertFalse(endThread.isAlive(), "end 线程未在 15s 内结束（用例无效）");
        assertFalse(appendThread.isAlive(), "finishAnswer 线程未在 15s 内结束（用例无效）");
        assertNull(endError.get(), "end 线程异常: " + endError.get());
        assertNull(appendError.get(), "finishAnswer 线程异常: " + appendError.get());
        assertEquals(List.of("AI轮"), appended, "finishAnswer 线程应已完成（否则用例无效）");

        List<CardRecord.Turn> turns = cardRepo.findById(USER, cardId).orElseThrow().turns();
        assertEquals(2, turns.size(),
                "end 写回覆盖了并发 finishAnswer 追加的 AI 轮（P2-工程13 发现4）| turns="
                        + turns.stream().map(CardRecord.Turn::text).toList());
        assertFalse(turns.get(1).isUser(), "第二条应为 finishAnswer 追加的 AI 轮");
        assertTrue(turns.get(1).text() != null && !turns.get(1).text().isBlank(), "AI 轮文本不得为空");
    }

    // ── helpers ──

    /**
     * 写回线程（持 cardLock、已读旧快照）等待 append 线程给出一个**真实事件**：
     * ① append 已落盘（appendSaved）= 共锁未生效（变异）→ 随后旧快照写回必然覆盖 → 用例红；
     * ② append 线程 BLOCKED = 已被同一把 cardLock 挡住（修复生效）→ 放行，append 在本线程释放锁后写入。
     * <p>两者都是可观测事实，**不用固定超时当绿灯**（P2-工程13 发现11：原实现绿灯靠
     * {@code await(HOLD_MS=3000)} 超时达成）。deadline 只是「两件事都没发生」的兜底，触发即用例无效。
     */
    private static void awaitAppendSettled(Thread appendThread, CountDownLatch appendSaved)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (appendSaved.await(10, TimeUnit.MILLISECONDS)) return;
            if (appendThread != null && appendThread.getState() == Thread.State.BLOCKED) return;
        }
        throw new AssertionError("append 线程既未落盘也未阻塞在 cardLock 上——竞态窗口未建立（用例无效）");
    }

    /** REVIEW P2-交易73：动作搬运器（本类不测动作落盘，控制器需要它才能构造）。 */
    private static ActionReviewService actionReviewService(InMemoryFileStorage storage) {
        TodoFileRepository todoRepo = new TodoFileRepository(storage);
        return new ActionReviewService(todoRepo, new TodoAppService(todoRepo, new MemoryService(storage)));
    }

    private static RecordFileRepository recordRepo(InMemoryFileStorage fs) {
        RecordFileRepository repo = new RecordFileRepository(fs);
        repo.setTagIndexService(new TagIndexService(fs));
        return repo;
    }

    private static QuestionAppService questionService(CardFileRepository cardRepo, CardLockRegistry registry,
                                                      ContextEngine contextEngine) {
        return new QuestionAppService(
                contextEngine != null ? contextEngine : mock(ContextEngine.class),
                cardRepo, recordRepo(new InMemoryFileStorage()),
                new MemoryService(new InMemoryFileStorage()), new TestAiClient(), null,
                mock(PluginService.class), registry);
    }

    private static CardRecord activeCard(String cardId, List<String> userTexts) {
        List<CardRecord.Turn> turns = new ArrayList<>();
        for (String t : userTexts) turns.add(new CardRecord.Turn(true, t, "10:00"));
        LocalDateTime now = LocalDateTime.now();
        return new CardRecord(cardId, "conversation", "active", List.of(), turns, null, now, now);
    }

    private static List<String> turnsOf(CardFileRepository repo, String cardId) {
        CardRecord card = repo.findById(USER, cardId).orElseThrow();
        return card.turns().stream().map(CardRecord.Turn::text).toList();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
