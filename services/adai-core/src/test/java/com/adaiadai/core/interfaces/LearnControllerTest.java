package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.LearnCandidateAppService;
import com.adaiadai.core.application.LearnDigestAppService;
import com.adaiadai.core.domain.learn.LearnCard;
import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.kernel.plugin.PluginRegistry;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LearnControllerTest — learn 端点（RFC 20260829）。
 * <p>
 * 覆盖：插件门控 403（喂入/列表/树）、喂入成功、喂入空内容 400、喂入失败 400 人话、
 * 列表 type 筛选、树、非法 type 400、V2 复习流转/编辑、V2 批 3 反哺候选。
 */
class LearnControllerTest {

    private final LearnDigestAppService digestService = mock(LearnDigestAppService.class);
    private final LearnCandidateAppService candidateService = mock(LearnCandidateAppService.class);
    private final com.adaiadai.core.application.LearnReviewPushService reviewPushService =
            mock(com.adaiadai.core.application.LearnReviewPushService.class);
    private final com.adaiadai.core.application.LearnTranscriptionService transcriptionService =
            mock(com.adaiadai.core.application.LearnTranscriptionService.class);
    private final PluginService pluginService = mock(PluginService.class);
    /** 门控 B 降级落盘（RFC 20260917）。 */
    private final com.adaiadai.core.kernel.record.RecordRepository recordRepository =
            mock(com.adaiadai.core.kernel.record.RecordRepository.class);
    private final ObjectMapper om = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private MockMvc mvc(String... plugins) {
        when(pluginService.hasPlugin(anyString(), anyString())).thenReturn(
                java.util.Arrays.asList(plugins).contains(PluginRegistry.PLUGIN_LEARN));
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(new LearnController(digestService, candidateService, reviewPushService, transcriptionService, pluginService, recordRepository, new com.adaiadai.core.application.ApiTokenGuard()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    private LearnCard sampleCard() {
        return new LearnCard(LearnCard.TYPE_TRADING, "回调一半的判定", "bilibili",
                "某UP", "https://b23.tv/x", "2026-05-05",
                LocalDate.of(2026, 9, 6), LearnCard.STATUS_NEW, true, "与 R66 互补",
                List.of("止损", "回调"), "回调一半是买点",
                List.of("02:31 回调一半=(high+low)/2"), List.of("口径一致？"), "");
    }

    /**
     * 门控 B（RFC 20260917）：无 learn 插件时**不再 403**——「接收」是基础能力，落成一条普通记录。
     * <p>
     * 2026-09-17 前的行为是 403「learn 插件未启用」→ 新用户分享第一步就断（RFC §一 F1）。
     */
    @Test
    void digest_withoutLearnPlugin_recordsInsteadOfForbidden() throws Exception {
        mvc().perform(post("/api/v1/learn/cards")
                        .header("X-User-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"字幕内容\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("recorded"))
                .andExpect(jsonPath("$.recordId").exists())
                .andExpect(jsonPath("$.message").exists());

        // V2 验收：接收路径**不得**触发任何消化（不抓取 / 不转写 / 不调 LLM）——零费用
        verify(digestService, never()).submit(anyString(), any(LearnDigestAppService.DigestRequest.class));
        // 素材确实落成了一条记录（接收不丢）
        verify(recordRepository).save(eq("bob"), any(com.adaiadai.core.kernel.record.ContentRecord.class));
    }

    /** 门控 B：**校验优先于门控**——无插件时参数非法仍 400，不得静默记一条空记录。 */
    @Test
    void digest_withoutLearnPlugin_blankContent_stillReturns400() throws Exception {
        mvc().perform(post("/api/v1/learn/cards")
                        .header("X-User-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        verify(recordRepository, never()).save(anyString(), any(com.adaiadai.core.kernel.record.ContentRecord.class));
    }

    // ── RFC 20260917 §五 2b：产物反馈端点 ──

    @Test
    void feedback_returnsRecordedWithCanRepage() throws Exception {
        when(digestService.feedback(eq("adai"), eq("ai"), eq("某卡"), eq("太啰嗦了")))
                .thenReturn(new LearnDigestAppService.LearnFeedbackResult(
                        "recorded", "记住了，以后我按这个来。", true));

        mvc("learn").perform(post("/api/v1/learn/cards/feedback")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ai\",\"title\":\"某卡\",\"feedback\":\"太啰嗦了\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("recorded"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.canRepage").value(true));
    }

    /**
     * 反馈属于「整理能力」，**不适用门控 B 的接收降级** → 无 learn 插件仍 403。
     * （与 `/digest` 的区别正是本 RFC 的核心：接收免费，整理受控。）
     */
    @Test
    void feedback_withoutLearnPlugin_returns403() throws Exception {
        mvc().perform(post("/api/v1/learn/cards/feedback")
                        .header("X-User-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ai\",\"title\":\"某卡\",\"feedback\":\"太啰嗦了\"}"))
                .andExpect(status().isForbidden());
    }

    /** 空反馈 → 400（bean validation 挡在服务层之前，不落空偏好）。 */
    @Test
    void feedback_blank_returns400() throws Exception {
        mvc("learn").perform(post("/api/v1/learn/cards/feedback")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ai\",\"title\":\"某卡\",\"feedback\":\"   \"}"))
                .andExpect(status().isBadRequest());
        verify(digestService, never()).feedback(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void digest_success_returnsRunning() throws Exception {
        when(digestService.submit(anyString(), any(LearnDigestAppService.DigestRequest.class)))
                .thenReturn(new LearnDigestAppService.DigestSubmitResult("running"));
        mvc("learn").perform(post("/api/v1/learn/cards")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"字幕内容\",\"platform\":\"bilibili\",\"author\":\"某UP\",\"url\":\"https://b23.tv/x\",\"published\":\"2026-05-05\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("running"));
    }

    @Test
    void digest_inflight_returnsRunning() throws Exception {
        when(digestService.submit(anyString(), any(LearnDigestAppService.DigestRequest.class)))
                .thenReturn(new LearnDigestAppService.DigestSubmitResult("running"));
        mvc("learn").perform(post("/api/v1/learn/cards")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"字幕内容\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("running"));
    }

    @Test
    void digest_blankContent_returns400() throws Exception {
        mvc("learn").perform(post("/api/v1/learn/cards")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void digest_aiFailure_returns400WithHumanMessage() throws Exception {
        when(digestService.submit(anyString(), any(LearnDigestAppService.DigestRequest.class)))
                .thenThrow(new LearnException("AI 消化失败，原始素材已留存（learn/_raw/），可稍后重试"));
        mvc("learn").perform(post("/api/v1/learn/cards")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"字幕内容\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("素材已留存")));
    }

    @Test
    void digestStatus_withoutLearnPlugin_returns403() throws Exception {
        mvc().perform(get("/api/v1/learn/digest/status").header("X-User-Id", "bob"))
                .andExpect(status().isForbidden());
    }

    @Test
    void digestStatus_success_returnsJobState() throws Exception {
        when(digestService.digestJobStatus("adai"))
                .thenReturn(new LearnDigestAppService.DigestJobStatus("running", null, null, null));
        mvc("learn").perform(get("/api/v1/learn/digest/status")
                        .header("X-User-Id", "adai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("running"));
    }

    @Test
    void digestStatus_done_returnsCardLocator() throws Exception {
        when(digestService.digestJobStatus("adai"))
                .thenReturn(new LearnDigestAppService.DigestJobStatus("done", "ai", "RAG 与 Agent", null));
        mvc("learn").perform(get("/api/v1/learn/digest/status")
                        .header("X-User-Id", "adai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.type").value("ai"))
                .andExpect(jsonPath("$.title").value("RAG 与 Agent"));
    }

    @Test
    void digest_invalidType_returns400() throws Exception {
        mvc("learn").perform(post("/api/v1/learn/cards")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"字幕内容\",\"type\":\"hacking\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void card_withoutLearnPlugin_returns403() throws Exception {
        mvc().perform(get("/api/v1/learn/card")
                        .header("X-User-Id", "bob")
                        .param("type", "ai").param("title", "回调一半的判定"))
                .andExpect(status().isForbidden());
    }

    @Test
    void card_success_returnsCardDetail() throws Exception {
        when(digestService.detail(anyString(), anyString(), anyString())).thenReturn(sampleCard());
        mvc("learn").perform(get("/api/v1/learn/card")
                        .header("X-User-Id", "adai")
                        .param("type", "trading").param("title", "回调一半的判定"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("回调一半的判定"))
                .andExpect(jsonPath("$.type").value("trading"))
                .andExpect(jsonPath("$.keyPoints[0]").value("02:31 回调一半=(high+low)/2"));
    }

    @Test
    void card_notExists_returns400WithHumanMessage() throws Exception {
        when(digestService.detail(anyString(), anyString(), anyString()))
                .thenThrow(new LearnException("卡片不存在：trading/没有的卡片"));
        mvc("learn").perform(get("/api/v1/learn/card")
                        .header("X-User-Id", "adai")
                        .param("type", "trading").param("title", "没有的卡片"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("卡片不存在")));
    }

    @Test
    void card_invalidType_returns400() throws Exception {
        mvc("learn").perform(get("/api/v1/learn/card")
                        .header("X-User-Id", "adai")
                        .param("type", "bogus").param("title", "x"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_withoutLearnPlugin_returns403() throws Exception {
        mvc().perform(get("/api/v1/learn/cards").header("X-User-Id", "bob"))
                .andExpect(status().isForbidden());
    }

    @Test
    void list_success_returnsCards() throws Exception {
        when(digestService.list(anyString(), anyString())).thenReturn(List.of(sampleCard()));
        mvc("learn").perform(get("/api/v1/learn/cards")
                        .header("X-User-Id", "adai")
                        .param("type", "trading"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("回调一半的判定"));
    }

    @Test
    void list_invalidType_returns400() throws Exception {
        mvc("learn").perform(get("/api/v1/learn/cards")
                        .header("X-User-Id", "adai")
                        .param("type", "bogus"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tree_withoutLearnPlugin_returns403() throws Exception {
        mvc().perform(get("/api/v1/learn/tree").header("X-User-Id", "bob"))
                .andExpect(status().isForbidden());
    }

    @Test
    void tree_success_returnsGroupedCards() throws Exception {
        when(digestService.tree(anyString())).thenReturn(Map.of("ai", List.of(sampleCard())));
        mvc("learn").perform(get("/api/v1/learn/tree").header("X-User-Id", "adai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ai[0].title").value("回调一半的判定"));
    }

    // ── V2 复习状态流转 ──

    @Test
    void changeStatus_withoutLearnPlugin_returns403() throws Exception {
        mvc().perform(patch("/api/v1/learn/cards/status")
                        .header("X-User-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"trading\",\"title\":\"回调一半的判定\",\"status\":\"review\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void changeStatus_success_returnsUpdatedCard() throws Exception {
        LearnCard updated = new LearnCard(LearnCard.TYPE_TRADING, "回调一半的判定", "bilibili",
                "某UP", "https://b23.tv/x", "2026-05-05",
                LocalDate.of(2026, 9, 6), LearnCard.STATUS_REVIEW, true, "与 R66 互补",
                List.of("止损", "回调"), "回调一半是买点",
                List.of("02:31 回调一半=(high+low)/2"), List.of("口径一致？"), "");
        when(digestService.changeStatus(anyString(), anyString(), anyString(), anyString())).thenReturn(updated);
        mvc("learn").perform(patch("/api/v1/learn/cards/status")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"trading\",\"title\":\"回调一半的判定\",\"status\":\"review\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("review"))
                .andExpect(jsonPath("$.title").value("回调一半的判定"));
    }

    @Test
    void changeStatus_blankField_returns400() throws Exception {
        mvc("learn").perform(patch("/api/v1/learn/cards/status")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"trading\",\"title\":\"回调一半的判定\",\"status\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changeStatus_notFound_returns400HumanMessage() throws Exception {
        when(digestService.changeStatus(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new LearnException("卡片不存在：trading/没有的卡片"));
        mvc("learn").perform(patch("/api/v1/learn/cards/status")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"trading\",\"title\":\"没有的卡片\",\"status\":\"review\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("卡片不存在")));
    }

    // ── V2 编辑（对话流让阿呆改的后端支撑）──

    @Test
    void edit_withoutLearnPlugin_returns403() throws Exception {
        mvc().perform(patch("/api/v1/learn/cards")
                        .header("X-User-Id", "bob")
                        .param("type", "trading").param("title", "回调一半的判定")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"retell\":\"复述：回调一半是几何口径\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void edit_success_returnsUpdatedCard() throws Exception {
        LearnCard updated = new LearnCard(LearnCard.TYPE_TRADING, "回调一半的判定", "bilibili",
                "某UP", "https://b23.tv/x", "2026-05-05",
                LocalDate.of(2026, 9, 6), LearnCard.STATUS_NEW, true, "与 R66 互补",
                List.of("止损", "回调"), "回调一半是买点",
                List.of("02:31 回调一半=(high+low)/2"), List.of("口径一致？"), "复述内容");
        when(digestService.edit(anyString(), anyString(), anyString(), any())).thenReturn(updated);
        mvc("learn").perform(patch("/api/v1/learn/cards")
                        .header("X-User-Id", "adai")
                        .param("type", "trading").param("title", "回调一半的判定")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"retell\":\"复述内容\",\"coreView\":\"回调一半是买点\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retell").value("复述内容"))
                .andExpect(jsonPath("$.title").value("回调一半的判定"));
    }

    @Test
    void edit_invalidType_returns400() throws Exception {
        mvc("learn").perform(patch("/api/v1/learn/cards")
                        .header("X-User-Id", "adai")
                        .param("type", "bogus").param("title", "回调一半的判定")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"retell\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void edit_notFound_returns400HumanMessage() throws Exception {
        when(digestService.edit(anyString(), anyString(), anyString(), any()))
                .thenThrow(new LearnException("卡片不存在：trading/没有的卡片"));
        mvc("learn").perform(patch("/api/v1/learn/cards")
                        .header("X-User-Id", "adai")
                        .param("type", "trading").param("title", "没有的卡片")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"retell\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("卡片不存在")));
    }

    // ── V2 批 3：trading 反哺候选 ──

    @Test
    void candidate_withoutLearnPlugin_returns403() throws Exception {
        mvc().perform(post("/api/v1/learn/cards/candidate")
                        .header("X-User-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"trading\",\"title\":\"回调一半的判定\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void candidate_success_returnsCandidate() throws Exception {
        com.adaiadai.core.domain.learn.LearnTradingCandidate c =
                new com.adaiadai.core.domain.learn.LearnTradingCandidate(
                        "回调一半的判定", "learn/trading/2026-09-06_回调一半的判定", "trading",
                        LocalDate.of(2026, 9, 7), "回调一半才是买点",
                        List.of("02:31 回调一半=(high+low)/2"), "与 R66 互补", List.of("止损"));
        when(candidateService.createFromCard(anyString(), anyString(), anyString())).thenReturn(c);
        mvc("learn").perform(post("/api/v1/learn/cards/candidate")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"trading\",\"title\":\"回调一半的判定\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("回调一半的判定"))
                .andExpect(jsonPath("$.learnCardId").value("learn/trading/2026-09-06_回调一半的判定"))
                .andExpect(jsonPath("$.sourceType").value("trading"));
    }

    @Test
    void candidate_blankBody_returns400() throws Exception {
        mvc("learn").perform(post("/api/v1/learn/cards/candidate")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void candidate_nonTradingOrNotRelated_returns400HumanMessage() throws Exception {
        when(candidateService.createFromCard(anyString(), anyString(), anyString()))
                .thenThrow(new LearnException("只有交易类（trading）卡片能反哺成规则候选"));
        mvc("learn").perform(post("/api/v1/learn/cards/candidate")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ai\",\"title\":\"RAG 与 Agent\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("trading")));
    }

    @Test
    void candidates_list_returnsArray() throws Exception {
        com.adaiadai.core.domain.learn.LearnTradingCandidate c =
                new com.adaiadai.core.domain.learn.LearnTradingCandidate(
                        "回调一半的判定", "learn/trading/2026-09-06_回调一半的判定", "trading",
                        LocalDate.of(2026, 9, 7), "回调一半才是买点",
                        List.of(), "与 R66 互补", List.of());
        when(candidateService.listCandidates(anyString())).thenReturn(List.of(c));
        mvc("learn").perform(get("/api/v1/learn/cards/candidates")
                        .header("X-User-Id", "adai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("回调一半的判定"));
    }

    @Test
    void candidates_list_withoutPlugin_returns403() throws Exception {
        mvc().perform(get("/api/v1/learn/cards/candidates")
                        .header("X-User-Id", "bob"))
                .andExpect(status().isForbidden());
    }

    @Test
    void candidate_delete_returnsOk() throws Exception {
        mvc("learn").perform(delete("/api/v1/learn/cards/candidates")
                        .header("X-User-Id", "adai")
                        .param("title", "回调一半的判定"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
    }

    // ── 图片喂入（2026-09-12 完整升级批）──

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder imageUpload() {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/api/v1/learn/digest/image");
    }

    @Test
    void digestImage_withoutPlugin_returns403() throws Exception {
        mvc().perform(imageUpload()
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "files", "p.png", "image/png", new byte[]{1, 2}))
                        .header("X-User-Id", "bob"))
                .andExpect(status().isForbidden());
    }

    @Test
    void digestImage_nonImageContentType_returns400HumanMessage() throws Exception {
        mvc("learn").perform(imageUpload()
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "files", "notes.txt", "text/plain", "不是图".getBytes()))
                        .header("X-User-Id", "adai"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("只能发图片（png/jpg/webp）"));
    }

    @Test
    void digestImage_success_returnsPending() throws Exception {
        when(digestService.submitImages(anyString(), any(), any(), any()))
                .thenReturn(new LearnDigestAppService.DigestSubmitResult("pending"));

        mvc("learn").perform(imageUpload()
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "files", "p.png", "image/png", new byte[]{1, 2, 3}))
                        .header("X-User-Id", "adai")
                        .param("type", "ai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"));
    }

    // ── 全文 / 找卡片（2026-09-12 完整升级批）──

    @Test
    void content_returnsRawMarkdown() throws Exception {
        when(digestService.content("adai", "ai", "Harness 到底是什么？"))
                .thenReturn(new LearnDigestAppService.CardContent("ai", "Harness 到底是什么？",
                        "harness", false, "---\ntitle: x\n---\n\n## 内容脉络\n- 第一条",
                        "## 内容脉络\n- 第一条"));

        mvc("learn").perform(get("/api/v1/learn/content")
                        .header("X-User-Id", "adai")
                        .param("type", "ai")
                        .param("title", "Harness 到底是什么？"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topic").value("harness"))
                .andExpect(jsonPath("$.writable").value(false))
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("内容脉络")))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("title: x"))));
    }

    /** 卡片流（2026-09-15）：全文接口把页序列一并给出；老卡为空数组（前端据此降级）。 */
    @Test
    void content_exposesPagesArray() throws Exception {
        when(digestService.content("adai", "ai", "带页的卡"))
                .thenReturn(new LearnDigestAppService.CardContent("ai", "带页的卡", "harness", true,
                        "---\ntitle: 带页的卡\n---\n\n## 核心观点\n一句话\n",
                        "## 核心观点\n一句话\n",
                        java.util.List.of(new com.adaiadai.core.domain.learn.LearnPage(
                                com.adaiadai.core.domain.learn.LearnPage.KIND_TABLE, "对照表", "一句话结论",
                                java.util.List.of(),
                                new com.adaiadai.core.domain.learn.LearnPage.Table(
                                        java.util.List.of("列A", "列B"),
                                        java.util.List.of(java.util.List.of("a", "b"))),
                                java.util.List.of(), null, null, java.util.List.of()))));

        mvc("learn").perform(get("/api/v1/learn/content")
                        .header("X-User-Id", "adai")
                        .param("type", "ai")
                        .param("title", "带页的卡"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pages[0].kind").value("table"))
                .andExpect(jsonPath("$.pages[0].title").value("对照表"))
                .andExpect(jsonPath("$.pages[0].table.headers[0]").value("列A"))
                .andExpect(jsonPath("$.pages[0].table.rows[0][1]").value("b"));
    }

    /** 老卡（没有页段）→ pages 空数组，不报错（向后兼容的硬要求）。 */
    @Test
    void content_legacyCard_pagesEmpty() throws Exception {
        when(digestService.content("adai", "ai", "老卡"))
                .thenReturn(new LearnDigestAppService.CardContent("ai", "老卡", "未归类", true,
                        "---\ntitle: 老卡\n---\n\n## 核心观点\nx\n", "## 核心观点\nx\n"));

        mvc("learn").perform(get("/api/v1/learn/content")
                        .header("X-User-Id", "adai")
                        .param("type", "ai")
                        .param("title", "老卡"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pages").isArray())
                .andExpect(jsonPath("$.pages").isEmpty());
    }

    @Test
    void content_invalidType_returns400() throws Exception {        mvc("learn").perform(get("/api/v1/learn/content")
                        .header("X-User-Id", "adai")
                        .param("type", "hacking")
                        .param("title", "x"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void content_withoutPlugin_returns403() throws Exception {
        mvc().perform(get("/api/v1/learn/content")
                        .header("X-User-Id", "bob")
                        .param("type", "ai")
                        .param("title", "x"))
                .andExpect(status().isForbidden());
    }

    @Test
    void find_returnsRankedCards() throws Exception {
        LearnCard card = new LearnCard(LearnCard.TYPE_AI, "量价关系入门", "web", null, null, null,
                LocalDate.of(2026, 9, 12), LearnCard.STATUS_NEW, false, null, List.of(), "观点",
                List.of(), List.of(), "", "量价关系");
        when(digestService.find("adai", "量价", 5)).thenReturn(List.of(card));

        mvc("learn").perform(get("/api/v1/learn/find")
                        .header("X-User-Id", "adai")
                        .param("q", "量价")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("量价关系入门"))
                .andExpect(jsonPath("$[0].topic").value("量价关系"));
    }

    @Test
    void migrate_returnsMigrationSummary() throws Exception {
        when(digestService.migrateLegacy("adai")).thenReturn(List.of(
                new com.adaiadai.core.domain.learn.LearnCardRepository.MigrationItem(
                        "ai", "learn/ai/2026-09-12_老卡.md", "learn/ai/未归类/01-老卡.md")));

        mvc("learn").perform(post("/api/v1/learn/migrate").header("X-User-Id", "adai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.migrated").value(1))
                .andExpect(jsonPath("$.items[0].to").value("learn/ai/未归类/01-老卡.md"));
    }

    @Test
    void migrate_withoutPlugin_returns403() throws Exception {
        mvc().perform(post("/api/v1/learn/migrate").header("X-User-Id", "bob"))
                .andExpect(status().isForbidden());
    }

    // ── 缺口批（2026-09-13）：删卡 + 改主题 ──

    @Test
    void deleteCard_returnsCascadeSummary() throws Exception {
        when(digestService.deleteCard("adai", "ai", "要删的卡")).thenReturn("learn/ai/量价关系/01-要删的卡.md");
        when(candidateService.deleteByLearnCardId("adai", "learn/ai/量价关系/01-要删的卡.md"))
                .thenReturn(List.of("反哺候选一"));

        mvc("learn").perform(delete("/api/v1/learn/cards")
                        .header("X-User-Id", "adai")
                        .param("type", "ai")
                        .param("title", "要删的卡"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.cascadedCandidates[0]").value("反哺候选一"));
    }

    @Test
    void deleteCard_invalidType_returns400() throws Exception {
        mvc("learn").perform(delete("/api/v1/learn/cards")
                        .header("X-User-Id", "adai")
                        .param("type", "hacking")
                        .param("title", "x"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteCard_withoutPlugin_returns403() throws Exception {
        mvc().perform(delete("/api/v1/learn/cards")
                        .header("X-User-Id", "bob")
                        .param("type", "ai")
                        .param("title", "x"))
                .andExpect(status().isForbidden());
    }

    @Test
    void moveTopic_returnsUpdatedCard() throws Exception {
        when(digestService.moveToTopic("adai", "ai", "待归类", "量价关系"))
                .thenReturn(new LearnCard(LearnCard.TYPE_AI, "待归类", "bilibili", "UP", null, null,
                        LocalDate.of(2026, 9, 13), LearnCard.STATUS_NEW, false, null, List.of(), "观点",
                        List.of(), List.of(), "", "量价关系"));

        mvc("learn").perform(patch("/api/v1/learn/cards/topic")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ai\",\"title\":\"待归类\",\"topic\":\"量价关系\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topic").value("量价关系"));
    }

    @Test
    void moveTopic_blankTopic_returns400() throws Exception {
        mvc("learn").perform(patch("/api/v1/learn/cards/topic")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ai\",\"title\":\"x\",\"topic\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void repages_returnsRepagedTrue() throws Exception {
        when(digestService.repages("adai", "ai", "老卡"))
                .thenReturn(new LearnCard(LearnCard.TYPE_AI, "老卡", "bilibili", "UP", null, null,
                        LocalDate.of(2026, 9, 13), LearnCard.STATUS_NEW, false, null, List.of(), "观点",
                        List.of(), List.of(), "", "harness"));

        mvc("learn").perform(post("/api/v1/learn/cards/repages")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ai\",\"title\":\"老卡\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repaged").value(true))
                .andExpect(jsonPath("$.title").value("老卡"));
    }

    @Test
    void repages_missingTitle_returns400() throws Exception {
        mvc("learn").perform(post("/api/v1/learn/cards/repages")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ai\",\"title\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    /** 业务人话要原样透出（没素材 / 只读卡都走这条），不能变成 500。 */
    @Test
    void repages_businessFailure_returns400WithPlainWords() throws Exception {
        when(digestService.repages("adai", "ai", "老卡"))
                .thenThrow(new com.adaiadai.core.domain.learn.LearnException(
                        "这张卡没留下原始素材（learn/_raw/），排不了页——重新整理一次这个来源即可"));

        mvc("learn").perform(post("/api/v1/learn/cards/repages")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ai\",\"title\":\"老卡\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("没留下原始素材")));
    }

    /** learn 插件未启用 → 403（与其余 learn 端点同门控）。 */
    @Test
    void repages_pluginDisabled_returns403() throws Exception {
        mvc().perform(post("/api/v1/learn/cards/repages")
                        .header("X-User-Id", "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ai\",\"title\":\"老卡\"}"))
                .andExpect(status().isForbidden());
    }
}
