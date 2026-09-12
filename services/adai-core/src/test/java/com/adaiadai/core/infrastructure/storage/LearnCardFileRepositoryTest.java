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
        String path = "learn/ai/2026-09-12_" + LearnCard.fileStem("改名卡") + ".md";
        storage.write("adai", path, storage.read("adai", path).replace("## 核心观点", "## 核心观点（一句话）"));

        LearnCard updated = repository.applyEdit("adai", LearnCard.TYPE_AI, "改名卡",
                new com.adaiadai.core.domain.learn.LearnCardPatch("新观点", null, null, null, null, null, null));

        assertEquals("新观点", updated.coreView());
        String content = storage.read("adai", path);
        assertEquals(1, content.split("## 核心观点", -1).length - 1, "不该出现重复的核心观点段");
    }
}
