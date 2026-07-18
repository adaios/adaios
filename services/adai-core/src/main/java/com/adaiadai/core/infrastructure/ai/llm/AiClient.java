package com.adaiadai.core.infrastructure.ai.llm;

import com.adaiadai.core.kernel.context.engine.ContextPackage;

/**
 * AiClient — AI 模型客户端抽象（端口定义）。
 * <p>
 * 接收 Context Engine 输出的 ContextPackage，返回 AI 理解结果。
 * 同时提供轻量意图识别兜底能力。
 * <p>
 * AI 在 AdaiOS 架构中属于基础设施层（非业务层），
 * 因此此接口位于 infrastructure/ai/llm，而非 kernel 或 domain。
 */
public interface AiClient {

    /**
     * 理解用户的上下文包。
     */
    AiUnderstanding understand(ContextPackage contextPackage);

    /**
     * 轻量意图识别兜底。
     * <p>
     * 当 {@link com.adaiadai.core.kernel.context.IntentRecognizer} 规则无法确定意图时，
     * 交给 AI 做一次轻量判断。只需返回 log / question / decision 之一。
     *
     * @param content 用户输入原文
     * @return log / question / decision
     */
    String recognizeIntent(String content);
}
