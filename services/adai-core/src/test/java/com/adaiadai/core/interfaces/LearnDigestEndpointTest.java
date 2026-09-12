package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.LearnCandidateAppService;
import com.adaiadai.core.application.LearnDigestAppService;
import com.adaiadai.core.application.LearnReviewPushService;
import com.adaiadai.core.application.LearnTranscriptionService;
import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.YearMonth;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LearnDigestEndpointTest — learn 抓取批端点（RFC 20260912 §3.7 端点草案，2026-09-12）。
 * <p>
 * 覆盖：{@code POST /learn/digest}（链接喂入受理）、{@code /digest/confirm}（费用确认）、
 * {@code /digest/quota}（额度可查）、插件门控 403、入参校验 400 人话、旧 {@code /cards} 别名兼容。
 */
class LearnDigestEndpointTest {

    private final LearnDigestAppService digestService = mock(LearnDigestAppService.class);
    private final LearnCandidateAppService candidateService = mock(LearnCandidateAppService.class);
    private final LearnReviewPushService reviewPushService = mock(LearnReviewPushService.class);
    private final LearnTranscriptionService transcriptionService = mock(LearnTranscriptionService.class);
    private final PluginService pluginService = mock(PluginService.class);

    private final ObjectMapper om = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private MockMvc mvc(String... plugins) {
        when(pluginService.hasPlugin(anyString(), anyString())).thenReturn(
                java.util.Arrays.asList(plugins).contains(PluginRegistry.PLUGIN_LEARN));
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(new LearnController(digestService, candidateService,
                        reviewPushService, transcriptionService, pluginService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    // ── 链接喂入 ──

    @Test
    void digest_withUrl_acceptedAndReturnsPending() throws Exception {
        when(digestService.submit(anyString(), any(LearnDigestAppService.DigestRequest.class)))
                .thenReturn(new LearnDigestAppService.DigestSubmitResult("pending"));

        mvc("learn").perform(post("/api/v1/learn/digest")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://www.bilibili.com/video/BV1xx411c7mD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"));

        verify(digestService).submit(eq("adai"), any(LearnDigestAppService.DigestRequest.class));
    }

    @Test
    void digest_withoutUrlOrContent_returns400HumanMessage() throws Exception {
        mvc("learn").perform(post("/api/v1/learn/digest")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("链接")));
    }

    @Test
    void digest_invalidType_returns400() throws Exception {
        mvc("learn").perform(post("/api/v1/learn/digest")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/a\",\"type\":\"hacking\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("ai/trading/other")));
    }

    @Test
    void digest_fetchFailureSurfacesHumanMessage() throws Exception {
        when(digestService.submit(anyString(), any(LearnDigestAppService.DigestRequest.class)))
                .thenThrow(new LearnException("这个平台暂时抓不了，把正文粘进来我照样能整理"));

        mvc("learn").perform(post("/api/v1/learn/digest")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://mp.weixin.qq.com/s/x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("粘进来")));
    }

    @Test
    void digest_withoutLearnPlugin_returns403() throws Exception {
        mvc().perform(post("/api/v1/learn/digest")
                        .header("X-User-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/a\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void legacyCardsEndpoint_stillAcceptsUrl() throws Exception {
        when(digestService.submit(anyString(), any(LearnDigestAppService.DigestRequest.class)))
                .thenReturn(new LearnDigestAppService.DigestSubmitResult("pending"));

        mvc("learn").perform(post("/api/v1/learn/cards")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    void legacyCardsEndpoint_blankContent_returns400() throws Exception {
        mvc("learn").perform(post("/api/v1/learn/cards")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    // ── 费用确认 ──

    @Test
    void confirm_true_returnsJobStatus() throws Exception {
        when(digestService.confirm("adai", true)).thenReturn(
                new LearnDigestAppService.DigestJobStatus("running", null, null, null,
                        LearnDigestAppService.STAGE_TRANSCRIBING, null, null));

        mvc("learn").perform(post("/api/v1/learn/digest/confirm")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("running"))
                .andExpect(jsonPath("$.stage").value("transcribing"));
    }

    @Test
    void confirm_false_cancels() throws Exception {
        when(digestService.confirm("adai", false)).thenReturn(
                new LearnDigestAppService.DigestJobStatus("cancelled", null, null, "已取消转写（没花钱）"));

        mvc("learn").perform(post("/api/v1/learn/digest/confirm")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("没花钱")));
    }

    @Test
    void confirm_missingFlag_returns400() throws Exception {
        mvc("learn").perform(post("/api/v1/learn/digest/confirm")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void confirm_noPendingTask_returns400HumanMessage() throws Exception {
        when(digestService.confirm("adai", true)).thenThrow(new LearnException("现在没有等待确认的整理任务"));

        mvc("learn").perform(post("/api/v1/learn/digest/confirm")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("没有等待确认")));
    }

    // ── 额度可查 ──

    @Test
    void quota_returnsMonthUsageAndRemaining() throws Exception {
        when(transcriptionService.quota("adai")).thenReturn(new LearnTranscriptionService.QuotaView(
                YearMonth.now().toString(), 1200, 0.096d, 36000, 34800, 0.288d, true, null));

        mvc("learn").perform(get("/api/v1/learn/digest/quota").header("X-User-Id", "adai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedSeconds").value(1200))
                .andExpect(jsonPath("$.quotaSeconds").value(36000))
                .andExpect(jsonPath("$.remainSeconds").value(34800))
                .andExpect(jsonPath("$.yuanPerHour").value(0.288))
                .andExpect(jsonPath("$.asrAvailable").value(true));
    }

    @Test
    void quota_reportsUnavailableWithReason() throws Exception {
        when(transcriptionService.quota("adai")).thenReturn(new LearnTranscriptionService.QuotaView(
                YearMonth.now().toString(), 0, 0d, 36000, 36000, 0.288d, false, "服务器上还没装转码工具（ffmpeg）"));

        mvc("learn").perform(get("/api/v1/learn/digest/quota").header("X-User-Id", "adai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asrAvailable").value(false))
                .andExpect(jsonPath("$.unavailableReason").value(org.hamcrest.Matchers.containsString("ffmpeg")));
    }

    @Test
    void quota_withoutLearnPlugin_returns403() throws Exception {
        mvc().perform(get("/api/v1/learn/digest/quota").header("X-User-Id", "bob"))
                .andExpect(status().isForbidden());
    }

    // ── 阶段态轮询（抓取批新增字段）──

    @Test
    void digestStatus_exposesStageAndCostForConfirmation() throws Exception {
        when(digestService.digestJobStatus("adai")).thenReturn(
                new LearnDigestAppService.DigestJobStatus("needs_confirmation", null, null,
                        "这个视频没有字幕，需要转写：37 分钟，预计约 0.18 元（本月剩余额度 10 小时）",
                        LearnDigestAppService.STAGE_TRANSCRIBING,
                        new LearnDigestAppService.DigestJobStatus.SourceView("bilibili", "某视频", "某UP", 2244),
                        new LearnDigestAppService.DigestJobStatus.CostView(2244, true, 0.1795d, 0, 36000, 36000)));

        mvc("learn").perform(get("/api/v1/learn/digest/status").header("X-User-Id", "adai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("needs_confirmation"))
                .andExpect(jsonPath("$.stage").value("transcribing"))
                .andExpect(jsonPath("$.source.title").value("某视频"))
                .andExpect(jsonPath("$.source.durationSeconds").value(2244))
                .andExpect(jsonPath("$.cost.estimatedYuan").value(0.1795));
    }

    @Test
    void digestStatus_legacyShapeStillWorks() throws Exception {
        when(digestService.digestJobStatus("adai")).thenReturn(
                new LearnDigestAppService.DigestJobStatus("done", "ai", "某卡片", null));

        mvc("learn").perform(get("/api/v1/learn/digest/status").header("X-User-Id", "adai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.title").value("某卡片"));
    }

    @Test
    void digestStatus_withoutLearnPlugin_returns403() throws Exception {
        mvc().perform(get("/api/v1/learn/digest/status").header("X-User-Id", "bob"))
                .andExpect(status().isForbidden());
    }

    @Test
    void controllerHasNoUnusedDependencyAssumptions() {
        // 构造冒烟：确保依赖注入链完整（新增 transcriptionService 后仍可装配）
        List.of(mvc("learn")).forEach(java.util.Objects::requireNonNull);
    }
}
