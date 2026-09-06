package com.adaiadai.core.kernel.knowledge;

import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LearnKnowledgeSourceTest — learn 笔记知识源（RFC 20260829 L2 问答召回）。
 * <p>
 * 覆盖：注入最近笔记（标题+type+核心观点）、created 倒序、无卡片不注入、
 * 损坏/非 learn 文件跳过、_raw 素材不注入、上限 5 篇、enrich 不双份。
 */
class LearnKnowledgeSourceTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private LearnKnowledgeSource source;

    @BeforeEach
    void setUp() {
        source = new LearnKnowledgeSource(storage);
    }

    private String cardMd(String title, String type, String created, String coreView) {
        return """
                ---
                title: %s
                type: %s
                platform: bilibili
                author: 某UP
                url: https://b23.tv/x
                published: 2026-05-05
                created: %s
                status: new
                trade_related: false
                trade_note: ""
                tags: [rag]
                ---
                ## 核心观点
                %s

                ## 关键要点
                - 要点一
                """.formatted(title, type, created, coreView);
    }

    @Test
    void globalContext_injectsRecentNotes_withTitleTypeCoreView() {
        storage.write("adai", "learn/ai/2026-09-06_RAG与Agent.md",
                cardMd("RAG 与 Agent", "ai", "2026-09-06", "RAG 是检索增强，Agent 是自主行动"));
        String ctx = source.globalContext("adai");

        assertTrue(ctx.contains("## 你最近的学习笔记"));
        assertTrue(ctx.contains("RAG 与 Agent"));
        assertTrue(ctx.contains("（ai）"));
        assertTrue(ctx.contains("RAG 是检索增强，Agent 是自主行动"));
    }

    @Test
    void globalContext_noNotes_returnsEmpty() {
        assertEquals("", source.globalContext("adai"), "无卡片 → 不注入");
        assertEquals("", source.globalContext("bob"));
    }

    @Test
    void globalContext_onlyOwnUserNotes_injected() {
        storage.write("adai", "learn/ai/2026-09-06_我的卡.md",
                cardMd("我的卡", "ai", "2026-09-06", "观点"));
        assertTrue(source.globalContext("adai").contains("我的卡"));
        assertEquals("", source.globalContext("bob"), "bob 无卡片 → 空");
    }

    @Test
    void recentNotes_sortedByCreatedDesc_andCappedAt5() {
        for (int i = 1; i <= 7; i++) {
            String d = "2026-09-%02d".formatted(i);
            storage.write("adai", "learn/ai/" + d + "_第" + i + "篇.md",
                    cardMd("第" + i + "篇", "ai", d, "观点" + i));
        }
        String ctx = source.globalContext("adai");
        // created 倒序：最近的是 09-07 第 7 篇 → 注入上限 5 → 7/6/5/4/3
        assertTrue(ctx.contains("第7篇"), "最新 09-07 应注入");
        assertFalse(ctx.contains("第2篇"), "超过上限 5 的旧卡（09-02 第 2 篇）不注入");
        int first = ctx.indexOf("第7篇"), second = ctx.indexOf("第6篇");
        assertTrue(first >= 0 && second > first, "created 倒序：第 7 篇在第 6 篇之前");
    }

    @Test
    void corruptedAndNonLearnFiles_skipped() {
        storage.write("adai", "learn/ai/2026-09-06_好卡.md",
                cardMd("好卡", "ai", "2026-09-06", "好观点"));
        storage.write("adai", "learn/ai/2026-09-06_坏卡.md", "无 frontmatter 的坏文件");
        storage.write("adai", "learn/README.md", "目录说明不算卡片");
        storage.write("adai", "learn/_raw/raw.txt", "原始素材不注入");
        String ctx = source.globalContext("adai");
        assertTrue(ctx.contains("好卡"));
        assertFalse(ctx.contains("坏卡"), "损坏文件跳过");
        assertFalse(ctx.contains("目录说明"), "README 跳过");
        assertFalse(ctx.contains("raw"), "learn/_raw 素材不注入");
    }

    @Test
    void enrich_returnsEmpty_noDuplicateInjection() {
        storage.write("adai", "learn/ai/2026-09-06_RAG.md",
                cardMd("RAG", "ai", "2026-09-06", "观点"));
        assertEquals("", source.enrich("adai", "life"),
                "enrich 返回空——globalContext 已注入，loadKnowledgeContext 会去重防双份");
        assertEquals("", source.enrich("adai", "question"));
        assertEquals("", source.enrich("adai", "trading"));
    }
}
