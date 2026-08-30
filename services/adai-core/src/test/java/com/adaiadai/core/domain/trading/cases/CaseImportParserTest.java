package com.adaiadai.core.domain.trading.cases;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * CaseImportParserTest — 完美案例笔记解析（2026-08-31 批量导入）。
 * <p>
 * 覆盖用户实际格式（B1/B2 飞书笔记采样）：名称【缩写】/名称[缩写_日期]/名称、
 * 正文日期、飞书转义 `\-`、8 位日期、区间取首、类型分组跳过、缺日期 null。
 */
class CaseImportParserTest {

    @Test
    void parsesB2StyleTitleDates() {
        String text = """
                ## 昂立康[ALK_20250714]
                ![Image](xxx)
                ## 中大力德[ZDLD_20250804]
                """;
        List<CaseImportParser.ImportItem> items = CaseImportParser.parse(text);
        assertEquals(2, items.size());
        assertEquals("昂立康", items.get(0).name());
        assertEquals(LocalDate.of(2025, 7, 14), items.get(0).buyDate());
        assertEquals("中大力德", items.get(1).name());
        assertEquals(LocalDate.of(2025, 8, 4), items.get(1).buyDate());
    }

    @Test
    void parsesB1StyleBodyDatesWithEscapedDash() {
        String text = """
                ## 华纳药厂【HNYC】
                - 两个买点 B1：2025\\-05\\-09 SB1 2025\\-05\\-12
                ## 微芯生物【WXSW】
                - 2025\\-06\\-20
                """;
        List<CaseImportParser.ImportItem> items = CaseImportParser.parse(text);
        assertEquals(2, items.size());
        assertEquals("华纳药厂", items.get(0).name());
        assertEquals(LocalDate.of(2025, 5, 9), items.get(0).buyDate(), "取第一个买点（B1 日）");
        assertEquals(LocalDate.of(2025, 6, 20), items.get(1).buyDate());
    }

    @Test
    void rangeDate_takesFirst() {
        String text = "## 宁波韵升【NBYS】\n- 2025\\-08\\-04~2025\\-08\\-06\n- 三天都可买";
        List<CaseImportParser.ImportItem> items = CaseImportParser.parse(text);
        assertEquals(LocalDate.of(2025, 8, 4), items.get(0).buyDate(), "区间取首个日期");
    }

    @Test
    void groupTitles_skipped() {
        String text = """
                ## 完美类型一：
                ## 华纳药厂【HNYC】
                - 2025\\-05\\-09
                ## 类型：B2 常规战法
                ## 昂立康[ALK_20250714]
                """;
        List<CaseImportParser.ImportItem> items = CaseImportParser.parse(text);
        assertEquals(2, items.size(), "类型分组标题应被跳过");
        assertEquals("华纳药厂", items.get(0).name());
        assertEquals("昂立康", items.get(1).name());
    }

    @Test
    void missingDate_returnsNull() {
        String text = "## 航天发展\n![Image](xxx)\n## 方正科技【FZKJ]\n- B1:\n";
        List<CaseImportParser.ImportItem> items = CaseImportParser.parse(text);
        assertEquals(2, items.size());
        assertNull(items.get(0).buyDate(), "无日期 → null（导入时报告跳过）");
        assertNull(items.get(1).buyDate());
    }

    @Test
    void titleWithEscapedBrackets_stripsTrailingSlash() {
        String text = "## 昂立康\\[ALK\\_20250714\\]\n![Image](x)";
        List<CaseImportParser.ImportItem> items = CaseImportParser.parse(text);
        assertEquals(1, items.size());
        assertEquals("昂立康", items.get(0).name(), "尾部转义反斜杠应去除");
        assertEquals(LocalDate.of(2025, 7, 14), items.get(0).buyDate());
    }

    @Test
    void blank_returnsEmpty() {
        assertEquals(0, CaseImportParser.parse(null).size());
        assertEquals(0, CaseImportParser.parse("  ").size());
    }
}
