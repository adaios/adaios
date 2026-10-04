package com.adaiadai.core.interfaces;

import com.adaiadai.core.application.LearnCandidateAppService;
import com.adaiadai.core.application.LearnDigestAppService;
import com.adaiadai.core.application.LearnFetchService;
import com.adaiadai.core.application.LearnReviewPushService;
import com.adaiadai.core.application.LearnTranscriptionService;
import com.adaiadai.core.domain.learn.LearnCard;
import com.adaiadai.core.infrastructure.storage.InMemoryFileStorage;
import com.adaiadai.core.infrastructure.storage.LearnCardFileRepository;
import com.adaiadai.core.kernel.ai.AiClient;
import com.adaiadai.core.kernel.plugin.PluginService;
import com.adaiadai.core.kernel.record.RecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LearnCardWriteAddressingTest — 学习卡写端点的**寻址安全**（REVIEW P2-learn23，2026-10-04）。
 * <p>
 * 与 {@code LearnControllerTest}（service 全 mock）的差别：这里接的是**真仓储 + 真文件存储**
 * （{@link InMemoryFileStorage}）——能一路验到「HTTP 400 之后，只读卡文件到底动没动」。
 * <p>
 * 覆盖两条写端点：
 * <ul>
 *   <li>{@code PATCH /api/v1/learn/cards}（编辑补丁）</li>
 *   <li>{@code PATCH /api/v1/learn/cards/status}（复习状态流转）</li>
 * </ul>
 * 只读卡唯一命中 → 400 人话，且卡片文件**逐字节未改**（本条修复的核心断言）；可写卡命中 → 200 照常写。
 */
class LearnCardWriteAddressingTest {

    private final InMemoryFileStorage storage = new InMemoryFileStorage();
    private final LearnCardFileRepository repository = new LearnCardFileRepository(storage);
    /** 5 参测试装配：视觉模型/配额/记忆都不接（本测试只走编辑与状态流转，不碰 LLM）。 */
    private final LearnDigestAppService digestService = new LearnDigestAppService(
            mock(AiClient.class), repository, Runnable::run, mock(LearnFetchService.class),
            mock(LearnTranscriptionService.class));
    private final LearnCandidateAppService candidateService = mock(LearnCandidateAppService.class);
    private final LearnReviewPushService reviewPushService = mock(LearnReviewPushService.class);
    private final LearnTranscriptionService transcriptionService = mock(LearnTranscriptionService.class);
    private final PluginService pluginService = mock(PluginService.class);
    /** 门控 B 降级落盘（RFC 20260917）；本测试不触及。 */
    private final RecordRepository recordRepository = mock(RecordRepository.class);
    private final ObjectMapper om = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String FOREIGN_PATH = "learn/ai/外部主题/01-外部卡.md";
    /** 别处整理的只读卡（没有 {@code origin: product}）。 */
    private static final String FOREIGN_MD = """
            ---
            title: 外部卡
            type: ai
            created: 2026-09-06
            status: new
            ---

            ## 核心观点
            别处整理的卡，产品一个字都不该动。
            """;

    @BeforeEach
    void setUp() {
        when(pluginService.hasPlugin(anyString(), anyString())).thenReturn(true);
    }

    private MockMvc mvc() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(new LearnController(digestService, candidateService,
                        reviewPushService, transcriptionService, pluginService, recordRepository,
                        new com.adaiadai.core.application.ApiTokenGuard()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om))
                .build();
    }

    /** ① 编辑只读卡 → 400 人话，且文件未被改动（P2-learn23 核心断言）。 */
    @Test
    void editReadOnlyOnlyCard_returns400_andFileUntouched() throws Exception {
        storage.write("adai", FOREIGN_PATH, FOREIGN_MD);

        mvc().perform(patch("/api/v1/learn/cards")
                        .header("X-User-Id", "adai")
                        .param("type", "ai")
                        .param("title", "外部卡")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coreView\":\"我想改一下\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("外部只读卡")));

        assertEquals(FOREIGN_MD, storage.read("adai", FOREIGN_PATH),
                "只读卡文件一个字都不能动（本条核心断言）");
    }

    /** ② 复习流转只读卡 → 400 人话，且文件未被改动。 */
    @Test
    void changeStatusOnReadOnlyOnlyCard_returns400_andFileUntouched() throws Exception {
        storage.write("adai", FOREIGN_PATH, FOREIGN_MD);

        mvc().perform(patch("/api/v1/learn/cards/status")
                        .header("X-User-Id", "adai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ai\",\"title\":\"外部卡\",\"status\":\"review\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("外部只读卡")));

        assertEquals(FOREIGN_MD, storage.read("adai", FOREIGN_PATH),
                "只读卡文件一个字都不能动（本条核心断言）");
    }

    /** ③ 不回归：可写卡命中 → 照常 200 且真写进文件。 */
    @Test
    void editWritableCard_succeeds_andWritesThrough() throws Exception {
        repository.save("adai", new LearnCard(LearnCard.TYPE_AI, "我的卡", "web", null, null, null,
                LocalDate.of(2026, 10, 1), LearnCard.STATUS_NEW, false, null, List.of(), "旧观点",
                List.of(), List.of(), "", "我的主题"));

        mvc().perform(patch("/api/v1/learn/cards")
                        .header("X-User-Id", "adai")
                        .param("type", "ai")
                        .param("title", "我的卡")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coreView\":\"新观点\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coreView").value("新观点"))
                .andExpect(jsonPath("$.writable").value(true));

        assertEquals("新观点",
                repository.find("adai", LearnCard.TYPE_AI, "我的卡").orElseThrow().coreView(),
                "可写卡照常写得进去");
    }
}
