package com.adaiadai.core.kernel.record;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ImageQaFormatter — image_qa 记录解析与展示自然化（第一原则：**永远不要让人觉得有第三视角**）。
 * <p>
 * 数据层 content 是结构化契约（freeze §2.1）：{@code 【多图问答】\n图片记录：{ids}\n问：{q}\n答：{a}}——
 * 给系统解析/搜索用；但 UI 若直接渲染，用户看到的是「问：/答：/图片记录：」标签，
 * 像有第三视角在转述，而不是「我和阿呆的自然对话」。
 * <p>
 * 展示转换：标题 = 用户问句（自然语言），正文 = 问/答两行（去标签）。图片引用由缩略图表达，
 * 不再出现文字。
 * <p>
 * 本类同时是 image_qa content 的**解析单一事实源**：图片引用 id 提取（{@link #imageRecordIds}）
 * 与对话 turns 解析（{@link #parseTurns}）——Feed 聚合（缩略图/对话历史）与
 * MediaRecordAppService（S-2 聚合卡身份解析）共用同一契约，避免各层重复实现 IMAGE_REF。
 */
public final class ImageQaFormatter {

    /** image_qa content 中的图片引用（freeze §2.1：`图片记录：{id1}, {id2}`，逗号+空格分隔）。 */
    private static final Pattern IMAGE_REF = Pattern.compile("图片记录[：:]([^\\n]+)");

    private ImageQaFormatter() {
    }

    /**
     * 解析 image_qa content 引用的图片记录 id 列表（freeze §2.1：{@code 图片记录：{id1}, {id2}}）。
     * <p>
     * 非 image_qa 格式 / 无引用 → 空列表（调用方据此回退或跳过）。
     *
     * @param content image_qa 记录 content
     * @return 引用的图片记录 id（保序去空），无引用返回空列表
     */
    public static List<String> imageRecordIds(String content) {
        if (content == null) return List.of();
        Matcher m = IMAGE_REF.matcher(content);
        if (!m.find()) return List.of();
        return Arrays.stream(m.group(1).split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * 将 image_qa 的结构化 content 解析为对话 turns（用户问句 + 阿呆回答），
     * 供 Feed 聚合条目附带对话历史（前端进对话态显示 Q/A 气泡）。
     * <p>
     * 与 {@link #naturalize} 同源解析：问句/回答取自「问：/答：」字段，时间由调用方统一传入
     * （image_qa 记录 createdAt 的 {@code HH:mm}，问/答同刻发生）。
     *
     * @param content image_qa 记录 content
     * @param time    turns 展示时间（{@code HH:mm}）
     * @return [{@code Turn(isUser=true, 问句)}, {@code Turn(isUser=false, 回答)}]；
     *         无法解析（找不到「问：」/问句为空）返回 {@code null}（调用方保持无 turns）
     */
    public static List<CardRecord.Turn> parseTurns(String content, String time) {
        if (content == null) return null;
        int qIdx = content.indexOf("问：");
        int aIdx = content.indexOf("答：");
        if (qIdx < 0) return null;
        String question = (aIdx > qIdx ? content.substring(qIdx + 2, aIdx) : content.substring(qIdx + 2)).strip();
        if (question.isEmpty()) return null;
        String answer = aIdx >= 0 ? content.substring(aIdx + 2).strip() : "";
        if (answer.isEmpty()) {
            return List.of(new CardRecord.Turn(true, question, time));
        }
        return List.of(
                new CardRecord.Turn(true, question, time),
                new CardRecord.Turn(false, answer, time)
        );
    }

    /**
     * 将 image_qa 的结构化 content 转为自然对话形态。
     *
     * @return {@code [title, content]}——title=用户问句，content=问+答两行（无标签）；
     *         非 image_qa 格式（找不到「问：」）返回 {@code null}（调用方保持原样）。
     */
    public static String[] naturalize(String content) {
        if (content == null) return null;
        int qIdx = content.indexOf("问：");
        int aIdx = content.indexOf("答：");
        if (qIdx < 0) return null;
        String question = (aIdx > qIdx ? content.substring(qIdx + 2, aIdx) : content.substring(qIdx + 2)).strip();
        if (question.isEmpty()) return null;
        String answer = aIdx >= 0 ? content.substring(aIdx + 2).strip() : "";
        String body = answer.isEmpty() ? question : question + "\n" + answer;
        return new String[]{question, body};
    }

    /** 逐行去「【xxx】」标签（【备注】/【图片文字】…），保留内容本身——第一原则同样适用于 image 记录。 */
    public static String naturalizeImage(String content) {
        if (content == null) return null;
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\n")) {
            String stripped = line.strip();
            while (stripped.startsWith("【")) {
                int close = stripped.indexOf("】");
                if (close < 0) break;
                stripped = stripped.substring(close + 1).strip();
            }
            if (!stripped.isEmpty()) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(stripped);
            }
        }
        return sb.toString().strip();
    }
}
