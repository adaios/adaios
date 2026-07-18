package com.adaiadai.core.kernel.context;

import com.adaiadai.core.infrastructure.ai.llm.AiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * IntentRecognizer — intent recognition with fallback chain.
 *
 * Priority:
 * 1. Session-aware: if the previous record was a QUESTION within 5 min,
 *    treat follow-up inputs (short/non-declarative) as QUESTION too.
 * 2. AI-based: call LLM for content with ambiguous patterns.
 * 3. Regex fallback: pattern matching for known question forms.
 */
@Component
public class IntentRecognizer {

    private static final Logger log = LoggerFactory.getLogger(IntentRecognizer.class);

    private final AiClient aiClient;

    public IntentRecognizer(AiClient aiClient) {
        this.aiClient = aiClient;
    }

    private static final Pattern Q_END = Pattern.compile("[？？?]|\\s*[吗呢吧啊]$");

    private static final Pattern Q_WORD = Pattern.compile(
            "如何|怎么|怎样|怎么样|哪些|什么|为什么|何时|哪里|谁|有没有|是否|能不能|"
                    + "要不要|该不该|可不可以|会不会|行不行|对不对|"
                    + "为啥|为何|何必|何不|"
                    + "几[时天日]|多[大长高重多少久远]|"
                    + "(?:星期|周|月|年)几");

    private static final Pattern Q_ABAB = Pattern.compile("(.)不\\1");

    private static final Pattern Q_REQUEST = Pattern.compile(
            "看看|帮我|帮我看看|分析[一下]?|评估[一下]?|建议[一下]?|推荐[一下]?|"
                    + "你觉得|你认为|你看|你说|"
                    + "详细说说|展开说说|展开讲讲|继续说|继续|还有呢|然后呢|讲讲|说说|"
                    + "解释[一下]?|说明[一下]?");

    private static final Pattern SESSION_ENDER = Pattern.compile(
            ".*(?:结束|不说了|不问了|没了|就这些|停|暂停|停止|谢谢|就这样).*");

    /**
     * Recognize intent with session context.
     */
    public Intent recognize(String content, boolean previousWasQuestion, boolean sessionActive) {
        if (content == null || content.isBlank()) return Intent.STATEMENT;
        String trimmed = content.trim();

        // Session-aware: if in a conversation and prev was question,
        // treat follow-ups as QUESTION (unless explicit end)
        if ((previousWasQuestion || sessionActive) && !SESSION_ENDER.matcher(trimmed).matches()) {
            if (trimmed.length() < 30) {
                return Intent.QUESTION;
            }
        }

        if (Q_END.matcher(trimmed).find()) return Intent.QUESTION;
        if (Q_WORD.matcher(trimmed).find()) return Intent.QUESTION;
        if (Q_ABAB.matcher(trimmed).find()) return Intent.QUESTION;
        if (Q_REQUEST.matcher(trimmed).find()) return Intent.QUESTION;

        return Intent.STATEMENT;
    }

    /**
     * AI-based recognition fallback.
     */
    public Intent recognizeWithAi(String content) {
        if (content == null || content.isBlank()) return Intent.STATEMENT;
        try {
            String result = aiClient.recognizeIntent(content);
            if ("question".equals(result) || "decision".equals(result)) {
                return Intent.QUESTION;
            }
            return Intent.STATEMENT;
        } catch (Exception e) {
            log.warn("AI intent recognition failed: {}", e.getMessage());
            return null;
        }
    }

    public boolean isSessionEnder(String content) {
        if (content == null || content.isBlank()) return false;
        return SESSION_ENDER.matcher(content.trim()).matches();
    }

    public enum Intent {
        STATEMENT,
        QUESTION
    }
}
