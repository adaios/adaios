package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.learn.LearnCard;
import com.adaiadai.core.domain.learn.LearnException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    void saveDuplicateTitle_anyDate_throws() {
        // P1-learn2（2026-09-07）：跨日同名是旧卡无法寻址/改错卡的根源 → 从喂入端拒绝任意日期同名
        LearnCard card = sample(LearnCard.TYPE_AI, "RAG 与 Agent 的区别", LocalDate.of(2026, 9, 6));
        repository.save("adai", card);
        LearnException sameDay = assertThrows(LearnException.class,
                () -> repository.save("adai", sample(LearnCard.TYPE_AI, "RAG 与 Agent 的区别", LocalDate.of(2026, 9, 6))));
        assertTrue(sameDay.getMessage().contains("已有同名卡片"));
        LearnException otherDay = assertThrows(LearnException.class,
                () -> repository.save("adai", sample(LearnCard.TYPE_AI, "RAG 与 Agent 的区别", LocalDate.of(2026, 9, 9))));
        assertTrue(otherDay.getMessage().contains("已有同名卡片"), "跨日同名同样拒绝");
        assertEquals(1, repository.list("adai", LearnCard.TYPE_AI).size(), "未产生第二张同名卡");
    }

    @Test
    void sameTitleDifferentType_allowed() {
        repository.save("adai", sample(LearnCard.TYPE_AI, "同一标题", LocalDate.of(2026, 9, 6)));
        // 不同类型 → 不同目录不同路径，可并存
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
        assertEquals("RAG-与-Agent-的区别", LearnCard.fileStem("RAG/与*Agent?的区别"));
        assertEquals("untitled", LearnCard.fileStem("///***"));
        assertEquals("untitled", LearnCard.fileStem("   "));
        assertEquals("untitled", LearnCard.fileStem("...."));
        assertFalse(LearnCard.fileStem("../etc/passwd").contains(".."));
        assertFalse(LearnCard.fileStem("../etc/passwd").contains("/"));
    }

    // ── V2 复习状态流转 ──

    @Test
    void updateStatus_newToReviewToDone_roundTrip() {
        LearnCard card = sample(LearnCard.TYPE_AI, "RAG 与 Agent 的区别", LocalDate.of(2026, 9, 6));
        repository.save("adai", card);
        assertEquals(LearnCard.STATUS_NEW, repository.find("adai", LearnCard.TYPE_AI, card.title()).get().status());

        LearnCard reviewed = repository.updateStatus("adai", LearnCard.TYPE_AI, card.title(), LearnCard.STATUS_REVIEW, LocalDate.of(2026, 9, 7));
        assertEquals(LearnCard.STATUS_REVIEW, reviewed.status(), "返回更新后卡片");
        assertEquals(LearnCard.STATUS_REVIEW,
                repository.find("adai", LearnCard.TYPE_AI, card.title()).get().status(), "文件已持久化");
        assertEquals(card.coreView(), reviewed.coreView(), "正文核心观点保留");
        assertEquals(LocalDate.of(2026, 9, 7), reviewed.reviewAt(), "S-learn1：进入 review 写 review_at=today");
        assertEquals(null, reviewed.remindedAt(), "进入 review 清 reminded_at（新周期）");

        LearnCard done = repository.updateStatus("adai", LearnCard.TYPE_AI, card.title(), LearnCard.STATUS_DONE, LocalDate.of(2026, 9, 7));
        assertEquals(LearnCard.STATUS_DONE, done.status());
        assertEquals(card.title(), done.title());
        assertEquals(null, done.reviewAt(), "离开 review → 清计时（不再提醒）");
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

        repository.updateStatus("adai", LearnCard.TYPE_AI, card.title(), LearnCard.STATUS_REVIEW, LocalDate.of(2026, 9, 7));

        String after = storage.read("adai", path);
        assertTrue(after.contains("status: review"), "frontmatter status 已更新");
        assertTrue(after.contains("今天用自己话复述了一遍"), "正文手写复述原样保留");
        assertTrue(after.contains("02:31 回调一半 = (high+low)/2"), "要点原样保留");
    }

    @Test
    void updateStatus_missingCard_throwsHumanMessage() {
        LearnException e = assertThrows(LearnException.class,
                () -> repository.updateStatus("adai", LearnCard.TYPE_AI, "不存在的卡片", LearnCard.STATUS_REVIEW, LocalDate.of(2026, 9, 7)));
        assertTrue(e.getMessage().contains("卡片不存在"));
    }

    @Test
    void updateStatus_invalidTypeOrBlankTitle_throws() {
        assertThrows(LearnException.class,
                () -> repository.updateStatus("adai", "hacking", "标题", LearnCard.STATUS_REVIEW, LocalDate.of(2026, 9, 7)));
        assertThrows(LearnException.class,
                () -> repository.updateStatus("adai", LearnCard.TYPE_AI, "  ", LearnCard.STATUS_REVIEW, LocalDate.of(2026, 9, 7)));
    }

    @Test
    void updateStatus_userIsolated() {
        repository.save("adai", sample(LearnCard.TYPE_AI, "我的卡片", LocalDate.of(2026, 9, 6)));
        repository.save("bob", sample(LearnCard.TYPE_AI, "我的卡片", LocalDate.of(2026, 9, 6)));
        repository.updateStatus("adai", LearnCard.TYPE_AI, "我的卡片", LearnCard.STATUS_REVIEW, LocalDate.of(2026, 9, 7));
        repository.updateStatus("adai", LearnCard.TYPE_AI, "我的卡片", LearnCard.STATUS_DONE, LocalDate.of(2026, 9, 7));
        assertEquals(LearnCard.STATUS_DONE,
                repository.find("adai", LearnCard.TYPE_AI, "我的卡片").get().status());
        assertEquals(LearnCard.STATUS_NEW,
                repository.find("bob", LearnCard.TYPE_AI, "我的卡片").get().status(), "bob 卡片不受影响");
    }

    // ── V2 learn 审查修复（2026-09-07）──

    @Test
    void updateStatus_doneToReview_reEntersWithNewReviewAt() {
        LearnCard card = sample(LearnCard.TYPE_AI, "再看一遍", LocalDate.of(2026, 8, 1));
        repository.save("adai", card);
        repository.updateStatus("adai", LearnCard.TYPE_AI, card.title(), LearnCard.STATUS_REVIEW, LocalDate.of(2026, 8, 5));
        repository.updateStatus("adai", LearnCard.TYPE_AI, card.title(), LearnCard.STATUS_DONE, LocalDate.of(2026, 8, 6));
        LearnCard reReview = repository.updateStatus("adai", LearnCard.TYPE_AI, card.title(), LearnCard.STATUS_REVIEW, LocalDate.of(2026, 8, 20));
        assertEquals(LocalDate.of(2026, 8, 20), reReview.reviewAt(), "P2-learn8 done→review 重进：review_at 更新为新进入日");
    }

    @Test
    void updateStatus_illegalJump_throws() {
        LearnCard card = sample(LearnCard.TYPE_AI, "跳变卡", LocalDate.of(2026, 9, 1));
        repository.save("adai", card);
        // P2-learn8：禁止 new→done 跳变（跳过复习队列）
        LearnException e = assertThrows(LearnException.class,
                () -> repository.updateStatus("adai", LearnCard.TYPE_AI, card.title(), LearnCard.STATUS_DONE, LocalDate.of(2026, 9, 7)));
        assertTrue(e.getMessage().contains("不被允许"));
        assertEquals(LearnCard.STATUS_NEW,
                repository.find("adai", LearnCard.TYPE_AI, card.title()).get().status(), "非法流转不落盘");
    }

    @Test
    void updateStatus_doneToNew_jumpBack_throws() {
        LearnCard card = sample(LearnCard.TYPE_AI, "回跳卡", LocalDate.of(2026, 9, 1));
        repository.save("adai", card);
        repository.updateStatus("adai", LearnCard.TYPE_AI, card.title(), LearnCard.STATUS_REVIEW, LocalDate.of(2026, 9, 2));
        repository.updateStatus("adai", LearnCard.TYPE_AI, card.title(), LearnCard.STATUS_DONE, LocalDate.of(2026, 9, 3));
        LearnException e = assertThrows(LearnException.class,
                () -> repository.updateStatus("adai", LearnCard.TYPE_AI, card.title(), LearnCard.STATUS_NEW, LocalDate.of(2026, 9, 7)));
        assertTrue(e.getMessage().contains("不被允许"), "done→new 回跳两步不允许");
    }

    @Test
    void find_multipleSameTitle_throwsHumanMessage() {
        // 历史/手工残留跨日同名（save 已源头拒绝，此处验证读侧防御）
        LearnCard a = sample(LearnCard.TYPE_AI, "同名残留", LocalDate.of(2026, 8, 1));
        storage.write("adai", "learn/ai/2026-08-01_" + LearnCard.fileStem(a.title()) + ".md",
                LearnCardFileRepository.toMarkdown(a));
        LearnCard b = sample(LearnCard.TYPE_AI, "同名残留", LocalDate.of(2026, 8, 5));
        storage.write("adai", "learn/ai/2026-08-05_" + LearnCard.fileStem(b.title()) + ".md",
                LearnCardFileRepository.toMarkdown(b));
        LearnException e = assertThrows(LearnException.class,
                () -> repository.find("adai", LearnCard.TYPE_AI, "同名残留"));
        assertTrue(e.getMessage().contains("2 张同名卡片"), "P1-learn2：歧义显式 400，禁止静默取最新");
        assertTrue(e.getMessage().contains("2026-08-01"), "列出 created 供人工合并");
    }

    @Test
    void markReminded_writesRemindedAt() {
        LearnCard card = sample(LearnCard.TYPE_AI, "提醒节流卡", LocalDate.of(2026, 8, 20));
        repository.save("adai", card);
        repository.updateStatus("adai", LearnCard.TYPE_AI, card.title(), LearnCard.STATUS_REVIEW, LocalDate.of(2026, 8, 20));
        LearnCard reminded = repository.markReminded("adai", LearnCard.TYPE_AI, card.title(), LocalDate.of(2026, 9, 7));
        assertEquals(LocalDate.of(2026, 9, 7), reminded.remindedAt(), "markReminded 写 reminded_at");
        assertEquals(LearnCard.STATUS_REVIEW, reminded.status(), "不改变 status");
        LearnCard loaded = repository.find("adai", LearnCard.TYPE_AI, card.title()).orElseThrow();
        assertEquals(LocalDate.of(2026, 9, 7), loaded.remindedAt(), "已持久化");
    }

    @Test
    void updateStatus_remindedAtClearedOnReEnter() {
        LearnCard card = sample(LearnCard.TYPE_AI, "再入卡", LocalDate.of(2026, 8, 1));
        repository.save("adai", card);
        repository.updateStatus("adai", LearnCard.TYPE_AI, card.title(), LearnCard.STATUS_REVIEW, LocalDate.of(2026, 8, 2));
        repository.markReminded("adai", LearnCard.TYPE_AI, card.title(), LocalDate.of(2026, 8, 10));
        // 用户 done 后 30 天再看一遍 → 重新计时，清 reminded_at
        repository.updateStatus("adai", LearnCard.TYPE_AI, card.title(), LearnCard.STATUS_DONE, LocalDate.of(2026, 8, 15));
        LearnCard reReview = repository.updateStatus("adai", LearnCard.TYPE_AI, card.title(), LearnCard.STATUS_REVIEW, LocalDate.of(2026, 9, 10));
        assertEquals(LocalDate.of(2026, 9, 10), reReview.reviewAt());
        assertEquals(null, reReview.remindedAt(), "重进队列清上次提醒日");
    }

    @Test
    void applyEdit_preservesUnknownFrontmatterKeyAndSection() {
        LearnCard card = sample(LearnCard.TYPE_AI, "未知段卡", LocalDate.of(2026, 9, 6));
        repository.save("adai", card);
        // 手工添加未知 frontmatter 键 + 未知正文段（md 即真相源，编辑不得抹）
        String path = storage.listFiles("adai", "learn/ai").stream()
                .filter(f -> f.endsWith(".md")).findFirst().orElseThrow();
        String content = storage.read("adai", path)
                .replaceFirst("---\n", "---\nmood: 今天状态不错\n")
                + "\n## 我的补充笔记\n这段是手工加的，不许被编辑抹掉。\n";
        storage.write("adai", path, content);

        LearnCard edited = repository.applyEdit("adai", LearnCard.TYPE_AI, card.title(),
                new com.adaiadai.core.domain.learn.LearnCardPatch(
                        "编辑后的观点", null, null, "复述：写了一遍", null, null, List.of("rag", "新标签")));

        assertEquals("编辑后的观点", edited.coreView(), "受管段更新");
        assertEquals(List.of("rag", "新标签"), edited.tags());
        String after = storage.read("adai", path);
        assertTrue(after.contains("mood: 今天状态不错"), "P2-learn7：未知 frontmatter 键保留");
        assertTrue(after.contains("我的补充笔记") && after.contains("不许被编辑抹掉"),
                "P2-learn7：未知正文段保留");
        assertTrue(after.contains("复述：写了一遍"), "受管段已写回");
    }

    @Test
    void applyEdit_missingCard_throws() {
        LearnCard card = sample(LearnCard.TYPE_AI, "不存在的卡", LocalDate.of(2026, 9, 6));
        LearnException e = assertThrows(LearnException.class,
                () -> repository.applyEdit("adai", card.type(), card.title(),
                        new com.adaiadai.core.domain.learn.LearnCardPatch(null, null, null, null, null, null, null)));
        assertTrue(e.getMessage().contains("卡片不存在"));
    }

    // ── 读侧对齐批（2026-09-12）：A 形态卡片读得到、改不动 ──

    /** A 形态卡片（Mac 上 DSH 技能写的）：主题子目录 + 编号文件名 + 段名带括号后缀 + 嵌套 source 块。 */
    private static final String A_FORM_CARD = """
            ---
            title: Harness 到底是什么？
            type: ai
            source:
              platform: bilibili
              author: 马克的技术工作坊
              url: https://www.bilibili.com/video/BV12LR1B3EUt/
              published: 2026-05-05
            created: 2026-09-06
            status: new
            trade_related: false
            tags: [harness]
            ---
            ## 核心观点（一句话）

            **Harness = 围绕模型搭系统的工程学科**。

            ## 内容脉络

            - 第一条脉络

            ## 我的疑问

            - 与软件工程的关系？
            """;

    @Test
    void normalizeSectionName_toleratesNumberingAndSuffix() {
        assertEquals("核心观点", LearnCardFileRepository.normalizeSectionName("核心观点（一句话）"));
        assertEquals("核心观点", LearnCardFileRepository.normalizeSectionName("二、核心观点"));
        assertEquals("核心观点", LearnCardFileRepository.normalizeSectionName("2. 核心观点"));
        assertEquals("我的疑问", LearnCardFileRepository.normalizeSectionName("我的疑问（可讨论点）"));
        assertEquals("关键要点", LearnCardFileRepository.normalizeSectionName("关键要点"));
        assertEquals("内容脉络", LearnCardFileRepository.normalizeSectionName("内容脉络"),
                "不同名字的段不冒充别的段（不做语义改名）");
    }

    @Test
    void aFormCard_isParsedWithCoreViewAndQuestions() {
        storage.write("adai", "learn/ai/harness/01-video-card.md", A_FORM_CARD);

        List<LearnCard> cards = repository.list("adai", LearnCard.TYPE_AI);

        assertEquals(1, cards.size(), "递归列表会读到主题子目录里的卡（看得见）");
        LearnCard card = cards.get(0);
        assertTrue(card.coreView().contains("Harness = 围绕模型搭系统"),
                "段名带后缀也要能解析出核心观点：" + card.coreView());
        assertEquals(1, card.questions().size(), "「我的疑问」段能解析");
        assertTrue(card.keyPoints().isEmpty(), "「内容脉络」是另一个名字的段，不冒充关键要点（如实为空）");
    }

    @Test
    void aFormCard_editIsRefusedWithHumanMessage_fileUntouched() {
        storage.write("adai", "learn/ai/harness/01-video-card.md", A_FORM_CARD);
        String before = storage.read("adai", "learn/ai/harness/01-video-card.md");

        LearnException e = assertThrows(LearnException.class, () -> repository.applyEdit(
                "adai", LearnCard.TYPE_AI, "Harness 到底是什么？",
                new com.adaiadai.core.domain.learn.LearnCardPatch("改过的核心观点", null, null, null, null, null, null)));

        assertTrue(e.getMessage().contains("Mac 上整理"), "要说清为什么不能改：" + e.getMessage());
        assertTrue(e.getMessage().contains("另存"), "要给可行路径");
        assertEquals(before, storage.read("adai", "learn/ai/harness/01-video-card.md"),
                "别人的原始文件一个字都不能动");
        assertFalse(storage.exists("adai", "learn/ai/2026-09-06_"
                + LearnCard.fileStem("Harness 到底是什么？") + ".md"), "也不该另写一份到产品路径");
    }

    @Test
    void aFormCard_statusChangeIsRefused() {
        storage.write("adai", "learn/ai/harness/01-video-card.md", A_FORM_CARD);

        LearnException e = assertThrows(LearnException.class, () -> repository.updateStatus(
                "adai", LearnCard.TYPE_AI, "Harness 到底是什么？", LearnCard.STATUS_REVIEW, LocalDate.of(2026, 9, 12)));

        assertTrue(e.getMessage().contains("Mac 上整理"));
    }

    @Test
    void productCard_isStillEditable_regression() {
        LearnCard card = sample(LearnCard.TYPE_AI, "产品卡", LocalDate.of(2026, 9, 12));
        repository.save("adai", card);

        LearnCard updated = repository.applyEdit("adai", LearnCard.TYPE_AI, "产品卡",
                new com.adaiadai.core.domain.learn.LearnCardPatch("改过的核心观点", null, null, null, null, null, null));

        assertEquals("改过的核心观点", updated.coreView(), "本实现产出的卡照旧可编辑（守卫不能误伤）");
    }

    @Test
    void handRenamedManagedSection_isNotDuplicatedOnWrite() {
        // 手工把段名改成「核心观点（一句话）」→ 写回时按归一识别为受管段，不再补一份重复段
        LearnCard card = sample(LearnCard.TYPE_AI, "改名卡", LocalDate.of(2026, 9, 12));
        repository.save("adai", card);
        String path = storage.listFiles("adai", "learn/ai").stream()
                .filter(f -> f.endsWith(".md") && !f.endsWith("README.md"))
                .findFirst().orElseThrow();
        storage.write("adai", path, storage.read("adai", path).replace("## 核心观点", "## 核心观点（一句话）"));

        LearnCard updated = repository.applyEdit("adai", LearnCard.TYPE_AI, "改名卡",
                new com.adaiadai.core.domain.learn.LearnCardPatch("新观点", null, null, null, null, null, null));

        assertEquals("新观点", updated.coreView());
        String content = storage.read("adai", path);
        assertEquals(1, content.split("## 核心观点", -1).length - 1, "不该出现重复的核心观点段");
    }

    // ── 结构统一批（2026-09-12）：主题目录契约 + README 索引 + 素材归位 + 同名消解 ──

    @Test
    void save_landsInTopicDir_withSeqAndKeepsTopicInFrontmatter() {
        repository.save("adai", new LearnCard(LearnCard.TYPE_AI, "回调一半的判定",
                "bilibili", "UP", "https://b23.tv/a", "2026-05-05", LocalDate.of(2026, 9, 12),
                LearnCard.STATUS_NEW, false, null, List.of("买点"), "观点", List.of("要点"), List.of("疑问"), "",
                "量价关系"));

        assertTrue(storage.exists("adai", "learn/ai/量价关系/01-回调一半的判定.md"),
                "新产品卡落 {type}/{topic}/NN-{slug}.md（与 Mac 侧技能同契约）；实际："
                        + storage.listFiles("adai", "learn/ai"));
        String content = storage.read("adai", "learn/ai/量价关系/01-回调一半的判定.md");
        assertTrue(content.contains("topic: 量价关系"), "主题写进 frontmatter");
        assertTrue(content.contains("origin: product"), "带 origin 标记（判定可写的依据）");
    }

    @Test
    void save_sameTopic_incrementsSeq_andKeepsSingleReadmeEntryPerCard() {
        for (String title : List.of("第一篇", "第二篇")) {
            repository.save("adai", new LearnCard(LearnCard.TYPE_AI, title, "bilibili", "UP", null, null,
                    LocalDate.of(2026, 9, 12), LearnCard.STATUS_NEW, false, null, List.of(), "观点",
                    List.of("要点"), List.of(), "", "量价关系"));
        }

        assertTrue(storage.exists("adai", "learn/ai/量价关系/01-第一篇.md"));
        assertTrue(storage.exists("adai", "learn/ai/量价关系/02-第二篇.md"), "同主题编号递增");
        String readme = storage.read("adai", "learn/ai/量价关系/README.md");
        assertTrue(readme.contains("阿呆整理记录"), "README 索引带自动段标记");
        assertTrue(readme.contains("01-第一篇.md") && readme.contains("02-第二篇.md"), "两篇都进索引");
        assertEquals(2, repository.list("adai", LearnCard.TYPE_AI).size(), "README 本身不当卡片");
    }

    @Test
    void save_missingTopic_fallsBackToDefaultTopicDir() {
        LearnCard card = new LearnCard(LearnCard.TYPE_AI, "无主题卡", "web", null, null, null,
                LocalDate.of(2026, 9, 12), LearnCard.STATUS_NEW, false, null, List.of(), "观点",
                List.of("要点"), List.of(), "");
        repository.save("adai", card);

        assertTrue(storage.exists("adai", "learn/ai/" + LearnCard.DEFAULT_TOPIC + "/01-无主题卡.md"),
                "LLM 没给主题 → 归到默认主题，界面上看得见（不是散在 type 根目录）");
    }

    @Test
    void save_existingTopicReadme_appendsMarkerSectionWithoutRewriting() {
        storage.write("adai", "learn/ai/harness-engineering/README.md",
                "---\ntitle: Harness 资料包\ntype: ai\ncreated: 2026-09-06\n---\n\n# Harness 资料包\n\n手写目录，不许被重写。\n");
        repository.save("adai", new LearnCard(LearnCard.TYPE_AI, "Harness 新篇", "bilibili", "UP", null, null,
                LocalDate.of(2026, 9, 12), LearnCard.STATUS_NEW, false, null, List.of(), "观点",
                List.of("要点"), List.of(), "", "harness-engineering"));

        String readme = storage.read("adai", "learn/ai/harness-engineering/README.md");
        assertTrue(readme.contains("手写目录，不许被重写"), "他人 README 的既有内容一个字不动（追加式）");
        assertTrue(readme.contains("阿呆整理记录") && readme.contains("01-Harness 新篇.md"), "自动段追加索引");
        assertTrue(storage.exists("adai", "learn/ai/harness-engineering/01-Harness 新篇.md"),
                "A 已有主题目录里新建卡同样按 NN 编号");
    }

    @Test
    void list_reportsTopicFromPath_andReadOnlyForForeignCards() {
        storage.write("adai", "learn/ai/harness/01-video-card.md", A_FORM_CARD);
        repository.save("adai", new LearnCard(LearnCard.TYPE_AI, "我的卡", "web", null, null, null,
                LocalDate.of(2026, 9, 12), LearnCard.STATUS_NEW, false, null, List.of(), "观点",
                List.of("要点"), List.of(), "", "我的主题"));

        List<LearnCard> cards = repository.list("adai", LearnCard.TYPE_AI);
        LearnCard foreign = cards.stream().filter(c -> c.title().equals("Harness 到底是什么？")).findFirst().orElseThrow();
        LearnCard mine = cards.stream().filter(c -> c.title().equals("我的卡")).findFirst().orElseThrow();
        assertEquals("harness", foreign.topic(), "主题取自文件所在目录（路径是位置真相）");
        assertFalse(foreign.writable(), "别处整理的卡 → 只读");
        assertEquals("我的主题", mine.topic());
        assertTrue(mine.writable());
    }

    @Test
    void sameTitleAsForeignCard_isAllowed_andOwnCardWinsAddressing() {
        // P2-learn20：A 卡与产品卡标题完全相同时，原先新建被同名保护拒绝
        storage.write("adai", "learn/ai/harness/01-video-card.md", A_FORM_CARD);
        repository.save("adai", new LearnCard(LearnCard.TYPE_AI, "Harness 到底是什么？", "bilibili", "UP",
                "https://b23.tv/a", "2026-05-05", LocalDate.of(2026, 9, 12), LearnCard.STATUS_NEW, false,
                null, List.of(), "我的观点", List.of("要点"), List.of(), "", "harness"));

        LearnCard found = repository.find("adai", LearnCard.TYPE_AI, "Harness 到底是什么？").orElseThrow();
        assertTrue(found.writable(), "同名时本产品卡优先（操作落在自己的卡上）");
        assertEquals("我的观点", found.coreView());
        assertEquals(2, repository.list("adai", LearnCard.TYPE_AI).size(), "两张卡都在（别处的只读但看得见）");
    }

    @Test
    void duplicateOwnTitle_stillRejected() {
        LearnCard card = sample(LearnCard.TYPE_AI, "重复卡", LocalDate.of(2026, 9, 12));
        repository.save("adai", card);
        LearnException e = assertThrows(LearnException.class, () -> repository.save("adai", card));
        assertTrue(e.getMessage().contains("已有同名卡片"), e.getMessage());
    }

    @Test
    void promoteRaw_movesStagedAssetIntoTopicDir_andReadRawFallsBack() {
        repository.saveRaw("adai", "bilibili-BV1-meta.json", "{\"title\":\"x\"}");
        LearnCard card = new LearnCard(LearnCard.TYPE_AI, "留痕卡", "bilibili", "UP", null, null,
                LocalDate.of(2026, 9, 12), LearnCard.STATUS_NEW, false, null, List.of(), "观点",
                List.of("要点"), List.of(), "", "harness");
        repository.save("adai", card);

        List<String> promoted = repository.promoteRaw("adai", LearnCard.TYPE_AI, card.topic(),
                List.of("bilibili-BV1-meta.json", "从未产生的-稿.txt"));

        assertEquals(List.of("bilibili-BV1-meta.json"), promoted, "只搬真实存在的素材（未转写的不搬）");
        assertTrue(storage.exists("adai", "learn/ai/harness/_raw/bilibili-BV1-meta.json"),
                "素材归位到主题目录（与 Mac 侧技能同契约）");
        assertFalse(storage.exists("adai", "learn/_raw/bilibili-BV1-meta.json"), "暂存副本已清");
        assertEquals("{\"title\":\"x\"}", repository.readRaw("adai", "bilibili-BV1-meta.json"),
                "归位后仍能按名回读（转写稿幂等复用的前提）");
    }

    @Test
    void topics_listsExistingTopicDirsByType() {
        storage.write("adai", "learn/ai/harness/01-video-card.md", A_FORM_CARD);
        repository.save("adai", new LearnCard(LearnCard.TYPE_AI, "我的卡", "web", null, null, null,
                LocalDate.of(2026, 9, 12), LearnCard.STATUS_NEW, false, null, List.of(), "观点",
                List.of("要点"), List.of(), "", "我的主题"));

        List<String> topics = repository.topics("adai", LearnCard.TYPE_AI);
        assertTrue(topics.contains("harness") && topics.contains("我的主题"),
                "已有主题目录都报出来（供消化时提示归并）：" + topics);
        assertTrue(repository.topics("adai", LearnCard.TYPE_TRADING).isEmpty());
    }

    @Test
    void legacyFlatCard_stillReadableAndWritable_noForcedMigration() {
        LearnCard legacy = sample(LearnCard.TYPE_AI, "老卡", LocalDate.of(2026, 9, 1));
        storage.write("adai", "learn/ai/2026-09-01_" + LearnCard.fileStem("老卡") + ".md",
                LearnCardFileRepository.toMarkdown(legacy));

        Optional<LearnCard> found = repository.find("adai", LearnCard.TYPE_AI, "老卡");
        assertTrue(found.isPresent(), "老扁平布局照旧读得到（不强制迁移）");
        assertEquals(LearnCard.DEFAULT_TOPIC, found.get().topic());
        LearnCard edited = repository.applyEdit("adai", LearnCard.TYPE_AI, "老卡",
                new com.adaiadai.core.domain.learn.LearnCardPatch("老卡也能改", null, null, null, null, null, null));
        assertEquals("老卡也能改", edited.coreView(), "老卡原地可写（不是一刀切只读）");
        assertTrue(storage.exists("adai", "learn/ai/2026-09-01_" + LearnCard.fileStem("老卡") + ".md"),
                "写入没有偷偷搬家");
    }

    @Test
    void readCard_returnsRawMarkdown_includingSectionsProductDoesNotModel() {
        storage.write("adai", "learn/ai/harness/01-video-card.md", A_FORM_CARD);

        String md = repository.readCard("adai", LearnCard.TYPE_AI, "Harness 到底是什么？");

        assertTrue(md.contains("## 内容脉络"), "手工卡独有的段（产品没建模）也要按原文读得到");
        assertTrue(md.contains("第一条脉络"));
        assertEquals(null, repository.readCard("adai", LearnCard.TYPE_AI, "不存在的卡"));
    }

    @Test
    void readCard_afterSave_productCardAlsoReadable() {
        LearnCard card = sample(LearnCard.TYPE_AI, "全文卡", LocalDate.of(2026, 9, 12));
        repository.save("adai", card);

        String md = repository.readCard("adai", LearnCard.TYPE_AI, "全文卡");

        assertTrue(md.contains("全文卡") && md.contains("## 核心观点"), md);
    }

    @Test
    void cardPath_returnsRealTopicPath_usedForCrossDomainBacklink() {
        storage.write("adai", "learn/ai/harness/01-video-card.md", A_FORM_CARD);
        LearnCard card = sample(LearnCard.TYPE_AI, "我的卡", LocalDate.of(2026, 9, 12));
        repository.save("adai", card.withTopic("量价关系"));

        assertEquals("learn/ai/量价关系/01-我的卡.md",
                repository.cardPath("adai", LearnCard.TYPE_AI, "我的卡"));
        assertEquals("learn/ai/harness/01-video-card.md",
                repository.cardPath("adai", LearnCard.TYPE_AI, "Harness 到底是什么？"),
                "别处整理的卡也能给出真实回链路径（不再按日期拼假路径）");
        assertEquals(null, repository.cardPath("adai", LearnCard.TYPE_AI, "没有这张"));
    }

    @Test
    void list_skipsRawAssetsAndReadme_insideTopicDir() {
        storage.write("adai", "learn/ai/harness/README.md",
                "---\ntitle: 资料包\ntype: ai\ncreated: 2026-09-06\n---\n\n## 目录\n");
        storage.write("adai", "learn/ai/harness/01-video-card.md", A_FORM_CARD);
        // _raw/ 里的素材即便碰巧是 .md，也不是卡片
        storage.write("adai", "learn/ai/harness/_raw/notes.md",
                "---\ntitle: 素材片段\ntype: ai\ncreated: 2026-09-06\n---\n\n正文\n");

        List<LearnCard> cards = repository.list("adai", LearnCard.TYPE_AI);

        assertEquals(1, cards.size(), "只有真卡片进列表（README 与 _raw/ 素材都排除）：" + cards);
        assertEquals("Harness 到底是什么？", cards.get(0).title());
    }

    // ── 老卡一次性迁移（2026-09-12 完整升级批）──

    @Test
    void migrateLegacy_movesFlatCardIntoTopicDir_andIsIdempotent() {
        LearnCard legacy = sample(LearnCard.TYPE_AI, "老卡", LocalDate.of(2026, 9, 12));
        storage.write("adai", "learn/ai/2026-09-12_" + LearnCard.fileStem("老卡") + ".md",
                LearnCardFileRepository.toMarkdown(legacy));

        var items = repository.migrateLegacy("adai");

        assertEquals(1, items.size());
        assertEquals("learn/ai/" + LearnCard.DEFAULT_TOPIC + "/01-老卡.md", items.get(0).to(),
                "没标主题 → 归到默认主题目录");
        assertFalse(storage.exists("adai", "learn/ai/2026-09-12_老卡.md"), "老文件已删（不搬家）");
        String moved = storage.read("adai", "learn/ai/" + LearnCard.DEFAULT_TOPIC + "/01-老卡.md");
        assertTrue(moved.contains("origin: product") && moved.contains("topic: " + LearnCard.DEFAULT_TOPIC),
                "补写归属与主题键：" + moved.substring(0, Math.min(200, moved.length())));
        assertTrue(moved.contains("核心观点"), "正文一字不动（只是补键 + 挪位置）");
        assertTrue(repository.find("adai", LearnCard.TYPE_AI, "老卡").orElseThrow().writable(),
                "迁移后仍是本产品卡（可编辑）");
        assertTrue(repository.migrateLegacy("adai").isEmpty(), "幂等：再跑没有可迁的");
    }

    @Test
    void migrateLegacy_honorsFrontmatterTopic_andLeavesSkillCardsAlone() {
        storage.write("adai", "learn/ai/harness/01-video-card.md", A_FORM_CARD);   // Mac 侧技能卡
        LearnCard legacy = sample(LearnCard.TYPE_AI, "带主题的老卡", LocalDate.of(2026, 9, 1));
        storage.write("adai", "learn/ai/2026-09-01_" + LearnCard.fileStem("带主题的老卡") + ".md",
                LearnCardFileRepository.toMarkdown(legacy).replaceFirst("type: ai\n", "type: ai\ntopic: 量价关系\n"));

        var items = repository.migrateLegacy("adai");

        assertEquals(1, items.size());
        assertEquals("learn/ai/量价关系/01-带主题的老卡.md", items.get(0).to(), "frontmatter 里的主题被尊重");
        assertEquals(1, storage.listFiles("adai", "learn/ai/harness").size(),
                "技能卡一张没多没少（一动没动）：" + storage.listFiles("adai", "learn/ai/harness"));
        assertFalse(repository.find("adai", LearnCard.TYPE_AI, "Harness 到底是什么？").orElseThrow().writable(),
                "技能卡迁移后依旧只读");
    }

    @Test
    void migrateLegacy_nonCardFlatFile_leftAlone() {
        storage.write("adai", "learn/ai/2026-09-12_不是卡.md", "随手记的几行字，没有 frontmatter\n");

        assertTrue(repository.migrateLegacy("adai").isEmpty());
        assertTrue(storage.exists("adai", "learn/ai/2026-09-12_不是卡.md"), "认不出是卡就不动它");
    }

    // ── 对抗审查修复批（2026-09-12）：P1-A / P2-2 / P3 的回归 ──

    @Test
    void originMarkerInBody_doesNotMakeForeignCardWritable() {
        // P1-A：原先扫全文 → 外部卡正文/代码块里出现一行 origin: product 就被当产品卡被改写
        storage.write("adai", "learn/ai/mac主题/01-外部卡.md", """
                ---
                title: 外部卡
                type: ai
                created: 2026-09-10
                ---
                ## 核心观点

                讲一个契约示例：

                ```yaml
                origin: product
                ```
                """);

        LearnCard card = repository.find("adai", LearnCard.TYPE_AI, "外部卡").orElseThrow();

        assertFalse(card.writable(), "只有**前言块**里的 origin 才算数（正文里的同名字符串不算）");
        LearnException e = assertThrows(LearnException.class, () -> repository.applyEdit(
                "adai", LearnCard.TYPE_AI, "外部卡",
                new com.adaiadai.core.domain.learn.LearnCardPatch("产品注入", null, null, null, null, null, null)));
        assertTrue(e.getMessage().contains("不改动它") && e.getMessage().contains("另存"), e.getMessage());
        assertFalse(storage.read("adai", "learn/ai/mac主题/01-外部卡.md").contains("产品注入"),
                "外部文件一个字都不能动");
        assertFalse(storage.read("adai", "learn/ai/mac主题/01-外部卡.md").contains("## 关键要点"),
                "更不能把产品模板段注入别人的文件");
    }

    @Test
    void promoteRaw_binaryAsset_movesByteIdentical() {
        // 审查指出「风险最高的二进制通道零覆盖」→ 补真二进制（非法 UTF-8）回归
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x00, (byte) 0xFF, (byte) 0xFE, 0x01};
        repository.saveRawBytes("adai", "image-1-abc.png", png);

        List<String> promoted = repository.promoteRaw("adai", LearnCard.TYPE_AI, "量价关系",
                List.of("image-1-abc.png"));

        assertEquals(List.of("image-1-abc.png"), promoted);
        assertArrayEquals(png, storage.readBytes("adai", "learn/ai/量价关系/_raw/image-1-abc.png"),
                "字节完全一致（不经过文本通道）");
        assertArrayEquals(png, repository.readRawBytes("adai", "image-1-abc.png"), "归位后仍能按名回读");
        assertFalse(storage.exists("adai", "learn/_raw/image-1-abc.png"), "暂存副本已清");
    }

    @Test
    void promoteRaw_sameNameDifferentContent_keepsBoth() {
        // P2-2：原先目标同名直接覆盖（源必留痕被破）→ 现在并存 + 保留旧留痕
        repository.promoteRaw("adai", LearnCard.TYPE_AI, "量价关系",
                writeStaged("article-x-text.txt", "旧内容-不可丢"));
        repository.saveRaw("adai", "article-x-text.txt", "新内容-文章更新后重跑");

        List<String> promoted = repository.promoteRaw("adai", LearnCard.TYPE_AI, "量价关系",
                List.of("article-x-text.txt"));

        assertEquals("旧内容-不可丢", repository.readRaw("adai", "article-x-text.txt"),
                "旧留痕没被顶掉（仍能按原名读到）");
        assertEquals("新内容-文章更新后重跑", repository.readRaw("adai", promoted.get(0)),
                "新内容另存一份：promoted=" + promoted);
    }

    @Test
    void topicDir_controlChars_doNotBreakPath() {
        LearnCard card = new LearnCard(LearnCard.TYPE_AI, "控制符卡", "web", null, null, null,
                LocalDate.of(2026, 9, 12), LearnCard.STATUS_NEW, false, null, List.of(), "观点",
                List.of(), List.of(), "", "奇怪\u0000主题");

        repository.save("adai", card);   // 原先   会让路径解析抛 500

        assertTrue(repository.find("adai", LearnCard.TYPE_AI, "控制符卡").isPresent(),
                "控制符主题不炸路径：" + storage.listFiles("adai", "learn/ai"));
    }

    @Test
    void nextSeq_ignoresDatePrefixedFiles() {
        storage.write("adai", "learn/ai/量价关系/2026-09-12_手工命名.md",
                LearnCardFileRepository.toMarkdown(sample(LearnCard.TYPE_AI, "手工命名", LocalDate.of(2026, 9, 12))));
        repository.save("adai", new LearnCard(LearnCard.TYPE_AI, "新产品卡", "web", null, null, null,
                LocalDate.of(2026, 9, 12), LearnCard.STATUS_NEW, false, null, List.of(), "观点",
                List.of(), List.of(), "", "量价关系"));

        assertTrue(storage.exists("adai", "learn/ai/量价关系/01-新产品卡.md"),
                "日期前缀文件不算编号（原先会命名成 2027-新产品卡）：" + storage.listFiles("adai", "learn/ai/量价关系"));
    }

    /** 往暂存区写一份文本素材并返回名单（辅助）。 */
    private List<String> writeStaged(String name, String content) {
        repository.saveRaw("adai", name, content);
        return List.of(name);
    }

    @Test
    void writeOps_returnTopicAndWritable_soFrontendDoesNotLoseGrouping() {
        // web 侧自查 P1：PATCH 响应原先不带 topic/writable → 前端就地写回会把卡片跳到「未归类」组
        LearnCard card = new LearnCard(LearnCard.TYPE_AI, "分组卡", "web", null, null, null,
                LocalDate.of(2026, 9, 12), LearnCard.STATUS_NEW, false, null, List.of(), "观点",
                List.of("要点"), List.of(), "", "量价关系");
        repository.save("adai", card);

        LearnCard edited = repository.applyEdit("adai", LearnCard.TYPE_AI, "分组卡",
                new com.adaiadai.core.domain.learn.LearnCardPatch("改过的观点", null, null, null, null, null, null));
        LearnCard reviewed = repository.updateStatus("adai", LearnCard.TYPE_AI, "分组卡",
                LearnCard.STATUS_REVIEW, LocalDate.of(2026, 9, 12));

        assertEquals("量价关系", edited.topic(), "编辑响应带主题（前端不跳组）");
        assertTrue(edited.writable());
        assertEquals("量价关系", reviewed.topic(), "流转响应带主题");
        assertEquals(LearnCard.STATUS_REVIEW, reviewed.status());
    }

    // ── 缺口批（2026-09-13）：删卡（软删除）+ 改主题 ──

    @Test
    void deleteCard_movesToTrash_removesFromListAndReadme() {
        LearnCard card = new LearnCard(LearnCard.TYPE_AI, "要删的卡", "web", null, null, null,
                LocalDate.of(2026, 9, 13), LearnCard.STATUS_NEW, false, null, List.of(), "观点",
                List.of("要点"), List.of(), "", "量价关系");
        repository.save("adai", card);
        String before = repository.cardPath("adai", LearnCard.TYPE_AI, "要删的卡");
        String readmeBefore = storage.read("adai", "learn/ai/量价关系/README.md");
        assertTrue(readmeBefore.contains("要删的卡"), "删前索引里有它");

        String removedPath = repository.deleteCard("adai", LearnCard.TYPE_AI, "要删的卡");

        assertEquals(before, removedPath, "回执给出原始路径（供级联清理回链）");
        assertFalse(storage.exists("adai", before), "原位置已不在");
        assertTrue(repository.find("adai", LearnCard.TYPE_AI, "要删的卡").isEmpty(), "列表里也没有了");
        List<String> trash = storage.listFiles("adai", "learn/_trash");
        assertEquals(1, trash.size(), "软删除：内容进了 _trash（可人工捡回）：" + trash);
        String trashed = storage.read("adai", trash.get(0));
        assertTrue(trashed.contains("要删的卡") && trashed.contains("## 核心观点"), "内容一字不少");
        String readmeAfter = storage.read("adai", "learn/ai/量价关系/README.md");
        assertFalse(readmeAfter.contains("要删的卡"), "索引行已摘除：" + readmeAfter);
    }

    @Test
    void deleteCard_foreignCard_isRefused_fileUntouched() {
        storage.write("adai", "learn/ai/harness/01-video-card.md", A_FORM_CARD);

        LearnException e = assertThrows(LearnException.class,
                () -> repository.deleteCard("adai", LearnCard.TYPE_AI, "Harness 到底是什么？"));

        assertTrue(e.getMessage().contains("不改动它"), e.getMessage());
        assertTrue(storage.exists("adai", "learn/ai/harness/01-video-card.md"), "别处的原始文件不许删");
        assertEquals(0, storage.listFiles("adai", "learn/_trash").size(), "_trash 里也不该有它");
    }

    @Test
    void moveToTopic_movesFile_updatesFrontmatter_andBothReadmes() {
        LearnCard card = new LearnCard(LearnCard.TYPE_AI, "待归类的卡", "bilibili", "UP", null, null,
                LocalDate.of(2026, 9, 13), LearnCard.STATUS_NEW, false, null, List.of(), "观点",
                List.of("要点"), List.of(), "", "未归类");
        repository.save("adai", card);
        // 目标主题里先放一张（占 01），再把 README 手写内容叠上——同时验证续号与「不重写他人内容」
        repository.save("adai", new LearnCard(LearnCard.TYPE_AI, "早就有的卡", "web", null, null, null,
                LocalDate.of(2026, 9, 1), LearnCard.STATUS_NEW, false, null, List.of(), "观点",
                List.of(), List.of(), "", "量价关系"));
        String readmePath = "learn/ai/量价关系/README.md";
        storage.write("adai", readmePath, storage.read("adai", readmePath) + "\n手写内容\n");

        LearnCard moved = repository.moveToTopic("adai", LearnCard.TYPE_AI, "待归类的卡", "量价关系");

        assertEquals("量价关系", moved.topic());
        assertEquals("learn/ai/量价关系/02-待归类的卡.md", repository.cardPath("adai", LearnCard.TYPE_AI, "待归类的卡"),
                "挪进新主题并续号（老主题已有 01 时不能撞号）");
        assertFalse(storage.exists("adai", "learn/ai/" + LearnCard.DEFAULT_TOPIC + "/01-待归类的卡.md"), "老位置已清");
        String content = repository.readCard("adai", LearnCard.TYPE_AI, "待归类的卡");
        assertTrue(content.contains("topic: 量价关系"), "frontmatter 同步了新主题");
        String newReadme = storage.read("adai", "learn/ai/量价关系/README.md");
        assertTrue(newReadme.contains("手写内容"), "别人手写的 README 内容保留");
        assertTrue(newReadme.contains("待归类的卡"), "新主题索引里有它");
        String oldReadme = storage.read("adai", "learn/ai/" + LearnCard.DEFAULT_TOPIC + "/README.md");
        assertFalse(oldReadme.contains("待归类的卡"), "老主题索引已摘行");
    }

    @Test
    void moveToTopic_sameTopic_isNoop() {
        LearnCard card = new LearnCard(LearnCard.TYPE_AI, "原地卡", "web", null, null, null,
                LocalDate.of(2026, 9, 13), LearnCard.STATUS_NEW, false, null, List.of(), "观点",
                List.of(), List.of(), "", "量价关系");
        repository.save("adai", card);
        String path = repository.cardPath("adai", LearnCard.TYPE_AI, "原地卡");

        LearnCard same = repository.moveToTopic("adai", LearnCard.TYPE_AI, "原地卡", "量价关系");

        assertEquals("量价关系", same.topic());
        assertEquals(path, repository.cardPath("adai", LearnCard.TYPE_AI, "原地卡"), "同主题不动文件（幂等）");
    }

    @Test
    void moveToTopic_foreignCard_isRefused() {
        storage.write("adai", "learn/ai/harness/01-video-card.md", A_FORM_CARD);

        LearnException e = assertThrows(LearnException.class, () -> repository.moveToTopic(
                "adai", LearnCard.TYPE_AI, "Harness 到底是什么？", "harness-engineering"));

        assertTrue(e.getMessage().contains("不改动它"), e.getMessage());
        assertTrue(storage.exists("adai", "learn/ai/harness/01-video-card.md"), "别处的卡不许挪");
    }

    @Test
    void deleteCard_andMoveToTopic_missingCard_humanMessage() {
        LearnException e1 = assertThrows(LearnException.class,
                () -> repository.deleteCard("adai", LearnCard.TYPE_AI, "没有这张"));
        assertTrue(e1.getMessage().contains("卡片不存在"));
        LearnException e2 = assertThrows(LearnException.class,
                () -> repository.moveToTopic("adai", LearnCard.TYPE_AI, "没有这张", "x"));
        assertTrue(e2.getMessage().contains("卡片不存在"));
    }
}
