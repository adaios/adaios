package com.adaiadai.core.infrastructure.ai.vision;

/**
 * ImageRequest — 图片理解请求。
 *
 * @param base64Image  图片 base64 编码（不含 data URL 前缀，构造时拼接）
 * @param contentType  MIME 类型（如 image/png、image/jpeg）
 * @param caption      用户可选备注（可为 null）
 */
public record ImageRequest(
        String base64Image,
        String contentType,
        String caption
) {}
