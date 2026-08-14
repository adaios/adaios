package com.adaiadai.core.infrastructure.ai.vision;

import java.util.List;

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

    /**
     * 就一张图片追问（多模态对话，L4 图片问答）。
     * <p>
     * 把图片重新发给视觉模型 + 用户问题，返回自然语言回答
     * （区别于 {@link #understand} 的结构化 JSON 理解）。
     *
     * @param request  图片请求（base64 + content type）
     * @param question 用户对图片的追问
     * @return 自然语言回答（已剥 think/answer 壳）
     */
    String ask(ImageRequest request, String question);

    /**
     * 就多张图片追问（多图问答，Phase 1 带图 ask）。
     * <p>
     * 多张图片一次发给视觉模型 + 用户问题，返回自然语言回答
     * （区别于 {@link #ask} 的单图，模型综合多图信息连贯回答）。
     *
     * @param requests 多张图片请求（base64 + content type，至少 1 张）
     * @param question 用户对多图的追问
     * @return 自然语言回答（已剥 think/answer 壳）
     */
    String askMulti(List<ImageRequest> requests, String question);
}
