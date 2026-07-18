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

    private Intent r(String content) { return recognizer.recognize(content, false, false); }
    private Intent rSession(String content) { return recognizer.recognize(content, true, true); }

    // ── STATEMENT ──

    @Test void statem_pureDescription() { assertEquals(Intent.STATEMENT, r("今天买了立昂微")); }
    @Test void statem_lifeLog() { assertEquals(Intent.STATEMENT, r("下午喝了一杯咖啡")); }
    @Test void statem_shortLog() { assertEquals(Intent.STATEMENT, r("刚开完会，累")); }
    @Test void statem_empty() { assertEquals(Intent.STATEMENT, r("")); }
    @Test void statem_null() { assertEquals(Intent.STATEMENT, recognizer.recognize(null, false, false)); }

    // ── QUESTION (no session) ──

    @Test void question_weather() { assertEquals(Intent.QUESTION, r("今天天气如何")); }
    @Test void question_weekday() { assertEquals(Intent.QUESTION, r("今天星期几")); }
    @Test void question_withQuestionMark() { assertEquals(Intent.QUESTION, r("今天会下雨吗？")); }
    @Test void question_simpleMao() { assertEquals(Intent.QUESTION, r("今天天气好吗")); }
    @Test void question_neMood() { assertEquals(Intent.QUESTION, r("这个位置能不能买呢")); }
    @Test void question_abab() { assertEquals(Intent.QUESTION, r("买不买")); }
    @Test void question_request() { assertEquals(Intent.QUESTION, r("分析一下今天的大盘")); }
    @Test void question_niJuede() { assertEquals(Intent.QUESTION, r("你觉得立昂微怎么样")); }

    // ── Session-aware: short follow-ups in conversation should be QUESTION ──

    @Test void session_shortFollowUp_isQuestion() {
        assertEquals(Intent.QUESTION, rSession("详细说说"));
    }

    @Test void session_anotherFollowUp() {
        assertEquals(Intent.QUESTION, rSession("继续说"));
    }

    @Test void session_detailRequest() {
        assertEquals(Intent.QUESTION, rSession("展开讲讲"));
    }

    @Test void session_longInput_withoutQMark_inSession_isQuestion() {
        // In session context, long inputs are still treated as QUESTION
        assertEquals(Intent.QUESTION, rSession("我想知道更多关于这个话题的信息"));
    }

    @Test void session_shortInput_withoutPattern_inSession_isQuestion() {
        // Short input without patterns still QUESTION because of session context
        assertEquals(Intent.QUESTION, rSession("然后呢"));
    }

    // ── Session ender ──
    @Test void session_ender_stopsConversation() {
        assertTrue(recognizer.isSessionEnder("不说了"));
        assertTrue(recognizer.isSessionEnder("就这些"));
        assertTrue(recognizer.isSessionEnder("结束"));
    }

    // ── Recognize with AI fallback ──
    @Test void recognizeWithAi_logContent() {
        Intent result = recognizer.recognizeWithAi("今天买了立昂微");
        assertEquals(Intent.STATEMENT, result);
    }

    // ── Edge cases ──
    @Test void boundary_implicitRequest() {
        assertEquals(Intent.QUESTION, r("帮我看看立昂微"));
    }

    @Test void session_notActive_normalBehavior() {
        // Without session context, content that is neither a question nor a session follow-up
        assertEquals(Intent.STATEMENT, r("知道了"));
    }

    @Test void session_active_shortAck() {
        // In session, even a short acknowledgment becomes a question follow-up
        assertEquals(Intent.QUESTION, rSession("知道了"));
    }

    @Test void session_notActive_stillMatchesQ() {
        // Even without session context, explicit Q patterns still work
        assertEquals(Intent.QUESTION, r("今天天气怎么样"));
    }
}
