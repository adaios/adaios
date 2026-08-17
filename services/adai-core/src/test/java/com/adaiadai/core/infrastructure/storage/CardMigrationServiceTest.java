package com.adaiadai.core.infrastructure.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        service = new CardMigrationService(storage, cardRepository);
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
}
