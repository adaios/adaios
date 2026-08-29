package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.QuestionAppService;
import com.adaiadai.core.kernel.record.RecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AskStreamController — 流式问答端点测试（ai-calling-governance 批 2，REVIEW P2-用户2）。
 * <p>
 * SSE 异步链路：手动 executor 收集任务 → perform 后显式执行 → asyncDispatch 断言
 * SSE 输出（data 事件序列 + [DONE]）。
 */
class AskStreamControllerTest {

    private QuestionAppService questionAppService;
    private RecordRepository recordRepository;
    private MockMvc mockMvc;
    private List<Runnable> tasks;

    @BeforeEach
    void setUp() {
        questionAppService = mock(QuestionAppService.class);
        recordRepository = mock(RecordRepository.class);
        tasks = new ArrayList<>();
        Executor manual = tasks::add;
        AskStreamController controller = new AskStreamController(
                questionAppService, recordRepository, new ObjectMapper(), manual);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** 驱动一次异步请求：perform → 执行提交到 executor 的任务 → asyncDispatch 返回 SSE 文本。 */
    private String performAndDispatch(String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/records/ask-stream")
                        .header("X-User-Id", "default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(request().asyncStarted())
                .andReturn();
        tasks.forEach(Runnable::run);
        // SSE 默认字符集 ISO-8859-1 会把中文断言变成 ?，显式 UTF-8（生产端经 byte[] 透传，编码无关）
        return mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void askStream_success_emitsTextMetaDone() throws Exception {
        doAnswer(inv -> {
            QuestionAppService.AnswerStreamHandler h = inv.getArgument(3);
            h.onDelta("你好");
            h.onDelta("，世界");
            h.onComplete(new QuestionAppService.StreamResult(
                    "rec_1", "摘要", List.of("日常"), "你好，世界", "life"));
            return null;
        }).when(questionAppService).answerStream(any(), any(), isNull(), any());

        String sse = performAndDispatch("{\"content\":\"问题\"}");

        assertTrue(sse.contains("{\"type\":\"text\",\"content\":\"你好\"}"), "增量事件逐块发出: " + sse);
        assertTrue(sse.contains("{\"type\":\"text\",\"content\":\"，世界\"}"), "第二块增量: " + sse);
        assertTrue(sse.contains("\"type\":\"meta\""), "末尾 meta 事件: " + sse);
        assertTrue(sse.contains("\"summary\":\"摘要\""), "meta 带 summary: " + sse);
        assertTrue(sse.contains("\"content\":\"你好，世界\""), "meta.content 为权威定稿: " + sse);
        assertTrue(sse.contains("data:[DONE]") || sse.contains("data:[DONE]\n") || sse.endsWith("[DONE]"),
                "以 [DONE] 收尾: " + sse);
        verify(recordRepository).save(eq("default"), any());
    }

    @Test
    void askStream_withCardId_appendsUserTurn_noRecordSave() throws Exception {
        when(questionAppService.findDuplicateResend(any(), any(), any())).thenReturn(Optional.empty());
        doAnswer(inv -> {
            QuestionAppService.AnswerStreamHandler h = inv.getArgument(3);
            h.onComplete(new QuestionAppService.StreamResult("card_1", "s", List.of(), "t", "life"));
            return null;
        }).when(questionAppService).answerStream(any(), any(), eq("card_1"), any());

        String sse = performAndDispatch("{\"content\":\"续聊问题\",\"cardId\":\"card_1\"}");

        verify(questionAppService).ensureCardWithUserTurn(eq("default"), eq("card_1"), eq("续聊问题"), any());
        verify(recordRepository, never()).save(any(), any());
        assertTrue(sse.contains("\"type\":\"meta\""), sse);
    }

    @Test
    void askStream_duplicateResend_streamsExistingAnswerWithoutAiCall() throws Exception {
        when(questionAppService.findDuplicateResend(any(), any(), any())).thenReturn(Optional.of(
                new QuestionAppService.AnswerResult("card_1", "旧摘要", List.of("日常"), "上次的回答", "life")));

        String sse = performAndDispatch("{\"content\":\"同一句\",\"cardId\":\"card_1\"}");

        assertTrue(sse.contains("{\"type\":\"text\",\"content\":\"上次的回答\"}"), "重发命中一次性推旧回答: " + sse);
        assertTrue(sse.contains("\"summary\":\"旧摘要\""), sse);
        assertTrue(sse.contains("[DONE]"), sse);
        verify(questionAppService, never()).answerStream(any(), any(), any(), any());
        verify(questionAppService, never()).ensureCardWithUserTurn(any(), any(), any(), any());
        verify(recordRepository, never()).save(any(), any());
    }

    @Test
    void askStream_error_emitsErrorEventThenDone() throws Exception {
        doAnswer(inv -> {
            QuestionAppService.AnswerStreamHandler h = inv.getArgument(3);
            h.onDelta("半截");
            h.onError("阿呆说到一半断线了，请重试");
            return null;
        }).when(questionAppService).answerStream(any(), any(), isNull(), any());

        String sse = performAndDispatch("{\"content\":\"问题\"}");

        assertTrue(sse.contains("{\"type\":\"error\",\"message\":\"阿呆说到一半断线了，请重试\"}"), sse);
        assertTrue(sse.contains("[DONE]"), "error 后仍以 [DONE] 收尾让前端干净结束: " + sse);
    }

    @Test
    void askStream_blankContent_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/records/ask-stream")
                        .header("X-User-Id", "default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest());
        assertTrue(tasks.isEmpty(), "400 不提交任何流式任务");
        assertEquals(0, tasks.size());
    }
}
