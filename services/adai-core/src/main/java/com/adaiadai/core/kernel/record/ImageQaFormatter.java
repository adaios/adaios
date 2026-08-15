package com.adaiadai.core.kernel.record;

/**
 * ImageQaFormatter — image_qa 记录展示自然化（第一原则：**永远不要让人觉得有第三视角**）。
 * <p>
 * 数据层 content 是结构化契约（freeze §2.1）：{@code 【多图问答】\n图片记录：{ids}\n问：{q}\n答：{a}}——
 * 给系统解析/搜索用；但 UI 若直接渲染，用户看到的是「问：/答：/图片记录：」标签，
 * 像有第三视角在转述，而不是「我和阿呆的自然对话」。
 * <p>
 * 展示转换：标题 = 用户问句（自然语言），正文 = 问/答两行（去标签）。图片引用由缩略图表达，
 * 不再出现文字。
 */
public final class ImageQaFormatter {

    private ImageQaFormatter() {
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
