package com.adaiadai.core.kernel.context;

import com.adaiadai.core.infrastructure.ai.llm.MockAiClient;
import com.adaiadai.core.kernel.context.IntentRecognizer.Intent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IntentRecognizerTest {

    private IntentRecognizer recognizer;

    @BeforeEach
    void setUp() {
        recognizer = new IntentRecognizer(new MockAiClient());
    }

    // MockAiClient returns "ask" for: 天气, 吗, ？, 总结, 分析
    // MockAiClient returns "log" for everything else

    @Test void recognizeWithAi_question_weather() {
        assertEquals(Intent.QUESTION, recognizer.recognizeWithAi("今天天气如何"));
    }

    @Test void recognizeWithAi_question_withMa() {
        assertEquals(Intent.QUESTION, recognizer.recognizeWithAi("今天会下雨吗"));
    }

    @Test void recognizeWithAi_question_summary() {
        assertEquals(Intent.QUESTION, recognizer.recognizeWithAi("总结下今天的项目问题"));
    }

    @Test void recognizeWithAi_question_analysis() {
        assertEquals(Intent.QUESTION, recognizer.recognizeWithAi("分析下今天的大盘"));
    }

    @Test void recognizeWithAi_log_description() {
        assertEquals(Intent.STATEMENT, recognizer.recognizeWithAi("今天买了立昂微"));
    }

    @Test void recognizeWithAi_log_life() {
        assertEquals(Intent.STATEMENT, recognizer.recognizeWithAi("下午喝了一杯咖啡"));
    }

    @Test void recognizeWithAi_empty() {
        assertEquals(Intent.STATEMENT, recognizer.recognizeWithAi(""));
    }

    @Test void recognizeWithAi_null() {
        assertEquals(Intent.STATEMENT, recognizer.recognizeWithAi(null));
    }
}
