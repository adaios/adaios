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
                List.of("它说的回调一半和课程口径是否一致？"), "");
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
        assertEquals("", got.retell(), "V1 落盘卡片复述段为空");
    }

    // ── V2 复述段建模 + 编辑 ──

    @Test
    void retell_roundTrip_preservedInFile() {
        LearnCard card = new LearnCard(LearnCard.TYPE_AI, "RAG 与 Agent 的区别",
                "bilibili", "UP", "https://b23.tv/yyy", null,
                LocalDate.of(2026, 9, 6), LearnCard.STATUS_NEW, false, null,
                List.of("rag"), "核心观点", List.of("要点1"), List.of("疑问1"),
                "今天自己复述了一遍：RAG 是检索增强，Agent 是自主规划，二者互补。");
        repository.save("adai", card);
        Optional<LearnCard> loaded = repository.find("adai", LearnCard.TYPE_AI, card.title());
        assertTrue(loaded.isPresent());
        assertEquals(card.retell(), loaded.get().retell(), "复述段落 round-trip 保留");
    }

    @Test
    void update_overwritesBody_keepsPath() {
        LearnCard card = sample(LearnCard.TYPE_AI, "RAG 与 Agent 的区别", LocalDate.of(2026, 9, 6));
        repository.save("adai", card);
        LearnCard edited = new LearnCard(card.type(), card.title(), card.platform(), card.author(),
                card.url(), card.published(), card.created(), card.status(), card.tradeRelated(),
                card.tradeNote(), List.of("rag", "检索"), "更新后的核心观点",
                List.of("新要点A", "新要点B"), List.of(), "复述：写了一遍");
        LearnCard updated = repository.update("adai", edited);
        assertEquals("更新后的核心观点", updated.coreView());
        Optional<LearnCard> loaded = repository.find("adai", LearnCard.TYPE_AI, card.title());
        assertTrue(loaded.isPresent());
        assertEquals(List.of("新要点A", "新要点B"), loaded.get().keyPoints());
        assertEquals(List.of(), loaded.get().questions());
        assertEquals("复述：写了一遍", loaded.get().retell());
        assertEquals(1, repository.list("adai", LearnCard.TYPE_AI).size(), "更新不产生新文件");
    }

    @Test
    void update_missingCard_throws() {
        LearnCard card = sample(LearnCard.TYPE_AI, "不存在的卡片", LocalDate.of(2026, 9, 6));
        LearnException e = assertThrows(LearnException.class, () -> repository.update("adai", card));
        assertTrue(e.getMessage().contains("卡片不存在"));
    }

    @Test
    void update_titleChange_notFound_impliesRenameNeedsNewCard() {
        LearnCard card = sample(LearnCard.TYPE_AI, "原标题", LocalDate.of(2026, 9, 6));
        repository.save("adai", card);
        LearnCard renamed = new LearnCard(card.type(), "新标题", card.platform(), card.author(),
                card.url(), card.published(), card.created(), card.status(), card.tradeRelated(),
                card.tradeNote(), card.tags(), card.coreView(), card.keyPoints(), card.questions(),
                card.retell());
        LearnException e = assertThrows(LearnException.class, () -> repository.update("adai", renamed));
        assertTrue(e.getMessage().contains("卡片不存在"), "新标题无对应文件 = 视为不存在，改名走新建卡片");
    }

    @Test
    void update_createdChange_throwsPathStabilityGuard() {
        LearnCard card = sample(LearnCard.TYPE_AI, "原标题", LocalDate.of(2026, 9, 6));
        repository.save("adai", card);
        LearnCard dateShifted = new LearnCard(card.type(), card.title(), card.platform(), card.author(),
                card.url(), card.published(), LocalDate.of(2026, 9, 7), card.status(),
                card.tradeRelated(), card.tradeNote(), card.tags(), card.coreView(),
                card.keyPoints(), card.questions(), card.retell());
        LearnException e = assertThrows(LearnException.class, () -> repository.update("adai", dateShifted));
        assertTrue(e.getMessage().contains("不可修改"), "created 变 = 路径变 = 移动文件，拒绝");
    }

    @Test
    void update_userIsolated() {
        repository.save("adai", sample(LearnCard.TYPE_AI, "我的卡片", LocalDate.of(2026, 9, 6)));
        repository.save("bob", sample(LearnCard.TYPE_AI, "我的卡片", LocalDate.of(2026, 9, 6)));
        LearnCard edited = new LearnCard(LearnCard.TYPE_AI, "我的卡片", null, null, null, null,
                LocalDate.of(2026, 9, 6), LearnCard.STATUS_NEW, false, null, List.of(),
                "adai 改的观点", List.of(), List.of(), "");
        repository.update("adai", edited);
        assertEquals("adai 改的观点",
                repository.find("adai", LearnCard.TYPE_AI, "我的卡片").get().coreView());
        assertEquals("回调到一半才是买点，核心是几何口径",
                repository.find("bob", LearnCard.TYPE_AI, "我的卡片").get().coreView(), "bob 卡片不受影响");
    }

    @Test
    void nonTradingContent_tradeRelatedForcedFalse() {
        LearnCard aiCard = new LearnCard(LearnCard.TYPE_AI, "RAG 与 Agent 的区别",
                "bilibili", "UP", "https://b23.tv/yyy", null,
                LocalDate.of(2026, 9, 6), LearnCard.STATUS_NEW, true, "不应保留",
                List.of("rag"), "核心观点", List.of("要点1"), List.of("疑问1"), "");
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

    // ── V2 复习状态流转 ──

    @Test
    void updateStatus_newToReviewToDone_roundTrip() {
        LearnCard card = sample(LearnCard.TYPE_AI, "RAG 与 Agent 的区别", LocalDate.of(2026, 9, 6));
        repository.save("adai", card);
        assertEquals(LearnCard.STATUS_NEW, repository.find("adai", LearnCard.TYPE_AI, card.title()).get().status());

        LearnCard reviewed = repository.updateStatus("adai", LearnCard.TYPE_AI, card.title(), LearnCard.STATUS_REVIEW);
        assertEquals(LearnCard.STATUS_REVIEW, reviewed.status(), "返回更新后卡片");
        assertEquals(LearnCard.STATUS_REVIEW,
                repository.find("adai", LearnCard.TYPE_AI, card.title()).get().status(), "文件已持久化");
        assertEquals(card.coreView(), reviewed.coreView(), "正文核心观点保留");

        LearnCard done = repository.updateStatus("adai", LearnCard.TYPE_AI, card.title(), LearnCard.STATUS_DONE);
        assertEquals(LearnCard.STATUS_DONE, done.status());
        assertEquals(card.title(), done.title());
    }

    @Test
    void updateStatus_preservesBodyNotModeledLikeRetell() {
        LearnCard card = sample(LearnCard.TYPE_AI, "RAG 与 Agent 的区别", LocalDate.of(2026, 9, 6));
        repository.save("adai", card);
        // 手写「复述」段落（V1 未建模，md 即真相源——流转状态不得覆盖）
        String path = storage.listFiles("adai", "learn/ai").stream()
                .filter(f -> f.endsWith(".md")).findFirst().orElseThrow();
        String content = storage.read("adai", path);
        storage.write("adai", path, content + "\n今天用自己话复述了一遍：RAG 检索增强，Agent 自主规划。\n");

        repository.updateStatus("adai", LearnCard.TYPE_AI, card.title(), LearnCard.STATUS_REVIEW);

        String after = storage.read("adai", path);
        assertTrue(after.contains("status: review"), "frontmatter status 已更新");
        assertTrue(after.contains("今天用自己话复述了一遍"), "正文手写复述原样保留");
        assertTrue(after.contains("02:31 回调一半 = (high+low)/2"), "要点原样保留");
    }

    @Test
    void updateStatus_missingCard_throwsHumanMessage() {
        LearnException e = assertThrows(LearnException.class,
                () -> repository.updateStatus("adai", LearnCard.TYPE_AI, "不存在的卡片", LearnCard.STATUS_REVIEW));
        assertTrue(e.getMessage().contains("卡片不存在"));
    }

    @Test
    void updateStatus_invalidTypeOrBlankTitle_throws() {
        assertThrows(LearnException.class,
                () -> repository.updateStatus("adai", "hacking", "标题", LearnCard.STATUS_REVIEW));
        assertThrows(LearnException.class,
                () -> repository.updateStatus("adai", LearnCard.TYPE_AI, "  ", LearnCard.STATUS_REVIEW));
    }

    @Test
    void updateStatus_userIsolated() {
        repository.save("adai", sample(LearnCard.TYPE_AI, "我的卡片", LocalDate.of(2026, 9, 6)));
        repository.save("bob", sample(LearnCard.TYPE_AI, "我的卡片", LocalDate.of(2026, 9, 6)));
        repository.updateStatus("adai", LearnCard.TYPE_AI, "我的卡片", LearnCard.STATUS_DONE);
        assertEquals(LearnCard.STATUS_DONE,
                repository.find("adai", LearnCard.TYPE_AI, "我的卡片").get().status());
        assertEquals(LearnCard.STATUS_NEW,
                repository.find("bob", LearnCard.TYPE_AI, "我的卡片").get().status(), "bob 卡片不受影响");
    }
}
