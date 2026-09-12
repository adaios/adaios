package com.adaiadai.core.application;

import com.adaiadai.core.domain.learn.LearnCard;
import com.adaiadai.core.domain.learn.LearnCardRepository;
import com.adaiadai.core.domain.learn.LearnException;
import com.adaiadai.core.domain.learn.LearnSource;
import com.adaiadai.core.kernel.ai.AiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LearnJsonRepairTest — 修掉「LLM 输出 JSON 夹未转义引号」这条**未修 pitfall**
 * （2026-09-12 实测发现，见 {@code ai-engineering/assets/pitfalls.md} §二）。
 * <p>
 * 症状：技术类素材里模型爱在 JSON 字符串值内直接写英文双引号，严格 Jackson 解析提前结束字符串
 * （`Unexpected character ('思')`），**同一素材重试就成**（概率性）→ 用户看到的是随机失败。
 * 对策（prompt 层 + 解析层**双做**）：prompt 要求值内用中文引号；解析层先剥离代码块围栏，
 * 严格解析失败再走宽松修复（值内引号转义 + 尾随逗号删除），修不好仍 fail-visible。
 */
class LearnJsonRepairTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiClient aiClient = mock(AiClient.class);
    private final LearnCardRepository repository = mock(LearnCardRepository.class);
    private final LearnFetchService fetchService = mock(LearnFetchService.class);
    private final LearnTranscriptionService transcriptionService = mock(LearnTranscriptionService.class);
    private final Executor directExecutor = Runnable::run;

    private LearnDigestAppService service;

    @BeforeEach
    void setUp() {
        service = new LearnDigestAppService(aiClient, repository, directExecutor, fetchService, transcriptionService);
    }

    // ── 解析层修复 ──

    @Test
    void repairJson_escapesUnescapedQuotesInsideValues() throws Exception {
        String broken = "{\"title\":\"他说\"回调一半\"就是买点\",\"type\":\"trading\"}";

        JsonNode node = MAPPER.readTree(LearnDigestAppService.repairJson(broken));

        assertEquals("他说\"回调一半\"就是买点", node.path("title").asText());
        assertEquals("trading", node.path("type").asText());
    }

    @Test
    void repairJson_keepsCorrectlyEscapedQuotesIntact() throws Exception {
        String valid = "{\"title\":\"已经转义\\\"的引号\",\"type\":\"ai\"}";

        JsonNode node = MAPPER.readTree(LearnDigestAppService.repairJson(valid));

        assertEquals("已经转义\"的引号", node.path("title").asText());
    }

    @Test
    void repairJson_removesTrailingCommas() throws Exception {
        JsonNode node = MAPPER.readTree(LearnDigestAppService.repairJson("{\"a\":[1,2,],\"b\":\"x\",}"));

        assertEquals(2, node.path("a").size());
        assertEquals("x", node.path("b").asText());
    }

    @Test
    void repairJson_preservesArraysOfStrings() throws Exception {
        String json = "{\"key_points\":[\"第一点\",\"第二点\"],\"questions\":[]}";

        JsonNode node = MAPPER.readTree(LearnDigestAppService.repairJson(json));

        assertEquals(2, node.path("key_points").size());
        assertEquals("第二点", node.path("key_points").get(1).asText());
    }

    @Test
    void repairJson_nestedObjectsStillParse() throws Exception {
        JsonNode node = MAPPER.readTree(LearnDigestAppService.repairJson(
                "{\"a\":{\"b\":\"c\"},\"d\":[{\"e\":\"f\"}]}"));

        assertEquals("c", node.path("a").path("b").asText());
        assertEquals("f", node.path("d").get(0).path("e").asText());
    }

    // ── 端到端：坏 JSON 不再让用户看到随机失败 ──

    @Test
    void digest_unescapedQuotesInLlmOutput_stillProducesCard() {
        when(aiClient.generate(any(), any())).thenReturn("""
                {"title":"他说"回调一半"就是买点","type":"trading","tags":["回调"],
                "core_view":"回调一半是买点","key_points":["02:31 一半=(high+low)/2"],
                "questions":["口径一致？"],"trade_related":true,"trade_note":"与 R66 互补"}""");

        LearnCard card = service.digest("adai", "素材正文", null, null, null, null, null);

        assertEquals("他说\"回调一半\"就是买点", card.title());
        assertEquals(LearnCard.TYPE_TRADING, card.type());
        assertTrue(card.tradeRelated());
        verify(repository).save(eq("adai"), any(LearnCard.class));
    }

    @Test
    void digest_codeFencedJson_parsed() {
        when(aiClient.generate(any(), any())).thenReturn("""
                ```json
                {"title":"围栏里的卡片","type":"ai","tags":[],"core_view":"观点",
                "key_points":["要点"],"questions":[],"trade_related":false,"trade_note":""}
                ```""");

        LearnCard card = service.digest("adai", "素材正文", null, null, null, null, null);

        assertEquals("围栏里的卡片", card.title());
        assertEquals(LearnCard.TYPE_AI, card.type());
    }

    @Test
    void digest_jsonWithPreambleText_parsed() {
        when(aiClient.generate(any(), any())).thenReturn("""
                好的，我整理好了：
                {"title":"带前缀的卡片","type":"other","tags":[],"core_view":"观点",
                "key_points":[],"questions":[],"trade_related":false,"trade_note":""}
                希望有帮助！""");

        assertEquals("带前缀的卡片",
                service.digest("adai", "素材正文", null, null, null, null, null).title());
    }

    @Test
    void repairJson_hopelesslyBroken_stillFailsVisible() {
        when(aiClient.generate(any(), any())).thenReturn("模型今天不想输出 JSON，只说了句人话");

        LearnException e = assertThrows(LearnException.class,
                () -> service.digest("adai", "素材正文", null, null, null, null, null));

        assertTrue(e.getMessage().contains("素材已留存"), "修不好也必须 fail-visible：素材留存、不产半成品");
        verify(repository).saveRawSource(eq("adai"), anyString());
        verify(repository, never()).save(anyString(), any(LearnCard.class));
    }

    @Test
    void digest_promptForbidsRawDoubleQuotesInValues() {
        when(aiClient.generate(any(), any())).thenReturn("""
                {"title":"任意","type":"ai","tags":[],"core_view":"观点",
                "key_points":[],"questions":[],"trade_related":false,"trade_note":""}""");

        service.digest("adai", "素材正文", null, null, null, null, null);

        org.mockito.ArgumentCaptor<String> prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(aiClient).generate(any(), prompt.capture());
        assertTrue(prompt.getValue().contains("禁止出现英文双引号"),
                "prompt 层要说清格式约束（与解析层宽松兜底双做）");
        assertTrue(prompt.getValue().contains("「」"), "给出可用的替代引号写法");
    }

    @Test
    void digest_urlPath_alsoSurvivesQuotedLlmOutput() {
        when(fetchService.fetchAndArchive(eq("adai"), anyString())).thenReturn(new LearnSource(
                "article", "abc", "https://example.com/post", "某文章", "站点", null,
                "正文", false, null, 0, java.util.List.of()));
        when(aiClient.generate(any(), any())).thenReturn("""
                {"title":"带"引号"的标题","type":"ai","tags":[],"core_view":"观点",
                "key_points":[],"questions":[],"trade_related":false,"trade_note":""}""");

        service.submit("adai", new LearnDigestAppService.DigestRequest(
                "https://example.com/post", null, null, null, null, null));

        assertEquals(LearnDigestAppService.STATUS_DONE, service.digestJobStatus("adai").status());
    }

    @Test
    void extractJson_handlesFencesWithoutLanguageTag() {
        when(aiClient.generate(any(), any())).thenReturn("""
                ```
                {"title":"无语言标签围栏","type":"ai","tags":[],"core_view":"观点",
                "key_points":[],"questions":[],"trade_related":false,"trade_note":""}
                ```""");

        assertEquals("无语言标签围栏",
                service.digest("adai", "素材", null, null, null, null, null).title());
    }

    @Test
    void repairJson_doesNotCorruptPlainContent() {
        String plain = "{\"title\":\"正常标题\",\"key_points\":[\"a\",\"b\"]}";

        assertEquals(plain, LearnDigestAppService.repairJson(plain),
                "正常 JSON 不应被修复逻辑改动（避免引入新失真）");
        assertFalse(LearnDigestAppService.repairJson(plain).contains("\\\\\""));
    }
}
