package com.adaiadai.core.domain.learn;

import java.util.List;

/**
 * LearnPage — 学习卡片里的「一页」（2026-09-15 卡片流批）。
 * <p>
 * <b>为什么要有它</b>：产品原先一张卡只有「核心观点（一段话）+ 关键要点（若干 200 字长句）」，
 * 39KB 字幕被压成 6 条巨型 bullet = <b>段落墙</b>，用户「一个字都不想看」。
 * 页模型把同一批内容拆成「一页只讲一件事」的视觉单元：<b>一句结论 + 一张图或一张表</b>。
 * <p>
 * <b>kind 与载荷的对应</b>（载荷字段全部可空，前端按存在的字段渲染）：
 * <ul>
 *   <li>{@link #KIND_POINTS} — bullets</li>
 *   <li>{@link #KIND_TABLE} — table</li>
 *   <li>{@link #KIND_NUMBERS} — numbers</li>
 *   <li>{@link #KIND_COMPARE} — left + right（正反/失败vs正确）</li>
 *   <li>{@link #KIND_DIAGRAM} — nodes（竖排节点 + 连线，手机端用 HTML/CSS 画，不需要 mermaid）</li>
 *   <li>{@link #KIND_QUOTE} — bullets（当作引语行渲染）</li>
 * </ul>
 * <p>
 * <b>File First</b>：页序列不是数据库记录，它落在卡片 md 的 {@code ## 卡片页} 段里（见
 * {@link LearnCardPages}）；没有该段的旧卡/别处整理的卡 → 空列表 → 渲染端降级为原有形态。
 */
public record LearnPage(
        String kind,
        String title,
        String claim,
        List<String> bullets,
        Table table,
        List<NumberCell> numbers,
        Side left,
        Side right,
        List<Node> nodes) {

    public static final String KIND_POINTS = "points";
    public static final String KIND_TABLE = "table";
    public static final String KIND_NUMBERS = "numbers";
    public static final String KIND_COMPARE = "compare";
    public static final String KIND_DIAGRAM = "diagram";
    public static final String KIND_QUOTE = "quote";

    /** 表格载荷：headers 与 rows 的列数不一定相等，渲染端自行兜底。 */
    public record Table(List<String> headers, List<List<String>> rows) {}

    /** 数字卡载荷：v = 大号数字/词，l = 下方说明。 */
    public record NumberCell(String v, String l) {}

    /** 对照栏载荷：tone = good|bad|neutral（只影响配色）。 */
    public record Side(String title, String tone, List<String> items) {}

    /** 竖排图节点：text = 主文案，note = 小字补充，tone = good|bad|neutral|info。 */
    public record Node(String text, String note, String tone) {}

    public LearnPage {
        kind = normalizeKind(kind);
        title = title == null ? "" : title.strip();
        claim = claim == null ? "" : claim.strip();
        bullets = bullets == null ? List.of() : List.copyOf(bullets);
        numbers = numbers == null ? List.of() : List.copyOf(numbers);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    /** 非法/缺失的 kind 归 points（不丢页——丢了用户就少一块内容）。 */
    public static String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) return KIND_POINTS;
        String k = kind.strip().toLowerCase();
        return switch (k) {
            case KIND_TABLE, KIND_NUMBERS, KIND_COMPARE, KIND_DIAGRAM, KIND_QUOTE -> k;
            default -> KIND_POINTS;
        };
    }

    /** 这一页有没有实际内容（空页不入库、不渲染）。 */
    public boolean isEmpty() {
        return title.isBlank() && claim.isBlank()
                && bullets.isEmpty() && numbers.isEmpty() && nodes.isEmpty()
                && table == null && left == null && right == null;
    }

    /** 是否含结构化载荷（只有标题+结论的页也算有效页——封面/过渡页就是这种）。 */
    public boolean hasPayload() {
        return !bullets.isEmpty() || !numbers.isEmpty() || !nodes.isEmpty()
                || table != null || left != null || right != null;
    }
}
