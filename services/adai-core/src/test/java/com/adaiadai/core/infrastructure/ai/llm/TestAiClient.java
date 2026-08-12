package com.adaiadai.core.infrastructure.ai.llm;

import com.adaiadai.core.kernel.context.engine.ContextPackage;
import com.adaiadai.core.kernel.ai.AiUnderstanding;
import com.adaiadai.core.kernel.ai.AiClient;

import java.util.List;

/**
 * TestAiClient — 测试用的 AI 客户端桩。
 * <p>
 * 替代已删除的 MockAiClient，为测试提供确定性返回数据。
 */
public class TestAiClient implements AiClient {

    @Override
    public AiUnderstanding understand(ContextPackage contextPackage) {
        return switch (contextPackage.scene()) {
            case "trading" -> new AiUnderstanding(
                    "交易记录: " + contextPackage.recordTitle(),
                    "交易洞察: " + contextPackage.recordTitle(),
                    null, null,
                    List.of("投资", "交易", "半导体"),
                    "neutral", "trading", true,
                    "建议跟踪该标的的后续走势。",
                    "[Test] trading response"
            );
            case "life" -> new AiUnderstanding(
                    "生活记录: " + contextPackage.recordTitle(),
                    "生活洞察: " + contextPackage.recordTitle(),
                    null, null,
                    List.of("生活", "日常", "记录"),
                    "positive", "life", false, null,
                    "[Test] life response"
            );
            default -> new AiUnderstanding(
                    "记录: " + contextPackage.recordTitle(),
                    "洞察: " + contextPackage.recordTitle(),
                    null, null,
                    List.of("测试", "记录"),
                    "neutral", "life", false, null,
                    "[Test] default response"
            );
        };
    }

    @Override
    public String generate(ContextPackage contextPackage, String systemPrompt) {
        return "[Test] 生成正文：" + contextPackage.recordTitle();
    }

    @Override
    public String recognizeIntent(String content) {
        if (content == null || content.isBlank()) return "log";
        if (content.contains("天气") || content.contains("吗") || content.contains("？")
                || content.contains("总结") || content.contains("分析")) {
            return "ask";
        }
        return "log";
    }
}
