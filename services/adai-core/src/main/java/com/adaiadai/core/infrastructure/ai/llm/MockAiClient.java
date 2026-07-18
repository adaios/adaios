package com.adaiadai.core.infrastructure.ai.llm;

import com.adaiadai.core.kernel.context.engine.ContextPackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MockAiClient — Mock 实现的 AI 客户端。
 * <p>
 * 当 {@code adai.ai.provider=mock}（默认）时生效。
 * 返回模拟的理解结果，不真实调用任何 LLM API。
 */
@Component
@ConditionalOnProperty(name = "adai.ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockAiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(MockAiClient.class);

    @Override
    public AiUnderstanding understand(ContextPackage contextPackage) {
        log.info("[Mock AI] 处理 ContextPackage | scene={} | record={} | 预估 tokens={}",
                contextPackage.scene(), contextPackage.recordTitle(), contextPackage.estimateTokens());

        // 根据场景模拟不同的返回
        return switch (contextPackage.scene()) {
            case "trading" -> mockTrading(contextPackage);
            case "life" -> mockLife(contextPackage);
            case "research" -> mockResearch(contextPackage);
            default -> mockDefault(contextPackage);
        };
    }

    private AiUnderstanding mockTrading(ContextPackage ctx) {
        return new AiUnderstanding(
                "交易记录: " + ctx.recordTitle(),
                List.of("投资", "交易", "半导体"),
                "neutral",
                true,
                "建议跟踪该标的的后续走势，设置止盈止损提醒。",
                "[Mock] 这是交易场景的模拟回复。实际部署后由 LLM 生成。"
        );
    }

    private AiUnderstanding mockLife(ContextPackage ctx) {
        return new AiUnderstanding(
                "生活记录: " + ctx.recordTitle(),
                List.of("生活", "日常", "记录"),
                "positive",
                false,
                null,
                "[Mock] 这是生活场景的模拟回复。"
        );
    }

    private AiUnderstanding mockResearch(ContextPackage ctx) {
        return new AiUnderstanding(
                "研究笔记: " + ctx.recordTitle(),
                List.of("研究", "分析", "笔记"),
                "neutral",
                false,
                null,
                "[Mock] 这是研究场景的模拟回复。"
        );
    }

    @Override
    public String recognizeIntent(String content) {
        // Mock: 简单规则模拟
        if (content.contains("天气") || content.contains("吗") || content.contains("？")) {
            return "question";
        }
        if (content.contains("买入") || content.contains("卖出") || content.contains("开仓")) {
            return "decision";
        }
        return "log";
    }

    private AiUnderstanding mockDefault(ContextPackage ctx) {
        return new AiUnderstanding(
                "记录: " + ctx.recordTitle(),
                ctx.recordTags(),
                "neutral",
                false,
                null,
                "[Mock] 通用场景模拟回复。"
        );
    }
}
