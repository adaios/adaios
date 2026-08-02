package com.adaiadai.core.infrastructure.ai.vision;

/**
 * VisualAiClient — 视觉理解模型客户端抽象（端口定义）。
 * <p>
 * 镜像 {@code AiClient} 模式：AI 在 AdaiOS 架构中属基础设施层（非业务层），
 * 接口位于 {@code infrastructure/ai/vision}。
 * 文本理解走 {@code AiClient}（DeepSeek），图片理解走 {@code VisualAiClient}（GLM）。
 */
public interface VisualAiClient {

    /**
     * 理解一张图片，返回结构化结果。
     *
     * @param request 图片请求（base64 + content type + 可选用户备注）
     * @return 结构化图片理解（summary / category / extractedText / tags）
     */
    ImageUnderstanding understand(ImageRequest request);
}
