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
     * 一次理解**多张**图片（图文一体：一次投递 = 一条记录，RFC 20260815-media-event-unification）。
     * <p>
     * 与 {@link #askMulti} 的区别：这里不提问，要的是「把这几张图综合成一段理解 + 结构化字段」，
     * 供入账路径落一条主记录（summary / category / extractedText / tags）。
     * <p>
     * 默认实现只用第一张（老实现零改动即兼容）；GLM 实现走真正的多图一次识别。
     *
     * @param requests 多张图片（base64 + content type + 可选备注），至少 1 张
     * @param caption  用户随图发的那句话（可空）
     * @return 结构化图片理解（多图综合）
     */
    default ImageUnderstanding understandMulti(List<ImageRequest> requests, String caption) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("图片不能为空");
        }
        return understand(requests.get(0));
    }

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
     * 就一张图片追问，并**按本次调用**指定输出上限。
     * <p>
     * P2-learn25（2026-09-16）：输出上限原先只能全局配（{@code adai.ai.vision.max-tokens}），
     * 长书页这类「一次要抄很多字」的调用没法单独放宽——截断虽已在提示词里要求显式标注
     * 「（余下内容未能提取）」，但内容仍是丢的。此处给调用方一个按需放宽的口子。
     * <p>
     * 默认实现**忽略**覆盖值（老实现零改动即兼容）；支持能力的实现方（如
     * {@code GlmVisualAiClient}）按该值覆盖本次请求的 {@code max_tokens}。
     *
     * @param maxTokensOverride 本次调用的 max_tokens；{@code null} 或非正数 = 用实现默认/全局配置
     * @return 自然语言回答（已剥 think/answer 壳）
     */
    default String ask(ImageRequest request, String question, Integer maxTokensOverride) {
        return ask(request, question);
    }

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
