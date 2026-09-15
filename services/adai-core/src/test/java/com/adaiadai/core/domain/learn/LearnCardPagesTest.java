package com.adaiadai.core.domain.learn;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LearnCardPagesTest — 卡片页序列的解析与渲染（2026-09-15 卡片流批）。
 * <p>
 * 底线：**坏页段绝不能让卡片读不出来**（返回空列表即降级为旧形态），也绝不吞掉已有四段。
 */
class LearnCardPagesTest {

    @Test
    void noSectionMeansEmpty() {
        assertTrue(LearnCardPages.parse("## 核心观点\n一句话\n\n## 关键要点\n- a\n").isEmpty());
        assertTrue(LearnCardPages.parse("").isEmpty());
        assertTrue(LearnCardPages.parse(null).isEmpty());
    }

    @Test
    void parsesFencedSection() {
        String body = """
                ## 核心观点
                一句话

                ## 卡片页

                ```json
                [{"kind":"diagram","title":"三层递进","claim":"范围一圈圈变大",
                  "nodes":[{"text":"Prompt Engineering","note":"怎么问"},
                           {"text":"Harness Engineering","tone":"good"}]}]
                ```
                """;
        List<LearnPage> pages = LearnCardPages.parse(body);
        assertEquals(1, pages.size());
        LearnPage p = pages.get(0);
        assertEquals(LearnPage.KIND_DIAGRAM, p.kind());
        assertEquals("三层递进", p.title());
        assertEquals("范围一圈圈变大", p.claim());
        assertEquals(2, p.nodes().size());
        assertEquals("怎么问", p.nodes().get(0).note());
        assertEquals("neutral", p.nodes().get(0).tone());
        assertEquals("good", p.nodes().get(1).tone());
    }

    @Test
    void parsesBareSectionWithoutFence() {
        String body = "## 卡片页\n[{\"title\":\"封面\",\"claim\":\"一句话\"}]\n";
        List<LearnPage> pages = LearnCardPages.parse(body);
        assertEquals(1, pages.size());
        assertEquals(LearnPage.KIND_POINTS, pages.get(0).kind());
    }

    @Test
    void stopsAtNextSection() {
        // 页段后面还有别的段：不能被一起吞进来
        String body = """
                ## 卡片页
                [{"title":"A","claim":"a"}]

                ## 我的疑问
                - 这里不是 JSON，不该让解析炸掉
                """;
        List<LearnPage> pages = LearnCardPages.parse(body);
        assertEquals(1, pages.size());
        assertEquals("A", pages.get(0).title());
    }

    @Test
    void badJsonDegradesToEmpty() {
        assertTrue(LearnCardPages.parse("## 卡片页\n```json\n{不是数组}\n```\n").isEmpty());
        assertTrue(LearnCardPages.parse("## 卡片页\n```json\n[{ 截断了\n```\n").isEmpty());
    }

    @Test
    void unknownKindFallsBackToPoints() {
        assertEquals(LearnPage.KIND_POINTS, LearnPage.normalizeKind("不存在"));
        assertEquals(LearnPage.KIND_POINTS, LearnPage.normalizeKind(null));
        assertEquals(LearnPage.KIND_TABLE, LearnPage.normalizeKind(" TABLE "));
    }

    @Test
    void parsesEveryPayloadKind() {
        String body = """
                ## 卡片页
                ```json
                [
                 {"kind":"points","title":"p","bullets":["一","二"]},
                 {"kind":"table","title":"t","table":{"headers":["列1","列2"],"rows":[["a","b"],["c","d"]]}},
                 {"kind":"numbers","title":"n","numbers":[{"v":"3→7","l":"人"},{"v":"$200","l":"成本"}]},
                 {"kind":"compare","title":"c","left":{"title":"失败","tone":"bad","items":["x"]},
                  "right":{"title":"正确","tone":"good","items":["y"]}},
                 {"kind":"quote","title":"q","bullets":["Humans steer. Agents execute."]}
                ]
                ```
                """;
        List<LearnPage> pages = LearnCardPages.parse(body);
        assertEquals(5, pages.size());
        assertEquals(List.of("一", "二"), pages.get(0).bullets());
        assertEquals(2, pages.get(1).table().headers().size());
        assertEquals(2, pages.get(1).table().rows().size());
        assertEquals("3→7", pages.get(2).numbers().get(0).v());
        assertEquals("bad", pages.get(3).left().tone());
        assertEquals("正确", pages.get(3).right().title());
        assertEquals(1, pages.get(4).bullets().size());
    }

    @Test
    void dropsEmptyPages() {
        String body = "## 卡片页\n[{\"kind\":\"points\"},{\"title\":\"有内容\",\"claim\":\"c\"}]\n";
        List<LearnPage> pages = LearnCardPages.parse(body);
        assertEquals(1, pages.size());
        assertEquals("有内容", pages.get(0).title());
    }

    @Test
    void sectionHeaderToleratesPrefixAndSuffix() {
        assertEquals("页", LearnCardPages.normalizeHeader("二、页（自动）"));
        assertEquals("卡片页", LearnCardPages.normalizeHeader("3. 卡片页"));
        assertEquals("卡片页", LearnCardPages.normalizeHeader("卡片页(自动生成)"));
    }

    @Test
    void renderThenParseRoundTrip() {
        List<LearnPage> pages = List.of(
                new LearnPage(LearnPage.KIND_NUMBERS, "账单", "贵 20 倍", List.of(), null,
                        List.of(new LearnPage.NumberCell("6 小时", "$200")), null, null, List.of()),
                new LearnPage(LearnPage.KIND_COMPARE, "争议", "分两层看", List.of(), null, List.of(),
                        new LearnPage.Side("质疑", "bad", List.of("旧技术")),
                        new LearnPage.Side("反驳", "good", List.of("框架本身是产出")), List.of()));
        String section = LearnCardPages.renderSection(pages);
        assertTrue(section.startsWith("## 卡片页"));

        List<LearnPage> back = LearnCardPages.parse("## 核心观点\nx\n\n" + section);
        assertEquals(2, back.size());
        assertEquals("账单", back.get(0).title());
        assertEquals("$200", back.get(0).numbers().get(0).l());
        assertEquals("good", back.get(1).right().tone());
        assertEquals("框架本身是产出", back.get(1).right().items().get(0));
    }

    @Test
    void emptyPagesRenderNothing() {
        assertEquals("", LearnCardPages.renderSection(List.of()));
        assertEquals("", LearnCardPages.renderSection(null));
    }

    @Test
    void capsPageCount() {
        StringBuilder sb = new StringBuilder("## 卡片页\n[");
        for (int i = 0; i < LearnCardPages.MAX_PAGES + 10; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"title\":\"p").append(i).append("\"}");
        }
        sb.append("]");
        assertEquals(LearnCardPages.MAX_PAGES, LearnCardPages.parse(sb.toString()).size());
    }

    @Test
    void parseLenientHandlesArrayOutput() {
        // 回填路径：模型直出数组（可能带围栏、前后夹话、或只给一个对象）
        assertEquals(1, LearnCardPages.parseLenient("```json\n[{\"title\":\"甲\",\"claim\":\"a\"}]\n```").size());
        assertEquals(1, LearnCardPages.parseLenient("好的，我排好了：\n[{\"title\":\"乙\"}]\n希望有用").size());
        assertEquals(1, LearnCardPages.parseLenient("{\"title\":\"丙\"}").size(), "只给一个对象也认");
        assertTrue(LearnCardPages.parseLenient("完全不是 JSON").isEmpty());
        assertTrue(LearnCardPages.parseLenient(null).isEmpty());
    }

    @Test
    void pageEmptinessDetectsPayload() {
        LearnPage onlyTitle = new LearnPage(null, "标题", "", List.of(), null, List.of(), null, null, List.of());
        assertFalse(onlyTitle.isEmpty());
        assertFalse(onlyTitle.hasPayload());
        LearnPage bullets = new LearnPage(null, "", "", List.of("x"), null, List.of(), null, null, List.of());
        assertTrue(bullets.hasPayload());
    }
}
