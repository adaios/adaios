package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.application.QuestionAppService;
import com.adaiadai.core.infrastructure.ai.llm.TestAiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * CardMigrationService — 迁移逻辑测试。
 * <p>
 * 生产死循环回归（2026-08-10）：迁移把旧卡复制到 records/cards/.../card_{id}.md
 * 但内容 id 未改写为 card_ 前缀、也不删旧文件 → 同 id 双文件 → retryCards 死循环。
 * 修复：迁移时重写 frontmatter id + 成功后删旧文件（move 语义）。
 */
class CardMigrationServiceTest {

    private InMemoryFileStorage storage;
    private CardFileRepository cardRepository;
    private CardMigrationService service;

    @BeforeEach
    void setUp() {
        storage = new InMemoryFileStorage();
        cardRepository = new CardFileRepository(storage);
        service = new CardMigrationService(storage, cardRepository, new CardLockRegistry());
    }

    private String cardContent(String id, String tags) {
        return """
                ---
                id: %s
                type: conversation
                status: active
                tags: [%s]
                createdAt: 2026-07-23T14:43:02.933953697
                updatedAt: 2026-07-23T14:43:02.933953697
                ---

                ## 14:43
                用户：我帅么
                """.formatted(id, tags);
    }

    @Test
    void migrate_writesPrefixedIdAndDeletesOldFile() {
        // 旧格式卡片位于 records/ 根目录（无 card_ 前缀）
        storage.write("default", "records/1784788982678.md", cardContent("1784788982678", "外貌"));

        CardMigrationService.MigrationResult result = service.migrate("default");

        assertEquals(1, result.migrated(), "应成功迁移 1 张");
        assertTrue(result.migratedFiles().get(0).contains("records/cards/2026/07/23/card_1784788982678.md"),
                "新文件应落在 cards 目录且带 card_ 前缀");
        // 旧文件应被删除（move 语义，避免同 id 双文件）
        assertFalse(storage.exists("default", "records/1784788982678.md"), "旧文件应删除");
        // 新文件 frontmatter id 应带 card_ 前缀
        String newContent = storage.read("default", "records/cards/2026/07/23/card_1784788982678.md");
        assertTrue(newContent.contains("id: card_1784788982678"), "frontmatter id 应改写为 card_ 前缀");
        // findAll 去重后应为一条完整卡片
        var cards = cardRepository.findAll("default");
        assertEquals(1, cards.size(), "迁移后应只有一条卡片");
        assertEquals("card_1784788982678", cards.get(0).id(), "卡片 id 应带 card_ 前缀");
    }

    // ── REVIEW P2-工程13 发现3：迁移写盘/删除与 append/end 共锁；目标已存在时不得用旧内容覆盖 ──

    @Test
    void migrate_doesNotOverwriteNewerCardFile_whenTargetAlreadyExists() {
        // 旧目录里有一份旧快照（只有 1 轮），cards 目录里已有正在聊的最新版（2 轮，append 已写入）。
        // 迁移若照旧 write(newPath) 就会用旧内容覆盖最新版 → 丢轮次（发现3）。
        storage.write("default", "records/1784788982678.md", cardContent("1784788982678", "外貌"));
        storage.write("default", "records/cards/2026/07/23/card_1784788982678.md", """
                ---
                id: card_1784788982678
                type: conversation
                status: active
                tags: [外貌]
                createdAt: 2026-07-23T14:43:02.933953697
                updatedAt: 2026-07-23T14:43:02.933953697
                ---

                ## 14:43
                用户：我帅么

                ## 14:45
                用户：那还用说
                """);

        CardMigrationService.MigrationResult result = service.migrate("default");

        assertEquals(1, result.migrated(), "旧目录文件仍应被迁移处理（move 语义）");
        assertFalse(storage.exists("default", "records/1784788982678.md"), "旧文件应删除（去重）");
        String kept = storage.read("default", "records/cards/2026/07/23/card_1784788982678.md");
        assertTrue(kept.contains("那还用说"),
                "目标文件已存在时迁移不得用旧目录内容覆盖（会丢窗口内 append 的轮次）");
    }

    @Test
    void migrate_skipsCardsAlreadyInCardsDir() {
        // cards 目录下的文件不应被迁移扫描到（避免重复迁移）
        storage.write("default", "records/cards/2026/07/23/card_1784788982678.md",
                cardContent("card_1784788982678", "外貌"));

        CardMigrationService.MigrationResult result = service.migrate("default");

        assertEquals(0, result.migrated(), "cards 目录内文件不应被重复迁移");
        assertTrue(storage.exists("default", "records/cards/2026/07/23/card_1784788982678.md"), "原文件应保留");
    }

    // ── #216：判定收紧 + 无 id 跳过（误判即删 / 数据淹没防护）──

    @Test
    void migrate_skipsNoteWithHeadingsButNoConversationMarker() {
        // 普通带 ## 标题的笔记（无「用户：」对话标记）不应被当卡片迁移并删原文件
        storage.write("default", "records/123456.md", """
                ---
                title: 生活笔记
                type: note
                ---

                ## 今天想记录的事情
                买了个新键盘
                """);

        CardMigrationService.MigrationResult result = service.migrate("default");

        assertEquals(0, result.migrated(), "非对话笔记不应被迁移");
        assertTrue(storage.exists("default", "records/123456.md"), "原文件应保留（不误删）");
    }

    @Test
    void migrate_skipsCardWithoutIdField() {
        // 缺 id 字段的卡片跳过（原并入 card_unknown → findAll 合并为一条，数据淹没）
        storage.write("default", "records/999999.md", """
                ---
                type: conversation
                status: active
                ---

                ## 14:43
                用户：你好
                """);

        CardMigrationService.MigrationResult result = service.migrate("default");

        assertEquals(0, result.migrated(), "缺 id 的卡片不应迁移");
        assertTrue(storage.exists("default", "records/999999.md"), "原文件应保留（不并入 card_unknown）");
    }

    // ── #217：rewriteIdInFrontmatter 只改 frontmatter，不误改 body 中的 id: 行 ──

    @Test
    void migrate_doesNotRewriteIdInBody() {
        // body 含 id: 行（如 markdown 引用/列表），frontmatter 的 id 应仍被正确改写
        storage.write("default", "records/555555.md", """
                ---
                id: 555555
                type: conversation
                ---

                ## 14:00
                用户：帮我看看 id: 12345 对不对
                """);

        CardMigrationService.MigrationResult result = service.migrate("default");

        assertEquals(1, result.migrated(), "有效卡片应迁移");
        // 缺 createdAt → 回退固定时间戳（2026-07-01，B37 2026-08-17：不污染当天），路径按该日期
        String fallback = java.time.LocalDate.of(2026, 7, 1).format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String newContent = storage.read("default", "records/cards/" + fallback + "/card_555555.md");
        assertTrue(newContent.contains("id: card_555555"), "frontmatter id 应改写为 card_ 前缀");
        assertTrue(newContent.contains("id: 12345"), "body 中的 id: 12345 不应被误改");
    }

    // ── REVIEW P2-工程15（2026-09-26 夜间批）：迁移重写必须显式携带幂等键 ──

    @Test
    void migrate_preservesIdempotencyKeysOnRewrite() {
        // 带键的卡经迁移重写后，conversationRecordId / conversationTurnsHash 必须仍在——
        // 旧实现（8 参构造器 + 只搬原始文本）解析出的卡是无键卡，重写路径就成了「把有键卡写成无键卡」的隐患
        storage.write("default", "records/1784788982678.md", """
                ---
                id: 1784788982678
                type: conversation
                status: ended
                tags: [外貌]
                createdAt: 2026-07-23T14:43:02.933953697
                updatedAt: 2026-07-23T14:43:02.933953697
                summary: 用户问了外貌
                conversationRecordId: rec_1784788982678
                conversationTurnsHash: 1234567
                ---

                ## 14:43
                用户：我帅么
                """);

        CardMigrationService.MigrationResult result = service.migrate("default");

        assertEquals(1, result.migrated(), "应成功迁移 1 张");
        String migrated = storage.read("default", "records/cards/2026/07/23/card_1784788982678.md");
        assertTrue(migrated.contains("conversationRecordId: rec_1784788982678"),
                "迁移重写不得抹掉 conversationRecordId");
        assertTrue(migrated.contains("conversationTurnsHash: 1234567"),
                "迁移重写不得抹掉 conversationTurnsHash");
        // 读回也必须带键（否则下一次 end 被当成新对话）
        var cards = cardRepository.findAll("default");
        assertEquals(1, cards.size(), "迁移后应只有一条卡片");
        assertEquals("rec_1784788982678", cards.get(0).conversationRecordId(), "迁移后卡片应仍带 conversationRecordId");
        assertEquals(Integer.valueOf(1234567), cards.get(0).conversationTurnsHash(), "迁移后卡片应仍带 conversationTurnsHash");
    }

    @Test
    void migrate_legacyCardWithoutKeys_staysKeyless() {
        // 无键老卡不得被凭空造键：文件与读回都保持无键
        storage.write("default", "records/1784788982679.md", cardContent("1784788982679", "外貌"));

        CardMigrationService.MigrationResult result = service.migrate("default");

        assertEquals(1, result.migrated(), "应成功迁移 1 张");
        String migrated = storage.read("default", "records/cards/2026/07/23/card_1784788982679.md");
        assertFalse(migrated.contains("conversationRecordId"), "无键老卡不得被凭空造 conversationRecordId");
        assertFalse(migrated.contains("conversationTurnsHash"), "无键老卡不得被凭空造 conversationTurnsHash");
        var cards = cardRepository.findAll("default");
        assertEquals(1, cards.size());
        assertNull(cards.get(0).conversationRecordId(), "读回 conversationRecordId 应为 null");
        assertNull(cards.get(0).conversationTurnsHash(), "读回 conversationTurnsHash 应为 null");
    }

    @Test
    void migrate_normalizesUnparseableTurnsHashToKeyless() {
        // 非法指纹（读侧 CardFileRepository.parseFromFile 按「缺失」处理）→ 迁移重写以解析结果为准，
        // 不再原样搬运「人看有、机器不认」的僵尸指纹行（重写后文件与读侧口径一致）。
        // 旧行为（只搬原始文本）会把该行原样留在迁移后的文件里。
        storage.write("default", "records/1784788982680.md", """
                ---
                id: 1784788982680
                type: conversation
                status: ended
                tags: [外貌]
                createdAt: 2026-07-23T14:43:02.933953697
                updatedAt: 2026-07-23T14:43:02.933953697
                conversationRecordId: rec_1784788982680
                conversationTurnsHash: not-a-number
                ---

                ## 14:43
                用户：我帅么
                """);

        CardMigrationService.MigrationResult result = service.migrate("default");

        assertEquals(1, result.migrated(), "应成功迁移 1 张");
        String migrated = storage.read("default", "records/cards/2026/07/23/card_1784788982680.md");
        assertTrue(migrated.contains("conversationRecordId: rec_1784788982680"), "可解析的键必须保留");
        assertFalse(migrated.contains("conversationTurnsHash"),
                "非法指纹行应按读侧口径归一化掉（不得留下僵尸键行）");
        var cards = cardRepository.findAll("default");
        assertEquals(1, cards.size());
        assertEquals("rec_1784788982680", cards.get(0).conversationRecordId());
        assertNull(cards.get(0).conversationTurnsHash(), "非法指纹读回应为 null");
    }

    // ── REVIEW P2-工程13 发现3：迁移写盘与 append 共锁（并发窗口内不丢轮次）──

    /**
     * 在迁移线程写目标卡片文件时挂起的存储：制造「迁移已持锁（或变异时未持锁）、尚未写盘」的窗口，
     * 让 append 线程走真实业务路径插入。
     */
    private static final class HoldWriteStorage extends InMemoryFileStorage {

        private final CountDownLatch writeReached = new CountDownLatch(1);
        private final CountDownLatch appendSaved = new CountDownLatch(1);
        private final AtomicBoolean firstMigrationWrite = new AtomicBoolean(true);
        private final AtomicBoolean appendPhase = new AtomicBoolean(false);
        private volatile Thread appendThread;
        private volatile Thread migrationThread;

        void setAppendThread(Thread thread) {
            this.appendThread = thread;
        }

        void setMigrationThread(Thread thread) {
            this.migrationThread = thread;
        }

        void beginAppendPhase() {
            appendPhase.set(true);
        }

        boolean awaitWriteReached() throws InterruptedException {
            return writeReached.await(15, TimeUnit.SECONDS);
        }

        @Override
        public void write(String userId, String path, String content) {
            // 迁移线程的第一次写 = 写目标卡片文件；只挂这一次，append 线程随后在窗口内尝试写入
            if (Thread.currentThread() == migrationThread && firstMigrationWrite.compareAndSet(true, false)) {
                writeReached.countDown();
                try {
                    awaitAppendSettled(appendThread, appendSaved);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            super.write(userId, path, content);
            if (appendPhase.get() && Thread.currentThread() == appendThread) {
                appendSaved.countDown();
            }
        }
    }

    @Test
    @Timeout(20)
    void migrateConcurrentWithAppend_doesNotLoseTurns() throws Exception {
        HoldWriteStorage holdStorage = new HoldWriteStorage();
        CardFileRepository repo = new CardFileRepository(holdStorage);
        CardLockRegistry registry = new CardLockRegistry();
        CardMigrationService migrationService = new CardMigrationService(holdStorage, repo, registry);

        QuestionAppService questionService = new QuestionAppService(
                mock(com.adaiadai.core.kernel.context.engine.ContextEngine.class),
                repo, mock(com.adaiadai.core.kernel.record.RecordRepository.class),
                mock(com.adaiadai.core.kernel.memory.MemoryService.class),
                new TestAiClient(), null, mock(com.adaiadai.core.kernel.plugin.PluginService.class),
                registry);

        String cardId = "card_1784788982678";
        holdStorage.write("default", "records/1784788982678.md", cardContent("1784788982678", "外貌"));

        AtomicReference<Throwable> errors = new AtomicReference<>();

        Thread migrationThread = new Thread(() -> {
            try {
                migrationService.migrate("default");
            } catch (Throwable t) {
                errors.compareAndSet(null, t);
            }
        }, "migration-thread");

        Thread appendThread = new Thread(() -> {
            try {
                assertTrue(holdStorage.awaitWriteReached(), "迁移未在 15s 内到达写盘点");
                holdStorage.beginAppendPhase();
                questionService.ensureCardWithUserTurn("default", cardId, "窗口内新轮次", LocalDateTime.now());
            } catch (Throwable t) {
                errors.compareAndSet(null, t);
            }
        }, "migration-append-thread");
        holdStorage.setAppendThread(appendThread);
        holdStorage.setMigrationThread(migrationThread);

        migrationThread.start();
        appendThread.start();
        migrationThread.join(15_000);
        appendThread.join(15_000);

        assertFalse(migrationThread.isAlive(), "迁移线程未在 15s 内结束（用例无效）");
        assertFalse(appendThread.isAlive(), "append 线程未在 15s 内结束（用例无效）");
        assertNull(errors.get(), "线程异常: " + errors.get());

        var texts = repo.findById("default", cardId).orElseThrow().turns().stream()
                .map(t -> t.text()).toList();
        assertTrue(texts.contains("窗口内新轮次"),
                "迁移写盘未与 append 共锁，窗口内 append 的轮次被旧内容覆盖 | turns=" + texts);
    }

    /**
     * 与 {@code CardRmwConcurrencyTest} 同口径的竞态同步：迁移线程（持 cardLock、即将写盘）等待
     * append 线程给出一个**真实事件**——① 已落盘（共锁未生效）或 ② BLOCKED 在 cardLock 上（共锁生效）。
     * 不用固定超时当绿灯；deadline 只是「两件事都没发生」的兜底。
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
}
