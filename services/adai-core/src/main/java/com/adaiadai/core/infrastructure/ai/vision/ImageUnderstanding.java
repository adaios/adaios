package com.adaiadai.core.infrastructure.ai.vision;

import java.util.List;

/**
 * ImageUnderstanding — 图片结构化理解结果。
 *
 * @param summary       一句话概括（如"持仓截图：浦发银行"）
 * @param category      图片类别：trading / whiteboard / invoice / memo / photo
 * @param extractedText 图片中的文字（OCR 提取，无则空串）
 * @param tags          中文标签
 */
public record ImageUnderstanding(
        String summary,
        String category,
        String extractedText,
        List<String> tags
) {

    /** 类别 → Domain 映射（trading → trading，其余归 life）。 */
    public static String domainOf(String category) {
        return "trading".equals(category) ? "trading" : "life";
    }
}
