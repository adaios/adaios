package com.adaiadai.core.infrastructure.storage;

import com.adaiadai.core.domain.learn.LearnCard;
import com.adaiadai.core.domain.learn.LearnCardPages;
import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.domain.learn.LearnPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LearnCardPagesStorageTest — 页段落盘与兼容（2026-09-15 卡片流批）。
 * <p>
 * 三条底线：① 有页 → md 多一段，四段原样在；② 无页 → md 与老模板逐字节同形（旧卡零差异）；
 * ③ 改状态/编辑正文都**不能毁掉页段**（页是呈现层，不是可被编辑手术重写的受管段）。
 */
class LearnCardPagesStorageTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private LearnCardFileRepository repository;

    @BeforeEach
    void setUp() {
        repository = new LearnCardFileRepository(storage);
    }

    private LearnCard card(String title) {
        return new LearnCard("ai", title, "bilibili", "马克的技术工作坊", "https://b23.tv/x", "2026-05-05",
                LocalDate.of(2026, 9, 15), LearnCard.STATUS_NEW, false, null,
                List.of("harness"), "一句话核心观点",
                List.of("要点一", "要点二"), List.of("疑问一"), "");
    }

    private List<LearnPage> pages() {
        return List.of(
                new LearnPage(LearnPage.KIND_DIAGRAM, "三层递进", "范围一圈圈变大", List.of(), null,
                        List.of(), null, null,
                        List.of(new LearnPage.Node("Harness Engineering", "整个系统怎么搭", "good"))),
                new LearnPage(LearnPage.KIND_NUMBERS, "账单", "贵 20 倍", List.of(), null,
                        List.of(new LearnPage.NumberCell("6 小时", "$200")), null, null, List.of()));
    }

    private String readBack(String title) {
        List<String> files = storage.listFiles("u1", "learn/ai");
        for (String f : files) {
            String md = storage.read("u1", f);
            if (md != null && md.contains("title: " + title)) return md;
        }
        throw new AssertionError("没找到卡片：" + title);
    }

    @Test
    void saveWithPagesWritesSectionAndKeepsLegacySections() {
        repository.save("u1", card("带页卡"), pages());
        String md = readBack("带页卡");
        assertTrue(md.contains("## 卡片页"), "应有页段");
        assertTrue(md.contains("\"Harness Engineering\""), "页载荷应落盘");
        assertTrue(md.contains("## 核心观点") && md.contains("## 关键要点")
                && md.contains("## 我的疑问") && md.contains("## 复述"), "原有四段必须原样在");
        assertTrue(md.contains("一句话核心观点"), "核心观点内容不能丢");
        assertTrue(md.contains("要点一"), "要点内容不能丢");
    }

    @Test
    void legacySaveHasNoPagesSection() {
        repository.save("u1", card("老式卡"));
        String md = readBack("老式卡");
        assertFalse(md.contains("## 卡片页"), "无页时不该多出空段（与老模板逐字节同形）");
    }

    @Test
    void pagesSectionSurvivesStatusChange() {
        repository.save("u1", card("状态卡"), pages());
        repository.updateStatus("u1", "ai", "状态卡", LearnCard.STATUS_REVIEW, LocalDate.of(2026, 9, 16));
        String md = readBack("状态卡");
        assertTrue(md.contains("## 卡片页"), "改复习状态不该丢页");
        assertEquals(2, LearnCardPages.parse(md.substring(md.indexOf("## 卡片页"))).size());
    }

    @Test
    void pagesSectionSurvivesBodyRewrite() {
        repository.save("u1", card("编辑卡"), pages());
        String md = readBack("编辑卡");
        String body = md.substring(md.indexOf("## 核心观点"));
        String rewritten = LearnCardFileRepository.rewriteBodySections(body, card("编辑卡"));
        assertTrue(rewritten.contains("## 卡片页"), "编辑手术必须把页段当未知段原样保留");
        assertTrue(rewritten.contains("Harness Engineering"), "页内容不能被编辑手术抹掉");
    }

    @Test
    void pagesRoundTripThroughStorage() {
        repository.save("u1", card("往返卡"), pages());
        String md = readBack("往返卡");
        String body = md.substring(md.indexOf("## 核心观点"));
        List<LearnPage> back = LearnCardPages.parse(body);
        assertEquals(2, back.size());
        assertEquals("三层递进", back.get(0).title());
        assertEquals("$200", back.get(1).numbers().get(0).l());
    }

    // ── 历史卡回填（updatePages：只补页段，别的都不许动）──

    @Test
    void updatePagesAppendsSectionForLegacyCard() {
        repository.save("u1", card("老卡回填"));          // 先落一张没有页的老卡
        String before = readBack("老卡回填");
        assertFalse(before.contains("## 卡片页"));

        repository.updatePages("u1", "ai", "老卡回填", pages());

        String after = readBack("老卡回填");
        assertTrue(after.contains("## 卡片页"));
        assertEquals(2, LearnCardPages.parse(after.substring(after.indexOf("## 卡片页"))).size());
        // 原有内容一字不动
        assertTrue(after.contains("一句话核心观点"));
        assertTrue(after.contains("要点一"));
        assertTrue(after.contains("疑问一"));
    }

    @Test
    void updatePagesReplacesOldSectionInsteadOfDuplicating() {
        repository.save("u1", card("替换页"), pages());
        repository.updatePages("u1", "ai", "替换页",
                List.of(new LearnPage(LearnPage.KIND_QUOTE, "新页", "只剩一页", List.of("原话"), null,
                        List.of(), null, null, List.of())));
        String after = readBack("替换页");
        assertEquals(1, after.split("## 卡片页", -1).length - 1, "页段只能有一份");
        assertFalse(after.contains("三层递进"), "旧页内容应被替换掉");
        assertTrue(after.contains("只剩一页"));
    }

    @Test
    void updatePagesKeepsHandWrittenSections() {
        repository.save("u1", card("手工段卡"));
        // 手工追加一段（模拟用户在别处写的内容）
        String md = readBack("手工段卡");
        storage.write("u1", pathOf("手工段卡"), md + "\n## 我的补充\n这段是手工写的。\n");
        repository.updatePages("u1", "ai", "手工段卡", pages());
        String after = readBack("手工段卡");
        assertTrue(after.contains("## 我的补充") && after.contains("这段是手工写的。"),
                "回填不得动手工段");
        assertTrue(after.contains("## 卡片页"));
    }

    @Test
    void updatePagesRejectsReadOnlyCard() {
        // 别处整理的卡（没有 origin: product）→ 只读，回填也要被人话拒绝
        String path = "learn/ai/手工主题/01-别处的卡.md";
        storage.write("u1", path, """
                ---
                title: 别处的卡
                type: ai
                created: 2026-09-01
                ---

                ## 核心观点（一句话）
                别人整理的。
                """);
        LearnException ex = assertThrows(LearnException.class,
                () -> repository.updatePages("u1", "ai", "别处的卡", pages()));
        assertTrue(ex.getMessage().contains("只当资料看"), "应给人话而不是技术异常：" + ex.getMessage());
    }

    @Test
    void rawAssetsListsTopicMaterial() {
        storage.write("u1", "learn/ai/harness/_raw/bilibili-BV1x-transcript.txt", "转写稿");
        storage.write("u1", "learn/ai/harness/_raw/bilibili-BV1x-meta.json", "{}");
        List<String> assets = repository.rawAssets("u1", "ai", "harness");
        assertTrue(assets.contains("bilibili-BV1x-transcript.txt"));
        assertTrue(assets.contains("bilibili-BV1x-meta.json"));
        assertEquals(2, assets.size());
        assertTrue(repository.rawAssets("u1", "ai", "不存在的主题").isEmpty());
    }

    private String pathOf(String title) {
        for (String f : storage.listFiles("u1", "learn/ai")) {
            String md = storage.read("u1", f);
            if (md != null && md.contains("title: " + title)) return f;
        }
        throw new AssertionError("没找到卡片：" + title);
    }
}
