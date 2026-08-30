package com.adaiadai.core.domain.trading.cases;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CaseImportParser — 完美案例笔记解析（2026-08-31：批量导入）。
 * <p>
 * 解析飞书/笔记导出的 markdown（用户实际格式，2026-08-31 采样 B1/B2 两文件验证）：
 * <pre>
 * ## 股票名【拼音缩写】          → 名称 + 正文行找日期
 * ## 股票名[缩写_20250714]       → 名称 + 标题内嵌 8 位日期
 * ## 股票名[缩写_2025-07-14]
 * ## 股票名                        → 无日期（跳过导入，报告缺日期）
 * - 两个买点 B1：2025\-05\-09      → 正文日期（飞书转义 `\-` 兼容）
 * ## 完美类型一：/ ## 类型：B2     → 类型分组标题（跳过）
 * </pre>
 * 每只股票取**第一个**日期（多买点如「B1：2025-05-09 SB1 2025-05-12」取首个；区间
 * 「2025-08-04~2025-08-06」取首个）。纯解析无 IO，可单测。
 */
public final class CaseImportParser {

    /** 日期：8 位数字 或 yyyy-MM-dd / yyyy.MM.dd / 飞书转义 yyyy\-MM\-dd（1-2 个分隔符）。 */
    private static final Pattern DATE = Pattern.compile(
            "(\\d{8}|\\d{4}[\\\\\\-. ]{1,2}\\d{2}[\\\\\\-. ]{1,2}\\d{2})");
    private static final Pattern TITLE = Pattern.compile("^##\\s+(.+)$");
    /** 类型分组标题（完美类型X：/ 类型X：/ 补充 等非股票标题）。 */
    private static final Pattern GROUP = Pattern.compile("^(完美类型|类型|补充)");
    /** 行内买点类型标记（B1：2025-05-09 / SB1 2025-05-12 / B2 等）。 */
    private static final Pattern TYPE = Pattern.compile("\\b(B1|SB1|B2)\\b");
    private static final Pattern BRACKET = Pattern.compile("[【\\[]([^】\\]]+)[】\\]]");

    private CaseImportParser() {}

    /** 解析结果项（日期缺失 → buyDate null，导入时报告跳过；买点类型：行内 B1/SB1/B2 标记，无 → null）。 */
    public record ImportItem(String name, LocalDate buyDate, String buyType) {}

    public static List<ImportItem> parse(String text) {
        List<ImportItem> items = new ArrayList<>();
        if (text == null || text.isBlank()) return items;
        String currentName = null;
        LocalDate currentDate = null;
        String currentType = null;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            Matcher tm = TITLE.matcher(line);
            if (tm.matches()) {
                // 收尾上一只
                if (currentName != null) {
                    items.add(new ImportItem(currentName, currentDate, currentType));
                }
                String title = tm.group(1).strip();
                if (GROUP.matcher(title).find()) {
                    currentName = null;
                    currentDate = null;
                    currentType = null;
                    continue;
                }
                String name = BRACKET.matcher(title).replaceAll("").strip();
                name = name.replaceAll("\\\\+$", "").strip(); // 尾部转义反斜杠残留
                name = name.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9]+$", ""); // 尾部符号残留（国义招标\：）
                if (name.isEmpty()) {
                    currentName = null;
                    currentDate = null;
                    currentType = null;
                    continue;
                }
                currentName = name;
                currentDate = null;
                currentType = null;
                Matcher bm = BRACKET.matcher(title);
                if (bm.find()) {
                    Matcher dm = DATE.matcher(bm.group(1));
                    if (dm.find()) currentDate = normalize(dm.group(1));
                }
            } else if (currentName != null && line.startsWith("-")) {
                // 2026-08-31：行内买点类型标记（B1：2025-05-09 / SB1 2025-05-12）
                Matcher tm2 = TYPE.matcher(line);
                if (tm2.find() && currentType == null) {
                    currentType = tm2.group(1);
                }
                if (currentDate == null) {
                    Matcher dm = DATE.matcher(line);
                    if (dm.find()) currentDate = normalize(dm.group(1));
                }
            }
        }
        if (currentName != null) {
            items.add(new ImportItem(currentName, currentDate, currentType));
        }
        return items;
    }

    /** 日期规范化：去反斜杠/空格、`.`→`-`、8 位数字 → yyyy-MM-dd。 */
    static LocalDate normalize(String raw) {
        String d = raw.replace("\\", "").replace(" ", "").replace(".", "-");
        if (d.length() == 8) {
            d = d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6);
        }
        try {
            return LocalDate.parse(d, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            return null;
        }
    }
}
