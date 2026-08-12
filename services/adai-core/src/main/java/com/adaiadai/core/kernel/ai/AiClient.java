package com.adaiadai.core.kernel.ai;

import com.adaiadai.core.kernel.context.engine.ContextPackage;

/**
 * AiClient — AI 模型客户端抽象（端口定义）。
 * <p>
 * 接收 Context Engine 输出的 ContextPackage，返回 AI 理解结果。
 * 同时提供轻量意图识别兜底能力。
 * <p>
 * 端口定义归 kernel（REVIEW #22 依赖倒置：kernel 只依赖接口，不反向依赖基础设施）；
 * 具体实现（DeepSeekAiClient 等）与装饰器归 infrastructure/ai/llm。
 * AI 在 AdaiOS 架构中仍属基础设施层，只是「端口在 kernel、实现在 infra」。
 */
public interface AiClient {

    /**
     * 理解用户的上下文包（摘要语义：输出 summary/tags/insight 等结构化理解）。
     */
    AiUnderstanding understand(ContextPackage contextPackage);

    /**
     * 生成正文（生成语义：无 JSON 摘要指令，按给定 systemPrompt 产出完整文本）。
     * <p>
     * 用于"生成型"任务（如交易复盘正文），与 understand 的"分析摘要"语义分离——
     * understand 的默认 system 会引导模型输出 3-5 词 summary，压制结构化正文模板。
     *
     * @param contextPackage 已注入上下文的包（prompt 作为 user 消息）
     * @param systemPrompt   自定义系统指令（引导输出正文格式），null 时用默认
     * @return 生成的正文文本
     */
    String generate(ContextPackage contextPackage, String systemPrompt);

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
