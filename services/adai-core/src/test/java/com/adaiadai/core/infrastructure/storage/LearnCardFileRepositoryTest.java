package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.learn.LearnCard;
import com.adaiadai.core.domain.learn.LearnException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LearnCardFileRepositoryTest — learn 卡片 md 存储（RFC 20260829）。
 * <p>
 * 验证：save/find round-trip（含中文标题/日期/列表字段）、list 按 type、同日同名防覆盖、
 * 多用户隔离、损坏文件跳过、原始素材留存、fileStem 清洗防路径逃逸。
 */
class LearnCardFileRepositoryTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private LearnCardFileRepository repository;

    @BeforeEach
    void setUp() {
        repository = new LearnCardFileRepository(storage);
    }

    private LearnCard sample(String type, String title, LocalDate created) {
        return new LearnCard(type, title, "bilibili", "马克的技术工作坊",
                "https://b23.tv/xxx", "2026-05-05", created,
                LearnCard.STATUS_NEW, type.equals("trading"), "与 R66 止损互补",
                List.of("止损", "回调"), "回调到一半才是买点，核心是几何口径",
                List.of("02:31 回调一半 = (high+low)/2", "05:47 与 R66 的关系"),
                List.of("它说的回调一半和课程口径是否一致？"));
    }

    @Test
    void saveAndFind_roundTrip_preservesAllFields() {
        LearnCard card = sample(LearnCard.TYPE_TRADING, "回调一半的判定", LocalDate.of(2026, 9, 6));
        repository.save("adai", card);

        Optional<LearnCard> loaded = repository.find("adai", LearnCard.TYPE_TRADING, card.title());
        assertTrue(loaded.isPresent());
        LearnCard got = loaded.get();
        assertEquals(card.title(), got.title());
        assertEquals(LearnCard.TYPE_TRADING, got.type());
        assertEquals("bilibili", got.platform());
        assertEquals("马克的技术工作坊", got.author());
        assertEquals("2026-05-05", got.published());
        assertEquals(LocalDate.of(2026, 9, 6), got.created());
        assertTrue(got.tradeRelated(), "trading 内容带可执行规则 → trade_related=true");
        assertEquals("与 R66 止损互补", got.tradeNote());
        assertEquals(List.of("止损", "回调"), got.tags());
        assertEquals("回调到一半才是买点，核心是几何口径", got.coreView());
        assertEquals(2, got.keyPoints().size());
        assertEquals(1, got.questions().size());
    }

    @Test
    void nonTradingContent_tradeRelatedForcedFalse() {
        LearnCard aiCard = new LearnCard(LearnCard.TYPE_AI, "RAG 与 Agent 的区别",
                "bilibili", "UP", "https://b23.tv/yyy", null,
                LocalDate.of(2026, 9, 6), LearnCard.STATUS_NEW, true, "不应保留",
                List.of("rag"), "核心观点", List.of("要点1"), List.of("疑问1"));
        assertFalse(aiCard.tradeRelated(), "非 trading 内容 trade_related 强制 false（防 LLM 幻觉）");
    }

    @Test
    void saveSameDaySameTitle_throws() {
        LearnCard card = sample(LearnCard.TYPE_AI, "RAG 与 Agent 的区别", LocalDate.of(2026, 9, 6));
        repository.save("adai", card);
        LearnException e = assertThrows(LearnException.class,
                () -> repository.save("adai", sample(LearnCard.TYPE_AI, "RAG 与 Agent 的区别", LocalDate.of(2026, 9, 6))));
        assertTrue(e.getMessage().contains("已有同日同名卡片"));
    }

    @Test
    void sameTitleDifferentType_orDifferentDay_allowed() {
        repository.save("adai", sample(LearnCard.TYPE_AI, "同一标题", LocalDate.of(2026, 9, 6)));
        // 不同类型 → 不同路径，可存
        repository.save("adai", sample(LearnCard.TYPE_OTHER, "同一标题", LocalDate.of(2026, 9, 6)));
        assertEquals(1, repository.list("adai", LearnCard.TYPE_AI).size());
        assertEquals(1, repository.list("adai", LearnCard.TYPE_OTHER).size());
    }

    @Test
    void list_filtersByType_andSortsCreatedDesc() {
        repository.save("adai", sample(LearnCard.TYPE_AI, "较早一篇", LocalDate.of(2026, 9, 1)));
        repository.save("adai", sample(LearnCard.TYPE_AI, "较新一篇", LocalDate.of(2026, 9, 6)));
        repository.save("adai", sample(LearnCard.TYPE_TRADING, "交易卡片", LocalDate.of(2026, 9, 3)));

        List<LearnCard> ai = repository.list("adai", LearnCard.TYPE_AI);
        assertEquals(2, ai.size());
        assertEquals("较新一篇", ai.get(0).title(), "created 倒序");
        List<LearnCard> trading = repository.list("adai", LearnCard.TYPE_TRADING);
        assertEquals(1, trading.size());
        assertEquals(0, repository.list("adai", LearnCard.TYPE_OTHER).size());
    }

    @Test
    void tree_groupsByType() {
        repository.save("adai", sample(LearnCard.TYPE_AI, "AI 卡片", LocalDate.of(2026, 9, 6)));
        repository.save("adai", sample(LearnCard.TYPE_TRADING, "交易卡片", LocalDate.of(2026, 9, 6)));
        var tree = repository.tree("adai");
        assertEquals(2, tree.size());
        assertTrue(tree.containsKey(LearnCard.TYPE_AI));
        assertTrue(tree.containsKey(LearnCard.TYPE_TRADING));
        assertEquals(1, tree.get(LearnCard.TYPE_AI).size());
    }

    @Test
    void userIsolation_sameTitleDifferentUsers() {
        repository.save("adai", sample(LearnCard.TYPE_AI, "我的卡片", LocalDate.of(2026, 9, 6)));
        assertTrue(repository.find("adai", LearnCard.TYPE_AI, "我的卡片").isPresent());
        assertFalse(repository.find("bob", LearnCard.TYPE_AI, "我的卡片").isPresent(),
                "bob 不应看到 adai 的卡片");
        assertEquals(0, repository.list("bob", LearnCard.TYPE_AI).size());
    }

    @Test
    void corruptedFile_skippedInList() {
        repository.save("adai", sample(LearnCard.TYPE_AI, "好卡片", LocalDate.of(2026, 9, 6)));
        // 直接写入损坏文件（无 frontmatter）
        storage.write("adai", "learn/ai/2026-09-06_坏卡片.md", "这不是合法卡片内容\n没有 frontmatter");
        List<LearnCard> ai = repository.list("adai", LearnCard.TYPE_AI);
        assertEquals(1, ai.size(), "损坏文件应跳过不中断");
        assertEquals("好卡片", ai.get(0).title());
    }

    @Test
    void saveRawSource_persistsOriginalMaterial() {
        repository.saveRawSource("adai", "原始字幕全文……");
        var files = storage.listFiles("adai", "learn/_raw");
        assertEquals(1, files.size());
        assertTrue(files.get(0).endsWith(".txt"));
        assertTrue(storage.read("adai", files.get(0)).contains("原始字幕全文"));
    }

    @Test
    void fileStem_cleansUnsafeCharacters() {
        assertEquals("RAG-与-Agent-的区别", LearnCardFileRepository.fileStem("RAG/与*Agent?的区别"));
        assertEquals("untitled", LearnCardFileRepository.fileStem("///***"));
        assertEquals("untitled", LearnCardFileRepository.fileStem("   "));
        assertEquals("untitled", LearnCardFileRepository.fileStem("...."));
        assertFalse(LearnCardFileRepository.fileStem("../etc/passwd").contains(".."));
        assertFalse(LearnCardFileRepository.fileStem("../etc/passwd").contains("/"));
    }
}
