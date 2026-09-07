package com.adaiadai.core.domain.learn;

/**
 * LearnCard — 学习卡片（RFC 20260829 learn 插件）。
 * <p>
 * File First：每张卡片 = {@code data/{userId}/learn/{type}/{yyyy-MM-dd}_{title}.md}
 * （frontmatter 元数据 + RFC 3.4 正文四段）。多用户按 userId 分目录（对齐 trading）。
 * <p>
 * type：ai（技术）| trading（交易）| other（科普/人文）——卡片分类，非插件 domain 收敛对象
 * （learn 不进 life/trading/project 收敛，2026-09-06 拍板）。trade_related 仅 type=trading
 * 内容有意义（是否含可执行交易规则/信号）；V1 只记录不联动规则库。
 *
 * @param type         卡片分类（ai/trading/other）
 * @param title        标题（LLM 从素材提炼，同日同标题幂等键）
 * @param platform     来源平台（bilibili/youtube/web…，可空）
 * @param author       来源作者/UP 主（可空）
 * @param url          原文链接（可空）
 * @param published    原文发布日期 yyyy-MM-dd（可空）
 * @param created      消化日期（服务器日期）
 * @param status       复习状态（new/review/done，V2 复习流转）
 * @param tradeRelated 是否涉及可执行交易规则（仅 type=trading 有意义）
 * @param tradeNote    交易备注：与 R 规则的关系（V1 仅记录，可空）
 * @param tags         标签列表
 * @param coreView     核心观点（一句话）
 * @param keyPoints    关键要点列表（可带时间戳，如 "02:31 要点"）
 * @param questions    我的疑问列表（存疑点，可触发后续讨论）
 * @param retell       复述段（24h 内自己写 100-200 字——消化关键；AI 不代写，
 *                     V2 编辑/对话流让阿呆改 后可填充）
 */
public record LearnCard(
        String type,
        String title,
        String platform,
        String author,
        String url,
        String published,
        java.time.LocalDate created,
        String status,
        boolean tradeRelated,
        String tradeNote,
        java.util.List<String> tags,
        String coreView,
        java.util.List<String> keyPoints,
        java.util.List<String> questions,
        String retell) {

    public static final String TYPE_AI = "ai";
    public static final String TYPE_TRADING = "trading";
    public static final String TYPE_OTHER = "other";
    public static final String STATUS_NEW = "new";
    public static final String STATUS_REVIEW = "review";
    public static final String STATUS_DONE = "done";

    public LearnCard {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("卡片标题不能为空");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("卡片类型不能为空");
        }
        if (created == null) {
            throw new IllegalArgumentException("消化日期不能为空");
        }
        tags = tags == null ? java.util.List.of() : tags;
        keyPoints = keyPoints == null ? java.util.List.of() : keyPoints;
        questions = questions == null ? java.util.List.of() : questions;
        status = status == null ? STATUS_NEW : status;
        retell = retell == null ? "" : retell.strip();
        // 语义收敛：非交易内容不标 trade_related（防 LLM 幻觉把理念类标成规则）
        if (!TYPE_TRADING.equals(type)) {
            tradeRelated = false;
            tradeNote = null;
        }
    }

    /** 合法类型。 */
    public static boolean isValidType(String type) {
        return TYPE_AI.equals(type) || TYPE_TRADING.equals(type) || TYPE_OTHER.equals(type);
    }

    /** 合法复习状态（new → review → done）。 */
    public static boolean isValidStatus(String status) {
        return STATUS_NEW.equals(status) || STATUS_REVIEW.equals(status) || STATUS_DONE.equals(status);
    }
}
