package com.adaiadai.core.kernel.ai;

import com.adaiadai.core.kernel.context.engine.ContextPackage;

/**
 * StreamingAiClient — AI 流式输出能力端口（REVIEW P2-用户2，2026-08-29：ai-calling-governance 批 2 聊天流式）。
 * <p>
 * 与 {@link AiClient#generate} 的同步语义对应：流式逐块产出正文增量（SSE delta），
 * 前端「边出边显示」——消除 2 核 4G 服务器 AI 生成期间「无反馈」的慢感。
 * <p>
 * 端口定义归 kernel（REVIEW #22 依赖倒置）；实现归 infrastructure/ai/llm
 * （DeepSeekAiClient 同时实现本接口——同一 bean 双接口）。
 */
public interface StreamingAiClient {

    /**
     * 流式生成正文：逐块回调 {@code onDelta}（每块为增量文本，可能为空串；调用方不得依赖块边界）。
     *
     * @param contextPackage 已注入上下文的包（prompt 作为 user 消息；聊天多轮历史走 buildChatRequestBody）
     * @param systemPrompt   自定义系统指令（null 用默认）
     * @param onDelta        增量回调（同步调用，实现方在读取 SSE 时逐块触发）
     * @return 完整生成文本（与各 delta 拼接一致）
     * @throws RuntimeException 调用失败（调用方应降级非流式重试一次，见 ai-calling-governance 降级矩阵）
     */
    String streamGenerate(ContextPackage contextPackage, String systemPrompt,
                          java.util.function.Consumer<String> onDelta);
}
